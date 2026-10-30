package com.highwayradar.aa;

import android.graphics.Bitmap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.concurrent.atomic.AtomicBoolean;

/** Removes the host's Compose primary-action elevation only for our rasterized settings icon. */
final class GearheadSettingsShadowHook {
    private GearheadSettingsShadowHook() {}

    static void install(ClassLoader loader) {
        try {
            Object flatElevation =
                    XposedHelpers.newInstance(loader.loadClass("bnq"), 0f, 0f, 0f, 0f, 0f);
            AtomicBoolean reported = new AtomicBoolean();
            // fzr.c = CarActionButton(... ButtonElevation ..., Composer, changed, defaults).
            XposedHelpers.findAndHookMethod(
                    "fzr",
                    loader,
                    "c",
                    "fzt",
                    "cih",
                    "fzs",
                    "hpg",
                    "cnf",
                    "bnq",
                    "ast",
                    boolean.class,
                    "dob",
                    "dob",
                    "bwt",
                    int.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object icon = XposedHelpers.getObjectField(param.args[0], "c");
                                if (icon == null || !"gdy".equals(icon.getClass().getName()))
                                    return;
                                Bitmap bitmap = (Bitmap) XposedHelpers.getObjectField(icon, "a");
                                if (!matchesSettingsIcon(bitmap)) return;
                                param.args[5] = flatElevation;
                                // Suppress the default-elevation argument and let Compose compare
                                // the explicit immutable zero-elevation value normally.
                                param.args[12] = ((Integer) param.args[12]) & ~0x20;
                                param.args[11] = ((Integer) param.args[11]) & ~0x30000;
                                if (reported.compareAndSet(false, true)) {
                                    XposedBridge.log(
                                            "HWRSettings: compact cog Compose shadow removed");
                                }
                            } catch (Throwable error) {
                                HookLog.warnOnce(
                                        "settings-shadow-model",
                                        "Settings action styling unavailable: " + error);
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.warnOnce(
                    "settings-shadow-install", "Settings shadow hook unavailable: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    android.view.ViewGroup.class,
                    "dispatchTouchEvent",
                    android.view.MotionEvent.class,
                    new XC_MethodHook() {
                        private final android.os.Handler handler =
                                new android.os.Handler(android.os.Looper.getMainLooper());
                        private Runnable longPressRunnable;
                        private boolean longPressTriggered;
                        private float downX, downY;

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                android.view.View view = (android.view.View) param.thisObject;
                                if (view.getRootView() != view
                                        || !view.isAttachedToWindow()
                                        || view.getWidth() <= 0
                                        || view.getHeight() <= 0) return;
                                android.view.MotionEvent event =
                                        (android.view.MotionEvent) param.args[0];
                                int action = event.getActionMasked();
                                if (action == android.view.MotionEvent.ACTION_DOWN) {
                                    HookLog.i(
                                            "Gearhead touch DOWN: view="
                                                    + view.getClass().getSimpleName()
                                                    + " isRoot="
                                                    + (view.getRootView() == view)
                                                    + " w="
                                                    + view.getWidth()
                                                    + " h="
                                                    + view.getHeight()
                                                    + " x="
                                                    + event.getX()
                                                    + " y="
                                                    + event.getY());
                                }
                                float density = view.getResources().getDisplayMetrics().density;
                                float buttonSize = 96 * density;
                                boolean inTopRight =
                                        event.getX() >= view.getWidth() - buttonSize
                                                && event.getY() <= buttonSize;
                                if (action == android.view.MotionEvent.ACTION_DOWN) {
                                    longPressTriggered = false;
                                    if (longPressRunnable != null) {
                                        handler.removeCallbacks(longPressRunnable);
                                        longPressRunnable = null;
                                    }
                                    if (inTopRight) {
                                        downX = event.getX();
                                        downY = event.getY();
                                        longPressRunnable =
                                                () -> {
                                                    longPressTriggered = true;
                                                    android.content.Intent backIntent =
                                                            new android.content.Intent(
                                                                            "com.highwayradar.aa.ACTION_BACK")
                                                                    .setPackage(
                                                                            "com.highwayradar.aa.companion")
                                                                    .addFlags(
                                                                            android.content.Intent
                                                                                    .FLAG_RECEIVER_FOREGROUND);
                                                    view.getContext().sendBroadcast(backIntent);
                                                    HookLog.i(
                                                            "HWRSettings: long-press on settings"
                                                                    + " gear sent ACTION_BACK");
                                                };
                                        handler.postDelayed(longPressRunnable, 450L);
                                    }
                                } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                                    if (longPressRunnable != null) {
                                        float dx = Math.abs(event.getX() - downX);
                                        float dy = Math.abs(event.getY() - downY);
                                        if (dx > 24 * density || dy > 24 * density) {
                                            handler.removeCallbacks(longPressRunnable);
                                            longPressRunnable = null;
                                        }
                                    }
                                } else if (action == android.view.MotionEvent.ACTION_UP
                                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                                    if (longPressRunnable != null) {
                                        handler.removeCallbacks(longPressRunnable);
                                        longPressRunnable = null;
                                    }
                                    if (longPressTriggered) {
                                        param.setResult(true);
                                        longPressTriggered = false;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            HookLog.warnOnce(
                    "settings-longpress-install",
                    "Could not install settings long-press hook: " + t);
        }
    }

    private static boolean matchesSettingsIcon(Bitmap bitmap) {
        return bitmap != null
                && !bitmap.isRecycled()
                && bitmap.getWidth() == 96
                && bitmap.getHeight() == 96
                && bitmap.getPixel(0, 0) == 0
                && bitmap.getPixel(48, 4) == 0xff232629
                && bitmap.getPixel(4, 48) == 0xff232629;
    }
}
