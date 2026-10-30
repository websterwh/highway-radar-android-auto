package com.highwayradar.aa.companion;

import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** Entry point the car host binds to. Declared with the POI category in the manifest. */
public final class HighwayRadarCarAppService extends CarAppService {

    @Override
    public void onCreate() {
        super.onCreate();
        sendBroadcast(
                new android.content.Intent(CarConnectionReceiver.ACTION_REFRESH)
                        .setClass(this, CarConnectionReceiver.class));
    }

    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @Override
    public Session onCreateSession() {
        return new HighwayRadarSession();
    }
}
