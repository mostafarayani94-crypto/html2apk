import com.h2a.builder.core.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

public class CoreTest {
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    /* ---- بناء AXML صناعي (بنفس تركيب aapt2): pool + resource map + عنصر manifest فيه package/versionCode/versionName + عنصر application فيه label ---- */
    static byte[] axml(boolean utf8, String[] strs) throws IOException {
        // الفهارس: 0 manifest, 1 package, 2 versionCode, 3 versionName, 4 application, 5 label, 6.. قيم
        ByteArrayOutputStream pool = new ByteArrayOutputStream();
        int[] off = new int[strs.length];
        for (int i = 0; i < strs.length; i++) {
            off[i] = pool.size();
            String s = strs[i];
            if (utf8) {
                byte[] b = s.getBytes("UTF-8");
                pool.write(s.length()); pool.write(b.length); pool.write(b); pool.write(0);
            } else {
                byte[] b = s.getBytes("UTF-16LE");
                pool.write(s.length() & 0xFF); pool.write(s.length() >> 8); pool.write(b); pool.write(0); pool.write(0);
            }
        }
        while (pool.size() % 4 != 0) pool.write(0);
        int hdr = 28, stringsStart = hdr + 4 * strs.length, psize = stringsStart + pool.size();
        ByteBuffer p = ByteBuffer.allocate(psize).order(ByteOrder.LITTLE_ENDIAN);
        p.putShort((short) 1).putShort((short) hdr).putInt(psize).putInt(strs.length).putInt(0).putInt(utf8 ? 0x100 : 0).putInt(stringsStart).putInt(0);
        for (int o : off) p.putInt(o);
        p.put(pool.toByteArray());
        // resource map chunk (0x0180): resource id لكل attr name
        ByteBuffer rm = ByteBuffer.allocate(8 + 4 * 6).order(ByteOrder.LITTLE_ENDIAN);
        rm.putShort((short) 0x0180).putShort((short) 8).putInt(8 + 24);
        for (int i = 0; i < 6; i++) rm.putInt(0x01010000 + i);
        // start element manifest: 3 attrs
        ByteBuffer m = ByteBuffer.allocate(16 + 20 + 3 * 20).order(ByteOrder.LITTLE_ENDIAN);
        m.putShort((short) 0x0102).putShort((short) 16).putInt(16 + 20 + 60).putInt(1).putInt(-1);
        m.putInt(-1).putInt(0).putShort((short) 20).putShort((short) 20).putShort((short) 3).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        // package (string)
        m.putInt(-1).putInt(1).putInt(6).putShort((short) 8).put((byte) 0).put((byte) 3).putInt(6);
        // versionCode (int)
        m.putInt(-1).putInt(2).putInt(-1).putShort((short) 8).put((byte) 0).put((byte) 0x10).putInt(1);
        // versionName (string)
        m.putInt(-1).putInt(3).putInt(7).putShort((short) 8).put((byte) 0).put((byte) 3).putInt(7);
        // application element: label
        ByteBuffer a = ByteBuffer.allocate(16 + 20 + 20).order(ByteOrder.LITTLE_ENDIAN);
        a.putShort((short) 0x0102).putShort((short) 16).putInt(16 + 20 + 20).putInt(2).putInt(-1);
        a.putInt(-1).putInt(4).putShort((short) 20).putShort((short) 20).putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        a.putInt(-1).putInt(5).putInt(8).putShort((short) 8).put((byte) 0).put((byte) 3).putInt(8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(p.array()); body.write(rm.array()); body.write(m.array()); body.write(a.array());
        ByteBuffer h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        h.putShort((short) 3).putShort((short) 8).putInt(8 + body.size());
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(h.array()); all.write(body.toByteArray());
        return all.toByteArray();
    }

    static int versionCodeOf(byte[] x) {
        ByteBuffer b = ByteBuffer.wrap(x).order(ByteOrder.LITTLE_ENDIAN);
        int off = 8; off += b.getInt(off + 4);           // بعد الـ pool
        off += b.getInt(off + 4);                        // بعد الـ resource map
        int ext = off + 16;                              // manifest
        int a = ext + 20 + 20;                           // attr #2 = versionCode
        check(b.get(a + 15) == 0x10, "نوع versionCode");
        return b.getInt(a + 16);
    }

    static File zipOf(File f, Map<String, byte[]> files) throws IOException {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        for (Map.Entry<String, byte[]> e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); }
        z.close();
        return f;
    }

