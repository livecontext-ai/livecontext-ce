/**
 * Centralized label normalization utility.
 *
 * This module provides a single source of truth for normalizing labels across the entire frontend.
 * It ensures consistent normalization for all node types.
 *
 * === PREFIX SYSTEM (7 categories) ===
 *
 * | Prefix      | Category  | Applies To                                              |
 * |-------------|-----------|--------------------------------------------------------|
 * | trigger:    | Entry     | All triggers (webhook, chat, schedule, etc.)            |
 * | mcp:        | MCP       | Tools (MCP tool calls)                                  |
 * | table:      | Table     | CRUD operations (database tables)                       |
 * | agent:      | AI        | Agent, Guardrail, Classify                              |
 * | core:       | Core      | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, User Approval |
 * | note:       | Note      | Notes                                                   |
 * | interface:  | Interface | Interfaces                                              |
 *
 * Normalization rules (same as backend LabelNormalizer):
 * 1. Transliterate accented characters to ASCII (e→e, a→a, u→u, c→c, etc.)
 * 2. Convert to lowercase
 * 3. Replace ALL non-alphanumeric characters with underscores
 * 4. Collapse multiple consecutive underscores into one
 * 5. Remove leading/trailing underscores
 *
 * Examples:
 * - "My Label" -> "my_label"
 * - "If / else" -> "if_else"
 * - "While Loop" -> "while_loop"
 * - "Step-123" -> "step_123"
 * - "Entree IDs" -> "entree_ids"
 * - "Trigger - Joueurs Football - Instagram" -> "trigger_joueurs_football_instagram"
 */

/**
 * Normalizes a label to a slug format compatible with frontend and backend.
 *
 * This is the canonical normalization method used throughout the application.
 * All labels (triggers, steps, core nodes) should use this method to ensure
 * consistent matching between frontend and backend.
 *
 * @param label The label to normalize (can be null or blank)
 * @returns The normalized label, or null if input is null or blank, or if normalization results in empty string
 */
export function normalizeLabel(label: string | null | undefined): string | null {
  if (!label || label.trim() === '') {
    return null;
  }

  const trimmed = label.trim();
  if (trimmed === '') {
    return null;
  }

  // Transliterate accented characters to ASCII (e→e, a→a, c→c, etc.)
  // NFD decomposes characters: e becomes e + combining acute accent
  // Then we remove the combining diacritical marks (Unicode block \u0300-\u036f)
  const ascii = trimmed.normalize('NFD').replace(/[\u0300-\u036f]/g, '');

  // Convert to lowercase
  const lower = ascii.toLowerCase();

  // Replace ALL non-alphanumeric characters with underscores
  const underscored = lower.replace(/[^a-z0-9]/g, '_');

  // Collapse multiple underscores into one
  const collapsed = underscored.replace(/_+/g, '_');

  // Remove leading/trailing underscores
  const normalized = collapsed.replace(/^_|_$/g, '');

  // Return null if normalization results in empty string
  return normalized === '' ? null : normalized;
}

// ========================================================================
// KEY CONSTRUCTION METHODS - 7 categories
// ========================================================================

/**
 * Creates a normalized trigger key from a label.
 * Used for ALL trigger types (webhook, chat, schedule, form, datasource, manual, workflow).
 *
 * Example: "My Webhook" -> "trigger:my_webhook"
 */
export function triggerKey(label: string | null | undefined): string | null {
  const normalized = normalizeLabel(label);
  return normalized != null ? `trigger:${normalized}` : null;
}

/**
 * Creates a normalized MCP key from a label.
 * Used for MCP tool calls.
 *
 * Example: "API Call" -> "mcp:api_call"
 */
export function mcpKey(label: string | null | undefined): string | null {
  const normalized = normalizeLabel(label);
  return normalized != null ? `mcp:${normalized}` : null;
}

/**
 * Creates a normalized table key from a label.
 * Used for CRUD operations (database tables).
 *
 * Example: "Users Table" -> "table:users_table"
 */
export function tableKey(label: string | null | undefined): string | null {
  const normalized = normalizeLabel(label);
  return normalized != null ? `table:${normalized}` : null;
}

/**
 * Creates a normalized agent key from a label.
 * Used for ALL AI reasoning nodes (agent, guardrail, classify).
 *
 * Example: "My Analyzer" -> "agent:my_analyzer"
 */
export function agentKey(label: string | null | undefined): string | null {
  const normalized = normalizeLabel(label);
  return normalized != null ? `agent:${normalized}` : null;
}

