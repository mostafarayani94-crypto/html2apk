/* مولّد ملفات Java (MainActivity / AppBridge / AdsHelper). كل ميزة بتتولّد بس لو متفعّلة، فالبناء الافتراضي يفضل بسيط. */
'use strict';
const q = s => JSON.stringify(String(s)); // literal بين علامات تنصيص يصلح لـ Java

function mainActivity(c) {
  const hasBridge = c.bridge, ads = c.ads && c.ads.enabled, usesBridge = c.bridge || c.downloads;
  const cut = c.fullscreen ? `
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }` : '';
  const body = [];
  body.push('        final WebView web = getBridge().getWebView();');
  if (c.keepScreenOn) body.push('        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);');
  if (c.fullscreen) body.push('        hideBars();');
  if (c.cacheMode !== 'default') body.push(`        web.getSettings().setCacheMode(WebSettings.${{ noCache: 'LOAD_NO_CACHE', cacheElseNetwork: 'LOAD_CACHE_ELSE_NETWORK' }[c.cacheMode]});`);
  if (c.desktopMode) body.push('        try { WebSettings s = web.getSettings(); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true); s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false); } catch (Exception e) { }');
  if (hasBridge || c.downloads) {
    body.push(`        appBridge = new AppBridge(this, web, ${hasBridge ? 'true' : 'false'});`);
    if (hasBridge) { body.push('        web.addJavascriptInterface(appBridge, "AndroidAPI");', '        web.addJavascriptInterface(appBridge, "AndoridAPI");'); }
    if (c.downloads) body.push('        appBridge.installDownloads();');
  }
  if (ads) {
    body.push('        adsHelper = new AdsHelper(this, getBridge());', '        adsHelper.start();');
    if (hasBridge) body.push('        appBridge.onShowRewarded = new Runnable() { public void run() { adsHelper.showRewarded(); } };', '        appBridge.onShowInterstitial = new Runnable() { public void run() { adsHelper.showInterstitial(); } };');
  }
  if (c.pullToRefresh) body.push('        installPullToRefresh(web);');

  return `package ${c.appId};

import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
${c.pullToRefresh ? 'import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;\n' : ''}import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    ${usesBridge ? 'private AppBridge appBridge;' : ''}
${ads ? '    private AdsHelper adsHelper;\n' : ''}
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);${cut}
        try {
${body.join('\n')}
        } catch (Exception e) {
            android.util.Log.e("html2apk", "setup failed", e);
        }
    }
${c.pullToRefresh ? `
    private void installPullToRefresh(final WebView web) {
        ViewGroup parent = (ViewGroup) web.getParent();
        int index = parent.indexOfChild(web);
        ViewGroup.LayoutParams lp = web.getLayoutParams();
        parent.removeView(web);
        final SwipeRefreshLayout refresh = new SwipeRefreshLayout(this);
        refresh.addView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(refresh, index, lp);
        refresh.setOnChildScrollUpCallback(new SwipeRefreshLayout.OnChildScrollUpCallback() {
            @Override public boolean canChildScrollUp(SwipeRefreshLayout p, android.view.View child) { return web.getScrollY() > 0; }
        });
        refresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override public void onRefresh() { web.reload(); refresh.setRefreshing(false); }
        });
    }
` : ''}
    private void hideBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ctl = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ctl.hide(WindowInsetsCompat.Type.systemBars());
        ctl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
${c.fullscreen ? '        if (hasFocus) hideBars();' : '        // no-op'}
    }

    @Override
    public void onPause() {
        super.onPause();
        ${usesBridge ? 'if (appBridge != null) appBridge.stopSensor();' : ''}
    }

    @Override
    public void onDestroy() {
${ads ? '        if (adsHelper != null) adsHelper.destroy();\n' : ''}        super.onDestroy();
    }
}
`;
}

function appBridge(c) {
  return `package ${c.appId};

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
        name = (name == null ? "" : name).replaceAll("[\\\\\\\\/:*?\\"<>|]", "_").trim();
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
`;
}

