package com.highwayradar.aa;

import android.app.AndroidAppHelper;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Observes detector data and connection state from the Highway Radar app. Read-only: does not open
 * Bluetooth or issue any detector commands.
 *
 * <p>Three hook points:
 *
 * <ol>
 *   <li>{@code wb.i0.B(byte[])} – Attowave (Uniden/Radenso) signal table parser; fires on every BLE
 *       notification. Builds the signal array and calls {@link DetectorRelay#relay(String, boolean,
 *       String)}.
 *   <li>{@code wb.i0.w(k6.t1)} – final state-setter for all Attowave managers; fires after every
 *       BLE state transition. Calls {@link DetectorRelay#relayState(String, boolean)}.
 *   <li>{@code zb.m.j(q2.k)} – Valentine V1 (gen 2) connection state setter.
 * </ol>
 *
 * <p>Model labels are read via {@code
 * com.highwayradar.app.settings.definitions.rd.SVConnectionType#getListItemTitle(Value)} through
 * {@code AppSettings.INSTANCE} — the HR app's own display strings, not a local mapping. The
 * configured type persists in AppSettings even while the BLE device is disconnected, so {@link
 * LiveOverlayDataStore#getDetectorModel()} reflects the user's selection at all times.
 */
final class DetectorTableProbe {

    private static long lastSignalLogMs;

    private DetectorTableProbe() {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    static void install(ClassLoader loader) {
        installAttowaveSignalHook(loader);
        installAttowaveStateHook(loader);
        installV1StateHook(loader);
        // Wait until Application exists before touching the app's settings singletons.
        // Periodic metadata also bootstraps Maps after its process restarts, without
        // replaying old signal tables or claiming a dead app is still connected.
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (AndroidAppHelper.currentApplication() != null) {
                            querySelectedState(loader);
                        }
                        handler.postDelayed(this, 3000L);
                    }
                },
                1000L);
    }

    // -------------------------------------------------------------------------
    // Hook 1: wb.i0.B(byte[]) — Attowave signal table
    //
    // 'wb.i0' is the abstract base class shared by all Attowave managers (R4, R8, R9, DS1).
    // param.thisObject is the specific singleton that received the BLE notification, so
    // calling f() on it yields that detector's SVConnectionType.Value — not always R8.
    // -------------------------------------------------------------------------

    private static void installAttowaveSignalHook(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "wb.i0",
                    loader,
                    "B",
                    byte[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            try {
                                List<?> entries =
                                        (List<?>)
                                                XposedHelpers.getObjectField(param.thisObject, "d");
                                JSONArray array = new JSONArray();
                                if (entries != null) {
                                    for (int i = 0; i < Math.min(entries.size(), 8); i++) {
                                        Object item = entries.get(i);
                                        if (item == null) continue;
                                        Object fields = XposedHelpers.getObjectField(item, "a");
                                        if (fields == null) continue;

                                        Object alertType =
                                                XposedHelpers.getObjectField(fields, "a");
                                        int rssi = XposedHelpers.getIntField(fields, "b");
                                        int rawRssi = XposedHelpers.getIntField(fields, "c");
                                        double freqRaw = XposedHelpers.getDoubleField(fields, "d");
                                        Object dirObj = XposedHelpers.getObjectField(fields, "e");
                                        Object muteObj = XposedHelpers.getObjectField(fields, "f");

                                        String bandName =
                                                alertType != null
                                                        ? alertType.toString()
                                                        : "UNKNOWN";
                                        String dirName =
                                                dirObj != null
                                                        ? dirObj.toString()
                                                        : "NOT_AVAILABLE";
                                        boolean isFront = "FRONT".equalsIgnoreCase(dirName);
                                        boolean isMuted =
                                                muteObj != null
                                                        && ("MUTED"
                                                                        .equalsIgnoreCase(
                                                                                muteObj.toString())
                                                                || "MUTE_MEMORY"
                                                                        .equalsIgnoreCase(
                                                                                muteObj
                                                                                        .toString()));
                                        double freqGhz =
                                                freqRaw > 1000.0 ? freqRaw / 1000.0 : freqRaw;

                                        JSONObject sig = new JSONObject();
                                        sig.put("band", bandName);
                                        sig.put("frequencyGhz", freqGhz);
                                        sig.put("strength", Math.max(0, Math.min(8, rssi)));
                                        sig.put("rawStrength", rawRssi);
                                        sig.put("direction", dirName);
                                        sig.put("front", isFront);
                                        sig.put("muted", isMuted);
                                        array.put(sig);
                                    }
                                }

                                boolean connected = attowaveConnected(param.thisObject);
                                String model = readModelLabel(loader, param.thisObject);
                                if (model == null
                                        || !model.equals(readModelFromAppSettings(loader))) return;

                                DetectorRelay.relay(model, connected, array.toString());

                                long now = SystemClock.elapsedRealtime();
                                synchronized (DetectorTableProbe.class) {
                                    if (lastSignalLogMs != 0 && now - lastSignalLogMs < 5000L)
                                        return;
                                    lastSignalLogMs = now;
                                }
                                HookLog.i(
                                        "Detector signals relayed: entries="
                                                + (entries != null ? entries.size() : 0)
                                                + " model="
                                                + model
                                                + " connected="
                                                + connected
                                                + " activeSignals="
                                                + array.length());
                            } catch (Throwable e) {
                                HookLog.warnOnce(
                                        "detector-signal-probe", "Signal probe error: " + e);
                            }
                        }
                    });
            HookLog.i("Detector signal hook installed: wb.i0.B(byte[])");
        } catch (Throwable t) {
            HookLog.w("Detector signal hook unavailable: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 2: wb.i0.w(k6.t1) — Attowave connection state machine transition
    //
    // This method is 'final' on wb.i0 and is the single write point for the
    // BLE state field (f11332c). It is called by all four Attowave subclasses
    // (R4, R8, R9, DS1). After it returns, c contains the new state and p()
    // has already fired the corresponding ConnectionStatusChangeEvent.
    // -------------------------------------------------------------------------

    private static void installAttowaveStateHook(ClassLoader loader) {
        try {
            Class<?> t1Class = loader.loadClass("k6.t1");
            XposedHelpers.findAndHookMethod(
                    "wb.i0",
                    loader,
                    "w",
                    t1Class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            try {
                                querySelectedState(loader);
                            } catch (Throwable e) {
                                HookLog.warnOnce(
                                        "detector-state-hook", "Attowave state hook error: " + e);
                            }
                        }
                    });
            HookLog.i("Detector state hook installed: wb.i0.w(k6.t1)");
        } catch (Throwable t) {
            HookLog.w("Detector state hook unavailable: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 3: zb.m.j(q2.k) — Valentine V1 (gen 2) connection state setter
    //
    // zb.m is the V1 manager. j(q2.k) sets f12789h and fires
    // V1ConnectionStatusChangeEvent. q2.k.Connected is the live-connection state.
    // -------------------------------------------------------------------------

    private static void installV1StateHook(ClassLoader loader) {
        try {
            Class<?> kClass = loader.loadClass("q2.k");
            XposedHelpers.findAndHookMethod(
                    "zb.m",
                    loader,
                    "j",
                    kClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            try {
                                querySelectedState(loader);
                            } catch (Throwable e) {
                                HookLog.warnOnce("v1-state-hook", "V1 state hook error: " + e);
                            }
                        }
                    });
            HookLog.i("V1 state hook installed: zb.m.j(q2.k)");
        } catch (Throwable t) {
            HookLog.w("V1 state hook unavailable: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Selected-state snapshot: connection hooks and the periodic heartbeat share
    // this path, so changing the selected detector cannot leave a cached label.
    // -------------------------------------------------------------------------

    private static void querySelectedState(ClassLoader loader) {
        try {
            DetectorMuteReceiver.install(loader);
            String model = readModelFromAppSettings(loader);
            boolean connected = model != null && readConnectedFromActiveManager(loader);
            DetectorRelay.relayState(model, connected);
        } catch (Throwable t) {
            HookLog.warnOnce("detector-initial", "Initial state query failed: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers (package-private for DetectorStateVerifier)
    // -------------------------------------------------------------------------

    /**
     * Reads the model display label for the currently-selected detector type from {@code
     * AppSettings.INSTANCE.getRd().getConnectionType()}.
     *
     * <p>Returns {@code null} when the selection is {@code NONE} or AppSettings is unavailable. The
     * selection persists even while disconnected, so this reflects the user's choice.
     */
    static String readModelFromAppSettings(ClassLoader loader) {
        try {
            Class<?> cls =
                    loader.loadClass("com.highwayradar.app.settings.definitions.AppSettings");
            Object appSettings = XposedHelpers.getStaticObjectField(cls, "INSTANCE");
            Object sgRd = XposedHelpers.callMethod(appSettings, "getRd");
            Object svCt = XposedHelpers.callMethod(sgRd, "getConnectionType");
            Object value = XposedHelpers.callMethod(svCt, "getValue");
            if (value == null) return null;
            if ("NONE".equals(((Enum<?>) value).name())) return null;
            return (String) XposedHelpers.callMethod(svCt, "getListItemTitle", value);
        } catch (Throwable t) {
            HookLog.warnOnce("model-appsettings", "AppSettings model read failed: " + t);
            return null;
        }
    }

    /**
     * Reads the label for the type returned by {@code managerInstance.f()}, using the same
     * AppSettings lookup so the string is the app's own display text.
     */
    private static String readModelLabel(ClassLoader loader, Object managerInstance) {
        try {
            Object enumValue = XposedHelpers.callMethod(managerInstance, "f");
            if (enumValue == null) return null;
            String enumName = ((Enum<?>) enumValue).name();
            if ("NONE".equals(enumName)) return null;
            // Resolve display label from AppSettings using the instance's type.
            Class<?> cls =
                    loader.loadClass("com.highwayradar.app.settings.definitions.AppSettings");
            Object appSettings = XposedHelpers.getStaticObjectField(cls, "INSTANCE");
            Object sgRd = XposedHelpers.callMethod(appSettings, "getRd");
            Object svCt = XposedHelpers.callMethod(sgRd, "getConnectionType");
            return (String) XposedHelpers.callMethod(svCt, "getListItemTitle", enumValue);
        } catch (Throwable outer) {
            HookLog.warnOnce("detector-model-label", "App detector label unavailable: " + outer);
            return null;
        }
    }

    /**
     * Best-effort check: is the currently-selected detector type actively connected? Consults the
     * specific manager singleton for that type; returns false on any failure.
     */
    static boolean readConnectedFromActiveManager(ClassLoader loader) {
        try {
            Class<?> cls =
                    loader.loadClass("com.highwayradar.app.settings.definitions.AppSettings");
            Object appSettings = XposedHelpers.getStaticObjectField(cls, "INSTANCE");
            Object sgRd = XposedHelpers.callMethod(appSettings, "getRd");
            Object svCt = XposedHelpers.callMethod(sgRd, "getConnectionType");
            Object value = XposedHelpers.callMethod(svCt, "getValue");
            if (value == null) return false;
            switch (((Enum<?>) value).name()) {
                case "R8":
                    return attowaveConnected(loader, "yb.g", "u");
                case "R9":
                    return attowaveConnected(loader, "yb.l", "u");
                case "R4":
                    return attowaveConnected(loader, "yb.d", "u");
                case "DS1":
                    return ds1Connected(loader);
                case "V1G2":
                    return v1Connected(loader);
                default:
                    return false;
            }
        } catch (Throwable t) {
            HookLog.warnOnce("conn-check", "Active manager connected check failed: " + t);
            return false;
        }
    }

    private static boolean attowaveConnected(ClassLoader loader, String cls, String field) {
        try {
            Object singleton = XposedHelpers.getStaticObjectField(loader.loadClass(cls), field);
            return attowaveConnected(singleton);
        } catch (Throwable t) {
            return false;
        }
    }

    // DS1 singleton is accessed via xb.f.a() (lazy via Companion), not a named static field.
    private static boolean ds1Connected(ClassLoader loader) {
        try {
            Object singleton = XposedHelpers.callStaticMethod(loader.loadClass("xb.f"), "a");
            return attowaveConnected(singleton);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean attowaveConnected(Object manager) {
        if (manager == null) return false;
        Object state = XposedHelpers.getObjectField(manager, "c");
        // md.a.getStatus() in the real app labels only wb.o as Connected.
        // wb.p is Connecting; l() also remains true in disconnected/retry states.
        return DetectorConnectionState.isAttowaveConnected(
                state == null ? null : state.getClass().getName());
    }

    // V1 manager exposes its state via zb.k.a().c() → q2.k enum.
    private static boolean v1Connected(ClassLoader loader) {
        try {
            Object mgr = XposedHelpers.callStaticMethod(loader.loadClass("zb.k"), "a");
            Object state = XposedHelpers.callMethod(mgr, "c");
            return state != null && "Connected".equals(((Enum<?>) state).name());
        } catch (Throwable t) {
            return false;
        }
    }
}