/**
 * Creates a normalized core key from a label.
 * Used for ALL core flow nodes: Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, User Approval
 *
 * Example: "Check Status" -> "core:check_status"
 */
export function coreKey(label: string | null | undefined): string | null {
  const normalized = normalizeLabel(label);
  return normalized != null ? `core:${normalized}` : null;
}

/**
 * Creates a normalized note key from a label.
 * Used for notes.
 *
 * Example: "My Note" -> "note:my_note"
 */
export function noteKey(label: string | null | undefined): string | null {
  const normalized = normalizeLabel(label);
  return normalized != null ? `note:${normalized}` : null;
}

/**
 * Creates a normalized interface key from a label.
 * Used for interfaces.
 *
 * Example: "User Form" -> "interface:user_form"
 */
export function interfaceKey(label: string | null | undefined): string | null {
  const normalized = normalizeLabel(label);
  return normalized != null ? `interface:${normalized}` : null;
}

// ========================================================================
// KEY VALIDATION METHODS
// ========================================================================

/**
 * Checks if a string is a normalized key (has a known prefix).
 *
 * Valid prefixes: trigger:, mcp:, table:, agent:, core:, note:, interface:
 */
export function isNormalizedKey(key: string | null | undefined): boolean {
  if (!key || key.trim() === '') {
    return false;
  }
  return key.startsWith('trigger:') || key.startsWith('mcp:') ||
         key.startsWith('table:') || key.startsWith('agent:') ||
         key.startsWith('core:') || key.startsWith('note:') ||
         key.startsWith('interface:');
}

/**
 * Checks if a key is a trigger key.
 */
export function isTriggerKey(key: string | null | undefined): boolean {
  return key != null && key.startsWith('trigger:');
}

/**
 * Checks if a key is an MCP key.
 */
export function isMcpKey(key: string | null | undefined): boolean {
  return key != null && key.startsWith('mcp:');
}

/**
 * Checks if a key is a table key.
 */
export function isTableKey(key: string | null | undefined): boolean {
  return key != null && key.startsWith('table:');
}

/**
 * Checks if a key is an agent key.
 */
export function isAgentKey(key: string | null | undefined): boolean {
  return key != null && key.startsWith('agent:');
}

/**
 * Checks if a key is a core key.
 */
export function isCoreKey(key: string | null | undefined): boolean {
  return key != null && key.startsWith('core:');
}

/**
 * Checks if a key is a note key.
 */
export function isNoteKey(key: string | null | undefined): boolean {
  return key != null && key.startsWith('note:');
}

/**
 * Checks if a key is an interface key.
 */
export function isInterfaceKey(key: string | null | undefined): boolean {
  return key != null && key.startsWith('interface:');
}

// ========================================================================
// UTILITY METHODS
// ========================================================================

/**
 * Extracts the node type from a normalized key.
 * Example: "mcp:api_call" -> "mcp"
 */
export function getNodeType(key: string | null | undefined): string | null {
  if (!key || key.trim() === '') {
    return null;
  }
  const colonIndex = key.indexOf(':');
  if (colonIndex > 0) {
    return key.substring(0, colonIndex);
  }
  return null;
}

/**
 * Extracts the normalized label from a key (without the prefix).
 * Example: "mcp:api_call" -> "api_call"
 */
export function extractLabelFromKey(key: string | null | undefined): string | null {
  if (!key || key.trim() === '') {
    return null;
  }
  const colonIndex = key.indexOf(':');
  if (colonIndex >= 0 && colonIndex < key.length - 1) {
    return key.substring(colonIndex + 1);
  }
  return null;
}

/**
 * Extracts and normalizes a label from a prefixed reference.
 *
 * Handles formats like:
 * - "core:My Label" -> "my_label"
 * - "trigger:Test Trigger" -> "test_trigger"
 * - "mcp:My Step" -> "my_step"
 *
 * @param prefixedRef The prefixed reference (e.g., "core:My Label")
 * @param prefix The prefix to extract (e.g., "core:", "trigger:", "mcp:")
 * @returns The normalized label, or null if input is null/blank or prefix doesn't match
 */
export function extractAndNormalizeLabel(
  prefixedRef: string | null | undefined,
  prefix: string
): string | null {
  if (!prefixedRef || prefixedRef.trim() === '') {
    return null;
  }

  const trimmed = prefixedRef.trim();
  if (trimmed.startsWith(prefix)) {
    const label = trimmed.substring(prefix.length).trim();
    return normalizeLabel(label);
  }

  // If no prefix, assume it's already a label and normalize it
  return normalizeLabel(trimmed);
}

