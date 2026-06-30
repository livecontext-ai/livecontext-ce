/**
 * Activity Grouping Utility
 *
 * Groups consecutive tool calls with the same toolName together
 * for a cleaner UI display in the ActivityFeed.
 */

import type { ToolActivity, ToolVisualization } from '@/components/chat/ActivityFeed';

export interface GroupedToolActivity {
  type: 'group';
  // Stable ID based on first call's ID (for React key)
  id: string;
  toolName: string;
  calls: ToolActivity[];
  // Aggregated status: 'pending' > 'error' > 'interrupted' > 'success'.
  // 'interrupted' surfaces only when no calls failed but at least one never
  // ran (turn stopped before the tool executed).
  overallStatus: 'pending' | 'success' | 'error' | 'interrupted';
  // Total duration of all calls
  totalDurationMs: number;
  // First call timestamp (for ordering)
  timestamp: number;
  // Grouped visualizations (for datasource/interface/workflow)
  visualizations: ToolVisualization[];
}

export type ToolActivityOrGroup = ToolActivity | GroupedToolActivity;

/**
 * Check if an item is a grouped tool activity
 */
export function isGroupedTool(item: ToolActivityOrGroup): item is GroupedToolActivity {
  return 'type' in item && item.type === 'group';
}

/**
 * Compute overall status from a list of tool activities
 */
function computeOverallStatus(calls: ToolActivity[]): 'pending' | 'success' | 'error' | 'interrupted' {
  if (calls.some(c => c.status === 'pending')) return 'pending';
  if (calls.some(c => c.status === 'error')) return 'error';
  if (calls.some(c => c.status === 'interrupted')) return 'interrupted';
  return 'success';
}

/**
 * Compute total duration from a list of tool activities
 */
function computeTotalDuration(calls: ToolActivity[]): number {
  return calls.reduce((sum, c) => sum + (c.durationMs || 0), 0);
}

/**
 * Extract visualizations from a list of tool activities
 */
function extractVisualizations(calls: ToolActivity[]): ToolVisualization[] {
  return calls
    .filter(c => c.visualization)
    .map(c => c.visualization!);
}

/**
 * Group consecutive tool calls with the same toolName.
 * ALL tool calls are always grouped (even single calls) for consistent UI.
 *
 * System tools (starting with '_system_') are never grouped.
 */
export function groupConsecutiveTools(tools: ToolActivity[]): ToolActivityOrGroup[] {
  if (tools.length === 0) return [];

  const result: ToolActivityOrGroup[] = [];
  let currentGroup: ToolActivity[] = [];
  let currentToolName: string | null = null;

  for (const tool of tools) {
    // System tools are never grouped
    const isSystemTool = tool.toolName.startsWith('_system_');

    if (!isSystemTool && tool.toolName === currentToolName) {
      // Same tool name - add to current group
      currentGroup.push(tool);
    } else {
      // Different tool name - flush previous group (always as a group)
      if (currentGroup.length >= 1) {
        result.push({
          type: 'group',
          id: `group-${currentGroup[0].id}`,
          toolName: currentToolName!,
          calls: currentGroup,
          overallStatus: computeOverallStatus(currentGroup),
          totalDurationMs: computeTotalDuration(currentGroup),
          timestamp: currentGroup[0].timestamp,
          visualizations: extractVisualizations(currentGroup),
        });
      }

      // Start new group (or add system tool directly)
      if (isSystemTool) {
        result.push(tool);
        currentGroup = [];
        currentToolName = null;
      } else {
        currentGroup = [tool];
        currentToolName = tool.toolName;
      }
    }
  }

  // Flush last group (always as a group)
  if (currentGroup.length >= 1) {
    result.push({
      type: 'group',
      id: `group-${currentGroup[0].id}`,
      toolName: currentToolName!,
      calls: currentGroup,
      overallStatus: computeOverallStatus(currentGroup),
      totalDurationMs: computeTotalDuration(currentGroup),
      timestamp: currentGroup[0].timestamp,
      visualizations: extractVisualizations(currentGroup),
    });
  }

  return result;
}

