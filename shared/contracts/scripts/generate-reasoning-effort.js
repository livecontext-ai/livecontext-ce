#!/usr/bin/env node

/**
 * Generates language-specific bindings for the reasoning-effort contract.
 *
 * Reads:  shared/contracts/reasoning-effort.json   (source of truth)
 * Writes:
 *   - backend/agent-common/src/main/java/com/apimarketplace/agent/domain/ReasoningEffort.java
 *   - mcp/bridge/lib/reasoningEffort.mjs
 *   - frontend/lib/ai-providers/reasoningEffort.ts
 *
 * NOTE: ReasoningEffortResolver.java (precedence logic) is hand-written and NOT
 * generated - it composes ReasoningEffort but has no level data of its own.
 *
 * Usage:  node shared/contracts/scripts/generate-reasoning-effort.js
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const CONTRACT_PATH = path.join(ROOT, 'shared', 'contracts', 'reasoning-effort.json');

const JAVA_OUT = path.join(ROOT, 'backend', 'agent-common', 'src', 'main', 'java',
  'com', 'apimarketplace', 'agent', 'domain', 'ReasoningEffort.java');
const JS_OUT = path.join(ROOT, 'mcp', 'bridge', 'lib', 'reasoningEffort.mjs');
const TS_OUT = path.join(ROOT, 'frontend', 'lib', 'ai-providers', 'reasoningEffort.ts');

function load() {
  return JSON.parse(fs.readFileSync(CONTRACT_PATH, 'utf-8'));
}

function ensureDir(filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

// Derived views shared by all three emitters.
function derive(schema) {
  const levelNames = schema.levels.map((l) => l.name);
  const enumNames = levelNames.map((n) => n.toUpperCase());
  const codexMaxOnly = schema.levels.filter((l) => l.codexMaxOnly).map((l) => l.name);
  // A codex-max-only level (e.g. xhigh) clamps down to the highest level that is
  // NOT max-only, on non-codex-max models.
  const nonMaxOnly = schema.levels.filter((l) => !l.codexMaxOnly).map((l) => l.name);
  const clampTarget = nonMaxOnly[nonMaxOnly.length - 1];
  const claudeBudget = {};
  for (const l of schema.levels) claudeBudget[l.name] = String(l.claudeThinkingBudget);
  return { levelNames, enumNames, codexMaxOnly, clampTarget, claudeBudget };
}

const GEN_NOTE_JS = [
  '// GENERATED FILE - do not edit by hand.',
  '// Source of truth: shared/contracts/reasoning-effort.json',
  '// Re-run: node shared/contracts/scripts/generate-reasoning-effort.js',
];

// ─── Java ────────────────────────────────────────────────────────────────────

function emitJava(schema, d) {
  const constants = d.enumNames.map((n, i) =>
    `    ${n}${i === d.enumNames.length - 1 ? ';' : ','}`).join('\n');
  return `package com.apimarketplace.agent.domain;

import java.util.Locale;

/**
 * Categorical reasoning-effort intent for CLI-backed (bridge) providers.
 *
 * <p><strong>GENERATED FILE - do not edit by hand.</strong> Source of truth:
 * {@code shared/contracts/reasoning-effort.json}. Re-run
 * {@code node shared/contracts/scripts/generate-reasoning-effort.js} after editing the JSON.
 *
 * <p>Mapped to each CLI's concrete flag at the bridge adapter leaf (e.g. Codex
 * {@code -c model_reasoning_effort="<level>"}); the canonical {@link #wire()}
 * value is the lowercase string the CLIs expect. Unknown/unsupported levels are
 * dropped at the adapter so the CLI falls back to its own default. Precedence
 * (per-conversation override > per-agent > per-model default) is handled by the
 * hand-written {@code ReasoningEffortResolver}.
 */
public enum ReasoningEffort {
${constants}

