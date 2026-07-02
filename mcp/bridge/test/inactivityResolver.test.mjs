/**
 * Unit tests for the bridge inactivity window resolver (lib/inactivityResolver.mjs).
 *
 * The precedence (per-agent credential > DTO field > 5-min default), the seconds->ms conversion,
 * the 0=disabled rule, and the blank/NaN fallbacks must match the Java consumer
 * AgentLoopService.resolveInactivityWindowMs so the bridge and in-process paths agree.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveInactivityMs, DEFAULT_INACTIVITY_MS } from '../lib/inactivityResolver.mjs';

test('credential (number seconds) wins and converts to ms', () => {
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: 120 }), 120000);
});

test('credential string (from JSON) is parsed', () => {
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: '90' }), 90000);
});

test('credential 0 disables the watchdog', () => {
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: 0 }), 0);
});

test('credential takes precedence over the DTO field', () => {
  assert.equal(resolveInactivityMs(45, { __inactivityTimeoutSeconds__: 10 }), 10000);
});

test('a blank credential falls back to the DTO field', () => {
  assert.equal(resolveInactivityMs(45, { __inactivityTimeoutSeconds__: '' }), 45000);
});

test('no credential -> the DTO field is used', () => {
  assert.equal(resolveInactivityMs(30, undefined), 30000);
  assert.equal(resolveInactivityMs(30, {}), 30000);
});

test('no credential and no DTO field -> 5-minute default', () => {
  assert.equal(resolveInactivityMs(null, undefined), DEFAULT_INACTIVITY_MS);
  assert.equal(resolveInactivityMs(undefined, {}), DEFAULT_INACTIVITY_MS);
});

test('a malformed credential falls back to the default', () => {
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: 'abc' }), DEFAULT_INACTIVITY_MS);
});

test('a malformed credential falls through to the DTO field when present', () => {
  assert.equal(resolveInactivityMs(45, { __inactivityTimeoutSeconds__: 'abc' }), 45000);
});

test('DTO field 0 disables the watchdog', () => {
  assert.equal(resolveInactivityMs(0, undefined), 0);
});

// Range enforcement on the credential channel (parity with Java
// AgentLoopService.resolveInactivityWindowMs): 0 = disabled, 10-7200 = custom,
// anything else is out-of-contract and must not arm a seconds-scale watchdog.

test('a below-contract credential (1-9s) is ignored, not armed', () => {
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: 3 }), DEFAULT_INACTIVITY_MS);
  assert.equal(resolveInactivityMs(45, { __inactivityTimeoutSeconds__: 3 }), 45000);
});

test('an above-contract credential (>7200s) is ignored, not clamped', () => {
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: 999999 }), DEFAULT_INACTIVITY_MS);
});

test('a negative credential is out-of-contract (only exactly 0 disables)', () => {
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: -5 }), DEFAULT_INACTIVITY_MS);
});

test('the contract bounds 10 and 7200 are accepted on the credential channel', () => {
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: 10 }), 10000);
  assert.equal(resolveInactivityMs(null, { __inactivityTimeoutSeconds__: 7200 }), 7200000);
});
