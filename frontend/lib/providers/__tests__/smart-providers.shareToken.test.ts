import { describe, expect, it, beforeEach } from 'vitest';
import { initShareApiClient, clearShareApiClient } from '../smart-providers';
import { apiClient } from '@/lib/api';

/**
 * Regression guard for the "stuck on ShareToken" bug.
 *
 * apiClient is a process-wide singleton. Share pages call initShareApiClient()
 * to make it emit `ShareToken …`; when the share context unmounts,
 * clearShareApiClient() MUST restore the pre-share (JWT) provider - otherwise
 * every later request in the authenticated app is treated as a read-only
 * shared request and POSTs get 403.
 */
describe('initShareApiClient / clearShareApiClient - restore pre-share auth', () => {
  beforeEach(() => {
    // Drain any saved state from a prior test, then install a clean JWT provider.
    clearShareApiClient();
    apiClient.setTokenProvider(async () => 'jwt-token');
  });

  it('restores the pre-share JWT provider after the share context ends', async () => {
    initShareApiClient('sl_abc');
    expect(await apiClient.getTokenProvider()!()).toBe('ShareToken sl_abc');

    clearShareApiClient();
    expect(await apiClient.getTokenProvider()!()).toBe('jwt-token');
  });

  it('capture-once guard: a second init never saves a ShareToken as the restore target', async () => {
    initShareApiClient('sl_first');
    initShareApiClient('sl_second'); // must NOT overwrite the saved JWT provider
    expect(await apiClient.getTokenProvider()!()).toBe('ShareToken sl_second');

    clearShareApiClient();
    expect(await apiClient.getTokenProvider()!()).toBe('jwt-token'); // not 'ShareToken sl_first'
  });
});
