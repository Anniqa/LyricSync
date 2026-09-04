package com.lyricsync.app.lyrics.translation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;

import java.util.List;

public final class GoogleTranslateParser {

    public static class ParseResult {
        public String detectedSourceLang = "auto";
        public boolean isSameLanguage = false;
        public int translatedLineCount = 0;
    }

    private GoogleTranslateParser() {}

    public static ParseResult parse(String jsonResponse,
                                    List<LyricsData.LyricsLine> translatableLines,
                                    String targetLang) {
        ParseResult result = new ParseResult();
        if (jsonResponse == null || jsonResponse.trim().isEmpty() || translatableLines == null) {
            return result;
        }

        JsonElement rootElement = JsonParser.parseString(jsonResponse);
        if (!rootElement.isJsonArray()) return result;
        JsonArray rootArray = rootElement.getAsJsonArray();

        // 1. Detect source language (index 2)
        if (rootArray.size() > 2 && !rootArray.get(2).isJsonNull()) {
            result.detectedSourceLang = rootArray.get(2).getAsString();
        }

        if (targetLang != null && !targetLang.isEmpty()
                && !targetLang.equalsIgnoreCase("auto")
                && targetLang.equalsIgnoreCase(result.detectedSourceLang)) {
            result.isSameLanguage = true;
            return result;
        }

        // 2. Concatenate all sentence segments (index 0)
        if (rootArray.size() == 0 || !rootArray.get(0).isJsonArray()) return result;
        JsonArray segments = rootArray.get(0).getAsJsonArray();

        StringBuilder fullText = new StringBuilder();
        for (JsonElement segElem : segments) {
            if (segElem.isJsonArray()) {
                JsonArray seg = segElem.getAsJsonArray();
                if (seg.size() > 0 && !seg.get(0).isJsonNull()) {
                    fullText.append(seg.get(0).getAsString());
                }
            }
        }

        // 3. Split by newline to match original lines
        String[] splitLines = fullText.toString().split("\n", -1);

        // 4. Map sequentially to translatable lines
        int count = Math.min(translatableLines.size(), splitLines.length);
        for (int i = 0; i < count; i++) {
            String translated = splitLines[i].trim();
            translatableLines.get(i).translation = translated.isEmpty() ? null : translated;
        }
        result.translatedLineCount = count;
        return result;
    }
}
