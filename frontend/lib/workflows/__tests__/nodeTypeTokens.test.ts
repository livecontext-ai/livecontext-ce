import { describe, it, expect } from 'vitest';
import {
  groupNodeTypesByFamily,
  nodeTypeIconProps,
  nodeTypeLabel,
  nodeTypeMatchesSearch,
  nodeTypeToNodeId,
  parseNodeType,
} from '../nodeTypeTokens';

/**
 * The browser half of the node-type token grammar written by
 * `WorkflowNodeTypeExtractor.java`. These tokens arrive from the server as
 * opaque strings; everything a person sees in the filter (the family it is
 * grouped under, its name, its glyph) is derived here, so a mistake shows up as
 * an option labelled "Http_request" or drawn with the wrong icon rather than as
 * an error.
 */

describe('parseNodeType', () => {
  it('splits a prefixed token into family + subtype', () => {
    expect(parseNodeType('mcp:gmail')).toEqual({ value: 'mcp:gmail', family: 'mcp', subtype: 'gmail' });
  });

  it('treats the bare interface token as its own family', () => {
    expect(parseNodeType('interface')).toEqual({ value: 'interface', family: 'interface', subtype: '' });
  });

  it('lowercases, so a hand-typed token lands in the same bucket', () => {
    expect(parseNodeType('MCP:Gmail').value).toBe('mcp:gmail');
  });

  it('files an unknown prefix under core instead of dropping it', () => {
    // A token this build does not know still exists on real rows; hiding it
    // would make those rows unreachable by the only filter that finds them.
    expect(parseNodeType('future:thing').family).toBe('core');
  });

  it('keeps a subtype that itself contains a colon', () => {
    expect(parseNodeType('mcp:foo:bar').subtype).toBe('foo:bar');
  });
});

describe('nodeTypeToNodeId', () => {
  it('maps a trigger through the shared trigger-kind map', () => {
    expect(nodeTypeToNodeId(parseNodeType('trigger:webhook'))).toBe('webhook-trigger');
  });

  it('maps the datasource trigger to its historical tables- id', () => {
    expect(nodeTypeToNodeId(parseNodeType('trigger:datasource'))).toBe('tables-trigger');
  });

  it('falls back to <type>-trigger for a kind the map does not carry', () => {
    expect(nodeTypeToNodeId(parseNodeType('trigger:future'))).toBe('future-trigger');
  });

  it('maps a plain agent to the ai-agent glyph', () => {
    expect(nodeTypeToNodeId(parseNodeType('agent:agent'))).toBe('ai-agent');
  });

  it('falls back to the agent glyph for an unknown agent subtype', () => {
    expect(nodeTypeToNodeId(parseNodeType('agent:future'))).toBe('ai-agent');
  });

  it('has no node id for an integration - it is a catalog API, not a node class', () => {
    expect(nodeTypeToNodeId(parseNodeType('mcp:gmail'))).toBeNull();
  });

  it('maps the find operation to its palette id, which is spelled differently', () => {
    // Stored as `crud-find` (Step.java) -> token `table:find`, but the palette
    // registers it as `find-row`. Without the alias the option reads "Find" with
    // a fallback glyph while the canvas says "Find Rows".
    expect(nodeTypeToNodeId(parseNodeType('table:find'))).toBe('find-row');
  });

  it('maps the four drifting core types to their palette ids', () => {
    expect(nodeTypeToNodeId(parseNodeType('core:decision'))).toBe('if-else');
    expect(nodeTypeToNodeId(parseNodeType('core:loop'))).toBe('while-group');
    expect(nodeTypeToNodeId(parseNodeType('core:http_request'))).toBe('http-request');
    expect(nodeTypeToNodeId(parseNodeType('core:approval'))).toBe('user-approval');
  });

  it('leaves the five CRUD operations whose ids already line up alone', () => {
    for (const op of ['create-row', 'create-column', 'read-row', 'update-row', 'delete-row']) {
      expect(nodeTypeToNodeId(parseNodeType(`table:${op}`))).toBe(op);
    }
  });
});

