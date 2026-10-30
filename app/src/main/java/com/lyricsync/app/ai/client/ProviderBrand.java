package com.lyricsync.app.ai.client;

import java.util.Locale;

/**
 * Which logo and tile colour a provider row shows, from its kind or base URL.
 * Pure JVM: returns a {@link Logo} the UI maps to a drawable, so the matching is tested.
 */
public final class ProviderBrand {
    private ProviderBrand() {}

    public enum Logo { GOOGLE, OPENAI, DEEPSEEK, RIVER, OPENROUTER, OLLAMA, API }

    public static final class Brand {
        public final Logo logo;
        /** Tile background, opaque ARGB. */
        public final int tile;
        /** Logo tint on the tile, opaque ARGB. */
        public final int tint;

        Brand(Logo logo, int tile, int tint) {
            this.logo = logo;
            this.tile = tile;
            this.tint = tint;
        }
    }

    public static final Brand GOOGLE = new Brand(Logo.GOOGLE, 0xFF1A2A4A, 0xFF8AB4F8);
    public static final Brand CHATGPT = new Brand(Logo.OPENAI, 0xFF0F3D33, 0xFFFFFFFF);

    /** Brand for an OpenAI-compatible base URL; unknown hosts get the generic API mark. */
    public static Brand forUrl(String baseUrl) {
        String host = AiProfiles.host(baseUrl).toLowerCase(Locale.ROOT);
        if (host.endsWith("deepseek.com")) return new Brand(Logo.DEEPSEEK, 0xFF1C2A66, 0xFF8FA4FF);
        if (host.endsWith("river.ai")) return new Brand(Logo.RIVER, 0xFF0E1D4A, 0xFFC4D2EC);
        if (host.endsWith("openrouter.ai")) return new Brand(Logo.OPENROUTER, 0xFF2A2F3A, 0xFFCBD5E1);
        if (host.endsWith("openai.com")) return new Brand(Logo.OPENAI, 0xFF0F3D33, 0xFFFFFFFF);
        if (host.equals("localhost") || host.startsWith("127.") || host.startsWith("192.168.")
                || host.startsWith("10.") || baseUrl != null && baseUrl.contains(":11434")) {
            return new Brand(Logo.OLLAMA, 0xFF2B2B2B, 0xFFFFFFFF);
        }
        return new Brand(Logo.API, 0xFF24252F, 0xFF1ED760);
    }
}
