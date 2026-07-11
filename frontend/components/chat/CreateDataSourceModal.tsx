'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  COLUMN_STYLE_PRESETS,
  PRESET_CATEGORIES,
  renderPresetPreview,
  type ColumnStylePreset,
} from '@/components/data-table/visualHelpers';
import {
  Plus, X, Table as TableIcon, Columns3,
  ArrowRight, ArrowLeft, Check,
  Settings, Star,
} from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import { useTranslations } from 'next-intl';
import { IS_CE } from '@/lib/edition';
import LoadingSpinner from '@/components/LoadingSpinner';

// ============== Types ==============

interface ColumnToAdd {
  id: string;
  name: string;
  preset: ColumnStylePreset;
  displayConfig?: Record<string, any>;
}

// Types that have advanced config options
const CONFIGURABLE_TYPES = new Set(['date', 'number', 'select', 'multi_select', 'rating', 'progress', 'sentiment']);

interface CreateDataSourceModalProps {
  onClose: () => void;
  onDataSourceCreated: (dataSourceId: number) => void;
}

// ============== Step Indicator ==============

const TOTAL_STEPS = 2;
const presetMap = new Map(COLUMN_STYLE_PRESETS.map(p => [p.id, p]));

interface StepIndicatorProps {
  currentStep: number;
  onStepClick: (step: number) => void;
  labels: [string, string];
}

