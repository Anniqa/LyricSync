package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.content.res.ColorStateList;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.lyricsync.app.R;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AiProfiles;
import com.lyricsync.app.ai.client.AiProvider;
import com.lyricsync.app.ai.client.ProviderBrand;
import com.lyricsync.app.util.Haptics;

import java.util.List;

/**
 * "AI provider" card: Google, ChatGPT (Codex sign-in) and every saved OpenAI-compatible provider as a row; tap a
 * row to use it, the pencil to edit it, "Add provider" for a new one (see
 * {@link AiProviderEditor}). Also the web search card (Gemini or an Exa key).
 */
public final class AiProviderSection {

    private static final String EXA_SIGNUP_URL = "https://dashboard.exa.ai/api-keys";

    private final Activity activity;
    private final SharedPreferences prefs;
    private final LinearLayout list;
    private final TextView webStatus;
    private final TextView exaStatus;
    private final TextView exaButton;
    private final MaterialSwitch exaFirst;

    public AiProviderSection(Activity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
        AiProfiles.migrate(prefs);
        list = activity.findViewById(R.id.provider_list);
        webStatus = activity.findViewById(R.id.tv_web_search_status);
        exaStatus = activity.findViewById(R.id.tv_exa_key_status);
        exaButton = activity.findViewById(R.id.btn_exa_key);
        exaFirst = activity.findViewById(R.id.switch_exa_first);

        activity.findViewById(R.id.btn_add_provider).setOnClickListener(v -> {
            Haptics.tick(v);
            AiProviderEditor.show(activity, prefs, null, this::updateStatus);
        });

        exaButton.setOnClickListener(v -> {
            Haptics.tick(v);
            showExaKeyDialog();
        });
        exaFirst.setChecked(prefs.getBoolean(AiProvider.PREF_EXA_FIRST, false));
        exaFirst.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(AiProvider.PREF_EXA_FIRST, on).apply();
            updateStatus();
        });
        activity.findViewById(R.id.row_exa_first).setOnClickListener(v -> {
            if (exaFirst.isEnabled()) exaFirst.toggle();
        });
        activity.findViewById(R.id.btn_exa_get_key).setOnClickListener(v -> {
            Haptics.tick(v);
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(EXA_SIGNUP_URL)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(activity, activity.getString(R.string.ai_link_error, EXA_SIGNUP_URL), Toast.LENGTH_LONG).show();
            }
        });
        updateStatus();
    }

    /** Redraws the rows and the web search state; call on resume too, since signing in changes both. */
    public void updateStatus() {
        renderProviders();
        AiProvider.Settings s = AiProvider.read(prefs);
        boolean google = AntigravityAuthManager.getInstance(activity).isAuthenticated();
        String engine = AiProvider.searchEngine(s, google);
        webStatus.setText(AiProvider.ENGINE_EXA.equals(engine) ? R.string.web_search_using_exa
                : AiProvider.ENGINE_GEMINI.equals(engine) ? R.string.web_search_using_gemini
                : R.string.web_search_none);
        boolean hasKey = !s.exaKey.isEmpty();
        exaStatus.setText(hasKey ? R.string.exa_key_status_set : R.string.exa_key_status_none);
        exaButton.setText(hasKey ? R.string.exa_key_change : R.string.exa_key_add);
        exaFirst.setEnabled(hasKey);
        exaFirst.setChecked(hasKey && s.exaFirst);
    }

    private void renderProviders() {
        list.removeAllViews();
        String active = AiProfiles.activeId(prefs);
        addGoogleRow(active.isEmpty());
        addCodexRow(AiProvider.ACTIVE_CODEX.equals(active));
        List<AiProfiles.Profile> all = AiProfiles.list(prefs);
        for (AiProfiles.Profile p : all) addProfileRow(p, p.id.equals(active));
    }

    private void addGoogleRow(boolean selected) {
        AntigravityAuthManager auth = AntigravityAuthManager.getInstance(activity);
        String mail = auth.isAuthenticated() ? auth.getEmail() : null;
        String summary = !auth.isAuthenticated() ? activity.getString(R.string.provider_google_off)
                : mail != null && !mail.isEmpty() ? mail : activity.getString(R.string.main_ai_connected_fallback);
        View row = row(ProviderBrand.GOOGLE, activity.getString(R.string.provider_google), summary, selected);
        row.findViewById(R.id.provider_edit).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            if (selected) return;
            Haptics.tick(v);
            AiProfiles.activate(prefs, null);
            updateStatus();
        });
    }

    /** ChatGPT sign-in: tap opens the sheet until signed in and set up, then switches like any row. */
    private void addCodexRow(boolean selected) {
        com.lyricsync.app.ai.auth.CodexAuth codex = com.lyricsync.app.ai.auth.CodexAuth.get(activity);
        String model = prefs.getString(AiProvider.PREF_CODEX_MODEL, "");
        boolean ready = codex.isSignedIn() && !model.isEmpty();
        String summary = !codex.isSignedIn() ? activity.getString(R.string.codex_row_signed_out)
                : model.isEmpty() ? activity.getString(R.string.codex_row_choose_model)
                : model + " · " + effortLabel(prefs.getString(AiProvider.PREF_CODEX_EFFORT, "medium"));
        View row = row(ProviderBrand.CHATGPT, activity.getString(R.string.codex_title), summary, selected);
        row.setOnClickListener(v -> {
            Haptics.tick(v);
            if (!ready) CodexSheet.show(activity, prefs, this::updateStatus);
            else if (!selected) {
                AiProfiles.activate(prefs, AiProvider.ACTIVE_CODEX);
                updateStatus();
            }
        });
        View edit = row.findViewById(R.id.provider_edit);
        edit.setContentDescription(activity.getString(R.string.provider_edit_cd, activity.getString(R.string.codex_title)));
        edit.setOnClickListener(v -> {
            Haptics.tick(v);
            CodexSheet.show(activity, prefs, this::updateStatus);
        });
    }

    private String effortLabel(String effort) {
        switch (effort == null ? "" : effort) {
            case "low": return activity.getString(R.string.effort_low);
            case "medium": return activity.getString(R.string.effort_medium);
            case "high": return activity.getString(R.string.effort_high);
            default: return activity.getString(R.string.effort_auto);
        }
    }

    private void addProfileRow(AiProfiles.Profile p, boolean selected) {
        String summary = AiProfiles.host(p.baseUrl) + " · " + AiProvider.shortModel(p.model)
                + (p.effort == null || p.effort.isEmpty() ? "" : " · " + effortLabel(p.effort));
        View row = row(ProviderBrand.forUrl(p.baseUrl), p.name, summary, selected);
        row.setOnClickListener(v -> {
            if (selected) return;
            Haptics.tick(v);
            AiProfiles.activate(prefs, p.id);
            updateStatus();
        });
        View edit = row.findViewById(R.id.provider_edit);
        edit.setContentDescription(activity.getString(R.string.provider_edit_cd, p.name));
        edit.setOnClickListener(v -> {
            Haptics.tick(v);
            AiProviderEditor.show(activity, prefs, p, this::updateStatus);
        });
    }

    private View row(ProviderBrand.Brand brand, String name, String summary, boolean selected) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_ai_provider, list, false);
        row.findViewById(R.id.provider_tile).setBackgroundTintList(ColorStateList.valueOf(brand.tile));
        ImageView logo = row.findViewById(R.id.provider_logo);
        logo.setImageResource(logoRes(brand.logo));
        logo.setImageTintList(ColorStateList.valueOf(brand.tint));
        ((TextView) row.findViewById(R.id.provider_name)).setText(name);
        ((TextView) row.findViewById(R.id.provider_summary)).setText(summary);
        row.findViewById(R.id.provider_check).setVisibility(selected ? View.VISIBLE : View.GONE);
        row.setBackgroundResource(selected ? R.drawable.ls_bg_option_selected : R.drawable.ls_bg_option);
        row.setSelected(selected);
        list.addView(row);
        return row;
    }

    static int logoRes(ProviderBrand.Logo logo) {
        switch (logo) {
            case GOOGLE: return R.drawable.ls_logo_google;
            case OPENAI: return R.drawable.ls_logo_openai;
            case DEEPSEEK: return R.drawable.ls_logo_deepseek;
            case RIVER: return R.drawable.ls_logo_river;
            case OPENROUTER: return R.drawable.ls_logo_openrouter;
            case OLLAMA: return R.drawable.ls_logo_ollama;
            default: return R.drawable.ls_logo_api;
        }
    }

    private void showExaKeyDialog() {
        String existing = prefs.getString(AiProvider.PREF_EXA_KEY, "");
        MainUi.textInputDialog(activity, R.string.exa_key_dialog_title, R.string.exa_key_dialog_msg,
                R.string.exa_key_hint, existing, true, R.string.save, value -> {
                    if (value.isEmpty()) prefs.edit().remove(AiProvider.PREF_EXA_KEY).apply();
                    else prefs.edit().putString(AiProvider.PREF_EXA_KEY, value).apply();
                    updateStatus();
                });
    }

    /** Kept for the activity's lifecycle; the editor releases its own scan. */
    public void release() {}
}
