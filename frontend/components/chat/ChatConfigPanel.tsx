'use client';

import React, { useCallback, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronRight, Info, Loader2 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { ModelPicker } from '@/components/ai/ModelPicker';
import {
  getEffectiveDefaultSelectedModel,
  getModelsCache,
  isEmptySelectedModel,
  toNonBridgeSelectedModel,
} from '@/hooks/useModels';
import { useChatConfig, type ChatConfig } from '@/hooks/useChatConfig';

interface ChatConfigPanelProps {
  /** When provided, writes go to PUT /agents/{agentId}. */
  agentId?: string | null;
  /** Used as the fallback destination (PUT /conversations/{conversationId}) when no agentId. */
  conversationId?: string | null;
  /** When true, the advanced turn-limits block is rendered inline (no collapsible). */
  advancedOpenByDefault?: boolean;
  /** Optional compact layout flag - shrinks gaps for popover/tab usage. */
  compact?: boolean;
  /** When true, renders only the 3 turn-limit fields (no systemPrompt/temperature/tokens/tools/webSearch).
   *  Used by CreateAgentModal where those fields already exist in the main form. */
  limitsOnly?: boolean;
  /** When true, edits the persisted per-(user, workspace) defaults (V312) - the account
   *  Preferences page uses this. Takes precedence over agentId/conversationId. */
  userDefault?: boolean;
  onPendingConfigurationSave?: (save: Promise<unknown>) => void;
}

interface NumericField {
  key: keyof Pick<
    ChatConfig,
    | 'maxPerResourcePerTurn'
    | 'loopIdenticalStop'
    | 'loopConsecutiveStop'
  >;
  labelKey: string;
  infoKey: string;
  defaultValue: number;
  min: number;
  max: number;
}

const TURN_LIMIT_FIELDS: NumericField[] = [
  { key: 'maxPerResourcePerTurn', labelKey: 'maxPerResourcePerTurnLabel', infoKey: 'maxPerResourcePerTurnInfo', defaultValue: 5, min: 1, max: 100 },
  { key: 'loopIdenticalStop', labelKey: 'loopIdenticalStopLabel', infoKey: 'loopIdenticalStopInfo', defaultValue: 15, min: 2, max: 100 },
  { key: 'loopConsecutiveStop', labelKey: 'loopConsecutiveStopLabel', infoKey: 'loopConsecutiveStopInfo', defaultValue: 40, min: 4, max: 200 },
];

/**
 * Centralized configuration panel for a chat/agent.
 *
 * Used from:
 *   - MessageComposer → AttachmentHandler Options tab (conversation scope)
 *   - CreateAgentModal "Advanced mode" block (agent scope)
 *
 * Persistence is routed by `useChatConfig` based on whether `agentId` is provided.
 */
export function ChatConfigPanel({
  agentId,
  conversationId,
  advancedOpenByDefault = false,
  compact = false,
  limitsOnly = false,
  userDefault = false,
  onPendingConfigurationSave,
}: ChatConfigPanelProps) {
  const t = useTranslations('chatConfig');
  const { config, updateConfig, isLoading, isSaving, error, target } = useChatConfig({
    agentId: agentId ?? null,
    conversationId: conversationId ?? null,
    userDefault,
    onPendingSave: onPendingConfigurationSave,
  });
  const [advancedOpen, setAdvancedOpen] = useState(advancedOpenByDefault);
  // Summariser-model override visibility: null = follow the persisted pair
  // (hydrates async), true/false = the user touched the toggle this session.
  const [compactionModelOpen, setCompactionModelOpen] = useState<boolean | null>(null);

  const handleNumberChange = useCallback(
    (key: keyof ChatConfig, raw: string, fallback: number) => {
      const parsed = parseInt(raw, 10);
      updateConfig({ [key]: Number.isFinite(parsed) ? parsed : fallback } as Partial<ChatConfig>);
    },
    [updateConfig],
  );

  // Compaction (COLD-summary) override block - shared across the three render
  // branches (create-agent limitsOnly, account-default, composer advanced). A
  // binary toggle (the per-scope override) plus a cadence field shown only when on.
  const renderCompaction = () => {
    const enabled = config.compactionEnabled === true;
    // Both-or-neither summariser-model pair; a stored partial pair reads as unset.
    const hasModelOverride = Boolean(config.compactionModelProvider && config.compactionModelName);
    const modelOverrideOpen = compactionModelOpen ?? hasModelOverride;
    const handleModelOverrideToggle = (checked: boolean) => {
      setCompactionModelOpen(checked);
      if (checked) {
        // Persist the resolved default pair immediately so the toggle never
        // shows a picker whose displayed selection isn't actually saved.
        // Never seed a bridge pair: the summariser runs a bare single
        // completion which a CLI bridge cannot serve, so a bridge default
        // falls back to the first non-bridge provider's default model.
        const def = toNonBridgeSelectedModel(getEffectiveDefaultSelectedModel(), getModelsCache());
        if (!isEmptySelectedModel(def) && !hasModelOverride) {
          updateConfig({ compactionModelProvider: def.provider, compactionModelName: def.id });
        }
      } else {
        // Blank pair = clear back to inherit (agent columns cleared /
        // conversation + user-default keys omitted on rewrite).
        updateConfig({ compactionModelProvider: '', compactionModelName: '' });
      }
    };
    return (
      <div className="space-y-3">
        <div className="flex items-center justify-between gap-3">
          <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
            <span className="min-w-0">{t('compactionEnabledLabel')}</span>
            <InfoTooltip text={t('compactionEnabledInfo')} />
          </label>
          <Switch
            checked={enabled}
            onCheckedChange={(checked) => updateConfig({ compactionEnabled: checked })}
            aria-label={t('compactionEnabledLabel')}
          />
        </div>
        {enabled && (
          <>
            <NumericInput
              inline
              label={t('compactionAfterTurnsLabel')}
              info={t('compactionAfterTurnsInfo')}
              value={config.compactionAfterTurns ?? 5}
              onChange={(v) => handleNumberChange('compactionAfterTurns', String(v), 5)}
              min={1}
              max={100}
            />
            {/* Summariser-model override - off = inherit (agent conversations use the
                agent's compaction model if set, otherwise the platform default; the
                primary chat model is never a tier). Bridge providers are excluded:
                the summariser is a bare single completion no CLI bridge can serve. */}
            <div className="space-y-2">
              <div className="flex items-center justify-between gap-3">
                <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
                  <span className="min-w-0">{t('compactionModelLabel')}</span>
                  <InfoTooltip text={t('compactionModelInfo')} />
                </label>
                <Switch
                  checked={modelOverrideOpen}
                  onCheckedChange={handleModelOverrideToggle}
                  aria-label={t('compactionModelLabel')}
                />
              </div>
              {modelOverrideOpen ? (
                <ModelPicker
                  value={{
                    provider: config.compactionModelProvider ?? '',
                    id: config.compactionModelName ?? '',
                  }}
                  onChange={(next) =>
                    updateConfig({ compactionModelProvider: next.provider, compactionModelName: next.id })
                  }
                  providerLabel={t('compactionModelProviderLabel')}
                  modelLabel={t('compactionModelNameLabel')}
                  excludeBridgeProviders
                />
              ) : (
                <p className="text-xs text-theme-secondary">{t('compactionModelPlatformDefault')}</p>
              )}
            </div>
          </>
        )}
      </div>
    );
  };

  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8">
        <Loader2 className="h-4 w-4 animate-spin text-theme-secondary" />
      </div>
    );
  }

  const gap = compact ? 'space-y-3' : 'space-y-4';

  if (limitsOnly) {
    return (
      <TooltipProvider delayDuration={0}>
        <div className={`${gap} p-4`}>
          {isSaving && (
            <div className="flex justify-end">
              <Loader2 className="h-3 w-3 animate-spin text-theme-secondary" />
            </div>
          )}
          {error && (
            <div className="text-xs text-red-500 bg-red-50 dark:bg-red-900/20 rounded px-2 py-1">
              {error}
            </div>
          )}
          <p className="text-xs text-theme-secondary">{t('advancedSectionDescription')}</p>
          <div className="grid grid-cols-3 gap-3">
            {TURN_LIMIT_FIELDS.map((f) => (
              <NumericInput
                key={f.key}
                label={t(f.labelKey)}
                info={t(f.infoKey)}
                value={(config[f.key] as number | undefined) ?? f.defaultValue}
                onChange={(v) => handleNumberChange(f.key, String(v), f.defaultValue)}
                min={f.min}
                max={f.max}
              />
            ))}
          </div>
          <div className="pt-3 border-t border-theme">
            {renderCompaction()}
          </div>
        </div>
      </TooltipProvider>
    );
  }

  // Account Preferences ("Chat defaults") - aligned with the General Preferences
  // rows above it: each toggle/select is a title+description row with the control on
  // the right (platform Switch / Select), system prompt + temperature stack full
  // width, and "Advanced limits" is its own always-visible section (no collapse).
  if (userDefault) {
    const temperature = config.temperature ?? 0.7;
    return (
      <TooltipProvider delayDuration={0}>
        <div className="space-y-6">
          {isSaving && (
            <div className="flex justify-end">
              <Loader2 className="h-3 w-3 animate-spin text-theme-secondary" />
            </div>
          )}
          {error && (
            <div className="text-xs text-red-500 bg-red-50 dark:bg-red-900/20 rounded px-2 py-1">
              {error}
            </div>
          )}

          {/* System prompt - full width (long-form content can't sit in a right column). */}
          <div className="space-y-2">
            <h4 className="flex items-center gap-1.5 font-medium text-theme-primary">
              {t('systemPromptLabel')}
              <InfoTooltip text={t('systemPromptInfo')} />
            </h4>
            <Textarea
              value={config.systemPrompt ?? ''}
              onChange={(e) => updateConfig({ systemPrompt: e.target.value })}
              placeholder={t('systemPromptPlaceholder')}
              rows={3}
              className="resize-y"
            />
          </div>

          {/* Temperature - full-width slider (a 220px right-column track is too cramped). */}
          <div className="space-y-2">
            <h4 className="flex items-center gap-1.5 font-medium text-theme-primary">
              {t('temperatureLabel')}
              <InfoTooltip text={t('temperatureInfo')} />
            </h4>
            <Slider
              value={[temperature]}
              onValueChange={(values) => updateConfig({ temperature: values[0] })}
              min={0}
              max={2}
              step={0.1}
              className="w-full"
            />
            <div className="flex justify-between text-xs text-theme-secondary mt-1">
              <span>0</span>
              <span className="font-medium text-theme-primary">{temperature.toFixed(1)}</span>
              <span>2</span>
            </div>
          </div>

          {/* Max tokens / iterations / timeout / inactivity (2x2 grid) */}
          <div className="grid grid-cols-2 gap-3">
            <NumericInput
              label={t('maxTokensLabel')}
              info={t('maxTokensInfo')}
              value={config.maxTokens ?? 16000}
              onChange={(v) => updateConfig({ maxTokens: v })}
              min={1}
            />
            <NumericInput
              label={t('maxIterationsLabel')}
              info={t('maxIterationsInfo')}
              value={config.maxIterations ?? 100}
              onChange={(v) => updateConfig({ maxIterations: v })}
              min={1}
              max={1000}
            />
            <NumericInput
              label={t('executionTimeoutLabel')}
              info={t('executionTimeoutInfo')}
              value={config.executionTimeout ?? 3600}
              onChange={(v) => updateConfig({ executionTimeout: v })}
              min={10}
              max={7200}
            />
            <NumericInput
              label={t('inactivityTimeoutLabel')}
              info={t('inactivityTimeoutInfo')}
              value={config.inactivityTimeout ?? 300}
              onChange={(v) => updateConfig({ inactivityTimeout: v })}
              min={0}
              max={7200}
            />
          </div>

          {/* Tools mode */}
          <SettingRow title={t('toolsModeLabel')} info={t('toolsModeInfo')}>
            <Select
              value={config.toolsMode === 'none' ? 'none' : 'all'}
              onValueChange={(value) => updateConfig({ toolsMode: value })}
            >
              <SelectTrigger className="w-full sm:w-[200px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent className="z-[100000]">
                <SelectItem value="all">{t('toolsModeAll')}</SelectItem>
                <SelectItem value="none">{t('toolsModeNone')}</SelectItem>
              </SelectContent>
            </Select>
          </SettingRow>

          {/* Web search */}
          <SettingRow title={t('webSearchLabel')} info={t('webSearchInfo')}>
            <Switch
              checked={config.webSearch !== false}
              onCheckedChange={(checked) => updateConfig({ webSearch: checked })}
              aria-label={t('webSearchLabel')}
            />
          </SettingRow>

          {/* Image generation - opt-in (default off). */}
          <SettingRow title={t('imageGenerationLabel')} info={t('imageGenerationInfo')}>
            <Switch
              checked={config.imageGeneration?.enabled ?? false}
              onCheckedChange={(checked) =>
                updateConfig({
                  imageGeneration: { ...(config.imageGeneration ?? {}), enabled: checked },
                })
              }
              aria-label={t('imageGenerationLabel')}
            />
          </SettingRow>

          {/* Run sensitive actions without asking */}
          <SettingRow title={t('autoAuthorizeLabel')} info={t('autoAuthorizeInfo')}>
            <Switch
              checked={config.autoAuthorizeTools ?? false}
              onCheckedChange={(checked) => updateConfig({ autoAuthorizeTools: checked })}
              aria-label={t('autoAuthorizeLabel')}
            />
          </SettingRow>

          {/* Advanced limits - own always-visible section (no collapse on this page). */}
          <div className="space-y-4 pt-4 border-t border-theme">
            <div>
              <h4 className="font-medium text-theme-primary">{t('advancedSectionTitle')}</h4>
              <p className="text-sm text-theme-secondary">{t('advancedSectionDescription')}</p>
            </div>
            <div className="grid grid-cols-3 gap-3">
              {TURN_LIMIT_FIELDS.map((f) => (
                <NumericInput
                  key={f.key}
                  label={t(f.labelKey)}
                  info={t(f.infoKey)}
                  value={(config[f.key] as number | undefined) ?? f.defaultValue}
                  onChange={(v) => handleNumberChange(f.key, String(v), f.defaultValue)}
                  min={f.min}
                  max={f.max}
                />
              ))}
            </div>
            <div className="pt-2 border-t border-theme">
              {renderCompaction()}
            </div>
          </div>
        </div>
      </TooltipProvider>
    );
  }

  return (
    <TooltipProvider delayDuration={0}>
      <div className={`${gap} p-4`}>
        {/* Scope label - hidden for the user-default (account Preferences) target, which
            renders its own section header; we keep only the saving indicator there. */}
        {target === 'user-default' ? (
          isSaving && (
            <div className="flex justify-end">
              <Loader2 className="h-3 w-3 animate-spin text-theme-secondary" />
            </div>
          )
        ) : (
          <div className="flex items-center justify-between">
            <div className="text-xs font-medium text-theme-secondary uppercase tracking-wide">
              {target === 'agent'
                ? t('scopeAgent')
                : target === 'draft'
                  ? t('scopeDraft')
                  : t('scopeConversation')}
            </div>
            {isSaving && <Loader2 className="h-3 w-3 animate-spin text-theme-secondary" />}
          </div>
        )}
        {target === 'draft' && (
          <div className="text-xs text-theme-secondary bg-[var(--bg-secondary)] rounded px-2 py-1.5">
            {t('draftHint')}
          </div>
        )}

        {error && (
          <div className="text-xs text-red-500 bg-red-50 dark:bg-red-900/20 rounded px-2 py-1">
            {error}
          </div>
        )}

        {/* System prompt */}
        <div>
          <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
            {t('systemPromptLabel')}
            <InfoTooltip text={t('systemPromptInfo')} />
          </label>
          <textarea
            value={config.systemPrompt ?? ''}
            onChange={(e) => updateConfig({ systemPrompt: e.target.value })}
            placeholder={t('systemPromptPlaceholder')}
            rows={3}
            className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-3 py-2 text-sm text-theme-primary placeholder-theme-muted focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] resize-y"
          />
        </div>

        {/* Temperature */}
        <div>
          <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
            {t('temperatureLabel')}
            <InfoTooltip text={t('temperatureInfo')} />
          </label>
          <Slider
            value={[config.temperature ?? 0.7]}
            onValueChange={(values) => updateConfig({ temperature: values[0] })}
            min={0}
            max={2}
            step={0.1}
            className="w-full"
          />
          <div className="flex justify-between text-xs text-theme-secondary mt-1">
            <span>0</span>
            <span className="font-medium text-theme-primary">{(config.temperature ?? 0.7).toFixed(1)}</span>
            <span>2</span>
          </div>
        </div>

        {/* Max tokens / iterations / timeout / inactivity. In the narrow composer popover
            (compact) a multi-column grid squeezes each number input until the typed value is
            unreadable, so stack them one-per-row with the value box on the right; otherwise a
            2-column grid lays the four fields out 2x2. */}
        <div className={compact ? 'space-y-2.5' : 'grid grid-cols-2 gap-3'}>
          <NumericInput
            inline={compact}
            label={t('maxTokensLabel')}
            info={t('maxTokensInfo')}
            value={config.maxTokens ?? 16000}
            onChange={(v) => updateConfig({ maxTokens: v })}
            min={1}
          />
          <NumericInput
            inline={compact}
            label={t('maxIterationsLabel')}
            info={t('maxIterationsInfo')}
            value={config.maxIterations ?? 100}
            onChange={(v) => updateConfig({ maxIterations: v })}
            min={1}
            max={1000}
          />
          <NumericInput
            inline={compact}
            label={t('executionTimeoutLabel')}
            info={t('executionTimeoutInfo')}
            value={config.executionTimeout ?? 3600}
            onChange={(v) => updateConfig({ executionTimeout: v })}
            min={10}
            max={7200}
          />
          <NumericInput
            inline={compact}
            label={t('inactivityTimeoutLabel')}
            info={t('inactivityTimeoutInfo')}
            value={config.inactivityTimeout ?? 300}
            onChange={(v) => updateConfig({ inactivityTimeout: v })}
            min={0}
            max={7200}
          />
        </div>

        {/* Tools mode + web search */}
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
              {t('toolsModeLabel')}
              <InfoTooltip text={t('toolsModeInfo')} />
            </label>
            {/* Only All / No tools here. A custom tool list isn't a conversation-scope
                concept - it has no per-conversation storage or picker; tool curation is an
                agent-level feature (the agent editor). A legacy stored 'custom' is shown as
                'all' so the trigger never renders blank. */}
            <Select
              value={config.toolsMode === 'none' ? 'none' : 'all'}
              onValueChange={(value) => updateConfig({ toolsMode: value })}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent className="z-[100000]">
                <SelectItem value="all">{t('toolsModeAll')}</SelectItem>
                <SelectItem value="none">{t('toolsModeNone')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div>
            <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
              {t('webSearchLabel')}
              <InfoTooltip text={t('webSearchInfo')} />
            </label>
            <button
              type="button"
              onClick={() => updateConfig({ webSearch: !(config.webSearch ?? true) })}
              className="flex h-9 w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors"
            >
              <span>{config.webSearch === false ? t('disabled') : t('enabled')}</span>
              <span
                className={`relative w-8 h-4 rounded-full transition-colors flex-shrink-0 ${
                  config.webSearch === false ? 'bg-gray-300 dark:bg-gray-600' : 'bg-black dark:bg-white'
                }`}
              >
                <span
                  className={`absolute top-0.5 left-0.5 w-3 h-3 rounded-full bg-white dark:bg-black transition-transform ${
                    config.webSearch === false ? '' : 'translate-x-4'
                  }`}
                />
              </span>
            </button>
          </div>
          {/* Image generation - opt-in (default off; cost per image varies 5-134 credits).
              Agentic catalog calls (chat, image-gen) try the user's credential first and
              fall back to the platform credential when the user has none - a user with
              their own OpenAI/Gemini key burns 0 credits, platform-key calls are billed.
              The dispatcher handles the trace either way. */}
          <div>
            <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
              {t('imageGenerationLabel')}
              <InfoTooltip text={t('imageGenerationInfo')} />
            </label>
            <button
              type="button"
              onClick={() => updateConfig({
                imageGeneration: { ...(config.imageGeneration ?? {}), enabled: !(config.imageGeneration?.enabled ?? false) },
              })}
              className="flex h-9 w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors"
            >
              <span>{config.imageGeneration?.enabled ? t('enabled') : t('disabled')}</span>
              <span
                className={`relative w-8 h-4 rounded-full transition-colors flex-shrink-0 ${
                  config.imageGeneration?.enabled ? 'bg-black dark:bg-white' : 'bg-gray-300 dark:bg-gray-600'
                }`}
              >
                <span
                  className={`absolute top-0.5 left-0.5 w-3 h-3 rounded-full bg-white dark:bg-black transition-transform ${
                    config.imageGeneration?.enabled ? 'translate-x-4' : ''
                  }`}
                />
              </span>
            </button>
          </div>
          {/* Auto-authorize sensitive actions - general-chat scopes (conversation + the
              "defaults for next conversation" draft on the home composer). Hidden only for
              agent-backed chats (the gate is exempt there). Mirrors the "ne plus demander"
              checkbox on the authorization card: both write chatConfig.autoAuthorizeTools,
              which the backend turns into a "*" gate-skip. */}
          {target !== 'agent' && (
            <div>
              <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                {t('autoAuthorizeLabel')}
                <InfoTooltip text={t('autoAuthorizeInfo')} />
              </label>
              <button
                type="button"
                onClick={() => updateConfig({ autoAuthorizeTools: !(config.autoAuthorizeTools ?? false) })}
                className="flex h-9 w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors"
              >
                <span>{config.autoAuthorizeTools ? t('enabled') : t('disabled')}</span>
                <span
                  className={`relative w-8 h-4 rounded-full transition-colors flex-shrink-0 ${
                    config.autoAuthorizeTools ? 'bg-black dark:bg-white' : 'bg-gray-300 dark:bg-gray-600'
                  }`}
                >
                  <span
                    className={`absolute top-0.5 left-0.5 w-3 h-3 rounded-full bg-white dark:bg-black transition-transform ${
                      config.autoAuthorizeTools ? 'translate-x-4' : ''
                    }`}
                  />
                </span>
              </button>
            </div>
          )}
        </div>

        {/* Advanced mode - collapsible. The same 3 turn-limit overrides are available in
            both scopes (agent → V100 column; conversation → chatConfig.turnLimits JSONB).
            maxPerResourcePerTurn is applied uniformly to every tracked resource type
            (agent / skill / sub_agent / interface / workflow / table). */}
        <div className="pt-2 border-t border-theme">
          <button
            type="button"
            onClick={() => setAdvancedOpen((prev) => !prev)}
            className="flex items-center gap-1 text-sm font-medium text-theme-primary hover:text-[var(--accent-primary)] transition-colors w-full"
          >
            {advancedOpen ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            <span>{t('advancedSectionTitle')}</span>
          </button>
          {advancedOpen && (
            <div className={`${gap} mt-3`}>
              <p className="text-xs text-theme-secondary">{t('advancedSectionDescription')}</p>
              <div className={compact ? 'space-y-2.5' : 'grid grid-cols-2 gap-3'}>
                {TURN_LIMIT_FIELDS.map((f) => (
                  <NumericInput
                    key={f.key}
                    inline={compact}
                    label={t(f.labelKey)}
                    info={t(f.infoKey)}
                    value={(config[f.key] as number | undefined) ?? f.defaultValue}
                    onChange={(v) => handleNumberChange(f.key, String(v), f.defaultValue)}
                    min={f.min}
                    max={f.max}
                  />
                ))}
              </div>
              <div className="pt-1 border-t border-theme">
                {renderCompaction()}
              </div>
            </div>
          )}
        </div>
      </div>
    </TooltipProvider>
  );
}