function adsHelper(c) {
  const a = c.ads;
  const banner = a.bannerPosition !== 'none';
  return `package ${c.appId};

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import com.getcapacitor.Bridge;
import com.getcapacitor.WebViewListener;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.rewarded.RewardItem;

/** إعلانات AdMob: بانر ثابت، إعلان بيني (مؤقت أو عدد صفحات)، وفيديو بمكافأة. */
public class AdsHelper {
    private static final String BANNER_ID = ${q(a.bannerId)};
    private static final String INTERSTITIAL_ID = ${q(a.interstitialId)};
    private static final String REWARDED_ID = ${q(a.rewardedId)};
    private static final String INTERSTITIAL_MODE = ${q(a.interstitialMode)};
    private static final int EVERY = ${a.interstitialEvery > 0 ? a.interstitialEvery : 3};

    private final Activity act;
    private final Bridge bridge;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AdView banner;
    private InterstitialAd interstitial;
    private RewardedAd rewarded;
    private int pages = 0;
    private boolean destroyed = false;
    private Runnable tick;

    public AdsHelper(Activity act, Bridge bridge) {
        this.act = act;
        this.bridge = bridge;
    }

    public void start() {
        MobileAds.initialize(act);
${banner ? `        banner = new AdView(act);
        banner.setAdSize(AdSize.BANNER);
        banner.setAdUnitId(BANNER_ID);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.${a.bannerPosition === 'top' ? 'TOP' : 'BOTTOM'});
        act.addContentView(banner, lp);
        banner.loadAd(new AdRequest.Builder().build());
` : ''}        if (!INTERSTITIAL_MODE.equals("none")) loadInterstitial();
        if (!REWARDED_ID.isEmpty()) loadRewarded();
        if (INTERSTITIAL_MODE.equals("timer")) {
            tick = new Runnable() {
                public void run() {
                    if (destroyed) return;
                    showInterstitial();
                    handler.postDelayed(this, EVERY * 1000L);
                }
            };
            handler.postDelayed(tick, EVERY * 1000L);
        } else if (INTERSTITIAL_MODE.equals("pages")) {
            bridge.addWebViewListener(new WebViewListener() {
                @Override
                public void onPageLoaded(WebView webView) {
                    pages++;
                    if (pages % EVERY == 0) act.runOnUiThread(new Runnable() { public void run() { showInterstitial(); } });
                }
            });
        }
    }

    private void loadInterstitial() {
        if (INTERSTITIAL_ID.isEmpty() || destroyed) return;
        InterstitialAd.load(act, INTERSTITIAL_ID, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
            @Override public void onAdLoaded(@NonNull InterstitialAd ad) { interstitial = ad; }
            @Override public void onAdFailedToLoad(@NonNull LoadAdError error) { interstitial = null; }
        });
    }

    public void showInterstitial() {
        if (interstitial == null || destroyed) { loadInterstitial(); return; }
        InterstitialAd ad = interstitial;
        interstitial = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() { loadInterstitial(); }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError error) { loadInterstitial(); }
        });
        ad.show(act);
    }

    private void loadRewarded() {
        if (REWARDED_ID.isEmpty() || destroyed) return;
        RewardedAd.load(act, REWARDED_ID, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override public void onAdLoaded(@NonNull RewardedAd ad) { rewarded = ad; }
            @Override public void onAdFailedToLoad(@NonNull LoadAdError error) { rewarded = null; }
        });
    }

    /** بيستدعي window.onRewarded(amount) في الصفحة لما المستخدم يكمل الفيديو. */
    public void showRewarded() {
        if (rewarded == null || destroyed) { loadRewarded(); return; }
        RewardedAd ad = rewarded;
        rewarded = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() { loadRewarded(); }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError error) { loadRewarded(); }
        });
        ad.show(act, new OnUserEarnedRewardListener() {
            @Override
            public void onUserEarnedReward(@NonNull RewardItem item) {
                final String js = "window.onRewarded&&window.onRewarded(" + item.getAmount() + ")";
                act.runOnUiThread(new Runnable() { public void run() { bridge.getWebView().evaluateJavascript(js, null); } });
            }
        });
    }

    public void destroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (banner != null) banner.destroy();
    }
}
`;
}

module.exports = { mainActivity, appBridge, adsHelper };
