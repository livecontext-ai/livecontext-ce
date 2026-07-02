// GENERATED FILE - do not edit by hand.
// Source of truth: shared/contracts/reasoning-effort.json
// Re-run: node shared/contracts/scripts/generate-reasoning-effort.js
//
// Shared reasoning-effort constants/helpers for the three UI surfaces (per-model
// admin default, per-conversation chat selector, per-agent setting). The effort
// knob applies to the providers in EFFORT_PROVIDERS (bridge CLIs + the direct
// Anthropic API); others ignore it.

/** Canonical levels, low→high, matching the backend enum's wire form. */
export const REASONING_EFFORT_LEVELS = ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'] as const;
export type ReasoningEffortLevel = (typeof REASONING_EFFORT_LEVELS)[number];

/** All CLI-backed (bridge) providers. */
export const BRIDGE_PROVIDERS = ["claude-code","codex","gemini-cli","mistral-vibe"];

/**
 * Providers that actually honor the effort level today: the bridge CLIs whose
 * adapter maps it (claude-code, codex) plus the direct Anthropic API
 * (ClaudeProvider → output_config.effort, clamped per model). gemini-cli and
 * mistral-vibe expose no usable knob, so we must NOT advertise the control
 * for them.
 */
export const EFFORT_PROVIDERS = ["claude-code","codex","anthropic"];

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
