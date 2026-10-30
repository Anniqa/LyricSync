package com.lyricsync.app.ai.auth;

import com.google.gson.JsonObject;
import com.lyricsync.app.ai.client.AiProfilesTest;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** ChatGPT sign-in: the authorize URL OpenAI accepts, token storage from JWT claims, and pasted inputs. */
public class CodexAuthTest {

    @Test
    public void authorizeUrlMatchesTheCodexClient() {
        HttpUrl u = HttpUrl.get(CodexAuth.authorizeUrl("st", CodexAuth.challenge("verifier")));
        assertEquals("auth.openai.com", u.host());
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", u.queryParameter("client_id"));
        assertEquals("http://localhost:1455/auth/callback", u.queryParameter("redirect_uri"));
        assertEquals("S256", u.queryParameter("code_challenge_method"));
        assertTrue(u.queryParameter("scope").contains("offline_access"));
        // S256 = base64url(sha256(verifier)) without padding (checked against Python hashlib)
        assertEquals("iMnq5o6zALKXGivsnlom_0F5_WYda32GHkxlV7mq7hQ", CodexAuth.challenge("verifier"));
    }

    @Test
    public void storesTokensAndReadsAccountFromClaims() {
        AiProfilesTest.MemoryPrefs prefs = new AiProfilesTest.MemoryPrefs();
        CodexAuth auth = new CodexAuth(prefs, null);
        JsonObject t = new JsonObject();
        t.addProperty("access_token", jwt("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acc-1\",\"chatgpt_plan_type\":\"plus\"},"
                + "\"https://api.openai.com/profile\":{\"email\":\"Ana@Example.com\"}}"));
        t.addProperty("refresh_token", "rt-1");
        t.addProperty("expires_in", 3600);
        assertNull(auth.store(t));
        assertTrue(auth.isSignedIn());
        assertEquals("ana@example.com", auth.email());
        assertEquals("plus", auth.plan());

        JsonObject refreshed = new JsonObject(); // refresh responses may omit the refresh token
        refreshed.addProperty("access_token", jwt("{}"));
        refreshed.addProperty("expires_in", 60);
        assertNull(auth.store(refreshed));
        assertEquals("rt-1", prefs.getString(CodexAuth.PREF_REFRESH, ""));
        assertEquals("acc-1", prefs.getString(CodexAuth.PREF_ACCOUNT, ""));

        auth.logout();
        assertTrue(!auth.isSignedIn() && auth.email().isEmpty());
    }

    @Test
    public void readsWhatUsersPaste() {
        assertEquals("abc", CodexAuth.callbackCode("http://localhost:1455/auth/callback?code=abc&state=s"));
        assertNull(CodexAuth.callbackCode("rt_0123456789abcdefghij"));
        assertEquals("rt_0123456789abcdefghij", CodexAuth.refreshTokenFrom("{\"tokens\":{\"refresh_token\":\"rt_0123456789abcdefghij\"}}"));
        assertEquals("rt_0123456789abcdefghij", CodexAuth.refreshTokenFrom("  rt_0123456789abcdefghij "));
        assertEquals("", CodexAuth.refreshTokenFrom("not a token"));
    }

    @Test
    public void modelListKeepsListedApiModelsWithTheirEfforts() {
        java.util.List<CodexAuth.Model> m = CodexAuth.parseModels("{\"models\":[{\"slug\":\"gpt-reserve\",\"visibility\":\"hide\"},"
                + "{\"slug\":\"gpt-6-luna\",\"display_name\":\"GPT-6-Luna\",\"visibility\":\"list\",\"supported_in_api\":true,"
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"high\"}]}]}");
        assertEquals(1, m.size());
        assertEquals("gpt-6-luna", m.get(0).id);
        assertEquals(java.util.Arrays.asList("low", "high"), m.get(0).efforts);
    }

    private static String jwt(String payload) {
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return e.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "."
                + e.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }
}
