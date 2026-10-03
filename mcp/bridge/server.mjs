#!/usr/bin/env node

/**
 * Agent Bridge Server - Routes conversation LLM execution to CLI backends.
 *
 * Supports multiple CLI adapters (Claude Code, Codex) via the adapter pattern.
 * Spawns the appropriate CLI as a child process, parses NDJSON output,
 * publishes events to Redis, and returns the final response.
 *
 * Env vars:
 *   PORT            - HTTP port (default: 8093)
 *   REDIS_URL       - Redis connection URL (default: redis://localhost:6379)
 *   AGENT_CLI_URL   - agent-service base URL (default: http://localhost:8090)
 *   MCP_SERVER_PATH - Path to agent-cli-server.mjs (default: auto-detected)
 *   CLAUDE_BIN      - Path to claude binary (default: "claude")
 *   CLAUDE_CLI_JS   - Path to claude cli.js for direct node invocation (optional)
 *   CODEX_BIN       - Path to codex binary (default: "codex")
 *   CONVERSATION_SERVICE_URL - conversation-service URL (default: http://localhost:8087)
 */

import express from 'express';
import { spawn } from 'child_process';
import { randomBytes } from 'crypto';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';
import { createInterface } from 'readline';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync } from 'fs';
import { tmpdir } from 'os';
import Redis from 'ioredis';
import { RedisPublisher } from './redis-publisher.mjs';
import { maskSecrets } from '../repo-tool.mjs'; // reused: masks the Redis password in startup logs
import { resolveAgentCwd } from './lib/spawnCwd.mjs';
import { buildAttachmentPrompt } from './lib/attachmentPrompt.mjs';
import { stripNulFromArgs } from './lib/spawnSafety.mjs';
import { ClaudeAdapter } from './adapters/claude-adapter.mjs';
import { CodexAdapter } from './adapters/codex-adapter.mjs';
import { GeminiAdapter } from './adapters/gemini-adapter.mjs';
import { MistralAdapter } from './adapters/mistral-adapter.mjs';
import { AgentStopReason } from './lib/agentStopReason.js';
import { applyResultMapping } from './lib/stopReasonMapper.js';
import { sharedPricingCache, signedSnapshotFetcher } from './lib/pricing.js';
import { AgentBudgetGuard, TenantBudgetGuard, chainBudgetGuards, sumCacheCounters } from './lib/budgetGuards.js';
import { internalSignedHeaders, resolveRestrictedToolset, UNRESTRICTED_PROVIDER_ID, withGatewaySignatureV2 } from './lib/gatewayAuth.mjs';
import { installBridgeSecurity, createSecretSource, enforcementMode, resolveRequestIdentity } from './lib/bridgeSecurity.mjs';
import { childUserFromEnv, wrapSpawnForChildUser, grantChildAccess, childUserEnv } from './lib/childUser.mjs';
import { buildBaseChildEnv } from './lib/childEnv.mjs';
import { resolveInactivityMs } from './lib/inactivityResolver.mjs';
import { createInactivityWatchdog } from './lib/inactivityWatchdog.mjs';
import { applyRecoveredUsage as applyRecoveredUsageToRun } from './lib/usageRecovery.mjs';
import { maxToolHoldSecondsFor } from './lib/toolHold.mjs';
import { detectAll, detectOne, invalidateCache, CLI_IDS } from './cli-detector.mjs';
import { collectCliHealthMetrics, createCliSnapshotCache } from './lib/cliHealth.mjs';
import { extractToolResultAndMetadata } from './lib/toolContent.mjs';
import { adapterHandlerErrorLine, announceRunFailure, isFailurePublishedByCaller } from './lib/runFailureEvent.mjs';

// Per-process secret that authenticates the trusted `__BRIDGE_META__` channel. Minted ONCE
// at startup and handed to the agent-cli MCP subprocess via env, so only content this bridge
// and its own MCP server emit carries the marker `\n__BRIDGE_META__:<nonce>:`. Untrusted tool
// output (shell/read/fetch) cannot guess it and can no longer forge frontend metadata cards.
if (!process.env.BRIDGE_META_NONCE) {
  process.env.BRIDGE_META_NONCE = randomBytes(16).toString('hex');
}

const __dirname = dirname(fileURLToPath(import.meta.url));

// ─── Configuration ────────────────────────────────────────────────────────

// All env vars that point at external services must be set explicitly in production.
// In dev/test we accept localhost defaults; under NODE_ENV=production a missing var
// is a hard boot failure - we'd rather refuse to start than silently fall back to a
// localhost URL that won't resolve and turn every downstream call into a timeout.
// Each entry: { name, kind } where kind ∈ {'url','path'} drives the validation strategy.
const REQUIRED_PROD_ENV = [
  { name: 'AUTH_BALANCE_URL',         kind: 'url'  },
  { name: 'REDIS_URL',                kind: 'url'  },
  { name: 'AGENT_CLI_URL',            kind: 'url'  },
  { name: 'CONVERSATION_SERVICE_URL', kind: 'url'  },
  { name: 'MCP_SERVER_PATH',          kind: 'path' },
];
if (process.env.NODE_ENV === 'production') {
  const errors = [];
  for (const { name, kind } of REQUIRED_PROD_ENV) {
    const value = process.env[name];
    if (!value) { errors.push(`${name}: missing`); continue; }
    if (kind === 'url') {
      try { new URL(value); }
      catch { errors.push(`${name}: not a valid URL ("${value}")`); }
    } else if (kind === 'path') {
      // fs.existsSync is sync but only runs once at boot - acceptable.
      if (!existsSync(value)) errors.push(`${name}: file not found ("${value}")`);
    }
  }
  if (errors.length > 0) {
    console.error(`[BRIDGE] FATAL: invalid production environment:\n  - ${errors.join('\n  - ')}\nRefusing to start.`);
    process.exit(1);
  }
}

const PORT = parseInt(process.env.PORT || '8093', 10);
const REDIS_URL = process.env.REDIS_URL || 'redis://localhost:6379';
const AGENT_CLI_URL = process.env.AGENT_CLI_URL || 'http://localhost:8090';
const MCP_SERVER_PATH = process.env.MCP_SERVER_PATH || resolve(__dirname, '..', 'agent-cli-server.mjs');
const CONVERSATION_SERVICE_URL = process.env.CONVERSATION_SERVICE_URL || 'http://localhost:8087';
const AUTH_BALANCE_URL = process.env.AUTH_BALANCE_URL || 'http://localhost:8083/api/credits/balance';
// Shared HMAC secret (gateway.filter.secret-key, provisioned as GATEWAY_SECRET_KEY).
// The bridge calls AUTH_BALANCE_URL DIRECTLY (bypassing the gateway), so it must sign
// the request itself - see lib/gatewayAuth.mjs. Empty in dev/test (backend filter is
// disabled there); in prod the balance refresh silently 401s without it (the guard
// just keeps its previous value), so warn loudly at boot rather than fail silently.
// LC-053: read from the systemd credential ($CREDENTIALS_DIRECTORY/gateway-secret) when the unit
// provides one, so the secret is never in this process's environment; else GATEWAY_SECRET_KEY.
const SECRETS = createSecretSource();
const GATEWAY_SECRET_KEY = SECRETS.platformSecret();
// Provider id the auth-service internal credit client signs with - kept in sync with
// CreditConsumptionClient.INTERNAL_PROVIDER_ID so both sides hit the same code path.
const INTERNAL_PROVIDER_ID = 'internal-credit-client';
// The pricing snapshot lives under auth-service's /api/internal/auth/, which it can HMAC-gate
// (AUTH_INTERNAL_HMAC_REQUIRED_PATH), so the shared cache signs its reads like the balance refresh.
sharedPricingCache.fetcher = signedSnapshotFetcher(GATEWAY_SECRET_KEY);
if (process.env.NODE_ENV === 'production' && !GATEWAY_SECRET_KEY) {
  // Non-fatal: a missing secret degrades the mid-run tenant budget guard back to a
  // one-shot snapshot (every balance refresh 401s and the guard keeps its seed value).
  // Don't refuse to start over it, but make it impossible to miss in logs.
  console.warn('[BRIDGE] WARN GATEWAY_SECRET_KEY missing - tenant balance refresh will 401 and the budget guard cannot react to mid-run balance changes.');
}
// All numeric tunables are env-overridable so prod ops can adjust without
// a code change. Defaults are tuned for the current bridge workload.
// MAX_TIMEOUT_MS is the hard cap on a single agent run (req/res socket timeouts +
// child spawn wall-clock). It must cover the per-agent executionTimeout /
// inactivityTimeout contract maximum (7200s) plus dispatch overhead: under the
// previous 65-min default a valid 2h budget could never elapse on the bridge -
// the run was always cut at 65 min with TIMEOUT. The Java bridge clients' read
// timeouts (130 min) sit above this cap so the bridge's typed timeout response
// wins over a client-side socket abort.
const MAX_TIMEOUT_MS = parseInt(process.env.BRIDGE_MAX_TIMEOUT_MS || String(125 * 60 * 1000), 10);
const SIGKILL_GRACE_MS = parseInt(process.env.BRIDGE_SIGKILL_GRACE_MS || '3000', 10);
const CANCEL_POLL_MS = parseInt(process.env.BRIDGE_CANCEL_POLL_MS || '2000', 10);
const MAX_HISTORY_MESSAGES = parseInt(process.env.BRIDGE_MAX_HISTORY_MESSAGES || '20', 10);
const BODY_LIMIT = process.env.BRIDGE_BODY_LIMIT || '100mb';

