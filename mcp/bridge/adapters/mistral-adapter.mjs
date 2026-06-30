/**
 * Mistral Vibe CLI Adapter - Handles Mistral Vibe CLI-specific spawn args,
 * MCP config format, and NDJSON message parsing.
 *
 * Vibe CLI uses `vibe --prompt "text" --output streaming` which emits
 * NDJSON events. MCP config is TOML format at $HOME/.vibe/config.toml.
 *
 * Note: Vibe uses `servername_toolname` format (no `mcp__` prefix).
 * The stripMcpPrefix() in server.mjs handles this with a fallback pattern.
 *
 * Env vars:
 *   VIBE_BIN       - Path to vibe binary (default: "vibe")
 *   MISTRAL_API_KEY - Mistral API key (copied to vibe config)
 */

import { writeFileSync, mkdirSync, copyFileSync, existsSync, readFileSync } from 'fs';
import { resolve, join } from 'path';
import { homedir } from 'os';
import { applyResultMapping } from '../lib/stopReasonMapper.js';
import { recordCallUsage, handleCodexStyleItemEvent, handleClaudeStyleAssistantMessage, handleFlatCliMessage, buildStdinPayload, incrementTurn } from '../lib/adapterHelpers.mjs';

// ─── Configuration ────────────────────────────────────────────────────────

const VIBE_BIN = process.env.VIBE_BIN || 'vibe';

// ─── Adapter ──────────────────────────────────────────────────────────────

export class MistralAdapter {

  getProviderName() {
    return 'mistral';
  }

  /**
   * Per-run state owned by this adapter. Mistral/Vibe currently has no
   * per-run dedup or accumulator state, but the contract is enforced by
   * `server.mjs` so any future stateful field on this adapter has to opt
   * into per-run isolation here, never on `this` (singletons would race).
   */
  createRunState(_runOpts = {}) {
    return {};
  }

  /**
   * Resolve spawn command for the Vibe CLI.
   */
  getCommand() {
    return {
      cmd: VIBE_BIN,
      useShell: process.platform === 'win32',
      cmdLabel: VIBE_BIN,
    };
  }

