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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Serialises the live alert list and hands it to the Android Auto companion app.
 *
 * <p>The transport is an explicit broadcast, sent from Highway Radar's own process with
 * {@link Context#sendBroadcast} against action {@link #ACTION} and extra {@link #EXTRA_ALERTS_JSON},
 * targeted at {@link #TARGET_PACKAGE} so no other app can observe it. A bound service was not an
 * option: the data lives inside a process this module does not own and cannot host a service in.
 *
 * <p>Hook ticks arrive far faster than the companion needs to redraw, so sends are throttled to one
 * per 500 ms for changed payloads, with unchanged payload heartbeats every 3 seconds.
 * A single trailing flush keeps the newest payload, so the last state of a burst is never lost.
 *
 * <p>The payload contract is pinned by {@code IHighwayRadarAlertListener} in the module's aidl
 * directory: a JSON array of flat alert objects.
 */
final class AlertRelay {

    static final String ACTION = "com.highwayradar.aa.ALERTS_UPDATED";
    static final String EXTRA_ALERTS_JSON = "alerts_json";
    static final String TARGET_PACKAGE = "com.highwayradar.aa.companion";

    private static final int PREVIEW_LIMIT = 1200;
    private static final AtomicBoolean PREVIEW_LOGGED = new AtomicBoolean();

    private static final Object LOCK = new Object();
    private static final AlertRelayPolicy POLICY = new AlertRelayPolicy();

    /** Host application context; resolved on the first tick that runs after the host initialised. */
    private static volatile Context hostContext;

    private AlertRelay() {}

    static void relay(Object alerts) {
        if (!(alerts instanceof List)) {
            HookLog.warnOnce("hook-result", "hooked method returned "
                    + (alerts == null ? "null" : alerts.getClass().getName())
                    + " instead of a List; nothing relayed");
            return;
        }
        Context context = hostContext();
        if (context == null) {
            return;
        }
        List<?> alertList = (List<?>) alerts;
        String json = toJson(alertList);
        if (PREVIEW_LOGGED.compareAndSet(false, true)) {
            HookLog.i("first relay of " + alertList.size() + " alert(s), " + json.length()
                    + " bytes: " + preview(json));
        }
        send(json);
    }

    private static Context hostContext() {
        Context cached = hostContext;
        if (cached != null) {
            return cached;
        }
        Application application = AndroidAppHelper.currentApplication();
        if (application == null) {
            HookLog.once("host-app", "host application is not ready yet; "
                    + "waiting for the next tick before relaying");
            return null;
        }
        hostContext = application.getApplicationContext();
        return hostContext;
    }

    private static void send(String json) {
        synchronized (LOCK) {
            POLICY.offer(json);
            scheduleFlush(SystemClock.elapsedRealtime());
        }
    }

    /** Called under LOCK; reserve before posting so concurrent ticks cannot queue duplicates. */
    private static void scheduleFlush(long now) {
        long due = POLICY.reserveFlush(now);
        if (due >= 0L) {
            Scheduler.HANDLER.removeCallbacks(Scheduler.FLUSH);
            Scheduler.HANDLER.postDelayed(Scheduler.FLUSH, Math.max(0L, due - now));
        }
    }

    private static void flush() {
        String json;
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            json = POLICY.flush(now);
            scheduleFlush(now);
        }
        // Only the main handler broadcasts: a newer tick cannot overtake this payload.
        Context context = hostContext;
        if (json != null && context != null) {
            broadcast(context, json);
        }
    }

    private static void broadcast(Context context, String json) {
        try {
            Intent companionIntent = new Intent(ACTION);
            companionIntent.setPackage(TARGET_PACKAGE);
            companionIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            companionIntent.putExtra(EXTRA_ALERTS_JSON, json);
            context.sendBroadcast(companionIntent);

            Intent mapsIntent = new Intent(ACTION);
            mapsIntent.setPackage("com.google.android.apps.maps");
            mapsIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mapsIntent.putExtra(EXTRA_ALERTS_JSON, json);
            context.sendBroadcast(mapsIntent);
        } catch (Throwable t) {
            HookLog.warnOnce("relay-failed", "sendBroadcast(" + ACTION + ") failed: " + t);
        }
    }

    private static String toJson(List<?> alerts) {
        JSONArray array = new JSONArray();
        for (Object alert : alerts) {
            if (alert == null) {
                continue;
            }
            try {
                JSONObject json = new JSONObject();
                for (Map.Entry<String, Object> field : AlertExtractor.extract(alert).entrySet()) {
                    putNullable(json, field.getKey(), field.getValue());
                }
                array.put(json);
            } catch (Throwable t) {
                // One unreadable alert must not cost the rest of the list.
                HookLog.warnOnce("alert-json:" + alert.getClass().getName(),
                        "could not relay a " + alert.getClass().getName() + ": " + t);
            }
        }
        return array.toString();
    }

    /** JSONObject.put with a null value drops the key; NULL keeps the field present and null. */
    private static void putNullable(JSONObject json, String key, Object value) throws JSONException {
        json.put(key, value == null ? JSONObject.NULL : value);
    }

    private static String preview(String json) {
        return json.length() <= PREVIEW_LIMIT
                ? json
                : json.substring(0, PREVIEW_LIMIT) + "... (truncated)";
    }

    /** Held apart so the handler is created on the first tick rather than at class load. */
    private static final class Scheduler {

        static final Handler HANDLER = new Handler(Looper.getMainLooper());

        static final Runnable FLUSH = new Runnable() {
            @Override
            public void run() {
                flush();
            }
        };
    }
}