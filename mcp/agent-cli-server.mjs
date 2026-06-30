#!/usr/bin/env node

/**
 * MCP stdio server for Claude Code <-> platform tool bridge.
 *
 * Proxies tool calls from Claude Code to the agent-service REST API.
 * Each MCP server instance = one CLI session with a specific model.
 *
 * All tool definitions (including conversation-specific tools) come from the backend.
 * The bridge passes conversation context to the backend, which decides what tools to include.
 *
 * Usage: node agent-cli-server.mjs --model opus|sonnet|haiku
 * Env:   AGENT_CLI_USER  -- tenant ID sent as X-User-Id header
 *        AGENT_CLI_URL   -- base URL of agent-service (default: http://localhost:8090)
 *        CONVERSATION_ID -- conversation ID (enables conversation tools on backend)
 *        CONVERSATION_SERVICE_URL -- conversation-service URL (for tool callback routing)
 *        STREAM_ID       -- stream channel ID
 *        IS_NEW_CONVERSATION -- "true" to include set_conversation_title tool
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { REPO_TOOL_DEF, handleRepoTool, isRepoEnabled } from './repo-tool.mjs';
import { SHELL_TOOL_DEF, handleShellTool, isShellEnabled } from './shell-tool.mjs';
import { buildSuccessContent, withBridgeMeta } from './bridge/lib/toolContent.mjs';
import { Agent, setGlobalDispatcher } from 'undici';

// --- Global fetch timeout for tool calls ---
//
// Node's built-in fetch (undici) defaults headersTimeout AND bodyTimeout to 300s (5 min). A
// platform tool call here is a SYNCHRONOUS request that agent-service holds open until the work
// finishes (a sub-agent run, a long workflow, a catalog poll), so the response can legitimately
// take far longer than 300s. The 300s default aborted those with a generic "fetch failed" at
// exactly 5 min - the very symptom this fix targets - instead of letting the real terminal result
// (or a real error) come back. The actual bound now lives at the agent layer (per-agent
// executionTimeout + the inactivity watchdog), and a genuinely broken connection still rejects
// immediately (ECONNRESET / "other side closed"), so we disable ONLY the response-wait timeouts.
// setGlobalDispatcher writes Symbol.for('undici.globalDispatcher.1'), which Node's built-in fetch()
// reads - so this applies to the bare fetch() calls in this file. connectTimeout keeps undici's
// default, so a dead host still fails fast. startSession's own 5s AbortController is unaffected.
try {
  setGlobalDispatcher(new Agent({ headersTimeout: 0, bodyTimeout: 0 }));
} catch (e) {
  process.stderr.write(`[agent-cli] could not set global fetch dispatcher (tool calls keep undici's 300s default): ${e.message}\n`);
}

// --- CLI Args ---

const MODEL_ALIASES = {
  opus: 'claude-opus-4-6',
  sonnet: 'claude-sonnet-4-6',
  haiku: 'claude-haiku-4-5-20251001',
};

function parseArgs() {
  const args = process.argv.slice(2);
  let modelAlias = 'sonnet'; // default
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--model' && args[i + 1]) {
      modelAlias = args[i + 1];
    }
  }
  if (!MODEL_ALIASES[modelAlias]) {
    console.error(`Unknown model alias: ${modelAlias}. Use: ${Object.keys(MODEL_ALIASES).join(', ')}`);
    process.exit(1);
  }
  return { modelAlias, modelId: MODEL_ALIASES[modelAlias] };
}

const { modelAlias, modelId } = parseArgs();
const BASE_URL = process.env.AGENT_CLI_URL || 'http://localhost:8090';
const USER_ID = process.env.AGENT_CLI_USER || 'default-tenant';
const CONVERSATION_ID = process.env.CONVERSATION_ID || '';
const CONVERSATION_SERVICE_URL = process.env.CONVERSATION_SERVICE_URL || '';
const STREAM_ID = process.env.STREAM_ID || '';
const IS_NEW_CONVERSATION = process.env.IS_NEW_CONVERSATION === 'true';
const AGENT_ENTITY_ID = process.env.AGENT_ENTITY_ID || '';
// Canonical enabled MODULE keys (JSON array) forwarded by the bridge from the agent's
// toolsConfig (AgentModuleResolver vocabulary). Included in the CliSessionStartRequest body
// so CliAgentService.resolveModules scopes the core tool set to the agent's mode - without
// this the session defaulted to ALL modules and the CLI advertised every core tool schema,
// billing it on every turn. Empty/malformed ⇒ null ⇒ backend treats as unrestricted.
let ENABLED_MODULES = null;
try {
  if (process.env.ENABLED_MODULES) {
    const parsed = JSON.parse(process.env.ENABLED_MODULES);
    if (Array.isArray(parsed)) ENABLED_MODULES = parsed;
  }
} catch {
  // Malformed env → leave null (unrestricted), never crash the MCP subprocess over it.
}
// Phase 3 of MIGRATION_ORG_ID_NOT_NULL.md - workspace scope propagated from
// the bridge subprocess env (set by mcp/bridge/server.mjs from the inbound
// X-Organization-ID header or the bridge DTO body). Empty string when the
// caller is in personal scope OR when bridge wasn't passed the value (legacy).
// Once Phase 6 NOT NULL constraint ships, an empty ORGANIZATION_ID at this
// layer surfaces as a DB-level violation on the downstream INSERT, not silent
// NULL stamping - that's the design.
const ORGANIZATION_ID = process.env.ORGANIZATION_ID || '';
const ORGANIZATION_ROLE = process.env.ORGANIZATION_ROLE || '';
// Stable per-execution UUID minted by the dispatcher (conversation-service or
// orchestrator AgentNode). Forwarded into the CliSessionStartRequest body so
// CliAgentService injects __executionId__ on the MCP credentials map - the
// claim log row written by task_claim then keys on this UUID, aligning with
// agent_executions.id at end-of-run. Empty string when the dispatcher hasn't
// been updated yet (legacy).
const EXECUTION_ID = process.env.EXECUTION_ID || '';
// JSON array of user-authorized sensitive tool-action rule keys ("tool:action").
// Forwarded into the CLI session so the bridge guard skips re-prompting on resume.
const APPROVED_TOOL_ACTIONS = process.env.APPROVED_TOOL_ACTIONS || '[]';

// --- Session resilience knobs (env-overridable; tiny values in tests) ---
// A transient backend blip at session start (host reboot before k3s networking is
// ready, a deploy rollout gap on a 1-replica service, a brief overlay-network hiccup)
// must NOT cost the whole chat its platform tools. We bound the fetch, retry the
// session a few times, and re-establish it lazily on the first ListTools/CallTool.
const FETCH_TIMEOUT_MS = Number(process.env.AGENT_CLI_FETCH_TIMEOUT_MS) || 5000;
const SESSION_START_ATTEMPTS = Number(process.env.AGENT_CLI_SESSION_ATTEMPTS) || 3;
const SESSION_START_BACKOFF_MS = Number(process.env.AGENT_CLI_SESSION_BACKOFF_MS) || 400;
const delay = (ms) => new Promise((r) => setTimeout(r, ms));

// --- Gateway Auth ---

import { gatewaySignedHeaders } from './bridge/lib/gatewayAuth.mjs';

const GATEWAY_SECRET_KEY = process.env.GATEWAY_SECRET_KEY || '';

// Sign with the SAME identifiers we send as headers (X-User-Id / X-Organization-ID),
// using the shared HMAC twin of the Java GatewayAuthenticationFilter. The previous
// inline scheme hashed `KEY:providerId:timestamp:mcp-world-gateway` with plain SHA-256
// and a truncated digest - incompatible with the current filter (which expects an
// HMAC over `providerId|userId|orgId|timestamp`). It produced no errors only because
// `/api/agent/cli/*` are public paths AND this subprocess gets no GATEWAY_SECRET_KEY
// (so it falls through to X-Provider-ID only); the helper preserves that fallback.
function createGatewayHeaders(providerId) {
  return gatewaySignedHeaders({
    secretKey: GATEWAY_SECRET_KEY,
    providerId,
    userId: USER_ID,
    organizationId: ORGANIZATION_ID,
  });
}

// --- HTTP Helper ---

async function apiPost(path, body, timeoutMs = 0) {
  const url = `${BASE_URL}${path}`;
  const gatewayHeaders = createGatewayHeaders(USER_ID);
  const headers = {
    'Content-Type': 'application/json',
    'X-User-Id': USER_ID,
    ...gatewayHeaders,
  };
  // Phase 3 - propagate org context to orchestrator/agent-service so downstream
  // INSERTs stamp organization_id from this scope. Omit headers when empty so
  // legacy single-tenant deploys behave identically.
  if (ORGANIZATION_ID) headers['X-Organization-ID'] = ORGANIZATION_ID;
  if (ORGANIZATION_ROLE) headers['X-Organization-Role'] = ORGANIZATION_ROLE;
  // Optional bound (timeoutMs > 0): a hanging fetch (e.g. right after a host reboot,
  // before k3s networking is ready, the ClusterIP routes but never answers) would
  // otherwise block startSession indefinitely - and because the MCP handshake used
  // to wait on startSession, the CLI saw "0 MCP connected". AbortController makes it
  // fail fast so the retry loop can take over. Only startSession passes a timeout;
  // tool calls keep the original unbounded behaviour (a workflow/catalog call can be
  // legitimately long, so we must NOT abort it).
  let res;
  if (timeoutMs > 0) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body), signal: ctrl.signal });
    } finally {
      clearTimeout(timer);
    }
  } else {
    res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
  }
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  return res.json();
}

// --- Session Lifecycle ---

let sessionId = null;
let tools = []; // { name, description, inputSchema }

async function startSession() {
  // Build session request - backend decides all tools (including conversation tools)
  const body = { model: modelId };
  if (CONVERSATION_ID && CONVERSATION_SERVICE_URL) {
    body.conversationId = CONVERSATION_ID;
    body.conversationServiceUrl = CONVERSATION_SERVICE_URL;
    body.streamId = STREAM_ID;
    body.isNewConversation = IS_NEW_CONVERSATION;
  }
  // Scope the core tool set to the agent's toolsConfig modules when the bridge supplied them
  // (parity with the direct loop). Omitted ⇒ CliAgentService.resolveModules(null) ⇒ all
  // modules (legacy unrestricted behaviour, e.g. agents with no toolsConfig).
  if (ENABLED_MODULES) {
    body.enabledModules = ENABLED_MODULES;
  }
  // Pass agentId so CliAgentService injects __agentId__ into session credentials,
  // enabling SubAgentExecutionHandler to build the caller chain for budget settlement.
  if (AGENT_ENTITY_ID) {
    body.agentId = AGENT_ENTITY_ID;
  }
  // Pass executionId so CliAgentService injects __executionId__ into credentials.
  // AgentTaskService.claimTask reads it to write the claim log row keyed by the
  // same UUID the observability writer will persist as agent_executions.id.
  if (EXECUTION_ID) {
    body.executionId = EXECUTION_ID;
  }
  // Forward user-authorized tool-action rules so CliAgentService injects
  // __approvedToolActions__ into the session credentials → the authorization gate
  // skips re-prompting on the resume turn (parity with the remote loop path).
  try {
    const approved = JSON.parse(APPROVED_TOOL_ACTIONS);
    if (Array.isArray(approved) && approved.length > 0) {
      body.approvedToolActions = approved;
    }
  } catch {
    // Malformed env → treat as no approvals (gate will prompt, which is safe).
  }

  const data = await apiPost('/api/agent/cli/session', body, FETCH_TIMEOUT_MS);

  sessionId = data.sessionId;
  tools = (data.availableTools || []).map((t) => ({
    name: t.name,
    description: t.description || '',
    inputSchema: t.inputSchema || { type: 'object', properties: {} },
  }));

  log(`Session started: ${sessionId} (${tools.length} tools, model=${modelAlias})`);
}

/**
 * Establish a session, retrying a bounded number of times with linear backoff.
 * Returns true once a session exists, false if every attempt failed (the caller
 * still serves - repo stays usable and a later call can retry). Never throws.
 */
