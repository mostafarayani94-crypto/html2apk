package com.h2a.builder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** واجهة (HTML محلي) + جسر Native بيدير المشاريع والبناء والتوقيع والتثبيت أوفلاين. */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 7;
    private static final String ACTION_INSTALL = "com.h2a.builder.INSTALL_RESULT";
    private WebView web;
    private String pickKind;
    private Signer signer;
    private Projects projects;
    private BroadcastReceiver installReceiver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences crashPrefs = getSharedPreferences("diag", MODE_PRIVATE);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try { crashPrefs.edit().putString("crash", android.util.Log.getStackTraceString(e)).commit(); } catch (Throwable x) { /* */ }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
        signer = new Signer(this);
        projects = new Projects(this, signer);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Native(), "Native");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView v, String u, String m, JsResult r) { toast(m); r.confirm(); return true; }
        });
        web.loadUrl("file:///android_asset/ui/index.html");
        installReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(confirm); }
                } else if (status == PackageInstaller.STATUS_SUCCESS) js("onInstall", "{\"ok\":true}");
                else js("onInstall", q(new String[][]{{"ok", "false"}, {"msg", String.valueOf(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))}}));
            }
        };
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, new IntentFilter(ACTION_INSTALL), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installReceiver, new IntentFilter(ACTION_INSTALL));
    }

    @Override protected void onDestroy() { try { unregisterReceiver(installReceiver); } catch (Exception e) { /* */ } super.onDestroy(); }

    /** الرجوع: الواجهة هي اللي بتقرر (ترجع لقائمة المشاريع أو تقفل قائمة)، ولو ما اتعاملتش معاه التطبيق يقفل. */
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.h2aBack?window.h2aBack():false", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) { if (!"true".equals(v)) MainActivity.super.onBackPressed(); }
        });
    }

    private void toast(String m) { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show(); }

    private void js(final String fn, final String jsonArg) {
        runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript("window." + fn + "&&window." + fn + "(" + jsonArg + ")", null); } });
    }

    private static String q(String[][] kv) {
        JSONObject o = new JSONObject();
        try { for (String[] p : kv) { if (p[1].equals("true") || p[1].equals("false")) o.put(p[0], Boolean.parseBoolean(p[1])); else o.put(p[0], p[1]); } } catch (Exception e) { /* */ }
        return o.toString();
    }

    private static String err(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private File cacheFile(String kind) { return new File(getCacheDir(), "pick_" + kind); }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        final String kind = pickKind;
        if (res != RESULT_OK) { js("onPicked", q(new String[][]{{"kind", kind}, {"cancel", "true"}})); return; }
        Uri picked = data == null ? null : data.getData();
        if (picked == null && data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) picked = data.getClipData().getItemAt(0).getUri();
        if (picked == null) { js("onPicked", q(new String[][]{{"kind", kind}, {"error", "الاختيار رجع من غير ملف، جرّب تاني أو اختار من مجلد التنزيلات"}})); return; }
        final Uri uri = picked;
        new Thread(new Runnable() { public void run() {
            try {
                String name = "file";
                Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) { try { if (c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.getString(i) != null) name = c.getString(i); } } finally { c.close(); } }
                File dst = cacheFile(kind);
                long limit = kind.equals("html") ? Projects.MAX_HTML : 60L * 1024 * 1024;
                long size = copy(getContentResolver().openInputStream(uri), new FileOutputStream(dst), limit);
                JSONObject o = new JSONObject();
                o.put("kind", kind); o.put("name", name); o.put("size", size);
                if (kind.equals("icon")) {
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(dst.getAbsolutePath());
                    if (bm == null) throw new IOException("الملف ده مش صورة صالحة (استخدم PNG أو JPG)");
                    o.put("thumb", "data:image/png;base64," + Base64.encodeToString(Projects.png(Projects.square(bm, 128)), Base64.NO_WRAP));
                } else if (kind.equals("html")) {
                    o.put("text", new String(Projects.readAll(dst), "UTF-8"));
                }
                js("onPicked", o.toString());
            } catch (Throwable e) { js("onPicked", q(new String[][]{{"kind", kind}, {"error", err(e)}})); }
        } }).start();
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        try {
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) { total += n; if (total > limit) throw new IOException(limit < 10L * 1024 * 1024 ? "الملف كبير جداً (الحد " + (limit / 1048576) + " MB). استخدم ZIP." : "الملف كبير جداً"); out.write(buf, 0, n); }
            return total;
        } finally { in.close(); out.close(); }
    }

    final class Native {
        @JavascriptInterface public void pick(final String kind) {
            pickKind = kind;
            runOnUiThread(new Runnable() { public void run() {
                String mime = kind.equals("icon") ? "image/*" : "*/*";
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(i, REQ_PICK);
                } catch (Throwable first) {
                    try {
                        Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                        g.addCategory(Intent.CATEGORY_OPENABLE);
                        g.setType(mime);
                        startActivityForResult(Intent.createChooser(g, "اختار ملف"), REQ_PICK);
                    } catch (Throwable second) {
                        js("onPicked", q(new String[][]{{"kind", kind}, {"error", "مفيش تطبيق ملفات على الجهاز يقدر يفتح الاختيار: " + second.getClass().getSimpleName()}}));
                    }
                }
            } });
        }

        // ---------- المشاريع ----------

        @JavascriptInterface public String listProjects() {
            try { return projects.list(); } catch (Throwable e) { return "[]"; }
        }

        @JavascriptInterface public String getProject(String id) {
            try { return projects.get(id).toString(); } catch (Throwable e) { return q(new String[][]{{"error", err(e)}}); }
        }

        /** cfgJson فيه الإعدادات. ملفات ZIP والأيقونة الجديدة بتتاخد من آخر اختيار لو newZip/newIcon = true. */
        @JavascriptInterface public String saveProject(String cfgJson) {
            try {
                JSONObject cfg = new JSONObject(cfgJson);
                File z = cfg.optBoolean("newZip", false) ? cacheFile("zip") : null;
                File ic = cfg.optBoolean("newIcon", false) ? cacheFile("icon") : null;
                JSONObject m = projects.save(cfg, z, ic);
                if (ic != null) ic.delete();
                return q(new String[][]{{"ok", "true"}, {"id", m.getString("id")}});
            } catch (Throwable e) { return q(new String[][]{{"ok", "false"}, {"msg", err(e)}}); }
        }

        @JavascriptInterface public String duplicateProject(String id) {
            try { return q(new String[][]{{"ok", "true"}, {"id", projects.duplicate(id)}}); } catch (Throwable e) { return q(new String[][]{{"ok", "false"}, {"msg", err(e)}}); }
        }

        @JavascriptInterface public boolean deleteProject(String id) {
            try { projects.delete(id); return true; } catch (Throwable e) { return false; }
        }

        @JavascriptInterface public void buildProject(final String id) {
            new Thread(new Runnable() { public void run() {
                try {
                    JSONObject r = projects.build(id, new Projects.Progress() { public void on(String m) { js("onProgress", JSONObject.quote(m)); } });
                    js("onBuilt", r.toString());
                } catch (Throwable e) {
                    android.util.Log.e("h2a", "build failed", e);
                    js("onBuilt", q(new String[][]{{"ok", "false"}, {"id", id}, {"msg", e.getClass().getSimpleName() + ": " + err(e)}}));
                }
            } }).start();
        }

        // ---------- التثبيت والحفظ ----------

        @JavascriptInterface public boolean canInstall() { return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls(); }

        @JavascriptInterface public void allowInstall() {
            runOnUiThread(new Runnable() { public void run() {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } });
        }

        @JavascriptInterface public void install(final String id) {
            new Thread(new Runnable() { public void run() {
                try {
                    File apk = projects.apk(id);
                    if (!apk.exists()) throw new IOException("ابنِ التطبيق الأول");
                    PackageInstaller pi = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    int sid = pi.createSession(sp);
                    PackageInstaller.Session s = pi.openSession(sid);
                    try {
                        OutputStream o = s.openWrite("app.apk", 0, apk.length());
                        InputStream fin = new FileInputStream(apk);
                        try {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = fin.read(buf)) > 0) o.write(buf, 0, n);
                            s.fsync(o);
                        } finally { fin.close(); o.close(); }
                        Intent cb = new Intent(ACTION_INSTALL).setPackage(getPackageName());
                        android.app.PendingIntent pend = android.app.PendingIntent.getBroadcast(MainActivity.this, sid, cb, pendingFlags());
                        s.commit(pend.getIntentSender());
                    } finally { s.close(); }
                } catch (Throwable e) { js("onInstall", q(new String[][]{{"ok", "false"}, {"msg", err(e)}})); }
            } }).start();
        }

        @JavascriptInterface public void saveApk(String id, String name) {
            try {
                File apk = projects.apk(id);
                if (!apk.exists()) throw new IOException("ابنِ التطبيق الأول");
                String n = name.replaceAll("[^\\p{L}\\p{N}_ -]", "").trim();
                if (n.isEmpty()) n = "app";
                toast("تم الحفظ: " + saveToDownloads(n + ".apk", "application/vnd.android.package-archive", Projects.readAll(apk)));
            } catch (Exception e) { toast("فشل الحفظ: " + err(e)); }
        }

        /** نسخة احتياطية لمفتاح التوقيع (مهمة: من غيرها مش هتقدر تحدّث تطبيقاتك لو مسحت التطبيق ده). */
        @JavascriptInterface public void exportKey() {
            try {
                JSONObject o = new JSONObject();
                o.put("key", Base64.encodeToString(signer.exportKey(), Base64.NO_WRAP));
                o.put("cert", Base64.encodeToString(signer.exportCert(), Base64.NO_WRAP));
                toast("تم الحفظ: " + saveToDownloads("h2a-signing-key.json", "application/json", o.toString().getBytes("UTF-8")));
            } catch (Exception e) { toast("فشل: " + err(e)); }
        }

        @JavascriptInterface public void applyKey() {
            try {
                JSONObject o = new JSONObject(new String(Projects.readAll(cacheFile("key")), "UTF-8"));
                signer.importKey(Base64.decode(o.getString("key"), Base64.DEFAULT), Base64.decode(o.getString("cert"), Base64.DEFAULT));
                toast("تم استيراد المفتاح");
            } catch (Exception e) { toast("ملف المفتاح غير صالح"); }
        }

        /** آخر تعطّل (لو حصل) عشان يتعرض في الواجهة بدل ما التطبيق يقفل ساكت. */
        @JavascriptInterface public String lastCrash() {
            SharedPreferences d = getSharedPreferences("diag", MODE_PRIVATE);
            String c = d.getString("crash", "");
            if (!c.isEmpty()) d.edit().remove("crash").apply();
            return c.length() > 1500 ? c.substring(0, 1500) : c;
        }

        @JavascriptInterface public String info() { return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") " + Build.MANUFACTURER + " " + Build.MODEL; }
    }

    private static int pendingFlags() {
        int f = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) f |= android.app.PendingIntent.FLAG_MUTABLE; // لازم mutable عشان النظام يضيف نتيجة التثبيت
        return f;
    }

    private String saveToDownloads(String name, String mime, byte[] data) throws IOException {
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (Build.VERSION.SDK_INT < 29) { // أندرويد 8 و9: مجلد التطبيق الخارجي (من غير صلاحيات)
            File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IOException("تعذّر الحفظ");
            dir.mkdirs();
            Projects.writeAll(new File(dir, name), data);
            return "Android/data/" + getPackageName() + "/files/Download/" + name;
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, mime);
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        ContentResolver r = getContentResolver();
        Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (u == null) throw new IOException("تعذّر الحفظ");
        OutputStream o = r.openOutputStream(u);
        try { o.write(data); } finally { o.close(); }
        cv.clear();
        cv.put(MediaStore.Downloads.IS_PENDING, 0);
        r.update(u, cv, null, null);
        return "Downloads/" + name;
    }
}
