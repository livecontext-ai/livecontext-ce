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
import { UpgradeRequiredNotice } from '@/components/billing/UpgradeRequiredBadge';
import { useCategoryModels } from '@/hooks/useCategoryModels';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import { useModelCostBasis } from '@/lib/hooks/useModelCostBasis';
import type { CostProfileId } from '@/lib/billing/model-cost-estimate';
import { NoProviderCta } from '@/components/ai/NoProviderCta';
import { IS_CE } from '@/lib/edition';
import { cn } from '@/lib/utils';

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
   * Restrict the picker to models that expose the requested capability, or any of
   * several when given a list. Defaults to {@code 'chat'} (legacy behaviour:
   * chat-completion LLMs). Pass {@code 'image'} for image-gen surfaces (agent config /
   * ChatConfigPanel image-generation block) - the filter hides chat-only models and
   * providers that have zero matching models.
   *
   * <p>A LIST is for a surface that genuinely accepts more than one kind of model, and
   * the classify node is the case: it runs on a chat model or on a decision model, so it
   * passes {@code ['chat', 'decision']} and offers both families in one list. Asking for
   * {@code 'decision'} alone would hide the chat engine that is still the default and
   * the fallback when the decision endpoint is unavailable.
   *
   * <p>Capabilities come from the catalogue's {@code mode} when it has one, and fall
   * back to the client-side id list ({@code IMAGE_MODEL_IDS}) for rows that predate it.
   */
  filterCapability?: ModelCapability | readonly ModelCapability[];
  /**
   * A catalogue category to merge into the chat list, for a surface that runs a kind of
   * model the chat catalogue does not carry.
   *
   * <p>The classify node is the case: a decision model is deliberately absent from the
   * chat answer (it cannot hold a conversation, so the server strips it before the client
   * sees anything), and {@code filterCapability} can only subtract from what arrived. So
   * asking for both KINDS takes two things: this, to fetch the missing slice, and
   * {@code filterCapability}, to say which of the merged result belongs on this screen.
   */
  unionCategory?: string | null;
  /**
   * Hide CLI-bridge providers (claude-code / codex / gemini-cli /
   * mistral-vibe) even for admins. Set it on surfaces that dispatch a bare
   * single completion (e.g. the compaction summariser): a CLI bridge can
   * never serve those - the backend rejects a bridge-linked pair with 400
   * BRIDGE_EXECUTION_NOT_RELAYABLE and a direct bridge pick fails at run
   * time. Default false (primary-model pickers keep the full catalog).
   */
  excludeBridgeProviders?: boolean;
  /**
   * Which shape of work the credit estimate beside each model should price.
   * The surface knows what it is configuring and the caller must say so: an
   * agent that calls tools costs roughly a hundred times a classify step, so a
   * single default would be wrong on every screen but one. Defaults to the
   * agent conversation, the dearest case, so an un-updated call site
   * over-states rather than under-states the cost.
   */
  costProfile?: CostProfileId;
}

/**
 * The chat providers plus the ones an extra category brought, without duplicates.
 *
 * <p>A model can legitimately arrive from both lists, so the merge is by
 * provider name then by model id, and the chat entry wins: it carries the enrichment the
 * chat answer has always carried, and re-ordering it would move models under a user who
 * only asked for one more family. Returns the original array untouched when there is
 * nothing to merge, so every other picker keeps the exact identity it had.
 */
