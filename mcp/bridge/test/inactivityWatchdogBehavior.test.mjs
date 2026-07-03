/**
 * BEHAVIORAL tests for the bridge inactivity watchdog (lib/inactivityWatchdog.mjs).
 *
 * The wiring test (inactivityAndTimeoutWiring.test.mjs) only pins the SOURCE of
 * server.mjs - a refactor keeping the strings but breaking the timer would pass it.
 * These tests exercise the real mechanics: a genuinely silent child process is
 * killed at the window, output re-arms the clock, <=0 disables, clear() disarms.
 * Windows are generous (>=200ms) with wide assertion margins to stay flake-free.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';

import { createInactivityWatchdog } from '../lib/inactivityWatchdog.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

test('a silent child is killed once the window elapses (the real INACTIVITY kill path)', async () => {
  // Child prints nothing and would live for 30s if the watchdog did not kill it.
  const child = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 30000)']);
  let tripped = false;
  const startedAt = Date.now();
  const watchdog = createInactivityWatchdog(300, () => {
    tripped = true;
    child.kill('SIGTERM');
  });
  watchdog.reset(); // armed from spawn: a CLI that never emits anything is caught too

  const [, signal] = await once(child, 'close');
  watchdog.clear();

  assert.equal(tripped, true, 'the watchdog must have tripped');
  assert.equal(signal, 'SIGTERM', 'the child must have been killed by the watchdog, not exited on its own');
  const elapsed = Date.now() - startedAt;
  assert.ok(elapsed >= 280, `the kill must not fire before the window (elapsed ${elapsed}ms)`);
  assert.ok(elapsed < 10000, `the kill must fire at the window, far before the child's natural 30s exit (elapsed ${elapsed}ms)`);
});

test('output re-arms the clock: a chatty-then-silent child survives the chatty phase and dies after the LAST line', async () => {
  // Child prints 5 lines 150ms apart (each < the 400ms window), then goes silent for 30s.
  const child = spawn(process.execPath, ['-e', `
    let n = 0;
    const t = setInterval(() => {
      console.log('line ' + n);
      if (++n >= 5) { clearInterval(t); setTimeout(() => {}, 30000); }
    }, 150);
  `]);
  let tripped = false;
  let lastLineAt = 0;
  const watchdog = createInactivityWatchdog(400, () => {
    tripped = true;
    child.kill('SIGTERM');
  });
  watchdog.reset();
  createInterface({ input: child.stdout }).on('line', () => {
    lastLineAt = Date.now();
    watchdog.reset(); // any output means the CLI is alive - restart the inactivity clock
  });

  const [, signal] = await once(child, 'close');
  watchdog.clear();

  assert.equal(tripped, true);
  assert.equal(signal, 'SIGTERM');
  // The kill must come from silence AFTER the last line, not during the chatty phase:
  // 5 lines * 150ms > the 400ms window, so without per-line resets the child would
  // have died mid-output and lastLineAt would sit well before close.
  const silence = Date.now() - lastLineAt;
  assert.ok(lastLineAt > 0, 'the child must have printed before being killed');
  assert.ok(silence >= 380, `the kill must fire a full window after the last line (silence ${silence}ms)`);
});

test('inactivityMs = 0 disables the watchdog entirely (0 = disabled contract)', async () => {
  let tripped = false;
  const watchdog = createInactivityWatchdog(0, () => { tripped = true; });
  watchdog.reset();
  assert.equal(watchdog.isArmed(), false, 'reset() must be a no-op when disabled');
  await sleep(150);
  assert.equal(tripped, false, 'a disabled watchdog must never fire');
});

test('negative / missing window also disables (defensive parity with the resolver contract)', async () => {
  const negative = createInactivityWatchdog(-5, () => { throw new Error('must not fire'); });
  negative.reset();
  assert.equal(negative.isArmed(), false);
  const missing = createInactivityWatchdog(undefined, () => { throw new Error('must not fire'); });
  missing.reset();
  assert.equal(missing.isArmed(), false);
  await sleep(50);
});

test('clear() disarms without firing (child close/error path)', async () => {
  let tripped = false;
  const watchdog = createInactivityWatchdog(100, () => { tripped = true; });
  watchdog.reset();
  assert.equal(watchdog.isArmed(), true);
  watchdog.clear();
  assert.equal(watchdog.isArmed(), false);
  await sleep(250);
  assert.equal(tripped, false, 'a cleared watchdog must never fire');
});

test('each reset() pushes the deadline back a full window', async () => {
  let trippedAt = 0;
  const startedAt = Date.now();
  const watchdog = createInactivityWatchdog(300, () => { trippedAt = Date.now(); });
  watchdog.reset();
  await sleep(150);
  watchdog.reset(); // half-way re-arm: total life must be ~150 + 300, not 300
  while (!trippedAt) await sleep(20);
  const lifetime = trippedAt - startedAt;
  assert.ok(lifetime >= 420, `the mid-window reset must extend the deadline (lifetime ${lifetime}ms)`);
});
