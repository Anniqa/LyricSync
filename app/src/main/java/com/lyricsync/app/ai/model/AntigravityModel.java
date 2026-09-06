package com.lyricsync.app.ai.model;

import java.util.Objects;

/**
 * Represents an AI model discovered dynamically from Google Antigravity Cloudcode PA.
 */
public class AntigravityModel {

    public String id;
    public String displayName;
    public int maxOutputTokens;
    public boolean supportsThinking;
    public boolean isInternal;

    public AntigravityModel() {
    }

    public AntigravityModel(String id, String displayName, int maxOutputTokens, boolean supportsThinking, boolean isInternal) {
        this.id = id;
        this.displayName = displayName;
        this.maxOutputTokens = maxOutputTokens;
        this.supportsThinking = supportsThinking;
        this.isInternal = isInternal;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public boolean isSupportsThinking() {
        return supportsThinking;
    }

    public void setSupportsThinking(boolean supportsThinking) {
        this.supportsThinking = supportsThinking;
    }

    public boolean isInternal() {
        return isInternal;
    }

    public void setInternal(boolean internal) {
        isInternal = internal;
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
