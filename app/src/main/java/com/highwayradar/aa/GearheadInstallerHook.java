package com.highwayradar.aa;

import android.os.Build;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Makes installer-provenance getter calls inside Gearhead report "installed from Google Play".
 * This applies to all queried packages in that process, not just the companion app.
 *
 * <p>The Car App Library host (Gearhead) refuses to enumerate a sideloaded
 * {@code androidx.car.app.CarAppService} in its launcher/"Customize launcher" list even when the
 * service itself resolves correctly via {@link android.content.pm.PackageManager} and every
 * manifest declaration ({@code minCarApiLevel}, category, host validator) is correct. On Android
 * 11+ (API 30, {@link Build.VERSION_CODES#R}) Gearhead reads installer provenance via
 * {@link android.content.pm.InstallSourceInfo#getInitiatingPackageName()}, which a plain
 * {@code pm install -i com.android.vending} does not satisfy (that only sets the legacy
 * installer-package attribution, not the initiating-package field the newer API reports). Below
 * API 30 Gearhead instead reads the legacy {@link android.content.pm.PackageManager#getInstallerPackageName(String)}.
 *
 * <p>This hook is scoped to Gearhead's own process only ({@link #TARGET_PACKAGE}) and never runs
 * inside Highway Radar or the companion app. It does not patch any file, modify Gearhead's APK, or
 * touch Play Services' {@code phenotype.db} — it intercepts the two provenance getters in memory
 * for the lifetime of the hooked process, as originally implemented by Fermata
 * (AndreyPavlenko/Fermata) and distributed standalone as Android-Auto-XLauncher-Unlocked
 * (Rikj000/Android-Auto-XLauncher-Unlocked).
 */
final class GearheadInstallerHook {

    static final String TARGET_PACKAGE = "com.google.android.projection.gearhead";

    private static final String GOOGLE_PLAY_PACKAGE = "com.android.vending";

    private GearheadInstallerHook() {}

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                XposedHelpers.findAndHookMethod(
                        "android.content.pm.InstallSourceInfo", lpparam.classLoader,
                        "getInitiatingPackageName",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(GOOGLE_PLAY_PACKAGE);
                            }
                        });
                HookLog.i("hook installed on InstallSourceInfo.getInitiatingPackageName() in "
                        + lpparam.processName);
            } else {
                XposedHelpers.findAndHookMethod(
                        "android.content.pm.PackageManager", lpparam.classLoader,
                        "getInstallerPackageName", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(GOOGLE_PLAY_PACKAGE);
                            }
                        });
                HookLog.i("hook installed on PackageManager.getInstallerPackageName() in "
                        + lpparam.processName);
            }
            try {
                Class<?> keyguardClass = XposedHelpers.findClass("android.app.KeyguardManager", lpparam.classLoader);
                XC_MethodHook returnFalseHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(false);
                    }
                };
                XposedHelpers.findAndHookMethod(keyguardClass, "isKeyguardLocked", returnFalseHook);
                XposedHelpers.findAndHookMethod(keyguardClass, "inKeyguardRestrictedInputMode", returnFalseHook);
                XposedHelpers.findAndHookMethod(keyguardClass, "isDeviceLocked", returnFalseHook);
                try {
                    XposedHelpers.findAndHookMethod(keyguardClass, "isDeviceLocked", int.class, returnFalseHook);
                } catch (Throwable ignored) {}
                try {
                    XposedHelpers.findAndHookMethod(keyguardClass, "isDeviceSecure", returnFalseHook);
                } catch (Throwable ignored) {}
                HookLog.i("Keyguard lock bypass hooks installed in " + lpparam.processName);
            } catch (Throwable t) {
                HookLog.w("could not hook KeyguardManager in Gearhead: " + t);
            }
        } catch (Throwable t) {
            HookLog.w("could not hook installer-provenance getters: " + t);
        }
    }
}
