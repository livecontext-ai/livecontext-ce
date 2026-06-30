/**
 * Claude CLI Adapter - Handles Claude Code CLI-specific spawn args,
 * MCP config format, and NDJSON message parsing.
 *
 * Extracted from server.mjs to support multiple CLI backends.
 */

import { existsSync, writeFileSync } from 'fs';
import { resolve } from 'path';
import { applyResultMapping } from '../lib/stopReasonMapper.js';
import { recordCallUsage, dispatchToolCall, dispatchToolResult, incrementTurn } from '../lib/adapterHelpers.mjs';
import { claudeReasoningEnv } from '../lib/reasoningEffort.mjs';

// ─── Anthropic protocol constants ─────────────────────────────────────────

const STOP_REASON_PAUSE_TURN = 'pause_turn';

// ─── Configuration ────────────────────────────────────────────────────────

const CLAUDE_BIN = process.env.CLAUDE_BIN || 'claude';
const CLAUDE_CLI_JS = process.env.CLAUDE_CLI_JS || '';

// Direct path to the Claude Code .exe on Windows. We spawn this instead of the
// `claude.cmd` shim because Node's `shell: true` runs `cmd.exe /d /s /c "..."`,
// which truncates any single argument at the first '\n'. Our `-p` prompt is
// multi-line (`buildPromptWithHistory`), so cmd.exe silently delivers only
// "Here" to claude.exe. Spawning the .exe with `shell: false` preserves
// newlines end-to-end via CreateProcess.
const CLAUDE_EXE_WIN = process.env.CLAUDE_EXE_WIN || resolve(
  process.env.APPDATA || '',
  'npm/node_modules/@anthropic-ai/claude-code/bin/claude.exe'
);

// ─── Helpers ──────────────────────────────────────────────────────────────
//
// Model is passed verbatim to `claude --model <id>`. The CLI accepts both
// Anthropic API ids (e.g. "claude-opus-4-7", "claude-sonnet-4-6") AND
// family aliases (e.g. "opus", "sonnet", "haiku"). Catalog rows under
// provider='claude-code' store the exact API id (sourced from
// BridgeAllowlist - backend/shared-agent-lib/.../bridge/BridgeAllowlist.java)
// so version selection in the UI (Opus 4.7 vs 4.6) is honoured at the CLI
// layer. An earlier version of this file extracted just the family alias
// via regex, which silently collapsed all Opus versions into whatever
// Anthropic's "opus" alias currently resolves to - bug fixed in V128.

/**
 * Extract total input tokens from Anthropic usage object.
 * Anthropic reports cache-aware usage:
 *   input_tokens        - new (non-cached) input tokens
 *   cache_creation_input_tokens - tokens written to cache this request
 *   cache_read_input_tokens     - tokens read from cache
 * Total real input = sum of all three.
 */
function getTotalInputTokens(usage) {
  if (!usage) return 0;
  return (usage.input_tokens || 0)
    + (usage.cache_creation_input_tokens || 0)
    + (usage.cache_read_input_tokens || 0);
}

// ─── Adapter ──────────────────────────────────────────────────────────────

/**
 * Whether two consecutive Claude API calls represent the same logical user
 * turn split into pause/resume by extended thinking. Anthropic Opus 4.x emits
 * a provisional response with `stop_reason=pause_turn` followed by an auto-
 * resumed call replaying the same input context. Both are billed (so we count
 * both in `perCallUsages`), but we mark the second one as a continuation so
 * prod log readers do not misread it as a bridge double-execution bug.
 */
export function isExtendedThinkingContinuation(prevCall, currentInput, prevStopReason) {
  return prevStopReason === STOP_REASON_PAUSE_TURN
      && prevCall != null
      && prevCall.promptTokens === currentInput;
}

export class ClaudeAdapter {

  getProviderName() {
    return 'anthropic';
  }

