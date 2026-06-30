import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ToolDataService, type ToolDataResult } from '../ToolDataService';
import { apiClient } from '@/lib/api';

/**
 * Regression guard for the ~10s workflow-builder load.
 *
 * When the batch endpoint (`POST /workflow-inspector/tools/batch`) fails - e.g.
 * it was 403'd in a read-only share context - `fetchToolsBatch` used to return
 * an empty Map, which made node creation fetch every tool ONE BY ONE
 * sequentially (38 tools → ~10s). The fix fetches all slugs in PARALLEL inside
 * the catch block and populates the batch cache so downstream
 * `getFromBatchCache()` hits and no sequential per-node fetch fires.
 */
describe('ToolDataService.fetchToolsBatch - parallel fallback when the batch endpoint fails', () => {
  beforeEach(() => {
    ToolDataService.clearCache();
    vi.restoreAllMocks();
  });
  afterEach(() => {
    ToolDataService.clearCache();
    vi.restoreAllMocks();
  });

  it('falls back to PARALLEL per-slug fetches (one per unique slug) and populates the batch cache', async () => {
    const postSpy = vi.spyOn(apiClient, 'post').mockRejectedValue(new Error('HTTP 403'));
    const bySlug = vi
      .spyOn(ToolDataService, 'fetchToolDataBySlug')
      .mockImplementation(async (slug: string): Promise<ToolDataResult> => ({
        toolData: { toolSlug: slug } as ToolDataResult['toolData'],
      }));

    const result = await ToolDataService.fetchToolsBatch([
      'gmail/gmail-send',
      'slack/slack-post-message',
      'gmail/gmail-send', // duplicate → must be deduped
    ]);

    // The batch was attempted once, then fell back.
    expect(postSpy).toHaveBeenCalledTimes(1);
    // One fetch per UNIQUE slug (2), not per toolId (3) - dedup preserved.
    expect(bySlug).toHaveBeenCalledTimes(2);
    expect(bySlug).toHaveBeenCalledWith('gmail-send');
    expect(bySlug).toHaveBeenCalledWith('slack-post-message');

    // Cache is populated so node creation hits it instead of firing per-node fetches.
    expect(result.get('gmail-send')).toEqual({ toolData: { toolSlug: 'gmail-send' } });
    expect(ToolDataService.getFromBatchCache('gmail/gmail-send')).toEqual({
      toolData: { toolSlug: 'gmail-send' },
    });
    expect(ToolDataService.getFromBatchCache('slack/slack-post-message')).toBeDefined();
  });

  it('isolates a single failing slug so it does not reject the whole fallback', async () => {
    vi.spyOn(apiClient, 'post').mockRejectedValue(new Error('HTTP 403'));
    vi.spyOn(ToolDataService, 'fetchToolDataBySlug').mockImplementation(async (slug: string) => {
      if (slug === 'bad-tool') throw new Error('boom');
      return { toolData: { toolSlug: slug } as ToolDataResult['toolData'] };
    });

    const result = await ToolDataService.fetchToolsBatch(['good/good-tool', 'bad/bad-tool']);

    expect(result.get('good-tool')).toBeDefined();
    expect(result.has('bad-tool')).toBe(false);
  });
});
