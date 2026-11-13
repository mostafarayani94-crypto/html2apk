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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JsResult;
import com.h2a.builder.core.ApkBuilder;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

/** واجهة (HTML محلي) + جسر Native بيعمل البناء والتوقيع والتثبيت أوفلاين. */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 7;
    private static final String ACTION_INSTALL = "com.h2a.builder.INSTALL_RESULT";
    private WebView web;
    private String pickKind;
    private File lastApk;
    private String lastName = "app";
    private Signer signer;
    private BroadcastReceiver installReceiver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        signer = new Signer(this);
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
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    private void toast(String m) { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show(); }

    private void js(final String fn, final String jsonArg) {
        runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript("window." + fn + "&&window." + fn + "(" + jsonArg + ")", null); } });
    }

    private static String q(String[][] kv) {
        JSONObject o = new JSONObject();
        try { for (String[] p : kv) { if (p[1].equals("true") || p[1].equals("false")) o.put(p[0], Boolean.parseBoolean(p[1])); else o.put(p[0], p[1]); } } catch (Exception e) { /* */ }
        return o.toString();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        final String kind = pickKind;
        if (res != RESULT_OK || data == null || data.getData() == null) { js("onPicked", q(new String[][]{{"kind", kind}, {"cancel", "true"}})); return; }
        final Uri uri = data.getData();
        new Thread(new Runnable() { public void run() {
            try {
                String name = "file";
                Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) { try { if (c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i); } } finally { c.close(); } }
                File dst = new File(getCacheDir(), "pick_" + kind);
                long size = copy(getContentResolver().openInputStream(uri), new FileOutputStream(dst), 60L * 1024 * 1024);
                JSONObject o = new JSONObject();
                o.put("kind", kind); o.put("name", name); o.put("size", size);
                js("onPicked", o.toString());
            } catch (Exception e) { js("onPicked", q(new String[][]{{"kind", kind}, {"error", String.valueOf(e.getMessage())}})); }
        } }).start();
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        try {
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) { total += n; if (total > limit) throw new IOException("الملف كبير جداً"); out.write(buf, 0, n); }
            return total;
        } finally { in.close(); out.close(); }
    }

    private static byte[] readFile(File f) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        copy(new FileInputStream(f), o, Long.MAX_VALUE);
        return o.toByteArray();
    }

    private void copyAsset(String name, File dst) throws IOException { copy(getAssets().open(name), new FileOutputStream(dst), Long.MAX_VALUE); }

    final class Native {
        @JavascriptInterface public void pick(String kind) {
            pickKind = kind;
            final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            if (kind.equals("icon")) i.setType("image/*");
            else if (kind.equals("zip")) i.setType("*/*");
            else i.setType("*/*");
            runOnUiThread(new Runnable() { public void run() { startActivityForResult(i, REQ_PICK); } });
        }

        /** ids: بيرجع معرّف الحزمة الخاص بالاسم (بيتولّد مرة وبيتحفظ، عشان التحديثات). */
        @JavascriptInterface public String appIdFor(String name) {
            SharedPreferences sp = getSharedPreferences("ids", MODE_PRIVATE);
            String key = "n_" + name.trim();
            String id = sp.getString(key, null);
            if (id == null) {
                String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
                SecureRandom r = new SecureRandom();
                StringBuilder sb = new StringBuilder("com.h2a.a");
                for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
                id = sb.toString();
                sp.edit().putString(key, id).apply();
            }
            return id;
        }

        @JavascriptInterface public void build(final String cfgJson) {
            new Thread(new Runnable() { public void run() {
                try { doBuild(new JSONObject(cfgJson)); }
                catch (Throwable e) { android.util.Log.e("h2a", "build failed", e); js("onBuilt", q(new String[][]{{"ok", "false"}, {"msg", e.getClass().getSimpleName() + ": " + e.getMessage()}})); }
            } }).start();
        }

        @JavascriptInterface public boolean canInstall() { return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls(); }

        @JavascriptInterface public void allowInstall() {
            runOnUiThread(new Runnable() { public void run() {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } });
        }

        @JavascriptInterface public void install() {
            new Thread(new Runnable() { public void run() {
                try {
                    if (lastApk == null || !lastApk.exists()) throw new IOException("ابنِ التطبيق الأول");
                    PackageInstaller pi = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    int id = pi.createSession(sp);
                    PackageInstaller.Session s = pi.openSession(id);
                    try {
                        OutputStream o = s.openWrite("app.apk", 0, lastApk.length());
                        InputStream fin = new FileInputStream(lastApk);
                        try {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = fin.read(buf)) > 0) o.write(buf, 0, n);
                            s.fsync(o);
                        } finally { fin.close(); o.close(); }
                        Intent cb = new Intent(ACTION_INSTALL).setPackage(getPackageName());
                        int flags = PendingIntent_FLAGS();
                        android.app.PendingIntent pend = android.app.PendingIntent.getBroadcast(MainActivity.this, id, cb, flags);
                        s.commit(pend.getIntentSender());
                    } finally { s.close(); }
                } catch (Throwable e) { js("onInstall", q(new String[][]{{"ok", "false"}, {"msg", String.valueOf(e.getMessage())}})); }
            } }).start();
        }

        @JavascriptInterface public void saveApk() {
            try {
                if (lastApk == null || !lastApk.exists()) throw new IOException("ابنِ التطبيق الأول");
                toast("تم الحفظ: " + saveToDownloads(lastName + ".apk", "application/vnd.android.package-archive", readFile(lastApk)));
            } catch (Exception e) { toast("فشل الحفظ: " + e.getMessage()); }
        }

        /** نسخة احتياطية لمفتاح التوقيع (مهمة: من غيرها مش هتقدر تحدّث تطبيقاتك لو مسحت التطبيق ده). */
        @JavascriptInterface public void exportKey() {
            try {
                JSONObject o = new JSONObject();
                o.put("key", Base64.encodeToString(signer.exportKey(), Base64.NO_WRAP));
                o.put("cert", Base64.encodeToString(signer.exportCert(), Base64.NO_WRAP));
                toast("تم الحفظ: " + saveToDownloads("h2a-signing-key.json", "application/json", o.toString().getBytes("UTF-8")));
            } catch (Exception e) { toast("فشل: " + e.getMessage()); }
        }

        @JavascriptInterface public void importKey() { pick("key"); }

        @JavascriptInterface public void applyKey() {
            try {
                JSONObject o = new JSONObject(new String(readFile(new File(getCacheDir(), "pick_key")), "UTF-8"));
                signer.importKey(Base64.decode(o.getString("key"), Base64.DEFAULT), Base64.decode(o.getString("cert"), Base64.DEFAULT));
                toast("تم استيراد المفتاح");
            } catch (Exception e) { toast("ملف المفتاح غير صالح"); }
        }
    }

    private static int PendingIntent_FLAGS() {
        int f = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) f |= android.app.PendingIntent.FLAG_MUTABLE; // لازم mutable عشان النظام يضيف نتيجة التثبيت
        return f;
    }

    private String saveToDownloads(String name, String mime, byte[] data) throws IOException {
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
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

    private void progress(String m) { js("onProgress", JSONObject.quote(m)); }

    private static final java.util.regex.Pattern COLOR = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    private void doBuild(JSONObject c) throws Exception {
        String name = c.getString("appName").trim();
        if (name.isEmpty() || name.length() > 40) throw new IOException("الاسم لازم من 1 إلى 40 حرف");
        String source = c.optString("source", "html");
        String bg = c.optString("backgroundColor", "#ffffff");
        if (!COLOR.matcher(bg).matches()) bg = "#ffffff";
        String orient = c.optString("orientation", "auto");
        if (!orient.equals("portrait") && !orient.equals("landscape")) orient = "auto";
        String cache = c.optString("cacheMode", "default");
        if (!cache.equals("noCache") && !cache.equals("cacheElseNetwork")) cache = "default";
        String versionName = c.optString("versionName", "1.0");
        if (!versionName.matches("^[0-9A-Za-z._-]{1,20}$")) throw new IOException("رقم الإصدار غلط");
        int versionCode = c.optInt("versionCode", 0);
        SharedPreferences sp = getSharedPreferences("ids", MODE_PRIVATE);
        if (versionCode <= 0) { versionCode = sp.getInt("v_" + name, 0) + 1; }
        String pkg = new Native().appIdFor(name);

        JSONObject shell = new JSONObject();
        shell.put("orientation", orient);
        shell.put("fullscreen", c.optBoolean("fullscreen", false));
        shell.put("keepScreenOn", c.optBoolean("keepScreenOn", false));
        shell.put("desktopMode", c.optBoolean("desktopMode", false));
        shell.put("allowMixedContent", c.optBoolean("allowMixedContent", false));
        shell.put("backgroundColor", bg);
        shell.put("cacheMode", cache);
        shell.put("bridge", c.optBoolean("bridge", true));
        shell.put("downloads", c.optBoolean("downloads", true));
        shell.put("permissions", c.optJSONArray("permissions") != null ? c.getJSONArray("permissions") : new org.json.JSONArray());

        ApkBuilder.Spec spec = new ApkBuilder.Spec();
        spec.packageName = pkg; spec.label = name; spec.versionName = versionName; spec.versionCode = versionCode;
        if (source.equals("url")) {
            String url = c.optString("url", "");
            if (!url.startsWith("https://")) throw new IOException("الرابط لازم يبدأ بـ https://");
            shell.put("url", url);
            spec.remote = true;
        } else if (source.equals("zip")) {
            spec.zip = new File(getCacheDir(), "pick_zip");
            if (!spec.zip.exists()) throw new IOException("اختار ملف ZIP");
        } else if (source.equals("editor")) {
            File f = new File(getCacheDir(), "editor.html");
            FileOutputStream fo = new FileOutputStream(f);
            try { fo.write(c.optString("code", "").getBytes("UTF-8")); } finally { fo.close(); }
            if (f.length() == 0) throw new IOException("المحرر فاضي");
            spec.html = f;
        } else {
            spec.html = new File(getCacheDir(), "pick_html");
            if (!spec.html.exists()) throw new IOException("اختار ملف HTML");
        }
        final File iconFile = new File(getCacheDir(), "pick_icon");
        if (c.optBoolean("useIcon", false) && iconFile.exists()) {
            final Bitmap src = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
            if (src == null) throw new IOException("الأيقونة مش صورة صالحة");
            spec.icons = new ApkBuilder.IconProvider() { public byte[] png(int size) {
                int m = Math.min(src.getWidth(), src.getHeight());
                Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - m) / 2, (src.getHeight() - m) / 2, m, m);
                Bitmap sc = Bitmap.createScaledBitmap(sq, size, size, true);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                sc.compress(Bitmap.CompressFormat.PNG, 100, o);
                return o.toByteArray();
            } };
        }
        spec.configJson = shell.toString();

        progress("تجهيز القالب…");
        File template = new File(getCacheDir(), "template.apk");
        copyAsset("template.apk", template);
        File unsigned = new File(getCacheDir(), "unsigned.apk");
        progress("بناء التطبيق…");
        ApkBuilder.build(template, unsigned, spec);
        progress(signer.hasKey() ? "توقيع التطبيق…" : "إنشاء مفتاح التوقيع (مرة واحدة، ممكن ياخد ثواني)…");
        File out = new File(getCacheDir(), "signed.apk");
        if (out.exists()) out.delete();
        signer.sign(unsigned, out);
        unsigned.delete();
        template.delete();
        lastApk = out;
        lastName = name.replaceAll("[^\\p{L}\\p{N}_ -]", "").trim();
        if (lastName.isEmpty()) lastName = "app";
        sp.edit().putInt("v_" + name, versionCode).apply();
        JSONObject res = new JSONObject();
        res.put("ok", true); res.put("package", pkg);
        if (spec.icons != null && spec.iconsReplaced == 0) res.put("warn", "الأيقونة ما اتبدّلتش (شكل القالب مختلف)"); res.put("size", out.length()); res.put("versionCode", versionCode);
        js("onBuilt", res.toString());
    }
}
