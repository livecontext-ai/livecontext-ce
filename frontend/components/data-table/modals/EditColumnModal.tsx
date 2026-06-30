'use client';

import React, { useState, useEffect, useMemo } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslations } from 'next-intl';
import { Check, Pencil, X } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  RatingConfig,
  SelectConfig,
  ProgressConfig,
  DateFormatConfig,
  NumberFormatConfig,
  type SelectOptionItem,
  type NumberFormatType,
} from '@/components/data-table/modals/AddColumnModal';
import { COLUMN_STYLE_PRESETS, renderPresetPreview, type ColumnStylePreset } from '@/components/data-table/visualHelpers';
import type { ColumnDefinition } from '@/components/data-table/types';
import type { ColumnDisplayConfig, ColumnVisualType } from '@/types/data-sources';

export interface EditColumnSubmission {
  /** New header_name when changed (else undefined). */
  newName?: string;
  /** Full display config when changed (else undefined). */
  display?: ColumnDisplayConfig;
}

export interface EditColumnModalProps {
  isOpen: boolean;
  isSaving: boolean;
  column: ColumnDefinition | null;
  onClose: () => void;
  onSave: (submission: EditColumnSubmission) => void;
}

/**
 * First preset whose visualType matches the column's type. Used to drive the
 * type-recap card visuals (icon, i18n label/description, preset preview). When
 * no exact match exists we fall back to the first text preset so the card still
 * renders something meaningful instead of crashing.
 */
function pickRecapPreset(visualType: ColumnVisualType | undefined): ColumnStylePreset {
  const match = visualType && COLUMN_STYLE_PRESETS.find((p) => p.visualType === visualType);
  return match ?? COLUMN_STYLE_PRESETS[0];
}

/**
 * Edit-only modal for an existing column. Visually mirrors the Step-2 pane of
 * `AddColumnModal` (same type-recap card, same per-type config sub-components,
 * same overall layout) so the user gets a consistent experience between
 * creation and edit. Step 1 is intentionally absent - changing the visual type
 * after creation would invalidate row data, so the type is shown read-only and
 * the user is told to delete + recreate to change it.
 */
