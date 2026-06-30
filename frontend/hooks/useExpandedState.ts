import { useState, useCallback, useEffect, useRef } from 'react';

/**
 * Module-level storage for expanded states.
 * Persists across component re-mounts within the same session.
 * Resets on page refresh (which is the desired behavior).
 */
const expandedStateStore = new Map<string, boolean>();

/**
 * Allowlist: tools that should be EXPANDED by default during streaming.
 * Everything else is collapsed by default.
 *
 * Supports two formats:
 * - Simple tool name: 'visualize'
 * - Tool with action: 'workflow:run_workflow'
 */
export const TOOLS_EXPANDED_BY_DEFAULT = new Set([
  'visualize',
]);

/**
 * Check if a tool should be expanded by default (allowlist).
 * All tools are collapsed by default unless explicitly listed.
 */
function shouldExpandByDefault(toolName?: string, action?: string): boolean {
  if (!toolName) return false;

  const normalizedTool = toolName.toLowerCase();

  // First check tool:action pattern
  if (action) {
    const toolActionKey = `${normalizedTool}:${action.toLowerCase()}`;
    if (TOOLS_EXPANDED_BY_DEFAULT.has(toolActionKey)) {
      return true;
    }
  }

  // Then check simple tool name
  return TOOLS_EXPANDED_BY_DEFAULT.has(normalizedTool);
}

/**
 * Hook to manage expanded/collapsed state that persists across re-renders.
 *
 * Behavior:
 * - If isStreaming=true: collapsed by default (unless tool is in TOOLS_EXPANDED_BY_DEFAULT)
 * - If isStreaming=false (history): collapsed by default
 * - User can always toggle freely, even during streaming
 *
 * @param id - Unique identifier for this expandable item
 * @param isStreaming - When true, applies streaming defaults
 * @param toolName - Optional tool name to check against TOOLS_EXPANDED_BY_DEFAULT
 * @param action - Optional action (for tools like workflow that have sub-actions)
 * @param defaultExpanded - When true, item starts expanded by default (e.g., tool groups)
 * @returns [isExpanded, toggleExpanded] tuple
 */
export function useExpandedState(
  id: string,
  isStreaming = false,
  toolName?: string,
  action?: string,
  defaultExpanded = false,
): [boolean, () => void] {
  // Track if user has manually toggled this item during current streaming session
  const userToggledRef = useRef(false);

  const [isExpanded, setIsExpanded] = useState(() => {
    // Check if already in store (user previously toggled or from history)
    const storedValue = expandedStateStore.get(id);
    if (storedValue !== undefined) {
      return storedValue;
    }

    // During streaming: use defaultExpanded or allowlist
    if (isStreaming) {
      const defaultValue = defaultExpanded || shouldExpandByDefault(toolName, action);
      expandedStateStore.set(id, defaultValue);
      return defaultValue;
    }

    // History/default: use defaultExpanded parameter
    return defaultExpanded;
  });

  // When a NEW item appears during streaming, set its initial state
  // (only if user hasn't manually toggled it)
  useEffect(() => {
    if (isStreaming && !userToggledRef.current && expandedStateStore.get(id) === undefined) {
      const defaultValue = defaultExpanded || shouldExpandByDefault(toolName, action);
      expandedStateStore.set(id, defaultValue);
      setIsExpanded(defaultValue);
    }
  }, [isStreaming, id, toolName, action, defaultExpanded]);

  const toggleExpanded = useCallback(() => {
    userToggledRef.current = true; // Mark as user-toggled
    setIsExpanded(prev => {
      const newValue = !prev;
      expandedStateStore.set(id, newValue);
      return newValue;
    });
  }, [id]);

  return [isExpanded, toggleExpanded];
}
