# Desain Teknis: Fitur Google Translate Lirik (Spotify-Style) untuk LyricSync

## 1. Ringkasan & Tujuan (Overview & Goals)
Menambahkan kemampuan penerjemahan lirik lagu secara otomatis dan on-demand seperti pada aplikasi Spotify. Ketika lagu berbahasa asing diputar, pengguna dapat mengaktifkan terjemahan melalui panel pengaturan overlay atau pengaturan utama, dan hasil terjemahan akan ditampilkan sebagai subtitle sekunder tepat di bawah baris lirik lagu.

### Sasaran Utama:
1. **Zero Configuration / Tanpa API Key**: Menggunakan endpoint publik Google Translate (`client=dict-chrome-ex`) yang cepat, gratis, andal tanpa CAPTCHA/bot block, dan tidak membutuhkan API key berbayar.
2. **Efisiensi Jaringan (Single Batch HTTP POST)**: Mengirim seluruh teks lirik dalam 1 kali HTTP POST request per lagu dengan `application/x-www-form-urlencoded`, mencegah error `414 URI Too Long` pada lirik panjang.
3. **Pengalaman Pengguna Mirip Spotify & Bebas Jitter Scroll**: Subtitle terjemahan terpasang pada baris lirik dengan tinggi layout statis. Tingkat kecerahan (alpha/color) mengikuti status nyanyian baris (aktif, upcoming, past) tanpa memicu `requestLayout()` yang dapat merusak kehalusan scrolling 120Hz `SpringScroller`.
4. **Caching Lokal Sadar Bahasa (Language-Aware Caching)**: Menyimpan teks terjemahan beserta kode bahasa target (`translationLang`) di cache disk SQLite sehingga pergantian bahasa target otomatis memicu pembaruan tanpa menampilkan terjemahan basi.
5. **Bahasa Fleksibel**: Otomatis mengikuti bahasa sistem perangkat pengguna secara default (`Locale.getDefault().getLanguage()`), dengan opsi memilih bahasa target manual di pengaturan aplikasi.

---

## 2. Arsitektur & Komponen (Architecture & Components)

### 2.1 Diagram Alir Data (Data Flow)
```
[MediaSessionTracker] -> Lagu Berubah -> [LyricsProviderManager]
                                                |
                                        (Lirik Utama Dimuat)
                                                |
                                                v
                                    [FloatingOverlayService]
                                    |                      |
                    (Tampilkan Lirik Asli)         (Cek Pref Terjemahan)
                                                           |
                                                           v
                                            [GoogleTranslateService]
                                                           |
                                            (Batch POST 1x HTTP)
                                                           |
                                                           v
                                              (Update LyricsLine.translation
                                               & Delegasi Simpan ke Cache)
                                                           |
                                                           v
                                            [SyllableHighlighter]
                                        (Highlight teks & subtitle aktif)
```

### 2.2 Komponen Baru: `GoogleTranslateService.java`
- **Lokasi**: `app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateService.java`
- **Konstanta & Konfigurasi**:
  - `API_URL = "https://translate.googleapis.com/translate_a/single"`
  - `PREF_TRANSLATION_ENABLED = "pref_translation_enabled"`
  - `PREF_TARGET_LANG = "pref_translation_target_lang"`
  - `DEFAULT_TARGET_LANG = "auto"`
- **Protokol Jaringan**:
  - Menggunakan **HTTP POST** dengan body `application/x-www-form-urlencoded` (`FormBody` di OkHttp).
  - Parameter: `client=dict-chrome-ex`, `sl=auto`, `tl={targetLang}`, `dt=t`, `q={multilineText}`.
  - Header `User-Agent`: `Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36`.
  - Pembatalan panggilan aktif (`call.cancel()`) jika lagu berganti sebelum terjemahan selesai.
