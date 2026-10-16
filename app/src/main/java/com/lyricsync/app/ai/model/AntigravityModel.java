package com.lyricsync.app.ai.model;

import java.util.Objects;

/**
 * Represents an AI model discovered dynamically from Google Antigravity Cloudcode PA.
 *
 * <p>Plain public fields: every reader in this app already reads them directly, so a
 * getter/setter layer would only be a second way to say the same thing.
 */
public class AntigravityModel {

    public String id;
    public String displayName;
    public int maxOutputTokens;
    public boolean supportsThinking;
    public boolean isInternal;

    public AntigravityModel(String id, String displayName, int maxOutputTokens, boolean supportsThinking, boolean isInternal) {
        this.id = id;
        this.displayName = displayName;
        this.maxOutputTokens = maxOutputTokens;
        this.supportsThinking = supportsThinking;
        this.isInternal = isInternal;
    }

    @Override
    public String toString() {
        return displayName != null && !displayName.trim().isEmpty() ? displayName : (id != null ? id : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AntigravityModel that = (AntigravityModel) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
