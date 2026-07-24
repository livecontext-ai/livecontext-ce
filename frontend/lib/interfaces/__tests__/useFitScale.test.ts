/**
 * Pins `fitScale`, the one scaling rule shared by every surface that renders an interface at its
 * own fixed viewport (canvas node, list card, side panel, fullscreen, share, marketplace).
 * A wrong scale here silently distorts or crops the interface on all of them at once.
 */
import { describe, it, expect } from 'vitest';
import { fitScale } from '../useFitScale';

const VERTICAL = { width: 1080, height: 1920 };
const CLASSIC = { width: 1280, height: 800 };

describe('fitScale', () => {
  describe('contain', () => {
    it('letterboxes on the tighter axis so the whole interface stays visible', () => {
      // A 400x600 box against 1080x1920: min(400/1080, 600/1920) = 0.3125 (height-bound).
      expect(fitScale({ width: 400, height: 600 }, VERTICAL, 'contain')).toBe(0.3125);
    });

    it('is width-bound when the box is the wider constraint', () => {
      // 640x800 against 1280x800: min(0.5, 1) = 0.5.
      expect(fitScale({ width: 640, height: 800 }, CLASSIC, 'contain')).toBe(0.5);
    });

    it('needs both dimensions - an unmeasured height yields no scale', () => {
      expect(fitScale({ width: 400, height: 0 }, VERTICAL, 'contain')).toBe(0);
    });
  });

  describe('width', () => {
    it('fills the width and ignores the box height (the caller derives it from the ratio)', () => {
      expect(fitScale({ width: 640, height: 10 }, CLASSIC, 'width')).toBe(0.5);
    });
  });

  describe('allowUpscale', () => {
    it('upscales by default so a thumbnail fills its card', () => {
      // 2560 wide against a 1280 viewport = 2x, kept.
      expect(fitScale({ width: 2560, height: 1600 }, CLASSIC, 'contain')).toBe(2);
    });

    it('clamps at 1 when upscaling is off, so a small interface is never blown up blurry', () => {
      // The side panel and the fullscreen view rely on this clamp.
      expect(fitScale({ width: 2560, height: 1600 }, CLASSIC, 'contain', false)).toBe(1);
    });

    it('still downscales when upscaling is off', () => {
      expect(fitScale({ width: 640, height: 400 }, CLASSIC, 'contain', false)).toBe(0.5);
    });
  });

  describe('guards', () => {
    it('yields 0 for an unmeasured box, so callers can render a placeholder frame', () => {
      expect(fitScale({ width: 0, height: 0 }, CLASSIC, 'contain')).toBe(0);
      expect(fitScale({ width: 0, height: 0 }, CLASSIC, 'width')).toBe(0);
    });

    it('yields 0 for a degenerate viewport instead of dividing by zero', () => {
      expect(fitScale({ width: 400, height: 600 }, { width: 0, height: 0 }, 'contain')).toBe(0);
    });
  });
});
