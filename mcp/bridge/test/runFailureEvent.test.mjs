/**
 * When the bridge announces a failed run on the conversation stream, and when it must not.
 *
 * A chat reads a stream `error` as the END of the turn. Two bridge publications broke that:
 *
 *  1. A linked (model-execution-link) run that failed before producing output is re-run by
 *     agent-service on the billed pair's direct API with the SAME streamId. The bridge had
 *     already published `error`, so the whole successful retry streamed to nobody. The
 *     caller now says it publishes the failure itself (`__callerPublishesFailure__`), and the
 *     bridge stays quiet on its two fatal paths (the /execute catch and the spawn `error`).
 *  2. A single CLI message an adapter could not handle published `error` although the CLI
 *     run went on and succeeded. It is now logged only.
 *
 * The decision lives in lib/runFailureEvent.mjs and is exercised here; server.mjs calls
 * app.listen at import time, so its wiring is pinned by source, like the other *Wiring tests.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import {
  ADAPTER_HANDLER_ERROR_TAG,
  CALLER_PUBLISHES_FAILURE_KEY,
  adapterHandlerErrorLine,
  announceRunFailure,
  isFailurePublishedByCaller,
} from '../lib/runFailureEvent.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const serverSource = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
const javaDtoSource = readFileSync(resolve(__dirname, '..', '..', '..', 'backend', 'agent-client', 'src', 'main',
  'java', 'com', 'apimarketplace', 'agent', 'client', 'dto', 'execution', 'AgentExecutionRequestDto.java'), 'utf8');

function recordingPublisher() {
  const errors = [];
  return {
    streamId: 'stream-1',
    errors,
    publishError: async (message) => { errors.push(message); },
  };
}

// ─── The decision ────────────────────────────────────────────────────────

test('a caller that does not publish the failure itself still gets the bridge error event (unchanged behaviour)', async () => {
  const publisher = recordingPublisher();

  const published = await announceRunFailure(publisher, 'spawn claude ENOENT', { callerPublishesFailure: false });

  assert.equal(published, true);
  assert.deepEqual(publisher.errors, ['spawn claude ENOENT']);
});

test('regression: a caller that re-runs the failed turn on the same stream gets NO bridge error event, only a log line', async () => {
  const publisher = recordingPublisher();
  const lines = [];

  const published = await announceRunFailure(publisher, 'spawn claude ENOENT',
    { callerPublishesFailure: true, log: (line) => lines.push(line) });

  assert.equal(published, false);
  assert.deepEqual(publisher.errors, [], 'the chat would end the turn and drop the retried reply');
  assert.equal(lines.length, 1);
  assert.match(lines[0], /stream-1/);
  assert.match(lines[0], /spawn claude ENOENT/);
});

test('a Redis failure while publishing never escapes: the HTTP failure answer must still go out', async () => {
  const publisher = { streamId: 's', publishError: async () => { throw new Error('redis down'); } };

  assert.equal(await announceRunFailure(publisher, 'boom'), true);
});

test('the flag is read from the credentials map, as a boolean or its string form, and absent means "not set"', () => {
  assert.equal(isFailurePublishedByCaller({ [CALLER_PUBLISHES_FAILURE_KEY]: true }), true);
  assert.equal(isFailurePublishedByCaller({ [CALLER_PUBLISHES_FAILURE_KEY]: 'true' }), true);
  assert.equal(isFailurePublishedByCaller({ [CALLER_PUBLISHES_FAILURE_KEY]: false }), false);
  assert.equal(isFailurePublishedByCaller({ __restrictedToolset__: true }), false);
  assert.equal(isFailurePublishedByCaller({}), false);
  assert.equal(isFailurePublishedByCaller(null), false);
  assert.equal(isFailurePublishedByCaller(undefined), false);
});

test('the key is spelled exactly as the Java DTO writes it (a drift is not a compile error, it is the bug again)', () => {
  assert.equal(CALLER_PUBLISHES_FAILURE_KEY, '__callerPublishesFailure__');
  assert.match(javaDtoSource, /CALLER_PUBLISHES_FAILURE_KEY\s*=\s*"__callerPublishesFailure__"/);
});

// ─── The wiring in server.mjs ────────────────────────────────────────────

test('server reads the flag from the request credentials through the shared helper', () => {
  assert.match(serverSource,
    /import \{[^}]*\bannounceRunFailure\b[^}]*\bisFailurePublishedByCaller\b[^}]*\} from '\.\/lib\/runFailureEvent\.mjs'/);
  assert.match(serverSource, /const\s+callerPublishesFailure\s*=\s*isFailurePublishedByCaller\(credentials\)/);
});

test('both fatal paths announce the failure through the helper with the flag, and executeViaCli receives it', () => {
  const announcements = serverSource.match(/announceRunFailure\(publisher,\s*\w+\.message,\s*\{\s*callerPublishesFailure\s*\}\)/g) || [];
  assert.equal(announcements.length, 2,
    'expected the /execute catch and the spawn `error` handler - one of them lost the flag or was bypassed');
  assert.match(serverSource, /async function executeViaCli\(\{[^}]*\bcallerPublishesFailure\b[^}]*\}\)/);
  assert.match(serverSource, /executeViaCli\(\{[\s\S]*?\bcallerPublishesFailure,[\s\S]*?\}\)/);
  const spawnErrorHandler = serverSource.slice(serverSource.indexOf("child.on('error'"),
    serverSource.indexOf("child.on('error'") + 1200);
  assert.match(spawnErrorHandler, /announceRunFailure\(publisher,\s*err\.message,\s*\{\s*callerPublishesFailure\s*\}\)/);
});

test('no raw publishError is left in server.mjs: every failure event goes through the one decision', () => {
  assert.doesNotMatch(serverSource, /publisher\.publishError\(/);
});

test('regression: an adapter that throws on ONE message is logged, never published as a (terminal) stream error', () => {
  const start = serverSource.indexOf('await adapter.handleMessage(msg, ctx);');
  assert.notEqual(start, -1, 'the adapter dispatch moved: re-point this test at it');
  // Code only: the explanatory comment in that block may name the helper it does not call.
  const catchBlock = serverSource.slice(start, serverSource.indexOf('});', start))
    .split('\n').filter((line) => !line.trim().startsWith('//')).join('\n');
  assert.match(catchBlock, /console\.error\(adapterHandlerErrorLine\(\{[\s\S]*?streamId:\s*publisher\.streamId[\s\S]*?error:\s*e,?[\s\S]*?\}\)\)/,
    'the error must still be logged, through the one tagged line, with its stream');
  assert.doesNotMatch(catchBlock, /publishError|announceRunFailure/,
    'the CLI run continues after a bad message: a stream `error` ends the turn in the chat');
});

test('an adapter handler error is countable: one line that STARTS with the stable tag, with provider, message type, stream and stack', () => {
  const error = new Error('cannot read properties of undefined');
  const line = adapterHandlerErrorLine({ provider: 'codex', msgType: 'item.completed', streamId: 'stream-9', error });

  // The tag the line always carried, so a log query written against it keeps matching.
  assert.equal(ADAPTER_HANDLER_ERROR_TAG, '[BRIDGE:handleMessage]');
  assert.ok(line.startsWith(`${ADAPTER_HANDLER_ERROR_TAG} `), line);
  assert.match(line, /provider=codex msg\.type=item\.completed stream=stream-9: /);
  assert.ok(line.includes(error.stack), 'the full stack is kept: hiding it hid a production incident once');
  assert.match(adapterHandlerErrorLine({ msgType: 'x', streamId: 's', error: 'plain' }), /provider=adapter .*: plain$/);
});
