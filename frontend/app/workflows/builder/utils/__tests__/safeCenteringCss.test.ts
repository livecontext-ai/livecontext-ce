import { describe, it, expect } from 'vitest';
import { SAFE_CENTERING_CSS, centeringCssFor } from '../safeCenteringCss';

/**
 * The embedder centering layer follows the same fragment/complete contract as
 * the platform base stylesheet: fragments get centered inside preview panels,
 * complete documents own their body layout and must render exactly as authored
 * (parity with the screenshot/video renderer, which injects nothing).
 *
 * Regression for the 2026-07-16 report: a complete vertical interface was
 * perfectly centered in the generated video but shifted in the builder preview
 * and application panel - the flex centering (and the base CSS) overrode the
 * author's own body rules there.
 */
describe('centeringCssFor', () => {
  it('returns the safe-centering CSS for a fragment template', () => {
    expect(centeringCssFor('<div>widget</div>')).toBe(SAFE_CENTERING_CSS);
    expect(centeringCssFor('<h1>Title</h1><p>body</p>')).toBe(SAFE_CENTERING_CSS);
  });

  it('returns the safe-centering CSS for an empty/absent template (nothing to protect)', () => {
    expect(centeringCssFor('')).toBe(SAFE_CENTERING_CSS);
    expect(centeringCssFor(undefined)).toBe(SAFE_CENTERING_CSS);
    expect(centeringCssFor(null)).toBe(SAFE_CENTERING_CSS);
  });

  it('returns NOTHING for a complete document (author owns the body layout)', () => {
    expect(centeringCssFor('<!DOCTYPE html><html><body>Hi</body></html>')).toBe('');
    expect(centeringCssFor('<html lang="en"><body>Hi</body></html>')).toBe('');
    expect(centeringCssFor('  \n<!doctype html><html></html>')).toBe('');
  });
});
