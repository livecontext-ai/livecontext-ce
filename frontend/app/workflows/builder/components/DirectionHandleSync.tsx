import * as React from 'react';
import { useUpdateNodeInternals } from 'reactflow';

/**
 * Re-measures node handles when the reading direction changes.
 *
 * Must live INSIDE ReactFlowProvider (for `useUpdateNodeInternals`), so it is a tiny
 * child mounted in <ReactFlow>, not part of the BuilderCanvas body (which is outside
 * the provider). Without it, ReactFlow keeps each handle's OLD measured bounds when a
 * node re-renders with its handles on new edges - the "handles stay at the bottom
 * after switching back to horizontal" bug.
 *
 * It re-measures on EVERY direction change (a user toggle AND the loader seeding the
 * plan's saved direction), because both move handles. It deliberately does NOT
 * re-flow the graph: re-flowing on the loader's seed would trash the author's saved
 * node positions. The user toggle re-flows separately (in the settings panel), which
 * is the only place a direction change should move nodes.
 */
export function DirectionHandleSync({ direction, nodeIds }: { direction: string; nodeIds: string[] }) {
  const updateNodeInternals = useUpdateNodeInternals();
  const prevDirection = React.useRef(direction);
  const nodeIdsRef = React.useRef(nodeIds);
  // Keep the latest ids in a ref (synced post-render) so the direction effect can read
  // them without listing `nodeIds` as a dependency (which would re-fire on every edit).
  React.useEffect(() => {
    nodeIdsRef.current = nodeIds;
  });

  React.useEffect(() => {
    if (prevDirection.current === direction) return;
    prevDirection.current = direction;
    // DOUBLE rAF, deliberately. On a direction flip the handles swap edges (Left/Right
    // <-> Top/Bottom via a CSS position + transform change) AND the in-canvas toggle
    // re-flows node positions in the same commit. A SINGLE rAF fires before the browser
    // has committed the moved handles' new box, so `updateNodeInternals` reads the OLD
    // handle position and every edge draws to where the handle USED to be - the gap
    // between the arrow and the handle the user reported. Measuring on the SECOND frame
    // (after layout is committed) makes the edge endpoints land on the handles. We call
    // on both frames so the fix holds even when the second rAF is coalesced.
    let raf2 = 0;
    const raf1 = requestAnimationFrame(() => {
      nodeIdsRef.current.forEach((id) => updateNodeInternals(id));
      raf2 = requestAnimationFrame(() => {
        nodeIdsRef.current.forEach((id) => updateNodeInternals(id));
      });
    });
    return () => {
      cancelAnimationFrame(raf1);
      if (raf2) cancelAnimationFrame(raf2);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fire on direction change only
  }, [direction]);

  return null;
}
