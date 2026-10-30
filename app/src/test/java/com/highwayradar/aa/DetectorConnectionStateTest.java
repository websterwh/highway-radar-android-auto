package com.highwayradar.aa;

public final class DetectorConnectionStateTest {
    public static void main(String[] args) {
        if (!DetectorConnectionState.isAttowaveConnected("wb.o")) {
            throw new AssertionError("Native Connected state must be connected");
        }
        // Native md.a.getStatus(): p=Connecting, s=Disconnected, q=Connection
        // failed, r=Connection lost, t=Disconnecting. Several make l() true.
        for (String state :
                new String[] {"wb.p", "wb.s", "wb.q", "wb.r", "wb.t", null, "unknown"}) {
            if (DetectorConnectionState.isAttowaveConnected(state)) {
                throw new AssertionError("Not an active connection: " + state);
            }
        }
        System.out.println("Native detector connection-state checks passed");
    }
}
