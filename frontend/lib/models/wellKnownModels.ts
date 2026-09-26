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
 * <p><strong>Each entry is checked against the /models page</strong>
 * (`wellKnownModels.test.ts`): the provider must have rows there, so every link lands on a
 * real filtered list. Deliberately NOT against what the cloud hosts: this column is an
 * informative index of model families, like /models itself, and switching a model off in
 * the admin panel must not take a family out of the footer (it did on 2026-09-25).
 *
 * <p>Each entry links to its own filtered view of /models (`providerHref`), the public
 * chronological list of the models; the docs are one click further.
 */
export interface WellKnownModel {
  /** Family name as a visitor knows it. */
  label: string;
  /** Provider key in the model catalogue, i.e. what the test verifies against. */
  provider: string;
}

/** Eight, the same width as the integrations column beside it. */
export const WELL_KNOWN_MODELS: readonly WellKnownModel[] = [
  { label: 'Claude', provider: 'anthropic' },
  { label: 'GPT', provider: 'openai' },
  { label: 'Gemini', provider: 'google' },
  { label: 'Grok', provider: 'xai' },
  { label: 'Mistral', provider: 'mistral' },
  { label: 'DeepSeek', provider: 'deepseek' },
  { label: 'Qwen', provider: 'qwen' },
  { label: 'Kimi', provider: 'moonshot' },
];
