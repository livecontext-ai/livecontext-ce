'use client';

import * as React from 'react';
import Image from 'next/image';
import { Plus, Trash2, ChevronDown, ChevronRight } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { ExpressionField, ConnectionProps } from '../ExpressionField';
import { OptionalSection } from '../OptionalSection';
import { ModelPicker } from '@/components/ai/ModelPicker';
import type { SelectedModel } from '@/hooks/useModels';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData, GuardrailRule, GuardrailType, GuardrailAction } from '../../../types';
import { createDefaultGuardrailRules } from '../../../types';
import { InfoPopover } from '@/components/ui/info-popover';

// Default values
const DEFAULT_TEMPERATURE = 0.2; // Lower for consistent validation
const DEFAULT_MAX_TOKENS = 512;

// Guardrail type options. Descriptions are translated in the component
// (workflowBuilder.forms.guardrail.typeDescriptions.<type>).
const GUARDRAIL_TYPES: { value: GuardrailType; label: string }[] = [
  { value: 'pii_detection', label: 'PII Detection' },
  { value: 'toxic_language', label: 'Toxic Language' },
  { value: 'prompt_injection', label: 'Prompt Injection' },
  { value: 'keyword_filter', label: 'Keyword Filter' },
  { value: 'regex_pattern', label: 'Regex Pattern' },
  { value: 'length_check', label: 'Length Check' },
  { value: 'topic_restriction', label: 'Topic Restriction' },
  { value: 'competitor_mention', label: 'Competitor Mention' },
  { value: 'custom', label: 'Custom Rule' },
];

// Rule types the backend checks exactly, without an AI call (a PII address is still
// judged by the model). Mirrors GuardrailRuleEvaluator on the orchestrator side.
const DETERMINISTIC_GUARDRAIL_TYPES: ReadonlySet<GuardrailType> = new Set<GuardrailType>([
  'keyword_filter', 'regex_pattern', 'length_check', 'pii_detection', 'custom', 'competitor_mention',
]);

// Action options. Descriptions are translated in the component
// (workflowBuilder.forms.guardrail.actionDescriptions.<action>).
const GUARDRAIL_ACTIONS: { value: GuardrailAction; label: string }[] = [
  { value: 'block', label: 'Block' },
  { value: 'sanitize', label: 'Sanitize' },
  { value: 'flag', label: 'Flag' },
];

// PII types for selection
const PII_TYPES = [
  { value: 'email', label: 'Email' },
  { value: 'phone', label: 'Phone' },
  { value: 'ssn', label: 'SSN' },
  { value: 'credit_card', label: 'Credit Card' },
  { value: 'address', label: 'Address' },
];

interface GuardrailParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  getParamExpression: (key: string) => string;
  handleParamExpressionChange: (key: string, value: string) => void;
}

