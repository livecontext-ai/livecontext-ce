/**
 * Gemini CLI Adapter - Handles Google Gemini CLI-specific spawn args,
 * MCP config format, and NDJSON message parsing.
 *
 * Gemini CLI uses `gemini -p "prompt" --output-format stream-json` which emits
 * NDJSON events with various message types for content, tool calls, and results.
 *
 * MCP config: JSON `settings.json` in $GEMINI_HOME directory.
 *
 * Env vars:
 *   GEMINI_BIN  - Path to gemini binary (default: "gemini")
 *   GEMINI_HOME - Override Gemini config home (auto-set to tmpDir)
 */

import { writeFileSync, mkdirSync, copyFileSync, existsSync } from 'fs';
import { resolve, join } from 'path';
import { homedir } from 'os';
import { applyResultMapping } from '../lib/stopReasonMapper.js';
import { recordCallUsage, handleCodexStyleItemEvent, handleClaudeStyleAssistantMessage, handleFlatCliMessage, buildStdinPayload, incrementTurn } from '../lib/adapterHelpers.mjs';

// ─── Configuration ────────────────────────────────────────────────────────

const GEMINI_BIN = process.env.GEMINI_BIN || 'gemini';

// ─── Helpers ──────────────────────────────────────────────────────────────

/**
 * Extract total input tokens from Gemini usage object.
 * Gemini may report: prompt_token_count / candidates_token_count / total_token_count
 * or: input_tokens / output_tokens.
 */
function getInputTokens(usage) {
  if (!usage) return 0;
  return usage.input_tokens || usage.prompt_token_count || usage.promptTokenCount || 0;
}

function getOutputTokens(usage) {
  if (!usage) return 0;
  return usage.output_tokens || usage.candidates_token_count || usage.candidatesTokenCount || 0;
}

// ─── Adapter ──────────────────────────────────────────────────────────────

export class GeminiAdapter {

  getProviderName() {
    return 'google';
  }

  /**
   * Per-run state owned by this adapter. Gemini currently has no per-run
   * dedup or accumulator state, but the contract is enforced by `server.mjs`
   * so any future stateful field on this adapter has to opt into per-run
   * isolation here, never on `this` (singletons would race).
   */
  createRunState(_runOpts = {}) {
    return {};
  }

  /**
   * Resolve spawn command for the Gemini CLI.
   */
  getCommand() {
    return {
      cmd: GEMINI_BIN,
      useShell: process.platform === 'win32',
      cmdLabel: GEMINI_BIN,
    };
  }

  /**
   * Build CLI arguments for `gemini`.
   *
   * @param {object} config
   * @param {string} config.prompt - full prompt text
   * @param {string} config.systemPrompt - system prompt
   * @param {string} config.model - model ID (e.g. "gemini-2.5-pro")
   * @param {number} config.maxTurns - max iterations
   * @param {string} config.mcpConfigPath - path to MCP config (unused, config via GEMINI_HOME)
   * @returns {{args: string[], stdinPayload: string}} args + stdin payload (gemini reads prompt from stdin via '-')
   */
  buildArgs(config) {
    const { prompt, systemPrompt, model, maxTurns, restrictedToolset, mcpServerName } = config;

    // Gemini CLI does not support a separate system prompt flag - prepend it
    // to the user prompt via the shared builder. Returned in the result
    // object so concurrent runs through the singleton GeminiAdapter cannot
    // race on shared state.
    const stdinPayload = buildStdinPayload(systemPrompt, prompt);

    // Use '-' to read prompt from stdin (avoids shell escaping issues)
    const args = [
      '-p', '-',
      '--output-format', 'stream-json',
      '--sandbox', 'none',
    ];

    if (restrictedToolset) {
      // CLOUD model-execution-link API mode: expose ONLY the platform MCP tools, mirroring the
      // claude-code adapter (auto-approve MCP + strip the native tools). This REPLACED an
      // earlier `--approval-mode plan` lockdown that silently broke EVERYTHING: `plan` is a
      // read-only planning mode that NEVER executes a tool, so every platform MCP call was
      // blocked and a linked model could use no tool at all (same failure class proven live on
      // codex; gemini shares it by design). `yolo` auto-approves tool calls (the analog of
      // claude's --dangerously-skip-permissions) so MCP actually runs headless, while the
      // native built-in tools are stripped in settings.json (an explicit excludeTools denylist,
      // see writeMcpConfig) and MCP is pinned to the platform server only. (A `coreTools: []`
      // allowlist would be strictly more robust than a denylist, but gemini-cli's empty-allowlist
      // semantics need live confirmation - validate that when the binary is provisioned.)
      // Bound further by an empty cwd + no repo/project files (server.mjs).
      // NOTE: gemini-cli is not installed on the current bridge host, so this path cannot be
      // exercised live yet - the flag choice follows gemini-cli's documented approval modes;
      // verify end-to-end once the binary is provisioned (lane-2).
      args.push('--approval-mode', 'yolo',
        '--allowed-mcp-server-names', mcpServerName || 'agent-cli');
    }

    if (model) {
      args.push('--model', model);
    }

    if (maxTurns) {
      args.push('--max-turns', String(maxTurns));
    }

    return { args, stdinPayload };
  }

