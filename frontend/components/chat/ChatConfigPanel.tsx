'use client';

import React, { useCallback, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronRight, Loader2 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { InfoPopover } from '@/components/ui/info-popover';
import { ModelPicker } from '@/components/ai/ModelPicker';
import {
  getEffectiveDefaultSelectedModel,
  getModelsCache,
  isEmptySelectedModel,
  toNonBridgeSelectedModel,
} from '@/hooks/useModels';
import { useChatConfig, type ChatConfig } from '@/hooks/useChatConfig';
import { track } from '@/lib/analytics/analytics';

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
 *   - (NOT CreateAgentModal: it renders its own native fields and no longer mounts this)
 *   - AgentChatDefaults, i.e. the Agents page "Settings" tab
 *     (userDefault scope - the per-(user, workspace) defaults. Settings > Overview >
 *     Preferences used to mount this too and now only links to that tab.)
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
          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
            <span className="min-w-0">{t('compactionEnabledLabel')}</span>
            <InfoPopover label={t('compactionEnabledLabel')}>{t('compactionEnabledInfo')}</InfoPopover>
          </span>
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
                <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
                  <span className="min-w-0">{t('compactionModelLabel')}</span>
                  <InfoPopover label={t('compactionModelLabel')}>{t('compactionModelInfo')}</InfoPopover>
                </span>
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
                  costProfile="chatConversation"
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
      <>
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
      </>
    );
  }

  // Account Preferences ("Chat defaults") - aligned with the General Preferences
  // rows above it: each toggle/select is a title+description row with the control on
  // the right (platform Switch / Select), system prompt + temperature stack full
  // width, and "Advanced limits" is its own always-visible section (no collapse).
  if (userDefault) {
    const temperature = config.temperature ?? 0.7;
    return (
      <>
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
              <InfoPopover label={t('systemPromptLabel')}>{t('systemPromptInfo')}</InfoPopover>
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
              <InfoPopover label={t('temperatureLabel')}>{t('temperatureInfo')}</InfoPopover>
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
              onValueChange={(value) => {
                updateConfig({ toolsMode: value });
                track('chat_config_updated', { setting: 'tools_mode', value });
              }}
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
              onCheckedChange={(checked) => {
                updateConfig({ webSearch: checked });
                track('chat_config_updated', { setting: 'web_search', value: Boolean(checked) });
              }}
              aria-label={t('webSearchLabel')}
            />
          </SettingRow>

          {/* Generation (image, video, audio, voice, music) - opt-in, and kept
              separate from the image toggle above on purpose: a per-second video
              model spends an order of magnitude more per call, so granting
              images must not silently grant it. */}
          <SettingRow title={t('generationLabel')} info={t('generationInfo')}>
            <Switch
              checked={config.generation?.enabled ?? false}
              onCheckedChange={(checked) =>
                updateConfig({
                  generation: { ...(config.generation ?? {}), enabled: checked },
                })
              }
              aria-label={t('generationLabel')}
            />
          </SettingRow>

          {/* Mailbox - opt-in, and the permissions row appears only once it is on:
              offering a mode for a capability the chat does not have reads as a
              setting that does nothing. */}
          <SettingRow title={t('mailboxLabel')} info={t('mailboxInfo')}>
            <Switch
              checked={config.mailbox?.enabled ?? false}
              onCheckedChange={(checked) =>
                updateConfig({
                  mailbox: { ...(config.mailbox ?? {}), enabled: checked },
                  // Granting it with no mode stated would mean full access, so the safe half
                  // is chosen for the user and left visible for them to widen. On the way OFF
                  // the mode is written back to 'read' rather than cleared: buildConversationPatch
                  // falls back to the CURRENT value for an undefined entry, so "clearing" it
                  // would leave a stale 'write' that comes back the next time this is switched on.
                  mailboxAccessMode: checked ? (config.mailboxAccessMode ?? 'read') : 'read',
                })
              }
              aria-label={t('mailboxLabel')}
            />
          </SettingRow>
          {config.mailbox?.enabled && (
            <SettingRow title={t('mailboxAccessLabel')} info={t('mailboxAccessInfo')}>
              <Switch
                // Absent means WRITE on the server, so the display has to say write too: showing
                // read-only for a stored config with no mode would report a restriction nothing
                // is enforcing. This panel always writes both keys, but it is not the only writer.
                checked={(config.mailboxAccessMode ?? 'write') === 'write'}
                onCheckedChange={(checked) =>
                  updateConfig({ mailboxAccessMode: checked ? 'write' : 'read' })
                }
                aria-label={t('mailboxAccessLabel')}
              />
            </SettingRow>
          )}

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
      </>
    );
  }

  return (
    <>
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
          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
            {t('systemPromptLabel')}
            <InfoPopover label={t('systemPromptLabel')}>{t('systemPromptInfo')}</InfoPopover>
          </span>
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
          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
            {t('temperatureLabel')}
            <InfoPopover label={t('temperatureLabel')}>{t('temperatureInfo')}</InfoPopover>
          </span>
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
            <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
              {t('toolsModeLabel')}
              <InfoPopover label={t('toolsModeLabel')}>{t('toolsModeInfo')}</InfoPopover>
            </span>
            {/* Only All / No tools here. A custom tool list isn't a conversation-scope
                concept - it has no per-conversation storage or picker; tool curation is an
                agent-level feature (the agent editor). A legacy stored 'custom' is shown as
                'all' so the trigger never renders blank. */}
            <Select
              value={config.toolsMode === 'none' ? 'none' : 'all'}
              onValueChange={(value) => {
                updateConfig({ toolsMode: value });
                track('chat_config_updated', { setting: 'tools_mode', value });
              }}
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
            <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
              {t('webSearchLabel')}
              <InfoPopover label={t('webSearchLabel')}>{t('webSearchInfo')}</InfoPopover>
            </span>
            <button
              type="button"
              onClick={() => updateConfig({ webSearch: !(config.webSearch ?? true) })}
              className="flex h-9 w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors"
            >
              <span>{config.webSearch === false ? t('disabled') : t('enabled')}</span>
              <Switch checked={config.webSearch !== false} presentational />
            </button>
          </div>
          {/* Generation covers image, video, audio, voice and music through one
              tool. Opt-in on its own: a per-second video model spends an order of
              magnitude more per call. */}
          <div>
            <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
              {t('generationLabel')}
              <InfoPopover label={t('generationLabel')}>{t('generationInfo')}</InfoPopover>
            </span>
            <button
              type="button"
              onClick={() => updateConfig({
                generation: { ...(config.generation ?? {}), enabled: !(config.generation?.enabled ?? false) },
              })}
              className="flex h-9 w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors"
            >
              <span>{config.generation?.enabled ? t('enabled') : t('disabled')}</span>
              <Switch checked={config.generation?.enabled ?? false} presentational />
            </button>
          </div>
          {/* Mailbox, on the general-chat scopes only, exactly like auto-authorize below.
              The ACCOUNT default merely seeds a conversation, so without a row here a chat
              that inherited a mailbox could never be narrowed or revoked from inside it.
              Hidden on an agent-backed chat: there the agent owns the setting and
              CreateAgentModal renders it, while this panel's agent read/write path
              (configFromAgent / buildAgentPatch) carries no mailbox at all, so a row here
              would read OFF whatever the agent holds and save nothing when clicked. */}
          {target !== 'agent' && (
            <>
              <div>
                <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                  {t('mailboxLabel')}
                  <InfoPopover label={t('mailboxLabel')}>{t('mailboxInfo')}</InfoPopover>
                </span>
                <button
                  type="button"
                  onClick={() => {
                    const enabled = !(config.mailbox?.enabled ?? false);
                    updateConfig({
                      mailbox: { ...(config.mailbox ?? {}), enabled },
                      // Granting with no mode stated means FULL access, so the safe half is
                      // chosen and left visible to widen. On the way off it is written back
                      // rather than cleared: buildConversationPatch falls back to the CURRENT
                      // value for an undefined entry, so a stale 'write' would return.
                      mailboxAccessMode: enabled ? (config.mailboxAccessMode ?? 'read') : 'read',
                    });
                  }}
                  aria-label={t('mailboxLabel')}
                  className="flex h-9 w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors"
                >
                  <span>{config.mailbox?.enabled ? t('enabled') : t('disabled')}</span>
                  <Switch checked={config.mailbox?.enabled ?? false} presentational />
                </button>
              </div>
              {config.mailbox?.enabled && (
                <div>
                  <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                    {t('mailboxAccessLabel')}
                    <InfoPopover label={t('mailboxAccessLabel')}>{t('mailboxAccessInfo')}</InfoPopover>
                  </span>
                  <button
                    type="button"
                    onClick={() => updateConfig({
                      mailboxAccessMode:
                        (config.mailboxAccessMode ?? 'write') === 'write' ? 'read' : 'write',
                    })}
                    aria-label={t('mailboxAccessLabel')}
                    className="flex h-9 w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors"
                  >
                    <span>
                      {(config.mailboxAccessMode ?? 'write') === 'write'
                        ? t('mailboxAccessWrite')
                        : t('mailboxAccessRead')}
                    </span>
                    <Switch
                      checked={(config.mailboxAccessMode ?? 'write') === 'write'}
                      presentational
                    />
                  </button>
                </div>
              )}
            </>
          )}
          {/* Auto-authorize sensitive actions - general-chat scopes (conversation + the
              "defaults for next conversation" draft on the home composer). Hidden only for
              agent-backed chats (the gate is exempt there). Mirrors the "ne plus demander"
              checkbox on the authorization card: both write chatConfig.autoAuthorizeTools,
              which the backend turns into a "*" gate-skip. */}
          {target !== 'agent' && (
            <div>
              <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                {t('autoAuthorizeLabel')}
                <InfoPopover label={t('autoAuthorizeLabel')}>{t('autoAuthorizeInfo')}</InfoPopover>
              </span>
              <button
                type="button"
                onClick={() => updateConfig({ autoAuthorizeTools: !(config.autoAuthorizeTools ?? false) })}
                className="flex h-9 w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-theme-primary hover:bg-[var(--bg-secondary)] transition-colors"
              >
                <span>{config.autoAuthorizeTools ? t('enabled') : t('disabled')}</span>
                <Switch checked={config.autoAuthorizeTools ?? false} presentational />
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
    </>
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
  /** Explanation behind the ⓘ icon (opens on click) - same affordance as the NumericInput fields. */
  info: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 sm:gap-4">
      <div className="min-w-0">
        <h4 className="flex items-center gap-1.5 font-medium text-theme-primary">
          {title}
          <InfoPopover label={title}>{info}</InfoPopover>
        </h4>
      </div>
      <div className="flex-shrink-0">{children}</div>
    </div>
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
        <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
          <span className="min-w-0">{label}</span>
          <InfoPopover label={label}>{info}</InfoPopover>
        </span>
        {input}
      </div>
    );
  }

  return (
    <div>
      <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
        {label}
        <InfoPopover label={label}>{info}</InfoPopover>
      </span>
      {input}
    </div>
  );
}

export default ChatConfigPanel;
