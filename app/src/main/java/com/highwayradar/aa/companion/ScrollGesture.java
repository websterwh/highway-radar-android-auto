package com.highwayradar.aa.companion;

final class ScrollGesture {
    interface Sink {
        void send(int action, float x, float y, long downTime, long eventTime);
    }

    private final Sink sink;
    private int width;
    private int height;
    private double pendingX;
    private double pendingY;
    private double x;
    private double y;
    private boolean active;
    private boolean framePending;
    private long downTime;
    private long lastEventTime = Long.MIN_VALUE;

    ScrollGesture(Sink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    void configure(int width, int height, long now) {
        cancel(now);
        this.width = width;
        this.height = height;
    }

    /** Returns true only when the caller must schedule the next frame. */
    boolean scroll(float distanceX, float distanceY) {
        if (width <= 0 || height <= 0
                || Float.isNaN(distanceX) || Float.isInfinite(distanceX)
                || Float.isNaN(distanceY) || Float.isInfinite(distanceY)) {
            return false;
        }
        if (distanceX == 0f && distanceY == 0f) return false;
        pendingX -= (double) distanceX;
        pendingY -= (double) distanceY;
        boolean schedule = !framePending;
        framePending = true;
        return schedule;
    }

    void flush(long now) {
        framePending = false;
        if (pendingX == 0.0 && pendingY == 0.0) {
            return;
        }
        long eventTime = Math.max(now, lastEventTime);
        if (!active) {
            x = Math.min(width / 2.0, width - 1.0);
            y = Math.min(height / 2.0, height - 1.0);
            downTime = eventTime;
            active = true;
            send(0, eventTime);
        }
        x = Math.max(0.0, Math.min(width - 1.0, x + pendingX));
        y = Math.max(0.0, Math.min(height - 1.0, y + pendingY));
        pendingX = 0.0;
        pendingY = 0.0;
        send(2, eventTime);
    }

    void end(long now) {
        flush(now);
        if (active) {
            send(1, Math.max(now, lastEventTime));
            active = false;
        }
    }

    void cancel(long now) {
        framePending = false;
        pendingX = 0.0;
        pendingY = 0.0;
        if (active) {
            send(3, Math.max(now, lastEventTime));
            active = false;
        }
    }

    private void send(int action, long eventTime) {
        lastEventTime = eventTime;
        sink.send(action, (float) x, (float) y, downTime, eventTime);
    }
}
