import type { AIModel } from '@/hooks/useModels';

/**
 * The model a free-tier account should open on, given the catalogue's own choice.
 *
 * <p>Pure, and shared by the two shapes this decision takes: `usePreferFreeTierModel`
 * steers the opening selection, while the side panels and the agent modal resolve a
 * fallback default of their own. Keeping one function means the surfaces cannot drift
 * into different answers.
 *
 * <p>On the Free plan the answer is the free tier's best-ranked model, even when the
 * catalogue default is itself covered but ranked lower. Otherwise, or when no model is
 * open to the free tier, {@code candidate} is returned untouched, so a paid plan, CE and
 * a catalogue with nothing opened behave exactly as before.
 */
export function resolveFreeTierPreferredModel(
  models: AIModel[],
  candidate: AIModel | undefined,
  prefersFreeTierModels: boolean,
): AIModel | undefined {
  if (!prefersFreeTierModels) return candidate;
  return bestFreeTierModel(models) ?? candidate;
}

/**
 * The best-ranked model opened to the free tier, or undefined when none is.
 *
 * <p>{@code models} arrives sorted by the admin's global ranking (lowest
 * {@code displayOrder} first), so the first covered model IS the free tier's rank #1.
 */
export function bestFreeTierModel(models: AIModel[]): AIModel | undefined {
  return models.find((m) => m.freeTierEnabled === true);
}
