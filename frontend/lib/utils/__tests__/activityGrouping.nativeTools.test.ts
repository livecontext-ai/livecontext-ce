import { describe, it, expect } from 'vitest';
import { getToolDescription, getToolIconType } from '../activityGrouping';

// The bridge agent now runs as a full Claude Code: its native tool calls (Bash,
// Grep, Read, Edit, …) stream into the chat through the SAME path as platform
// tools. These tests lock in that the live activity feed shows WHAT the agent is
// doing (the command / pattern / file / url), not a bare "Bash"/"Read", and that
// each native tool maps to a sensible icon. Tool names arrive capitalized exactly
// as Claude Code emits them (matching internally lowercases).

describe('getToolDescription - native Claude Code tools', () => {
  it('Bash: shows the command, whitespace-collapsed', () => {
    expect(getToolDescription('Bash', JSON.stringify({ command: 'npm   run\n test' })))
      .toBe('npm run test');
  });

  it('Bash: truncates a long command at 60 chars', () => {
    const long = 'gh workflow run deploy-k8s-prod.yml --ref dev -f backend_image_tag=sha-abc1234';
    const out = getToolDescription('Bash', JSON.stringify({ command: long }))!;
    expect(out.startsWith('gh workflow run deploy-k8s-prod.yml')).toBe(true);
    expect(out.endsWith('…')).toBe(true);
    expect(out.length).toBeLessThanOrEqual(61); // 60 + ellipsis
  });

  it('Bash: falls back to description when no command, then to a default', () => {
    expect(getToolDescription('Bash', JSON.stringify({ description: 'Deploy to prod' })))
      .toBe('Deploy to prod');
    expect(getToolDescription('Bash', JSON.stringify({})))
      .toBe('Run command');
  });

  it('Grep: shows the pattern', () => {
    expect(getToolDescription('Grep', JSON.stringify({ pattern: 'TODO.*fix', path: 'src' })))
      .toBe('Search: "TODO.*fix"');
    expect(getToolDescription('Grep', JSON.stringify({})))
      .toBe('Search');
  });

  it('Glob: shows the pattern', () => {
    expect(getToolDescription('Glob', JSON.stringify({ pattern: '**/*.tsx' })))
      .toBe('Find: "**/*.tsx"');
  });

  it('Read/Edit/Write: show the file basename (POSIX and Windows paths)', () => {
    expect(getToolDescription('Read', JSON.stringify({ file_path: 'backend/src/App.java' })))
      .toBe('Read App.java');
    expect(getToolDescription('Edit', JSON.stringify({ file_path: 'C:\\repo\\a\\b.ts' })))
      .toBe('Edit b.ts');
    expect(getToolDescription('Write', JSON.stringify({ file_path: '/tmp/out.json' })))
      .toBe('Write out.json');
  });

  it('WebFetch: shows the host/path without the scheme', () => {
    expect(getToolDescription('WebFetch', JSON.stringify({ url: 'https://example.com/docs' })))
      .toBe('Fetch example.com/docs');
  });

  it('WebSearch: shows the query', () => {
    expect(getToolDescription('WebSearch', JSON.stringify({ query: 'k3s image gc' })))
      .toBe('Search web: "k3s image gc"');
  });

  it('Task: shows the description (or subagent type)', () => {
    expect(getToolDescription('Task', JSON.stringify({ description: 'Audit the change', subagent_type: 'claude' })))
      .toBe('Agent: Audit the change');
    expect(getToolDescription('Task', JSON.stringify({ subagent_type: 'Explore' })))
      .toBe('Agent: Explore');
  });

  it('TodoWrite: pluralizes the todo count', () => {
    expect(getToolDescription('TodoWrite', JSON.stringify({ todos: [{}, {}, {}] })))
      .toBe('Update 3 todos');
    expect(getToolDescription('TodoWrite', JSON.stringify({ todos: [{}] })))
      .toBe('Update 1 todo');
  });

  it('NotebookEdit shows the notebook basename', () => {
    expect(getToolDescription('NotebookEdit', JSON.stringify({ notebook_path: 'nb/analysis.ipynb' })))
      .toBe('Edit analysis.ipynb');
  });

  it('falls back to a sensible label when the key field is missing', () => {
    expect(getToolDescription('Read', JSON.stringify({}))).toBe('Read file');
    expect(getToolDescription('Edit', JSON.stringify({}))).toBe('Edit file');
    expect(getToolDescription('Write', JSON.stringify({}))).toBe('Write file');
    expect(getToolDescription('MultiEdit', JSON.stringify({}))).toBe('Edit file');
    expect(getToolDescription('NotebookEdit', JSON.stringify({}))).toBe('Edit notebook');
    expect(getToolDescription('Glob', JSON.stringify({}))).toBe('Find files');
    expect(getToolDescription('WebFetch', JSON.stringify({}))).toBe('Fetch URL');
    expect(getToolDescription('WebSearch', JSON.stringify({}))).toBe('Web search');
    expect(getToolDescription('Task', JSON.stringify({}))).toBe('Sub-agent');
    expect(getToolDescription('TodoWrite', JSON.stringify({}))).toBe('Update todos');
  });

  it('does not regress the generic fallback for unknown tools', () => {
    expect(getToolDescription('SomethingNew', JSON.stringify({ action: 'do_thing', name: 'x' })))
      .toBe('do thing: "x"');
  });
});

describe('getToolIconType - native Claude Code tools', () => {
  const cases: Array<[string, string]> = [
    ['Bash', 'terminal'],
    ['Grep', 'search'],
    ['Glob', 'search'],
    ['Read', 'file'],
    ['Edit', 'pencil'],
    ['Write', 'pencil'],
    ['MultiEdit', 'pencil'],
    ['NotebookEdit', 'pencil'],
    ['WebFetch', 'globe'],
    ['WebSearch', 'globe'],
    ['Task', 'agent'],
    ['TodoWrite', 'tasks'],
  ];
  it.each(cases)('%s → %s icon', (tool, expected) => {
    expect(getToolIconType(tool)).toBe(expected);
  });

  it('unknown tools still fall back to the generic api icon', () => {
    expect(getToolIconType('SomethingNew')).toBe('api');
  });
});
