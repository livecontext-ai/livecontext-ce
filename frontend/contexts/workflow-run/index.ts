/**
 * Workflow Run Module - Clean, SOLID architecture for workflow execution.
 *
 * Architecture:
 * - RunStateStore: Single source of truth for state
 * - WorkflowRunManager: Facade coordinating everything
 * - Real-time events via WebSocket (useChannel hook)
 *
 * Usage:
 * ```typescript
 * import { getWorkflowRunManager } from '@/contexts/workflow-run';
 *
 * const manager = getWorkflowRunManager(runId);
 * await manager.initialize();
 *
 * // Execute steps
 * const result = await manager.executeStep('mcp:myStep');
 *
 * // Subscribe to state changes
 * const unsubscribe = manager.subscribe((state) => {
 *   console.log('Ready steps:', state.readySteps);
 * });
 * ```
 */

// State Store
export {
  RunStateStore,
  getRunStateStore,
  deleteRunStateStore,
  hasRunStateStore,
  TERMINAL_STATUSES,
  type RunState,
  type NodeState,
  type RunStatus,
  type TriggerType,
  type ExecutionMode,
  type WorkflowStatusState,
} from './RunStateStore';

// Debug Logger
export { streamDebug } from './streamingDebug';

// Main Manager (Facade)
export {
  WorkflowRunManager,
  getWorkflowRunManager,
  deleteWorkflowRunManager,
  hasWorkflowRunManager,
  forEachWorkflowRunManager,
  type StepExecutionResult,
  type CoreExecutionResult,
  type RerunResult,
} from './WorkflowRunManager';
