// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: api }));

import { interfaceService } from '../interface.service';

beforeEach(() => {
  vi.clearAllMocks();
  api.get.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 100 });
});

describe('interface paged-list parameters', () => {
  it('sends excludeType so hidden interface families do not inflate totalCount', async () => {
    await interfaceService.getInterfacesPage({
      size: 100,
      includeTemplates: false,
      excludeType: 'web_search',
    });

    expect(api.get).toHaveBeenCalledWith('/interfaces/paged', {
      params: {
        size: '100',
        excludeType: 'web_search',
        includeTemplates: 'false',
      },
    });
  });
});
