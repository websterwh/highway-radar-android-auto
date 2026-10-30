package com.highwayradar.aa.companion;

/** Connection edges, not car-screen visibility, drive automation. */
final class ConnectionPolicy {
    enum Action {
        NONE,
        START,
        STOP
    }

    static Action action(
            boolean wasConnected, boolean connected, boolean autoStart, boolean autoStop) {
        if (wasConnected == connected) return Action.NONE;
        if (connected) return autoStart ? Action.START : Action.NONE;
        return autoStop ? Action.STOP : Action.NONE;
    }
}
