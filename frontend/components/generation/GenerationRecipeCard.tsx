'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { GenerationCard } from '@/components/generation/GenerationCard';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { useGenerationProvenance } from '@/hooks/useGenerationHistory';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import type { GenerationProvenance } from '@/lib/api/storage-api';

/**
 * "Generated with X, from these words" - and the way to change one of them and run it again.
 *
 * <p><b>Why an asset needs this at all.</b> Once a generated file lands in the workspace it is
 * indistinguishable from an uploaded one: same row, same bytes, nothing saying which model made it
 * or from which prompt. Looking at it, there was no way to tell it was generated, and no way to
 * make a variant except retyping the prompt from memory - which is a new guess, not a variation.
 *
 * <p><b>Why it is a separate component and not part of the file viewer.</b> The viewer is mounted
 * on every file, everywhere: the Files page, the side panel, the chat cards, the generation
 * dialog's own result preview. Asking each of those for a recipe would spend a request per file
 * opened, on an answer that is null for almost all of them, and would make a leaf component that
 * renders a PNG depend on the query cache being present. Mounted only where a control to run it
 * again is actually offered, the cost and the dependency land exactly where the feature is used.
 *
 * <p><b>It draws the SAME card as the history.</b> This used to be a layout of its own, and it said
 * different things about the same asset than the grid two screens away did - down to a different
 * verb on the identical button. The one thing it drops is the thumbnail: the asset is already on
 * screen at full size, right above it.
 *
 * <p>Renders NOTHING for a file that was not generated here, which is the ordinary case.
 */
export interface GenerationRecipeCardProps {
  /** Storage row id of the asset. */
  entryId: string;
  /** Run this asset's generation again, with its recipe in hand. */
  onRegenerate: (provenance: GenerationProvenance) => void;
  className?: string;
}

export function GenerationRecipeCard({
  entryId,
  onRegenerate,
  className,
}: GenerationRecipeCardProps) {
  const t = useTranslations('generationHistory');
  const { provenance } = useGenerationProvenance(entryId);
  // Through the SAME cache every other generation surface reads. Used for two things: what to call
  // the model, and whether it still exists. A model that has left the catalogue cannot be re-run,
  // and letting the button open the form anyway would silently land the reader on a DIFFERENT model
  // with their old prompt in it - a variant of something else, with nothing on screen saying so.
  const { models } = useGenerationModels();

  if (!provenance) return null;

  const model = models.find((m) => m.model === provenance.model);
  const reusable = Boolean(model);

  return (
    <GenerationCard
      className={`w-full max-w-md ${className ?? ''}`}
      title={provenance.prompt?.trim() || t('untitled')}
      // Named as a sentence here, unlike the grid: among a wall of generated assets the model is a
      // caption, but on a file that could equally have been uploaded it is the fact that makes this
      // card worth reading at all.
      modelLine={t('generatedWith', { model: model?.label ?? provenance.model })}
      iconSlug={model?.iconSlug}
      kind={provenance.kind}
      // The recipe's own timestamp, which survives a copy of the file; absent on recipes written
      // before it was recorded, and then simply not shown.
      date={provenance.at ? formatUtcDate(provenance.at) : undefined}
      billedCredits={provenance.billedCredits}
      onModify={() => onRegenerate(provenance)}
      modifyDisabled={!reusable}
      modifyTitle={reusable ? undefined : t('reuseUnavailable')}
    />
  );
}

export default GenerationRecipeCard;
