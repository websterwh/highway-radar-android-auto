package com.highwayradar.aa;

/**
 * Main-thread selection of current viewport cameras, plus cross-thread one-shot frame coalescing.
 */
final class ProjectionCameraPolicy {
    private boolean framePending;
    private int width;
    private int height;
    private Object selected;
    private boolean proven;
    private boolean ambiguous;

    synchronized boolean requestFrame() {
        if (framePending) return false;
        framePending = true;
        return true;
    }

    synchronized void consumeFrame() {
        framePending = false;
    }

    void beginSelection(int width, int height) {
        this.width = width;
        this.height = height;
        selected = null;
        proven = false;
        ambiguous = false;
    }

    void consider(Object camera, boolean ownsView, int cameraWidth, int cameraHeight) {
        if (camera == null
                || width <= 0
                || height <= 0
                || cameraWidth != width
                || cameraHeight != height) return;
        if (proven && !ownsView) return;
        if (ownsView && !proven) {
            selected = camera;
            proven = true;
            ambiguous = false;
            return;
        }
        if (ambiguous) return;
        if (selected != null && selected != camera) {
            selected = null;
            ambiguous = true;
            return;
        }
        selected = camera;
    }

    Object selected() {
        return selected;
    }

    boolean isProven() {
        return proven;
    }

    boolean isAmbiguous() {
        return ambiguous;
    }
}
