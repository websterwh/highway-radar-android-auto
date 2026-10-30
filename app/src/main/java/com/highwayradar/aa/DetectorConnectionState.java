package com.highwayradar.aa;

/** Mirrors the state discriminator used by Highway Radar's native status widget. */
final class DetectorConnectionState {
    private DetectorConnectionState() {}

    static boolean isAttowaveConnected(String stateClassName) {
        return "wb.o".equals(stateClassName);
    }
}
