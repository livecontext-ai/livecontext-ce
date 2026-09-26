'use client';

import * as React from 'react';
import { AlertCircle, Coins, FolderOpen, Loader2, Pencil } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { FileDetailView } from '@/components/app/FileDetailView';
import { FormatGlyph, ProviderIcon } from '@/lib/generation/formats';
import { paramLabel, readableRefusal, type LabelTranslator } from '@/lib/generation/labels';
import type { StudioRequestEnvelope, StudioResultEnvelope } from '@/lib/generation/studioMessage';
import { describeBilledFactors, describeCharge, formatCredits } from '@/lib/generation/price';

/**
 * One studio turn on screen: what was asked for, and what came back.
 *
 * <p><b>Why the asset is mounted through {@code FileDetailView} rather than an img/video tag.</b>
 * That component IS the app's file viewer: the one /app/files opens, the one the side panel mounts,
 * the one the chat's visualize cards use. It already knows how to load an image, a clip, an audio
 * track or a PDF through the opaque, org-scoped by-id route, and to fall back to a metadata
 * placeholder for anything it cannot show. A hand-rolled preview here would be a second answer to
 * a question the app has already answered, and it would drift: the first thing it would get wrong
 * is addressing the file by its storage KEY, which is not a URL, resolves against the current page
 * and writes the tenant prefix into the DOM.
 *
 * <p><b>Three states, and the middle one matters most.</b> A turn can be answered, refused, or
 * still open. "Still open" is not a spinner that will resolve: the request was written down before
 * the provider was called, so a turn with no answer is one whose connection was lost while the
 * server may have finished it and charged for it. It says so, and points at Files, instead of
 * inviting a resend.
 */

export interface StudioTurnCardProps {
  request: StudioRequestEnvelope;
  /** Absent while the turn is running, or for good if the answer never arrived. */
  result?: StudioResultEnvelope;
  /** True only for a turn this session is still waiting on. */
  isRunning?: boolean;
  /** Put this turn's words and parameters back in the composer. */
  onReuse?: (request: StudioRequestEnvelope) => void;
  /** Open the produced asset where files live. */
  onOpenInFiles?: (fileId: string) => void;
  /**
   * The provider's mark, resolved by the thread from the model catalogue.
   *
   * <p>Not stored in the envelope on purpose: an icon slug is presentation, it changes with the
   * catalogue, and a turn from last month would keep showing whatever mark was current when it ran.
   * Absent (an unknown or retired model) renders no icon rather than a placeholder box.
   */
  iconSlug?: string | null;
}

