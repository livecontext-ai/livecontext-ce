import { describe, it, expect } from 'vitest';
import { getToolIconType, type ToolIconType } from '../activityGrouping';

// Regression: a tool family absent from getToolIconType fell back to the API plug,
// so the thinking block showed a plug for `channel` (and mailbox, memory,
// generation, ask_user, wait, ...). Every tool name the agent can emit gets an icon
// that fits it, and an unknown name falls back to a neutral wrench, never to an
// icon that means something else.

// Every tool the backend exposes to the agent (ToolDefinition names + TOOL_NAME
// constants), plus the bridge's `view_attachment` relabel.
const LIVECONTEXT_TOOLS: Array<[string, ToolIconType]> = [
  ['channel', 'channel'],
  ['mailbox', 'mailbox'],
  ['memory', 'memory'],
  ['generation', 'generation'],
  ['agent_browse', 'browser'],
  ['ask_user', 'askUser'],
  ['wait', 'wait'],
  ['download_file', 'download'],
  ['store_file', 'save'],
  ['view_attachment', 'attachment'],
  ['skill', 'skill'],
  ['application', 'application'],
  ['table', 'table'],
  ['datasource', 'table'],
  ['interface', 'interface'],
  ['workflow', 'workflow'],
  ['agent', 'agent'],
  ['catalog', 'search'],
  ['web_search', 'globe'],
  ['files', 'files'],
  ['visualize', 'eye'],
  ['get_tool_result', 'eye'],
  ['credential', 'key'],
  ['request_credential', 'key'],
  ['get_connected_services', 'key'],
  ['set_conversation_title', 'pencil'],
  ['list_all_tools', 'help'],
  ['get_tool_help', 'help'],
  ['get_resource_schema', 'help'],
  ['get_examples', 'help'],
  ['expression_help', 'help'],
];

// Claude Code native tools (claude-adapter.mjs nativeTools, plus the task/monitor
// tools of current Claude Code), Codex's published `shell`, and the Gemini CLI's
// native tools (gemini-adapter.mjs).
const CLI_NATIVE_TOOLS: Array<[string, ToolIconType]> = [
  ['Bash', 'terminal'],
  ['BashOutput', 'terminal'],
  ['KillShell', 'terminal'],
  ['KillBash', 'terminal'],
  ['Monitor', 'terminal'],
  ['SlashCommand', 'terminal'],
  ['SendUserMessage', 'askUser'],
  ['LSP', 'code'],
  ['ToolSearch', 'search'],
  ['Agent', 'agent'],
  ['Skill', 'skill'],
  ['TaskCreate', 'tasks'],
  ['TaskUpdate', 'tasks'],
  ['TaskList', 'tasks'],
  ['TaskGet', 'tasks'],
  ['TaskOutput', 'tasks'],
  ['TaskStop', 'tasks'],
  ['shell', 'terminal'],
  ['run_shell_command', 'terminal'],
  ['read_file', 'file'],
  ['read_many_files', 'file'],
  ['write_file', 'pencil'],
  ['replace', 'pencil'],
  ['list_directory', 'files'],
  ['glob', 'search'],
  ['search_file_content', 'search'],
  ['web_fetch', 'globe'],
  ['google_web_search', 'globe'],
  ['save_memory', 'memory'],
];

describe('getToolIconType - every LiveContext tool has its own icon', () => {
  it.each(LIVECONTEXT_TOOLS)('%s -> %s', (tool, expected) => {
    expect(getToolIconType(tool)).toBe(expected);
  });

  it('agent_browse is the browser, not the agent robot (the agent_ prefix rule comes after)', () => {
    expect(getToolIconType('agent_browse')).toBe('browser');
    expect(getToolIconType('agent_something_new')).toBe('agent');
  });
});

describe('getToolIconType - native tools of the CLI agents', () => {
  it.each(CLI_NATIVE_TOOLS)('%s -> %s', (tool, expected) => {
    expect(getToolIconType(tool)).toBe(expected);
  });
});

describe('getToolIconType - fallbacks', () => {
  it('an unknown tool gets the neutral wrench, not the API plug', () => {
    expect(getToolIconType('SomethingNew')).toBe('tool');
  });

  it('system tools render no icon', () => {
    expect(getToolIconType('_system_stop')).toBeNull();
  });
});
