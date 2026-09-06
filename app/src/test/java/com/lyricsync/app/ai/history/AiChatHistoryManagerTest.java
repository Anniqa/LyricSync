package com.lyricsync.app.ai.history;

import android.content.SharedPreferences;

import com.lyricsync.app.ai.history.AiChatHistoryManager.ChatSession;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AiChatHistoryManagerTest {

    private FakeSharedPreferences fakePrefs;
    private AiChatHistoryManager historyManager;

    @Before
    public void setUp() {
        fakePrefs = new FakeSharedPreferences();
        historyManager = new AiChatHistoryManager(fakePrefs);
    }

    @Test
    public void testEmptyByDefault() {
        assertFalse(historyManager.hasHistory());
        assertEquals(0, historyManager.getSessionCount());
        assertTrue(historyManager.getSessions().isEmpty());
    }

    @Test
    public void testSaveAndRetrieveSession() {
        ChatSession session = new ChatSession("s-1", "Bohemian Rhapsody", "Queen", 1000L,
                Arrays.asList(AiChatMessage.user("What is this song about?"),
                        AiChatMessage.model("It is an operatic rock masterpiece.")),
                Collections.singletonList("https://en.wikipedia.org/wiki/Bohemian_Rhapsody"));

        historyManager.saveSession(session);

        assertTrue(historyManager.hasHistory());
        assertEquals(1, historyManager.getSessionCount());

        ChatSession retrieved = historyManager.getSession("s-1");
        assertNotNull(retrieved);
        assertEquals("Bohemian Rhapsody", retrieved.songTitle);
        assertEquals("Queen", retrieved.songArtist);
        assertEquals(2, retrieved.getMessageCount());
        assertEquals("It is an operatic rock masterpiece.", retrieved.messages.get(1).content);
        assertEquals(1, retrieved.webSources.size());
    }

    @Test
    public void testOrderNewestFirst() {
        ChatSession s1 = new ChatSession("s-1", "Song A", "Artist A", 1000L,
                Collections.singletonList(AiChatMessage.user("Hello")), null);
        ChatSession s2 = new ChatSession("s-2", "Song B", "Artist B", 2000L,
                Collections.singletonList(AiChatMessage.user("World")), null);
        ChatSession s3 = new ChatSession("s-3", "Song C", "Artist C", 1500L,
                Collections.singletonList(AiChatMessage.user("Foo")), null);

        historyManager.saveSession(s1);
        historyManager.saveSession(s2);
        historyManager.saveSession(s3);

        List<ChatSession> sessions = historyManager.getSessions();
        assertEquals(3, sessions.size());
        assertEquals("s-2", sessions.get(0).id); // 2000L
        assertEquals("s-3", sessions.get(1).id); // 1500L
        assertEquals("s-1", sessions.get(2).id); // 1000L
    }

    @Test
    public void testUpdateExistingSession() {
        ChatSession s1 = new ChatSession("s-1", "Song A", "Artist A", 1000L,
                Collections.singletonList(AiChatMessage.user("Q1")), null);
        historyManager.saveSession(s1);

        ChatSession updated = new ChatSession("s-1", "Song A", "Artist A", 2000L,
                Arrays.asList(AiChatMessage.user("Q1"), AiChatMessage.model("A1")), null);
        historyManager.saveSession(updated);

        assertEquals(1, historyManager.getSessionCount());
        ChatSession retrieved = historyManager.getSession("s-1");
        assertNotNull(retrieved);
        assertEquals(2, retrieved.getMessageCount());
        assertEquals(2000L, retrieved.timestamp);
    }

    @Test
    public void testDeleteSession() {
        ChatSession s1 = new ChatSession("s-1", "Song A", "Artist A", 1000L, null, null);
        ChatSession s2 = new ChatSession("s-2", "Song B", "Artist B", 2000L, null, null);
        historyManager.saveSession(s1);
        historyManager.saveSession(s2);

        assertEquals(2, historyManager.getSessionCount());
        boolean deleted = historyManager.deleteSession("s-1");
        assertTrue(deleted);
        assertEquals(1, historyManager.getSessionCount());
        assertNull(historyManager.getSession("s-1"));
        assertNotNull(historyManager.getSession("s-2"));

        assertFalse(historyManager.deleteSession("non-existent"));
    }

    @Test
    public void testClearAllSessions() {
        ChatSession s1 = new ChatSession("s-1", "Song A", "Artist A", 1000L, null, null);
        ChatSession s2 = new ChatSession("s-2", "Song B", "Artist B", 2000L, null, null);
        historyManager.saveSession(s1);
        historyManager.saveSession(s2);

        assertEquals(2, historyManager.getSessionCount());
        historyManager.clearAllSessions();

        assertEquals(0, historyManager.getSessionCount());
        assertFalse(historyManager.hasHistory());
    }

    @Test
    public void testMaxSessionsCapping() {
        for (int i = 1; i <= 60; i++) {
            ChatSession s = new ChatSession("s-" + i, "Song " + i, "Artist", i * 100L,
                    Collections.singletonList(AiChatMessage.user("Msg " + i)), null);
            historyManager.saveSession(s);
        }

        assertEquals(AiChatHistoryManager.MAX_SESSIONS, historyManager.getSessionCount());
        // s-60 should be first, s-1 should have been evicted
        List<ChatSession> list = historyManager.getSessions();
        assertEquals("s-60", list.get(0).id);
        assertNull(historyManager.getSession("s-1"));
    }

    @Test
    public void testSnippetGeneration() {
        ChatSession session = new ChatSession("s-1", "Song", "Artist", 1000L,
                Arrays.asList(AiChatMessage.user("### Explain **everything** about this!"),
                        AiChatMessage.model("Sure thing.")), null);
        String snippet = session.getSnippet();
        assertFalse(snippet.contains("#"));
        assertFalse(snippet.contains("*"));
        assertTrue(snippet.contains("Explain"));
    }

    private static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() { return new HashMap<>(values); }

        @Override
        public String getString(String key, String defValue) {
            Object val = values.get(key);
            return val instanceof String ? (String) val : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) { return null; }

        @Override
        public int getInt(String key, int defValue) {
            Object val = values.get(key);
            return val instanceof Integer ? (Integer) val : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object val = values.get(key);
            return val instanceof Long ? (Long) val : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object val = values.get(key);
            return val instanceof Float ? (Float) val : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object val = values.get(key);
            return val instanceof Boolean ? (Boolean) val : defValue;
        }

        @Override
        public boolean contains(String key) { return values.containsKey(key); }

        @Override
        public Editor edit() { return new FakeEditor(); }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        class FakeEditor implements Editor {
            private final Map<String, Object> changes = new HashMap<>(values);

            @Override
            public Editor putString(String key, String value) { changes.put(key, value); return this; }
            @Override
            public Editor putStringSet(String key, Set<String> v) { return this; }
            @Override
            public Editor putInt(String key, int value) { changes.put(key, value); return this; }
            @Override
            public Editor putLong(String key, long value) { changes.put(key, value); return this; }
            @Override
            public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
            @Override
            public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
            @Override
            public Editor remove(String key) { changes.remove(key); return this; }
            @Override
            public Editor clear() { changes.clear(); return this; }
            @Override
            public boolean commit() { values.clear(); values.putAll(changes); return true; }
            @Override
            public void apply() { commit(); }
        }
    }
}
