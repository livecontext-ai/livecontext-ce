import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';

/**
 * Efficiently checks if nodes have changed (for streaming update optimization).
 * Compares status, statusCounts, and loopChildren changes.
 */
export function nodesHaveChanged(
  oldNodes: Node<BuilderNodeData>[],
  newNodes: Node<BuilderNodeData>[]
): boolean {
  if (oldNodes.length !== newNodes.length) {
    console.log('[nodesHaveChanged] Length changed:', oldNodes.length, '->', newNodes.length);
    return true;
  }

  // Create a map for O(1) lookup by ID (order might change)
  const newNodeMap = new Map(newNodes.map(n => [n.id, n]));

  for (const oldNode of oldNodes) {
    const newNode = newNodeMap.get(oldNode.id);
    if (!newNode) {
      console.log('[nodesHaveChanged] Node removed:', oldNode.id);
      return true;
    }

    const oldStatus = (oldNode.data as any)?.status;
    const newStatus = (newNode.data as any)?.status;
    if (oldStatus !== newStatus) {
      console.log('[nodesHaveChanged] Status changed for node', oldNode.id, ':', oldStatus, '->', newStatus);
      return true;
    }

    // Deep compare statusCounts
    const oldCounts = (oldNode.data as any)?.statusCounts;
    const newCounts = (newNode.data as any)?.statusCounts;
    if (oldCounts !== newCounts) {
      if (!oldCounts || !newCounts) {
        console.log('[nodesHaveChanged] StatusCounts changed for node', oldNode.id, ':', oldCounts, '->', newCounts);
        return true; // One is undefined, changed
      }
      const oldKeys = Object.keys(oldCounts);
      const newKeys = Object.keys(newCounts);
      if (oldKeys.length !== newKeys.length) {
        console.log('[nodesHaveChanged] StatusCounts keys changed for node', oldNode.id);
        return true;
      }
      for (const key of oldKeys) {
        if (oldCounts[key] !== newCounts[key]) {
          console.log('[nodesHaveChanged] StatusCounts value changed for node', oldNode.id, 'key', key, ':', oldCounts[key], '->', newCounts[key]);
          return true;
        }
      }
    }

    // Browser-agent live-view coords. These arrive via the agentBrowseStep
    // event mid-execution while status is already 'running' - without a
    // dedicated check the diff would miss them and the eye button would
    // open the panel with stale (or empty) cdpToken/cdpWsUrl.
    const browserKeys: ReadonlyArray<keyof BuilderNodeData> = [
      'lastBrowserSessionId', 'lastBrowserCdpToken', 'lastBrowserCdpWsUrl',
      'lastBrowserRunId', 'lastBrowserStepIndex', 'lastBrowserCurrentUrl',
    ];
    for (const k of browserKeys) {
      if ((oldNode.data as any)?.[k] !== (newNode.data as any)?.[k]) {
        console.log('[nodesHaveChanged] Browser coord changed for', oldNode.id, k);
        return true;
      }
    }

    // Check loopChildren changes for loop nodes
    const oldLoopChildren = (oldNode.data as any)?.loopChildren;
    const newLoopChildren = (newNode.data as any)?.loopChildren;
    if (oldLoopChildren !== newLoopChildren) {
      if (Array.isArray(oldLoopChildren) && Array.isArray(newLoopChildren)) {
        if (oldLoopChildren.length !== newLoopChildren.length) {
          console.log('[nodesHaveChanged] LoopChildren length changed for node', oldNode.id);
          return true;
        }
        for (let i = 0; i < oldLoopChildren.length; i++) {
          const oldChild = oldLoopChildren[i];
          const newChild = newLoopChildren[i];
          if (oldChild.status !== newChild.status) {
            console.log('[nodesHaveChanged] LoopChild status changed for', oldNode.id, 'child', oldChild.id, ':', oldChild.status, '->', newChild.status);
            return true;
          }
          // Compare loopChild statusCounts
          const oldChildCounts = oldChild.statusCounts;
          const newChildCounts = newChild.statusCounts;
          if (oldChildCounts !== newChildCounts) {
            if (!oldChildCounts || !newChildCounts) {
              console.log('[nodesHaveChanged] LoopChild statusCounts changed for', oldNode.id, 'child', oldChild.id);
              return true;
            }
            for (const key of Object.keys(oldChildCounts)) {
              if (oldChildCounts[key] !== newChildCounts[key]) {
                console.log('[nodesHaveChanged] LoopChild statusCounts value changed for', oldNode.id, 'child', oldChild.id, 'key', key);
                return true;
              }
            }
            for (const key of Object.keys(newChildCounts)) {
              if (!(key in oldChildCounts)) {
                console.log('[nodesHaveChanged] LoopChild statusCounts new key for', oldNode.id, 'child', oldChild.id, 'key', key);
                return true;
              }
            }
          }
        }
      } else if (oldLoopChildren || newLoopChildren) {
        console.log('[nodesHaveChanged] LoopChildren changed for node', oldNode.id);
        return true;
      }
    }
  }
  console.log('[nodesHaveChanged] No changes detected');
  return false;
}

/**
 * Efficiently checks if edges have changed (for streaming update optimization).
 * Compares status and statusCounts changes.
 */
export function edgesHaveChanged(oldEdges: Edge[], newEdges: Edge[]): boolean {
  if (oldEdges.length !== newEdges.length) return true;
  for (let i = 0; i < oldEdges.length; i++) {
    const oldEdge = oldEdges[i];
    const newEdge = newEdges[i];
    if (oldEdge.id !== newEdge.id) return true;
    if ((oldEdge.data as any)?.status !== (newEdge.data as any)?.status) return true;

    // Deep compare statusCounts
    const oldCounts = (oldEdge.data as any)?.statusCounts;
    const newCounts = (newEdge.data as any)?.statusCounts;
    if (oldCounts === newCounts) continue; // Same reference, no change
    if (!oldCounts || !newCounts) return true; // One is undefined, changed
    const oldKeys = Object.keys(oldCounts);
    const newKeys = Object.keys(newCounts);
    if (oldKeys.length !== newKeys.length) return true;
    for (const key of oldKeys) {
      if (oldCounts[key] !== newCounts[key]) return true;
    }
  }
  return false;
}
