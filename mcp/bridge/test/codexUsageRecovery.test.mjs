/**
 * Regression tests for the usage a killed codex run used to lose entirely.
 *
 * `codex exec --json` prints token usage ONLY on `turn.completed` (verified
 * against codex-cli 0.154.0). A turn killed mid-flight - the user pressing Stop,
 * the inactivity watchdog, a budget kill - therefore reported NOTHING, so the
 * run reached billing with zero tokens and was billed nothing, however many model
 * calls it had already paid for. In prod EVERY stopped codex chat billed 0,
 * including one that had made 21 tool calls, while the same chat allowed to
 * finish billed its real tokens.
 *
 * Codex's own rollout log has the numbers: it is appended as the turn runs and
 * carries `token_count` events whose `info.total_token_usage` is cumulative. The
 * bridge points CODEX_HOME at the run's temp dir, so the file belongs to exactly
 * one run. These tests pin that recovery, including the cases where it must stay
 * silent rather than invent a number.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CodexAdapter } from '../adapters/codex-adapter.mjs';

/** Build a CODEX_HOME containing a rollout file made of the given NDJSON lines. */
function makeCodexHome(lines, { day = '2026/09/17', name = 'rollout-test.jsonl' } = {}) {
  const home = mkdtempSync(join(tmpdir(), 'codexhome-'));
  const dir = join(home, 'sessions', ...day.split('/'));
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, name), lines.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8');
  return home;
}

const tokenCountEvent = (usage, { wrapped = true } = {}) => (wrapped
  ? { timestamp: '2026-09-17T11:30:00.000Z', type: 'event_msg', payload: { type: 'token_count', info: { total_token_usage: usage } } }
  : { type: 'token_count', info: { total_token_usage: usage } });

const usageOf = (input, output, extra = {}) => ({
  input_tokens: input,
  cached_input_tokens: 0,
  cache_write_input_tokens: 0,
  output_tokens: output,
  reasoning_output_tokens: 0,
  total_tokens: input + output,
  ...extra,
});

