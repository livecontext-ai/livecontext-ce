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
import { ChevronDown } from 'lucide-react';
import Image from 'next/image';
import { cn } from '@/lib/utils';
import { PROVIDER_ICON_MAP } from '@/lib/ai-providers/providerIcons';
import { SelectedModel, modelMatches, selectedModelFromAIModel, AIModel } from '@/hooks/useModels';
import { ModelOptionDisplay, ModelInfoPopover } from '@/components/ai/ModelInfo';
import { REASONING_EFFORT_LEVELS, supportsReasoningEffort } from '@/lib/ai-providers/reasoningEffort';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue, SELECT_EMPTY_VALUE_SENTINEL } from '@/components/ui/select';

// Re-exported for callers that imported it from this module historically.
export { PROVIDER_ICON_MAP };

/**
 * Each row inherits {@link AIModel} (capability flags, context window, rate
 * limits, and other metadata) so the shared {@link ModelOptionDisplay} can render the same
 * enriched metadata as the workflow inspector picker. The dropdown-specific
 * {@code iconSlug} is the only header-side overlay.
 */
type DropdownModel = AIModel & { iconSlug: string };

const MENU_WIDTH = 320; // w-80

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
  reasoningEffort,
  onReasoningEffortChange,
  reasoningEffortLabel,
  effortAutoLabel,
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
  /** Per-conversation reasoning-effort override. When `onReasoningEffortChange`
   *  is provided and the selected provider supports effort, an effort control is
   *  rendered at the top of the open menu. Omitted by the panel chats. */
  reasoningEffort?: string;
  onReasoningEffortChange?: (effort: string) => void;
  reasoningEffortLabel?: string;
  effortAutoLabel?: string;
}) {
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
      // the 320px menu at rect.left would overflow the right edge.
      const left = Math.max(MARGIN, Math.min(rect.left, window.innerWidth - MENU_WIDTH - MARGIN));
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

  return (
    <div ref={modelSelectorRef} data-model-selector>
      <button
        type="button"
        onClick={() => setShowModelSelector(!showModelSelector)}
        className="flex h-9 items-center gap-2 transition-colors duration-150 cursor-pointer rounded-lg px-2.5 text-theme-primary hover:bg-theme-secondary"
        title={changeModelTitle}
      >
        <span className="truncate max-w-[180px] text-sm">
          {selectedModelData?.name
            || selectedModel.id
            || (availableModels.length === 0 ? noModelsLabel : '')}
        </span>
        <ChevronDown className={cn(
          "w-3.5 h-3.5 transition-transform duration-200",
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
          className="fixed w-80 bg-theme-primary border border-theme rounded-xl shadow-lg z-[10000] p-2 flex flex-col"
          style={{
            left: menuPos.left,
            maxHeight: menuPos.maxHeight,
            ...(menuPos.placement === 'above' ? { bottom: menuPos.bottom } : { top: menuPos.top }),
          }}
        >
          {showEffortControl && (
            <div className="flex items-center justify-between gap-2 px-3 py-2 mb-1 border-b border-theme shrink-0" data-model-selector-keep-open>
              <span className="text-xs text-theme-secondary">{reasoningEffortLabel}</span>
              <Select
                value={reasoningEffort || SELECT_EMPTY_VALUE_SENTINEL}
                onValueChange={(v) => onReasoningEffortChange?.(v === SELECT_EMPTY_VALUE_SENTINEL ? '' : v)}
              >
                <SelectTrigger
                  data-model-selector-keep-open
                  className="h-7 min-h-0 w-auto gap-1.5 rounded-lg px-2.5 py-1 text-xs"
                >
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
          {availableModels.length === 0 && emptyState}
          <div className="space-y-0.5 model-selector-scroll pr-1 overflow-y-auto">
            {availableModels.map((model) => {
              const isSelected = modelMatches(model, selectedModel);
              return (
                <div
                  key={`${model.provider}:${model.id}`}
                  onClick={() => {
                    setSelectedModel(selectedModelFromAIModel(model));
                    setShowModelSelector(false);
                  }}
                  className={cn(
                    "group flex items-start gap-2.5 px-2.5 py-2 rounded-lg cursor-pointer transition-colors",
                    "hover:bg-gray-100 dark:hover:bg-gray-800",
                    isSelected && "bg-gray-100 dark:bg-gray-800"
                  )}
                >
                  <Image
                    src={`/icons/services/${model.iconSlug}.svg`}
                    alt={model.provider}
                    width={18}
                    height={18}
                    className="w-[18px] h-[18px] flex-shrink-0 mt-0.5"
                  />
                  <div className="flex-1 min-w-0">
                    <ModelOptionDisplay model={model} />
                  </div>
                  <div className="flex-shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                    <ModelInfoPopover model={model} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>,
        document.body,
      )}
    </div>
  );
}