  /**
   * Per-run state owned by this adapter, isolated from the shared `ctx` so
   * other adapters never see Claude-specific dedup data. Called once per
   * `claude` subprocess by the bridge runtime (server.mjs).
   *
   * @param {object} runOpts - request-scoped data the runtime injects so the
   *   adapter never reaches outside its own state. Recognised:
   *     attachmentPathToName: Map<string, string> - paths → user-facing names
   *       (used by the Read→view_attachment relabel below)
   */
  createRunState(runOpts = {}) {
    return {
      // Anthropic block.id (`toolu_…`) of every tool_use we have already
      // dispatched, so snapshot re-emissions in `stream-json --verbose` mode
      // (one assistant event per content_block_stop) do not cause a tool to
      // run multiple times.
      seenToolUseIds: new Set(),
      // Anthropic message id (`msg_…`) of every assistant message whose usage
      // we have already pushed to perCallUsages.
      seenAssistantUsageMsgIds: new Set(),
      // Maps a tool_use input file path to the user-facing attachment name
      // (Claude `Read` → bridge `view_attachment` relabel).
      attachmentPathToName: runOpts.attachmentPathToName || null,
      // Stream-deltas tracking - Claude CLI may emit text/thinking either as
      // streaming `stream_event` deltas OR as full `assistant` blocks. These
      // flags prevent double-publishing when both arrive for the same content.
      streamedContentViaDeltas: false,
      streamedThinkingViaDeltas: false,
    };
  }

  /**
   * Resolve spawn command and args for the Claude CLI.
   *
   * Windows path resolution (in priority order):
   *   1. CLAUDE_CLI_JS env → spawn node directly on the .js entry
   *   2. claude.exe at APPDATA/npm/... → spawn the exe directly with
   *      shell:false so newlines in `-p` survive (cmd.exe would truncate)
   *   3. fall back to CLAUDE_BIN via shell (legacy - multi-line prompts
   *      will break here; only reached when the install layout is unusual)
   */
  getCommand() {
    if (CLAUDE_CLI_JS) {
      return {
        cmd: process.execPath,  // "node"
        useShell: false,
        cmdLabel: `node ${CLAUDE_CLI_JS}`,
      };
    }
    if (process.platform === 'win32' && existsSync(CLAUDE_EXE_WIN)) {
      return {
        cmd: CLAUDE_EXE_WIN,
        useShell: false,
        cmdLabel: 'claude.exe',
      };
    }
    return {
      cmd: CLAUDE_BIN,
      useShell: process.platform === 'win32',
      cmdLabel: CLAUDE_BIN,
    };
  }

