package com.highwayradar.aa;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldsMatcher;
import org.luckypray.dexkit.query.matchers.InterfacesMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Structurally re-derives Google Maps' obfuscated projection classes/methods with DexKit instead
 * of hardcoding per-version letter names. Maps renames its single/double-letter obfuscated
 * identifiers on almost every release (the 26.42→26.43 jump renamed every class and method this
 * module hooks), but the *shape* of those classes — field counts/types, constructor arity,
 * interface membership, descriptor pattern — is stable across such renames. Each {@code resolve*}
 * method below encodes one such shape.
 *
 * <p>Resolution runs once per Maps process, against the installed APK path (standard
 * {@code ApplicationInfo.sourceDir}, already readable by any package that can load Maps' own
 * classes). Nothing here mutates Maps or ships without the structural fallback: callers must
 * treat a {@code null} result as "fall back to the hardcoded literal names for this version" —
 * DexKit only narrows how often that fallback needs a human to re-derive new letters by hand.
 */
final class MapsClassResolver {

    private MapsClassResolver() {}

    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            HookLog.w("DexKit native library unavailable: " + t);
        }
    }

    /** Resolved projection symbols, or null fields where structural matching failed. */
    static final class Resolved {
        Class<?> coordinateClass; // was "bjis": lat/lon fixed-point coordinate
        Class<?> projectorInterface; // was "bjqn": camera projector
        Class<?> pixelResultClass; // was "bjjo": screen-space x/y result of forward()
        Method coordinateFactory; // was "bjis.F(DD)Lbjis;"
        Method forward; // was "bjqn.h(Lbjis;)Lbjjo;"
        Method widthMethod; // was "bjqn.e()I" (disambiguated at runtime, see probe)
        Method heightMethod; // was "bjqn.d()I" (disambiguated at runtime, see probe)
        Field pixelX; // was "bjjo.b:F"
        Field pixelY; // was "bjjo.c:F"
        List<Method> projectorProviders; // zero-arg methods returning the projector interface
        // (was "ahtd.f()"/"bkgj.f()")
    }

    /**
     * Runs all structural matchers against {@code apkPath} using {@code loader} to materialize
     * {@link Class}/{@link Method}/{@link Field} instances. Returns a {@link Resolved} with
     * whichever fields were found; never throws — a failed matcher just leaves its field(s) null
     * so the caller can fall back to literal names for the parts that didn't resolve.
     */
    static Resolved resolve(String apkPath, ClassLoader loader) {
        Resolved out = new Resolved();
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            ClassData coordinate = resolveCoordinateClass(bridge);
            ClassData pixel = resolvePixelResultClass(bridge);
            MethodData forwardMethod =
                    (coordinate != null && pixel != null)
                            ? resolveForwardMethod(bridge, coordinate, pixel)
                            : null;
            ClassData projector = forwardMethod != null ? forwardMethod.getDeclaredClass() : null;

            if (coordinate != null) {
                try {
                    out.coordinateClass = coordinate.getInstance(loader);
                    out.coordinateFactory = resolveCoordinateFactory(coordinate, loader);
                } catch (Throwable t) {
                    HookLog.warnOnce(
                            "dexkit-coordinate-load", "DexKit coordinate class load failed: " + t);
                }
            }
            if (pixel != null) {
                try {
                    out.pixelResultClass = pixel.getInstance(loader);
                    List<Field> floatFields = instanceFloatFields(pixel, loader);
                    if (floatFields.size() == 2) {
                        out.pixelX = floatFields.get(0);
                        out.pixelY = floatFields.get(1);
                    }
                } catch (Throwable t) {
                    HookLog.warnOnce("dexkit-pixel-load", "DexKit pixel class load failed: " + t);
                }
            }
            if (projector != null) {
                try {
                    out.projectorInterface = projector.getInstance(loader);
                    if (out.coordinateClass != null) {
                        out.forward = forwardMethod.getMethodInstance(loader);
                    }
                    out.projectorProviders = resolveProjectorProviders(bridge, projector, loader);
                    resolveWidthHeightCandidates(projector, loader, out);
                } catch (Throwable t) {
                    HookLog.warnOnce(
                            "dexkit-projector-load", "DexKit projector class load failed: " + t);
                }
            }
        } catch (Throwable t) {
            HookLog.warnOnce("dexkit-resolve", "DexKit structural resolution failed: " + t);
        }
        return out;
    }

    /**
     * Coordinate class (was {@code bjis}): implements Comparable + Serializable, has a 2-arg int
     * constructor and a 3-arg int constructor, and exposes a static {@code (double,double)→self}
     * factory. This shape is distinctive among Maps' hundreds of small value classes.
     */
    private static ClassData resolveCoordinateClass(DexKitBridge bridge) {
        try {
            List<ClassData> matches =
                    bridge.findClass(
                            FindClass.create()
                                    .matcher(
                                            ClassMatcher.create()
                                                    .interfaces(
                                                            InterfacesMatcher.create()
                                                                    .add("java.lang.Comparable")
                                                                    .add("java.io.Serializable"))
                                                    .methods(
                                                            MethodsMatcher.create()
                                                                    .add(
                                                                            MethodMatcher.create()
                                                                                    .name(
                                                                                            "<init>")
                                                                                    .paramTypes(
                                                                                            "int",
                                                                                            "int"))
                                                                    .add(
                                                                            MethodMatcher.create()
                                                                                    .name(
                                                                                            "<init>")
                                                                                    .paramTypes(
                                                                                            "int",
                                                                                            "int",
                                                                                            "int"))
                                                                    .add(
                                                                            MethodMatcher.create()
                                                                                    .modifiers(
                                                                                            Modifier
                                                                                                    .STATIC)
                                                                                    .paramTypes(
                                                                                            "double",
                                                                                            "double")))));
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (Throwable t) {
            HookLog.warnOnce("dexkit-coordinate", "DexKit coordinate matcher failed: " + t);
            return null;
        }
    }

    /**
     * Pixel-result class (was {@code bjjo}): exactly two non-static float fields, a zero-arg
     * constructor, and a 2-arg {@code (float,float)} constructor. This is the screen-space
     * x/y result Maps' camera projector returns from its forward-projection call.
     */
    private static ClassData resolvePixelResultClass(DexKitBridge bridge) {
        try {
            List<ClassData> matches =
                    bridge.findClass(
                            FindClass.create()
                                    .matcher(
                                            ClassMatcher.create()
                                                    .fields(
                                                            FieldsMatcher.create()
                                                                    .addForType("float")
                                                                    .addForType("float"))
                                                    .methods(
                                                            MethodsMatcher.create()
                                                                    .add(
                                                                            MethodMatcher.create()
                                                                                    .name(
                                                                                            "<init>")
                                                                                    .paramTypes())
                                                                    .add(
                                                                            MethodMatcher.create()
                                                                                    .name(
                                                                                            "<init>")
                                                                                    .paramTypes(
                                                                                            "float",
                                                                                            "float")))));
            HookLog.i("DexKit pixel-class candidates: " + matches.size());
            if (matches.size() == 1) return matches.get(0);
            // Multiple candidates share this shape (small x/y value classes are common). Narrow
            // by the distinctive static triangle-signed-area method: (self,self,self)->float,
            // all three params the class itself. This method only makes sense on a 2D point
            // type and is far rarer than the constructor shape alone.
            ClassData unique = null;
            for (ClassData candidate : matches) {
                boolean hasTriangleArea = false;
                for (MethodData m : candidate.getMethods()) {
                    if (m.getParamCount() == 3
                            && "float".equals(m.getReturnTypeName())
                            && Modifier.isStatic(m.getModifiers())
                            && candidate.getName().equals(safeParamType(m, 0))
                            && candidate.getName().equals(safeParamType(m, 1))
                            && candidate.getName().equals(safeParamType(m, 2))) {
                        hasTriangleArea = true;
                        break;
                    }
                }
                if (hasTriangleArea) {
                    if (unique != null) return null; // ambiguous: more than one match
                    unique = candidate;
                }
            }
            return unique;
        } catch (Throwable t) {
            HookLog.warnOnce("dexkit-pixel", "DexKit pixel matcher failed: " + t);
            return null;
        }
    }

    /**
     * The forward-projection method (was {@code bjqn.h(Lbjis;)Lbjjo;}): an abstract method
     * declared directly on the projector interface, taking exactly the resolved coordinate type
     * and returning exactly the resolved pixel-result type.
     */
    private static MethodData resolveForwardMethod(
            DexKitBridge bridge, ClassData coordinate, ClassData pixel) {
        try {
            List<MethodData> matches =
                    bridge.findMethod(
                            FindMethod.create()
                                    .matcher(
                                            MethodMatcher.create()
                                                    .paramTypes(coordinate.getName())
                                                    .returnType(pixel.getName())));
            // Concrete implementation classes commonly override this exact signature, so the
            // match set includes both the interface method and its overrides. The interface
            // declaration is the only one that must be unique and abstract.
            MethodData abstractMatch = null;
            for (MethodData m : matches) {
                if (!Modifier.isAbstract(m.getModifiers())) continue;
                if (abstractMatch != null) return null; // ambiguous: more than one abstract decl
                abstractMatch = m;
            }
            return abstractMatch;
        } catch (Throwable t) {
            HookLog.warnOnce("dexkit-forward", "DexKit forward-method matcher failed: " + t);
            return null;
        }
    }

    /**
     * Any class exposing a public zero-arg method whose return type is the resolved projector
     * interface (was {@code ahtd}/{@code bkgj}). Multiple provider classes are expected and all
     * are hooked; Maps obtains the live camera projector through more than one UI owner.
     */
    private static List<Method> resolveProjectorProviders(
            DexKitBridge bridge, ClassData projector, ClassLoader loader) {
        ArrayList<Method> out = new ArrayList<>();
        try {
            List<MethodData> matches =
                    bridge.findMethod(
                            FindMethod.create()
                                    .matcher(
                                            MethodMatcher.create()
                                                    .paramTypes()
                                                    .returnType(projector.getName())));
            // One hook per *declaring class*: a provider class may expose more than one zero-arg
            // method matching this shape only by coincidence, but Maps' actual camera-provider
            // getters ("ahtd.f"/"bkgj.f") are unique per class in every observed version.
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (MethodData m : matches) {
                String className = m.getDeclaredClassName();
                if (className.equals(projector.getName())) continue; // skip the interface itself
                if (!seen.add(className)) continue;
                try {
                    out.add(m.getMethodInstance(loader));
                } catch (Throwable ignored) {
                    // A provider method that fails to load is skipped, not fatal.
                }
            }
        } catch (Throwable t) {
            HookLog.warnOnce("dexkit-providers", "DexKit provider matcher failed: " + t);
        }
        return out;
    }

    private static Method resolveCoordinateFactory(ClassData coordinate, ClassLoader loader) {
        try {
            for (MethodData m : coordinate.getMethods()) {
                if (m.getParamCount() == 2
                        && "double".equals(safeParamType(m, 0))
                        && "double".equals(safeParamType(m, 1))
                        && coordinate.getName().equals(m.getReturnTypeName())
                        && Modifier.isStatic(m.getModifiers())) {
                    return m.getMethodInstance(loader);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String safeParamType(MethodData m, int index) {
        List<String> names = m.getParamTypeNames();
        return index < names.size() ? names.get(index) : null;
    }

    private static List<Field> instanceFloatFields(ClassData classData, ClassLoader loader) {
        ArrayList<Field> out = new ArrayList<>();
        try {
            Class<?> type = classData.getInstance(loader);
            for (Field f : type.getDeclaredFields()) {
                if (f.getType() == float.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    out.add(f);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * The projector interface declares exactly two zero-arg {@code ()int} methods for viewport
     * width/height. Their names are not structurally distinguishable from each other, so the
     * caller tries both bindings and keeps whichever one is self-consistent at runtime (see
     * {@code MapsProjectionProbe}'s calibration against the attached View's own measured size).
     * This method only narrows the candidate set to "the zero-arg int-returning methods declared
     * directly on the interface" — exactly 2 is the expected, resolvable case.
     */
    private static void resolveWidthHeightCandidates(
            ClassData projector, ClassLoader loader, Resolved out) {
        ArrayList<Method> candidates = new ArrayList<>();
        try {
            for (MethodData m : projector.getMethods()) {
                if (m.getParamCount() == 0 && "int".equals(m.getReturnTypeName())) {
                    candidates.add(m.getMethodInstance(loader));
                }
            }
        } catch (Throwable ignored) {
        }
        if (candidates.size() == 2) {
            out.widthMethod = candidates.get(0);
            out.heightMethod = candidates.get(1);
        }
    }
}
