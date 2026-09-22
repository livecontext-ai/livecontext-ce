'use client';

import * as React from 'react';
import Image from 'next/image';
import clsx from 'clsx';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { ExpressionField, ConnectionProps } from '../ExpressionField';
import { OptionalSection } from '../OptionalSection';
import { ModelPicker } from '@/components/ai/ModelPicker';
import type { ModelCapability, SelectedModel } from '@/hooks/useModels';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData, ClassifyCategory } from '../../../types';
import { createDefaultClassifyCategories } from '../../../types';
import type { Connection } from '../useInspectorConnections';

/**
 * The two engines this node can run on, and the reason its picker is the only one that
 * asks for more than one kind of model.
 *
 * <p>A chat model reads the categories in a prompt and writes back a label; a decision
 * model scores every declared category in one pass and cannot write prose at all. The
 * node is the same either way (same categories, same output ports, same branches), so
 * both belong in one list. Offering only decision models would hide the engine that is
 * still the default and the fallback when the decision endpoint is unavailable.
 *
 * <p>Module-level so its identity is stable across renders: an inline array literal here
 * would re-filter the whole catalogue on every keystroke in this form.
 */
const CLASSIFY_ENGINES: readonly ModelCapability[] = ['chat', 'decision'];

/**
 * The catalogue slice the decision models live in.
 *
 * <p>Needed IN ADDITION to the capability filter above, and the distinction is the thing
 * to remember: the chat answer never contains a decision model (the server removes it
 * before the client sees anything, which is what keeps it out of every chat picker), so
 * a filter alone has nothing to let through. This names the slice to fetch; the filter
 * then says which of the merged result belongs here.
 */
const DECISION_MODEL_CATEGORY = 'classification';

/**
 * Providers whose models return a typed decision. Kept beside the category above: both
 * describe the same engine, one for fetching it and one for adapting the form to it.
 */
const DECISION_PROVIDERS: ReadonlySet<string> = new Set(['typesafe']);

// Default values
const DEFAULT_TEMPERATURE = 0.3; // Lower for more consistent classification
const DEFAULT_MAX_TOKENS = 1024;

interface ClassifyParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  getParamExpression: (key: string) => string;
  handleParamExpressionChange: (key: string, value: string) => void;
  // Connection handlers for ExpressionEditor
  connections?: Connection[];
  draggingFromHandle?: string | null;
  hoveredTargetHandle?: string | null;
  handleHandleClick?: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown?: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp?: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef?: (handleId: string, el: HTMLDivElement | null) => void;
}

