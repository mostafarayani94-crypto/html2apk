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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
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
        boolean wantBridge = cfg.optBoolean("bridge", true) && !remote; // أمان: مفيش جسر لموقع خارجي
        boolean wantDownloads = cfg.optBoolean("downloads", true);
        if (wantBridge || wantDownloads) {
            bridge = new AppBridge(this, web, wantBridge);
            if (wantBridge) { web.addJavascriptInterface(bridge, "AndroidAPI"); web.addJavascriptInterface(bridge, "AndoridAPI"); }
            if (wantDownloads) bridge.installDownloads();
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                String sc = r.getUrl().getScheme();
                if (sc == null || sc.equals("http") || sc.equals("https") || sc.equals("file") || sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception e) { /* مفيش تطبيق */ }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
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

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(remote ? url : "file:///android_asset/www/index.html");
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
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
