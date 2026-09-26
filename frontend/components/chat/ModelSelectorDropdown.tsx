'use client';

/**
 * ModelSelectorDropdown - Compact model selector for the message composer.
 *
 * Shared by the main chat composer, the Workflow panel chat, and the AI Chat
 * side panel. The trigger sits inside the composer bubble, which is
 * `overflow-hidden` (rounded corners), so the menu is PORTALLED to <body> with
 * fixed coordinates - the same escape hatch AttachmentHandler uses - otherwise
 * the menu would be clipped by the bubble. Placement is adaptive (below when
 * there is room, which is the welcome view where the composer sits high on the
 * page; above when the composer is docked at the bottom) and left-clamped to the
 * viewport (the trigger is on the right of the composer, so a naive left-anchor
 * would overflow the edge).
 *
 * The positioning ref is owned INTERNALLY (one per instance). The welcome view
 * (/app/chat with no conversation) renders the composer twice - the desktop copy
 * at 22vh and a `display:none` mobile copy - so a single ref shared from the
 * caller resolved to the hidden mobile trigger, whose zero-size rect pinned the
 * menu at the viewport's top-left (0,0). The zero-size guard below also makes the
 * hidden copy render no menu at all, so only the visible composer shows one.
 *
 * Translation-free by design: the only user-facing strings (`changeModelTitle`
 * and the optional reasoning-effort labels) are passed in by the caller, so the
 * component renders without a NextIntl provider (panels mock/render it in tests).
 */

import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { clampMenuLeft } from '@/lib/utils/menuPlacement';
import { ChevronDown, RotateCcw } from 'lucide-react';
import Image from 'next/image';
import { cn } from '@/lib/utils';
import { track } from '@/lib/analytics/analytics';
import { PROVIDER_ICON_MAP, getProviderDisplayName } from '@/lib/ai-providers/providerIcons';
import { SelectedModel, modelMatches, selectedModelFromAIModel, AIModel } from '@/hooks/useModels';
import { ModelOptionDisplay, ModelInfoPopover } from '@/components/ai/ModelInfo';
import { useModelCostBasis } from '@/lib/hooks/useModelCostBasis';
import { REASONING_EFFORT_LEVELS, supportsReasoningEffort } from '@/lib/ai-providers/reasoningEffort';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue, SELECT_EMPTY_VALUE_SENTINEL } from '@/components/ui/select';
import { ServiceLogo } from '@/components/ui/service-logo';

// Re-exported for callers that imported it from this module historically.
export { PROVIDER_ICON_MAP };

/**
 * Each row inherits {@link AIModel} (capability flags, context window, rate
 * limits, and other metadata) so the shared {@link ModelOptionDisplay} can render the same
 * enriched metadata as the workflow inspector picker. The dropdown-specific
 * {@code iconSlug} is the only header-side overlay.
 */
type DropdownModel = AIModel & { iconSlug: string };

/** The four price tiers a catalogue model can carry, in the order the footer lists them. */
export const MODEL_TIER_ORDER = ['top', 'high', 'mid', 'budget'] as const;
export type ModelTierKey = (typeof MODEL_TIER_ORDER)[number];

/**
 * Caller-translated labels for the footer filters, so the component stays NextIntl-free
 * (see the file header). Built by {@code modelFilterLabelsFrom(t)} in the composers.
 */
export interface ModelFilterLabels {
  tier: string;
  provider: string;
  allTiers: string;
  allProviders: string;
  tiers: Record<ModelTierKey, string>;
  noMatch: string;
  clear: string;
  /** The footer's reset control, which puts every control in it back to its default. */
  reset: string;
}

/** Per-viewer convenience: the last tier / provider filter, remembered in this browser only. */
const FILTERS_STORAGE_KEY = 'lc.composer.modelFilters';

interface StoredFilters { tier?: string; provider?: string }

