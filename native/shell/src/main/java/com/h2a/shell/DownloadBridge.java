package com.h2a.shell;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * حفظ الملفات في مجلد التنزيلات باسمها الحقيقي (اللي التطبيق حدده في download="...")،
 * بدل الاسم العشوائي الطويل اللي WebView بيطلّعه لروابط blob:.
 */
public final class DownloadBridge {
    private final Activity act;
    private final FilesBridge files;

    DownloadBridge(Activity act) { this(act, null); }
    DownloadBridge(Activity act, FilesBridge files) { this.act = act; this.files = files; }

    /** JS بيتحقن في كل صفحة: بيلتقط a[download] وshowSaveFilePicker ويبعت الاسم الصحيح للجسر. */
    static final String POLYFILL =
        "(function(){if(window.__h2aDl||!window.H2ADownload)return;window.__h2aDl=1;"
        + "var last='';"
        + "function ext(m){var t={'text/csv':'.csv','text/plain':'.txt','application/json':'.json','text/html':'.html','application/pdf':'.pdf','image/png':'.png','image/jpeg':'.jpg','image/gif':'.gif','image/webp':'.webp','image/svg+xml':'.svg','application/zip':'.zip','text/xml':'.xml','application/xml':'.xml','audio/mpeg':'.mp3','video/mp4':'.mp4','application/vnd.ms-excel':'.xls','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':'.xlsx'};return t[(m||'').split(';')[0]]||''}"
        + "function b64(b){return new Promise(function(ok,no){var r=new FileReader();r.onload=function(){var s=String(r.result);ok(s.slice(s.indexOf(',')+1))};r.onerror=function(){no(r.error)};r.readAsDataURL(b)})}"
        + "function nameOf(a,blob){var n=a.getAttribute('download');var h=a.getAttribute('href')||'';"
        + "if(!n&&h.indexOf('blob:')!==0&&h.indexOf('data:')!==0){try{n=decodeURIComponent(h.split('?')[0].split('#')[0].split('/').pop())}catch(e){n=''}}"
        + "n=(n||'').trim();if(!n)n=((document.title||'').trim()||'download');if(!/\\.[A-Za-z0-9]{1,6}$/.test(n))n+=ext(blob&&blob.type);return n}"
        + "function saveBlob(blob,name){last=name;return b64(blob).then(function(d){var r=H2ADownload.save(name,blob.type||'application/octet-stream',d);if(String(r).indexOf('ok')!==0)alert('\\u062A\\u0639\\u0630\\u0651\\u0631 \\u062D\\u0641\\u0638 \\u0627\\u0644\\u0645\\u0644\\u0641: '+r)})}"
        + "function handle(a){var href=a.href||'';if(!a.hasAttribute('download'))return false;"
        + "if(href.indexOf('blob:')===0||href.indexOf('data:')===0){fetch(href).then(function(r){return r.blob()}).then(function(b){return saveBlob(b,nameOf(a,b))}).catch(function(e){alert('\\u062A\\u0639\\u0630\\u0651\\u0631 \\u062D\\u0641\\u0638 \\u0627\\u0644\\u0645\\u0644\\u0641: '+e)});return true}"
        + "if(/^https?:/i.test(href)){var n=nameOf(a,null);last=n;H2ADownload.saveUrl(n,href);return true}return false}"
        + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[download]');if(a&&!e.defaultPrevented&&handle(a))e.preventDefault()},true);"
        + "var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(this.hasAttribute('download')&&handle(this))return;return oc.apply(this,arguments)};"
        + "if(!window.showSaveFilePicker){window.showSaveFilePicker=function(o){var name=(o&&o.suggestedName)||'download';var chunks=[];"
        + "return Promise.resolve({kind:'file',name:name,createWritable:function(){return Promise.resolve({write:function(d){var x=(d&&d.data!==undefined)?d.data:d;chunks.push(x instanceof Blob?x:new Blob([x]));return Promise.resolve()},"
        + "close:function(){return saveBlob(new Blob(chunks,{type:''}),name)}})}})}}"
        + "})();";

    static String cleanName(String name) {
        String n = (name == null ? "" : name).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "download";
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 8 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n;
    }

    private void toast(final String m) {
        act.runOnUiThread(new Runnable() { public void run() { Toast.makeText(act, m, Toast.LENGTH_LONG).show(); } });
    }

    @JavascriptInterface
    public String save(String name, String mime, String base64) {
        try {
            String n = cleanName(name);
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            if (files != null && files.wantsFolder()) { // مجلد الحفظ اللي اختاره المستخدم
                String r = files.saveFlow(n, mime, data);
                if (r.startsWith("ok:")) { if (!r.equals("ok:pending")) toast("تم الحفظ: " + n); return r; }
            }
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, n);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                ContentResolver r = act.getContentResolver();
                Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new IOException("insert failed");
                OutputStream o = r.openOutputStream(u);
                try { o.write(data); } finally { o.close(); }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                r.update(u, cv, null, null);
                where = "Downloads/" + n;
            } else {
                File dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IOException("no storage");
                dir.mkdirs();
                File f = new File(dir, n);
                FileOutputStream o = new FileOutputStream(f);
                try { o.write(data); } finally { o.close(); }
                where = f.getAbsolutePath();
            }
            toast("تم الحفظ في التنزيلات: " + n);
            return "ok:" + where;
        } catch (Throwable e) {
            return "error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
    }

    @JavascriptInterface
    public String saveUrl(String name, String url) {
        try {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return "error: bad url";
            String n = cleanName(name);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            int dot = n.lastIndexOf('.');
            String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot + 1).toLowerCase());
            if (mime != null) req.setMimeType(mime);
            req.setTitle(n);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, n);
            ((DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            toast("جاري التنزيل: " + n);
            return "ok";
        } catch (Throwable e) {
            return "error: " + e.getClass().getSimpleName();
        }
    }
}
