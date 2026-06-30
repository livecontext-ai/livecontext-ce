// GENERATED FILE - do not edit by hand.
// Source of truth: shared/contracts/reasoning-effort.json
// Re-run: node shared/contracts/scripts/generate-reasoning-effort.js
//
// Shared reasoning-effort constants/helpers for the three UI surfaces (per-model
// admin default, per-conversation chat selector, per-agent setting). The effort
// knob only applies to bridge/CLI providers; others ignore it.

/** Canonical levels, low→high, matching the backend enum's wire form. */
export const REASONING_EFFORT_LEVELS = ['minimal', 'low', 'medium', 'high', 'xhigh'] as const;
export type ReasoningEffortLevel = (typeof REASONING_EFFORT_LEVELS)[number];

/** All CLI-backed (bridge) providers. */
export const BRIDGE_PROVIDERS = ["claude-code","codex","gemini-cli","mistral-vibe"];

/**
 * Bridge providers whose adapter actually maps an effort level today. The other
 * bridges ignore the value, so we must NOT advertise the control for them.
 */
export const EFFORT_PROVIDERS = ["claude-code","codex"];

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
