package com.lyricsync.app.overlay;

import android.app.AlertDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.WindowManager;
import java.util.List;

import com.lyricsync.app.R;
import com.lyricsync.app.ai.history.AiChatHistoryManager;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.util.CleanMarkdownFormatter;
import com.lyricsync.app.util.Haptics;

/**
 * Owns the overlay chat's history surface: the history dialog with its session
 * list, the clear-all confirmation, and the new-chat / session-restore flows.
 *
 * First slice of the FloatingOverlayService split. All chat view state stays in
 * the service (package-private on purpose), so this controller carries no init
 * order of its own — it only runs from a user tap once the overlay exists.
 */
final class OverlayChatHistoryController {

    private final FloatingOverlayService service;

    OverlayChatHistoryController(FloatingOverlayService service) {
        this.service = service;
    }

    void handleAiNewChatOverlay() {
        if (service.activeChatCall != null) {
            try {
                service.activeChatCall.cancel();
            } catch (Exception ignored) {}
            service.activeChatCall = null;
        }
        if (service.activeAiBubbleAnimator != null) {
            service.activeAiBubbleAnimator.stopAll();
            service.activeAiBubbleAnimator = null;
        }
        service.hideToolStatusNow();

        if (service.songChatManager != null) {
            service.songChatManager.startNewSession();
        }
        if (service.aiChatMessages != null) {
            service.aiChatMessages.removeAllViews();
        }
        if (service.aiChatScroll != null) {
            service.aiChatScroll.setVisibility(View.GONE);
        }
        if (service.aiChatEmptyState != null) {
            service.aiChatEmptyState.setVisibility(View.VISIBLE);
        }
        if (service.aiPromptTemplatesContainer != null) {
            service.aiPromptTemplatesContainer.setVisibility(View.VISIBLE);
        }
        if (service.aiChatInput != null) {
            service.aiChatInput.setText("");
        }
        Toast.makeText(service, R.string.ai_new_chat_started, Toast.LENGTH_SHORT).show();
    }

    void restoreChatSessionOverlay(AiChatHistoryManager.ChatSession session) {
        if (session == null || service.songChatManager == null) return;
        if (service.activeChatCall != null) {
            try {
                service.activeChatCall.cancel();
            } catch (Exception ignored) {}
            service.activeChatCall = null;
        }
        if (service.activeAiBubbleAnimator != null) {
            service.activeAiBubbleAnimator.stopAll();
            service.activeAiBubbleAnimator = null;
        }
        service.hideToolStatusNow();

        service.songChatManager.loadSession(session);

        if (service.aiChatMessages != null) {
            service.aiChatMessages.removeAllViews();
            if (service.aiChatScroll != null) {
                service.aiChatScroll.setVisibility(View.VISIBLE);
            }
            if (service.aiChatEmptyState != null) {
                service.aiChatEmptyState.setVisibility(View.GONE);
            }
            if (service.aiPromptTemplatesContainer != null) {
                service.aiPromptTemplatesContainer.setVisibility(View.GONE);
            }

            if (session.messages != null) {
                for (AiChatMessage msg : session.messages) {
                    if (msg == null || msg.content == null || msg.content.trim().isEmpty()) continue;
                    boolean isUser = "user".equalsIgnoreCase(msg.role);
                    if (isUser) {
                        service.addUserChatBubble(msg.content);
                    } else {
                        TextView bubble = service.addAiChatBubble("");
                        CleanMarkdownFormatter.applyMarkdown(bubble, msg.content, false);
                    }
                }
            }

            if (session.webSources != null && !session.webSources.isEmpty()) {
                service.addSourceChipsBelowBubble(session.webSources);
            }

            service.scrollAiChatToBottom();
        }
        Toast.makeText(service, R.string.ai_history_restore, Toast.LENGTH_SHORT).show();
    }