    /**
     * Tolerant parse: trims, upper-cases, and matches an enum constant. Returns
     * {@code null} for {@code null}, blank, or unrecognized input - callers
     * decide what null means (validation rejects it; the resolver skips it).
     */
    public static ReasoningEffort fromString(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        for (ReasoningEffort level : values()) {
            if (level.name().equals(normalized)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Whether {@code raw} parses to a known level. {@code null}/blank is treated
     * as "valid" here (it means "inherit / no override"); only a non-blank value
     * that fails to parse is invalid. Use at API/entity write boundaries.
     */
    public static boolean isValidOrBlank(String raw) {
        return raw == null || raw.trim().isEmpty() || fromString(raw) != null;
    }

    /** Canonical lowercase wire value the CLIs expect: {@code "minimal"}, {@code "high"}, … */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
`;
}

// ─── JS (bridge) ─────────────────────────────────────────────────────────────

function emitJs(schema, d) {
  const budgetLines = d.levelNames
    .map((n) => `  ${n}: '${d.claudeBudget[n]}',`).join('\n');
  const codexMaxOnlySet = d.codexMaxOnly.map((n) => `'${n}'`).join(', ');
  return `${GEN_NOTE_JS.join('\n')}
//
// Reasoning-effort → per-CLI knob mapping for bridge providers. The backend
// resolves a single canonical level and sends it on the request DTO; each CLI
// exposes the knob differently, so the translation lives here. Degrade-safe: an
// unset/blank/unknown level yields NO flag/env → the CLI uses its own default.

/** Canonical levels, low→high. */
export const EFFORT_LEVELS = ${JSON.stringify(d.levelNames)};

/** Codex levels accepted only on codex-max model variants (clamped down elsewhere). */
const CODEX_MAX_ONLY = new Set([${codexMaxOnlySet}]);
const CODEX_MAX_PATTERN = /${schema.codexMaxModelPattern}/i;
const CODEX_CLAMP_TARGET = '${d.clampTarget}';

/** Claude Code extended-thinking token budget per level (env: MAX_THINKING_TOKENS). */
const CLAUDE_THINKING_BUDGET = Object.freeze({
${budgetLines}
});

/**
 * Normalize an incoming level to its canonical lowercase form, or null when
 * absent/blank/unrecognized (→ caller emits nothing → CLI default).
 */
export function normalizeEffort(level) {
  if (!level || typeof level !== 'string') return null;
  const v = level.trim().toLowerCase();
  return EFFORT_LEVELS.includes(v) ? v : null;
}

/**
 * Codex CLI: \`codex exec -c model_reasoning_effort=<level>\`. A max-only level
 * (e.g. xhigh) clamps to the highest non-max level on non-codex-max models so the
 * CLI doesn't reject the run. Returns the argv fragment, or [] when nothing to set.
 *
 * The value is emitted BARE (no inner quotes): codex parses a \`-c\` value as JSON
 * and falls back to a raw string when JSON parsing fails, so a whitelisted bare
 * word like \`high\` becomes the string "high". Bare is also shell-robust - on the
 * Windows \`shell:true\` spawn path cmd.exe would strip inner double-quotes, making
 * the quoted form arrive differently than on Linux (\`shell:false\`); bare is
 * identical on both. \`effective\` is always one of the whitelisted levels, so
 * there is no injection surface.
 */
export function codexReasoningArgs(level, model) {
  const v = normalizeEffort(level);
  if (!v) return [];
  let effective = v;
  if (CODEX_MAX_ONLY.has(v) && !CODEX_MAX_PATTERN.test(model || '')) {
    effective = CODEX_CLAMP_TARGET;
  }
  return ['-c', \`model_reasoning_effort=\${effective}\`];
}

/**
 * Claude Code: map the level to an extended-thinking token budget exported via
 * the MAX_THINKING_TOKENS env var, returned as an env patch the adapter merges.
 * Unknown level → {} (no env change → CLI default).
 *
 * CAVEAT: unlike codex's per-run flag, setting MAX_THINKING_TOKENS makes Claude
 * Code spend extended thinking on EVERY turn at this budget (no per-turn opt-out)
 * - a deliberate cost/latency tradeoff. We only set it when a level is explicitly
 * chosen, so the default (unset) keeps Claude's normal behavior. The exact env-var
 * name is verified against the installed CLI at e2e; if it changes, this is the
 * single swap point.
 */
export function claudeReasoningEnv(level) {
  const v = normalizeEffort(level);
  if (!v) return {};
  return { MAX_THINKING_TOKENS: CLAUDE_THINKING_BUDGET[v] };
}
`;
}

// ─── TS (frontend) ───────────────────────────────────────────────────────────

function emitTs(schema, d) {
  const levelsLiteral = d.levelNames.map((n) => `'${n}'`).join(', ');
  return `${GEN_NOTE_JS.join('\n')}
//
// Shared reasoning-effort constants/helpers for the three UI surfaces (per-model
// admin default, per-conversation chat selector, per-agent setting). The effort
// knob only applies to bridge/CLI providers; others ignore it.

/** Canonical levels, low→high, matching the backend enum's wire form. */
export const REASONING_EFFORT_LEVELS = [${levelsLiteral}] as const;
export type ReasoningEffortLevel = (typeof REASONING_EFFORT_LEVELS)[number];

/** All CLI-backed (bridge) providers. */
export const BRIDGE_PROVIDERS = ${JSON.stringify(schema.bridgeProviders)};

/**
 * Bridge providers whose adapter actually maps an effort level today. The other
 * bridges ignore the value, so we must NOT advertise the control for them.
 */
export const EFFORT_PROVIDERS = ${JSON.stringify(schema.effortProviders)};

export function isBridgeProvider(provider?: string | null): boolean {
  return !!provider && BRIDGE_PROVIDERS.includes(provider.toLowerCase());
}

/**
 * Whether to surface the effort control for a given model: only for the bridge
 * providers whose adapter honors it ({@link EFFORT_PROVIDERS}).
 */
export function supportsReasoningEffort(opts: {
  provider?: string | null;
  providerKind?: string | null;
}): boolean {
  return !!opts.provider && EFFORT_PROVIDERS.includes(opts.provider.toLowerCase());
}
`;
}

// ─── main ────────────────────────────────────────────────────────────────────

function main() {
  const schema = load();
  const d = derive(schema);

  ensureDir(JAVA_OUT);
  ensureDir(JS_OUT);
  ensureDir(TS_OUT);

  fs.writeFileSync(JAVA_OUT, emitJava(schema, d));
  fs.writeFileSync(JS_OUT, emitJs(schema, d));
  fs.writeFileSync(TS_OUT, emitTs(schema, d));

  console.log('[generate-reasoning-effort] wrote', JAVA_OUT);
  console.log('[generate-reasoning-effort] wrote', JS_OUT);
  console.log('[generate-reasoning-effort] wrote', TS_OUT);
}

// Exported so a drift-guard test can re-emit in-memory and diff against the
// committed files WITHOUT writing. Only writes to disk when run as a script.
module.exports = { load, derive, emitJava, emitJs, emitTs, JAVA_OUT, JS_OUT, TS_OUT };

if (require.main === module) {
  main();
}
