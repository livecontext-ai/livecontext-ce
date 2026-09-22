'use client';

import * as React from 'react';
import { Check, Loader2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverTrigger } from '@/components/ui/popover';
// A menu opened from the studio is drawn on the studio's own ground: it renders in a portal on
// the document, so it cannot inherit the surface's tokens and has to be handed them.
import { StudioPopoverContent } from '@/components/studio/StudioPopoverContent';
// See components/ui/menu.ts: the bare PopoverContent has no background in this theme.
import { menuItemClass, menuSurfaceClass } from '@/components/ui/menu';
import type { GenerationOptionsState } from '@/hooks/useGenerationOptions';
import { paramLabel, type LabelTranslator } from '@/lib/generation/labels';
import type { StudioField } from '@/lib/generation/paramSpec';

/**
 * One parameter of the selected model, as a toggle on the composer.
 *
 * <p><b>Why a pill and not a form row.</b> These come and go with the model - a video model has a
 * duration and a resolution, a voice model has a voice and a language, and neither has the other's.
 * Laid out as a form, the composer would change height every time the model changed. As a row of
 * pills, each shows its value when it has one and its name when it does not, and the row simply
 * gets longer or shorter.
 *
 * <p><b>Why some values are fetched and not listed.</b> A voice belongs to the account holding the
 * key: the shared defaults, plus whatever that account cloned or bought. No list written into the
 * catalogue is true for two readers, and a wrong voice id is opaque and fails at the provider after
 * the call is paid for. So the catalogue only says that asking is worth it, and the values are
 * fetched when the field is opened, with the reader's own key.
 */

export interface StudioParamControlProps {
  field: StudioField;
  value: unknown;
  onChange: (value: unknown) => void;
  /**
   * The provider's answer for THIS parameter, when it is one whose values only the provider can
   * name. Fetched by the composer in a single batch rather than per control: the underlying hook
   * asks for every dynamic parameter of the model at once, so one control owning its own request
   * would multiply the calls to the provider by the number of pills on screen.
   */
  optionsState?: GenerationOptionsState;
  /** Called the first time this control is opened, so the composer can start asking. */
  onOpened?: () => void;
  /** True when this parameter is required and still empty: the pill says so before the submit does. */
  missing?: boolean;
  /** Row is short of width: show the glyph and the value, drop the word. */
  dense?: boolean;
  disabled?: boolean;
}

