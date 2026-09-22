'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Coins, Pencil } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { FormatGlyph, ProviderIcon } from '@/lib/generation/formats';
import { describeCharge } from '@/lib/generation/price';

/**
 * One past generation, drawn the same way everywhere it appears.
 *
 * <p><b>Why one component.</b> A generated asset is shown in four places - the generation dialog's
 * history, the Files browser, the studio, and the file viewer itself - and each of them had its own
 * idea of what a past generation looks like. They said different things about the same asset: one
 * led with the prompt, another with a sentence about the model; one offered "Reuse", another "Change
 * something and generate again", for the identical action. A reader who learned the card in one
 * place had to learn it again in the next.
 *
 * <p>Purely presentational. It fetches nothing and decides nothing: what an entry IS comes from the
 * surfaces that own the query, so this cannot be the reason one of them shows something stale.
 */
export interface GenerationCardProps {
  /**
   * The asset itself. Omitted where the asset is already on screen at full size - the file viewer
   * shows the image above this card, and repeating it as a thumbnail says nothing twice.
   */
  thumb?: React.ReactNode;
  /**
   * What the reader recognises the entry by: their own words.
   *
   * <p>The PROMPT, not the file name. A generated file is called something like
   * {@code 20260824_elevenlabs-text-to-speech.mp3}, which is not what anyone was thinking about
   * when they made it.
   */
  title: string;
  /** The model line, already phrased by the caller ("Gemini 2.5 Flash Image", "Generated with X"). */
  modelLine: string;
  /** Provider logo beside the model line. */
  iconSlug?: string | null;
  /**
   * Format of the asset, drawn in the logo's place when there is no logo.
   *
   * <p>Two entries arrive with no provider icon: a model that has left the catalogue, and one whose
   * integration simply ships none. The first is the case that matters - it is the card that can say
   * least about what made the asset, and it used to lose its mark entirely - and the second gets
   * the format glyph too rather than a special case, which is a truthful mark either way.
   */
  kind?: string;
  /** When it was made, already formatted in the app locale. */
  date?: string;
  /**
   * Credits the platform charged for it.
   *
   * <p>Absent whenever the platform charged nothing: the reader ran it on their own provider key,
   * or this install has no ledger. Absent is NOT zero and is never drawn as zero - a generation
   * paid for at the provider did not cost nothing, and saying so on the card would be a claim about
   * money rather than a missing value.
   */
  billedCredits?: number | null;
  /** Open the asset. Omit and the thumbnail is not clickable. */
  onOpen?: () => void;
  /** Accessible name for opening the asset. Required when {@link onOpen} is given. */
  openLabel?: string;
  /** Load this recipe back into the form, to run it again with something changed. */
  onModify?: () => void;
  /** The model has left the catalogue: the control stays visible, and says why it cannot run. */
  modifyDisabled?: boolean;
  /** Why the control is disabled, on hover. */
  modifyTitle?: string;
  /** `li` inside a list, `div` on its own. Defaults to `div`. */
  as?: 'li' | 'div';
  className?: string;
}

export function GenerationCard({
  thumb,
  title,
  modelLine,
  iconSlug,
  kind,
  date,
  billedCredits,
  onOpen,
  openLabel,
  onModify,
  modifyDisabled = false,
  modifyTitle,
  as = 'div',
  className,
}: GenerationCardProps) {
  const t = useTranslations('generationHistory');
  const Root = as;
  // Finite AND positive. The backend only stores a positive charge, but a value that arrived
  // through JSON can still be a string, a null or a NaN, and each of those would render as a price
  // that was never charged.
  const cost = typeof billedCredits === 'number' && Number.isFinite(billedCredits) && billedCredits > 0
    ? billedCredits
    : null;
  // The amount in the unit its own edition spends in, decided once for every surface that states
  // a charge - see describeCharge.
  const price = cost === null ? null : describeCharge(cost, t);

  return (
    <Root className={`group flex flex-col overflow-hidden rounded-xl border border-theme bg-theme-secondary ${className ?? ''}`}>
      {thumb && (
        <button
          type="button"
          onClick={onOpen}
          disabled={!onOpen}
          aria-label={onOpen ? openLabel : undefined}
          className="flex aspect-[4/3] items-center justify-center overflow-hidden bg-theme-tertiary disabled:cursor-default"
        >
          {thumb}
        </button>
      )}

      <div className={`flex flex-1 flex-col gap-1.5 px-2.5 py-2 ${thumb ? 'border-t border-theme' : ''}`}>
        <p className="text-sm text-theme-primary line-clamp-2 break-words" title={title}>{title}</p>
        <p className="flex items-center gap-1.5 text-xs text-theme-muted">
          {iconSlug
            ? <ProviderIcon slug={iconSlug} className="h-3 w-3 flex-shrink-0 rounded-sm" />
            : <FormatGlyph kind={kind} className="h-3 w-3 flex-shrink-0" />}
          <span className="truncate">{modelLine}</span>
        </p>

        {/* Date and price on one line: both answer "what did this one cost me", and stacking them
            pushed the button below the fold of a card in a three-column grid. */}
        {(date || price !== null) && (
          <p className="flex items-center justify-between gap-2 text-xs text-theme-muted">
            {date ? <span className="truncate">{date}</span> : <span />}
            {price !== null && (
              <span
                className="inline-flex items-center gap-1 whitespace-nowrap text-theme-secondary"
                title={t('costTitle')}
              >
                <Coins className="h-3 w-3 flex-shrink-0" />
                {price}
              </span>
            )}
          </p>
        )}

        {onModify && (
          <div className="mt-auto pt-1" title={modifyTitle}>
            <Button
              variant="outline"
              size="sm"
              className="w-full"
              disabled={modifyDisabled}
              onClick={onModify}
            >
              {/* An edit pencil, not a replay arrow. The button does not re-run this generation, it
                  opens the form with its recipe in it so something can be changed first - and a
                  circular arrow promised a repeat, which is both the wrong action and the expensive
                  one to be wrong about. */}
              <Pencil className="mr-1.5 h-3.5 w-3.5" />
              {t('modify')}
            </Button>
          </div>
        )}
      </div>
    </Root>
  );
}

export default GenerationCard;