describe('nodeTypeLabel', () => {
  it('uses the palette label, so the filter names a node what the canvas names it', () => {
    expect(nodeTypeLabel(parseNodeType('core:code'))).toBe('Code');
    expect(nodeTypeLabel(parseNodeType('table:create-row'))).toBe('Create Row');
    expect(nodeTypeLabel(parseNodeType('trigger:webhook'))).toBe('Webhook');
    expect(nodeTypeLabel(parseNodeType('interface'))).toBe('Interface');
  });

  it('prettifies an integration slug, which has no palette entry', () => {
    expect(nodeTypeLabel(parseNodeType('mcp:google_ads'))).toBe('Google Ads');
  });

  it('gives the find operation the name the canvas gives it', () => {
    expect(nodeTypeLabel(parseNodeType('table:find'))).toBe('Find Rows');
  });

  it('gives the four drifting core types the name the canvas gives them', () => {
    // Their stored type and their palette id are spelled differently, so
    // without an alias the picker would invent "Decision" / "Loop" / "Approval"
    // for nodes the product calls something else - and the search field, which
    // matches on the label, would not find them by the name the user knows.
    expect(nodeTypeLabel(parseNodeType('core:decision'))).toBe('If / else');
    expect(nodeTypeLabel(parseNodeType('core:loop'))).toBe('While');
    expect(nodeTypeLabel(parseNodeType('core:http_request'))).toBe('HTTP Request');
    expect(nodeTypeLabel(parseNodeType('core:approval'))).toBe('User Approval');
  });

  it('leaves a core type whose id already matches the palette alone', () => {
    expect(nodeTypeLabel(parseNodeType('core:transform'))).toBe('Transform');
  });

  it('prettifies a node type this build has no palette entry for', () => {
    expect(nodeTypeLabel(parseNodeType('core:future_node'))).toBe('Future Node');
  });
});

describe('nodeTypeIconProps', () => {
  it('marks an integration as MCP so the brand logo resolves', () => {
    expect(nodeTypeIconProps(parseNodeType('mcp:gmail'))).toEqual({ iconSlug: 'gmail', isMcp: true });
  });

  it('gives a trigger the entry kind, matching what the cards draw', () => {
    expect(nodeTypeIconProps(parseNodeType('trigger:schedule')))
      .toEqual({ nodeId: 'schedule-trigger', nodeKind: 'entry' });
  });

  it('gives an interface its own kind', () => {
    expect(nodeTypeIconProps(parseNodeType('interface')))
      .toEqual({ nodeId: 'interface', nodeKind: 'interface' });
  });
});

describe('nodeTypeMatchesSearch', () => {
  it('matches on the label a person can see', () => {
    expect(nodeTypeMatchesSearch(parseNodeType('mcp:gmail'), 'gma')).toBe(true);
  });

  it('matches on the raw token, so a token pasted from an agent finds its option', () => {
    expect(nodeTypeMatchesSearch(parseNodeType('core:http_request'), 'core:http')).toBe(true);
  });

  it('matches a whole family by its prefix', () => {
    expect(nodeTypeMatchesSearch(parseNodeType('mcp:slack'), 'mcp:')).toBe(true);
  });

  it('is case-insensitive', () => {
    expect(nodeTypeMatchesSearch(parseNodeType('mcp:gmail'), 'GMAIL')).toBe(true);
  });

  it('an empty search matches everything rather than nothing', () => {
    expect(nodeTypeMatchesSearch(parseNodeType('mcp:gmail'), '   ')).toBe(true);
  });

  it('does not match an unrelated term', () => {
    expect(nodeTypeMatchesSearch(parseNodeType('mcp:gmail'), 'slack')).toBe(false);
  });
});

describe('groupNodeTypesByFamily', () => {
  it('orders families the way the picker lists them, whatever order they arrive in', () => {
    const groups = groupNodeTypesByFamily([
      { value: 'interface' },
      { value: 'core:loop' },
      { value: 'trigger:webhook' },
      { value: 'mcp:gmail' },
    ]);

    expect(groups.map((g) => g.family)).toEqual(['trigger', 'mcp', 'core', 'interface']);
  });

  it('omits families with nothing in them', () => {
    const groups = groupNodeTypesByFamily([{ value: 'mcp:gmail' }]);
    expect(groups).toHaveLength(1);
    expect(groups[0].family).toBe('mcp');
  });

  it('keeps the incoming order inside a family - the server sorted it by usage', () => {
    const groups = groupNodeTypesByFamily([
      { value: 'mcp:zendesk' },
      { value: 'mcp:asana' },
    ]);

    expect(groups[0].items.map((i) => i.value)).toEqual(['mcp:zendesk', 'mcp:asana']);
  });

  it('carries the parsed token through, so callers do not parse twice', () => {
    const groups = groupNodeTypesByFamily([{ value: 'mcp:gmail', count: 3 }]);
    expect(groups[0].items[0]).toMatchObject({ value: 'mcp:gmail', count: 3 });
    expect(groups[0].items[0].parsed.subtype).toBe('gmail');
  });
});
