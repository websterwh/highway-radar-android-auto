package com.highwayradar.aa;

import android.content.Context;
import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Lets connection automation run even when the companion's car screen was never opened. */
final class GearheadConnectionHook {
    static void install(ClassLoader loader) {
        try {
            Class<?> contextImpl = XposedHelpers.findClass("android.app.ContextImpl", loader);
            XC_MethodHook observer =
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()
                                    || param.args.length == 0
                                    || !(param.args[0] instanceof Intent)) return;
                            Intent original = (Intent) param.args[0];
                            if (!"androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
                                    .equals(original.getAction())) return;
                            try {
                                Intent refresh =
                                        new Intent("com.highwayradar.aa.CONNECTION_REFRESH")
                                                .setClassName(
                                                        "com.highwayradar.aa.companion",
                                                        "com.highwayradar.aa.companion.CarConnectionReceiver")
                                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                                ((Context) param.thisObject).sendBroadcast(refresh);
                            } catch (Throwable error) {
                                HookLog.warnOnce(
                                        "connection-forward",
                                        "Could not notify connection observer: " + error);
                            }
                        }
                    };
            XposedBridge.hookAllMethods(contextImpl, "sendBroadcast", observer);
            XposedBridge.hookAllMethods(contextImpl, "sendBroadcastAsUser", observer);
        } catch (Throwable error) {
            HookLog.warnOnce(
                    "connection-hook", "Connection notification hook unavailable: " + error);
        }
    }
}
