package com.lyricsync.app.ai.client;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Several saved providers (DeepSeek and River side by side), one active, mirrored to the keys the app reads. */
public class AiProfilesTest {

    private final MemoryPrefs prefs = new MemoryPrefs();

    private AiProfiles.Profile deepseek() {
        return AiProfiles.save(prefs, new AiProfiles.Profile("", "", "api.deepseek.com", "sk-ds", "deepseek-chat"));
    }

    private AiProfiles.Profile river() {
        return AiProfiles.save(prefs, new AiProfiles.Profile("", "River", "https://api.river.ai/v1/", "rv-1", "deepseek-ai/DeepSeek-V4.1-Flash"));
    }

    @Test
    public void switchingProvidersChangesWhatTheAppUses() {
        AiProfiles.Profile ds = deepseek(), rv = river();
        assertEquals("DeepSeek", ds.name);
        assertEquals("https://api.deepseek.com/v1", ds.baseUrl);
        assertFalse("saving alone does not switch", AiProvider.read(prefs).customReady());

        AiProfiles.activate(prefs, ds.id);
        AiProvider.Settings s = AiProvider.read(prefs);
        assertTrue(s.customReady());
        assertEquals("deepseek-chat", s.model);
        assertEquals(ds.id, AiProfiles.activeId(prefs));

        AiProfiles.activate(prefs, rv.id);
        s = AiProvider.read(prefs);
        assertEquals("https://api.river.ai/v1", s.baseUrl);
        assertEquals("rv-1", s.apiKey);

        AiProfiles.activate(prefs, null); // Google
        assertFalse(AiProvider.read(prefs).customReady());
        assertEquals("", AiProfiles.activeId(prefs));
        assertEquals(2, AiProfiles.list(prefs).size());
    }

    @Test
    public void editingAppliesOnlyToTheActiveProviderAndDeletingItFallsBackToGoogle() {
        AiProfiles.Profile ds = deepseek(), rv = river();
        AiProfiles.activate(prefs, ds.id);

        rv.model = "other";
        AiProfiles.save(prefs, rv);
        assertEquals("inactive edit leaves the live settings alone", "deepseek-chat", AiProvider.read(prefs).model);

        ds.model = "deepseek-reasoner";
        AiProfiles.save(prefs, ds);
        assertEquals("deepseek-reasoner", AiProvider.read(prefs).model);
        assertEquals(2, AiProfiles.list(prefs).size());

        AiProfiles.delete(prefs, rv.id);
        assertEquals(ds.id, AiProfiles.activeId(prefs));
        AiProfiles.delete(prefs, ds.id);
        assertFalse(AiProvider.read(prefs).customReady());
        assertTrue(AiProfiles.list(prefs).isEmpty());
    }

    @Test
    public void theSingleProviderOfEarlierVersionsIsKept() {
        prefs.edit().putBoolean(AiProvider.PREF_CUSTOM_ENABLED, true).putString(AiProvider.PREF_BASE_URL, "https://api.river.ai/v1")
                .putString(AiProvider.PREF_API_KEY, "rv-1").putString(AiProvider.PREF_MODEL, "m").apply();
        AiProfiles.migrate(prefs);
        AiProfiles.migrate(prefs); // idempotent
        assertEquals(1, AiProfiles.list(prefs).size());
        AiProfiles.Profile p = AiProfiles.find(AiProfiles.list(prefs), AiProfiles.activeId(prefs));
        assertNotNull("it stays the active provider", p);
        assertEquals("River", p.name);
        assertTrue(AiProvider.read(prefs).customReady());
    }

    @Test
    public void namesAndValidation() {
        assertEquals("River", AiProfiles.defaultName("api.river.ai"));
        assertEquals("llm.example.com", AiProfiles.defaultName("https://api.llm.example.com/v1"));
        assertEquals("localhost", AiProfiles.defaultName("http://localhost:11434"));
        assertEquals(AiProfiles.Problem.URL, new AiProfiles.Profile("", "", "no url", "", "m").problem());
        assertEquals(AiProfiles.Problem.MODEL, new AiProfiles.Profile("", "", "api.deepseek.com", "", " ").problem());
        assertEquals(AiProfiles.Problem.NONE, new AiProfiles.Profile("", "", "localhost:11434", "", "llama3").problem());
    }

    /** In-memory prefs; apply() is immediate. */
    public static final class MemoryPrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(m); }
        @Override public String getString(String k, String d) { Object v = m.get(k); return v instanceof String ? (String) v : d; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { Object v = m.get(k); return v instanceof Integer ? (Integer) v : d; }
        @Override public long getLong(String k, long d) { Object v = m.get(k); return v instanceof Long ? (Long) v : d; }
        @Override public float getFloat(String k, float d) { Object v = m.get(k); return v instanceof Float ? (Float) v : d; }
        @Override public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v instanceof Boolean ? (Boolean) v : d; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>();
                @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                @Override public Editor remove(String k) { pending.put(k, null); return this; }
                @Override public Editor clear() { m.clear(); return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    for (Map.Entry<String, Object> e : pending.entrySet()) {
                        if (e.getValue() == null) m.remove(e.getKey()); else m.put(e.getKey(), e.getValue());
                    }
                }
            };
        }
    }
}
