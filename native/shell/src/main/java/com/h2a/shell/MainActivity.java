package com.h2a.shell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.Gravity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** قشرة WebView عامة: كل إعداداتها بتتقرا من assets/config.json اللي التطبيق الباني بيحطه جوه الـ APK. */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 41, REQ_FILE = 42;
    private WebView web;
    private JSONObject cfg = new JSONObject();
    private AppBridge bridge;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPerm;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private boolean fullscreen;
    private ProgressBar bar;
    private TextView pullHint;
    private float pullStartY = -1, pullDy;
    private String lastUrl = "";
    private FilesBridge files;
    private FrameLayout rootView;
    private View splashView, customView;
    private WebChromeClient.CustomViewCallback customCb;
    private boolean confirmExit, externalBrowser;
    private String baseHost = "";
    private int normalUiFlags;

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            return o.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    private boolean has(JSONArray a, String v) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { String t = readAsset("config.json"); if (t != null) cfg = new JSONObject(t); } catch (Exception e) { /* إعدادات افتراضية */ }

        String orient = cfg.optString("orientation", "auto");
        if (orient.equals("portrait")) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else if (orient.equals("landscape")) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        int bg = Color.WHITE;
        try { bg = Color.parseColor(cfg.optString("backgroundColor", "#ffffff")); } catch (Exception e) { /* أبيض */ }
        fullscreen = cfg.optBoolean("fullscreen", false);
        if (cfg.optBoolean("keepScreenOn", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (fullscreen && Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        confirmExit = cfg.optBoolean("confirmExit", false);
        externalBrowser = cfg.optString("externalLinks", "app").equals("browser");
        applySystemBars();

        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (3 * getResources().getDisplayMetrics().density)));
        pullHint = new TextView(this);
        pullHint.setText("\u21BB");
        pullHint.setTextSize(22);
        pullHint.setTextColor(Color.WHITE);
        pullHint.setGravity(Gravity.CENTER);
        pullHint.setBackgroundColor(0xCC334155);
        pullHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams((int) (44 * getResources().getDisplayMetrics().density), (int) (44 * getResources().getDisplayMetrics().density));
        plp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        root.addView(pullHint, plp);
        if (cfg.optBoolean("splash", false)) addSplash(root, bg);
        setContentView(root);
        normalUiFlags = getWindow().getDecorView().getSystemUiVisibility();
        applyFullscreen();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setMixedContentMode(cfg.optBoolean("allowMixedContent", false) ? WebSettings.MIXED_CONTENT_ALWAYS_ALLOW : WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        String cache = cfg.optString("cacheMode", "default");
        if (cache.equals("noCache")) s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        else if (cache.equals("cacheElseNetwork")) s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        if (cfg.optBoolean("allowZoom", false) && !cfg.optBoolean("desktopMode", false)) {
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
        }
        if (cfg.optBoolean("desktopMode", false)) {
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
        }

        String url = cfg.optString("url", "");
        boolean remote = url.startsWith("https://");
        if (remote) { try { baseHost = String.valueOf(Uri.parse(url).getHost()); } catch (Exception e) { baseHost = ""; } }
        if (!remote) { // ملفات التطبيق نفسه: نسمح بـ fetch/XHR/modules على الملفات المحلية
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        }
        if (cfg.optBoolean("pullToRefresh", false)) setupPullToRefresh();
        web.addJavascriptInterface(new ShareBridge(this), "H2AShare"); // navigator.share لكل التطبيقات (نص/رابط/ملفات)
        boolean wantBridge = cfg.optBoolean("bridge", true) && !remote; // أمان: مفيش جسر لموقع خارجي
        boolean wantDownloads = cfg.optBoolean("downloads", true);
        if (!remote) { // استقبال الملفات + مجلد الحفظ + الطباعة
            files = new FilesBridge(this, web, cfg.optBoolean("saveFolder", false));
            web.addJavascriptInterface(files, "H2AFiles");
        }
        if (wantDownloads && !remote) web.addJavascriptInterface(new DownloadBridge(this, files), "H2ADownload"); // حفظ الملفات بأسمائها الحقيقية
        if (wantBridge || wantDownloads) {
            bridge = new AppBridge(this, web, wantBridge);
            if (wantBridge) { web.addJavascriptInterface(bridge, "AndroidAPI"); web.addJavascriptInterface(bridge, "AndoridAPI"); }
            if (wantDownloads) bridge.installDownloads();
        }

        web.setWebViewClient(new WebViewClient() {
            private void inject(WebView v) {
                v.evaluateJavascript(ShareBridge.POLYFILL, null);
                v.evaluateJavascript(DownloadBridge.POLYFILL, null);
                if (files != null) v.evaluateJavascript(FilesBridge.POLYFILL, null);
            }
            @Override public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) { inject(v); }
            @Override public void onPageFinished(WebView v, String u) {
                bar.setVisibility(View.GONE);
                inject(v);
                if (files != null) v.evaluateJavascript("window.__h2aPoll&&window.__h2aPoll(true)", null);
                hideSplash();
            }
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (files != null) { android.webkit.WebResourceResponse x = files.intercept(r.getUrl()); if (x != null) return x; }
                return super.shouldInterceptRequest(v, r);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) {
                    lastUrl = r.getUrl().toString();
                    String page = readAsset("h2a-error.html");
                    if (page == null) page = "<meta name=viewport content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;text-align:center;padding:48px'><h2>\u062A\u0639\u0630\u0651\u0631 \u0627\u0644\u0627\u062A\u0635\u0627\u0644</h2><button onclick='location.href=__URL__'>\u0625\u0639\u0627\u062F\u0629 \u0627\u0644\u0645\u062D\u0627\u0648\u0644\u0629</button></body>";
                    v.loadDataWithBaseURL(null, page.replace("__URL__", JSONObject.quote(lastUrl)), "text/html", "UTF-8", null);
                    hideSplash();
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                String sc = r.getUrl().getScheme();
                if (externalBrowser && r.isForMainFrame() && r.hasGesture() && (sc != null && (sc.equals("http") || sc.equals("https"))) && isExternal(r.getUrl())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception e) { /* مفيش متصفح */ }
                    return true;
                }
                if (sc == null || sc.equals("http") || sc.equals("https") || sc.equals("file") || sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception e) { /* مفيش تطبيق */ }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v; customCb = cb;
                rootView.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            @Override public void onHideCustomView() { leaveCustomView(); }
            @Override public void onProgressChanged(WebView v, int p) { bar.setProgress(p); bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE); }
            @Override public boolean onJsAlert(WebView v, String u, String m, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(m).setCancelable(false)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { r.confirm(); } }).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView v, String u, String m, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(m).setCancelable(false)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { r.confirm(); } })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { r.cancel(); } }).show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView v, String u, String m, String def, final JsPromptResult r) {
                final EditText et = new EditText(MainActivity.this);
                et.setText(def);
                new AlertDialog.Builder(MainActivity.this).setMessage(m).setView(et).setCancelable(false)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { r.confirm(et.getText().toString()); } })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { r.cancel(); } }).show();
                return true;
            }
            @Override public void onPermissionRequest(final PermissionRequest req) {
                List<String> need = new ArrayList<String>();
                for (String res : req.getResources()) {
                    if (res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) need.add(Manifest.permission.CAMERA);
                    else if (res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) need.add(Manifest.permission.RECORD_AUDIO);
                }
                runOnUiThread(new Runnable() { public void run() { handleWebPerm(req); } });
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { cb.invoke(origin, true, false); return; }
                pendingGeo = cb; pendingGeoOrigin = origin;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMS);
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });

        // صلاحيات وقت التشغيل المطلوبة في الإعدادات
        JSONArray perms = cfg.optJSONArray("permissions");
        List<String> ask = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 23) {
            if (has(perms, "camera")) ask.add(Manifest.permission.CAMERA);
            if (has(perms, "microphone")) ask.add(Manifest.permission.RECORD_AUDIO);
            if (has(perms, "location")) { ask.add(Manifest.permission.ACCESS_FINE_LOCATION); }
            List<String> missing = new ArrayList<String>();
            for (String p : ask) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
            if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }

        if (files != null) files.ingest(getIntent()); // التطبيق اتفتح بملف أو مشاركة
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(remote ? url : "file:///android_asset/www/index.html");
        String upd = cfg.optString("updateUrl", "");
        if (!upd.isEmpty()) Updater.check(this, upd, false);
    }

    /** ألوان شريط الحالة وأزرار النظام (اختياري). */
    private void applySystemBars() {
        String sb = cfg.optString("statusBarColor", ""), nb = cfg.optString("navBarColor", "");
        if (Build.VERSION.SDK_INT < 21 || (sb.isEmpty() && nb.isEmpty())) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        View decor = getWindow().getDecorView();
        int vis = decor.getSystemUiVisibility();
        try {
            if (!sb.isEmpty()) {
                int c = Color.parseColor(sb);
                getWindow().setStatusBarColor(c);
                if (Build.VERSION.SDK_INT >= 23 && luma(c) > 0.6) vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        } catch (Exception e) { /* لون غلط: نتجاهله */ }
        try {
            if (!nb.isEmpty()) {
                int c = Color.parseColor(nb);
                getWindow().setNavigationBarColor(c);
                if (Build.VERSION.SDK_INT >= 26 && luma(c) > 0.6) vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } catch (Exception e) { /* لون غلط: نتجاهله */ }
        decor.setSystemUiVisibility(vis);
    }

    private static double luma(int c) { return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0; }

    /** شاشة بداية: لون الخلفية + أيقونة التطبيق واسمه لحد ما الصفحة تحمّل. */
    private void addSplash(FrameLayout root, int bg) {
        int c = bg;
        try { String sc = cfg.optString("splashColor", ""); if (!sc.isEmpty()) c = Color.parseColor(sc); } catch (Exception e) { /* نفس لون الخلفية */ }
        FrameLayout sp = new FrameLayout(this);
        sp.setBackgroundColor(c);
        sp.setClickable(true);
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        try {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
            col.addView(iv, new android.widget.LinearLayout.LayoutParams((int) (96 * d), (int) (96 * d)));
        } catch (Exception e) { /* من غير أيقونة */ }
        TextView t = new TextView(this);
        try { t.setText(getPackageManager().getApplicationLabel(getApplicationInfo())); } catch (Exception e) { /* من غير اسم */ }
        t.setTextSize(20);
        t.setPadding(0, (int) (14 * d), 0, 0);
        t.setTextColor(luma(c) > 0.55 ? 0xFF1E1B4B : Color.WHITE);
        col.addView(t);
        sp.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(sp, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        splashView = sp;
        sp.postDelayed(new Runnable() { public void run() { hideSplash(); } }, 8000); // أمان: ما يفضلش للأبد
    }

    private void hideSplash() {
        final View sp = splashView;
        if (sp == null) return;
        splashView = null;
        sp.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() { public void run() { if (sp.getParent() instanceof ViewGroup) ((ViewGroup) sp.getParent()).removeView(sp); } }).start();
    }

    private boolean isExternal(Uri u) {
        String h = u.getHost();
        if (h == null) return false;
        if (baseHost.isEmpty()) return true; // تطبيق محلي: أي رابط http(s) بره التطبيق
        return !(h.equals(baseHost) || h.endsWith("." + baseHost) || baseHost.endsWith("." + h));
    }

    private void leaveCustomView() {
        if (customView == null) return;
        rootView.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customCb != null) { customCb.onCustomViewHidden(); customCb = null; }
        getWindow().getDecorView().setSystemUiVisibility(normalUiFlags);
        applyFullscreen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (files != null && files.ingest(intent) && web != null) web.evaluateJavascript("window.__h2aPoll&&window.__h2aPoll(false)", null);
    }

    /** سحب للتحديث: بس لما الصفحة في أولها. بيشتغل بدون ما يمنع أي لمس عادي. */
    private void setupPullToRefresh() {
        final float trigger = 110 * getResources().getDisplayMetrics().density;
        web.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: pullStartY = web.getScrollY() == 0 ? e.getY() : -1; pullDy = 0; break;
                    case MotionEvent.ACTION_MOVE:
                        if (pullStartY >= 0) {
                            pullDy = e.getY() - pullStartY;
                            if (web.getScrollY() != 0) { pullStartY = -1; pullHint.setVisibility(View.GONE); break; }
                            if (pullDy > 24) { pullHint.setVisibility(View.VISIBLE); pullHint.setTranslationY(Math.min(pullDy, trigger) * 0.6f); pullHint.setAlpha(Math.min(1f, pullDy / trigger)); }
                        }
                        break;
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                        if (pullStartY >= 0 && pullDy >= trigger && e.getActionMasked() == MotionEvent.ACTION_UP) web.reload();
                        pullStartY = -1; pullDy = 0; pullHint.setVisibility(View.GONE); break;
                }
                return false;
            }
        });
    }

    private void handleWebPerm(PermissionRequest req) {
        List<String> need = new ArrayList<String>();
        for (String res : req.getResources()) {
            if (res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) need.add(Manifest.permission.CAMERA);
            else if (res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) need.add(Manifest.permission.RECORD_AUDIO);
        }
        List<String> missing = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 23) for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) { req.grant(req.getResources()); return; }
        pendingWebPerm = req;
        requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        boolean all = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) all = false;
        if (pendingWebPerm != null) { if (all) pendingWebPerm.grant(pendingWebPerm.getResources()); else pendingWebPerm.deny(); pendingWebPerm = null; }
        if (pendingGeo != null) { pendingGeo.invoke(pendingGeoOrigin, all, false); pendingGeo = null; }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FilesBridge.REQ_TREE && files != null) { files.onTreeResult(res, data); return; }
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] r = res == RESULT_OK ? WebChromeClient.FileChooserParams.parseResult(res, data) : null;
            fileCallback.onReceiveValue(r);
            fileCallback = null;
        }
    }

    private void applyFullscreen() {
        if (!fullscreen) return;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bridge != null) bridge.stopSensor();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { leaveCustomView(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        if (confirmExit) {
            new AlertDialog.Builder(this).setMessage("تقفل التطبيق؟")
                .setPositiveButton("اقفل", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { MainActivity.super.onBackPressed(); } })
                .setNegativeButton("لا", null).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
