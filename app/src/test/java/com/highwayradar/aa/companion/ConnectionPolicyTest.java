package com.highwayradar.aa.companion;

/** Opening the car app launches directly; this policy applies only to connection edges. */
public final class ConnectionPolicyTest {
    public static void main(String[] args) {
        check(false, true, false, true, ConnectionPolicy.Action.NONE);
        check(false, true, true, false, ConnectionPolicy.Action.START);
        check(false, true, true, true, ConnectionPolicy.Action.START);
        // A manually opened app still stops on disconnect with auto-start disabled.
        check(true, false, false, true, ConnectionPolicy.Action.STOP);
        check(true, false, true, true, ConnectionPolicy.Action.STOP);
        check(true, false, false, false, ConnectionPolicy.Action.NONE);
        check(true, false, true, false, ConnectionPolicy.Action.NONE);
        for (boolean start : new boolean[] {false, true}) {
            for (boolean stop : new boolean[] {false, true}) {
                check(true, true, start, stop, ConnectionPolicy.Action.NONE);
                check(false, false, start, stop, ConnectionPolicy.Action.NONE);
            }
        }
        System.out.println("Connection edges and independent auto-stop checks passed");
    }

    private static void check(
            boolean previous,
            boolean current,
            boolean start,
            boolean stop,
            ConnectionPolicy.Action expected) {
        ConnectionPolicy.Action actual = ConnectionPolicy.action(previous, current, start, stop);
        if (actual != expected)
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
