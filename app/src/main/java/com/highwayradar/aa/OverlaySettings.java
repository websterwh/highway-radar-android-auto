package com.highwayradar.aa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/**
 * Live settings for the Google Maps projection overlay. Settings are synchronised with the
 * companion app via broadcast and content provider query. Detector metadata and signals are
 * received via {@link DetectorRelay#ACTION} broadcasts from the Highway Radar Xposed hook running
 * in the HR process.
 */
final class OverlaySettings {

    static final String ACTION_SETTINGS_CHANGED = "com.highwayradar.aa.SETTINGS_CHANGED";
    static final String AUTHORITY = "com.highwayradar.aa.companion.settings";
    static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/all");

    static final String KEY_MAPS_OVERLAY = "show_maps_overlay";
    static final String KEY_MAPS_POLICE = "show_maps_police";
    static final String KEY_MAPS_AIRCRAFT = "show_maps_aircraft";
    static final String KEY_MAPS_HUD = "show_maps_hud";

    private static volatile boolean overlayEnabled = true;
    private static volatile boolean policeEnabled = true;
    private static volatile boolean aircraftEnabled = true;
    private static volatile boolean hudEnabled = true;
    private static volatile boolean appStopped;
    private static final String ACTION_APP_STOPPED = "com.highwayradar.aa.APP_STOPPED";

    private static boolean initialized = false;

    private static final BroadcastReceiver RECEIVER =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();
                    if (ACTION_SETTINGS_CHANGED.equals(action)) {
                        updateFromIntent(intent);
                    } else if (ACTION_APP_STOPPED.equals(action)) {
                        appStopped = true;
                        LiveOverlayDataStore.updateAlertsFromJson("[]");
                        LiveOverlayDataStore.updateDetectorFromJson(
                                "{\"model\":null,\"connected\":false}");
                        MapsProjectionOverlay.requestRedraw();
                    } else if (AlertRelay.ACTION.equals(action)) {
                        appStopped = false;
                        String json = intent.getStringExtra(AlertRelay.EXTRA_ALERTS_JSON);
                        LiveOverlayDataStore.updateAlertsFromJson(json);
                        HookLog.i(
                                "OverlaySettings received alerts: count="
                                        + LiveOverlayDataStore.getMarkers().size());
                        MapsProjectionOverlay.requestRedraw();
                    } else if (DetectorRelay.ACTION.equals(action)) {
                        String json = intent.getStringExtra(DetectorRelay.EXTRA_DETECTOR_JSON);
                        if (!LiveOverlayDataStore.parseEnvelope(json).malformed) appStopped = false;
                        LiveOverlayDataStore.updateDetectorFromJson(json);
                        HookLog.i("OverlaySettings received detector update");
                        MapsProjectionOverlay.requestRedraw();
                    }
                }
            };

    private OverlaySettings() {}

    static void init(Context context) {
        if (initialized || context == null) return;
        initialized = true;
        Context app =
                context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            IntentFilter filter = new IntentFilter(ACTION_SETTINGS_CHANGED);
            filter.addAction(AlertRelay.ACTION);
            filter.addAction(DetectorRelay.ACTION);
            filter.addAction(ACTION_APP_STOPPED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
            }
            HookLog.i("OverlaySettings receiver registered");
        } catch (Throwable t) {
            HookLog.w("Failed to register OverlaySettings receiver: " + t);
        }

        queryContentProvider(app);
    }

    private static void queryContentProvider(Context context) {
        try {
            Bundle bundle =
                    context.getContentResolver()
                            .call(Uri.parse("content://" + AUTHORITY), "getAll", null, null);
            if (bundle != null) {
                overlayEnabled = bundle.getBoolean(KEY_MAPS_OVERLAY, overlayEnabled);
                policeEnabled = bundle.getBoolean(KEY_MAPS_POLICE, policeEnabled);
                aircraftEnabled = bundle.getBoolean(KEY_MAPS_AIRCRAFT, aircraftEnabled);
                hudEnabled = bundle.getBoolean(KEY_MAPS_HUD, hudEnabled);
                HookLog.i(
                        "OverlaySettings loaded from provider: overlay="
                                + overlayEnabled
                                + " police="
                                + policeEnabled
                                + " aircraft="
                                + aircraftEnabled
                                + " hud="
                                + hudEnabled);
            }
        } catch (Throwable t) {
            HookLog.warnOnce(
                    "settings-provider", "Could not query companion settings provider: " + t);
        }
    }

    private static void updateFromIntent(Intent intent) {
        if (intent.hasExtra(KEY_MAPS_OVERLAY)) {
            overlayEnabled = intent.getBooleanExtra(KEY_MAPS_OVERLAY, overlayEnabled);
        }
        if (intent.hasExtra(KEY_MAPS_POLICE)) {
            policeEnabled = intent.getBooleanExtra(KEY_MAPS_POLICE, policeEnabled);
        }
        if (intent.hasExtra(KEY_MAPS_AIRCRAFT)) {
            aircraftEnabled = intent.getBooleanExtra(KEY_MAPS_AIRCRAFT, aircraftEnabled);
        }
        if (intent.hasExtra(KEY_MAPS_HUD)) {
            hudEnabled = intent.getBooleanExtra(KEY_MAPS_HUD, hudEnabled);
        }
        HookLog.i(
                "OverlaySettings updated from broadcast: overlay="
                        + overlayEnabled
                        + " police="
                        + policeEnabled
                        + " aircraft="
                        + aircraftEnabled
                        + " hud="
                        + hudEnabled);
        MapsProjectionOverlay.requestRedraw();
    }

    static boolean isOverlayEnabled() {
        return overlayEnabled && !appStopped;
    }

    static boolean isPoliceEnabled() {
        return policeEnabled;
    }

    static boolean isAircraftEnabled() {
        return aircraftEnabled;
    }

    static boolean isHudEnabled() {
        return hudEnabled;
    }
}
