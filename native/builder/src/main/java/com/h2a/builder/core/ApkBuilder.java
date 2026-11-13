package com.h2a.builder.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** بيبني APK غير موقّع من القالب (template.apk): يبدّل الـ manifest والأيقونات ويضيف ملفات الويب وconfig.json. */
public final class ApkBuilder {
    public static final String PKG_PLACEHOLDER = "com.h2a.placeholder", LABEL_PLACEHOLDER = "__APP_LABEL__", VERSION_PLACEHOLDER = "0.0.0-h2a";
    private static final long MAX_UNPACKED = 150L * 1024 * 1024;
    private static final Pattern ICON = Pattern.compile("^res/mipmap[^/]*/ic_launcher\\.png$");
    private static final Pattern PKG = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    public interface IconProvider { byte[] png(int sizePx) throws IOException; }

    public static final class Spec {
        public String packageName, label, versionName, configJson;
        public int versionCode = 1;
        public File html;      // ملف HTML واحد (أو null)
        public File zip;       // ZIP موقع كامل (أو null)
        public boolean remote; // وضع رابط: مفيش ملفات ويب
        public IconProvider icons; // اختياري
        public int iconsReplaced;  // نتيجة: كام أيقونة اتبدّلت (0 يعني القالب مختلف عن المتوقع)
    }

    private ApkBuilder() { }

    public static void build(File template, File out, Spec spec) throws IOException {
        if (!PKG.matcher(spec.packageName).matches()) throw new IOException("اسم الحزمة غلط");
        if (spec.label == null || spec.label.trim().isEmpty() || spec.label.length() > 40) throw new IOException("الاسم غلط");
        if (!spec.remote && spec.html == null && spec.zip == null) throw new IOException("مفيش ملفات للتطبيق");

        Set<String> written = new HashSet<String>();
        ZipFile in = new ZipFile(template);
        ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out));
        try {
            Enumeration<? extends ZipEntry> en = in.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC") || name.equals("META-INF/MANIFEST.MF"))) continue;
                byte[] bytes = readAll(in.getInputStream(e));
                if (name.equals("AndroidManifest.xml")) {
                    Map<String, String> rep = new HashMap<String, String>();
                    rep.put(PKG_PLACEHOLDER, spec.packageName);
                    rep.put(LABEL_PLACEHOLDER, spec.label.trim());
                    rep.put(VERSION_PLACEHOLDER, spec.versionName);
                    bytes = AxmlPatcher.patch(bytes, rep, spec.versionCode);
                } else if (spec.icons != null && name.startsWith("res/") && name.endsWith(".png") && (isMarked(bytes) || ICON.matcher(name).matches())) {
                    int px = pngWidth(bytes);
                    bytes = spec.icons.png(px > 0 ? px : iconSize(name));
                    spec.iconsReplaced++;
                }
                put(zo, name, bytes, name.equals("resources.arsc") || name.endsWith(".png") || name.endsWith(".so"));
                written.add(name);
            }
            put(zo, "assets/config.json", spec.configJson.getBytes("UTF-8"), false);
            if (!spec.remote) {
                if (spec.html != null) put(zo, "assets/www/index.html", readAll(new java.io.FileInputStream(spec.html)), false);
                else addSite(zo, spec.zip);
            }
        } finally {
            zo.close();
            in.close();
        }
    }

    /** هل الصورة هي أيقونة القالب (فيها العلامة)؟ بتشتغل مهما كان مسار الملف. */
    static boolean isMarked(byte[] png) {
        byte[] m = "h2a-icon-marker".getBytes();
        outer:
        for (int i = 0; i + m.length <= png.length && i < 600; i++) {
            for (int j = 0; j < m.length; j++) if (png[i + j] != m[j]) continue outer;
            return true;
        }
        return false;
    }

    /** عرض الـ PNG من الهيدر (IHDR) أو 0 لو مش PNG. */
    static int pngWidth(byte[] p) {
        if (p.length < 24 || (p[0] & 0xFF) != 0x89 || p[1] != 'P') return 0;
        return ((p[16] & 0xFF) << 24) | ((p[17] & 0xFF) << 16) | ((p[18] & 0xFF) << 8) | (p[19] & 0xFF);
    }

    static int iconSize(String path) {
        if (path.contains("xxxhdpi")) return 192;
        if (path.contains("xxhdpi")) return 144;
        if (path.contains("xhdpi")) return 96;
        if (path.contains("hdpi")) return 72;
        if (path.contains("mdpi")) return 48;
        return 96;
    }

    /** بيضيف محتوى الـ ZIP تحت assets/www مع حماية: مسارات آمنة، index.html لازم يتواجد، وحد أقصى للحجم. */
    private static void addSite(ZipOutputStream zo, File zip) throws IOException {
        ZipFile z = new ZipFile(zip);
        try {
            // حدد فولدر الجذر لو كل حاجة جواه
            String prefix = "";
            boolean rootIndex = false;
            Set<String> tops = new HashSet<String>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = clean(e.getName());
                if (n == null || e.isDirectory() || n.startsWith("__MACOSX/")) continue;
                if (n.equals("index.html")) rootIndex = true;
                int sl = n.indexOf('/');
                tops.add(sl < 0 ? "" : n.substring(0, sl + 1));
            }
            if (!rootIndex) {
                if (tops.size() == 1 && !tops.contains("") && z.getEntry(tops.iterator().next() + "index.html") != null) prefix = tops.iterator().next();
                else throw new IOException("الـ ZIP لازم يحتوي index.html في الجذر (أو جوه فولدر واحد)");
            }
            long total = 0;
            Set<String> seen = new HashSet<String>();
            en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = clean(e.getName());
                if (e.isDirectory() || n == null || n.startsWith("__MACOSX/") || !n.startsWith(prefix)) continue;
                String rel = n.substring(prefix.length());
                if (rel.isEmpty() || rel.startsWith(".") || rel.contains("/.")) continue;
                byte[] b = readLimited(z.getInputStream(e), MAX_UNPACKED - total);
                total += b.length;
                if (!seen.add(rel)) continue;
                put(zo, "assets/www/" + rel, b, false);
            }
        } finally { z.close(); }
    }

    /** مسار آمن أو null: بيرفض .. والمسارات المطلقة والـ backslash. */
    static String clean(String name) throws IOException {
        if (name.indexOf('\\') >= 0 || name.startsWith("/") || name.indexOf('\0') >= 0) throw new IOException("مسار غير آمن في الـ ZIP: " + name);
        for (String part : name.split("/")) if (part.equals("..")) throw new IOException("مسار غير آمن في الـ ZIP: " + name);
        return name;
    }

    private static void put(ZipOutputStream zo, String name, byte[] bytes, boolean store) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        if (store) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(bytes.length);
            ze.setCompressedSize(bytes.length);
            ze.setCrc(crc.getValue());
        }
        zo.putNextEntry(ze);
        zo.write(bytes);
        zo.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException { return readLimited(in, Long.MAX_VALUE); }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) throw new IOException("الملفات أكبر من الحد المسموح");
                o.write(buf, 0, n);
            }
            return o.toByteArray();
        } finally { in.close(); }
    }
}
