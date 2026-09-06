package com.lyricsync.app.ai.history;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lyricsync.app.ai.model.AiChatMessage;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Manages persistent storage and lifecycle for AI chat sessions.
 * Stores conversation history per track/topic in SharedPreferences with JSON serialization,
 * supporting session restoration, single-session deletion, and full history wipe.
 */
public class AiChatHistoryManager {

    public static final String PREF_NAME = "lyricsync_ai_history";
    public static final String KEY_SESSIONS = "chat_sessions_json";
    public static final int MAX_SESSIONS = 50;

    public static class ChatSession {
        public String id;
        public String songTitle;
        public String songArtist;
        public long timestamp;
        public List<AiChatMessage> messages = new ArrayList<>();
        public List<String> webSources = new ArrayList<>();

        public ChatSession() {}

        public ChatSession(String id, String songTitle, String songArtist, long timestamp,
                           List<AiChatMessage> messages, List<String> webSources) {
            this.id = id;
            this.songTitle = songTitle;
            this.songArtist = songArtist;
            this.timestamp = timestamp;
            if (messages != null) {
                this.messages.addAll(messages);
            }
            if (webSources != null) {
                this.webSources.addAll(webSources);
            }
        }

        public String getSnippet() {
            if (messages == null || messages.isEmpty()) {
                return "";
            }
            // Return preview from the first user query or last model answer
            for (AiChatMessage msg : messages) {
                if (msg != null && msg.content != null && !msg.content.trim().isEmpty()) {
                    String clean = msg.content.replaceAll("[#*`_>\\r\\n]+", " ").trim();
                    if (!clean.isEmpty()) {
                        return clean.length() > 80 ? clean.substring(0, 77) + "..." : clean;
                    }
                }
            }
            return "";
        }

        public String getFormattedDate() {
            if (timestamp <= 0) return "";
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
                return sdf.format(new Date(timestamp));
            } catch (Exception e) {
                return "";
            }
        }

        public int getMessageCount() {
            return messages != null ? messages.size() : 0;
        }
    }

    private final SharedPreferences prefs;
    private final Gson gson;
    private static final Type SESSIONS_TYPE = new TypeToken<List<ChatSession>>() {}.getType();

    public AiChatHistoryManager(Context context) {
        this(context != null ? context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) : null);
    }

    public AiChatHistoryManager(SharedPreferences prefs) {
        this.prefs = prefs;
        this.gson = new Gson();
    }

    /**
     * Retrieves all saved chat sessions ordered newest first.
     */
    public synchronized List<ChatSession> getSessions() {
        if (prefs == null) return new ArrayList<>();
        String json = prefs.getString(KEY_SESSIONS, null);
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<ChatSession> list = gson.fromJson(json, SESSIONS_TYPE);
            if (list == null) return new ArrayList<>();
            // Sort newest first
            Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Retrieves a single session by unique ID.
     */
    public synchronized ChatSession getSession(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (ChatSession s : getSessions()) {
            if (id.equals(s.id)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Saves or updates a session. If id is missing, generates a new one.
     * Brings the session to the front of the list and caps to MAX_SESSIONS.
     */
    public synchronized void saveSession(ChatSession session) {
        if (session == null || prefs == null) return;
        if (session.id == null || session.id.trim().isEmpty()) {
            session.id = UUID.randomUUID().toString();
        }
        if (session.timestamp <= 0) {
            session.timestamp = System.currentTimeMillis();
        }

        List<ChatSession> list = getSessions();
        // Remove existing session with the same id
        Iterator<ChatSession> it = list.iterator();
        while (it.hasNext()) {
            ChatSession s = it.next();
            if (session.id.equals(s.id)) {
                it.remove();
                break;
            }
        }

        // Add to front
        list.add(0, session);

        // Cap to MAX_SESSIONS
        if (list.size() > MAX_SESSIONS) {
            list = new ArrayList<>(list.subList(0, MAX_SESSIONS));
        }

        try {
            String json = gson.toJson(list);
            prefs.edit().putString(KEY_SESSIONS, json).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Deletes a single session by its unique ID.
     * @return true if found and removed.
     */
    public synchronized boolean deleteSession(String id) {
        if (id == null || prefs == null) return false;
        List<ChatSession> list = getSessions();
        boolean removed = false;
        Iterator<ChatSession> it = list.iterator();
        while (it.hasNext()) {
            ChatSession s = it.next();
            if (id.equals(s.id)) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (removed) {
            try {
                String json = gson.toJson(list);
                prefs.edit().putString(KEY_SESSIONS, json).apply();
            } catch (Exception ignored) {}
        }
        return removed;
    }

    /**
     * Deletes all saved chat sessions.
     */
    public synchronized void clearAllSessions() {
        if (prefs == null) return;
        prefs.edit().remove(KEY_SESSIONS).apply();
    }

    /**
     * Returns the number of saved sessions.
     */
    public synchronized int getSessionCount() {
        return getSessions().size();
    }

    /**
     * Checks if any sessions exist.
     */
    public synchronized boolean hasHistory() {
        return getSessionCount() > 0;
    }
}
