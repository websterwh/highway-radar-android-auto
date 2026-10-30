package com.highwayradar.aa;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the latitude and longitude accessors of the coordinate type behind
 * {@code MapMarkerData.getPosition()} (the app's {@code com.highwayradar.app.utils.Coord}).
 *
 * <p>Those accessor names were never confirmed against a live build, so nothing is hardcoded:
 * they are discovered at runtime from the class's own public zero-argument floating point members,
 * most specific name pattern first. When discovery comes back empty the resolver relays {@code null}
 * for the coordinate and says so in the log; it never falls back to a guessed member name.
 *
 * <p>The resolved accessors are memoised per coordinate class and reported once per process.
 */
final class LatLonResolver {

    /** Ordered by decreasing confidence: the first token that occurs in a member name wins. */
    private static final String[] LATITUDE_TOKENS = {"latitude", "lat"};
    private static final String[] LONGITUDE_TOKENS = {"longitude", "lng", "lon"};

    private static final ConcurrentMap<Class<?>, LatLonResolver> RESOLVERS = new ConcurrentHashMap<>();

    /** Reads a coordinate, or reports null for a value that is unknown or unreadable. */
    private interface Accessor {
        Double read(Object coordinate);

        String describe();
    }

    private final Accessor latitude;
    private final Accessor longitude;

    private LatLonResolver(Class<?> coordinateType) {
        latitude = findAccessor(coordinateType, "latitude", LATITUDE_TOKENS);
        longitude = findAccessor(coordinateType, "longitude", LONGITUDE_TOKENS);
        if (latitude != null || longitude != null) {
            HookLog.once("latlon:" + coordinateType.getName(), "resolved coordinates on "
                    + coordinateType.getName() + ": latitude=" + describe(latitude)
                    + " longitude=" + describe(longitude));
        }
    }

    static LatLonResolver forType(Class<?> coordinateType) {
        return RESOLVERS.computeIfAbsent(coordinateType, LatLonResolver::new);
    }

    /** Returns {@code {latitude, longitude}}; either element is null when unknown or unreadable. */
    Double[] read(Object coordinate) {
        if (coordinate == null) {
            return new Double[]{null, null};
        }
        return new Double[]{
                latitude == null ? null : latitude.read(coordinate),
                longitude == null ? null : longitude.read(coordinate)
        };
    }

    private static Accessor findAccessor(Class<?> type, String what, String[] tokens) {
        Method bestMethod = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method method : type.getMethods()) {
            if (method.isSynthetic() || method.getParameterCount() != 0
                    || !isCoordinateType(method.getReturnType())) {
                continue;
            }
            int score = score(method.getName(), tokens);
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                bestMethod = method;
            }
        }
        if (bestMethod != null) {
            return new MethodAccessor(bestMethod);
        }

        Field bestField = null;
        bestScore = Integer.MAX_VALUE;
        for (Field field : Reflect.declaredFields(type)) {
            if (field.isSynthetic() || !isCoordinateType(field.getType())) {
                continue;
            }
            int score = score(field.getName(), tokens);
            if (score < 0 || score >= bestScore) {
                continue;
            }
            try {
                field.setAccessible(true);
            } catch (RuntimeException e) {
                continue;
            }
            bestScore = score;
            bestField = field;
        }
        if (bestField != null) {
            return new FieldAccessor(bestField);
        }

        HookLog.warnOnce("latlon-missing:" + type.getName(), "no " + what + " accessor found on "
                + type.getName() + "; relaying null. Read its dump in this log on a live device and"
                + " teach LatLonResolver the real name.");
        return null;
    }

    /**
     * Ranks a member name against the token list, or returns -1 when no token occurs in it. Earlier
     * tokens outweigh everything later, and among equally specific matches the terser name wins so
     * that {@code getLat()} beats an unrelated {@code getLatDegreesOfTravel()}.
     */
    private static int score(String memberName, String[] tokens) {
        String lower = memberName.toLowerCase(Locale.US);
        for (int i = 0; i < tokens.length; i++) {
            if (lower.contains(tokens[i])) {
                return i * 100 + lower.length();
            }
        }
        return -1;
    }

    private static boolean isCoordinateType(Class<?> type) {
        return type == double.class || type == float.class
                || type == Double.class || type == Float.class;
    }

    private static String describe(Accessor accessor) {
        return accessor == null ? "not found" : accessor.describe();
    }

    private static final class MethodAccessor implements Accessor {
        private final Method method;

        MethodAccessor(Method method) {
            this.method = method;
        }

        @Override
        public Double read(Object coordinate) {
            return Reflect.asDouble(Reflect.invoke(coordinate, method));
        }

        @Override
        public String describe() {
            return method.getName() + "()";
        }
    }

    private static final class FieldAccessor implements Accessor {
        private final Field field;

        FieldAccessor(Field field) {
            this.field = field;
        }

        @Override
        public Double read(Object coordinate) {
            return Reflect.asDouble(Reflect.readField(coordinate, field));
        }

        @Override
        public String describe() {
            return field.getName();
        }
    }
}