/**
 * Lightweight module-level store for canvas nodes.
 * Set by WorkflowBuilder, readable by any component that needs to
 * resolve step aliases to node data (e.g. for icons).
 */
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';

let _nodes: Node<BuilderNodeData>[] = [];

export function setCanvasNodes(nodes: Node<BuilderNodeData>[]) {
  _nodes = nodes;
}

export function getCanvasNodes(): Node<BuilderNodeData>[] {
  return _nodes;
}
