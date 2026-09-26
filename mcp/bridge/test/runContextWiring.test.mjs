/**
 * A CLI session rebuilds its credentials from what the bridge forwards, so a run marker the
 * bridge does not forward is gone. Prod 2026-09-23: an unattended task ran on the CLI bridge,
 * its session had no task id, and its ask_user waited 150 s on a chat screen nobody had open
 * instead of reaching the connected Telegram. The task id, the unattended marker and the
 * arming must travel credentials -> executeViaCli -> MCP env -> session body. Parse-time
 * forwarding contracts, like toolHoldWiring.test.mjs.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const serverSource = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
const cliSource = readFileSync(resolve(__dirname, '..', '..', 'agent-cli-server.mjs'), 'utf8');

test('server: the run markers are read from the dispatcher credentials', () => {
  assert.match(serverSource, /taskId: \(credentials && credentials\.__taskId__\) \|\| null/);
  assert.match(serverSource, /unattendedRun: isTrue\(credentials && credentials\.__unattendedRun__\)/);
  assert.match(serverSource, /requireToolAuthorization: isTrue\(credentials && credentials\.__requireToolAuthorization__\)/);
});

test('server: executeViaCli takes them and writes them into the MCP env', () => {
  assert.match(serverSource, /async function executeViaCli\(\{[^}]*taskId, unattendedRun, requireToolAuthorization, agentDepth, workflowRunId \}\)/);
  assert.match(serverSource, /TASK_ID: taskId \|\| ''/);
  assert.match(serverSource, /UNATTENDED_RUN: unattendedRun \? 'true' : ''/);
  assert.match(serverSource, /REQUIRE_TOOL_AUTHORIZATION: requireToolAuthorization \? 'true' : ''/);
});

test('cli server: the env reaches the session body, only when set', () => {
  assert.match(cliSource, /const TASK_ID = process\.env\.TASK_ID \|\| ''/);
  assert.match(cliSource, /const UNATTENDED_RUN = process\.env\.UNATTENDED_RUN === 'true'/);
  assert.match(cliSource, /const REQUIRE_TOOL_AUTHORIZATION = process\.env\.REQUIRE_TOOL_AUTHORIZATION === 'true'/);
  assert.match(cliSource, /if \(TASK_ID\) \{\s*body\.taskId = TASK_ID;/);
  assert.match(cliSource, /if \(UNATTENDED_RUN\) \{\s*body\.unattendedRun = true;/);
  assert.match(cliSource, /if \(REQUIRE_TOOL_AUTHORIZATION\) \{\s*body\.requireToolAuthorization = true;/);
});

test('the sub-agent depth travels too, and only when above 0', () => {
  assert.match(serverSource, /agentDepth: depthOf\(credentials && credentials\.__agent_depth__\)/);
  assert.match(serverSource, /AGENT_DEPTH: agentDepth > 0 \? String\(agentDepth\) : ''/);
  assert.match(cliSource, /if \(Number\.isInteger\(AGENT_DEPTH\) && AGENT_DEPTH > 0\) \{\s*body\.agentDepth = AGENT_DEPTH;/);
  const match = serverSource.match(/function depthOf\(value\) \{([\s\S]*?)\n\}/);
  assert.ok(match, 'depthOf must exist');
  const depthOf = new Function('value', match[1]);
  assert.equal(depthOf(1), 1);
  assert.equal(depthOf('2'), 2);
  assert.equal(depthOf(0), 0);
  assert.equal(depthOf(undefined), 0);
  assert.equal(depthOf(-1), 0);
  assert.equal(depthOf('x'), 0);
});

test('the workflow run id travels too, so a workflow node never asks through a channel', () => {
  assert.match(serverSource, /workflowRunId: workflowRunId \|\| null/);
  assert.match(serverSource, /agentDepth, workflowRunId \}\)/);
  assert.match(serverSource, /WORKFLOW_RUN_ID: workflowRunId \|\| ''/);
  assert.match(cliSource, /const WORKFLOW_RUN_ID = process\.env\.WORKFLOW_RUN_ID \|\| ''/);
  assert.match(cliSource, /if \(WORKFLOW_RUN_ID\) \{\s*body\.workflowRunId = WORKFLOW_RUN_ID;/);
});

test('isTrue accepts the two forms the Java side writes, and nothing else', async () => {
  const match = serverSource.match(/function isTrue\(value\) \{\s*return ([^;]+);/);
  assert.ok(match, 'isTrue must exist');
  const isTrue = new Function('value', `return ${match[1]};`);
  assert.equal(isTrue(true), true);
  assert.equal(isTrue('true'), true);
  assert.equal(isTrue(false), false);
  assert.equal(isTrue('false'), false);
  assert.equal(isTrue(undefined), false);
  assert.equal(isTrue(1), false);
});
