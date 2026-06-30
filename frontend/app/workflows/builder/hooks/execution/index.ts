/**
 * Execution hooks for workflow runs.
 *
 * These hooks provide a cleaner API for managing workflow execution
 * streaming connections, polling, and execution state.
 *
 * Note: Streaming connection management is now handled by WorkflowRunManager.
 */

export { useWorkflowStreaming } from './useWorkflowStreaming';
