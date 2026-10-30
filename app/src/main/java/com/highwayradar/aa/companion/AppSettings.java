package com.highwayradar.aa.companion;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Single source of truth for user display and overlay preferences. */
public final class AppSettings {

    public static final String PREFS_NAME = "companion_settings";
    public static final String ACTION_SETTINGS_CHANGED = "com.highwayradar.aa.SETTINGS_CHANGED";

    public static final String KEY_SHOW_IN_AA = "show_in_android_auto";
    public static final String KEY_AUTO_START = "auto_start_highway_radar";
    public static final String KEY_AUTO_STOP = "auto_stop_highway_radar";
    public static final String KEY_RESET_ON_FOCUS = "reset_on_focus";
    public static final String KEY_MAPS_OVERLAY = "show_maps_overlay";
    public static final String KEY_MAPS_POLICE = "show_maps_police";
    public static final String KEY_MAPS_AIRCRAFT = "show_maps_aircraft";
    public static final String KEY_MAPS_HUD = "show_maps_hud";

    private static volatile AppSettings sInstance;

    private final Context mAppContext;
    private final SharedPreferences mPrefs;

    private AppSettings(Context context) {
        mAppContext = context.getApplicationContext();
        mPrefs = mAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            mAppContext.grantUriPermission(
                    "com.google.android.apps.maps",
                    android.net.Uri.parse("content://com.highwayradar.aa.companion.settings/all"),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException error) {
            android.util.Log.w("HighwayRadarAA", "Could not grant Maps settings visibility", error);
        }
    }

    public static AppSettings get(Context context) {
        if (sInstance == null) {
            synchronized (AppSettings.class) {
                if (sInstance == null) {
                    sInstance = new AppSettings(context);
                }
            }
        }
        return sInstance;
    }

    public boolean isAutoStart() {
        return mPrefs.getBoolean(KEY_AUTO_START, false);
    }

    public void setAutoStart(boolean value) {
        mPrefs.edit().putBoolean(KEY_AUTO_START, value).apply();
        notifySettingsChanged();
    }

    public boolean isAutoStop() {
        return mPrefs.getBoolean(KEY_AUTO_STOP, true);
    }

    public void setAutoStop(boolean value) {
        mPrefs.edit().putBoolean(KEY_AUTO_STOP, value).apply();
        notifySettingsChanged();
    }

    public boolean isResetOnFocus() {
        return mPrefs.getBoolean(KEY_RESET_ON_FOCUS, false);
    }

    public void setResetOnFocus(boolean value) {
        mPrefs.edit().putBoolean(KEY_RESET_ON_FOCUS, value).apply();
        notifySettingsChanged();
    }

    public boolean isShowInAndroidAuto() {
        return mPrefs.getBoolean(KEY_SHOW_IN_AA, true);
    }

    public void setShowInAndroidAuto(boolean value) {
        mPrefs.edit().putBoolean(KEY_SHOW_IN_AA, value).apply();
        updateCarAppServiceComponent(value);
        notifySettingsChanged();
    }

    public boolean isShowMapsOverlay() {
        return mPrefs.getBoolean(KEY_MAPS_OVERLAY, true);
    }

    public void setShowMapsOverlay(boolean value) {
        mPrefs.edit().putBoolean(KEY_MAPS_OVERLAY, value).apply();
        notifySettingsChanged();
    }

    public boolean isShowMapsPolice() {
        return mPrefs.getBoolean(KEY_MAPS_POLICE, true);
    }

    public void setShowMapsPolice(boolean value) {
        mPrefs.edit().putBoolean(KEY_MAPS_POLICE, value).apply();
        notifySettingsChanged();
    }

    public boolean isShowMapsAircraft() {
        return mPrefs.getBoolean(KEY_MAPS_AIRCRAFT, true);
    }

    public void setShowMapsAircraft(boolean value) {
        mPrefs.edit().putBoolean(KEY_MAPS_AIRCRAFT, value).apply();
        notifySettingsChanged();
    }

    public boolean isShowMapsHud() {
        return mPrefs.getBoolean(KEY_MAPS_HUD, true);
    }

    public void setShowMapsHud(boolean value) {
        mPrefs.edit().putBoolean(KEY_MAPS_HUD, value).apply();
        notifySettingsChanged();
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(KEY_AUTO_START, isAutoStart());
        bundle.putBoolean(KEY_AUTO_STOP, isAutoStop());
        bundle.putBoolean(KEY_RESET_ON_FOCUS, isResetOnFocus());
        bundle.putBoolean(KEY_SHOW_IN_AA, isShowInAndroidAuto());
        bundle.putBoolean(KEY_MAPS_OVERLAY, isShowMapsOverlay());
        bundle.putBoolean(KEY_MAPS_POLICE, isShowMapsPolice());
        bundle.putBoolean(KEY_MAPS_AIRCRAFT, isShowMapsAircraft());
        bundle.putBoolean(KEY_MAPS_HUD, isShowMapsHud());
        return bundle;
    }

    private void updateCarAppServiceComponent(boolean enabled) {
        try {
            PackageManager pm = mAppContext.getPackageManager();
            ComponentName component =
                    new ComponentName(mAppContext, HighwayRadarCarAppService.class);
            int newState =
                    enabled
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            android.util.Log.w(
                    "HighwayRadarAA", "Failed to update CarAppService component state", t);
        }
    }

    public void notifySettingsChanged() {
        try {
            Intent intent = new Intent(ACTION_SETTINGS_CHANGED);
            intent.putExtras(toBundle());
            mAppContext.sendBroadcast(intent);
        } catch (Throwable t) {
            android.util.Log.w("HighwayRadarAA", "Failed to broadcast settings change", t);
        }
    }
}
