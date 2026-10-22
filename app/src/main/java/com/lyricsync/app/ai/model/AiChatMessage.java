package com.lyricsync.app.ai.model;

import java.util.Objects;

/**
 * Represents a single chat message turn in an Antigravity AI conversation.
 */
public class AiChatMessage {

    public String role;
    public String content;

    public AiChatMessage() {
    }

    public AiChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static AiChatMessage user(String content) {
        return new AiChatMessage("user", content);
    }

    public static AiChatMessage model(String content) {
        return new AiChatMessage("model", content);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AiChatMessage that = (AiChatMessage) o;
        return Objects.equals(role, that.role) &&
                Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content);
    }

    @Override
    public String toString() {
        return "AiChatMessage{" +
                "role='" + role + '\'' +
                ", content='" + content + '\'' +
                '}';
    }
}
