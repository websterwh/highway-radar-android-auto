package com.highwayradar.aa;

/** Projection coordinates use the projector viewport, not necessarily the View's pixel size. */
final class MapViewport {
    private MapViewport() {}

    static float scale(int viewSize, int projectionSize) {
        return viewSize > 0 && projectionSize > 0 ? (float) viewSize / projectionSize : 0f;
    }

    static float heading(float degrees, float scaleX, float scaleY) {
        double angle = Math.toRadians(degrees);
        return (float)
                Math.toDegrees(Math.atan2(Math.sin(angle) * scaleX, Math.cos(angle) * scaleY));
    }
}