async function startSessionWithRetry(reason) {
  let lastErr;
  for (let attempt = 1; attempt <= SESSION_START_ATTEMPTS; attempt++) {
    try {
      await startSession();
      return true;
    } catch (e) {
      lastErr = e;
      log(`startSession attempt ${attempt}/${SESSION_START_ATTEMPTS} failed (${reason}): ${e.message}`);
      if (attempt < SESSION_START_ATTEMPTS) await delay(SESSION_START_BACKOFF_MS * attempt);
    }
  }
  log(`startSession gave up after ${SESSION_START_ATTEMPTS} attempts (${reason}): ${lastErr ? lastErr.message : 'unknown'}`);
  return false;
}

// De-duplicate concurrent session establishment (ListTools + CallTool can race).
let sessionPromise = null;

/**
 * Ensure a session exists, reusing an in-flight attempt. If the attempt finishes
 * WITHOUT a session (backend still down), the memo is cleared so the NEXT
 * ListTools/CallTool retries fresh - a chat that opened during a transient outage
 * self-heals on a later turn instead of staying tool-less for its whole life.
 */
function ensureSession() {
  if (sessionId) return Promise.resolve(true);
  if (!sessionPromise) {
    sessionPromise = startSessionWithRetry('lazy').then((ok) => {
      if (!sessionId) sessionPromise = null;
      return ok;
    });
  }
  return sessionPromise;
}

