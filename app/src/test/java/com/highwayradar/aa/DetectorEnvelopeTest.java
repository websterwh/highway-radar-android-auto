package com.highwayradar.aa;

import com.highwayradar.aa.LiveOverlayDataStore.ParsedEnvelope;

/**
 * Standalone JVM verification for the v2 detector envelope parser.
 *
 * <h3>Compile and run after assembling the module</h3>
 * <pre>
 *   Use the module's compiled debug classes, the Android SDK android.jar, and a JVM
 *   org.json jar on the classpath. Run this class's main method. Parsing does not call
 *   Android APIs; the SDK jar supplies the store's referenced Android type definitions.
 * </pre>
 *
 * <h3>Five scenarios the parent must distinguish (parser level)</h3>
 * <ol>
 *   <li><b>CONNECTED_EMPTY</b> — connected=true, signals key present, array empty.
 *       Parser: hasSignals()=true, signals.isEmpty()=true.
 *       Store: metadata+signal timestamps both advanced; no signal expiry needed for empty array.
 *   <li><b>DISCONNECTED</b> — connected=false, signals key absent.
 *       Parser: connected=false, hasSignals()=false.
 *       Store: signals cleared immediately; metadata timestamp advanced.
 *   <li><b>NO_FEED</b> — null/empty/never received.
 *       Parser: malformed.
 *       Store: unchanged; hasRecentDetectorState()=false before any relay.
 *   <li><b>MALFORMED</b> — bad JSON.
 *       Parser: malformed=true, error non-null.
 *       Store: unchanged; existing model/connected preserved.
 *   <li><b>EXPIRY</b> — signals key was present; after DETECTOR_TIMEOUT_MS (6 s) getSignals()
 *       returns empty.  After METADATA_TIMEOUT_MS (15 s) isDetectorConnected() returns false
 *       and hasRecentDetectorState() returns false.
 *       Not testable on JVM (no SystemClock); exercise via DHU: send PAYLOAD_WITH_SIGNALS,
 *       wait 7 s → getSignals() empty; wait 16 s total → isDetectorConnected()=false.
 * </ol>
 *
 * <h3>Renderer decision tree (parent calls via DHU / adb logcat)</h3>
 * <pre>
 *   !hasRecentDetectorState()                 → "Waiting" (HR silent / dead)
 *   isDetectorConnected() &amp;&amp; !hasRecentDetectorSignals() → model + "Connected"
 *   hasRecentDetectorSignals()                → signal bars
 *   !isDetectorConnected() &amp;&amp; hasRecentDetectorState()  → model + "Disconnected"
 * </pre>
 *
 * <h3>Canonical DHU broadcast payloads</h3>
 * See constants below.  Send via:
 * <pre>
 *   adb shell am broadcast -a com.highwayradar.aa.DETECTOR_UPDATED \
 *       --es detector_json '&lt;PAYLOAD&gt;' com.google.android.apps.maps
 * </pre>
 */
public final class DetectorEnvelopeTest {

    // ------------------------------------------------------------------
    // Canonical test payloads — use these with adb broadcasts to the Maps
    // process to exercise each scenario end-to-end.
    // ------------------------------------------------------------------

    /** CONNECTED_EMPTY: detector online, no active radar hits. */
    public static final String PAYLOAD_CONNECTED_EMPTY =
            "{\"model\":\"Uniden R8\",\"connected\":true,\"signals\":[]}";

    /** WITH_SIGNALS: one K-band hit front — use to exercise signal expiry (wait 7 s). */
    public static final String PAYLOAD_WITH_SIGNALS =
            "{\"model\":\"Uniden R8\",\"connected\":true,\"signals\":["
            + "{\"band\":\"K\",\"frequencyGhz\":24.15,\"strength\":5,"
            + "\"rawStrength\":80,\"direction\":\"FRONT\",\"front\":true,\"muted\":false}"
            + "]}";

    /** DISCONNECTED: BLE dropped; model persists in store; signals cleared immediately. */
    public static final String PAYLOAD_DISCONNECTED =
            "{\"model\":\"Uniden R8\",\"connected\":false}";

    /**
     * STATE_HEARTBEAT: state-only relay (no "signals" key).
     * Store must renew metadata timestamp (→ isDetectorConnected stays true) but must NOT
     * renew signal timestamp (→ signal rows still expire on their own 6 s timer).
     */
    public static final String PAYLOAD_STATE_HEARTBEAT =
            "{\"model\":\"Uniden R8\",\"connected\":true}";

    /** V1 connected. */
    public static final String PAYLOAD_V1_CONNECTED =
            "{\"model\":\"Valentine V1 (gen 2)\",\"connected\":true,\"signals\":[]}";