export function StudioTurnCard({
  request, result, isRunning = false, onReuse, onOpenInFiles, iconSlug,
}: StudioTurnCardProps) {
  const t = useTranslations('studio');
  const tGeneration = useTranslations('generation');
  // The price is worded once, by the cards that show a past generation, and read here rather than
  // said again: two namespaces for one sentence is how the thread and the history two rows apart
  // end up quoting the same charge differently.
  const tHistory = useTranslations('generationHistory');
  // The same dictionary, narrowed for the label helpers that need `has` to guard a key the API
  // catalogue can ship without this build knowing the word for it.
  const tLabels = tGeneration as unknown as LabelTranslator;

  const file = result?.file;
  const fileId = file?.id ? String(file.id) : null;
  // A request with no answer and nothing in flight: the answer never came back.
  const unanswered = !result && !isRunning;

  return (
    <Card className="overflow-hidden">
      <CardContent className="space-y-3 p-4">
        <div className="flex items-start gap-2">
          <FormatGlyph kind={request.kind} className="mt-0.5 h-4 w-4 flex-shrink-0 text-theme-muted" />
          <div className="min-w-0 flex-1">
            {request.prompt
              ? <p className="whitespace-pre-wrap break-words text-sm text-theme-primary">{request.prompt}</p>
              // A turn can be entirely a file plus parameters (an upscale, a style transfer). Saying
              // so beats an empty line that reads like a rendering fault.
              : <p className="text-sm italic text-theme-muted">{t('turn.noPrompt')}</p>}
          </div>
          <div className="flex flex-shrink-0 items-center gap-1.5 text-xs text-theme-muted">
            <ProviderIcon slug={iconSlug} className="h-3.5 w-3.5 rounded-sm" />
            {/* The model that ran THIS turn, not the one currently selected: the reader may have
                changed model since, and a card labelled with the current one would misattribute
                every earlier asset in the thread. */}
            <span className="max-w-[140px] truncate">{result?.model ?? request.model}</span>
          </div>
        </div>

        {request.params && Object.keys(request.params).length > 0 && (
          <ParamSummary params={request.params} t={tLabels} />
        )}

        {isRunning && (
          <div className="flex items-center gap-2 rounded-xl bg-theme-tertiary px-3 py-2 text-sm text-theme-secondary">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t('turn.running')}
          </div>
        )}

        {unanswered && (
          // Deliberately not worded as a failure. The submission left, and the server may have
          // finished and committed the charge; "try again" here would be a second purchase.
          <Notice tone="warn" text={t('turn.noAnswer')} />
        )}

        {result && !result.success && (
          // The endpoint's own words, verbatim: every refusal on this path names a remedy the
          // reader can act on, and a generic replacement throws away the only useful half. The
          // guard only swallows a payload that PARSES as JSON, which no actionable sentence is.
          //
          // `warn` rather than `error` once it was charged: red reads as "nothing happened, do it
          // again", and doing it again is a second purchase.
          <Notice
            tone={result.chargedAnyway ? 'warn' : 'error'}
            text={readableRefusal(result.error, t('turn.failed'))}
          />
        )}

        {result?.chargedAnyway && (
          // A generation that RAN and was billed but could not be filed. Saying so is the whole
          // point: without it the reader reads a failure, sends the same thing again, and pays
          // twice for one asset.
          <Notice tone="warn" text={t('turn.chargedNoAsset')} />
        )}

        {result?.assetUrl && (
          // The provider's own link, and the ONLY route to something that was paid for: nothing was
          // stored, so there is no file row and no second copy. It expires in minutes, which is why
          // the label says to save it now rather than offering it as an ordinary download.
          <a
            href={result.assetUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-sm text-theme-accent underline underline-offset-2"
          >
            {t('turn.recoverAsset')}
          </a>
        )}

        {result?.success && (
          fileId ? (
            <div className="h-[46vh] overflow-hidden rounded-xl">
              <FileDetailView
                entryId={fileId}
                s3Key={file?.path ? String(file.path) : undefined}
                fileName={file?.name ? String(file.name) : undefined}
                mimeType={file?.mimeType ? String(file.mimeType) : undefined}
                sizeBytes={typeof file?.size === 'number' ? file.size : undefined}
                chromeless
                fitMediaToHost
                showMetadata={false}
                // Never called in chromeless mode (there is no header to click), but it is a
                // required prop and a wrong value would trap whoever un-chromelesses this.
                onBack={() => onOpenInFiles?.(fileId)}
              />
            </div>
          ) : (
            // No id means nothing can be previewed or downloaded from here. The sentence asserts
            // only what is certain: the generation finished, this card cannot show it.
            <Notice tone="warn" text={t('turn.savedNoPreview')} />
          )
        )}

        <div className="flex flex-wrap items-center gap-2">
          {result?.billedQuantity != null && result.billedUnit && (
            <span className="text-xs text-theme-muted">
              {/* Through the dictionary's own placeholders, not a string replace: the unit and the
                  number sit differently in different languages. */}
              {tGeneration('billedOn', {
                quantity: result.billedQuantity,
                unit: result.billedUnit,
              })}
            </span>
          )}
          {/* The third number in the charge, when there was one.

              The size above says what was produced and the amount below says what it cost; between
              them sits the only thing that makes the two agree, and it is the one the composer
              already showed as an estimate before the turn ran. Leaving it off the finished card
              made the surface state a factor while a price was being decided and forget it once the
              price was real - the moment it is no longer an estimate but a charge.

              The factor and its reasons come from the RESPONSE, not from a local recalculation:
              this card outlives the model list it was generated from, and a model whose modifiers
              were edited since would have the card re-explain a past charge with today's rules. */}
          {result?.billedMultiplier != null && (() => {
            // Worded by the SAME expression the composer's estimate uses, so the two halves of one
            // screen do not speak two vocabularies: the server writes its reasons as raw contract
            // names in English (`resolution x2`), and printing them verbatim put that beside an
            // estimate reading "includes Resolution x2", translated and list-joined per locale.
            const reason = describeBilledFactors(result.billedMultiplierReasons, tGeneration);
            return (
              <span className="text-xs text-theme-muted" title={reason || undefined}>
                {tGeneration('price.multiplierBadge', {
                  factor: formatCredits(result.billedMultiplier),
                })}
                {/* READ OUT, not just hovered. `title` on a role-less span is not an accessible
                    name and does not exist on touch at all - which is exactly the argument written
                    out in StudioComposer, and this card shipped the thing that comment forbids.
                    It is the worse place for it: the composer explains an estimate, this explains
                    a charge that already happened. */}
                {reason && <span className="sr-only">{` ${reason}`}</span>}
              </span>
            );
          })()}
          {/* What it cost, in the same words and the same unit as the history cards on the screen
              below - a turn that states the size it was billed on and not the amount leaves the
              reader to look the price up somewhere else for the generation they just ran. Absent
              when the platform charged nothing, never drawn as a zero. */}
          {result?.billedCredits != null && result.billedCredits > 0 && (
            <span
              className="inline-flex items-center gap-1 text-xs text-theme-secondary"
              title={tHistory('costTitle')}
            >
              <Coins className="h-3 w-3 flex-shrink-0" />
              {/* Through the SAME expression the history cards use, so the thread and the grid
                  under it cannot state one charge in two units. Written out here once, this card
                  quoted credits on a self-hosted install whose every other spend surface, the
                  history card directly below included, quotes dollars. */}
              {describeCharge(result.billedCredits, tHistory)}
            </span>
          )}
          <div className="flex-1" />
          {onReuse && (
            <Button variant="ghost" size="sm" onClick={() => onReuse(request)}>
              {/* The same verb and the same pencil as every past-generation card, because it is the
                  same action and it is wired to the same handler: load this recipe back into the
                  composer so something can be changed before it runs. On an empty studio the two
                  controls sit ten pixels apart, and a circular arrow beside "Reuse" promised a
                  repeat - the wrong action, and the expensive one to be wrong about. */}
              <Pencil className="mr-1.5 h-3.5 w-3.5" />
              {t('turn.modify')}
            </Button>
          )}
          {fileId && onOpenInFiles && (
            <Button variant="outline" size="sm" onClick={() => onOpenInFiles(fileId)}>
              <FolderOpen className="mr-1.5 h-3.5 w-3.5" />
              {t('turn.openInFiles')}
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

/** A short line of state, in the app's status colours. */
function Notice({ tone, text }: { tone: 'warn' | 'error'; text: string }) {
  return (
    <div
      className={`flex items-start gap-2 rounded-xl px-3 py-2 text-sm ${
        tone === 'error'
          ? 'bg-[var(--status-error)]/10 text-[var(--status-error)]'
          : 'bg-[var(--status-warning)]/10 text-[var(--status-warning)]'
      }`}
    >
      <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" />
      <span className="min-w-0 break-words">{text}</span>
    </div>
  );
}

/**
 * What was set on this turn, beyond the words.
 *
 * <p>File parameters are shown by COUNT rather than by handle: a storage handle is a UUID and a
 * key, neither of which tells the reader anything, and printing the key would put the tenant prefix
 * on screen. What they need to know is that this turn ran on two reference images.
 */
function ParamSummary({ params, t }: { params: Record<string, unknown>; t: LabelTranslator }) {
  const entries = Object.entries(params).filter(([, value]) => {
    if (Array.isArray(value)) return value.length > 0;
    return value !== undefined && value !== null && String(value).trim() !== '';
  });
  if (entries.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-1.5">
      {entries.map(([name, value]) => (
        <span
          key={name}
          className="rounded-lg bg-theme-tertiary px-2 py-0.5 text-xs text-theme-secondary"
        >
          {paramLabel(name, t)}
          {': '}
          <span className="opacity-80">
            {Array.isArray(value) ? `${value.length}` : String(value)}
          </span>
        </span>
      ))}
    </div>
  );
}
