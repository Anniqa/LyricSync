package com.lyricsync.app.util;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local snapshot of what the overlay service is currently showing, so the
 * activity can mirror it without opening a second MediaSession tracker.
 *
 * The service publishes; the activity observes while it is resumed.
 */
public final class NowPlaying {
    private NowPlaying() {}

    public static final class Snapshot {
        public final String title;
        public final String artist;
        /** Human-readable lyrics status, e.g. "Word-by-word · SpicyLyrics". */
        public final String lyricsStatus;
        public final Bitmap art;
        public final boolean playing;
        public final boolean hasTrack;

        public Snapshot(String title, String artist, String lyricsStatus,
                        Bitmap art, boolean playing, boolean hasTrack) {
            this.title = title;
            this.artist = artist;
            this.lyricsStatus = lyricsStatus;
            this.art = art;
            this.playing = playing;
            this.hasTrack = hasTrack;
        }

        public static Snapshot empty() {
            return new Snapshot(null, null, null, null, false, false);
        }
    }

    public interface Listener {
        void onNowPlaying(Snapshot snapshot);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Snapshot current = Snapshot.empty();

    public static Snapshot current() {
        return current;
    }

    public static void publish(Snapshot snapshot) {
        final Snapshot s = snapshot == null ? Snapshot.empty() : snapshot;
        current = s;
        if (listeners.isEmpty()) return;
        main.post(() -> {
            for (Listener l : listeners) {
                l.onNowPlaying(s);
            }
        });
    }

    public static void clear() {
        publish(Snapshot.empty());
    }

    /** Registers and immediately delivers the current snapshot. Call from the main thread. */
    public static void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        listener.onNowPlaying(current);
    }

    public static void removeListener(Listener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }
}
