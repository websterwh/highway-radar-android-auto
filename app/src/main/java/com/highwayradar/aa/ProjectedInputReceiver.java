package com.highwayradar.aa;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inspector.WindowInspector;

import java.lang.ref.WeakReference;

/** Signature-protected input dispatch confined to this process's private-display windows. */
final class ProjectedInputReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.highwayradar.aa.PROJECTED_INPUT";
    private static final String PERMISSION =
            "com.highwayradar.aa.companion.permission.CONTROL_PROJECTED_APP";
    private static final String TARGET_PACKAGE = "com.highwayradar.app";
    private static final String COMPANION_PACKAGE = "com.highwayradar.aa.companion";
    private static final String DESCRIPTOR = "com.highwayradar.aa.ProjectedInputBridge";
    private static boolean installed;

    private static volatile WeakReference<Activity> activeActivity = new WeakReference<>(null);

    static void setActiveActivity(Activity activity) {
        activeActivity = new WeakReference<>(activity);
    }

    static void clearActiveActivity(Activity activity) {
        if (activeActivity.get() == activity) {
            activeActivity.clear();
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Point displaySize = new Point();
    private final int[] windowLocation = new int[2];
    private WeakReference<View> activeRoot = new WeakReference<>(null);
    private int activeDisplayId;
    private long activeDownTime;
    private long lastEventTime;
    private float lastX;
    private float lastY;

    static synchronized void install(Context applicationContext) {
        if (Build.VERSION.SDK_INT < 30 || installed) return;
        try {
            if (applicationContext == null
                    || !TARGET_PACKAGE.equals(applicationContext.getPackageName())) return;
            ProjectedInputReceiver receiver = new ProjectedInputReceiver();
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                applicationContext.registerReceiver(
                        receiver, filter, PERMISSION, receiver.main, Context.RECEIVER_EXPORTED);
            } else {
                applicationContext.registerReceiver(
                        receiver, filter, PERMISSION, receiver.main, Context.RECEIVER_EXPORTED);
            }
            installed = true;
            HookLog.i("Projected input receiver registered with signature sender permission");
        } catch (Throwable t) {
            HookLog.warnOnce(
                    "projected-input-registration", "Could not register projected input: " + t);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || !ACTION.equals(intent.getAction())) return;
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            Object reply = extras.get("reply");
            if (!(reply instanceof ResultReceiver)) return;
            int companionUid =
                    context.getPackageManager().getApplicationInfo(COMPANION_PACKAGE, 0).uid;
            Bundle result = new Bundle();
            result.putBinder("input_bridge", new InputBridge(companionUid));
            ((ResultReceiver) reply).send(0, result);
        } catch (Throwable t) {
            HookLog.warnOnce("projected-input-receive", "Could not receive projected input: " + t);
        }
    }

    private final class InputBridge extends Binder {
        private final int companionUid;

        InputBridge(int companionUid) {
            this.companionUid = companionUid;
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION + 1) {
                if (Binder.getCallingUid() != companionUid) {
                    throw new SecurityException(
                            "Projected input requires the installed companion UID");
                }
                data.enforceInterface(DESCRIPTOR);
                if ((flags & IBinder.FLAG_ONEWAY) == 0) {
                    throw new SecurityException("Projected input must be one-way");
                }
                if (data.dataAvail() != 4) {
                    throw new SecurityException("Invalid projected back parcel size");
                }
                final int displayId = data.readInt();
                if (data.dataAvail() != 0) {
                    throw new SecurityException("Unexpected projected back parcel data");
                }
                main.post(() -> sendBack(displayId));
                return true;
            }
            if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
            if (Binder.getCallingUid() != companionUid) {
                throw new SecurityException("Projected input requires the installed companion UID");
            }
            data.enforceInterface(DESCRIPTOR);
            if ((flags & IBinder.FLAG_ONEWAY) == 0) {
                throw new SecurityException("Projected input must be one-way");
            }
            if (data.dataAvail() != 32) {
                throw new SecurityException("Invalid projected input parcel size");
            }
            final int displayId = data.readInt();
            final int action = data.readInt();
            final float x = data.readFloat();
            final float y = data.readFloat();
            final long downTime = data.readLong();
            final long eventTime = data.readLong();
            if (data.dataAvail() != 0) {
                throw new SecurityException("Unexpected projected input parcel data");
            }
            // Never retain the pooled Parcel or touch window/gesture state on a Binder thread.
            main.post(() -> receive(displayId, action, x, y, downTime, eventTime));
            return true;
        }
    }

    private void sendBack(int displayId) {
        try {
            Activity activity = activeActivity.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                if ("com.highwayradar.app.ui.main_activity.MainActivity"
                        .equals(activity.getClass().getName())) {
                    HookLog.i("Back ignored: already at root MainActivity on display " + displayId);
                    return;
                }
                activity.onBackPressed();
                HookLog.i("Invoked onBackPressed() on " + activity.getClass().getName() + " on display " + displayId);
                return;
            }
            View root = selectRoot(displayId);
            if (!eligible(root, displayId)) return;
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
            root.dispatchKeyEvent(down);
            root.dispatchKeyEvent(up);
            HookLog.i("Dispatched KEYCODE_BACK to Highway Radar on display " + displayId);
        } catch (Throwable t) {
            HookLog.warnOnce("projected-back-dispatch", "Could not dispatch back key: " + t);
        }
    }

    private void receive(
            int displayId, int action, float x, float y, long downTime, long eventTime) {
        boolean gestureChanged = false;
        try {
            if (displayId <= 0
                    || !Float.isFinite(x)
                    || !Float.isFinite(y)
                    || x < 0
                    || y < 0
                    || downTime <= 0
                    || eventTime < downTime
                    || eventTime > SystemClock.uptimeMillis()) return;
            if (action != -1
                    && action != MotionEvent.ACTION_DOWN
                    && action != MotionEvent.ACTION_MOVE
                    && action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_CANCEL) return;

            boolean startsStroke = action == -1 || action == MotionEvent.ACTION_DOWN;
            View root = startsStroke ? selectRoot(displayId) : activeRoot.get();
            if (!eligible(root, displayId)) return;
            root.getDisplay().getRealSize(displaySize);
            if (x >= displaySize.x || y >= displaySize.y) return;
            if (!startsStroke
                    && (activeDisplayId != displayId
                            || activeDownTime != downTime
                            || eventTime < lastEventTime)) return;

            // No gesture state changes until every field and the destination are validated.
            gestureChanged = true;
            if (startsStroke) {
                cancelActive(eventTime);
                if (!eligible(root, displayId)) return;
                activeRoot = new WeakReference<>(root);
                activeDisplayId = displayId;
                activeDownTime = downTime;
            }
            lastX = x;
            lastY = y;
            lastEventTime = eventTime;
            if (action == -1) {
                dispatch(root, MotionEvent.ACTION_DOWN, x, y, downTime, eventTime);
                clearActive();
                if (eligible(root, displayId)) {
                    dispatch(root, MotionEvent.ACTION_UP, x, y, downTime, eventTime);
                }
            } else {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    clearActive();
                }
                dispatch(root, action, x, y, downTime, eventTime);
            }
        } catch (Throwable t) {
            if (gestureChanged) clearActive();
            HookLog.warnOnce(
                    "projected-input-dispatch", "Could not dispatch projected input: " + t);
        }
    }

    private static View selectRoot(int displayId) {
        if (Build.VERSION.SDK_INT < 29) return null;
        View last = null;
        View focused = null;
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (!eligible(root, displayId)) continue;
            last = root;
            if (root.hasWindowFocus()) focused = root;
        }
        return focused != null ? focused : last;
    }

    private static boolean eligible(View root, int displayId) {
        if (displayId <= 0
                || root == null
                || !root.isAttachedToWindow()
                || !root.isShown()
                || root.getWindowVisibility() != View.VISIBLE
                || !TARGET_PACKAGE.equals(root.getContext().getPackageName())) return false;
        Display display = root.getDisplay();
        return display != null
                && display.getDisplayId() == displayId
                && (display.getFlags() & Display.FLAG_PRIVATE) != 0;
    }

    private void cancelActive(long eventTime) {
        View root = activeRoot.get();
        int displayId = activeDisplayId;
        long downTime = activeDownTime;
        long cancelTime = Math.max(eventTime, lastEventTime);
        float x = lastX;
        float y = lastY;
        clearActive();
        if (downTime > 0 && eligible(root, displayId)) {
            dispatch(root, MotionEvent.ACTION_CANCEL, x, y, downTime, cancelTime);
        }
    }

    private void clearActive() {
        activeRoot.clear();
        activeDisplayId = 0;
        activeDownTime = 0;
        lastEventTime = 0;
    }

    private void dispatch(View root, int action, float x, float y, long downTime, long eventTime) {
        root.getLocationOnScreen(windowLocation);
        MotionEvent event =
                MotionEvent.obtain(
                        downTime,
                        eventTime,
                        action,
                        x - windowLocation[0],
                        y - windowLocation[1],
                        0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            root.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