// ─── Bridge authentication + exposure (LC-001) ────────────────────────────
//
// `POST /api/bridge/execute` spawns a CLI agent child process on this host. It used to accept
// any caller that could reach the port (and the gateway routed `/api/bridge/**` from the public
// edge). The gateway route is gone; these two settings close the rest.
//
// BRIDGE_REQUIRE_GATEWAY_AUTH: "true" = always enforce (no secret = 503, fail closed; prod sets
// this), "false" = never (local dev), unset = enforce iff a shared secret is available (CE). With
// enforcement off no run is ever unrestricted. See lib/bridgeSecurity.mjs.
// BRIDGE_REQUIRE_SIGNATURE_V2=true additionally refuses requests without the body-bound
// X-Bridge-Signature (turn it on once every caller is on the signing build).
const AUTH_MODE = enforcementMode();
// BRIDGE_BIND_ADDRESS: comma-separated list of addresses to listen on, default loopback only.
// Every launcher in the repo sets it explicitly (prod systemd: loopback + the LAN address the
// k3s pods dial; containers: 0.0.0.0 inside the container network).
const BIND_ADDRESSES = (process.env.BRIDGE_BIND_ADDRESS || '127.0.0.1')
  .split(',')
  .map(a => a.trim())
  .filter(Boolean);

// ─── CLI Adapters ─────────────────────────────────────────────────────────

const ADAPTERS = {
  'claude-code': new ClaudeAdapter(),
  'codex': new CodexAdapter(),
  'gemini-cli': new GeminiAdapter(),
  'mistral-vibe': new MistralAdapter(),
};

/**
 * Select the appropriate CLI adapter based on provider name.
 * 'claude-code' → ClaudeAdapter, 'codex' → CodexAdapter
 * Legacy: 'anthropic' or unrecognized → ClaudeAdapter (backward compat)
 */
function getAdapter(provider) {
  if (provider && ADAPTERS[provider.toLowerCase()]) {
    return ADAPTERS[provider.toLowerCase()];
  }
  // Legacy fallback: default to Claude
  return ADAPTERS['claude-code'];
}

// ─── Redis ────────────────────────────────────────────────────────────────

let redis;       // For commands (SET, EXISTS, GET)
let redisPub;    // Dedicated connection for PUBLISH (avoids connection state issues)
try {
  redis = new Redis(REDIS_URL, { maxRetriesPerRequest: 3 });
  redisPub = new Redis(REDIS_URL, { maxRetriesPerRequest: 3 });
  await redis.ping();
  await redisPub.ping();
  console.log(`[BRIDGE] Redis connected: ${maskSecrets(REDIS_URL)}`);
} catch (e) {
  console.error(`[BRIDGE] Redis connection failed: ${e.message}`);
  process.exit(1);
}

// ─── Tool Name Prefixing ──────────────────────────────────────────────────

const FALLBACK_TOOL_NAMES = [
  'catalog', 'table', 'interface', 'agent', 'skill',
  'workflow', 'application', 'web_search',
  // 'request_credential' is the legacy routing alias of 'credential'
  // (pre-rename sessions) - keep both prefixable.
  'set_conversation_title', 'get_tool_result', 'credential', 'request_credential',
];

function prefixToolNames(systemPrompt, serverName, toolNames) {
  if (!systemPrompt || !toolNames?.length) return systemPrompt;
  const prefix = `mcp__${serverName}__`;
  let result = systemPrompt;
  for (const tool of toolNames) {
    result = result.replaceAll(new RegExp(`(?<![a-zA-Z_])${tool}\\(`, 'g'), `${prefix}${tool}(`);
  }
  return result;
}

/** Strip MCP prefix from tool name: mcp__agent-cli__table → table */
function stripMcpPrefix(toolName) {
  if (!toolName) return toolName;
  // Standard MCP prefix: mcp__servername__toolname
  const match = toolName.match(/^mcp__[^_]+(?:__[^_]+)*__(.+)$/);
  if (match) return match[1];
  // Mistral Vibe format: agent-cli_toolname or agent_cli_toolname
  const vibeMatch = toolName.match(/^agent[-_]cli[-_](.+)$/);
  if (vibeMatch) return vibeMatch[1];
  return toolName;
}

// `extractToolResultAndMetadata` lives in ./lib/toolContent.mjs next to the producer
// (`buildSuccessContent`) and the marker definition, so both sides of the sentinel share one
// implementation and can be unit-tested without loading this Express server. It is injected
// into each adapter's ctx below.

// ─── Express App ──────────────────────────────────────────────────────────

const app = express();

// Authentication front (lib/bridgeSecurity.mjs), mounted FIRST: the pre-parse HMAC gate runs
// before the JSON body is read, then the body is parsed with its raw bytes kept, then the
// body-bound signature, the replay cache and the signed-identity check run. It is mounted on the
// `/api/bridge` PREFIX, so a future route under it is protected by default. `/health`,
// `/metrics` and `/cli-status` stay open: they are read-only, and `/cli-status` + `/health`
// are polled unsigned by agent-service (BridgeAvailabilityFilter, LlmProviderStatusController).
installBridgeSecurity(app, { secrets: SECRETS, mode: AUTH_MODE, bodyLimit: BODY_LIMIT });

// LC-053: optional dedicated uid for the spawned agent (BRIDGE_CHILD_UID / BRIDGE_CHILD_GID).
const CHILD_USER = childUserFromEnv();

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', service: 'agent-bridge', port: PORT });
});

/**
 * Per-CLI availability probe. Returns the install/version status for each
 * supported CLI provider so the setup wizard can show "Claude Code: ✓ 0.4.1,
 * Gemini CLI: ✗ not found" instead of a single boolean.
 *
 * Query params:
 *   ?cli=claudeCode|codex|geminiCli|mistralVibe - return only that CLI
 *   ?force=1 - bypass the 30s in-memory cache
 *
 * Response shape (no `cli` filter):
 *   {
 *     bridgeReachable: true,
 *     platform: "linux"|"darwin"|"win32",
 *     clis: {
 *       claudeCode:  { id, label, installed, binary, version, error },
 *       codex:       { ... },
 *       geminiCli:   { ... },
 *       mistralVibe: { ... }
 *     }
 *   }
 *
 * With `?cli=`: returns the single entry merged with bridgeReachable/platform.
 */
app.get('/cli-status', async (req, res) => {
  const force = req.query.force === '1' || req.query.force === 'true';
  const cliFilter = typeof req.query.cli === 'string' ? req.query.cli : null;
  // `force=1` must invalidate the shared cache too, otherwise an unfiltered
  // call right after a filtered force-refresh would still serve stale data.
  if (force) invalidateCache();
  try {
    if (cliFilter) {
      if (!CLI_IDS.includes(cliFilter)) {
        return res.status(400).json({
          bridgeReachable: true,
          error: `unknown cli '${cliFilter}'. Expected one of: ${CLI_IDS.join(', ')}`,
        });
      }
      const entry = await detectOne(cliFilter);
      return res.json({
        bridgeReachable: true,
        platform: process.platform,
        cli: entry,
      });
    }
    const clis = await detectAll({ force });
    return res.json({
      bridgeReachable: true,
      platform: process.platform,
      clis,
    });
  } catch (err) {
    console.error('[BRIDGE] /cli-status failed:', err);
    return res.status(500).json({
      bridgeReachable: true,
      error: err?.message || String(err),
    });
  }
});

// Where refresh-token.sh / codex-refresh-token.sh / cli-update.sh drop their
// state files. server.mjs runs from <bridge root>/bridge, and those scripts are
// installed at <bridge root>, so "../state" is the same directory on both sides.
const CLI_STATE_DIR = process.env.CLI_HEALTH_STATE_DIR || resolve(__dirname, '..', 'state');

// The CLI probe is served from a snapshot refreshed in the BACKGROUND, never
// awaited by the scrape. Awaiting it would spawn four `--version` processes on
// essentially every 30s scrape (cli-detector's own cache is 30s, the same as
// the scrape interval), and a slow spawn on a loaded host would push /metrics
// past its scrape timeout - which reports a perfectly healthy bridge as
// `up == 0` and takes bridge_up, the series ServiceDown watches, with it.
const cliSnapshot = createCliSnapshotCache({ detect: () => detectAll({ force: true }) });

