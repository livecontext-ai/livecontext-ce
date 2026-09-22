/**
 * Codex CLI Adapter - Handles OpenAI Codex CLI-specific spawn args,
 * MCP config format, and NDJSON message parsing.
 *
 * Codex CLI uses `codex exec "prompt" --json --full-auto` which emits
 * NDJSON events with `item.completed` and `turn.completed` types.
 *
 * Env vars:
 *   CODEX_BIN - Path to codex binary (default: "codex")
 */

import { writeFileSync, mkdirSync, copyFileSync, existsSync, readdirSync, statSync, openSync, readSync, closeSync } from 'fs';
import { resolve, join } from 'path';
import { homedir } from 'os';
import { applyResultMapping } from '../lib/stopReasonMapper.js';
import { recordCallUsage, handleCodexStyleItemEvent, incrementTurn, dispatchToolCall, dispatchToolResult, buildStdinPayload, synthIdFor } from '../lib/adapterHelpers.mjs';
import { codexReasoningArgs } from '../lib/reasoningEffort.mjs';

// ─── Configuration ────────────────────────────────────────────────────────

const CODEX_BIN = process.env.CODEX_BIN || 'codex';

/** Rollout files are NDJSON; split on either ending rather than assuming one. */
const NEWLINE_RE = /\r?\n/;

// ─── Adapter ──────────────────────────────────────────────────────────────

export class CodexAdapter {

  getProviderName() {
    return 'openai';
  }

  /**
   * Per-run state owned by this adapter. Codex currently has no per-run
   * dedup or accumulator state - Claude does - but the contract is enforced
   * by `server.mjs` so any future stateful field on this adapter has to opt
   * into per-run isolation here, never on `this`.
   */
  createRunState(_runOpts = {}) {
    return {};
  }

  /**
   * Per-MCP-call timeout written into the generated config.toml, in seconds.
   *
   * Codex stops waiting on one `tools/call` after `tool_timeout_sec` and does NOT cancel
   * it (the server keeps running; the model reads a timeout error). Its built-in default
   * moved from 60 s to 120 s to 300 s across releases and the binary is installed
   * unpinned, so the bridge WRITES the value instead of guessing which one applies:
   * `[mcp_servers.<name>] tool_timeout_sec` is a documented per-server key
   * (developers.openai.com/codex/config-reference) and `codex mcp list --json` reads the
   * written value back (verified 2026-09-06 against codex-cli 0.117.0: a config.toml written
   * by writeMcpConfig into a scratch CODEX_HOME lists `"tool_timeout_sec": 700.0`). server.mjs
   * derives the backend's hold on a parked call from it.
   *
   * This widens codex's tolerance for EVERY call on the platform server, not only parked
   * ones, so it is set above the largest tool the platform runs synchronously (web_search
   * agent_browse, 640 s; the gateway allows 660 s on those routes): the written value must
   * never be what cuts a real tool. Whether such a tool actually completes on a bridge run
   * is a separate question, answered by the run's inactivity watchdog (a CLI sitting on a
   * call prints nothing), not by this number.
   */
  static TOOL_TIMEOUT_SEC = 700;

  getToolCallTimeoutSeconds() {
    return CodexAdapter.TOOL_TIMEOUT_SEC;
  }

  /**
   * Largest tail of a rollout file we are willing to scan, in bytes.
   *
   * The rollout holds the whole transcript, so a long agentic run can reach
   * tens of MB; the usage events we want are appended after every model call,
   * so the last one is always near the end. 4 MB covers a very large final
   * tool output and still bounds the read on a box that is, by definition,
   * cleaning up after a killed run.
   */
  static ROLLOUT_SCAN_MAX_BYTES = 4 * 1024 * 1024;

