/**
 * LC-053: optional dedicated uid for the spawned agent (lib/childUser.mjs).
 * Pre-fix the agent always ran as the bridge uid and could read /proc/<bridge pid>/environ.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { childUserFromEnv, wrapSpawnForChildUser, grantChildAccess, childUserEnv } from '../lib/childUser.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));

test('unset BRIDGE_CHILD_UID keeps the legacy same-uid spawn', () => {
  assert.equal(childUserFromEnv({}, 'linux'), null);
  const plan = { cmd: 'claude', args: ['-p'], useShell: false };
  assert.deepEqual(wrapSpawnForChildUser(plan, null), plan);
});

test('BRIDGE_CHILD_UID wraps the CLI in setpriv and clears every capability set', () => {
  const user = childUserFromEnv({ BRIDGE_CHILD_UID: '991', BRIDGE_CHILD_GID: '992', BRIDGE_CHILD_HOME: '/home/a' }, 'linux');
  assert.deepEqual(user, { uid: 991, gid: 992, home: '/home/a', setpriv: '/usr/bin/setpriv' });
  const plan = wrapSpawnForChildUser({ cmd: '/usr/bin/claude', args: ['-p', '--verbose'], useShell: false }, user);
  assert.equal(plan.cmd, '/usr/bin/setpriv');
  assert.deepEqual(plan.args, [
    '--reuid=991', '--regid=992', '--clear-groups',
    '--inh-caps=-all', '--ambient-caps=-all', '--bounding-set=-all',
    '--', '/usr/bin/claude', '-p', '--verbose',
  ]);
  assert.equal(plan.useShell, false);
});

test('invalid ids and Windows are refused loudly instead of silently running as the bridge uid', () => {
  assert.throws(() => childUserFromEnv({ BRIDGE_CHILD_UID: '__LC_AGENT_UID__' }, 'linux'), /positive integer/);
  assert.throws(() => childUserFromEnv({ BRIDGE_CHILD_UID: '0' }, 'linux'), /positive integer/);
  assert.throws(() => childUserFromEnv({ BRIDGE_CHILD_UID: '991' }, 'win32'), /not supported on Windows/);
  assert.throws(() => wrapSpawnForChildUser({ cmd: 'x', args: [], useShell: true }, { uid: 1, gid: 1, setpriv: 's' }), /shell-wrapped/);
});

test('grantChildAccess hands the per-run tree to the agent group (setgid dirs, rw files), skipping symlinks', () => {
  const calls = [];
  const tree = {
    '/r': { dir: true, children: ['a', 'l'] },
    '/r/a': { dir: false },
    '/r/l': { link: true },
  };
  const fsImpl = {
    lstatSync: (p) => ({ isSymbolicLink: () => !!tree[p.replace(/\\/g, '/')].link, isDirectory: () => !!tree[p.replace(/\\/g, '/')].dir }),
    readdirSync: (p) => tree[p.replace(/\\/g, '/')].children,
    chownSync: (p, uid, gid) => calls.push(['chown', p.replace(/\\/g, '/'), uid, gid]),
    chmodSync: (p, mode) => calls.push(['chmod', p.replace(/\\/g, '/'), mode.toString(8)]),
  };
  grantChildAccess(['/r', null], { gid: 992 }, fsImpl, 1001);
  assert.deepEqual(calls, [
    ['chown', '/r', 1001, 992], ['chmod', '/r', '2770'],
    ['chown', '/r/a', 1001, 992], ['chmod', '/r/a', '660'],
  ]);
});

test('grantChildAccess propagates a failure (the run must fail, not fall back to the bridge uid)', () => {
  const fsImpl = {
    lstatSync: () => ({ isSymbolicLink: () => false, isDirectory: () => false }),
    readdirSync: () => [],
    chownSync: () => { const e = new Error('EPERM'); e.code = 'EPERM'; throw e; },
    chmodSync: () => {},
  };
  assert.throws(() => grantChildAccess(['/x'], { gid: 5 }, fsImpl, 1), /EPERM/);
});

test('childUserEnv points HOME at the agent home unless the adapter already set a per-run HOME', () => {
  const user = { home: '/opt/livecontext/agent-home' };
  assert.equal(childUserEnv({ HOME: '/home/livecontext', USERPROFILE: 'x' }, user, '/home/livecontext').HOME, '/opt/livecontext/agent-home');
  assert.equal(childUserEnv({ HOME: '/tmp/bridge-run' }, user, '/home/livecontext').HOME, '/tmp/bridge-run');
  assert.deepEqual(childUserEnv({ HOME: 'h' }, null), { HOME: 'h' });
});

test('server.mjs spawns through the child-user plan and warns when it is not configured', () => {
  const server = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
  assert.match(server, /grantChildAccess\(\[tmpDir, restrictedCwd, throwawayCwd\], CHILD_USER\);/);
  assert.match(server, /plan = wrapSpawnForChildUser\(plan, CHILD_USER\);/);
  assert.match(server, /const child = spawn\(plan\.cmd, plan\.args, \{/);
  assert.match(server, /agent processes run as the bridge uid \(BRIDGE_CHILD_UID unset\)/);
});

test('the hardening drop-in grants exactly the capabilities setpriv needs and loads the secret as a credential', () => {
  const dropIn = readFileSync(resolve(__dirname, '..', '..', '..', 'deploy', 'systemd', 'lc-bridge-child-uid.conf'), 'utf8');
  assert.match(dropIn, /^AmbientCapabilities=CAP_SETUID CAP_SETGID CAP_SETPCAP$/m);
  assert.match(dropIn, /^CapabilityBoundingSet=CAP_SETUID CAP_SETGID CAP_SETPCAP$/m);
  assert.match(dropIn, /^LoadCredential=gateway-secret:\/etc\/livecontext\/bridge-gateway-secret$/m);
  const unit = readFileSync(resolve(__dirname, '..', '..', '..', 'deploy', 'systemd', 'lc-bridge.service'), 'utf8');
  assert.match(unit, /^ProtectProc=invisible$/m);
  assert.match(unit, /^NoNewPrivileges=true$/m);
  assert.doesNotMatch(unit, /^AmbientCapabilities=/m, 'the main unit must not hand capabilities to a same-uid agent');
});
