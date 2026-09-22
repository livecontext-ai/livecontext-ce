import { describe, it, expect } from 'vitest';
import {
  filterByNodeTypes,
  nodeTypeFacets,
  processApps,
  type SortableApp,
} from '../applicationSort';

/**
 * Node-type filter on the /app/applications listing.
 *
 * <p>This page filters client-side because it already holds the whole union of
 * published + acquired apps in memory. That makes the rules below the ONLY
 * definition of what the filter means here, so they have to match the
 * server-side behaviour of the workflows list exactly: ANY-of matching, and
 * facet counts taken before the filter is applied.
 */

function app(title: string, nodeTypes?: string[], over: Partial<SortableApp> = {}): SortableApp {
  return {
    pub: { id: title, title, nodeTypes },
    source: over.source ?? 'published',
    acquiredAt: over.acquiredAt,
    lastExecutedAt: over.lastExecutedAt,
  };
}

describe('filterByNodeTypes', () => {
  it('keeps only the apps carrying the requested type', () => {
    const apps = [app('Gmail digest', ['mcp:gmail']), app('Slack alerts', ['mcp:slack'])];
    expect(filterByNodeTypes(apps, ['mcp:gmail']).map((a) => a.pub.title)).toEqual(['Gmail digest']);
  });

  it('several types mean ANY of them, not all of them', () => {
    const apps = [
      app('Gmail digest', ['mcp:gmail']),
      app('Slack alerts', ['mcp:slack']),
      app('Notion sync', ['mcp:notion']),
    ];
    expect(filterByNodeTypes(apps, ['mcp:gmail', 'mcp:slack']).map((a) => a.pub.title))
      .toEqual(['Gmail digest', 'Slack alerts']);
  });

  it('an empty selection is no filter at all', () => {
    const apps = [app('A', ['mcp:gmail']), app('B', ['mcp:slack'])];
    expect(filterByNodeTypes(apps, [])).toHaveLength(2);
  });

  it('an unknown token returns nothing rather than everything', () => {
    // Widening back to the full list would look like the filter doing nothing.
    expect(filterByNodeTypes([app('A', ['mcp:gmail'])], ['mcp:nope'])).toHaveLength(0);
  });

  it('drops an app published before node types existed, instead of crashing on it', () => {
    expect(filterByNodeTypes([app('Legacy', undefined)], ['mcp:gmail'])).toHaveLength(0);
  });

  it('compares case-insensitively', () => {
    expect(filterByNodeTypes([app('A', ['MCP:Gmail'])], ['mcp:gmail'])).toHaveLength(1);
  });
});

describe('nodeTypeFacets', () => {
  it('counts APPS per token, not nodes - one app with three Gmail steps counts once', () => {
    const apps = [
      app('A', ['mcp:gmail', 'mcp:gmail', 'core:loop']),
      app('B', ['mcp:gmail']),
    ];
    expect(nodeTypeFacets(apps)).toEqual([
      { value: 'mcp:gmail', count: 2 },
      { value: 'core:loop', count: 1 },
    ]);
  });

  it('orders by descending count, then alphabetically, like the workflows list does', () => {
    const apps = [
      app('A', ['core:loop', 'mcp:zendesk', 'mcp:asana']),
      app('B', ['core:loop']),
    ];
    expect(nodeTypeFacets(apps).map((f) => f.value)).toEqual(['core:loop', 'mcp:asana', 'mcp:zendesk']);
  });

  it('ignores apps with no tokens', () => {
    expect(nodeTypeFacets([app('Legacy', undefined), app('Empty', [])])).toEqual([]);
  });
});

describe('processApps with node types', () => {
  it('applies the node-type filter alongside the other refinements', () => {
    const apps = [
      app('Own Gmail', ['mcp:gmail'], { source: 'published' }),
      app('Own Slack', ['mcp:slack'], { source: 'published' }),
      app('Installed Gmail', ['mcp:gmail'], { source: 'acquired' }),
    ];

    const result = processApps(apps, 'published', 'all', 'name', undefined, ['mcp:gmail']);

    expect(result.map((a) => a.pub.title)).toEqual(['Own Gmail']);
  });

  it('stays backward-compatible: omitting the argument filters nothing', () => {
    const apps = [app('A', ['mcp:gmail']), app('B', ['mcp:slack'])];
    expect(processApps(apps, 'all', 'all', 'name')).toHaveLength(2);
  });

  it('still floats favorites to the top of the filtered set', () => {
    const apps = [
      app('Alpha', ['mcp:gmail']),
      app('Zulu', ['mcp:gmail']),
      app('Excluded', ['mcp:slack']),
    ];

    const result = processApps(apps, 'all', 'all', 'name', new Set(['Zulu']), ['mcp:gmail']);

    expect(result.map((a) => a.pub.title)).toEqual(['Zulu', 'Alpha']);
  });
});