function readStoredFilters(): StoredFilters {
  try {
    const raw = window.localStorage.getItem(FILTERS_STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as StoredFilters;
    return {
      tier: typeof parsed.tier === 'string' ? parsed.tier : '',
      provider: typeof parsed.provider === 'string' ? parsed.provider : '',
    };
  } catch {
    return {};
  }
}

function writeStoredFilters(filters: StoredFilters): void {
  try {
    window.localStorage.setItem(FILTERS_STORAGE_KEY, JSON.stringify(filters));
  } catch {
    // A private window or a browser blocking site data: the filter still works for this menu.
  }
}

const MENU_WIDTH = 320;

type MenuPos =
  | { left: number; placement: 'above'; bottom: number; maxHeight: number }
  | { left: number; placement: 'below'; top: number; maxHeight: number };

export function ModelSelectorDropdown({
  showModelSelector,
  setShowModelSelector,
  selectedModel,
  selectedModelData,
  availableModels,
  setSelectedModel,
  changeModelTitle,
  noModelsLabel,
  emptyState,
  upgradeRequired = false,
  blockedForModel,
  freeTierForModel,
  prefersFreeTierModels = false,
  upgradeNotice,
  freeTierBadge,
  reasoningEffort,
  onReasoningEffortChange,
  reasoningEffortLabel,
  effortAutoLabel,
  filterLabels,
}: {
  showModelSelector: boolean;
  setShowModelSelector: (v: boolean) => void;
  selectedModel: SelectedModel;
  selectedModelData: { name: string; id: string } | undefined;
  availableModels: DropdownModel[];
  setSelectedModel: (model: SelectedModel) => void;
  changeModelTitle: string;
  /** Trigger label when the model list is EMPTY and nothing is selected -
   *  without it the trigger collapses to a blank chevron. Caller-translated,
   *  keeping this component NextIntl-free. */
  noModelsLabel?: string;
  /** Rendered inside the open menu instead of the (empty) model list - the
   *  composers inject {@code <NoProviderCta variant='menu' />} here so an
   *  unconfigured CE gets a way out instead of a blank menu. Injected as a
   *  node (not imported) to keep this component translation-free. */
  emptyState?: React.ReactNode;
  /** True when this account's credits cannot pay for what a model does, which
   *  marks every row. The verdict is the CALLER's to fetch: this component
   *  stays free of both translations and data hooks. */
  upgradeRequired?: boolean;
  /**
   * The same question, asked per model. The Free plan's monthly credits pay
   * for the models opened to the free tier and for no others, so the account-level
   * {@code upgradeRequired} would badge rows the account can already run. Also the
   * caller's to fetch, for the same reason: no data hook lives here. Absent falls
   * back to {@code upgradeRequired}.
   */
  blockedForModel?: (model: { freeTierEnabled?: boolean }) => boolean;
  /**
   * The mirror of {@code blockedForModel}: is this model free for this reader
   * right now? Caller-resolved for the same reason, and it must be
   * {@code useMonthlyCreditsCannotPay.freeTierForModel} - the verdict that also
   * weighs the monthly BALANCE, and whose doc says what goes wrong otherwise.
   * Absent = no model is marked, which is what every surface showed before this
   * existed.
   */
  freeTierForModel?: (model: { freeTierEnabled?: boolean }) => boolean;
  /** True when free-tier models should be offered first. Caller-resolved. */
  prefersFreeTierModels?: boolean;
  /** Rendered under the model list, saying why and linking to the plans. A
   *  node rather than an import, for the same reason as {@code emptyState}. */
  upgradeNotice?: React.ReactNode;
  /**
   * Rendered next to the model name on the CLOSED composer, when the model in
   * hand is one the reader's Free monthly credits cover.
   *
   * <p>The menu is where a choice is made, but the composer is where a new
   * account sits before it ever opens one: without this, the only place it could
   * learn that its current model costs nothing was a menu it had no reason to
   * open. A node rather than a flag for the same reason as {@code upgradeNotice}
   * - the words stay with the caller. Shown
   * only when the SELECTED model is covered, so a caller hands it down
   * unconditionally.
   */
  freeTierBadge?: React.ReactNode;
  /** Per-conversation reasoning-effort override. When `onReasoningEffortChange`
   *  is provided and the selected provider supports effort, an effort control is
   *  rendered in the menu footer, after the filters. Omitted by the panel chats. */
  reasoningEffort?: string;
  onReasoningEffortChange?: (effort: string) => void;
  reasoningEffortLabel?: string;
  effortAutoLabel?: string;
  /** Tier and provider filters in the menu footer, next to the effort control.
   *  Every composer passes them (built by {@code modelFilterLabelsFrom(t)});
   *  omitted = no filters, for a caller that has no translator to offer. */
  filterLabels?: ModelFilterLabels;
}) {
  // One answer for the whole menu, handed down to each presentational row: the
  // multiplier and the cost profiles are about the install, not about any one
  // model, so a query behind every option would re-observe the same cached
  // answer once per catalogue entry.
  const { basis: costBasis } = useModelCostBasis();

  // Free-tier models lead for an account on the Free plan. A STABLE partition, so the admin's catalogue order survives
  // inside each half. Driven by a PROP, not a hook: this component is
  // translation-free and data-hook-free by design (see the file header), and the
  // panels render it without a query client.
  const orderedModels = React.useMemo(() => {
    if (!prefersFreeTierModels) return availableModels;
    return [
      ...availableModels.filter((m) => m.freeTierEnabled === true),
      ...availableModels.filter((m) => m.freeTierEnabled !== true),
    ];
  }, [availableModels, prefersFreeTierModels]);

  // The notice under the list must agree with the rows above it. Left on the
  // account-level verdict, a Free account with monthly credits read "upgrade to
  // continue" directly beneath the very model those credits pay for.
  // The catalogue row behind the current selection. `selectedModelData` is typed
  // as a name and an id, so it cannot answer either verdict below even when a
  // caller happens to pass a fuller object. One scan, two answers.
  const currentModel = React.useMemo(
    () => availableModels.find((m) => modelMatches(m, selectedModel)),
    [availableModels, selectedModel],
  );

  const selectionBlocked = React.useMemo(() => {
    if (!blockedForModel) return upgradeRequired;
    return currentModel ? blockedForModel(currentModel) : upgradeRequired;
  }, [blockedForModel, currentModel, upgradeRequired]);

  // Whether the CLOSED composer says the current model is free. The verdict is
  // the caller's, so it weighs the monthly balance as well as the plan, and
  // cannot claim "free" on the very row `selectionBlocked` locks.
  const selectionIsFreeTier = React.useMemo(
    () => (freeTierForModel && currentModel ? freeTierForModel(currentModel) : false),
    [freeTierForModel, currentModel],
  );

  // Per-instance positioning + outside-click ref (see the file header for why it
  // must NOT be shared across composer copies).
  const modelSelectorRef = useRef<HTMLDivElement>(null);
  const [menuPos, setMenuPos] = useState<MenuPos | null>(null);

  // Compute the portalled menu position from the trigger rect (adaptive
  // above/below, viewport-clamped). Recomputed on resize / scroll while open.
  useEffect(() => {
    if (!showModelSelector) {
      setMenuPos(null);
      return;
    }
    const update = () => {
      const rect = modelSelectorRef.current?.getBoundingClientRect();
      if (!rect) return;
      // A zero-size rect means the trigger is display:none - the hidden mobile
      // composer copy in the welcome view. Render no menu for it (otherwise it
      // would pin a stray menu at the viewport's top-left, the 0,0 origin); only
      // the visible composer's instance has a measurable trigger.
      if (rect.width === 0 && rect.height === 0) {
        setMenuPos(null);
        return;
      }
      const GAP = 8;
      const MARGIN = 16;
      const DESIRED = 440;
      const MIN_USEFUL = 240;
      const spaceAbove = rect.top - MARGIN - GAP;
      const spaceBelow = window.innerHeight - rect.bottom - MARGIN - GAP;
      // Left-clamp: the trigger sits on the right of the composer, so anchoring
      // the 320px menu at rect.left would overflow the right edge. Shared with
      // every other hand-positioned menu, which adds the case this hand-rolled
      // clamp got wrong: a screen narrower than the menu itself.
      const left = clampMenuLeft(rect.left, MENU_WIDTH, MARGIN);
      // Prefer opening downward when there is enough room below (the welcome view,
      // where the composer sits high on the page) or below is the roomier side;
      // fall back to above when the composer is docked at the bottom.
      const placeBelow = spaceBelow >= Math.min(DESIRED, MIN_USEFUL) || spaceBelow >= spaceAbove;
      if (placeBelow) {
        setMenuPos({ left, placement: 'below', top: rect.bottom + GAP, maxHeight: Math.max(200, Math.min(DESIRED, spaceBelow)) });
      } else {
        setMenuPos({ left, placement: 'above', bottom: window.innerHeight - rect.top + GAP, maxHeight: Math.max(200, Math.min(DESIRED, spaceAbove)) });
      }
    };
    update();
    window.addEventListener('resize', update);
    window.addEventListener('scroll', update, true);
    return () => {
      window.removeEventListener('resize', update);
      window.removeEventListener('scroll', update, true);
    };
  }, [showModelSelector, modelSelectorRef]);

  // Close the menu on outside click. Centralised here (previously each panel
  // re-implemented it) so every composer gets the same behavior - including the
  // guard that keeps the menu open while the effort <Select> is interacted with.
  // The menu is portalled out of `modelSelectorRef`, so clicks inside it are
  // recognised via the `data-model-selector-keep-open` tag on the portal root.
  useEffect(() => {
    if (!showModelSelector) return;

    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as Element | null;
      if (!target) return;
      // Opening the reasoning-effort <Select> mutates the DOM between mousedown
      // and mouseup, so the browser dispatches `click` on the document root.
      // While a Select listbox is open, treat such a root-targeted click as
      // internal, otherwise the picker would close the whole model menu.
      if ((target === document.documentElement || target === document.body)
          && document.querySelector('[role="listbox"]')) return;
      if (target.closest?.('[data-model-selector-keep-open]')) return;
      if (modelSelectorRef.current && !modelSelectorRef.current.contains(event.target as Node)) {
        setShowModelSelector(false);
      }
    };

    // Defer attach so the same click that opened the menu doesn't close it.
    const timeoutId = setTimeout(() => {
      document.addEventListener('click', handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timeoutId);
      document.removeEventListener('click', handleClickOutside);
    };
  }, [showModelSelector, setShowModelSelector, modelSelectorRef]);

  const showEffortControl = !!onReasoningEffortChange
    && supportsReasoningEffort({ provider: selectedModel.provider });

  // Footer filters. Remembered per browser: a user who only ever wants budget models
  // does not re-pick the tier on every message. Never applied to the SELECTED model:
  // the trigger keeps showing it whatever the list is narrowed to.
  const [storedFilters] = useState<StoredFilters>(readStoredFilters);
  const [tierFilter, setTierFilter] = useState<string>(storedFilters.tier ?? '');
  const [providerFilter, setProviderFilter] = useState<string>(storedFilters.provider ?? '');
  const showFilters = !!filterLabels && availableModels.length > 0;
  const tiersPresent = MODEL_TIER_ORDER.filter((tier) => availableModels.some((m) => m.tier === tier));
  const providersPresent = Array.from(new Set(availableModels.map((m) => m.provider)))
    .sort((a, b) => getProviderDisplayName(a).localeCompare(getProviderDisplayName(b)));
  // A select with one real choice is noise (a CE install with a single provider key is
  // the common shape): it is not offered, and the other filter still is.
  const showTierFilter = showFilters && tiersPresent.length >= 2;
  const showProviderFilter = showFilters && providersPresent.length >= 2;
  // A remembered filter whose value this catalogue does not hold, or whose control is not
  // offered (a filter nobody can see or clear must not narrow anything: a catalogue with
  // untiered models and one tiered one would otherwise show one row and no way out), is
  // DROPPED from the state and from storage, not merely masked: masked, it would come back
  // on its own the moment the catalogue changed, with nothing the user did to ask for it.
  // Only judged once the catalogue is loaded, so an empty first render keeps the memory.
  // Judged only by a menu that OFFERS the filters: a caller without labels never touches
  // the memory another composer stored.
  const staleTier = showFilters && tierFilter !== ''
    && (!showTierFilter || !tiersPresent.includes(tierFilter as ModelTierKey));
  const staleProvider = showFilters && providerFilter !== ''
    && (!showProviderFilter || !providersPresent.includes(providerFilter));
  useEffect(() => {
    if (!staleTier && !staleProvider) return;
    const tier = staleTier ? '' : tierFilter;
    const provider = staleProvider ? '' : providerFilter;
    setTierFilter(tier);
    setProviderFilter(provider);
    writeStoredFilters({ tier, provider });
  }, [staleTier, staleProvider, tierFilter, providerFilter]);
  const effectiveTier = staleTier ? '' : tierFilter;
  const effectiveProvider = staleProvider ? '' : providerFilter;
  const visibleModels = showFilters
    ? orderedModels.filter((m) =>
        (!effectiveTier || m.tier === effectiveTier)
        && (!effectiveProvider || m.provider === effectiveProvider))
    : orderedModels;
  const changeTierFilter = (tier: string) => {
    setTierFilter(tier);
    writeStoredFilters({ tier, provider: effectiveProvider });
  };
  const changeProviderFilter = (provider: string) => {
    setProviderFilter(provider);
    writeStoredFilters({ tier: effectiveTier, provider });
  };
  const clearFilters = () => {
    setTierFilter('');
    setProviderFilter('');
    writeStoredFilters({ tier: '', provider: '' });
  };
  const showFooter = showTierFilter || showProviderFilter || showEffortControl;
  // The footer's reset: every control it OFFERS back to its default, and nothing it does
  // not. Scoped that way because the effort override outlives the menu that set it (it is
  // per conversation), so a menu showing no effort control must not silently drop one that
  // a previous model's menu chose; and the filters are per browser, so clearing them from
  // a menu that does not show them would undo another composer's memory.
  const filtersActive = (showTierFilter || showProviderFilter) && !!(effectiveTier || effectiveProvider);
  const effortActive = showEffortControl && !!reasoningEffort;
  // No labels means no translator, which is the one caller that gets no filters either;
  // an unlabelled button in a menu of labelled ones is worse than no button.
  const showReset = !!filterLabels && (filtersActive || effortActive);
  const resetFooter = () => {
    if (filtersActive) clearFilters();
    if (effortActive) onReasoningEffortChange?.('');
  };

  return (
    // The composer's button row hosts this beside the mic and the send button
    // and can be as narrow as a 320px side panel, so the model NAME is the row's
    // elastic part: `min-w-0` lets it shrink to an ellipsis rather than push the
    // send button out of the bubble's `overflow-hidden`. The chevron keeps its
    // size, so the control still reads as a menu at every width.
    <div ref={modelSelectorRef} data-model-selector className="flex min-w-0">
      <button
        type="button"
        onClick={() => setShowModelSelector(!showModelSelector)}
        className="flex h-9 min-w-0 items-center gap-2 transition-colors duration-150 cursor-pointer rounded-lg px-2.5 text-theme-primary hover:bg-theme-secondary"
        title={changeModelTitle}
      >
        <span className="truncate min-w-0 max-w-[180px] text-sm">
          {selectedModelData?.name
            || selectedModel.id
            || (availableModels.length === 0 ? noModelsLabel : '')}
        </span>
        {/* After the name and before the chevron, and OUTSIDE the truncating
            span: the name is the elastic part of this row (see the wrapper's
            comment), so a chip inside it would be the first thing an ellipsis
            ate on a 320px side panel - exactly the width where a new account
            meets it. `shrink-0` keeps it whole and lets the name give way.
            `flex items-center` rather than a bare span: an inline wrapper put the
            chip on the text baseline of the button's taller inherited line box,
            so it sat below the model name instead of on its centre line. */}
        {selectionIsFreeTier && freeTierBadge && (
          <span className="flex shrink-0 items-center">{freeTierBadge}</span>
        )}
        <ChevronDown className={cn(
          "w-3.5 h-3.5 shrink-0 transition-transform duration-200",
          showModelSelector && "rotate-180"
        )} />
      </button>

      {showModelSelector && menuPos && createPortal(
        // Portalled to <body> so the composer's overflow-hidden bubble can't clip
        // it. `data-model-selector-keep-open` makes the outside-click handler
        // treat clicks inside the menu as internal.
        <div
          data-testid="model-selector-menu"
          data-model-selector-keep-open
          className="fixed max-w-[calc(100vw-1rem)] bg-theme-primary border border-theme rounded-xl shadow-lg z-[10000] p-2 flex flex-col"
          style={{
            // From MENU_WIDTH, the same number the clamp above measures with.
            width: MENU_WIDTH,
            left: menuPos.left,
            maxHeight: menuPos.maxHeight,
            ...(menuPos.placement === 'above' ? { bottom: menuPos.bottom } : { top: menuPos.top }),
          }}
        >
          {availableModels.length === 0 && emptyState}
          {showFilters && visibleModels.length === 0 && (
            <div
              className="flex items-center justify-between gap-2 px-3 py-3 text-sm text-theme-secondary"
              data-testid="model-selector-no-match"
            >
              <span role="status" aria-live="polite">{filterLabels?.noMatch}</span>
              <button
                type="button"
                data-model-selector-keep-open
                onClick={clearFilters}
                className="shrink-0 rounded-lg px-2 py-1 text-sm text-theme-primary hover:bg-theme-secondary"
              >
                {filterLabels?.clear}
              </button>
            </div>
          )}
          <div className="space-y-0.5 model-selector-scroll pr-1 overflow-y-auto">
            {visibleModels.map((model) => {
              const isSelected = modelMatches(model, selectedModel);
              const blocked = blockedForModel ? blockedForModel(model) : upgradeRequired;
              const free = freeTierForModel ? freeTierForModel(model) : false;
              return (
                <div
                  key={`${model.provider}:${model.id}`}
                  data-testid={blocked ? 'model-row-blocked' : undefined}
                  onClick={() => {
                    setSelectedModel(selectedModelFromAIModel(model));
                    setShowModelSelector(false);
                    track('chat_model_changed', {
                      model_id: model.id,
                      provider: model.provider,
                      previous_model_id: selectedModel.id || null,
                    });
                  }}
                  className={cn(
                    "group flex items-start gap-2.5 px-2.5 py-2 rounded-lg cursor-pointer transition-colors",
                    "hover:bg-gray-100 dark:hover:bg-gray-800",
                    isSelected && "bg-gray-100 dark:bg-gray-800"
                  )}
                >
                  {/* Greyed with the name when the balance cannot pay, and the
                      only part of the row that fades: it is decorative, so losing
                      contrast costs nothing, whereas compositing the whole row
                      takes the 11px meta line below AA. Still a choice either
                      way - `blocked` means "cannot pay right now", which a top-up
                      changes, and disabling the row would also hide the notice
                      under the list, the one thing here that says what to do. */}
                  <ServiceLogo as={Image}
                    src={`/icons/services/${model.iconSlug}.svg`}
                    alt={model.provider}
                    width={18}
                    height={18}
                    className={cn("w-[18px] h-[18px] flex-shrink-0 mt-0.5", blocked && "opacity-50")}
                  />
                  <div className="flex-1 min-w-0">
                    <ModelOptionDisplay
                      model={model}
                      upgradeRequired={blocked}
                      freeTier={free}
                      costBasis={costBasis}
                      costProfile="chatConversation"
                    />
                  </div>
                  {/* Revealed on hover for a pointer, always present on a coarse
                      pointer (no hover to reveal it with), and revealed when the
                      button inside it takes focus, so a keyboard does not tab into
                      something invisible. This card is the only place a reader who
                      cannot hover gets the full sentence behind the "Free" chip. */}
                  <div className="flex-shrink-0 opacity-0 group-hover:opacity-100 focus-within:opacity-100 pointer-coarse:opacity-100 transition-opacity">
                    <ModelInfoPopover model={model} freeTier={free} costBasis={costBasis} costProfile="chatConversation" />
                  </div>
                </div>
              );
            })}
          </div>
          {/* Under the list, never in a row: a row is an option, and this menu
              keeps itself open for anything clicked inside it, so a dialog
              opened from a row would sit underneath the menu it came from. */}
          {selectionBlocked && upgradeNotice && (
            /* Gated on the VERDICT, not on the node: the node is always handed
               down and returns null on its own, so keying the wrapper off it
               left an empty bordered strip at the foot of every menu. */
            <div className="shrink-0 border-t border-theme px-3 py-2">{upgradeNotice}</div>
          )}
          {/* Footer: what narrows the list (tier, provider) and what shapes the next
              answer (reasoning effort), in one strip under the list rather than a
              header above it, so the rows start at the top of the menu. */}
          {showFooter && (
            <div
              data-testid="model-selector-footer"
              data-model-selector-keep-open
              className="shrink-0 mt-1 flex flex-wrap items-center justify-between gap-x-1.5 gap-y-1 border-t border-theme px-2 pt-2 pb-0.5"
            >
              {/* TWO groups, not five loose controls, and that is the whole layout rule:
                  what NARROWS the list travels together, and what shapes the next ANSWER
                  is the other thing in the row. A flat wrap broke them apart at whatever
                  width ran out, so a narrow menu could put the effort control at the end
                  of the filters and one filter alone underneath, which reads as two
                  unrelated rows. As groups, either everything fits on one line or the
                  effort control takes a line of its own.
                  Both groups are `shrink-0` so the OUTER flex wraps them instead of
                  squeezing them (a shrinkable group wraps INSIDE itself first, which is
                  the behaviour being fixed), and `max-w-full` is what still lets the
                  filters group wrap internally when it alone is wider than the menu. */}
              <div
                data-model-selector-keep-open
                className="flex max-w-full shrink-0 flex-wrap items-center gap-1.5"
              >
              {showTierFilter && filterLabels && (
                  <Select value={effectiveTier || SELECT_EMPTY_VALUE_SENTINEL} onValueChange={(v) => changeTierFilter(v === SELECT_EMPTY_VALUE_SENTINEL ? '' : v)}>
                    <SelectTrigger
                      data-model-selector-keep-open
                      aria-label={filterLabels.tier}
                      title={filterLabels.tier}
                      className="h-7 min-h-0 w-auto gap-1.5 rounded-lg px-2.5 py-1 text-xs"
                    >
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent data-model-selector-keep-open>
                      <SelectItem value="" className="text-xs">{filterLabels.allTiers}</SelectItem>
                      {tiersPresent.map((tier) => (
                        <SelectItem key={tier} value={tier} className="text-xs">{filterLabels.tiers[tier]}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
              )}
              {showProviderFilter && filterLabels && (
                  <Select value={effectiveProvider || SELECT_EMPTY_VALUE_SENTINEL} onValueChange={(v) => changeProviderFilter(v === SELECT_EMPTY_VALUE_SENTINEL ? '' : v)}>
                    <SelectTrigger
                      data-model-selector-keep-open
                      aria-label={filterLabels.provider}
                      title={filterLabels.provider}
                      className="h-7 min-h-0 w-auto gap-1.5 rounded-lg px-2.5 py-1 text-xs"
                    >
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent data-model-selector-keep-open>
                      <SelectItem value="" className="text-xs">{filterLabels.allProviders}</SelectItem>
                      {providersPresent.map((provider) => (
                        <SelectItem key={provider} value={provider} className="text-xs">{getProviderDisplayName(provider)}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
              )}
              {showReset && filterLabels && (
                <button
                  type="button"
                  data-model-selector-keep-open
                  data-testid="model-selector-reset"
                  onClick={resetFooter}
                  aria-label={filterLabels.reset}
                  title={filterLabels.reset}
                  // The hover is an ARBITRARY-VALUE background on purpose. Tailwind v4 emits
                  // no rule for a `hover:` variant of a class hand-written in @layer
                  // components, so `hover:text-theme-primary` (and `hover:bg-theme-secondary`,
                  // which the model rows and the clear-filters button in this same file still
                  // use) compile to nothing and give no feedback at all. Those two are a
                  // pre-existing instance of the same defect, left alone here only because
                  // reviving them changes how the rows look; `hover:bg-[var(--bg-secondary)]`
                  // is the spelling that actually works and the one to copy.
                  className="inline-flex h-7 min-h-0 shrink-0 items-center gap-1 rounded-lg border border-theme px-2.5 py-1 text-xs text-theme-secondary transition-colors hover:bg-[var(--bg-secondary)]"
                >
                  <RotateCcw className="h-3 w-3" />
                  {filterLabels.reset}
                </button>
              )}
              </div>
              {showEffortControl && (
                <div data-model-selector-keep-open className="flex max-w-full shrink-0 items-center">
                  <Select
                    value={reasoningEffort || SELECT_EMPTY_VALUE_SENTINEL}
                    onValueChange={(v) => onReasoningEffortChange?.(v === SELECT_EMPTY_VALUE_SENTINEL ? '' : v)}
                  >
                    <SelectTrigger
                      data-model-selector-keep-open
                      aria-label={reasoningEffortLabel}
                      title={reasoningEffortLabel}
                      className="h-7 min-h-0 w-auto gap-1.5 rounded-lg px-2.5 py-1 text-xs"
                    >
                      <span className="text-theme-secondary">{reasoningEffortLabel}</span>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent data-model-selector-keep-open>
                      <SelectItem value="" className="text-xs">{effortAutoLabel}</SelectItem>
                      {REASONING_EFFORT_LEVELS.map((lvl) => (
                        <SelectItem key={lvl} value={lvl} className="text-xs">{lvl}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}
            </div>
          )}
        </div>,
        document.body,
      )}
    </div>
  );
}
