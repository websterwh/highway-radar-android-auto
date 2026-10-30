package com.highwayradar.aa;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Routes ordinary same-app navigation through the root owner of the private display. */
final class ProjectedActivityLaunch {
    private static final String TARGET_PACKAGE = "com.highwayradar.app";
    private static boolean installed;

    private ProjectedActivityLaunch() {}

    // Normal Activity.startActivity overloads delegate here. Result callbacks, custom options,
    // startActivities,
    // startActivityIfNeeded, IntentSender and non-Activity launches are not intercepted.
    static synchronized void install(ClassLoader classLoader) {
        if (installed) return;
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Activity",
                    classLoader,
                    "startActivityForResult",
                    Intent.class,
                    int.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Activity source = (Activity) param.thisObject;
                                if (!TARGET_PACKAGE.equals(source.getPackageName())) return;
                                Display display = source.getWindowManager().getDefaultDisplay();
                                if (display == null) {
                                    HookLog.warnOnce(
                                            "projected-launch-no-display",
                                            "Skipping projected launch: source display"
                                                + " unavailable");
                                    return;
                                }
                                if (display.getDisplayId() == Display.DEFAULT_DISPLAY
                                        || !display.isValid()
                                        || (display.getFlags() & Display.FLAG_PRIVATE) == 0) return;
                                Intent intent = (Intent) param.args[0];
                                if (intent == null || !targetsSameApp(source, intent)) return;

                                // Activities not marked allowEmbedded cannot launch themselves on
                                // another app's virtual display. Use its authorized root owner.
                                if ((Integer) param.args[1] >= 0 || param.args[2] != null) return;
                                // Fail closed: a rejected broker request must not silently move
                                // the existing task onto the physical phone behind its keyguard.
                                param.setResult(null);
                                Intent launch = new Intent(intent);
                                if (launch.getComponent() == null) {
                                    launch.setComponent(
                                            intent.resolveActivity(source.getPackageManager()));
                                }
                                Bundle request = new Bundle();
                                request.putInt("display_id", display.getDisplayId());
                                request.putInt("task_id", source.getTaskId());
                                request.putString(
                                        "intent_uri", launch.toUri(Intent.URI_INTENT_SCHEME));
                                Bundle reply =
                                        source.getContentResolver()
                                                .call(
                                                        Uri.parse(
                                                                "content://com.highwayradar.aa.companion.settings"),
                                                        "launchProjectedActivity",
                                                        null,
                                                        request);
                                if (reply == null || !reply.getBoolean("accepted")) {
                                    throw new IllegalStateException(
                                            "Projected launch was not accepted");
                                }
                            } catch (Throwable t) {
                                HookLog.warnOnce(
                                        "projected-launch-failed",
                                        "Could not preserve projected activity display: " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "finish",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                Display display = activity.getWindowManager().getDefaultDisplay();
                                if (TARGET_PACKAGE.equals(activity.getPackageName())
                                        && display != null
                                        && display.getDisplayId() != Display.DEFAULT_DISPLAY
                                        && (display.getFlags() & Display.FLAG_PRIVATE) != 0) {
                                    activity.overridePendingTransition(0, 0);
                                }
                            } catch (Throwable error) {
                                HookLog.warnOnce(
                                        "projected-menu-exit",
                                        "Could not simplify menu exit: " + error);
                            }
                        }
                    });
            installed = true;
            HookLog.i("Private-display same-app activity launch hook installed");
        } catch (Throwable t) {
            HookLog.warnOnce(
                    "projected-launch-install", "Could not hook projected activity launches: " + t);
        }
    }

    private static boolean targetsSameApp(Activity source, Intent intent) {
        ComponentName component = intent.getComponent();
        if (component != null) return TARGET_PACKAGE.equals(component.getPackageName());
        String targetPackage = intent.getPackage();
        if (targetPackage != null) return TARGET_PACKAGE.equals(targetPackage);
        ComponentName resolved = intent.resolveActivity(source.getPackageManager());
        if (resolved == null) {
            HookLog.warnOnce(
                    "projected-launch-unresolved",
                    "Skipping projected launch: destination activity unresolved");
            return false;
        }
        return TARGET_PACKAGE.equals(resolved.getPackageName());
    }
}
