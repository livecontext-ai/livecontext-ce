// @vitest-environment jsdom
import { renderHook, waitFor, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// The hook imports orchestratorApi as its default `api`; stub the module so importing it
// pulls in no real client. Tests always inject their own `api`.
vi.mock('@/lib/api/orchestrator', () => ({ orchestratorApi: {} }));

import { useLinkedAgentLoader } from '../useLinkedAgentLoader';
import type { LinkedAgentApi } from '@/lib/chat/linkedAgent';

const AGENT = { id: 'a1', name: 'Analyst', avatarUrl: 'preset:coral' } as never;

function makeApi(overrides?: Partial<LinkedAgentApi>): {
  api: LinkedAgentApi;
  getAgent: ReturnType<typeof vi.fn>;
  getAgentByConversationId: ReturnType<typeof vi.fn>;
} {
  const getAgent = vi.fn(() => Promise.resolve(AGENT));
  const getAgentByConversationId = vi.fn(() => Promise.resolve(null));
  const api: LinkedAgentApi = { getAgent, getAgentByConversationId, ...overrides };
  return { api, getAgent, getAgentByConversationId };
}

interface Args {
  conversationId?: string | null;
  linkedAgentId?: string | null;
  isAuthenticated?: boolean;
  isReady?: boolean;
}

function setup(initial: Args, api: LinkedAgentApi) {
  const onAgentResolved = vi.fn();
  const onLoadingChange = vi.fn();
  const utils = renderHook(
    (props: Args) =>
      useLinkedAgentLoader({
        conversationId: props.conversationId,
        linkedAgentId: props.linkedAgentId,
        isAuthenticated: props.isAuthenticated ?? true,
        isReady: props.isReady ?? true,
        onAgentResolved,
        onLoadingChange,
        api,
      }),
    { initialProps: initial },
  );
  return { ...utils, onAgentResolved, onLoadingChange };
}

describe('useLinkedAgentLoader', () => {
  afterEach(() => cleanup());

  it('re-fetches by id when the forward link resolves AFTER the first (reverse) load - the regression', async () => {
    // First render: only the conversation id is known (currentConversation/sidebar not yet
    // loaded), so it takes the reverse path and resolves to "no agent" (model selector).
    const { api, getAgent, getAgentByConversationId } = makeApi();
    const { rerender, onAgentResolved } = setup({ conversationId: 'c1', linkedAgentId: null }, api);

    await waitFor(() => expect(getAgentByConversationId).toHaveBeenCalledWith('c1'));
    expect(getAgent).not.toHaveBeenCalled();
    expect(onAgentResolved).toHaveBeenLastCalledWith(null);

    // The forward link becomes known (conversation / sidebar loaded) -> MUST re-fetch by id.
    rerender({ conversationId: 'c1', linkedAgentId: 'a1' });

    await waitFor(() => expect(getAgent).toHaveBeenCalledWith('a1'));
    expect(onAgentResolved).toHaveBeenLastCalledWith(AGENT);
    // The reverse lookup must not run a second time.
    expect(getAgentByConversationId).toHaveBeenCalledTimes(1);
  });

  it('does not re-fetch when the (conversation, agent) pair is unchanged', async () => {
    const { api, getAgent } = makeApi();
    const { rerender, onAgentResolved } = setup({ conversationId: 'c1', linkedAgentId: 'a1' }, api);

    await waitFor(() => expect(getAgent).toHaveBeenCalledTimes(1));
    rerender({ conversationId: 'c1', linkedAgentId: 'a1' });
    rerender({ conversationId: 'c1', linkedAgentId: 'a1' });

    // Give any stray async a tick, then assert still exactly one fetch.
    await Promise.resolve();
    expect(getAgent).toHaveBeenCalledTimes(1);
    expect(onAgentResolved).toHaveBeenCalledWith(AGENT);
  });

  it('clears the resolved agent when navigating to a new chat (no conversation id)', async () => {
    const { api } = makeApi();
    const { rerender, onAgentResolved } = setup({ conversationId: 'c1', linkedAgentId: 'a1' }, api);

    await waitFor(() => expect(onAgentResolved).toHaveBeenCalledWith(AGENT));
    onAgentResolved.mockClear();

    rerender({ conversationId: undefined, linkedAgentId: null });
    expect(onAgentResolved).toHaveBeenCalledWith(null);
  });

  it('maps a getAgent 404 to "no agent" (model selector) without throwing', async () => {
    const err = Object.assign(new Error('not found'), { status: 404 });
    const { api } = makeApi({ getAgent: vi.fn(() => Promise.reject(err)) });
    const { onAgentResolved } = setup({ conversationId: 'c1', linkedAgentId: 'gone' }, api);

    await waitFor(() => expect(onAgentResolved).toHaveBeenCalledWith(null));
  });

  it('does not call the API until auth + token provider are ready', async () => {
    const { api, getAgent } = makeApi();
    const { rerender } = setup(
      { conversationId: 'c1', linkedAgentId: 'a1', isAuthenticated: true, isReady: false },
      api,
    );

    await Promise.resolve();
    expect(getAgent).not.toHaveBeenCalled();

    rerender({ conversationId: 'c1', linkedAgentId: 'a1', isAuthenticated: true, isReady: true });
    await waitFor(() => expect(getAgent).toHaveBeenCalledWith('a1'));
  });

  it('toggles the loading flag around the fetch', async () => {
    const { api } = makeApi();
    const { onLoadingChange } = setup({ conversationId: 'c1', linkedAgentId: 'a1' }, api);

    await waitFor(() => expect(onLoadingChange).toHaveBeenCalledWith(false));
    expect(onLoadingChange).toHaveBeenNthCalledWith(1, true);
  });
});
