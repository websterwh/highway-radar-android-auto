package com.highwayradar.aa.companion;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.util.ArrayDeque;

/** Bootstraps a permission-protected, one-way input bridge inside the projected app. */
final class ProjectedInputSession implements AutoCloseable {
    private static final String TAG = "HighwayRadarInput";
    private static final String ACTION = "com.highwayradar.aa.PROJECTED_INPUT";
    private static final String DESCRIPTOR = "com.highwayradar.aa.ProjectedInputBridge";
    private static final int MAX_PENDING_INPUTS = 32;
    private static final long CONNECT_TIMEOUT_MS = 1000L;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // All connection and gesture state is main-thread confined, including reply/death callbacks.
    private final ArrayDeque<PendingInput> pendingInputs = new ArrayDeque<>();
    private ScrollGesture scrollGesture = new ScrollGesture(this::sendMotionEvent);
    private IBinder bridge;
    private IBinder.DeathRecipient bridgeDeath;
    private PendingInput deliveredStroke;
    private long connectionGeneration;
    private boolean connecting;
    private long outgoingDownTime;
    private int displayId = -1;
    private int width;
    private int height;
    private boolean active;
    private boolean closed;
    private boolean scrollPending;

    private final Runnable connectTimeoutTask =
            () -> {
                if (connecting) resetConnection();
            };
    private final Runnable scrollFlushTask =
            () -> {
                if (active) scrollGesture.flush(SystemClock.uptimeMillis());
            };
    private final Runnable scrollEndTask =
            () -> {
                if (active) {
                    scrollGesture.end(SystemClock.uptimeMillis());
                    scrollPending = false;
                }
            };

    ProjectedInputSession(Context context) {
        this.context = context.getApplicationContext();
    }

    void configure(int displayId, int width, int height) {
        if (closed) return;
        cancelDrag();
        resetConnection();
        this.displayId = displayId;
        this.width = width;
        this.height = height;
        active = displayId > 0 && width > 0 && height > 0;
        scrollGesture.configure(width, height, SystemClock.uptimeMillis());
    }

    void tap(float x, float y) {
        if (!active
                || !Float.isFinite(x)
                || !Float.isFinite(y)
                || x < 0
                || y < 0
                || x >= width
                || y >= height) return;
        cancelDrag();
        long now = SystemClock.uptimeMillis();
        sendMotionEvent(-1, x, y, now, now);
    }

    void sendBack() {
        if (!active || closed || displayId <= 0) return;
        if (bridge != null) {
            transmitBack();
            return;
        }
        if (!connecting) connect();
    }

    void scroll(float distanceX, float distanceY) {
        if (!active || !Float.isFinite(distanceX) || !Float.isFinite(distanceY)) return;
        if (distanceX == 0f && distanceY == 0f) return;
        boolean scheduleFrame = scrollGesture.scroll(distanceX, distanceY);
        scrollPending = true;
        // Keep one fixed frame deadline. Debouncing it on every input event
        // starves continuous gestures whose events arrive faster than 16 ms.
        if (scheduleFrame) mainHandler.postDelayed(scrollFlushTask, 16L);
        mainHandler.removeCallbacks(scrollEndTask);
        mainHandler.postDelayed(scrollEndTask, 80L);
    }

    private void cancelDrag() {
        mainHandler.removeCallbacks(scrollFlushTask);
        mainHandler.removeCallbacks(scrollEndTask);
        if (scrollPending) {
            scrollGesture.cancel(SystemClock.uptimeMillis());
            scrollPending = false;
        }
    }

