package com.highwayradar.aa;

public final class LiveMarker {

    public enum Kind {
        POLICE,
        AIRCRAFT,
        CAMERA
    }

    public final String id;
    public final Kind kind;
    public final String label;
    public final double lat;
    public final double lon;
    public final double headingDegrees;
    public final int altitudeFt;

    public LiveMarker(String id, Kind kind, String label, double lat, double lon,
            double headingDegrees, int altitudeFt) {
        this.id = id != null ? id : "";
        this.kind = kind != null ? kind : Kind.POLICE;
        this.label = label != null ? label : "";
        this.lat = lat;
        this.lon = lon;
        this.headingDegrees = headingDegrees;
        this.altitudeFt = altitudeFt;
    }
}
