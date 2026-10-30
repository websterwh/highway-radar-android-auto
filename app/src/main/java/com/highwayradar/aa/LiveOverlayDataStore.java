package com.highwayradar.aa;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Shared in-process state for the Google Maps projection overlay.
 *
 * <h3>Two independent freshness windows</h3>
 * <pre>
 *   METADATA_TIMEOUT_MS (15 s) — governs isDetectorConnected() and hasRecentDetectorState().
 *     Renewed by every valid relay: full signal relay, state-only relay, and disconnect relay.
 *     If no relay arrives for 15 s (HR killed, Maps restarted) isDetectorConnected() flips to
 *     false and the renderer shows "Waiting" rather than a stale "Connected" reading.
 *
 *   DETECTOR_TIMEOUT_MS  (6 s) — governs getSignals() and hasRecentDetectorSignals().
 *     Renewed only when the relay envelope contains a "signals" key.
 *     A state-only relay (no "signals" key) does NOT renew this timer, so signal rows still
 *     expire on their own schedule even if connection state continues to arrive.
 * </pre>
 *
 * <h3>Renderer decision tree</h3>
 * <pre>
 *   !hasRecentDetectorState()             → "Waiting" / hide widget (feed silent)
 *   isDetectorConnected() + !hasRecentDetectorSignals() → model name + "Connected"
 *   hasRecentDetectorSignals()            → signal bars
 *   !isDetectorConnected()                → model name + "Disconnected"
 * </pre>
 *
 * <h3>Protocol (v2)</h3>
 * <pre>{@code
 * Full relay:        { "model": string|null, "connected": bool, "signals": [...] }
 * State-only relay:  { "model": string|null, "connected": bool }          // no "signals" key
 * }</pre>
 */
public final class LiveOverlayDataStore {

    /** Signal rows expire after this many ms of silence. */
    static final long DETECTOR_TIMEOUT_MS  = 6_000L;

    /**
     * Metadata (model + connected flag) is considered stale after this many ms without any
     * relay.  Covers HR process death, Maps cold-start before HR sends its first relay, etc.
     * Longer than {@link #DETECTOR_TIMEOUT_MS} because quiet driving (no radar) still
     * generates BLE notifications — but generous enough not to fire during a brief hiccup.
     */
    static final long METADATA_TIMEOUT_MS  = 15_000L;

    // -------------------------------------------------------------------------
    // State — all volatile for cross-thread visibility
    // -------------------------------------------------------------------------

    /** Alert markers from the AlertRelay. */
    private static volatile List<LiveMarker> currentMarkers = Collections.emptyList();

    /** Model display label from HR's own AppSettings; persists across disconnects. */
    private static volatile String  currentModel = null;

    /**
     * The {@code connected} flag from the most recent relay. Meaningful only while
     * {@link #lastMetadataReceivedAtMs} is within {@link #METADATA_TIMEOUT_MS}.
     */
    private static volatile boolean currentConnected = false;

    /** Signal rows; replaced on each full relay. */
    private static volatile List<LiveSignal> currentSignals = Collections.emptyList();

    /**
     * {@link SystemClock#elapsedRealtime()} at which the last valid envelope was parsed,
     * regardless of whether it carried signals.  Zero until first relay.
     */
    private static volatile long lastMetadataReceivedAtMs = 0L;

    /**
     * {@link SystemClock#elapsedRealtime()} at which the last relay carrying a {@code "signals"}
     * key was parsed.  Zero until first signal relay.
     */
    private static volatile long lastSignalReceivedAtMs = 0L;

    // -------------------------------------------------------------------------
    // Expiry redraw — two separate one-shot Handlers (signal and metadata)
    // -------------------------------------------------------------------------

    private static volatile Handler  sharedHandler;
    private static          Runnable signalExpiryRunnable;
    private static          Runnable metadataExpiryRunnable;

    private LiveOverlayDataStore() {}

    // =========================================================================
    // Alert markers
    // =========================================================================

