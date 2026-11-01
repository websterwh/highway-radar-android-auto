package com.highwayradar.aa;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * Broadcasts detector metadata and signal readings to the companion app and Google Maps overlay.
 *
 * <p>Protocol (v2, no backwards compatibility with v1 array format):
 *
 * <pre>{@code
 * {
 *   "model"    : string | null,   // HR app display label, e.g. "Uniden R8", "Valentine V1 (gen 2)"
 *   "connected": boolean,         // true only when BLE session is active
 *   "signals"  : [ ... ]          // present only in full relay; absent in state-only relay
 * }
 * }</pre>
 *
 * <p>The receiver in {@code OverlaySettings} feeds this to {@link
 * LiveOverlayDataStore#updateDetectorFromJson(String)}. When the {@code "signals"} key is absent
 * the store updates only model and connected state; when present it also refreshes the signal list
 * and resets the expiry timer. On {@code connected=false} the store clears signals immediately
 * regardless of the signals key.
 */
public final class DetectorRelay {

    public static final String ACTION = "com.highwayradar.aa.DETECTOR_UPDATED";
    public static final String EXTRA_DETECTOR_JSON = "detector_json";

    private static final long MIN_INTERVAL_MS = 100L;

    private static final Object LOCK = new Object();
    private static volatile Context hostContext;
    private static volatile String lastJson = buildStateJson(null, false);
    private static long lastBroadcastAtMs;
    private static volatile boolean flushPending;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable FLUSH_RUNNABLE =
            () -> {
                Context ctx = hostContext();
                String json;
                synchronized (LOCK) {
                    flushPending = false;
                    json = lastJson;
                    lastBroadcastAtMs = SystemClock.elapsedRealtime();
                }
                if (ctx != null) broadcast(ctx, json);
            };

    private DetectorRelay() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Relays a full envelope: model, connected, and the current signal array. Called by the
     * signal-table hook on every BLE notification.
     *
     * @param model HR app display label for the selected detector, or {@code null}
     * @param connected true when a BLE session is active
     * @param signalsJson JSON array string produced by the signal parser
     */
    public static void relay(String model, boolean connected, String signalsJson) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("model", model != null ? model : JSONObject.NULL);
            obj.put("connected", connected);
            JSONArray signals = parseSignalsArray(signalsJson);
            obj.put("signals", signals);
            dispatch(obj.toString());
        } catch (JSONException e) {
            HookLog.warnOnce("relay-build", "Could not build detector JSON: " + e);
        }
    }

    /**
     * Relays a state-only envelope: model and connected, without a {@code "signals"} key. Called by
     * the connection-state hooks so the overlay updates immediately on connect/ disconnect without
     * waiting for the next signal packet. The store leaves any existing signals intact (subject to
     * their own expiry timer) unless connected=false.
     *
     * @param model HR app display label, or {@code null} when no detector is selected
     * @param connected true when a BLE session is active
     */
    public static void relayState(String model, boolean connected) {
        dispatch(buildStateJson(model, connected));
    }

    // -------------------------------------------------------------------------
    // Internal dispatch (rate-limited, coalesced)
    // -------------------------------------------------------------------------

    private static void dispatch(String json) {
        if (json == null) return;
        Context context = hostContext();
        if (context == null) return;

        synchronized (LOCK) {
            // A state-only heartbeat must not discard a signal packet waiting for
            // the throttle to flush. Never replay an already-sent signal table.
            if (flushPending) {
                try {
                    JSONObject pending = new JSONObject(lastJson);
                    JSONObject incoming = new JSONObject(json);
                    if (!incoming.has("signals")
                            && pending.has("signals")
                            && Objects.equals(
                                    incoming.optString("model", null),
                                    pending.optString("model", null))
                            && incoming.optBoolean("connected")
                                    == pending.optBoolean("connected")) {
                        json = lastJson;
                    }
                } catch (JSONException e) {
                    HookLog.warnOnce(
                            "detector-coalesce", "Invalid pending detector envelope: " + e);
                }
            }
            lastJson = json;
            long now = SystemClock.elapsedRealtime();
            long elapsed = now - lastBroadcastAtMs;
            // One main-thread sender prevents a background BLE callback's old
            // Connected envelope from overtaking a newer disconnect broadcast.
            if (!flushPending) {
                flushPending = true;
                HANDLER.postDelayed(FLUSH_RUNNABLE, Math.max(0L, MIN_INTERVAL_MS - elapsed));
            }
        }
    }

    private static void broadcast(Context context, String json) {
        try {
            Intent companionIntent = new Intent(ACTION);
            companionIntent.setPackage("com.highwayradar.aa.companion");
            companionIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            companionIntent.putExtra(EXTRA_DETECTOR_JSON, json);
            context.sendBroadcast(companionIntent);

            Intent mapsIntent = new Intent(ACTION);
            mapsIntent.setPackage("com.google.android.apps.maps");
            mapsIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mapsIntent.putExtra(EXTRA_DETECTOR_JSON, json);
            context.sendBroadcast(mapsIntent);
            HookLog.i("DetectorRelay broadcast sent to Maps: length=" + json.length());
        } catch (Throwable t) {
            HookLog.warnOnce("relay-broadcast", "sendBroadcast(" + ACTION + ") failed: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String buildStateJson(String model, boolean connected) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("model", model != null ? model : JSONObject.NULL);
            obj.put("connected", connected);
            // No "signals" key — state-only update.
            return obj.toString();
        } catch (JSONException e) {
            return "{\"model\":null,\"connected\":false}";
        }
    }

    private static JSONArray parseSignalsArray(String signalsJson) {
        if (signalsJson == null || signalsJson.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(signalsJson);
        } catch (JSONException e) {
            HookLog.warnOnce("relay-signals-parse", "Signals JSON invalid: " + e);
            return new JSONArray();
        }
    }

    private static Context hostContext() {
        Context cached = hostContext;
        if (cached != null) return cached;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return null;
        hostContext = app.getApplicationContext();
        return hostContext;
    }
}
