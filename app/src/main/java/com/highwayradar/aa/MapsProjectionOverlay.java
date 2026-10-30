package com.highwayradar.aa;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class MapsProjectionOverlay extends Drawable {

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static WeakReference<View> content = new WeakReference<>(null);
    private static WeakReference<View> overlayTarget = new WeakReference<>(null);
    private static MapsProjectionOverlay current;
    private static volatile long generation;
    private static long nextGeneration;

    /** Captured at attach and refreshed each sample. Drives all dp→px conversions. */
    private static volatile float displayDensity = 1f;

    /** Mirrors Configuration.UI_MODE_NIGHT_YES from the host process. */
    private static volatile boolean nightMode = false;

    private static final Path POLICE_WINDSHIELD =
            polygon(-5, -7, -3.5f, -3.5f, 3.5f, -3.5f, 5, -7);
    private static final Path POLICE_WINDSHIELD_INNER =
            polygon(-3.8f, -6.5f, -2.8f, -4f, 2.8f, -4f, 3.8f, -6.5f);
    private static final Path AIRCRAFT_GLYPH =
            polygon(
                    0, -23, 4, -14, 4, -5, 21, 5, 21, 10, 4, 5, 3, 15, 9, 20, 9, 23, 0, 20,
                    -9, 23, -9, 20, -3, 15, -4, 5, -21, 10, -21, 5, -4, -5, -4, -14);

    // ── ProjectedMarker ───────────────────────────────────────────────────────

    static final class ProjectedMarker {
        final LiveMarker marker;
        final float x, y, rotationDegrees;

        ProjectedMarker(LiveMarker marker, float x, float y, float rotationDegrees) {
            this.marker = marker;
            this.x = x;
            this.y = y;
            this.rotationDegrees = rotationDegrees;
        }
    }

    // ── Instance state ────────────────────────────────────────────────────────

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hudBounds = new RectF();
    private List<LiveSignal> lastSignals;
    private List<LiveSignal> hudSignals = Collections.emptyList();
    private ProjectedMarker[] markers = new ProjectedMarker[0];
    private int projectionWidth;
    private int projectionHeight;
    private static boolean redrawPosted;
    private int drawableAlpha = 255;

    // ── Attachment ────────────────────────────────────────────────────────────

    private static View findMapSurfaceView(View root) {
        if (root == null) return null;
        if (root instanceof android.view.SurfaceView || root instanceof android.view.TextureView)
            return root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findMapSurfaceView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        String name = root.getClass().getName().toLowerCase(Locale.US);
        if (name.contains("mapview")
                || name.contains("renderview")
                || name.contains("glview")
                || name.contains("mapsurface")) return root;
        return null;
    }

    static void attach(View view) {
        MapsHudInput.install();
        detach(content.get());
        content = new WeakReference<>(view);
        View target = findMapSurfaceView(view);
        if (target == null) {
            target =
                    (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0)
                            ? ((ViewGroup) view).getChildAt(0)
                            : view;
        }
        overlayTarget = new WeakReference<>(target);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        if (view.getDisplay() != null) view.getDisplay().getMetrics(dm);
        displayDensity = dm.density > 0 ? dm.density : 1f;
        Configuration cfg = view.getContext().getResources().getConfiguration();
        nightMode =
                (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        current = new MapsProjectionOverlay();
        generation = ++nextGeneration;
        current.setBounds(0, 0, target.getWidth(), target.getHeight());
        target.getOverlay().add(current);
        current.invalidateSelf();
        target.invalidate();
        if (target.getWidth() == 0 || target.getHeight() == 0) {
            target.addOnLayoutChangeListener(
                    new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(
                                View changed,
                                int left,
                                int top,
                                int right,
                                int bottom,
                                int oldLeft,
                                int oldTop,
                                int oldRight,
                                int oldBottom) {
                            if (changed.getWidth() <= 0 || changed.getHeight() <= 0) return;
                            changed.removeOnLayoutChangeListener(this);
                            if (content.get() == view) attach(view);
                        }
                    });
        }
        HookLog.i(
                "Maps overlay attached to "
                        + target.getClass().getSimpleName()
                        + " density="
                        + displayDensity
                        + " night="
                        + nightMode
                        + " size="
                        + target.getWidth()
                        + "x"
                        + target.getHeight());
    }

    static void detach(View view) {
        if (view != content.get()) return;
        generation = 0;
        View target = overlayTarget.get();
        if (target != null && current != null) target.getOverlay().remove(current);
        else if (view != null && current != null) view.getOverlay().remove(current);
        current = null;
        content.clear();
        overlayTarget.clear();
    }

    // ── Static accessors ──────────────────────────────────────────────────────

    static long generation() {
        return generation;
    }

    static int viewportWidth() {
        View target = overlayTarget.get();
        return target == null ? 0 : target.getWidth();
    }

    static View hudViewport() {
        return overlayTarget.get();
    }

    static boolean hudHitTest(float x, float y) {
        return current != null
                && isVisibleOwner()
                && OverlaySettings.isOverlayEnabled()
                && OverlaySettings.isHudEnabled()
                && LiveOverlayDataStore.isDetectorConnected()
                && !LiveOverlayDataStore.getSignals().isEmpty()
                && current.hudBounds.contains(x, y);
    }

    static int viewportHeight() {
        View target = overlayTarget.get();
        return target == null ? 0 : target.getHeight();
    }

    static boolean ownsViewport(View cameraView) {
        View target = overlayTarget.get();
        if (cameraView == null || target == null) return false;
        for (View node = target;
                node != null;
                node = node.getParent() instanceof View ? (View) node.getParent() : null) {
            if (node == cameraView) return true;
        }
        return false;
    }

    static boolean isVisibleOwner() {
        View view = content.get();
        return view != null
                && view.isAttachedToWindow()
                && view.isShown()
                && view.getWindowVisibility() == View.VISIBLE;
    }

    static void requestRedraw() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invalidateNow();
            return;
        }
        synchronized (MapsProjectionOverlay.class) {
            if (redrawPosted) return;
            redrawPosted = true;
        }
        mainHandler.post(
                () -> {
                    synchronized (MapsProjectionOverlay.class) {
                        redrawPosted = false;
                    }
                    invalidateNow();
                });
    }

    private static void invalidateNow() {
        MapsProjectionOverlay overlay = current;
        View target = overlayTarget.get();
        if (overlay != null) overlay.invalidateSelf();
        if (target != null) {
            target.invalidate();
            if (target.getParent() instanceof View) ((View) target.getParent()).invalidate();
        }
        View c = content.get();
        if (c != null && c != target) c.invalidate();
    }

    /** Takes ownership of a fresh draw-thread snapshot; never schedules another draw. */
    static boolean sample(long generation, ProjectedMarker[] markers, int width, int height) {
        if (Looper.myLooper() != Looper.getMainLooper()
                || generation == 0
                || generation != MapsProjectionOverlay.generation) return false;
        View view = overlayTarget.get();
        if (view == null) view = content.get();
        MapsProjectionOverlay overlay = current;
        if (view == null || overlay == null || !view.isAttachedToWindow()) return false;
        if (!view.isShown() || view.getWindowVisibility() != View.VISIBLE) return false;
        Configuration cfg = view.getContext().getResources().getConfiguration();
        nightMode =
                (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        overlay.markers = markers;
        overlay.projectionWidth = width;
        overlay.projectionHeight = height;
        overlay.setBounds(0, 0, view.getWidth(), view.getHeight());
        return true;
    }

    // ── Drawable.draw ─────────────────────────────────────────────────────────

    @Override
    public void draw(Canvas canvas) {
        HookLog.once("maps-overlay-draw", "Maps overlay draw is active");
        hudBounds.setEmpty();
        if (!OverlaySettings.isOverlayEnabled()) return;
        // Synchronous projection: update marker positions from the current camera state
        // before rendering, without scheduling a recursive redraw.
        boolean projectionFresh = MapsProjectionProbe.updateLiveFrame();
        int width = getBounds().width();
        int height = getBounds().height();
        if (width <= 0 || height <= 0) return;

        float density = displayDensity;
        float markerScale = density * 0.75f;

        int saved = canvas.save();
        canvas.clipRect(getBounds());

        float sx = MapViewport.scale(width, projectionWidth);
        float sy = MapViewport.scale(height, projectionHeight);
        for (ProjectedMarker p : markers) {
            if (!projectionFresh) break;
            if (p == null
                    || p.marker == null
                    || !Float.isFinite(p.x)
                    || !Float.isFinite(p.y)
                    || !Float.isFinite(p.rotationDegrees)
                    || sx <= 0
                    || sy <= 0
                    || p.x < 0
                    || p.x >= projectionWidth
                    || p.y < 0
                    || p.y >= projectionHeight) continue;
            if (p.marker.kind == LiveMarker.Kind.POLICE && !OverlaySettings.isPoliceEnabled())
                continue;
            if (p.marker.kind == LiveMarker.Kind.AIRCRAFT && !OverlaySettings.isAircraftEnabled())
                continue;
            if (p.marker.kind == LiveMarker.Kind.CAMERA && !OverlaySettings.isPoliceEnabled())
                continue;
            drawMarker(canvas, p, markerScale, density, sx, sy);
        }

        if (OverlaySettings.isHudEnabled()) drawHud(canvas, density, nightMode, width, height);

        canvas.restoreToCount(saved);
    }

    // ── Marker rendering ──────────────────────────────────────────────────────

    private void drawMarker(
            Canvas canvas,
            ProjectedMarker p,
            float scale,
            float density,
            float scaleX,
            float scaleY) {
        int saved = canvas.save();
        canvas.translate(p.x * scaleX, p.y * scaleY);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);

        if (p.marker.kind == LiveMarker.Kind.POLICE) {
            color(Color.argb(80, 0, 0, 0));
            canvas.drawCircle(0, 2, 16.5f, paint);
            color(Color.WHITE);
            canvas.drawCircle(0, 0, 16.5f, paint);
            color(Color.rgb(255, 120, 0));
            canvas.drawCircle(0, 0, 14.5f, paint);
            // Siren bar
            color(Color.WHITE);
            canvas.drawRoundRect(-5, -9, 5, -7, 1, 1, paint);
            color(Color.rgb(235, 40, 40));
            canvas.drawRect(-4, -8.5f, -1, -7.5f, paint);
            color(Color.rgb(40, 100, 240));
            canvas.drawRect(1, -8.5f, 4, -7.5f, paint);
            // Windshield
            color(Color.WHITE);
            canvas.drawPath(POLICE_WINDSHIELD, paint);
            color(Color.rgb(255, 120, 0));
            canvas.drawPath(POLICE_WINDSHIELD_INNER, paint);
            // Body
            color(Color.WHITE);
            canvas.drawRoundRect(-8, -3.5f, 8, 4f, 2, 2, paint);
            color(Color.rgb(255, 120, 0));
            canvas.drawRoundRect(-4, 0.5f, 4, 3f, 1, 1, paint);
            color(Color.WHITE);
            canvas.drawCircle(-5.8f, 1f, 1.2f, paint);
            canvas.drawCircle(5.8f, 1f, 1.2f, paint);
            color(Color.rgb(45, 45, 45));
            canvas.drawRect(-7.5f, 4f, -5, 6f, paint);
            canvas.drawRect(5, 4f, 7.5f, 6f, paint);

        } else if (p.marker.kind == LiveMarker.Kind.CAMERA) {
            color(Color.argb(80, 0, 0, 0));
            canvas.drawCircle(0, 2, 16.5f, paint);
            color(Color.WHITE);
            canvas.drawCircle(0, 0, 16.5f, paint);
            color(Color.rgb(229, 57, 53));
            canvas.drawCircle(0, 0, 14.5f, paint);
            color(Color.WHITE);
            canvas.drawRoundRect(-8, -4, 8, 6, 2, 2, paint);
            canvas.drawRoundRect(-3, -7, 3, -4, 1, 1, paint);
            color(Color.rgb(229, 57, 53));
            canvas.drawCircle(0, 1, 3.5f, paint);
            color(Color.WHITE);
            canvas.drawCircle(0, 1, 1.8f, paint);

        } else {
            // Aircraft — nose toward heading vector
            color(Color.argb(70, 0, 0, 0));
            canvas.drawCircle(6, 6, 12, paint);
            float rotation = MapViewport.heading(p.rotationDegrees, scaleX, scaleY);
            canvas.rotate(rotation);
            color(Color.rgb(255, 157, 40));
            canvas.drawPath(AIRCRAFT_GLYPH, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            color(Color.BLACK);
            canvas.drawPath(AIRCRAFT_GLYPH, paint);
            paint.setStyle(Paint.Style.FILL);
            if (p.marker.altitudeFt > 0) {
                canvas.rotate(-rotation);
                // Text in glyph-space units: target screen size is 10*density px → divide by scale.
                float altSp = 10f * density / scale;
                String altText = String.format(Locale.US, "ALT %,d FT", p.marker.altitudeFt);
                paint.setTextSize(altSp);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextAlign(Paint.Align.CENTER);
                float altW = paint.measureText(altText);
                float altY = 30f;
                color(Color.argb(215, 10, 16, 26));
                canvas.drawRoundRect(
                        -altW / 2 - 4, altY - altSp - 1, altW / 2 + 4, altY + 3, 3, 3, paint);
                color(Color.WHITE);
                canvas.drawText(altText, 0, altY, paint);
            }
        }
        canvas.restoreToCount(saved);
    }

    // ── HUD ──────────────────────────────────────────────────────────────────

    /** Compact readable model/status header and up to two signal rows. */
    private void drawHud(Canvas canvas, float d, boolean night, int canvasW, int canvasH) {
        paint.setTextAlign(Paint.Align.LEFT);
        final float LEFT_CLEARANCE = 84 * d;
        // Anchor in the top right, clear of the left-hand search card.
        final float TOP_INSET = 12 * d;
        final float RIGHT_INSET = 16 * d;
        final float PILL_H = 36 * d;
        final float PILL_R = 8 * d;
        final float ROW_H = 34 * d;
        final float ROW_GAP = 2 * d;
        final float MIN_W = 170 * d;
        final float MAX_W = 260 * d;

        float w = Math.min(MAX_W, canvasW - LEFT_CLEARANCE - RIGHT_INSET);
        if (w < MIN_W || canvasH < PILL_H + TOP_INSET + 8 * d) return;

        // Live state
        String model = LiveOverlayDataStore.getDetectorModel();
        boolean conn = LiveOverlayDataStore.isDetectorConnected();
        List<LiveSignal> allSigs = LiveOverlayDataStore.getSignals();
        boolean hasSigs = allSigs != null && !allSigs.isEmpty();

        // Signal snapshots are immutable. Sort only when a new snapshot arrives,
        // not for every camera frame while panning the map.
        if (allSigs != lastSignals) {
            lastSignals = allSigs;
            List<LiveSignal> sorted = new ArrayList<>(allSigs);
            Collections.sort(sorted, (a, b) -> b.strength - a.strength);
            hudSignals = sorted.size() > 2 ? sorted.subList(0, 2) : sorted;
        }
        List<LiveSignal> rows = hudSignals;
        int rowCount =
                Math.min(
                        rows.size(),
                        Math.max(
                                0,
                                (int)
                                        ((canvasH - TOP_INSET - PILL_H - 8 * d)
                                                / (ROW_H + ROW_GAP))));

        // Palette
        int surface = night ? Color.rgb(10, 16, 26) : Color.rgb(248, 249, 250);
        int modelClr = night ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36);
        int statusClr = night ? Color.rgb(220, 225, 232) : Color.rgb(45, 50, 58);
        int emptyBar = night ? Color.rgb(40, 50, 60) : Color.rgb(195, 200, 205);
        int mutedTxt = night ? Color.rgb(190, 200, 212) : Color.rgb(65, 72, 82);

        // Block geometry — header top at TOP_INSET, rows stacked beneath, aligned right
        float headerTop = TOP_INSET;
        float headerBottom = headerTop + PILL_H;
        float belowH = rowCount > 0 ? rowCount * ROW_H + rowCount * ROW_GAP : 0;
        float blockBottom = headerBottom + belowH;
        float left = canvasW - RIGHT_INSET - w;
        float right = left + w;
        hudBounds.set(left, headerTop, right, blockBottom);

        paint.setStyle(Paint.Style.FILL);

        // ── Header pill ───────────────────────────────────────────────────────
        color(surface);
        canvas.drawRoundRect(left, headerTop, right, headerBottom, PILL_R, PILL_R, paint);
        if (!night) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.75f * d);
            color(Color.argb(28, 0, 0, 0));
            canvas.drawRoundRect(left, headerTop, right, headerBottom, PILL_R, PILL_R, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // Status dot
        float dotCx = left + 11 * d;
        float dotCy = headerTop + PILL_H / 2;
        int dotColor =
                hasSigs
                        ? Color.rgb(255, 160, 0)
                        : conn ? Color.rgb(78, 168, 110) : Color.rgb(95, 105, 115);
        color(dotColor);
        canvas.drawCircle(dotCx, dotCy, 4f * d, paint);

        float textY = headerTop + 24 * d;

        // Metadata freshness is independent of whether the detector has any active hits.
        boolean recentFeed = LiveOverlayDataStore.hasRecentDetectorState();
        String statusLabel =
                hasSigs
                        ? "Tap to mute"
                        : !recentFeed
                                ? "Waiting"
                                : conn
                                        ? "Connected"
                                        : model != null ? "Disconnected" : "Not configured";

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(15 * d);
        float statusWidth = paint.measureText(statusLabel);
        float modelSlotW = Math.max(1, w - 36 * d - statusWidth);
        String modelLabel = (model != null && !model.isEmpty()) ? model : "Detector";
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(18 * d);
        CharSequence modelEllipsized =
                TextUtils.ellipsize(modelLabel, paint, modelSlotW, TextUtils.TruncateAt.END);
        color(modelClr);
        canvas.drawText(modelEllipsized.toString(), left + 22 * d, textY, paint);

        // Status shares one readable header line instead of an empty second line.
        if (statusLabel != null) {
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(15 * d);
            paint.setTextAlign(Paint.Align.RIGHT);
            color(statusClr);
            canvas.drawText(
                    TextUtils.ellipsize(statusLabel, paint, w - 24 * d, TextUtils.TruncateAt.END)
                            .toString(),
                    right - 8 * d,
                    textY,
                    paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        // ── Signal rows (below header) ────────────────────────────────────────
        for (int i = 0; i < rowCount; i++) {
            float rowTop = headerBottom + ROW_GAP + i * (ROW_H + ROW_GAP);
            float rowBottom = rowTop + ROW_H;
            float rowR = 5 * d;

            color(surface);
            canvas.drawRoundRect(left, rowTop, right, rowBottom, rowR, rowR, paint);
            if (!night) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(0.75f * d);
                color(Color.argb(28, 0, 0, 0));
                canvas.drawRoundRect(left, rowTop, right, rowBottom, rowR, rowR, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            drawSignalRow(
                    canvas, rows.get(i), left, rowTop, w, ROW_H, d, night, emptyBar, mutedTxt);
        }
    }

    private void drawSignalRow(
            Canvas canvas,
            LiveSignal sig,
            float left,
            float top,
            float width,
            float rowH,
            float d,
            boolean night,
            int emptyBar,
            int mutedTxt) {
        float baseline = top + rowH * 0.65f;

        // Direction arrow
        String arrow = "";
        if (sig.direction != null) {
            switch (sig.direction.toUpperCase(Locale.US)) {
                case "FRONT":
                    arrow = "↑";
                    break;
                case "REAR":
                    arrow = "↓";
                    break;
                case "LEFT":
                case "SIDE_LEFT":
                    arrow = "←";
                    break;
                case "RIGHT":
                case "SIDE_RIGHT":
                    arrow = "→";
                    break;
                case "SIDE":
                    arrow = "↔";
                    break;
            }
        }

        // 8 strength bars, right-aligned
        final int N = 8;
        final float BW = 4 * d, BG = 2 * d, BMAX = 22 * d;
        final float barsRight = left + width - 8 * d;
        final float barsLeft = barsRight - N * (BW + BG) + BG;
        int barsFilled = Math.max(0, Math.min(N, sig.strength));
        paint.setStyle(Paint.Style.FILL);
        boolean showBars = width >= 240 * d;
        for (int b = 0; showBars && b < N; b++) {
            float barBot = top + rowH - 3 * d;
            float barH = BMAX * (b + 1) / N;
            float bx = barsLeft + b * (BW + BG);
            int c =
                    (b >= barsFilled)
                            ? emptyBar
                            : sig.muted
                                    ? (night ? Color.rgb(170, 185, 200) : Color.rgb(80, 95, 110))
                                    : barsFilled >= 6
                                            ? Color.rgb(234, 67, 53) // red at high strength
                                            : Color.rgb(255, 160, 0); // amber
            color(c);
            canvas.drawRoundRect(bx, barBot - barH, bx + BW, barBot, 1.5f * d, 1.5f * d, paint);
        }

        // Band (bold) + freq+arrow at fixed column
        int txtColor = sig.muted ? mutedTxt : (night ? Color.WHITE : Color.rgb(32, 33, 36));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(19 * d);
        paint.setTextAlign(Paint.Align.LEFT);
        color(txtColor);
        canvas.drawText(
                TextUtils.ellipsize(
                                sig.band != null ? sig.band : "Radar",
                                paint,
                                42 * d,
                                TextUtils.TruncateAt.END)
                        .toString(),
                left + 10 * d,
                baseline,
                paint);

        int freqColor =
                sig.muted ? mutedTxt : night ? Color.rgb(225, 232, 240) : Color.rgb(45, 50, 58);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(17 * d);
        color(freqColor);
        String freqArrow =
                (sig.frequencyGhz > 0 ? String.format(Locale.US, "%.3f", sig.frequencyGhz) : "")
                        + (arrow.isEmpty() ? "" : " " + arrow)
                        + (sig.muted ? " Mute" : "");
        float frequencyWidth = (showBars ? barsLeft - left : width - 10 * d) - 60 * d;
        canvas.drawText(
                TextUtils.ellipsize(freqArrow, paint, frequencyWidth, TextUtils.TruncateAt.END)
                        .toString(),
                left + 60 * d,
                baseline,
                paint);
    }

    // ── Paint helpers ─────────────────────────────────────────────────────────

    private static Path polygon(float... points) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            path.lineTo(points[i], points[i + 1]);
        }
        path.close();
        return path;
    }

    private void color(int c) {
        paint.setColor(c);
        paint.setAlpha((Color.alpha(c) * drawableAlpha + 127) / 255);
    }

    @Override
    public void setAlpha(int alpha) {
        drawableAlpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return drawableAlpha;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public ColorFilter getColorFilter() {
        return paint.getColorFilter();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
