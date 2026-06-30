/**
 * The bridge logs its Redis connection on startup. REDIS_URL carries the Redis
 * PASSWORD (redis://:<pwd>@host), so logging it raw leaks the password to journald
 * - and from there to Loki. server.mjs connects to Redis + listens on import, so we
 * can't import it here; assert against source text (same convention as the wiring
 * tests). The masking behaviour itself is exercised against the reused maskSecrets.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const { maskSecrets } = await import('../../repo-tool.mjs');
const __dirname = dirname(fileURLToPath(import.meta.url));
const server = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');

test('server.mjs never logs the raw REDIS_URL (password must not reach journald/Loki)', () => {
  const logLines = server.split(/\r?\n/).filter((l) => /console\.\w+\(/.test(l) && /REDIS_URL/.test(l));
  assert.ok(logLines.length >= 2, 'expected the two Redis startup log lines to reference REDIS_URL');
  for (const l of logLines) {
    assert.match(l, /maskSecrets\(\s*REDIS_URL\s*\)/, `Redis URL must be masked in log: ${l.trim()}`);
    assert.doesNotMatch(l, /\$\{\s*REDIS_URL\s*\}/, `raw \${REDIS_URL} must not be logged: ${l.trim()}`);
  }
});

test('maskSecrets hides the password in a redis:// connection URL', () => {
  const masked = maskSecrets('redis://:example-password@203.0.113.10:6379/0');
  assert.doesNotMatch(masked, /example-password/, 'the Redis password must be masked');
  assert.match(masked, /redis:\/\/:\*\*\*@203\.0\.113\.10:6379/, 'host/port stay visible, only the secret is hidden');
});
