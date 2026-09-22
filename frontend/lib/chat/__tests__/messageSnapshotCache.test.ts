/**
 * @vitest-environment jsdom
 *
 * Tests for the message snapshot cache, the hand-off that makes a REMOUNT of the chat page
 * invisible. The everyday remount is the end-of-stream URL sync: a conversation started from
 * /app/chat gets its own /app/c/{id} route once the reply is persisted, which swaps the page
 * component for a fresh instance whose message list starts empty. Without the snapshot the
 * transcript blanks to a skeleton and repaints, which reads as the page refreshing itself.
 *
 * The two invariants that keep a shared, module-scope cache safe are enforced in the cache and
 * pinned here: only COMPLETE threads are recorded, and nothing crosses a server render.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  rememberMessages,
  recallMessages,
  forgetMessages,
  resetMessageSnapshots,
} from '@/lib/chat/messageSnapshotCache';
import { emitConversationMessagesCleared } from '@/lib/chat/conversationMessagesBus';
import type { Message } from '@/lib/api/conversationApi';

const message = (id: string): Message => ({
  id,
  conversationId: 'conv-1',
  role: 'user',
  content: id,
  model: 'deepseek-chat',
  timestamp: '2026-09-12T10:00:00Z',
}) as Message;

const COMPLETE = { complete: true };

beforeEach(() => {
  resetMessageSnapshots();
});

describe('messageSnapshotCache', () => {
  it('hands the recorded list back for the same conversation', () => {
    const messages = [message('a'), message('b')];
    rememberMessages('conv-1', messages, COMPLETE);

    expect(recallMessages('conv-1')).toBe(messages);
    expect(recallMessages('conv-2')).toBeUndefined();
  });

  it('refuses a thread that still has older pages, and drops what it had', () => {
    // A partial list would be seeded next to a page counter that no longer describes it, and
    // scroll-up would then walk pages the reader already has. Keeping the earlier complete
    // snapshot is no better: the reader has since grown past it.
    rememberMessages('conv-1', [message('a')], COMPLETE);
    rememberMessages('conv-1', [message('a'), message('b')], { complete: false });

    expect(recallMessages('conv-1')).toBeUndefined();
  });

  it('refuses an implausibly long thread rather than truncating it', () => {
    // Half a thread is not a snapshot: seeding it would silently hide messages the reader had.
    const many = Array.from({ length: 60 }, (_, i) => message(`m-${i}`));
    rememberMessages('conv-1', many, COMPLETE);

    expect(recallMessages('conv-1')).toBeUndefined();
  });

  it('accepts exactly the ceiling and refuses one past it', () => {
    const atCeiling = Array.from({ length: 40 }, (_, i) => message(`m-${i}`));
    rememberMessages('conv-1', atCeiling, COMPLETE);
    expect(recallMessages('conv-1')).toHaveLength(40);

    const overCeiling = [...atCeiling, message('m-40')];
    rememberMessages('conv-2', overCeiling, COMPLETE);
    expect(recallMessages('conv-2')).toBeUndefined();
  });

  it('never records an empty list over a real one', () => {
    // A remount seeded with [] is exactly the blank the cache exists to prevent, and
    // clearMessages() legitimately empties the state on its way out of a conversation.
    rememberMessages('conv-1', [message('a')], COMPLETE);
    rememberMessages('conv-1', [], COMPLETE);

    expect(recallMessages('conv-1')).toHaveLength(1);
  });

  it('ignores a missing conversation id on every entry point', () => {
    rememberMessages(undefined, [message('a')], COMPLETE);
    rememberMessages(null, [message('a')], COMPLETE);

    expect(recallMessages(undefined)).toBeUndefined();
    expect(recallMessages(null)).toBeUndefined();
    expect(() => forgetMessages(undefined)).not.toThrow();
    expect(() => forgetMessages(null)).not.toThrow();
  });

  it('keeps only the most recent conversations (no unbounded growth)', () => {
    for (let i = 0; i < 8; i++) {
      rememberMessages(`conv-${i}`, [message(`m-${i}`)], COMPLETE);
    }

    // Cap is 5: the oldest keys were evicted, the newest survive.
    expect(recallMessages('conv-0')).toBeUndefined();
    expect(recallMessages('conv-2')).toBeUndefined();
    expect(recallMessages('conv-3')).toBeDefined();
    expect(recallMessages('conv-7')).toBeDefined();
  });

  it('re-recording a conversation refreshes its recency', () => {
    rememberMessages('conv-old', [message('a')], COMPLETE);
    for (let i = 0; i < 4; i++) rememberMessages(`conv-${i}`, [message(`m-${i}`)], COMPLETE);
    // Touch conv-old so it is no longer the oldest, then push one more conversation in.
    rememberMessages('conv-old', [message('a2')], COMPLETE);
    rememberMessages('conv-new', [message('n')], COMPLETE);

    expect(recallMessages('conv-old')).toBeDefined();
    expect(recallMessages('conv-0')).toBeUndefined();
  });

  it('drops a conversation whose messages were wiped', () => {
    rememberMessages('conv-1', [message('a')], COMPLETE);

    forgetMessages('conv-1');
    expect(recallMessages('conv-1')).toBeUndefined();

    // Same thing through the wipe event the sidebar emits: re-seeding a cleared conversation
    // would resurrect the transcript the user just deleted.
    rememberMessages('conv-1', [message('a')], COMPLETE);
    emitConversationMessagesCleared('conv-1');
    expect(recallMessages('conv-1')).toBeUndefined();
  });

  it('leaves other conversations alone when one is wiped', () => {
    rememberMessages('conv-1', [message('a')], COMPLETE);
    rememberMessages('conv-2', [message('b')], COMPLETE);

    emitConversationMessagesCleared('conv-1');

    expect(recallMessages('conv-1')).toBeUndefined();
    expect(recallMessages('conv-2')).toBeDefined();
  });
});