// Minimal Prometheus metrics endpoint. Fully synchronous on purpose.
app.get('/metrics', (_req, res) => {
  const uptime = process.uptime();
  const mem = process.memoryUsage();
  const clis = cliSnapshot.get();

  res.set('Content-Type', 'text/plain; version=0.0.4');
  res.send([
    '# HELP bridge_up Whether the bridge is running',
    '# TYPE bridge_up gauge',
    'bridge_up 1',
    '# HELP bridge_uptime_seconds Bridge uptime in seconds',
    '# TYPE bridge_uptime_seconds gauge',
    `bridge_uptime_seconds ${uptime.toFixed(0)}`,
    '# HELP bridge_memory_rss_bytes Resident set size in bytes',
    '# TYPE bridge_memory_rss_bytes gauge',
    `bridge_memory_rss_bytes ${mem.rss}`,
    '# HELP bridge_memory_heap_used_bytes Heap used in bytes',
    '# TYPE bridge_memory_heap_used_bytes gauge',
    `bridge_memory_heap_used_bytes ${mem.heapUsed}`,
    ...collectCliHealthMetrics({
      clis,
      stateDir: CLI_STATE_DIR,
      probeAgeSeconds: cliSnapshot.ageSeconds(),
    }),
    '',
  ].join('\n'));
});

