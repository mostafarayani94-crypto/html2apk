package com.h2a.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * تحديث تلقائي من رابط: التطبيق بيقرا ملف JSON على https اللي إنت بتحطه:
 *   {"versionCode": 7, "versionName": "1.6", "apk": "https://.../app.apk", "notes": "إيه الجديد"}
 * لو versionCode أكبر من اللي مثبّت، بيعرض رسالة وينزّل الـ APK ويفتح شاشة التثبيت.
 * أندرويد بيقبل التحديث بس لو الـ APK متوقّع بنفس مفتاح التوقيع (يعني من نفس الباني على نفس الجهاز).
 */
final class Updater {
    private Updater() { }

    static void check(final Activity act, final String url, final boolean manual) {
        if (url == null || !url.startsWith("https://")) return;
        final SharedPreferences sp = act.getSharedPreferences("h2a_update", Context.MODE_PRIVATE);
        if (!manual && System.currentTimeMillis() - sp.getLong("last", 0) < 6L * 3600 * 1000) return;
        new Thread(new Runnable() { public void run() {
            try {
                JSONObject j = new JSONObject(new String(fetch(url, 200 * 1024), "UTF-8"));
                sp.edit().putLong("last", System.currentTimeMillis()).apply();
                final long remote = j.optLong("versionCode", 0);
                final String apk = j.optString("apk", "");
                final String name = j.optString("versionName", String.valueOf(remote));
                final String notes = j.optString("notes", "");
                if (remote <= current(act) || !apk.startsWith("https://")) { if (manual) toast(act, "أنت على آخر إصدار"); return; }
                if (!manual && sp.getLong("skip", -1) == remote) return;
                act.runOnUiThread(new Runnable() { public void run() {
                    new AlertDialog.Builder(act).setTitle("تحديث جديد: " + name).setMessage(notes.isEmpty() ? "في نسخة جديدة من التطبيق." : notes)
                        .setPositiveButton("حدّث الآن", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { download(act, apk); } })
                        .setNegativeButton("لاحقاً", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { sp.edit().putLong("skip", remote).apply(); } }).show();
                } });
            } catch (Throwable e) { if (manual) toast(act, "تعذّر فحص التحديثات"); }
        } }).start();
    }

    private static long current(Context c) throws Exception {
        PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static void toast(final Activity a, final String m) {
        a.runOnUiThread(new Runnable() { public void run() { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); } });
    }

    private static byte[] fetch(String u, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n, t = 0;
            while ((n = in.read(b)) > 0) { t += n; if (t > max) throw new Exception("too big"); o.write(b, 0, n); }
            return o.toByteArray();
        } finally { c.disconnect(); }
    }

    private static void download(final Activity act, final String apkUrl) {
        final android.widget.ProgressBar pb = new android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        final AlertDialog dlg = new AlertDialog.Builder(act).setTitle("جاري تنزيل التحديث…").setView(pb).setCancelable(false).create();
        dlg.show();
        new Thread(new Runnable() { public void run() {
            try {
                File dir = new File(ShareProvider.root(act), "update");
                FilesBridge.deleteTree(dir);
                dir.mkdirs();
                File f = new File(dir, "update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                final long total = c.getContentLengthLong();
                if (total > 300L * 1024 * 1024) throw new Exception("too big");
                InputStream in = c.getInputStream();
                FileOutputStream o = new FileOutputStream(f);
                byte[] b = new byte[32768]; int n; long got = 0; int shown = -1;
                try {
                    while ((n = in.read(b)) > 0) {
                        o.write(b, 0, n); got += n;
                        if (got > 300L * 1024 * 1024) throw new Exception("too big");
                        final int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        if (pct != shown) { shown = pct; act.runOnUiThread(new Runnable() { public void run() { pb.setProgress(pct); } }); }
                    }
                } finally { in.close(); o.close(); c.disconnect(); }
                act.runOnUiThread(new Runnable() { public void run() { dlg.dismiss(); install(act); } });
            } catch (Throwable e) {
                act.runOnUiThread(new Runnable() { public void run() { dlg.dismiss(); Toast.makeText(act, "تعذّر تنزيل التحديث", Toast.LENGTH_LONG).show(); } });
            }
        } }).start();
    }

    static void install(Activity act) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(act, "اسمح للتطبيق بالتثبيت ثم ارجع ودوس تحديث تاني", Toast.LENGTH_LONG).show();
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
                return;
            }
            Uri u = Uri.parse("content://" + ShareProvider.authority(act) + "/update/update.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable e) { Toast.makeText(act, "تعذّر فتح شاشة التثبيت", Toast.LENGTH_LONG).show(); }
    }
}
