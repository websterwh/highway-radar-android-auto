package com.highwayradar.aa;

/** Latest-state throttle. Callers serialize access and supply elapsed-realtime milliseconds. */
final class AlertRelayPolicy {

    private static final long CHANGE_INTERVAL_MS = 500L;
    private static final long HEARTBEAT_INTERVAL_MS = 3000L;

    private String pendingJson;
    private String lastSentJson;
    private long lastSentAtMs;
    private long scheduledAtMs = -1L;

    void offer(String json) {
        pendingJson = json;
    }

    /** Returns a new callback deadline, or -1 when the existing callback suffices. */
    long reserveFlush(long now) {
        if (pendingJson == null) {
            return -1L;
        }
        long due = dueAt(now);
        // Never postpone an existing callback; a change may bring a heartbeat forward.
        if (scheduledAtMs >= 0L && scheduledAtMs <= due) {
            return -1L;
        }
        scheduledAtMs = due;
        return due;
    }

    String flush(long now) {
        scheduledAtMs = -1L;
        if (pendingJson == null || now < dueAt(now)) {
            return null;
        }
        String json = pendingJson;
        pendingJson = null;
        lastSentJson = json;
        lastSentAtMs = now;
        // Do not synthesize heartbeats forever after the hook source stops producing ticks.
        return json;
    }

    private long dueAt(long now) {
        if (lastSentJson == null) {
            return now;
        }
        return lastSentAtMs + (pendingJson.equals(lastSentJson)
                ? HEARTBEAT_INTERVAL_MS : CHANGE_INTERVAL_MS);
    }
}
