/**
 * The model families named in the public footer.
 *
 * <p>The footer described every other side of the product (workflows, agents, tables,
 * integrations, marketplace) and said nothing about the models it runs on, which is one of
 * the first things a visitor wants to know about an AI platform. This is that column.
 *
 * <p><strong>Families, not model ids.</strong> The catalogue carries ~275 models and moves
 * every week (see `model-catalog/models.json`); pinning "claude-3-7-sonnet-20250219" in the
 * footer of every public page would be a name to maintain and a claim to re-check. A family
 * is stable: the provider is what a visitor recognises, and what they actually choose in
 * the app.
 *
 * <p><strong>Each entry is checked against the catalogue seed</strong>
 * (`wellKnownModels.test.ts`): the provider must exist there with at least one enabled
 * model. So the footer can never advertise a provider the platform does not run, which is
 * the same discipline the integrations fallback follows.
 *
 * <p>All entries point at the models documentation. There is no per-model public page today
 * (unlike `/integrations/{slug}`), and inventing one URL per family would be one soft
 * 404s; the docs page is where BYOK keys, the CLI bridges and the catalogue are explained.
 */
export interface WellKnownModel {
  /** Family name as a visitor knows it. */
  label: string;
  /** Provider key in the model catalogue, i.e. what the test verifies against. */
  provider: string;
}

/**
 * Only the families the platform actually runs. There were eight (Grok, Mistral, Qwen and Kimi
 * too) until 2026-09-25, when the catalogue stopped carrying every model that is not enabled:
 * those four providers left the seed, and advertising them would name models nobody can select.
 */
export const WELL_KNOWN_MODELS: readonly WellKnownModel[] = [
  { label: 'Claude', provider: 'anthropic' },
  { label: 'GPT', provider: 'openai' },
  { label: 'Gemini', provider: 'google' },
  { label: 'DeepSeek', provider: 'deepseek' },
];