export function StudioParamControl({
  field, value, onChange, optionsState, onOpened, missing = false, dense = false, disabled = false,
}: StudioParamControlProps) {
  const t = useTranslations('studio');
  const tGeneration = useTranslations('generation') as unknown as LabelTranslator;
  const [open, setOpen] = React.useState(false);

  const label = paramLabel(field.name, tGeneration);
  const hasValue = value !== undefined && value !== null && String(value).trim() !== '';

  // Asked only once a field has been OPENED: it costs a call to the provider, so it belongs to the
  // moment the reader looks, not to loading the composer.
  const options = optionsState?.options ?? [];
  const isLoading = optionsState?.isLoading ?? false;
  const truncated = optionsState?.truncated ?? false;
  const error = optionsState?.error ?? null;

  const choices = React.useMemo(() => {
    // The provider's own answer REPLACES the catalogue's rather than joining it: they describe the
    // same field, and the account's list is the one the run will be judged against.
    if (field.optionsMustBeFetched && options.length > 0) {
      return options.map((option) => ({ value: option.value, label: option.label }));
    }
    return field.choices.map((choice) => ({ value: choice, label: choice }));
  }, [field.optionsMustBeFetched, field.choices, options]);

  // A closed list is only closed when the platform actually enforces it, and only when there IS a
  // list. Everything else keeps a text field, so a value the platform accepts is never forbidden by
  // this screen - and a truncated list never becomes a dead end for someone looking for a value
  // outside the sample.
  const allowsFreeText = field.kind !== 'choice'
    || field.choicesAreSuggestions
    || truncated
    || (choices.length === 0 && !isLoading);

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        if (disabled) return;
        if (next) onOpened?.();
        setOpen(next);
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          disabled={disabled}
          className={`flex h-8 flex-shrink-0 items-center gap-1 rounded-lg px-2 text-xs transition-colors disabled:opacity-50 ${
            missing
              ? 'bg-[var(--status-error)]/10 text-[var(--status-error)]'
              : hasValue
                ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                : 'bg-theme-tertiary text-theme-secondary hover:bg-theme-tertiary/70'
          }`}
          title={missing ? t('params.required', { label }) : label}
        >
          {/* Dropped when the ROW is short of width, not when the viewport is: this composer is
              drawn at several widths on one screen. The control keeps its glyph, its value and its
              title, so it stays identifiable by sight and by pointer. */}
          {!dense && <span className="whitespace-nowrap">{label}</span>}
          {hasValue && <span className="max-w-[90px] truncate opacity-80">{String(value)}</span>}
          {missing && <span aria-hidden="true">*</span>}
        </button>
      </PopoverTrigger>
      <StudioPopoverContent align="start" className={`${menuSurfaceClass} max-h-[50vh] w-64 overflow-y-auto`}>
        <p className="mb-2 text-xs font-medium text-theme-secondary">{label}</p>

        {field.kind === 'choice' && (
          <div className="mb-2 space-y-0.5">
            {isLoading && (
              <div className="flex items-center gap-2 px-1 py-2 text-xs text-theme-muted">
                <Loader2 className="h-3 w-3 animate-spin" />
                {t('params.loadingOptions')}
              </div>
            )}
            {/* An empty dropdown says "there are none", which is a different fact from "we could not
                ask". The reason is shown, and the text field below stays usable either way. */}
            {!isLoading && error && (
              <p className="px-1 py-1 text-xs text-theme-muted">{t('params.optionsUnavailable')}</p>
            )}
            {choices.map((choice) => {
              const isSelected = String(value ?? '') === choice.value;
              return (
                <button
                  key={choice.value}
                  type="button"
                  className={`${menuItemClass} justify-between ${isSelected ? 'bg-gray-100 dark:bg-gray-800' : ''}`}
                  onClick={() => { onChange(choice.value); setOpen(false); }}
                >
                  <span className="truncate">{choice.label}</span>
                  {isSelected && <Check className="h-3.5 w-3.5 flex-shrink-0" />}
                </button>
              );
            })}
            {truncated && (
              // Saying so is the point: a sample presented as the whole list is a dead end for
              // anyone looking for a value that is not in it.
              <p className="px-1 pt-1 text-xs text-theme-muted">{t('params.optionsTruncated')}</p>
            )}
          </div>
        )}

        {allowsFreeText && (
          <Input
            type={field.kind === 'number' ? 'number' : 'text'}
            value={value === undefined || value === null ? '' : String(value)}
            min={field.limit?.min}
            max={field.limit?.max}
            placeholder={numericPlaceholder(field) ?? t('params.freeTextPlaceholder')}
            onChange={(event) => onChange(event.target.value)}
            className="h-8 text-sm"
          />
        )}

        {hasValue && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="mt-2 w-full"
            onClick={() => { onChange(undefined); setOpen(false); }}
          >
            {t('params.clear')}
          </Button>
        )}
      </StudioPopoverContent>
    </Popover>
  );
}

/**
 * The accepted range, shown in the field rather than enforced silently.
 *
 * <p>A number outside the model's bounds is refused by the provider after the call is submitted, so
 * the bounds belong on screen while the value is being typed.
 */
function numericPlaceholder(field: StudioField): string | undefined {
  if (field.kind !== 'number') return undefined;
  const { min, max } = field.limit ?? {};
  if (min != null && max != null) return `${min} - ${max}`;
  if (min != null) return `≥ ${min}`;
  if (max != null) return `≤ ${max}`;
  return undefined;
}
