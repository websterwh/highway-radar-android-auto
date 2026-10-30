package com.highwayradar.aa.companion;

import java.util.ArrayList;
import java.util.List;

public class ScrollGestureTest {
    static class Event {
        final int action;
        final float x;
        final float y;
        final long downTime;
        final long eventTime;

        Event(int action, float x, float y, long downTime, long eventTime) {
            this.action = action;
            this.x = x;
            this.y = y;
            this.downTime = downTime;
            this.eventTime = eventTime;
        }
    }

    public static void main(String[] args) {
        List<Event> events = new ArrayList<>();
        ScrollGesture gesture = new ScrollGesture((action, x, y, downTime, eventTime) -> {
            events.add(new Event(action, x, y, downTime, eventTime));
        });

        gesture.configure(1000, 1000, 1000L);

        // 1. Single scroll without flush does nothing yet
        gesture.scroll(10f, 20f);
        if (!events.isEmpty()) throw new AssertionError("Expected no events before flush");

        // 2. Flush emits DOWN and MOVE
        gesture.flush(1016L);
        if (events.size() != 2) throw new AssertionError("Expected 2 events (DOWN, MOVE), got " + events.size());
        if (events.get(0).action != 0) throw new AssertionError("Expected DOWN, got " + events.get(0).action);
        if (events.get(1).action != 2) throw new AssertionError("Expected MOVE, got " + events.get(1).action);
        long downTime = events.get(0).downTime;
        if (downTime != 1016L || events.get(1).downTime != downTime
                || events.get(1).eventTime != 1016L) {
            throw new AssertionError("DOWN and MOVE must share the stroke's uptime origin");
        }
        if (events.get(0).x != 500f || events.get(0).y != 500f) {
            throw new AssertionError("Expected center DOWN at 500,500, got " + events.get(0).x + "," + events.get(0).y);
        }
        // distanceX is positive when scrolling right (content moves left), so x moves by -distanceX
        if (events.get(1).x != 490f || events.get(1).y != 480f) {
            throw new AssertionError("Expected MOVE to 490,480, got " + events.get(1).x + "," + events.get(1).y);
        }

        // 3. Second scroll & flush continues the SAME stroke
        events.clear();
        gesture.scroll(-50f, 0f);
        gesture.flush(1032L);
        if (events.size() != 1) throw new AssertionError("Expected 1 MOVE event, got " + events.size());
        if (events.get(0).action != 2) throw new AssertionError("Expected MOVE, got " + events.get(0).action);
        if (events.get(0).downTime != downTime || events.get(0).eventTime != 1032L) {
            throw new AssertionError("Continuation must preserve downTime and advance eventTime");
        }
        if (events.get(0).x != 540f || events.get(0).y != 480f) {
            throw new AssertionError("Expected MOVE to 540,480, got " + events.get(0).x + "," + events.get(0).y);
        }

        // 4. End emits UP
        events.clear();
        gesture.end(1100L);
        if (events.size() != 1) throw new AssertionError("Expected 1 UP event, got " + events.size());
        if (events.get(0).action != 1) throw new AssertionError("Expected UP, got " + events.get(0).action);
        if (events.get(0).downTime != downTime || events.get(0).eventTime != 1100L) {
            throw new AssertionError("UP must end the same timestamped stroke");
        }

        events.clear();
        gesture.scroll(Float.NaN, 1f);
        gesture.scroll(1f, Float.POSITIVE_INFINITY);
        gesture.flush(1110L);
        if (!events.isEmpty()) throw new AssertionError("Invalid distances must not start a stroke");

        gesture.scroll(2000f, -2000f);
        gesture.flush(1116L);
        Event clamped = events.get(1);
        if (clamped.x != 0f || clamped.y != 999f) {
            throw new AssertionError("Motion must stay within the configured display");
        }
        gesture.cancel(1200L);
        Event cancel = events.get(2);
        if (cancel.action != 3 || cancel.downTime != 1116L || cancel.eventTime != 1200L) {
            throw new AssertionError("Cancellation must finish the active timestamped stroke");
        }

        events.clear();
        gesture.configure(200, 100, 1200L);
        gesture.scroll(5f, 7f);
        gesture.scroll(10f, 13f);
        gesture.flush(1190L);
        if (events.size() != 2 || events.get(1).x != 85f || events.get(1).y != 30f
                || events.get(1).eventTime != 1200L) {
            throw new AssertionError("Coalescing must use the new bounds and monotonic event time");
        }
        gesture.end(1180L);
        if (events.get(2).eventTime != 1200L || events.get(2).downTime != 1200L) {
            throw new AssertionError("A backwards clock sample must not regress UP timestamps");
        }

        events.clear();
        gesture.configure(0, 0, 1300L);
        gesture.scroll(10f, 10f);
        gesture.flush(1316L);
        if (!events.isEmpty()) throw new AssertionError("Detached displays must not emit strokes");

        testContinuousFrames();
        testFrameCancellation();
        System.out.println("ALL SCROLL GESTURE TESTS PASSED!");
    }

