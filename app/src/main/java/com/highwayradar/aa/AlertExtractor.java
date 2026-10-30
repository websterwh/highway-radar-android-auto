package com.highwayradar.aa;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one live {@code com.highwayradar.app.support.alerts.Alert} into the flat map that is
 * relayed to the companion app.
 *
 * <p>Every field is read by member name through {@link Reflect}; anything that is missing, renamed
 * or throws is relayed as {@code null} rather than failing the tick or the host app. The concrete
 * class name and the human-readable list item text are both relayed in full, so the companion app
 * can refine its own classification once the real member names are confirmed on a device.
 */
final class AlertExtractor {

    /**
     * Kotlin names a boolean {@code isMuted} property {@code isMuted()} but a {@code muted} one
     * {@code getMuted()}, so both spellings are tried.
     */
    private static final String[] MUTED_ACCESSORS = {"isMuted", "getMuted"};

    private AlertExtractor() {}

    static Map<String, Object> extract(Object alert) {
        Class<?> alertType = alert.getClass();
        ClassIntrospector.dumpHierarchyOnce(alertType);

        Object marker = Reflect.call(alert, "getMapMarker");
        Object listItem = Reflect.call(alert, "getListItem");
        String title = Reflect.asText(Reflect.call(listItem, "getTitle"));
        String subtitle = Reflect.asText(Reflect.call(listItem, "getSubtitle"));

        Map<?, ?> dumped = null;
        try {
            Object dumpResult = Reflect.call(alert, "dumpToJson");
            if (dumpResult instanceof Map) {
                dumped = (Map<?, ?>) dumpResult;
            }
        } catch (Throwable ignored) {}

        String band = null;
        Double frequency = null;
        if (dumped != null) {
            Object b = dumped.get("band");
            if (b != null) band = b.toString().replace("\"", "").trim();
            Object f = dumped.get("frequency");
            if (f instanceof Number) {
                frequency = ((Number) f).doubleValue();
            } else if (f != null) {
                try {
                    frequency = Double.parseDouble(f.toString().replace("\"", "").trim());
                } catch (Exception ignored) {}
            }
        }

        if (band != null && title == null) {
            title = band + (frequency != null ? String.format(Locale.US, " %.4f GHz", frequency) : " Radar");
        }

        Double[] coordinate = readCoordinate(marker, alert);
        String id = Reflect.asText(Reflect.call(alert, "getId"));

        String category = (band != null || alertType.getName().startsWith("lb."))
                ? "radar_detector"
                : categorize(id, alertType.getSimpleName(), title, subtitle);

        Double directionRad = Reflect.asDouble(Reflect.call(marker, "getDirectionRad"));
        if (directionRad == null) {
            directionRad = Reflect.asDouble(Reflect.call(listItem, "getDirectionRad", "getBearingRad"));
        }
        if (directionRad == null) {
            for (Field field : Reflect.declaredFields(alertType)) {
                String name = field.getName().toLowerCase(Locale.US);
                if ((name.contains("heading") || name.contains("direction") || name.contains("bearing"))
                        && (field.getType() == double.class || field.getType() == Double.class)) {
                    directionRad = Reflect.asDouble(Reflect.readField(alert, field));
                    if (directionRad != null) break;
                }
            }
        }

        Integer altitudeFt = null;
        if ("aircraft".equals(category)) {
            for (Field field : Reflect.declaredFields(alertType)) {
                String name = field.getName().toLowerCase(Locale.US);
                if (name.contains("alt") && (field.getType() == double.class || field.getType() == Double.class)) {
                    Double altM = Reflect.asDouble(Reflect.readField(alert, field));
                    if (altM != null && altM > 0) {
                        altitudeFt = (int) Math.round(altM * 3.28084);
                        break;
                    }
                }
            }
            if (altitudeFt == null) {
                try {
                    Field fc = alertType.getDeclaredField("f6482c");
                    Double altM = Reflect.asDouble(Reflect.readField(alert, fc));
                    if (altM != null && altM > 0) {
                        altitudeFt = (int) Math.round(altM * 3.28084);
                    }
                } catch (Throwable ignored) {}
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("concreteClassName", alertType.getName());
        payload.put("heuristicCategory", category);
        payload.put("band", band);
        payload.put("frequency", frequency);
        payload.put("severity", Reflect.asInteger(Reflect.call(alert, "getSeverity")));
        payload.put("isMuted", Reflect.asBoolean(Reflect.call(alert, MUTED_ACCESSORS)));
        payload.put("lat", coordinate[0]);
        payload.put("lon", coordinate[1]);
        payload.put("directionRad", directionRad);
        payload.put("altitudeFt", altitudeFt);
        payload.put("isCritical", Reflect.asBoolean(Reflect.call(marker, "isCritical")));
        payload.put("markerId", Reflect.asText(Reflect.call(marker, "getMarkerId")));
        payload.put("title", title);
        payload.put("subtitle", subtitle);
        payload.put("distanceText", Reflect.asText(Reflect.call(listItem, "getDistance")));
        payload.put("description", Reflect.asText(Reflect.call(listItem, "getDescription")));
        payload.put("bearingRad", Reflect.asDouble(Reflect.call(listItem, "getBearingRad")));
        return payload;
    }

    private static Double[] readCoordinate(Object marker, Object alert) {
        if (marker != null) {
            ClassIntrospector.dumpOnce(marker.getClass());
            Object position = Reflect.call(marker, "getPosition");
            if (position != null) {
                ClassIntrospector.dumpOnce(position.getClass());
                Double[] coord = LatLonResolver.forType(position.getClass()).read(position);
                if (coord[0] != null && coord[1] != null) {
                    return coord;
                }
            }
        }
        if (alert == null) {
            return new Double[]{null, null};
        }
        Object directPosition = Reflect.call(alert, "getPosition", "getCoord", "getLocation", "getCoordinates");
        if (directPosition != null) {
            Double[] coord = LatLonResolver.forType(directPosition.getClass()).read(directPosition);
            if (coord[0] != null && coord[1] != null) {
                return coord;
            }
        }
        for (Field field : Reflect.declaredFields(alert.getClass())) {
            Class<?> type = field.getType();
            if (type.getName().contains("Coord") || type.getSimpleName().equals("Coord")) {
                Object coordObj = Reflect.readField(alert, field);
                if (coordObj != null) {
                    Double[] coord = LatLonResolver.forType(coordObj.getClass()).read(coordObj);
                    if (coord[0] != null && coord[1] != null) {
                        return coord;
                    }
                }
            }
        }
        return new Double[]{null, null};
    }

    /**
     * Best-effort alert type, most specific rule first, from the concrete class's simple name and
     * the list item text.
     *
     * <p>The fully qualified name is deliberately not searched: every class lives under
     * {@code com.highwayradar.app}, so the package alone would classify everything as "radar".
     * This is a heuristic, and {@code concreteClassName} is relayed alongside it precisely so the
     * companion app can replace it with an exact mapping.
     */
    private static String categorize(String id, String simpleClassName, String title, String subtitle) {
        // Observed live ID namespaces remain available even for alerts without a list item.
        // Do not infer meaning from obfuscated single-letter class names.
        if (id != null && id.startsWith("camera/")) {
            return "camera";
        }
        if (id != null && id.startsWith("aircraft/")) {
            return "aircraft";
        }
        String haystack = (nullToEmpty(simpleClassName) + ' ' + nullToEmpty(title) + ' '
                + nullToEmpty(subtitle)).toLowerCase(Locale.US);
        if (haystack.contains("radardetector")
                || (haystack.contains("radar") && haystack.contains("detector"))) {
            return "radar_detector";
        }
        if (haystack.contains("aircraft") || haystack.contains("helicopter")
                || haystack.contains("airplane") || haystack.contains("aviation")) {
            return "aircraft";
        }
        if (haystack.contains("police")) {
            return "police";
        }
        if (haystack.contains("laser")) {
            return "laser";
        }
        if (haystack.contains("camera") || haystack.contains("speedcam")) {
            return "camera";
        }
        if (haystack.contains("radar") || haystack.contains("gpsr")) {
            return "radar";
        }
        return "unknown";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}