// GENERATED FILE - do not edit by hand.
// Source of truth: shared/contracts/reasoning-effort.json
// Re-run: node shared/contracts/scripts/generate-reasoning-effort.js
//
// Reasoning-effort → per-CLI knob mapping for bridge providers. The backend
// resolves a single canonical level and sends it on the request DTO; each CLI
// exposes the knob differently, so the translation lives here. Degrade-safe: an
// unset/blank/unknown level yields NO flag/env → the CLI uses its own default.

/** Canonical levels, low→high. */
export const EFFORT_LEVELS = ["minimal","low","medium","high","xhigh","max"];

/** Canonical levels accepted only on codex-max model variants (clamped down elsewhere). */
const CODEX_MAX_ONLY = new Set(['xhigh', 'max']);
const CODEX_MAX_PATTERN = /codex-max/i;
const CODEX_CLAMP_TARGET = 'high';

/** Codex CLI wire value per canonical level (codex has no `max`: it maps to xhigh). */
const CODEX_EFFORT = Object.freeze({
  minimal: 'minimal',
  low: 'low',
  medium: 'medium',
  high: 'high',
  xhigh: 'xhigh',
  max: 'xhigh',
});

/**
 * Claude Code categorical effort per canonical level (env: CLAUDE_CODE_EFFORT_LEVEL).
 * The CLI accepts low|medium|high|xhigh|max - `minimal` clamps to `low`.
 */
const CLAUDE_CODE_EFFORT = Object.freeze({
  minimal: 'low',
  low: 'low',
  medium: 'medium',
  high: 'high',
  xhigh: 'xhigh',
  max: 'max',
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
 * Codex CLI: `codex exec -c model_reasoning_effort=<level>`. Each canonical
 * level maps to the codex wire value first (codex has no `max`, so it rides as
 * xhigh); a max-only level (xhigh, max) then clamps to the highest non-max wire
 * value on non-codex-max models so the CLI doesn't reject the run. Returns the
 * argv fragment, or [] when nothing to set.
 *
 * The value is emitted BARE (no inner quotes): codex parses a `-c` value as JSON
 * and falls back to a raw string when JSON parsing fails, so a whitelisted bare
 * word like `high` becomes the string "high". Bare is also shell-robust - on the
 * Windows `shell:true` spawn path cmd.exe would strip inner double-quotes, making
 * the quoted form arrive differently than on Linux (`shell:false`); bare is
 * identical on both. `effective` is always one of the whitelisted wire values,
 * so there is no injection surface.
 */
export function codexReasoningArgs(level, model) {
  const v = normalizeEffort(level);
  if (!v) return [];
  let effective = CODEX_EFFORT[v];
  if (CODEX_MAX_ONLY.has(v) && !CODEX_MAX_PATTERN.test(model || '')) {
    effective = CODEX_CLAMP_TARGET;
  }
  return ['-c', `model_reasoning_effort=${effective}`];
}

/**
 * Claude Code: map the level to the categorical CLAUDE_CODE_EFFORT_LEVEL env
 * value, returned as an env patch the adapter merges. Unknown level → {} (no env
 * change → CLI default: high on Fable 5/Opus 4.8/Sonnet 5, xhigh on Opus 4.7).
 *
 * Why categorical and not MAX_THINKING_TOKENS: every current adaptive-reasoning
 * model (Fable 5, Sonnet 5, Opus 4.7+) ignores the fixed thinking budget - and
 * the 4.6 family only honors it under CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING=1 -
 * so the old budget env was a silent no-op (code.claude.com/docs/en/model-config).
 * CLAUDE_CODE_EFFORT_LEVEL takes precedence over the CLI's configured
 * effortLevel setting and accepts `max` (session-scoped only elsewhere). On a
 * model that lacks a level (e.g. xhigh on Opus 4.6), the CLI itself falls back
 * to the highest supported level at or below it. Verified against the installed
 * CLI at e2e; if the env name changes, this is the single swap point.
 */
export function claudeReasoningEnv(level) {
  const v = normalizeEffort(level);
  if (!v) return {};
  return { CLAUDE_CODE_EFFORT_LEVEL: CLAUDE_CODE_EFFORT[v] };
}