  /**
   * Recover the cumulative usage of a run whose CLI died before reporting it.
   *
   * `codex exec --json` prints usage ONLY on `turn.completed` (verified against
   * codex-cli 0.154.0: the stdout stream is thread.started / turn.started /
   * item.completed / turn.completed and carries no `token_count`). A turn that
   * is killed mid-flight therefore reports NOTHING, however many model calls and
   * tool round-trips it already paid for - a stopped chat billed zero while the
   * same chat completing billed its real tokens.
   *
   * Codex's own rollout log does carry them: it is appended as the turn runs and
   * holds `token_count` events whose `info.total_token_usage` is the cumulative
   * usage of the thread so far. The bridge points CODEX_HOME at this run's temp
   * dir (see buildChildEnv), so the file is unambiguous - one run, one dir, no
   * cross-run contamination - and it is still on disk at kill time, just before
   * the temp dir is deleted.
   *
   * That the events are written DURING the turn, and not flushed at the end, is what
   * makes this work at all, so it was measured rather than assumed: a two-call run on
   * codex-cli 0.154.0 wrote FOUR token_count events into its rollout, at line indexes
   * 15, 20, 21 and 25 of 27, with the cumulative input total growing 15209 -> 30500 ->
   * 45909 as the calls completed. A kill at any point therefore leaves the usage of
   * every completed call on disk. (One of those four carried no total_token_usage; the
   * scan below simply skips such a line rather than treating it as an answer.)
   *
   * Returns null when there is nothing to recover, never a zeroed object: the
   * caller must be able to tell "no usage found" from "a call that cost zero",
   * and only the first may be silently ignored.
   *
   * @param {string} tmpDir - this run's CODEX_HOME
   * @returns {{promptTokens:number, completionTokens:number, cachedTokens:number,
   *            reasoningTokens:number} | null}
   */
  recoverUsage(tmpDir) {
    const file = this._latestRolloutFile(tmpDir);
    if (!file) return null;

    const tail = this._readTail(file, CodexAdapter.ROLLOUT_SCAN_MAX_BYTES);
    if (!tail) return null;

    // Scan forward and keep the LAST parseable total_token_usage: the values are
    // cumulative, so the newest line is the whole truth and earlier ones are
    // prefixes of it. A first line truncated by the tail read simply fails to
    // parse and is skipped.
    let latest = null;
    for (const line of tail.split(NEWLINE_RE)) {
      if (!line.includes('total_token_usage')) continue;
      let parsed;
      try {
        parsed = JSON.parse(line);
      } catch {
        continue;
      }
      const usage = this._extractTotalUsage(parsed);
      if (usage) latest = usage;
    }
    if (!latest) return null;

    const promptTokens = latest.input_tokens || 0;
    const completionTokens = latest.output_tokens || 0;
    if (promptTokens <= 0 && completionTokens <= 0) return null;

    return {
      // codex's input_tokens INCLUDES cached_input_tokens, and cachedTokens is
      // forwarded separately so billing applies the cached discount - the same
      // split the turn.completed path uses, so a recovered run is billed exactly
      // like a reported one. The rollout's cache_write count is inside input_tokens
      // too and has no additive slot in this family, so it is not mapped.
      promptTokens,
      completionTokens,
      cachedTokens: latest.cached_input_tokens || 0,
      reasoningTokens: latest.reasoning_output_tokens || 0,
    };
  }

  /**
   * `info.total_token_usage` lives one level down in the stdout event shape and
   * two levels down in the rollout shape (wrapped in `payload`). Read both
   * rather than pinning one: the rollout format is codex's, not ours.
   */
  _extractTotalUsage(parsed) {
    const candidates = [parsed?.info, parsed?.payload?.info, parsed?.payload, parsed];
    for (const candidate of candidates) {
      const usage = candidate?.total_token_usage;
      if (usage && typeof usage === 'object') return usage;
    }
    return null;
  }

