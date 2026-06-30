/**
 * Single source of truth for AI provider → icon slug mapping.
 *
 * Used by workflow builder (ClassifyNode / GuardrailNode / shared / inspector forms /
 * AgentConfigurationPanel), chat (ModelSelectorDropdown, ChatPageV2, CreateAgentModal,
 * AgentSnapshotPanel), agent fleet (FleetInspectorPanel, hooks), settings
 * (ModelManagementPanel, AddModelDialog) and AppHeader / panel contents.
 *
 * When a new provider ships in the backend live catalog (`ModelCatalogService`),
 * add it here - and every icon in the app updates at once.
 */
export const PROVIDER_ICON_MAP: Record<string, string> = {
  openai: 'openai',
  anthropic: 'anthropic',
  google: 'googlegemini',
  mistral: 'mistral',
  deepseek: 'deepseek',
  meta: 'meta',
  xai: 'xai',
  perplexity: 'perplexity',
  cohere: 'cohere',
  openrouter: 'openrouter',
  zai: 'zai',
  'claude-code': 'claude-code',
  codex: 'codex',
  'gemini-cli': 'gemini-cli',
  'mistral-vibe': 'mistral-vibe',
};

/**
 * Build a full /icons/services/<slug>.svg path for a provider, or null when the
 * provider is missing. Prefer this helper over PROVIDER_ICON_MAP[x] + string
 * concatenation in consumers so the path convention stays in one place.
 */
export function getProviderIconSrc(provider: string | null | undefined): string | null {
  const slug = getProviderIconSlug(provider);
  return slug ? `/icons/services/${slug}.svg` : null;
}

/**
 * Resolve a provider to its icon slug. Falls back to the lowercased provider name -
 * icons are stored under /icons/services/<slug>.svg and many providers are named
 * after their slug, so this produces a working icon for providers not yet in the map.
 */
export function getProviderIconSlug(provider: string | null | undefined): string | undefined {
  if (!provider) return undefined;
  const normalized = provider.toLowerCase();
  return PROVIDER_ICON_MAP[normalized] ?? normalized;
}

/**
 * Single source of truth for AI provider → human-readable label. Used by chat,
 * agent fleet, workflow inspector forms, settings and any selector that shows a
 * provider to the end user. Extend in lockstep with PROVIDER_ICON_MAP.
 */
export const PROVIDER_DISPLAY_NAMES: Record<string, string> = {
  openai: 'OpenAI',
  anthropic: 'Anthropic',
  google: 'Google',
  mistral: 'Mistral',
  deepseek: 'DeepSeek',
  meta: 'Meta',
  xai: 'xAI',
  perplexity: 'Perplexity',
  cohere: 'Cohere',
  openrouter: 'OpenRouter',
  zai: 'z.ai',
  'claude-code': 'Claude Code',
  codex: 'Codex',
  'gemini-cli': 'Gemini CLI',
  'mistral-vibe': 'Mistral Vibe',
};

/**
 * Resolve a provider key to its human label, falling back to the raw key when
 * unknown (so new providers coming from the live catalog still render readably).
 */
export function getProviderDisplayName(provider: string | null | undefined): string {
  if (!provider) return '';
  return PROVIDER_DISPLAY_NAMES[provider] ?? provider;
}
