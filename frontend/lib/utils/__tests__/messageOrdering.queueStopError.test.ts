/**
 * Reproduction + regression coverage for the chat message ORDERING bug reported when a
 * queued message drains, or a message is relaunched after a stop / error.
 *
 * SYMPTOM (user report): "quand on envoie un message, on stop, ou on a un message en queue
 * et qu'il part, on a parfois de mauvais ordre - le message peut arriver pas dans l'ordre."
 * Concretely the just-sent user message renders ABOVE the previous turn's assistant reply.
 *
 * ROOT CAUSE: the frontend orders messages purely by the `timestamp` STRING
 * (`messageUtils.sortMessagesByTime`, used by every path: loadMessages, addMessageLocal,
 * mergeMessages, updateMessageLocal). But that field mixes two clocks:
 *   - the OPTIMISTIC user message (appended at send time) carries a CLIENT wall-clock
 *     timestamp (`new Date().toISOString()` in doSendMessage), while
 *   - the PRIOR assistant reply carries a SERVER timestamp (Instant.now() backend-side).
 * The backend's authoritative order is `createdAt` (a server @CreationTimestamp, monotonic,
 * single-clock - the chat fetch is `findByConversationIdOrderByCreatedAtDesc`), but the
 * frontend ignores it and re-sorts by `timestamp`. With even a few seconds of client/server
 * clock skew (client behind), the new user message's client timestamp lands BEFORE the prior
 * reply's server timestamp and sorts ahead of it. The `priorReplyPendingCommit` gate in
 * ChatCore shrinks the timing window but cannot erase raw clock skew (it even says so in a
 * NOTE comment) because the SORT is the real ordering authority.
 *
 * These tests model the three reported sequences (queue drain, stop+relaunch, error+relaunch)
 * through the REAL ordering functions. They FAIL on the pre-fix code (timestamp-only sort) and
 * PASS once sorting prefers the server-monotonic `createdAt`, with optimistic (not-yet-persisted,
 * no-createdAt) messages anchored last.
 *
 * The first cases below are skew REGRESSIONS (they fail on the pre-fix timestamp-only sort). The
 * last two ("multiple queued optimistic messages" and "malformed createdAt") are branch-coverage
 * for the post-fix comparator (the `timestamp` fallback when neither side has createdAt, and the
 * NaN guard in createdAtMs); they pass both pre- and post-fix and are not regressions.
 */
import { describe, expect, it } from 'vitest';
import { mergeMessages, sortMessagesByTime } from '../messageUtils';
import type { Message } from '@/lib/api/conversation.types';

type MsgInit = Partial<Message> & Pick<Message, 'id' | 'role' | 'content' | 'timestamp'>;

function msg(init: MsgInit): Message {
  return {
    conversationId: 'c1',
    model: 'deepseek-chat',
    ...init,
  } as Message;
}

/** A backend-persisted message: server `timestamp` AND server-monotonic `createdAt`. */
function persisted(id: string, role: Message['role'], content: string, serverIso: string): Message {
  // Backend serialises createdAt as a TZ-less LocalDateTime ("2026-...T..."); timestamp keeps 'Z'.
  return msg({ id, role, content, timestamp: serverIso, createdAt: serverIso.replace(/Z$/, '') });
}

/** An OPTIMISTIC local user message appended by the composer: CLIENT timestamp, NO createdAt. */
function optimistic(content: string, clientIso: string): Message {
  return msg({ id: `temp-${content}`, role: 'user', content, timestamp: clientIso, pendingLocal: true });
}

const order = (ms: Message[]) => ms.map((m) => m.content);

