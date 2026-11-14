package com.h2a.shell;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * جسر المشاركة: بيخلّي navigator.share (Web Share API) تفتح قائمة المشاركة الكاملة بتاعة أندرويد
 * (واتساب، فيسبوك، ماسنجر، بلوتوث، رسائل…) لنص أو رابط أو ملفات. WebView نفسه ما بيدعمش navigator.share.
 */
public final class ShareBridge {
    private static final long MAX_TOTAL = 24L * 1024 * 1024;
    private final Activity act;

    ShareBridge(Activity act) { this.act = act; }

    /** JS بيتحقن في كل صفحة: بيعرّف navigator.share/canShare ويكلّم الجسر. */
    static final String POLYFILL =
        "(function(){if(window.__h2aShare||!window.H2AShare)return;window.__h2aShare=1;"
        + "function b64(f){return new Promise(function(ok,no){var r=new FileReader();r.onload=function(){var s=String(r.result);ok(s.slice(s.indexOf(',')+1))};r.onerror=function(){no(r.error)};r.readAsDataURL(f)})}"
        + "navigator.canShare=function(d){if(!d)return false;if(d.files&&d.files.length)return true;return !!(d.title||d.text||d.url)};"
        + "navigator.share=function(d){d=d||{};return (async function(){"
        + "if(!navigator.canShare(d))throw new TypeError('Nothing to share');"
        + "var files=[];if(d.files&&d.files.length){for(var i=0;i<d.files.length;i++){var f=d.files[i];files.push({name:f.name||('file'+i),type:f.type||'application/octet-stream',data:await b64(f)})}}"
        + "var text=[d.text,d.url].filter(Boolean).join('\\n');"
        + "var r=H2AShare.share(JSON.stringify({title:d.title||'',text:text,files:files}));"
        + "if(r!=='ok')throw new DOMException(String(r),'NotAllowedError');"
        + "})()};})();";

    @JavascriptInterface
    public String share(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String title = o.optString("title", "");
            String text = o.optString("text", "");
            JSONArray files = o.optJSONArray("files");
            Intent i;
            if (files == null || files.length() == 0) {
                if (title.isEmpty() && text.isEmpty()) return "error: nothing to share";
                i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                if (!title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.putExtra(Intent.EXTRA_TEXT, text.isEmpty() ? title : text);
            } else {
                ArrayList<Uri> uris = new ArrayList<Uri>();
                String common = null;
                long total = 0;
                File base = ShareProvider.root(act);
                cleanOld(base);
                File dir = new File(base, "s" + System.currentTimeMillis());
                dir.mkdirs();
                for (int k = 0; k < files.length(); k++) {
                    JSONObject f = files.getJSONObject(k);
                    byte[] data = Base64.decode(f.getString("data"), Base64.DEFAULT);
                    total += data.length;
                    if (total > MAX_TOTAL) return "error: files too large";
                    String name = f.optString("name", "file" + k).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
                    if (name.isEmpty() || name.startsWith(".")) name = "file" + k + name;
                    File out = new File(dir, name);
                    FileOutputStream fo = new FileOutputStream(out);
                    try { fo.write(data); } finally { fo.close(); }
                    uris.add(new Uri.Builder().scheme("content").authority(ShareProvider.authority(act)).appendPath(dir.getName()).appendPath(name).build());
                    String t = f.optString("type", "application/octet-stream");
                    if (t.isEmpty()) t = "application/octet-stream";
                    common = common == null ? t : (common.equals(t) ? t : (common.split("/")[0].equals(t.split("/")[0]) ? t.split("/")[0] + "/*" : "*/*"));
                }
                if (uris.size() == 1) {
                    i = new Intent(Intent.ACTION_SEND);
                    i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                }
                i.setType(common);
                if (!title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
                if (!text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ClipData clip = ClipData.newRawUri("files", uris.get(0));
                for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
                i.setClipData(clip);
            }
            final Intent chooser = Intent.createChooser(i, title.isEmpty() ? null : title);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.runOnUiThread(new Runnable() { public void run() { try { act.startActivity(chooser); } catch (Exception e) { /* مفيش تطبيق مشاركة */ } } });
            return "ok";
        } catch (Throwable e) {
            return "error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
    }

    /** بيمسح ملفات مشاركة أقدم من يوم عشان الكاش ما يتملّاش. */
    private static void cleanOld(File base) {
        File[] ds = base.listFiles();
        if (ds == null) return;
        long limit = System.currentTimeMillis() - 24L * 3600 * 1000;
        for (File d : ds) {
            if (d.lastModified() < limit) {
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) f.delete();
                d.delete();
            }
        }
    }
}
