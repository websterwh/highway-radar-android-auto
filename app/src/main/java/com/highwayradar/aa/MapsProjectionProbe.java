package com.highwayradar.aa;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class MapsProjectionProbe {
    static final String TARGET_PACKAGE = "com.google.android.apps.maps";

    private static final Map<View, Boolean> REGISTERED_VIEWS = new WeakHashMap<>();
    // Bounded ring, not a WeakHashMap: some provider methods (observed on Maps 26.43's
    // "blas.d()") return a freshly allocated wrapper on every call instead of a stable singleton.
    // A WeakHashMap only shrinks via GC, which call rate can outrun, so an unbounded candidate
    // set — scanned every frame in updateLiveFrame — becomes the actual lag source. The real
    // camera owner is re-identified every frame by viewport dimensions anyway (see
    // ProjectionCameraPolicy), so retaining more than a handful of recent candidates buys
    // nothing; capping eviction here keeps the scan cheap regardless of a provider's allocation
    // behavior.
    private static final int MAX_PROJECTOR_CANDIDATES = 8;
    private static final Object PROJECTORS_LOCK = new Object();
    private static volatile ProjectorCandidate[] sProjectors = new ProjectorCandidate[0];
    private static final ProjectionCameraPolicy POLICY = new ProjectionCameraPolicy();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ProjectionAccess sProjectionAccess;

    /**
     * Set to TRUE on the calling thread before we read the camera projector ourselves (in
     * updateLiveFrame). The camera hook skips its redraw scheduling to prevent a re-entrant cycle
     * and a self-sustaining vsync loop.
     */
    private static final ThreadLocal<Boolean> PROJECTING = new ThreadLocal<>();

    // One-shot vsync callback: fires once per external camera update, never reschedules itself.
    private static final android.view.Choreographer.FrameCallback FRAME_CALLBACK =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    POLICY.consumeFrame();
                    if (!MapsProjectionOverlay.isVisibleOwner()
                            || !OverlaySettings.isOverlayEnabled()) return;
                    // Project once in draw(), using the camera at that traversal, not a
                    // second snapshot that can become stale before the draw starts.
                    MapsProjectionOverlay.requestRedraw();
                    // No reschedule — next camera hook event will schedule the next frame.
                }
            };

    private MapsProjectionProbe() {}

    private static final Runnable POST_FRAME =
            () -> {
                if (!MapsProjectionOverlay.isVisibleOwner()
                        || !OverlaySettings.isOverlayEnabled()) {
                    POLICY.consumeFrame();
                    return;
                }
                android.view.Choreographer.getInstance().postFrameCallback(FRAME_CALLBACK);
            };

    static void scheduleNextFrame() {
        if (!POLICY.requestFrame()) return;
        // At most one main-thread post and one callback for a burst, including renderer reads.
        if (Looper.myLooper() == Looper.getMainLooper()) POST_FRAME.run();
        else MAIN.post(POST_FRAME);
    }

    /**
     * Synchronous projection called from draw(). Reads the current camera state and publishes a
     * fresh marker array without scheduling a recursive redraw.
     */
    static boolean updateLiveFrame() {
        ProjectionAccess access = sProjectionAccess;
        long gen = MapsProjectionOverlay.generation();
        if (gen == 0 || access == null) return false;
        PROJECTING.set(Boolean.TRUE);
        try {
            POLICY.beginSelection(
                    MapsProjectionOverlay.viewportWidth(), MapsProjectionOverlay.viewportHeight());
            Object projector = null;
            // Never invoke Maps under the registry lock. Projectors are captured from the map
            // controller and remain valid until Maps replaces that controller.
            for (ProjectorCandidate entry : sProjectors) {
                Object candidate = entry.projector.get();
                if (candidate == null) continue;
                try {
                    int width = (Integer) access.width.invoke(candidate);
                    int height = (Integer) access.height.invoke(candidate);
                    // Maps does not expose the renderer View through this controller. Exact
                    // viewport dimensions are therefore the only safe ownership signal.
                    POLICY.consider(candidate, false, width, height);
                    if (POLICY.selected() == candidate) projector = candidate;
                } catch (Throwable t) {
                    HookLog.warnOnce("maps-projector-read", "Maps projector read failed: " + t);
                    // An unavailable candidate must not prevent a proven owner being sampled.
                }
            }
            if (POLICY.isAmbiguous()) {
                HookLog.warnOnce(
                        "maps-camera-ambiguous",
                        "Maps viewport camera is ambiguous; hiding markers");
                return false;
            }
            if (POLICY.selected() == null || projector == null) return false;
            if (!POLICY.isProven()) {
                HookLog.warnOnce(
                        "maps-camera-dimensions",
                        "Maps camera selected by unique viewport dimensions only; ownership"
                            + " unproven");
            }
            access.calibrate(
                    projector,
                    MapsProjectionOverlay.viewportWidth(),
                    MapsProjectionOverlay.viewportHeight());
            return sampleAndProject(projector, access, gen);
        } catch (Throwable t) {
            HookLog.warnOnce("maps-project", "Maps projection failed: " + t);
        } finally {
            // Do not retain a manager after the draw or carry ownership across viewport changes.
            POLICY.beginSelection(0, 0);
            PROJECTING.remove();
        }
        return false;
    }

    static void install(XC_LoadPackage.LoadPackageParam load) {
        hookMapContainer(load);

        MapsClassResolver.Resolved resolved = null;
        try {
            resolved =
                    MapsClassResolver.resolve(load.appInfo.sourceDir, load.classLoader);
        } catch (Throwable t) {
            HookLog.warnOnce("dexkit-unavailable", "DexKit structural resolution skipped: " + t);
        }

        if (resolved != null
                && resolved.coordinateClass != null
                && resolved.projectorInterface != null
                && resolved.pixelResultClass != null
                && resolved.forward != null
                && resolved.coordinateFactory != null
                && resolved.pixelX != null
                && resolved.pixelY != null
                && resolved.widthMethod != null
                && resolved.heightMethod != null
                && resolved.projectorProviders != null
                && !resolved.projectorProviders.isEmpty()) {
            try {
                sProjectionAccess = new ProjectionAccess(resolved);
                for (Method provider : resolved.projectorProviders) {
                    hookProjectorProvider(provider);
                }
                HookLog.i(
                        "Maps projection symbols resolved structurally via DexKit: coordinate="
                                + resolved.coordinateClass.getName()
                                + " projector="
                                + resolved.projectorInterface.getName()
                                + " providers="
                                + resolved.projectorProviders.size());
                return;
            } catch (Throwable t) {
                HookLog.w("DexKit-resolved projection access failed to bind, falling back: " + t);
                sProjectionAccess = null;
            }
        } else {
            HookLog.warnOnce(
                    "dexkit-incomplete",
                    "DexKit structural resolution incomplete; falling back to literal names");
        }

        // Fallback: literal obfuscated names verified against the last known Maps version. These
        // go stale on every Maps update; DexKit above is the update-resistant path and is tried
        // first. Keep this path working so an unexpected matcher regression degrades to "needs a
        // human to re-derive names" rather than "the overlay silently never attaches".
        hookProjectorProvider(load, "ahtd");
        hookProjectorProvider(load, "bkgj");
    }

    private static void hookMapContainer(XC_LoadPackage.LoadPackageParam load) {
        try {
            Class<?> type =
                    XposedHelpers.findClass(
                            "com.google.android.apps.gmm.base.views.map.MapViewContainer",
                            load.classLoader);
            XC_MethodHook attachHook =
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable()) register((View) param.thisObject);
                        }
                    };
            XposedHelpers.findAndHookMethod(type, "onAttachedToWindow", attachHook);
            XposedHelpers.findAndHookMethod(
                    type,
                    "onSizeChanged",
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    attachHook);
            HookLog.i("Maps MapViewContainer hooks installed in " + load.processName);
        } catch (Throwable t) {
            HookLog.w("Maps MapViewContainer hooks unavailable: " + t);
        }
    }

    private static void register(View content) {
        try {
            if (REGISTERED_VIEWS.put(content, Boolean.TRUE) == null) {
                content.addOnAttachStateChangeListener(
                        new View.OnAttachStateChangeListener() {
                            @Override
                            public void onViewAttachedToWindow(View v) {
                                v.post(() -> attach(v));
                            }

                            @Override
                            public void onViewDetachedFromWindow(View v) {
                                MapsProjectionOverlay.detach(v);
                            }
                        });
            }
            attach(content);
        } catch (Throwable t) {
            HookLog.warnOnce("maps-register", "Maps registration failed: " + t);
        }
    }

    private static void attach(View view) {
        if (!view.isAttachedToWindow()) return;
        OverlaySettings.init(view.getContext());
        MapsProjectionOverlay.attach(view);
        MapsProjectionOverlay.requestRedraw();
        scheduleNextFrame();
    }

    private static void hookProjectorProvider(
            XC_LoadPackage.LoadPackageParam load, String name) {
        try {
            if (sProjectionAccess == null)
                sProjectionAccess = new ProjectionAccess(load.classLoader);
            XposedHelpers.findAndHookMethod(
                    name, load.classLoader, "f", projectorCaptureHook(name));
            HookLog.i("Maps projector provider hooked: " + name);
        } catch (Throwable t) {
            HookLog.w("Maps projector provider unavailable for " + name + ": " + t);
        }
    }

    /** DexKit-resolved variant: {@code provider} is already a concrete, loaded {@link Method}. */
    private static void hookProjectorProvider(Method provider) {
        try {
            XposedBridge.hookMethod(provider, projectorCaptureHook(provider.getDeclaringClass().getName()));
            HookLog.i("Maps projector provider hooked: " + provider.getDeclaringClass().getName());
        } catch (Throwable t) {
            HookLog.w("Maps projector provider unavailable for " + provider + ": " + t);
        }
    }

    private static XC_MethodHook projectorCaptureHook(String providerLabel) {
        return new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            Object projector = param.getResult();
                            if (projector == null) return;
                            captureProjector(projector, providerLabel);
                            if (MapsProjectionOverlay.generation() != 0) {
                                MapsProjectionOverlay.requestRedraw();
                                scheduleNextFrame();
                            }
                        }
                    };
    }

    /**
     * Records {@code projector} as a candidate, evicting the oldest entry once the bounded ring
     * is full. Identity-deduplicated against current candidates so a provider returning the same
     * singleton on every call (the common case) does not spam the log or grow the ring.
     */
    private static void captureProjector(Object projector, String providerLabel) {
        synchronized (PROJECTORS_LOCK) {
            ProjectorCandidate[] current = sProjectors;
            for (ProjectorCandidate c : current) {
                if (c.projector.get() == projector) return; // already tracked
            }
            ProjectorCandidate[] next;
            if (current.length < MAX_PROJECTOR_CANDIDATES) {
                next = java.util.Arrays.copyOf(current, current.length + 1);
                next[current.length] = new ProjectorCandidate(projector);
            } else {
                // Drop the oldest (index 0) and append the newest; keeps the ring bounded without
                // ever growing, regardless of how fast a provider allocates new wrappers.
                next = new ProjectorCandidate[current.length];
                System.arraycopy(current, 1, next, 0, current.length - 1);
                next[current.length - 1] = new ProjectorCandidate(projector);
            }
            sProjectors = next;
        }
        HookLog.once(
                "maps-projector-capture-" + providerLabel,
                "Maps projector captured from " + providerLabel);
    }

    private static final class ProjectorCandidate {
        final WeakReference<Object> projector;

        ProjectorCandidate(Object projector) {
            this.projector = new WeakReference<>(projector);
        }
    }

    private static final class ProjectionAccess {
        final Method coordinate;
        Method height;
        Method width;
        final Method forward;
        final Field x;
        final Field y;
        // DexKit resolves the projector interface's two zero-arg int methods structurally but
        // cannot tell width from height apart by shape alone. True until the first successful
        // sample confirms (or flips) the guessed binding against the attached View's own size.
        private volatile boolean needsCalibration;

        /** Literal-name fallback for a known Maps version (see {@code install}'s comment). */
        ProjectionAccess(ClassLoader loader) {
            Class<?> geo = XposedHelpers.findClass("bjis", loader);
            Class<?> projector = XposedHelpers.findClass("bjqn", loader);
            coordinate = XposedHelpers.findMethodExact(geo, "F", double.class, double.class);
            height = XposedHelpers.findMethodExact(projector, "d");
            width = XposedHelpers.findMethodExact(projector, "e");
            forward = XposedHelpers.findMethodExact(projector, "h", geo);
            x = XposedHelpers.findField(forward.getReturnType(), "b");
            y = XposedHelpers.findField(forward.getReturnType(), "c");
        }

        /** DexKit-resolved variant: every member is already a concrete, loaded instance. */
        ProjectionAccess(MapsClassResolver.Resolved resolved) {
            coordinate = resolved.coordinateFactory;
            height = resolved.heightMethod;
            width = resolved.widthMethod;
            forward = resolved.forward;
            x = resolved.pixelX;
            y = resolved.pixelY;
            needsCalibration = true;
        }

        Object project(Object projector, double lat, double lon) throws Exception {
            Object geo = coordinate.invoke(null, lat, lon);
            return forward.invoke(projector, geo);
        }

        /**
         * Confirms (or flips) the guessed width/height binding by comparing the projector's
         * reported size against the View that actually owns this projection. Runs once; a
         * mismatch in both orientations leaves the original guess in place rather than looping.
         */
        void calibrate(Object projector, int viewportWidth, int viewportHeight) {
            if (!needsCalibration) return;
            needsCalibration = false;
            try {
                int reportedWidth = (Integer) width.invoke(projector);
                int reportedHeight = (Integer) height.invoke(projector);
                boolean matches =
                        reportedWidth == viewportWidth && reportedHeight == viewportHeight;
                boolean swappedMatches =
                        reportedWidth == viewportHeight && reportedHeight == viewportWidth;
                if (!matches && swappedMatches) {
                    Method originalWidth = width;
                    width = height;
                    height = originalWidth;
                    HookLog.i("DexKit width/height binding swapped after calibration");
                }
            } catch (Throwable ignored) {
                // Leave the original guess in place; a persistent mismatch surfaces as 0x0
                // viewport dimensions, which sampleAndProject already treats as "no frame".
            }
        }
    }


    private static boolean sampleAndProject(
            Object projector, ProjectionAccess access, long generation) throws Exception {
        int height = (Integer) access.height.invoke(projector);
        int width = (Integer) access.width.invoke(projector);
        if (width <= 0 || height <= 0) return false;

        List<LiveMarker> liveMarkers = LiveOverlayDataStore.getMarkers();
        // sample() takes ownership: retain a fresh array, but avoid a temporary list and toArray.
        MapsProjectionOverlay.ProjectedMarker[] out =
                new MapsProjectionOverlay.ProjectedMarker[liveMarkers.size()];
        int count = 0;

        for (int i = 0; i < liveMarkers.size(); i++) {
            LiveMarker marker = liveMarkers.get(i);
            if (!Double.isFinite(marker.lat) || !Double.isFinite(marker.lon)) continue;
            Object pixel = access.project(projector, marker.lat, marker.lon);
            if (pixel == null) continue;
            float x = access.x.getFloat(pixel);
            float y = access.y.getFloat(pixel);
            if (!Float.isFinite(x) || !Float.isFinite(y)) continue;

            float rotation = 0f;
            if (marker.kind == LiveMarker.Kind.AIRCRAFT && Double.isFinite(marker.headingDegrees)) {
                double[] ahead =
                        destinationPoint(marker.lat, marker.lon, 30.0, marker.headingDegrees);
                Object headPx = access.project(projector, ahead[0], ahead[1]);
                if (headPx != null) {
                    float dx = access.x.getFloat(headPx) - x;
                    float dy = access.y.getFloat(headPx) - y;
                    if (Float.isFinite(dx) && Float.isFinite(dy)) {
                        rotation = (float) Math.toDegrees(Math.atan2(dx, -dy));
                    }
                }
            }
            out[count++] = new MapsProjectionOverlay.ProjectedMarker(marker, x, y, rotation);
        }

        boolean sampled =
                MapsProjectionOverlay.sample(
                        generation,
                        count == out.length ? out : java.util.Arrays.copyOf(out, count),
                        width,
                        height);
        if (sampled) HookLog.once("maps-live-projection", "Maps live projection is active");
        return sampled;
    }


    /**
     * Spherical forward problem: point reached from (lat, lon) by travelling distMeters along
     * bearingDeg. Returns [lat2, lon2] in degrees.
     */
    static double[] destinationPoint(double lat, double lon, double distMeters, double bearingDeg) {
        final double R = 6_371_008.8;
        double delta = distMeters / R;
        double theta = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat);
        double lam1 = Math.toRadians(lon);
        double phi2 =
                Math.asin(
                        Math.sin(phi1) * Math.cos(delta)
                                + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
        double lam2 =
                lam1
                        + Math.atan2(
                                Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                                Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
        return new double[] {
            Math.toDegrees(phi2), ((Math.toDegrees(lam2) + 180) % 360 + 360) % 360 - 180
        };
    }
}