    public static void updateAlertsFromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            currentMarkers = Collections.emptyList();
            return;
        }
        try {
            JSONArray array = new JSONArray(json);
            List<LiveMarker> markers = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj == null) continue;
                String id       = obj.optString("id", "");
                String category = obj.optString("heuristicCategory", "");
                if (obj.isNull("lat") || obj.isNull("lon")) continue;
                double lat = obj.optDouble("lat", Double.NaN);
                double lon = obj.optDouble("lon", Double.NaN);
                if (!Double.isFinite(lat) || !Double.isFinite(lon)) continue;

                LiveMarker.Kind kind = LiveMarker.Kind.POLICE;
                if ("aircraft".equalsIgnoreCase(category) || id.startsWith("aircraft/")) {
                    kind = LiveMarker.Kind.AIRCRAFT;
                } else if ("camera".equalsIgnoreCase(category) || id.startsWith("camera/")) {
                    kind = LiveMarker.Kind.CAMERA;
                }

                String title = obj.optString("title", "");
                String label = title.isEmpty()
                        ? (kind == LiveMarker.Kind.AIRCRAFT ? "Aircraft" : "Police")
                        : title;

                double headingDeg = 0.0;
                if (!obj.isNull("directionRad")) {
                    headingDeg = (Math.toDegrees(obj.optDouble("directionRad", 0.0)) + 360.0) % 360.0;
                }

                int altFt = obj.isNull("altitudeFt") ? 0 : obj.optInt("altitudeFt", 0);
                markers.add(new LiveMarker(id, kind, label, lat, lon, headingDeg, altFt));
            }
            currentMarkers = Collections.unmodifiableList(markers);

        } catch (Throwable t) {
            HookLog.w("LiveOverlayDataStore: alerts JSON error: " + t);
        }
    }

    // =========================================================================
    // Detector envelope
    // =========================================================================

    /**
     * Applies a v2 detector envelope.
     *
     * <p>Rules:
     * <ul>
     *   <li>Malformed input is silently discarded; existing state is unchanged.
     *   <li>Every valid envelope (including disconnect) renews {@code lastMetadataReceivedAtMs}
     *       and (re-)schedules the metadata expiry redraw.
     *   <li>On {@code connected=false}: signal rows are cleared immediately; signal expiry
     *       timer is cancelled.
     *   <li>When the {@code "signals"} key is <em>present</em>: signal rows are replaced and
     *       the signal expiry timer is (re-)scheduled.
     *   <li>When the {@code "signals"} key is <em>absent</em> (state-only relay): existing
     *       signal rows and signal expiry timer are left untouched.
     * </ul>
     */
    public static void updateDetectorFromJson(String json) {
        ParsedEnvelope env = parseEnvelope(json);
        if (env.malformed) {
            HookLog.warnOnce("detector-envelope", "Malformed detector envelope: " + env.parseError);
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (!Objects.equals(currentModel, env.model)) {
            currentSignals = Collections.emptyList();
            lastSignalReceivedAtMs = 0L;
            cancelSignalExpiry();
        }

        // --- metadata (always) --------------------------------------------------
        currentModel              = env.model;
        currentConnected          = env.connected;
        lastMetadataReceivedAtMs  = now;
        scheduleMetadataExpiry();

        // --- signals (conditional) ----------------------------------------------
        if (!env.connected) {
            // Disconnected: no active readings possible.
            currentSignals        = Collections.emptyList();
            lastSignalReceivedAtMs = 0L;
            cancelSignalExpiry();
            return;
        }
        if (env.signals != null) {
            // Full relay with signals key present (may be empty if no alerts active).
            currentSignals = env.signals.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(env.signals);
            lastSignalReceivedAtMs = now;
            scheduleSignalExpiry();
        }
        // State-only relay (signals == null): signal rows and timer untouched.
    }

    // =========================================================================
    // Renderer API  (MapsProjectionOverlay calls these — do not rename/remove)
    // =========================================================================

    /** Markers for the map layer. */
    public static List<LiveMarker> getMarkers() {
        return currentMarkers;
    }

    /**
     * Model display label as reported by the HR app, e.g. {@code "Uniden R8"},
     * {@code "Valentine V1 (gen 2)"}, or {@code null} when no detector is selected.
     * Persists across disconnects; reflects the user's setting in the HR app.
     */
    public static String getDetectorModel() {
        return currentModel;
    }

    /**
     * {@code true} only when the HR app has an active BLE session <em>and</em> the feed
     * is fresh (a relay arrived within the last {@value #METADATA_TIMEOUT_MS} ms).
     *
     * <p>Returns {@code false} when HR was killed without sending a disconnect relay — the
     * stale {@code currentConnected} flag is ignored once the metadata window expires.
     *
     * <p>Use {@link #hasRecentDetectorState()} to distinguish "disconnected but feed alive"
     * from "feed silent / HR gone".
     */
    public static boolean isDetectorConnected() {
        return currentConnected && isMetadataFresh();
    }

    /**
     * {@code true} when a relay of any kind arrived within the last
     * {@value #METADATA_TIMEOUT_MS} ms.
     *
     * <ul>
     *   <li>{@code false} → feed is silent; HR is likely dead or Maps cold-started before
     *       HR sent its first relay.  Renderer should show "Waiting".
     *   <li>{@code true} + {@link #isDetectorConnected()} {@code false} → feed is alive but
     *       BLE is disconnected.  Renderer may show model name + "Disconnected".
     *   <li>{@code true} + {@link #isDetectorConnected()} {@code true} → renderer may show
     *       model name + signal HUD.
     * </ul>
     */
    public static boolean hasRecentDetectorState() {
        return isMetadataFresh();
    }

    /**
     * Live signal rows for the HUD.  Returns an empty list when no signal relay has been
     * received within the last {@value #DETECTOR_TIMEOUT_MS} ms.  Never returns stale rows.
     */
    public static List<LiveSignal> getSignals() {
        if (!isSignalFresh()) return Collections.emptyList();
        return currentSignals;
    }

    /**
     * {@code true} when {@link #getSignals()} would return a non-empty list.
     * Signals expire independently of the metadata/connection window.
     */
    public static boolean hasRecentDetectorSignals() {
        return !currentSignals.isEmpty() && isSignalFresh();
    }

    // =========================================================================
    // Freshness helpers
    // =========================================================================

    private static boolean isMetadataFresh() {
        return lastMetadataReceivedAtMs > 0L
                && SystemClock.elapsedRealtime() - lastMetadataReceivedAtMs < METADATA_TIMEOUT_MS;
    }

    private static boolean isSignalFresh() {
        return lastSignalReceivedAtMs > 0L
                && SystemClock.elapsedRealtime() - lastSignalReceivedAtMs < DETECTOR_TIMEOUT_MS;
    }

    // =========================================================================
    // Expiry redraw scheduling
    // =========================================================================

    /** Arms a redraw at exactly {@link #DETECTOR_TIMEOUT_MS} after the latest signal relay. */
    private static void scheduleSignalExpiry() {
        Handler h = handler();
        if (h == null) return;
        if (signalExpiryRunnable == null) {
            signalExpiryRunnable = () -> {
                currentSignals = Collections.emptyList();
                MapsProjectionOverlay.requestRedraw();
            };
        }
        h.removeCallbacks(signalExpiryRunnable);
        h.postDelayed(signalExpiryRunnable, DETECTOR_TIMEOUT_MS);
    }

    private static void cancelSignalExpiry() {
        Handler h = sharedHandler;
        if (h != null && signalExpiryRunnable != null) {
            h.removeCallbacks(signalExpiryRunnable);
        }
    }

    /**
     * Arms a redraw at exactly {@link #METADATA_TIMEOUT_MS} after the latest relay.
     * When it fires, {@link #isMetadataFresh()} becomes false, causing
     * {@link #isDetectorConnected()} and {@link #hasRecentDetectorState()} to return false.
     */
    private static void scheduleMetadataExpiry() {
        Handler h = handler();
        if (h == null) return;
        if (metadataExpiryRunnable == null) {
            metadataExpiryRunnable = () -> MapsProjectionOverlay.requestRedraw();
        }
        h.removeCallbacks(metadataExpiryRunnable);
        h.postDelayed(metadataExpiryRunnable, METADATA_TIMEOUT_MS);
    }

    private static Handler handler() {
        Handler h = sharedHandler;
        if (h != null) return h;
        Looper main = Looper.getMainLooper();
        if (main == null) return null;
        h = new Handler(main);
        sharedHandler = h;
        return h;
    }

    // =========================================================================
    // Envelope parser (package-private — called by DetectorEnvelopeTest on JVM)
    // =========================================================================

    /**
     * Parses a v2 detector envelope. No Android API calls; safe in JVM unit tests.
     *
     * <p>{@code ParsedEnvelope.signals == null}  → "signals" key absent (state-only relay)<br>
     * {@code ParsedEnvelope.signals.isEmpty()}  → key present, zero rows (connected, no alerts)<br>
     * {@code ParsedEnvelope.malformed}          → unparseable; caller must not apply the result
     */
    static ParsedEnvelope parseEnvelope(String json) {
        if (json == null || json.trim().isEmpty()) {
            return ParsedEnvelope.malformed("null or empty input");
        }
        try {
            JSONObject obj = new JSONObject(json);

            String  model     = obj.isNull("model") ? null : obj.optString("model", null);
            boolean connected = obj.optBoolean("connected", false);

            List<LiveSignal> signals = null;  // null ↔ key absent
            if (obj.has("signals")) {
                JSONArray arr = obj.getJSONArray("signals");
                signals = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject sig = arr.optJSONObject(i);
                    if (sig == null) continue;
                    double frequency = sig.optDouble("frequencyGhz", 0.0);
                    if (!Double.isFinite(frequency) || frequency < 0) continue;
                    signals.add(new LiveSignal(
                            sig.optString("band", "Radar"),
                            frequency,
                            Math.max(0, Math.min(8, sig.optInt("strength", 0))),
                            sig.optInt("rawStrength", 0),
                            sig.optString("direction", "NOT_AVAILABLE"),
                            sig.optBoolean("front", true),
                            sig.optBoolean("muted", false),
                            0L  /* receivedAtMs set by caller in live path */));
                }
            }
            return new ParsedEnvelope(model, connected, signals, false, null);
        } catch (Throwable t) {
            return ParsedEnvelope.malformed(t.getMessage());
        }
    }

    // =========================================================================
    // ParsedEnvelope
    // =========================================================================

    /**
     * Immutable result of {@link #parseEnvelope(String)}.
     *
     * <table>
     *   <tr><th>{@code signals}</th><th>Meaning</th></tr>
     *   <tr><td>{@code null}</td><td>key absent — state-only relay; do not touch signal rows</td></tr>
     *   <tr><td>empty list</td><td>key present, no active alerts (connected empty)</td></tr>
     *   <tr><td>non-empty</td><td>active signal rows</td></tr>
     * </table>
     * When {@code malformed} is {@code true} all other fields are undefined.
     */
    static final class ParsedEnvelope {
        final String          model;
        final boolean         connected;
        /** {@code null} when the "signals" key was absent. */
        final List<LiveSignal> signals;
        final boolean         malformed;
        final String          parseError;

        ParsedEnvelope(String model, boolean connected, List<LiveSignal> signals,
                boolean malformed, String parseError) {
            this.model      = model;
            this.connected  = connected;
            this.signals    = signals;
            this.malformed  = malformed;
            this.parseError = parseError;
        }

        static ParsedEnvelope malformed(String reason) {
            return new ParsedEnvelope(null, false, null, true, reason);
        }

        /** {@code true} when the "signals" key was present (array may be empty). */
        boolean hasSignals() { return !malformed && signals != null; }
    }
}
