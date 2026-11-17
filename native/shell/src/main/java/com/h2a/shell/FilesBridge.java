package com.h2a.shell;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * ملفات التطبيق: استقبال الملفات (فتح بواسطة / مشاركة إلى التطبيق)، حفظ في مجلد يختاره المستخدم (SAF)، وطباعة الصفحة.
 * الملفات الواردة بتتنسخ لمجلد مؤقت وبتتقدّم للصفحة برابط https://h2a.local/f/... تقدر تعمله fetch.
 */
public final class FilesBridge {
    static final int REQ_TREE = 43;
    static final String HOST = "h2a.local";
    private static final long MAX_FILE = 200L * 1024 * 1024;

    private final Activity act;
    private final WebView web;
    private final boolean folderMode;
    private final Object lock = new Object();
    private JSONArray last = new JSONArray();       // آخر دفعة وصلت
    private JSONArray untaken = new JSONArray();    // لسه الصفحة ما سحبتهاش
    private final Map<String, File> served = new HashMap<String, File>();
    private final Map<String, String> servedType = new HashMap<String, String>();
    private File pendingFile; private String pendingName, pendingMime;

    FilesBridge(Activity act, WebView web, boolean folderMode) { this.act = act; this.web = web; this.folderMode = folderMode; }

    /** JS بيتحقن في الصفحة: window.H2A فيه files/onFiles/chooseFolder/print. */
    static final String POLYFILL =
        "(function(){if(window.__h2aF||!window.H2AFiles)return;window.__h2aF=1;"
        + "var hs=[],all=[];"
        + "function mk(d){if(d.textContent!==undefined)d.text=function(){return Promise.resolve(d.textContent)};if(d.url){d.blob=function(){return fetch(d.url).then(function(r){return r.blob()})};d.arrayBuffer=function(){return fetch(d.url).then(function(r){return r.arrayBuffer()})};d.text=function(){return fetch(d.url).then(function(r){return r.text()})}}return d}"
        + "window.__h2aPoll=function(force){var q=[];try{q=JSON.parse(force?H2AFiles.current():H2AFiles.take())}catch(e){}if(!q.length)return;q=q.map(mk);all=force?q:all.concat(q);"
        + "hs.forEach(function(h){try{h(q)}catch(e){}});try{window.dispatchEvent(new CustomEvent('h2afiles',{detail:q}))}catch(e){}};"
        + "window.H2A=Object.assign(window.H2A||{},{files:function(){return all.slice()},onFiles:function(fn){hs.push(fn);if(all.length)try{fn(all.slice())}catch(e){}},"
        + "chooseFolder:function(){return H2AFiles.chooseFolder()},hasFolder:function(){return H2AFiles.hasFolder()},print:function(){H2AFiles.print()}});"
        + "window.print=function(){H2AFiles.print()};"
        + "})();";

    static boolean isIncoming(Intent it) {
        if (it == null || it.getAction() == null) return false;
        String a = it.getAction();
        return a.equals(Intent.ACTION_VIEW) || a.equals(Intent.ACTION_SEND) || a.equals(Intent.ACTION_SEND_MULTIPLE);
    }

