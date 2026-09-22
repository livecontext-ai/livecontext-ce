// @vitest-environment jsdom
/**
 * A deleted conversation goes from all THREE places that can put it back.
 *
 * Two reports land here. The direct one: a deleted conversation "does not
 * disappear straight away". The indirect one, and the reason the agent kind is
 * handled: deleting an AGENT deletes its conversations server-side, and nothing
 * told the sidebar, so its rows sat there until a hard reload.
 *
 * Three copies of the list exist and each one alone is enough to resurrect a row:
 *   - the local array, which renders;
 *   - the shared context, because useSidebarConversations renders whichever of
 *     the two is LONGER - filtering only the local copy hands the render to the
 *     stale one;
 *   - the React Query page cache, which is `staleTime: Infinity` with an hour of
 *     `gcTime` and `refetchOnMount: false`, so it re-seeds the local array with
 *     the deleted row the next time the sidebar mounts.
 *
 * The server mock here really forgets the row on delete, because it really did.
 * A fixture that kept serving it would make the invalidate-and-refetch look like
 * a resurrection bug, and hide the one case the eviction exists for: a cached
 * page that is NOT on screen, which invalidation marks stale but does not refetch.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const removeSharedConversation = vi.fn();
const getConversations = vi.fn();

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isReady: true, user: { sub: 'u1' } }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedApp: () => ({
    addConversations: vi.fn(),
    removeConversation: removeSharedConversation,
    setHasMore: vi.fn(),
    state: { conversations: [], hasMore: false },
  }),
}));
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: { getConversations: (...a: unknown[]) => getConversations(...a) },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: Object.assign(() => null, { subscribe: () => () => {} }),
}));

import { useConversationList } from '@/hooks/conversation/useConversationList';
import {
  RESOURCE_DELETED_EVENT,
  notifyResourceDeleted,
  type ResourceDeletedDetail,
} from '@/lib/resources/resourceDeleted';
import { queryKeys } from '@/lib/query-client';

const CONV_A = 'c0000000-0000-4000-8000-00000000000a';
const CONV_B = 'c0000000-0000-4000-8000-00000000000b';
const CONV_OLD = 'c0000000-0000-4000-8000-00000000000c';
const AGENT = 'a0000000-0000-4000-8000-000000000001';
const OTHER_AGENT = 'a0000000-0000-4000-8000-000000000002';

const conv = (id: string, agentId?: string) => ({
  id, userId: 'u1', title: id, model: 'm', provider: 'p',
  createdAt: '', updatedAt: '', messageCount: 0, agentId,
});

const PAGE_0 = queryKeys.conversations.page(0, 50);
/** A page the sidebar scrolled past: cached, inactive, never refetched on invalidate. */
const PAGE_1 = queryKeys.conversations.page(1, 50);

let client: QueryClient;
/** What the server would answer now. The delete really removes rows from it. */
let serverRows: ReturnType<typeof conv>[];

function Harness() {
  const { conversations } = useConversationList({ pageSize: 50 });
  return <span data-testid="ids">{conversations.map(c => c.id).join(',')}</span>;
}

const ids = () => screen.getByTestId('ids').textContent;
const pageIds = (key: readonly unknown[]) => {
  const page = client.getQueryData(key) as { content?: { id: string }[] } | undefined;
  return (page?.content ?? []).map(c => c.id).join(',');
};

async function mount() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><Harness /></QueryClientProvider>);
  await waitFor(() => expect(ids()).not.toBe(''));
  // A second page the user already loaded, then scrolled away from.
  client.setQueryData(PAGE_1, { content: [conv(CONV_OLD), conv(CONV_A, AGENT)], last: true });
}

/** Delete on the server, then announce it exactly as the API service does. */
async function deleteAndAnnounce(kind: 'conversation' | 'agent', id: string) {
  serverRows = serverRows.filter(c => (kind === 'conversation' ? c.id !== id : c.agentId !== id));
  await act(async () => { notifyResourceDeleted(kind, id); });
}

beforeEach(() => {
  removeSharedConversation.mockReset();
  serverRows = [conv(CONV_A, AGENT), conv(CONV_B)];
  getConversations.mockReset().mockImplementation(async (page: number) => ({
    content: page === 0 ? serverRows : [],
    last: true,
  }));
});