- **Logika Ekstraksi & Rekonstruksi Baris (Anti-Desinkronisasi)**:
  - Google Translate memecah teks berdasarkan batas kalimat (*sentence-splitting*), bukan batas baris. Karena itu, pemetaan indeks array 1-ke-1 langsung dilarang keras.
  - **Algoritma Rekonstruksi yang Benar**:
    1. Filter baris non-interlude dan non-empty ke dalam list referensi `List<LyricsLine> translatableLines`.
    2. Gabungkan teks baris dengan pemisah newline `\n`.
    3. Ekstrak kode bahasa sumber dari respon Google: `jsonRoot.get(2).getAsString()`.
       Jika bahasa sumber sama dengan `targetLang` (misal lagu Indonesia dengan target bahasa Indonesia), hentikan proses dan tandai bahwa terjemahan tidak diperlukan.
    4. Iterasi seluruh elemen pada `jsonRoot.get(0).getAsJsonArray()` dan gabungkan seluruh string hasil terjemahan (`segment[0]`) ke dalam satu `StringBuilder`.
    5. Pecah string gabungan menggunakan delimiter newline: `split("\n", -1)`.
    6. Petakan hasil pecahan secara sekuensial ke `translatableLines.get(i).translation`.
  - **Thread Dispatch**:
    Seluruh callback listener (`onSuccess`, `onError`) di-dispatch ke Main Thread via `new Handler(Looper.getMainLooper())`.

### 2.3 Perubahan Model Data: `LyricsData.java`
- Menambahkan field `translationLang` pada `LyricsData`:
  ```java
  public class LyricsData {
      public Type type;
      public List<LyricsLine> lines = new ArrayList<>();
      public String provider;
      public String translationLang; // Kode bahasa target dari terjemahan saat ini
      ...
  ```
- Menambahkan field `translation` pada `LyricsLine`:
  ```java
  public static class LyricsLine {
      public long startTime;
      public long endTime;
      public String text;
      public String translation; // Teks terjemahan bahasa target
      public List<Word> words = new ArrayList<>();
      public boolean isInterlude;
      public List<LyricsLine> backgroundVocals;
      ...
  }
  ```

### 2.4 Penyimpanan Cache & Enkapsulasi: `LyricsCacheManager.java` & `LyricsProviderManager.java`
- `LyricsData` diserialisasi penuh (termasuk `translation` dan `translationLang`) ke SQLite via Gson.
- **Validasi Cache Bahasa**:
  Saat `getCached(track)` dipanggil, jika `cached.translationLang` tidak cocok dengan bahasa target pengguna saat ini, data lirik utama tetap dipakai, namun field terjemahan dikosongkan dan pembaruan terjemahan latar belakang dipicu.
- **Enkapsulasi**:
  `LyricsProviderManager` menyediakan method delegasi:
  ```java
  public void updateCachedLyrics(TrackInfo track, LyricsData lyrics) {
      executor.execute(() -> cacheManager.cache(track, lyrics));
  }
  ```
  Hal ini menjaga `LyricsCacheManager` tetap terisolasi di dalam layer data provider tanpa diekspos secara sembarangan ke service overlay.

---

## 3. Desain Antarmuka & Rendering (UI & Rendering)

### 3.1 Panel Pengaturan Overlay (`overlay_lyrics.xml` & `FloatingOverlayService.java`)
- **Penambahan Kontrol di `overlay_settings_panel`**:
  - Menambahkan baris opsi horizontal di bawah kontrol font/animasi:
    - Ikon terjemahan & Label: *"Terjemahan Lirik"* / *"Lyrics Translation"*.
    - Switch Material On/Off (`overlay_translate_switch`).
  - Handler perubahan status switch:
    - Menyimpan status ke `SharedPreferences` ("lyricsync") dengan kunci `GoogleTranslateService.PREF_TRANSLATION_ENABLED`.
    - Memanggil `highlighter.setTranslationEnabled(enabled)` untuk menampilkan/menyembunyikan subtitle secara instan.
    - Jika diaktifkan dan lagu saat ini belum memiliki data terjemahan, langsung memicu `GoogleTranslateService.translateLines()`.