  /** Newest rollout `.jsonl` under this run's CODEX_HOME sessions tree, or null. */
  _latestRolloutFile(tmpDir) {
    if (!tmpDir) return null;
    const root = join(tmpDir, 'sessions');
    if (!existsSync(root)) return null;

    let newest = null;
    const walk = (dir) => {
      let entries;
      try {
        entries = readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const entry of entries) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full);
        } else if (entry.name.endsWith('.jsonl')) {
          try {
            const mtime = statSync(full).mtimeMs;
            if (!newest || mtime > newest.mtime) newest = { path: full, mtime };
          } catch { /* vanished mid-walk */ }
        }
      }
    };
    walk(root);
    return newest ? newest.path : null;
  }

  /** Read at most `maxBytes` from the END of a file, as UTF-8. */
  _readTail(file, maxBytes) {
    let fd = null;
    try {
      const size = statSync(file).size;
      if (size === 0) return null;
      const length = Math.min(size, maxBytes);
      const buffer = Buffer.alloc(length);
      fd = openSync(file, 'r');
      // Use what was actually read, so Buffer.alloc's zero padding cannot end up glued
      // to the content. A short read would also cost us the newest events (the window
      // is taken from the END of the file, so a truncated one loses its tail and the
      // recovery falls back to an older, smaller total) - readSync does not short-read
      // a regular file in practice, and the guard below keeps that case honest rather
      // than silently wrong.
      const read = readSync(fd, buffer, 0, length, size - length);
      if (read <= 0) return null;
      return buffer.subarray(0, read).toString('utf-8');
    } catch {
      return null;
    } finally {
      if (fd !== null) { try { closeSync(fd); } catch { /* already closed */ } }
    }
  }

  /**
   * Resolve spawn command for the Codex CLI.
   */
  getCommand() {
    return {
      cmd: CODEX_BIN,
      useShell: process.platform === 'win32',
      cmdLabel: CODEX_BIN,
    };
  }

  /**
   * Build CLI arguments for `codex exec`.
   *
   * @param {object} config
   * @param {string} config.prompt - full prompt text
   * @param {string} config.systemPrompt - system prompt
   * @param {string} config.model - model ID (e.g. "codex-mini-latest")
   * @param {number} config.maxTurns - max iterations
   * @param {string} config.mcpConfigPath - path to MCP config (TOML)
   * @returns {{args: string[], stdinPayload: string}} args + stdin payload (codex reads prompt from stdin via '-')
   */
  buildArgs(config) {
    const { prompt, systemPrompt, model, maxTurns, mcpConfigPath, reasoningEffort, restrictedToolset } = config;

    // Use '-' to read prompt from stdin (avoids shell escaping issues on Windows)
    const args = [
      'exec', '-',
      '--json',
      // codex exec refuses to run outside a "trusted" directory unless it is a git repo or
      // this flag is set. Restricted (model-execution-link) runs use an EMPTY temp cwd
      // (server.mjs restrictedCwd), which is NOT a git repo, so codex would abort with
      // "Not inside a trusted directory and --skip-git-repo-check was not specified"
      // (verified on codex-cli 0.142.x, prod 2026-06-26). Harmless for non-restricted runs
      // (cwd is the real git checkout - the check would pass anyway).
      '--skip-git-repo-check',
    ];

    if (restrictedToolset) {
      // CLOUD model-execution-link API mode: expose ONLY the platform MCP tools, mirroring the
      // claude-code adapter (auto-approve MCP + strip the native tools). This REPLACED an
      // earlier read-only-sandbox lockdown that silently broke EVERYTHING: in headless
      // `codex exec` (no human approver), ANY sandbox except danger-full-access makes codex
      // require approval for each MCP tool call and then auto-cancel it - every call returned
      // "user cancelled MCP tool call", so a linked model could use NO tool at all (verified
      // live on codex-cli 0.142.2 across read-only/workspace-write + never/on-request/on-failure;
      // MCP only executes under danger-full-access).
      //
      // So we run full-access (MCP works) and instead REMOVE codex's own native tools with
      // --disable, the way claude uses --tools "": shell_tool/unified_exec is the shell escape
      // vector (verified: with it disabled the agent reports it has no local tools and cannot
      // read a host file), and browser/apps(image) close the other first-party surfaces. Bound
      // further by an empty cwd + no repo/project files + AGENT_REPO_PATH='' (server.mjs).
      //
      // LIMITATION (codex-specific, unlike claude): a couple of codex/account-side tools
      // (apply_patch, web.run) are NOT strippable by flags and stay reachable under full-access.
      // The empty throwaway cwd bounds their blast radius; a hard OS-level sandbox around the
      // codex process is the only way to fully contain them (tracked for lane-2 infra).
      args.push('-c', 'sandbox_mode="danger-full-access"',
        // full-access already bypasses approvals; set never explicitly so no headless run can
        // ever stall on an approval prompt (proven safe: with full-access there is nothing to
        // cancel, MCP calls execute - verified live 2026-07-03).
        '-c', 'approval_policy="never"',
        '--disable', 'shell_tool',
        '--disable', 'unified_exec',
        '--disable', 'browser_use',
        '--disable', 'browser_use_external',
        '--disable', 'computer_use',
        '--disable', 'in_app_browser',
        '--disable', 'apps',
        '--disable', 'image_generation');
    } else {
      args.push('--dangerously-bypass-approvals-and-sandbox');
    }

    if (model) {
      args.push('--model', model);
    }

    // Reasoning effort → `-c model_reasoning_effort="<level>"` (xhigh clamps to
    // high off codex-max variants). No-op when unset → Codex uses its default.
    args.push(...codexReasoningArgs(reasoningEffort, model));

    // Codex exec doesn't support --instructions, so prepend system prompt to
    // the prompt via the shared builder. Returned as part of the result
    // object - NOT stored on `this` - so two concurrent runs through the
    // same singleton CodexAdapter cannot race.
    const stdinPayload = buildStdinPayload(systemPrompt, prompt);

    // Codex CLI uses CODEX_HOME for MCP config - set via env in buildChildEnv()

    return { args, stdinPayload };
  }

  /**
   * Write Codex MCP config file (TOML format).
   * Codex CLI reads MCP config from $CODEX_HOME/config.toml
   *
   * @param {string} tmpDir - temp directory path (used as CODEX_HOME)
   * @param {object} mcpServerConfig - { serverName, command, args, env }
   * @returns {string} path to the config file
   */
  writeMcpConfig(tmpDir, mcpServerConfig) {
    const configPath = resolve(tmpDir, 'config.toml');

    // Build TOML content for Codex MCP server configuration
    const envEntries = Object.entries(mcpServerConfig.env || {})
      .map(([k, v]) => `${k} = "${v.replace(/"/g, '\\"')}"`)
      .join('\n');

    // Use forward slashes for Windows compatibility in TOML
    const argsArray = (mcpServerConfig.args || [])
      .map(a => `"${a.replace(/\\/g, '/').replace(/"/g, '\\"')}"`)
      .join(', ');

    const toml = `[mcp_servers.${mcpServerConfig.serverName}]
type = "stdio"
command = "${mcpServerConfig.command}"
args = [${argsArray}]
tool_timeout_sec = ${CodexAdapter.TOOL_TIMEOUT_SEC}

[mcp_servers.${mcpServerConfig.serverName}.env]
${envEntries}
`;

    writeFileSync(configPath, toml);
    return configPath;
  }

  /**
   * Build extra env overrides for the child process.
   * Sets CODEX_HOME to the temp directory so Codex reads config.toml from there.
   * Copies auth credentials from the real CODEX_HOME so login is preserved.
   */
  buildChildEnv(tmpDir) {
    const env = { ...process.env };
    if (tmpDir) {
      // Copy auth files from real CODEX_HOME to temp dir
      const realHome = env.CODEX_HOME || join(homedir(), '.codex');
      for (const file of ['auth.json', 'cap_sid']) {
        const src = join(realHome, file);
        if (existsSync(src)) {
          try {
            copyFileSync(src, join(tmpDir, file));
          } catch (e) {
            console.warn(`[BRIDGE:codex] Failed to copy ${file}: ${e.message}`);
          }
        }
      }
      env.CODEX_HOME = tmpDir;
    }
    return env;
  }

  /**
   * Handle a single NDJSON message from the Codex CLI.
   * Maps Codex CLI events to the shared context.
   *
   * Codex NDJSON event types:
   * - item.completed + agent_message: text output
   * - item.completed + mcp_tool_call: tool invocation
   * - item.completed + mcp_tool_call_result: tool result
   * - item.completed + reasoning: thinking/reasoning
   * - turn.completed: iteration end
   *
   * @param {object} msg - parsed NDJSON line
   * @param {object} ctx - shared execution context
   */
  async handleMessage(msg, ctx) {
    if (msg.type === 'item.started' || msg.type === 'item.completed') {
      const item = msg.item;
      if (!item) return;

      // Codex-specific item types not handled by handleCodexStyleItemEvent.
      // Everything else (agent_message, mcp_tool_call, mcp_tool_call_result,
      // function_call, function_call_output, reasoning) goes through the
      // shared helper used by gemini & mistral.
      const isCodexOnly = item.type === 'command_execution' || item.type === 'web_search';
      if (!isCodexOnly) {
        return handleCodexStyleItemEvent(msg, ctx, {
          providerKey: 'codex',
          recordUsage: (u, c) => {
            recordCallUsage(c, {
              promptTokens: u.input_tokens || u.prompt_tokens || 0,
              completionTokens: u.output_tokens || u.completion_tokens || 0,
              // Cached prompt subset (already included in input_tokens) -
              // forwarded so billing applies the OpenAI cached discount.
              cachedTokens: u.cached_input_tokens || 0,
              // `cache_write_input_tokens` is deliberately NOT mapped: it is part of
              // input_tokens (the captured payload's own arithmetic says so, input +
              // output == total with the write inside input), whereas
              // cacheCreationInputTokens is the ADDITIVE Anthropic slot. Putting a subset
              // there bills those tokens twice for a codex run billed under an Anthropic
              // pair, and buys nothing otherwise - OpenAI prices no separate cache write.
              // `reasoning_output_tokens` is what codex actually emits - captured live
              // from codex-cli 0.154.0, in both the turn.completed payload and the
              // rollout log. The `reasoning_tokens` spelling read here before matched
              // nothing, so every reasoning token this provider spent was recorded as
              // zero. Kept as a fallback rather than replaced, in case an older or
              // newer build uses it.
              reasoningTokens: u.reasoning_output_tokens || u.reasoning_tokens || 0,
            });
          },
        });
      }

      // Codex-only handlers below.
      if (msg.type === 'item.started' && item.type === 'command_execution' && item.command) {
        const toolId = item.id || synthIdFor('codex_cmd');
        await dispatchToolCall(ctx, {
          toolId,
          toolName: 'shell',
          argsStr: JSON.stringify({ command: item.command }),
        });
        return;
      }

      if (msg.type === 'item.completed' && item.type === 'command_execution') {
        const toolId = item.id || synthIdFor('codex_cmd');
        const command = item.command || '';
        const output = item.aggregated_output || item.output || '';
        const isError = item.exit_code != null && item.exit_code !== 0;
        if (!ctx.pendingToolCalls.has(toolId)) {
          await dispatchToolCall(ctx, {
            toolId,
            toolName: 'shell',
            argsStr: JSON.stringify({ command }),
          });
        }
        await dispatchToolResult(ctx, {
          toolId,
          isError,
          content: output,
          fallbackToolName: 'shell',
        });
        return;
      }

      if (msg.type === 'item.completed' && item.type === 'web_search') {
        const toolId = item.id || synthIdFor('codex_ws');
        const query = item.query || item.action?.query || '';
        const searchResults = JSON.stringify(item.action || {});
        await dispatchToolCall(ctx, {
          toolId,
          toolName: 'web_search',
          argsStr: JSON.stringify({ query }),
        });
        await dispatchToolResult(ctx, {
          toolId,
          isError: false,
          content: searchResults,
          fallbackToolName: 'web_search',
        });
        return;
      }
      return;
    }

    if (msg.type === 'turn.completed') {
      incrementTurn(ctx);
      console.log(`[BRIDGE:codex] turn completed, numTurns=${ctx.state?.numTurns || 0}`);

      // Extract usage from turn if present
      if (msg.usage) {
        const u = msg.usage;
        const input = u.input_tokens || u.prompt_tokens || 0;
        const output = u.output_tokens || u.completion_tokens || 0;
        if (input > 0 || output > 0) {
          recordCallUsage(ctx, {
            promptTokens: input,
            completionTokens: output,
            cachedTokens: u.cached_input_tokens || 0,
            // See the note on the item path: `reasoning_output_tokens` is what codex
            // emits, and cache writes stay inside input_tokens rather than beside them.
            reasoningTokens: u.reasoning_output_tokens || u.reasoning_tokens || 0,
          });
        }
      }
    } else if (msg.type === 'error') {
      const errMsg = msg.message || msg.error || 'Unknown Codex error';
      console.error(`[BRIDGE:codex:error] ${errMsg}`);
      // Synthesize a result-shaped message so the shared mapper can handle it.
      applyResultMapping('codex', { subtype: 'error', error: errMsg }, ctx);
    } else if (msg.type === 'session.completed' || msg.type === 'response.completed') {
      // Session completion - delegate to the shared stopReason mapper.
      console.log(`[BRIDGE:codex:done] type=${msg.type}`);
      const mapped = applyResultMapping('codex', { ...msg, subtype: msg.subtype || 'success' }, ctx);

      if (mapped.success && !ctx.getContent() && msg.result) {
        ctx.updateState({ fullContent: msg.result });
      }
      if (msg.model) {
        ctx.updateState({ cliModel: msg.model });
      }
    } else {
      console.log(`[BRIDGE:codex:msg] type=${msg.type}`);
    }
  }
}