  /**
   * Write Gemini MCP config file (JSON format).
   * Gemini CLI reads MCP config from $GEMINI_HOME/settings.json
   *
   * @param {string} tmpDir - temp directory path (used as GEMINI_HOME)
   * @param {object} mcpServerConfig - { serverName, command, args, env }
   * @returns {string} path to the config file
   */
  writeMcpConfig(tmpDir, mcpServerConfig, restrictedToolset = false) {
    const configPath = resolve(tmpDir, 'settings.json');

    const config = {
      mcpServers: {
        [mcpServerConfig.serverName]: {
          command: mcpServerConfig.command,
          args: (mcpServerConfig.args || []).map(a => a.replace(/\\/g, '/')),
          env: mcpServerConfig.env || {},
        },
      },
    };

    if (restrictedToolset) {
      // API mode: strip the built-in gemini tools so only the platform MCP tools remain
      // (the settings-side analog of claude's --tools ""), via an explicit excludeTools denylist
      // of gemini-cli's core tool names. Needed because --approval-mode yolo
      // (set in buildArgs so MCP actually executes) would otherwise also auto-run the native
      // shell/file/web tools. excludeTools is an explicit denylist of gemini-cli's core tool
      // names; MCP tools are not core tools, so they are unaffected. (Unverified until the
      // gemini binary is installed on the bridge - see buildArgs note.)
      config.excludeTools = [
        'run_shell_command', 'write_file', 'replace', 'read_file', 'read_many_files',
        'web_fetch', 'google_web_search', 'glob', 'search_file_content',
        'list_directory', 'save_memory',
      ];
    }

    writeFileSync(configPath, JSON.stringify(config, null, 2));
    return configPath;
  }

  /**
   * Build extra env overrides for the child process.
   * Sets GEMINI_HOME to the temp directory so Gemini CLI reads settings from there.
   * Copies auth credentials from the real config directory if they exist.
   */
  buildChildEnv(tmpDir) {
    const env = { ...process.env };
    if (tmpDir) {
      // Copy auth files from real Gemini home to temp dir
      const realHome = env.GEMINI_HOME || join(homedir(), '.gemini');
      for (const file of ['credentials.json', 'auth.json', '.gemini_api_key']) {
        const src = join(realHome, file);
        if (existsSync(src)) {
          try {
            copyFileSync(src, join(tmpDir, file));
          } catch (e) {
            console.warn(`[BRIDGE:gemini] Failed to copy ${file}: ${e.message}`);
          }
        }
      }
      env.GEMINI_HOME = tmpDir;
    }
    return env;
  }

