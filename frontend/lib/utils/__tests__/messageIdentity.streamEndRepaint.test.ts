/**
 * The state half of the end-of-stream repaint.
 *
 * THE BUG: every end-of-stream resync (`onStreamComplete` reloads the thread from the server)
 * committed a brand-new array of brand-new objects, including for the rows that had not
 * changed, so the transcript was re-rendered at the end of every answer. The old guard against
 * that compared only `id` and `content`, which had a second consequence: a reconciliation
 * whose ONLY change was a persisted `toolCalls` / `executionId` / `feedback` was silently
 * discarded and never reached the screen.
 *
 * {@link reconcileMessageIdentity} replaces it. Two properties, both pinned here: an
 * equivalent list comes back as the PREVIOUS array, so the reconciliation commits nothing at
 * all; and when something did change, every untouched message keeps its previous reference,
 * which is what a memoized consumer needs to skip it. Ordering, membership and content always
 * come from the freshly fetched list.
 */

import { describe, it, expect } from 'vitest';
import { reconcileMessageIdentity, areMessagesIdentical } from '@/lib/utils/messageUtils';
import type { Message } from '@/lib/api/conversationApi';

function msg(overrides: Partial<Message> & { id: string }): Message {
  return {
    conversationId: 'conv-1',
    role: 'user',
    content: 'hello',
    model: 'deepseek-chat',
    timestamp: '2026-09-12T10:00:00Z',
    createdAt: '2026-09-12T10:00:00Z',
    ...overrides,
  } as Message;
}

/** What a fetch produces: the same rows, but every object freshly deserialised. */
function refetched(messages: Message[]): Message[] {
  return messages.map(m => JSON.parse(JSON.stringify(m)) as Message);
}

describe('reconcileMessageIdentity', () => {
  it('keeps every existing bubble when the fetch brings a NEW message (the reported repaint)', () => {
    // The everyday end-of-stream shape: the reply gets persisted, so the list grows by one.
    // Pre-fix this replaced all three objects and repainted the whole transcript.
    const prev = [msg({ id: 'a' }), msg({ id: 'b', role: 'assistant', content: 'hi' })];
    const next = [...refetched(prev), msg({ id: 'c', role: 'assistant', content: 'new reply' })];

    const result = reconcileMessageIdentity(prev, next);

    expect(result).toHaveLength(3);
    expect(result[0]).toBe(prev[0]);
    expect(result[1]).toBe(prev[1]);
    expect(result[2].id).toBe('c');
  });

  it('returns the PREVIOUS array when the refetched list is equivalent (commits nothing)', () => {
    const prev = [msg({ id: 'a' }), msg({ id: 'b', role: 'assistant', content: 'hi' })];

    const result = reconcileMessageIdentity(prev, refetched(prev));

    // Same array identity, so setState has nothing to commit and the tree reconciles to itself.
    expect(result).toBe(prev);
  });

  it('keeps the previous reference for every UNCHANGED message when one message changed', () => {
    const prev = [
      msg({ id: 'a' }),
      msg({ id: 'b', role: 'assistant', content: 'partial' }),
      msg({ id: 'c' }),
    ];
    const next = refetched(prev);
    next[1] = { ...next[1], content: 'final answer' };

    const result = reconcileMessageIdentity(prev, next);

    expect(result).not.toBe(prev);
    expect(result[0]).toBe(prev[0]);
    expect(result[2]).toBe(prev[2]);
    // Only the message that actually changed is a new object, which is what lets a memoized
    // consumer re-render that bubble alone.
    expect(result[1]).not.toBe(prev[1]);
    expect(result[1].content).toBe('final answer');
  });

  it('treats server-only fields as a real change (that is WHY the resync exists)', () => {
    // toolCalls / executionId land only once the row is persisted. If the reconciliation
    // considered them noise, the resync would become a no-op and the tool cards would never
    // appear - the opposite failure of the one being fixed.
    const prev = [msg({ id: 'a', role: 'assistant', content: 'done' })];

    const withToolCalls = refetched(prev);
    withToolCalls[0] = { ...withToolCalls[0], toolCalls: '[{"name":"search"}]' };
    expect(reconcileMessageIdentity(prev, withToolCalls)[0]).not.toBe(prev[0]);

    const withExecutionId = refetched(prev);
    withExecutionId[0] = { ...withExecutionId[0], executionId: 'exec-7' };
    expect(reconcileMessageIdentity(prev, withExecutionId)[0]).not.toBe(prev[0]);

    const withFeedback = refetched(prev);
    withFeedback[0] = { ...withFeedback[0], feedback: 1 };
    expect(reconcileMessageIdentity(prev, withFeedback)[0]).not.toBe(prev[0]);
  });

  it('compares attachments by value, not by array identity', () => {
    const attachment = {
      storageId: 's-1',
      type: 'IMAGE' as const,
      fileName: 'shot.png',
      mimeType: 'image/png',
      sizeBytes: 1024,
    };
    const prev = [msg({ id: 'a', attachments: [attachment] })];

    // Same attachment, different array + object instances: must NOT count as a change, or
    // every single refetch would report every message with an attachment as modified.
    expect(reconcileMessageIdentity(prev, refetched(prev))).toBe(prev);

    const changed = refetched(prev);
    changed[0].attachments = [{ ...attachment, storageId: 's-2' }];
    expect(reconcileMessageIdentity(prev, changed)[0]).not.toBe(prev[0]);

    const dropped = refetched(prev);
    delete dropped[0].attachments;
    expect(reconcileMessageIdentity(prev, dropped)[0]).not.toBe(prev[0]);
  });

  it('treats an absent attachments array and an empty one as the same thing', () => {
    // The backend omits the field; an optimistic local message can carry []. Neither renders
    // anything, so the difference must not count as a change.
    const withNone = [msg({ id: 'a' })];
    const withEmpty = [msg({ id: 'a', attachments: [] })];

    expect(reconcileMessageIdentity(withNone, withEmpty)).toBe(withNone);
    expect(reconcileMessageIdentity(withEmpty, withNone)).toBe(withEmpty);
  });

  it('takes membership and ORDER from the fetched list, never from the previous one', () => {
    const prev = [msg({ id: 'a' }), msg({ id: 'b' })];
    // Server truth: 'b' was deleted, 'c' appeared, and 'a' moved to the end.
    const next = [msg({ id: 'c' }), msg({ id: 'a' })];

    const result = reconcileMessageIdentity(prev, next);

    expect(result.map(m => m.id)).toEqual(['c', 'a']);
    // 'a' is unchanged, so it still carries its previous reference even though it moved.
    expect(result[1]).toBe(prev[0]);
  });

  it('adopts the fetched list wholesale when there is nothing on screen yet', () => {
    const next = [msg({ id: 'a' })];
    expect(reconcileMessageIdentity([], next)).toBe(next);
  });

  it('returns the previous array when both lists are empty', () => {
    // An empty conversation reconciles on every stream completion too. Handing back a fresh []
    // would commit a state update and re-render, in the path built to produce none.
    const prev: Message[] = [];
    expect(reconcileMessageIdentity(prev, [])).toBe(prev);
  });


  it('does not reuse a previous message when the id differs (optimistic temp id to persisted id)', () => {
    // The optimistic user bubble is keyed `temp-…` until the server row arrives with a real id.
    // Those are two different rows as far as identity goes; the swap is what makes the message
    // actionable (feedback, retry), so it must go through.
    const prev = [msg({ id: 'temp-123', pendingLocal: true })];
    const next = [msg({ id: 'server-1' })];

    const result = reconcileMessageIdentity(prev, next);

    expect(result[0]).toBe(next[0]);
    expect(result[0].id).toBe('server-1');
  });

  it('passes through a row with no usable id instead of matching it by position', () => {
    // Defensive: an id is how a message is matched, so a row without one can only be adopted.
    const prev = [msg({ id: 'a' })];
    const next = [{ ...msg({ id: 'a' }), id: '' } as Message];

    expect(reconcileMessageIdentity(prev, next)[0]).toBe(next[0]);
  });
});

