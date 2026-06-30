/**
 * Conversation title display helpers.
 *
 * The backend persists a placeholder title ("Generating title...") when a
 * conversation is created (ChatStreamInitializer / ConversationHistoryService /
 * MonolithChatController) and only replaces it once the LLM calls the
 * set_conversation_title tool. If that never happens (the user stops the request
 * early, generation fails, or the CE bridge title callback is unavailable) the
 * row would otherwise stay stuck on that placeholder forever.
 *
 * These helpers treat the placeholder as "no real title yet" and fall back to the
 * first user message preview: the first ~100 chars of the first USER message,
 * which the backend populates for EVERY conversation type (general chat, agent,
 * and workflow alike). The user message is persisted before the assistant
 * streams, so the preview is available even when the request is aborted
 * mid-flight, which is exactly the "user stopped early" case.
 */

// Matches the backend creation sentinel in both casings it is actually produced
// with: "Generating Title..." (ChatStreamInitializer / MonolithChatController)
// and "Generating title..." (ConversationHistoryService null-title fallback).
const TITLE_PLACEHOLDER_RE = /^generating title\.\.\.$/i;

/** True when `title` is absent/blank or is the backend "generating" placeholder. */
export function isPlaceholderTitle(title?: string | null): boolean {
  const trimmed = title?.trim();
  return !trimmed || TITLE_PLACEHOLDER_RE.test(trimmed);
}

interface ConversationTitleSource {
  title?: string | null;
  firstMessagePreview?: string | null;
}

/**
 * Best label for a conversation row: the real assigned title if there is one,
 * else the first user message preview, else `fallbackLabel`. Type-agnostic: the
 * preview fallback applies to general chat exactly as it does to agent/workflow
 * conversations, so a row is never stuck on "Generating title..." once the user
 * has sent a first message.
 */
export function conversationDisplayTitle(
  conversation: ConversationTitleSource,
  fallbackLabel: string,
): string {
  if (!isPlaceholderTitle(conversation.title)) {
    return conversation.title!.trim();
  }
  const preview = conversation.firstMessagePreview?.trim();
  if (preview) return preview;
  return fallbackLabel;
}
