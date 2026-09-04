# Desain Teknis: Fitur Google Translate Lirik (Spotify-Style) untuk LyricSync

## 1. Ringkasan & Tujuan (Overview & Goals)
Menambahkan kemampuan penerjemahan lirik lagu secara otomatis dan on-demand seperti pada aplikasi Spotify. Ketika lagu berbahasa asing diputar, pengguna dapat mengaktifkan terjemahan melalui panel pengaturan overlay, dan hasil terjemahan akan ditampilkan sebagai subtitle sekunder tepat di bawah baris lirik yang sedang dinyanyikan.

### Sasaran Utama:
1. **Zero Configuration / Tanpa API Key**: Menggunakan endpoint publik Google Translate (`gtx`) yang cepat, gratis, dan tidak membutuhkan registrasi kartu kredit/API key.
2. **Efisiensi Jaringan (Single Batch Request)**: Mengirim seluruh teks lirik dalam 1 kali HTTP request per lagu, bukan per baris.
3. **Pengalaman Pengguna Mirip Spotify**: Subtitle terjemahan hanya muncul pada **baris yang sedang aktif dinyanyikan**, menjaga overlay tetap bersih dan hemat ruang.
4. **Caching Lokal**: Hasil terjemahan disimpan bersama dengan cache lirik lagu sehingga pemutaran berulang tidak memakan bandwidth internet.
5. **Bahasa Fleksibel**: Otomatis mengikuti bahasa sistem perangkat pengguna secara default, dengan opsi memilih bahasa target manual di pengaturan aplikasi.

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
                                            (Batch Translate 1x HTTP)
                                                           |
                                                           v
                                              (Update LyricsLine.translation
                                               & Simpan ke Cache Disk)
                                                           |
                                                           v
                                            [SyllableHighlighter]
                                        (Tampilkan subtitle pada baris aktif)
```

### 2.2 Komponen Baru: `GoogleTranslateService.java`
- **Lokasi**: `app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateService.java`
- **Fungsi Utama**:
  - `translateLines(List<LyricsLine> lines, String targetLang, TranslationCallback callback)`:
    - Memfilter baris interlude (`isInterlude == true`) dan baris kosong.
    - Menggabungkan teks baris dengan karakter pemisah newline `\n`.
    - Mengirimkan request HTTP GET/POST ke `https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl={targetLang}&dt=t&q={encodedText}`.
    - Mengurai format array JSON dari respon Google Translate:
      Format respon: `[[["Hasil Terjemahan 1", "Teks Asli 1", ...], ["Hasil Terjemahan 2", ...]], ...]`.
    - Memetakan hasil terjemahan kembali secara 1-ke-1 ke `line.translation`.
    - Jika bahasa sumber terdeteksi identik dengan bahasa target, proses menandai bahwa terjemahan tidak diperlukan.

### 2.3 Perubahan Model Data: `LyricsData.java`
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

### 2.4 Penyimpanan Cache: `LyricsCacheManager.java`
- Format JSON cache saat ini secara otomatis mengikutsertakan field publik pada kelas `LyricsLine` via Gson.
- Ketika terjemahan selesai dimuat di latar belakang, `LyricsCacheManager.cache(track, lyrics)` dipanggil untuk memperbarui data cache lokal dengan teks terjemahan.

---

## 3. Desain Antarmuka & Rendering (UI & Rendering)

### 3.1 Panel Pengaturan Overlay (`overlay_lyrics.xml` & `FloatingOverlayService.java`)
- **Penambahan Kontrol di `overlay_settings_panel`**:
  - Baris opsi horizontal berisi:
    - Ikon terjemahan & Label: *"Terjemahan Lirik"* / *"Translate"*.
    - Switch Material On/Off (`overlay_translate_switch`).
  - Handler perubahan status switch:
    - Menyimpan status aktif/nonaktif ke preferensi `AndroidPrefs.PREF_TRANSLATION_ENABLED`.
    - Memperbarui visibilitas subtitle baris aktif secara real-time tanpa me-reload layout overlay.
    - Jika dinyalakan dan lagu saat ini belum memiliki data terjemahan, langsung memicu pengambilan terjemahan di latar belakang.

### 3.2 Tipografi & Layout Subtitle (`SyllableHighlighter.java`)
- Di dalam method `createLineView`:
  - `TextView translationView = new TextView(context);`
  - **Ukuran Font**: `overlayFontSizeSp * 0.75f` (75% dari ukuran lirik utama).
  - **Warna Teks**: Warna sekunder redup (`color/text_secondary` atau turunan aksen album).
  - **Margin**: Jarak atas 2dp di bawah teks lirik utama.
  - **Visibility Awal**: `View.GONE`.
  - Disematkan ke dalam `container` vertikal baris lirik.
- Saat baris menjadi aktif (`highlightActiveLine`):
  - Jika toggle terjemahan aktif dan `line.translation` tersedia:
    - `translationView.setText(line.translation);`
    - `translationView.setVisibility(View.VISIBLE);`
  - Pada baris yang sebelumnya aktif:
    - `translationView.setVisibility(View.GONE);`

### 3.3 Pengaturan Aplikasi Utama (`MainActivity.java` & `activity_main.xml`)
- Menambahkan CardView *"Terjemahan Lirik"*:
  - **Sakelar Utama**: Mengaktifkan/menonaktifkan fitur terjemahan secara umum.
  - **Pilihan Bahasa Target**:
    - Dropdown/Dialog Spinner daftar bahasa:
      - `Otomatis (Bahasa Sistem HP)` (Default)
      - `Bahasa Indonesia (id)`
      - `English (en)`
      - `日本語 (ja)`
      - `한국어 (ko)`
      - `Español (es)`
      - `简体中文 (zh-CN)`
      - Dan bahasa umum lainnya.

---

## 4. Penanganan Kasus Khusus & Error (Edge Cases & Resilience)

1. **Jaringan Terputus / Timeout**:
   - Jika panggilan ke Google Translate gagal, fungsi callback mengabaikan error tanpa menginterupsi pemutaran lirik utama. Lirik asli tetap berjalan sinkron 100%.
2. **Karakter Khusus & Tanda Baca**:
   - Menggunakan UTF-8 encoding untuk query parameter. Delimiter baris menggunakan format yang aman agar pemisahan baris tidak tertukar.
3. **Lirik Berubah Cepat (Pengguna Skip Lagu)**:
   - Request terjemahan sebelumnya dibatalkan (*cancelled*) menggunakan OkHttp tag / Call cancelation saat lagu berganti, menghindari balapan respon (race condition).
4. **Bahasa Asal Sama dengan Bahasa Target**:
   - Jika Google Translate mendeteksi bahwa lagu aslinya sudah dalam bahasa target pengguna, `translation` tidak ditampilkan agar tidak ada baris ganda yang identik.

---

## 5. Rencana Pengujian (Testing Strategy)

1. **Unit Testing JVM (`GoogleTranslateServiceSelfTest.java`)**:
   - Menguji parsing respon JSON array Google Translate.
   - Menguji rekonstruksi pemetaan 1-ke-1 baris lirik dengan berbagai variasi jumlah baris dan baris kosong.
   - Menguji penanganan karakter non-ASCII (Hangul, Kanji, aksen bahasa Spanyol/Prancis).
2. **Pengujian Integrasi**:
   - Memastikan simpan & muat cache `LyricsLine.translation` via `LyricsCacheManager`.
   - Menguji persistensi pengaturan bahasa dan toggle di `AndroidPrefs`.
   - Memverifikasi pergantian baris lirik aktif di `SyllableHighlighter` menampilkan dan menyembunyikan subtitle secara halus.
