/**
 * LC-001 rollout guard: every artifact that starts a bridge makes an explicit, working choice
 * about the bind address and gateway authentication.
 *
 * server.mjs now defaults to loopback-only and to HMAC enforcement ON. A launcher that inherits
 * both defaults without a secret answers every dispatch 503, and one whose caller sits on another
 * host is unreachable; both surface as an EMPTY chat (the Java clients return null), never as an
 * error. So each launcher is pinned here:
 *   - prod systemd unit: loopback + the LAN address the k3s pods dial (helm external.bridge.url),
 *     never 0.0.0.0, enforcement left ON;
 *   - container image (every compose file, CE + e2e slots): binds the container interface and
 *     leaves enforcement in auto mode; the compose files share a per-install secret with the app;
 *   - start-all.ps1 (local dev): loopback, enforcement off.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, '..', '..', '..');
const read = (p) => readFileSync(resolve(repoRoot, p), 'utf8').replace(/\r\n/g, '\n');

test('prod systemd unit binds loopback + the LAN address the pods dial, never 0.0.0.0, and keeps enforcement on', (t) => {
  const unitPath = 'deploy/systemd/lc-bridge.service';
  if (!existsSync(resolve(repoRoot, unitPath))) return t.skip('unit not in this checkout (CE export)');
  const unit = read(unitPath);
  const bind = unit.match(/^Environment=BRIDGE_BIND_ADDRESS=(\S+)$/m);
  assert.ok(bind, 'lc-bridge.service must set BRIDGE_BIND_ADDRESS');
  const addresses = bind[1].split(',');
  assert.ok(!addresses.includes('0.0.0.0'), '0.0.0.0 also binds the public interface');
  assert.ok(addresses.includes('127.0.0.1'), 'the deploy smoke check curls localhost:8093');

  const values = read('deploy/helm/livecontext/values-prod.yaml');
  const bridgeUrl = values.match(/^\s*bridge:\n\s*url:\s*http:\/\/([\d.]+):8093/m);
  assert.ok(bridgeUrl, 'values-prod external.bridge.url not found');
  assert.ok(addresses.includes(bridgeUrl[1]), `the pods dial ${bridgeUrl[1]}, which the unit does not bind`);

  assert.ok(unit.indexOf('Environment=BRIDGE_BIND_ADDRESS=') < unit.indexOf('EnvironmentFile='),
    'declared before EnvironmentFile so the hand-managed .env can override it');
  assert.match(unit, /^Environment=BRIDGE_REQUIRE_GATEWAY_AUTH=true$/m,
    'prod forces enforcement on, so a missing secret fails closed (503) instead of opening the bridge');
  assert.ok(unit.indexOf('Environment=BRIDGE_REQUIRE_GATEWAY_AUTH=') < unit.indexOf('EnvironmentFile='));
});

test('bridge image binds the container interface and leaves enforcement in AUTO mode (on iff a secret exists)', () => {
  const dockerfile = read('mcp/bridge/Dockerfile');
  assert.match(dockerfile, /^ENV BRIDGE_BIND_ADDRESS=0\.0\.0\.0$/m);
  assert.doesNotMatch(dockerfile, /BRIDGE_REQUIRE_GATEWAY_AUTH=/, 'the image must not force enforcement on or off');
});

test('every CE compose file shares the per-install secret between the app and the bridge (secure by default)', () => {
  const entrypoint = read('backend/monolith-service/ce-entrypoint.sh');
  assert.match(entrypoint, /BRIDGE_SECRET_DIR="\$\{BRIDGE_SECRET_DIR:-\/app\/data\/bridge-secret\}"/);
  assert.match(entrypoint, /^ensure_bridge_shared_secret$/m, 'the generator must actually run');
  assert.match(entrypoint, /export BRIDGE_SHARED_SECRET/);
  assert.match(read('backend/monolith-service/src/main/resources/application-ce.yml'), /shared-secret: \$\{BRIDGE_SHARED_SECRET:\}/);
  for (const path of [
    'docker/docker-compose-ce-monolith.yml',
    'cli/assets/docker-compose.yml',
    'deploy/ce-export/public-assets/docker-compose-ce-monolith.yml',
  ]) {
    if (!existsSync(resolve(repoRoot, path))) continue;
    const compose = read(path);
    const bridge = compose.slice(compose.indexOf('\n  bridge:\n'), compose.indexOf('\n  livecontext:\n'));
    assert.match(bridge, /BRIDGE_SHARED_SECRET_FILE: \/run\/lc-bridge-secret\/bridge-shared-secret/, `${path}: bridge must read the shared secret`);
    assert.match(bridge, /- livecontext_bridge_secret:\/run\/lc-bridge-secret:ro/, `${path}: bridge must mount the secret volume read-only`);
    assert.doesNotMatch(bridge, /livecontext_keys/, `${path}: the bridge must never mount the JWT/encryption keys volume`);
    assert.match(compose, /- livecontext_bridge_secret:\/app\/data\/bridge-secret/, `${path}: app must mount the secret volume`);
    assert.match(compose, /^ {2}livecontext_bridge_secret:$/m, `${path}: volume must be declared`);
    assert.doesNotMatch(compose, /BRIDGE_BIND_ADDRESS:\s*["']?127\.0\.0\.1/, `${path}: loopback is unreachable from the app container`);
    assert.doesNotMatch(compose, /BRIDGE_REQUIRE_GATEWAY_AUTH:\s*["']?(true|false)/, `${path}: leave the image's auto mode alone`);
  }
});

test('start-all.ps1 (local dev) sets both explicitly', (t) => {
  if (!existsSync(resolve(repoRoot, 'start-all.ps1'))) return t.skip('start-all.ps1 not in this checkout');
  const script = read('start-all.ps1');
  const block = script.slice(script.indexOf('"bridge" = @{'), script.indexOf('"conversation" = @{'));
  assert.match(block, /"BRIDGE_REQUIRE_GATEWAY_AUTH" = "false"/);
  assert.match(block, /"BRIDGE_BIND_ADDRESS" = "127\.0\.0\.1"/);
});

test('prod helm renders the host-tools opt-in the Java bridge clients read', (t) => {
  const helpersPath = 'deploy/helm/livecontext/templates/_helpers.tpl';
  if (!existsSync(resolve(repoRoot, helpersPath))) return t.skip('helm chart not in this checkout');
  assert.match(read(helpersPath), /- name: CONVERSATION_BRIDGE_HOST_TOOLS_ENABLED\n\s+value: \{\{ \$root\.Values\.external\.bridge\.hostToolsEnabled/);
  for (const yml of ['backend/conversation-service/src/main/resources/application.yml', 'backend/agent-service/src/main/resources/application.yml']) {
    assert.match(read(yml), /host-tools-enabled: \$\{CONVERSATION_BRIDGE_HOST_TOOLS_ENABLED:false\}/, yml);
  }
});
