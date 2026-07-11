'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { COLUMN_STYLE_PRESETS, PRESET_CATEGORIES, renderPresetPreview } from '@/components/data-table/visualHelpers';
import type { ColumnStylePreset } from '@/components/data-table/visualHelpers';
import { IS_CE } from '@/lib/edition';
import { useTranslations } from 'next-intl';
import {
  Plus, ArrowRight, ArrowLeft, Check, Columns3,
  X, Star,
} from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';

const TOTAL_STEPS = 2;

// ============== Step Indicator ==============

interface StepIndicatorProps {
  currentStep: number;
  totalSteps: number;
  onStepClick?: (step: number) => void;
  labels: [string, string];
}

const StepIndicator: React.FC<StepIndicatorProps> = ({ currentStep, totalSteps, onStepClick, labels }) => {
  const steps = [
    { number: 1, icon: Columns3, label: labels[0] },
    { number: 2, icon: Plus, label: labels[1] },
  ];

  return (
    <div className="flex items-center justify-center gap-2 mb-6">
      {steps.slice(0, totalSteps).map((step, index) => {
        const isActive = step.number === currentStep;
        const isCompleted = step.number < currentStep;
        const Icon = step.icon;

        return (
          <React.Fragment key={step.number}>
            <button
              type="button"
              onClick={() => onStepClick?.(step.number)}
              className={`flex items-center gap-2 px-3 py-1.5 rounded-full transition-all ${
                isActive
                  ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                  : isCompleted
                  ? 'bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 cursor-pointer hover:bg-emerald-500/30'
                  : 'bg-theme-tertiary text-theme-secondary cursor-pointer hover:bg-theme-secondary'
              }`}
            >
              {isCompleted ? (
                <Check className="h-4 w-4" />
              ) : (
                <Icon className="h-4 w-4" />
              )}
              <span className="text-sm font-medium hidden sm:inline">{step.label}</span>
            </button>
            {index < totalSteps - 1 && (
              <div className={`w-8 h-0.5 rounded-full ${
                step.number < currentStep ? 'bg-emerald-500' : 'bg-theme-tertiary'
              }`} />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
};

// ============== Type-specific Config ==============

export interface RatingConfigProps {
  max: number;
  onChange: (max: number) => void;
  label: string;
}

export const RatingConfig: React.FC<RatingConfigProps> = ({ max, onChange, label }) => (
  <div>
    <label className="block text-sm font-medium text-theme-primary mb-2">{label}</label>
    <div className="flex items-center gap-3">
      <input
        type="range"
        min={1}
        max={10}
        value={max}
        onChange={(e) => onChange(Number(e.target.value))}
        className="flex-1 accent-amber-500"
      />
      <div className="flex items-center gap-1 text-amber-500 min-w-[80px] justify-end">
        {Array.from({ length: max }).map((_, i) => (
          <Star key={i} className="h-3.5 w-3.5 fill-current" />
        ))}
      </div>
    </div>
  </div>
);

export interface SelectOptionItem {
  label: string;
  value: string;
  color?: string;
}

export interface SelectConfigProps {
  options: SelectOptionItem[];
  onChange: (options: SelectOptionItem[]) => void;
  labels: { options: string; add: string; placeholder: string };
}

export const SelectConfig: React.FC<SelectConfigProps> = ({ options, onChange, labels }) => {
  const [newOption, setNewOption] = useState('');
  const [newColor, setNewColor] = useState('#0ea5e9');

  const addOption = () => {
    const trimmed = newOption.trim();
    if (trimmed && !options.some(o => o.value === trimmed)) {
      onChange([...options, { label: trimmed, value: trimmed, color: newColor }]);
      setNewOption('');
    }
  };

  return (
    <div>
      <label className="block text-sm font-medium text-theme-primary mb-2">{labels.options}</label>
      <div className="flex flex-wrap gap-1.5 mb-2">
        {options.map((opt) => (
          <span
            key={opt.value}
            className="inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-sm text-white font-medium"
            style={{ backgroundColor: opt.color || '#0ea5e9' }}
          >
            {opt.label}
            <button
              type="button"
              onClick={() => onChange(options.filter((o) => o.value !== opt.value))}
              className="hover:opacity-70 transition-opacity"
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        ))}
      </div>
      <div className="flex gap-2 items-center">
        <Input
          value={newOption}
          onChange={(e) => setNewOption(e.target.value)}
          placeholder={labels.placeholder}
          className="flex-1"
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addOption(); } }}
        />
        <input
          type="color"
          value={newColor}
          onChange={(e) => setNewColor(e.target.value)}
          className="w-8 h-8 rounded cursor-pointer border border-theme"
        />
        <Button type="button" variant="outline" size="sm" onClick={addOption} disabled={!newOption.trim()}>
          {labels.add}
        </Button>
      </div>
    </div>
  );
};

export interface DateFormatConfigProps {
  dateFormat: 'date' | 'datetime' | 'time';
  onChange: (format: 'date' | 'datetime' | 'time') => void;
  label: string;
}

export const DateFormatConfig: React.FC<DateFormatConfigProps> = ({ dateFormat, onChange, label }) => (
  <div>
    <label className="block text-sm font-medium text-theme-primary mb-2">{label}</label>
    <select
      value={dateFormat}
      onChange={(e) => onChange(e.target.value as 'date' | 'datetime' | 'time')}
      className="w-48 h-9 rounded-md border border-theme bg-theme-primary px-2 text-sm text-theme-primary"
    >
      <option value="date">Date</option>
      <option value="datetime">Date + Time</option>
      <option value="time">Time only</option>
    </select>
  </div>
);

export type NumberFormatType = 'plain' | 'currency' | 'percentage';

export interface NumberFormatConfigProps {
  format: NumberFormatType;
  decimals: number;
  currencySymbol: string;
  onFormatChange: (format: NumberFormatType) => void;
  onDecimalsChange: (decimals: number) => void;
  onCurrencySymbolChange: (symbol: string) => void;
  labels: { format: string; decimals: string; symbol: string };
}

export const NumberFormatConfig: React.FC<NumberFormatConfigProps> = ({ format, decimals, currencySymbol, onFormatChange, onDecimalsChange, onCurrencySymbolChange, labels }) => (
  <div className="space-y-3">
    <div>
      <label className="block text-sm font-medium text-theme-primary mb-2">{labels.format}</label>
      <select
        value={format}
        onChange={(e) => onFormatChange(e.target.value as NumberFormatType)}
        className="w-48 h-9 rounded-md border border-theme bg-theme-primary px-2 text-sm text-theme-primary"
      >
        <option value="plain">Plain number</option>
        <option value="currency">Currency</option>
        <option value="percentage">Percentage</option>
      </select>
    </div>
    <div className="flex gap-4">
      <div>
        <label className="block text-sm font-medium text-theme-primary mb-2">{labels.decimals}</label>
        <Input
          type="number"
          min={0}
          max={6}
          value={decimals}
          onChange={(e) => onDecimalsChange(Math.max(0, Number(e.target.value) || 0))}
          className="w-20"
        />
      </div>
      {format === 'currency' && (
        <div>
          <label className="block text-sm font-medium text-theme-primary mb-2">{labels.symbol}</label>
          <Input
            value={currencySymbol}
            onChange={(e) => onCurrencySymbolChange(e.target.value)}
            className="w-20"
            placeholder="$"
          />
        </div>
      )}
    </div>
  </div>
);

export interface ProgressConfigProps {
  max: number;
  onChange: (max: number) => void;
  label: string;
}

export const ProgressConfig: React.FC<ProgressConfigProps> = ({ max, onChange, label }) => (
  <div>
    <label className="block text-sm font-medium text-theme-primary mb-2">{label}</label>
    <Input
      type="number"
      min={1}
      value={max}
      onChange={(e) => onChange(Math.max(1, Number(e.target.value) || 100))}
      className="w-32"
    />
  </div>
);

// ============== Main Component ==============

const presetMap = new Map(COLUMN_STYLE_PRESETS.map(p => [p.id, p]));

export interface AddColumnModalProps {
  isOpen: boolean;
  isAdding: boolean;
  columnName: string;
  selectedStyle: ColumnStylePreset;
  onClose: () => void;
  onAdd: () => void;
  onColumnNameChange: (name: string) => void;
  onStyleChange: (style: ColumnStylePreset) => void;
}

export function AddColumnModal({
  isOpen,
  isAdding,
  columnName,
  selectedStyle,
  onClose,
  onAdd,
  onColumnNameChange,
  onStyleChange,
}: AddColumnModalProps) {
  const t = useTranslations('modals.addColumn');

  const [currentStep, setCurrentStep] = useState(1);
  const [mounted, setMounted] = useState(false);

  // Type-specific config state
  const [ratingMax, setRatingMax] = useState<number>((selectedStyle.display?.max as number) ?? 5);
  const [selectOptions, setSelectOptions] = useState<SelectOptionItem[]>(() => {
    const opts = selectedStyle.display?.options;
    if (Array.isArray(opts)) return opts.map((o) => (typeof o === 'string' ? { label: o, value: o } : o));
    return [{ label: 'Pending', value: 'Pending', color: '#f97316' }, { label: 'Ready', value: 'Ready', color: '#0ea5e9' }, { label: 'Done', value: 'Done', color: '#22c55e' }];
  });
  const [progressMax, setProgressMax] = useState<number>((selectedStyle.display?.max as number) ?? 100);
  const [dateFormat, setDateFormat] = useState<'date' | 'datetime' | 'time'>((selectedStyle.display?.dateFormat as 'date' | 'datetime' | 'time') || 'date');
  const [numberFormat, setNumberFormat] = useState<'plain' | 'currency' | 'percentage'>((selectedStyle.display?.format as 'plain' | 'currency' | 'percentage') || 'plain');
  const [numberDecimals, setNumberDecimals] = useState<number>((selectedStyle.display?.decimals as number) ?? 0);
  const [currencySymbol, setCurrencySymbol] = useState<string>((selectedStyle.display?.currencySymbol as string) || '$');
  const [vectorDimension, setVectorDimension] = useState<number>((selectedStyle.display?.dimension as number) ?? 1536);
  const [vectorMetric, setVectorMetric] = useState<'cosine' | 'l2' | 'dot'>((selectedStyle.display?.metric as 'cosine' | 'l2' | 'dot') || 'cosine');

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Reset wizard when modal opens
  useEffect(() => {
    if (isOpen) {
      setCurrentStep(1);
    }
  }, [isOpen]);

  // Sync type-specific config when preset type changes (step 1 selection)
  const [lastPresetId, setLastPresetId] = useState(selectedStyle.id);
  useEffect(() => {
    if (selectedStyle.id === lastPresetId) return;
    setLastPresetId(selectedStyle.id);

    if (selectedStyle.visualType === 'rating') {
      setRatingMax((selectedStyle.display?.max as number) ?? 5);
    } else if (selectedStyle.visualType === 'select' || selectedStyle.visualType === 'multi_select') {
      const opts = selectedStyle.display?.options;
      if (Array.isArray(opts)) setSelectOptions(opts.map((o) => (typeof o === 'string' ? { label: o, value: o } : o)));
    } else if (selectedStyle.visualType === 'progress') {
      setProgressMax((selectedStyle.display?.max as number) ?? 100);
    } else if (selectedStyle.visualType === 'date') {
      setDateFormat((selectedStyle.display?.dateFormat as 'date' | 'datetime' | 'time') || 'date');
    } else if (selectedStyle.visualType === 'number') {
      setNumberFormat((selectedStyle.display?.format as 'plain' | 'currency' | 'percentage') || 'plain');
      setNumberDecimals((selectedStyle.display?.decimals as number) ?? 0);
      setCurrencySymbol((selectedStyle.display?.currencySymbol as string) || '$');
    } else if (selectedStyle.visualType === 'vector') {
      setVectorDimension((selectedStyle.display?.dimension as number) ?? 1536);
      setVectorMetric((selectedStyle.display?.metric as 'cosine' | 'l2' | 'dot') || 'cosine');
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedStyle.id]);

  // Sync config overrides back to parent style whenever they change
  useEffect(() => {
    if (currentStep !== 2) return;
    const display = { ...selectedStyle.display };
    let changed = false;

    if (selectedStyle.visualType === 'rating' && display.max !== ratingMax) {
      display.max = ratingMax;
      changed = true;
    } else if (selectedStyle.visualType === 'select' || selectedStyle.visualType === 'multi_select') {
      const current = display.options;
      if (JSON.stringify(current) !== JSON.stringify(selectOptions)) {
        display.options = selectOptions;
        changed = true;
      }
    } else if (selectedStyle.visualType === 'progress' && display.max !== progressMax) {
      display.max = progressMax;
      changed = true;
    } else if (selectedStyle.visualType === 'date' && display.dateFormat !== dateFormat) {
      display.dateFormat = dateFormat;
      changed = true;
    } else if (selectedStyle.visualType === 'number') {
      if (display.format !== numberFormat || display.decimals !== numberDecimals || display.currencySymbol !== currencySymbol) {
        display.format = numberFormat;
        display.decimals = numberDecimals;
        display.currencySymbol = currencySymbol;
        changed = true;
      }
    } else if (selectedStyle.visualType === 'vector') {
      if (display.dimension !== vectorDimension || display.metric !== vectorMetric) {
        display.dimension = vectorDimension;
        display.metric = vectorMetric;
        changed = true;
      }
    }

    if (changed) {
      onStyleChange({ ...selectedStyle, display });
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ratingMax, selectOptions, progressMax, dateFormat, numberFormat, numberDecimals, currencySymbol, vectorDimension, vectorMetric, currentStep]);

  const canProceedFromStep1 = !!selectedStyle;
  const canSubmit = columnName.trim().length > 0;

  const nextStep = () => {
    if (currentStep < TOTAL_STEPS && canProceedFromStep1) {
      setCurrentStep(2);
    }
  };

  const prevStep = () => {
    if (currentStep > 1) setCurrentStep(1);
  };

  const goToStep = (step: number) => {
    if (step === 1 || canProceedFromStep1) {
      setCurrentStep(step);
    }
  };

  const handleClose = () => {
    onClose();
    onColumnNameChange('');
    onStyleChange(COLUMN_STYLE_PRESETS[0]);
    setCurrentStep(1);
  };

  if (!isOpen || !mounted) return null;

  const SelectedIcon = selectedStyle.icon;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={handleClose}
    >
      <div
        className="w-full max-w-2xl bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme flex flex-col overflow-hidden max-h-[90vh]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-8 pt-8 pb-4 flex-shrink-0">
          <div className="text-center mb-4">
            {currentStep === 1 && (
              <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
                <Columns3 className="w-8 h-8 text-theme-primary" />
              </div>
            )}
            <h3 className="text-2xl font-semibold text-theme-primary">{t('title')}</h3>
            <p className="text-sm text-theme-secondary mt-1">
              {currentStep === 1 ? t('stepChooseTypeSubtitle') : t('stepConfigureSubtitle')}
            </p>
          </div>
          <StepIndicator
            currentStep={currentStep}
            totalSteps={TOTAL_STEPS}
            onStepClick={goToStep}
            labels={[t('stepChooseType'), t('stepConfigure')]}
          />
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-8 pb-4 min-h-0">
          {/* Step 1: Choose Type */}
          {currentStep === 1 && (
            <div className="animate-in fade-in-0 slide-in-from-right-4 duration-300">
              {PRESET_CATEGORIES.map((cat) => (
                <div key={cat.id} className="mb-4">
                  <p className="text-[10px] font-semibold uppercase tracking-widest text-theme-muted mb-2 px-0.5">
                    {t(`category.${cat.id}`)}
                  </p>
                  <div className="grid grid-cols-2 gap-3">
                    {cat.presetIds.map((pid) => {
                      const preset = presetMap.get(pid);
                      if (!preset) return null;
                      const Icon = preset.icon;
                      const isSelected = selectedStyle.id === pid;
                      // Vector columns are self-hosted-only: visible in cloud
                      // (discoverability) but disabled with a "CE only" badge.
                      // Server-authoritative - the backend rejects the type
                      // regardless of this UI gate.
                      const isCeOnly = pid === 'vector' && !IS_CE;
                      return (
                        <button
                          key={pid}
                          type="button"
                          disabled={isCeOnly}
                          title={isCeOnly ? t('ceOnlyTooltip') : undefined}
                          onClick={() => { if (!isCeOnly) onStyleChange(preset); }}
                          className={`group relative text-left rounded-2xl border p-4 transition-all ${
                            isCeOnly
                              ? 'border-slate-200 dark:border-slate-700/50 opacity-55 cursor-not-allowed'
                              : isSelected
                              ? 'border-[var(--accent-primary)] bg-[var(--accent-primary)]/5 ring-1 ring-[var(--accent-primary)]/20 hover:shadow-md'
                              : 'border-slate-200 dark:border-slate-700/50 hover:border-slate-300 dark:hover:border-slate-600 hover:shadow-md'
                          }`}
                        >
                          {isCeOnly && (
                            <span className="absolute top-2 right-2 rounded-full bg-theme-secondary px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-theme-muted">
                              {t('ceOnlyBadge')}
                            </span>
                          )}
                          {isSelected && !isCeOnly && (
                            <div className="absolute top-2 right-2">
                              <Check className="h-4 w-4 text-[var(--accent-primary)]" />
                            </div>
                          )}
                          <div className="flex items-center gap-3 mb-2">
                            <span className={`rounded-full p-2 ${
                              isSelected
                                ? 'bg-[var(--accent-primary)]/10 text-[var(--accent-primary)]'
                                : 'bg-theme-secondary text-theme-primary'
                            }`}>
                              <Icon className="h-4 w-4" />
                            </span>
                            <div>
                              <p className="text-sm font-semibold text-theme-primary">
                                {t(`types.${pid}.label`)}
                              </p>
                              <p className="text-xs text-theme-secondary">
                                {t(`types.${pid}.description`)}
                              </p>
                            </div>
                          </div>
                          <div className="rounded-xl bg-theme-secondary/30 p-2.5 flex items-center justify-center">
                            {renderPresetPreview(preset)}
                          </div>
                        </button>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Step 2: Configure */}
          {currentStep === 2 && (
            <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
              {/* Selected type recap */}
              <div className="rounded-xl border border-slate-200 dark:border-slate-700/50 bg-theme-secondary/50 overflow-hidden">
                <div className="flex items-center gap-3 px-4 py-3 border-b border-slate-200 dark:border-slate-700/50">
                  <div className="w-8 h-8 rounded-full bg-[var(--accent-primary)]/10 flex items-center justify-center">
                    <SelectedIcon className="h-4 w-4 text-[var(--accent-primary)]" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-theme-primary leading-tight">{t(`types.${selectedStyle.id}.label`)}</p>
                    <p className="text-xs text-theme-muted leading-tight mt-0.5">{t(`types.${selectedStyle.id}.description`)}</p>
                  </div>
                </div>
                <div className="px-5 py-5 bg-theme-primary flex items-center justify-center min-h-[64px]">
                  {renderPresetPreview(selectedStyle)}
                </div>
              </div>

              {/* Column name */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('columnName')} *
                </label>
                <Input
                  value={columnName}
                  onChange={(e) => onColumnNameChange(e.target.value)}
                  placeholder={t('columnNamePlaceholder')}
                  className="w-full"
                  autoFocus
                />
              </div>

              {/* Type-specific configuration */}
              {selectedStyle.visualType === 'rating' && (
                <RatingConfig max={ratingMax} onChange={setRatingMax} label={t('configRatingMax')} />
              )}
              {(selectedStyle.visualType === 'select' || selectedStyle.visualType === 'multi_select') && (
                <SelectConfig
                  options={selectOptions}
                  onChange={setSelectOptions}
                  labels={{
                    options: t('configSelectOptions'),
                    add: t('configSelectAdd'),
                    placeholder: t('configSelectPlaceholder'),
                  }}
                />
              )}
              {selectedStyle.visualType === 'progress' && (
                <ProgressConfig max={progressMax} onChange={setProgressMax} label={t('configProgressMax')} />
              )}
              {selectedStyle.visualType === 'date' && (
                <DateFormatConfig dateFormat={dateFormat} onChange={setDateFormat} label={t('configDateFormat')} />
              )}
              {selectedStyle.visualType === 'number' && (
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
              {selectedStyle.visualType === 'vector' && (
                <div className="space-y-3">
                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-1">{t('configVectorDimension')}</label>
                    <select
                      value={vectorDimension}
                      onChange={(e) => setVectorDimension(Number(e.target.value))}
                      className="w-full h-9 rounded-md border border-theme bg-theme-primary px-3 text-sm"
                    >
                      <option value={384}>384 (MiniLM)</option>
                      <option value={768}>768 (BERT, E5-base)</option>
                      <option value={1024}>1024 (E5-large, Cohere)</option>
                      <option value={1536}>1536 (OpenAI ada-002)</option>
                      <option value={2000}>2000</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-1">{t('configVectorMetric')}</label>
                    <select
                      value={vectorMetric}
                      onChange={(e) => setVectorMetric(e.target.value as 'cosine' | 'l2' | 'dot')}
                      className="w-full h-9 rounded-md border border-theme bg-theme-primary px-3 text-sm"
                    >
                      <option value="cosine">Cosine</option>
                      <option value="l2">L2 (Euclidean)</option>
                      <option value="dot">Dot Product</option>
                    </select>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-8 py-4 border-t border-theme flex justify-between flex-shrink-0">
          <Button
            variant="ghost"
            onClick={prevStep}
            disabled={currentStep === 1 || isAdding}
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            {t('back')}
          </Button>

          <div className="flex gap-2">
            <Button variant="outline" onClick={handleClose} disabled={isAdding}>
              {t('cancel')}
            </Button>
            {currentStep < TOTAL_STEPS ? (
              <Button onClick={nextStep} disabled={!canProceedFromStep1}>
                {t('next')}
                <ArrowRight className="h-4 w-4 ml-2" />
              </Button>
            ) : (
              <Button onClick={onAdd} disabled={!canSubmit || isAdding}>
                {isAdding ? (
                  <>
                    <LoadingSpinner size="xs" className="mr-2" />
                    {t('adding')}
                  </>
                ) : (
                  <>
                    <Check className="h-4 w-4 mr-2" />
                    {t('create')}
                  </>
                )}
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