test('recoverUsage returns the tokens a killed turn already spent', () => {
  const home = makeCodexHome([
    { type: 'session_meta' },
    tokenCountEvent(usageOf(15255, 5)),
  ]);
  try {
    const recovered = new CodexAdapter().recoverUsage(home);
    assert.ok(recovered, 'a killed run with a rollout must not report "no usage"');
    assert.equal(recovered.promptTokens, 15255);
    assert.equal(recovered.completionTokens, 5);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('recoverUsage keeps the LAST token_count, because the values are cumulative', () => {
  const home = makeCodexHome([
    tokenCountEvent(usageOf(10000, 2)),
    tokenCountEvent(usageOf(15255, 5)),
    { type: 'response_item', payload: { type: 'message' } },
  ]);
  try {
    const recovered = new CodexAdapter().recoverUsage(home);
    assert.equal(recovered.promptTokens, 15255, 'an earlier event is a prefix of the truth, not the truth');
    assert.equal(recovered.completionTokens, 5);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('recoverUsage splits cached and reasoning tokens out, so a recovered run is billed like a reported one', () => {
  // Field names are NOT invented here: this payload is a line captured verbatim from a
  // real codex-cli 0.154.0 rollout on the bridge host. `cache_write_input_tokens` and
  // `reasoning_output_tokens` are what codex actually writes - which is also why the live
  // turn.completed path had to be corrected, having read `reasoning_tokens` and therefore
  // recorded zero reasoning tokens for every codex run.
  const home = makeCodexHome([
    JSON.parse('{"timestamp":"2026-09-17T08:30:09.466Z","ordinal":13,"type":"event_msg",'
      + '"payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":15255,'
      + '"cached_input_tokens":12160,"cache_write_input_tokens":128,"output_tokens":40,'
      + '"reasoning_output_tokens":32,"total_tokens":15295}}}}'),
  ]);
  try {
    const recovered = new CodexAdapter().recoverUsage(home);
    // codex's input_tokens INCLUDES the cached subset; billing applies the cached
    // discount from the separate field, exactly as the turn.completed path does.
    assert.equal(recovered.promptTokens, 15255);
    assert.equal(recovered.cachedTokens, 12160);
    assert.equal(recovered.reasoningTokens, 32);
    // The rollout's cache_write count is NOT carried: this family has no additive cache
    // slot (input + output equals the reported total, so the write is inside input), and
    // cacheCreationInputTokens is the additive Anthropic one. Mapping one to the other
    // would bill those tokens twice for a codex run billed under an Anthropic pair.
    assert.equal(recovered.cacheCreationInputTokens, undefined);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('recoverUsage reads the un-wrapped stdout shape too, not only the rollout wrapper', () => {
  const home = makeCodexHome([tokenCountEvent(usageOf(777, 3), { wrapped: false })]);
  try {
    const recovered = new CodexAdapter().recoverUsage(home);
    assert.equal(recovered.promptTokens, 777);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('recoverUsage picks the newest rollout when a run left several', async () => {
  const home = makeCodexHome([tokenCountEvent(usageOf(100, 1))], { name: 'rollout-old.jsonl' });
  try {
    // Second file written later: its mtime is what decides, not the name.
    await new Promise((r) => setTimeout(r, 20));
    const dir = join(home, 'sessions', '2026', '09', '17');
    writeFileSync(join(dir, 'rollout-new.jsonl'),
      JSON.stringify(tokenCountEvent(usageOf(900, 9))) + '\n', 'utf8');
    const recovered = new CodexAdapter().recoverUsage(home);
    assert.equal(recovered.promptTokens, 900);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('recoverUsage returns null (never a zeroed object) when there is nothing to recover', () => {
  const adapter = new CodexAdapter();

  // No CODEX_HOME at all.
  assert.equal(adapter.recoverUsage(null), null);
  assert.equal(adapter.recoverUsage(join(tmpdir(), 'codex-does-not-exist-' + Date.now())), null);

  // A home with no sessions tree.
  const empty = mkdtempSync(join(tmpdir(), 'codexhome-'));
  try {
    assert.equal(adapter.recoverUsage(empty), null);
  } finally {
    rmSync(empty, { recursive: true, force: true });
  }

  // A rollout with no usage event.
  const noUsage = makeCodexHome([{ type: 'session_meta' }, { type: 'turn_context' }]);
  try {
    assert.equal(adapter.recoverUsage(noUsage), null);
  } finally {
    rmSync(noUsage, { recursive: true, force: true });
  }

  // A usage event that really is zero: null, so the caller cannot mistake it for
  // a recovered value and the run keeps its honest zero.
  const zero = makeCodexHome([tokenCountEvent(usageOf(0, 0))]);
  try {
    assert.equal(adapter.recoverUsage(zero), null);
  } finally {
    rmSync(zero, { recursive: true, force: true });
  }
});

test('recoverUsage scans only the TAIL of a huge rollout, and still finds the newest total', () => {
  // A long agentic run's rollout reaches tens of MB, and the read is bounded. The events
  // we want are appended after every model call, so the newest is always at the end - but
  // the bound has to be applied to the END of the file, not its start, or the recovery
  // would read the oldest totals and under-bill.
  const filler = { type: 'response_item', payload: { junk: 'x'.repeat(2000) } };
  const lines = [tokenCountEvent(usageOf(10, 1))];
  for (let i = 0; i < 3000; i++) lines.push(filler);       // ~6 MB of transcript
  lines.push(tokenCountEvent(usageOf(45909, 108)));        // the truth, at the very end
  const home = makeCodexHome(lines);
  try {
    const recovered = new CodexAdapter().recoverUsage(home);
    assert.equal(recovered.promptTokens, 45909);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('recoverUsage returns null for an empty rollout rather than reading past it', () => {
  const home = mkdtempSync(join(tmpdir(), 'codexhome-'));
  try {
    const dir = join(home, 'sessions', '2026', '09', '17');
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'rollout-empty.jsonl'), '', 'utf8');
    assert.equal(new CodexAdapter().recoverUsage(home), null);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('recoverUsage survives a corrupt rollout instead of throwing into the kill path', () => {
  const home = mkdtempSync(join(tmpdir(), 'codexhome-'));
  try {
    const dir = join(home, 'sessions', '2026', '09', '17');
    mkdirSync(dir, { recursive: true });
    // A truncated line (what a tail read produces) followed by a good one.
    writeFileSync(join(dir, 'rollout-broken.jsonl'),
      '{"type":"event_msg","payload":{"info":{"total_token_usa\n'
      + JSON.stringify(tokenCountEvent(usageOf(321, 7))) + '\n', 'utf8');
    const recovered = new CodexAdapter().recoverUsage(home);
    assert.equal(recovered.promptTokens, 321, 'an unparseable line must be skipped, not fatal');
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});
