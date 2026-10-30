package com.highwayradar.aa;

/** Standalone JVM checks; compile this with ProjectionCameraPolicy.java, then run main. */
public final class ProjectionCameraPolicyTest {
    public static void main(String[] args) {
        ProjectionCameraPolicy policy = new ProjectionCameraPolicy();
        Object phone = new Object();
        Object projection = new Object();
        Object other = new Object();

        policy.beginSelection(800, 480);
        policy.consider(phone, false, 1080, 2400);
        check(policy.selected() == null, "Physical viewport cannot win by being the latest camera");
        policy.consider(projection, false, 800, 480);
        check(policy.selected() == projection && !policy.isProven(), "Unique dimensions fallback");
        policy.consider(projection, false, 800, 480);
        check(policy.selected() == projection, "Repeated same manager is not ambiguity");
        policy.consider(other, false, 800, 480);
        check(
                policy.selected() == null && policy.isAmbiguous(),
                "Same-size foreign camera fails closed");
        policy.consider(phone, false, 800, 480);
        check(policy.selected() == null, "A third camera cannot erase ambiguity");
        policy.consider(projection, true, 800, 480);
        check(
                policy.selected() == projection && policy.isProven() && !policy.isAmbiguous(),
                "Proven viewport resolves unbound ambiguity");
        policy.consider(phone, false, 800, 480);
        check(policy.selected() == projection, "Unbound read cannot replace proven camera");
        policy.consider(other, true, 800, 480);
        check(policy.selected() == null && policy.isAmbiguous(), "Two proven owners fail closed");

        policy.beginSelection(480, 800);
        check(
                policy.selected() == null && !policy.isAmbiguous(),
                "New draw discards prior ownership");
        policy.consider(projection, true, 800, 480);
        check(policy.selected() == null, "Resize cannot reuse old viewport pixels");
        policy.consider(projection, true, 480, 800);
        check(policy.selected() == projection, "Updated viewport can acquire its owner");
        policy.beginSelection(0, 0);
        policy.consider(projection, true, 0, 0);
        check(policy.selected() == null, "Unlaid-out viewport is not eligible");

        check(policy.requestFrame(), "First external event schedules one frame");
        for (int i = 0; i < 100; i++) {
            check(!policy.requestFrame(), "Burst coalesces while main post or vsync is pending");
        }
        policy.consumeFrame();
        check(policy.requestFrame(), "Next external event after callback schedules again");
        policy.consumeFrame();
        check(policy.requestFrame(), "Cancellation while hidden releases pending gate");
        policy.consumeFrame();
        System.out.println("Projection camera selection and one-shot frame checks passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
