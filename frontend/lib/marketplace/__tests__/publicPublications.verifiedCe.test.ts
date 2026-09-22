import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('server-only', () => ({}));

// Self-hosted. The badge does not exist there, so the server-rendered marketplace
// pages must not spend a gateway round-trip per render asking about it. The edition is
// resolved at module load, hence a separate file from the main reader suite.
vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: false }));

import { fetchVerifiedPublisherHandles } from '../publicPublications';

describe('fetchVerifiedPublisherHandles on a self-hosted install', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('answers empty and never calls the gateway', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    expect(await fetchVerifiedPublisherHandles(['ada', 'linus'])).toEqual(new Set());
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