const StepIndicator: React.FC<StepIndicatorProps> = ({ currentStep, onStepClick, labels }) => {
  const steps = [
    { number: 1, icon: TableIcon, label: labels[0] },
    { number: 2, icon: Columns3, label: labels[1] },
  ];

  return (
    <div className="flex items-center justify-center gap-2 mb-6">
      {steps.map((step, index) => {
        const isActive = step.number === currentStep;
        const isCompleted = step.number < currentStep;
        const Icon = step.icon;

        return (
          <React.Fragment key={step.number}>
            <button
              type="button"
              onClick={() => onStepClick(step.number)}
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
            {index < TOTAL_STEPS - 1 && (
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

// ============== Column Config Inline ==============

interface SelectOptionItem { label: string; value: string; color?: string; }

interface ColumnConfigInlineProps {
  column: ColumnToAdd;
  onChange: (displayConfig: Record<string, any>) => void;
  ct: ReturnType<typeof useTranslations>;
}

const ColumnConfigInline: React.FC<ColumnConfigInlineProps> = ({ column, onChange, ct }) => {
  const type = column.preset.visualType;
  const config = column.displayConfig || {};

  if (type === 'date') {
    const dateFormat = (config.dateFormat as string) || 'date';
    return (
      <div className="pt-2">
        <label className="block text-xs font-medium text-theme-secondary mb-1">{ct('configDateFormat')}</label>
        <select
          value={dateFormat}
          onChange={(e) => onChange({ ...config, dateFormat: e.target.value })}
          className="w-40 h-9 rounded-lg border border-theme bg-theme-primary px-2 text-sm text-theme-primary"
        >
          <option value="date">Date</option>
          <option value="datetime">Date + Time</option>
          <option value="time">Time only</option>
        </select>
      </div>
    );
  }

  if (type === 'number') {
    const format = (config.format as string) || 'plain';
    const decimals = (config.decimals as number) ?? 0;
    const symbol = (config.currencySymbol as string) || '$';
    return (
      <div className="pt-2 flex flex-wrap gap-3 items-end">
        <div>
          <label className="block text-xs font-medium text-theme-secondary mb-1">{ct('configNumberFormat')}</label>
          <select
            value={format}
            onChange={(e) => onChange({ ...config, format: e.target.value })}
            className="w-36 h-9 rounded-lg border border-theme bg-theme-primary px-2 text-sm text-theme-primary"
          >
            <option value="plain">Plain</option>
            <option value="currency">Currency</option>
            <option value="percentage">Percentage</option>
          </select>
        </div>
        <div>
          <label className="block text-xs font-medium text-theme-secondary mb-1">{ct('configNumberDecimals')}</label>
          <Input type="number" min={0} max={6} value={decimals} onChange={(e) => onChange({ ...config, decimals: Math.max(0, Number(e.target.value) || 0) })} className="w-16 h-9" />
        </div>
        {format === 'currency' && (
          <div>
            <label className="block text-xs font-medium text-theme-secondary mb-1">{ct('configCurrencySymbol')}</label>
            <Input value={symbol} onChange={(e) => onChange({ ...config, currencySymbol: e.target.value })} className="w-16 h-9" placeholder="$" />
          </div>
        )}
      </div>
    );
  }

  if (type === 'select' || type === 'multi_select') {
    const options: SelectOptionItem[] = (config.options as SelectOptionItem[]) || [];
    const [newOpt, setNewOpt] = React.useState('');
    const [newColor, setNewColor] = React.useState('#0ea5e9');
    const addOpt = () => {
      const trimmed = newOpt.trim();
      if (trimmed && !options.some(o => o.value === trimmed)) {
        onChange({ ...config, options: [...options, { label: trimmed, value: trimmed, color: newColor }] });
        setNewOpt('');
      }
    };
    return (
      <div className="pt-2">
        <label className="block text-xs font-medium text-theme-secondary mb-1">{ct('configSelectOptions')}</label>
        {options.length > 0 && (
          <div className="flex flex-wrap gap-1 mb-2">
            {options.map(opt => (
              <span key={opt.value} className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs text-white font-medium" style={{ backgroundColor: opt.color || '#0ea5e9' }}>
                {opt.label}
                <button type="button" onClick={() => onChange({ ...config, options: options.filter(o => o.value !== opt.value) })} className="hover:opacity-70"><X className="h-2.5 w-2.5" /></button>
              </span>
            ))}
          </div>
        )}
        <div className="flex gap-2 items-center">
          <Input value={newOpt} onChange={(e) => setNewOpt(e.target.value)} placeholder={ct('configSelectPlaceholder')} className="flex-1 h-9" onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addOpt(); } }} />
          <input type="color" value={newColor} onChange={(e) => setNewColor(e.target.value)} className="w-7 h-7 rounded cursor-pointer border border-theme" />
          <Button type="button" variant="outline" size="sm" onClick={addOpt} disabled={!newOpt.trim()}>{ct('configSelectAdd')}</Button>
        </div>
      </div>
    );
  }

  if (type === 'rating') {
    const max = (config.max as number) || 5;
    return (
      <div className="pt-2">
        <label className="block text-xs font-medium text-theme-secondary mb-1">{ct('configRatingMax')}</label>
        <div className="flex items-center gap-3">
          <input type="range" min={1} max={10} value={max} onChange={(e) => onChange({ ...config, max: Number(e.target.value) })} className="flex-1 accent-amber-500" />
          <div className="flex items-center gap-0.5 text-amber-500 min-w-[60px] justify-end">
            {Array.from({ length: max }).map((_, i) => (<Star key={i} className="h-3 w-3 fill-current" />))}
          </div>
        </div>
      </div>
    );
  }

  if (type === 'progress') {
    const max = (config.max as number) || 100;
    return (
      <div className="pt-2">
        <label className="block text-xs font-medium text-theme-secondary mb-1">{ct('configProgressMax')}</label>
        <Input type="number" min={1} value={max} onChange={(e) => onChange({ ...config, max: Math.max(1, Number(e.target.value) || 100) })} className="w-24 h-9" />
      </div>
    );
  }

  if (type === 'sentiment') {
    const upLabel = (config.labels as any)?.up || 'Positive';
    const downLabel = (config.labels as any)?.down || 'Negative';
    return (
      <div className="pt-2 flex gap-3">
        <div>
          <label className="block text-xs font-medium text-theme-secondary mb-1">Thumbs up label</label>
          <Input value={upLabel} onChange={(e) => onChange({ ...config, labels: { ...(config.labels as any || {}), up: e.target.value } })} className="w-28 h-9" />
        </div>
        <div>
          <label className="block text-xs font-medium text-theme-secondary mb-1">Thumbs down label</label>
          <Input value={downLabel} onChange={(e) => onChange({ ...config, labels: { ...(config.labels as any || {}), down: e.target.value } })} className="w-28 h-9" />
        </div>
      </div>
    );
  }

  return null;
};

// ============== Inline Column Adder ==============

interface InlineColumnAdderProps {
  onAdd: (name: string, preset: ColumnStylePreset) => void;
  onCancel: () => void;
  t: ReturnType<typeof useTranslations>;      // createTable namespace
  ct: ReturnType<typeof useTranslations>;     // addColumn namespace (types, category)
}

const InlineColumnAdder: React.FC<InlineColumnAdderProps> = ({ onAdd, onCancel, t, ct }) => {
  const [colName, setColName] = useState('');
  const [selectedPreset, setSelectedPreset] = useState<ColumnStylePreset>(COLUMN_STYLE_PRESETS[0]);

  const handleAdd = () => {
    if (colName.trim()) {
      onAdd(colName.trim(), selectedPreset);
    }
  };

  return (
    <div className="rounded-2xl border border-theme bg-theme-secondary/30 p-4 space-y-4 animate-in fade-in-0 duration-200">
      {/* Column name */}
      <div>
        <label className="block text-sm font-medium text-theme-primary mb-1.5">{t('columnNameLabel')}</label>
        <Input
          type="text"
          value={colName}
          onChange={(e) => setColName(e.target.value)}
          placeholder={t('columnNamePlaceholder')}
          className="w-full"
          autoFocus
          onKeyDown={(e) => {
            if (e.key === 'Enter' && colName.trim()) handleAdd();
            if (e.key === 'Escape') onCancel();
          }}
        />
      </div>

      {/* Type picker (same grid as AddColumnModal) */}
      <div>
        <label className="block text-sm font-medium text-theme-primary mb-2">{t('columnTypeLabel')}</label>
        <div className="max-h-[280px] overflow-y-auto pr-1 space-y-3">
          {PRESET_CATEGORIES.map((cat) => (
            <div key={cat.id}>
              <p className="text-[10px] font-semibold uppercase tracking-widest text-theme-muted mb-1.5 px-0.5">
                {ct(`category.${cat.id}`)}
              </p>
              <div className="grid grid-cols-2 gap-2">
                {cat.presetIds.map((pid) => {
                  const preset = presetMap.get(pid);
                  if (!preset) return null;
                  const Icon = preset.icon;
                  const isSelected = selectedPreset.id === pid;
                  // Vector/embedding columns are self-hosted-only: shown in cloud
                  // (so users know the feature exists) but disabled with a "CE only"
                  // badge. Mirrors AddColumnModal; the backend rejects the type
                  // regardless of this UI gate.
                  const isCeOnly = pid === 'vector' && !IS_CE;
                  return (
                    <button
                      key={pid}
                      type="button"
                      disabled={isCeOnly}
                      title={isCeOnly ? ct('ceOnlyTooltip') : undefined}
                      onClick={() => { if (!isCeOnly) setSelectedPreset(preset); }}
                      className={`group relative text-left rounded-xl border p-3 transition-all ${
                        isCeOnly
                          ? 'border-slate-200 dark:border-slate-700/50 opacity-55 cursor-not-allowed'
                          : isSelected
                          ? 'border-[var(--accent-primary)] bg-[var(--accent-primary)]/5 ring-1 ring-[var(--accent-primary)]/20 hover:shadow-sm'
                          : 'border-slate-200 dark:border-slate-700/50 hover:border-slate-300 dark:hover:border-slate-600 hover:shadow-sm'
                      }`}
                    >
                      {isCeOnly && (
                        <span className="absolute top-1.5 right-1.5 rounded-full bg-theme-secondary px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-theme-muted">
                          {ct('ceOnlyBadge')}
                        </span>
                      )}
                      {isSelected && !isCeOnly && (
                        <div className="absolute top-1.5 right-1.5">
                          <Check className="h-3.5 w-3.5 text-[var(--accent-primary)]" />
                        </div>
                      )}
                      <div className="flex items-center gap-2 mb-1.5">
                        <span className={`rounded-full p-1.5 ${
                          isSelected && !isCeOnly
                            ? 'bg-[var(--accent-primary)]/10 text-[var(--accent-primary)]'
                            : 'bg-theme-secondary text-theme-primary'
                        }`}>
                          <Icon className="h-3.5 w-3.5" />
                        </span>
                        <p className="text-sm font-medium text-theme-primary">{ct(`types.${pid}.label`)}</p>
                      </div>
                      <div className="rounded-lg bg-theme-secondary/30 p-2 flex items-center justify-center">
                        {renderPresetPreview(preset)}
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Actions */}
      <div className="flex gap-2 pt-1">
        <Button type="button" variant="outline" size="sm" onClick={onCancel} className="flex-1">
          {t('cancel')}
        </Button>
        <Button type="button" size="sm" onClick={handleAdd} disabled={!colName.trim()} className="flex-1">
          <Check className="h-3.5 w-3.5 mr-1.5" />
          {t('addColumn')}
        </Button>
      </div>
    </div>
  );
};

// ============== Main Component ==============

export const CreateDataSourceModal: React.FC<CreateDataSourceModalProps> = ({
  onClose,
  onDataSourceCreated,
}) => {
  const t = useTranslations('modals.createTable');
  const ct = useTranslations('modals.addColumn');
  const [mounted, setMounted] = useState(false);
  const [currentStep, setCurrentStep] = useState(1);

  // Step 1: Table info
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  // Step 2: Columns
  const [columns, setColumns] = useState<ColumnToAdd[]>([]);
  const [showAddColumn, setShowAddColumn] = useState(false);
  const [expandedConfigId, setExpandedConfigId] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  const canProceedFromStep1 = name.trim().length > 0;

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

  const addColumn = (colName: string, preset: ColumnStylePreset) => {
    setColumns([...columns, { id: `col-${Date.now()}`, name: colName, preset }]);
    setShowAddColumn(false);
  };

  const removeColumn = (id: string) => {
    setColumns(columns.filter(col => col.id !== id));
    if (expandedConfigId === id) setExpandedConfigId(null);
  };

  const updateColumnConfig = (id: string, displayConfig: Record<string, any>) => {
    setColumns(prev => prev.map(col => col.id === id ? { ...col, displayConfig } : col));
  };

  const handleCreate = async () => {
    if (!name.trim()) return;

    try {
      setIsCreating(true);

      const dataSourceConfig = {
        name: name.trim(),
        description: description.trim(),
        sourceConfig: {},
        data: [],
        createdBy: 'user',
        mappingSpec: {},
      };

      const newDataSource = await orchestratorApi.createDataSource(dataSourceConfig);
      const dataSourceId = (newDataSource as any).id;

      if (columns.length > 0) {
        for (const column of columns) {
          try {
            // Mirror the add-column-to-existing-table flow (useColumnOperations):
            // the backend reads `display` (NOT `displayConfig`) and rejects columns
            // whose display contract is incomplete - e.g. a vector column missing
            // `display.dimension`. Start from the preset's defaults (vector →
            // {dimension:1536, metric:'cosine'}, select → default options, …) so
            // inline-added columns always carry a valid display, then layer the
            // user's inline overrides and the column name as the label.
            const extra = {
              structure: column.preset.structure,
              display: {
                ...(column.preset.display ?? {}),
                ...(column.displayConfig ?? {}),
                label: column.name,
              },
              ...(column.preset.defaultValue !== undefined
                ? { defaultValue: column.preset.defaultValue }
                : {}),
            };
            await orchestratorApi.createColumn(dataSourceId, {
              name: column.name,
              type: column.preset.visualType,
              ...extra,
            });
          } catch (err) {
            console.error(`Error adding column ${column.name}:`, err);
          }
        }
      }

      onDataSourceCreated(dataSourceId);
      onClose();
    } catch (err) {
      console.error('Error creating new data source:', err);
    } finally {
      setIsCreating(false);
    }
  };

  if (!mounted) return null;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-2xl bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] animate-in fade-in-0 zoom-in-95 duration-200 border border-theme flex flex-col overflow-hidden max-h-[90vh]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-8 pt-8 pb-4 flex-shrink-0">
          <div className="text-center mb-4">
            {currentStep === 1 && (
              <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
                <TableIcon className="w-8 h-8 text-theme-primary" />
              </div>
            )}
            <h3 className="text-xl font-semibold text-theme-primary">{t('title')}</h3>
            <p className="text-sm text-theme-secondary mt-1">
              {currentStep === 1 ? t('stepInfoSubtitle') : t('stepColumnsSubtitle')}
            </p>
          </div>
          <StepIndicator
            currentStep={currentStep}
            onStepClick={goToStep}
            labels={[t('stepInfo'), t('stepColumns')]}
          />
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-8 pb-4 min-h-0">
          {/* Step 1: Table Info */}
          {currentStep === 1 && (
            <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">{t('nameLabel')}</label>
                <Input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t('namePlaceholder')}
                  className="w-full"
                  autoFocus
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && canProceedFromStep1) nextStep();
                  }}
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">{t('descriptionLabel')}</label>
                <Input
                  type="text"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t('descriptionPlaceholder')}
                  className="w-full"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && canProceedFromStep1) nextStep();
                  }}
                />
              </div>
            </div>
          )}

          {/* Step 2: Columns */}
          {currentStep === 2 && (
            <div className="animate-in fade-in-0 slide-in-from-right-4 duration-300 space-y-4">
              {/* Added columns list */}
              {columns.length > 0 && (
                <div className="space-y-2">
                  {columns.map((column) => {
                    const Icon = column.preset.icon;
                    const hasConfig = CONFIGURABLE_TYPES.has(column.preset.visualType);
                    const isExpanded = expandedConfigId === column.id;
                    return (
                      <div
                        key={column.id}
                        className={`rounded-xl border bg-theme-secondary/50 transition-all ${isExpanded ? 'border-[var(--accent-primary)]/30' : 'border-slate-200 dark:border-slate-700/50'}`}
                      >
                        <div className="flex items-center justify-between p-3">
                          <div className="flex items-center gap-3">
                            <span className="rounded-full p-1.5 bg-[var(--accent-primary)]/10">
                              <Icon className="h-3.5 w-3.5 text-[var(--accent-primary)]" />
                            </span>
                            <div>
                              <span className="text-sm font-medium text-theme-primary">{column.name}</span>
                              <span className="text-xs text-theme-secondary ml-2">{ct(`types.${column.preset.id}.label`)}</span>
                            </div>
                          </div>
                          <div className="flex items-center gap-1">
                            {hasConfig && (
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                onClick={() => setExpandedConfigId(isExpanded ? null : column.id)}
                                className={`h-8 w-8 p-0 ${isExpanded ? 'text-[var(--accent-primary)]' : 'text-theme-secondary hover:text-theme-primary'}`}
                                title="Configure"
                              >
                                <Settings className="h-3.5 w-3.5" />
                              </Button>
                            )}
                            <Button
                              type="button"
                              variant="ghost"
                              size="sm"
                              onClick={() => removeColumn(column.id)}
                              className="h-8 w-8 p-0 hover:bg-red-100 hover:text-red-600"
                            >
                              <X className="h-3.5 w-3.5" />
                            </Button>
                          </div>
                        </div>
                        {isExpanded && (
                          <div className="px-3 pb-3 animate-in fade-in-0 duration-200">
                            <ColumnConfigInline
                              column={column}
                              onChange={(dc) => updateColumnConfig(column.id, dc)}
                              ct={ct}
                            />
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}

              {/* Add column section */}
              {showAddColumn ? (
                <InlineColumnAdder
                  onAdd={addColumn}
                  onCancel={() => setShowAddColumn(false)}
                  t={t}
                  ct={ct}
                />
              ) : (
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setShowAddColumn(true)}
                  className="w-full h-10 border-dashed"
                >
                  <Plus className="h-4 w-4 mr-2" />
                  {t('addColumn')}
                </Button>
              )}

              {columns.length === 0 && !showAddColumn && (
                <p className="text-xs text-theme-secondary text-center mt-2">
                  {t('columnsOptional')}
                </p>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-8 py-4 border-t border-theme flex justify-between flex-shrink-0">
          <Button
            variant="ghost"
            onClick={prevStep}
            disabled={currentStep === 1 || isCreating}
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            {t('back')}
          </Button>

          <div className="flex gap-2">
            <Button variant="outline" onClick={onClose} disabled={isCreating}>
              {t('cancel')}
            </Button>
            {currentStep < TOTAL_STEPS ? (
              <Button onClick={nextStep} disabled={!canProceedFromStep1}>
                {t('next')}
                <ArrowRight className="h-4 w-4 ml-2" />
              </Button>
            ) : (
              <Button onClick={handleCreate} disabled={!canProceedFromStep1 || isCreating}>
                {isCreating ? (
                  <>
                    <LoadingSpinner size="xs" className="mr-2" />
                    {t('creating')}
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
};