describe('chat message ordering - queue / stop / error relaunch (clock-skew safe)', () => {
  it('queue drain: a queued user message must stay AFTER the prior reply even when the client clock is behind the server', () => {
    // Prior turn, persisted by the server.
    const u1 = persisted('u1', 'user', 'first question', '2026-01-01T00:00:10.000Z');
    const a1 = persisted('a1', 'assistant', 'answer one', '2026-01-01T00:00:14.000Z');

    // The queued message drains: the composer appends it optimistically. Client clock is ~6s
    // behind the server, so its client timestamp (08) predates the prior reply's server one (14).
    const u2 = optimistic('second question', '2026-01-01T00:00:08.000Z');

    // This is exactly what useMessages.addMessageLocal does: sortMessagesByTime([...prev, local]).
    const result = sortMessagesByTime([u1, a1, u2]);

    expect(order(result)).toEqual(['first question', 'answer one', 'second question']);
  });

  it('stop + relaunch: the relaunched message must stay AFTER the stopped turn partial reply', () => {
    const u1 = persisted('u1', 'user', 'do X', '2026-01-01T00:00:10.000Z');
    // Partial assistant content persisted when the user stopped the stream (server timestamp).
    const a1 = persisted('a1', 'assistant', 'partial answer', '2026-01-01T00:00:12.000Z');

    // User stops, then relaunches a new message; client clock behind => earlier client timestamp.
    const u2 = optimistic('do it differently', '2026-01-01T00:00:09.000Z');

    const result = sortMessagesByTime([u1, a1, u2]);

    expect(order(result)).toEqual(['do X', 'partial answer', 'do it differently']);
  });

  it('error + relaunch: the relaunched message must stay AFTER the persisted error turn', () => {
    const u1 = persisted('u1', 'user', 'try this', '2026-01-01T00:00:10.000Z');
    // persistAttemptAndError stamps the assistant error line with the server Instant.now().
    const a1 = persisted('a1', 'assistant', 'Error: insufficient credits', '2026-01-01T00:00:11.000Z');

    const u2 = optimistic('retry now', '2026-01-01T00:00:08.500Z');

    const result = sortMessagesByTime([u1, a1, u2]);

    expect(order(result)).toEqual(['try this', 'Error: insufficient credits', 'retry now']);
  });

  it('reload after the reply commits: backend messages keep server insert order regardless of timestamp skew', () => {
    // After the turn, loadMessages fetches both rows (each with createdAt). The optimistic temp
    // user message is deduped out by content. mergeMessages must preserve the server order even
    // if the persisted USER row's server timestamp happens to be > the assistant row's (a write
    // ordering quirk that timestamp-only sorting would render backwards).
    const backend = [
      persisted('u-real', 'user', 'second question', '2026-01-01T00:00:20.500Z'), // later string ts
      persisted('a-real', 'assistant', 'answer two', '2026-01-01T00:00:20.100Z'), // earlier string ts, but inserted AFTER? no:
    ];
    // createdAt is the authority: user inserted first, assistant second.
    backend[0] = { ...backend[0], createdAt: '2026-01-01T00:00:20.000' };
    backend[1] = { ...backend[1], createdAt: '2026-01-01T00:00:20.300' };

    const merged = mergeMessages(backend, [], false);

    expect(order(merged)).toEqual(['second question', 'answer two']);
  });

  it('plain timestamp sort still works when every message is server-persisted (no optimistic rows)', () => {
    const a = persisted('a', 'user', 'q1', '2026-01-01T00:00:01.000Z');
    const b = persisted('b', 'assistant', 'r1', '2026-01-01T00:00:02.000Z');
    const c = persisted('c', 'user', 'q2', '2026-01-01T00:00:03.000Z');

    expect(order(sortMessagesByTime([c, a, b]))).toEqual(['q1', 'r1', 'q2']);
  });

  it('breaks an exact createdAt tie by the timestamp string (two rows persisted in the same instant)', () => {
    // A user turn and its assistant tool-call row can land in the SAME createdAt microsecond.
    // The createdAt comparison is then a tie, so `timestamp` decides - the user line (00.000)
    // must precede the assistant line (00.500), not depend on input order.
    const u = msg({ id: 'u', role: 'user', content: 'ask', timestamp: '2026-01-01T00:00:00.000Z', createdAt: '2026-01-01T00:00:00' });
    const a = msg({ id: 'a', role: 'assistant', content: 'reply', timestamp: '2026-01-01T00:00:00.500Z', createdAt: '2026-01-01T00:00:00' });

    expect(order(sortMessagesByTime([a, u]))).toEqual(['ask', 'reply']);
  });

  it('same-turn interleave: an optimistic next message sorts after the just-persisted assistant of the prior turn even if its client clock is behind', () => {
    // The transient window where the prior reply has just committed (createdAt) and the next
    // user message is still optimistic (no createdAt) with an earlier CLIENT timestamp. The
    // optimistic message must stay below the reply, never jump above it.
    const a1 = persisted('a1', 'assistant', 'the reply', '2026-01-01T00:00:14.000Z');
    const u2 = optimistic('next message', '2026-01-01T00:00:13.000Z');

    expect(order(sortMessagesByTime([a1, u2]))).toEqual(['the reply', 'next message']);
    // Order is independent of the input order (the comparator decides, not insertion).
    expect(order(sortMessagesByTime([u2, a1]))).toEqual(['the reply', 'next message']);
  });

  it('a live message_added WS message now carries createdAt and orders by it, not by timestamp', () => {
    // The fix adds createdAt to the message_added WS payload, so a live-delivered row is treated
    // as persisted and ordered by createdAt. Here the two clocks DISAGREE: the WS assistant has an
    // earlier timestamp string but a LATER createdAt. Pre-fix (timestamp-only) it would sort first
    // (wrong); the server-monotonic createdAt is the authority, so it must sort last.
    const wsAssistant = msg({ id: 'ws-a', role: 'assistant', content: 'live reply', timestamp: '2026-01-01T00:00:26.000Z', createdAt: '2026-01-01T00:00:30' });
    const earlierUser = msg({ id: 'u', role: 'user', content: 'earlier question', timestamp: '2026-01-01T00:00:31.000Z', createdAt: '2026-01-01T00:00:25' });

    expect(order(sortMessagesByTime([wsAssistant, earlierUser])))
      .toEqual(['earlier question', 'live reply']);
  });

  it('multiple queued optimistic messages keep their relative order by client timestamp, all after any persisted row', () => {
    // Both queued messages lack createdAt (still optimistic), exercising the FINAL `timestamp`
    // fallback branch of the comparator (the only branch the other cases never reach). The queue
    // can hold several messages; they must drain in send order (earlier client timestamp first)
    // and all sit AFTER the persisted prior turn.
    const a1 = persisted('a1', 'assistant', 'prior reply', '2026-01-01T00:00:10.000Z');
    const q1 = optimistic('queued first', '2026-01-01T00:00:11.000Z');
    const q2 = optimistic('queued second', '2026-01-01T00:00:12.000Z');

    // Shuffled input: the comparator, not insertion order, must produce the result.
    expect(order(sortMessagesByTime([q2, a1, q1])))
      .toEqual(['prior reply', 'queued first', 'queued second']);
  });

  it('a malformed createdAt is ignored (treated as un-persisted), never sorting on a garbage value', () => {
    // createdAtMs guards against a NaN parse: a malformed createdAt yields null, so the row is
    // treated like an optimistic (no-createdAt) message - anchored after a validly-persisted row,
    // and ordered among other null-createdAt rows by `timestamp`. This protects the sort from a
    // single bad backend value silently reordering the whole list.
    const good = persisted('g', 'assistant', 'valid reply', '2026-01-01T00:00:10.000Z');
    const bad = msg({ id: 'b', role: 'user', content: 'malformed createdAt', timestamp: '2026-01-01T00:00:09.000Z', createdAt: 'not-a-real-date' });

    // The validly-persisted row wins regardless of input order; the malformed one anchors last.
    expect(order(sortMessagesByTime([bad, good]))).toEqual(['valid reply', 'malformed createdAt']);
    expect(order(sortMessagesByTime([good, bad]))).toEqual(['valid reply', 'malformed createdAt']);

    // Between two malformed-createdAt rows, the `timestamp` fallback decides (earlier first).
    const bad2 = msg({ id: 'b2', role: 'user', content: 'second malformed', timestamp: '2026-01-01T00:00:20.000Z', createdAt: 'garbage' });
    expect(order(sortMessagesByTime([bad2, bad]))).toEqual(['malformed createdAt', 'second malformed']);
  });
});
