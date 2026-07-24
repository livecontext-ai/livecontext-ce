import { describe, it, expect } from 'vitest';
import { snapBoxToFormat, MAX_NODE_WIDTH } from '../interfaceNodeBox';

/**
 * The interface node's canvas box is snapped to the interface's declared format so the
 * node IS the format (no internal letterbox, ring hugs the real shape). These specs pin
 * the snap maths shared by the node's effect and any future caller.
 */
describe('snapBoxToFormat', () => {
  it('unset box + classic 1280x800 keeps the historical 400x250 default', () => {
    expect(snapBoxToFormat({ width: 1280, height: 800 }, {})).toEqual({ width: 400, height: 250 });
  });

  it('unset box + vertical 1080x1920 gives a usable portrait node (225x400), not a letterboxed strip', () => {
    expect(snapBoxToFormat({ width: 1080, height: 1920 }, {})).toEqual({ width: 225, height: 400 });
  });

  it('unset box + square 1080x1080 fills the default snap box (400x400)', () => {
    expect(snapBoxToFormat({ width: 1080, height: 1080 }, {})).toEqual({ width: 400, height: 400 });
  });

  it('a box that already has the format ratio is preserved (user resize survives re-renders)', () => {
    expect(snapBoxToFormat({ width: 1920, height: 1080 }, { width: 800, height: 450 }))
      .toEqual({ width: 800, height: 450 });
  });

  it('a near-match box (within the 2% ratio tolerance) is corrected to the exact ratio, not reset', () => {
    // 405x250 is 1.25% off the 16:10 ratio - a rounding artifact, not a format change.
    expect(snapBoxToFormat({ width: 1280, height: 800 }, { width: 405, height: 250 }))
      .toEqual({ width: 400, height: 250 });
  });

  it('an already-snapped box is a fixed point (no drift across re-renders)', () => {
    const first = snapBoxToFormat({ width: 1080, height: 1920 }, {});
    const second = snapBoxToFormat({ width: 1080, height: 1920 }, first);
    expect(second).toEqual(first);
  });

  it('a ratio-MISMATCHED box re-snaps from the default box - regression: containing the new format inside the old box ratcheted the node smaller on every format change', () => {
    // classic-shaped 400x250 box, format now vertical: NOT 141x250 (contain), but a fresh 225x400.
    expect(snapBoxToFormat({ width: 1080, height: 1920 }, { width: 400, height: 250 }))
      .toEqual({ width: 225, height: 400 });
  });

  it('format round-trip A->B->A restores the original size (no ratchet)', () => {
    const classic = { width: 1280, height: 800 };
    const vertical = { width: 1080, height: 1920 };
    const a = snapBoxToFormat(classic, {});
    const b = snapBoxToFormat(vertical, a);
    const back = snapBoxToFormat(classic, b);
    expect(back).toEqual(a);
  });

  it('a box with only one dimension set falls back to the default snap box', () => {
    expect(snapBoxToFormat({ width: 1280, height: 800 }, { width: 700 }))
      .toEqual({ width: 400, height: 250 });
  });

  it('min bounds clamp without breaking the ratio (banner 3:1 resized tiny)', () => {
    // 150x50 has the exact 3:1 banner ratio; min-height 80 wins -> 240x80, still 3:1.
    expect(snapBoxToFormat({ width: 1500, height: 500 }, { width: 150, height: 50 }))
      .toEqual({ width: 240, height: 80 });
  });

  it('max bounds clamp without breaking the ratio (vertical resized huge)', () => {
    // 1125x2000 has the exact 9:16 ratio; max-height 800 wins -> 450x800.
    expect(snapBoxToFormat({ width: 1080, height: 1920 }, { width: 1125, height: 2000 }))
      .toEqual({ width: 450, height: 800 });
  });

  it('a degenerate custom format (2160x16) is hard-capped at the max width instead of exploding', () => {
    // Ratio preservation would demand 10800x80; the cap wins and the thumbnail absorbs
    // the resulting letterbox (the ring hugs the content frame, not the box).
    const result = snapBoxToFormat({ width: 2160, height: 16 }, {});
    expect(result.width).toBe(MAX_NODE_WIDTH);
    expect(result.height).toBe(80);
  });

  /**
   * The run-mode snap in InterfacePreviewNode feeds its own result back in: it writes
   * the box, re-reads it from the ReactFlow store, and re-snaps. That loop only ends
   * because re-snapping a snapped box settles within the effect's 1px tolerance. The
   * property is load-bearing, so pin it rather than leave it to luck - including on
   * the degenerate customs, where rounding does drift a little before settling.
   */
  describe('re-snapping converges (the run-mode effect re-feeds its own output)', () => {
    const FORMATS = [
      { name: 'classic', width: 1280, height: 800 },
      { name: 'vertical', width: 1080, height: 1920 },
      { name: 'square', width: 1080, height: 1080 },
      { name: 'mobile', width: 390, height: 844 },
      { name: 'ultrawide degenerate', width: 2160, height: 16 },
      { name: 'sliver degenerate', width: 16, height: 2160 },
      { name: 'odd ratio', width: 51, height: 16 },
    ];
    const START_BOXES = [
      { label: 'unset', box: {} },
      { label: 'importer default 400x250', box: { width: 400, height: 250 } },
      { label: 'tiny', box: { width: 100, height: 80 } },
      { label: 'huge', box: { width: 800, height: 800 } },
    ];

    it.each(FORMATS)('$name reaches a fixed point within a few passes from every starting box', (viewport) => {
      for (const { label, box } of START_BOXES) {
        let current = snapBoxToFormat(viewport, box);
        let passes = 0;
        // Re-snap until the result stops moving by more than the effect's tolerance.
        for (; passes < 5; passes++) {
          const next = snapBoxToFormat(viewport, current);
          if (Math.abs(next.width - current.width) <= 1 && Math.abs(next.height - current.height) <= 1) break;
          current = next;
        }
        expect(passes, `${viewport.name} from ${label} never settled`).toBeLessThan(5);
        // And once settled it must STAY settled: a two-cycle here would make the
        // effect dispatch forever without ever tripping the tolerance guard.
        const again = snapBoxToFormat(viewport, current);
        expect(Math.abs(again.width - current.width)).toBeLessThanOrEqual(1);
        expect(Math.abs(again.height - current.height)).toBeLessThanOrEqual(1);
      }
    });
  });
});
