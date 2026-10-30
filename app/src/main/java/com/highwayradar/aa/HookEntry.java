package com.highwayradar.aa;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Module entry point, loaded by the Xposed framework in every hooked process.
 *
 * <p>Package-scoped hooks share this module because they share the same Vector/LSPosed scope
 * management and build/deploy pipeline, not because they share any code:
 *
 * <ul>
 *   <li>{@link #ALERTS_TARGET_PACKAGE}: the alert-relay hook. The hook point is {@code s7.c.a()},
 *       the static no-arg method that returns Highway Radar's live alert list; it is invoked every
 *       time the app's alert list changes, which makes it the correct place to sample the list. The
 *       list itself is only read on the way out of the method.
 *   <li>{@link GearheadInstallerHook#TARGET_PACKAGE}: makes Gearhead treat the sideloaded companion
 *       app as if it were installed from Google Play, required for it to be enumerated in Android
 *       Auto's app launcher at all. See {@link GearheadInstallerHook} for details.
 *   <li>{@link MapsProjectionProbe#TARGET_PACKAGE}: live geographically projected alerts and
 *       detector metadata from the real app.
 * </ul>
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String ALERTS_TARGET_PACKAGE = "com.highwayradar.app";
    private static final String TARGET_CLASS = "s7.c";
    private static final String TARGET_METHOD = "a";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean GEARHEAD_HOOK_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean MAPS_PROBE_INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (MapsProjectionProbe.TARGET_PACKAGE.equals(lpparam.packageName)) {
            if (MAPS_PROBE_INSTALLED.compareAndSet(false, true)) {
                MapsProjectionProbe.install(lpparam);
            }
            return;
        }
        if (GearheadInstallerHook.TARGET_PACKAGE.equals(lpparam.packageName)) {
            String process = lpparam.processName;
            boolean gearheadProcess =
                    GearheadInstallerHook.TARGET_PACKAGE.equals(process)
                            || (process != null
                                    && process.startsWith(
                                            GearheadInstallerHook.TARGET_PACKAGE + ":"));
            if (gearheadProcess && GEARHEAD_HOOK_INSTALLED.compareAndSet(false, true)) {
                GearheadInstallerHook.install(lpparam);
                GearheadSettingsShadowHook.install(lpparam.classLoader);
                GearheadConnectionHook.install(lpparam.classLoader);
            }
            return;
        }
        if (!ALERTS_TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Activity",
                    lpparam.classLoader,
                    "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.app.Activity activity = (android.app.Activity) param.thisObject;
                            if (activity.getWindowManager().getDefaultDisplay().getDisplayId()
                                    == android.view.Display.DEFAULT_DISPLAY) return;
                            ProjectedInputReceiver.install(activity.getApplicationContext());
                            activity.setShowWhenLocked(true);
                            activity.getWindow()
                                    .addFlags(
                                            android.view.WindowManager.LayoutParams
                                                            .FLAG_SHOW_WHEN_LOCKED
                                                    | android.view.WindowManager.LayoutParams
                                                            .FLAG_KEEP_SCREEN_ON);
                            ProjectedInputReceiver.setActiveActivity(activity);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "android.app.Activity",
                    lpparam.classLoader,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.app.Activity activity = (android.app.Activity) param.thisObject;
                            if (activity.getWindowManager().getDefaultDisplay().getDisplayId()
                                    != android.view.Display.DEFAULT_DISPLAY) {
                                ProjectedInputReceiver.setActiveActivity(activity);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "android.app.Activity",
                    lpparam.classLoader,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ProjectedInputReceiver.clearActiveActivity(
                                    (android.app.Activity) param.thisObject);
                        }
                    });
            HookLog.i("Secondary-display activity flags installed for " + lpparam.packageName);
        } catch (Throwable t) {
            HookLog.w("Could not hook Activity.onCreate for keyguard bypass: " + t);
        }
        ProjectedActivityLaunch.install(lpparam.classLoader);
        DetectorTableProbe.install(lpparam.classLoader);
        HookLog.i("hooking " + TARGET_CLASS + "." + TARGET_METHOD + "() in " + lpparam.processName);
        try {
            XposedHelpers.findAndHookMethod(
                    TARGET_CLASS,
                    lpparam.classLoader,
                    TARGET_METHOD,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            AlertRelay.relay(param.getResult());
                        }
                    });
        } catch (Throwable t) {
            // Allow a later load of the package to retry rather than staying permanently disabled.
            INSTALLED.set(false);
            HookLog.w("could not hook " + TARGET_CLASS + "." + TARGET_METHOD + "(): " + t);
            return;
        }
        HookLog.i("hook installed on " + TARGET_CLASS + "." + TARGET_METHOD + "()");
    }
}
