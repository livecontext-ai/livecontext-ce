/**
 * @vitest-environment node
 *
 * The snapshot cache is a module-scope Map holding one reader's transcript, and `recallMessages`
 * runs during render, which on the server means during a request. A module-scope cache that
 * accepted server-side writes would be shared by every request the process serves, so one
 * reader's conversation could be seeded into another reader's first paint.
 *
 * Nothing writes it from the server today (the only writer is a passive effect), but that is a
 * property of the current call sites, not of the cache. Each guard is pinned INDEPENDENTLY
 * here: asserting "nothing comes back" would pass with the write guard deleted, since the read
 * guard alone would hide it.
 */

import { describe, it, expect, afterEach } from 'vitest';
import {
  rememberMessages,
  recallMessages,
  forgetMessages,
  resetMessageSnapshots,
} from '@/lib/chat/messageSnapshotCache';
import type { Message } from '@/lib/api/conversationApi';

const message = (id: string): Message => ({
  id,
  conversationId: 'conv-1',
  role: 'user',
  content: id,
  model: 'deepseek-chat',
  timestamp: '2026-09-12T10:00:00Z',
}) as Message;

/**
 * Run `fn` with the read guard satisfied, so what comes back reflects the MAP, not the guard.
 * The environment is node, so `window` is genuinely absent outside this helper.
 */
function readingAsIfClient<T>(fn: () => T): T {
  (globalThis as { window?: unknown }).window = {};
  try {
    return fn();
  } finally {
    delete (globalThis as { window?: unknown }).window;
  }
}

afterEach(() => {
  readingAsIfClient(resetMessageSnapshots);
});

describe('messageSnapshotCache on the server', () => {
  it('writes nothing into the shared map', () => {
    expect(typeof window).toBe('undefined');

    rememberMessages('conv-1', [message('a')], { complete: true });

    // Read with the guard satisfied: if the write had landed, this would find it.
    expect(readingAsIfClient(() => recallMessages('conv-1'))).toBeUndefined();
  });

  it('hands nothing back, even when the map is populated', () => {
    readingAsIfClient(() => rememberMessages('conv-1', [message('a')], { complete: true }));
    expect(readingAsIfClient(() => recallMessages('conv-1'))).toBeDefined();

    // The same read from a server render must refuse.
    expect(recallMessages('conv-1')).toBeUndefined();
  });

  it('does not mutate the map on a server-side forget', () => {
    readingAsIfClient(() => rememberMessages('conv-1', [message('a')], { complete: true }));

    forgetMessages('conv-1');

    expect(readingAsIfClient(() => recallMessages('conv-1'))).toBeDefined();
  });
});
