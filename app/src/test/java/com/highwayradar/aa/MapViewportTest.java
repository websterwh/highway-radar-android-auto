package com.highwayradar.aa;

public final class MapViewportTest {
    public static void main(String[] args) {
        near(1f, MapViewport.scale(800, 800));
        near(0.5f, MapViewport.scale(400, 800));
        near(200f, 400f * MapViewport.scale(400, 800));
        near(75f, 150f * MapViewport.scale(200, 400));
        near(0f, MapViewport.scale(0, 800));
        near(0f, MapViewport.scale(800, 0));
        near(0f, MapViewport.heading(0f, 0.5f, 1f));
        near(90f, MapViewport.heading(90f, 0.5f, 1f));
        near(-90f, MapViewport.heading(270f, 0.5f, 1f));
        near(45f, MapViewport.heading(45f, 0.5f, 0.5f));
        near(26.56505f, MapViewport.heading(45f, 0.5f, 1f));
        System.out.println("Viewport marker anchoring checks passed");
    }

    private static void near(float expected, float actual) {
        if (Math.abs(expected - actual) > 0.001f) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