/**
 * Truncate a string with ellipsis
 */
function truncate(str: string, maxLen: number): string {
  if (!str) return '';
  return str.length > maxLen ? str.substring(0, maxLen) + '…' : str;
}

/** Last path segment of a file path (handles both / and \ separators). */
function basename(p: string): string {
  const parts = p.split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : p;
}

/**
 * Tool icon types for ActivityFeed display
 */
export type ToolIconType =
  | 'table'
  | 'interface'
  | 'workflow'
  | 'agent'
  | 'search'
  | 'globe'
  | 'help'
  | 'key'
  | 'tasks'
  | 'eye'
  | 'code'
  | 'api'
  | 'files'
  | 'play'
  | 'pencil'
  | 'terminal'
  | 'file';

/**
 * Get the icon type for a tool based on its name.
 * Returns null only for system tools like _system_stop
 */
export function getToolIconType(toolName: string): ToolIconType | null {
  const normalized = toolName.toLowerCase();

  // System tools - no icon
  if (normalized.startsWith('_system_')) return null;

  // Table / Datasource (bridge MCP tool is named 'table', agent-service uses 'datasource')
  if (normalized === 'datasource' || normalized === 'table') return 'table';

  // Interface
  if (normalized === 'interface') return 'interface';

  // Workflow
  if (normalized === 'workflow') return 'workflow';

  // Agent / Skill
  if (normalized.startsWith('agent_') || normalized === 'agent') return 'agent';
  if (normalized === 'skill') return 'agent';

  // Application
  if (normalized === 'application') return 'play';

  // Web Search / Fetch (unified facade)
  if (normalized === 'web_search') return 'globe';

  // Catalog (unified facade)
  if (normalized === 'catalog') return 'search';

  // Help tools
  if (normalized.includes('help') || normalized === 'list_all_tools' || normalized === 'get_tool_help' || normalized === 'get_resource_schema' || normalized === 'get_examples' || normalized === 'expression_help') return 'help';

  // Credentials
  if (normalized === 'get_connected_services' || normalized.includes('credential')) return 'key';

  // Tasks
  if (normalized === 'tasks') return 'tasks';

  // Set conversation title
  if (normalized === 'set_conversation_title' || normalized.includes('title')) return 'pencil';

  // Get tool result (expand truncated results)
  if (normalized === 'get_tool_result') return 'eye';

  // Visualize
  if (normalized === 'visualize') return 'eye';

  // Schema tools
  if (normalized.includes('schema')) return 'code';

  // Files browser
  if (normalized === 'files') return 'files';

  // Native Claude Code tools (the bridge agent now runs with the full toolset).
  if (normalized === 'bash') return 'terminal';
  if (normalized === 'grep' || normalized === 'glob') return 'search';
  if (normalized === 'read') return 'file';
  if (normalized === 'edit' || normalized === 'write' || normalized === 'multiedit' || normalized === 'notebookedit') return 'pencil';
  if (normalized === 'webfetch' || normalized === 'websearch') return 'globe';
  if (normalized === 'task') return 'agent';
  if (normalized === 'todowrite') return 'tasks';

  // Default: API/tool icon for any other tool
  return 'api';
}

/**
 * Get a user-friendly description of a tool call based on tool name and arguments.
 * Returns a concise, actionable description instead of raw JSON.
 */
