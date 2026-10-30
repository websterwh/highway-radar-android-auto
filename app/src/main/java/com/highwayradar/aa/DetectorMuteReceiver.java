package com.highwayradar.aa;

import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import de.robv.android.xposed.XposedHelpers;

import java.util.List;

/** Requests the real app's detector-alert mute policy; never sends raw detector commands. */
final class DetectorMuteReceiver extends BroadcastReceiver {
    static final String ACTION = "com.highwayradar.aa.MUTE_DETECTOR";
    private static boolean installed;
    private final ClassLoader loader;
    private long lastRequestAt;

    private DetectorMuteReceiver(ClassLoader loader) {
        this.loader = loader;
    }

    static synchronized void install(ClassLoader loader) {
        Context app = AndroidAppHelper.currentApplication();
        if (installed || app == null) return;
        try {
            DetectorMuteReceiver receiver = new DetectorMuteReceiver(loader);
            IntentFilter filter = new IntentFilter(ACTION);
            String permission = "com.highwayradar.aa.companion.permission.CONTROL_PROJECTED_APP";
            Handler handler = new Handler(Looper.getMainLooper());
            app.registerReceiver(receiver, filter, permission, handler, Context.RECEIVER_EXPORTED);
            installed = true;
            HookLog.i("Native detector mute receiver installed with signature sender permission");
        } catch (Throwable error) {
            HookLog.warnOnce(
                    "mute-registration", "Could not install native detector mute bridge: " + error);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        long now = SystemClock.elapsedRealtime();
        if (lastRequestAt != 0 && now - lastRequestAt < 250L) return;
        lastRequestAt = now;
        try {
            if (!DetectorTableProbe.readConnectedFromActiveManager(loader)) {
                de.robv.android.xposed.XposedBridge.log(
                        "Highway Radar: detector mute ignored: selected detector is not connected");
                return;
            }
            Object settings =
                    XposedHelpers.getStaticObjectField(
                            loader.loadClass(
                                    "com.highwayradar.app.settings.definitions.AppSettings"),
                            "INSTANCE");
            Object rd = XposedHelpers.callMethod(settings, "getRd");
            Object connection = XposedHelpers.callMethod(rd, "getConnectionType");
            Object selected = XposedHelpers.callMethod(connection, "getValue");
            String detector = ((Enum<?>) selected).name();
            if ("V1G2".equals(detector)) detector = "V1";
            Class<?> detectorAlert = loader.loadClass("lb.q");
            Class<?> eventType =
                    loader.loadClass(
                            "com.highwayradar.app.events.types.muting.SetOneMutingStatusEvent");
            Object result = XposedHelpers.callStaticMethod(loader.loadClass("s7.c"), "a");
            if (!(result instanceof List<?>)) return;
            // The native main mute button submits this same event using stable Alert IDs.
            // Snapshot the current list; raw signal frequencies/indices are not alert IDs.
            Object[] alerts = ((List<?>) result).toArray();
            int submitted = 0;
            for (Object alert : alerts) {
                if (!detectorAlert.isInstance(alert)
                        || !detector.equals(XposedHelpers.callMethod(alert, "d"))
                        || !Boolean.TRUE.equals(XposedHelpers.callMethod(alert, "isActive"))
                        || Boolean.TRUE.equals(XposedHelpers.callMethod(alert, "isMuted")))
                    continue;
                String id = (String) XposedHelpers.callMethod(alert, "getId");
                Object event = XposedHelpers.newInstance(eventType, id, true);
                XposedHelpers.callMethod(event, "submit");
                submitted++;
            }
            de.robv.android.xposed.XposedBridge.log(
                    "Highway Radar: native detector mute submitted: alerts=" + submitted);
        } catch (Throwable error) {
            HookLog.warnOnce("mute-submit", "Could not submit native detector mute: " + error);
        }
    }
}