/**
 * Extracts and normalizes a core label from "core:label" format.
 *
 * Core nodes include: Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, User Approval
 *
 * @param coreRef The core reference (e.g., "core:my_loop")
 * @returns The normalized label, or null if input is null/blank
 */
export function extractCoreLabel(coreRef: string | null | undefined): string | null {
  return extractAndNormalizeLabel(coreRef, 'core:');
}

/**
 * Known port patterns for core nodes.
 * Used to strip ports from edge references.
 *
 * Note: Split does NOT use edge ports - it uses internal parallel spawning.
 * Items are passed via unified pattern: {{core:split.output.items}}.
 */
const CORE_PORT_PATTERNS = [
  /^(if|else)$/,              // Decision: if, else
  /^elseif_\d+$/,             // Decision: elseif_0, elseif_1, etc.
  /^case_\d+$/,               // Switch: case_0, case_1, etc.
  /^default$/,                // Switch: default
  /^(body|iterate|exit)$/,    // Loop: body, iterate, exit
  /^branch_\d+$/,             // Fork: branch_0, branch_1, etc.
  /^choice_\d+$/,             // Option: choice_0, choice_1, etc.
  /^(approved|rejected|timeout)$/, // Approval: approved, rejected, timeout
  // Split doesn't use edge ports
];

/**
 * Checks if a string is a known core port.
 */
function isKnownCorePort(str: string): boolean {
  return CORE_PORT_PATTERNS.some(pattern => pattern.test(str));
}

/**
 * Known port patterns for agent nodes.
 * Used to strip ports from edge references.
 */
const AGENT_PORT_PATTERNS = [
  /^category_\d+$/,                    // Classify: category_0, category_1, etc.
  /^(pass|fail)$/,                     // Guardrail: pass, fail
  /^(approved|rejected|timeout)$/,     // User Approval: approved, rejected, timeout
];

/**
 * Checks if a string is a known agent port.
 */
function isKnownAgentPort(str: string): boolean {
  return AGENT_PORT_PATTERNS.some(pattern => pattern.test(str));
}

/**
 * Checks if a string is a known port (core or agent).
 */
function isKnownPort(str: string): boolean {
  return isKnownCorePort(str) || isKnownAgentPort(str);
}

/**
 * Extracts and normalizes a core label from "core:label:port" format,
 * stripping the port suffix if present.
 *
 * This is essential for streaming edge matching where backend sends refs like
 * "core:my_decision:if" but frontend edges have node IDs without ports.
 *
 * Examples:
 * - "core:my_decision:if" -> "my_decision"
 * - "core:my_fork:branch_0" -> "my_fork"
 * - "core:my_loop:body" -> "my_loop"
 * - "core:my_loop" -> "my_loop" (no port)
 *
 * @param coreRef The core reference with optional port
 * @returns The normalized label without port, or null if input is null/blank
 */
export function extractCoreLabelWithoutPort(coreRef: string | null | undefined): string | null {
  if (!coreRef || coreRef.trim() === '') {
    return null;
  }

  const trimmed = coreRef.trim();

  // Strip # suffix first (branch info from streaming)
  const withoutHash = trimmed.split('#')[0];

  // Must start with core:
  if (!withoutHash.startsWith('core:')) {
    return normalizeLabel(trimmed);
  }

  // Remove "core:" prefix
  const afterPrefix = withoutHash.substring('core:'.length);

  // Split by ":" to check for port
  const parts = afterPrefix.split(':');

  if (parts.length === 1) {
    // No port, just the label
    return normalizeLabel(parts[0]);
  }

  // Check if the last part is a known port
  const lastPart = parts[parts.length - 1];
  if (isKnownPort(lastPart)) {
    // Last part is a port, join all except the last
    const labelParts = parts.slice(0, -1);
    return normalizeLabel(labelParts.join(':'));
  }

  // Last part is not a known port, it's part of the label
  return normalizeLabel(afterPrefix);
}

/**
 * Extracts and normalizes an agent label from "agent:label:port" format,
 * stripping the port suffix if present.
 *
 * This is essential for streaming edge matching where backend sends refs like
 * "agent:ai_sorter:category_0" but frontend edges have node IDs without ports.
 *
 * Examples:
 * - "agent:ai_sorter:category_0" -> "ai_sorter"
 * - "agent:my_guard:pass" -> "my_guard"
 * - "agent:my_agent" -> "my_agent" (no port)
 *
 * @param agentRef The agent reference with optional port
 * @returns The normalized label without port, or null if input is null/blank
 */
