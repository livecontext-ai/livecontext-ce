import { describe, expect, it, vi } from 'vitest';

// Self-hosted: the badge does not exist there, so no request may leave the browser.
vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: false }));

const getVerifiedUserIds = vi.fn();
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getVerifiedUserIds: (ids: Array<string | number>) => getVerifiedUserIds(ids) },
}));

import { loadVerifiedFlag } from '../verifiedUsers';

describe('loadVerifiedFlag on a self-hosted install', () => {
  it('answers false immediately and never calls the backend', async () => {
    await expect(loadVerifiedFlag('1')).resolves.toBe(false);

    expect(getVerifiedUserIds).not.toHaveBeenCalled();
  });
});
