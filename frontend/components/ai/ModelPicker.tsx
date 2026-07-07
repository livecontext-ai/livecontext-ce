'use client';

/**
 * ModelPicker - shared provider+model selector for all LLM-picking surfaces.
 *
 * Used by:
 * - `CreateAgentModal` (agent create/edit form)
 * - `AgentConfigurationPanel` (workflow agent node inspector)
 * - `ClassifyParametersForm` (workflow classify node inspector)
 * - `GuardrailParametersForm` (workflow guardrail node inspector)
 *
 * Centralising this widget eliminates the four near-identical copies that
 * previously drifted on casing / fallback semantics, and enforces the typed
 * {@link SelectedModel} contract at a single boundary: the component's own
 * `onChange` emits `{ provider, id }`, so a caller that stored two separate
 * strings on a legacy data shape is forced to normalise on both read and
 * write.
 *
 * The chat-side selectors (`ChatHeader`, `ModelSelectorDropdown`) render a
 * different UI (single combined dropdown with tier badges / pricing), so
 * they stay separate - they still use the same `SelectedModel` helpers.
 */

import * as React from 'react';
import Image from 'next/image';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select';
import {
  useVisibleModels,
  SelectedModel,
  toSelectedModel,
  ModelCapability,
  modelHasCapability,
  isBridgeModel,
} from '@/hooks/useModels';
import { getProviderIconSlug, getProviderDisplayName } from '@/lib/ai-providers/providerIcons';
import { ModelOptionDisplay, ModelInfoPopover } from '@/components/ai/ModelInfo';
import { NoProviderCta } from '@/components/ai/NoProviderCta';
import { IS_CE } from '@/lib/edition';

export interface ModelPickerProps {
  /** Current selection - `{ provider: '', id: '' }` when unset. */
  value: SelectedModel;
  /** Emits the full typed pair on every change (provider switch or model pick). */
  onChange: (next: SelectedModel) => void;
  /** Disable both dropdowns (e.g. run mode / read-only inspector). */
  disabled?: boolean;
  /** Optional i18n labels - fall back to English when not provided. */
  providerLabel?: string;
  modelLabel?: string;
  /** Extra class on the wrapper. */
  className?: string;
  /**
   * Restrict the picker to models that expose the requested capability.
   * Defaults to {@code 'chat'} (legacy behaviour: chat-completion LLMs).
   * Pass {@code 'image'} for image-gen surfaces (agent config /
   * ChatConfigPanel image-generation block) - the filter hides chat-only
   * models and providers that have zero matching models. Capabilities
   * are derived client-side from the model id (see {@code IMAGE_MODEL_IDS})
   * until the backend surfaces them on {@code /v3/chat/models}.
   */
  filterCapability?: ModelCapability;
  /**
   * Hide CLI-bridge providers (claude-code / codex / gemini-cli /
   * mistral-vibe) even for admins. Set it on surfaces that dispatch a bare
   * single completion (e.g. the compaction summariser): a CLI bridge can
   * never serve those - the backend rejects a bridge-linked pair with 400
   * BRIDGE_EXECUTION_NOT_RELAYABLE and a direct bridge pick fails at run
   * time. Default false (primary-model pickers keep the full catalog).
   */
  excludeBridgeProviders?: boolean;
}

/**
 * Two stacked {@code <Select>}s: provider first, then the models available
 * for that provider. Selection state flows through the typed
 * {@link SelectedModel} contract - no string concatenation, no manual
 * provider-from-id inference at the call-site.
 */
