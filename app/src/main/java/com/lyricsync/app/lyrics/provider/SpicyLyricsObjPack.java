package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Decoder for SpicyLyrics SLObjPack serialized payload.
 * Upstream SpicyLyrics (v6.x+) packs API responses into [valuesList, opcodeStream]
 * to compress JSON structures.
 */
public final class SpicyLyricsObjPack {

    private static final int MAX_DEPTH = 512;
    private static final Set<String> FORBIDDEN_KEYS;

    static {
        Set<String> set = new HashSet<>();
        set.add("__proto__");
        set.add("constructor");
        set.add("prototype");
        FORBIDDEN_KEYS = Collections.unmodifiableSet(set);
    }

    private SpicyLyricsObjPack() {}

    /**
     * Unpacks a packed JSON array payload [valuesList, stream] into its original JsonElement.
     *
     * @param packed JsonArray containing [valuesList, stream]
     * @return Decoded JsonElement (usually JsonObject or JsonArray)
     * @throws IllegalArgumentException if payload is invalid
     */
    public static JsonElement unpack(JsonArray packed) {
        if (packed == null || packed.size() != 2) {
            throw new IllegalArgumentException("SLObjPack unpack: Invalid payload structure");
        }

        JsonElement valuesRaw = packed.get(0);
        JsonElement streamRaw = packed.get(1);

        if (!valuesRaw.isJsonArray() || !streamRaw.isJsonArray()) {
            throw new IllegalArgumentException("SLObjPack unpack: Invalid payload structure (elements not arrays)");
        }

        JsonArray valuesList = valuesRaw.getAsJsonArray();
        JsonArray stream = streamRaw.getAsJsonArray();

        Decoder decoder = new Decoder(valuesList, stream);
        return decoder.decodeRoot();
    }

    private static class Decoder {
        private final JsonArray valuesList;
        private final JsonArray stream;
        private final int streamLen;
        private final int valuesLen;
        private int cursor = 0;

        Decoder(JsonArray valuesList, JsonArray stream) {
            this.valuesList = valuesList;
            this.stream = stream;
            this.streamLen = stream.size();
            this.valuesLen = valuesList.size();
        }

        JsonElement decodeRoot() {
            JsonElement result = decode(0);
            if (cursor != streamLen) {
                throw new IllegalArgumentException("SLObjPack unpack: Extra data after decoding: "
                        + cursor + " / " + streamLen);
            }
            return result;
        }

        private int readStreamInt() {
            if (cursor >= streamLen) {
                throw new IllegalArgumentException("SLObjPack unpack: Unexpected end of stream");
            }
            return stream.get(cursor++).getAsInt();
        }

        private JsonElement resolvePointer(int ptr) {
            if (ptr < 0 || ptr >= valuesLen) {
                throw new IllegalArgumentException("SLObjPack unpack: Invalid value pointer " + ptr);
            }
            return valuesList.get(ptr);
        }

        private String readKey() {
            int ptr = readStreamInt();
            JsonElement elem = resolvePointer(ptr);
            if (!elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("SLObjPack unpack: Keys must be strings, got " + elem);
            }
            String key = elem.getAsString();
            if (FORBIDDEN_KEYS.contains(key)) {
                throw new IllegalArgumentException("SLObjPack unpack: Forbidden key: " + key);
            }
            return key;
        }

        private JsonElement decode(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("SLObjPack unpack: Max depth exceeded");
            }

            int op = readStreamInt();
            if (op >= 0) {
                return resolvePointer(op);
            }

            switch (op) {
                case -1: { // Arbitrary Object
                    int numKeys = readStreamInt();
                    String[] keys = new String[numKeys];
                    for (int i = 0; i < numKeys; i++) {
                        keys[i] = readKey();
                    }
                    JsonObject obj = new JsonObject();
                    for (int i = 0; i < numKeys; i++) {
                        obj.add(keys[i], decode(depth + 1));
                    }
                    return obj;
                }
                case -2: { // Standard Array
                    int numItems = readStreamInt();
                    JsonArray arr = new JsonArray(numItems);
                    for (int i = 0; i < numItems; i++) {
                        arr.add(decode(depth + 1));
                    }
                    return arr;
                }
                case -3: { // Schema Array (uniform objects)
                    int numItems = readStreamInt();
                    int numKeys = readStreamInt();
                    String[] keys = new String[numKeys];
                    for (int i = 0; i < numKeys; i++) {
                        keys[i] = readKey();
                    }
                    JsonArray arr = new JsonArray(numItems);
                    for (int i = 0; i < numItems; i++) {
                        JsonObject obj = new JsonObject();
                        for (int k = 0; k < numKeys; k++) {
                            obj.add(keys[k], decode(depth + 1));
                        }
                        arr.add(obj);
                    }
                    return arr;
                }
                case -4: // Empty Array
                    return new JsonArray();

                case -5: { // Single-item Array
                    JsonArray arr = new JsonArray(1);
                    arr.add(decode(depth + 1));
                    return arr;
                }
                case -6: // Empty Object
                    return new JsonObject();

                default:
                    throw new IllegalArgumentException("SLObjPack unpack: Unknown opcode " + op);
            }
        }
    }
}
