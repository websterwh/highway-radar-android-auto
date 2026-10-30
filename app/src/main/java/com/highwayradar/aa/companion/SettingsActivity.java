package com.highwayradar.aa.companion;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Clean phone-side configuration screen with instant toggles for Android Auto visibility and
 * Google Maps overlays.
 */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppSettings settings = AppSettings.get(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.rgb(18, 20, 24));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad * 2, pad, pad * 2);

        scrollView.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    android.graphics.Insets bars =
                            insets.getInsets(
                                    android.view.WindowInsets.Type.systemBars()
                                            | android.view.WindowInsets.Type.displayCutout());
                    root.setPadding(
                            pad + bars.left,
                            pad + bars.top,
                            pad + bars.right,
                            pad * 2 + bars.bottom);
                    return insets;
                });

        // Header Title
        TextView title = new TextView(this);
        title.setText("Highway Radar AA");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Android Auto & Google Maps Overlay Settings");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.rgb(150, 155, 165));
        subtitle.setPadding(0, dp(4), 0, dp(24));
        root.addView(subtitle);

        // Section 1: Android Auto App
        addSectionHeader(root, "Android Auto App");
        LinearLayout card1 = createCard();
        addToggle(
                card1,
                "Show Companion in Android Auto",
                "Display Highway Radar in Android Auto launcher grid and multi-app dock",
                settings.isShowInAndroidAuto(),
                (v, checked) -> settings.setShowInAndroidAuto(checked));
        root.addView(card1);

        addSectionHeader(root, "Passthrough & Connection");
        LinearLayout automation = createCard();
        addToggle(
                automation,
                "Auto-start on Android Auto connection",
                "Start on connection without opening the car app. Opening Highway Radar in Android"
                        + " Auto always starts it; Maps overlays wait until it runs.",
                settings.isAutoStart(),
                (v, checked) -> settings.setAutoStart(checked));
        addDivider(automation);
        addToggle(
                automation,
                "Auto-stop on Android Auto disconnection",
                "Stop Highway Radar on disconnect regardless of auto-start. Requires root.",
                settings.isAutoStop(),
                (v, checked) -> settings.setAutoStop(checked));
        addDivider(automation);
        addToggle(
                automation,
                "Reset to home screen on focus",
                "Return to Highway Radar home screen when switching back to the app in Android"
                    + " Auto.",
                settings.isResetOnFocus(),
                (v, checked) -> settings.setResetOnFocus(checked));
        root.addView(automation);

        // Section 2: Google Maps Overlay
        addSectionHeader(root, "Google Maps Overlay");
        LinearLayout card2 = createCard();
        addToggle(
                card2,
                "Enable Maps Overlay",
                "Master switch for drawing alerts and HUD over Google Maps",
                settings.isShowMapsOverlay(),
                (v, checked) -> settings.setShowMapsOverlay(checked));
        addDivider(card2);
        addToggle(
                card2,
                "Police (Orange)",
                "Show police presence as Google Maps-style orange incident pins",
                settings.isShowMapsPolice(),
                (v, checked) -> settings.setShowMapsPolice(checked));
        addDivider(card2);
        addToggle(
                card2,
                "Aircraft Patrols",
                "Show overhead patrol airplanes with directional heading",
                settings.isShowMapsAircraft(),
                (v, checked) -> settings.setShowMapsAircraft(checked));
        addDivider(card2);
        addToggle(
                card2,
                "Radar Detector HUD",
                "Show radar detector frequency and band signal strength panel",
                settings.isShowMapsHud(),
                (v, checked) -> settings.setShowMapsHud(checked));
        root.addView(card2);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private void addSectionHeader(LinearLayout parent, String title) {
        TextView header = new TextView(this);
        header.setText(title.toUpperCase());
        header.setTextSize(12);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Color.rgb(255, 150, 40));
        header.setPadding(dp(4), dp(18), 0, dp(8));
        parent.addView(header);
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(28, 31, 38));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        return card;
    }

    private void addToggle(
            LinearLayout parent,
            String label,
            String description,
            boolean checked,
            CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        textLayout.setLayoutParams(textParams);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(Color.WHITE);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        textLayout.addView(labelView);

        if (description != null) {
            TextView descView = new TextView(this);
            descView.setText(description);
            descView.setTextSize(12);
            descView.setTextColor(Color.rgb(150, 155, 165));
            descView.setPadding(0, dp(2), dp(8), 0);
            textLayout.addView(descView);
        }

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);

        row.addView(textLayout);
        row.addView(toggle);
        parent.addView(row);
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(42, 46, 56));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divider.setLayoutParams(params);
        parent.addView(divider);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
