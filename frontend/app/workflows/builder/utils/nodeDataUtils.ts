/**
 * Utility functions for node data management.
 *
 * This file defines the single source of truth for runtime-only node properties.
 * Runtime props are added by usePreparedGraph for interactivity but should NOT
 * be persisted to the workflow plan or node state.
 */

import type { BuilderNodeData } from '../types';

/**
 * Runtime callback props added by usePreparedGraph.
 * These enable node interactivity but are recreated on each render.
 */
export interface RuntimeNodeCallbacks {
  onDeleteNode?: () => void;
  onDuplicateNode?: () => void;
  onTogglePreview?: () => void;
  onNodeUpdate?: (data: BuilderNodeData) => void;
  onExtractLoopChild?: (childId: string) => void;
  onNoteUpdate?: (updates: any) => void;
  onLoopClick?: () => void;
  onLoopChildClick?: (childId: string) => void;
  onCreateNode?: (item: any, position: any, options?: any) => void;
  onConnect?: (connection: any) => void;
}

/**
 * Runtime state props added by usePreparedGraph.
 * These are computed/derived state that should not be persisted.
 */
export interface RuntimeNodeState {
  isPreviewMode?: boolean;
  validationIssues?: string[];
  _matchNodeId?: string;
  highlightState?: string;
  selectedLoopChildId?: string;
}

/**
 * Combined type for all runtime props.
 */
export type RuntimeNodeProps = RuntimeNodeCallbacks & RuntimeNodeState;

/**
 * List of all runtime property keys.
 *
 * IMPORTANT: When adding new runtime props in usePreparedGraph,
 * add them here too to ensure they are stripped before persistence.
 */
const RUNTIME_PROP_KEYS: (keyof RuntimeNodeProps)[] = [
  // Callbacks
  'onDeleteNode',
  'onDuplicateNode',
  'onTogglePreview',
  'onNodeUpdate',
  'onExtractLoopChild',
  'onNoteUpdate',
  'onLoopClick',
  'onLoopChildClick',
  'onCreateNode',
  'onConnect',
  // Matching
  '_matchNodeId',
  // State
  'isPreviewMode',
  'validationIssues',
  'highlightState',
  'selectedLoopChildId',
];

/**
 * Strips runtime-only props from node data before persistence.
 *
 * Use this in handleNodeUpdate to clean data before saving to state.
 * Runtime props are added by usePreparedGraph and should not be persisted.
 *
 * @param data - Node data potentially containing runtime props
 * @returns Clean node data suitable for persistence
 */
export function stripRuntimeProps<T extends object>(data: T): Omit<T, keyof RuntimeNodeProps> {
  const result = { ...data };
  for (const prop of RUNTIME_PROP_KEYS) {
    delete (result as Record<string, unknown>)[prop];
  }
  return result as Omit<T, keyof RuntimeNodeProps>;
}
