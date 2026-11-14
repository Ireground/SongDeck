package com.songdeck.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings dialog: recording latency compensation. */
public final class Settings {

    public static void show(Activity a, Store store, Runnable onChange) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(4));

        TextView title = Ui.label(a, "RECORDING DELAY FIX", 11, Ui.DIM, true);
        box.addView(title);
        TextView help = Ui.label(a,
                "If your recorded vocals land a little late (behind the metronome), raise this. "
                        + "If they land early, lower it. Headphones help a lot. Bluetooth headphones add a big delay, "
                        + "so wired ones are best for recording.", 13, Ui.TEXT, false);
        help.setPadding(0, Ui.dp(6), 0, Ui.dp(10));
        box.addView(help);

        TextView value = Ui.label(a, "", 16, Ui.TEXT, true);
        box.addView(value);
        Ui.Slider s = new Ui.Slider(a, 0, 400, store.latencyMs(), 100);
        value.setText(store.latencyMs() + " ms");
        s.setListener((v, done) -> {
            int ms = Math.round(v / 5f) * 5;
            value.setText(ms + " ms");
            if (done) {
                store.setLatencyMs(ms);
                if (onChange != null) onChange.run();
            }
        });
        box.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(44)));
        TextView tip = Ui.label(a, "Default: 100 ms. Double-tap the slider to reset.", 12, Ui.DIM, false);
        box.addView(tip);

        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Done", null)
                .show();
    }
}
