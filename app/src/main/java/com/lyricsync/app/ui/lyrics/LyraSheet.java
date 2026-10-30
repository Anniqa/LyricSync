package com.lyricsync.app.ui.lyrics;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.lyricsync.app.R;
import com.lyricsync.app.ai.client.AiClients;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.client.MainThreadLyricActionRouter;
import com.lyricsync.app.ai.service.SongChatManager;
import com.lyricsync.app.ai.util.CleanMarkdownFormatter;
import com.lyricsync.app.ai.util.FollowUps;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.overlay.FloatingOverlayService;
import com.lyricsync.app.ui.main.MainPrefs;
import com.lyricsync.app.util.Haptics;
import com.lyricsync.app.util.LocaleHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lyra inside the full-screen lyrics. The sheet covers the lower part only, so the line
 * being sung stays in view. Quick asks are built from what is on screen right now (the
 * current line, the script, translation state); Lyra's tools act on the same source the
 * screen shows, so "Latin only" or "jump to the chorus" happen in front of the user.
 * The conversation lasts while the screen is open.
 */
final class LyraSheet {

    /** One quick ask: chip label, the prompt sent, whether it needs the web. */
    static final class Ask {
        final String label;
        final String prompt;
        final boolean web;

        Ask(String label, String prompt, boolean web) {
            this.label = label;
            this.prompt = prompt;
            this.web = web;
        }
    }

    interface Context {
        FloatingOverlayService.LyricsSource source();

        /** Index of the line being sung, or -1. */
        int activeLine();
    }

    private final Activity activity;
    private final Context ctx;
    private final AntigravityApiClient ai;
    private final SongChatManager chat;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BottomSheetDialog sheet;
    private View root;
    private LinearLayout messages, quick;
    private NestedScrollView scroll;
    private EditText input;
    private ImageView face, send;
    private AntigravityApiClient.Cancellable call;
    private String songKey;

    LyraSheet(Activity activity, Context ctx) {
        this.activity = activity;
        this.ctx = ctx;
        this.ai = AiClients.forApp(activity);
        this.chat = new SongChatManager(ai);
        this.prefs = activity.getSharedPreferences(MainPrefs.FILE, android.content.Context.MODE_PRIVATE);
    }

    void show() {
        FloatingOverlayService.LyricsSource src = ctx.source();
        if (src == null) return;
        ai.setLyricActionDelegate(new MainThreadLyricActionRouter(main, src.actions()));
        if (sheet == null) build();
        syncSong(src);
        renderQuick(null);
        sheet.show();
    }

    void release() {
        if (call != null) call.cancel();
        call = null;
        if (sheet != null) sheet.dismiss();
    }

    private void build() {
        root = LayoutInflater.from(activity).inflate(R.layout.sheet_lyra, new FrameLayout(activity), false);
        messages = root.findViewById(R.id.lyra_messages);
        quick = root.findViewById(R.id.lyra_quick);
        scroll = root.findViewById(R.id.lyra_scroll);
        input = root.findViewById(R.id.lyra_input);
        face = root.findViewById(R.id.lyra_face);
        send = root.findViewById(R.id.lyra_send);
        send.setOnClickListener(v -> submit());
        input.setOnEditorActionListener((v, id, e) -> {
            if (id != EditorInfo.IME_ACTION_SEND) return false;
            submit();
            return true;
        });
        root.findViewById(R.id.lyra_new_chat).setOnClickListener(v -> {
            Haptics.tick(v);
            if (call != null) call.cancel();
            call = null;
            chat.clearHistory();
            messages.removeAllViews();
            root.findViewById(R.id.lyra_intro).setVisibility(View.VISIBLE);
            setBusy(false);
            renderQuick(null);
        });

        sheet = new BottomSheetDialog(activity);
        sheet.setContentView(root);
        if (sheet.getWindow() != null) {
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            sheet.getWindow().setDimAmount(0.25f);
        }
        View parent = (View) root.getParent();
        if (parent != null) parent.setBackgroundColor(0);
        // About 58% of the screen: the active lyric line (at 32% from the top) stays visible.
        int h = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.58f);
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (lp != null) lp.height = h;
        else root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        BottomSheetBehavior<?> b = sheet.getBehavior();
        b.setPeekHeight(h);
        b.setSkipCollapsed(true);
        b.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    /** New song: fresh conversation and the song's lyrics as context. */
    private void syncSong(FloatingOverlayService.LyricsSource src) {
        TrackInfo t = src.track();
        String key = t == null ? "" : t.title + "|" + t.artist;
        LyricsData l = src.lyrics();
        if (!key.equals(songKey)) {
            songKey = key;
            if (call != null) call.cancel();
            call = null;
            chat.clearHistory();
            messages.removeAllViews();
            root.findViewById(R.id.lyra_intro).setVisibility(View.VISIBLE);
            setBusy(false);
        }
        chat.setCurrentSong(t != null ? t.title : null, t != null ? t.artist : null, chatLyrics(l));
        chat.setAppLanguage(LocaleHelper.getLanguage(activity));
        ((TextView) root.findViewById(R.id.lyra_song)).setText(t == null ? ""
                : activity.getString(R.string.overlay_track_line, t.title, t.artist != null ? t.artist : ""));
        String title = t != null && t.title != null ? t.title.trim() : "";
        boolean has = l != null && !l.isEmpty();
        TextView greet = root.findViewById(R.id.lyra_greeting);
        if (title.isEmpty()) greet.setText(R.string.ai_greeting_idle);
        else if (!has) greet.setText(activity.getString(R.string.ai_greeting_no_lyrics, title));
        else if (StandaloneLyricsSource.nonLatin(l)) greet.setText(activity.getString(R.string.ai_greeting_non_latin, title));
        else greet.setText(activity.getString(R.string.lyra_full_greeting, title));
        face.setImageResource(has ? R.drawable.ls_mascot_proud : R.drawable.ls_mascot);
    }