  /**
   * Build CLI arguments for claude -p.
   *
   * @param {object} config
   * @param {string} config.prompt - full prompt text
   * @param {string} config.systemPrompt - system prompt
   * @param {string} config.model - model ID (e.g. "claude-opus-4-6")
   * @param {number} config.maxTurns - max iterations
   * @param {string} config.mcpConfigPath - path to MCP JSON config
   * @returns {{args: string[], stdinPayload: null}} Claude takes the prompt via `-p`
   *   so it never needs stdin; we still return the unified shape for symmetry
   *   with the other adapters.
   */
  buildArgs(config) {
    const { prompt, systemPrompt, model, maxTurns, mcpConfigPath, restrictedToolset, mcpServerName } = config;

    const args = [
      '-p', prompt,
      '--output-format', 'stream-json',
      '--max-turns', String(maxTurns),
      '--verbose',
      '--strict-mcp-config',
      '--mcp-config', mcpConfigPath,
      // Auto-approve the AVAILABLE tools (so a headless `-p` run never hangs on a
      // permission prompt). This does NOT re-enable tools removed via --disallowedTools:
      // a disallowed tool is gone from the tool set entirely, not merely un-approved
      // (the 5 interactive tools below are proof - they stay blocked under skip-permissions).
      // In restricted mode the available set is the platform MCP tools only.
      '--dangerously-skip-permissions',
      '--no-session-persistence',
    ];

    // Pass the model id verbatim - claude CLI accepts both API ids
    // ("claude-opus-4-7") and family aliases ("opus").
    if (model) {
      args.push('--model', model);
    }

    // Run as a full Claude Code: ALL native tools (Bash, Read, Write, Edit, Glob,
    // Grep, WebFetch, WebSearch, TodoWrite, NotebookEdit, Task/sub-agents, …) are
    // enabled. What the agent can actually REACH is gated by the HOST, not by this
    // code: it can deploy / ssh / read server logs only if the operator provisioned
    // the creds on the bridge box (gh auth, SSH key, kubeconfig) - exactly like a
    // real Claude Code on a dev machine, where having Bash ≠ being able to deploy.
    // The per-deployment restriction lives in the systemd unit + which creds are
    // present, never here.
    //
    // The platform MCP tools (workflow/table/catalog/…) stay DIRECTLY callable
    // because ENABLE_TOOL_SEARCH=false (set in buildChildEnv) pins every tool loaded
    // upfront. That is the REAL fix for the 2026-06-04 regression - the earlier
    // theory ("disabling the native tools is what keeps ToolSearch off") was wrong:
    // tool-search AUTO mode defers tools once their combined definitions cross ~10%
    // of the context window, independent of whether the natives are on. The env
    // kill-switch removes deferral entirely, so natives + platform tools coexist,
    // all directly callable. (Verify in e2e before shipping - this is load-bearing.)
    //
    // Only genuinely interactive / session-only tools stay off: they cannot work in
    // a headless `-p` run (no user to answer, no plan-mode / worktree UX) and would
    // otherwise hang or no-op.
    if (restrictedToolset) {
      // CLOUD model-execution-link API mode: the routed agent must reach ONLY the platform
      // MCP tools (mcp__<server>__*). PRIMARY mechanism: --tools "" empties the entire
      // built-in tool SET (per claude-code's own --help: 'Use "" to disable all tools'), so
      // Bash/Read/Edit/Glob/Grep/WebFetch/Write are never loaded and cannot be reached in any
      // permission mode. MCP tools are unaffected (--tools only scopes the built-in set) and
      // stay auto-approved under --dangerously-skip-permissions, so headless runs do not hang.
      //
      // WHY NOT --disallowedTools ALONE: it proved INSUFFICIENT on claude-code 2.1.x - a
      // routed agent still ran Bash ('git status') in prod 2026-06-26 despite the deny list
      // and an empty cwd. A bare-name deny rule did not actually remove the tool from the set
      // here. We KEEP the explicit --disallowedTools list below as defence-in-depth, but
      // --tools "" is the load-bearing fix. (The run also uses an empty cwd, see server.mjs.)
      args.push('--tools', '');
      const nativeTools = [
        'Bash', 'BashOutput', 'KillShell', 'KillBash',
        'Read', 'Write', 'Edit', 'MultiEdit', 'NotebookEdit',
        'Glob', 'Grep', 'WebFetch', 'WebSearch',
        'TodoWrite', 'Task', 'SlashCommand', 'SendUserMessage',
        'AskUserQuestion', 'EnterPlanMode', 'ExitPlanMode', 'EnterWorktree', 'ExitWorktree',
      ];
      args.push('--disallowedTools', ...nativeTools);
    } else {
      // Full-freedom (default direct claude-code): all native tools on; only block the
      // interactive/session tools that cannot work headless.
      const disallowedTools = [
        'AskUserQuestion',
        'EnterPlanMode', 'ExitPlanMode',
        'EnterWorktree', 'ExitWorktree',
      ];
      args.push('--disallowedTools', ...disallowedTools);
    }

    if (systemPrompt) {
      args.push('--system-prompt', systemPrompt);
    }

    // Prepend CLAUDE_CLI_JS if using direct node invocation
    const finalArgs = CLAUDE_CLI_JS ? [CLAUDE_CLI_JS, ...args] : args;
    return { args: finalArgs, stdinPayload: null };
  }

  /**
   * Write Claude MCP config file (JSON format).
   *
   * @param {string} tmpDir - temp directory path
   * @param {object} mcpServerConfig - { serverName, command, args, env }
   * @returns {string} path to the config file
   */
  writeMcpConfig(tmpDir, mcpServerConfig) {
    const configPath = resolve(tmpDir, 'mcp.json');
    const config = {
      mcpServers: {
        [mcpServerConfig.serverName]: {
          command: mcpServerConfig.command,
          args: mcpServerConfig.args,
          env: mcpServerConfig.env,
          // Belt-and-suspenders with ENABLE_TOOL_SEARCH=false (buildChildEnv): pin
          // this server's tools (the platform workflow/table/catalog/… tools) to
          // load directly, never deferred, no matter how many native tools are on.
          alwaysLoad: true,
        },
      },
    };
    writeFileSync(configPath, JSON.stringify(config));
    return configPath;
  }

