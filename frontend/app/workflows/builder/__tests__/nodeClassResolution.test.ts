import { describe, expect, it } from 'vitest';
import { findNodeClassById, getPaletteItemDataFromId } from '../nodes/nodeClasses';

describe('node class resolution', () => {
  it('prefers canonical node IDs over aliases from earlier node classes', () => {
    expect(findNodeClassById('filter')?.id).toBe('filter');
    expect(findNodeClassById('filter-123')?.id).toBe('filter');

    const paletteItem = getPaletteItemDataFromId('filter');
    expect(paletteItem).toMatchObject({
      id: 'filter',
      label: 'Filter',
      kind: 'action',
      nodeType: 'flowNode',
    });
  });

  it('keeps alias resolution for nodes without a matching canonical ID', () => {
    expect(findNodeClassById('safety')?.id).toBe('guardrail');
    expect(findNodeClassById('safety-123')?.id).toBe('guardrail');
  });
});