function mergeProviders(
  chatProviders: ReturnType<typeof useVisibleModels>['providers'],
  extra: ReturnType<typeof useVisibleModels>['providers'] | undefined,
): ReturnType<typeof useVisibleModels>['providers'] {
  if (!extra || extra.length === 0) return chatProviders;

  const merged = chatProviders.map(p => ({ ...p, models: [...p.models] }));
  const byName = new Map(merged.map(p => [p.name, p]));

  for (const provider of extra) {
    const existing = byName.get(provider.name);
    if (!existing) {
      merged.push({ ...provider, models: [...provider.models] });
      continue;
    }
    const seen = new Set(existing.models.map(m => m.id));
    for (const model of provider.models) {
      if (!seen.has(model.id)) existing.models.push(model);
    }
  }
  // Re-sorted, because the chat list arrives sorted by displayOrder and appending would
  // put a merged provider last whatever its rank says. Harmless while the decision
  // provider sorts last anyway, and silently wrong the day a merged slice ranks first.
  merged.sort((a, b) => (a.displayOrder ?? 999) - (b.displayOrder ?? 999));
  return merged;
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
  costProfile = 'chatConversation',
  unionCategory = null,
}: ModelPickerProps) {
  const { providers: chatProviders, defaultModel, defaultProvider, isLoading, error } =
    useVisibleModels();
  // A category the chat catalogue does not carry, merged in for surfaces that run more
  // than one kind of model. Null for every other picker, which then fetches nothing.
  const { data: extraCategoryModels } = useCategoryModels(unionCategory ?? null);
  const providers = React.useMemo(
    () => mergeProviders(chatProviders, extraCategoryModels?.providers),
    [chatProviders, extraCategoryModels],
  );
  // Asked ONCE for the whole list: the underlying balance is about the account,
  // and a query observer per option would be a waste of the same cached answer.
  // `blockedForModel` then refines that one answer per row without refetching,
  // because the Free plan's AI allowance pays for some models and not others.
  const { blockedForModel, freeTierForModel, prefersFreeTierModels } = useMonthlyCreditsCannotPay();
  // Same reasoning, same shape: one answer for the whole list, handed down to
  // the presentational rows rather than fetched behind each of them.
  const { basis: costBasis } = useModelCostBasis();

  // A stable identity for the requested capability, so an inline array literal from a
  // caller does not invalidate the memo below on every render.
  const capabilityKey = Array.isArray(filterCapability)
    ? filterCapability.join(',')
    : (filterCapability as ModelCapability);

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
    // capabilityKey, not filterCapability: a caller passing an inline array literal
    // hands a new identity on every render, which would make this memo recompute the
    // whole list every time and defeat the point of computing it once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [providers, capabilityKey, excludeBridgeProviders]);

  // V494: on a plan whose monthly credits are workflow-scoped, the models opened
  // to the free tier are the ones the account's AI allowance can actually pay
  // for, so they lead - both the provider list and each provider's models.
  //
  // A STABLE partition, not a re-sort: within each group the admin's global
  // drag-and-drop ranking is preserved exactly. The admin still decides the
  // order; this only decides which half a reader meets first.
  const orderedProviders = React.useMemo(() => {
    if (!prefersFreeTierModels) return filteredProviders;
    const freeFirst = <T,>(items: T[], isFree: (item: T) => boolean): T[] => [
      ...items.filter(isFree),
      ...items.filter(i => !isFree(i)),
    ];
    return freeFirst(
      filteredProviders.map(p => ({ ...p, models: freeFirst(p.models, m => m.freeTierEnabled === true) })),
      p => p.models.some(m => m.freeTierEnabled === true),
    );
  }, [filteredProviders, prefersFreeTierModels]);

  // Resolve the current provider record with a cascading fallback:
  // 1) the caller-provided provider, if it exists in the FILTERED catalog
  // 2) the backend-declared default provider, if it has a matching model
  // 3) the first provider by displayOrder among filtered providers
  const currentProvider = React.useMemo(() => {
    if (value.provider && orderedProviders.some(p => p.name === value.provider)) {
      return value.provider;
    }
    // V494 note: this deliberately does NOT steer a free-tier account away from the
    // declared default, even though the list below is ordered to put covered models
    // first. This component only DISPLAYS a fallback - it calls onChange from the two
    // change handlers and nowhere else - so a steered display would show one model
    // while the caller's saved value stays empty and the run uses the catalogue
    // default: a node inspector showing a model the run will not use. The surfaces
    // that steer the opening selection do it where it is real, by writing the value
    // (usePreferFreeTierModel for chat, the panels' own effectiveDefault effect).
    if (defaultProvider && orderedProviders.some(p => p.name === defaultProvider)) {
      return defaultProvider;
    }
    // filteredProviders, NOT orderedProviders: the free-tier partition changes which
    // provider sits at index 0, and this branch DISPLAYS a value without writing it.
    // Reading the fallback off the reordered list would put a model in the trigger
    // that the caller never stores, which is the one hazard the comment above is about.
    return filteredProviders[0]?.name ?? '';
  }, [value.provider, orderedProviders, filteredProviders, defaultProvider]);

  const currentProviderData = React.useMemo(
    () => orderedProviders.find(p => p.name === currentProvider),
    [orderedProviders, currentProvider],
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
    // Same reason as the provider above: fall back on the CATALOGUE order, so the
    // displayed model is the one an untouched picker actually resolves to.
    const unordered = filteredProviders.find(p => p.name === currentProvider)?.models ?? availableModels;
    return unordered[0]?.id
      ?? defaultModel
      ?? '';
  }, [value.id, availableModels, filteredProviders, currentProvider, currentProviderData, defaultModel]);

  const handleProviderChange = (providerName: string) => {
    const provider = orderedProviders.find(p => p.name === providerName);
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
  if (IS_CE && !isLoading && !error && orderedProviders.length === 0) {
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
                  className="w-4 h-4 flex-shrink-0 rounded-md p-0.5 dark:bg-slate-100/10"
                />
              )}
              <span>{currentProvider ? getProviderDisplayName(currentProvider) : ''}</span>
            </div>
          </SelectTrigger>
          {/* This picker can be hosted inside the composer Options popover (z-[99999],
              AttachmentHandler) - the default SelectContent z-[10001] would paint the
              list BEHIND that host. z-[100000] matches ChatConfigPanel's convention. */}
          <SelectContent className="z-[100000]">
            {orderedProviders.map(provider => (
              <SelectItem key={provider.name} value={provider.name}>
                <div className="flex items-center gap-2">
                  <Image
                    src={`/icons/services/${getProviderIconSlug(provider.name)}.svg`}
                    alt={provider.name}
                    width={16}
                    height={16}
                    className="w-4 h-4 flex-shrink-0 rounded-md p-0.5 dark:bg-slate-100/10"
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
          {selectedModel && (
            <ModelInfoPopover
              model={selectedModel}
              freeTier={freeTierForModel(selectedModel)}
              costBasis={costBasis}
              costProfile={costProfile}
            />
          )}
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
                  className="w-4 h-4 flex-shrink-0 rounded-md p-0.5 dark:bg-slate-100/10"
                />
              )}
              {/* Show the full model info (tier · capabilities · context · price)
                  exactly like the dropdown rows, so the collapsed trigger and the
                  menu stay visually identical. Both markers travel with it, and so
                  does the greyed name, since all three ride `upgradeRequired` and
                  `freeTier` into the shared row. What the trigger does NOT get is
                  the provider icon's fade: the icon here is the control's own
                  affordance rather than one row among many, and fading it reads as
                  a disabled field, which this is not. */}
              {selectedModel ? (
                <ModelOptionDisplay
                  model={selectedModel}
                  upgradeRequired={blockedForModel(selectedModel)}
                  freeTier={freeTierForModel(selectedModel)}
                  costBasis={costBasis}
                  costProfile={costProfile}
                />
              ) : (
                <span className="truncate text-sm font-medium">{selectedModelName}</span>
              )}
            </div>
          </SelectTrigger>
          <SelectContent className="z-[100000]">
            {availableModels.map(model => {
              // Asked once per row, as the composer menu does: the same answer is
              // needed by the dimming, the lock and the chip.
              const blocked = blockedForModel(model);
              const free = freeTierForModel(model);
              return (
              <SelectItem key={model.id} value={model.id}>
                <div
                  className="flex items-center gap-2 min-w-0 w-full"
                  data-testid={blocked ? 'model-row-blocked' : undefined}
                >
                  {/* Greyed exactly as the composer menu greys it: the NAME inside
                      the shared row, plus this decorative icon, and never the whole
                      subtree - the 11px meta line cannot afford the contrast. Still
                      selectable, because a top-up changes the verdict and because an
                      agent's model may well be chosen here by someone about to pay
                      for precisely that. */}
                  <Image
                    src={`/icons/services/${getProviderIconSlug(currentProvider)}.svg`}
                    alt={currentProvider}
                    width={16}
                    height={16}
                    className={cn(
                      'w-4 h-4 flex-shrink-0 rounded-md p-0.5 dark:bg-slate-100/10 mt-0.5',
                      blocked && 'opacity-50',
                    )}
                  />
                  <ModelOptionDisplay
                    model={model}
                    upgradeRequired={blocked}
                    freeTier={free}
                    costBasis={costBasis}
                    costProfile={costProfile}
                  />
                </div>
              </SelectItem>
              );
            })}
          </SelectContent>
        </Select>
        {/* Under the control, never inside it: a click in a listbox option
            cannot open a dialog without leaving the menu floating over it. */}
        <UpgradeRequiredNotice blocked={blockedForModel(selectedModel)} className="mt-1.5" />
      </div>
    </div>
  );
}