  /**
   * Build CLI arguments for `vibe`.
   *
   * @param {object} config
   * @param {string} config.prompt - full prompt text
   * @param {string} config.systemPrompt - system prompt
   * @param {string} config.model - model ID (e.g. "mistral-large-latest")
   * @param {number} config.maxTurns - max iterations
   * @param {string} config.mcpConfigPath - unused (config via HOME)
   * @returns {{args: string[], stdinPayload: string}} args + stdin payload (vibe reads prompt from stdin via '-')
   */
  buildArgs(config) {
    const { prompt, systemPrompt, model, maxTurns, restrictedToolset, mcpServerName } = config;

    // Vibe CLI does not support a separate system prompt flag - prepend it to
    // the user prompt via the shared builder. Returned in the result object so
    // concurrent runs through the singleton MistralAdapter cannot race on
    // shared state.
    const stdinPayload = buildStdinPayload(systemPrompt, prompt);

    // Use stdin for prompt (avoids shell escaping issues)
    const args = [
      '--prompt', '-',
      '--output', 'streaming',
    ];

    if (restrictedToolset) {
      // CLOUD model-execution-link API mode: in programmatic mode (--prompt), --enabled-tools
      // DISABLES every other tool, so allowlisting only the platform MCP server's tools blocks
      // all native tools. The glob matches the MCP tools (named after the server) whatever
      // vibe's exact prefix. Empty cwd + no project files (server.mjs) blocks project context.
      args.push('--enabled-tools', `*${mcpServerName || 'agent-cli'}*`);
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
   * Write Vibe MCP config file (TOML format).
   * Vibe CLI reads MCP config from $HOME/.vibe/config.toml
   *
   * @param {string} tmpDir - temp directory path (used as HOME)
   * @param {object} mcpServerConfig - { serverName, command, args, env }
   * @returns {string} path to the config file
   */
  writeMcpConfig(tmpDir, mcpServerConfig) {
    const vibeDir = resolve(tmpDir, '.vibe');
    mkdirSync(vibeDir, { recursive: true });
    const configPath = resolve(vibeDir, 'config.toml');

    // Build TOML content for Vibe MCP server configuration
    const envEntries = Object.entries(mcpServerConfig.env || {})
      .map(([k, v]) => `${k} = "${v.replace(/"/g, '\\"')}"`)
      .join('\n');

    // Use forward slashes for Windows compatibility in TOML
    const argsArray = (mcpServerConfig.args || [])
      .map(a => `"${a.replace(/\\/g, '/').replace(/"/g, '\\"')}"`)
      .join(', ');

    const toml = `[[mcp_servers]]
name = "${mcpServerConfig.serverName}"
transport = "stdio"
command = "${mcpServerConfig.command}"
args = [${argsArray}]

[mcp_servers.${mcpServerConfig.serverName}.env]
${envEntries}
`;

    writeFileSync(configPath, toml);
    return configPath;
  }

  /**
   * Build extra env overrides for the child process.
   * Sets HOME to the temp directory so Vibe reads .vibe/config.toml from there.
   * Copies auth credentials from the real home if they exist.
   */
  buildChildEnv(tmpDir) {
    const env = { ...process.env };
    if (tmpDir) {
      const realHome = homedir();
      const realVibeDir = join(realHome, '.vibe');
      const tmpVibeDir = join(tmpDir, '.vibe');
      mkdirSync(tmpVibeDir, { recursive: true });

      // Copy .env file with MISTRAL_API_KEY from real vibe config
      const envFile = join(realVibeDir, '.env');
      if (existsSync(envFile)) {
        try {
          copyFileSync(envFile, join(tmpVibeDir, '.env'));
        } catch (e) {
          console.warn(`[BRIDGE:mistral] Failed to copy .env: ${e.message}`);
        }
      }

      // Copy auth/credentials files if they exist
      for (const file of ['credentials.json', 'auth.json']) {
        const src = join(realVibeDir, file);
        if (existsSync(src)) {
          try {
            copyFileSync(src, join(tmpVibeDir, file));
          } catch (e) {
            console.warn(`[BRIDGE:mistral] Failed to copy ${file}: ${e.message}`);
          }
        }
      }

      // Ensure MISTRAL_API_KEY is in env (from process.env or .env file)
      if (!env.MISTRAL_API_KEY && existsSync(envFile)) {
        try {
          const content = readFileSync(envFile, 'utf-8');
          const match = content.match(/MISTRAL_API_KEY=(.+)/);
          if (match) {
            env.MISTRAL_API_KEY = match[1].trim();
          }
        } catch { /* ignore */ }
      }

      env.HOME = tmpDir;
    }
    return env;
  }

  /**
   * Handle a single NDJSON message from the Vibe CLI.
   * Maps Vibe CLI events to the shared context.
   *
   * Vibe NDJSON event types (empirically determined):
   * - type=system, subtype=init: initialization, model info
   * - role=assistant: text output from the agent
   * - type=tool_use / tool_call: tool invocation (uses servername_toolname format)
   * - type=tool_result: tool result
   * - type=thinking: reasoning content
   * - type=turn_complete: iteration end
   * - type=result: final result with usage
   * - type=error: error message
   * - item.completed: Codex-style nested events
   *
   * @param {object} msg - parsed NDJSON line
   * @param {object} ctx - shared execution context
   */
  async handleMessage(msg, ctx) {
    const { publisher, pendingToolCalls, toolResults, thinkingSections, orderedEntries } = ctx;

    // ── Codex-style item events ──
    if (msg.type === 'item.completed' || msg.type === 'item.started') {
      await this._handleItemEvent(msg, ctx);
      return;
    }

    // ── System init ──
    if (msg.type === 'system') {
      if (msg.model) {
        ctx.updateState({ cliModel: msg.model });
        console.log(`[BRIDGE:mistral] CLI model: ${msg.model}`);
      }
      if (msg.subtype === 'init') {
        const tools = msg.tools || [];
        console.log(`[BRIDGE:mistral] Init: ${tools.length} tools`);
      }
      return;
    }

    // ── Assistant message (role-based) ──
    // Note: do NOT call incrementTurn here. Vibe CLI emits both `role:assistant`
    // AND a subsequent `turn_complete`/`turn.completed` event for the same
    // logical turn. Incrementing in both paths double-counts. The shared
    // turn_complete branch below is the canonical bump.
    if (msg.role === 'assistant') {
      const text = msg.content || msg.text || '';
      if (text) {
        await publisher.publishContent(text);
        ctx.updateState({ fullContent: ctx.getContent() + text });
      }
      if (msg.model) ctx.updateState({ cliModel: msg.model });
      if (msg.usage) this._recordUsage(msg.usage, ctx);
      return;
    }

    // ── Assistant message (type-based, with content blocks) ──
    if (msg.type === 'assistant' && msg.message?.content) {
      await this._handleAssistantMessage(msg, ctx);
      return;
    }

    // ── Flat CLI events (text/delta/tool_use/tool_result/thinking) ──
    if (await handleFlatCliMessage(msg, ctx, { providerKey: 'mistral' })) return;

    // ── Turn complete ──
    if (msg.type === 'turn_complete' || msg.type === 'turn.completed') {
      incrementTurn(ctx);
      console.log(`[BRIDGE:mistral] turn completed, numTurns=${(ctx.state?.numTurns || 0)}`);
      if (msg.usage) this._recordUsage(msg.usage, ctx);
      return;
    }

    // ── Result / completion ──
    if (msg.type === 'result' || msg.type === 'session.completed' || msg.type === 'response.completed') {
      console.log(`[BRIDGE:mistral:done] type=${msg.type}, subtype=${msg.subtype || '-'}`);

      // Delegate to the shared stopReason mapper so the adapter emits canonical
      // AgentStopReason values (mirrors the Java enum).
      const mapped = applyResultMapping('mistral', msg, ctx);
      if (mapped.success && !ctx.getContent() && msg.result) {
        ctx.updateState({ fullContent: msg.result });
      }
      if (msg.model) ctx.updateState({ cliModel: msg.model });
      if (msg.usage) this._recordUsage(msg.usage, ctx);
      if (msg.num_turns) ctx.updateState({ numTurns: msg.num_turns });
      return;
    }

    // ── Error ── delegate to the shared mapper so the canonical
    // AgentStopReason is emitted (mirrors codex/claude behavior).
    if (msg.type === 'error') {
      const errMsg = msg.message || msg.error || 'Unknown Mistral error';
      console.error(`[BRIDGE:mistral:error] ${errMsg}`);
      applyResultMapping('mistral', { subtype: 'error', error: errMsg }, ctx);
      return;
    }

    // ── Unknown event ──
    console.log(`[BRIDGE:mistral:msg] type=${msg.type} subtype=${msg.subtype || '-'}`);
  }

  // ─── Private Helpers ─────────────────────────────────────────────────────

  /**
   * Handle Codex-style item.completed / item.started events.
   */
  async _handleItemEvent(msg, ctx) {
    return handleCodexStyleItemEvent(msg, ctx, {
      providerKey: 'mistral',
      recordUsage: (usage, c) => this._recordUsage(usage, c),
    });
  }

  /**
   * Handle assistant message with content blocks.
   */
  async _handleAssistantMessage(msg, ctx) {
    return handleClaudeStyleAssistantMessage(msg, ctx, {
      providerKey: 'mistral',
      recordUsage: (usage, c) => this._recordUsage(usage, c),
    });
  }

  /**
   * Record usage from a Mistral usage object. Delegates the canonical
   * push + cumulative recompute to the shared adapterHelpers.recordCallUsage,
   * keeping only the Mistral-specific field aliasing here.
   */
  _recordUsage(usage, ctx) {
    const input = usage.input_tokens || usage.prompt_tokens || usage.promptTokens || 0;
    const output = usage.output_tokens || usage.completion_tokens || usage.completionTokens || 0;
    if (input > 0 || output > 0) {
      recordCallUsage(ctx, {
        promptTokens: input,
        completionTokens: output,
        reasoningTokens: usage.reasoning_tokens || 0,
      });
    }
  }
}
