/**
 * Which way a plan reads, and whether its stored positions can be kept.
 *
 * The defect this rule closes: a stored position only means something in the direction it
 * was computed in, and the canvas used to render legacy plans (horizontal positions, no
 * stamp) in the viewer's default. A vertical-default user saw every old workflow drawn with
 * top-to-bottom handles on left-to-right positions.
 */
import { describe, expect, it } from 'vitest';
import {
  canvasLoadLayoutOptions,
  planSyncLayoutOptions,
  resolvePlanLayout,
} from '../planLayoutDirection';

describe('resolvePlanLayout', () => {
  it('reads a stamped plan in its own direction, whatever the viewer prefers', () => {
    expect(resolvePlanLayout({
      storedDirection: 'vertical', hasStoredPositions: true, fallbackDirection: 'horizontal',
    })).toEqual({ direction: 'vertical', relayout: false });
  });

  it('regression: reads unstamped positions as the historical horizontal layout, not the viewer default', () => {
    expect(resolvePlanLayout({
      storedDirection: undefined, hasStoredPositions: true, fallbackDirection: 'vertical',
    })).toEqual({ direction: 'horizontal', relayout: false });
  });

  it('lets the viewer default decide only a plan with no layout information at all', () => {
    expect(resolvePlanLayout({
      storedDirection: undefined, hasStoredPositions: false, fallbackDirection: 'vertical',
    })).toEqual({ direction: 'vertical', relayout: false });
  });

  it('keeps a stamp even without positions (an agent build the user already oriented)', () => {
    expect(resolvePlanLayout({
      storedDirection: 'vertical', hasStoredPositions: false, fallbackDirection: 'horizontal',
    })).toEqual({ direction: 'vertical', relayout: false });
  });

  it('ignores a stamp that is not a direction', () => {
    expect(resolvePlanLayout({
      storedDirection: 'TB', hasStoredPositions: true, fallbackDirection: 'vertical',
    })).toEqual({ direction: 'horizontal', relayout: false });
    expect(resolvePlanLayout({
      storedDirection: 42, hasStoredPositions: false, fallbackDirection: 'vertical',
    })).toEqual({ direction: 'vertical', relayout: false });
  });

  it('re-lays out positions computed in the other direction when the surface pins one', () => {
    expect(resolvePlanLayout({
      storedDirection: 'horizontal', hasStoredPositions: true, fallbackDirection: 'vertical', forcedDirection: 'vertical',
    })).toEqual({ direction: 'vertical', relayout: true });
  });

  it('keeps positions a pin agrees with', () => {
    expect(resolvePlanLayout({
      storedDirection: 'vertical', hasStoredPositions: true, fallbackDirection: 'horizontal', forcedDirection: 'vertical',
    })).toEqual({ direction: 'vertical', relayout: false });
  });

  it('never asks to re-lay a plan that has no position to keep', () => {
    expect(resolvePlanLayout({
      storedDirection: 'horizontal', hasStoredPositions: false, fallbackDirection: 'vertical', forcedDirection: 'vertical',
    })).toEqual({ direction: 'vertical', relayout: false });
  });

  it('takes unstamped positions to be in the direction the caller says they came from', () => {
    expect(resolvePlanLayout({
      storedDirection: undefined,
      hasStoredPositions: true,
      fallbackDirection: 'vertical',
      forcedDirection: 'vertical',
      unstampedPositionsDirection: 'vertical',
    })).toEqual({ direction: 'vertical', relayout: false });
  });
});

describe('canvasLoadLayoutOptions', () => {
  it('lets the plan decide on an ordinary canvas, with the user default as the fallback', () => {
    expect(canvasLoadLayoutOptions({ layoutDirection: 'horizontal', defaultDirection: 'vertical', isPinned: false }))
      .toEqual({ fallbackDirection: 'vertical' });
  });

  it('pins every plan to the surface direction on a pinned canvas', () => {
    expect(canvasLoadLayoutOptions({ layoutDirection: 'vertical', defaultDirection: 'horizontal', isPinned: true }))
      .toEqual({ fallbackDirection: 'vertical', forcedDirection: 'vertical' });
  });
});

describe('planSyncLayoutOptions', () => {
  it('regression: an agent edit that lost the stamp does not re-lay a vertical workflow horizontally', () => {
    // The plan the sync re-reads is the one this canvas has been showing, so its unstamped
    // positions are the canvas's own. Read as legacy horizontal, a vertical workflow would
    // be thrown into a full re-layout after every agent action.
    expect(resolvePlanLayout({
      storedDirection: undefined, hasStoredPositions: true, ...planSyncLayoutOptions('vertical'),
    })).toEqual({ direction: 'vertical', relayout: false });
  });

  it('lets a direction the agent stated win over the canvas one, positions kept', () => {
    // An agent may set layoutDirection with set_plan. Forcing the canvas direction here
    // re-laid its graph sideways and the next Save wrote the canvas direction back over it.
    expect(resolvePlanLayout({
      storedDirection: 'horizontal', hasStoredPositions: true, ...planSyncLayoutOptions('vertical'),
    })).toEqual({ direction: 'horizontal', relayout: false });
  });

  it('keeps the canvas direction for a plan with nothing stored', () => {
    expect(resolvePlanLayout({
      storedDirection: undefined, hasStoredPositions: false, ...planSyncLayoutOptions('vertical'),
    })).toEqual({ direction: 'vertical', relayout: false });
  });
});
