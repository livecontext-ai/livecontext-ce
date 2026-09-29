'use client';

import { useCallback } from 'react';
import dynamic from 'next/dynamic';
import { internalsSymbol, useStore, type EdgeProps, type ReactFlowState } from 'reactflow';
import { correctHandlePoint, type MeasuredHandle, type NodeBox } from './heroEdgeGeometry';

const BuilderEdge = dynamic(() => import('@/app/workflows/builder/components/BuilderEdge').then((module) => module.BuilderEdge), { ssr: false });

interface Endpoint {
  node: NodeBox;
  handle: MeasuredHandle | undefined;
}

/** The node box and the handle an edge attaches to, read from ReactFlow's store. */
export function endpointOf(state: ReactFlowState, nodeId: string, side: 'source' | 'target', handleId: string | null | undefined): Endpoint | undefined {
  const node = state.nodeInternals.get(nodeId);
  if (!node?.positionAbsolute || !node.width || !node.height) return undefined;
  const bounds = node[internalsSymbol]?.handleBounds?.[side] ?? [];
  const handle = (handleId ? bounds.find((candidate) => candidate.id === handleId) : undefined) ?? bounds[0];
  return {
    node: { x: node.positionAbsolute.x, y: node.positionAbsolute.y, width: node.width, height: node.height },
    handle: handle ? { x: handle.x, y: handle.y, width: handle.width, height: handle.height, position: handle.position } : undefined,
  };
}

const same = (a?: NodeBox | MeasuredHandle, b?: NodeBox | MeasuredHandle) =>
  a === b || (!!a && !!b && a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height);

/** Re-render only when a number that feeds the path changed. */
export const sameEndpoint = (a: Endpoint | undefined, b: Endpoint | undefined) =>
  a === b || (!!a && !!b && same(a.node, b.node) && same(a.handle, b.handle) && a.handle?.position === b.handle?.position);

/**
 * The product's edge, fed endpoints that hold under the hero's CSS scale. ReactFlow's own
 * handle positions are shrunk by that scale on a phone, which drew every connection off to
 * the left of its cards; see heroEdgeGeometry for the measurement and the inversion.
 */
export function HeroEdge(props: EdgeProps) {
  const source = useStore(
    useCallback((state: ReactFlowState) => endpointOf(state, props.source, 'source', props.sourceHandleId), [props.source, props.sourceHandleId]),
    sameEndpoint,
  );
  const target = useStore(
    useCallback((state: ReactFlowState) => endpointOf(state, props.target, 'target', props.targetHandleId), [props.target, props.targetHandleId]),
    sameEndpoint,
  );
  const start = correctHandlePoint(source?.node, source?.handle, { x: props.sourceX, y: props.sourceY });
  const end = correctHandlePoint(target?.node, target?.handle, { x: props.targetX, y: props.targetY });
  return <BuilderEdge {...props} sourceX={start.x} sourceY={start.y} targetX={end.x} targetY={end.y} />;
}
