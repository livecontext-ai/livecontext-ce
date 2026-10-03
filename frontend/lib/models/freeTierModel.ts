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

type FreeTierRankable = Pick<AIModel, 'freeTierEnabled' | 'freeTierRank' | 'displayOrder'>;

/**
 * The list as a Free account reads it: the models opened to the free tier first, in the
 * free tier's OWN ranking, then everything else exactly as it arrived.
 *
 * <p>The free tier has a ranking of its own (the admin's Free tier tab), carried per model
 * as {@code freeTierRank}. The ranked models come first, by that rank; a covered model the
 * admin never ranked there (one just opened) follows them, by its global
 * {@code displayOrder}. The two are never compared with each other: they are different
 * scales, and a model just opened at global #1 would otherwise tie with the free tier's
 * #1 and take the opening. The admin tab draws its list by the same rule. The sort is
 * stable: equal ranks keep the order the list arrived in.
 *
 * <p>The ONE place this order is computed. The chat composer, the model picker (its
 * provider order included, see {@link freeTierProvidersFirst}) and the opening model all
 * call it, so a Free account never meets two different "first" models.
 */
export function freeTierFirst<T extends FreeTierRankable>(models: T[]): T[] {
  const covered = models.filter((m) => m.freeTierEnabled === true);
  const ranked = covered.filter((m) => m.freeTierRank != null);
  const unranked = covered.filter((m) => m.freeTierRank == null);
  return [
    ...ranked.sort((a, b) => (a.freeTierRank as number) - (b.freeTierRank as number)),
    ...unranked.sort((a, b) => (a.displayOrder ?? 999) - (b.displayOrder ?? 999)),
    ...models.filter((m) => m.freeTierEnabled !== true),
  ];
}

/**
 * Providers as a Free account reads them in a two-level picker: those offering a covered
 * model first, in the order their best covered model holds in {@link freeTierFirst}, then
 * the rest exactly as they arrived.
 *
 * <p>Without this the provider of the free tier's #1 could sit behind another provider
 * whose covered model ranks lower, and the picker would lead with a different model than
 * the one the account opened on. An unlisted covered model does not make its provider
 * lead: nothing it OFFERS is free.
 */
export function freeTierProvidersFirst<
  P extends { models: (FreeTierRankable & { unlisted?: boolean })[] },
>(providers: P[]): P[] {
  const offered = providers.flatMap((p) =>
    p.models
      .filter((m) => m.freeTierEnabled === true && m.unlisted !== true)
      .map((m) => ({
        freeTierEnabled: true,
        freeTierRank: m.freeTierRank,
        displayOrder: m.displayOrder,
        owner: p,
      })),
  );
  const leading: P[] = [];
  for (const m of freeTierFirst(offered)) {
    if (!leading.includes(m.owner)) leading.push(m.owner);
  }
  return [...leading, ...providers.filter((p) => !leading.includes(p))];
}

/**
 * The best-ranked model opened to the free tier, or undefined when none is: the first
 * covered model of {@link freeTierFirst}, that is the free tier's own rank #1.
 */
export function bestFreeTierModel(models: AIModel[]): AIModel | undefined {
  return freeTierFirst(models).find((m) => m.freeTierEnabled === true);
}
