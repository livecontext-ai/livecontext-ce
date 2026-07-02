/**
 * Tests for the reasoning-effort → per-CLI knob mapping (lib/reasoningEffort.mjs)
 * and its integration into the Codex/Claude adapters. Covers the normalization
 * contract, the Codex `-c model_reasoning_effort` arg (incl. xhigh/max clamping -
 * codex has no `max` level), the Claude categorical CLAUDE_CODE_EFFORT_LEVEL env
 * (incl. the minimal→low clamp), and the degrade-safe "omit when unset" path.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeEffort, codexReasoningArgs, claudeReasoningEnv } from '../lib/reasoningEffort.mjs';
import { CodexAdapter } from '../adapters/codex-adapter.mjs';
import { ClaudeAdapter } from '../adapters/claude-adapter.mjs';

test('normalizeEffort accepts known levels case/whitespace-insensitively', () => {
  assert.equal(normalizeEffort('high'), 'high');
  assert.equal(normalizeEffort(' HIGH '), 'high');
  assert.equal(normalizeEffort('XHigh'), 'xhigh');
  assert.equal(normalizeEffort('minimal'), 'minimal');
  assert.equal(normalizeEffort('MAX'), 'max');
});

test('normalizeEffort returns null for blank, unknown, and non-string input', () => {
  assert.equal(normalizeEffort(''), null);
  assert.equal(normalizeEffort('   '), null);
  assert.equal(normalizeEffort('ultra'), null);
  assert.equal(normalizeEffort(null), null);
  assert.equal(normalizeEffort(42), null);
});

test('codexReasoningArgs maps a level to the -c model_reasoning_effort override', () => {
  assert.deepEqual(codexReasoningArgs('high', 'gpt-5-codex'), ['-c', 'model_reasoning_effort=high']);
  assert.deepEqual(codexReasoningArgs('medium', 'gpt-5-codex'), ['-c', 'model_reasoning_effort=medium']);
});

test('codexReasoningArgs clamps xhigh to high on non-codex-max models', () => {
  assert.deepEqual(codexReasoningArgs('xhigh', 'gpt-5-codex'), ['-c', 'model_reasoning_effort=high']);
});

test('codexReasoningArgs keeps xhigh on codex-max variants', () => {
  assert.deepEqual(codexReasoningArgs('xhigh', 'gpt-5.1-codex-max'), ['-c', 'model_reasoning_effort=xhigh']);
});

test('codexReasoningArgs maps max to xhigh on codex-max variants (codex has no max level)', () => {
  assert.deepEqual(codexReasoningArgs('max', 'gpt-5.1-codex-max'), ['-c', 'model_reasoning_effort=xhigh']);
});

test('codexReasoningArgs clamps max to high on non-codex-max models', () => {
  assert.deepEqual(codexReasoningArgs('max', 'gpt-5-codex'), ['-c', 'model_reasoning_effort=high']);
});

test('codexReasoningArgs passes minimal through (codex accepts it natively)', () => {
  assert.deepEqual(codexReasoningArgs('minimal', 'gpt-5-codex'), ['-c', 'model_reasoning_effort=minimal']);
});

test('codexReasoningArgs returns [] when the level is unset or unknown', () => {
  assert.deepEqual(codexReasoningArgs(null, 'gpt-5-codex'), []);
  assert.deepEqual(codexReasoningArgs('', 'gpt-5-codex'), []);
  assert.deepEqual(codexReasoningArgs('bogus', 'gpt-5-codex'), []);
});

test('claudeReasoningEnv maps a level to the categorical CLAUDE_CODE_EFFORT_LEVEL env', () => {
  assert.deepEqual(claudeReasoningEnv('high'), { CLAUDE_CODE_EFFORT_LEVEL: 'high' });
  assert.deepEqual(claudeReasoningEnv('xhigh'), { CLAUDE_CODE_EFFORT_LEVEL: 'xhigh' });
  assert.deepEqual(claudeReasoningEnv('max'), { CLAUDE_CODE_EFFORT_LEVEL: 'max' });
});

test('claudeReasoningEnv clamps minimal to low (Claude Code has no minimal level)', () => {
  assert.deepEqual(claudeReasoningEnv('minimal'), { CLAUDE_CODE_EFFORT_LEVEL: 'low' });
});

test('claudeReasoningEnv never emits the dead MAX_THINKING_TOKENS budget env', () => {
  // Regression: the former thinking-budget env was a silent no-op on every
  // adaptive-reasoning model (Fable 5, Sonnet 5, Opus 4.7+; 4.6 family without
  // CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING=1) per code.claude.com/docs/en/model-config.
  for (const lvl of ['minimal', 'low', 'medium', 'high', 'xhigh', 'max']) {
    assert.equal(claudeReasoningEnv(lvl).MAX_THINKING_TOKENS, undefined);
  }
});

test('claudeReasoningEnv returns {} when the level is unset or unknown', () => {
  assert.deepEqual(claudeReasoningEnv(null), {});
  assert.deepEqual(claudeReasoningEnv('bogus'), {});
});

test('CodexAdapter.buildArgs splices the effort override into the spawn args', () => {
  const { args } = new CodexAdapter().buildArgs({
    prompt: 'hi', systemPrompt: 's', model: 'gpt-5-codex', maxTurns: 10,
    mcpConfigPath: '/tmp/x', reasoningEffort: 'high',
  });
  assert.ok(args.join(' ').includes('-c model_reasoning_effort=high'), `args: ${args.join(' ')}`);
});

test('CodexAdapter.buildArgs omits the override when no effort is set', () => {
  const { args } = new CodexAdapter().buildArgs({
    prompt: 'hi', systemPrompt: 's', model: 'gpt-5-codex', maxTurns: 10, mcpConfigPath: '/tmp/x',
  });
  assert.equal(args.includes('-c'), false, `args should not contain -c: ${args.join(' ')}`);
});

test('ClaudeAdapter.buildChildEnv merges the categorical effort env for the level', () => {
  const env = new ClaudeAdapter().buildChildEnv('/tmp/x', 'high');
  assert.equal(env.CLAUDE_CODE_EFFORT_LEVEL, 'high');
});

test('ClaudeAdapter.buildChildEnv adds no effort env when no effort is set', () => {
  const env = new ClaudeAdapter().buildChildEnv('/tmp/x');
  assert.equal(env.CLAUDE_CODE_EFFORT_LEVEL, undefined);
  assert.equal(env.MAX_THINKING_TOKENS, undefined);
});
