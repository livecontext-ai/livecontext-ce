/**
 * The one ordering rule the recovery depends on, and the one place it cannot be tested
 * by executing anything.
 *
 * `lib/usageRecovery.mjs` decides WHETHER to recover; `server.mjs` decides WHEN. The when
 * is a single constraint: the run's temp dir is the CLI's HOME for that run, and it holds
 * the only record of what a killed turn spent, so the recovery must happen before
 * `rmSync(tmpDir)`. Move the call one line down and the feature is silently dead - no
 * failing test, no log line, just stopped runs billing zero again, which is exactly the
 * bug it was written to fix.
 *
 * `server.mjs` calls `app.listen` at import time and exports nothing, so it cannot be
 * imported to be exercised. Same reason and same shape as inactivityAndTimeoutWiring,
 * toolHoldWiring, spawnCwdWiring and enabledModulesWiring.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const serverSource = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');

/** The body of each `child.on('<event>', ...)` handler that deletes the temp dir. */
function handlersThatDeleteTmpDir() {
  const found = [];
  const re = /child\.on\('(close|error)'/g;
  let match;
  while ((match = re.exec(serverSource)) !== null) {
    const body = serverSource.slice(match.index, match.index + 1200);
    const removal = body.indexOf('rmSync(tmpDir');
    if (removal === -1) continue;
    found.push({ event: match[1], body, removal });
  }
  return found;
}

test('both CLI exit paths recover the usage before deleting the temp dir', () => {
  const handlers = handlersThatDeleteTmpDir();

  assert.equal(handlers.length, 2,
    'expected the close and error exit paths - if this drops to 1, an exit path stopped '
    + 'cleaning up or stopped existing, and either way the pairing below is not checked');

  for (const { event, body, removal } of handlers) {
    const recovery = body.indexOf('applyRecoveredUsage()');
    assert.notEqual(recovery, -1,
      `the ${event} handler deletes the temp dir without recovering usage first`);
    assert.ok(recovery < removal,
      `the ${event} handler recovers usage AFTER deleting the temp dir, so it can only `
      + 'ever find nothing');
  }
});

test('server.mjs uses the shared recovery module rather than its own copy of the rules', () => {
  assert.match(
    serverSource,
    /import \{[^}]*\bapplyRecoveredUsage\b[^}]*\} from '\.\/lib\/usageRecovery\.mjs'/,
    'both the decision and the mutation that sets the billed amount live in one tested place',
  );
  assert.doesNotMatch(
    serverSource,
    /(?:function\s+|(?:const|let|var)\s+)recoverUnreportedUsage\s*[=(]/,
    'a private copy here drifts from the module its tests cover',
  );
  // The run closure may format the log line and assign its own `usage`, but must not
  // re-implement what goes into perCallUsages: that list is where the cache and reasoning
  // breakdown is summed from, so a copy here would decide a billed amount that no test in
  // the module can see.
  assert.doesNotMatch(
    serverSource,
    /perCallUsages\.push\(/,
    'the recovered entry is recorded by the module, where a test can see it',
  );
});

test('the recovery does not go through recordCallUsage', () => {
  // recordCallUsage also stamps an iteration timestamp - which would invent an iteration
  // duration for a turn that never completed - and re-runs the budget guard against a
  // child that is already dead.
  const applyBlock = serverSource.match(/const applyRecoveredUsage = \(\) => \{[\s\S]*?\n {4}\};/);
  assert.ok(applyBlock, 'the apply helper must exist');
  assert.doesNotMatch(applyBlock[0], /recordCallUsage\s*\(/);
});