describe('a deleted conversation', () => {
  it('leaves the rendered list', async () => {
    await mount();
    expect(ids()).toBe(`${CONV_A},${CONV_B}`);

    await deleteAndAnnounce('conversation', CONV_B);

    expect(ids()).toBe(CONV_A);
  });

  it('is evicted from a cached page the sidebar is NOT showing', async () => {
    // The anti-resurrection half. Invalidation only refetches the active page, so
    // without this eviction page 1 keeps the deleted row for its full hour of
    // gcTime and re-seeds the list the next time the user scrolls back to it.
    await mount();
    expect(pageIds(PAGE_1)).toBe(`${CONV_OLD},${CONV_A}`);

    await deleteAndAnnounce('conversation', CONV_A);

    expect(pageIds(PAGE_1)).toBe(CONV_OLD);
  });

  it('refetches, so the page is refilled from the server and not from what was on screen', async () => {
    await mount();
    const before = getConversations.mock.calls.length;

    await deleteAndAnnounce('conversation', CONV_B);

    await waitFor(() => expect(getConversations.mock.calls.length).toBeGreaterThan(before));
    await waitFor(() => expect(pageIds(PAGE_0)).toBe(CONV_A));
  });

  it('is removed from the shared context, which the sidebar prefers when it is longer', async () => {
    await mount();

    await deleteAndAnnounce('conversation', CONV_B);

    expect(removeSharedConversation).toHaveBeenCalledWith(CONV_B);
    expect(removeSharedConversation).toHaveBeenCalledTimes(1);
  });
});

describe('a deleted agent takes its conversations with it', () => {
  it('drops every conversation that belonged to it, and only those', async () => {
    await mount();

    await deleteAndAnnounce('agent', AGENT);

    expect(ids()).toBe(CONV_B);
    expect(pageIds(PAGE_1)).toBe(CONV_OLD);
    expect(removeSharedConversation).toHaveBeenCalledWith(CONV_A);
    expect(removeSharedConversation).not.toHaveBeenCalledWith(CONV_B);
  });

  it('leaves the list alone for an agent none of them belonged to', async () => {
    await mount();

    await deleteAndAnnounce('agent', OTHER_AGENT);

    expect(ids()).toBe(`${CONV_A},${CONV_B}`);
    expect(removeSharedConversation).not.toHaveBeenCalled();
  });
});

describe('the side panel is told about the cascade', () => {
  /** Everything the hook announces while the test runs. */
  let announced: ResourceDeletedDetail[];
  const record = (e: Event) => { announced.push((e as CustomEvent<ResourceDeletedDetail>).detail); };

  beforeEach(() => { announced = []; window.addEventListener(RESOURCE_DELETED_EVENT, record); });
  afterEach(() => window.removeEventListener(RESOURCE_DELETED_EVENT, record));

  it('announces every conversation the agent owned, marked as cascaded', async () => {
    // The panel matches tabs by kind and id and cannot know which conversations
    // belonged to the agent, so a `conversation-<id>` tab would otherwise sit there
    // showing something the server deleted. `cascadedFrom` says this was inferred
    // from the agent's deletion rather than observed, because the server's cascade
    // is best-effort.
    await mount();

    await deleteAndAnnounce('agent', AGENT);

    const cascaded = announced.filter(d => d.kind === 'conversation');
    expect(cascaded).toEqual([{ kind: 'conversation', id: CONV_A, cascadedFrom: { kind: 'agent', id: AGENT } }]);
  });

  it('does not redo its work on the pass its own announcement causes', async () => {
    // The dispatch is synchronous, so the handler re-enters before React has
    // flushed `conversationsRef`. Without the guard the re-entrant pass recomputed
    // the same id and removed it from the shared context a second time.
    await mount();

    await deleteAndAnnounce('agent', AGENT);

    expect(removeSharedConversation).toHaveBeenCalledTimes(1);
    expect(removeSharedConversation).toHaveBeenCalledWith(CONV_A);
  });

  it('announces nothing when the deleted agent owned none of them', async () => {
    await mount();

    await deleteAndAnnounce('agent', OTHER_AGENT);

    expect(announced.filter(d => d.kind === 'conversation')).toEqual([]);
  });

  it('does not re-announce a conversation deleted directly', async () => {
    // Only the agent branch cascades. A conversation deletion that re-emitted
    // itself would be an unbounded loop.
    await mount();

    await deleteAndAnnounce('conversation', CONV_B);

    expect(announced.filter(d => d.kind === 'conversation')).toEqual([{ kind: 'conversation', id: CONV_B }]);
  });
});

describe('unrelated deletions', () => {
  it('ignore a workflow, which shares the broadcast but not this list', async () => {
    await mount();
    const before = getConversations.mock.calls.length;

    await act(async () => { notifyResourceDeleted('workflow', CONV_A); });

    expect(ids()).toBe(`${CONV_A},${CONV_B}`);
    expect(removeSharedConversation).not.toHaveBeenCalled();
    // And it costs nothing: a list that refetched on every unrelated deletion
    // would turn one bulk delete elsewhere into a burst of conversation pages.
    expect(getConversations.mock.calls.length).toBe(before);
  });
});
