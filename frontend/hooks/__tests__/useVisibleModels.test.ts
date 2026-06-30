// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

// getTokenProvider: fetchModels probes for a token (auth'd catalog resolves the
// per-tenant LLM source since fba5e4353); returning null keeps the test on the
// anonymous (skipAuth) path.
vi.mock('@/lib/api', () => ({ apiClient: { get: vi.fn(), getTokenProvider: vi.fn(() => null) } }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: vi.fn() }));

import { apiClient } from '@/lib/api';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { useVisibleModels, clearModelsCache } from '@/hooks/useModels';

const CATALOG = {
  providers: [
    {
      name: 'openai',
      defaultModel: 'gpt-5.4',
      supportsStreaming: true,
      supportsToolCalling: true,
      displayOrder: 1,
      models: [{ id: 'gpt-5.4', name: 'gpt-5.4', provider: 'openai', providerKind: 'cloud', displayOrder: 1 }],
    },
    {
      name: 'codex',
      defaultModel: 'gpt-5.4',
      supportsStreaming: true,
      supportsToolCalling: true,
      displayOrder: 2,
      models: [{ id: 'gpt-5.4', name: 'gpt-5.4', provider: 'codex', providerKind: 'bridge', displayOrder: 2 }],
    },
  ],
  defaultProvider: 'openai',
  defaultModel: 'gpt-5.4',
};

const mockAuth = (isAdmin: boolean | undefined) => {
  if (isAdmin === undefined) {
    (useOptionalAuth as ReturnType<typeof vi.fn>).mockReturnValue(undefined);
  } else {
    (useOptionalAuth as ReturnType<typeof vi.fn>).mockReturnValue({ hasRole: (r: string) => isAdmin && r === 'ADMIN' });
  }
};

describe('useVisibleModels', () => {
  beforeEach(() => {
    clearModelsCache();
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue(CATALOG);
  });
  afterEach(() => vi.clearAllMocks());

  it('admin sees the bridge provider (codex) alongside the cloud one', async () => {
    mockAuth(true);
    const { result } = renderHook(() => useVisibleModels());
    await waitFor(() => expect(result.current.models.length).toBeGreaterThan(0));
    expect(result.current.providers.map(p => p.name).sort()).toEqual(['codex', 'openai']);
    expect(result.current.models.some(m => m.provider === 'codex')).toBe(true);
  });

  it('non-admin does NOT see the bridge provider', async () => {
    mockAuth(false);
    const { result } = renderHook(() => useVisibleModels());
    await waitFor(() => expect(result.current.models.length).toBeGreaterThan(0));
    expect(result.current.providers.map(p => p.name)).toEqual(['openai']);
    expect(result.current.models.some(m => m.provider === 'codex')).toBe(false);
  });

  it('no auth context degrades to the safe non-admin view (bridges hidden)', async () => {
    mockAuth(undefined);
    const { result } = renderHook(() => useVisibleModels());
    await waitFor(() => expect(result.current.models.length).toBeGreaterThan(0));
    expect(result.current.providers.map(p => p.name)).toEqual(['openai']);
  });

  it('deduplicates concurrent model fetches before the cache is warm', async () => {
    mockAuth(true);
    let resolveCatalog!: (value: typeof CATALOG) => void;
    (apiClient.get as ReturnType<typeof vi.fn>).mockReturnValueOnce(
      new Promise<typeof CATALOG>(resolve => {
        resolveCatalog = resolve;
      }),
    );

    const first = renderHook(() => useVisibleModels());
    const second = renderHook(() => useVisibleModels());

    expect(apiClient.get).toHaveBeenCalledTimes(1);
    resolveCatalog(CATALOG);

    await waitFor(() => expect(first.result.current.models.length).toBeGreaterThan(0));
    await waitFor(() => expect(second.result.current.models.length).toBeGreaterThan(0));
    expect(apiClient.get).toHaveBeenCalledTimes(1);
  });
});