    /** DS1 disconnected — model label from HR's own AppSettings. */
    public static final String PAYLOAD_DS1_DISCONNECTED =
            "{\"model\":\"Radenso DS1\",\"connected\":false}";

    /** Null model: detector type set to NONE in HR settings. */
    public static final String PAYLOAD_NO_MODEL =
            "{\"model\":null,\"connected\":false,\"signals\":[]}";

    /** MALFORMED — must not crash or alter existing store state. */
    public static final String PAYLOAD_MALFORMED = "not-valid-json{{";

    // ------------------------------------------------------------------
    // Runner
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        // --- Scenario 1: CONNECTED_EMPTY ---
        connectedEmpty();

        // --- Scenario 2: DISCONNECTED ---
        disconnected();

        // --- Scenario 3: NO_FEED ---
        nullInput();
        emptyInput();

        // --- Scenario 4: MALFORMED ---
        malformed();
        partialJson();

        // --- Scenario 5: EXPIRY — parser side only (timing in Android, see Javadoc) ---
        withSignals();
        stateHeartbeatDoesNotHaveSignals();

        // --- Invariants ---
        modelPreservedOnDisconnect();
        nullModel();
        missingConnectedDefaultsFalse();
        signalFieldRoundtrip();
        v1ModelLabel();
        ds1ModelLabel();

