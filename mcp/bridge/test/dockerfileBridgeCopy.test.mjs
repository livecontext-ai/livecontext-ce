/**
 * Regression guard for the bridge DOCKER image (CE publish path).
 *
 * `mcp/bridge/Dockerfile` copies sources explicitly:
 *   COPY agent-cli-server.mjs ./
 *   COPY repo-tool.mjs ./
 *   COPY shell-tool.mjs ./
 *   COPY bridge/ ./bridge/
 *
 * `agent-cli-server.mjs` STATICALLY imports its top-level mcp siblings
 * (`./repo-tool.mjs`, `./shell-tool.mjs`, …). If the Dockerfile omits a sibling it
 * imports, the bundled MCP subprocess dies with ERR_MODULE_NOT_FOUND at the first
 * agent run and the agent silently loses every platform tool.
 *
 * This is the Docker analogue of deployArtifactPackaging.test.mjs (which pins the
 * lane-2 systemd tar list). The systemd path shipped shell-tool.mjs; the Docker
 * image did NOT until this gate - hence the CE bridge image was broken at runtime.
 *
 * Per the convention in repoToolWiring.test.mjs, assert against source text.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const mcpDir = resolve(__dirname, '..', '..');            // mcp/
const dockerfile = readFileSync(resolve(mcpDir, 'bridge', 'Dockerfile'), 'utf8');
const cli = readFileSync(resolve(mcpDir, 'agent-cli-server.mjs'), 'utf8');

/** Top-level mcp siblings `source` statically imports via the given prefix. */
function topLevelMcpImports(source, prefix /* './' */) {
  const esc = prefix.replace(/\./g, '\\.').replace(/\//g, '\\/');
  const re = new RegExp(`from\\s*'${esc}([a-zA-Z0-9_-]+\\.mjs)'`, 'g');
  const out = new Set();
  let m;
  while ((m = re.exec(source)) !== null) out.add(m[1]);
  return out;
}

/** The set of *.mjs files the Dockerfile COPYs into the image root. */
function dockerfileCopiedMjs() {
  const out = new Set();
  const re = /^\s*COPY\s+([a-zA-Z0-9_-]+\.mjs)\s+\.\/\s*$/gm;
  let m;
  while ((m = re.exec(dockerfile)) !== null) out.add(m[1]);
  return out;
}

test('agent-cli-server still imports both repo and shell tools (gate did not regress)', () => {
  const siblings = topLevelMcpImports(cli, './');
  assert.ok(siblings.has('repo-tool.mjs'), 'agent-cli-server.mjs should import repo-tool.mjs');
  assert.ok(siblings.has('shell-tool.mjs'), 'agent-cli-server.mjs should import shell-tool.mjs');
});

test('bridge Dockerfile COPYs every top-level mcp/*.mjs agent-cli-server imports', () => {
  const required = topLevelMcpImports(cli, './');
  const copied = dockerfileCopiedMjs();
  assert.ok(required.size > 0, 'expected to find at least one imported mcp sibling');
  for (const file of required) {
    assert.ok(
      copied.has(file),
      `mcp/bridge/Dockerfile is missing "COPY ${file} ./" - the CE bridge image will ERR_MODULE_NOT_FOUND at first agent run`,
    );
  }
});

test('shell-tool.mjs is explicitly COPYed into the bridge image', () => {
  // Pin the specific file the Docker image omitted while the systemd tar shipped it.
  assert.ok(dockerfileCopiedMjs().has('shell-tool.mjs'));
});

test('npm + pip point at the combined system CA bundle, not the extra CA alone (MITM tarball-CDN fix)', () => {
  // Both npm `cafile` and pip `global.cert` REPLACE the trust store (they do not
  // append). Setting them to the interception root alone (/tmp/build-extra-ca.pem)
  // validated MITM-intercepted hosts but broke NON-intercepted CDN tarballs with
  // UNABLE_TO_GET_ISSUER_CERT_LOCALLY. They must use the combined bundle so both
  // public roots and the extra CA are trusted.
  assert.match(dockerfile, /npm config set cafile \/etc\/ssl\/certs\/ca-certificates\.crt/);
  assert.match(dockerfile, /pip config set global\.cert \/etc\/ssl\/certs\/ca-certificates\.crt/);
  assert.doesNotMatch(
    dockerfile,
    /config set (cafile|global\.cert) \/tmp\/build-extra-ca\.pem/,
    'npm/pip must not point at the extra CA alone - it replaces the trust store and breaks non-intercepted CDNs',
  );
});