  /**
   * Build extra env overrides for the child process.
   * Claude Code needs CLAUDECODE and CLAUDE_CODE_ENTRYPOINT removed
   * to allow spawning from within a Claude Code session.
   *
   * Reasoning effort: Claude Code has no categorical effort flag, so the level
   * is mapped to an extended-thinking token budget exported via the env var the
   * CLI reads (see {@code claudeReasoningEnv}). Unset/unknown level → no env
   * change → CLI default. The env-var name is verified at e2e and swapped in one
   * place ({@code lib/reasoningEffort.mjs}) if the installed CLI differs.
   */
  buildChildEnv(_tmpDir, reasoningEffort) {
    // Dropped from the child's environment:
    //   CLAUDECODE / CLAUDE_CODE_ENTRYPOINT - let claude spawn from within a session.
    //   REDIS_URL - carries the bridge's Redis PASSWORD and the child never needs it
    //     (the MCP subprocess gets its own explicit env dict; claude + agent-cli talk
    //     HTTP, not Redis). With native Read/Bash enabled the agent could otherwise
    //     echo it from /proc/self/environ; stripping it keeps the bridge's OWN infra
    //     secret out of the agent context. This is hygiene on the bridge's internal
    //     secret, NOT a capability limit - provider keys the agent legitimately needs
    //     are left in place, and creds the operator provisions for deploy/ssh live in
    //     files (gh config, ~/.ssh, kubeconfig), not in this stripped var.
    const STRIP = new Set(['CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT', 'REDIS_URL']);
    const base = Object.fromEntries(
      Object.entries(process.env).filter(([k]) => !STRIP.has(k))
    );
    return {
      ...base,
      // Pin EVERY tool (native + platform MCP) loaded directly - never deferred
      // behind ToolSearch. With the native tools now enabled, tool-search AUTO mode
      // would otherwise defer tools once their combined definitions exceed ~10% of
      // the context window, which is exactly what made the platform tools uncallable
      // in the 2026-06-04 regression. `false` keeps them all in context, side by side.
      ENABLE_TOOL_SEARCH: 'false',
      // We spawn claude with --dangerously-skip-permissions, which the CLI REFUSES
      // under root ("cannot be used with root/sudo privileges for security reasons").
      // A containerized CE runs the bridge as root (uid 0), so without this the
      // claude-code models never start (CLI exits code 1 before any turn). IS_SANDBOX=1
      // is claude-code's supported opt-in for "running in a controlled sandbox, allow
      // skip-permissions as root" - exactly this case. Scoped to root so the non-root
      // (prod, user-space) bridge is unchanged. getuid is undefined on Windows -> skipped.
      ...(process.getuid?.() === 0 ? { IS_SANDBOX: '1' } : {}),
      ...claudeReasoningEnv(reasoningEffort),
    };
  }

