import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: true }));

const getUserBadges = vi.fn();
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getUserBadges: (ids: Array<string | number>) => getUserBadges(ids) },
}));

import { __resetVerifiedUserCache, cachedBadges, loadBadges, loadVerifiedFlag } from '../verifiedUsers';

async function flushBatch(): Promise<void> {
  await vi.advanceTimersByTimeAsync(20);
}

/**
 * The partner half of the shared badge loader: one request answers both badges, and the two
 * flags are independent of each other.
 */
describe('loadBadges - official-partner flag', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    getUserBadges.mockReset();
    __resetVerifiedUserCache();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('answers both badges for a whole frame with ONE request', async () => {
    getUserBadges.mockResolvedValue({ verified: ['1'], partners: ['2', 1] });

    const pending = [loadBadges('1'), loadBadges('2'), loadBadges('3')];
    await flushBatch();

    expect(getUserBadges).toHaveBeenCalledTimes(1);
    await expect(Promise.all(pending)).resolves.toEqual([
      { verified: true, partner: true },
      { verified: false, partner: true },
      { verified: false, partner: false },
    ]);
  });

  it('a verified lookup made alongside does not trigger a second request for the partner flag', async () => {
    getUserBadges.mockResolvedValue({ verified: [], partners: ['7'] });

    const verified = loadVerifiedFlag('7');
    const badges = loadBadges('7');
    await flushBatch();

    expect(getUserBadges).toHaveBeenCalledTimes(1);
    await expect(verified).resolves.toBe(false);
    await expect(badges).resolves.toEqual({ verified: false, partner: true });
    expect(cachedBadges('7')).toEqual({ verified: false, partner: true });
  });

  it('an older backend that answers no `partners` list reads as no partner, not a failure', async () => {
    getUserBadges.mockResolvedValue({ verified: ['4'] });

    const pending = loadBadges('4');
    await flushBatch();

    await expect(pending).resolves.toEqual({ verified: true, partner: false });
  });

  it('a failed lookup answers no badge and is not cached', async () => {
    getUserBadges.mockRejectedValue(new Error('gateway down'));

    const pending = loadBadges('9');
    await flushBatch();

    await expect(pending).resolves.toEqual({ verified: false, partner: false });
    expect(cachedBadges('9')).toBeUndefined();
  });
});
