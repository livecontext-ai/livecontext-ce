import { describe, it, expect, vi } from 'vitest';

// MarkdownRender transitively imports next-intl (via the visualization child
// components), which fails to resolve `next/navigation` under vitest's node
// environment. We only exercise the pure splitByMarkers logic, so stub the
// child modules that pull that chain in. (Type-only imports are erased.)
vi.mock('@/components/chat/VisualizeBlock', () => ({ VisualizeBlock: () => null }));
vi.mock('@/components/chat/ResourceCarousel', () => ({ ResourceCarousel: () => null }));
vi.mock('@/hooks/useThemeSafely', () => ({ useThemeSafely: () => 'light' }));

import { splitByMarkers, type Segment } from '../MarkdownRender';

/**
 * Regression tests for the visualize-marker segmentation.
 *
 * Two bugs fixed (fix/visualize-marker-in-codeblocks):
 *  1. A [visualize:…] / [tool_call:…] written as documentation INSIDE a fenced
 *     or inline code region was promoted to a real card. maskCodeRegions() now
 *     blanks code regions before scanning, so example markers stay text.
 *  2. A malformed id for a UUID-based type (placeholder like `<appId>`) rendered
 *     an "Application not found" 404 card. The UUID guard degrades it to text.
 *
 * Both assertions below FAIL on the pre-fix code (which scanned the raw text and
 * had no UUID guard).
 */
const UUID = '11111111-1111-4111-8111-111111111111';
const RUN = '22222222-2222-4222-8222-222222222222';

const viz = (s: Segment[]) => s.filter((seg) => seg.type === 'visualize');
const onlyViz = (s: Segment[]) =>
  s.filter((seg): seg is Extract<Segment, { type: 'visualize' }> => seg.type === 'visualize');

describe('splitByMarkers - code-region masking (bug 1)', () => {
  it('does NOT promote a visualize marker written inside a fenced ``` block', () => {
    const text = ['Here is the syntax:', '```', `[visualize:application:${UUID}]`, '```'].join('\n');
    const segments = splitByMarkers(text);
    expect(viz(segments)).toHaveLength(0);
    // the marker survives as literal text (inside the code block content)
    expect(segments.some((s) => s.type === 'text' && s.content.includes('[visualize:application:'))).toBe(true);
  });

  it('does NOT promote a visualize marker inside an inline `code` span', () => {
    const text = `Write \`[visualize:application:${UUID}]\` to embed the app.`;
    expect(viz(splitByMarkers(text))).toHaveLength(0);
  });

  it('does NOT promote a tool_call marker inside a ~~~ fence', () => {
    const text = ['~~~', '[tool_call:search]', '~~~'].join('\n');
    const segments = splitByMarkers(text);
    expect(segments.some((s) => s.type === 'tool_call')).toBe(false);
  });

  it('STILL promotes a real marker outside any code region', () => {
    const segments = onlyViz(splitByMarkers(`Live app: [visualize:application:${UUID}]`));
    expect(segments).toHaveLength(1);
    expect(segments[0]).toMatchObject({ vizType: 'application', id: UUID });
  });

  it('promotes the real marker but ignores the documented one in the same text', () => {
    const text = [
      `Real card: [visualize:application:${UUID}]`,
      'Example you can copy:',
      '```',
      '[visualize:application:<your-app-id>]',
      '```',
    ].join('\n');
    const segments = onlyViz(splitByMarkers(text));
    expect(segments).toHaveLength(1);
    expect(segments[0].id).toBe(UUID);
  });
});

describe('splitByMarkers - UUID guard (bug 2)', () => {
  it('degrades a malformed application id (<appId>) to text, not a 404 card', () => {
    const segments = splitByMarkers('[visualize:application:<appId>]');
    expect(viz(segments)).toHaveLength(0);
    expect(segments).toEqual([{ type: 'text', content: '[visualize:application:<appId>]' }]);
  });

  it('renders a card when the application id is a real UUID', () => {
    expect(viz(splitByMarkers(`[visualize:application:${UUID}]`))).toHaveLength(1);
  });

  it('guards every UUID-based type (incl. interface-backed agent_browse / image_generation)', () => {
    for (const t of ['workflow', 'interface', 'agent', 'agent_browse', 'image_generation']) {
      expect(viz(splitByMarkers(`[visualize:${t}:not-a-uuid]`))).toHaveLength(0);
    }
  });

  it('does NOT guard non-UUID types (datasource keeps its numeric id)', () => {
    const segments = onlyViz(splitByMarkers('[visualize:datasource:123]'));
    expect(segments).toHaveLength(1);
    expect(segments[0]).toMatchObject({ vizType: 'datasource', id: '123' });
  });
});

describe('splitByMarkers - 4-field runId (bug 2 carousel input)', () => {
  it('captures the runId from the 4-field application marker', () => {
    const segments = onlyViz(splitByMarkers(`[visualize:application:${UUID}:${RUN}]`));
    expect(segments).toHaveLength(1);
    expect(segments[0].runId).toBe(RUN);
  });

  it('dedups identical type+id+runId but keeps distinct runs distinct', () => {
    const same = `[visualize:application:${UUID}:${RUN}] [visualize:application:${UUID}:${RUN}]`;
    expect(viz(splitByMarkers(same))).toHaveLength(1);

    const other = '33333333-3333-4333-8333-333333333333';
    const distinct = `[visualize:application:${UUID}:${RUN}] [visualize:application:${UUID}:${other}]`;
    const segs = onlyViz(splitByMarkers(distinct));
    expect(segs).toHaveLength(2);
    expect(segs.map((s) => s.runId)).toEqual([RUN, other]);
  });
});
