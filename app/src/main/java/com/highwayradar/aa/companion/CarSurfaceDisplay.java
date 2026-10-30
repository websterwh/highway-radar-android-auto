package com.highwayradar.aa.companion;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.car.app.SurfaceContainer;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Mirrors the installed Highway Radar app onto the car surface via a private virtual display. */
final class CarSurfaceDisplay implements AutoCloseable {
    private static final String TAG = "HighwayRadarDisplay";
    private static volatile WeakReference<CarSurfaceDisplay> activeDisplay =
            new WeakReference<>(null);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ProjectedInputSession inputSession;
    private final ThreadPoolExecutor commands =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
    private VirtualDisplay display;
    private Surface output;
    private int width;
    private int height;
    private int dpi;
    private boolean launched;
    private boolean needsLaunch;
    private String launchError;
    private volatile boolean closed;
    private volatile long commandGeneration;

    CarSurfaceDisplay(Context context) {
        this.context = context;
        this.inputSession = new ProjectedInputSession(context);
    }

    void attach(SurfaceContainer container) {
        if (closed) return;
        Surface next = container.getSurface();
        if (next == null
                || !next.isValid()
                || container.getWidth() <= 0
                || container.getHeight() <= 0) return;
        int nextWidth = container.getWidth();
        int nextHeight = container.getHeight();
        int nextDpi = Math.max(120, container.getDpi());
        if (display != null && output != next) {
            detach();
        }
        width = nextWidth;
        height = nextHeight;
        dpi = nextDpi;
        if (display == null) {
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            // Intentionally omit VIRTUAL_DISPLAY_FLAG_PUBLIC: SystemUI's
            // KeyguardDisplayManager only installs the secondary-display keyguard
            // overlay on public displays.  A private display is owned by this app;
            // the system never draws the keyguard clock over it, so Highway Radar
            // remains visible while the physical phone is locked or screen-off.
            // Root is required to launch on a private display (am start --display),
            // which is already a precondition of this passthrough.
            display =
                    manager.createVirtualDisplay(
                            "Highway Radar car surface",
                            width,
                            height,
                            dpi,
                            next,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (display == null) throw new IllegalStateException("Virtual display creation failed");
            needsLaunch = true;
        } else {
            display.resize(width, height, dpi);
            display.setSurface(next);
            // Normal menu returns retain the existing task. An explicit force-stop
            // removes it, so recover only that case rather than relaunching every time.
            try {
                ApplicationInfo app =
                        context.getPackageManager().getApplicationInfo("com.highwayradar.app", 0);
                if ((app.flags & ApplicationInfo.FLAG_STOPPED) != 0) needsLaunch = true;
            } catch (PackageManager.NameNotFoundException error) {
                needsLaunch = true;
            }
        }
        output = next;
        activeDisplay = new WeakReference<>(this);
        Log.i(
                TAG,
                "Attached display="
                        + display.getDisplay().getDisplayId()
                        + " surface="
                        + width
                        + "x"
                        + height);
        inputSession.configure(display.getDisplay().getDisplayId(), width, height);
        refresh();
    }

    /** Keep the display and its activity; only the host's temporary output is detached. */
    void detach() {
        if (activeDisplay.get() == this) activeDisplay = new WeakReference<>(null);
        if (display != null && output != null) {
            discardPendingCommands();
            inputSession.configure(-1, 0, 0);
            display.setSurface(null);
            output = null;
            Log.i(TAG, "Detached display=" + display.getDisplay().getDisplayId());
        }
    }

    void onHostResumed() {
        if (closed || display == null) return;
        launchError = null;
        boolean reset = AppSettings.get(context).isResetOnFocus();
        int flags = reset ? 0x10008000 : 0x10020000;
        launchActivity(
                "am start --display "
                        + display.getDisplay().getDisplayId()
                        + " -f "
                        + String.format("0x%08x", flags)
                        + " -n com.highwayradar.app/.ui.main_activity.MainActivity");
    }

    void sendBack() {
        if (closed) return;
        inputSession.sendBack();
    }

    static void sendBackToActive() {
        CarSurfaceDisplay active = activeDisplay.get();
        if (active != null) active.sendBack();
    }

    void refresh() {
        if (closed || display == null || output == null || !output.isValid()) return;
        if (!launched) {
            launched = true;
            Log.i(TAG, "Launching real Highway Radar app on car surface");
        }
        if (needsLaunch) {
            launchError = null;
            needsLaunch = false;
            context.grantUriPermission(
                    "com.highwayradar.app",
                    android.net.Uri.parse(
                            "content://com.highwayradar.aa.companion.settings/projection"),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            launchActivity(
                    "am start --display "
                            + display.getDisplay().getDisplayId()
                            + " -f 0x10000000 -n"
                            + " com.highwayradar.app/.ui.main_activity.MainActivity");
        }
    }

    void click(float x, float y) {
        if (launchError != null) {
            launchError = null;
            onHostResumed();
            return;
        }
        if (!canInput()) {
            Log.w(
                    TAG,
                    "click dropped: canInput=false (closed="
                            + closed
                            + " display="
                            + display
                            + " output="
                            + output
                            + " launchError="
                            + launchError
                            + ")");
            return;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        if (x < 0 || y < 0 || x >= width || y >= height) {
            Log.w(
                    TAG,
                    "click out of bounds: ("
                            + x
                            + ", "
                            + y
                            + ") width="
                            + width
                            + " height="
                            + height);
            return;
        }
        inputSession.tap(x, y);
    }

    void scroll(float dx, float dy) {
        if (!canInput() || !Float.isFinite(dx) || !Float.isFinite(dy)) return;
        inputSession.scroll(dx, dy);
    }

    private boolean canInput() {
        return !closed && display != null && output != null && launchError == null;
    }

    static boolean startIfAttached() {
        CarSurfaceDisplay owner = activeDisplay.get();
        if (owner == null || owner.closed) return false;
        return owner.main.post(
                () -> {
                    if (owner.closed || activeDisplay.get() != owner) return;
                    owner.refresh();
                });
    }

    static boolean launchProjectedActivity(Bundle request) {
        CarSurfaceDisplay owner = activeDisplay.get();
        if (owner == null || request == null) return false;
        int displayId = request.getInt("display_id", -1);
        int taskId = request.getInt("task_id", -1);
        String uri = request.getString("intent_uri");
        if (displayId <= 0 || taskId < 0 || uri == null) return false;
        final Intent intent;
        try {
            intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        } catch (java.net.URISyntaxException error) {
            return false;
        }
        if (intent.getComponent() == null
                || !"com.highwayradar.app".equals(intent.getComponent().getPackageName()))
            return false;
        return owner.main.post(
                () -> {
                    if (!owner.canInput()
                            || activeDisplay.get() != owner
                            || owner.display.getDisplay().getDisplayId() != displayId) return;
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    owner.launchActivity(
                            "am start --display "
                                    + displayId
                                    + " --task "
                                    + taskId
                                    + " '"
                                    + intent.toUri(Intent.URI_INTENT_SCHEME).replace("'", "'\\''")
                                    + "'");
                });
    }

    private void launchActivity(String command) {
        if (closed) return;
        long generation = commandGeneration;
        try {
            commands.execute(() -> runLaunch(command, generation));
        } catch (RejectedExecutionException error) {
            if (!closed) Log.w(TAG, "Activity launch queue is full");
        }
    }

    private void runLaunch(String command, long generation) {
        if (closed || generation != commandGeneration) return;
        File file = null;
        Process process = null;
        try {
            file = File.createTempFile("car-display-", ".log", context.getCacheDir());
            process =
                    new ProcessBuilder("su", "-c", command)
                            .redirectErrorStream(true)
                            .redirectOutput(file)
                            .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Root command timed out");
            }
            String result = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (process.exitValue() != 0
                    || result.contains("Error:")
                    || result.contains("Exception")) {
                throw new IllegalStateException("Root command failed: " + result);
            }
            Log.i(TAG, command + ": " + result);
            main.post(
                    () -> {
                        if (closed || generation != commandGeneration) return;
                        if (launchError != null) {
                            launchError = null;
                            refresh();
                        }
                    });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (!closed) {
                Log.e(TAG, "Real app command failed", error);
                main.post(
                        () -> {
                            if (closed || generation != commandGeneration) return;
                            launchError =
                                    "Cannot launch Highway Radar. Check companion root access in"
                                            + " KernelSU.";
                        });
            }
        } finally {
            if (process != null) process.destroy();
            if (file != null && !file.delete()) Log.w(TAG, "Could not delete command log");
        }
    }

    private void discardPendingCommands() {
        commandGeneration++;
        commands.getQueue().clear();
    }

    @Override
    public void close() {
        closed = true;
        inputSession.close();
        commands.shutdownNow();
        detach();
        if (display != null) {
            display.release();
            display = null;
        }
    }
}