export function getToolDescription(toolName: string, args?: string, visualization?: ToolVisualization, result?: string): string | null {
  if (!args) return null;

  try {
    let parsed = JSON.parse(args);

    // Handle wrapped arguments format from streaming: { raw: "...", thinking: "..." }
    if (parsed.raw && typeof parsed.raw === 'string') {
      try {
        parsed = JSON.parse(parsed.raw);
      } catch {
        // raw is not valid JSON, use as-is
      }
    }

    // Normalize tool name to lowercase for matching
    const normalizedToolName = toolName.toLowerCase();

    // Resource display name from visualization metadata (resolved by backend)
    const vizTitle = visualization?.title;

    // Try to extract resource name from tool result (for get/update actions that only have IDs in args)
    let resultName: string | undefined;
    if (result) {
      try {
        const parsedResult = JSON.parse(result);
        // Result can be { name: "..." } or { data: { name: "..." } }
        resultName = parsedResult?.name || parsedResult?.data?.name;
      } catch { /* ignore parse errors */ }
    }

    switch (normalizedToolName) {
      // === TABLE (datasource) ===
      case 'datasource':
      case 'table': {
        const action = parsed.action;
        const name = parsed.name;
        const dsId = parsed.datasource_id ?? parsed.table_id;
        const dataLen = Array.isArray(parsed.data) ? parsed.data.length : null;
        const rowsLen = Array.isArray(parsed.rows) ? parsed.rows.length : null;
        const tableRef = vizTitle ? `"${truncate(vizTitle, 24)}"` : dsId != null ? `#${dsId}` : '';

        switch (action) {
          case 'create':
            return `Create "${truncate(name, 20)}"${dataLen ? ` with ${dataLen} rows` : ''}`;
          case 'list':
            return 'List all tables';
          case 'get':
            return `Get table ${tableRef}`;
          case 'items':
            return `Get rows from ${tableRef}`;
          case 'add_items':
            return `Add ${dataLen || ''} rows to ${tableRef}`;
          case 'insert_rows':
            return `Insert ${rowsLen || ''} rows into ${tableRef}`;
          case 'query_rows':
            return `Query ${tableRef}`;
          case 'update_rows':
            return `Update rows in ${tableRef}`;
          case 'delete_rows':
            return `Delete rows from ${tableRef}`;
          case 'add_columns':
            return `Add columns to ${tableRef}`;
          case 'update':
            return `Update ${tableRef}`;
          case 'delete':
            return `Delete ${tableRef}`;
          case 'help':
            return 'Table Help';
          default:
            return action ? `${action}` : null;
        }
      }

      // === INTERFACE ===
      case 'interface': {
        const action = parsed.action;
        const name = parsed.name;
        const ifId = parsed.interface_id;
        const ifRef = vizTitle ? `"${truncate(vizTitle, 24)}"` : ifId != null ? `#${ifId}` : '';

        switch (action) {
          case 'create':
            return `Create interface "${truncate(name, 20)}"`;
          case 'list':
            return 'List all interfaces';
          case 'get':
            return `Get interface ${ifRef}`;
          case 'update':
            return `Update interface ${ifRef}`;
          case 'delete':
            return `Delete interface ${ifRef}`;
          case 'help':
            return 'Interface Help';
          default:
            return action ? `${action} interface` : null;
        }
      }

      // === WORKFLOW (unified facade) ===
      case 'workflow': {
        const action = parsed.action;

        switch (action) {
          case 'init': {
            const name = parsed.name;
            // ||BADGE|| syntax will be parsed by GroupedToolCard to show a styled label
            return name ? `Initialize workflow "${truncate(name, 40)}"` : 'Initialize workflow';
          }
          case 'add_trigger': {
            const label = parsed.trigger?.label;
            return label ? `Add trigger "${truncate(label, 20)}"` : 'Add trigger';
          }
          case 'add_mcp': {
            const label = parsed.step?.label;
            return label ? `Add step "${truncate(label, 20)}"` : 'Add step';
          }
          case 'add_agent': {
            const label = parsed.agent?.label || parsed.label;
            return label ? `Add agent "${truncate(label, 20)}"` : 'Add agent';
          }
          case 'add_decision': {
            const label = parsed.decision?.label || parsed.label;
            return label ? `Add decision "${truncate(label, 20)}"` : 'Add decision';
          }
          case 'add_loop': {
            const label = parsed.loop?.label || parsed.label;
            return label ? `Add loop "${truncate(label, 20)}"` : 'Add loop';
          }
          case 'add_split': {
            const label = parsed.split?.label || parsed.label;
            return label ? `Add split "${truncate(label, 20)}"` : 'Add split';
          }
          case 'add_node': {
            const nodeType = parsed.type;
            const label = parsed.label;
            const typeName = nodeType ? nodeType.replace(/_/g, ' ') : 'node';
            return label ? `Add ${typeName} "${truncate(label, 20)}"` : `Add ${typeName}`;
          }
          case 'set_loop_exits':
            return 'Set loop exit conditions';
          case 'connect':
          case 'add_edge':
            return `Connect ${parsed.from} → ${parsed.to}`;
          case 'disconnect':
            return `Disconnect ${parsed.from} → ${parsed.to}`;
          case 'link_interface':
            return 'Link interface to node';
          case 'insert_after':
            return `Insert after ${parsed.after}`;
          case 'modify':
            return `Modify node ${parsed.node_id || parsed.node}`;
          case 'remove':
            return `Remove node ${parsed.node_id || parsed.node}`;
          case 'undo':
            return 'Undo last change';
          case 'redo':
            return 'Redo change';
          case 'get_summary':
            return 'Get workflow summary';
          case 'describe':
            return parsed.node ? `Describe node ${parsed.node}` : 'Describe workflow';
          case 'list_nodes':
            return 'List all nodes';
          case 'get_errors':
            return 'Check for errors';
          case 'save':
            return 'Save workflow';
          case 'load':
            return parsed.workflow_id ? `Load workflow` : (parsed.name ? `Load "${truncate(parsed.name, 20)}"` : 'Load workflow');
          case 'create':
            return 'Create workflow';
          case 'validate':
            return 'Validate workflow';
          case 'discard':
            return 'Discard session';
          case 'set_schedule':
            return 'Set schedule';
          case 'execute':
            return 'Execute workflow';
          // CRUD actions (absorbed from separate tools)
          case 'get':
            return (parsed.name || resultName) ? `Get workflow "${truncate(parsed.name || resultName, 20)}"` : 'Get workflow';
          case 'list':
            return 'List workflows';
          case 'delete':
            return (parsed.name || resultName) ? `Delete workflow "${truncate(parsed.name || resultName, 20)}"` : 'Delete workflow';
          case 'runs':
            return (parsed.name || resultName) ? `Get runs "${truncate(parsed.name || resultName, 20)}"` : 'Get runs';
          case 'help':
            return parsed.topic ? `Help: ${parsed.topic}` : 'Workflow Help';
          default:
            return action ? action.replace(/_/g, ' ') : null;
        }
      }

      // === CONVERSATION ===
      case 'set_conversation_title':
        return `Set title: "${truncate(parsed.title, 25)}"`;
      case 'get_tool_result':
        return `Get result for ${parsed.tool_call_id}`;

      // === WEB SEARCH (unified facade) ===
      case 'web_search': {
        const wsAction = parsed.action;
        if (wsAction === 'fetch') {
          if (parsed.urls && Array.isArray(parsed.urls)) {
            return `Fetch: ${parsed.urls.length} pages`;
          }
          return `Fetch: ${truncate(parsed.url, 40)}`;
        }
        if (wsAction === 'agent_browse') {
          // agent_browse carries the goal in `task` (no `query`). Show the
          // task verbatim so the user sees what the browser agent is
          // working on, e.g. "Browse: Find iPhone price on amazon.fr".
          const task = parsed.task || parsed.start_url;
          return task ? `Browse: "${truncate(task, 35)}"` : 'Browser Agent';
        }
        if (wsAction === 'browse_status') return 'Check browser session';
        if (wsAction === 'browse_intervene') return 'Intervene in browser session';
        if (wsAction === 'browse_abort') return 'Abort browser session';
        if (wsAction === 'browse_screenshot') return 'Capture screenshot';
        if (wsAction === 'help') return 'Web search help';
        return `Search: "${truncate(parsed.query, 30)}"`;
      }

      // === CATALOG (unified facade) ===
      case 'catalog': {
        const catAction = parsed.action;
        switch (catAction) {
          case 'search':
            return `Search: "${truncate(parsed.query, 25)}"`;
          case 'execute':
          case 'call': {
            const toolName = parsed.tool_name || parsed.endpoint_name || parsed.api_name;
            if (toolName) {
              return `Call "${truncate(toolName, 25)}"`;
            }
            return 'Call API';
          }
          case 'response_schema': {
            const schemaToolName = parsed.tool_name || parsed.endpoint_name;
            if (schemaToolName) {
              return `Schema for "${truncate(schemaToolName, 20)}"`;
            }
            return 'Get response schema';
          }
          case 'schema_help':
            return 'Schema Help';
          default:
            return catAction ? catAction.replace(/_/g, ' ') : 'Catalog';
        }
      }

      // === VISUALIZATION ===
      case 'visualize': {
        const vizType = parsed.type;
        const vizTitle = parsed.title;
        // Map internal types to user-friendly names
        const typeNames: Record<string, string> = {
          datasource: 'table',
          interface: 'interface',
          workflow: 'workflow',
          slide: 'slide deck',
        };
        const displayType = typeNames[vizType] || vizType;
        if (vizTitle) {
          return `Display ${displayType} "${truncate(vizTitle, 20)}"`;
        }
        return `Display ${displayType}`;
      }

      // === AGENT (unified facade from bridge, or legacy separate tools) ===
      case 'agent': {
        const action = parsed.action;
        const agentName = parsed.name || resultName;
        switch (action) {
          case 'create':
            return `Create agent "${truncate(parsed.name, 20)}"`;
          case 'get':
            return agentName ? `Get agent "${truncate(agentName, 20)}"` : 'Get agent';
          case 'list':
            return 'List agents';
          case 'update':
            return agentName ? `Update agent "${truncate(agentName, 20)}"` : 'Update agent';
          case 'delete':
            return agentName ? `Delete agent "${truncate(agentName, 20)}"` : 'Delete agent';
          case 'help':
            return 'Agent Help';
          default:
            return action ? action.replace(/_/g, ' ') : null;
        }
      }
      case 'agent_create':
        return `Create agent "${truncate(parsed.name, 20)}"`;
      case 'agent_get':
        return (parsed.name || resultName) ? `Get agent "${truncate(parsed.name || resultName, 20)}"` : 'Get agent';
      case 'agent_list':
        return 'List agents';
      case 'agent_update':
        return (parsed.name || resultName) ? `Update agent "${truncate(parsed.name || resultName, 20)}"` : 'Update agent';
      case 'agent_delete':
        return (parsed.name || resultName) ? `Delete agent "${truncate(parsed.name || resultName, 20)}"` : 'Delete agent';
      case 'agent_help':
        return 'Agent Help';

      // === SKILL (unified facade from bridge) ===
      case 'skill': {
        const action = parsed.action;
        const skillName = parsed.name || parsed.skill_name || resultName;
        const skillRef = skillName ? `"${truncate(skillName, 24)}"` : (parsed.skill_id ? `#${parsed.skill_id}` : '');
        switch (action) {
          case 'create':
            return `Create skill ${skillRef || ''}`.trim();
          case 'list':
            return 'List skills';
          case 'get':
            return `Get skill ${skillRef}`;
          case 'update':
            return `Update skill ${skillRef}`;
          case 'delete':
            return `Delete skill ${skillRef}`;
          case 'assign':
            return `Assign skill ${skillRef}`;
          case 'help':
            return 'Skill Help';
          default:
            return action ? action.replace(/_/g, ' ') : null;
        }
      }

      // === APPLICATION (unified facade from bridge) ===
      case 'application': {
        const action = parsed.action;
        switch (action) {
          case 'create':
            return `Create app "${truncate(parsed.name, 20)}"`;
          case 'list':
            return 'List apps';
          case 'get':
            return (parsed.name || resultName) ? `Get app "${truncate(parsed.name || resultName, 20)}"` : 'Get app';
          case 'delete':
            return (parsed.name || resultName) ? `Delete app "${truncate(parsed.name || resultName, 20)}"` : 'Delete app';
          case 'publish':
            return (parsed.name || resultName) ? `Publish app "${truncate(parsed.name || resultName, 20)}"` : 'Publish app';
          default:
            return action ? action.replace(/_/g, ' ') : null;
        }
      }

      // === TASKS ===
      case 'tasks': {
        const action = parsed.action;
        switch (action) {
          case 'plan': {
            const count = Array.isArray(parsed.tasks) ? parsed.tasks.length : '';
            return `Plan ${count} tasks`;
          }
          case 'add':
            return `Add task: "${truncate(parsed.description, 25)}"`;
          case 'check':
            return `Complete task #${parsed.id}`;
          case 'uncheck':
            return `Uncheck task #${parsed.id}`;
          case 'focus':
            return `Focus on task #${parsed.id}`;
          case 'update':
            return `Update task #${parsed.id}`;
          case 'memo':
            return `Add note to task #${parsed.id}`;
          case 'remove':
            return `Remove task #${parsed.id}`;
          case 'show':
            return 'Show all tasks';
          default:
            return action || null;
        }
      }

      // === HELP ===
      case 'list_all_tools':
        return parsed.category ? `List ${parsed.category} tools` : 'List all tools';
      case 'get_tool_help': {
        const helpToolName = parsed.tool_name;
        if (helpToolName) {
          return `Help: ${truncate(helpToolName, 25)}`;
        }
        return 'Tool Help';
      }
      case 'get_resource_schema': {
        const resourceType = parsed.resource_type;
        if (resourceType) {
          return `Schema: ${resourceType}`;
        }
        return 'Get schema';
      }
      case 'get_examples': {
        const exampleType = parsed.resource_type;
        if (exampleType) {
          return `Examples: ${exampleType}`;
        }
        return 'Get examples';
      }
      case 'expression_help':
        return 'Expression syntax';

      // === CREDENTIALS ===
      case 'get_connected_services':
        return 'List connected services';

      // === NATIVE CLAUDE CODE TOOLS (full agent toolset over the bridge) ===
      // Surface WHAT the agent is doing (the command / pattern / file / url) so the
      // live activity feed stays legible instead of showing a bare "Bash"/"Read".
      case 'bash': {
        const cmd = typeof parsed.command === 'string' ? parsed.command.trim() : '';
        if (cmd) return truncate(cmd.replace(/\s+/g, ' '), 60);
        return typeof parsed.description === 'string' ? truncate(parsed.description, 40) : 'Run command';
      }
      case 'grep': {
        const pattern = parsed.pattern;
        return pattern ? `Search: "${truncate(String(pattern), 30)}"` : 'Search';
      }
      case 'glob': {
        const pattern = parsed.pattern;
        return pattern ? `Find: "${truncate(String(pattern), 30)}"` : 'Find files';
      }
      case 'read': {
        const file = parsed.file_path;
        return file ? `Read ${basename(String(file))}` : 'Read file';
      }
      case 'edit':
      case 'multiedit': {
        const file = parsed.file_path;
        return file ? `Edit ${basename(String(file))}` : 'Edit file';
      }
      case 'write': {
        const file = parsed.file_path;
        return file ? `Write ${basename(String(file))}` : 'Write file';
      }
      case 'notebookedit': {
        const file = parsed.notebook_path;
        return file ? `Edit ${basename(String(file))}` : 'Edit notebook';
      }
      case 'webfetch': {
        const url = parsed.url;
        return url ? `Fetch ${truncate(String(url).replace(/^https?:\/\//, ''), 40)}` : 'Fetch URL';
      }
      case 'websearch': {
        const query = parsed.query;
        return query ? `Search web: "${truncate(String(query), 30)}"` : 'Web search';
      }
      case 'task': {
        const desc = parsed.description || parsed.subagent_type;
        return desc ? `Agent: ${truncate(String(desc), 35)}` : 'Sub-agent';
      }
      case 'todowrite': {
        const count = Array.isArray(parsed.todos) ? parsed.todos.length : null;
        return count != null ? `Update ${count} todo${count === 1 ? '' : 's'}` : 'Update todos';
      }

      default: {
        // Smart fallback: try to extract meaningful info from arguments
        // This handles API tools (catalog results) and unknown tools

        // If there's an action field, use it
        if (parsed.action && typeof parsed.action === 'string') {
          const action = parsed.action.replace(/_/g, ' ');
          // If there's also a name/id, include it
          const identifier = parsed.name || parsed.id || parsed.query;
          if (identifier && typeof identifier === 'string') {
            return `${action}: "${truncate(identifier, 20)}"`;
          }
          return action;
        }

        // If there's a query field (search tools)
        if (parsed.query && typeof parsed.query === 'string') {
          return `Search: "${truncate(parsed.query, 25)}"`;
        }

        // If there's a name field
        if (parsed.name && typeof parsed.name === 'string') {
          return truncate(parsed.name, 30);
        }

        // If there's an id field
        if (parsed.id) {
          return `ID: ${parsed.id}`;
        }

        return null;
      }
    }
  } catch {
    // Not valid JSON
    return null;
  }
}

