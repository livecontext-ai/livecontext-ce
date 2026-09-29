/**
 * One radius ladder for the whole app, documented in components/ui/README.md:
 *
 *   rounded-2xl  a floating surface
 *   rounded-xl   a control (Button of any size, icon button, card, modal step)
 *   rounded-lg   a Switch track (its handle takes the step below, rounded-md)
 *   rounded-md   a small non-interactive label: badge, chip, counter
 *
 * These are the SHARED primitives at each rung. They are what a single edit
 * propagates from, so a regression here is an app-wide regression: a capsule
 * Badge sitting next to a square Button was the most repeated mismatch there was.
 */
import { describe, expect, it } from 'vitest';
import { badgeVariants } from '@/components/ui/badge';
import { buttonVariants } from '@/components/ui/button';
import { canvasChromeChipRadiusClass, canvasChromeSurfaceClass } from '@/components/ui/canvas-chrome';
import { nodeIconBoxRadiusClass, nodeIconRadiusClass } from '@/app/workflows/builder/components/nodes/shared';

function radiusOf(className: string): string[] {
  return className.split(/\s+/).filter((c) => c.startsWith('rounded'));
}

describe('the radius ladder', () => {
  it('a Badge is a small square label, at the bottom rung', () => {
    expect(radiusOf(badgeVariants())).toEqual(['rounded-md']);
  });

  it('a Badge sits at the SAME rung as the canvas info chips - both are labels', () => {
    expect(radiusOf(badgeVariants())).toEqual([canvasChromeChipRadiusClass]);
  });

  it('a Button is a control, one rung above a label, at every size', () => {
    for (const size of ['default', 'sm', 'lg', 'icon'] as const) {
      expect(radiusOf(buttonVariants({ size }))).toEqual(['rounded-xl']);
    }
  });

  it('a floating surface is one rung above the controls it holds', () => {
    expect(radiusOf(canvasChromeSurfaceClass)).toEqual(['rounded-2xl']);
  });

  it('the node-icon tile sits on the ladder, and climbs a rung with its box', () => {
    // It is the icon every surface shares (canvas, palette, inspector, run
    // panel, workflow list and board, marketplace card), so it was the most
    // visible capsule left next to the square cards holding it.
    const rungs = ['rounded-md', 'rounded-lg', 'rounded-xl', 'rounded-2xl'];
    const at = (size: Parameters<typeof nodeIconRadiusClass>[0]) => rungs.indexOf(nodeIconRadiusClass(size));

    expect(at('xs')).toBeGreaterThanOrEqual(0);
    expect(at('xs')).toBeLessThan(at('md'));
    expect(at('md')).toBeLessThan(at('lg'));
  });

  it('a box that wraps an icon tile reads its rung from its OWN height', () => {
    // The bubbles around a node icon are 24 / 28 / 40px, in-between sizes with
    // no NodeIconSize name. Picking their rung by eye is what put `rounded-xl`
    // (12px) on the 28px bubble - a circle among square tiles.
    expect(nodeIconBoxRadiusClass(24)).toBe('rounded-md');
    expect(nodeIconBoxRadiusClass(28)).toBe('rounded-lg');
    expect(nodeIconBoxRadiusClass(40)).toBe('rounded-xl');
  });

  it('the wrapper ladder agrees with the tile ladder at the sizes they share', () => {
    // A bubble and the tile inside it must not disagree, or the icon and its
    // frame draw two different corners at the same spot.
    expect(nodeIconBoxRadiusClass(16)).toBe(nodeIconRadiusClass('2xs'));
    expect(nodeIconBoxRadiusClass(24)).toBe(nodeIconRadiusClass('xs'));
    expect(nodeIconBoxRadiusClass(32)).toBe(nodeIconRadiusClass('sm'));
    expect(nodeIconBoxRadiusClass(36)).toBe(nodeIconRadiusClass('md'));
    expect(nodeIconBoxRadiusClass(44)).toBe(nodeIconRadiusClass('lg'));
  });

  it('no box on the icon ladder gets a corner big enough to read as a circle', () => {
    const px: Record<string, number> = {
      'rounded-md': 6, 'rounded-lg': 8, 'rounded-xl': 12, 'rounded-2xl': 16,
    };
    for (const height of [24, 28, 32, 36, 40, 44]) {
      const radius = px[nodeIconBoxRadiusClass(height)];
      expect(radius, `${height}px`).toBeDefined();
      expect(radius / height, `${height}px`).toBeLessThanOrEqual(1 / 3);
    }
  });

  it('no shared primitive is a capsule any more', () => {
    for (const className of [badgeVariants(), buttonVariants(), canvasChromeSurfaceClass]) {
      expect(radiusOf(className)).not.toContain('rounded-full');
    }
    expect(canvasChromeChipRadiusClass).not.toBe('rounded-full');
    for (const size of ['xs', 'sm', 'md', 'lg'] as const) {
      expect(nodeIconRadiusClass(size), `size ${size}`).not.toBe('rounded-full');
    }
  });
});