    // ── quick asks ──────────────────────────────────────────────────────────────

    /** The sweet spots: what is worth asking about the screen right now. */
    List<Ask> asks() {
        List<Ask> out = new ArrayList<>();
        FloatingOverlayService.LyricsSource src = ctx.source();
        LyricsData l = src != null ? src.lyrics() : null;
        if (l == null || l.isEmpty()) {
            out.add(new Ask(s(R.string.lyra_ask_story), s(R.string.lyra_ask_story_prompt), true));
            out.add(new Ask(s(R.string.lyra_ask_similar), s(R.string.lyra_ask_similar_prompt), false));
            return out;
        }
        int i = ctx.activeLine();
        String line = i >= 0 && i < l.lines.size() && !l.lines.get(i).isInterlude ? l.lines.get(i).text : null;
        boolean nonLatin = StandaloneLyricsSource.nonLatin(l);
        if (line != null && !line.trim().isEmpty()) {
            out.add(new Ask(s(R.string.lyra_ask_line), activity.getString(R.string.lyra_ask_line_prompt, i, line.trim()), false));
        }
        if (nonLatin && !(src.romajiEnabled() && !src.originalScriptEnabled())) {
            out.add(new Ask(s(R.string.lyra_ask_latin_only), s(R.string.lyra_ask_latin_only_prompt), false));
        }
        if (nonLatin) {
            out.add(new Ask(s(R.string.lyra_ask_sing), s(R.string.lyra_ask_sing_prompt), false));
        }
        out.add(new Ask(s(R.string.lyra_ask_meaning), s(R.string.lyra_ask_meaning_prompt), false));
        out.add(new Ask(s(R.string.lyra_ask_chorus), s(R.string.lyra_ask_chorus_prompt), false));
        if (!src.translationEnabled()) {
            out.add(new Ask(s(R.string.lyra_ask_translate), s(R.string.lyra_ask_translate_prompt), false));
        }
        out.add(new Ask(s(R.string.lyra_ask_story), s(R.string.lyra_ask_story_prompt), true));
        return out;
    }

    /** Follow-ups after an answer replace the quick asks; null shows the quick asks. */
    private void renderQuick(List<String> followUps) {
        quick.removeAllViews();
        if (followUps != null && !followUps.isEmpty()) {
            for (String f : followUps) addChip(f, f, false, true);
            return;
        }
        for (Ask a : asks()) addChip(a.label, a.prompt, a.web, false);
    }

    private void addChip(String label, String prompt, boolean web, boolean followUp) {
        TextView chip = new TextView(activity);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setMaxLines(1);
        chip.setTextColor(ContextCompat.getColor(activity, followUp ? R.color.ls_lyra_green : R.color.ls_lyra_platinum));
        chip.setBackgroundResource(R.drawable.ls_ai_followup);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinHeight(dp(36));
        chip.setPadding(dp(14), 0, dp(14), 0);
        if (!followUp) {
            Drawable d = ContextCompat.getDrawable(activity, web ? R.drawable.ic_search_globe : R.drawable.ic_ai_sparkle);
            if (d != null) {
                d = d.mutate();
                d.setBounds(0, 0, dp(14), dp(14));
                d.setTint(ContextCompat.getColor(activity, R.color.ls_lyra_green));
                chip.setCompoundDrawablesRelative(d, null, null, null);
                chip.setCompoundDrawablePadding(dp(6));
            }
        }
        chip.setOnClickListener(v -> {
            Haptics.tick(v);
            ask(prompt, label, web);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        if (quick.getChildCount() > 0) lp.setMarginStart(dp(8));
        quick.addView(chip, lp);
    }

    // ── conversation ────────────────────────────────────────────────────────────

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || call != null) return;
        input.setText("");
        ask(text, text, false);
    }

