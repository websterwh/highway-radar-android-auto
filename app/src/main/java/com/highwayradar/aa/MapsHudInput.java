package com.highwayradar.aa;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Consumes only a stroke beginning inside the live, actionable detector HUD. */
final class MapsHudInput {
    private static boolean installed;
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor();
    private static final int[] ROOT_LOCATION = new int[2];
    private static final int[] MAP_LOCATION = new int[2];
    private static WeakReference<View> strokeRoot = new WeakReference<>(null);
    private static float downX, downY;
    private static boolean cancelled;

    static void install() {
        if (installed) return;
        try {
            XC_MethodHook touch =
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            View root = (View) param.thisObject;
                            View map = MapsProjectionOverlay.hudViewport();
                            if (map == null || map.getRootView() != root) return;
                            MotionEvent event = (MotionEvent) param.args[0];
                            root.getLocationOnScreen(ROOT_LOCATION);
                            map.getLocationOnScreen(MAP_LOCATION);
                            float x = event.getX() + ROOT_LOCATION[0] - MAP_LOCATION[0];
                            float y = event.getY() + ROOT_LOCATION[1] - MAP_LOCATION[1];
                            int action = event.getActionMasked();
                            if (action == MotionEvent.ACTION_DOWN) {
                                strokeRoot.clear();
                                if (!MapsProjectionOverlay.hudHitTest(x, y)) return;
                                strokeRoot = new WeakReference<>(root);
                                downX = x;
                                downY = y;
                                cancelled = false;
                            }
                            if (strokeRoot.get() != root) return;
                            int slop =
                                    ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
                            if (event.getPointerCount() != 1
                                    || Math.abs(x - downX) > slop
                                    || Math.abs(y - downY) > slop) {
                                cancelled = true;
                            }
                            if (action == MotionEvent.ACTION_UP) {
                                strokeRoot.clear();
                                if (!cancelled && MapsProjectionOverlay.hudHitTest(x, y)) {
                                    requestMute(root.getContext().getApplicationContext());
                                }
                            } else if (action == MotionEvent.ACTION_CANCEL) {
                                strokeRoot.clear();
                            }
                            // Outside-panel strokes never enter this branch. Maps still owns
                            // all ordinary pan/zoom/tap handling and all its native controls.
                            param.setResult(true);
                        }
                    };
            XposedHelpers.findAndHookMethod(
                    ViewGroup.class, "dispatchTouchEvent", MotionEvent.class, touch);
            XposedHelpers.findAndHookMethod(
                    View.class, "dispatchTouchEvent", MotionEvent.class, touch);
            installed = true;
        } catch (Throwable error) {
            HookLog.warnOnce("hud-input-install", "Could not install detector HUD input: " + error);
        }
    }

    private static void requestMute(Context app) {
        REQUESTS.execute(
                () -> {
                    try {
                        Bundle result =
                                app.getContentResolver()
                                        .call(
                                                Uri.parse(
                                                        "content://com.highwayradar.aa.companion.settings"),
                                                "muteDetector",
                                                null,
                                                null);
                        de.robv.android.xposed.XposedBridge.log(
                                "Highway Radar: detector HUD mute request accepted="
                                        + (result != null && result.getBoolean("accepted")));
                    } catch (RuntimeException error) {
                        HookLog.warnOnce(
                                "hud-mute-request", "Could not request detector mute: " + error);
                    }
                });
    }
}