    /** بينسخ ملفات الـ intent ويجهّز وصفها للصفحة. بيرجّع true لو فيه حاجة اتضافت. */
    boolean ingest(Intent it) {
        if (!isIncoming(it)) return false;
        File base = new File(act.getCacheDir(), "incoming");
        deleteTree(base);
        base.mkdirs();
        long batch = System.currentTimeMillis();
        JSONArray out = new JSONArray();
        ArrayList<Uri> uris = new ArrayList<Uri>();
        String action = it.getAction();
        if (Intent.ACTION_VIEW.equals(action)) { if (it.getData() != null) uris.add(it.getData()); }
        else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = (Uri) it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            String text = it.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", "text.txt"); o.put("type", "text/plain"); o.put("textContent", text);
                    String subj = it.getStringExtra(Intent.EXTRA_SUBJECT);
                    if (subj != null) o.put("subject", subj);
                    out.put(o);
                } catch (Exception e) { /* تجاهل */ }
            }
        } else {
            ArrayList<Uri> list = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        int i = 0;
        for (Uri u : uris) {
            JSONObject o = new JSONObject();
            try {
                String name = displayName(u);
                File dir = new File(base, batch + "-" + i);
                dir.mkdirs();
                File f = new File(dir, DownloadBridge.cleanName(name));
                long size = copy(u, f);
                String type = typeOf(u, f.getName(), it.getType());
                String id = batch + "-" + i + "/" + f.getName();
                synchronized (lock) { served.put(id, f); servedType.put(id, type); }
                o.put("name", f.getName()); o.put("type", type); o.put("size", size);
                o.put("url", "https://" + HOST + "/f/" + batch + "-" + i + "/" + URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20"));
            } catch (Throwable e) {
                try { o.put("name", String.valueOf(u.getLastPathSegment())); o.put("error", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); } catch (Exception x) { /* تجاهل */ }
            }
            out.put(o);
            i++;
        }
        if (out.length() == 0) return false;
        synchronized (lock) { last = out; untaken = out; }
        return true;
    }

    /** بيرجّع استجابة WebView لروابط الملفات الواردة (أو null لو مش رابط ملف). */
    WebResourceResponse intercept(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        java.util.List<String> seg = u.getPathSegments();
        if (seg.size() != 3 || !"f".equals(seg.get(0))) return null;
        File f; String type;
        synchronized (lock) { String id = seg.get(1) + "/" + seg.get(2); f = served.get(id); type = servedType.get(id); }
        if (f == null || !f.isFile()) return null;
        try {
            Map<String, String> h = new HashMap<String, String>();
            h.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse(type == null ? "application/octet-stream" : type, null, 200, "OK", h, new FileInputStream(f));
        } catch (IOException e) { return null; }
    }

    private String displayName(Uri u) {
        String n = null;
        if ("content".equals(u.getScheme())) {
            Cursor c = null;
            try {
                c = act.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst()) n = c.getString(0);
            } catch (Throwable e) { /* تجاهل */ } finally { if (c != null) c.close(); }
        }
        if (n == null || n.isEmpty()) n = u.getLastPathSegment();
        if (n == null || n.isEmpty()) n = "file";
        int slash = n.lastIndexOf('/');
        return slash >= 0 ? n.substring(slash + 1) : n;
    }

    private String typeOf(Uri u, String name, String intentType) {
        String t = null;
        try { t = act.getContentResolver().getType(u); } catch (Throwable e) { /* تجاهل */ }
        if (t == null || t.equals("application/octet-stream") || t.equals("*/*")) {
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (ext != null) t = ext;
        }
        if (t == null || t.equals("*/*")) t = intentType != null && !intentType.equals("*/*") ? intentType : "application/octet-stream";
        return t;
    }

    private long copy(Uri u, File to) throws IOException {
        InputStream in = act.getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("تعذّر فتح الملف");
        OutputStream o = new FileOutputStream(to);
        try {
            byte[] b = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > MAX_FILE) throw new IOException("الملف أكبر من 200 ميجا");
                o.write(b, 0, n);
            }
            return total;
        } finally { in.close(); o.close(); }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /* ---------------- واجهة JS ---------------- */

    @JavascriptInterface public String take() {
        synchronized (lock) { String s = untaken.toString(); untaken = new JSONArray(); return s; }
    }
    @JavascriptInterface public String current() {
        synchronized (lock) { untaken = new JSONArray(); return last.toString(); }
    }

    @JavascriptInterface public void print() {
        act.runOnUiThread(new Runnable() { public void run() {
            try {
                PrintManager pm = (PrintManager) act.getSystemService(Context.PRINT_SERVICE);
                String job = String.valueOf(act.getTitle());
                PrintDocumentAdapter a = web.createPrintDocumentAdapter(job);
                pm.print(job, a, new PrintAttributes.Builder().build());
            } catch (Throwable e) { Toast.makeText(act, "تعذّرت الطباعة", Toast.LENGTH_LONG).show(); }
        } });
    }

    /* ---------------- الحفظ في مجلد (SAF) ---------------- */

    private SharedPreferences prefs() { return act.getSharedPreferences("h2a_files", Context.MODE_PRIVATE); }
    private Uri folder() { String s = prefs().getString("folder", null); return s == null ? null : Uri.parse(s); }

    @JavascriptInterface public boolean hasFolder() { return folder() != null; }

    @JavascriptInterface public String chooseFolder() {
        act.runOnUiThread(new Runnable() { public void run() {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                act.startActivityForResult(i, REQ_TREE);
            } catch (Throwable e) { Toast.makeText(act, "تعذّر فتح اختيار المجلد", Toast.LENGTH_LONG).show(); }
        } });
        return "ok";
    }

    boolean wantsFolder() { return folderMode; }

    /** بيحفظ في المجلد المختار. لو مفيش مجلد لسه: بيطلب اختياره وبيكمّل الحفظ بعدها. بيرجّع "ok:..." أو "error:...". */
    String saveFlow(String name, String mime, byte[] data) {
        Uri tree = folder();
        if (tree != null) {
            try { return "ok:" + writeTo(tree, name, mime, data); }
            catch (Throwable e) { prefs().edit().remove("folder").apply(); /* الإذن ضاع: نختار تاني */ }
        }
        try {
            if (pendingFile != null) pendingFile.delete();
            pendingFile = new File(act.getCacheDir(), "pending-save.bin");
            FileOutputStream o = new FileOutputStream(pendingFile);
            try { o.write(data); } finally { o.close(); }
            pendingName = name; pendingMime = mime;
            chooseFolder();
            return "ok:pending";
        } catch (Throwable e) { return "error: " + e.getClass().getSimpleName(); }
    }

    private String writeTo(Uri tree, String name, String mime, byte[] data) throws IOException {
        ContentResolver r = act.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri doc = DocumentsContract.createDocument(r, parent, mime == null || mime.isEmpty() ? "application/octet-stream" : mime, name);
        if (doc == null) throw new IOException("createDocument failed");
        OutputStream o = r.openOutputStream(doc);
        if (o == null) throw new IOException("open failed");
        try { o.write(data); } finally { o.close(); }
        return name;
    }

    void onTreeResult(int res, Intent data) {
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (pendingFile != null) { pendingFile.delete(); pendingFile = null; }
            return;
        }
        Uri tree = data.getData();
        try { act.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Throwable e) { /* تجاهل */ }
        prefs().edit().putString("folder", tree.toString()).apply();
        Toast.makeText(act, "تم اختيار مجلد الحفظ", Toast.LENGTH_SHORT).show();
        if (pendingFile != null) {
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                InputStream in = new FileInputStream(pendingFile);
                byte[] b = new byte[16384]; int n;
                try { while ((n = in.read(b)) > 0) bo.write(b, 0, n); } finally { in.close(); }
                String saved = writeTo(tree, pendingName, pendingMime, bo.toByteArray());
                Toast.makeText(act, "تم الحفظ: " + saved, Toast.LENGTH_LONG).show();
            } catch (Throwable e) { Toast.makeText(act, "تعذّر الحفظ في المجلد", Toast.LENGTH_LONG).show(); }
            pendingFile.delete(); pendingFile = null;
        }
    }
}
