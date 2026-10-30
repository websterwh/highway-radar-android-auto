package com.highwayradar.aa;

import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single logging surface for the module.
 *
 * <p>Every message goes out under one tag so a single logcat filter shows the whole lifecycle.
 * Per-tick failures (a renamed getter, a broadcast that the system rejected) are logged once per
 * process instead of once per alert, which would otherwise bury the useful diagnostics.
 */
final class HookLog {

    static final String TAG = "HWRadarHook";

    private static final Set<String> REPORTED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private HookLog() {}

    static void i(String message) {
        Log.i(TAG, message);
    }

    static void w(String message) {
        Log.w(TAG, message);
    }

    /** Logs {@code message} the first time {@code key} is seen in this process, then stays silent. */
    static void once(String key, String message) {
        if (REPORTED.add(key)) {
            Log.i(TAG, message);
        }
    }

    /** Logs a recurring failure once per {@code key}, at warning level. */
    static void warnOnce(String key, String message) {
        if (REPORTED.add(key)) {
            Log.w(TAG, message);
        }
    }
}