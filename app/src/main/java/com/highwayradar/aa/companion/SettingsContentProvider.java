package com.highwayradar.aa.companion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Exposes live user preferences to other scoped processes (like Google Maps Xposed hook). */
public final class SettingsContentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.highwayradar.aa.companion.settings";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("muteDetector".equals(method)) {
            try {
                int mapsUid =
                        getContext()
                                .getPackageManager()
                                .getApplicationInfo("com.google.android.apps.maps", 0)
                                .uid;
                if (Binder.getCallingUid() != mapsUid) {
                    throw new SecurityException("Only Google Maps may request detector mute");
                }
                getContext()
                        .sendBroadcast(
                                new android.content.Intent("com.highwayradar.aa.MUTE_DETECTOR")
                                        .setPackage("com.highwayradar.app")
                                        .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND));
                Bundle result = new Bundle();
                result.putBoolean("accepted", true);
                return result;
            } catch (android.content.pm.PackageManager.NameNotFoundException error) {
                throw new SecurityException("Google Maps is not installed", error);
            }
        }
        if ("launchProjectedActivity".equals(method)) {
            try {
                int uid =
                        getContext()
                                .getPackageManager()
                                .getApplicationInfo("com.highwayradar.app", 0)
                                .uid;
                if (Binder.getCallingUid() != uid) {
                    throw new SecurityException(
                            "Only Highway Radar may request projected navigation");
                }
                Bundle result = new Bundle();
                result.putBoolean("accepted", CarSurfaceDisplay.launchProjectedActivity(extras));
                return result;
            } catch (android.content.pm.PackageManager.NameNotFoundException error) {
                throw new SecurityException("Highway Radar is not installed", error);
            }
        }
        if ("getAll".equals(method)) {
            if (getContext() != null) {
                return AppSettings.get(getContext()).toBundle();
            }
        }
        return super.call(method, arg, extras);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.highwayradar.settings";
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
