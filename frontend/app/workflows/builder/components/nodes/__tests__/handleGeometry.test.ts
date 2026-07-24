import { describe, it, expect } from 'vitest';
import { Position } from 'reactflow';
import {
  branchSpreadPercent,
  getBranchHandleGeometry,
  getBranchHandleGeometryAt,
  getBranchRowFlow,
  getSideAttachment,
  getSourceHandleGeometry,
  getTargetHandleGeometry,
  isFlowBackward,
} from '../handleGeometry';

/**
 * These pin the ONE invariant the whole vertical-layout feature rests on: the flow
 * axis owns two opposite edges of the node, and everything else must live on the
 * other axis. Before this module the 44 handle sites each hardcoded their side, so
 * a node could silently disagree with the canvas it was drawn on.
 */
describe('handleGeometry', () => {
  describe('flow-axis handles', () => {
    it('puts target and source on opposite edges in horizontal', () => {
      expect(getTargetHandleGeometry('horizontal').position).toBe(Position.Left);
      expect(getSourceHandleGeometry('horizontal').position).toBe(Position.Right);
    });

    it('puts target and source on opposite edges in vertical', () => {
      expect(getTargetHandleGeometry('vertical').position).toBe(Position.Top);
      expect(getSourceHandleGeometry('vertical').position).toBe(Position.Bottom);
    });

    it('centres the handle on the cross axis, so it sits mid-edge', () => {
      // Horizontal: pinned on the vertical centre of the left/right border.
      expect(getTargetHandleGeometry('horizontal').style).toMatchObject({
        top: '50%',
        transform: 'translateY(-50%)',
      });
      // Vertical: pinned on the horizontal centre of the top/bottom border.
      expect(getTargetHandleGeometry('vertical').style).toMatchObject({
        left: '50%',
        transform: 'translateX(-50%)',
      });
    });

    it('never leaves an offset on the axis it does not own', () => {
      // A leftover `left: -6` in vertical would drag the dot off the corner.
      expect(getTargetHandleGeometry('vertical').style).not.toHaveProperty('left', -6);
      expect(getSourceHandleGeometry('vertical').style).not.toHaveProperty('right', -6);
      expect(getTargetHandleGeometry('horizontal').style).not.toHaveProperty('top', -6);
      expect(getSourceHandleGeometry('horizontal').style).not.toHaveProperty('bottom', -6);
    });
  });

  describe('branch handles', () => {
    it('sends outgoing branches to the source edge for each direction', () => {
      expect(getBranchHandleGeometry('horizontal', true).position).toBe(Position.Right);
      expect(getBranchHandleGeometry('vertical', true).position).toBe(Position.Bottom);
    });

    it('pins an incoming branch to the LEFT of its row in horizontal', () => {
      // Merge pins its INPUTS to rows, so the incoming case is real, not theoretical.
      expect(getBranchHandleGeometry('horizontal', false).position).toBe(Position.Left);
    });

    it('clears the node padding, which differs per axis', () => {
      // The node box is px-5 py-4: a row-pinned handle needs a bigger offset
      // horizontally (20px padding) than vertically (16px) to land on the border.
      expect(getBranchHandleGeometry('horizontal', true).style).toMatchObject({ right: -27 });
      expect(getBranchHandleGeometry('vertical', true).style).toMatchObject({ bottom: -23 });
    });

    it('does NOT pin an incoming branch to its row in vertical (the row is under the header)', () => {
      // A vertical incoming handle cannot stay on its row: the rows sit below the
      // header while the target edge is the node's TOP. The row-pinned helper is
      // outgoing-only in vertical, and Merge/While hoist the incoming handles to node
      // level with getBranchHandleGeometryAt instead. Guards against the ~61px-inside
      // regression where a row-pinned `top: -23` landed in the header.
      const geo = getBranchHandleGeometry('vertical', false);
      expect(geo.position).toBe(Position.Bottom); // outgoing default, NOT a row-pinned top
    });

    it('hoists incoming vertical handles to the TOP border, spread by index', () => {
      const first = getBranchHandleGeometryAt('vertical', false, 0, 2);
      const second = getBranchHandleGeometryAt('vertical', false, 1, 2);
      expect(first.position).toBe(Position.Top);
      expect(second.position).toBe(Position.Top);
      // On the node's border (small offset), not inside it, and spread apart.
      expect(first.style).toMatchObject({ top: -6, left: '35%' });
      expect(second.style).toMatchObject({ top: -6, left: '65%' });
    });

    it('hoists outgoing vertical handles to the BOTTOM border, spread by index', () => {
      const geo = getBranchHandleGeometryAt('vertical', true, 1, 3);
      expect(geo.position).toBe(Position.Bottom);
      expect(geo.style).toMatchObject({ bottom: -6, left: '50%' });
    });

    it('flips the row container so each handle stays under its own label', () => {
      // This is what lets the handle stay INSIDE its row instead of being hoisted
      // to node level and losing its association with the branch it belongs to.
      expect(getBranchRowFlow('horizontal')).toContain('flex-col');
      expect(getBranchRowFlow('vertical')).toContain('flex-row');
    });
  });

  describe('branchSpreadPercent', () => {
    it('centres a lone handle', () => {
      expect(branchSpreadPercent(0, 1)).toBe(50);
    });

    it('splits two handles symmetrically about the centre', () => {
      expect(branchSpreadPercent(0, 2)).toBe(35);
      expect(branchSpreadPercent(1, 2)).toBe(65);
    });

    it('spreads three or more across the middle half, clear of the corners', () => {
      expect(branchSpreadPercent(0, 3)).toBe(25);
      expect(branchSpreadPercent(1, 3)).toBe(50);
      expect(branchSpreadPercent(2, 3)).toBe(75);
      // Whatever the count, nothing reaches a rounded corner.
      for (const total of [4, 5, 8]) {
        for (let i = 0; i < total; i++) {
          expect(branchSpreadPercent(i, total)).toBeGreaterThanOrEqual(25);
          expect(branchSpreadPercent(i, total)).toBeLessThanOrEqual(75);
        }
      }
    });
  });

  describe('getSideAttachment', () => {
    it('hangs attachments off the edge the flow does not use', () => {
      // Horizontal flow owns left/right, so the bar goes below.
      expect(getSideAttachment('horizontal', 8)).toMatchObject({ top: 'calc(100% + 8px)', left: '50%' });
      // Vertical flow owns top/bottom, so the bar goes beside.
      expect(getSideAttachment('vertical', 8)).toMatchObject({ left: 'calc(100% + 8px)', top: '50%' });
    });

    it('clears a whole node-width when something already occupies the band', () => {
      // Horizontal: the occupant is a ~24px row, so a px nudge clears it.
      expect(getSideAttachment('horizontal', 8, 32)).toMatchObject({ top: 'calc(100% + 40px)' });
      // Vertical: the occupant (file strip) is as WIDE as the node, so a px nudge
      // would leave the bar inside it, under the strip's higher z-index. Only a
      // second 100% clears it. Regression for the vertical half of 3974c865e.
      expect(getSideAttachment('vertical', 8, 32)).toMatchObject({ left: 'calc(200% + 16px)' });
    });
  });

  describe('isFlowBackward', () => {
    it('is false for an ordinary forward edge in either direction', () => {
      // This is the regression that matters: with the old position-derived test,
      // EVERY forward edge on a top-to-bottom canvas (sourceY < targetY) was
      // flagged backward and silently forced to smoothstep.
      expect(isFlowBackward('horizontal', 0, 0, 100, 0)).toBe(false);
      expect(isFlowBackward('vertical', 0, 0, 0, 100)).toBe(false);
    });

    it('is true only for an edge running against the flow', () => {
      expect(isFlowBackward('horizontal', 100, 0, 0, 0)).toBe(true);
      expect(isFlowBackward('vertical', 0, 100, 0, 0)).toBe(true);
    });

    it('ignores the cross axis entirely', () => {
      // A forward edge that also steps sideways/down is still forward.
      expect(isFlowBackward('horizontal', 0, 0, 100, 500)).toBe(false);
      expect(isFlowBackward('vertical', 0, 0, 500, 100)).toBe(false);
    });
  });
});