    void showAiHistoryDialogOverlay() {
        if (service.aiChatHistoryManager == null) {
            service.aiChatHistoryManager = new AiChatHistoryManager(service);
        }

        final Context themedContext = new ContextThemeWrapper(service, R.style.Theme_LyricSync);
        View dialogView = LayoutInflater.from(themedContext).inflate(R.layout.dialog_ai_chat_history, null);
        AlertDialog dialog = new AlertDialog.Builder(themedContext)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView btnClearAll = dialogView.findViewById(R.id.btn_history_clear_all);
        View btnClose = dialogView.findViewById(R.id.btn_history_close);
        View emptyView = dialogView.findViewById(R.id.layout_history_empty);
        ScrollView scrollView = dialogView.findViewById(R.id.scroll_history_sessions);
        LinearLayout sessionsContainer = dialogView.findViewById(R.id.container_history_sessions);

        Runnable refreshList = new Runnable() {
            @Override
            public void run() {
                if (sessionsContainer == null) return;
                sessionsContainer.removeAllViews();
                List<AiChatHistoryManager.ChatSession> sessions = service.aiChatHistoryManager.getSessions();
                if (sessions.isEmpty()) {
                    if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
                    if (scrollView != null) scrollView.setVisibility(View.GONE);
                    if (btnClearAll != null) btnClearAll.setVisibility(View.GONE);
                } else {
                    if (emptyView != null) emptyView.setVisibility(View.GONE);
                    if (scrollView != null) scrollView.setVisibility(View.VISIBLE);
                    if (btnClearAll != null) btnClearAll.setVisibility(View.VISIBLE);

                    for (final AiChatHistoryManager.ChatSession session : sessions) {
                        View itemView = LayoutInflater.from(themedContext)
                                .inflate(R.layout.item_ai_history_session, sessionsContainer, false);

                        TextView tvTitle = itemView.findViewById(R.id.tv_history_song_title);
                        TextView tvArtistDate = itemView.findViewById(R.id.tv_history_artist_date);
                        TextView tvSnippet = itemView.findViewById(R.id.tv_history_snippet);
                        TextView tvCount = itemView.findViewById(R.id.tv_history_message_count);
                        ImageView btnDelete = itemView.findViewById(R.id.btn_delete_history_session);

                        String titleText = (session.songTitle != null && !session.songTitle.trim().isEmpty())
                                ? session.songTitle.trim() : service.getString(R.string.ai_insights_title);
                        if (tvTitle != null) tvTitle.setText(titleText);

                        StringBuilder sub = new StringBuilder();
                        if (session.songArtist != null && !session.songArtist.trim().isEmpty()) {
                            sub.append(session.songArtist.trim()).append(" · ");
                        }
                        sub.append(session.getFormattedDate());
                        if (tvArtistDate != null) tvArtistDate.setText(sub.toString());

                        String snip = session.getSnippet();
                        if (tvSnippet != null) {
                            if (!snip.isEmpty()) {
                                tvSnippet.setText(snip);
                                tvSnippet.setVisibility(View.VISIBLE);
                            } else {
                                tvSnippet.setVisibility(View.GONE);
                            }
                        }

                        int count = session.getMessageCount();
                        if (tvCount != null) {
                            tvCount.setText(service.getString(R.string.ai_history_session_messages, count));
                        }

                        itemView.setOnClickListener(v -> {
                            Haptics.tick(v);
                            dialog.dismiss();
                            restoreChatSessionOverlay(session);
                        });

                        if (btnDelete != null) {
                            btnDelete.setOnClickListener(v -> {
                                Haptics.tick(v);
                                service.aiChatHistoryManager.deleteSession(session.id);
                                Toast.makeText(service, R.string.ai_history_deleted_one, Toast.LENGTH_SHORT).show();
                                run();
                            });
                        }

                        sessionsContainer.addView(itemView);
                    }
                }
            }
        };

        refreshList.run();

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                Haptics.tick(v);
                dialog.dismiss();
            });
        }

        if (btnClearAll != null) {
            btnClearAll.setOnClickListener(v -> {
                Haptics.tick(v);
                AlertDialog confirmDialog = new AlertDialog.Builder(themedContext)
                        .setTitle(R.string.ai_history_clear_confirm_title)
                        .setMessage(R.string.ai_history_clear_confirm_msg)
                        .setPositiveButton(R.string.ai_history_clear_all, (d, which) -> {
                            service.aiChatHistoryManager.clearAllSessions();
                            Toast.makeText(service, R.string.ai_history_clear_success, Toast.LENGTH_SHORT).show();
                            refreshList.run();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                if (confirmDialog.getWindow() != null) {
                    confirmDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
                confirmDialog.show();
            });
        }

        dialog.show();
    }
}
