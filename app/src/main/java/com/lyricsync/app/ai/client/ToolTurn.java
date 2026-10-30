package com.lyricsync.app.ai.client;

import com.lyricsync.app.ai.client.AntigravityApiClient.Cancellable;
import com.lyricsync.app.ai.client.AntigravityApiClient.FunctionCall;
import com.lyricsync.app.ai.client.AntigravityApiClient.StreamListener;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.SourceItem;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolCallback;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolResult;
import com.lyricsync.app.ai.tools.ToolArgs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * One request across its tool round-trips, whatever the wire format (Gemini or OpenAI);
 * also the caller's cancel handle. Subclasses send the conversation and record tool turns;
 * running the tools, the loop guard and the listener calls live here once.
 */
abstract class ToolTurn implements Cancellable {
    private final AntigravityApiClient owner;
    private final StreamListener listener;
    private final StringBuilder text = new StringBuilder();
    private final List<String> sources = new ArrayList<>();
    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile Call call;
    private volatile Cancellable tool;
    private int toolRounds;
    private boolean breakBeforeNext;
    int promptTokens;
    int candidateTokens;

    ToolTurn(AntigravityApiClient owner, StreamListener listener) {
        this.owner = owner;
        this.listener = listener;
    }

    /** Sends the conversation so far; the reader ends with {@link #onReply}. */
    abstract void stream();

    /** Appends the model's calls and their results (an entry may be null) to the conversation. */
    abstract void addToolTurn(List<FunctionCall> calls, List<ToolResult> results);

    interface ResponseReader {
        void read(Response response);
    }

    @Override
    public void cancel() {
        cancelled = true;
        Call c = call;
        Cancellable t = tool;
        if (c != null) c.cancel();
        if (t != null) {
            try {
                t.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean isCancelled() {
        Call c = call;
        Cancellable t = tool;
        return cancelled || (c != null && c.isCanceled()) || (t != null && t.isCancelled());
    }

    final void send(Request request, ResponseReader reader) {
        if (isCancelled()) return;
        try {
            Call c = owner.httpClient.newCall(request);
            call = c;
            if (cancelled) c.cancel();
            c.enqueue(new Callback() {
                @Override
                public void onFailure(Call c, IOException e) {
                    fail(e);
                }

                @Override
                public void onResponse(Call c, Response response) {
                    reader.read(response);
                }
            });
        } catch (Exception e) {
            fail(new IOException("Failed to initiate stream request: " + e.getMessage(), e));
        }
    }

    final void delta(String d) {
        if (d == null || d.isEmpty()) return;
        // Text written before a tool call and the answer after it are separate paragraphs.
        if (breakBeforeNext) {
            breakBeforeNext = false;
            if (!Character.isWhitespace(text.charAt(text.length() - 1))) d = "\n\n" + d;
        }
        final String out = d;
        text.append(out);
        notify(() -> listener.onDelta(out));
    }

    final void addSourceLine(String s) {
        addOnce(sources, s);
    }

    /** The reply finished: run its tool calls, or answer when there are none (or the loop guard hits). */
    final void onReply(List<FunctionCall> calls) {
        if (isCancelled()) return;
        if (calls.isEmpty() || toolRounds >= AntigravityApiClient.MAX_TOOL_ITERATIONS) {
            complete();
            return;
        }
        toolRounds++;
        breakBeforeNext = text.length() > 0;
        runTools(calls, 0, new ArrayList<>());
    }

    /** Runs {@code calls} one after another (they may depend on each other), then streams again. */
    private void runTools(List<FunctionCall> calls, int i, List<ToolResult> results) {
        if (isCancelled()) return;
        if (i >= calls.size()) {
            addToolTurn(calls, results);
            stream();
            return;
        }
        FunctionCall fc = calls.get(i);
        String name = fc.getName();
        notify(() -> listener.onToolExecuting(name, LyricTools.describe(name, fc.getArgs())));
        ToolCallback done = result -> {
            if (isCancelled()) return;
            boolean error = result == null || result.isError;
            int count = result == null ? 0
                    : result.sources != null && !result.sources.isEmpty() ? result.sources.size()
                    : error ? 0 : 1;
            notify(() -> listener.onToolCompleted(name, count, error));
            if (result != null && result.sources != null) {
                for (SourceItem s : result.sources) addSource(s);
            }
            results.add(result);
            runTools(calls, i + 1, results);
        };
        try {
            if ("web_search".equals(name)) {
                tool = owner.toolExecutor.executeWebSearch(ToolArgs.str(fc.getArgs(), "query", ""),
                        ToolArgs.intValue(fc.getArgs(), "num_search_results", 5), done);
            } else if ("web_fetch".equals(name)) {
                tool = owner.toolExecutor.executeWebFetch(ToolArgs.str(fc.getArgs(), "url", ""), done);
            } else {
                LyricTools.dispatch(owner.lyricActionDelegate, name, fc.getArgs(), done);
            }
            if (cancelled && tool != null) tool.cancel();
        } catch (Exception e) {
            done.onResult(ToolResult.error("Tool execution error: " + e.getMessage()));
        }
    }

    private void addSource(SourceItem s) {
        if (s == null) return;
        String url = s.url != null ? s.url.trim() : "", title = s.title != null ? s.title.trim() : "";
        if (!url.isEmpty()) addOnce(sources, url);
        if (!title.isEmpty() && !url.isEmpty() && !title.equalsIgnoreCase(url)) addOnce(sources, title + "|" + url);
        else if (!title.isEmpty()) addOnce(sources, title);
    }

    private void complete() {
        if (finished || isCancelled()) return;
        finished = true;
        String full = text.toString();
        notify(() -> listener.onComplete(full, sources, promptTokens, candidateTokens));
    }

    final void fail(Exception e) {
        if (finished || isCancelled()) return; // a cancelled call ends silently
        finished = true;
        notify(() -> listener.onError(e));
    }

    private void notify(Runnable r) {
        if (listener == null) return;
        try {
            r.run();
        } catch (Throwable ignored) {
            // a broken listener must not abort the stream
        }
    }

    private static void addOnce(List<String> list, String s) {
        if (s != null && !s.isEmpty() && !list.contains(s)) list.add(s);
    }
}
