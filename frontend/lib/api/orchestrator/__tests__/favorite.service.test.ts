// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn() }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: api }));

import { favoriteService } from '../favorite.service';

beforeEach(() => {
  vi.clearAllMocks();
  api.post.mockResolvedValue(undefined);
  api.delete.mockResolvedValue(undefined);
});

describe('favoriteService', () => {
  it('getFavoriteIds GETs the lowercased-type ids path and returns the ids', async () => {
    api.get.mockResolvedValue({ ids: ['a', 'b'] });
    const ids = await favoriteService.getFavoriteIds('WORKFLOW');
    expect(api.get).toHaveBeenCalledWith('/favorites/workflow/ids');
    expect(ids).toEqual(['a', 'b']);
  });

  it('getFavoriteIds returns [] when the response carries no ids array', async () => {
    api.get.mockResolvedValue({});
    expect(await favoriteService.getFavoriteIds('TABLE')).toEqual([]);
  });

  it('addFavorite POSTs the URL-encoded id under the lowercased type', async () => {
    await favoriteService.addFavorite('INTERFACE', 'if 1/x');
    expect(api.post).toHaveBeenCalledWith('/favorites/interface/if%201%2Fx');
  });

  it('removeFavorite DELETEs the id under the lowercased type', async () => {
    await favoriteService.removeFavorite('AGENT', 'ag-7');
    expect(api.delete).toHaveBeenCalledWith('/favorites/agent/ag-7');
  });

  it('uses the matching path segment for every resource type', async () => {
    api.get.mockResolvedValue({ ids: [] });
    await favoriteService.getFavoriteIds('WORKFLOW');
    await favoriteService.getFavoriteIds('TABLE');
    await favoriteService.getFavoriteIds('INTERFACE');
    await favoriteService.getFavoriteIds('AGENT');
    expect(api.get.mock.calls.map((c) => c[0])).toEqual([
      '/favorites/workflow/ids',
      '/favorites/table/ids',
      '/favorites/interface/ids',
      '/favorites/agent/ids',
    ]);
  });
});
