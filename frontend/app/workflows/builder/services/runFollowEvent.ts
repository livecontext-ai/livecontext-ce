/**
 * The camera-follow contract, in one place.
 *
 * A hook dispatches the event and a canvas answers it, so neither owns the name: with
 * the string spelled out in both files, renaming it on one side leaves the other
 * listening for something nobody sends, and every test still passes while the feature
 * is quietly dead. Same reasoning as `lib/workflow/layoutAppliedEvent.ts`.
 */

export const WORKFLOW_FOLLOW_NODES_EVENT = 'workflowFollowNodes';

/** How long the camera takes to travel, in ms. */
export const FOLLOW_TRANSITION_MS = 600;

/**
 * Trailing debounce before a move, in ms.
 *
 * Steps change state in bursts, and moving on each one restarts the travel a fraction
 * of the way in, which reads as a lurch.
 */
export const FOLLOW_DEBOUNCE_MS = 260;

/**
 * Floor between two moves, in ms.
 *
 * The debounce alone does not deliver the smoothness it promises: a set changing every
 * 300 to 500 ms clears the debounce every time and still cuts each travel short. This
 * keeps one move from starting before the previous one has landed.
 */
export const FOLLOW_MIN_GAP_MS = FOLLOW_TRANSITION_MS;

export interface FollowNodesEventDetail {
  /** The canvas this move is for. Several are mounted at once. */
  workflowId?: string;
  /** Already resolved to node ids by the dispatcher. */
  nodeIds: string[];
}
