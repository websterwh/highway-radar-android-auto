package com.highwayradar.aa;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot reflection dumps of the obfuscated Highway Radar types this module reads.
 *
 * <p>The exact members of {@code Coord}, {@code MapMarkerData}, {@code AlertListItemData} and of
 * the concrete {@code Alert} subclasses were not pinned down by static analysis, so they are
 * dumped to logcat the first time each class is actually seen in a live process. Every class is
 * dumped exactly once per process, which keeps the output small enough to read end to end while
 * staying out of the way of the per-tick relay.
 *
 * <p>Read the output with {@code adb logcat -s HWRadarHook}; see README.md.
 */
final class ClassIntrospector {

    private static final Set<String> DUMPED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private ClassIntrospector() {}

    /** Dumps {@code type} and every one of its superclasses, once per class per process. */
    static void dumpHierarchyOnce(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            dumpOnce(current);
        }
    }

    /** Dumps the declared methods and fields of exactly {@code type}, once per process. */
    static void dumpOnce(Class<?> type) {
        String key = type.getName();
        if (!DUMPED.add(key)) {
            return;
        }
        Class<?> superClass = type.getSuperclass();
        HookLog.i("---- class " + key + " extends " + (superClass == null ? "none" : superClass.getName())
                + " ----");
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            HookLog.i("  method " + Modifier.toString(method.getModifiers()) + " "
                    + method.getReturnType().getName() + " " + method.getName() + parameterList(method));
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            HookLog.i("  field " + Modifier.toString(field.getModifiers()) + " "
                    + field.getType().getName() + " " + field.getName());
        }
    }

    private static String parameterList(Method method) {
        StringBuilder out = new StringBuilder("(");
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(parameterTypes[i].getName());
        }
        return out.append(')').toString();
    }
}