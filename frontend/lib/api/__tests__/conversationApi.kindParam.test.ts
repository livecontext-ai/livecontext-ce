// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
  getAuthToken: vi.fn(async () => 'a-token'),
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: api }));

import { conversationApi } from '../conversationApi';

/**
 * The `kind` narrowing as it actually reaches the server.
 *
 * <p><b>Why the sidebar's own test cannot see this.</b> It asserts that the component CALLS
 * `getConversations(0, 50, 'studio')`, which pins the caller and stops one layer above the line
 * that turns that argument into a query param. Delete `if (kind) params.kind = kind` and the
 * component test stays green while the server is asked for the unfiltered listing - and the sidebar
 * then renders every chat the reader has under the heading "Studio".
 *
 * <p>The narrowing has to happen server-side, not over the fetched page: a page of conversations is
 * chosen by RECENCY, so filtering it locally answers "the studio conversations among the most
 * recent ones", which is empty for anyone whose recent activity is chat and looks exactly like
 * having none.
 */
beforeEach(() => {
  vi.clearAllMocks();
  api.getAuthToken.mockResolvedValue('a-token');
  api.get.mockResolvedValue({ content: [], totalElements: 0 });
});

function sentParams(): Record<string, unknown> {
  expect(api.get).toHaveBeenCalledTimes(1);
  return api.get.mock.calls[0][1].params as Record<string, unknown>;
}

describe('conversationApi.getConversations - the kind narrowing', () => {
  it('asks the SERVER for studio conversations', async () => {
    await conversationApi.getConversations(0, 50, 'studio');

    expect(api.get).toHaveBeenCalledWith('/conversations', expect.anything());
    expect(sentParams()).toMatchObject({ kind: 'studio' });
  });

  it('says nothing about kind when the reader wants every conversation', async () => {
    // The other direction, so a client hardcoded to 'studio' cannot pass: it would empty the
    // sidebar for every reader who has never opened the studio.
    await conversationApi.getConversations(0, 50);

    expect(sentParams()).not.toHaveProperty('kind');
  });

  it('still carries the paging alongside the kind', async () => {
    // Guards against a narrowing that replaces the params rather than adding to them: the studio
    // list would then be served with the server's defaults and silently truncate.
    await conversationApi.getConversations(2, 10, 'studio');

    expect(sentParams()).toMatchObject({ page: '2', size: '10', kind: 'studio' });
  });

  it('asks nothing at all when there is no token yet', async () => {
    // A booting session and a signed-out one look identical from here. Asking anyway would put a
    // 401 in the console on every cold load of /app.
    api.getAuthToken.mockResolvedValue(null);

    const page = await conversationApi.getConversations(0, 50, 'studio');

    expect(api.get).not.toHaveBeenCalled();
    expect(page).toMatchObject({ content: [], totalElements: 0 });
  });
});

describe('conversationApi.searchConversations - pagination', () => {
  it('sends the requested title-search page and size', async () => {
    await conversationApi.searchConversations('needle', 'title', 2, 100);

    expect(api.get).toHaveBeenCalledWith('/conversations/search/title', {
      params: { searchTerm: 'needle', page: '2', size: '100' },
    });
  });
});
