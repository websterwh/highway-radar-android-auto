package com.highwayradar.aa;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Failure-tolerant reflective access to the obfuscated Highway Radar types.
 *
 * <p>Two rules hold everywhere in this module: a member that cannot be found is treated as absent
 * rather than as an error, and a member that throws is treated as absent for that call. Highway
 * Radar ships obfuscated code that is renamed on every update, so a renamed getter must degrade
 * the relayed payload, never the host app.
 *
 * <p>Lookups are memoised per concrete class plus member name, so a getter is resolved once per
 * process instead of once per alert per tick, and members known to be missing are remembered too.
 */
final class Reflect {

    private static final ConcurrentMap<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private Reflect() {}

    /** Public no-argument method, inherited ones included; null when the type has no such member. */
    static Method findMethod(Class<?> type, String name) {
        String key = type.getName() + '#' + name;
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING_METHODS.contains(key)) {
            return null;
        }
        Method found;
        try {
            found = type.getMethod(name);
        } catch (NoSuchMethodException e) {
            MISSING_METHODS.add(key);
            HookLog.warnOnce("missing:" + key, "no such method " + key + "; relayed as null");
            return null;
        }
        METHODS.put(key, found);
        return found;
    }

    /**
     * Calls the first of {@code names} that exists on {@code target}'s class and yields a value;
     * null when {@code target} is null or none of the names produce one.
     */
    static Object call(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            Method method = findMethod(target.getClass(), name);
            if (method == null) {
                continue;
            }
            Object value = invoke(target, method);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static Object invoke(Object target, Method method) {
        try {
            return method.invoke(target);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            HookLog.warnOnce("throw:" + method.getName(),
                    method.getName() + " threw " + cause + "; relayed as null");
        } catch (IllegalAccessException | IllegalArgumentException e) {
            HookLog.warnOnce("inaccessible:" + method.getName(),
                    "cannot call " + method.getName() + ": " + e + "; relayed as null");
        }
        return null;
    }

    /** Reads a field declared anywhere in {@code type}'s hierarchy; null when absent or unreadable. */
    static Object readField(Object target, Field field) {
        try {
            return field.get(target);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            HookLog.warnOnce("field:" + field.getName(),
                    "cannot read field " + field.getName() + ": " + e + "; relayed as null");
            return null;
        }
    }

    /** All fields of {@code type} and its superclasses, including non-public ones. */
    static Field[] declaredFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            Collections.addAll(fields, current.getDeclaredFields());
        }
        return fields.toArray(new Field[0]);
    }

    static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence) {
            return value.toString();
        }
        // getDistance() may be a formatted string or a raw number depending on the app build.
        return String.valueOf(value);
    }

    static Integer asInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    static Double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    static Boolean asBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }
}