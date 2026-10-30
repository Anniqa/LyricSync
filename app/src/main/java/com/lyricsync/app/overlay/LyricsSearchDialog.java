package com.lyricsync.app.overlay;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.lyricsync.app.R;
import com.lyricsync.app.lyrics.LyricsProviderManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.LyricsFormat;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.util.Haptics;

/**
 * Overlay dialog to find lyrics by hand: edit title/artist, watch each source's result
 * arrive, tap one to show it; or paste lyrics in any format. Choosing goes through
 * {@link Host#useLyrics}, the same path the AI's find_lyrics tool takes.
 */
public final class LyricsSearchDialog {

    public interface Host {
        TrackInfo currentTrack();

        LyricsProviderManager lyricsManager();

        /** Shows {@code lyrics} for the playing track and remembers the choice. */
        void useLyrics(LyricsData lyrics);
    }

    private final Context context;
    private final Host host;
    private AlertDialog dialog;
    private LyricsProviderManager.Search search;

    /** True when shown from the overlay service (needs the overlay window type). */
    private final boolean overlayWindow;

    LyricsSearchDialog(Context context, Host host) {
        this(context, host, true);
    }

    /** From an activity: a normal dialog on top of it. */
    public static LyricsSearchDialog forActivity(android.app.Activity activity, Host host) {
        return new LyricsSearchDialog(activity, host, false);
    }

    private LyricsSearchDialog(Context context, Host host, boolean overlayWindow) {
        this.context = context;
        this.host = host;
        this.overlayWindow = overlayWindow;
    }

    @SuppressLint("InflateParams") // AlertDialog supplies the parent via setView
    public void show() {
        dismiss();
        Context themed = new ContextThemeWrapper(context, R.style.Theme_LyricSync);
        View v = LayoutInflater.from(themed).inflate(R.layout.dialog_lyrics_search, null);
        dialog = new AlertDialog.Builder(themed).setView(v).create();
        if (dialog.getWindow() != null) {
            if (overlayWindow) dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnDismissListener(d -> cancelSearch());

        EditText title = v.findViewById(R.id.lyrics_search_title_input);
        EditText artist = v.findViewById(R.id.lyrics_search_artist_input);
        TextView status = v.findViewById(R.id.lyrics_search_status);
        LinearLayout results = v.findViewById(R.id.lyrics_search_results);
        TrackInfo track = host.currentTrack();
        if (track != null) {
            title.setText(track.title);
            artist.setText(track.artist);
        }
        Runnable run = () -> runSearch(text(title), text(artist), status, results);
        v.findViewById(R.id.lyrics_search_go).setOnClickListener(b -> {
            Haptics.tick(b);
            run.run();
        });
        artist.setOnEditorActionListener((tv, id, ev) -> {
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            run.run();
            return true;
        });

        View form = v.findViewById(R.id.lyrics_search_form), paste = v.findViewById(R.id.lyrics_paste_form);
        TextView mode = v.findViewById(R.id.lyrics_search_mode);
        mode.setOnClickListener(b -> {
            boolean toPaste = paste.getVisibility() != View.VISIBLE;
            paste.setVisibility(toPaste ? View.VISIBLE : View.GONE);
            form.setVisibility(toPaste ? View.GONE : View.VISIBLE);
            mode.setText(toPaste ? R.string.lyrics_search_title : R.string.lyrics_search_paste);
        });
        EditText pasteInput = v.findViewById(R.id.lyrics_paste_input);
        TextView pasteStatus = v.findViewById(R.id.lyrics_paste_status);
        v.findViewById(R.id.lyrics_paste_use).setOnClickListener(b -> {
            String raw = pasteInput.getText() != null ? pasteInput.getText().toString() : "";
            LyricsData l = LyricsFormat.parse(raw);
            if (l.isEmpty()) {
                pasteStatus.setText(R.string.lyrics_paste_unreadable);
                return;
            }
            Haptics.tick(b);
            l.provider = context.getString(R.string.lyrics_source_pasted);
            host.useLyrics(l);
            dismiss();
        });

        dialog.show();
        run.run(); // search the playing track right away
    }

    private void runSearch(String title, String artist, TextView status, LinearLayout results) {
        cancelSearch();
        results.removeAllViews();
        status.setText(R.string.lyrics_search_running);
        search = host.lyricsManager().search(host.currentTrack(), title, artist, new LyricsProviderManager.SearchListener() {
            @Override
            public void onCandidate(LyricsProviderManager.Candidate c) {
                addResult(results, c);
            }

            @Override
            public void onFinished(int found) {
                status.setText(found == 0 ? context.getString(R.string.lyrics_search_none)
                        : context.getResources().getQuantityString(R.plurals.lyrics_search_found, found, found));
            }
        });
    }

    /** Results are kept in quality order: SYLLABLE first, then by line count. */
    private void addResult(LinearLayout results, LyricsProviderManager.Candidate c) {
        View row = LayoutInflater.from(results.getContext()).inflate(R.layout.item_lyrics_result, results, false);
        LyricsData l = c.lyrics;
        ((TextView) row.findViewById(R.id.result_source)).setText(l.provider != null ? l.provider : c.source);
        TextView type = row.findViewById(R.id.result_type);
        boolean word = l.type == LyricsData.Type.SYLLABLE;
        type.setText(word ? R.string.lyrics_type_word : l.type == LyricsData.Type.LINE ? R.string.lyrics_type_line
                : R.string.lyrics_type_static);
        int color = ContextCompat.getColor(context, word ? R.color.ls_lyra_green : R.color.ls_lyra_silver);
        type.setTextColor(color);
        type.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context,
                word ? R.color.ls_lyra_green_container : R.color.ls_lyra_surface_high)));
        ((TextView) row.findViewById(R.id.result_preview)).setText(
                context.getResources().getQuantityString(R.plurals.lyrics_result_lines, lines(l), lines(l), preview(l)));
        row.setTag(l);
        row.setOnClickListener(b -> {
            Haptics.tick(b);
            host.useLyrics(l);
            dismiss();
        });
        int at = results.getChildCount();
        for (int i = 0; i < results.getChildCount(); i++) {
            if (LyricsProviderManager.betterThan(l, (LyricsData) results.getChildAt(i).getTag())) {
                at = i;
                break;
            }
        }
        results.addView(row, at);
        row.setAlpha(0f);
        row.animate().alpha(1f).setDuration(160).start();
    }

    public static int lines(LyricsData l) {
        int n = 0;
        for (LyricsData.LyricsLine line : l.lines) if (!line.isInterlude) n++;
        return n;
    }

    /** The first two sung lines, "a / b". */
    public static String preview(LyricsData l) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (LyricsData.LyricsLine line : l.lines) {
            if (line.isInterlude || line.text == null || line.text.trim().isEmpty()) continue;
            if (n++ > 0) sb.append(" / ");
            sb.append(line.text.trim());
            if (n == 2) break;
        }
        return sb.toString();
    }

    private void cancelSearch() {
        if (search != null) search.cancel();
        search = null;
    }

    public void dismiss() {
        cancelSearch();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
