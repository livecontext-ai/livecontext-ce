/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
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
  consumeDraftChatConfig,
  setUserDefaultChatConfigCache,
  clearUserDefaultChatConfigCache,
  clearDraftChatConfig,
} from '@/hooks/useChatConfig';

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('useChatConfig - user-default target + draft precedence', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearUserDefaultChatConfigCache();
    clearDraftChatConfig();
    mocks.getUserChatDefaults.mockResolvedValue({ webSearch: false, temperature: 0.3 });
    mocks.updateUserChatDefaults.mockImplementation(async (c) => c);
    mocks.getAgent.mockResolvedValue({ id: 'a1', name: 'A', toolsConfig: {} });
  });

  afterEach(() => {
    clearUserDefaultChatConfigCache();
    clearDraftChatConfig();
  });

  it('userDefaultTargetHydratesFromGetAndPersistsViaPut', async () => {
    const { result } = renderHook(() => useChatConfig({ userDefault: true, debounceMs: 0 }), { wrapper });

    // Hydrates from GET /v3/chat/defaults.
    await waitFor(() => expect(result.current.config.webSearch).toBe(false));
    expect(result.current.target).toBe('user-default');

    // Editing persists via PUT with the merged config.
    act(() => result.current.updateConfig({ webSearch: true }));
    await waitFor(() =>
      expect(mocks.updateUserChatDefaults).toHaveBeenCalledWith(
        expect.objectContaining({ webSearch: true, temperature: 0.3 }),
      ),
    );
  });

  it('draftEditOverridesTheWorkspaceDefaultWhenSeedingANewConversation', async () => {
    // Persisted workspace default = web search OFF.
    setUserDefaultChatConfigCache({ webSearch: false });

    // Draft target (new-chat composer): user flips web search ON for the next conversation.
    const { result } = renderHook(() => useChatConfig({ debounceMs: 0 }), { wrapper });
    expect(result.current.target).toBe('draft');

    act(() => result.current.updateConfig({ webSearch: true }));
    await waitFor(() => expect(result.current.config.webSearch).toBe(true));

    // The new conversation seed = workspace default with the draft edit on top → draft wins.
    const body = consumeDraftChatConfig();
    expect(body).toMatchObject({ webSearch: true });
  });

  it('agentTargetDoesNotTouchTheUserDefaultsEndpoint', async () => {
    const { result } = renderHook(() => useChatConfig({ agentId: 'a1' }), { wrapper });
    await waitFor(() => expect(result.current.target).toBe('agent'));
    expect(mocks.getUserChatDefaults).not.toHaveBeenCalled();
  });
});
