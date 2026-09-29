// @vitest-environment jsdom
/**
 * The node-icon tile is square-rounded, on the app's radius ladder.
 *
 * It used to be the one medallion left as a capsule, which put a circle next to
 * the square cards, chips and buttons it shares every surface with: the canvas
 * node, the palette, the inspector header, the run panel rows, the workflow
 * list and board, the marketplace card. NodeIcon is the single place all of
 * those get their icon from, so the shape lives here and nowhere else.
 *
 * Two things have to hold, and both were real bugs waiting to happen:
 *  - the THREE background branches must agree, or a node changes shape
 *    depending on whether its service icon happened to resolve;
 *  - the radius must climb with the box, or one value reads as a circle on the
 *    24px tile and as a hard corner on the 44px one.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// Both accessors: NodeIcon reads the theme through `useThemeSafely`, which
// calls `useOptionalTheme`, because it also renders on the public marketplace
// pages that have no ThemeProvider above them.
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ mode: 'edit' }) }));
vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: () => ({ url: null, error: false }) }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => <div data-testid="avatar" /> }));
vi.mock('next/image', () => ({
  default: ({ src, alt, onError }: { src: string; alt: string; onError?: () => void }) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img data-testid="node-icon-img" src={src} alt={alt} onError={onError} />
  ),
}));

import { NodeIcon, nodeIconRadiusClass, type NodeIconSize } from '../shared';

/** Every radius the app is allowed to draw, capsule excluded. */
const LADDER = ['rounded-md', 'rounded-lg', 'rounded-xl', 'rounded-2xl'];

function radiusOf(element: HTMLElement): string[] {
  return element.className.split(/\s+/).filter((c) => c.startsWith('rounded'));
}

function tile(ui: React.ReactElement): HTMLElement {
  const { container } = render(ui);
  return container.firstElementChild as HTMLElement;
}

const SIZES: NodeIconSize[] = ['2xs', 'xs', 'sm', 'md', 'lg'];

describe('NodeIcon - the tile is square-rounded', () => {
  beforeEach(() => { cleanup(); });

  it('is never a capsule, whichever branch draws its background', () => {
    // 1) a service icon resolved, 2) a caller-supplied background, 3) neither.
    const branches = [
      <NodeIcon key="icon" iconSlug="slack" nodeId="mcp-slack" />,
      <NodeIcon key="bg" nodeId="core-decision" bgClassName="bg-purple-100" />,
      <NodeIcon key="plain" nodeId="core-decision" />,
    ];

    for (const branch of branches) {
      expect(radiusOf(tile(branch))).not.toContain('rounded-full');
      cleanup();
    }
  });

  it('draws the SAME corner whether or not the service icon resolved', () => {
    // The branch that wins depends on a network request, so a node that
    // disagreed with itself here would change shape as the page loaded.
    const withIcon = radiusOf(tile(<NodeIcon iconSlug="slack" nodeId="mcp-slack" size="md" />));
    cleanup();
    const withBg = radiusOf(tile(<NodeIcon nodeId="mcp-slack" bgClassName="bg-purple-100" size="md" />));
    cleanup();
    const bare = radiusOf(tile(<NodeIcon nodeId="mcp-slack" size="md" />));

    expect(withIcon).toEqual(withBg);
    expect(withIcon).toEqual(bare);
  });

  it('stays on the ladder at every size, and never on two rungs at once', () => {
    for (const size of SIZES) {
      const radius = radiusOf(tile(<NodeIcon nodeId="core-decision" size={size} />));
      expect(radius, `size ${size}`).toHaveLength(1);
      expect(LADDER, `size ${size}`).toContain(radius[0]);
      cleanup();
    }
  });

  it('climbs with the box, so the corner keeps its weight from 24px to 44px', () => {
    const rung = (size: NodeIconSize) =>
      LADDER.indexOf(radiusOf(tile(<NodeIcon nodeId="core-decision" size={size} />))[0]);

    const xs = rung('xs'); cleanup();
    const md = rung('md'); cleanup();
    const lg = rung('lg');

    expect(xs).toBeLessThan(md);
    expect(md).toBeLessThan(lg);
  });

  it('nodeIconRadiusClass reports what the component actually draws', () => {
    // The run panel and the step table draw a placeholder where a NodeIcon goes
    // when a step has no icon. They read the radius from here, so a row cannot
    // change shape the moment the real icon lands.
    for (const size of SIZES) {
      expect(radiusOf(tile(<NodeIcon nodeId="core-decision" size={size} />)), `size ${size}`)
        .toEqual([nodeIconRadiusClass(size)]);
      cleanup();
    }
  });

  it('defaults to the largest tile, like the component itself', () => {
    expect(nodeIconRadiusClass()).toBe(nodeIconRadiusClass('lg'));
  });
});

describe('NodeIcon - the 2xs tile fits a 16px row', () => {
  beforeEach(() => { cleanup(); });

  it('is a 16px box, so a dense row does not crop it', () => {
    // The Analysis grid's rows are 16px: the 24px xs tile overflowed its slot and the
    // scrolling container around the grid cut its left edge off.
    const el = tile(<NodeIcon nodeId="core-decision" size="2xs" />);
    expect(el.className).toContain('h-4');
    expect(el.className).toContain('w-4');
    expect(radiusOf(el)).toEqual(['rounded-md']);
  });
});