/**
 * Extract iconSlug from tool arguments for workflow actions.
 * Returns the service icon slug for MCP/tool nodes (e.g., "gmail", "slack").
 * Agent, classify, guardrail, and control nodes return null to use WorkflowActionIcon fallback.
 */
export function getToolIconSlug(toolName: string, args?: string): string | null {
  if (!args) return null;

  try {
    let parsed = JSON.parse(args);

    // Handle wrapped arguments format from streaming: { raw: "...", thinking: "..." }
    if (parsed.raw && typeof parsed.raw === 'string') {
      try {
        parsed = JSON.parse(parsed.raw);
      } catch {
        // raw is not valid JSON, use as-is
      }
    }

    const normalizedToolName = toolName.toLowerCase();

    if (normalizedToolName === 'workflow') {
      const action = parsed.action;

      switch (action) {
        case 'add_agent':
        case 'add_trigger':
        case 'add_decision':
        case 'add_loop':
        case 'add_split':
          // These node types use Lucide icons from WORKFLOW_ACTION_ICONS (via WorkflowActionIcon fallback)
          return null;
        case 'add_mcp': {
          // For steps with tool data, use the tool's iconSlug or extract from id
          const toolSlug = parsed.step?.tool_slug || parsed.step?.id || parsed.id;
          if (toolSlug && typeof toolSlug === 'string') {
            // Extract service name from tool slug (e.g., "gmail_send_email" -> "gmail")
            const serviceName = toolSlug.split(/[_\/]/)[0];
            return serviceName || null;
          }
          return null;
        }
        case 'add_node': {
          // Unified add_node: delegate based on resolved type
          const nodeType = parsed.type;
          if (nodeType === 'mcp') {
            const toolSlug = parsed.tool_slug || parsed.id;
            if (toolSlug && typeof toolSlug === 'string') {
              const serviceName = toolSlug.split(/[_\/]/)[0];
              return serviceName || null;
            }
          }
          // Agent, classify, guardrail, and other node types use Lucide icons from WORKFLOW_ACTION_ICONS
          return null;
        }
        default:
          return null;
      }
    }

    return null;
  } catch {
    return null;
  }
}
