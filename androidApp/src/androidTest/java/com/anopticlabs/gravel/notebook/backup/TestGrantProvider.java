package com.anopticlabs.gravel.notebook.backup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import java.util.ArrayList;

/*
 * Test-package provider that grants the app URI permissions on {@link TestDocumentsProvider}
 * documents, the picker's role in production. Only the provider's owning UID may grant them,
 * and the instrumentation runs as the app's UID, so this runs in the test package's process.
 * Plain Java on {@code android.*} only: that process loads nothing but the test APK's own
 * classes. It grants only to the app under test and only when the caller is that app.
 */
public class TestGrantProvider extends ContentProvider {

    public static final String AUTHORITY = "com.anopticlabs.gravel.test.grants";
    public static final String METHOD_GRANT = "grant";
    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_PERSISTABLE = "persistable";
    public static final String EXTRA_PREFIX = "prefix";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String TARGET_PACKAGE = "com.anopticlabs.gravel";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GRANT.equals(method)) return null;
        if (arg == null) throw new IllegalArgumentException("target package required");
        final int appUid;
        try {
            appUid = getContext().getPackageManager().getPackageUid(TARGET_PACKAGE, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new SecurityException("target app not installed", e);
        }
        if (Binder.getCallingUid() != appUid || !TARGET_PACKAGE.equals(arg)) {
            throw new SecurityException("grants are issued only to, and for, the app under test");
        }
        ArrayList<Uri> uris = extras != null ? extras.<Uri>getParcelableArrayList(EXTRA_URIS) : null;
        if (uris == null) uris = new ArrayList<>();
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (extras != null && extras.getBoolean(EXTRA_PERSISTABLE, false)) flags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (extras != null && extras.getBoolean(EXTRA_PREFIX, false)) flags |= Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        for (Uri uri : uris) {
            if (!TestDocumentsProvider.AUTHORITY.equals(uri.getAuthority())) {
                throw new IllegalArgumentException("refusing to grant " + uri);
            }
            getContext().grantUriPermission(arg, uri, flags);
        }
        Bundle out = new Bundle();
        out.putInt("granted", uris.size());
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
