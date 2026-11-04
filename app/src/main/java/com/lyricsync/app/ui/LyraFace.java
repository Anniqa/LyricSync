package com.lyricsync.app.ui;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.widget.ImageView;

import com.lyricsync.app.R;
import com.lyricsync.app.util.AppLog;

/**
 * Lyra's face on any ImageView. Each mood is a still drawable plus an optional looping
 * animated WebP, {@code res/raw/lyra_anim_<mood>}. On API 28+ the animation plays when it
 * exists; otherwise the still picture is shown. Switching moods crossfades.
 */
public final class LyraFace {
    public static final int ANNOYED = 0, PROUD = 1, SMUG = 2, THINKING = 3;

    private static final String[] NAMES = {"annoyed", "proud", "smug", "thinking"};
    private static final int[] STILLS = {
            R.drawable.ls_mascot, R.drawable.ls_mascot_proud, R.drawable.ls_mascot_smug, R.drawable.ls_mascot};
    private static final long FADE_MS = 160;

    private LyraFace() {}

    /** Shows {@code mood} on {@code view}; a no-op when it already shows it. */
    public static void set(ImageView view, int mood) {
        if (view == null || mood < 0 || mood >= NAMES.length) return;
        Object shown = view.getTag(R.id.lyra_face_mood);
        if (shown instanceof Integer && (Integer) shown == mood) return;
        view.setTag(R.id.lyra_face_mood, mood);
        Drawable next = load(view.getContext(), mood);
        if (shown == null) {
            show(view, next);
            return;
        }
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            show(view, next);
            view.animate().alpha(1f).setDuration(FADE_MS).start();
        }).start();
    }

    private static void show(ImageView view, Drawable d) {
        Drawable old = view.getDrawable();
        if (Build.VERSION.SDK_INT >= 28 && old instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) old).stop();
        view.setImageDrawable(d);
        if (Build.VERSION.SDK_INT >= 28 && d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
    }

    private static Drawable load(Context c, int mood) {
        if (Build.VERSION.SDK_INT >= 28) {
            // Looked up by name (kept by values/keep.xml); a build without the file shows the still.
            int raw = c.getResources().getIdentifier("lyra_anim_" + NAMES[mood], "raw", c.getPackageName());
            if (raw != 0) {
                try {
                    Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(c.getResources(), raw));
                    if (d instanceof AnimatedImageDrawable) {
                        ((AnimatedImageDrawable) d).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    }
                    return d;
                } catch (Exception e) {
                    AppLog.w("LyraFace", "Animated face " + NAMES[mood] + " failed: " + e.getMessage());
                }
            }
        }
        return androidx.appcompat.content.res.AppCompatResources.getDrawable(c, STILLS[mood]);
    }
}
