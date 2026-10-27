package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.lyricsync.app.R;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AntigravityQuotaClient;
import com.lyricsync.app.ai.model.AntigravityModel;
import com.lyricsync.app.ai.model.QuotaSummary;
import com.lyricsync.app.ai.service.SongChatManager;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor;
import com.lyricsync.app.util.Haptics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI account card: Google (Antigravity) connect / manual token / logout, live quota,
 * and the model picker sheet. The overlay reads pref_ai_model / pref_ai_search_model.
 */
public final class AiAccountSection {

    private final Activity activity;
    private final SharedPreferences prefs;
    private final AntigravityAuthManager auth;
    private final AntigravityQuotaClient quota;

    private final View disconnected;
    private final View connected;
    private final TextView avatar;
    private final TextView email;
    private final LinearProgressIndicator weeklyBar;
    private final TextView weeklyValue;
    private final LinearProgressIndicator fiveHourBar;
    private final TextView fiveHourValue;
    private final TextView tokens;
    private final ImageView modelIcon;
    private final TextView modelName;
    private final TextView modelDesc;

    private List<AntigravityModel> serverModels = new ArrayList<>();
    private BottomSheetDialog modelSheet;
    private boolean released;

    public AiAccountSection(Activity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
        auth = AntigravityAuthManager.getInstance(activity);
        quota = new AntigravityQuotaClient(auth);

        disconnected = activity.findViewById(R.id.layout_ai_disconnected);
        connected = activity.findViewById(R.id.layout_ai_connected);
        avatar = activity.findViewById(R.id.tv_ai_avatar);
        email = activity.findViewById(R.id.tv_ai_email);
        weeklyBar = activity.findViewById(R.id.quota_weekly_progress);
        weeklyValue = activity.findViewById(R.id.tv_quota_weekly);
        fiveHourBar = activity.findViewById(R.id.quota_5h_progress);
        fiveHourValue = activity.findViewById(R.id.tv_quota_5h);
        tokens = activity.findViewById(R.id.tv_ai_tokens);
        modelIcon = activity.findViewById(R.id.ai_model_pill_icon);
        modelName = activity.findViewById(R.id.ai_model_pill_name);
        modelDesc = activity.findViewById(R.id.ai_model_desc_badge);

        activity.findViewById(R.id.btn_connect_google).setOnClickListener(v -> {
            Haptics.tick(v);
            Toast.makeText(activity, R.string.google_signin_opening, Toast.LENGTH_SHORT).show();
            auth.startBrowserLogin(activity, new AntigravityAuthManager.AuthCallback() {
                @Override
                public void onSuccess(String mail) {
                    ui(() -> {
                        Toast.makeText(activity, activity.getString(R.string.google_signin_success, mail),
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    });
                }

                @Override
                public void onError(String message) {
                    ui(() -> Toast.makeText(activity, activity.getString(R.string.google_signin_failed, message),
                            Toast.LENGTH_LONG).show());
                }
            });
        });
        activity.findViewById(R.id.btn_manual_token).setOnClickListener(v -> {
            Haptics.tick(v);
            showManualTokenDialog();
        });
        activity.findViewById(R.id.btn_disconnect_google).setOnClickListener(v -> {
            Haptics.tick(v);
            auth.logout();
            Toast.makeText(activity, R.string.google_signed_out, Toast.LENGTH_SHORT).show();
            refresh();
        });
        activity.findViewById(R.id.ai_model_row).setOnClickListener(v -> {
            Haptics.tick(v);
            showModelSheet();
        });

        updateModelRow(currentModel());
    }

    /** Run on the UI thread, but never after the activity is gone. */
    private void ui(Runnable r) {
        activity.runOnUiThread(() -> {
            if (!released && !activity.isFinishing() && !activity.isDestroyed()) r.run();
        });
    }

    private String currentModel() {
        return prefs.getString(MainPrefs.AI_MODEL, SongChatManager.DEFAULT_MODEL);
    }

    // ── Account state ───────────────────────────────────────────────────

    public void refresh() {
        boolean authed = auth.isAuthenticated();
        disconnected.setVisibility(authed ? View.GONE : View.VISIBLE);
        connected.setVisibility(authed ? View.VISIBLE : View.GONE);
        if (!authed) return;

        String mail = auth.getEmail();
        boolean hasMail = mail != null && !mail.isEmpty();
        email.setText(hasMail ? mail : activity.getString(R.string.main_ai_connected_fallback));
        avatar.setText(MainPrefs.avatarLetter(hasMail ? mail : null));

        quota.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
            @Override
            public void onQuotaLoaded(QuotaSummary summary) {
                ui(() -> {
                    if (summary == null) return;
                    showQuota(weeklyBar, weeklyValue, summary.geminiWeeklyFraction);
                    showQuota(fiveHourBar, fiveHourValue, summary.gemini5hFraction);
                });
            }

            @Override
            public void onError(String error) {
                // Keep the last shown values.
            }
        });
        quota.fetchModels(new AntigravityQuotaClient.ModelDiscoveryCallback() {
            @Override
            public void onModelsLoaded(List<AntigravityModel> models) {
                ui(() -> {
                    if (models == null || models.isEmpty()) return;
                    serverModels = models;
                    updateModelRow(currentModel());
                });
            }

            @Override
            public void onError(String error) {
                // Keeps the curated base list.
            }
        });

