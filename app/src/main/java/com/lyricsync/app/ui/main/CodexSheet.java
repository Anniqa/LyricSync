package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.lyricsync.app.R;
import com.lyricsync.app.ai.auth.CodexAuth;
import com.lyricsync.app.ai.client.AiProfiles;
import com.lyricsync.app.ai.client.AiProvider;
import com.lyricsync.app.util.Haptics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bottom sheet for the ChatGPT (Codex) provider: sign in through the browser (or paste the
 * callback address / a refresh token), then pick a model and reasoning effort. Save makes
 * ChatGPT the active provider.
 */
final class CodexSheet {

    private static final int[] EFFORT_IDS = {R.id.codex_effort_low, R.id.codex_effort_medium, R.id.codex_effort_high};
    private static final String[] EFFORTS = {"low", "medium", "high"};

    private final Activity activity;
    private final SharedPreferences prefs;
    private final CodexAuth auth;
    private final Runnable onChanged;
    private final BottomSheetDialog sheet;
    private final View signedOut, signedIn;
    private final TextView loginStatus, email, plan, modelsStatus;
    private final MaterialAutoCompleteTextView model;
    private final MaterialButtonToggleGroup effort;
    private boolean closed;

    static void show(Activity activity, SharedPreferences prefs, Runnable onChanged) {
        new CodexSheet(activity, prefs, onChanged);
    }

    private CodexSheet(Activity activity, SharedPreferences prefs, Runnable onChanged) {
        this.activity = activity;
        this.prefs = prefs;
        this.auth = CodexAuth.get(activity);
        this.onChanged = onChanged;
        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_codex, new FrameLayout(activity), false);
        signedOut = v.findViewById(R.id.codex_signed_out);
        signedIn = v.findViewById(R.id.codex_signed_in);
        loginStatus = v.findViewById(R.id.codex_login_status);
        email = v.findViewById(R.id.codex_email);
        plan = v.findViewById(R.id.codex_plan);
        modelsStatus = v.findViewById(R.id.codex_models_status);
        model = v.findViewById(R.id.et_codex_model);
        effort = v.findViewById(R.id.codex_effort_group);

        v.findViewById(R.id.btn_codex_login).setOnClickListener(b -> {
            Haptics.tick(b);
            loginStatus.setText(R.string.codex_login_waiting);
            auth.startLogin(activity, loginResult());
        });
        v.findViewById(R.id.btn_codex_manual).setOnClickListener(b -> {
            Haptics.tick(b);
            MainUi.textInputDialog(activity, R.string.codex_manual_title, R.string.codex_manual_msg,
                    R.string.codex_manual_hint, null, true, R.string.codex_manual_ok, text -> {
                        if (text.isEmpty()) return;
                        loginStatus.setText(R.string.codex_login_checking);
                        auth.loginManually(text, loginResult());
                    });
        });
        v.findViewById(R.id.btn_codex_logout).setOnClickListener(b -> {
            Haptics.tick(b);
            auth.logout();
            if (AiProvider.ACTIVE_CODEX.equals(AiProfiles.activeId(prefs))) AiProfiles.activate(prefs, null);
            onChanged.run();
            render();
        });
        v.findViewById(R.id.btn_codex_cancel).setOnClickListener(b -> sheet().dismiss());
        v.findViewById(R.id.btn_codex_save).setOnClickListener(this::save);

        String saved = prefs.getString(AiProvider.PREF_CODEX_EFFORT, "medium");
        int idx = java.util.Arrays.asList(EFFORTS).indexOf(saved);
        effort.check(EFFORT_IDS[idx < 0 ? 1 : idx]);
        model.setText(prefs.getString(AiProvider.PREF_CODEX_MODEL, ""), false);
        model.setOnClickListener(x -> {
            if (model.getAdapter() != null && model.getAdapter().getCount() > 0) model.showDropDown();
        });

        sheet = new BottomSheetDialog(activity);
        sheet.setContentView(v);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.setOnDismissListener(d -> closed = true);
        render();
        sheet.show();
    }

    private BottomSheetDialog sheet() {
        return sheet;
    }

    private CodexAuth.LoginCallback loginResult() {
        return new CodexAuth.LoginCallback() {
            @Override
            public void onSuccess(String mail) {
                ui(() -> {
                    Toast.makeText(activity, activity.getString(R.string.codex_signed_in,
                            mail.isEmpty() ? "ChatGPT" : mail), Toast.LENGTH_SHORT).show();
                    render();
                });
            }

            @Override
            public void onError(String message) {
                ui(() -> loginStatus.setText(activity.getString(R.string.codex_login_failed, message)));
            }
        };
    }

    private void render() {
        boolean in = auth.isSignedIn();
        signedOut.setVisibility(in ? View.GONE : View.VISIBLE);
        signedIn.setVisibility(in ? View.VISIBLE : View.GONE);
        if (!in) return;
        String mail = auth.email();
        email.setText(mail.isEmpty() ? activity.getString(R.string.codex_title) : mail);
        String p = auth.plan();
        plan.setText(p.isEmpty() ? activity.getString(R.string.codex_plan_unknown)
                : activity.getString(R.string.codex_plan, p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1)));
        loadModels();
    }

    private void loadModels() {
        modelsStatus.setText(R.string.ai_custom_scanning);
        auth.fetchModels(new CodexAuth.ModelsCallback() {
            @Override
            public void onModels(List<CodexAuth.Model> models) {
                ui(() -> {
                    List<String> ids = new ArrayList<>();
                    for (CodexAuth.Model m : models) ids.add(m.id);
                    model.setAdapter(new ArrayAdapter<>(activity, R.layout.item_dropdown_option, ids));
                    String current = text(model);
                    if (current.isEmpty() || !ids.contains(current)) model.setText(ids.get(0), false);
                    modelsStatus.setText(activity.getResources().getQuantityString(
                            R.plurals.ai_custom_models_found, ids.size(), ids.size()));
                });
            }

            @Override
            public void onError(String message) {
                ui(() -> modelsStatus.setText(activity.getString(R.string.ai_custom_scan_failed, message)));
            }
        });
    }

    private void save(View b) {
        String m = text(model);
        if (m.isEmpty()) {
            modelsStatus.setText(R.string.provider_err_model);
            return;
        }
        Haptics.tick(b);
        int checked = effort.getCheckedButtonId();
        String e = "medium";
        for (int i = 0; i < EFFORT_IDS.length; i++) if (EFFORT_IDS[i] == checked) e = EFFORTS[i];
        prefs.edit().putString(AiProvider.PREF_CODEX_MODEL, m).putString(AiProvider.PREF_CODEX_EFFORT, e).apply();
        AiProfiles.activate(prefs, AiProvider.ACTIVE_CODEX);
        Toast.makeText(activity, activity.getString(R.string.provider_saved_active, "ChatGPT"), Toast.LENGTH_SHORT).show();
        onChanged.run();
        sheet.dismiss();
    }

    private void ui(Runnable r) {
        activity.runOnUiThread(() -> {
            if (!closed && !activity.isFinishing() && !activity.isDestroyed()) r.run();
        });
    }

    private static String text(TextView v) {
        CharSequence t = v.getText();
        return t == null ? "" : t.toString().trim();
    }
}
