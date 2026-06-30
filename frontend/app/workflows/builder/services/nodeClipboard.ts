/**
 * Module-level node clipboard for the workflow builder's context menu and
 * Ctrl+C / Ctrl+V shortcuts. Lives outside React so a Copy on one node and a
 * Paste on the empty canvas (two different menu components) share one buffer,
 * and `useClipboardHasContent` lets the Paste affordance react to its state.
 *
 * The stored snapshot is sanitized at write time (runtime callbacks stripped),
 * so it never pins live render closures in memory.
 */
import * as React from 'react';
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { stripRuntimeProps } from '../utils/nodeDataUtils';

export interface ClipboardPayload {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
}

let payload: ClipboardPayload | null = null;
const listeners = new Set<() => void>();

function emit(): void {
  listeners.forEach((listener) => listener());
}

/** Strip runtime props from each node's data so the buffer is a plain snapshot. */
function sanitize(nodes: Node<BuilderNodeData>[]): Node<BuilderNodeData>[] {
  return nodes.map((node) => ({ ...node, data: stripRuntimeProps(node.data) as BuilderNodeData }));
}

export const nodeClipboard = {
  /** Replace the buffer. An empty/cleared payload resets it to null. */
  set(next: ClipboardPayload | null): void {
    payload = next && next.nodes.length > 0 ? { nodes: sanitize(next.nodes), edges: next.edges } : null;
    emit();
  },
  get(): ClipboardPayload | null {
    return payload;
  },
  clear(): void {
    payload = null;
    emit();
  },
  hasContent(): boolean {
    return payload !== null && payload.nodes.length > 0;
  },
  subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};

/** Reactive `true` when the clipboard holds at least one node. */
export function useClipboardHasContent(): boolean {
  return React.useSyncExternalStore(
    nodeClipboard.subscribe,
    nodeClipboard.hasContent,
    () => false,
  );
}
