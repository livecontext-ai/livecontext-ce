/**
 * @vitest-environment jsdom
 *
 * The WIRING of `normalizeOptInGrants`, not the helper.
 *
 * <p>An audit proved the distinction the hard way: reverting the call sites to the raw cast
 * left 101 tests across ten files green, because every one of them either exercised the
 * exported helper directly or mocked this hook wholesale. Two of the three sites are pinned
 * below; the third is documented where it is asserted, rather than claimed. A fix nobody calls is a fix nobody
 * has, and this one was missed on two of its three writers in turn.
 *
 * <p>What it guards: the server keeps an opt-in grant in the shape it was SENT, so a grant
 * saved as a bare boolean comes back as one. Every reader in the UI tests `?.enabled`, so an
 * unnormalised boolean renders the switch OFF on a grant that is ON, and the next save from
 * that screen revokes it.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const mocks = vi.hoisted(() => ({
  getUserChatDefaults: vi.fn(),
  updateUserChatDefaults: vi.fn(),
  getAgent: vi.fn(),
  updateAgent: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: { getAgent: mocks.getAgent, updateAgent: mocks.updateAgent },
}));
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    getUserChatDefaults: mocks.getUserChatDefaults,
    updateUserChatDefaults: mocks.updateUserChatDefaults,
  },
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));

import {
  useChatConfig,
  usePrimeUserChatDefaults,
  consumeDraftChatConfig,
  clearUserDefaultChatConfigCache,
  clearDraftChatConfig,
} from '@/hooks/useChatConfig';

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** The shape the defaults endpoint returns when the grant was saved as a bare boolean. */
const BOOLEAN_SHAPED = { mailbox: true, mailboxAccessMode: 'read', generation: true };

describe('useChatConfig - opt-in grants are normalised wherever the defaults cache is filled', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearUserDefaultChatConfigCache();
    clearDraftChatConfig();
  });

  afterEach(() => {
    clearUserDefaultChatConfigCache();
    clearDraftChatConfig();
  });

  it('hydration: the Settings switch reads ON for a grant stored as a boolean', async () => {
    mocks.getUserChatDefaults.mockResolvedValue({ ...BOOLEAN_SHAPED });

    const { result } = renderHook(() => useChatConfig({ userDefault: true }), { wrapper });

    await waitFor(() => expect(result.current.config.mailbox).toBeDefined());
    // Unnormalised this is `true`, and `config.mailbox?.enabled` is undefined: the switch
    // renders OFF on a granted mailbox, and saving from that screen revokes it.
    expect(result.current.config.mailbox).toEqual({ enabled: true });
    expect(result.current.config.generation).toEqual({ enabled: true });
    expect(result.current.config.mailboxAccessMode).toBe('read');
  });

  it('priming: the cache the CHAT surfaces read is normalised too', async () => {
    mocks.getUserChatDefaults.mockResolvedValue({ ...BOOLEAN_SHAPED });

    renderHook(() => usePrimeUserChatDefaults(), { wrapper });

    // The draft surface reads that cache, and it now has a mailbox row of its own, so this
    // is the writer that feeds the screens where the problem would actually be seen.
    await waitFor(() => {
      expect(consumeDraftChatConfig()?.mailbox).toEqual({ enabled: true });
    });
  });

  /**
   * Deliberately NOT claiming to pin the save-response call site.
   *
   * <p>An audit showed that the version of this test which did claim it was a false positive:
   * reverting that one line left it green, because the cache write is immediately followed by
   * a `setQueryData` that re-runs the hydration effect, which normalises again. The assertion
   * belonged to hydration, not to the save.
   *
   * <p>What is asserted instead is the outcome a user sees, which is what matters: after
   * saving a grant, the config the panel renders from is in the object shape. Normalising the
   * save response itself is belt-and-braces for the case where nothing re-hydrates, and it is
   * honestly unobservable through this hook.
   */
  it('after a save, the rendered config is in the shape every reader expects', async () => {
    mocks.getUserChatDefaults.mockResolvedValue({ temperature: 0.3 });
    mocks.updateUserChatDefaults.mockResolvedValue({ ...BOOLEAN_SHAPED });

    const { result } = renderHook(
      () => useChatConfig({ userDefault: true, debounceMs: 0 }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    result.current.updateConfig({ mailbox: { enabled: true }, mailboxAccessMode: 'read' });

    await waitFor(() => expect(mocks.updateUserChatDefaults).toHaveBeenCalled());
    await waitFor(() => {
      expect(result.current.config.mailbox).toEqual({ enabled: true });
    });
  });

  it('leaves the object shape untouched, so normalising is not a rewrite', async () => {
    mocks.getUserChatDefaults.mockResolvedValue({
      mailbox: { enabled: false },
      generation: { enabled: true, model: 'seedance-2.0-fast' },
    });

    const { result } = renderHook(() => useChatConfig({ userDefault: true }), { wrapper });

    await waitFor(() => expect(result.current.config.generation).toBeDefined());
    expect(result.current.config.mailbox).toEqual({ enabled: false });
    expect(result.current.config.generation).toEqual({
      enabled: true,
      model: 'seedance-2.0-fast',
    });
  });
});