async function endSession() {
  if (!sessionId) return;
  try {
    await apiPost('/api/agent/cli/session/end', { sessionId });
    log(`Session ended: ${sessionId}`);
  } catch (e) {
    log(`Failed to end session: ${e.message}`);
  }
  sessionId = null;
}

// --- Logging (stderr only, stdout is MCP) ---

function log(msg) {
  process.stderr.write(`[agent-cli-${modelAlias}] ${msg}\n`);
}

// Tool-result content builders (buildSuccessContent / withBridgeMeta) and the vision
// media key live in ./bridge/lib/toolContent.mjs - kept dependency-free so they can be
// unit-tested with `node --test` without loading the MCP SDK.

// --- MCP Server Setup ---

const server = new Server(
  { name: `agent-cli-${modelAlias}`, version: '1.0.0' },
  { capabilities: { tools: {} } }
);

async function listToolsResult() {
  // If the startup session failed (a transient blip - host reboot before k3s
  // networking is ready, a deploy rollout gap), `tools` would be empty. The CLI
  // calls ListTools at init, so (re)establish the session HERE first: a backend
  // that recovered a moment later self-heals without the whole chat losing its
  // platform tools. Bounded by the retry budget so we never hang the handshake.
  await ensureSession();
  // Local tools executed in-process on the bridge host (where the checkout lives):
  // `repo` (source/git) and `shell` (host commands). Advertised alongside the backend
  // tools when AGENT_REPO_PATH is configured. They are normal directly-listed MCP tools
  // - they work with Claude's ToolSearch disabled, and are surfaced even if the backend
  // session failed (they need no backend session).
  const localTools = [];
  if (isRepoEnabled() && !tools.some((t) => t.name === REPO_TOOL_DEF.name)) localTools.push(REPO_TOOL_DEF);
  if (isShellEnabled() && !tools.some((t) => t.name === SHELL_TOOL_DEF.name)) localTools.push(SHELL_TOOL_DEF);
  return localTools.length ? { tools: [...tools, ...localTools] } : { tools };
}
server.setRequestHandler(ListToolsRequestSchema, listToolsResult);

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: params } = request.params;

  // `repo` and `shell` are LOCAL tools executed here on the bridge host (where the
  // checkout is) - never proxied to the backend, and they need no backend session.
  if (name === REPO_TOOL_DEF.name) {
    return withBridgeMeta(await handleRepoTool(params || {}));
  }
  if (name === SHELL_TOOL_DEF.name) {
    return handleShellTool(params || {});
  }

  // Recover a session that never came up at startup (transient backend blip): a
  // tool call self-heals instead of failing for the rest of the chat.
  await ensureSession();
  if (!sessionId) {
    return {
      content: [{ type: 'text', text: 'Error: No active session (backend unreachable). Retry in a moment.' }],
      isError: true,
    };
  }

  // All tools (core + conversation) go through the same path:
  // agent-service routes conversation tools via __toolCallbackUrl__ to conversation-service
  async function callTool() {
    const result = await apiPost('/api/agent/cli/tool', {
      sessionId,
      tool: name,
      params: params || {},
    });
    return result;
  }

  try {
    let result;
    try {
      result = await callTool();
    } catch (e) {
      // Auto-recover expired sessions: recreate and retry once
      if (e.message && e.message.includes('Session not found')) {
        log(`Session expired, recreating...`);
        await startSession();
        result = await callTool();
      } else {
        throw e;
      }
    }

    if (result.success) {
      // Builds the text block + any vision image blocks, and re-emits the light metadata
      // (heavy media bytes stripped) as the __BRIDGE_META__ sentinel.
      return { content: buildSuccessContent(result) };
    } else {
      // Check if error response indicates expired session
      if (result.error && result.error.includes('Session not found')) {
        log(`Session expired (error response), recreating...`);
        await startSession();
        const retryResult = await callTool();
        if (retryResult.success) {
          return { content: buildSuccessContent(retryResult) };
        }
        return {
          content: [{ type: 'text', text: retryResult.error || 'Tool execution failed after session recovery' }],
          isError: true,
        };
      }
      return {
        content: [{ type: 'text', text: result.error || 'Tool execution failed' }],
        isError: true,
      };
    }
  } catch (e) {
    return {
      content: [{ type: 'text', text: `Error calling tool "${name}": ${e.message}` }],
      isError: true,
    };
  }
});

