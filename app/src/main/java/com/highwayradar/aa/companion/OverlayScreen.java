package com.highwayradar.aa.companion;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Surface;

import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.Lifecycle;

/** Full-surface shell; the display session owns mirroring the real Highway Radar app. */
public final class OverlayScreen extends Screen {
    private static final String TAG = "HighwayRadarAA";
    private final CarSurfaceDisplay mDisplay;
    private final CarIcon mSettingsIcon;
    private SurfaceContainer mSurfaceContainer;

    private final SurfaceCallback mSurfaceCallback =
            new SurfaceCallback() {
                @Override
                public void onSurfaceAvailable(SurfaceContainer container) {
                    Log.i(
                            TAG,
                            "Car surface available: "
                                    + container.getWidth()
                                    + "x"
                                    + container.getHeight());
                    mDisplay.detach();
                    releaseCurrentSurface(container.getSurface());
                    mSurfaceContainer = container;
                    resumeSurface();
                }

                @Override
                public void onSurfaceDestroyed(SurfaceContainer container) {
                    Log.i(TAG, "Car surface destroyed");
                    mDisplay.detach();
                    releaseCurrentSurface(container.getSurface());
                    if (container.getSurface() != null) container.getSurface().release();
                    mSurfaceContainer = null;
                }

                @Override
                public void onClick(float x, float y) {
                    if (isResumed()) mDisplay.click(x, y);
                }

                @Override
                public void onScroll(float distanceX, float distanceY) {
                    if (isResumed()) mDisplay.scroll(distanceX, distanceY);
                }
            };

    OverlayScreen(CarContext carContext) {
        super(carContext);
        mDisplay = new CarSurfaceDisplay(carContext);
        Drawable gear = carContext.getDrawable(R.drawable.ic_settings_gear);
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas iconCanvas = new Canvas(bitmap);
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(0xff232629);
        iconCanvas.drawCircle(48, 48, 46, background);
        background.setStyle(Paint.Style.STROKE);
        background.setStrokeWidth(2);
        background.setColor(0xff46494d);
        iconCanvas.drawCircle(48, 48, 46, background);
        gear.setBounds(16, 16, 80, 80);
        gear.draw(iconCanvas);
        mSettingsIcon = new CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build();
        getLifecycle()
                .addObserver(
                        (androidx.lifecycle.LifecycleEventObserver)
                                (source, event) -> {
                                    Log.i(TAG, "Overlay lifecycle=" + event);
                                    if (event == Lifecycle.Event.ON_RESUME) {
                                        if (mSurfaceContainer == null
                                                || mSurfaceContainer.getSurface() == null
                                                || !mSurfaceContainer.getSurface().isValid()) {
                                            getCarContext()
                                                    .getCarService(AppManager.class)
                                                    .setSurfaceCallback(mSurfaceCallback);
                                        }
                                        resumeSurface();
                                        mDisplay.onHostResumed();
                                    } else if (event == Lifecycle.Event.ON_PAUSE) {
                                        mDisplay.detach();
                                    } else if (event == Lifecycle.Event.ON_DESTROY) {
                                        mDisplay.close();
                                        releaseCurrentSurface(null);
                                        mSurfaceContainer = null;
                                        getCarContext()
                                                .getCarService(AppManager.class)
                                                .setSurfaceCallback(null);
                                    }
                                });
    }

    @Override
    public Template onGetTemplate() {
        Action settings =
                new Action.Builder()
                        .setIcon(mSettingsIcon)
                        .setBackgroundColor(CarColor.createCustom(0x00000000, 0x00000000))
                        .setFlags(Action.FLAG_IS_PERSISTENT | Action.FLAG_PRIMARY)
                        .setOnClickListener(
                                () -> getScreenManager().push(new SettingsScreen(getCarContext())))
                        .build();
        Action pan = new Action.Builder(Action.PAN).build();
        ActionStrip mapActions = new ActionStrip.Builder().addAction(pan).build();

        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder().addAction(settings).build())
                .setMapActionStrip(mapActions)
                .setPanModeListener(isInPanMode -> Log.i(TAG, "Pan mode: " + isInPanMode))
                .build();
    }

    private boolean isResumed() {
        return getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
    }

    private void resumeSurface() {
        if (!isResumed() || mSurfaceContainer == null) return;
        mDisplay.attach(mSurfaceContainer);
    }

    private void releaseCurrentSurface(Surface except) {
        if (mSurfaceContainer == null) return;
        Surface previous = mSurfaceContainer.getSurface();
        if (previous != null && previous != except) previous.release();
    }
}
