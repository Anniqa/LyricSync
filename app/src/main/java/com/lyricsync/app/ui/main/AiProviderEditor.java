package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.lyricsync.app.R;
import com.lyricsync.app.ai.client.AiProfiles;
import com.lyricsync.app.ai.client.AiProvider;
import com.lyricsync.app.ai.client.OpenAiModels;
import com.lyricsync.app.util.Haptics;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;

/**
 * Bottom sheet to add or edit one OpenAI-compatible provider. Nothing is stored until
 * Save. Model ids are scanned from {@code /models} shortly after the URL or key changes.
 */
final class AiProviderEditor {

    private static final long SCAN_DELAY_MS = 700;

    private final Activity activity;
    private final SharedPreferences prefs;
    private final AiProfiles.Profile existing; // null = adding
    private final Runnable onChanged;
    private final BottomSheetDialog sheet;

    private final TextInputLayout nameLayout, urlLayout, modelLayout;
    private final TextInputEditText name, url, key;
    private final MaterialAutoCompleteTextView model;
    private final TextView scanStatus;
    private final CircularProgressIndicator scanProgress;
    private final View getKey;
    private final com.google.android.material.button.MaterialButtonToggleGroup effort;
    private static final int[] EFFORT_IDS = {R.id.effort_auto, R.id.effort_low, R.id.effort_medium, R.id.effort_high};
    private final List<Chip> chips = new ArrayList<>();
    private final Runnable scanSoon = this::scan;
    private Call scanCall;
    private int scanGen;
    private String lastScanned = "";
    private boolean closed;

    static void show(Activity activity, SharedPreferences prefs, AiProfiles.Profile existing, Runnable onChanged) {
        new AiProviderEditor(activity, prefs, existing, onChanged);
    }

    private AiProviderEditor(Activity activity, SharedPreferences prefs, AiProfiles.Profile existing, Runnable onChanged) {
        this.activity = activity;
        this.prefs = prefs;
        this.existing = existing;
        this.onChanged = onChanged;

        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_ai_provider, new FrameLayout(activity), false);
        nameLayout = v.findViewById(R.id.til_name);
        urlLayout = v.findViewById(R.id.til_url);
        modelLayout = v.findViewById(R.id.til_model);
        name = v.findViewById(R.id.et_name);
        url = v.findViewById(R.id.et_url);
        key = v.findViewById(R.id.et_key);
        model = v.findViewById(R.id.et_model);
        scanStatus = v.findViewById(R.id.scan_status);
        scanProgress = v.findViewById(R.id.scan_progress);
        getKey = v.findViewById(R.id.btn_get_key);
        effort = v.findViewById(R.id.effort_group);
        int e = existing == null ? 0 : java.util.Arrays.asList(AiProfiles.EFFORTS).indexOf(existing.effort);
        effort.check(EFFORT_IDS[Math.max(e, 0)]);
        ((TextView) v.findViewById(R.id.editor_title)).setText(existing == null ? R.string.provider_add : R.string.provider_edit);

        if (existing != null) {
            name.setText(existing.name);
            url.setText(existing.baseUrl);
            key.setText(existing.apiKey);
            model.setText(existing.model, false);
            v.findViewById(R.id.editor_presets_group).setVisibility(View.GONE);
            View delete = v.findViewById(R.id.btn_delete);
            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(b -> confirmDelete());
        } else {
            addPresets(v.findViewById(R.id.editor_presets));
        }

        url.addTextChangedListener(watcher(() -> {
            urlLayout.setError(null);
            syncPresets();
            scanSoonOrHint();
        }));
        key.addTextChangedListener(watcher(this::scanSoonOrHint));
        model.addTextChangedListener(watcher(() -> modelLayout.setError(null)));
        model.setOnClickListener(x -> {
            if (model.getAdapter() != null && model.getAdapter().getCount() > 0) model.showDropDown();
        });
        v.findViewById(R.id.btn_scan).setOnClickListener(b -> {
            Haptics.tick(b);
            lastScanned = "";
            scan();
        });
        getKey.setOnClickListener(b -> openKeyPage());
        v.findViewById(R.id.btn_cancel).setOnClickListener(b -> sheet().dismiss());
        v.findViewById(R.id.btn_save).setOnClickListener(b -> save(b));

        sheet = new BottomSheetDialog(activity);
        sheet.setContentView(v);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        if (sheet.getWindow() != null) {
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        sheet.setOnDismissListener(d -> release());
        syncPresets();
        sheet.show();
        if (existing != null && !text(url).isEmpty()) scan();
    }

    private BottomSheetDialog sheet() {
        return sheet;
    }

    // ── Presets ──────────────────────────────────────────────────────────

    private void addPresets(LinearLayout row) {
        for (AiProfiles.Preset p : AiProfiles.PRESETS) {
            Chip chip = (Chip) LayoutInflater.from(activity).inflate(R.layout.item_provider_chip, row, false);
            chip.setText(p.name);
            com.lyricsync.app.ai.client.ProviderBrand.Brand brand = com.lyricsync.app.ai.client.ProviderBrand.forUrl(p.baseUrl);
            chip.setChipIconResource(AiProviderSection.logoRes(brand.logo));
            chip.setChipIconTint(android.content.res.ColorStateList.valueOf(brand.tint));
            chip.setChipIconVisible(true);
            chip.setChipIconSize(16 * activity.getResources().getDisplayMetrics().density);
            chip.setOnClickListener(c -> {
                Haptics.tick(c);
                String current = text(name);
                if (current.isEmpty() || isPresetName(current)) name.setText(p.name);
                url.setText(p.baseUrl);
                url.setSelection(p.baseUrl.length());
                key.requestFocus();
            });
            chips.add(chip);
            row.addView(chip);
        }
    }

