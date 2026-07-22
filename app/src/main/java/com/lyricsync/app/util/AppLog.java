package com.lyricsync.app.util;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.LinkedList;

public class AppLog {
    private static final int MAX_LINES = 200;
    private static final LinkedList<Entry> entries = new LinkedList<>();
    // SimpleDateFormat is not thread-safe and add()/toString() run on many threads
    // (poll thread, provider workers, UI), so give each thread its own formatter.
    private static final ThreadLocal<SimpleDateFormat> sdf =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.US));
    private static volatile OnLogListener listener;

    public static class Entry {
        public final long time;
        public final String level;
        public final String tag;
        public final String msg;

        Entry(String level, String tag, String msg) {
            this.time = System.currentTimeMillis();
            this.level = level;
            this.tag = tag;
            this.msg = msg;
        }

        @Override
        public String toString() {
            return sdf.get().format(new Date(time)) + " " + level + "/" + tag + ": " + msg;
        }
    }

    public interface OnLogListener {
        void onNewLog(Entry entry);
    }

    public static void setListener(OnLogListener l) {
        listener = l;
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        add("D", tag, msg);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        add("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        add("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        add("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        add("E", tag, msg + " | " + (t != null ? t.getMessage() : "null"));
    }

    private static void add(String level, String tag, String msg) {
        Entry entry = new Entry(level, tag, msg);
        synchronized (AppLog.class) {
            entries.add(entry);
            while (entries.size() > MAX_LINES) {
                entries.removeFirst();
            }
        }
        // Notify outside the lock to avoid deadlock/reentrancy if a listener logs.
        OnLogListener l = listener;
        if (l != null) {
            l.onNewLog(entry);
        }
    }

    public static synchronized String getAllText() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.toString()).append("\n");
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        entries.clear();
    }
}
