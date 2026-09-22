'use client';

import { useEffect, useRef } from 'react';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import { selectedModelFromAIModel, type AIModel, type SelectedModel } from '@/hooks/useModels';

/**
 * The model a free-tier account should open on, given the catalogue's own choice.
 *
 * <p>Pure, and shared by the two shapes this decision takes: the chat page primes an
 * empty selection through {@link usePreferFreeTierModel}, while the side panels
 * already resolve a default of their own and only need it steered. Keeping one
 * function means the three surfaces cannot drift into three answers.
 *
 * <p>Returns {@code candidate} untouched unless the account prefers free-tier models
 * AND the candidate is not one AND a covered model exists - so a paid plan, CE, and a
 * catalogue with nothing opened all behave exactly as before.
 */
export function resolveFreeTierPreferredModel(
  models: AIModel[],
  candidate: AIModel | undefined,
  prefersFreeTierModels: boolean,
): AIModel | undefined {
  if (!prefersFreeTierModels) return candidate;
  if (candidate?.freeTierEnabled === true) return candidate;
  return models.find((m) => m.freeTierEnabled === true) ?? candidate;
}

/**
 * Open a fresh chat on a model the account can actually pay for (V494).
 *
 * <p><b>The problem.</b> The composer's selection starts empty and is only filled
 * by the reader's own choice or by their last session. With nothing selected, the
 * turn is sent against the server's catalogue default, which is the admin's global
 * #1 - the right answer for an account with a wallet, and possibly a model the Free
 * plan's AI allowance does not cover. A visitor who signs up, opens chat and sends
 * one message would then be refused on that very first turn, which is precisely the
 * moment the allowance exists to serve.
 *
 * <p><b>Why prime the selection rather than change the server default.</b> The
 * catalogue default is deliberately the admin's ranking and is shared by every
 * account; making it plan-aware would mean resolving the reader's plan inside the
 * model catalogue, on a hot path, for a preference that is purely presentational.
 * This sets the same thing the reader would have set themselves.
 *
 * <p><b>It primes once and never argues.</b> A choice already present - restored
 * from the previous session or made in this one - is left exactly as it is, and the
 * ref makes sure a later clear is not re-primed on top of a deliberate action. On a
 * paid plan, on CE, or when no model is open to the free tier, it does nothing at all.
 */
export function usePreferFreeTierModel({
  models,
  selectedModel,
  setSelectedModel,
}: {
  models: AIModel[];
  selectedModel: SelectedModel;
  setSelectedModel: (model: SelectedModel) => void;
}): void {
  const { prefersFreeTierModels } = useMonthlyCreditsCannotPay();
  const primed = useRef(false);

  useEffect(() => {
    if (primed.current) return;
    if (!prefersFreeTierModels) return;
    // Anything already chosen wins, including a stale choice: correcting it would
    // silently move a reader off the model they picked.
    if (selectedModel?.id) return;
    const covered = models.find((m) => m.freeTierEnabled === true);
    if (!covered) return;

    primed.current = true;
    setSelectedModel(selectedModelFromAIModel(covered));
  }, [prefersFreeTierModels, selectedModel, models, setSelectedModel]);
}

export default usePreferFreeTierModel;