  /**
   * Handle a single NDJSON message from the Claude CLI.
   * Maps CLI stream-json events to the shared context.
   *
   * @param {object} msg - parsed NDJSON line
   * @param {object} ctx - shared execution context
   */
  async handleMessage(msg, ctx) {
    const { publisher, thinkingSections, orderedEntries } = ctx;
    const adapterState = ctx.adapterState;
    const { seenToolUseIds, seenAssistantUsageMsgIds } = adapterState;

    switch (msg.type) {
      case 'assistant': {
        const contentBlocks = msg.message?.content || [];
        const stopReason = msg.message?.stop_reason;
        // Tail of msg id helps confirm "snapshot of one API call" vs "new API
        // call" when reading prod logs - same suffix across consecutive events
        // = one API call streamed as multiple snapshots; different suffix = a
        // distinct API call (e.g. extended-thinking pause/resume).
        const msgIdTail = msg.message?.id ? msg.message.id.slice(-8) : '?';
        console.log(`[BRIDGE:claude:assistant] id=${msgIdTail} ${contentBlocks.length} blocks: ${contentBlocks.map(b => b.type).join(', ')}${stopReason ? ` (stop=${stopReason})` : ''}`);

        // Extract actual model from assistant message
        if (msg.message?.model) {
          ctx.updateState({ cliModel: msg.message.model });
        }

        // Per-API-call usage tracking. Two duplication risks:
        //
        //   1. Snapshot re-emission within ONE API call: Claude CLI in
        //      `stream-json --verbose` emits one `assistant` event per
        //      `content_block_stop`, all with the SAME `msg.message.id`.
        //      → caught by `seenAssistantUsageMsgIds`.
        //
        //   2. Extended-thinking pause/resume across API calls: Opus 4.x
        //      emits a provisional response (`stop_reason=pause_turn`) then
        //      an auto-resumed response with a NEW `msg.message.id`. Both
        //      replay the same input context - Anthropic bills both, so they
        //      are real API calls and we count both. The continuation marker
        //      below tells log readers it's normal extended-thinking behavior,
        //      not a bridge bug.
        //
        // Budget guards read `ctx.state.usage` mid-run, so we MUST update it
        // here too. The `result` event (handled below) carries Anthropic's
        // final canonical total and overrides via Math.max.
        const msgId = msg.message?.id;
        if (msg.message?.usage && (!msgId || !seenAssistantUsageMsgIds.has(msgId))) {
          if (msgId) seenAssistantUsageMsgIds.add(msgId);
          const u = msg.message.usage;
          const turnInput = getTotalInputTokens(u);
          const turnOutput = u.output_tokens || 0;

          const calls = ctx.state.perCallUsages;
          const prevCall = calls.length > 0 ? calls[calls.length - 1] : null;
          const finishReasons = ctx.state.finishReasons;
          const prevStopReason = finishReasons.length > 0 ? finishReasons[finishReasons.length - 1] : null;
          const isContinuation = isExtendedThinkingContinuation(prevCall, turnInput, prevStopReason);

          const { totalInput, totalOutput, callIndex } = recordCallUsage(ctx, {
            promptTokens: turnInput,
            completionTokens: turnOutput,
            cacheCreationInputTokens: u.cache_creation_input_tokens || 0,
            cacheReadInputTokens: u.cache_read_input_tokens || 0,
            reasoningTokens: u.reasoning_tokens || 0,
          });
          const cont = isContinuation ? ' (continuation of extended thinking)' : '';
          console.log(`[BRIDGE:claude:usage] API call #${callIndex}${cont}: input=${turnInput}, output=${turnOutput} | total: prompt=${totalInput}, completion=${totalOutput}`);
        }

        // Track finish reason per iteration (used by isExtendedThinkingContinuation
        // on the *next* assistant event - must be appended AFTER the continuation
        // check above so prevStopReason refers to the prior turn).
        if (msg.message?.stop_reason) {
          ctx.state.finishReasons.push(msg.message.stop_reason);
        }

        for (const block of contentBlocks) {
          if (block.type === 'text' && block.text) {
            if (!adapterState.streamedContentViaDeltas) {
              await publisher.publishContent(block.text);
            }
            ctx.updateState({ fullContent: ctx.getContent() + block.text });
          }

          if (block.type === 'tool_use') {
            // Skip provisional tool_use from extended-thinking pause_turn
            // responses - the auto-resumed response will re-emit the FINAL
            // tool_use with a fresh id. Per-block-id dedup cannot catch this
            // case because the ids genuinely differ; only stop_reason can.
            if (stopReason === STOP_REASON_PAUSE_TURN) {
              continue;
            }
            // Snapshot dedup: each `assistant` snapshot in `stream-json --verbose`
            // re-iterates all tool_use blocks seen so far. The Anthropic
            // `block.id` (`toolu_…`) is stable across snapshots → perfect key.
            if (block.id) {
              if (seenToolUseIds.has(block.id)) continue;
              seenToolUseIds.add(block.id);
            } else {
              console.warn(`[BRIDGE:claude] tool_use without id: ${block.name} - dedup bypassed, may double-execute`);
            }

            const rawName = block.name;
            let toolName = ctx.stripMcpPrefix(rawName);
            const argsStr = JSON.stringify(block.input || {});

            // Detect Read calls on attachment files → relabel as view_attachment.
            let attachmentFileName = null;
            if (toolName === 'Read' && block.input?.file_path && adapterState.attachmentPathToName) {
              const filePath = block.input.file_path.replace(/\\/g, '/');
              for (const [attPath, attName] of adapterState.attachmentPathToName) {
                if (filePath === attPath.replace(/\\/g, '/')) {
                  attachmentFileName = attName;
                  toolName = 'view_attachment';
                  break;
                }
              }
            }

            await dispatchToolCall(ctx, {
              toolId: block.id,
              toolName,
              argsStr,
              extras: { attachmentFileName },
            });
          }

          if (block.type === 'thinking' && block.thinking) {
            if (!adapterState.streamedThinkingViaDeltas) {
              await publisher.publishThinking(block.thinking);
            }
            thinkingSections.push({ title: '', content: block.thinking });
            orderedEntries.push({
              type: 'thinking',
              title: '',
              content: block.thinking,
              timestamp: Date.now(),
            });
          }
        }

        incrementTurn(ctx);
        break;
      }

      case 'user': {
        const userContent = msg.message?.content;
        if (!Array.isArray(userContent)) break;

        for (const block of userContent) {
          if (block.type === 'tool_result') {
            const isError = block.is_error === true;
            const { content: resultContent, metadata } = ctx.extractToolResultAndMetadata(block.content);
            await dispatchToolResult(ctx, {
              toolId: block.tool_use_id,
              isError,
              content: resultContent,
              metadata,
            });
          }
        }
        break;
      }

      case 'stream_event': {
        const event = msg.event;
        if (!event) break;

        if (event.type === 'content_block_delta') {
          if (event.delta?.type === 'text_delta' && event.delta.text) {
            adapterState.streamedContentViaDeltas = true;
            await publisher.publishContent(event.delta.text);
          }
          if (event.delta?.type === 'thinking_delta' && event.delta.thinking) {
            adapterState.streamedThinkingViaDeltas = true;
            await publisher.publishThinking(event.delta.thinking);
          }
        }
        break;
      }

      case 'result': {
        console.log(`[BRIDGE:claude:result] subtype=${msg.subtype}, cost=${msg.cost_usd ?? 'N/A'}, turns=${msg.num_turns ?? 'N/A'}`);
        // Delegate to the shared stopReason mapper so this adapter emits the same
        // canonical AgentStopReason values as the Java backend (COMPLETED,
        // MAX_ITERATIONS, BUDGET_EXHAUSTED, LOOP_DETECTED, ...) instead of just
        // collapsing every non-success result to ERROR.
        const mapped = applyResultMapping('claude', msg, ctx);
        if (mapped.success && !ctx.getContent() && msg.result) {
          ctx.updateState({ fullContent: msg.result });
        }

        if (msg.usage) {
          const resultInput = getTotalInputTokens(msg.usage);
          const resultOutput = msg.usage.output_tokens || 0;
          const prev = ctx.state.usage || { promptTokens: 0, completionTokens: 0 };
          ctx.updateState({
            usage: {
              promptTokens: Math.max(prev.promptTokens, resultInput),
              completionTokens: Math.max(prev.completionTokens, resultOutput),
            },
          });
        }
        if (msg.num_turns) {
          ctx.updateState({ numTurns: msg.num_turns });
        }
        if (msg.model) {
          ctx.updateState({ cliModel: msg.model });
        }
        break;
      }

      case 'system': {
        const tools = msg.tools || [];
        const mcpServers = (msg.mcp_servers || []).filter(s => s.status === 'connected');
        console.log(`[BRIDGE:claude] Init: ${tools.length} tools, ${mcpServers.length} MCP connected`);
        if (msg.model) {
          ctx.updateState({ cliModel: msg.model });
          console.log(`[BRIDGE:claude] CLI model: ${msg.model}`);
        }
        break;
      }

      case 'error': {
        // Mirror codex/gemini/mistral: route through the shared mapper so the
        // canonical AgentStopReason is emitted instead of falling silently
        // into the default branch.
        const errMsg = msg.message || msg.error || 'Unknown Claude error';
        console.error(`[BRIDGE:claude:error] ${errMsg}`);
        applyResultMapping('claude', { subtype: 'error', error: errMsg }, ctx);
        break;
      }

      default:
        console.log(`[BRIDGE:claude:msg] type=${msg.type} subtype=${msg.subtype || '-'}`);
        break;
    }
  }
}