export function GuardrailParametersForm({
  node,
  data,
  onUpdate,
  isRunMode = false,
  connectionProps,
  findUnknownVariables,
  getParamExpression,
  handleParamExpressionChange,
}: GuardrailParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const temperature = data.temperature ?? DEFAULT_TEMPERATURE;
  const maxTokens = data.maxTokens ?? DEFAULT_MAX_TOKENS;

  // Build the typed selection from the node's legacy two-field storage -
  // `data.provider` / `data.model` remain the persisted shape. ModelPicker
  // handles the provider/model cascade internally.
  const modelSelection: SelectedModel = {
    provider: data.provider ?? '',
    id: data.model ?? '',
  };

  const handleModelPick = (next: SelectedModel) => {
    onUpdate({ ...data, provider: next.provider, model: next.id });
  };

  // Get current rules
  const rules: GuardrailRule[] = data.guardrailRules ?? createDefaultGuardrailRules(data.id);

  // Track expanded rules
  const [expandedRules, setExpandedRules] = React.useState<Set<string>>(new Set([rules[0]?.id]));

  // State for optional params section
  const [showOptionalParams, setShowOptionalParams] = React.useState(false);

  // Count optional params that have values
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

  const toggleRuleExpanded = (ruleId: string) => {
    setExpandedRules(prev => {
      const next = new Set(prev);
      if (next.has(ruleId)) {
        next.delete(ruleId);
      } else {
        next.add(ruleId);
      }
      return next;
    });
  };

  // Rule management
  const handleAddRule = () => {
    if (isRunMode) return;
    const newId = `${data.id}-rule-${Date.now()}`;
    const newRule: GuardrailRule = {
      id: newId,
      type: 'keyword_filter',
      action: 'block',
      config: { keywordsExpression: '', mode: 'block' },
    };
    onUpdate({
      ...data,
      guardrailRules: [...rules, newRule],
    });
    setExpandedRules(prev => new Set([...prev, newId]));
  };

  const handleDeleteRule = (ruleId: string) => {
    if (isRunMode) return;
    if (rules.length <= 1) return; // Minimum 1 rule
    onUpdate({
      ...data,
      guardrailRules: rules.filter(r => r.id !== ruleId),
    });
  };

  const handleUpdateRule = (ruleId: string, updates: Partial<GuardrailRule>) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      guardrailRules: rules.map(r =>
        r.id === ruleId ? { ...r, ...updates } : r
      ),
    });
  };

  const handleChangeType = (ruleId: string, type: GuardrailType) => {
    // Reset config when type changes
    let config: GuardrailRule['config'] = {};
    switch (type) {
      case 'pii_detection':
        config = { piiTypes: ['email', 'phone', 'credit_card'] };
        break;
      case 'keyword_filter':
        config = { keywordsExpression: '', mode: 'block' };
        break;
      case 'length_check':
        config = { minLength: 1, maxLength: 10000 };
        break;
      case 'regex_pattern':
        config = { pattern: '', mode: 'require' };
        break;
      case 'topic_restriction':
      case 'competitor_mention':
        config = { topicsExpression: '' };
        break;
      case 'custom':
        config = { expression: '' };
        break;
    }
    handleUpdateRule(ruleId, { type, config });
  };

  // Render config editor based on rule type
  const renderConfigEditor = (rule: GuardrailRule, index: number) => {
    switch (rule.type) {
      case 'pii_detection':
        return (
          <div className="space-y-2">
            <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">PII Types to Detect</Label>
            <div className="flex flex-wrap gap-2">
              {PII_TYPES.map(pii => {
                const isSelected = rule.config?.piiTypes?.includes(pii.value as any) ?? false;
                return (
                  <button
                    key={pii.value}
                    type="button"
                    onClick={() => {
                      const current = rule.config?.piiTypes || [];
                      const updated = isSelected
                        ? current.filter(t => t !== pii.value)
                        : [...current, pii.value as any];
                      handleUpdateRule(rule.id, {
                        config: { ...rule.config, piiTypes: updated },
                      });
                    }}
                    disabled={isRunMode}
                    className={`px-2 py-1 text-xs rounded-lg border transition-colors ${
                      isSelected
                        ? 'bg-blue-100 dark:bg-blue-900/30 border-blue-300 dark:border-blue-700 text-blue-700 dark:text-blue-300'
                        : 'bg-slate-50 dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700'
                    }`}
                  >
                    {pii.label}
                  </button>
                );
              })}
            </div>
          </div>
        );

      case 'keyword_filter':
        return (
          <div className="space-y-3">
            <div className="space-y-2">
              <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">Mode</Label>
              <Select
                value={rule.config?.mode || 'block'}
                onValueChange={(value) => handleUpdateRule(rule.id, {
                  config: { ...rule.config, mode: value as 'block' | 'allow' },
                })}
                disabled={isRunMode}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="block">{t('guardrail.keywordModeBlock')}</SelectItem>
                  <SelectItem value="allow">{t('guardrail.keywordModeAllow')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">Keywords</Label>
                <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
              </div>
              <ExpressionEditor
                value={rule.config?.keywordsExpression || ''}
                onChange={(value) => {
                  if (isRunMode) return;
                  handleUpdateRule(rule.id, {
                    config: { ...rule.config, keywordsExpression: value },
                  });
                }}
                placeholder="spam, scam, hack..."
                className="w-full"
                unknownVariables={[]}
                handleId={`rule-${index}-keywords-${node.id}`}
                isRequired={true}
                readOnly={isRunMode}
              />
            </div>
          </div>
        );

      case 'regex_pattern':
        return (
          <div className="space-y-3">
          <div className="space-y-2">
            <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('guardrail.mode')}</Label>
            <Select
              value={rule.config?.mode === 'block' ? 'block' : 'require'}
              onValueChange={(value) => handleUpdateRule(rule.id, {
                config: { ...rule.config, mode: value as 'require' | 'block' },
              })}
              disabled={isRunMode}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="require">{t('guardrail.regexModeRequire')}</SelectItem>
                <SelectItem value="block">{t('guardrail.regexModeBlock')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">Regex Pattern</Label>
              <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
            </div>
            <ExpressionEditor
              value={rule.config?.pattern || ''}
              onChange={(value) => {
                if (isRunMode) return;
                handleUpdateRule(rule.id, {
                  config: { ...rule.config, pattern: value },
                });
              }}
              placeholder="^[a-zA-Z0-9]+$"
              className="w-full font-mono"
              unknownVariables={[]}
              handleId={`rule-${index}-pattern-${node.id}`}
              isRequired={true}
              readOnly={isRunMode}
            />
          </div>
          </div>
        );

      case 'length_check':
        return (
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-2">
              <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">Min Length</Label>
              <Input
                type="number"
                min="0"
                value={rule.config?.minLength ?? 1}
                onChange={(e) => handleUpdateRule(rule.id, {
                  config: { ...rule.config, minLength: parseInt(e.target.value) || 0 },
                })}
                disabled={isRunMode}
              />
            </div>
            <div className="space-y-2">
              <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">Max Length</Label>
              <Input
                type="number"
                min="1"
                value={rule.config?.maxLength ?? 10000}
                onChange={(e) => handleUpdateRule(rule.id, {
                  config: { ...rule.config, maxLength: parseInt(e.target.value) || 10000 },
                })}
                disabled={isRunMode}
              />
            </div>
          </div>
        );

      case 'topic_restriction':
      case 'competitor_mention':
        return (
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {rule.type === 'competitor_mention' ? 'Competitor Names' : 'Blocked Topics'}
              </Label>
              <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
            </div>
            <ExpressionEditor
              value={rule.config?.topicsExpression || ''}
              onChange={(value) => {
                if (isRunMode) return;
                handleUpdateRule(rule.id, {
                  config: { ...rule.config, topicsExpression: value },
                });
              }}
              placeholder={rule.type === 'competitor_mention' ? 'CompanyA, CompanyB...' : 'politics, violence...'}
              className="w-full"
              unknownVariables={[]}
              handleId={`rule-${index}-topics-${node.id}`}
              isRequired={true}
              readOnly={isRunMode}
            />
          </div>
        );

      case 'custom':
        return (
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">Validation Expression</Label>
              <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
            </div>
            <ExpressionEditor
              value={rule.config?.expression || ''}
              onChange={(value) => {
                if (isRunMode) return;
                handleUpdateRule(rule.id, {
                  config: { ...rule.config, expression: value },
                });
              }}
              placeholder="#length(#input) > 10"
              className="w-full font-mono"
              unknownVariables={[]}
              handleId={`rule-${index}-expression-${node.id}`}
              isRequired={true}
              readOnly={isRunMode}
            />
          </div>
        );

      default:
        return null;
    }
  };

  return (
    <div className="space-y-5 pt-2">
      <ModelPicker
        value={modelSelection}
        onChange={handleModelPick}
        disabled={isRunMode}
        providerLabel={t('provider')}
        modelLabel={t('model')}
        costProfile="guardrailCheck"
      />

      {/* Input to Validate - Required */}
      <ExpressionField
        label={t('guardrail.inputToValidate')}
        value={getParamExpression('guardrailParams')}
        onChange={(value) => handleParamExpressionChange('guardrailParams', value)}
        nodeId={node.id}
        fieldName="param-guardrailParams"
        isRequired={true}
        isRunMode={isRunMode}
        findUnknownVariables={findUnknownVariables}
        connectionProps={connectionProps}
        placeholder="{{trigger:webhook.output.message}}"
      />

      {/* Rules Section */}
      <div className="space-y-3 pt-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <p className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('guardrail.validationRules')}</p>
            <InfoPopover label={t('guardrail.validationRules')} size="sm" side="right" align="start" contentClassName="w-[280px] p-3">
              <p className="text-xs text-slate-600 dark:text-slate-300">
                {t('guardrail.rulesDescription')}
              </p>
            </InfoPopover>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
            onClick={handleAddRule}
            disabled={isRunMode}
            title={t('guardrail.addRule')}
          >
            <Plus className="h-4 w-4" />
          </Button>
        </div>

        <div className="space-y-3">
          {rules.map((rule, index) => {
            const isExpanded = expandedRules.has(rule.id);
            const typeInfo = GUARDRAIL_TYPES.find(t => t.value === rule.type);

            return (
              <div
                key={rule.id}
                className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden"
              >
                {/* Rule header */}
                <div
                  className="flex items-center gap-2 px-3 py-2 bg-slate-50 dark:bg-slate-800/50 cursor-pointer"
                  onClick={() => toggleRuleExpanded(rule.id)}
                >
                  <button type="button" className="p-0.5">
                    {isExpanded ? (
                      <ChevronDown className="h-4 w-4 text-slate-500" />
                    ) : (
                      <ChevronRight className="h-4 w-4 text-slate-500" />
                    )}
                  </button>
                  <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase">
                    {index + 1}
                  </span>
                  <span className="text-sm font-medium text-slate-700 dark:text-slate-300 flex-1 truncate">
                    {typeInfo?.label || rule.type}
                  </span>
                  {rules.length > 1 && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20"
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDeleteRule(rule.id);
                      }}
                      disabled={isRunMode}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  )}
                </div>

                {/* Rule config (expanded) */}
                {isExpanded && (
                  <div className="px-3 py-3 space-y-3 border-t border-slate-200 dark:border-slate-700">
                    {/* Type selector */}
                    <div className="space-y-2">
                      <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">Type</Label>
                      <Select
                        value={rule.type}
                        onValueChange={(value) => handleChangeType(rule.id, value as GuardrailType)}
                        disabled={isRunMode}
                      >
                        <SelectTrigger className="w-full">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {GUARDRAIL_TYPES.map(type => (
                            <SelectItem key={type.value} value={type.value} description={t(`guardrail.typeDescriptions.${type.value}`)}>
                              {type.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <p className="text-xs text-slate-500 dark:text-slate-400" data-testid={`guardrail-rule-engine-${index}`}>
                        {DETERMINISTIC_GUARDRAIL_TYPES.has(rule.type)
                          ? t('guardrail.engineDeterministic')
                          : t('guardrail.engineModel')}
                      </p>
                    </div>

                    {/* Action selector */}
                    <div className="space-y-2">
                      <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">On Failure</Label>
                      <Select
                        value={rule.action}
                        onValueChange={(value) => handleUpdateRule(rule.id, { action: value as GuardrailAction })}
                        disabled={isRunMode}
                      >
                        <SelectTrigger className="w-full">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {GUARDRAIL_ACTIONS.map(action => (
                            <SelectItem key={action.value} value={action.value} description={t(`guardrail.actionDescriptions.${action.value}`)}>
                              {action.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>

                    {/* Type-specific config */}
                    {renderConfigEditor(rule, index)}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* Optional Parameters */}
      <OptionalSection
        isOpen={showOptionalParams}
        onToggle={() => setShowOptionalParams(!showOptionalParams)}
        count={optionalParamsCount}
      >
        {/* Temperature */}
        <div className="space-y-2">
          <div className="flex items-center gap-1.5">
            <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('temperature')}</Label>
            <InfoPopover label={t('temperature')} size="sm" side="right" align="start" contentClassName="w-[280px] p-3">
              <p className="text-xs text-slate-600 dark:text-slate-300">{t('guardrail.temperatureDescription')}</p>
            </InfoPopover>
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
            <InfoPopover label={t('maxTokens')} size="sm" side="right" align="start" contentClassName="w-[280px] p-3">
              <p className="text-xs text-slate-600 dark:text-slate-300">{t('guardrail.maxTokensDescription')}</p>
            </InfoPopover>
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
    </div>
  );
}