  /**
   * Handle a single NDJSON message from the Gemini CLI.
   * Maps Gemini CLI events to the shared context.
   *
   * Gemini NDJSON event types (empirically determined):
   * - type=system: init, model info
   * - type=assistant / content blocks: text output
   * - type=tool_use / function_call: tool invocation
   * - type=tool_result / function_response: tool result
   * - type=thinking: reasoning content
   * - type=turn_complete: iteration end
   * - type=result: final result with usage
   * - type=error: error message
   * - item.completed with nested items (Codex-style events)
   *
   * @param {object} msg - parsed NDJSON line
   * @param {object} ctx - shared execution context
   */
  async handleMessage(msg, ctx) {
    // ── Codex/OpenAI style events (item.completed / item.started) ──
    if (msg.type === 'item.completed' || msg.type === 'item.started') {
      await this._handleItemEvent(msg, ctx);
      return;
    }

    // ── Claude-style assistant message with content blocks ──
    if (msg.type === 'assistant' && msg.message?.content) {
      await this._handleAssistantMessage(msg, ctx);
      return;
    }

    // ── Flat CLI events (text/delta/tool_use/tool_result/thinking) ──
    if (await handleFlatCliMessage(msg, ctx, { providerKey: 'gemini' })) return;

    // ── Turn complete ──
    if (msg.type === 'turn_complete' || msg.type === 'turn.completed') {
      incrementTurn(ctx);
      console.log(`[BRIDGE:gemini] turn completed, numTurns=${ctx.state?.numTurns || 0}`);

      if (msg.usage) {
        this._recordUsage(msg.usage, ctx);
      }
      return;
    }

    // ── System init ──
    if (msg.type === 'system') {
      if (msg.model) {
        ctx.updateState({ cliModel: msg.model });
        console.log(`[BRIDGE:gemini] CLI model: ${msg.model}`);
      }
      const tools = msg.tools || [];
      const mcpServers = (msg.mcp_servers || []).filter(s => s.status === 'connected');
      console.log(`[BRIDGE:gemini] Init: ${tools.length} tools, ${mcpServers.length} MCP connected`);
      return;
    }

    // ── Result / completion ──
    if (msg.type === 'result' || msg.type === 'session.completed' || msg.type === 'response.completed') {
      console.log(`[BRIDGE:gemini:done] type=${msg.type}, subtype=${msg.subtype || '-'}`);

      // Delegate to the shared stopReason mapper so the adapter emits canonical
      // AgentStopReason values (mirrors the Java enum).
      const mapped = applyResultMapping('gemini', msg, ctx);
      if (mapped.success && !ctx.getContent() && msg.result) {
        ctx.updateState({ fullContent: msg.result });
      }
      if (msg.model) {
        ctx.updateState({ cliModel: msg.model });
      }
      if (msg.usage) {
        this._recordUsage(msg.usage, ctx);
      }
      if (msg.num_turns) {
        ctx.updateState({ numTurns: msg.num_turns });
      }
      return;
    }

    // ── Error ── delegate to the shared mapper so the canonical
    // AgentStopReason is emitted (mirrors codex/claude behavior).
    if (msg.type === 'error') {
      const errMsg = msg.message || msg.error || 'Unknown Gemini error';
      console.error(`[BRIDGE:gemini:error] ${errMsg}`);
      applyResultMapping('gemini', { subtype: 'error', error: errMsg }, ctx);
      return;
    }

    // ── Unknown event - log for debugging ──
    console.log(`[BRIDGE:gemini:msg] type=${msg.type} subtype=${msg.subtype || '-'}`);
  }

  // ─── Private Helpers ─────────────────────────────────────────────────────

  /**
   * Handle Codex-style item.completed / item.started events.
   * Some CLI tools emit these instead of flat events.
   */
  async _handleItemEvent(msg, ctx) {
    return handleCodexStyleItemEvent(msg, ctx, {
      providerKey: 'gemini',
      recordUsage: (usage, c) => this._recordUsage(usage, c),
    });
  }

  /**
   * Handle Claude-style assistant message with content blocks.
   */
  async _handleAssistantMessage(msg, ctx) {
    return handleClaudeStyleAssistantMessage(msg, ctx, {
      providerKey: 'gemini',
      recordUsage: (usage, c) => this._recordUsage(usage, c),
    });
  }

  /**
   * Record usage from a Gemini usage object. Delegates the canonical
   * push + cumulative recompute to the shared adapterHelpers.recordCallUsage,
   * keeping only the Gemini-specific field aliasing here.
   */
  _recordUsage(usage, ctx) {
    const input = getInputTokens(usage);
    const output = getOutputTokens(usage);
    if (input > 0 || output > 0) {
      recordCallUsage(ctx, {
        promptTokens: input,
        completionTokens: output,
        cacheCreationInputTokens: usage.cache_creation_input_tokens || 0,
        cacheReadInputTokens: usage.cache_read_input_tokens || usage.cached_content_token_count || 0,
        reasoningTokens: usage.reasoning_tokens || usage.thoughts_token_count || 0,
      });
    }
  }
}
