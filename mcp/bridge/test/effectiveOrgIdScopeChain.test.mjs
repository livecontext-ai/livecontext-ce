/**
 * Regression test for the prod bug on 2026-05-20 ~23:00 UTC: user sees
 * "Stream error: effectiveOrgId is not defined" when launching a chat message
 * via the bridge.
 *
 * Root cause: `effectiveOrgId` / `effectiveOrgRole` were declared inside the
 * `app.post('/api/bridge/execute', ...)` request handler (server.mjs:318-319),
 * but the standalone helper `executeViaCli` (declared at module level)
 * referenced them in its env-builder block without receiving them as
 * parameters. At runtime this raised a ReferenceError that bubbled up as a
 * stream error to the user.
 *
 * The fix threads `effectiveOrgId` + `effectiveOrgRole` through 3 sites:
 *   1. Handler call site (server.mjs:413-429) - includes them in the args object
 *   2. executeViaCli signature destructure (server.mjs:534)
 *   3. mcpServerConfig.env body (server.mjs:571-572)
 *
 * If any of those 3 sites breaks again (refactor drops a field, copy-paste
 * adds a 4th env var without parameter, etc.), this test fails. Static-text
 * assertions are sufficient because the bug shape is a scope mismatch
 * detectable at parse time - no need to spin up Express + a subprocess.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SERVER_PATH = resolve(__dirname, '..', 'server.mjs');
const source = readFileSync(SERVER_PATH, 'utf8');

test('effectiveOrgId is destructured in executeViaCli signature (regression: prod ReferenceError 2026-05-20)', () => {
  // Match the function declaration line - must include effectiveOrgId among
  // the destructured params. Anchor on the function name to avoid matching
  // call sites or other functions with similar names.
  const sigPattern = /async function executeViaCli\(\{([^}]+)\}\)/;
  const match = source.match(sigPattern);
  assert.ok(match, 'executeViaCli signature not found - has the function been renamed?');

  const params = match[1];
  assert.ok(
    params.includes('effectiveOrgId'),
    'executeViaCli signature MUST destructure effectiveOrgId. Without it, the reference ' +
    'inside mcpServerConfig.env raises a ReferenceError at runtime - exactly the prod ' +
    'bug fixed on 2026-05-20. If you removed it, also remove the reference at line ~571.'
  );
  assert.ok(
    params.includes('effectiveOrgRole'),
    'executeViaCli signature MUST destructure effectiveOrgRole - symmetric to effectiveOrgId.'
  );
});

test('handler call site passes effectiveOrgId + effectiveOrgRole to executeViaCli', () => {
  // The handler must include both fields in the object literal it passes to
  // executeViaCli. We assert the substring appears between the call site
  // marker and the closing of that specific call.
  const callIdx = source.indexOf('await executeViaCli({');
  assert.notStrictEqual(callIdx, -1, 'executeViaCli call site not found');

  // Scan forward to the matching closing brace+paren of THIS call.
  // We use a simple brace-balancing scan starting from the `{` of the call args.
  const argsStart = source.indexOf('{', callIdx);
  let depth = 0;
  let end = argsStart;
  for (let i = argsStart; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}') {
      depth--;
      if (depth === 0) { end = i; break; }
    }
  }
  const callArgs = source.slice(argsStart, end + 1);

  assert.ok(
    /\beffectiveOrgId\b/.test(callArgs),
    'Handler call to executeViaCli MUST pass effectiveOrgId (declared at server.mjs:318). ' +
    'Without it, the destructure in executeViaCli leaves the binding undefined → silent ' +
    'org-context loss (worse than the ReferenceError, harder to spot in logs).'
  );
  assert.ok(
    /\beffectiveOrgRole\b/.test(callArgs),
    'Handler call to executeViaCli MUST pass effectiveOrgRole.'
  );
});

test('mcpServerConfig.env wires ORGANIZATION_ID + ORGANIZATION_ROLE from effectiveOrgId/Role', () => {
  // The actual consumer of effectiveOrgId - these two assertions pin the
  // contract between the parameter and the env var name the MCP subprocess
  // reads at agent-cli-server.mjs:66.
  assert.match(
    source,
    /ORGANIZATION_ID:\s*effectiveOrgId\b/,
    'mcpServerConfig.env.ORGANIZATION_ID must be sourced from effectiveOrgId. ' +
    'If you renamed the env var, also update mcp/agent-cli-server.mjs which reads it.'
  );
  assert.match(
    source,
    /ORGANIZATION_ROLE:\s*effectiveOrgRole\b/,
    'mcpServerConfig.env.ORGANIZATION_ROLE must be sourced from effectiveOrgRole.'
  );
});

test('effectiveOrgId resolution defaults to empty string when header + body field both missing', () => {
  // The empty-string fallback is load-bearing for the back-compat path: the
  // downstream MCP server (mcp/agent-cli-server.mjs:101-102) gates header
  // emission on a non-empty value, so an empty string correctly degrades to
  // "no org context" rather than a ReferenceError or stamp of "undefined".
  // If a future refactor changes this to `??` or removes the empty-string
  // tail, the agent-cli-server downstream guard might need to be revisited.
  assert.match(
    source,
    /const effectiveOrgId = req\.headers\['x-organization-id'\] \|\| organizationId \|\| ''/,
    'effectiveOrgId must resolve header → body → empty string. The empty-string tail is ' +
    'how no-org callers (daemons, pre-V261 callers) degrade gracefully without throwing.'
  );
});
