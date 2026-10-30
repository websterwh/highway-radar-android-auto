package com.highwayradar.aa;

/** Standalone JVM checks for the aircraft heading projection, without a Maps process. */
public final class MapsProjectionProbeTest {
    public static void main(String[] args) {
        double[] north = MapsProjectionProbe.destinationPoint(0, 0, 30, 0);
        check(north[0] > 0 && Math.abs(north[1]) < 1e-9, "North heading");
        double[] east = MapsProjectionProbe.destinationPoint(0, 0, 30, 90);
        check(east[1] > 0 && Math.abs(east[0]) < 1e-9, "East heading");
        double[] west = MapsProjectionProbe.destinationPoint(0, -179.99999, 30, 270);
        check(west[1] > 179 && west[1] < 180, "Westward dateline wrapping");
        double[] wrapEast = MapsProjectionProbe.destinationPoint(0, 179.99999, 30, 90);
        check(wrapEast[1] < -179 && wrapEast[1] >= -180, "Eastward dateline wrapping");
        for (double lat : new double[]{-89.999, 0, 47, 89.999}) {
            for (double heading : new double[]{0, 90, 180, 270, 360}) {
                double[] point = MapsProjectionProbe.destinationPoint(lat, -122, 30, heading);
                check(Double.isFinite(point[0]) && Math.abs(point[0]) <= 90
                        && Double.isFinite(point[1]) && point[1] >= -180 && point[1] < 180,
                        "Finite geographic coordinate");
                check(Math.abs(distance(lat, -122, point[0], point[1]) - 30) < 0.02,
                        "Heading point must remain 30 meters ahead");
            }
        }
        System.out.println("Aircraft heading geometry checks passed");
    }

    private static double distance(double lat, double lon, double otherLat, double otherLon) {
        double a = Math.pow(Math.sin(Math.toRadians(otherLat - lat) / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(otherLat))
                * Math.pow(Math.sin(Math.toRadians(otherLon - lon) / 2), 2);
        return 6_371_008.8 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