export function ModelPicker({
  value,
  onChange,
  disabled = false,
  providerLabel = 'Provider',
  modelLabel = 'Model',
  className,
  filterCapability = 'chat',
  excludeBridgeProviders = false,
}: ModelPickerProps) {
  const { providers, defaultModel, defaultProvider, isLoading, error } = useVisibleModels();

  // Apply the capability (and optional bridge-exclusion) filter ONCE at the
  // top: providers without any matching model are dropped entirely (so the
  // provider dropdown never shows an entry that resolves to an empty model
  // dropdown).
  const filteredProviders = React.useMemo(() => {
    return providers
      .map(p => ({
        ...p,
        models: p.models.filter(
          m =>
            modelHasCapability(m, filterCapability) &&
            (!excludeBridgeProviders ||
              !isBridgeModel({ providerKind: m.providerKind, provider: m.provider ?? p.name })),
        ),
      }))
      .filter(p => p.models.length > 0);
  }, [providers, filterCapability, excludeBridgeProviders]);

  // Resolve the current provider record with a cascading fallback:
  // 1) the caller-provided provider, if it exists in the FILTERED catalog
  // 2) the backend-declared default provider, if it has a matching model
  // 3) the first provider by displayOrder among filtered providers
  const currentProvider = React.useMemo(() => {
    if (value.provider && filteredProviders.some(p => p.name === value.provider)) {
      return value.provider;
    }
    if (defaultProvider && filteredProviders.some(p => p.name === defaultProvider)) {
      return defaultProvider;
    }
    return filteredProviders[0]?.name ?? '';
  }, [value.provider, filteredProviders, defaultProvider]);

  const currentProviderData = React.useMemo(
    () => filteredProviders.find(p => p.name === currentProvider),
    [filteredProviders, currentProvider],
  );

  const availableModels = currentProviderData?.models ?? [];

  const currentModelId = React.useMemo(() => {
    if (value.id && availableModels.some(m => m.id === value.id)) {
      return value.id;
    }
    // The provider-declared default may have been dropped by the capability /
    // bridge filters above - only honor it when it survived, otherwise fall
    // back to the first filtered model so the trigger never displays an
    // option the dropdown does not offer.
    const providerDefault = currentProviderData?.defaultModel;
    if (providerDefault && availableModels.some(m => m.id === providerDefault)) {
      return providerDefault;
    }
    return availableModels[0]?.id
      ?? defaultModel
      ?? '';
  }, [value.id, availableModels, currentProviderData, defaultModel]);

  const handleProviderChange = (providerName: string) => {
    const provider = filteredProviders.find(p => p.name === providerName);
    // Default to the provider's first capability-matching model. Bypass
    // provider.defaultModel since it may target a chat model the filter
    // would have excluded (e.g. defaultModel='gemini-2.5-flash' for a
    // google entry where filterCapability='image' wants gemini-2.5-flash-image).
    const nextId = provider?.models?.[0]?.id ?? '';
    onChange(toSelectedModel({ provider: providerName, id: nextId }));
  };

  const handleModelChange = (modelId: string) => {
    onChange(toSelectedModel({ provider: currentProvider, id: modelId }));
  };

  const selectedModel = React.useMemo(
    () => availableModels.find(m => m.id === currentModelId),
    [availableModels, currentModelId],
  );
  const selectedModelName = selectedModel?.name || currentModelId;

  // CE with ZERO usable models (no cloud link, no BYOK key - or none matching
  // the capability filter): replace the two dead Selects with the connect CTA.
  // Only once the catalog has actually resolved (not while loading, not on a
  // fetch error - a transient network failure is not an onboarding state).
  if (IS_CE && !isLoading && !error && filteredProviders.length === 0) {
    return (
      <div className={className}>
        <NoProviderCta variant="form" />
      </div>
    );
  }

  return (
    <div className={className}>
      <div className="space-y-2">
        <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {providerLabel}
        </Label>
        <Select
          value={currentProvider}
          onValueChange={handleProviderChange}
          disabled={disabled || isLoading}
        >
          <SelectTrigger className="w-full">
            <div className="flex items-center gap-2">
              {currentProvider && (
                <Image
                  src={`/icons/services/${getProviderIconSlug(currentProvider)}.svg`}
                  alt={currentProvider}
                  width={16}
                  height={16}
                  className="w-4 h-4 flex-shrink-0 rounded-full p-0.5 dark:bg-slate-100/10"
                />
              )}
              <span>{currentProvider ? getProviderDisplayName(currentProvider) : ''}</span>
            </div>
          </SelectTrigger>
          {/* This picker can be hosted inside the composer Options popover (z-[99999],
              AttachmentHandler) - the default SelectContent z-[10001] would paint the
              list BEHIND that host. z-[100000] matches ChatConfigPanel's convention. */}
          <SelectContent className="z-[100000]">
            {filteredProviders.map(provider => (
              <SelectItem key={provider.name} value={provider.name}>
                <div className="flex items-center gap-2">
                  <Image
                    src={`/icons/services/${getProviderIconSlug(provider.name)}.svg`}
                    alt={provider.name}
                    width={16}
                    height={16}
                    className="w-4 h-4 flex-shrink-0 rounded-full p-0.5 dark:bg-slate-100/10"
                  />
                  <span>{getProviderDisplayName(provider.name)}</span>
                </div>
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="space-y-2 mt-5">
        <div className="flex items-center justify-between gap-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {modelLabel}
          </Label>
          {selectedModel && <ModelInfoPopover model={selectedModel} />}
        </div>
        <Select
          key={currentProvider}
          value={currentModelId}
          onValueChange={handleModelChange}
          disabled={disabled || isLoading}
        >
          <SelectTrigger className="w-full">
            <div className="flex items-center gap-2 min-w-0 w-full">
              {currentProvider && (
                <Image
                  src={`/icons/services/${getProviderIconSlug(currentProvider)}.svg`}
                  alt={currentProvider}
                  width={16}
                  height={16}
                  className="w-4 h-4 flex-shrink-0 rounded-full p-0.5 dark:bg-slate-100/10"
                />
              )}
              {/* Show the full model info (tier · capabilities · context · price)
                  exactly like the dropdown rows, so the collapsed trigger and the
                  menu stay visually identical. */}
              {selectedModel ? (
                <ModelOptionDisplay model={selectedModel} />
              ) : (
                <span className="truncate text-sm font-medium">{selectedModelName}</span>
              )}
            </div>
          </SelectTrigger>
          <SelectContent className="z-[100000]">
            {availableModels.map(model => (
              <SelectItem key={model.id} value={model.id}>
                <div className="flex items-center gap-2 min-w-0 w-full">
                  <Image
                    src={`/icons/services/${getProviderIconSlug(currentProvider)}.svg`}
                    alt={currentProvider}
                    width={16}
                    height={16}
                    className="w-4 h-4 flex-shrink-0 rounded-full p-0.5 dark:bg-slate-100/10 mt-0.5"
                  />
                  <ModelOptionDisplay model={model} />
                </div>
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
