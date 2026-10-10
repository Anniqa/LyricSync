package com.lyricsync.app.lyrics.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Hepburn-style kana -> romaji transliteration, so a Japanese title shown by a
 * player ("\u3059\u305a\u3081") can be matched against the romanised form a
 * catalogue stores ("Suzume"). Katakana is folded to hiragana first; all other
 * characters (Latin, kanji, punctuation) are kept as-is.
 *
 * Kanji are not transliterated (that needs a reading dictionary); only kana is.
 */
public final class JapaneseRomanizer {

    private static final Map<Character, String> SINGLE = new HashMap<>();
    private static final Map<String, String> DIGRAPH = new HashMap<>();
    private static final char SOKUON = '\u3063';  // \u3063
    private static final char CHOONPU = '\u30FC'; // \u30FC

    static {
        putSingles(
                "\u3042:a \u3044:i \u3046:u \u3048:e \u304a:o "
              + "\u304b:ka \u304d:ki \u304f:ku \u3051:ke \u3053:ko "
              + "\u3055:sa \u3057:shi \u3059:su \u305b:se \u305d:so "
              + "\u305f:ta \u3061:chi \u3064:tsu \u3066:te \u3068:to "
              + "\u306a:na \u306b:ni \u306c:nu \u306d:ne \u306e:no "
              + "\u306f:ha \u3072:hi \u3075:fu \u3078:he \u307b:ho "
              + "\u307e:ma \u307f:mi \u3080:mu \u3081:me \u3082:mo "
              + "\u3084:ya \u3086:yu \u3088:yo "
              + "\u3089:ra \u308a:ri \u308b:ru \u308c:re \u308d:ro "
              + "\u308f:wa \u3090:i \u3091:e \u3092:o \u3093:n "
              + "\u304c:ga \u304e:gi \u3050:gu \u3052:ge \u3054:go "
              + "\u3056:za \u3058:ji \u305a:zu \u305c:ze \u305e:zo "
              + "\u3060:da \u3062:ji \u3065:zu \u3067:de \u3069:do "
              + "\u3070:ba \u3073:bi \u3076:bu \u3079:be \u307c:bo "
              + "\u3071:pa \u3074:pi \u3077:pu \u307a:pe \u307d:po "
              + "\u3041:a \u3043:i \u3045:u \u3047:e \u3049:o "
              + "\u3083:ya \u3085:yu \u3087:yo \u308e:wa "
              + "\u3094:vu");

        putDigraphs(
                "\u304d\u3083:kya \u304d\u3085:kyu \u304d\u3087:kyo "
              + "\u3057\u3083:sha \u3057\u3085:shu \u3057\u3087:sho \u3057\u3047:she "
              + "\u3061\u3083:cha \u3061\u3085:chu \u3061\u3087:cho \u3061\u3047:che "
              + "\u306b\u3083:nya \u306b\u3085:nyu \u306b\u3087:nyo "
              + "\u3072\u3083:hya \u3072\u3085:hyu \u3072\u3087:hyo "
              + "\u307f\u3083:mya \u307f\u3085:myu \u307f\u3087:myo "
              + "\u308a\u3083:rya \u308a\u3085:ryu \u308a\u3087:ryo "
              + "\u304e\u3083:gya \u304e\u3085:gyu \u304e\u3087:gyo "
              + "\u3058\u3083:ja \u3058\u3085:ju \u3058\u3087:jo \u3058\u3047:je "
              + "\u3073\u3083:bya \u3073\u3085:byu \u3073\u3087:byo "
              + "\u3074\u3083:pya \u3074\u3085:pyu \u3074\u3087:pyo "
              + "\u3075\u3041:fa \u3075\u3043:fi \u3075\u3047:fe \u3075\u3049:fo "
              + "\u3066\u3043:ti \u3067\u3043:di \u3068\u3045:tu \u3069\u3045:du "
              + "\u3046\u3043:wi \u3046\u3047:we \u3046\u3049:wo "
              + "\u3094\u3041:va \u3094\u3043:vi \u3094\u3047:ve \u3094\u3049:vo");
    }

    private JapaneseRomanizer() {}

    private static void putSingles(String packed) {
        for (String entry : packed.trim().split("\\s+")) {
            int c = entry.indexOf(':');
            if (c == 1) SINGLE.put(entry.charAt(0), entry.substring(2));
        }
    }

    private static void putDigraphs(String packed) {
        for (String entry : packed.trim().split("\\s+")) {
            int c = entry.indexOf(':');
            if (c == 2) DIGRAPH.put(entry.substring(0, 2), entry.substring(3));
        }
    }

    public static boolean containsKana(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (kanaAt(text.charAt(i)) != null) return true;
        }
        return false;
    }

    /** Folds katakana to hiragana; returns null when the character is not kana. */
    private static Character kanaAt(char ch) {
        if (ch >= '\u3041' && ch <= '\u3096') return ch;
        if (ch >= '\u30A1' && ch <= '\u30F6') return (char) (ch - 0x60);
        return null;
    }

    public static String romanize(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char raw = text.charAt(i);
            Character folded = kanaAt(raw);

            if (folded == null) {
                if (raw == CHOONPU && out.length() > 0) {
                    char last = out.charAt(out.length() - 1);
                    if ("aeiou".indexOf(Character.toLowerCase(last)) >= 0) out.append(last);
                    continue;
                }
                out.append(raw);
                continue;
            }

            if (folded == SOKUON) {
                Character nextFold = (i + 1 < text.length()) ? kanaAt(text.charAt(i + 1)) : null;
                String next = null;
                if (nextFold != null && i + 2 < text.length()) {
                    Character f3 = kanaAt(text.charAt(i + 2));
                    if (f3 != null) next = DIGRAPH.get("" + nextFold + f3);
                }
                if (next == null && nextFold != null) next = SINGLE.get(nextFold);
                if (next != null && !next.isEmpty() && "aeiou".indexOf(next.charAt(0)) < 0) {
                    out.append(next.charAt(0));
                }
                continue;
            }

            if (i + 1 < text.length()) {
                Character f2 = kanaAt(text.charAt(i + 1));
                if (f2 != null) {
                    String digraph = DIGRAPH.get("" + folded + f2);
                    if (digraph != null) {
                        out.append(digraph);
                        i++;
                        continue;
                    }
                }
            }

            String single = SINGLE.get(folded);
            out.append(single != null ? single : String.valueOf(raw));
        }
        return out.toString();
    }
}
