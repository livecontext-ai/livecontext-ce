/**
 * Chat Hooks Module V2
 *
 * Refactored Architecture:
 * - Uses StreamingContext as single source of truth for streaming state
 * - Clean separation between UI state and streaming state
 */

export { useMessageHandlersV2 } from './useMessageHandlersV2';