// --- Graceful Shutdown ---

async function shutdown() {
  await endSession();
  process.exit(0);
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
process.on('SIGHUP', shutdown);

// --- Main ---

async function main() {
  // Connect the MCP transport FIRST, then establish the backend session. A slow or
  // unreachable backend (host just rebooted, deploy in flight) must never delay the
  // MCP handshake - that delay is exactly what the CLI reported as "0 MCP connected".
  // ListTools/CallTool await ensureSession(), so tools are populated before first use;
  // kicking it off here warms it so the session is usually ready by the time ListTools
  // arrives.
  const transport = new StdioServerTransport();
  await server.connect(transport);
  log('MCP server connected via stdio');
  ensureSession().catch((e) => log(`initial ensureSession error: ${e.message}`));
}

// Exported so unit tests can drive the resilience logic with a mocked fetch without
// the module auto-starting the stdio server. Production runs the script directly,
// where AGENT_CLI_NO_AUTOSTART is unset → main() runs as before.
export const __test = {
  startSession,
  startSessionWithRetry,
  ensureSession,
  listToolsResult,
  buildSuccessContent,
  withBridgeMeta,
  apiPost,
  getSessionId: () => sessionId,
  getTools: () => tools,
  reset: () => { sessionId = null; tools = []; sessionPromise = null; },
};

if (process.env.AGENT_CLI_NO_AUTOSTART !== '1') {
  main().catch((e) => {
    log(`Fatal error: ${e.message}`);
    process.exit(1);
  });
}
