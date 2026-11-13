package com.h2a.builder.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * بيعدّل AndroidManifest.xml الثنائي (AXML) من غير ما يفكّه كله:
 *  - بيبدّل نصوص محددة في الـ string pool (اسم الحزمة، الاسم الظاهر، رقم الإصدار)
 *  - بيغيّر قيمة versionCode الرقمية في عنصر manifest
 * Java نقية (من غير Android) عشان تتختبر على أي JVM.
 */
public final class AxmlPatcher {
    private static final int CHUNK_XML = 0x0003, CHUNK_STRING_POOL = 0x0001, CHUNK_START_ELEMENT = 0x0102;
    private static final int FLAG_UTF8 = 0x100;
    private static final Charset UTF8 = Charset.forName("UTF-8"), UTF16 = Charset.forName("UTF-16LE");

    private AxmlPatcher() { }

    public static byte[] patch(byte[] axml, Map<String, String> replacements, Integer versionCode) {
        byte[] data = axml.clone();
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 8 || (bb.getShort(0) & 0xFFFF) != CHUNK_XML) throw new IllegalArgumentException("مش ملف AXML");
        int poolStart = 8;
        if ((bb.getShort(poolStart) & 0xFFFF) != CHUNK_STRING_POOL) throw new IllegalArgumentException("string pool مش موجود");
        int poolSize = bb.getInt(poolStart + 4);
        List<String> strings = readPool(bb, poolStart);

        if (versionCode != null) patchVersionCode(bb, poolStart + poolSize, strings, versionCode);

        boolean utf8 = (bb.getInt(poolStart + 16) & FLAG_UTF8) != 0;
        List<String> out = new ArrayList<String>(strings.size());
        for (String s : strings) {
            String r = replacements.get(s);
            out.add(r != null ? r : s);
        }
        byte[] newPool = writePool(out, utf8);
        ByteArrayOutputStream res = new ByteArrayOutputStream(data.length + 256);
        res.write(data, 0, poolStart);
        res.write(newPool, 0, newPool.length);
        res.write(data, poolStart + poolSize, data.length - poolStart - poolSize);
        byte[] result = res.toByteArray();
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length);
        return result;
    }

    /** قراءة الـ strings (للاختبار والتشخيص). */
    public static List<String> readStrings(byte[] axml) {
        return readPool(ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN), 8);
    }

    private static List<String> readPool(ByteBuffer bb, int start) {
        int headerSize = bb.getShort(start + 2) & 0xFFFF;
        int count = bb.getInt(start + 8), styleCount = bb.getInt(start + 12), flags = bb.getInt(start + 16), stringsStart = bb.getInt(start + 20);
        if (styleCount != 0) throw new IllegalArgumentException("الـ pool فيه styles، مش مدعوم");
        boolean utf8 = (flags & FLAG_UTF8) != 0;
        List<String> list = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            int off = start + stringsStart + bb.getInt(start + headerSize + 4 * i);
            list.add(utf8 ? readUtf8(bb, off) : readUtf16(bb, off));
        }
        return list;
    }

    private static String readUtf16(ByteBuffer bb, int off) {
        int len = bb.getShort(off) & 0xFFFF;
        int p = off + 2;
        if ((len & 0x8000) != 0) { len = ((len & 0x7FFF) << 16) | (bb.getShort(p) & 0xFFFF); p += 2; }
        byte[] b = new byte[len * 2];
        for (int i = 0; i < b.length; i++) b[i] = bb.get(p + i);
        return new String(b, UTF16);
    }

    private static String readUtf8(ByteBuffer bb, int off) {
        int p = off;
        int u16len = bb.get(p++) & 0xFF;
        if ((u16len & 0x80) != 0) u16len = ((u16len & 0x7F) << 8) | (bb.get(p++) & 0xFF);
        int blen = bb.get(p++) & 0xFF;
        if ((blen & 0x80) != 0) blen = ((blen & 0x7F) << 8) | (bb.get(p++) & 0xFF);
        byte[] b = new byte[blen];
        for (int i = 0; i < blen; i++) b[i] = bb.get(p + i);
        return new String(b, UTF8);
    }

    private static byte[] writePool(List<String> strings, boolean utf8) {
        int count = strings.size();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = data.size();
            String s = strings.get(i);
            if (utf8) {
                byte[] b = s.getBytes(UTF8);
                writeLen8(data, s.length());
                writeLen8(data, b.length);
                data.write(b, 0, b.length);
                data.write(0);
            } else {
                byte[] b = s.getBytes(UTF16);
                if (s.length() > 0x7FFF) throw new IllegalArgumentException("نص طويل جداً");
                data.write(s.length() & 0xFF);
                data.write((s.length() >> 8) & 0xFF);
                data.write(b, 0, b.length);
                data.write(0);
                data.write(0);
            }
        }
        while (data.size() % 4 != 0) data.write(0);
        int headerSize = 28;
        int stringsStart = headerSize + 4 * count;
        int size = stringsStart + data.size();
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) CHUNK_STRING_POOL).putShort((short) headerSize).putInt(size).putInt(count).putInt(0).putInt(utf8 ? FLAG_UTF8 : 0).putInt(stringsStart).putInt(0);
        for (int i = 0; i < count; i++) out.putInt(offsets[i]);
        out.put(data.toByteArray());
        return out.array();
    }

    private static void writeLen8(ByteArrayOutputStream o, int len) {
        if (len > 0x7FFF) throw new IllegalArgumentException("نص طويل جداً");
        if (len > 0x7F) { o.write(((len >> 8) & 0x7F) | 0x80); o.write(len & 0xFF); } else o.write(len);
    }

    private static void patchVersionCode(ByteBuffer bb, int off, List<String> strings, int versionCode) {
        int limit = bb.limit();
        boolean done = false;
        while (off + 8 <= limit) {
            int type = bb.getShort(off) & 0xFFFF, size = bb.getInt(off + 4);
            if (size <= 0) break;
            if (type == CHUNK_START_ELEMENT) {
                int ext = off + 16;
                int nameIdx = bb.getInt(ext + 4);
                if (nameIdx >= 0 && nameIdx < strings.size() && "manifest".equals(strings.get(nameIdx))) {
                    int attrStart = bb.getShort(ext + 8) & 0xFFFF, attrSize = bb.getShort(ext + 10) & 0xFFFF, attrCount = bb.getShort(ext + 12) & 0xFFFF;
                    for (int i = 0; i < attrCount; i++) {
                        int a = ext + attrStart + i * attrSize;
                        int an = bb.getInt(a + 4);
                        if (an >= 0 && an < strings.size() && "versionCode".equals(strings.get(an))) {
                            bb.putInt(a + 8, -1);               // rawValue: مفيش نص
                            bb.put(a + 15, (byte) 0x10);        // TYPE_INT_DEC
                            bb.putInt(a + 16, versionCode);
                            done = true;
                        }
                    }
                    break;
                }
            }
            off += size;
        }
        if (!done) throw new IllegalArgumentException("versionCode مش لاقيه في الـ manifest");
    }
}
