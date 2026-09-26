import { track } from '@/lib/analytics/analytics';
import type { GenerationModel, GenerationResult } from '@/lib/api/orchestrator/generation.service';
import { generationWasCharged } from './studioMessage';

/**
 * The two places a generation is started from: the Studio page and the "Create" modal.
 * Sent as `entry_point`, never `surface`: `surface` is a reserved common prop set by `track()`.
 */
export type StudioEntryPoint = 'studio' | 'modal';

/**
 * How a generation ended, as the product reads it:
 * `success` an asset came back, `refused` answered without running (nothing charged),
 * `failed` ran or broke without an asset, `lost` the answer never came back (may still be charged).
 */
export type StudioGenerationOutcome = 'success' | 'refused' | 'failed' | 'lost';

/** A recorded answer: a charged failure is `failed`, an uncharged one is a refusal. */
export function outcomeOfGenerationResult(
  result: Pick<GenerationResult, 'success' | 'data'>,
): StudioGenerationOutcome {
  if (result.success) return 'success';
  return generationWasCharged(result) ? 'failed' : 'refused';
}

/** A model picked by the reader (never one restored from a thread or a recipe). */
export function trackStudioModelSelected(model: GenerationModel, entryPoint: StudioEntryPoint): void {
  track('studio_model_selected', {
    model: model.model,
    kind: model.kind,
    provider: model.provider,
    entry_point: entryPoint,
  });
}

/** One submitted generation, once its outcome is known. Never the prompt nor the files. */
export function trackStudioGenerationSubmitted(
  model: Pick<GenerationModel, 'model' | 'kind' | 'provider'>,
  credentialSource: 'platform' | 'user',
  outcome: StudioGenerationOutcome,
  entryPoint: StudioEntryPoint,
): void {
  track('studio_generation_submitted', {
    model: model.model,
    kind: model.kind,
    provider: model.provider,
    credential_source: credentialSource,
    outcome,
    entry_point: entryPoint,
  });
}