    static Map<String, byte[]> readZip(File f) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        ZipFile z = new ZipFile(f);
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            InputStream in = z.getInputStream(ze); ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            m.put(ze.getName(), o.toByteArray());
        }
        z.close();
        return m;
    }

    public static void main(String[] args) throws Exception {
        String[] strs = {"manifest", "package", "versionCode", "versionName", "application", "label", ApkBuilder.PKG_PLACEHOLDER, ApkBuilder.VERSION_PLACEHOLDER, ApkBuilder.LABEL_PLACEHOLDER, "android.permission.INTERNET"};
        for (boolean utf8 : new boolean[]{false, true}) {
            byte[] src = axml(utf8, strs);
            check(AxmlPatcher.readStrings(src).equals(Arrays.asList(strs)), "قراءة الـ pool " + utf8);
            Map<String, String> rep = new HashMap<String, String>();
            rep.put(ApkBuilder.PKG_PLACEHOLDER, "com.h2a.myapp123");
            rep.put(ApkBuilder.LABEL_PLACEHOLDER, "حاسبتي الجميلة جداً ✓");   // أطول بكتير + عربي + رموز
            rep.put(ApkBuilder.VERSION_PLACEHOLDER, "2.5.1");
            byte[] out = AxmlPatcher.patch(src, rep, 4242);
            List<String> got = AxmlPatcher.readStrings(out);
            check(got.get(6).equals("com.h2a.myapp123") && got.get(7).equals("2.5.1") && got.get(8).equals("حاسبتي الجميلة جداً ✓") && got.get(9).equals("android.permission.INTERNET") && got.get(0).equals("manifest"), "النصوص بعد التعديل " + utf8 + got);
            check(versionCodeOf(out) == 4242, "versionCode " + utf8);
            check(ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == out.length, "حجم الملف في الهيدر");
            // ما نكسرش الباقي: التعديل مرتين بيشتغل
            byte[] again = AxmlPatcher.patch(out, Collections.singletonMap("2.5.1", "3.0"), 7);
            check(AxmlPatcher.readStrings(again).get(7).equals("3.0") && versionCodeOf(again) == 7, "تعديل ثاني");
            // نص أقصر
            byte[] shorter = AxmlPatcher.patch(src, Collections.singletonMap(ApkBuilder.LABEL_PLACEHOLDER, "x"), null);
            check(AxmlPatcher.readStrings(shorter).get(8).equals("x"), "نص أقصر");
        }
        try { AxmlPatcher.patch(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new HashMap<String, String>(), null); check(false, "لازم يرفض"); } catch (IllegalArgumentException ok) { }

        /* ---- ApkBuilder ---- */
        File dir = new File(System.getProperty("java.io.tmpdir"), "h2a-test-" + System.nanoTime()); dir.mkdirs();
        Map<String, byte[]> tpl = new LinkedHashMap<String, byte[]>();
        tpl.put("AndroidManifest.xml", axml(false, strs));
        tpl.put("classes.dex", new byte[]{1, 2, 3});
        tpl.put("resources.arsc", new byte[]{9, 9, 9, 9});
        tpl.put("res/mipmap-hdpi-v4/ic_launcher.png", new byte[]{7});
        tpl.put("res/mipmap-xxxhdpi-v4/ic_launcher.png", new byte[]{7});
        tpl.put("META-INF/CERT.SF", new byte[]{1}); tpl.put("META-INF/CERT.RSA", new byte[]{1}); tpl.put("META-INF/MANIFEST.MF", new byte[]{1}); tpl.put("META-INF/com/android/build/gradle/app-metadata.properties", new byte[]{1});
        File template = zipOf(new File(dir, "template.apk"), tpl);

        File html = new File(dir, "a.html"); try (FileOutputStream f = new FileOutputStream(html)) { f.write("<h1>مرحبا</h1>".getBytes("UTF-8")); }
        ApkBuilder.Spec sp = new ApkBuilder.Spec();
        sp.packageName = "com.h2a.app1"; sp.label = "تطبيقي"; sp.versionName = "1.2"; sp.versionCode = 5; sp.configJson = "{\"orientation\":\"auto\"}"; sp.html = html;
        final List<Integer> sizes = new ArrayList<Integer>();
        sp.icons = new ApkBuilder.IconProvider() { public byte[] png(int s) { sizes.add(s); return new byte[]{(byte) s}; } };
        File out = new File(dir, "out.apk");
        ApkBuilder.build(template, out, sp);
        Map<String, byte[]> r = readZip(out);
        check(r.containsKey("classes.dex") && r.containsKey("resources.arsc") && r.containsKey("assets/config.json"), "ملفات القالب");
        check(!r.containsKey("META-INF/CERT.SF") && !r.containsKey("META-INF/CERT.RSA") && !r.containsKey("META-INF/MANIFEST.MF"), "حذف التوقيعات القديمة");
        check(r.containsKey("META-INF/com/android/build/gradle/app-metadata.properties"), "باقي META-INF");
        check(new String(r.get("assets/www/index.html"), "UTF-8").equals("<h1>مرحبا</h1>"), "HTML");
        check(AxmlPatcher.readStrings(r.get("AndroidManifest.xml")).contains("com.h2a.app1") && versionCodeOf(r.get("AndroidManifest.xml")) == 5, "manifest");
        check(r.get("res/mipmap-hdpi-v4/ic_launcher.png")[0] == 72 && r.get("res/mipmap-xxxhdpi-v4/ic_launcher.png")[0] == (byte) 192, "أحجام الأيقونات " + sizes);
        ZipFile zf = new ZipFile(out); check(zf.getEntry("resources.arsc").getMethod() == ZipEntry.STORED, "resources.arsc غير مضغوط"); zf.close();

        // ZIP: مجلد واحد في الجذر + حماية
        Map<String, byte[]> site = new LinkedHashMap<String, byte[]>();
        site.put("mysite/index.html", "<p>x</p>".getBytes()); site.put("mysite/js/app.js", "1".getBytes()); site.put("mysite/img/a.png", new byte[]{1, 2}); site.put("__MACOSX/mysite/._index.html", new byte[]{0}); site.put("mysite/.DS_Store", new byte[]{0});
        sp = new ApkBuilder.Spec(); sp.packageName = "com.h2a.app2"; sp.label = "x"; sp.versionName = "1"; sp.configJson = "{}"; sp.zip = zipOf(new File(dir, "site.zip"), site);
        ApkBuilder.build(template, out, sp);
        r = readZip(out);
        check(r.containsKey("assets/www/index.html") && r.containsKey("assets/www/js/app.js") && r.containsKey("assets/www/img/a.png"), "ZIP اتفرد");
        check(!r.containsKey("assets/www/.DS_Store") && !r.keySet().toString().contains("MACOSX"), "تجاهل ملفات النظام");
        site.clear(); site.put("readme.txt", new byte[]{1}); sp.zip = zipOf(new File(dir, "bad.zip"), site);
        try { ApkBuilder.build(template, out, sp); check(false, "لازم يرفض من غير index"); } catch (IOException ok) { check(ok.getMessage().contains("index.html"), ok.getMessage()); }
        site.clear(); site.put("index.html", new byte[]{1}); site.put("../evil.txt", new byte[]{1}); sp.zip = zipOf(new File(dir, "evil.zip"), site);
        try { ApkBuilder.build(template, out, sp); check(false, "لازم يرفض مسار .."); } catch (IOException ok) { check(ok.getMessage().contains("غير آمن"), ok.getMessage()); }
        // وضع الرابط: مفيش ملفات ويب
        sp = new ApkBuilder.Spec(); sp.packageName = "com.h2a.app3"; sp.label = "u"; sp.versionName = "1"; sp.configJson = "{\"url\":\"https://a.com\"}"; sp.remote = true;
        ApkBuilder.build(template, out, sp); check(!readZip(out).keySet().toString().contains("assets/www"), "رابط بدون ويب");
        // تحقق المدخلات
        sp.packageName = "Bad.Pkg"; try { ApkBuilder.build(template, out, sp); check(false, "حزمة غلط"); } catch (IOException ok) { }
        System.out.println("كل اختبارات الباني الأساسي نجحت ✓");
    }
}
