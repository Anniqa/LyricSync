package com.lyricsync.app.lyrics.translation;

import android.os.Handler;
import android.os.Looper;

import com.lyricsync.app.lyrics.model.LyricsData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GoogleTranslateService {
    public static final String PREF_TRANSLATION_ENABLED = "pref_translation_enabled";
    public static final String PREF_TARGET_LANG = "pref_translation_target_lang";
    public static final String DEFAULT_TARGET_LANG = "auto";

    private static final String API_URL = "https://translate.googleapis.com/translate_a/single";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private final OkHttpClient client;
    private final Handler mainHandler;
    private Call activeCall;

    public interface TranslationCallback {
        void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang);
        void onError(Exception error);
    }

    public GoogleTranslateService() {
        this(new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build(),
                new Handler(Looper.getMainLooper()));
    }

    public GoogleTranslateService(OkHttpClient client, Handler mainHandler) {
        this.client = client;
        this.mainHandler = mainHandler;
    }

    public static List<LyricsData.LyricsLine> filterTranslatableLines(List<LyricsData.LyricsLine> allLines) {
        List<LyricsData.LyricsLine> result = new ArrayList<>();
        if (allLines == null) return result;
        for (LyricsData.LyricsLine line : allLines) {
            if (!line.isInterlude && line.text != null && !line.text.trim().isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    public static String buildQueryText(List<LyricsData.LyricsLine> translatableLines) {
        StringBuilder sb = new StringBuilder();
        for (LyricsData.LyricsLine line : translatableLines) {
            String sanitized = line.text.replace("\r", "").replace("\n", " ").trim();
            sb.append(sanitized).append("\n");
        }
        return sb.toString();
    }

    public static String resolveTargetLang(String prefLang, Locale systemLocale) {
        if (prefLang != null && !prefLang.isEmpty() && !DEFAULT_TARGET_LANG.equalsIgnoreCase(prefLang)) {
            return prefLang.trim().toLowerCase(Locale.ROOT);
        }
        if (systemLocale != null && systemLocale.getLanguage() != null && !systemLocale.getLanguage().isEmpty()) {
            return systemLocale.getLanguage().toLowerCase(Locale.ROOT);
        }
        return "en";
    }

    public synchronized void cancel() {
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
    }

    public synchronized void translateLines(List<LyricsData.LyricsLine> allLines,
                                           String targetLang,
                                           TranslationCallback callback) {
        cancel();

        List<LyricsData.LyricsLine> translatable = filterTranslatableLines(allLines);
        if (translatable.isEmpty()) {
            dispatchSuccess(new GoogleTranslateParser.ParseResult(), targetLang, callback);
            return;
        }

        String finalTargetLang = resolveTargetLang(targetLang, Locale.getDefault());
        String queryText = buildQueryText(translatable);

        RequestBody formBody = new FormBody.Builder()
                .add("client", "dict-chrome-ex")
                .add("sl", "auto")
                .add("tl", finalTargetLang)
                .add("dt", "t")
                .add("q", queryText)
                .build();

        Request request = new Request.Builder()
                .url(API_URL)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .build();

        activeCall = client.newCall(request);
        activeCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) return;
                dispatchError(e, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (call.isCanceled()) return;
                if (!response.isSuccessful() || response.body() == null) {
                    dispatchError(new IOException("Google Translate error: HTTP " + response.code()), callback);
                    return;
                }

                try {
                    String json = response.body().string();
                    GoogleTranslateParser.ParseResult parseResult =
                            GoogleTranslateParser.parse(json, translatable, finalTargetLang);
                    dispatchSuccess(parseResult, finalTargetLang, callback);
                } catch (Exception ex) {
                    dispatchError(ex, callback);
                }
            }
        });
    }

    private void dispatchSuccess(GoogleTranslateParser.ParseResult result,
                                String targetLang,
                                TranslationCallback callback) {
        if (mainHandler == null) {
            callback.onSuccess(result, targetLang);
        } else {
            mainHandler.post(() -> callback.onSuccess(result, targetLang));
        }
    }

    private void dispatchError(Exception error, TranslationCallback callback) {
        if (mainHandler == null) {
            callback.onError(error);
        } else {
            mainHandler.post(() -> callback.onError(error));
        }
    }
}
