package com.lyricsync.app.ui.main;

import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.lyricsync.app.R;
import com.lyricsync.app.util.Haptics;

/** Small view helpers shared by the main-screen sections. */
final class MainUi {
    private MainUi() {}

    interface IntValueListener {
        /** @param fromUser true only for drags/taps; programmatic setValue passes false. */
        void onValue(int value, boolean fromUser);
    }

    interface IntFormatter {
        String format(int value);
    }

    interface TextResult {
        void onText(String text);
    }

    /**
     * Seed a Material Slider (clamped + snapped, Slider throws on off-range values),
     * then listen. The listener is attached after seeding, so it never fires for the
     * initial value. {@code a11y} formats the value TalkBack reads out.
     */
    static void bindSlider(Slider slider, int initial, IntFormatter a11y, IntValueListener listener) {
        slider.setValue(MainPrefs.sliderValue(initial, slider.getValueFrom(), slider.getValueTo()));
        slider.setLabelFormatter(v -> a11y.format(Math.round(v)));
        slider.addOnChangeListener((s, value, fromUser) -> {
            listener.onValue(Math.round(value), fromUser);
            if (fromUser) Haptics.tick(s);
        });
    }

    /** Programmatic, range-safe slider move (fires the change listener with fromUser=false). */
    static void setSlider(Slider slider, int value) {
        slider.setValue(MainPrefs.sliderValue(value, slider.getValueFrom(), slider.getValueTo()));
    }

    /**
     * Material dialog with a single outlined text field.
     *
     * @param password mask the text and add a visibility toggle (tokens)
     */
    static void textInputDialog(Context context, int title, int message, int hint, String initial,
                                boolean password, int positive, TextResult onPositive) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_text_input,
                new FrameLayout(context), false);
        TextInputLayout layout = view.findViewById(R.id.dialog_input_layout);
        TextInputEditText input = view.findViewById(R.id.dialog_input);
        layout.setHint(hint);
        if (password) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        }
        if (initial != null && !initial.isEmpty()) {
            input.setText(initial);
            input.setSelection(initial.length());
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(positive, (d, w) -> {
                    CharSequence text = input.getText();
                    onPositive.onText(text == null ? "" : text.toString().trim());
                })
                .setNegativeButton(R.string.cancel, null);
        if (message != 0) builder.setMessage(message);
        builder.show();
    }
}
