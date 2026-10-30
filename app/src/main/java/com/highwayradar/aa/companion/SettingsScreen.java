package com.highwayradar.aa.companion;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;

/**
 * In-car settings screen: allows toggling the real-app passthrough and Google Maps overlay
 * directly from the Android Auto head unit display.
 */
public final class SettingsScreen extends Screen {

    private final AppSettings mSettings;

    SettingsScreen(CarContext carContext) {
        super(carContext);
        mSettings = AppSettings.get(carContext);
    }

    @Override
    public Template onGetTemplate() {
        Header header =
                new Header.Builder()
                        .setTitle("Display & Overlay Settings")
                        .setStartHeaderAction(Action.BACK)
                        .build();

        ItemList.Builder list = new ItemList.Builder();

        list.addItem(
                new Row.Builder()
                        .setTitle("Auto-start on Android Auto connection")
                        .addText(
                                "Start on connection without opening the car app. Opening Highway"
                                        + " Radar in Android Auto always starts it.")
                        .setToggle(
                                new Toggle.Builder(
                                                checked -> {
                                                    mSettings.setAutoStart(checked);
                                                    invalidate();
                                                })
                                        .setChecked(mSettings.isAutoStart())
                                        .build())
                        .build());
        list.addItem(
                new Row.Builder()
                        .setTitle("Auto-stop on Android Auto disconnection")
                        .addText(
                                "Stop Highway Radar on disconnect, even when auto-start is off."
                                        + " Requires root.")
                        .setToggle(
                                new Toggle.Builder(
                                                checked -> {
                                                    mSettings.setAutoStop(checked);
                                                    invalidate();
                                                })
                                        .setChecked(mSettings.isAutoStop())
                                        .build())
                        .build());
        list.addItem(
                new Row.Builder()
                        .setTitle("Reset to home screen on focus")
                        .addText(
                                "Return to Highway Radar home screen when switching back to the app"
                                    + " in Android Auto.")
                        .setToggle(
                                new Toggle.Builder(
                                                checked -> {
                                                    mSettings.setResetOnFocus(checked);
                                                    invalidate();
                                                })
                                        .setChecked(mSettings.isResetOnFocus())
                                        .build())
                        .build());

        list.addItem(
                new Row.Builder()
                        .setTitle("Google Maps Overlay")
                        .addText("Show alerts and radar HUD directly on Google Maps")
                        .setToggle(
                                new Toggle.Builder(
                                                checked -> {
                                                    mSettings.setShowMapsOverlay(checked);
                                                    invalidate();
                                                })
                                        .setChecked(mSettings.isShowMapsOverlay())
                                        .build())
                        .build());
        list.addItem(
                new Row.Builder()
                        .setTitle("Maps: Police (Orange)")
                        .addText("Show police presence as Google Maps-style orange pins")
                        .setToggle(
                                new Toggle.Builder(
                                                checked -> {
                                                    mSettings.setShowMapsPolice(checked);
                                                    invalidate();
                                                })
                                        .setChecked(mSettings.isShowMapsPolice())
                                        .build())
                        .build());
        list.addItem(
                new Row.Builder()
                        .setTitle("Maps: Aircraft")
                        .addText("Show overhead patrol aircraft on Google Maps")
                        .setToggle(
                                new Toggle.Builder(
                                                checked -> {
                                                    mSettings.setShowMapsAircraft(checked);
                                                    invalidate();
                                                })
                                        .setChecked(mSettings.isShowMapsAircraft())
                                        .build())
                        .build());
        list.addItem(
                new Row.Builder()
                        .setTitle("Maps: Radar Detector HUD")
                        .addText("Show Ka/K band frequencies & signal strength panel")
                        .setToggle(
                                new Toggle.Builder(
                                                checked -> {
                                                    mSettings.setShowMapsHud(checked);
                                                    invalidate();
                                                })
                                        .setChecked(mSettings.isShowMapsHud())
                                        .build())
                        .build());

        return new ListTemplate.Builder().setHeader(header).setSingleList(list.build()).build();
    }
}