    private void sendMotionEvent(int action, float x, float y, long downTime, long eventTime) {
        if (!active || closed) return;
        if (!pendingInputs.isEmpty()
                && SystemClock.uptimeMillis() - pendingInputs.peekFirst().eventTime
                        > CONNECT_TIMEOUT_MS) {
            resetConnection();
        }
        // A failed/expired connection must never replay a suffix of a delivered stroke.
        if (action == MotionEvent.ACTION_DOWN) outgoingDownTime = downTime;
        else if (action != -1 && outgoingDownTime != downTime) return;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            outgoingDownTime = 0;
        }
        PendingInput input = new PendingInput(displayId, action, x, y, downTime, eventTime);
        if (bridge != null) {
            transmit(input);
            return;
        }
        if (pendingInputs.size() == MAX_PENDING_INPUTS) {
            resetConnection();
            return;
        }
        pendingInputs.addLast(input);
        if (!connecting) connect();
    }

    private void connect() {
        connecting = true;
        final long generation = ++connectionGeneration;
        ResultReceiver reply =
                new ResultReceiver(mainHandler) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle result) {
                        if (!connecting || generation != connectionGeneration || !active || closed)
                            return;
                        IBinder candidate =
                                result == null ? null : result.getBinder("input_bridge");
                        if (candidate == null) return;
                        IBinder.DeathRecipient death =
                                () ->
                                        mainHandler.post(
                                                () -> {
                                                    if (bridge == candidate
                                                            && generation == connectionGeneration)
                                                        resetConnection();
                                                });
                        try {
                            candidate.linkToDeath(death, 0);
                        } catch (RemoteException | RuntimeException error) {
                            resetConnection();
                            return;
                        }
                        bridge = candidate;
                        bridgeDeath = death;
                        connecting = false;
                        mainHandler.removeCallbacks(connectTimeoutTask);
                        // Validate the entire backlog before delivering any of a queued stroke.
                        if (!pendingInputs.isEmpty()
                                && SystemClock.uptimeMillis() - pendingInputs.peekFirst().eventTime
                                        > CONNECT_TIMEOUT_MS) {
                            resetConnection();
                            return;
                        }
                        while (bridge != null && !pendingInputs.isEmpty()) {
                            PendingInput input = pendingInputs.removeFirst();
                            if (SystemClock.uptimeMillis() - input.eventTime > CONNECT_TIMEOUT_MS) {
                                resetConnection();
                                break;
                            }
                            if (!transmit(input)) break;
                        }
                    }
                };
        mainHandler.postDelayed(connectTimeoutTask, CONNECT_TIMEOUT_MS);
        try {
            // Parcel as the framework type. Sending our anonymous subclass directly
            // makes the remote app try to load a companion-only class while unmarshalling.
            Parcel receiverParcel = Parcel.obtain();
            ResultReceiver remoteReply;
            try {
                reply.writeToParcel(receiverParcel, 0);
                receiverParcel.setDataPosition(0);
                remoteReply = ResultReceiver.CREATOR.createFromParcel(receiverParcel);
            } finally {
                receiverParcel.recycle();
            }
            // Only bootstrap uses a broadcast. The receiver's existing signature permission
            // authenticates us; requiring a receiver permission would reject Highway Radar.
            context.sendBroadcast(
                    new Intent(ACTION)
                            .setPackage("com.highwayradar.app")
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            .putExtra("reply", remoteReply));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not bootstrap projected input", error);
            resetConnection();
        }
    }

    private boolean transmit(PendingInput input) {
        // Remember a possibly delivered DOWN too: a failed transact is not proof that
        // the remote side saw nothing. Reset makes one best-effort CANCEL, never a replay.
        if (input.action == MotionEvent.ACTION_DOWN || input.action == MotionEvent.ACTION_MOVE) {
            deliveredStroke = input;
        }
        try {
            transact(bridge, input);
            if (input.action == -1
                    || input.action == MotionEvent.ACTION_UP
                    || input.action == MotionEvent.ACTION_CANCEL) {
                deliveredStroke = null;
            }
            return true;
        } catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "Could not send projected input", error);
            resetConnection();
            return false;
        }
    }

    private static void transact(IBinder destination, PendingInput input) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(input.displayId);
            data.writeInt(input.action);
            data.writeFloat(input.x);
            data.writeFloat(input.y);
            data.writeLong(input.downTime);
            data.writeLong(input.eventTime);
            if (!destination.transact(
                    IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)) {
                throw new RemoteException("Projected input transaction rejected");
            }
        } finally {
            data.recycle();
        }
    }

    private void transmitBack() {
        if (bridge == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(displayId);
            bridge.transact(IBinder.FIRST_CALL_TRANSACTION + 1, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "Could not send back key", error);
            resetConnection();
        } finally {
            data.recycle();
        }
    }

    private void resetConnection() {
        ++connectionGeneration;
        connecting = false;
        mainHandler.removeCallbacks(connectTimeoutTask);
        pendingInputs.clear();
        PendingInput stroke = deliveredStroke;
        deliveredStroke = null;
        if (bridge != null && stroke != null) {
            try {
                transact(
                        bridge,
                        new PendingInput(
                                stroke.displayId,
                                MotionEvent.ACTION_CANCEL,
                                stroke.x,
                                stroke.y,
                                stroke.downTime,
                                SystemClock.uptimeMillis()));
            } catch (RemoteException | RuntimeException error) {
                Log.w(TAG, "Could not cancel disconnected projected input", error);
            }
        }
        if (bridge != null && bridgeDeath != null) bridge.unlinkToDeath(bridgeDeath, 0);
        bridge = null;
        bridgeDeath = null;
        mainHandler.removeCallbacks(scrollFlushTask);
        mainHandler.removeCallbacks(scrollEndTask);
        scrollPending = false;
        outgoingDownTime = 0;
        // Replace rather than cancel: a failing sink may be inside ScrollGesture.flush().
        // Its remaining MOVE is rejected above, and no failed stroke is retried.
        scrollGesture = new ScrollGesture(this::sendMotionEvent);
        scrollGesture.configure(width, height, SystemClock.uptimeMillis());
    }

    @Override
    public void close() {
        if (closed) return;
        cancelDrag();
        resetConnection();
        active = false;
        closed = true;
        displayId = -1;
    }

    private static final class PendingInput {
        final int displayId;
        final int action;
        final float x;
        final float y;
        final long downTime;
        final long eventTime;

        PendingInput(int displayId, int action, float x, float y, long downTime, long eventTime) {
            this.displayId = displayId;
            this.action = action;
            this.x = x;
            this.y = y;
            this.downTime = downTime;
            this.eventTime = eventTime;
        }
    }
}
