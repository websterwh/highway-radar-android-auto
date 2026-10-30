package com.highwayradar.aa.companion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.car.app.connection.CarConnection;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Wakeable connection observer. Broadcast extras never authorize a start/stop command. */
public final class CarConnectionReceiver extends BroadcastReceiver {
    public static final String ACTION_REFRESH = "com.highwayradar.aa.CONNECTION_REFRESH";
    public static final String ACTION_STOPPED = "com.highwayradar.aa.APP_STOPPED";
    public static final String ACTION_BACK = "com.highwayradar.aa.ACTION_BACK";
    private static final String TAG = "HighwayRadarConnection";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ACTION_BACK.equals(intent.getAction())) {
            CarSurfaceDisplay.sendBackToActive();
            return;
        }
        if (!(ACTION_REFRESH.equals(intent.getAction())
                || CarConnection.ACTION_CAR_CONNECTION_UPDATED.equals(intent.getAction()))) return;
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        WORKER.execute(
                () -> {
                    try {
                        update(app);
                    } catch (Exception error) {
                        Log.e(TAG, "Connection automation failed", error);
                    } finally {
                        pending.finish();
                    }
                });
    }

    private static void update(Context context) throws Exception {
        // Query the host's live provider rather than accepting a spoofable broadcast state.
        boolean connected;
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                Uri.parse("content://androidx.car.app.connection"),
                                new String[] {CarConnection.CAR_CONNECTION_STATE},
                                null,
                                null,
                                null)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int column = cursor.getColumnIndex(CarConnection.CAR_CONNECTION_STATE);
            if (column < 0) return;
            int state = cursor.getInt(column);
            if (state != CarConnection.CONNECTION_TYPE_PROJECTION
                    && state != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                    && state != CarConnection.CONNECTION_TYPE_NATIVE) return;
            connected = state == CarConnection.CONNECTION_TYPE_PROJECTION;
        }
        SharedPreferences prefs =
                context.getSharedPreferences("connection_runtime", Context.MODE_PRIVATE);
        boolean previous = prefs.getBoolean("connected", false);
        AppSettings settings = AppSettings.get(context);
        ConnectionPolicy.Action action =
                ConnectionPolicy.action(
                        previous, connected, settings.isAutoStart(), settings.isAutoStop());
        if (previous == connected) return;
        // Persist the observation even when automation is disabled. Auto-stop must also
        // work after manual starts and after the companion process has been reclaimed.
        if (!prefs.edit().putBoolean("connected", connected).commit()) {
            throw new IllegalStateException("Could not persist connection edge");
        }
        Log.i(TAG, "Projection connected=" + connected + " action=" + action);
        if (action == ConnectionPolicy.Action.START) {
            if (CarSurfaceDisplay.startIfAttached()) return;
            runRoot(
                    context,
                    "am start-foreground-service -n app.sabre.wzsabre/.WorkerService; "
                            + "am start-foreground-service -n com.highwayradar.app/.service.WorkerService");
        } else if (action == ConnectionPolicy.Action.STOP) {
            runRoot(context, "am force-stop com.highwayradar.app; am force-stop app.sabre.wzsabre");
            // Tells the Maps Xposed overlay the producer stopped so it clears stale state.
            Intent stopped =
                    new Intent(ACTION_STOPPED)
                            .setPackage("com.google.android.apps.maps")
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(stopped);
        }
    }

    private static void runRoot(Context context, String command) throws Exception {
        File output = File.createTempFile("connection-command-", ".log", context.getCacheDir());
        Process process = null;
        try {
            process =
                    new ProcessBuilder("su", "-c", command)
                            .redirectErrorStream(true)
                            .redirectOutput(output)
                            .start();
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Root connection command timed out");
            }
            String text = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || text.contains("Error:") || text.contains("Exception")) {
                throw new IllegalStateException("Root command failed: " + text);
            }
        } finally {
            if (process != null) process.destroy();
            if (!output.delete()) Log.w(TAG, "Could not remove command log");
        }
    }
}
