import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import http from 'node:http';

// Exercise the actual launcher process. Docker is a command fixture; readiness is real HTTP.
async function launch(t, { legacy = false, invalid = false } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'lc-launcher-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const bin = join(directory, 'bin');
  mkdirSync(bin);
  writeFileSync(join(bin, 'docker'), `#!/usr/bin/env node
if (process.argv.includes('config')) {
  console.error('A harmless Compose warning');
  console.log(JSON.stringify({services:{frontend:{ports:[{target:3000,published:process.env.INVALID_CONFIG ? 'invalid' : process.env.FRONTEND_PORT || process.env.TEST_PORT}]}}}));
} else { console.log('docker fixture ' + process.argv.slice(2).join(' ')); }
`, { mode: 0o755 });
  const preload = join(directory, 'offline.mjs');
  writeFileSync(preload, "globalThis.fetch = async () => ({ ok: false, status: 503 });");
  const server = http.createServer((_request, response) => response.end('ready'));
  await new Promise(resolve => server.listen(0, resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const port = String(server.address().port);
  const environment = { ...process.env, PATH: bin + ':' + process.env.PATH, LIVECONTEXT_HOME: join(directory, 'home'), TEST_PORT: port };
  delete environment.LIVECONTEXT_PORT;
  delete environment.FRONTEND_PORT;
  if (legacy) { environment.LIVECONTEXT_PORT = port; environment.TEST_PORT = '1'; }
  if (invalid) environment.INVALID_CONFIG = '1';
  const child = spawn(process.execPath, ['--import', preload, resolve('cli/bin/livecontext.mjs'), 'up'], { env: environment });
  const timeout = setTimeout(() => child.kill(), 10000);
  let output = '';
  child.stdout.on('data', data => output += data);
  child.stderr.on('data', data => output += data);
  const code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
  clearTimeout(timeout);
  return { code, output, port };
}
const posix = { skip: process.platform === 'win32' ? 'Run in the Linux Node container used by CI' : false };
test('custom Compose port drives readiness and displayed URL despite stderr warnings', posix, async t => {
  const result = await launch(t);
  assert.equal(result.code, 0, result.output);
  assert.ok(result.output.includes('http://localhost:' + result.port), result.output);
});
test('legacy port override reaches Compose and the readiness probe', posix, async t => {
  const result = await launch(t, { legacy: true });
  assert.equal(result.code, 0, result.output);
  assert.ok(result.output.includes('http://localhost:' + result.port), result.output);
});
test('invalid frontend port fails before starting containers', posix, async t => {
  const result = await launch(t, { invalid: true });
  assert.equal(result.code, 1, result.output);
  assert.match(result.output, /Cannot determine the frontend port/);
  assert.doesNotMatch(result.output, /up -d/);
});