app.post('/api/bridge/execute', async (req, res) => {
  const startTime = Date.now();
  const dto = req.body;
  const {
    prompt,
    systemPrompt,
    model,
    provider,
    // Resolved reasoning-effort level for CLI providers (minimal|low|medium|high|xhigh).
    // Backend resolved precedence (conversation override > agent > model default); we
    // just map it to each CLI's knob. Absent → CLI default.
    reasoningEffort,
    streamChannelId,
    conversationId,
    tenantId,
    maxIterations,
    // Per-agent TOTAL wall-clock cap (seconds), bounded by MAX_TIMEOUT_MS. Absent => MAX_TIMEOUT_MS.
    // Before this the bridge ignored the field entirely and every run could last up to MAX_TIMEOUT_MS.
    executionTimeout,
    // Per-agent inactivity watchdog window (seconds). Absent => 5 min default (every run gets one);
    // <= 0 => disabled.
    inactivityTimeout,
    conversationHistory,
    attachments,
    tools: dtoTools,
    // Budget guards (P4 #14): backend-supplied per-run limits + optional rate overrides.
    // All optional - when absent the guards stay disabled and behaviour is unchanged.
    maxCreditBudget,    // number - max credits this agent run may spend (per-agent)
    creditsConsumedSoFar, // number - credits already consumed before this run (for pre-check)
    tenantBalance,      // number - tenant credit balance at the start of the run
    pricingRates,       // [{provider, model, inputRate, outputRate, fixedCost}] override
    // Sub-agent parent-forwarding context (mirrors ConversationRedisStreamingCallback)
    parentConversationId,
    subAgentName,
    subAgentAvatarUrl,
    subAgentId,
    // P0-E - workflow run ID propagated from the agent-service DTO so the
    // bridge's cancel poll honors workflow:cancel:{workflowRunId} (set by the
    // orchestrator on workflow run cancellation).
    workflowRunId,
    // Agent identity - injected into CLI session credentials for budget cascade chain
    agentEntityId,
    // Stable per-execution UUID minted upstream by the dispatcher. Forwarded
    // into the agent-cli subprocess env so the CliSessionStartRequest carries
    // it → CliAgentService.startSession injects __executionId__ on the MCP
    // credentials → AgentTaskService.claimTask keys claim log rows by it.
    // Closes the task↔execution race (2026-05-22).
    executionId,
    // Phase 3 of MIGRATION_ORG_ID_NOT_NULL.md - workspace scope propagated to
    // the MCP subprocess via env. Gateway forwards X-Organization-ID on every
    // authenticated request; bridge accepts both the body field (explicit) and
    // the header (transport-default) - header wins when set, body is fallback
    // for async/daemon callers without a bound gateway-validated request.
    organizationId,
    organizationRole,
    // Per-tool-call context map (carries __approvedToolActions__ on a resume turn).
    credentials,
    // Canonical enabled MODULE keys (AgentModuleResolver vocabulary) resolved from the
    // agent's toolsConfig. Forwarded to the MCP subprocess (ENABLED_MODULES env) so
    // CliAgentService scopes the core tool set - without this the bridge ignored
    // toolsConfig.mode and advertised every core tool, billing its schema every turn.
    // Absent/null ⇒ CliAgentService.resolveModules(null) = the NO-CONFIG module set:
    // everything EXCEPT the credit-spending opt-ins (image_generation, generation).
    enabledModules,
  } = dto;
  // The identity this run acts as. For a verified request it is the SIGNED identity only (the
  // security front already refused a body tenantId/organizationId that differs from it, and the
  // organization role is taken only from a signed header); legacy header-or-body resolution is
  // kept solely for enforcement-off launchers.
  const identity = resolveRequestIdentity(req.gatewayAuth, { tenantId, organizationId, organizationRole }, req.headers);
  const effectiveTenantId = identity.tenantId;
  const effectiveOrgId = identity.organizationId;
  const effectiveOrgRole = identity.organizationRole;

  // Restricted mode locks the CLI to ONLY the platform MCP tools (no native Bash/Read/Write/
  // Web), an empty cwd (no AGENTS.md / CLAUDE.md / project files) and no repo/shell MCP tools.
  //
  // LC-001 / LC-022: restricted is now the DEFAULT. The only way out is the SIGNED channel: a
  // request carrying a valid BODY-BOUND signature made with UNRESTRICTED_PROVIDER_ID (the Java
  // bridge clients do so only for a platform-admin caller on a deployment that allows host tools,
  // `conversation.bridge.host-tools-enabled`). The body
  // flag `credentials.__restrictedToolset__` (model-execution-link "API mode") can still only
  // TIGHTEN; it can no longer be omitted to obtain a host shell.
  const restrictedToolset = resolveRestrictedToolset(req.gatewayAuth, credentials);

  // Set by a caller that re-runs a failed turn on this SAME stream (the execution-link
  // fallback): it publishes the stream's terminal event itself, so a bridge `error` would
  // end the turn in the chat before the retried reply arrives. See lib/runFailureEvent.mjs.
  const callerPublishesFailure = isFailurePublishedByCaller(credentials);

  // Select CLI adapter based on provider
  const adapter = getAdapter(provider);
  console.log(`[BRIDGE] Selected adapter: ${adapter.constructor.name} (provider=${provider || 'default'})`);

  // Derive tool names from DTO (backend provides them); fallback for backward compat
  const toolNames = (dtoTools || []).map(t => t.name).filter(Boolean);
  const effectiveToolNames = toolNames.length > 0 ? toolNames : FALLBACK_TOOL_NAMES;

  const streamId = streamChannelId || `bridge-${Date.now()}`;
  // Fleet activity (mirrors AgentActivityPublisher.java): taskId scopes the task-board
  // shimmer to (agent, task). The Java side injects it on the MCP credentials as __taskId__.
  const fleetTaskId = (credentials && credentials.__taskId__) || null;
  // Pass the command redis client via the constructor instead of a private
  // back-channel mutation - pub/sub clients can't run regular commands once
  // subscribed, so we need a second connection for SET/EXISTS.
  const publisher = new RedisPublisher(redisPub, streamId, conversationId, redis, {
    parentConversationId,
    subAgentName,
    subAgentAvatarUrl,
    subAgentId,
    workflowRunId,
  }, {
    // When agentEntityId is set, the publisher also emits tool_call_started/completed
    // to ws:agent:activity:{agentEntityId} - the per-tool progress the bridge path
    // otherwise never published (Java emits only execution_started/completed for it).
    agentEntityId,
    executionId,
    taskId: fleetTaskId,
  });

  // Build full prompt with conversation history for context
  const fullPrompt = buildPromptWithHistory(prompt, conversationHistory);

  console.log(`[BRIDGE] Execute: conv=${conversationId}, stream=${streamId}, tenant=${effectiveTenantId}, provider=${provider || 'default'}, historyMsgs=${(conversationHistory || []).length}, attachments=${(attachments || []).length}`);

  req.setTimeout(MAX_TIMEOUT_MS);
  res.setTimeout(MAX_TIMEOUT_MS);

  try {
    await publisher.setConversationIndex();

    // Transform system prompt: replace bare tool names with MCP-prefixed names
    const MCP_SERVER_NAME = 'agent-cli';
    const prefixedSystemPrompt = prefixToolNames(systemPrompt, MCP_SERVER_NAME, effectiveToolNames);

    // Build budget guard chain (no-op when neither budget nor balance provided).
    // Pricing cache is refreshed lazily; request-time `pricingRates` lets the
    // backend pin specific rows when running in offline/staging environments.
    if (Array.isArray(pricingRates) && pricingRates.length > 0) {
      sharedPricingCache.primeFromRates(pricingRates);
    } else {
      await sharedPricingCache.refreshIfStale();
    }
    // Wire a refresh callback so the bridge tenant guard mirrors the Java side:
    // re-fetch the live tenant balance every N iterations from auth-service. This
    // closes the prior gap where a long Claude Code run could keep spending after
    // the tenant balance was externally exhausted (top-up clawback, refund, etc.).
    // Gateway's AuthenticationFilter always emits X-User-ID as a numeric Long string
    // (see backend/gateway/.../AuthenticationFilter.java:375). The auth-service
    // CreditController binds it as @RequestHeader Long → a non-numeric tenantId
    // would silently 400 and turn refreshTenantBalance into a permanent no-op.
    // Fail loud here instead so any future tenant-id format change is caught early.
    const tenantIdStr = String(effectiveTenantId || '');
    const tenantIdIsNumeric = /^\d+$/.test(tenantIdStr);
    if (effectiveTenantId && !tenantIdIsNumeric) {
      process.stderr.write(`[BRIDGE:tenantGuard] WARN non-numeric tenantId="${tenantIdStr}" - auth-service expects Long, refresh disabled\n`);
    }
    const refreshTenantBalance = async () => {
      try {
        // AUTH_BALANCE_URL hits auth-service directly (no gateway hop), so the
        // GatewayAuthenticationFilter requires us to sign the request ourselves with
        // the shared HMAC - exactly like the Java CreditConsumptionClient. The
        // signature binds userId + orgId; internalSignedHeaders builds the signed
        // headers AND the X-User-ID / X-Organization-ID from the SAME inputs, so the
        // values we send can never diverge from the values we sign.
        const headers = withGatewaySignatureV2(internalSignedHeaders({
          secretKey: GATEWAY_SECRET_KEY,
          providerId: INTERNAL_PROVIDER_ID,
          userId: tenantIdStr,
          organizationId: effectiveOrgId,
          extra: { 'Accept': 'application/json' },
        }), { secretKey: GATEWAY_SECRET_KEY, method: 'GET', url: AUTH_BALANCE_URL });
        const res = await fetch(AUTH_BALANCE_URL, { headers });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const body = await res.json();
        return Number(body.balance);
      } catch (e) {
        process.stderr.write(`[BRIDGE:tenantGuard] balance refresh failed: ${e.message}\n`);
        return null; // null → guard keeps the previous value
      }
    };
    const refreshEnabled = Boolean(effectiveTenantId && tenantIdIsNumeric);
    const initialBalance = Number(tenantBalance) || 0;
    if (!refreshEnabled && initialBalance > 0) {
      // Caller seeded a balance but we can't refresh it - guard becomes a one-shot
      // snapshot frozen at request time. Surface this so operators know the run
      // won't react to mid-execution top-ups or clawbacks.
      process.stderr.write(
        `[BRIDGE:tenantGuard] WARN tenantBalance=${initialBalance} but refresh disabled - guard is a one-shot snapshot for this run\n`
      );
    }
    const tenantGuard = new TenantBudgetGuard({
      initialBalance,
      pricing: sharedPricingCache,
      refreshBalance: refreshEnabled ? refreshTenantBalance : null,
    });
    const agentGuard = new AgentBudgetGuard({
      budget: Number(maxCreditBudget) || 0,
      consumedSoFar: Number(creditsConsumedSoFar) || 0,
      pricing: sharedPricingCache,
    });
    const budgetGuard = chainBudgetGuards(tenantGuard, agentGuard);

    const result = await executeViaCli({
      prompt: fullPrompt,
      systemPrompt: prefixedSystemPrompt,
      model,
      maxTurns: maxIterations || 40,
      // Bridge now honors the per-agent executionTimeout as the spawn wall-clock (was always MAX_TIMEOUT_MS).
      spawnTimeoutMs: Math.min(MAX_TIMEOUT_MS,
        (typeof executionTimeout === 'number' && executionTimeout > 0) ? executionTimeout * 1000 : MAX_TIMEOUT_MS),
      // Inactivity watchdog window (ms): per-agent credential override > DTO field > 5-min default;
      // <= 0 disables. See lib/inactivityResolver.mjs (mirrors AgentLoopService.resolveInactivityWindowMs).
      inactivityMs: resolveInactivityMs(inactivityTimeout, credentials),
      tenantId: effectiveTenantId || 'default-tenant',
      publisher,
      attachments: attachments || [],
      isNewConversation: !conversationHistory || conversationHistory.length === 0
        || (conversationHistory.length === 1 && (conversationHistory[0].role || '').toUpperCase() === 'USER'),
      adapter,
      budgetGuard,
      provider,
      reasoningEffort,
      agentEntityId,
      effectiveOrgId,
      effectiveOrgRole,
      executionId,
      enabledModules,
      restrictedToolset,
      callerPublishesFailure,
      approvedToolActions: (credentials && credentials.__approvedToolActions__) || [],
      // What kind of run this is, said by the dispatcher that knows. Lost here, every tool call
      // of the session looked like a person watching the chat: an unattended task's ask_user
      // waited on a screen nobody had open instead of going to the connected chat channel.
      taskId: (credentials && credentials.__taskId__) || null,
      unattendedRun: isTrue(credentials && credentials.__unattendedRun__),
      requireToolAuthorization: isTrue(credentials && credentials.__requireToolAuthorization__),
      // A sub-agent is not the person's chat: nobody reads its conversation, and its parent is
      // waiting on it. Reset to 0 at the session, its ask_user parked on a card nobody could see.
      agentDepth: depthOf(credentials && credentials.__agent_depth__),
      // A workflow agent node: its conversation cannot be re-entered by a reply that arrives
      // later, so it must not put a question to a channel either (same rule as the direct route).
      workflowRunId: workflowRunId || null,
    });

    const durationMs = Date.now() - startTime;

    // Build conversationHistory for observability recording
    const builtHistory = buildConversationHistory(prompt, result.toolResults, result.content);

    // Build enhanced totalUsage with cache + reasoning token breakdown (the same cache
    // totals the budget guard priced during the run).
    const {
      cacheCreationTokens: totalCacheCreation,
      cacheReadTokens: totalCacheRead,
      cachedTokens: totalCached,
    } = sumCacheCounters(result.perCallUsages);
    let totalReasoning = 0;
    for (const call of result.perCallUsages || []) {
      totalReasoning += call.reasoningTokens || 0;
    }
    const enhancedUsage = {
      promptTokens: result.usage?.promptTokens || 0,
      completionTokens: result.usage?.completionTokens || 0,
      totalTokens: (result.usage?.promptTokens || 0) + (result.usage?.completionTokens || 0),
      cacheCreationInputTokens: totalCacheCreation,
      cacheReadInputTokens: totalCacheRead,
      cachedTokens: totalCached,
      reasoningTokens: totalReasoning,
    };

    // Build per-iteration usage
    const usagePerIteration = (result.perCallUsages || []).length > 0
      ? result.perCallUsages.map(u => ({
          promptTokens: u.promptTokens,
          completionTokens: u.completionTokens,
          totalTokens: (u.promptTokens + u.completionTokens + u.cacheCreationInputTokens + u.cacheReadInputTokens),
          cacheCreationInputTokens: u.cacheCreationInputTokens,
          cacheReadInputTokens: u.cacheReadInputTokens,
          cachedTokens: u.cachedTokens,
          reasoningTokens: u.reasoningTokens,
        }))
      : null;

    // Build iteration durations from timestamps
    const ts = result.iterationTimestamps || [];
    const iterationDurations = ts.length > 1
      ? ts.slice(1).map((t, i) => t - ts[i])
      : null;

    // Finish reasons per iteration
    const finishReasonsPerIteration = (result.finishReasons || []).length > 0
      ? result.finishReasons
      : null;

    const response = {
      success: result.success,
      finalResponse: result.content,
      content: result.content,
      toolResults: result.toolResults,
      iterations: result.numTurns,
      totalUsage: enhancedUsage,
      error: result.error || null,
      durationMs,
      // Echo the caller's provider (e.g. 'claude-code') when present - falling
      // back to the adapter's API-family name only as a default. Since V128
      // collapsed the bridge-vs-cloud model id suffix, the bridge provider is
      // the ONLY discriminator that keeps observability (agent_executions.provider)
      // and billing in sync with what the user actually picked.
      provider: provider || adapter.getProviderName(),
      model: model || 'unknown',
      conversationHistory: builtHistory,
      stopReason: result.stopReason,
      metrics: {
        reasoningDurationMs: durationMs,
        streamCompletedEarly: false,
        // Chat and workflow observability read the guard scope from metrics.budgetScope
        // (the key AgentLoopService writes); the top-level budgetScope below serves the
        // sub-agent path. Present only when a guard stopped the run.
        ...(result.budgetScope ? { budgetScope: result.budgetScope } : {}),
      },
      usagePerIteration,
      iterationDurations,
      finishReasonsPerIteration,
      thinkingSections: result.thinkingSections,
      orderedEntries: result.orderedEntries,
      budgetScope: result.budgetScope || null,
    };

    console.log(`[BRIDGE] Done: conv=${conversationId}, success=${result.success}, turns=${result.numTurns}, duration=${durationMs}ms`);
    res.json(response);
  } catch (e) {
    const durationMs = Date.now() - startTime;
    console.error(`[BRIDGE] Error: conv=${conversationId}, error=${e.message}, duration=${durationMs}ms`);
    await announceRunFailure(publisher, e.message, { callerPublishesFailure });

    res.status(500).json({
      success: false,
      content: '',
      error: e.message,
      durationMs,
      provider: provider || adapter.getProviderName(),
      model: model || 'unknown',
      iterations: 0,
      stopReason: AgentStopReason.ERROR,
      thinkingSections: [],
      orderedEntries: [],
      toolResults: [],
    });
  }
});

