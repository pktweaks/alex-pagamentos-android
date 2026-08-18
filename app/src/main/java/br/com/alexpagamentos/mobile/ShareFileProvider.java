package br.com.alexpagamentos.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class ShareFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File base = new File(requireContext().getCacheDir(), "alex_share");
        File file = new File(base, name);
        try {
            String basePath = base.getCanonicalPath() + File.separator;
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(basePath)) throw new FileNotFoundException();
        } catch (Exception e) { throw new FileNotFoundException(); }
        if (!file.exists()) throw new FileNotFoundException();
        return file;
    }

    private android.content.Context requireContext() {
        android.content.Context c = getContext();
        if (c == null) throw new IllegalStateException("Provider sem contexto");
        return c;
    }

    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n != null && n.toLowerCase().endsWith(".pdf")) return "application/pdf";
        if (n != null && n.toLowerCase().endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{f.getName(), f.length()});
            return cursor;
        } catch (Exception e) { return null; }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
}
