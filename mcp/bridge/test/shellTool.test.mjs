/**
 * Behavioral tests for the `shell` MCP tool (mcp/shell-tool.mjs) - the host-command
 * capability the bridge agent uses instead of Claude's built-in Bash (kept off so the
 * platform MCP tools stay directly callable). Exercises exec, cwd containment, the
 * output/secret/env safety rails, the timeout, the non-zero-exit surface, and the
 * CE/dev no-op gate. Commands use `node -e` so the suite is OS-agnostic.
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import { resolve } from 'path';
import { execFileSync } from 'child_process';

const { SHELL_TOOL_DEF, handleShellTool, isShellEnabled, scrubbedEnv, _internals2 } =
  await import('../../shell-tool.mjs');

let repo;
const txt = (r) => r.content[0].text;

before(() => {
  repo = mkdtempSync(resolve(tmpdir(), 'shelltool-'));
  execFileSync('git', ['-C', repo, 'init', '-q', '-b', 'dev']);
  writeFileSync(resolve(repo, 'marker.txt'), 'hi');
  process.env.AGENT_REPO_PATH = repo;
});

after(() => {
  delete process.env.AGENT_REPO_PATH;
  try { rmSync(repo, { recursive: true, force: true }); } catch { /* best effort */ }
});

test('SHELL_TOOL_DEF is a valid MCP tool named shell requiring command', () => {
  assert.equal(SHELL_TOOL_DEF.name, 'shell');
  assert.deepEqual(SHELL_TOOL_DEF.inputSchema.required, ['command']);
  assert.ok(SHELL_TOOL_DEF.inputSchema.properties.cwd);
  assert.ok(SHELL_TOOL_DEF.inputSchema.properties.timeout_ms);
});

test('isShellEnabled tracks AGENT_REPO_PATH (same gate as repo)', () => {
  assert.equal(isShellEnabled(), true);
  const saved = process.env.AGENT_REPO_PATH;
  delete process.env.AGENT_REPO_PATH;
  try { assert.equal(isShellEnabled(), false); } finally { process.env.AGENT_REPO_PATH = saved; }
});

test('runs a command and returns stdout + exit code 0', async () => {
  const r = await handleShellTool({ command: `node -e "console.log('hello-shell')"` });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /hello-shell/);
  assert.match(txt(r), /exit: 0/);
});

test('runs in the checkout root by default (cwd = repo)', async () => {
  const r = await handleShellTool({ command: `node -e "console.log(process.cwd())"` });
  assert.ok(!r.isError, txt(r));
  // realpath the tmp dir can differ (/var vs /private/var on macOS); compare basenames.
  assert.match(txt(r), new RegExp(repo.split(/[\\/]/).pop()));
});

test('a non-zero exit is surfaced as an error with the full output', async () => {
  const r = await handleShellTool({ command: `node -e "console.error('boom'); process.exit(3)"` });
  assert.ok(r.isError, txt(r));
  assert.match(txt(r), /exit: 3/);
  assert.match(txt(r), /boom/);
});

test('empty command errors clearly', async () => {
  const r = await handleShellTool({ command: '   ' });
  assert.ok(r.isError);
  assert.match(txt(r), /non-empty 'command'/);
});

test('cwd escaping the repo root is rejected', async () => {
  const r = await handleShellTool({ command: `node -e "1"`, cwd: '../../..' });
  assert.ok(r.isError, txt(r));
  assert.match(txt(r), /escape/i);
});

test('token-shaped secrets in output are masked', async () => {
  const fakeSecret = 'sk_' + 'live_FAKE_shell';
  const r = await handleShellTool({ command: `node -e "console.log('${fakeSecret}')"` });
  assert.ok(!r.isError, txt(r));
  assert.doesNotMatch(txt(r), new RegExp(fakeSecret), 'secret in stdout must be masked');
});

test('secret-shaped env vars are scrubbed from the child (cannot be echoed)', async () => {
  process.env.LC_TEST_API_KEY = 'plainNonTokenValue123';   // name matches denylist, value is NOT token-shaped
  try {
    const r = await handleShellTool({ command: `node -e "console.log('VAL=' + (process.env.LC_TEST_API_KEY || 'ABSENT'))"` });
    assert.ok(!r.isError, txt(r));
    assert.match(txt(r), /VAL=ABSENT/, 'a *_API_KEY env var must be stripped from the child');
    assert.doesNotMatch(txt(r), /plainNonTokenValue123/);
  } finally { delete process.env.LC_TEST_API_KEY; }
});

test('non-secret env vars survive the scrub (PATH kept so commands resolve)', () => {
  const env = scrubbedEnv({ PATH: '/usr/bin', HOME: '/home/x', MY_SECRET: 's', DB_PASSWORD: 'p', REDIS_URL: 'r', NORMAL_VAR: 'ok' });
  assert.equal(env.PATH, '/usr/bin');
  assert.equal(env.HOME, '/home/x');
  assert.equal(env.NORMAL_VAR, 'ok');
  assert.equal(env.MY_SECRET, undefined);
  assert.equal(env.DB_PASSWORD, undefined);
  assert.equal(env.REDIS_URL, undefined);
});

test('a command exceeding the timeout is killed and reported', async () => {
  const r = await handleShellTool({ command: `node -e "setTimeout(()=>{}, 5000)"`, timeout_ms: 500 });
  assert.ok(r.isError, txt(r));
  assert.match(txt(r), /timed out/i);
});

test('CE/dev no-op: unset AGENT_REPO_PATH → clean "not available"', async () => {
  const saved = process.env.AGENT_REPO_PATH;
  delete process.env.AGENT_REPO_PATH;
  try {
    const r = await handleShellTool({ command: `node -e "1"` });
    assert.ok(r.isError);
    assert.match(txt(r), /not available/i);
  } finally { process.env.AGENT_REPO_PATH = saved; }
});

test('timeout is clamped to the hard maximum', () => {
  assert.equal(_internals2.MAX_TIMEOUT_MS, 600_000);
});

test('oversized stdout is capped and flagged', async () => {
  const n = _internals2.MAX_OUTPUT_BYTES + 20_000;
  const r = await handleShellTool({ command: `node -e "process.stdout.write('x'.repeat(${n}))"` });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /stdout truncated at \d+ bytes/);
});
