/**
 * Restricted runs and attachments (LC-022 follow-up).
 *
 * A restricted claude run had its whole built-in tool set removed (`--tools ""`), including Read,
 * so an image or PDF the bridge wrote to the per-run attachment dir (lib/attachmentPrompt.mjs)
 * could never be opened: now the default for every non-admin run. With attachments on disk the
 * restricted run gets exactly Read, approved only under that directory. Restricted runs also no
 * longer pass --dangerously-skip-permissions: that flag would bypass the path scope entirely.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { ClaudeAdapter, claudeAbsolutePathRule } from '../adapters/claude-adapter.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const base = { prompt: 'p', maxTurns: 3, mcpConfigPath: '/tmp/run/mcp.json', mcpServerName: 'agent-cli' };

function valuesAfter(args, flag) {
  const i = args.indexOf(flag);
  if (i < 0) return null;
  const out = [];
  for (let j = i + 1; j < args.length && !String(args[j]).startsWith('--'); j++) out.push(args[j]);
  return out;
}

test('restricted run WITH attachments: only Read, scoped to the attachment directory', () => {
  const { args } = new ClaudeAdapter().buildArgs({ ...base, restrictedToolset: true, attachmentDir: '/tmp/bridge-abc/attachments' });
  assert.deepEqual(valuesAfter(args, '--tools'), ['Read']);
  assert.deepEqual(valuesAfter(args, '--allowedTools'), ['mcp__agent-cli', 'Read(//tmp/bridge-abc/attachments/**)']);
  const denied = valuesAfter(args, '--disallowedTools');
  assert.ok(!denied.includes('Read'), 'Read must not be denied, or the scoped rule is dead');
  for (const t of ['Bash', 'Write', 'Edit', 'Glob', 'Grep', 'WebFetch']) assert.ok(denied.includes(t), `${t} still denied`);
  assert.ok(!args.includes('--dangerously-skip-permissions'), 'skip-permissions would approve Read anywhere');
});

test('restricted run WITHOUT attachments: no built-in tool at all, platform MCP tools approved explicitly', () => {
  const { args } = new ClaudeAdapter().buildArgs({ ...base, restrictedToolset: true });
  assert.equal(args[args.indexOf('--tools') + 1], '');
  assert.deepEqual(valuesAfter(args, '--allowedTools'), ['mcp__agent-cli']);
  assert.ok(valuesAfter(args, '--disallowedTools').includes('Read'));
  assert.ok(!args.includes('--dangerously-skip-permissions'));
});

test('unrestricted run keeps the full native toolset under skip-permissions', () => {
  const { args } = new ClaudeAdapter().buildArgs({ ...base, restrictedToolset: false, attachmentDir: '/tmp/x' });
  assert.ok(args.includes('--dangerously-skip-permissions'));
  assert.ok(!args.includes('--tools'));
});

test('path rule: absolute POSIX and Windows directories become a recursive // rule', () => {
  assert.equal(claudeAbsolutePathRule('/tmp/a/'), '//tmp/a/**');
  assert.equal(claudeAbsolutePathRule('C:\\Users\\x\\att'), '//C:/Users/x/att/**');
});

test('server.mjs passes the attachment dir to the adapter only when something was written to disk', () => {
  const server = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
  assert.match(server, /if \(attachmentPathToName\.size > 0\) attachmentDirOnDisk = attachDir;/);
  assert.match(server, /attachmentDir: attachmentDirOnDisk,/);
});