### 3.2 Tipografi & Layout Subtitle (`SyllableHighlighter.java`)
- **Pencegahan Jitter Scroll**:
  - `translationView` disematkan ke dalam container vertikal `createPerWordView` dan `createPerLineView`.
  - Jika fitur terjemahan aktif dan `line.translation != null`, `translationView` diset `View.VISIBLE` sejak awal. Tinggi baris lirik dihitung sekali secara deterministik.
  - **Tipografi**:
    - Ukuran Font: `fontSizeSp * 0.72f`.
    - Typeface: `fontMedium`.
    - Margin: Jarak atas `dpToPx(2)` di bawah teks lirik utama.
  - **Sinkronisasi Status Alpha & Warna**:
    - **Active Line**: Warna teks terang `Color.WHITE` (atau aksen album), alpha `1.0f`.
    - **Upcoming Line**: Warna redup `0x82FFFFFF`, alpha `0.42f`.
    - **Past Line**: Warna lirik lampau `0x7FFFFFFF`, alpha `0.82f`.
  - Hal ini menjamin pembacaan subtitle sebelum baris dinyanyikan (*upcoming glance*) dan menjaga `SpringScroller` tetap mulus tanpa `requestLayout()` di tengah frame rendering 120Hz.

### 3.3 Pengaturan Aplikasi Utama (`MainActivity.java` & `activity_main.xml`)
- Menambahkan CardView *"Terjemahan Lirik"*:
  - **Sakelar Utama**: Switch On/Off fitur terjemahan.
  - **Pilihan Bahasa Target**:
    - Spinner/Dialog pilihan bahasa:
      - `Otomatis (Sistem HP)` (Default) -> `Locale.getDefault().getLanguage()`
      - `Bahasa Indonesia (id)`
      - `English (en)`
      - `日本語 (ja)`
      - `한국어 (ko)`
      - `Español (es)`
      - `简体中文 (zh-CN)`
      - `Deutsch (de)`
      - `Français (fr)`
  - Menyimpan pilihan ke `GoogleTranslateService.PREF_TARGET_LANG`.

---

## 4. Penanganan Kasus Khusus & Ketahanan (Edge Cases & Resilience)

1. **Jaringan Terputus / Timeout**:
   - Jika panggilan ke Google Translate gagal, callback `onError` mencatat log tanpa menginterupsi pemutaran lirik utama. Lirik asli tetap berjalan sinkron 100%.
2. **Karakter Khusus & Emoji**:
   - FormBody OkHttp meng-encode string ke UTF-8 secara otomatis. Delimiter `\n` tetap terjaga utuh.
3. **Pergantian Lagu Cepat (User Skip)**:
   - Instance `Call activeCall` dibatalkan segera (`activeCall.cancel()`) saat `fetchLyrics()` lagu berikutnya dipanggil, mencegah respon lama menimpa lirik lagu baru.
4. **Bahasa Lagu Sama dengan Bahasa Target**:
   - Elemen ke-2 respon Google Translate (`jsonRoot.get(2)`) diperiksa. Jika sama dengan bahasa target pengguna, field `translation` tidak diisi sehingga overlay tidak menampilkan baris subtitle yang identik dengan teks asli.
5. **Disparitas Jumlah Baris (Mismatch Protection)**:
   - Jika `translatedArray.length != translatableLines.size()`, algoritma memetakan hingga batas indeks terkecil (`Math.min`), menjamin tidak terjadi `IndexOutOfBoundsException`.

---

## 5. Rencana Pengujian (Testing Strategy)

1. **Unit Testing JVM Murni (`GoogleTranslateParserSelfTest.java`)**:
   - Menguji parsing respon JSON Google Translate yang terpecah menjadi banyak segmen kalimat (*sentence-split*).
   - Menguji penggabungan dan pemisahan baris dengan karakter khusus, tanda kutip, dan baris kosong.
   - Menguji penanganan karakter CJK (Hangul, Kanji) dan aksen Eropa.
   - Menguji pemetaan indeks `translatableLines` yang mengabaikan baris interlude.
2. **Pengujian Integrasi**:
   - Verifikasi delegasi penyimpanan `updateCachedLyrics` di `LyricsProviderManager`.
   - Verifikasi persistensi preferensi toggle dan pilihan bahasa di `MainActivity` dan `FloatingOverlayService`.
   - Verifikasi rendering subtitle di `SyllableHighlighter` dengan mode kata per kata (*syllable*) maupun baris (*line-synced*).
