package com.highwayradar.aa;

public final class LiveSignal {

    public final String band;
    public final double frequencyGhz;
    public final int strength;
    public final int rawStrength;
    public final String direction;
    public final boolean front;
    public final boolean muted;
    public final long receivedAtMs;

    public LiveSignal(String band, double frequencyGhz, int strength, int rawStrength,
            String direction, boolean front, boolean muted, long receivedAtMs) {
        this.band = band != null ? band : "K";
        this.frequencyGhz = frequencyGhz;
        this.strength = strength;
        this.rawStrength = rawStrength;
        this.direction = direction != null ? direction : "NOT_AVAILABLE";
        this.front = front;
        this.muted = muted;
        this.receivedAtMs = receivedAtMs;
    }
}