    private static void testContinuousFrames() {
        List<Event> events = new ArrayList<>();
        ScrollGesture gesture = new ScrollGesture((a, x, y, down, time) ->
                events.add(new Event(a, x, y, down, time)));
        gesture.configure(10000, 10000, 2000L);
        long due = Long.MAX_VALUE;
        int frames = 0;
        // 125 Hz input must not postpone the already queued 16 ms frame.
        for (long now = 2000L; now < 3000L; now++) {
            if (now == due) {
                gesture.flush(now);
                due = Long.MAX_VALUE;
                frames++;
            }
            if ((now - 2000L) % 8L == 0L && gesture.scroll(1f, 0f)) {
                if (due != Long.MAX_VALUE) throw new AssertionError("Duplicate queued frame");
                due = now + 16L;
            }
        }
        gesture.end(3000L);
        if (frames != 62 || events.get(0).eventTime != 2016L) {
            throw new AssertionError("Continuous input starved or rescheduled frame delivery");
        }
        long previousMove = 2000L;
        int downs = 0;
        for (Event event : events) {
            if (event.action == 0) downs++;
            if (event.action == 2) {
                if (event.eventTime - previousMove > 16L) {
                    throw new AssertionError("Pending motion waited more than one frame");
                }
                previousMove = event.eventTime;
            }
            if (event.downTime != 2016L) throw new AssertionError("Stroke origin changed");
        }
        Event up = events.get(events.size() - 1);
        if (downs != 1 || up.action != 1 || up.x != 4875f) {
            throw new AssertionError("Coalescing lost movement or split the active stroke");
        }
        System.out.println("125 Hz input: 62 scheduled frames, no frame deadline postponed");
    }

    private static void testFrameCancellation() {
        List<Event> events = new ArrayList<>();
        ScrollGesture gesture = new ScrollGesture((a, x, y, down, time) ->
                events.add(new Event(a, x, y, down, time)));
        gesture.configure(100, 100, 4000L);
        if (gesture.scroll(0f, 0f) || gesture.scroll(Float.NaN, 1f)) {
            throw new AssertionError("Empty or invalid input requested a frame");
        }
        if (!gesture.scroll(1f, 0f) || gesture.scroll(2f, 0f)) {
            throw new AssertionError("A burst must request exactly one frame");
        }
        gesture.cancel(4008L);
        if (!gesture.scroll(3f, 0f)) throw new AssertionError("Cancellation left a queued frame");
        gesture.flush(4024L);
        if (events.get(1).x != 47f) throw new AssertionError("Cancelled motion leaked into new stroke");
        gesture.configure(200, 200, 4030L);
        if (!gesture.scroll(4f, 0f)) throw new AssertionError("Reconfigure left a queued frame");
        gesture.flush(4046L);
        if (events.get(events.size() - 1).x != 96f) {
            throw new AssertionError("Reconfigure retained the old stroke bounds");
        }
    }
}