        int used = prefs.getInt(MainPrefs.AI_TOTAL_TOKENS, 0);
        if (used > 0) {
            tokens.setText(activity.getString(R.string.ai_total_tokens, used));
            tokens.setVisibility(View.VISIBLE);
        } else {
            tokens.setVisibility(View.GONE);
        }
    }

    private void showQuota(LinearProgressIndicator bar, TextView value, float fraction) {
        int pct = MainPrefs.clamp(Math.round(fraction * 100f), 0, 100);
        bar.setProgressCompat(pct, true);
        value.setText(activity.getString(R.string.main_percent, pct));
    }

    private void showManualTokenDialog() {
        MainUi.textInputDialog(activity, R.string.ai_manual_token_title, 0, R.string.ai_manual_token_hint,
                null, true, R.string.save, token -> {
                    if (token.isEmpty()) return;
                    auth.manualTokenLogin(token, new AntigravityAuthManager.AuthCallback() {
                        @Override
                        public void onSuccess(String mail) {
                            ui(() -> {
                                Toast.makeText(activity, activity.getString(R.string.google_connected, mail),
                                        Toast.LENGTH_SHORT).show();
                                refresh();
                            });
                        }

                        @Override
                        public void onError(String message) {
                            ui(() -> Toast.makeText(activity,
                                    activity.getString(R.string.google_connect_failed, message),
                                    Toast.LENGTH_LONG).show());
                        }
                    });
                });
    }

    // ── Models ──────────────────────────────────────────────────────────

    private ModelCatalog.Entry resolve(String id) {
        return ModelCatalog.resolve(id, modelId -> {
            for (AntigravityModel m : serverModels) {
                if (m.id != null && m.id.equalsIgnoreCase(modelId)) return m.displayName;
            }
            return null;
        });
    }

    static int iconRes(ModelCatalog.Icon icon) {
        switch (icon) {
            case CLAUDE: return R.drawable.ic_model_claude;
            case SPARKLE: return R.drawable.ic_ai_sparkle;
            default: return R.drawable.ic_model_gemini;
        }
    }

    private void updateModelRow(String id) {
        ModelCatalog.Entry e = resolve(id);
        modelIcon.setImageResource(iconRes(e.icon));
        modelName.setText(e.displayName);
        modelDesc.setText(e.description);
    }

    private void chooseModel(String id) {
        prefs.edit().putString(MainPrefs.AI_MODEL, id).apply();
        updateModelRow(id);
    }

    private void showModelSheet() {
        if (modelSheet != null && modelSheet.isShowing()) return;
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_model_selector,
                new FrameLayout(activity), false);
        LinearLayout list = content.findViewById(R.id.model_options_container);
        String current = currentModel();

        List<ModelCatalog.Entry> options = new ArrayList<>();
        Set<String> added = new HashSet<>();
        // 1. Curated base models, 2. models discovered from the API, 3. a custom current id.
        for (String id : ModelCatalog.BASE_MODELS) {
            options.add(resolve(id));
            added.add(id.toLowerCase(Locale.ROOT));
        }
        for (AntigravityModel m : serverModels) {
            if (m.id != null && added.add(m.id.toLowerCase(Locale.ROOT))) options.add(resolve(m.id));
        }
        if (!added.contains(current.toLowerCase(Locale.ROOT))) options.add(0, resolve(current));

        for (ModelCatalog.Entry opt : options) {
            View item = LayoutInflater.from(activity).inflate(R.layout.item_model_option, list, false);
            ((ImageView) item.findViewById(R.id.model_option_icon)).setImageResource(iconRes(opt.icon));
            ((TextView) item.findViewById(R.id.model_option_title)).setText(opt.displayName);
            ((TextView) item.findViewById(R.id.model_option_desc)).setText(opt.description);
            boolean selected = opt.id.equalsIgnoreCase(current);
            item.findViewById(R.id.model_option_check).setVisibility(selected ? View.VISIBLE : View.GONE);
            item.setBackgroundResource(selected ? R.drawable.ls_bg_option_selected : R.drawable.ls_bg_option);
            item.setSelected(selected);
            item.setOnClickListener(v -> {
                Haptics.tick(v);
                chooseModel(opt.id);
                sheet.dismiss();
            });
            list.addView(item);
        }

        content.findViewById(R.id.btn_add_custom_model).setOnClickListener(v -> {
            Haptics.tick(v);
            sheet.dismiss();
            showCustomModelDialog();
        });

        TextView searchSub = content.findViewById(R.id.tv_dialog_search_model_sub);
        showSearchModel(searchSub);
        content.findViewById(R.id.btn_select_search_model).setOnClickListener(v -> {
            Haptics.tick(v);
            showSearchModelDialog(searchSub);
        });

        sheet.setContentView(content);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.setOnDismissListener(d -> modelSheet = null);
        modelSheet = sheet;
        sheet.show();
    }

    private void showSearchModel(TextView sub) {
        String search = prefs.getString(MainPrefs.AI_SEARCH_MODEL, AntigravityToolExecutor.UNIFIED_SEARCH_MODEL);
        if (AntigravityToolExecutor.UNIFIED_SEARCH_MODEL.equalsIgnoreCase(search)) {
            sub.setText(activity.getString(R.string.ai_search_model_unified_sub, currentModel()));
        } else {
            sub.setText(search);
        }
    }

    private void showSearchModelDialog(TextView sub) {
        String activeChat = currentModel();
        final String[] ids = {
                AntigravityToolExecutor.UNIFIED_SEARCH_MODEL,
                "gemini-3.8-flash-high",
                "gemini-3.8-flash-low",
                "gemini-3.7-flash-high",
                "gemini-3.1-flash-lite"
        };
        final String[] labels = {
                activity.getString(R.string.ai_search_model_unified) + " (" + activeChat + ")",
                "Gemini 3.8 Flash High (Top Grounding)",
                "Gemini 3.8 Flash Low (Low Latency Grounding)",
                "Gemini 3.7 Flash High (Fast Grounding)",
                "Gemini 3.1 Flash Lite (Ultra Fast Grounding)"
        };
        String current = prefs.getString(MainPrefs.AI_SEARCH_MODEL, AntigravityToolExecutor.UNIFIED_SEARCH_MODEL);
        int checked = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equalsIgnoreCase(current)) {
                checked = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.ai_search_model_label)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (which < 0 || which >= ids.length) return;
                    String chosen = ids[which];
                    prefs.edit().putString(MainPrefs.AI_SEARCH_MODEL, chosen).apply();
                    AntigravityToolExecutor.setSearchModel(chosen);
                    showSearchModel(sub);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showCustomModelDialog() {
        MainUi.textInputDialog(activity, R.string.ai_custom_model_title, 0, R.string.ai_custom_model_hint,
                null, false, R.string.save, id -> {
                    if (id.isEmpty()) return;
                    chooseModel(id);
                    Toast.makeText(activity, activity.getString(R.string.ai_model_set, id), Toast.LENGTH_SHORT).show();
                });
    }

    public void release() {
        released = true;
        if (modelSheet != null) {
            modelSheet.dismiss();
            modelSheet = null;
        }
    }
}
