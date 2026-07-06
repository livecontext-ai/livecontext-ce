import { describe, expect, it, vi } from 'vitest';
import { toOutputSchema } from '../hooks/useNodeDefinitions';
import { nodeDefinitionService } from '@/lib/api/orchestrator/node-definitions.service';
import { apiClient } from '@/lib/api/api-client';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(),
  },
}));

describe('Node definition schema coherence', () => {
  it('preserves backend output keys recursively for the inspector schema', () => {
    const fields = [
      {
        key: 'file',
        type: 'object',
        description: 'Canonical file reference',
        children: [
          { key: 'path', type: 'string', description: 'Storage path' },
          { key: 'mimeType', type: 'string', description: 'MIME type' },
        ],
      },
      { key: 'success', type: 'boolean', description: 'Whether execution succeeded' },
    ];

    expect(toOutputSchema(fields)).toEqual([
      {
        key: 'file',
        type: 'object',
        description: 'Canonical file reference',
        children: [
          { key: 'path', type: 'string', description: 'Storage path' },
          { key: 'mimeType', type: 'string', description: 'MIME type' },
        ],
      },
      { key: 'success', type: 'boolean', description: 'Whether execution succeeded' },
    ]);
  });

  it('keeps runtime-only backend output keys and carries the runtimeOnly flag (surfaced with a badge)', () => {
    const fields = [
      { key: 'current_item', type: 'object', description: 'Runtime item', runtimeOnly: true },
      { key: 'current_index', type: 'number', description: 'Runtime index', runtimeOnly: true },
      { key: 'items', type: 'array', description: 'Persisted items' },
    ];

    // Runtime-only fields are no longer filtered out: they must appear in BOTH the OutputColumn
    // and the InputColumn ancestor variable picker (both read this schema), flagged so the UI can
    // badge them as body-only. Persisted fields carry no runtimeOnly flag.
    expect(toOutputSchema(fields)).toEqual([
      { key: 'current_item', type: 'object', description: 'Runtime item', runtimeOnly: true },
      { key: 'current_index', type: 'number', description: 'Runtime index', runtimeOnly: true },
      { key: 'items', type: 'array', description: 'Persisted items' },
    ]);
  });

  it('loads schemas from the centralized backend node-definitions endpoint', async () => {
    const response = [
      {
        nodeType: 'DOWNLOAD_FILE',
        label: 'Download File',
        category: 'core',
        variablePrefix: 'core',
        description: 'Downloads a file',
        terminal: false,
        branching: false,
        keywords: ['download'],
        outputs: [{ key: 'file', type: 'object', description: 'Canonical file reference' }],
      },
    ];
    vi.mocked(apiClient.get).mockResolvedValueOnce(response);

    await expect(nodeDefinitionService.getAll()).resolves.toBe(response);
    expect(apiClient.get).toHaveBeenCalledWith('/node-definitions');
  });
});
