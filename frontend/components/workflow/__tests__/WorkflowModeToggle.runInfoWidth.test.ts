import { describe, it, expect } from 'vitest';
import { computeRunInfoPanelWidths } from '../runInfoPanelWidth';

/**
 * Regression guard for the right-side-panel RunInfo overflow bug.
 *
 * The expanded RunInfo panel (which hosts the epoch selector) is anchored to the
 * right of the canvas. It used to force `min-w-[340px]` regardless of the
 * container while its max reserved a fixed 160px for the mode toggle - a toggle
 * the embedded application side panel hides. In a narrow side panel the 340px
 * floor exceeded the max, so the panel + its epoch rows overflowed off-screen to
 * the right and could not be clicked. The fix makes the bounds container-aware:
 * `minWidth <= maxWidth <= containerWidth` must always hold.
 */
describe('computeRunInfoPanelWidths', () => {
  it('never lets the panel exceed the container across the realistic width range', () => {
    for (let containerWidth = 280; containerWidth <= 2000; containerWidth += 7) {
      for (const showToggle of [true, false]) {
        const { minWidth, maxWidth } = computeRunInfoPanelWidths(containerWidth, showToggle);
        expect(minWidth, `minWidth<=maxWidth @${containerWidth}/${showToggle}`).toBeLessThanOrEqual(maxWidth);
        expect(maxWidth, `maxWidth<=containerWidth @${containerWidth}/${showToggle}`).toBeLessThanOrEqual(containerWidth);
        expect(minWidth, `minWidth>=0 @${containerWidth}/${showToggle}`).toBeGreaterThanOrEqual(0);
      }
    }
  });

  it('does not overflow a narrow application side panel (the reported bug)', () => {
    // 440px is a ~0.4-wide side panel on an 1100px laptop window - exactly where
    // the old `min-w-[340px]` + `-160` reservation pushed the panel off-screen.
    const { minWidth, maxWidth } = computeRunInfoPanelWidths(440, /* showToggle */ false);
    expect(maxWidth).toBeLessThanOrEqual(440 - 16);
    expect(minWidth).toBeLessThanOrEqual(maxWidth);
    // With the toggle hidden the panel keeps its comfortable 340px floor here.
    expect(minWidth).toBe(340);
  });

  it('shrinks the floor below 340 only when the container is genuinely too small', () => {
    // Very narrow container: the 340 preferred floor must yield so nothing overflows.
    const { minWidth, maxWidth } = computeRunInfoPanelWidths(300, false);
    expect(minWidth).toBe(maxWidth);
    expect(maxWidth).toBeLessThanOrEqual(300);
    expect(minWidth).toBeLessThan(340);
  });

  it('reserves room for the mode toggle only when it is shown', () => {
    // Same container - hiding the toggle frees ~136px of usable width.
    const withToggle = computeRunInfoPanelWidths(900, true);
    const withoutToggle = computeRunInfoPanelWidths(900, false);
    expect(withoutToggle.maxWidth).toBeGreaterThan(withToggle.maxWidth);
    expect(withToggle.maxWidth).toBe(900 - 160);
    expect(withoutToggle.maxWidth).toBe(900 - 24);
  });

  it('is resilient to a zero / non-finite container width (pre-measure render)', () => {
    for (const w of [0, Number.NaN, -50, undefined as unknown as number]) {
      const { minWidth, maxWidth } = computeRunInfoPanelWidths(w, false);
      expect(minWidth).toBeLessThanOrEqual(maxWidth);
      expect(maxWidth).toBeGreaterThanOrEqual(0);
      expect(Number.isFinite(minWidth)).toBe(true);
      expect(Number.isFinite(maxWidth)).toBe(true);
    }
  });
});
