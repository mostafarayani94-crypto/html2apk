package com.h2a.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;

/** جسر بين JavaScript والجهاز: window.AndroidAPI (واسم بديل AndoridAPI زي الأداة الأصلية). */
public class AppBridge implements SensorEventListener {
    private final Activity act;
    private final WebView web;
    private final boolean exposed;
    private SensorManager sensors;
    private long lastSensorMs = 0;
    public Runnable onShowRewarded;
    public Runnable onShowInterstitial;

    public AppBridge(Activity act, WebView web, boolean exposed) {
        this.act = act;
        this.web = web;
        this.exposed = exposed;
    }

    private void toastUi(final String msg) {
        act.runOnUiThread(new Runnable() { public void run() { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show(); } });
    }

    /* ---------- واجهة JS ---------- */
    @JavascriptInterface
    public void toast(String msg) { toastUi(msg == null ? "" : msg); }

    @JavascriptInterface
    public void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            ms = Math.max(1, Math.min(ms, 5000));
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Exception e) { /* الجهاز من غير اهتزاز */ }
    }

    @JavascriptInterface
    public void flashlight(boolean on) {
        try {
            CameraManager cm = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) { cm.setTorchMode(id, on); return; }
            }
            toastUi("No flashlight");
        } catch (Exception e) { toastUi("Flashlight error"); }
    }

    @JavascriptInterface
    public void alert(final String title, final String message) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                new AlertDialog.Builder(act).setTitle(title == null ? "" : title).setMessage(message == null ? "" : message).setPositiveButton(android.R.string.ok, null).show();
            }
        });
    }

    @JavascriptInterface
    public void share(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
        act.startActivity(Intent.createChooser(i, null));
    }

    @JavascriptInterface
    public void openUrl(String url) {
        try {
            Uri u = Uri.parse(url);
            String s = u.getScheme();
            if (s == null || !(s.equals("http") || s.equals("https") || s.equals("mailto") || s.equals("tel"))) return;
            act.startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) { /* مفيش تطبيق يفتحه */ }
    }

    @JavascriptInterface
    public void copy(final String text) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("text", text == null ? "" : text));
                toastUi("Copied");
            }
        });
    }

    @JavascriptInterface
    public String deviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("model", Build.MODEL);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("androidVersion", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("package", act.getPackageName());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public int batteryLevel() {
        try {
            BatteryManager bm = (BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) { return -1; }
    }

    @JavascriptInterface
    public void keepScreenOn(final boolean on) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                if (on) act.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else act.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    @JavascriptInterface
    public void exitApp() { act.runOnUiThread(new Runnable() { public void run() { act.finishAffinity(); } }); }

    /** حفظ ملف في التنزيلات. المحتوى base64. */
    @JavascriptInterface
    public void saveFile(String name, String mime, String base64) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            toastUi("Saved: " + saveBytes(name, mime, data));
        } catch (Exception e) { toastUi("Save failed"); }
    }

    /** بيستقبل data URL (بيستخدمه مدير التنزيلات لروابط blob). */
    @JavascriptInterface
    public void saveDataUrl(String name, String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (!dataUrl.startsWith("data:") || comma < 0) throw new IOException("bad data url");
            String meta = dataUrl.substring(5, comma);
            String payload = dataUrl.substring(comma + 1);
            String mime = meta.split(";")[0];
            if (mime.isEmpty()) mime = "application/octet-stream";
            byte[] data = meta.contains(";base64") ? Base64.decode(payload, Base64.DEFAULT) : URLDecoder.decode(payload, "UTF-8").getBytes("UTF-8");
            toastUi("Saved: " + saveBytes(name, mime, data));
        } catch (Exception e) { toastUi("Save failed"); }
    }

    @JavascriptInterface
    public void showRewarded() { if (onShowRewarded != null) act.runOnUiThread(onShowRewarded); }

    @JavascriptInterface
    public void showInterstitial() { if (onShowInterstitial != null) act.runOnUiThread(onShowInterstitial); }

    /* ---------- الحساسات: window.onAndroidSensor({x,y,z}) ---------- */
    @JavascriptInterface
    public void startSensor() {
        if (sensors != null) return;
        sensors = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (s == null) { sensors = null; toastUi("No accelerometer"); return; }
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
    }

    @JavascriptInterface
    public void stopSensor() {
        if (sensors != null) { sensors.unregisterListener(this); sensors = null; }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = System.currentTimeMillis();
        if (now - lastSensorMs < 100) return;
        lastSensorMs = now;
        final String js = "window.onAndroidSensor&&window.onAndroidSensor({x:" + e.values[0] + ",y:" + e.values[1] + ",z:" + e.values[2] + "})";
        act.runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript(js, null); } });
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /* ---------- التنزيلات ---------- */
    public void installDownloads() {
        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long length) {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (url.startsWith("blob:")) {
                    if (!exposed) { toastUi("Download not supported"); return; }
                    final String js = "(function(){fetch(" + JSONObject.quote(url) + ").then(function(r){return r.blob();}).then(function(b){var f=new FileReader();f.onloadend=function(){AndroidAPI.saveDataUrl(" + JSONObject.quote(name) + ",f.result);};f.readAsDataURL(b);});})();";
                    act.runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript(js, null); } });
                } else if (url.startsWith("data:")) {
                    saveDataUrl(name, url);
                } else if (url.startsWith("http://") || url.startsWith("https://")) {
                    try {
                        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                        if (mimeType != null) req.setMimeType(mimeType);
                        if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if (cookie != null) req.addRequestHeader("Cookie", cookie);
                        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                        ((DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                        toastUi("Downloading: " + name);
                    } catch (Exception e) { toastUi("Download failed"); }
                }
            }
        });
    }

    private String saveBytes(String name, String mime, byte[] data) throws IOException {
        name = (name == null ? "" : name).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) name = "download";
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
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
            return "Downloads/" + name;
        }
        File dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IOException("no storage");
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
        return f.getAbsolutePath();
    }
}