export function extractAgentLabelWithoutPort(agentRef: string | null | undefined): string | null {
  if (!agentRef || agentRef.trim() === '') {
    return null;
  }

  const trimmed = agentRef.trim();

  // Strip # suffix first (branch info from streaming)
  const withoutHash = trimmed.split('#')[0];

  // Must start with agent:
  if (!withoutHash.startsWith('agent:')) {
    return normalizeLabel(trimmed);
  }

  // Remove "agent:" prefix
  const afterPrefix = withoutHash.substring('agent:'.length);

  // Split by ":" to check for port
  const parts = afterPrefix.split(':');

  if (parts.length === 1) {
    // No port, just the label
    return normalizeLabel(parts[0]);
  }

  // Check if the last part is a known agent port
  const lastPart = parts[parts.length - 1];
  if (isKnownAgentPort(lastPart)) {
    // Last part is a port, join all except the last
    const labelParts = parts.slice(0, -1);
    return normalizeLabel(labelParts.join(':'));
  }

  // Last part is not a known port, it's part of the label
  return normalizeLabel(afterPrefix);
}

/**
 * Extracts the port from a prefixed edge reference.
 * Works for both core: and agent: prefixes.
 *
 * Examples:
 * - "agent:ai_sorter:category_0" -> "category_0"
 * - "core:check:if" -> "if"
 * - "core:my_switch:case_0" -> "case_0"
 * - "agent:my_agent" -> null (no port)
 * - "mcp:step1" -> null (no port for mcp)
 *
 * @param ref The edge reference
 * @returns The port string, or null if no port
 */
export function extractPortFromRef(ref: string | null | undefined): string | null {
  if (!ref) return null;
  const cleanRef = ref.split('#')[0];

  if (cleanRef.startsWith('agent:')) {
    const afterPrefix = cleanRef.substring('agent:'.length);
    const parts = afterPrefix.split(':');
    if (parts.length >= 2) {
      const lastPart = parts[parts.length - 1];
      if (isKnownAgentPort(lastPart)) return lastPart;
    }
    return null;
  }

  if (cleanRef.startsWith('core:')) {
    const afterPrefix = cleanRef.substring('core:'.length);
    const parts = afterPrefix.split(':');
    if (parts.length >= 2) {
      const lastPart = parts[parts.length - 1];
      if (isKnownCorePort(lastPart)) return lastPart;
    }
    return null;
  }

  return null;
}

/**
 * Extracts and normalizes a trigger label from "trigger:label" format.
 *
 * @param triggerRef The trigger reference (e.g., "trigger:My Trigger")
 * @returns The normalized label, or null if input is null/blank
 */
export function extractTriggerLabel(triggerRef: string | null | undefined): string | null {
  return extractAndNormalizeLabel(triggerRef, 'trigger:');
}

/**
 * Extracts and normalizes an MCP label from "mcp:label" format.
 *
 * @param mcpRef The MCP reference (e.g., "mcp:My Tool")
 * @returns The normalized label, or null if input is null/blank
 */
export function extractMcpLabel(mcpRef: string | null | undefined): string | null {
  return extractAndNormalizeLabel(mcpRef, 'mcp:');
}

/**
 * Extracts and normalizes a table label from "table:label" format.
 *
 * @param tableRef The table reference (e.g., "table:Users Table")
 * @returns The normalized label, or null if input is null/blank
 */
export function extractTableLabel(tableRef: string | null | undefined): string | null {
  return extractAndNormalizeLabel(tableRef, 'table:');
}

/**
 * REUSABLE TRIGGER TYPES
 *
 * These trigger types support accumulation: multiple executions on the same run,
 * with each execution incrementing the epoch counter.
 */
const REUSABLE_TRIGGER_TYPES = ['webhook', 'manual', 'chat', 'datasource', 'schedule', 'workflow'] as const;
export type ReusableTriggerType = typeof REUSABLE_TRIGGER_TYPES[number];

/**
 * Check if a trigger type is a reusable trigger type.
 *
 * Reusable triggers support:
 * - WAITING_TRIGGER status (waiting for external event)
 * - Accumulation (multiple executions on same run)
 * - Epoch-based tracking
 *
 * @param type The trigger type string (e.g., "webhook", "manual", "chat", "datasource", "schedule", "workflow")
 * @returns true if the type is a reusable trigger type
 */
export function isReusableTriggerType(type: string | null | undefined): boolean {
  if (!type) return false;
  const lowerType = type.toLowerCase();
  return REUSABLE_TRIGGER_TYPES.includes(lowerType as ReusableTriggerType);
}
