import type { StreamingStatus } from './StreamingContext';

/**
 * Client-side stand-in for a conversation id that does not exist yet: a brand-new chat
 * has no id until the send POST returns one. It keys local stream state only - it is
 * never a channel name, because the server publishes on the conversation it minted.
 */
export const PLACEHOLDER_CONVERSATION_PREFIX = 'temp-';

/** Just enough of a stream's state to decide whether it needs a live channel. */
type StreamStatusOnly = { status: StreamingStatus; streamId?: string | null; errorSettled?: boolean };

/**
 * Which conversations need a live WebSocket channel right now: the ones streaming (or
 * just finished, so a terminal snapshot still lands) plus whatever the server reports as
 * active after a reload.
 *
 * A stream that reported an `error` stays on the list too, because an `error` is not always
 * the end of the turn: when a model's execution link fails, the bridge publishes `error` and
 * the turn is retried on the direct API under the SAME stream, whose content and `done` (sent
 * once the reply is saved) arrive afterwards. Dropping the channel there threw the finished
 * reply away. Only an error with a stream behind it counts: a send refused before any stream
 * existed has nothing left to hear. And only until the server confirms the error was the end
 * (`errorSettled`): after that the channel would hold a slot, ask for a snapshot on every
 * resubscribe and cost REST reads on every reconnect, for a turn that is over.
 *
 * Sorted, so the array identity only changes when the SET changes and per-token content
 * mutations cannot churn subscriptions.
 *
 * Placeholder ids are excluded, and that is not cosmetic. The gateway resolves a
 * conversation channel by looking the conversation up in conversation-service, so
 * subscribing to `conversation:temp-1789…` cost an authorization round-trip, a
 * "denied access to channel" warning indistinguishable in the log from a real
 * authorization failure, and a permanent `false` in the session's authorization cache -
 * which is capped, so a long-lived tab that opens many new chats eventually stops caching
 * the channels it may actually read.
 *
 * Lives outside StreamingContext so it can be tested without importing a 2,500-line React
 * module: the type import above is erased at runtime.
 */
export function selectLiveChannelIds(
  streams: Map<string, StreamStatusOnly>,
  serverActiveStreams: Set<string> | string[] | undefined,
): string[] {
  const ids = new Set<string>();
  streams.forEach((s, id) => {
    if (s.status === 'streaming' || s.status === 'completed' || (s.status === 'error' && s.streamId && !s.errorSettled)) ids.add(id);
  });
  serverActiveStreams?.forEach((id: string) => ids.add(id));
  return Array.from(ids).filter((id) => !id.startsWith(PLACEHOLDER_CONVERSATION_PREFIX)).sort();
}
