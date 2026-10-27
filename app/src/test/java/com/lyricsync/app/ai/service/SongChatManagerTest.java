package com.lyricsync.app.ai.service;

import android.content.SharedPreferences;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.history.AiChatHistoryManager;
import com.lyricsync.app.ai.model.AiChatMessage;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SongChatManagerTest {

    private SongChatManager chatManager;
    private AiChatHistoryManager historyManager;
    private FakeSharedPreferences fakePrefs;

    @Before
    public void setUp() {
        chatManager = new SongChatManager(null, "gemini-3.8-flash-high");
        fakePrefs = new FakeSharedPreferences();
        historyManager = new AiChatHistoryManager(fakePrefs);
        chatManager.setHistoryManager(historyManager);
    }

    @Test
    public void testInitialState() {
        assertEquals("gemini-3.8-flash-high", chatManager.getModelId());
        assertTrue(chatManager.getHistory().isEmpty());
        assertTrue(chatManager.getLastWebSources().isEmpty());
        assertNotNull(chatManager.getActiveSessionId());
    }

    @Test
    public void testSetCurrentSongUpdatesContextAndPreservesSession() {
        String originalSession = chatManager.getActiveSessionId();
        chatManager.setCurrentSong("Bohemian Rhapsody", "Queen", "Is this the real life?");

        assertEquals("Bohemian Rhapsody", chatManager.getTitle());
        assertEquals("Queen", chatManager.getArtist());
        assertEquals("Is this the real life?", chatManager.getFullLyrics());
        assertEquals(originalSession, chatManager.getActiveSessionId());
    }

    @Test
    public void testStartNewSession() {
        chatManager.setCurrentSong("Song A", "Artist A", "Lyrics A");
        String oldSession = chatManager.getActiveSessionId();

        chatManager.startNewSession();

        String newSession = chatManager.getActiveSessionId();
        assertNotEquals(oldSession, newSession);
        assertTrue(chatManager.getHistory().isEmpty());
        assertEquals("Song A", chatManager.getTitle());
    }

    @Test
    public void testSetCurrentSongPreservesHistoryAcrossSongs() {
        chatManager.setCurrentSong("Song A", "Artist A", "Lyrics A");
        String session = chatManager.getActiveSessionId();

        AiChatHistoryManager.ChatSession saved = new AiChatHistoryManager.ChatSession(
                session, "Song A", "Artist A", 1000L,
                Collections.singletonList(AiChatMessage.user("hello")), null);
        chatManager.loadSession(saved);
        assertEquals(1, chatManager.getHistory().size());

        // Song changes to Song B
        chatManager.setCurrentSong("Song B", "Artist B", "Lyrics B");

        // Session and history remain intact
        assertEquals(session, chatManager.getActiveSessionId());
        assertEquals(1, chatManager.getHistory().size());
        assertEquals("hello", chatManager.getHistory().get(0).content);
        assertEquals("Song B", chatManager.getTitle());
    }

    @Test
    public void testBuildMessagesToSendLeavesUserMessageUntouched() {
        AiChatHistoryManager.ChatSession session = new AiChatHistoryManager.ChatSession(
                "sess-1",
                "Idol",
                "YOASOBI",
                System.currentTimeMillis(),
                Collections.singletonList(AiChatMessage.user("Halo apa kabar?")),
                Collections.<String>emptyList()
        );
        chatManager.loadSession(session);

        List<AiChatMessage> messages = chatManager.buildMessagesToSend(false);
        assertFalse(messages.isEmpty());
        AiChatMessage lastUser = messages.get(messages.size() - 1);
        assertEquals("user", lastUser.role);
        assertEquals("Halo apa kabar?", lastUser.content);
        assertFalse(lastUser.content.contains("Lagu yang SEDANG DIPUTAR"));
    }
    @Test
    public void testLoadSession() {
        AiChatHistoryManager.ChatSession session = new AiChatHistoryManager.ChatSession(
                "saved-id-123",
                "Hotel California",
                "Eagles",
                123456789L,
                Arrays.asList(
                        AiChatMessage.user("What is the dark desert highway?"),
                        AiChatMessage.model("It symbolizes the desolate road to Los Angeles.")
                ),
                Collections.singletonList("https://en.wikipedia.org/wiki/Hotel_California")
        );

        chatManager.loadSession(session);

        assertEquals("saved-id-123", chatManager.getActiveSessionId());
        assertEquals("Hotel California", chatManager.getTitle());
        assertEquals("Eagles", chatManager.getArtist());
        assertEquals(2, chatManager.getHistory().size());
        assertEquals("What is the dark desert highway?", chatManager.getHistory().get(0).content);
        assertEquals(1, chatManager.getLastWebSources().size());
    }

    @Test
    public void testCreateSessionSnapshot() {
        chatManager.setCurrentSong("Yesterday", "Beatles", "Yesterday, all my troubles...");
        AiChatHistoryManager.ChatSession snapshot = chatManager.createSessionSnapshot();

        assertNotNull(snapshot);
        assertEquals(chatManager.getActiveSessionId(), snapshot.id);
        assertEquals("Yesterday", snapshot.songTitle);
        assertEquals("Beatles", snapshot.songArtist);
        assertTrue(snapshot.messages.isEmpty());
    }

    @Test
    public void testBuildMessagesToSendSanitization() {
        chatManager.setCurrentSong("Song", "Artist", "Lyrics");
        List<AiChatMessage> messages = chatManager.buildMessagesToSend(false);

        // Must have system context user message + model acknowledgment
        assertTrue(messages.size() >= 2);
        assertEquals("user", messages.get(0).role);
        assertEquals("model", messages.get(1).role);
    }

    private static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String defValue) {
            Object val = values.get(key);
            return val instanceof String ? (String) val : defValue;
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return null; }
        @Override public int getInt(String key, int defValue) {
            Object val = values.get(key);
            return val instanceof Integer ? (Integer) val : defValue;
        }
        @Override public long getLong(String key, long defValue) {
            Object val = values.get(key);
            return val instanceof Long ? (Long) val : defValue;
        }
        @Override public float getFloat(String key, float defValue) {
            Object val = values.get(key);
            return val instanceof Float ? (Float) val : defValue;
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object val = values.get(key);
            return val instanceof Boolean ? (Boolean) val : defValue;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new FakeEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        class FakeEditor implements Editor {
            private final Map<String, Object> changes = new HashMap<>(values);
            @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> v) { return this; }
            @Override public Editor putInt(String key, int value) { changes.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { changes.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
            @Override public Editor remove(String key) { changes.remove(key); return this; }
            @Override public Editor clear() { changes.clear(); return this; }
            @Override public boolean commit() { values.clear(); values.putAll(changes); return true; }
            @Override public void apply() { commit(); }
        }
    }
}
