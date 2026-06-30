/**
 * Tests for cli-detector.mjs.
 *
 * Strategy: instead of mocking child_process, we point each CLI's binary
 * env var at a real tiny Node script that simulates the various outcomes
 * (version OK, exit code != 0, missing binary). This exercises the actual
 * spawn path and works identically on Linux/macOS/Windows.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Import after each env mutation: use dynamic import + cache busting via query string.
async function freshDetector() {
  const mod = await import(`../cli-detector.mjs?ts=${Date.now()}-${Math.random()}`);
  return mod;
}

function makeScript(body) {
  const dir = mkdtempSync(join(tmpdir(), 'bridge-test-'));
  const file = join(dir, 'fake.mjs');
  writeFileSync(file, body, 'utf8');
  try { chmodSync(file, 0o755); } catch { /* noop on windows */ }
  return file;
}

test('detectOne: reports installed + parses version when probe exits 0', async () => {
  const script = makeScript(`console.log('fake-cli 1.2.3'); process.exit(0);`);
  // CLAUDE_CLI_JS triggers "node <path> --version" path → simplest cross-OS hook.
  process.env.CLAUDE_CLI_JS = script;
  try {
    const { detectOne } = await freshDetector();
    const result = await detectOne('claudeCode');
    assert.equal(result.installed, true);
    assert.equal(result.version, '1.2.3');
    assert.equal(result.error, null);
    assert.equal(result.id, 'claudeCode');
  } finally {
    delete process.env.CLAUDE_CLI_JS;
  }
});

test('detectOne: reports not installed when binary is missing (ENOENT)', async () => {
  // A path that absolutely does not exist on any OS.
  process.env.CODEX_BIN = join(tmpdir(), 'definitely-not-a-real-binary-xyz123');
  try {
    const { detectOne } = await freshDetector();
    const result = await detectOne('codex');
    assert.equal(result.installed, false);
    assert.equal(result.version, null);
    assert.match(result.error, /not found|ENOENT|recognized|spawn/i);
  } finally {
    delete process.env.CODEX_BIN;
  }
});

test('detectOne: reports not installed when probe exits with non-zero code', async () => {
  const script = makeScript(`process.stderr.write('boom\\n'); process.exit(2);`);
  process.env.CLAUDE_CLI_JS = script;
  try {
    const { detectOne } = await freshDetector();
    const result = await detectOne('claudeCode');
    assert.equal(result.installed, false);
    assert.match(result.error, /code 2|not found/i);
  } finally {
    delete process.env.CLAUDE_CLI_JS;
  }
});

test('detectOne: returns descriptive error for unknown id', async () => {
  const { detectOne } = await freshDetector();
  const result = await detectOne('nope');
  assert.equal(result.installed, false);
  assert.equal(result.error, 'unknown cli id');
});

test('detectAll: returns all four CLI ids', async () => {
  // None of the env vars set → all four will resolve to default binaries
  // which most likely don't exist in CI. We just assert shape.
  const { detectAll, CLI_IDS } = await freshDetector();
  const result = await detectAll({ force: true });
  assert.deepEqual(Object.keys(result).sort(), [...CLI_IDS].sort());
  for (const id of CLI_IDS) {
    assert.equal(typeof result[id].installed, 'boolean');
    assert.ok('binary' in result[id]);
    assert.ok('version' in result[id]);
    assert.ok('error' in result[id]);
  }
});

test('detectAll: second call within TTL is served from cache', async () => {
  const { detectAll } = await freshDetector();
  await detectAll({ force: true });          // prime cache
  const t0 = Date.now();
  await detectAll();                          // should be instant
  const elapsed = Date.now() - t0;
  // Cached path must avoid spawning four processes again - well under 50ms.
  assert.ok(elapsed < 50, `cache hit should be fast, took ${elapsed}ms`);
});

test('detectAll: concurrent callers share the same in-flight Promise', async () => {
  const { detectAll } = await freshDetector();
  // Two parallel calls before any cache exists must coalesce - assert by
  // identity that both resolutions point at the exact same map object.
  const [a, b] = await Promise.all([
    detectAll({ force: true }),
    detectAll({ force: true }),
  ]);
  assert.strictEqual(a, b, 'parallel detectAll calls should share one result');
});

test('invalidateCache: forces a fresh probe on next call', async () => {
  const { detectAll, invalidateCache } = await freshDetector();
  const first = await detectAll({ force: true });
  invalidateCache();
  const second = await detectAll();
  assert.notStrictEqual(first, second, 'after invalidate, next call must produce a new map');
});

test('parseVersion: returns null for output without version-shaped tokens', async () => {
  // exit 0 + non-numeric output → installed:true but version null
  // (we no longer fall back to "first 80 chars of stdout" which produced garbage).
  const script = makeScript(`console.log('Welcome to fake-cli, type --help'); process.exit(0);`);
  process.env.CLAUDE_CLI_JS = script;
  try {
    const { detectOne } = await freshDetector();
    const result = await detectOne('claudeCode');
    assert.equal(result.installed, true);
    assert.equal(result.version, null);
  } finally {
    delete process.env.CLAUDE_CLI_JS;
  }
});

test('cli-detector: detectAuth - clean home + no provider key => not authenticated; the API-key env flips it', async () => {
  const { detectAuth } = await freshDetector();
  const KEYS = ['HOME', 'USERPROFILE', 'CODEX_HOME', 'GEMINI_HOME',
    'ANTHROPIC_API_KEY', 'OPENAI_API_KEY', 'GEMINI_API_KEY', 'GOOGLE_API_KEY', 'MISTRAL_API_KEY'];
  const saved = Object.fromEntries(KEYS.map((k) => [k, process.env[k]]));
  try {
    // Empty home + no provider keys => every on-disk credential check misses, so an
    // INSTALLED-but-not-logged-in CLI reads as not authenticated (the whole point:
    // the badge must not show a green "connected" for it).
    const fakeHome = mkdtempSync(join(tmpdir(), 'bridge-auth-'));
    process.env.HOME = fakeHome;
    process.env.USERPROFILE = fakeHome;
    delete process.env.CODEX_HOME;
    delete process.env.GEMINI_HOME;
    for (const k of ['ANTHROPIC_API_KEY', 'OPENAI_API_KEY', 'GEMINI_API_KEY', 'GOOGLE_API_KEY', 'MISTRAL_API_KEY']) {
      delete process.env[k];
    }

    assert.equal(detectAuth('claudeCode'), false);
    assert.equal(detectAuth('codex'), false);
    assert.equal(detectAuth('geminiCli'), false);
    assert.equal(detectAuth('mistralVibe'), false);
    assert.equal(detectAuth('unknownCli'), false, 'unknown id is never authenticated');

    // Each CLI honours its provider API-key env var => authenticated (API mode).
    process.env.ANTHROPIC_API_KEY = 'sk-a';
    assert.equal(detectAuth('claudeCode'), true);
    process.env.OPENAI_API_KEY = 'sk-o';
    assert.equal(detectAuth('codex'), true);
    process.env.GEMINI_API_KEY = 'g';
    assert.equal(detectAuth('geminiCli'), true);
    process.env.MISTRAL_API_KEY = 'm';
    assert.equal(detectAuth('mistralVibe'), true);
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k]; else process.env[k] = v;
    }
  }
});

test('cli-detector: detectOne includes an authenticated flag on the entry', async () => {
  const { detectOne } = await freshDetector();
  const result = await detectOne('claudeCode');
  // Whatever the install/auth state on the test host, the field must be present and
  // boolean so the status layer can thread it through to the badge.
  assert.equal(typeof result.authenticated, 'boolean');
});
