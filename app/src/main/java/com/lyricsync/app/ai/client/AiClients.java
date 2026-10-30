package com.lyricsync.app.ai.client;

import android.content.Context;
import android.content.SharedPreferences;

import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.auth.CodexAuth;

/** An AI client wired like the overlay's: active provider profile, Codex sign-in, Google fallback. */
public final class AiClients {
    private AiClients() {}

    public static AntigravityApiClient forApp(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("lyricsync", Context.MODE_PRIVATE);
        AntigravityApiClient client = new AntigravityApiClient(AntigravityAuthManager.getInstance(app));
        client.setProviderSettings(() -> AiProvider.read(prefs));
        client.setCodexAuth(CodexAuth.get(app));
        return client;
    }
}