// ─── CLI Execution ────────────────────────────────────────────────────────

/**
 * Spawn CLI with adapter-specific args and MCP config.
 * Parses NDJSON lines from stdout, publishes events to Redis.
 */
/** The sub-agent depth as the Java side writes it (a number or its string form); 0 when absent. */
function depthOf(value) {
  const depth = Number(value);
  return Number.isInteger(depth) && depth > 0 ? depth : 0;
}

/** A credential flag as the Java side writes it: a boolean, or its string form. */
function isTrue(value) {
  return value === true || value === 'true';
}

async function executeViaCli({ prompt, systemPrompt, model, maxTurns, spawnTimeoutMs, inactivityMs, tenantId, publisher, attachments, isNewConversation, adapter, budgetGuard, provider, reasoningEffort, agentEntityId, effectiveOrgId, effectiveOrgRole, executionId, enabledModules, restrictedToolset, callerPublishesFailure, approvedToolActions, taskId, unattendedRun, requireToolAuthorization, agentDepth, workflowRunId }) {
  // State tracking
  let fullContent = '';
  let numTurns = 0;
  let success = false;
  let stopReason = null;
  let error = null;
  let usage = { promptTokens: 0, completionTokens: 0 };
  let cliModel = null;
  const perCallUsages = [];
  const iterationTimestamps = [Date.now()];
  const finishReasons = [];
  const toolResults = [];
  const thinkingSections = [];
  const orderedEntries = [];
  const pendingToolCalls = new Map();

  // Create temp directory for MCP config and attachments
  const tmpDir = mkdtempSync(resolve(tmpdir(), 'bridge-'));

  // Build MCP server config (shared across adapters)
  const mcpServerConfig = {
    serverName: 'agent-cli',
    command: 'node',
    args: [MCP_SERVER_PATH],
    env: {
      AGENT_CLI_USER: tenantId,
      AGENT_CLI_URL: AGENT_CLI_URL,
      // Trusted-metadata channel nonce (see BRIDGE_META_NONCE mint at startup): the MCP
      // subprocess stamps it into every `__BRIDGE_META__` block so the bridge can tell
      // genuine tool metadata from a forged sentinel in untrusted tool output.
      BRIDGE_META_NONCE: process.env.BRIDGE_META_NONCE,
      CONVERSATION_ID: publisher.conversationId || '',
      CONVERSATION_SERVICE_URL: CONVERSATION_SERVICE_URL,
      STREAM_ID: publisher.streamId || '',
      IS_NEW_CONVERSATION: isNewConversation ? 'true' : 'false',
      AGENT_ENTITY_ID: agentEntityId || '',
      // Canonical enabled MODULE keys (JSON array) → agent-cli-server.mjs forwards them in
      // the CliSessionStartRequest body so CliAgentService scopes the core tool set to the
      // agent's toolsConfig.mode (parity with the direct loop). An EMPTY ARRAY is a real
      // answer, not an absence: it travels as '[]' and scopes the session to NO modules
      // (a tool-less judge, e.g. a classify or guardrail run). Only a non-array (absent)
      // value means "nobody scoped this", which the backend reads as its no-config module
      // set, never as every module.
      ENABLED_MODULES: Array.isArray(enabledModules) ? JSON.stringify(enabledModules) : '',
      // Path to the source checkout on the bridge host. When set (prod, via the
      // lc-bridge systemd drop-in), agent-cli-server.mjs advertises + executes the
      // local `repo` + `shell` MCP tools against it. Empty (dev/CE) → not advertised.
      // SECURITY: a model-execution-link (restricted "API mode") run MUST NOT get the
      // repo/shell tools - they run arbitrary commands/file access IN the source checkout
      // and would defeat the isolation. The native-tool lockdown (--tools "" /
      // --disallowedTools) + empty cwd do NOT touch MCP tools, so a routed agent reached
      // the repo via mcp__agent-cli__shell (verified: it ran `git status` in prod
      // 2026-06-26). Force the MCP subprocess to see NO checkout when restricted, so
      // isRepoEnabled() is false and neither `repo` nor `shell` is advertised or callable.
      AGENT_REPO_PATH: restrictedToolset ? '' : (process.env.AGENT_REPO_PATH || ''),
      // LC-022: the in-process `shell` MCP tool has its OWN opt-in (AGENT_SHELL_ENABLED=true on
      // the bridge host) instead of riding on AGENT_REPO_PATH. Forced off for restricted runs,
      // for the same reason as AGENT_REPO_PATH above.
      AGENT_SHELL_ENABLED: restrictedToolset ? '' : (process.env.AGENT_SHELL_ENABLED || ''),
      // Phase 3 - propagate the workspace scope to the MCP subprocess so its
      // own apiPost calls back into orchestrator carry X-Organization-ID. Closes
      // Pattern I (audit 2026-05-19) where InterfaceService.createOrUpdate*
      // received orgId=null and stamped NULL rows on the WebSearch interface.
      ORGANIZATION_ID: effectiveOrgId,
      ORGANIZATION_ROLE: effectiveOrgRole,
      // Stable per-execution UUID - minted by conversation-service / orchestrator
      // upstream, carried through the bridge request DTO. agent-cli-server.mjs
      // forwards into the CliSessionStartRequest body so CliAgentService injects
      // __executionId__ on the MCP credentials. AgentTaskService.claimTask then
      // writes claim log rows keyed by this UUID - closing the link to
      // agent_executions.id at end-of-run. Empty string when upstream hasn't
      // been updated yet (legacy dispatchers).
      EXECUTION_ID: executionId || '',
      // User-authorized sensitive tool-action rules (always ∪ once for this turn),
      // forwarded so CliAgentService injects __approvedToolActions__ → the gate skips
      // re-prompting on the resume turn (parity with the remote AgentLoopService path).
      APPROVED_TOOL_ACTIONS: JSON.stringify(approvedToolActions || []),
      // The run's kind, forwarded into the session so its tool calls are judged like the same
      // run on the direct route: a task (or a schedule, a webhook) has nobody in front of it,
      // so a question goes to the connected chat channel, and an armed agent asks permission.
      TASK_ID: taskId || '',
      UNATTENDED_RUN: unattendedRun ? 'true' : '',
      REQUIRE_TOOL_AUTHORIZATION: requireToolAuthorization ? 'true' : '',
      AGENT_DEPTH: agentDepth > 0 ? String(agentDepth) : '',
      WORKFLOW_RUN_ID: workflowRunId || '',
      // The watchdog window THIS run was armed with. agent-cli-server forwards it into the
      // session so the server-side approval gate can size a park that fits inside it and
      // still leaves time to run the tool once the user answers. Without it the gate falls
      // back to its own default and the watchdog kills the run mid-execution.
      AGENT_INACTIVITY_SECONDS: String(Math.max(0, Math.round((inactivityMs || 0) / 1000))),
      // How long a tool call may be HELD on this CLI (an approval card, an ask_user
      // question), derived from the per-call timeout the adapter configured for it. The
      // backend gate otherwise bounds a hold by the floor sized for the shortest CLI, which
      // ends a question card while the person is still reading it. Empty when the adapter
      // does not know its CLI's wait: the gate keeps its floor.
      AGENT_CLI_MAX_TOOL_HOLD_SECONDS: maxToolHoldSecondsFor(adapter),
    },
  };

  // Write CLI-specific MCP config. restrictedToolset is passed so adapters that strip native
  // built-in tools via the MCP settings file (gemini's excludeTools) can do so; adapters that
  // don't need it ignore the extra arg.
  const mcpConfigPath = adapter.writeMcpConfig(tmpDir, mcpServerConfig, restrictedToolset);

  // Process attachments: inline genuine text into the prompt, write everything
  // else (images, PDFs, any binary) to disk for the agent to Read. The agent has
  // the native Read tool unconditionally now, so on-disk files just add their
  // paths to the prompt. Binary must NEVER be inlined: decoding it as UTF-8
  // yields NUL bytes, and the prompt becomes the claude `-p` process argument,
  // which spawn rejects ("args[1] must be a string without null bytes") - the
  // prod crash on PDF attachments. buildAttachmentPrompt enforces that split;
  // stripNulFromArgs below is the unconditional backstop at the spawn boundary.
  let finalPrompt = prompt;
  let attachmentPathToName = new Map();
  // Set only when at least one attachment was written to disk: a restricted claude run then gets
  // Read scoped to exactly this directory (see ClaudeAdapter.buildArgs).
  let attachmentDirOnDisk = null;
  if (attachments && attachments.length > 0) {
    const attachDir = resolve(tmpDir, 'attachments');
    mkdirSync(attachDir, { recursive: true });
    const built = buildAttachmentPrompt(prompt, attachments, {
      attachDir,
      writeFile: writeFileSync,
      log: (m) => console.log(m),
    });
    finalPrompt = built.finalPrompt;
    attachmentPathToName = built.attachmentPathToName;
    if (attachmentPathToName.size > 0) attachmentDirOnDisk = attachDir;
  }

  // Build CLI args via adapter. Adapters always return `{ args, stdinPayload }`
  // - `stdinPayload` is a string for EVERY adapter now: the prompt travels via
  // stdin, never argv. codex/gemini/mistral read it via a `-` sentinel; claude
  // via `-p` with no positional. Putting a large prompt in argv overflows the OS
  // arg-size limit (Linux MAX_ARG_STRLEN ~128 KB) and spawn dies with E2BIG. Only
  // a truly null payload (an adapter that genuinely needs no stdin) leaves stdin
  // ignored. The unified shape was introduced to fix a race where the previous
  // `adapter._stdinPrompt` back-channel mutated a singleton and corrupted
  // concurrent runs.
  const { args: rawSpawnArgs, stdinPayload } = adapter.buildArgs({
    prompt: finalPrompt,
    systemPrompt,
    model,
    maxTurns,
    mcpConfigPath,
    reasoningEffort,
    restrictedToolset,
    // MCP server name (mcp__<serverName>__*) so adapters can build a tools allowlist
    // restricted to ONLY the platform tools when restrictedToolset is set.
    mcpServerName: mcpServerConfig.serverName,
    attachmentDir: attachmentDirOnDisk,
  });

  // Unconditional backstop: Node's spawn aborts the whole run if ANY argument
  // string contains a NUL byte. Binary attachments are already kept out of the
  // prompt (buildAttachmentPrompt), but the prompt also carries the user's chat
  // text and system prompt - strip NUL here so no input from any source can ever
  // crash the spawn ("args[1] must be a string without null bytes").
  const spawnArgs = stripNulFromArgs(rawSpawnArgs, (m) => console.warn(m));

  // Resolve spawn command via adapter
  const { cmd: spawnCmd, useShell, cmdLabel } = adapter.getCommand();

  // Observability: surface the resolved reasoning effort and the flag-only portion
  // of the spawn args (the prompt is omitted - it's in stdin for codex, or large in
  // claude's `-p`). Lets ops confirm which effort a CLI actually ran at.
  const flagArgs = spawnArgs.filter((a) => typeof a === 'string' && a.length < 80);
  console.log(`[BRIDGE] reasoningEffort=${reasoningEffort || '(none)'} | flagArgs=${JSON.stringify(flagArgs)}`);

  // Build child environment via adapter
  // The fallback strips platform secrets too: an adapter without buildChildEnv must not be the
  // one path that hands GATEWAY_SECRET_KEY to a spawned agent (LC-053).
  const childEnv = adapter.buildChildEnv ? adapter.buildChildEnv(tmpDir, reasoningEffort, restrictedToolset) : buildBaseChildEnv();

  console.log(`[BRIDGE] Spawning: ${cmdLabel} ${useShell ? '(shell)' : '(direct)'} --max-turns ${maxTurns}${restrictedToolset ? ' [restricted]' : ''}`);

  const needsStdin = stdinPayload != null;

  // Run the agent's native file/shell tools FROM the source checkout when the operator
  // configured one (AGENT_REPO_PATH, see resolveAgentCwd) - like a real Claude Code launched
  // inside the repo.
  //
  // RESTRICTED: run from a fresh EMPTY dir instead, so the CLI can never load the repo's
  // AGENTS.md / CLAUDE.md / .codex or read project files even on a CLI whose native read tool
  // we can't fully disable (codex/gemini read-only sandbox).
  //
  // NO CHECKOUT CONFIGURED (dev / CE): this used to inherit the bridge's own working directory,
  // i.e. its install dir (server.mjs, lib/gatewayAuth.mjs, and one level up in the systemd layout
  // the platform .env). Those runs get a throwaway empty dir too (LC-053).
  //
  // Both throwaway dirs are tracked so the cleanup handlers can remove them; the configured
  // checkout is the real source tree and must NEVER be deleted.
  const restrictedCwd = restrictedToolset
    ? mkdtempSync(resolve(tmpdir(), 'bridge-restricted-'))
    : null;
  const configuredCheckout = restrictedCwd ? null : resolveAgentCwd(process.env.AGENT_REPO_PATH || '');
  const throwawayCwd = (restrictedCwd || configuredCheckout)
    ? null
    : mkdtempSync(resolve(tmpdir(), 'bridge-cwd-'));
  const spawnCwd = restrictedCwd || configuredCheckout || throwawayCwd;

  // LC-053: with BRIDGE_CHILD_UID set, the agent runs under its own uid through setpriv, and the
  // per-run dirs it needs are handed to its group. A failure here throws (the run fails) rather
  // than silently running the agent as the bridge uid.
  let plan = { cmd: spawnCmd, args: spawnArgs, useShell };
  let effectiveChildEnv = childEnv;
  if (CHILD_USER) {
    grantChildAccess([tmpDir, restrictedCwd, throwawayCwd], CHILD_USER);
    plan = wrapSpawnForChildUser(plan, CHILD_USER);
    effectiveChildEnv = childUserEnv(childEnv, CHILD_USER);
  }

  return new Promise((resolvePromise) => {
    const child = spawn(plan.cmd, plan.args, {
      env: effectiveChildEnv,
      cwd: spawnCwd,
      stdio: [needsStdin ? 'pipe' : 'ignore', 'pipe', 'pipe'],
      timeout: spawnTimeoutMs || MAX_TIMEOUT_MS,
      shell: plan.useShell,
    });

    // Write prompt to stdin if adapter requires it. `stdinPayload` is closure-
    // local - it never touches the singleton adapter, so concurrent runs are
    // safe.
    if (needsStdin && child.stdin) {
      child.stdin.write(stdinPayload);
      child.stdin.end();
    }

    // Sentinel flags consumed by stopReasonMapper to distinguish kill causes from
    // CLI-reported subtypes. Once any of these flips to true, the mapper will pick
    // the corresponding canonical AgentStopReason regardless of what the CLI emits.
    let stoppedByUser = false;
    let cancelledBySystem = false;
    let budgetExhausted = false;
    let budgetScope = null;
    let timedOut = false;
    let inactivityTimedOut = false;
    let killSignalSent = false;
    // Note: loop detection is performed inside the CLI itself (Claude's max_turns,
    // Codex's iteration cap). The bridge has no per-iteration tool inspection, so
    // there is no `loopDetected` sentinel here. The corresponding branch in
    // stopReasonMapper.js is reachable only via the (test-only) ctx fixtures.

    /** Single point of child termination - guards against double-SIGTERM races. */
    const killChildOnce = (label) => {
      if (killSignalSent) return;
      killSignalSent = true;
      try {
        console.log(`[BRIDGE] killing child (pid=${child.pid}) - reason=${label}`);
        child.kill('SIGTERM');
        setTimeout(() => {
          // `child.killed` only means "a signal was sent" (already true from the SIGTERM
          // above), NOT "the process exited" - so it can never gate the escalation. Check
          // whether the process is still running instead, so a child that ignores SIGTERM
          // is actually force-killed.
          try {
            if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
          } catch { /* dead */ }
        }, SIGKILL_GRACE_MS);
      } catch { /* already dead */ }
    };

    // Inactivity watchdog: kill the child if it emits no stdout (NDJSON) for the configured window.
    // A working CLI resets the timer on every line; a stalled one (hung provider/downstream call)
    // trips it -> INACTIVITY_TIMEOUT. Armed from spawn so a CLI that never emits anything is caught
    // too. inactivityMs <= 0 disables it. Timer mechanics live in lib/inactivityWatchdog.mjs
    // (behaviorally tested); only the kill + sentinel side effects stay here.
    const idleWatchdog = createInactivityWatchdog(inactivityMs, () => {
      inactivityTimedOut = true;
      stopReason = AgentStopReason.INACTIVITY_TIMEOUT;
      error = `CLI produced no output for ${inactivityMs}ms (inactivity)`;
      console.error(`[BRIDGE] inactivity watchdog tripped (${inactivityMs}ms, pid=${child.pid}) - killing child`);
      killChildOnce('inactivity');
    });
    const resetIdleTimer = idleWatchdog.reset;
    const clearIdleTimer = idleWatchdog.clear;
    resetIdleTimer();

    // Run the budget guard whenever new usage is observed. Single in-flight promise
    // dedupes back-to-back usage events; once tripped, never re-runs.
    //
    // Self-correct: if a much larger usage event arrives while a check is in-flight
    // we'd otherwise drop it. We snapshot the prompt+completion tokens at check time
    // and, in `finally`, re-trigger if the live usage has materially advanced. This
    // closes the edge case where the LAST usage event of a CLI run is also the one
    // that pushes over budget - without re-trigger, that event is silently dropped.
    let budgetCheckInFlight = null;
    const runBudgetCheck = () => {
      if (!budgetGuard || budgetExhausted || budgetCheckInFlight) return;
      const snapshotPrompt = usage.promptTokens || 0;
      const snapshotCompletion = usage.completionTokens || 0;
      // Cache counters of every model call so far, so the guard prices a cache read at
      // its cache price instead of the full input rate (~5x over on a Claude Code turn).
      const cacheCounters = sumCacheCounters(perCallUsages);
      budgetCheckInFlight = (async () => {
        try {
          const result = await budgetGuard({
            promptTokens: snapshotPrompt,
            completionTokens: snapshotCompletion,
            ...cacheCounters,
            iterations: numTurns || 1,
            provider: provider || adapter.getProviderName(),
            model: model || 'unknown',
          });
          if (result && !result.proceed && !budgetExhausted) {
            console.log(`[BRIDGE] Budget guard tripped (scope=${result.scope}): ${result.reason}`);
            budgetExhausted = true;
            budgetScope = result.scope || 'agent';
            stopReason = AgentStopReason.BUDGET_EXHAUSTED;
            killChildOnce(`budget-${result.scope}`);
          }
        } catch (e) {
          console.warn(`[BRIDGE] Budget guard error: ${e.message}`);
        } finally {
          budgetCheckInFlight = null;
          // If usage advanced while we were checking, re-run once on the next tick.
          // Guards against the "last usage event drops" race when the dedup window
          // happens to coincide with the CLI's final report.
          if (!budgetExhausted &&
              ((usage.promptTokens || 0) > snapshotPrompt ||
               (usage.completionTokens || 0) > snapshotCompletion)) {
            queueMicrotask(runBudgetCheck);
          }
        }
      })();
    };

    // Cancel check interval - reads the JSON cancel payload to distinguish
    // user-initiated stops from system-initiated ones (deploy, supervisor, etc.).
    const cancelInterval = setInterval(async () => {
      const status = publisher.getCancelStatus
        ? await publisher.getCancelStatus()
        : { cancelled: await publisher.isCancelled(), cause: 'user' };
      if (status.cancelled) {
        const cause = status.cause === 'system' ? 'system' : 'user';
        if (cause === 'system') {
          cancelledBySystem = true;
          stopReason = AgentStopReason.CANCELLED;
        } else {
          stoppedByUser = true;
          stopReason = AgentStopReason.STOPPED_BY_USER;
        }
        killChildOnce(`cancel-${cause}`);
      }
    }, CANCEL_POLL_MS);

    // Build shared context for adapter message handling.
    // The `state` object also exposes the bridge-side sentinel flags so the shared
    // stopReasonMapper (called from each adapter) can pick STOPPED_BY_USER /
    // CANCELLED / TIMEOUT over whatever the CLI emits as it's being killed.
    // Per-run state owned by the adapter, isolated from the shared ctx so
    // future per-adapter fields can't accidentally race across concurrent
    // runs through the singleton adapter classes. Every adapter MUST
    // implement createRunState - the contract is enforced here, not
    // optionally fallen back to `{}`, so that adding a stateful field to
    // any adapter forces a thoughtful per-run lifetime decision.
    if (typeof adapter.createRunState !== 'function') {
      throw new Error(`Adapter ${adapter.getProviderName()} is missing createRunState(runOpts)`);
    }
    const adapterState = adapter.createRunState({ attachmentPathToName });

    const ctx = {
      publisher,
      pendingToolCalls,
      adapterState,
      // All primitives must be getters - assigning them as plain properties
      // captures the *initial* outer-closure value forever (the original
      // closure trap that hid the dedup bug for months). Reference types
      // (arrays/maps) work because they mutate in place, but they're still
      // exposed via getters for consistency. Test guard:
      // mcp/bridge/test/adapterHelpers.test.mjs "closure-trap regression".
      state: {
        get fullContent() { return fullContent; },
        get numTurns() { return numTurns; },
        get success() { return success; },
        get stopReason() { return stopReason; },
        get error() { return error; },
        get usage() { return usage; },
        get perCallUsages() { return perCallUsages; },
        get iterationTimestamps() { return iterationTimestamps; },
        get finishReasons() { return finishReasons; },
        get stoppedByUser() { return stoppedByUser; },
        get cancelledBySystem() { return cancelledBySystem; },
        get budgetExhausted() { return budgetExhausted; },
        get budgetScope() { return budgetScope; },
        get timedOut() { return timedOut; },
        get inactivityTimedOut() { return inactivityTimedOut; },
      },
      toolResults,
      thinkingSections,
      orderedEntries,
      stripMcpPrefix,
      extractToolResultAndMetadata,
      updateState(updates) {
        if (updates.fullContent != null) fullContent = updates.fullContent;
        if (updates.numTurns != null) numTurns = updates.numTurns;
        if (updates.success != null) success = updates.success;
        if (updates.stopReason != null) stopReason = updates.stopReason;
        if (updates.error != null) error = updates.error;
        if (updates.usage != null) {
          usage = updates.usage;
          // Fire-and-forget budget check on each usage update.
          runBudgetCheck();
        }
        if (updates.cliModel != null) cliModel = updates.cliModel;
      },
      getContent() { return fullContent; },
    };

    // Parse NDJSON from stdout
    const rl = createInterface({ input: child.stdout });

    rl.on('line', async (line) => {
      resetIdleTimer(); // any output from the CLI means it is alive - restart the inactivity clock
      if (!line.trim()) return;

      let msg;
      try {
        msg = JSON.parse(line);
      } catch (parseErr) {
        // Non-JSON lines (e.g. CLI banner output) are expected; log at debug
        // level only. The full line is included so we can grep for stray
        // protocol drift in prod.
        console.log(`[BRIDGE:non-json-stdout] ${line.slice(0, 200)}`);
        return;
      }

      try {
        // Delegate to adapter-specific message handler
        await adapter.handleMessage(msg, ctx);
      } catch (e) {
        // Log the FULL stack - adapter handler bugs used to be swallowed as
        // a one-line warning, hiding the root cause of prod regressions like
        // the 2026-04-08 tool-double-execution incident.
        // Logged ONLY, never published on the stream: one message the adapter could not
        // handle does not end the run, the CLI keeps going and the turn completes. The
        // stream has no non-terminal error event, and a chat reads `error` as the end of
        // the turn, so publishing one here made every later chunk of a reply that went on
        // to succeed stream to nobody. A run that really fails is announced once, where it
        // ends (announceRunFailure). The line's leading tag is the stable counting key.
        console.error(adapterHandlerErrorLine({
          provider: adapter.getProviderName?.(),
          msgType: msg?.type,
          streamId: publisher.streamId,
          error: e,
        }));
      }
    });

    // Log stderr in real-time for diagnostics, capped: the child's stderr can echo prompt or
    // tool content (LC-090), so the log keeps the size and a short head only.
    let stderrBuf = '';
    child.stderr.on('data', (chunk) => {
      const text = chunk.toString();
      stderrBuf += text;
      const trimmed = text.trim();
      const head = trimmed.length > 200 ? `${trimmed.slice(0, 200)}... (${trimmed.length} chars)` : trimmed;
      console.log(`[BRIDGE:stderr] ${head}`);
    });

    /**
     * Record what a killed CLI already spent, when it never got to report it.
     *
     * MUST run before the temp dir is deleted: it is that CLI's HOME for this run, and
     * holds the only record left. The rules live in lib/usageRecovery.mjs, where they are
     * tested; here we only apply the result to the two fields the response is built from.
     *
     * Deliberately NOT via recordCallUsage: that one also stamps an iteration timestamp
     * (which would fabricate an iteration duration for a turn that never completed) and
     * re-runs the budget guard on a child that is already dead.
     */
    const applyRecoveredUsage = () => {
      const applied = applyRecoveredUsageToRun(adapter, tmpDir, perCallUsages);
      if (!applied) return;
      usage = applied.usage;
      console.log(`[BRIDGE] recovered unreported usage after kill: prompt=${applied.entry.promptTokens}, `
        + `completion=${applied.entry.completionTokens}, cached=${applied.entry.cachedTokens}`);
    };

    child.on('close', async (code, signal) => {
      clearInterval(cancelInterval);
      clearIdleTimer();

      // Recover usage BEFORE the temp dir goes away - it is the CLI's HOME for
      // this run and holds the only record of what a killed turn already spent.
      applyRecoveredUsage();

      // Cleanup temp directory
      try { rmSync(tmpDir, { recursive: true, force: true }); } catch {}
      if (restrictedCwd) { try { rmSync(restrictedCwd, { recursive: true, force: true }); } catch {} }
      if (throwawayCwd) { try { rmSync(throwawayCwd, { recursive: true, force: true }); } catch {} }

      // If a sentinel was set (cancel / system / loop / budget) but the CLI did
      // not emit a `result` message before being killed, the mapper still has the
      // ground truth on ctx.state - call it once to materialise the canonical
      // stopReason. If neither sentinel nor mapper has spoken, fall back to
      // detecting timeout vs error from the exit signal.
      if (!stopReason || stopReason === 'END_TURN') {
        if (stoppedByUser || cancelledBySystem) {
          // Provider is irrelevant on this path: sentinels short-circuit before the
          // provider-specific lookup table is consulted. Pass the actual provider
          // anyway so the mapper's diagnostic logs reflect reality.
          applyResultMapping(provider || adapter.getProviderName(), { subtype: 'cancelled' }, ctx);
        } else if (code !== 0) {
          // Node.js sets signal='SIGTERM' when the spawn `timeout` option fires.
          // Distinguish that case from a real CLI error.
          const wasTimeout = signal === 'SIGTERM' && !stoppedByUser && !cancelledBySystem;
          if (wasTimeout) {
            timedOut = true;
            stopReason = AgentStopReason.TIMEOUT;
            error = `CLI exceeded ${spawnTimeoutMs || MAX_TIMEOUT_MS}ms timeout`;
            console.error(`[BRIDGE] CLI exceeded timeout (${spawnTimeoutMs || MAX_TIMEOUT_MS}ms)`);
          } else {
            stopReason = AgentStopReason.ERROR;
            error = stderrBuf.trim() || `CLI exited with code ${code}`;
            console.error(`[BRIDGE] CLI exited code=${code}: ${error}`);
          }
        }
      }

      if (!stopReason) {
        success = true;
        stopReason = AgentStopReason.COMPLETED;
      }

      resolvePromise({
        success,
        content: fullContent,
        toolResults,
        numTurns,
        usage,
        error,
        stopReason,
        // Which guard stopped the run ('tenant' | 'agent'), which the handler puts in the
        // response (top level and metrics.budgetScope). It was never returned, so every
        // bridge budget stop reached the backend with budget_scope NULL.
        budgetScope,
        thinkingSections,
        orderedEntries,
        cliModel,
        perCallUsages,
        iterationTimestamps,
        finishReasons,
      });
    });

    child.on('error', async (err) => {
      clearInterval(cancelInterval);
      clearIdleTimer();
      applyRecoveredUsage();
      try { rmSync(tmpDir, { recursive: true, force: true }); } catch {}
      if (restrictedCwd) { try { rmSync(restrictedCwd, { recursive: true, force: true }); } catch {} }
      if (throwawayCwd) { try { rmSync(throwawayCwd, { recursive: true, force: true }); } catch {} }

      error = err.message;
      stopReason = AgentStopReason.ERROR;
      await announceRunFailure(publisher, err.message, { callerPublishesFailure });

      resolvePromise({
        success: false,
        content: fullContent,
        toolResults,
        numTurns,
        usage,
        error: err.message,
        stopReason: AgentStopReason.ERROR,
        thinkingSections,
        orderedEntries,
        cliModel,
        perCallUsages,
        iterationTimestamps,
        finishReasons,
      });
    });
  });
}

