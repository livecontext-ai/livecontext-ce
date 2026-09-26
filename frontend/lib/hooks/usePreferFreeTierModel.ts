'use client';

import { useEffect } from 'react';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import { useUnifiedAppSafe } from '@/contexts/UnifiedAppContext';
import { selectedModelEquals, selectedModelFromAIModel, type AIModel } from '@/hooks/useModels';
import { bestFreeTierModel } from '@/lib/models/freeTierModel';
import { track } from '@/lib/analytics/analytics';

// The pure decision lives in its own module so surfaces that only need the answer (the
// agent modal) do not pull in the app context. Re-exported for existing importers.
export { bestFreeTierModel, resolveFreeTierPreferredModel } from '@/lib/models/freeTierModel';

/**
 * Whether this page load has already opened the composer on the free tier's #1.
 *
 * <p>Module-level, not a ref: the chat page and both side panels mount this hook, and
 * a per-instance ref would re-steer every time the reader moves between them, undoing
 * a model they picked a minute ago. One page load = one opening.
 */
let openedThisPageLoad = false;

/** Test-only: forget the opening so each spec starts from a fresh page load. */
export function resetFreeTierOpeningForTests(): void {
  openedThisPageLoad = false;
}

/**
 * Open the composer on the free tier's best-ranked model, on the Free plan.
 *
 * <p><b>The rule.</b> A Free account always OPENS on the best-ranked model opened to
 * the free tier (the admin's ranking, restricted to covered models). Not the catalogue
 * default, which is the admin's global #1 and may be a model the Free plan cannot pay
 * for, and not whatever the browser remembered.
 *
 * <p><b>Why a remembered choice does not win.</b> The selection is persisted in a
 * browser-wide storage key that is NOT scoped to the account, so on a shared browser
 * the Free account inherits the model the previous account picked (observed on prod:
 * a Free account opening on DeepSeek, the lowest-ranked free model, while Sonnet 5 is
 * the free tier's #1). Even an own earlier choice is a poor opening: the rule the
 * product states is "Free opens on the free #1".
 *
 * <p><b>Once per page load, then the reader decides.</b> The steer fires the first time
 * the plan verdict says Free and the catalogue has a covered model, and never again in
 * this page load, so a model the reader picks afterwards is kept. On a paid plan, on
 * CE, or when no model is open to the free tier, it does nothing at all
 * ({@code prefersFreeTierModels} is only ever true once the balance has answered, so
 * no wait on {@code verdictReady} is needed).
 *
 * <p><b>It waits for the restore.</b> The app context reads the stored model back in
 * its own mount effect, which React runs AFTER the effects of the surfaces below it.
 * With a warm catalogue and balance (a client-side move into the app) the steer would
 * otherwise be written first and then overwritten by the restored model, with the
 * opening already spent. Outside the app context there is nothing to write to, so the
 * hook does nothing and leaves the opening for a surface that can use it.
 */
export function usePreferFreeTierModel(models: AIModel[]): void {
  const { prefersFreeTierModels } = useMonthlyCreditsCannotPay();
  const app = useUnifiedAppSafe();
  const restored = app?.state.selectionRestored === true;
  const selectedModel = app?.state.selectedModel;
  const setSelectedModel = app?.setSelectedModel;

  useEffect(() => {
    if (openedThisPageLoad) return;
    if (!restored || !setSelectedModel) return;
    if (!prefersFreeTierModels) return;
    const best = bestFreeTierModel(models);
    if (!best) return;

    openedThisPageLoad = true;
    const target = selectedModelFromAIModel(best);
    if (!selectedModel || !selectedModelEquals(selectedModel, target)) {
      // Reported only when the selection actually changes. An empty selection has no previous
      // model to name, so the prop is left out rather than sent as an empty string.
      track('chat_model_auto_switched', {
        model: target.id,
        previous_model: selectedModel?.id || undefined,
        reason: 'free_tier',
      });
      setSelectedModel(target);
    }
  }, [prefersFreeTierModels, restored, selectedModel, models, setSelectedModel]);
}

export default usePreferFreeTierModel;