describe('areMessagesIdentical', () => {
  it('is true for a structurally identical copy and false on any rendered field', () => {
    const a = msg({ id: 'a' });
    expect(areMessagesIdentical(a, { ...a })).toBe(true);
    expect(areMessagesIdentical(a, { ...a, content: 'other' })).toBe(false);
    expect(areMessagesIdentical(a, { ...a, role: 'assistant' })).toBe(false);
    expect(areMessagesIdentical(a, { ...a, model: 'gpt' })).toBe(false);
    expect(areMessagesIdentical(a, { ...a, pendingLocal: true })).toBe(false);
  });

  it('compares EVERY field of Message, so a new one cannot be silently ignored', () => {
    // The field list is asserted exhaustive at compile time (MESSAGE_IDENTITY_FIELDS satisfies
    // the keys of Message). This walks the same ground at runtime: flipping any one field of a
    // fully populated message must be visible to the comparison. A field that stopped being
    // compared would make the end-of-stream resync decide the message did not change, and the
    // new value would never reach the screen.
    const full: Message = {
      id: 'a',
      conversationId: 'conv-1',
      role: 'assistant',
      content: 'done',
      model: 'deepseek-chat',
      timestamp: '2026-09-12T10:00:00Z',
      createdAt: '2026-09-12T10:00:00Z',
      toolCalls: '[]',
      toolCallId: 'tc-1',
      toolName: 'search',
      executionId: 'exec-1',
      agentId: 'agent-1',
      feedback: 1,
      attachments: [],
      pendingLocal: false,
    };

    const mutated: Record<keyof Message, unknown> = {
      id: 'b',
      conversationId: 'conv-2',
      role: 'user',
      content: 'other',
      model: 'gpt',
      timestamp: '2026-09-12T11:00:00Z',
      createdAt: '2026-09-12T11:00:00Z',
      toolCalls: '[{"name":"x"}]',
      toolCallId: 'tc-2',
      toolName: 'fetch',
      executionId: 'exec-2',
      agentId: 'agent-2',
      feedback: -1,
      attachments: [{ storageId: 's', type: 'IMAGE', fileName: 'f', mimeType: 'image/png' }],
      pendingLocal: true,
    };

    for (const field of Object.keys(mutated) as (keyof Message)[]) {
      const changed = { ...full, [field]: mutated[field] } as Message;
      expect(areMessagesIdentical(full, changed), `field "${field}" is not compared`).toBe(false);
    }
  });
});