// ─── Conversation History ─────────────────────────────────────────────────

/**
 * Build a conversationHistory array for observability recording.
 */
function buildConversationHistory(userPrompt, toolResults, assistantContent) {
  const history = [];

  if (userPrompt) {
    history.push({ role: 'USER', content: userPrompt });
  }

  if (toolResults && toolResults.length > 0) {
    for (const tr of toolResults) {
      const tc = tr.toolCall || {};
      let parsedArgs = null;
      if (tc.arguments) {
        try {
          parsedArgs = typeof tc.arguments === 'string'
            ? JSON.parse(tc.arguments)
            : tc.arguments;
        } catch { parsedArgs = {}; }
      }

      history.push({
        role: 'ASSISTANT',
        content: null,
        toolCalls: [{
          id: tc.id || null,
          toolName: tc.toolName || 'unknown',
          arguments: parsedArgs,
        }],
      });

      history.push({
        role: 'TOOL',
        content: tr.content || tr.error || '',
        toolCallId: tc.id || null,
        toolName: tc.toolName || 'unknown',
      });
    }
  }

  if (assistantContent) {
    history.push({ role: 'ASSISTANT', content: assistantContent });
  }

  return history.length > 0 ? history : null;
}

function buildPromptWithHistory(prompt, conversationHistory) {
  if (!conversationHistory || conversationHistory.length === 0) {
    return prompt;
  }

  const recentHistory = conversationHistory.slice(-MAX_HISTORY_MESSAGES);

  const historyLines = recentHistory.map((msg) => {
    const role = (msg.role || 'USER').toUpperCase();
    const content = msg.content || '';
    if (role === 'USER') return `[User]: ${content}`;
    if (role === 'ASSISTANT') return `[Assistant]: ${content}`;
    if (role === 'TOOL_RESULT') return `[Tool Result (${msg.toolName || 'unknown'})]: ${content}`;
    return `[${role}]: ${content}`;
  });

  return `Here is the conversation history for context:\n\n${historyLines.join('\n\n')}\n\n---\n\nNow respond to the user's latest message:\n\n${prompt}`;
}

