/**
 * LC-053 regression: the spawned CLI child used to inherit the bridge's entire environment,
 * GATEWAY_SECRET_KEY included (claude minus three names, codex/gemini/mistral minus nothing, and
 * the server.mjs fallback `{ ...process.env }`). Pre-fix, every "must not contain" assertion on an
 * adapter below fails.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, rmSync, mkdtempSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

import { buildBaseChildEnv, PROVIDER_CREDENTIAL_ALLOW } from '../lib/childEnv.mjs';
import { ClaudeAdapter } from '../adapters/claude-adapter.mjs';
import { CodexAdapter } from '../adapters/codex-adapter.mjs';
import { GeminiAdapter } from '../adapters/gemini-adapter.mjs';
import { MistralAdapter } from '../adapters/mistral-adapter.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));

const SOURCE = {
  PATH: '/usr/bin',
  HOME: '/home/livecontext',
  USERPROFILE: 'C:\\Users\\x',
  APPDATA: 'C:\\Users\\x\\AppData\\Roaming',
  HTTPS_PROXY: 'http://proxy:3128',
  NO_PROXY: 'localhost',
  NODE_EXTRA_CA_CERTS: '/app/extra-ca/ca.pem',
  SSL_CERT_FILE: '/etc/ssl/cert.pem',
  CODEX_HOME: '/home/livecontext/.codex',
  CLAUDE_BIN: '/usr/bin/claude',
  GATEWAY_SECRET_KEY: 'gw-secret',
  REDIS_URL: 'redis://:pwd@10.0.9.1:6379/0',
  BRIDGE_META_NONCE: 'nonce',
  CREDENTIAL_ENCRYPTION_PASSWORD: 'x',
  STRIPE_SECRET_KEY: 'sk_live_x',
  DATABASE_URL: 'postgres://u:p@h/db',
  ANTHROPIC_API_KEY: 'sk-ant',
  CLAUDE_CODE_OAUTH_TOKEN: 'oauth',
  OPENAI_API_KEY: 'sk-oai',
  GEMINI_API_KEY: 'g',
  GOOGLE_API_KEY: 'g2',
  GOOGLE_APPLICATION_CREDENTIALS: '/secrets/sa.json',
  MISTRAL_API_KEY: 'm',
};

test('platform secrets and secret-shaped names are removed', () => {
  const env = buildBaseChildEnv([], SOURCE);
  for (const name of ['GATEWAY_SECRET_KEY', 'REDIS_URL', 'BRIDGE_META_NONCE', 'CREDENTIAL_ENCRYPTION_PASSWORD', 'STRIPE_SECRET_KEY', 'DATABASE_URL']) {
    assert.ok(!(name in env), `${name} must not reach the child`);
  }
});

test('what the CLIs need to start and authenticate is kept', () => {
  const env = buildBaseChildEnv([], SOURCE);
  for (const name of ['PATH', 'HOME', 'USERPROFILE', 'APPDATA', 'HTTPS_PROXY', 'NO_PROXY', 'NODE_EXTRA_CA_CERTS', 'SSL_CERT_FILE', 'CODEX_HOME', 'CLAUDE_BIN', ...PROVIDER_CREDENTIAL_ALLOW.filter(n => n in SOURCE)]) {
    assert.equal(env[name], SOURCE[name], `${name} must reach the child`);
  }
});

test('wider secret shapes are stripped: PASS, _PWD, WEBHOOK, PAT, JDBC, AWS_*, AZURE_*, URLs with credentials', () => {
  const env = buildBaseChildEnv([], {
    PATH: 'p', PWD: '/work', OLDPWD: '/prev',
    DB_PASS: '1', PASS_PHRASE: '2', SMTP_PWD: '3', SLACK_WEBHOOK: 'https://hooks', GITHUB_PAT: 'ghp',
    JDBC_URL: 'jdbc:postgresql://h/db', AWS_REGION: 'eu-west-1', AZURE_TENANT_ID: 't',
    HTTPS_PROXY: 'http://user:pw@proxy:3128', MONITOR_URL: 'https://u:p@mon', AGENT_CLI_URL: 'http://10.43.1.1:8090',
    NO_PROXY: 'localhost', PASSENGER_APP_ENV: 'x',
  });
  assert.deepEqual(Object.keys(env).sort(), ['AGENT_CLI_URL', 'NO_PROXY', 'OLDPWD', 'PASSENGER_APP_ENV', 'PATH', 'PWD']);
});

test('BRIDGE_CHILD_ENV_ALLOW re-admits a named variable but never a platform secret', () => {
  const env = buildBaseChildEnv([], {
    AWS_PROFILE: 'agent', GATEWAY_SECRET_KEY: 's', REDIS_URL: 'redis://:p@h', BRIDGE_CHILD_ENV_ALLOW: 'aws_profile, GATEWAY_SECRET_KEY,REDIS_URL',
  });
  assert.equal(env.AWS_PROFILE, 'agent');
  assert.ok(!('GATEWAY_SECRET_KEY' in env));
  assert.ok(!('REDIS_URL' in env));
});

test('the in-process shell tool scrubs with the same rules', async () => {
  const { scrubbedEnv } = await import('../../shell-tool.mjs');
  const env = scrubbedEnv({ PATH: 'p', DB_PASS: '1', HTTPS_PROXY: 'http://u:p@h:1', AWS_REGION: 'x' });
  assert.deepEqual(env, { PATH: 'p' });
});

test('the strip is case-insensitive (Windows env names) and honours adapter extras', () => {
  const env = buildBaseChildEnv(['CLAUDECODE'], { Gateway_Secret_Key: 'x', claudecode: '1', Path: 'p' });
  assert.deepEqual(env, { Path: 'p' });
});

test('every adapter builds its child env without GATEWAY_SECRET_KEY / REDIS_URL, keeping its provider key', () => {
  const saved = { ...process.env };
  process.env.GATEWAY_SECRET_KEY = 'gw-secret';
  process.env.REDIS_URL = 'redis://:pwd@host:6379/0';
  process.env.ANTHROPIC_API_KEY = 'sk-ant';
  const tmp = mkdtempSync(resolve(tmpdir(), 'childenv-'));
  try {
    for (const adapter of [new ClaudeAdapter(), new CodexAdapter(), new GeminiAdapter(), new MistralAdapter()]) {
      const env = adapter.buildChildEnv(tmp, undefined, false);
      const label = adapter.constructor.name;
      assert.ok(!('GATEWAY_SECRET_KEY' in env), `${label} leaks GATEWAY_SECRET_KEY`);
      assert.ok(!('REDIS_URL' in env), `${label} leaks REDIS_URL`);
      assert.equal(env.ANTHROPIC_API_KEY, 'sk-ant', `${label} dropped a provider key`);
      assert.ok(env.PATH || env.Path, `${label} dropped PATH`);
    }
  } finally {
    for (const k of Object.keys(process.env)) if (!(k in saved)) delete process.env[k];
    Object.assign(process.env, saved);
    rmSync(tmp, { recursive: true, force: true });
  }
});

test('server.mjs fallback (adapter without buildChildEnv) uses the scrubbed builder, never process.env', () => {
  const server = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
  assert.match(server, /adapter\.buildChildEnv \? adapter\.buildChildEnv\(tmpDir, reasoningEffort, restrictedToolset\) : buildBaseChildEnv\(\)/);
  assert.doesNotMatch(server, /: \{ \.\.\.process\.env \};/);
});
