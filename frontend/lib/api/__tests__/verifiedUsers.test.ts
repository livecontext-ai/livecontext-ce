import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: true }));

const getVerifiedUserIds = vi.fn();
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getVerifiedUserIds: (ids: Array<string | number>) => getVerifiedUserIds(ids) },
}));

import { __resetVerifiedUserCache, cachedVerifiedFlag, loadVerifiedFlag } from '../verifiedUsers';

/** Advance past the batch window and let the in-flight request settle. */
async function flushBatch(): Promise<void> {
  await vi.advanceTimersByTimeAsync(20);
}

describe('loadVerifiedFlag', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    getVerifiedUserIds.mockReset();
    // The cache lives for the life of the page, so each case must start cold or the
    // previous one's answers would make its request disappear.
    __resetVerifiedUserCache();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('answers every id asked for in the same frame with ONE request', async () => {
    // This is the point of the module: a marketplace grid mounts 24 cards in one
    // commit, and 24 requests for a 24-number answer is what it exists to prevent.
    getVerifiedUserIds.mockResolvedValue(['2']);

    const pending = [
      loadVerifiedFlag('1'),
      loadVerifiedFlag('2'),
      loadVerifiedFlag('3'),
    ];
    await flushBatch();

    expect(getVerifiedUserIds).toHaveBeenCalledTimes(1);
    expect(getVerifiedUserIds).toHaveBeenCalledWith(['1', '2', '3']);
    await expect(Promise.all(pending)).resolves.toEqual([false, true, false]);
  });

  it('asks for the same id once, and answers every caller waiting on it', async () => {
    getVerifiedUserIds.mockResolvedValue(['7']);

    const pending = [loadVerifiedFlag('7'), loadVerifiedFlag('7')];
    await flushBatch();

    expect(getVerifiedUserIds).toHaveBeenCalledWith(['7']);
    await expect(Promise.all(pending)).resolves.toEqual([true, true]);
  });

  it('compares as strings, so a numeric id in the answer still matches', async () => {
    // The backend answers JSON numbers; the queue is keyed by string.
    getVerifiedUserIds.mockResolvedValue([7]);

    const pending = loadVerifiedFlag('7');
    await flushBatch();

    await expect(pending).resolves.toBe(true);
  });

  it('splits above the backend batch cap instead of sending one oversized request', async () => {
    getVerifiedUserIds.mockResolvedValue([]);

    const pending = Array.from({ length: 150 }, (_, i) => loadVerifiedFlag(String(i)));
    await flushBatch();

    expect(getVerifiedUserIds).toHaveBeenCalledTimes(2);
    expect(getVerifiedUserIds.mock.calls[0][0]).toHaveLength(100);
    expect(getVerifiedUserIds.mock.calls[1][0]).toHaveLength(50);
    await expect(Promise.all(pending)).resolves.toSatisfy((flags: boolean[]) =>
      flags.every((f) => f === false));
  });

  it('fails closed: a rejected lookup answers "not verified" rather than rejecting', async () => {
    // A badge lookup must never take a page down, and an unresolved promise would
    // hang every card that asked.
    getVerifiedUserIds.mockRejectedValue(new Error('gateway down'));

    const pending = loadVerifiedFlag('1');
    await flushBatch();

    await expect(pending).resolves.toBe(false);
  });

  it('does NOT cache a failure, so the next render asks again', async () => {
    // The cache is permanent for the life of the page. Writing a failed lookup into it
    // would turn one transient 5xx into every badge on the platform disappearing until
    // a full reload, with no error anywhere.
    getVerifiedUserIds.mockRejectedValueOnce(new Error('gateway down'));

    await expect((async () => { const p = loadVerifiedFlag('1'); await flushBatch(); return p; })())
        .resolves.toBe(false);
    expect(cachedVerifiedFlag('1')).toBeUndefined();

    getVerifiedUserIds.mockResolvedValue(['1']);
    await expect((async () => { const p = loadVerifiedFlag('1'); await flushBatch(); return p; })())
        .resolves.toBe(true);
    expect(getVerifiedUserIds).toHaveBeenCalledTimes(2);
  });

  it('a failed chunk does not poison the ids answered by a chunk that succeeded', async () => {
    // Chunks are independent requests. One failing must not blank the other's answers,
    // nor cache them.
    const ids = Array.from({ length: 150 }, (_, i) => String(i));
    getVerifiedUserIds.mockImplementation((chunk: string[]) =>
      chunk.length === 100 ? Promise.resolve(['1']) : Promise.reject(new Error('gateway down')));

    const pending = ids.map((id) => loadVerifiedFlag(id));
    await flushBatch();
    await Promise.all(pending);

    // From the chunk that answered: cached, both true and false.
    expect(cachedVerifiedFlag('1')).toBe(true);
    expect(cachedVerifiedFlag('2')).toBe(false);
    // From the chunk that failed: not cached at all.
    expect(cachedVerifiedFlag('120')).toBeUndefined();
  });

  it('a second batch after the first has flushed issues its own request', async () => {
    getVerifiedUserIds.mockResolvedValue([]);

    const first = loadVerifiedFlag('1');
    await flushBatch();
    const second = loadVerifiedFlag('2');
    await flushBatch();

    expect(getVerifiedUserIds).toHaveBeenCalledTimes(2);
    expect(getVerifiedUserIds.mock.calls[1][0]).toEqual(['2']);
    await expect(Promise.all([first, second])).resolves.toEqual([false, false]);
  });

  it('never asks twice for the same user: the answer is cached for the page', async () => {
    // A card scrolled out and back, or a name that appears on two rows, must not
    // become a second request.
    getVerifiedUserIds.mockResolvedValue(['1']);

    const first = loadVerifiedFlag('1');
    await flushBatch();
    const again = loadVerifiedFlag('1');
    await flushBatch();

    expect(getVerifiedUserIds).toHaveBeenCalledTimes(1);
    await expect(Promise.all([first, again])).resolves.toEqual([true, true]);
  });

  it('exposes the cached answer synchronously, so a known badge paints on first render', async () => {
    getVerifiedUserIds.mockResolvedValue(['1']);

    expect(cachedVerifiedFlag('1')).toBeUndefined();
    const pending = loadVerifiedFlag('1');
    await flushBatch();
    await pending;

    expect(cachedVerifiedFlag('1')).toBe(true);
    expect(cachedVerifiedFlag('2')).toBeUndefined();
  });

  it('caches a negative answer too, so an unverified name is asked about once', async () => {
    getVerifiedUserIds.mockResolvedValue([]);

    const pending = loadVerifiedFlag('9');
    await flushBatch();
    await pending;

    expect(cachedVerifiedFlag('9')).toBe(false);
    await expect(loadVerifiedFlag('9')).resolves.toBe(false);
    expect(getVerifiedUserIds).toHaveBeenCalledTimes(1);
  });

  it('an empty id answers false without queueing anything', async () => {
    await expect(loadVerifiedFlag('')).resolves.toBe(false);
    await flushBatch();

    expect(getVerifiedUserIds).not.toHaveBeenCalled();
  });
});