// ─── Start Server ─────────────────────────────────────────────────────────

// One listener per configured address. A listen failure (EADDRNOTAVAIL: the address is not on
// this host; EADDRINUSE: another process holds the port) is fatal and named, instead of an
// unhandled 'error' event: a bridge silently missing its LAN listener is an outage that only
// shows up as empty chats.
for (const address of BIND_ADDRESSES) {
  const server = app.listen(PORT, address, () => {
    console.log(`[BRIDGE] Agent Bridge Server listening on ${address}:${PORT}`);
  });
  server.on('error', (err) => {
    console.error(`[BRIDGE] FATAL: cannot listen on ${address}:${PORT} (${err.code || err.message}). `
      + 'Check BRIDGE_BIND_ADDRESS: every address must exist on this host and the port must be free.');
    process.exit(1);
  });
}
console.log(`[BRIDGE] Agent Bridge Server starting on ${BIND_ADDRESSES.join(', ')}:${PORT}`);
if (AUTH_MODE === 'on') {
  console.log(`[BRIDGE] Gateway HMAC required on /api/bridge/** (secret: ${SECRETS.platformSecretSource()}; unrestricted toolset requires a body-bound signature with X-Provider-ID: ${UNRESTRICTED_PROVIDER_ID})`);
  if (!SECRETS.inboundSecret()) {
    console.error('[BRIDGE] ERROR: gateway authentication is ENABLED but no shared secret is configured. Every /api/bridge request will 503 until it is provisioned.');
  }
} else if (AUTH_MODE === 'auto') {
  console.log(`[BRIDGE] Gateway HMAC on /api/bridge/** is enforced whenever a shared secret is available (now: ${SECRETS.inboundSecret() ? 'yes' : 'no, requests are NOT authenticated and every run is restricted'}).`);
} else {
  console.warn('[BRIDGE] SECURITY WARNING: gateway authentication is DISABLED (BRIDGE_REQUIRE_GATEWAY_AUTH=false). Anything that can reach this port can spawn a (restricted) agent. Only for launchers whose port is not reachable from outside.');
}
if (CHILD_USER) {
  console.log(`[BRIDGE] Agent processes run as uid=${CHILD_USER.uid} gid=${CHILD_USER.gid} (via setpriv).`);
} else {
  console.warn('[BRIDGE] WARN agent processes run as the bridge uid (BRIDGE_CHILD_UID unset): a spawned agent can read this process\'s memory and environment. See deploy/systemd/lc-bridge-child-uid.conf.');
}
console.log(`[BRIDGE] Adapters: ${Object.keys(ADAPTERS).join(', ')}`);
console.log(`[BRIDGE] MCP server: ${MCP_SERVER_PATH}`);
console.log(`[BRIDGE] Agent CLI URL: ${AGENT_CLI_URL}`);
console.log(`[BRIDGE] Redis: ${maskSecrets(REDIS_URL)}`);

process.on('SIGTERM', async () => {
  console.log('[BRIDGE] Shutting down...');
  await redis.quit().catch(() => {});
  process.exit(0);
});

process.on('SIGINT', async () => {
  console.log('[BRIDGE] Shutting down...');
  await redis.quit().catch(() => {});
  process.exit(0);
});
