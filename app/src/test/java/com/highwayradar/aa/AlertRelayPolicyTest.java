package com.highwayradar.aa;

import java.util.ArrayList;
import java.util.List;

/** Standalone regression checks: compile with AlertRelayPolicy.java and run main (no Android). */
public final class AlertRelayPolicyTest {

    public static void main(String[] args) {
        changedBurst();
        identicalHeartbeat();
        reversion();
        emptyTransition();
        heartbeatBroughtForward();
        monotonicClockOnly();
        System.out.println("AlertRelayPolicyTest: all checks passed");
    }

    private static void changedBurst() {
        Harness h = new Harness();
        h.offer("A", 0L);
        h.run(0L);
        h.offer("B", 100L);
        for (long now = 101L; now < 500L; now++) {
            h.offer("C", now);
            equal(500L, h.queuedAt, "burst deadline must not move");
        }
        equal(2, h.posts, "one initial callback and one trailing callback");
        h.run(499L);
        equal(1, h.sent.size(), "no early change");
        h.run(500L);
        equal("C", h.sent.get(1), "final burst value");
        equal(-1L, h.queuedAt, "no autonomous stale heartbeat");
    }

    private static void identicalHeartbeat() {
        Harness h = new Harness();
        h.offer("A", 0L);
        h.run(0L);
        for (long now = 1L; now < 3000L; now++) {
            h.offer("A", now);
        }
        equal(2, h.posts, "identical ticks must not duplicate callbacks");
        h.run(2999L);
        equal(1, h.sent.size(), "no heartbeat before 3000 ms");
        h.run(3000L);
        equal("A", h.sent.get(1), "identical heartbeat delivered");
        h.offer("A", 3001L);
        equal(6000L, h.queuedAt, "next heartbeat deadline");
        h.run(6000L);
        equal(3, h.sent.size(), "second identical heartbeat delivered");
    }

    private static void reversion() {
        Harness h = new Harness();
        h.offer("A", 0L);
        h.run(0L);
        h.offer("B", 100L);
        h.offer("A", 200L);
        equal(500L, h.queuedAt, "reversion does not postpone queued flush");
        h.run(500L);
        equal(1, h.sent.size(), "obsolete B and premature A suppressed");
        equal(3000L, h.queuedAt, "reversion still heartbeats");
        h.run(3000L);
        equal("A", h.sent.get(1), "reversion heartbeat is latest value");
    }

    private static void emptyTransition() {
        Harness h = new Harness();
        h.offer("[{}]", 0L);
        h.run(0L);
        h.offer("[]", 10L);
        h.run(500L);
        equal("[]", h.sent.get(1), "empty list clears previous alerts");
    }

    private static void heartbeatBroughtForward() {
        Harness h = new Harness();
        h.offer("A", 0L);
        h.run(0L);
        h.offer("A", 1L);
        equal(3000L, h.queuedAt, "heartbeat queued");
        h.offer("B", 100L);
        equal(500L, h.queuedAt, "change accelerates heartbeat callback");
        equal(1, h.replacements, "old callback replaced, not duplicated");
        h.run(500L);
        equal("B", h.sent.get(1), "change delivered without waiting for heartbeat");
    }

    private static void monotonicClockOnly() {
        // No wall clock API or Android dependency: deadlines use only this supplied uptime.
        Harness h = new Harness();
        long start = 8_000_000L;
        h.offer("A", start);
        h.run(start);
        h.offer("B", start + 10L);
        equal(start + 500L, h.queuedAt, "deadline relative to supplied monotonic clock");
        h.run(start + 500L);
        equal("B", h.sent.get(1), "nonzero clock origin");
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    /** Mirrors the relay's single callback replacement and dispatch contract. */
    private static final class Harness {
        final AlertRelayPolicy policy = new AlertRelayPolicy();
        final List<String> sent = new ArrayList<>();
        long queuedAt = -1L;
        int posts;
        int replacements;

        void offer(String json, long now) {
            policy.offer(json);
            schedule(now);
        }

        void schedule(long now) {
            long due = policy.reserveFlush(now);
            if (due >= 0L) {
                if (queuedAt >= 0L) {
                    replacements++;
                }
                queuedAt = Math.max(now, due);
                posts++;
            }
        }

        void run(long now) {
            if (queuedAt < 0L || now < queuedAt) {
                return;
            }
            queuedAt = -1L;
            String json = policy.flush(now);
            schedule(now);
            if (json != null) {
                sent.add(json);
            }
        }
    }
}