        System.out.println(checks + " detector envelope checks passed");
    }

    // ------------------------------------------------------------------
    // Scenario 1: CONNECTED_EMPTY
    // ------------------------------------------------------------------

    private static void connectedEmpty() {
        ParsedEnvelope e = parse(PAYLOAD_CONNECTED_EMPTY);
        check(!e.malformed,        "CONNECTED_EMPTY parses");
        check(e.connected,         "CONNECTED_EMPTY: connected=true");
        check("Uniden R8".equals(e.model), "CONNECTED_EMPTY: model label correct");
        check(e.hasSignals(),      "CONNECTED_EMPTY: signals key present");
        check(e.signals.isEmpty(), "CONNECTED_EMPTY: zero signal rows");
        // Store behaviour (Android): both timestamps advanced; no signal rows in HUD.
    }

    // ------------------------------------------------------------------
    // Scenario 2: DISCONNECTED
    // ------------------------------------------------------------------

    private static void disconnected() {
        ParsedEnvelope e = parse(PAYLOAD_DISCONNECTED);
        check(!e.malformed,        "DISCONNECTED parses");
        check(!e.connected,        "DISCONNECTED: connected=false");
        check("Uniden R8".equals(e.model), "DISCONNECTED: model persisted in envelope");
        // No signals key → store clears signal rows because connected=false.
        check(!e.hasSignals(),     "DISCONNECTED: no signals key");
        check(e.signals == null,   "DISCONNECTED: signals field null (state-only parse)");
        // Store behaviour: lastMetadataReceivedAtMs advanced; lastSignalReceivedAtMs reset to 0.
        //   getSignals()→[]; isDetectorConnected()→false; hasRecentDetectorState()→true (15 s).
    }

    // ------------------------------------------------------------------
    // Scenario 3: NO_FEED
    // ------------------------------------------------------------------

    private static void nullInput() {
        check(parse(null).malformed, "null input → malformed");
    }

    private static void emptyInput() {
        check(parse("").malformed,   "empty input → malformed");
        check(parse("   ").malformed,"whitespace input → malformed");
    }

    // ------------------------------------------------------------------
    // Scenario 4: MALFORMED
    // ------------------------------------------------------------------

    private static void malformed() {
        ParsedEnvelope e = parse(PAYLOAD_MALFORMED);
        check(e.malformed,           "bad JSON → malformed");
        check(e.parseError != null,  "malformed carries error message");
        // Store behaviour: updateDetectorFromJson returns immediately; no state change.
    }

    private static void partialJson() {
        check(parse("{\"model\":}").malformed, "partial JSON → malformed");
        check(parse("[1,2,3]").malformed,      "bare array rejected as malformed");
    }

    // ------------------------------------------------------------------
    // Scenario 5 (parser side): WITH_SIGNALS, STATE_HEARTBEAT separation
    // ------------------------------------------------------------------

    private static void withSignals() {
        ParsedEnvelope e = parse(PAYLOAD_WITH_SIGNALS);
        check(!e.malformed,          "WITH_SIGNALS parses");
        check(e.connected,           "WITH_SIGNALS: connected");
        check(e.hasSignals(),        "WITH_SIGNALS: signals key present");
        check(e.signals.size() == 1, "WITH_SIGNALS: one signal row");
        LiveSignal s = e.signals.get(0);
        check("K".equals(s.band),           "band=K");
        check(s.frequencyGhz > 24.0,        "freq > 24 GHz");
        check(s.strength == 5,              "strength=5");
        check(s.front,                      "front=true");
        check(!s.muted,                     "muted=false");
        // Store: advances BOTH lastMetadataReceivedAtMs AND lastSignalReceivedAtMs.
        // Signal expiry fires at +6 s; metadata expiry fires at +15 s.
    }

    /**
     * State-only heartbeat: no "signals" key.
     * Parser yields signals==null; store must advance metadata timestamp but leave signal
     * timestamp (and existing rows) untouched.
     */
    private static void stateHeartbeatDoesNotHaveSignals() {
        ParsedEnvelope e = parse(PAYLOAD_STATE_HEARTBEAT);
        check(!e.malformed,   "STATE_HEARTBEAT parses");
        check(e.connected,    "STATE_HEARTBEAT: connected=true");
        check(!e.hasSignals(),"STATE_HEARTBEAT: no signals key → hasSignals()=false");
        check(e.signals == null, "STATE_HEARTBEAT: signals field is null");
        // Store: advances lastMetadataReceivedAtMs (resets 15 s metadata timer).
        //        Does NOT advance lastSignalReceivedAtMs.
        //        Existing signal rows continue expiring on their own 6 s schedule.
    }

    // ------------------------------------------------------------------
    // Invariants
    // ------------------------------------------------------------------

    private static void modelPreservedOnDisconnect() {
        ParsedEnvelope e = parse("{\"model\":\"Radenso DS1\",\"connected\":false}");
        check(!e.malformed,                    "disconnect with DS1 parses");
        check("Radenso DS1".equals(e.model),  "model preserved in disconnect envelope");
        check(!e.connected,                    "connected=false");
    }

    private static void nullModel() {
        ParsedEnvelope e = parse(PAYLOAD_NO_MODEL);
        check(!e.malformed,         "null model parses");
        check(e.model == null,      "model is null");
        check(!e.connected,         "connected=false");
        check(e.hasSignals(),       "signals key present");
        check(e.signals.isEmpty(),  "signals empty");
    }

    private static void missingConnectedDefaultsFalse() {
        ParsedEnvelope e = parse("{\"model\":\"Uniden R9\",\"signals\":[]}");
        check(!e.malformed,             "missing connected key parses");
        check(!e.connected,             "missing connected defaults to false");
        check("Uniden R9".equals(e.model), "model still parsed");
    }

    private static void signalFieldRoundtrip() {
        String json = "{\"model\":\"Uniden R4\",\"connected\":true,\"signals\":["
                + "{\"band\":\"Ka\",\"frequencyGhz\":35.5,\"strength\":3,"
                + "\"rawStrength\":50,\"direction\":\"REAR\",\"front\":false,\"muted\":true},"
                + "{\"band\":\"K\",\"frequencyGhz\":24.2,\"strength\":1,"
                + "\"rawStrength\":20,\"direction\":\"NOT_AVAILABLE\",\"front\":false,\"muted\":false}"
                + "]}";
        ParsedEnvelope e = parse(json);
        check(!e.malformed,          "multi-signal parses");
        check(e.signals.size() == 2, "two signal rows");
        LiveSignal ka = e.signals.get(0);
        check("Ka".equals(ka.band),  "band Ka");
        check(!ka.front,             "rear");
        check(ka.muted,              "muted");
        LiveSignal k = e.signals.get(1);
        check("K".equals(k.band),   "band K");
        check(k.strength == 1,       "strength 1");
        check(!k.muted,              "not muted");
    }

    private static void v1ModelLabel() {
        ParsedEnvelope e = parse(PAYLOAD_V1_CONNECTED);
        check(!e.malformed,                           "V1 parses");
        check("Valentine V1 (gen 2)".equals(e.model),"V1 label matches HR app string");
    }

    private static void ds1ModelLabel() {
        ParsedEnvelope e = parse(PAYLOAD_DS1_DISCONNECTED);
        check(!e.malformed,                  "DS1 disconnect parses");
        check("Radenso DS1".equals(e.model), "DS1 label matches HR app string");
        check(!e.connected,                  "DS1 disconnected");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ParsedEnvelope parse(String json) {
        return LiveOverlayDataStore.parseEnvelope(json);
    }

    private static int checks;

    private static void check(boolean value, String failure) {
        checks++;
        if (!value) throw new AssertionError(failure);
    }
}
