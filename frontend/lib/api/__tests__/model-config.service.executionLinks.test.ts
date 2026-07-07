import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn().mockResolvedValue([]),
    put: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
  },
}));

import { apiClient } from '@/lib/api/api-client';
import { modelConfigService } from '@/lib/api/model-config.service';

describe('modelConfigService execution links', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('deletes via query params so a billed model containing "/" stays addressable', async () => {
    // Regression: the path form (/execution-links/{provider}/{model}/{scope}) broke on
    // OpenRouter-style ids - the raw slash split the path and %2F is rejected by the
    // backend URL firewall, leaving such links undeletable.
    await modelConfigService.deleteExecutionLink('openrouter', 'meta-llama/llama-3.3-70b', 'ALL');

    expect(apiClient.delete).toHaveBeenCalledTimes(1);
    const url = vi.mocked(apiClient.delete).mock.calls[0][0] as string;
    expect(url).toBe(
      '/model-config/execution-links?billedProvider=openrouter&billedModel=meta-llama%2Fllama-3.3-70b&scope=ALL',
    );
    // No path-segment form: the model id must never appear between slashes.
    expect(url).not.toContain('/execution-links/');
  });

  it('defaults the scope to ALL when omitted', async () => {
    await modelConfigService.deleteExecutionLink('anthropic', 'claude-opus-4-8');

    const url = vi.mocked(apiClient.delete).mock.calls[0][0] as string;
    expect(url).toContain('scope=ALL');
    expect(url).toContain('billedProvider=anthropic');
    expect(url).toContain('billedModel=claude-opus-4-8');
  });
});
