// GENERATED FILE - do not edit by hand.
// Source of truth: shared/contracts/reasoning-effort.json
// Re-run: node shared/contracts/scripts/generate-reasoning-effort.js
//
// Reasoning-effort → per-CLI knob mapping for bridge providers. The backend
// resolves a single canonical level and sends it on the request DTO; each CLI
// exposes the knob differently, so the translation lives here. Degrade-safe: an
// unset/blank/unknown level yields NO flag/env → the CLI uses its own default.

/** Canonical levels, low→high. */
export const EFFORT_LEVELS = ["minimal","low","medium","high","xhigh"];

/** Codex levels accepted only on codex-max model variants (clamped down elsewhere). */
const CODEX_MAX_ONLY = new Set(['xhigh']);
const CODEX_MAX_PATTERN = /codex-max/i;
const CODEX_CLAMP_TARGET = 'high';

/** Claude Code extended-thinking token budget per level (env: MAX_THINKING_TOKENS). */
const CLAUDE_THINKING_BUDGET = Object.freeze({
  minimal: '1024',
  low: '4096',
  medium: '8192',
  high: '16384',
  xhigh: '32000',
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
 * Codex CLI: `codex exec -c model_reasoning_effort=<level>`. A max-only level
 * (e.g. xhigh) clamps to the highest non-max level on non-codex-max models so the
 * CLI doesn't reject the run. Returns the argv fragment, or [] when nothing to set.
 *
 * The value is emitted BARE (no inner quotes): codex parses a `-c` value as JSON
 * and falls back to a raw string when JSON parsing fails, so a whitelisted bare
 * word like `high` becomes the string "high". Bare is also shell-robust - on the
 * Windows `shell:true` spawn path cmd.exe would strip inner double-quotes, making
 * the quoted form arrive differently than on Linux (`shell:false`); bare is
 * identical on both. `effective` is always one of the whitelisted levels, so
 * there is no injection surface.
 */
export function codexReasoningArgs(level, model) {
  const v = normalizeEffort(level);
  if (!v) return [];
  let effective = v;
  if (CODEX_MAX_ONLY.has(v) && !CODEX_MAX_PATTERN.test(model || '')) {
    effective = CODEX_CLAMP_TARGET;
  }
  return ['-c', `model_reasoning_effort=${effective}`];
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
