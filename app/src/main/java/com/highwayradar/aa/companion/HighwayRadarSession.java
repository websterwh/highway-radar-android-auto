package com.highwayradar.aa.companion;

import android.content.Intent;
import android.util.Log;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

/** Owns the single car app session: the overlay screen that mirrors the real app. */
public final class HighwayRadarSession extends Session {

    private static final String TAG = "HighwayRadarAA";

    private OverlayScreen mOverlayScreen;

    public HighwayRadarSession() {
        getLifecycle().addObserver(new LifecycleEventObserver() {
            @Override
            public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
                Log.i(TAG, "Session lifecycle=" + event);
            }
        });
    }

    @Override
    public Screen onCreateScreen(Intent intent) {
        CarContext carContext = getCarContext();
        mOverlayScreen = new OverlayScreen(carContext);
        return mOverlayScreen;
    }
}
