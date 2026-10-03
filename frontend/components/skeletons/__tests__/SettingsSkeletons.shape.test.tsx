// @vitest-environment jsdom
/**
 * A skeleton is a PROMISE about the shape that is coming. When its corner does
 * not match the element it stands in for, the page visibly re-shapes itself the
 * moment the data lands, which is the one thing a skeleton exists to avoid.
 *
 * Two boxes had drifted: the credential TYPE chip was `rounded-2xl` on an h-6
 * (24px) box - 16px of corner on 24px is a capsule, and the real chip is
 * `px-2 py-1 rounded-md` - and the tab bar sat a rung below the real one on both
 * the surface and its items.
 *
 * So this pins the two specific rungs against the shared primitives they must
 * follow, plus one invariant over EVERY box in the file: a radius that reaches
 * half the height redraws a capsule whatever the class is called.
 */
import '@testing-library/jest-dom/vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';

import {
  CredentialsListSkeleton,
  IntegrationsListSkeleton,
  OverviewPageSkeleton,
  SettingsHeaderSkeleton,
  SettingsPageSkeleton,
  SidebarSkeleton,
  TabsSkeleton,
} from '../SettingsSkeletons';

/** Tailwind height rung -> rendered px. */
const HEIGHT_PX: Record<string, number> = {
  'h-3': 12, 'h-4': 16, 'h-5': 20, 'h-6': 24, 'h-8': 32, 'h-9': 36,
  'h-10': 40, 'h-16': 64, 'h-32': 128, 'h-48': 192, 'h-64': 256,
};

/**
 * Radius rung -> rendered px, for Tailwind v4 (the version this app pins).
 *
 * `rounded-sm` is 4px in v4, NOT the 2px it was in v3 - that value moved to
 * `rounded-xs`. The wrong number here would understate a corner in the one
 * direction that hides a capsule from the assertions below.
 */
const RADIUS_PX: Record<string, number> = {
  rounded: 4, 'rounded-xs': 2, 'rounded-sm': 4, 'rounded-md': 6, 'rounded-lg': 8,
  'rounded-xl': 12, 'rounded-2xl': 16, 'rounded-3xl': 24,
};

const ALL_SKELETONS: Array<[string, React.ReactElement]> = [
  ['SettingsHeaderSkeleton', <SettingsHeaderSkeleton key="h" />],
  ['SettingsPageSkeleton', <SettingsPageSkeleton key="p" />],
  ['CredentialsListSkeleton', <CredentialsListSkeleton key="c" />],
  ['TabsSkeleton', <TabsSkeleton key="t" />],
  ['OverviewPageSkeleton', <OverviewPageSkeleton key="o" />],
  ['IntegrationsListSkeleton', <IntegrationsListSkeleton key="i" />],
  ['SidebarSkeleton', <SidebarSkeleton key="s" />],
];

function classesOf(el: Element): string[] {
  return el.className.split(/\s+/).filter(Boolean);
}

/**
 * The radius the REAL counterpart draws, read from its source.
 *
 * A skeleton's whole job is to promise the shape that is coming, so the promise
 * has to be checked against the element it stands in for - not against a shared
 * primitive that element does not use. Throws rather than returning undefined:
 * a pattern that silently stops matching would turn these into assertions that
 * `undefined === undefined`.
 */
function radiusInSource(relativePath: string, pattern: RegExp): string {
  const source = readFileSync(join(__dirname, '../../..', relativePath), 'utf8');
  const match = source.match(pattern);
  if (!match) throw new Error(`Counterpart shape not found in ${relativePath} with ${pattern}`);
  return match[1];
}

/**
 * The corner the element actually renders: the LARGEST radius rung on it, not
 * the first one in attribute order.
 *
 * <p>Taking the first was a real hole. `SkeletonBox` used to concatenate its
 * base `rounded` with the caller's class, so the capsule this file was written
 * to catch (`rounded` + `rounded-2xl` on an h-6 box) measured 4px, 0.17 of its
 * height, and every assertion below went green on the broken markup. The base
 * is merged away now, but reading the widest rung means these tests keep their
 * teeth if any caller ever ships two again.
 */
function radiusOf(el: Element): string | undefined {
  const rungs = classesOf(el).filter((c) => c in RADIUS_PX);
  if (rungs.length === 0) return undefined;
  return rungs.reduce((widest, c) => (RADIUS_PX[c] > RADIUS_PX[widest] ? c : widest));
}

afterEach(cleanup);

describe('settings skeletons - the corner matches what is coming', () => {
  it('never draws a capsule, in any skeleton of the file', () => {
    for (const [name, ui] of ALL_SKELETONS) {
      const { container } = render(ui);
      for (const el of Array.from(container.querySelectorAll('*'))) {
        expect(classesOf(el), name).not.toContain('rounded-full');
      }
      cleanup();
    }
  });

  it('never gives a box a radius that reaches half its own height', () => {
    // The failure this catches is silent: the class says "square", the pixels
    // draw a pill. It is the same arithmetic that hid the modal step capsule.
    for (const [name, ui] of ALL_SKELETONS) {
      const { container } = render(ui);
      for (const el of Array.from(container.querySelectorAll('*'))) {
        const classes = classesOf(el);
        const height = classes.find((c) => c in HEIGHT_PX);
        const radius = radiusOf(el);
        if (!height || !radius) continue;
        expect(RADIUS_PX[radius] / HEIGHT_PX[height], `${name}: ${height} ${radius}`).toBeLessThan(0.5);
      }
      cleanup();
    }
  });

  it('draws the credential type chip at the rung the REAL chip uses', () => {
    // Read off the counterpart's own source, not off `badgeVariants`: the real
    // cell is a hand-written `<span className="text-xs px-2 py-1 … rounded-md">`,
    // not a `<Badge>`, so keying on Badge would go red when Badge moves and stay
    // green when the chip does - drift in both directions, from the wrong file.
    const chipRadius = radiusInSource(
      join('app', '[locale]', 'app', 'settings', 'credentials', 'components', 'MyCredentialsList.tsx'),
      /className="text-xs px-2 py-1 bg-theme-tertiary (rounded-\w+)/,
    );
    const { container } = render(<CredentialsListSkeleton />);

    // The only h-6 box in this skeleton is that chip.
    const chips = Array.from(container.querySelectorAll('*')).filter((el) => classesOf(el).includes('h-6'));

    expect(chips).not.toHaveLength(0);
    for (const chip of chips) expect(radiusOf(chip)).toBe(chipRadius);
  });

  it('draws the tab bar and its items at the rungs the REAL bar uses', () => {
    // Same reasoning: the settings tab bar is hand-written markup, so both rungs come
    // from the shared bar the settings pages use rather than from `buttonVariants`.
    const tabBar = join('components', 'settings', 'AdaptiveTabBar.tsx');
    const barRadius = radiusInSource(tabBar, /p-1\.5 bg-theme-tertiary (rounded-\w+)/);
    const itemRadius = radiusInSource(tabBar, /flex h-9 items-center[^']*?(rounded-\w+)/);

    const { container } = render(<TabsSkeleton tabCount={3} />);
    const bar = container.firstElementChild as HTMLElement;
    const items = Array.from(bar.children);

    expect(radiusOf(bar)).toBe(barRadius);
    expect(items).toHaveLength(3);
    for (const item of items) expect(radiusOf(item)).toBe(itemRadius);
  });
});
