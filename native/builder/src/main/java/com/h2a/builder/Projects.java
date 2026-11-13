package com.h2a.builder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import com.h2a.builder.core.ApkBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * مشاريع المستخدم: كل مشروع فولدر في files/projects/<id>/ فيه:
 *   meta.json (الاسم والحزمة والإصدار والإعدادات)، index.html أو site.zip، icon.png (اختياري)، thumb.png، app.apk (آخر بناء).
 * معرّف الحزمة بيتولّد مرة واحدة لكل مشروع وما بيتغيّرش، فكل بناء جديد بيتثبّت فوق القديم.
 */
final class Projects {
    interface Progress { void on(String msg); }

    private static final Pattern ID = Pattern.compile("^[a-z0-9]{6,16}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z._-]{1,20}$");
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    static final long MAX_HTML = 4L * 1024 * 1024;

    private final Context ctx;
    private final Signer signer;
    private final File root;

    Projects(Context ctx, Signer signer) {
        this.ctx = ctx;
        this.signer = signer;
        root = new File(ctx.getFilesDir(), "projects");
        root.mkdirs();
    }

    File dir(String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) throw new IOException("معرّف المشروع غلط");
        return new File(root, id);
    }

    File apk(String id) throws IOException { return new File(dir(id), "app.apk"); }

    private static String rand(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(CHARS.charAt(r.nextInt(CHARS.length())));
        return sb.toString();
    }

    private JSONObject readMeta(String id) throws Exception {
        File f = new File(dir(id), "meta.json");
        if (!f.exists()) throw new IOException("المشروع مش موجود");
        return new JSONObject(new String(readAll(f), "UTF-8"));
    }

    private void writeMeta(String id, JSONObject m) throws Exception { writeAll(new File(dir(id), "meta.json"), m.toString().getBytes("UTF-8")); }

    /** قائمة المشاريع، الأحدث أولاً، مع صورة مصغّرة لكل مشروع. */
    String list() throws Exception {
        File[] dirs = root.listFiles();
        List<JSONObject> items = new ArrayList<JSONObject>();
        if (dirs != null) for (File d : dirs) {
            try {
                if (!ID.matcher(d.getName()).matches()) continue;
                JSONObject m = readMeta(d.getName());
                m.put("thumb", thumbData(d));
                m.put("hasApk", new File(d, "app.apk").exists());
                m.remove("settings");
                items.add(m);
            } catch (Exception e) { /* مشروع تالف: نتجاهله */ }
        }
        Collections.sort(items, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("updated"), a.optLong("updated")); }
        });
        JSONArray arr = new JSONArray();
        for (JSONObject o : items) arr.put(o);
        return arr.toString();
    }

    /** بيانات مشروع كاملة للتعديل (بما فيها كود الـ HTML). */
    JSONObject get(String id) throws Exception {
        File d = dir(id);
        JSONObject m = readMeta(id);
        File html = new File(d, "index.html");
        if (m.optString("source").equals("html") && html.exists()) m.put("code", new String(readAll(html), "UTF-8"));
        m.put("hasZip", new File(d, "site.zip").exists());
        m.put("hasIcon", new File(d, "icon.png").exists());
        m.put("thumb", thumbData(d));
        m.put("hasApk", new File(d, "app.apk").exists());
        return m;
    }

    private static String thumbData(File d) throws IOException {
        File t = new File(d, "thumb.png");
        if (!t.exists()) return "";
        return "data:image/png;base64," + Base64.encodeToString(readAll(t), Base64.NO_WRAP);
    }

    /**
     * إنشاء أو تحديث مشروع. cfg: id (اختياري)، appName، source، code، url، versionName، settings{…}، clearIcon.
     * pickedZip/pickedIcon: ملفات الاختيار الجديدة في الكاش (أو null لو ما اتغيّرتش).
     */
    JSONObject save(JSONObject cfg, File pickedZip, File pickedIcon) throws Exception {
        String name = cfg.optString("appName").trim();
        if (name.isEmpty() || name.length() > 40) throw new IOException("الاسم لازم من 1 إلى 40 حرف");
        String versionName = cfg.optString("versionName", "1.0").trim();
        if (!VERSION.matcher(versionName).matches()) throw new IOException("رقم الإصدار غلط (حروف وأرقام ونقط بس)");
        String source = cfg.optString("source", "html");
        if (!source.equals("html") && !source.equals("zip") && !source.equals("url")) throw new IOException("مصدر غلط");

        String id = cfg.optString("id", "");
        boolean isNew = id.isEmpty();
        if (isNew) id = rand(10);
        File d = dir(id);
        JSONObject m = isNew ? new JSONObject() : readMeta(id);
        long now = System.currentTimeMillis();
        if (isNew) {
            d.mkdirs();
            m.put("id", id);
            m.put("pkg", "com.h2a.a" + rand(12));
            m.put("versionCode", 0);
            m.put("created", now);
        }

        if (source.equals("html")) {
            String code = cfg.optString("code", "");
            if (code.trim().isEmpty()) throw new IOException("كود الـ HTML فاضي: اختار ملف أو الصق الكود");
            byte[] b = code.getBytes("UTF-8");
            if (b.length > MAX_HTML) throw new IOException("الـ HTML كبير (أكتر من 4 MB). استخدم ZIP.");
            writeAll(new File(d, "index.html"), b);
        } else if (source.equals("zip")) {
            if (pickedZip != null && pickedZip.exists()) copyFile(pickedZip, new File(d, "site.zip"));
            if (!new File(d, "site.zip").exists()) throw new IOException("اختار ملف ZIP");
        } else {
            String url = cfg.optString("url", "").trim();
            if (!url.startsWith("https://") || url.length() < 12) throw new IOException("الرابط لازم يبدأ بـ https://");
            m.put("url", url);
        }

        File icon = new File(d, "icon.png");
        if (cfg.optBoolean("clearIcon", false) && icon.exists()) icon.delete();
        if (pickedIcon != null && pickedIcon.exists()) {
            Bitmap src = BitmapFactory.decodeFile(pickedIcon.getAbsolutePath());
            if (src == null) throw new IOException("الأيقونة مش صورة صالحة (استخدم PNG أو JPG)");
            writeAll(icon, png(square(src, 512)));
        }
        // الصورة المصغّرة: الأيقونة المختارة أو حرف الاسم
        Bitmap base = icon.exists() ? BitmapFactory.decodeFile(icon.getAbsolutePath()) : null;
        if (base == null) base = letterIcon(name);
        writeAll(new File(d, "thumb.png"), png(Bitmap.createScaledBitmap(base, 96, 96, true)));

        JSONObject s = cfg.optJSONObject("settings");
        m.put("name", name);
        m.put("versionName", versionName);
        m.put("source", source);
        m.put("settings", s != null ? sanitize(s) : new JSONObject());
        m.put("updated", now);
        writeMeta(id, m);
        return m;
    }

    private static JSONObject sanitize(JSONObject s) throws Exception {
        JSONObject o = new JSONObject();
        String orient = s.optString("orientation", "auto");
        o.put("orientation", orient.equals("portrait") || orient.equals("landscape") ? orient : "auto");
        String bg = s.optString("backgroundColor", "#ffffff");
        o.put("backgroundColor", COLOR.matcher(bg).matches() ? bg : "#ffffff");
        String cache = s.optString("cacheMode", "default");
        o.put("cacheMode", cache.equals("noCache") || cache.equals("cacheElseNetwork") ? cache : "default");
        String[] flags = {"fullscreen", "keepScreenOn", "pullToRefresh", "desktopMode", "allowMixedContent"};
        for (String f : flags) o.put(f, s.optBoolean(f, false));
        o.put("downloads", s.optBoolean("downloads", true));
        o.put("bridge", s.optBoolean("bridge", true));
        JSONArray perms = new JSONArray();
        JSONArray in = s.optJSONArray("permissions");
        if (in != null) for (int i = 0; i < in.length(); i++) {
            String p = in.optString(i);
            if (p.equals("camera") || p.equals("microphone") || p.equals("location")) perms.put(p);
        }
        o.put("permissions", perms);
        return o;
    }

    String duplicate(String id) throws Exception {
        File src = dir(id);
        JSONObject m = readMeta(id);
        String nid = rand(10);
        File dst = dir(nid);
        dst.mkdirs();
        for (String f : new String[]{"index.html", "site.zip", "icon.png", "thumb.png"}) {
            File a = new File(src, f);
            if (a.exists()) copyFile(a, new File(dst, f));
        }
        m.put("id", nid);
        m.put("pkg", "com.h2a.a" + rand(12));
        m.put("name", (m.optString("name") + " (نسخة)").substring(0, Math.min(40, (m.optString("name") + " (نسخة)").length())));
        m.put("versionCode", 0);
        long now = System.currentTimeMillis();
        m.put("created", now);
        m.put("updated", now);
        m.remove("built");
        writeMeta(nid, m);
        return nid;
    }

    void delete(String id) throws Exception {
        File d = dir(id);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    /** بناء + توقيع المشروع. بيزوّد versionCode بس لو نجح. بيرجّع الـ APK في فولدر المشروع. */
    JSONObject build(String id, Progress p) throws Exception {
        File d = dir(id);
        JSONObject m = readMeta(id);
        JSONObject s = m.getJSONObject("settings");
        String name = m.getString("name");
        int versionCode = m.optInt("versionCode", 0) + 1;

        JSONObject shell = new JSONObject();
        shell.put("orientation", s.optString("orientation", "auto"));
        shell.put("fullscreen", s.optBoolean("fullscreen", false));
        shell.put("keepScreenOn", s.optBoolean("keepScreenOn", false));
        shell.put("pullToRefresh", s.optBoolean("pullToRefresh", false));
        shell.put("desktopMode", s.optBoolean("desktopMode", false));
        shell.put("allowMixedContent", s.optBoolean("allowMixedContent", false));
        shell.put("backgroundColor", s.optString("backgroundColor", "#ffffff"));
        shell.put("cacheMode", s.optString("cacheMode", "default"));
        shell.put("bridge", s.optBoolean("bridge", true));
        shell.put("downloads", s.optBoolean("downloads", true));
        shell.put("permissions", s.optJSONArray("permissions") != null ? s.getJSONArray("permissions") : new JSONArray());

        ApkBuilder.Spec spec = new ApkBuilder.Spec();
        spec.packageName = m.getString("pkg");
        spec.label = name;
        spec.versionName = m.optString("versionName", "1.0");
        spec.versionCode = versionCode;
        String source = m.optString("source", "html");
        if (source.equals("url")) {
            shell.put("url", m.getString("url"));
            spec.remote = true;
        } else if (source.equals("zip")) {
            spec.zip = new File(d, "site.zip");
            if (!spec.zip.exists()) throw new IOException("ملف الـ ZIP مش موجود، اختاره تاني");
        } else {
            spec.html = new File(d, "index.html");
            if (!spec.html.exists()) throw new IOException("ملف الـ HTML مش موجود");
        }
        File iconFile = new File(d, "icon.png");
        final Bitmap src = iconFile.exists() ? BitmapFactory.decodeFile(iconFile.getAbsolutePath()) : null;
        final Bitmap icon = src != null ? src : letterIcon(name);
        spec.icons = new ApkBuilder.IconProvider() {
            public byte[] png(int size) throws IOException { return Projects.png(Bitmap.createScaledBitmap(icon, size, size, true)); }
        };
        spec.configJson = shell.toString();

        p.on("تجهيز القالب…");
        File cache = ctx.getCacheDir();
        File template = new File(cache, "template.apk");
        copyStream(ctx.getAssets().open("template.apk"), new FileOutputStream(template));
        File unsigned = new File(cache, "unsigned.apk");
        File signed = new File(cache, "signed.apk");
        try {
            p.on("بناء التطبيق…");
            ApkBuilder.build(template, unsigned, spec);
            p.on(signer.hasKey() ? "توقيع التطبيق…" : "إنشاء مفتاح التوقيع (مرة واحدة، ممكن ياخد ثواني)…");
            if (signed.exists()) signed.delete();
            signer.sign(unsigned, signed);
            p.on("فحص الـ APK…");
            ApkBuilder.checkAligned(signed);
        } finally {
            unsigned.delete();
            template.delete();
        }
        File out = new File(d, "app.apk");
        if (out.exists()) out.delete();
        if (!signed.renameTo(out)) { copyFile(signed, out); signed.delete(); }

        m.put("versionCode", versionCode);
        m.put("built", System.currentTimeMillis());
        m.put("updated", System.currentTimeMillis());
        m.put("apkSize", out.length());
        writeMeta(id, m);
        JSONObject res = new JSONObject();
        res.put("ok", true);
        res.put("id", id);
        res.put("package", spec.packageName);
        res.put("versionCode", versionCode);
        res.put("size", out.length());
        if (spec.iconsReplaced == 0) res.put("warn", "الأيقونة ما اتبدّلتش (شكل القالب مختلف)");
        return res;
    }

    // ---------- صور ----------

    static Bitmap square(Bitmap src, int size) {
        int m = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - m) / 2, (src.getHeight() - m) / 2, m, m);
        return Bitmap.createScaledBitmap(sq, size, size, true);
    }

    static byte[] png(Bitmap b) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    /** أيقونة افتراضية: أول حرف من الاسم على خلفية ملوّنة. */
    static Bitmap letterIcon(String name) {
        int[] palette = {0xFF4F46E5, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFF8B5CF6, 0xFFEC4899, 0xFF14B8A6};
        int color = palette[(name.hashCode() & 0x7FFFFFFF) % palette.length];
        Bitmap b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        cv.drawRoundRect(new android.graphics.RectF(0, 0, 512, 512), 112, 112, p);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTextSize(300);
        p.setFakeBoldText(true);
        String t = name.trim();
        String letter = t.isEmpty() ? "A" : t.substring(0, t.offsetByCodePoints(0, 1)).toUpperCase();
        cv.drawText(letter, 256, 256 - (p.ascent() + p.descent()) / 2, p);
        return b;
    }

    // ---------- ملفات ----------

    static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    static void writeAll(File f, byte[] b) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try { o.write(b); } finally { o.close(); }
    }

    static void copyFile(File a, File b) throws IOException { copyStream(new FileInputStream(a), new FileOutputStream(b)); }

    static void copyStream(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { in.close(); out.close(); }
    }
}