    private static boolean isPresetName(String s) {
        for (AiProfiles.Preset p : AiProfiles.PRESETS) if (p.name.equals(s)) return true;
        return false;
    }

    /** Highlights the chip matching the URL and shows "get a key" when the service has a key page. */
    private void syncPresets() {
        AiProfiles.Preset current = AiProfiles.presetFor(text(url));
        for (int i = 0; i < chips.size(); i++) chips.get(i).setChecked(AiProfiles.PRESETS[i] == current);
        getKey.setVisibility(current != null && current.keyUrl != null ? View.VISIBLE : View.GONE);
    }

    private void openKeyPage() {
        AiProfiles.Preset p = AiProfiles.presetFor(text(url));
        if (p == null || p.keyUrl == null) return;
        Haptics.tick(getKey);
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, activity.getString(R.string.ai_link_error, p.keyUrl), Toast.LENGTH_LONG).show();
        }
    }

    // ── Model scan ───────────────────────────────────────────────────────

    private void scanSoonOrHint() {
        url.removeCallbacks(scanSoon);
        if (AiProvider.normalizeBaseUrl(text(url)).isEmpty()) {
            scanStatus.setText(R.string.provider_scan_idle);
            return;
        }
        url.postDelayed(scanSoon, SCAN_DELAY_MS);
    }

    /** GET {base}/models and offer the ids; a newer scan drops older answers. */
    private void scan() {
        if (closed) return;
        String base = AiProvider.normalizeBaseUrl(text(url));
        if (base.isEmpty()) {
            scanStatus.setText(R.string.provider_scan_idle);
            return;
        }
        String signature = base + "\n" + text(key);
        if (signature.equals(lastScanned)) return;
        lastScanned = signature;
        if (scanCall != null) scanCall.cancel();
        int gen = ++scanGen;
        scanProgress.setVisibility(View.VISIBLE);
        scanStatus.setText(R.string.ai_custom_scanning);
        scanCall = OpenAiModels.fetch(base, text(key), new OpenAiModels.Result() {
            @Override
            public void onModels(List<String> ids) {
                ui(gen, () -> {
                    scanProgress.setVisibility(View.GONE);
                    model.setAdapter(new ArrayAdapter<>(activity, R.layout.item_dropdown_option, new ArrayList<>(ids)));
                    if (text(model).isEmpty()) model.setText(ids.get(0), false);
                    scanStatus.setText(activity.getResources().getQuantityString(
                            R.plurals.ai_custom_models_found, ids.size(), ids.size()));
                });
            }

            @Override
            public void onError(String message) {
                ui(gen, () -> {
                    lastScanned = ""; // let the button retry
                    scanProgress.setVisibility(View.GONE);
                    scanStatus.setText(activity.getString(R.string.ai_custom_scan_failed, message));
                });
            }
        });
    }

    // ── Save / delete ────────────────────────────────────────────────────

    private void save(View button) {
        String chosenEffort = "";
        for (int i = 0; i < EFFORT_IDS.length; i++) if (EFFORT_IDS[i] == effort.getCheckedButtonId()) chosenEffort = AiProfiles.EFFORTS[i];
        AiProfiles.Profile p = new AiProfiles.Profile(existing != null ? existing.id : "", text(name),
                text(url), text(key), text(model), chosenEffort);
        AiProfiles.Problem problem = p.problem();
        if (problem == AiProfiles.Problem.URL) {
            urlLayout.setError(activity.getString(R.string.provider_err_url));
            url.requestFocus();
            return;
        }
        if (problem == AiProfiles.Problem.MODEL) {
            modelLayout.setError(activity.getString(R.string.provider_err_model));
            model.requestFocus();
            return;
        }
        Haptics.tick(button);
        AiProfiles.save(prefs, p);
        if (existing == null) AiProfiles.activate(prefs, p.id);
        Toast.makeText(activity, activity.getString(existing == null ? R.string.provider_saved_active : R.string.provider_saved,
                p.name), Toast.LENGTH_SHORT).show();
        onChanged.run();
        sheet.dismiss();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.provider_delete_title, existing.name))
                .setMessage(R.string.provider_delete_msg)
                .setPositiveButton(R.string.provider_delete, (d, w) -> {
                    AiProfiles.delete(prefs, existing.id);
                    onChanged.run();
                    sheet.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private void release() {
        closed = true;
        url.removeCallbacks(scanSoon);
        if (scanCall != null) scanCall.cancel();
    }

    private void ui(int gen, Runnable r) {
        activity.runOnUiThread(() -> {
            if (!closed && gen == scanGen && !activity.isFinishing() && !activity.isDestroyed()) r.run();
        });
    }

    private static TextWatcher watcher(Runnable after) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable e) {
                after.run();
            }
        };
    }

    private static String text(TextView v) {
        CharSequence t = v.getText();
        return t == null ? "" : t.toString().trim();
    }
}