export function EditColumnModal({
  isOpen,
  isSaving,
  column,
  onClose,
  onSave,
}: EditColumnModalProps) {
  const t = useTranslations('modals.editColumn');
  // Reuse the addColumn type catalogue for label/description so the recap card
  // wording stays in sync with creation. EditColumn doesn't redefine them.
  const tAdd = useTranslations('modals.addColumn');

  const [mounted, setMounted] = useState(false);

  const initial = useMemo(() => {
    const display = (column?.displayConfig ?? {}) as ColumnDisplayConfig;
    const initialOptions: SelectOptionItem[] = Array.isArray(display.options)
      ? display.options.map((o: unknown) =>
          typeof o === 'string' ? { label: o, value: o } : (o as SelectOptionItem)
        )
      : [];
    return {
      header_name: column?.header_name ?? '',
      type: column?.type as ColumnVisualType | undefined,
      ratingMax: typeof display.max === 'number' ? display.max : 5,
      progressMax: typeof display.max === 'number' ? display.max : 100,
      selectOptions: initialOptions,
      dateFormat: (display.dateFormat as 'date' | 'datetime' | 'time') ?? 'date',
      numberFormat: (display.format as NumberFormatType) ?? 'plain',
      numberDecimals: typeof display.decimals === 'number' ? display.decimals : 0,
      currencySymbol: typeof display.currencySymbol === 'string' ? display.currencySymbol : '$',
    };
  }, [column]);

  const [headerName, setHeaderName] = useState(initial.header_name);
  const [ratingMax, setRatingMax] = useState(initial.ratingMax);
  const [progressMax, setProgressMax] = useState(initial.progressMax);
  const [selectOptions, setSelectOptions] = useState<SelectOptionItem[]>(initial.selectOptions);
  const [dateFormat, setDateFormat] = useState<'date' | 'datetime' | 'time'>(initial.dateFormat);
  const [numberFormat, setNumberFormat] = useState<NumberFormatType>(initial.numberFormat);
  const [numberDecimals, setNumberDecimals] = useState(initial.numberDecimals);
  const [currencySymbol, setCurrencySymbol] = useState(initial.currencySymbol);

  // Reset state every time the modal opens or the column changes.
  // Without this, switching from one column to another while the modal is
  // mounted would show the previous column's state.
  useEffect(() => {
    if (!isOpen) return;
    setHeaderName(initial.header_name);
    setRatingMax(initial.ratingMax);
    setProgressMax(initial.progressMax);
    setSelectOptions(initial.selectOptions);
    setDateFormat(initial.dateFormat);
    setNumberFormat(initial.numberFormat);
    setNumberDecimals(initial.numberDecimals);
    setCurrencySymbol(initial.currencySymbol);
  }, [isOpen, initial]);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  if (!isOpen || !mounted || !column) return null;

  const visualType = column.type as ColumnVisualType;

  // Recap preset reflects the column's CURRENT display so the preview pane
  // shows the user's actual options/max/format, not a generic placeholder.
  const baseRecapPreset = pickRecapPreset(visualType);
  const recapPreset: ColumnStylePreset = {
    ...baseRecapPreset,
    display: { ...(baseRecapPreset.display ?? {}), ...(column.displayConfig ?? {}) },
  };
  const RecapIcon = recapPreset.icon;

  /**
   * Compute the diff submission. We only emit a `display` object when something
   * inside it actually changed; we only emit `newName` when the trimmed header
   * differs from the original. Empty-trim guards prevent sending blank renames.
   */
  const buildSubmission = (): EditColumnSubmission | null => {
    const sub: EditColumnSubmission = {};
    const trimmedName = headerName.trim();
    if (trimmedName !== '' && trimmedName !== column.header_name) {
      sub.newName = trimmedName;
    }

    // Compose new display config from the type-specific local state.
    const baseDisplay: ColumnDisplayConfig = { ...(column.displayConfig ?? {}) };
    let displayChanged = false;
    if (visualType === 'rating' && baseDisplay.max !== ratingMax) {
      baseDisplay.max = ratingMax;
      displayChanged = true;
    } else if (visualType === 'select' || visualType === 'multi_select') {
      const before = JSON.stringify(baseDisplay.options ?? []);
      const after = JSON.stringify(selectOptions);
      if (before !== after) {
        baseDisplay.options = selectOptions;
        displayChanged = true;
      }
    } else if (visualType === 'progress' && baseDisplay.max !== progressMax) {
      baseDisplay.max = progressMax;
      displayChanged = true;
    } else if (visualType === 'date' && baseDisplay.dateFormat !== dateFormat) {
      baseDisplay.dateFormat = dateFormat;
      displayChanged = true;
    } else if (visualType === 'number') {
      if (
        baseDisplay.format !== numberFormat ||
        baseDisplay.decimals !== numberDecimals ||
        baseDisplay.currencySymbol !== currencySymbol
      ) {
        baseDisplay.format = numberFormat;
        baseDisplay.decimals = numberDecimals;
        baseDisplay.currencySymbol = currencySymbol;
        displayChanged = true;
      }
    }

    if (displayChanged) sub.display = baseDisplay;
    if (sub.newName === undefined && sub.display === undefined) return null;
    return sub;
  };

  const canSave = (() => {
    const trimmed = headerName.trim();
    if (trimmed === '') return false;
    return buildSubmission() !== null;
  })();

  const handleSave = () => {
    const sub = buildSubmission();
    if (sub === null) return;
    onSave(sub);
  };

  const supportsTypeConfig =
    visualType === 'rating' ||
    visualType === 'select' ||
    visualType === 'multi_select' ||
    visualType === 'progress' ||
    visualType === 'date' ||
    visualType === 'number';

  // Best-effort i18n keys: fall back to the visualType string when the recap
  // preset id has no translation entry (e.g. uncommon legacy presets).
  const recapLabel = (() => {
    try { return tAdd(`types.${recapPreset.id}.label`); } catch { return visualType; }
  })();
  const recapDescription = (() => {
    try { return tAdd(`types.${recapPreset.id}.description`); } catch { return ''; }
  })();

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-2xl bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme flex flex-col overflow-hidden max-h-[90vh]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header - same shape as AddColumnModal Step-2 (centered icon-circle + title + subtitle). */}
        <div className="px-8 pt-8 pb-4 flex-shrink-0">
          <div className="text-center mb-4">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
              <Pencil className="w-8 h-8 text-theme-primary" />
            </div>
            <h3 className="text-2xl font-semibold text-theme-primary">{t('title')}</h3>
            <p className="text-sm text-theme-secondary mt-1">{t('subtitle')}</p>
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-8 pb-4 min-h-0">
          <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
            {/* Type recap - mirrors AddColumnModal Step-2 recap card 1:1.
              * Type is read-only here; the description below the card explains why. */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700/50 bg-theme-secondary/50 overflow-hidden">
              <div className="flex items-center gap-3 px-4 py-3 border-b border-slate-200 dark:border-slate-700/50">
                <div className="w-8 h-8 rounded-full bg-[var(--accent-primary)]/10 flex items-center justify-center">
                  <RecapIcon className="h-4 w-4 text-[var(--accent-primary)]" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-semibold text-theme-primary leading-tight">{recapLabel}</p>
                  {recapDescription && (
                    <p className="text-xs text-theme-muted leading-tight mt-0.5">{recapDescription}</p>
                  )}
                </div>
                <span className="text-[10px] uppercase tracking-wider text-theme-muted px-2 py-0.5 rounded-full bg-theme-tertiary flex-shrink-0">
                  {t('typeImmutableHint')}
                </span>
              </div>
              <div className="px-5 py-5 bg-theme-primary flex items-center justify-center min-h-[64px]">
                {renderPresetPreview(recapPreset)}
              </div>
            </div>

            {/* Column name */}
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t('columnName')} *
              </label>
              <Input
                value={headerName}
                onChange={(e) => setHeaderName(e.target.value)}
                placeholder={t('columnNamePlaceholder')}
                className="w-full"
                autoFocus
              />
            </div>

            {/* Type-specific configuration - same components as AddColumnModal */}
            {visualType === 'rating' && (
              <RatingConfig max={ratingMax} onChange={setRatingMax} label={t('configRatingMax')} />
            )}
            {(visualType === 'select' || visualType === 'multi_select') && (
              <>
                <SelectConfig
                  options={selectOptions}
                  onChange={setSelectOptions}
                  labels={{
                    options: t('configSelectOptions'),
                    add: t('configSelectAdd'),
                    placeholder: t('configSelectPlaceholder'),
                  }}
                />
                <p className="text-xs text-theme-muted -mt-2">{t('removeOptionWarning')}</p>
              </>
            )}
            {visualType === 'progress' && (
              <ProgressConfig max={progressMax} onChange={setProgressMax} label={t('configProgressMax')} />
            )}
            {visualType === 'date' && (
              <DateFormatConfig dateFormat={dateFormat} onChange={setDateFormat} label={t('configDateFormat')} />
            )}
            {visualType === 'number' && (
              <NumberFormatConfig
                format={numberFormat}
                decimals={numberDecimals}
                currencySymbol={currencySymbol}
                onFormatChange={setNumberFormat}
                onDecimalsChange={setNumberDecimals}
                onCurrencySymbolChange={setCurrencySymbol}
                labels={{
                  format: t('configNumberFormat'),
                  decimals: t('configNumberDecimals'),
                  symbol: t('configCurrencySymbol'),
                }}
              />
            )}
            {!supportsTypeConfig && (
              <p className="text-sm text-theme-muted">{t('noTypeConfigHint')}</p>
            )}
          </div>
        </div>

        {/* Footer - same right-aligned cancel + primary save layout as AddColumnModal. */}
        <div className="px-8 py-4 border-t border-theme flex justify-end gap-2 flex-shrink-0">
          <Button variant="outline" onClick={onClose} disabled={isSaving}>
            <X className="h-4 w-4 mr-2" />
            {t('cancel')}
          </Button>
          <Button onClick={handleSave} disabled={!canSave || isSaving}>
            {isSaving ? (
              <>
                <LoadingSpinner size="xs" className="mr-2" />
                {t('saving')}
              </>
            ) : (
              <>
                <Check className="h-4 w-4 mr-2" />
                {t('save')}
              </>
            )}
          </Button>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
