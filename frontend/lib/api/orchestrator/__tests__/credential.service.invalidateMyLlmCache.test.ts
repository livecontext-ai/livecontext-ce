import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * The wire contract of the self-scoped LLM key cache invalidation.
 *
 * After a user saves, removes or switches their OWN provider key, the next execution
 * must run on it without waiting out the resolver TTL. That is `POST
 * /llm-providers/invalidate-cache/mine?provider=<p>`: self-scoped (no admin role), and
 * distinct from the platform-wide `/llm-providers/invalidate-cache` the admin page uses.
 * Pinned here because the component test mocks the service wholesale.
 */
const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: postMock,
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

import { credentialService } from '../credential.service';

describe('credentialService.invalidateMyLlmCache', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs the self-scoped path with the provider as a query param', async () => {
    postMock.mockResolvedValue(undefined);

    await credentialService.invalidateMyLlmCache('openai');

    expect(postMock).toHaveBeenCalledTimes(1);
    expect(postMock).toHaveBeenCalledWith('/llm-providers/invalidate-cache/mine', {}, { params: { provider: 'openai' } });
  });

  it('never hits the platform-wide admin endpoint', async () => {
    postMock.mockResolvedValue(undefined);

    await credentialService.invalidateMyLlmCache('anthropic');

    const paths = postMock.mock.calls.map((call) => call[0]);
    expect(paths).not.toContain('/llm-providers/invalidate-cache');
  });
});

describe('credentialService.invalidateMyLlmCacheIfLlmKey', () => {
  beforeEach(() => vi.clearAllMocks());

  it('invalidates the provider slot for an llm_<provider> credential', async () => {
    postMock.mockResolvedValue(undefined);

    await credentialService.invalidateMyLlmCacheIfLlmKey('llm_google');

    expect(postMock).toHaveBeenCalledWith('/llm-providers/invalidate-cache/mine', {}, { params: { provider: 'google' } });
  });

  it('is a no-op for any other credential, and for a blank or bare llm_ integration', async () => {
    await credentialService.invalidateMyLlmCacheIfLlmKey('smtp');
    await credentialService.invalidateMyLlmCacheIfLlmKey('llm_');
    await credentialService.invalidateMyLlmCacheIfLlmKey(null);
    await credentialService.invalidateMyLlmCacheIfLlmKey(undefined);

    expect(postMock).not.toHaveBeenCalled();
  });

  it('swallows an invalidation failure: the save/delete/switch that triggered it must not fail', async () => {
    postMock.mockRejectedValue(new Error('agent-service unreachable'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await expect(credentialService.invalidateMyLlmCacheIfLlmKey('llm_openai')).resolves.toBeUndefined();

    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
});
