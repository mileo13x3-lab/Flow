package de.mrm.codepool;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateFileProvider extends ContentProvider {
    static File directory(Context c) {
        File dir = new File(c.getCacheDir(), "updates");

        if (dir.exists() && !dir.isDirectory()) {
            throw new IllegalStateException("Update-Ordner blockiert.");
        }

        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Update-Ordner nicht verfügbar.");
        }

        return dir;
    }

    static Uri uri(Context c, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(c.getPackageName() + ".updates")
                .appendPath(file.getName())
                .build();
    }

    private File file(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) ||
                !(getContext().getPackageName() + ".updates").equals(uri.getAuthority()) ||
                uri.getPathSegments().size() != 1 ||
                !uri.getLastPathSegment().matches("[a-f0-9-]{36}\\.apk")) {
            throw new FileNotFoundException("Ungültige Update-Datei.");
        }

        File file = new File(directory(getContext()), uri.getLastPathSegment());
        if (!file.isFile()) throw new FileNotFoundException("Update-Datei nicht mehr vorhanden.");
        return file;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Nur Lesen erlaubt.");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];

            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "Mr-M-Flow-Update.apk";
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }

            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
}