    /** {@code shown} is what the user bubble says (a chip shows its label, not the long prompt). */
    private void ask(String prompt, String shown, boolean web) {
        if (call != null) return;
        if (!ai.isAuthenticated()) {
            addBubble(s(R.string.ai_login_required), false);
            return;
        }
        FloatingOverlayService.LyricsSource src = ctx.source();
        if (src != null) syncSong(src);
        root.findViewById(R.id.lyra_intro).setVisibility(View.GONE);
        addBubble(shown, true);
        TextView reply = addBubble(s(R.string.ai_thinking), false);
        reply.setAlpha(0.7f);
        quick.removeAllViews();
        setBusy(true);
        face.setImageResource(R.drawable.ls_mascot);
        chat.setModelId(prefs.getString(MainPrefs.AI_MODEL, SongChatManager.DEFAULT_MODEL));
        chat.setAppState(appState(src));
        final StringBuilder acc = new StringBuilder();
        call = chat.sendMessage(prompt, web, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String delta) {
                main.post(() -> {
                    acc.append(delta);
                    reply.setAlpha(1f);
                    CleanMarkdownFormatter.applyMarkdown(reply, FollowUps.visible(acc.toString()), true);
                    scrollToEnd();
                });
            }

            @Override
            public void onToolExecuting(String tool, String detail) {
                main.post(() -> {
                    if (acc.length() > 0) return;
                    reply.setText(CleanMarkdownFormatter.formatTool(tool, detail, 0, activity).runningText);
                });
            }

            @Override
            public void onComplete(String full, List<String> sources, int in, int out) {
                main.post(() -> {
                    call = null;
                    setBusy(false);
                    FollowUps.Reply r = FollowUps.split(full);
                    reply.setAlpha(1f);
                    CleanMarkdownFormatter.applyMarkdown(reply, r.text, false);
                    face.setImageResource(R.drawable.ls_mascot_smug);
                    renderQuick(r.suggestions);
                    scrollToEnd();
                });
            }

            @Override
            public void onError(Exception e) {
                main.post(() -> {
                    call = null;
                    setBusy(false);
                    reply.setAlpha(1f);
                    reply.setText(e != null && e.getMessage() != null ? e.getMessage() : s(R.string.lyra_full_error));
                    renderQuick(null);
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        send.setEnabled(!busy);
        send.setAlpha(busy ? 0.4f : 1f);
    }

    private TextView addBubble(String text, boolean user) {
        TextView tv = new TextView(activity);
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextColor(ContextCompat.getColor(activity, R.color.ls_lyra_platinum));
        tv.setBackgroundResource(user ? R.drawable.ls_ai_user_bubble : R.drawable.ls_lyra_ai_bubble);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setTextIsSelectable(!user);
        if (user) tv.setText(text);
        else CleanMarkdownFormatter.applyMarkdown(tv, text, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.gravity = user ? Gravity.END : Gravity.START;
        if (user) lp.setMarginStart(dp(48));
        else lp.setMarginEnd(dp(24));
        messages.addView(tv, lp);
        scrollToEnd();
        return tv;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.smoothScrollTo(0, messages.getBottom()));
    }

    /** What Lyra sees about this screen; the tool layer can ask for more with get_app_state. */
    private String appState(FloatingOverlayService.LyricsSource src) {
        if (src == null) return "{}";
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("screen", "full_screen_lyrics");
        o.addProperty("position_ms", src.position());
        o.addProperty("duration_ms", src.duration());
        o.addProperty("playing", src.isPlaying());
        o.addProperty("active_line", ctx.activeLine());
        o.addProperty("romaji", src.romajiEnabled());
        o.addProperty("original_script", src.originalScriptEnabled());
        o.addProperty("translation", src.translationEnabled());
        return o.toString();
    }

    /** "[i] text | r: romaji | t: translation" per line: the format the overlay's chat uses. */
    static String chatLyrics(LyricsData lyrics) {
        if (lyrics == null || lyrics.lines == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lyrics.lines.size(); i++) {
            LyricsData.LyricsLine l = lyrics.lines.get(i);
            if (l == null || l.text == null || l.isInterlude) continue;
            sb.append('[').append(i).append("] ").append(l.text);
            if (l.romanji != null && !l.romanji.trim().isEmpty()) sb.append(" | r: ").append(l.romanji.trim());
            if (l.translation != null && !l.translation.trim().isEmpty()) sb.append(" | t: ").append(l.translation.trim());
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String s(int id) {
        return activity.getString(id);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
