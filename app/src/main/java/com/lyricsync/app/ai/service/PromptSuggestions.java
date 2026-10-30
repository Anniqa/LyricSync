package com.lyricsync.app.ai.service;

import java.util.ArrayList;
import java.util.List;

/**
 * The suggestion cards on the empty chat, picked for the song that is playing.
 *
 * <p>Each card is a real request the in-app AI can carry out with its tools (or a display
 * command the overlay applies directly), so tapping one does something useful instead of
 * starting yet another essay. Cards depend on the lyrics: romaji only for non-Latin
 * scripts, "explain this line" only when something is playing, and so on.
 */
public final class PromptSuggestions {

    /** One card. {@code web}: needs web search, which the overlay switches on for it. */
    public static final class Card {
        public final String id;
        public final String title;
        public final String description;
        public final String prompt;
        public final String icon;
        public final boolean web;

        Card(String id, String title, String description, String prompt, String icon, boolean web) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.prompt = prompt;
            this.icon = icon;
            this.web = web;
        }
    }

    /** What the cards may depend on. */
    public static final class Song {
        public boolean hasLyrics;
        public boolean nonLatin;
        public boolean hasTranslation;
        public boolean playing;
    }

    private PromptSuggestions() {}

    public static List<Card> forSong(String lang, Song s) {
        boolean id = "id".equalsIgnoreCase(lang) || "in".equalsIgnoreCase(lang);
        List<Card> c = new ArrayList<>();
        if (!s.hasLyrics) {
            add(c, id, "story", "ls_ic_person", true,
                    "Cerita di balik lagu", "Latar belakang, penulis, dan fakta rilisnya",
                    "Ceritakan latar belakang lagu ini: siapa penulisnya, kapan dirilis, dan kisah di baliknya. Cari di web dulu.",
                    "The story behind it", "Background, writers and release facts",
                    "Tell me the story behind this song: who wrote it, when it came out and what inspired it. Search the web first.");
            add(c, id, "similar", "ls_ic_queue_music", false,
                    "Lagu yang mirip", "5 rekomendasi dengan vibe serupa",
                    "Rekomendasikan 5 lagu dengan vibe mirip lagu ini, masing-masing dengan satu kalimat alasannya.",
                    "Similar songs", "5 picks with the same vibe",
                    "Recommend 5 songs with a similar vibe to this one, each with a one-line reason.");
            return c;
        }
        if (s.nonLatin) {
            add(c, id, "romaji_meaning", "ic_sparkles", false,
                    "Romaji + arti", "Bacaan Latin per suku kata dan terjemahannya",
                    "Tampilkan romaji dengan arti",
                    "Romaji + meaning", "Syllable-synced Latin reading and translation",
                    "Show romaji with meaning");
            add(c, id, "word_gloss", "ls_ic_school", false,
                    "Arti kata per kata", "Arti Indonesia di atas tiap kata, ikut sinkron",
                    "Tulis arti bahasa Indonesia tiap kata di atas kata itu (word_romaji, kosongkan untuk partikel) untuk semua baris, dan terjemahan baris lengkapnya di bawah.",
                    "Word-by-word meaning", "An English gloss over every word, in sync",
                    "Put the English meaning of each word above it (word_romaji, blank for particles) for every line, and the full line translation below.");
            add(c, id, "latin_only", "ls_ic_mic", false,
                    "Latin saja", "Sembunyikan huruf asli, cocok buat ikut nyanyi",
                    "Tampilkan romaji saja",
                    "Latin only", "Hide the original script to sing along",
                    "Show romaji only");
        } else {
            add(c, id, "sing_along", "ls_ic_mic", false,
                    "Panduan pengucapan", "Ejaan cara baca di atas tiap kata",
                    "Tulis cara pengucapan tiap kata dengan ejaan Indonesia di atas kata itu (word_romaji) untuk semua baris, supaya aku bisa ikut nyanyi.",
                    "Pronunciation guide", "How to say each word, above it",
                    "Write a simple phonetic spelling above each word (word_romaji) for every line so I can sing along.");
        }
        if (!s.hasTranslation) {
            add(c, id, "translate", "ls_ic_translate", false,
                    "Terjemahkan lirik", "Terjemahan natural di bawah tiap baris",
                    "Terjemahkan seluruh lirik ke bahasa Indonesia dan tampilkan di bawah tiap baris.",
                    "Translate the lyrics", "A natural translation under each line",
                    "Translate the whole song into English and show it under each line.");
        }
        if (s.playing) {
            add(c, id, "this_line", "ls_ic_bulb", false,
                    "Jelaskan baris ini", "Arti, nuansa, dan kata kunci baris yang sedang diputar",
                    "Jelaskan baris yang sedang diputar sekarang: artinya, nuansanya, dan kata-kata pentingnya.",
                    "Explain this line", "Meaning, nuance and key words of the current line",
                    "Explain the line that is playing right now: its meaning, nuance and key words.");
        }
        add(c, id, "meaning", "ls_ic_heart", false,
                "Makna lagu", "Tema, emosi, dan baris paling kuat",
                "Apa makna lagu ini? Jelaskan tema dan emosinya, lalu kutip 2-3 baris yang paling kuat.",
                "What it means", "Themes, feelings and the strongest lines",
                "What is this song about? Explain its themes and feelings, and quote the 2-3 strongest lines.");
        add(c, id, "learn", "ls_ic_school", false,
                "Belajar dari lagu", "5 kosakata atau frasa berguna dari liriknya",
                "Ajari aku 5 kosakata atau frasa paling berguna dari lirik ini, dengan arti dan contoh pemakaiannya.",
                "Learn from it", "5 useful words or phrases from the lyrics",
                "Teach me the 5 most useful words or phrases in these lyrics, with meanings and an example each.");
        add(c, id, "story", "ls_ic_person", true,
                "Cerita di balik lagu", "Latar belakang, penulis, dan fakta rilisnya",
                "Ceritakan latar belakang lagu ini: siapa penulisnya, kapan dirilis, dan kisah di baliknya. Cari di web dulu.",
                "The story behind it", "Background, writers and release facts",
                "Tell me the story behind this song: who wrote it, when it came out and what inspired it. Search the web first.");
        add(c, id, "tidy", "ls_ic_edit", false,
                "Rapikan lirik", "Perbaiki typo dan buang baris kredit/iklan",
                "Periksa liriknya: perbaiki typo dan kapitalisasi, dan hapus baris yang bukan lirik (kredit, iklan, info penulis). Sebutkan apa saja yang kamu ubah.",
                "Tidy the lyrics", "Fix typos, drop credit or ad lines",
                "Check the lyrics: fix typos and capitalisation, and delete lines that are not lyrics (credits, ads, writer info). List what you changed.");
        add(c, id, "similar", "ls_ic_queue_music", false,
                "Lagu yang mirip", "5 rekomendasi dengan vibe serupa",
                "Rekomendasikan 5 lagu dengan vibe mirip lagu ini, masing-masing dengan satu kalimat alasannya.",
                "Similar songs", "5 picks with the same vibe",
                "Recommend 5 songs with a similar vibe to this one, each with a one-line reason.");
        return c;
    }

    private static void add(List<Card> c, boolean id, String key, String icon, boolean web,
                            String idTitle, String idDesc, String idPrompt,
                            String enTitle, String enDesc, String enPrompt) {
        c.add(id ? new Card(key, idTitle, idDesc, idPrompt, icon, web)
                : new Card(key, enTitle, enDesc, enPrompt, icon, web));
    }
}