// ============================================================================
// Helpers
// ============================================================================

/**
 * A title + description row with the control aligned to the right - the same
 * shape used by the General Preferences rows (Language, Notifications) so the
 * "Chat defaults" section lines up with them.
 */
function SettingRow({
  title,
  info,
  children,
}: {
  title: string;
  /** Tooltip text behind an ⓘ icon - same affordance as the NumericInput fields. */
  info: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 sm:gap-4">
      <div className="min-w-0">
        <h4 className="flex items-center gap-1.5 font-medium text-theme-primary">
          {title}
          <InfoTooltip text={info} />
        </h4>
      </div>
      <div className="flex-shrink-0">{children}</div>
    </div>
  );
}

function InfoTooltip({ text }: { text: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Info className="h-3.5 w-3.5 text-theme-secondary cursor-help" />
      </TooltipTrigger>
      <TooltipContent side="top" className="max-w-xs z-[100000]">
        <p className="text-xs">{text}</p>
      </TooltipContent>
    </Tooltip>
  );
}

interface NumericInputProps {
  label: string;
  info: string;
  value: number;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  /** Inline row layout (label left, wide value box right) for narrow popovers. */
  inline?: boolean;
}

function NumericInput({ label, info, value, onChange, min, max, inline = false }: NumericInputProps) {
  const input = (
    <Input
      type="number"
      value={value}
      onChange={(e) => {
        const parsed = parseInt(e.target.value, 10);
        if (Number.isFinite(parsed)) onChange(parsed);
      }}
      min={min}
      max={max}
      className={inline ? 'w-28 shrink-0 text-right' : 'w-full'}
    />
  );

  if (inline) {
    return (
      <div className="flex items-center justify-between gap-3">
        <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
          <span className="min-w-0">{label}</span>
          <InfoTooltip text={info} />
        </label>
        {input}
      </div>
    );
  }

  return (
    <div>
      <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
        {label}
        <InfoTooltip text={info} />
      </label>
      {input}
    </div>
  );
}

export default ChatConfigPanel;
