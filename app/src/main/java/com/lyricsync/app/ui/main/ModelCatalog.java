package com.lyricsync.app.ui.main;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * AI model catalogue for the main screen's model picker: the curated base list and
 * the id → display name / description / icon mapping.
 *
 * <p>Moved verbatim from {@code MainActivity.resolveModelMeta}; the mapping is
 * covered by {@code ModelCatalogTest}. Pure JVM: icons are an enum the UI maps to
 * drawables.
 */
public final class ModelCatalog {
    private ModelCatalog() {}

    public enum Icon { GEMINI, CLAUDE, SPARKLE }

    /** Fallback model id when nothing is stored (mirrors SongChatManager.DEFAULT_MODEL). */
    public static final String DEFAULT_MODEL = "gemini-3.8-flash-high";

    public static final List<String> BASE_MODELS = Collections.unmodifiableList(Arrays.asList(
            "gemini-3.8-flash-high",
            "gemini-3.8-flash-medium",
            "gemini-3.8-flash-low",
            "gemini-3.7-flash-high",
            "gemini-3.7-flash-medium",
            "gemini-3.7-flash-low",
            "gemini-3.6-flash-high",
            "gemini-3.6-flash-medium",
            "gemini-3.6-flash-low",
            "gemini-3.5-flash-low",
            "gemini-3.5-flash-extra-low",
            "gemini-3-pro-high",
            "gemini-3-pro-low",
            "gemini-3-flash-agent",
            "gemini-pro-agent",
            "gemini-3.1-pro-low",
            "gemini-3.1-flash-lite",
            "claude-sonnet-4-6",
            "claude-opus-4-6-thinking",
            "claude-3-7-sonnet",
            "claude-3-5-sonnet",
            "gpt-oss-120b-medium"
    ));

    /** Optional server-provided display name for an id (null/blank/"undefined" = none). */
    public interface DisplayNames {
        String lookup(String id);
    }

    public static final class Entry {
        public final String id;
        public final String displayName;
        public final String description;
        public final Icon icon;

        Entry(String id, String displayName, String description, Icon icon) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.icon = icon;
        }
    }

    public static Entry resolve(String modelId, DisplayNames names) {
        if (modelId == null || modelId.trim().isEmpty()) {
            modelId = DEFAULT_MODEL;
        }
        String id = modelId.trim();
        String mLower = id.toLowerCase(Locale.ROOT);

        Icon icon = mLower.contains("claude") ? Icon.CLAUDE : Icon.GEMINI;
        String name = id;
        String desc = "Google Antigravity";

        if (mLower.startsWith("gemini-3.8-flash")) {
            if (mLower.contains("high")) {
                name = "Gemini 3.8 Flash (High)";
                desc = "Extended Thinking · High Effort";
            } else if (mLower.contains("medium")) {
                name = "Gemini 3.8 Flash (Med)";
                desc = "Thinking · Balanced Reasoning";
            } else if (mLower.contains("low")) {
                name = "Gemini 3.8 Flash (Low)";
                desc = "Ultra Low Latency · Fast Reasoning";
            } else {
                name = "Gemini 3.8 Flash";
                desc = "Thinking · Adaptable Reasoning";
            }
        } else if (mLower.startsWith("gemini-3.7-flash")) {
            if (mLower.contains("high")) {
                name = "Gemini 3.7 Flash (High)";
                desc = "Extended Thinking · High Effort";
            } else if (mLower.contains("medium")) {
                name = "Gemini 3.7 Flash (Med)";
                desc = "Thinking · Balanced Reasoning";
            } else if (mLower.contains("low")) {
                name = "Gemini 3.7 Flash (Low)";
                desc = "Low Latency · Fast";
            } else {
                name = "Gemini 3.7 Flash";
                desc = "Thinking · Adaptable Reasoning";
            }
        } else if (mLower.startsWith("gemini-3.6-flash")) {
            if (mLower.contains("high")) {
                name = "Gemini 3.6 Flash (High)";
                desc = "Thinking · High Effort";
            } else if (mLower.contains("medium")) {
                name = "Gemini 3.6 Flash (Med)";
                desc = "Thinking · Balanced Reasoning";
            } else if (mLower.contains("low")) {
                name = "Gemini 3.6 Flash (Low)";
                desc = "Low Latency · Fast";
            } else {
                name = "Gemini 3.6 Flash";
                desc = "Thinking · Adaptable Reasoning";
            }
        } else if (mLower.startsWith("gemini-3.5-flash") || mLower.equals("gemini-3-flash-agent")) {
            name = mLower.contains("agent") ? "Gemini 3.5 Flash (High)" : (mLower.contains("extra-low") ? "Gemini 3.5 Flash (Low)" : "Gemini 3.5 Flash (Med)");
            desc = mLower.contains("agent") ? "Agentic Reasoning · High Effort" : (mLower.contains("extra-low") ? "Low Latency · Low Cost" : "Balanced Reasoning");
        } else if (mLower.startsWith("gemini-3-pro")) {
            if (mLower.contains("high")) {
                name = "Gemini 3 Pro (High)";
                desc = "Deep Reasoning · High Effort";
            } else {
                name = "Gemini 3 Pro (Low)";
                desc = "Pro Reasoning · Low Latency";
            }
        } else if (mLower.startsWith("gemini-3-flash")) {
            name = "Gemini 3 Flash";
            desc = "Fast & Balanced Reasoning";
        } else if (mLower.startsWith("gemini-pro-agent") || mLower.startsWith("gemini-3.1-pro")) {
            name = mLower.contains("agent") ? "Gemini 3.1 Pro (High)" : "Gemini 3.1 Pro (Low)";
            desc = mLower.contains("agent") ? "Deep Analysis · Agentic High Effort" : "Pro Reasoning · Low Latency";
        } else if (mLower.startsWith("gemini-3.1-flash-lite")) {
            name = "Gemini 3.1 Flash Lite";
            desc = "Ultra Lightweight · Instant Response";
        } else if (mLower.equals("gemini-2.5-pro")) {
            name = "Gemini 2.5 Pro";
            desc = "Complex Reasoning & Analysis";
        } else if (mLower.startsWith("gemini-2.5-flash")) {
            name = mLower.contains("thinking") ? "Gemini 2.5 Flash Thinking" : "Gemini 2.5 Flash";
            desc = "Fast & Efficient · High Quota";
        } else if (mLower.startsWith("claude-sonnet-4-6")) {
            name = "Claude Sonnet 4.6";
            desc = "Extended Thinking · Deep Understanding";
        } else if (mLower.startsWith("claude-opus-4-6")) {
            name = "Claude Opus 4.6";
            desc = "Maximum Intelligence · Extended Thinking";
        } else if (mLower.startsWith("claude-3-7-sonnet")) {
            name = "Claude 3.7 Sonnet";
            desc = "Hybrid Reasoning & Poetic Lyrics";
        } else if (mLower.startsWith("claude-3-5-sonnet")) {
            name = "Claude 3.5 Sonnet";
            desc = "Nuanced Lyrics Understanding";
        } else if (mLower.startsWith("gpt-oss")) {
            icon = Icon.SPARKLE;
            name = "GPT-OSS 120B";
            desc = "Open Weights · Medium Effort";
        }

        if (names != null) {
            String server = names.lookup(id);
            if (server != null && !server.trim().isEmpty() && !server.equalsIgnoreCase("undefined")) {
                name = server.trim();
            }
        }

        return new Entry(id, name, desc, icon);
    }
}