export function ClassifyParametersForm({
  node,
  data,
  onUpdate,
  isRunMode = false,
  connectionProps,
  findUnknownVariables,
  getParamExpression,
  handleParamExpressionChange,
  connections = [],
  draggingFromHandle = null,
  hoveredTargetHandle = null,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: ClassifyParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const temperature = data.temperature ?? DEFAULT_TEMPERATURE;
  const maxTokens = data.maxTokens ?? DEFAULT_MAX_TOKENS;

  // A decision engine scores every category in one pass: it does no sampling, so the
  // sampling controls below are inert on it.
  //
  // Read off the PROVIDER, which is the only thing the node stores. The model's catalogue
  // `mode` would be the better signal and needs no list at all, but it lives on the
  // picker's catalogue entry rather than on the node, and this form holds only the
  // provider/model pair. So a second decision PROVIDER means one entry here; a second
  // decision model under this provider means none.
  const isDecisionEngine = DECISION_PROVIDERS.has((data.provider ?? '').toLowerCase());

  // Build the typed selection from the node's legacy two-field storage -
  // `data.provider` / `data.model` remain the persisted shape so existing
  // workflows don't need migration. The ModelPicker writes back through
  // the same two fields via handleModelPick.
  const modelSelection: SelectedModel = {
    provider: data.provider ?? '',
    id: data.model ?? '',
  };

  const handleModelPick = (next: SelectedModel) => {
    onUpdate({ ...data, provider: next.provider, model: next.id });
  };

  // Get current categories
  const categories: ClassifyCategory[] = data.classifyCategories ?? createDefaultClassifyCategories(data.id);

  // State for optional params section
  const [showOptionalParams, setShowOptionalParams] = React.useState(false);

  // Count optional params that have values (only temperature and maxTokens - prompt is backend-generated)
  const optionalParamsCount = React.useMemo(() => {
    let count = 0;
    if (data.temperature !== undefined && data.temperature !== DEFAULT_TEMPERATURE) count++;
    if (data.maxTokens !== undefined && data.maxTokens !== DEFAULT_MAX_TOKENS) count++;
    return count;
  }, [data.temperature, data.maxTokens]);

  const handleTemperatureChange = (value: string) => {
    const numValue = value === '' ? DEFAULT_TEMPERATURE : Math.min(2, Math.max(0, parseFloat(value) || DEFAULT_TEMPERATURE));
    onUpdate({ ...data, temperature: numValue });
  };

  const handleMaxTokensChange = (value: string) => {
    const numValue = value === '' ? DEFAULT_MAX_TOKENS : Math.min(128000, Math.max(1, parseInt(value) || DEFAULT_MAX_TOKENS));
    onUpdate({ ...data, maxTokens: numValue });
  };

  // Category management
  const handleAddCategory = () => {
    if (isRunMode) return;
    const newId = `${data.id}-category-${Date.now()}`;
    const newCategory: ClassifyCategory = {
      id: newId,
      label: `Category ${categories.length + 1}`,
      description: '',
    };
    onUpdate({
      ...data,
      classifyCategories: [...categories, newCategory],
    });
  };

  const handleDeleteCategory = (categoryId: string) => {
    if (isRunMode) return;
    // Find the index of the category to delete
    const categoryIndex = categories.findIndex(c => c.id === categoryId);
    // First two categories (index 0 and 1) are required and cannot be deleted
    if (categoryIndex < 2) return;
    onUpdate({
      ...data,
      classifyCategories: categories.filter(c => c.id !== categoryId),
    });
  };

  const handleRenameCategoryLabel = (categoryId: string, newLabel: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      classifyCategories: categories.map(c =>
        c.id === categoryId ? { ...c, label: newLabel } : c
      ),
    });
  };

  const handleUpdateCategoryDescription = (categoryId: string, newDescription: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      classifyCategories: categories.map(c =>
        c.id === categoryId ? { ...c, description: newDescription } : c
      ),
    });
  };

  // Get category handle ID for ExpressionEditor
  const getCategoryHandleId = (category: ClassifyCategory, index: number) => {
    return `category-${index}-${node.id}`;
  };

  return (
    <div className="space-y-5 pt-2">
      <ModelPicker
        value={modelSelection}
        onChange={handleModelPick}
        disabled={isRunMode}
        providerLabel={t('provider')}
        modelLabel={t('model')}
        costProfile="classifyStep"
        filterCapability={CLASSIFY_ENGINES}
        unionCategory={DECISION_MODEL_CATEGORY}
      />

      {/* Prompt - Required */}
      <ExpressionField
        label={t('classify.prompt')}
        value={getParamExpression('prompt')}
        onChange={(value) => handleParamExpressionChange('prompt', value)}
        nodeId={node.id}
        fieldName="param-prompt"
        isRequired={true}
        isRunMode={isRunMode}
        findUnknownVariables={findUnknownVariables}
        connectionProps={connectionProps}
        placeholder={t('classify.promptPlaceholder')}
        infoContent={t('classify.promptDescription')}
      />

      {/* Categories Section - styled like DecisionBranchesForm */}
      <div className="space-y-3 pt-2 pb-0">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <p className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('classify.categories')}</p>
            <Popover>
              <PopoverTrigger asChild>
                <button type="button" className="inline-flex items-center justify-center rounded-md hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                  <Info className="h-3 w-3 text-slate-400" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <p className="text-xs text-slate-600 dark:text-slate-300">{t('classify.categoriesDescription')}</p>
              </PopoverContent>
            </Popover>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
            onClick={handleAddCategory}
            disabled={isRunMode}
            title={isRunMode ? t('readOnlyInRunMode') : t('classify.addCategory')}
          >
            <Plus className="h-4 w-4" />
          </Button>
        </div>

        <div className="space-y-4">
          {categories.map((category, index) => {
            const categoryHandleId = getCategoryHandleId(category, index);

            return (
              <div key={category.id} className="space-y-2">
                <div className="flex flex-col gap-1">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded flex-shrink-0 bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300">
                      {index + 1}
                    </span>
                    <Input
                      className="flex w-full rounded-xl border-theme bg-[var(--bg-primary)] px-0 ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-[var(--accent-primary)] disabled:cursor-not-allowed disabled:opacity-50 text-sm font-semibold text-slate-500 dark:text-slate-400 h-6 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none"
                      value={category.label}
                      maxLength={50}
                      onChange={(e) => handleRenameCategoryLabel(category.id, e.target.value)}
                      onClick={(e) => e.stopPropagation()}
                      placeholder={t('classify.categoryPlaceholder')}
                      readOnly={isRunMode}
                    />
                    {/* Only show delete button for categories beyond the first two (index >= 2) */}
                    {index >= 2 ? (
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleDeleteCategory(category.id);
                        }}
                        disabled={isRunMode}
                        title={isRunMode ? t('readOnlyInRunMode') : t('classify.deleteCategory')}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    ) : (
                      <div className="w-8 h-8 flex-shrink-0" />
                    )}
                  </div>
                </div>
                <div className="space-y-2">
                  <div className="flex flex-col gap-2">
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('classify.descriptionLabel')}</span>
                      {/* First two categories are required, others are optional */}
                      {index < 2 ? (
                        <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                      ) : (
                        <span className="text-sm text-slate-400 dark:text-slate-500">{t('optional')}</span>
                      )}
                    </div>
                    <ExpressionEditor
                      value={category.description || ''}
                      onChange={(value) => {
                        if (isRunMode) return;
                        handleUpdateCategoryDescription(category.id, value);
                      }}
                      placeholder={t('classify.descriptionPlaceholder')}
                      className="w-full"
                      unknownVariables={[]}
                      handleId={categoryHandleId}
                      connections={connections}
                      onHandleClick={handleHandleClick}
                      draggingFromHandle={draggingFromHandle}
                      onHandleMouseDown={handleHandleMouseDown}
                      onHandleMouseUp={handleHandleMouseUp}
                      hoveredTargetHandle={hoveredTargetHandle}
                      onSetHandleRef={handleSetHandleRef}
                      isRequired={index < 2}
                      readOnly={isRunMode}
                    />
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Optional Parameters. Hidden entirely on a decision engine: both of them are
          sampling controls, and a decision model does no sampling - it scores every
          declared category in one pass. Showing a knob that changes nothing is worse than
          showing none, and the agent-facing docs already say the engine ignores them, so
          leaving the UI behind would be the cross-layer divergence the 3-layer rule is
          meant to prevent, only inverted. */}
      {!isDecisionEngine && (
      <OptionalSection
        isOpen={showOptionalParams}
        onToggle={() => setShowOptionalParams(!showOptionalParams)}
        count={optionalParamsCount}
      >
        {/* Temperature */}
        <div className="space-y-2">
          <div className="flex items-center gap-1.5">
            <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('temperature')}</Label>
            <Popover>
              <PopoverTrigger asChild>
                <button type="button" className="inline-flex items-center justify-center rounded-md hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                  <Info className="h-3 w-3 text-slate-400" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <p className="text-xs text-slate-600 dark:text-slate-300">{t('classify.temperatureDescription')}</p>
              </PopoverContent>
            </Popover>
          </div>
          <Input
            type="number"
            min="0"
            max="2"
            step="0.1"
            value={temperature}
            onChange={(e) => handleTemperatureChange(e.target.value)}
            disabled={isRunMode}
          />
        </div>

        {/* Max Tokens */}
        <div className="space-y-2">
          <div className="flex items-center gap-1.5">
            <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('maxTokens')}</Label>
            <Popover>
              <PopoverTrigger asChild>
                <button type="button" className="inline-flex items-center justify-center rounded-md hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                  <Info className="h-3 w-3 text-slate-400" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <p className="text-xs text-slate-600 dark:text-slate-300">{t('classify.maxTokensDescription')}</p>
              </PopoverContent>
            </Popover>
          </div>
          <Input
            type="number"
            min="1"
            max="128000"
            step="1"
            value={maxTokens}
            onChange={(e) => handleMaxTokensChange(e.target.value)}
            disabled={isRunMode}
          />
        </div>
      </OptionalSection>
      )}
    </div>
  );
}
