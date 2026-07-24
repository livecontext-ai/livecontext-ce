'use client';

import * as React from 'react';
import { Input } from '@/components/ui/input';
import {
  SELECT_EMPTY_VALUE_SENTINEL,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import {
  INTERFACE_FORMAT_PRESETS,
  normalizeInterfaceFormat,
} from '@/lib/interfaces/interfaceFormats';

/** Sentinel select value for the free-form "WIDTHxHEIGHT" entry (never a stored format). */
const CUSTOM_FORMAT_OPTION = '__custom__';

/**
 * Select value for Auto (stores NULL). Radix forbids an empty-string item value, so the app's
 * SelectItem silently rewrites `""` to this sentinel and leaves the caller to map it back
 * (see SELECT_EMPTY_VALUE_SENTINEL). Using it directly keeps the emitted value and the item's
 * real value the same, so Auto reads back as selected instead of falling through to the placeholder.
 */
const AUTO_FORMAT_OPTION = SELECT_EMPTY_VALUE_SENTINEL;

export interface InterfaceFormatSelectProps {
  /** The interface's stored format: a preset name, a "WIDTHxHEIGHT", or null/'' for none. */
  value: string | null | undefined;
  /** Emits the format to store: a canonical preset name, a normalised "WxH", or null for Auto. */
  onChange: (format: string | null) => void;
  /** Raised while a custom draft cannot be normalised, so the caller can block its save. */
  onValidityChange?: (invalid: boolean) => void;
  disabled?: boolean;
  /**
   * DOM id of the trigger, for a caller's `<label htmlFor>`. Override it wherever two of these can
   * co-render (the builder shows the inspector and the create-interface modal at once): duplicate
   * ids are invalid HTML, and the modal's label would resolve to the inspector's trigger behind
   * the dialog.
   */
  id?: string;
  /** Set false to drop the help paragraph where the column is too narrow for it. */
  showHelp?: boolean;
}

/**
 * The one control for an interface's display/capture format, shared by every surface that edits
 * an interface (the editor modal and the workflow inspector, which both save to the entity).
 *
 * Auto is not a preset: it stores NULL, which means "no declared shape" and keeps the screenshot
 * a FULL-PAGE capture. `classic` is the 1280x800 preset, an exact frame that crops below the
 * fold. Offering Auto as a real option is what stops a tall dashboard from being silently
 * cropped the first time someone opens this select.
 */
export function InterfaceFormatSelect({
  value,
  onChange,
  onValidityChange,
  disabled,
  id = 'interface-format-select',
  showHelp = true,
}: InterfaceFormatSelectProps) {
  const t = useTranslations('modals.createInterface');

  const stored = normalizeInterfaceFormat(value) || '';
  const isPreset = INTERFACE_FORMAT_PRESETS.some((p) => p.name === stored);

  const [selectValue, setSelectValue] = React.useState(
    stored === '' ? AUTO_FORMAT_OPTION : isPreset ? stored : CUSTOM_FORMAT_OPTION,
  );
  const [customDraft, setCustomDraft] = React.useState(isPreset ? '' : stored);
  const [customError, setCustomError] = React.useState<string | null>(null);

  // The normalised format the state above currently reflects. Comparing against it makes the
  // effect fire ONLY on a genuine external change (the caller switching to another interface):
  // on mount it already matches, and a controlled parent echoing back what we just emitted
  // matches too. Without that, the echo would re-derive and rewrite the draft mid-typing.
  const syncedFormat = React.useRef(stored);

  React.useEffect(() => {
    const s = normalizeInterfaceFormat(value) || '';
    if (s === syncedFormat.current) return;
    syncedFormat.current = s;
    const preset = INTERFACE_FORMAT_PRESETS.some((p) => p.name === s);
    setSelectValue(s === '' ? AUTO_FORMAT_OPTION : preset ? s : CUSTOM_FORMAT_OPTION);
    setCustomDraft(s && !preset ? s : '');
    setCustomError(null);
  }, [value]);

  // Custom is only usable once the draft normalises. A BLANK draft counts as unusable too: it
  // emits nothing, so the caller would otherwise save the PREVIOUS format while the UI shows an
  // empty Custom box - a silent disagreement between what is displayed and what is stored.
  const isCustomInvalid =
    selectValue === CUSTOM_FORMAT_OPTION && !normalizeInterfaceFormat(customDraft);

  // Through a ref: keeping the callback in the deps would loop forever for a caller passing an
  // inline lambda, which is the natural way to write this prop.
  const onValidityChangeRef = React.useRef(onValidityChange);
  onValidityChangeRef.current = onValidityChange;
  React.useEffect(() => {
    onValidityChangeRef.current?.(isCustomInvalid);
  }, [isCustomInvalid]);

  const emit = (next: string | null) => {
    syncedFormat.current = next || '';
    onChange(next);
  };

  const onSelect = (next: string) => {
    if (next === AUTO_FORMAT_OPTION) return emit(null);
    // Picking Custom is not itself a value: the draft is. Emitting here would send the empty
    // draft's null, i.e. CLEAR the shape, the moment the option is picked.
    if (next === CUSTOM_FORMAT_OPTION) {
      const normalized = normalizeInterfaceFormat(customDraft);
      if (normalized) emit(normalized);
      return;
    }
    emit(next);
  };

  return (
    <div>
      <Select
        value={selectValue}
        onValueChange={(v) => {
          setSelectValue(v);
          setCustomError(null);
          onSelect(v);
        }}
        disabled={disabled}
      >
        <SelectTrigger className="w-full" id={id}>
          <SelectValue placeholder={t('formatAuto')} />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={AUTO_FORMAT_OPTION} description={t('formatAutoHint')}>
            {t('formatAuto')}
          </SelectItem>
          {INTERFACE_FORMAT_PRESETS.map((preset) => (
            <SelectItem
              key={preset.name}
              value={preset.name}
              description={`${preset.width} x ${preset.height}`}
            >
              {t(`formatPreset_${preset.name}` as never)}
            </SelectItem>
          ))}
          <SelectItem value={CUSTOM_FORMAT_OPTION} description={t('formatCustomPlaceholder')}>
            {t('formatCustom')}
          </SelectItem>
        </SelectContent>
      </Select>

      {selectValue === CUSTOM_FORMAT_OPTION && (
        <div className="mt-2">
          <Input
            data-testid="interface-format-custom"
            value={customDraft}
            onChange={(e) => {
              setCustomDraft(e.target.value);
              setCustomError(null);
              const normalized = normalizeInterfaceFormat(e.target.value);
              if (normalized) emit(normalized);
            }}
            onBlur={() => {
              // A BLANK draft errors too. It blocks the save exactly like a malformed one, so
              // staying silent on it leaves the caller's button disabled (or its save refusing)
              // with nothing on screen explaining why.
              setCustomError(normalizeInterfaceFormat(customDraft) ? null : t('formatCustomInvalid'));
            }}
            placeholder={t('formatCustomPlaceholder')}
            className="w-full"
            disabled={disabled}
          />
          {customError && <p className="mt-1 text-xs text-red-500">{customError}</p>}
        </div>
      )}

      {showHelp && <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{t('formatHelp')}</p>}
    </div>
  );
}
