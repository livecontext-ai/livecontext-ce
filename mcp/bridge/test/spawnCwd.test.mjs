/**
 * resolveAgentCwd decides where the spawned agent CLI runs: the source checkout when it
 * exists (so native Bash/Read/Edit operate on the platform's own code), else undefined so
 * the child inherits the bridge cwd (dev/CE no-op). The existence check is injected so the
 * branch is tested without touching the filesystem.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveAgentCwd } from '../lib/spawnCwd.mjs';

const yes = () => true;
const no = () => false;

test('resolveAgentCwd: existing checkout path → that path', () => {
  assert.equal(resolveAgentCwd('/opt/livecontext/workspaces/livecontext', yes),
    '/opt/livecontext/workspaces/livecontext');
});

test('resolveAgentCwd: configured but missing path → undefined (inherit bridge cwd)', () => {
  assert.equal(resolveAgentCwd('/opt/livecontext/workspaces/livecontext', no), undefined);
});

test('resolveAgentCwd: empty path (dev/CE, unset AGENT_REPO_PATH) → undefined, never probed', () => {
  let probed = false;
  const spy = () => { probed = true; return true; };
  assert.equal(resolveAgentCwd('', spy), undefined);
  assert.equal(probed, false, 'an empty path must short-circuit before the existence check');
});

test('resolveAgentCwd: defaults the existence check to fs.existsSync (a bogus path → undefined)', () => {
  // No injected predicate → real fs.existsSync; a path that cannot exist resolves to undefined.
  assert.equal(resolveAgentCwd('/no/such/dir/lc-bridge-xyz-should-not-exist'), undefined);
});
