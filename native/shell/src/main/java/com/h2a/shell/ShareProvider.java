package com.h2a.shell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * بيقدّم الملفات المؤقتة (cache/share) لتطبيقات المشاركة (واتساب، بلوتوث…) بدون صلاحيات تخزين.
 * الـ authority = اسم الحزمة + ".sharefiles" (بيتعدّل في الـ manifest مع كل تطبيق).
 */
public class ShareProvider extends ContentProvider {
    static File root(android.content.Context c) {
        File d = new File(c.getCacheDir(), "share");
        d.mkdirs();
        return d;
    }

    static String authority(android.content.Context c) { return c.getPackageName() + ".sharefiles"; }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2) throw new FileNotFoundException("bad uri");
        try {
            File base = root(getContext()).getCanonicalFile();
            File f = new File(new File(base, seg.get(0)), seg.get(1)).getCanonicalFile();
            if (!f.getPath().startsWith(base.getPath() + File.separator) || !f.isFile()) throw new FileNotFoundException("not found");
            return f;
        } catch (IOException e) { throw new FileNotFoundException("bad path"); }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = resolve(uri);
            String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) row[i] = OpenableColumns.SIZE.equals(cols[i]) ? (Object) f.length() : f.getName();
            c.addRow(row);
            return c;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        int dot = n == null ? -1 : n.lastIndexOf('.');
        String t = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot + 1).toLowerCase());
        return t != null ? t : "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
