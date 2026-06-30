/**
 * Regression net for the spawn-boundary NUL guard (`stripNulFromArgs`).
 *
 * This is the unconditional last line: even if a NUL byte ever reaches the CLI
 * argument vector from ANY source (chat text, system prompt, a future code
 * path, a binary that slipped past attachment routing), the run must not crash
 * with "The argument 'args[1]' must be a string without null bytes".
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { stripNulFromArgs } from '../lib/spawnSafety.mjs';

const NUL = String.fromCharCode(0);

test('strips NUL from the claude -p prompt arg (the prod crash vector)', () => {
  const logs = [];
  const args = ['-p', 'hello' + NUL + 'world', '--model', 'claude-opus-4-8'];
  const out = stripNulFromArgs(args, (m) => logs.push(m));
  assert.equal(out[1], 'helloworld');
  assert.ok(!out[1].includes(NUL));
  assert.equal(logs.length, 1, 'an offending arg should be logged');
  assert.ok(logs[0].includes('1'), 'log names the offending index');
});

test('clean args pass through unchanged and log nothing', () => {
  const logs = [];
  const args = ['-p', 'normal prompt', '--max-turns', '20'];
  const out = stripNulFromArgs(args, (m) => logs.push(m));
  assert.deepEqual(out, args);
  assert.equal(logs.length, 0);
});

test('strips NUL from multiple args and reports every index', () => {
  const logs = [];
  const args = ['a' + NUL, 'b', 'c' + NUL + 'c'];
  const out = stripNulFromArgs(args, (m) => logs.push(m));
  assert.deepEqual(out, ['a', 'b', 'cc']);
  assert.ok(logs[0].includes('0') && logs[0].includes('2'));
});

test('the result never contains a NUL byte, whatever the input', () => {
  const args = [NUL, 'x' + NUL + 'y' + NUL, 'ok', NUL + NUL];
  const out = stripNulFromArgs(args, () => {});
  for (const a of out) assert.ok(!a.includes(NUL));
});

test('non-string args pass through untouched', () => {
  const obj = {};
  const out = stripNulFromArgs(['-p', 'text', 5, null, obj], () => {});
  assert.equal(out[2], 5);
  assert.equal(out[3], null);
  assert.equal(out[4], obj);
});

test('a non-array input is returned as-is (defensive)', () => {
  assert.equal(stripNulFromArgs(null, () => {}), null);
  assert.equal(stripNulFromArgs(undefined, () => {}), undefined);
});
