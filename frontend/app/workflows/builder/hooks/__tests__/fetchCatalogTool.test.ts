import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/lib/api';
import { fetchCatalogTool } from '../useMcpData';

vi.mock('@/lib/api', () => ({ apiClient: { get: vi.fn() } }));
const get = vi.mocked(apiClient.get);

describe('fetchCatalogTool', () => {
  beforeEach(() => get.mockReset());

  it('uses returned API and tool slugs including collision suffixes to load full metadata', async () => {
    get.mockResolvedValueOnce({ content: [{ slug: 'outlook-real', apiName: 'Microsoft Outlook' }], totalPages: 1 });
    get.mockResolvedValueOnce([{ slug: 'outlook-list-messages-2', name: 'list_messages', toolId: 'uuid' }]);
    get.mockResolvedValueOnce({ parameters: [{ name: '$filter' }], credentials: [{ credentialName: 'outlook' }] });
    const result = await fetchCatalogTool('Microsoft Outlook', 'list_messages');
    expect(get).toHaveBeenNthCalledWith(2, '/workflow-inspector/apis/outlook-real/tools');
    expect(get).toHaveBeenNthCalledWith(3, '/workflow-inspector/tools/outlook-list-messages-2/details');
    expect(result.details.parameters).toEqual([{ name: '$filter' }]);
    expect(result.tool.toolId).toBe('uuid');
  });

  it('fails rather than creating an empty tool when the operation is unavailable', async () => {
    get.mockResolvedValueOnce({ content: [{ slug: 'gmail', apiName: 'Gmail' }], totalPages: 1 });
    get.mockResolvedValueOnce([{ slug: 'gmail-send', name: 'send_message' }]);
    await expect(fetchCatalogTool('Gmail', 'list_messages')).rejects.toThrow('Operation unavailable');
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('fails when the details endpoint does not return a parameter schema', async () => {
    get.mockResolvedValueOnce({ content: [{ slug: 'gmail', apiName: 'Gmail' }], totalPages: 1 });
    get.mockResolvedValueOnce([{ slug: 'gmail-list', name: 'list_messages' }]);
    get.mockResolvedValueOnce(null);
    await expect(fetchCatalogTool('Gmail', 'list_messages')).rejects.toThrow('Tool details unavailable');
  });
});
