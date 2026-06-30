"use client";

import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { getClientLocale } from '@/lib/utils/locale';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  GripVertical,
  Star,
  Trash2,
  RotateCcw,
  Plus,
  AlertTriangle,
  Gauge,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  modelConfigService,
  type ModelConfigEntry,
  type ModelConfigOverrideInput,
} from "@/lib/api/model-config.service";
import { clearModelsCache } from "@/hooks/useModels";
import { getProviderIconSrc } from "@/lib/ai-providers/providerIcons";
import { REASONING_EFFORT_LEVELS, supportsReasoningEffort } from "@/lib/ai-providers/reasoningEffort";
import AddModelDialog from "./AddModelDialog";

interface ModelManagementPanelProps {
  t: (key: string) => string;
}

const TIER_OPTIONS = [
  { value: "top", label: "Top", badgeClass: "bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400" },
  { value: "high", label: "High", badgeClass: "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400" },
  { value: "mid", label: "Mid", badgeClass: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300" },
  { value: "budget", label: "Budget", badgeClass: "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400" },
];

const TIER_BADGE: Record<string, string> = Object.fromEntries(TIER_OPTIONS.map(t => [t.value, t.badgeClass]));

// Radix Select forbids empty-string item values, so the "inherit / no default"
// option uses a sentinel mapped back to "" before it reaches the API.
const EFFORT_INHERIT = "__inherit__";
const EFFORT_SELECT_OPTIONS = [
  { value: EFFORT_INHERIT, label: "-" },
  ...REASONING_EFFORT_LEVELS.map((lvl) => ({ value: lvl, label: lvl })),
];

function ProviderBadge({ provider }: { provider: string }) {
  const iconSrc = getProviderIconSrc(provider);
  return (
    <span
      className="inline-flex items-center gap-1.5 text-xs text-theme-secondary bg-theme-tertiary px-1.5 py-0.5 rounded whitespace-nowrap"
      title={provider}
    >
      {iconSrc ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={iconSrc} alt={provider} className="w-3.5 h-3.5 object-contain" />
      ) : null}
      <span className="font-mono">{provider}</span>
    </span>
  );
}

function NameCell({
  model,
  onNameChange,
  t,
}: {
  model: ModelConfigEntry;
  onNameChange: (model: ModelConfigEntry, displayName: string) => void;
  t: (key: string) => string;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(model.name);

  const commit = () => {
    setEditing(false);
    const trimmed = value.trim();
    if (trimmed && trimmed !== model.name) {
      onNameChange(model, trimmed);
    } else {
      setValue(model.name);
    }
  };

  const cancel = () => {
    setEditing(false);
    setValue(model.name);
  };

  if (editing) {
    return (
      <input
        type="text"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") commit();
          else if (e.key === "Escape") cancel();
        }}
        className="w-full h-6 px-1 text-sm font-medium rounded border border-theme bg-theme-primary text-theme-primary"
        autoFocus
      />
    );
  }

  return (
    <button
      type="button"
      onClick={() => { setValue(model.name); setEditing(true); }}
      className="text-sm font-medium text-theme-primary truncate text-left hover:underline decoration-dotted underline-offset-2"
      title={t("modelConfig.editName")}
    >
      {model.name}
    </button>
  );
}

function PricingCell({
  model,
  onPricingChange,
}: {
  model: ModelConfigEntry;
  onPricingChange: (model: ModelConfigEntry, input: number, output: number) => void;
}) {
  const [editingInput, setEditingInput] = useState(false);
  const [editingOutput, setEditingOutput] = useState(false);
  const [inputVal, setInputVal] = useState(String(model.pricing?.input ?? 0));
  const [outputVal, setOutputVal] = useState(String(model.pricing?.output ?? 0));

  // Image-gen rows overload `priceInput` as USD per image; `priceOutput` is
  // unused (V158 seeds it to 0). The token-style "$in / $out per 1M" rendering
  // is misleading there - render a single `$X / image` cell instead.
  const isImage = model.mode === 'image';

  const commitInput = () => {
    setEditingInput(false);
    const num = parseFloat(inputVal);
    if (!isNaN(num) && num !== (model.pricing?.input ?? 0)) {
      onPricingChange(model, num, model.pricing?.output ?? 0);
    }
  };

  const commitOutput = () => {
    setEditingOutput(false);
    const num = parseFloat(outputVal);
    if (!isNaN(num) && num !== (model.pricing?.output ?? 0)) {
      onPricingChange(model, model.pricing?.input ?? 0, num);
    }
  };

  const isFree = isImage
    ? (model.pricing?.input ?? 0) === 0
    : (model.pricing?.input ?? 0) === 0 && (model.pricing?.output ?? 0) === 0;

  if (isFree && !editingInput && !editingOutput) {
    return (
      <button
        type="button"
        onClick={() => { setInputVal("0"); setEditingInput(true); }}
        className="text-sm text-emerald-600 dark:text-emerald-400 font-medium hover:underline"
        title="Click to set pricing"
      >
        Free
      </button>
    );
  }

  if (isImage) {
    return (
      <div className="flex items-center gap-1 text-sm text-theme-secondary">
        {editingInput ? (
          <input
            type="number"
            value={inputVal}
            onChange={(e) => setInputVal(e.target.value)}
            onBlur={commitInput}
            onKeyDown={(e) => e.key === "Enter" && commitInput()}
            className="w-20 h-6 px-1 text-sm rounded border border-theme bg-theme-primary text-theme-primary text-right"
            autoFocus
            step="0.001"
          />
        ) : (
          <button
            type="button"
            onClick={() => { setInputVal(String(model.pricing?.input ?? 0)); setEditingInput(true); }}
            className="hover:text-theme-primary transition-colors tabular-nums"
            title="Per-image price (USD)"
          >
            ${model.pricing?.input ?? 0}
          </button>
        )}
        <span className="text-xs text-theme-secondary opacity-70">/ image</span>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-0.5 text-sm text-theme-secondary">
      {editingInput ? (
        <input
          type="number"
          value={inputVal}
          onChange={(e) => setInputVal(e.target.value)}
          onBlur={commitInput}
          onKeyDown={(e) => e.key === "Enter" && commitInput()}
          className="w-16 h-6 px-1 text-sm rounded border border-theme bg-theme-primary text-theme-primary text-right"
          autoFocus
          step="0.01"
        />
      ) : (
        <button
          type="button"
          onClick={() => { setInputVal(String(model.pricing?.input ?? 0)); setEditingInput(true); }}
          className="hover:text-theme-primary transition-colors tabular-nums"
          title="Input price"
        >
          ${model.pricing?.input ?? 0}
        </button>
      )}
      <span>/</span>
      {editingOutput ? (
        <input
          type="number"
          value={outputVal}
          onChange={(e) => setOutputVal(e.target.value)}
          onBlur={commitOutput}
          onKeyDown={(e) => e.key === "Enter" && commitOutput()}
          className="w-16 h-6 px-1 text-sm rounded border border-theme bg-theme-primary text-theme-primary text-right"
          autoFocus
          step="0.01"
        />
      ) : (
        <button
          type="button"
          onClick={() => { setOutputVal(String(model.pricing?.output ?? 0)); setEditingOutput(true); }}
          className="hover:text-theme-primary transition-colors tabular-nums"
          title="Output price"
        >
          ${model.pricing?.output ?? 0}
        </button>
      )}
    </div>
  );
}

/**
 * Compact formatter for rate-limit numbers - 4_000_000 → "4M", 10_000 → "10k".
 * Keeps one decimal when the value isn't a clean multiple (e.g. 1_500_000 → "1.5M").
 * Returns "-" for null/undefined so the cell never shows an empty gap.
 */
function formatCompactLimit(n: number | null | undefined): string {
  if (n == null) return "-";
  if (n >= 1_000_000) {
    const v = n / 1_000_000;
    return `${v % 1 === 0 ? v.toFixed(0) : v.toFixed(1)}M`;
  }
  if (n >= 1_000) {
    const v = n / 1_000;
    return `${v % 1 === 0 ? v.toFixed(0) : v.toFixed(1)}k`;
  }
  return String(n);
}

function RateLimitCell({
  model,
  onRateLimitChange,
  t,
}: {
  model: ModelConfigEntry;
  onRateLimitChange: (model: ModelConfigEntry, limits: {
    rateLimitTpm?: number | null;
    rateLimitRpm?: number | null;
    rateLimitTpmPerTenant?: number | null;
    rateLimitRpmPerTenant?: number | null;
  }) => void;
  t: (key: string) => string;
}) {
  const [editing, setEditing] = useState(false);
  const [tpm, setTpm] = useState(model.rateLimitTpm != null ? String(model.rateLimitTpm) : "");
  const [rpm, setRpm] = useState(model.rateLimitRpm != null ? String(model.rateLimitRpm) : "");
  const [tpmTenant, setTpmTenant] = useState(model.rateLimitTpmPerTenant != null ? String(model.rateLimitTpmPerTenant) : "");
  const [rpmTenant, setRpmTenant] = useState(model.rateLimitRpmPerTenant != null ? String(model.rateLimitRpmPerTenant) : "");
  const popoverRef = useRef<HTMLDivElement>(null);

  const hasLimits = model.rateLimitTpm != null || model.rateLimitRpm != null
    || model.rateLimitTpmPerTenant != null || model.rateLimitRpmPerTenant != null;

  const commit = useCallback(() => {
    setEditing(false);
    // Short-circuit if no field actually changed - clicking outside the popover
    // without touching any input should NOT trigger a save (and its refetch).
    const nextTpm        = tpm        ? parseInt(tpm, 10)        : null;
    const nextRpm        = rpm        ? parseInt(rpm, 10)        : null;
    const nextTpmTenant  = tpmTenant  ? parseInt(tpmTenant, 10)  : null;
    const nextRpmTenant  = rpmTenant  ? parseInt(rpmTenant, 10)  : null;
    const unchanged =
      nextTpm       === (model.rateLimitTpm          ?? null) &&
      nextRpm       === (model.rateLimitRpm          ?? null) &&
      nextTpmTenant === (model.rateLimitTpmPerTenant ?? null) &&
      nextRpmTenant === (model.rateLimitRpmPerTenant ?? null);
    if (unchanged) return;
    onRateLimitChange(model, {
      rateLimitTpm: nextTpm,
      rateLimitRpm: nextRpm,
      rateLimitTpmPerTenant: nextTpmTenant,
      rateLimitRpmPerTenant: nextRpmTenant,
    });
  }, [model, tpm, rpm, tpmTenant, rpmTenant, onRateLimitChange]);

  const commitRef = useRef(commit);
  commitRef.current = commit;

  const cancel = () => {
    setEditing(false);
    // Reset local state to model's current values
    setTpm(model.rateLimitTpm != null ? String(model.rateLimitTpm) : "");
    setRpm(model.rateLimitRpm != null ? String(model.rateLimitRpm) : "");
    setTpmTenant(model.rateLimitTpmPerTenant != null ? String(model.rateLimitTpmPerTenant) : "");
    setRpmTenant(model.rateLimitRpmPerTenant != null ? String(model.rateLimitRpmPerTenant) : "");
  };

  // Click-outside auto-saves (like PricingCell), Escape cancels
  useEffect(() => {
    if (!editing) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) {
        commitRef.current();
      }
    };
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === "Escape") cancel();
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("keydown", handleEscape);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("keydown", handleEscape);
    };
  }, [editing]);

  if (!editing) {
    const tpmLabel = formatCompactLimit(model.rateLimitTpm);
    const rpmLabel = formatCompactLimit(model.rateLimitRpm);
    const tooltip = hasLimits
      ? [
          `TPM: ${model.rateLimitTpm?.toLocaleString(getClientLocale()) ?? "-"}`,
          `RPM: ${model.rateLimitRpm?.toLocaleString(getClientLocale()) ?? "-"}`,
        ].join(" · ")
      : t("modelConfig.rateLimits.set");

    return (
      <button
        type="button"
        onClick={() => setEditing(true)}
        className="flex w-full items-center justify-between gap-3 text-sm text-theme-secondary hover:text-theme-primary transition-colors"
        title={tooltip}
      >
        <span className="flex items-baseline gap-1 tabular-nums">
          <span className="text-xs uppercase tracking-wide opacity-70">TPM</span>
          <span>{tpmLabel}</span>
        </span>
        <span className="flex items-baseline gap-1 tabular-nums">
          <span className="text-xs uppercase tracking-wide opacity-70">RPM</span>
          <span>{rpmLabel}</span>
        </span>
      </button>
    );
  }

  const inputClass = "w-full h-7 px-2 text-sm rounded border border-theme bg-theme-primary text-theme-primary";

  return (
    <div ref={popoverRef} className="absolute right-0 top-full mt-1 z-50 bg-theme-primary border border-theme rounded-xl shadow-lg p-3 w-56">
      <p className="text-sm font-medium text-theme-primary mb-2">{t("modelConfig.rateLimits.title")}</p>
      <div className="space-y-1.5">
        <input type="number" value={tpm} onChange={(e) => setTpm(e.target.value)}
          placeholder={t("modelConfig.rateLimits.tpmGlobal")} step="1000" min="0"
          className={inputClass} autoFocus />
        <input type="number" value={rpm} onChange={(e) => setRpm(e.target.value)}
          placeholder={t("modelConfig.rateLimits.rpmGlobal")} step="10" min="0"
          className={inputClass} />
        {/* Per-tenant rate-limit inputs hidden while the platform runs the GLOBAL
            rate-limit strategy (per-tenant caps are dormant, not enforced). The
            tpmTenant/rpmTenant state stays initialised from the model and is passed
            through unchanged by commit(), so editing the global limits never wipes
            the per-tenant values and re-enabling is just re-adding these inputs. */}
      </div>
      <div className="flex justify-end gap-1 mt-2">
        <Button size="sm" variant="outline" className="h-6 text-sm px-2" onClick={cancel}>
          {t("modelConfig.addDialog.cancel")}
        </Button>
        <Button size="sm" className="h-6 text-sm px-2" onClick={commit}>
          OK
        </Button>
      </div>
    </div>
  );
}

function SortableModelRow({
  model,
  index,
  onToggleEnabled,
  onToggleRecommended,
  onTierChange,
  onReasoningEffortChange,
  onPricingChange,
  onRateLimitChange,
  onNameChange,
  onDelete,
  onReset,
  t,
}: {
  model: ModelConfigEntry;
  index: number;
  onToggleEnabled: (model: ModelConfigEntry) => void;
  onToggleRecommended: (model: ModelConfigEntry) => void;
  onTierChange: (model: ModelConfigEntry, tier: string) => void;
  onReasoningEffortChange: (model: ModelConfigEntry, effort: string) => void;
  onPricingChange: (model: ModelConfigEntry, input: number, output: number) => void;
  onNameChange: (model: ModelConfigEntry, displayName: string) => void;
  onRateLimitChange: (model: ModelConfigEntry, limits: {
    rateLimitTpm?: number | null;
    rateLimitRpm?: number | null;
    rateLimitTpmPerTenant?: number | null;
    rateLimitRpmPerTenant?: number | null;
  }) => void;
  onDelete: (model: ModelConfigEntry) => void;
  onReset: (model: ModelConfigEntry) => void;
  t: (key: string) => string;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: `${model.provider}:${model.id}` });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        "grid items-center gap-2 px-3 py-2 rounded-lg border border-theme bg-theme-primary transition-colors",
        "grid-cols-[40px_28px_auto_1fr_auto_auto_24px_100px_140px_52px]",
        isDragging && "opacity-50 shadow-lg z-50",
        model.enabled === false && "opacity-40"
      )}
    >
      {/* Drag handle + number */}
      <div className="flex items-center gap-1">
        <button
          type="button"
          className="flex-shrink-0 cursor-grab active:cursor-grabbing text-theme-secondary hover:text-theme-primary"
          {...attributes}
          {...listeners}
        >
          <GripVertical className="w-3.5 h-3.5" />
        </button>
        <span className="text-sm text-theme-secondary tabular-nums">
          {index + 1}
        </span>
      </div>

      {/* Enable/disable toggle */}
      <button
        type="button"
        onClick={() => onToggleEnabled(model)}
        aria-pressed={model.enabled !== false}
        data-testid={`model-toggle-${model.provider}-${model.id}`}
        className={cn(
          "w-7 h-4 rounded-full relative transition-colors flex-shrink-0",
          model.enabled !== false
            ? "bg-emerald-500"
            : "bg-gray-300 dark:bg-gray-600"
        )}
      >
        <span className={cn(
          "absolute top-0.5 w-3 h-3 rounded-full bg-white transition-transform shadow-sm",
          model.enabled !== false ? "left-3.5" : "left-0.5"
        )} />
      </button>

      {/* Provider badge - icon + name (icons distinguish CLI from API) */}
      <ProviderBadge provider={model.provider} />

      {/* Model name + badges */}
      <div className="flex items-center gap-1.5 min-w-0">
        <div className="min-w-0 flex-1">
          <NameCell model={model} onNameChange={onNameChange} t={t} />
        </div>
        {model.isCustom && (
          <span className="text-sm bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400 px-1.5 py-0.5 rounded-full whitespace-nowrap flex-shrink-0">
            {t("modelConfig.custom")}
          </span>
        )}
      </div>

      {/* Tier select */}
      <Select
        value={model.tier || "mid"}
        onValueChange={(value) => onTierChange(model, value)}
      >
        <SelectTrigger className={cn(
          "!h-6 !min-h-0 text-sm font-medium !border-0 !rounded-full !px-2 !py-0.5 min-w-0 w-auto gap-0.5 [&>svg]:h-3 [&>svg]:w-3 !hover:bg-none hover:!bg-[unset]",
          TIER_BADGE[model.tier || "mid"]
        )}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {TIER_OPTIONS.map((tier) => (
            <SelectItem key={tier.value} value={tier.value}>
              {tier.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      {/* Reasoning-effort default - only for bridge/CLI models; empty cell keeps
          the grid aligned for non-bridge rows. "" = inherit (no default). */}
      {supportsReasoningEffort({ provider: model.provider, providerKind: model.providerKind }) ? (
        <Select
          value={model.defaultReasoningEffort || EFFORT_INHERIT}
          onValueChange={(value) => onReasoningEffortChange(model, value === EFFORT_INHERIT ? "" : value)}
        >
          <SelectTrigger className="!h-6 !min-h-0 text-sm !rounded-full !px-2 !py-0.5 min-w-0 w-auto gap-0.5 [&>svg]:h-3 [&>svg]:w-3 bg-theme-tertiary text-theme-secondary">
            <SelectValue placeholder="-" />
          </SelectTrigger>
          <SelectContent>
            {EFFORT_SELECT_OPTIONS.map((opt) => (
              <SelectItem key={opt.value || "inherit"} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      ) : (
        <div />
      )}

      {/* Recommended star */}
      <button
        type="button"
        onClick={() => onToggleRecommended(model)}
        className={cn(
          "flex-shrink-0 transition-colors",
          model.recommended
            ? "text-amber-500"
            : "text-theme-secondary hover:text-amber-400"
        )}
        title={t("modelConfig.recommended")}
      >
        <Star
          className="w-3.5 h-3.5"
          fill={model.recommended ? "currentColor" : "none"}
        />
      </button>

      {/* Pricing */}
      <div className="flex justify-end">
        <PricingCell model={model} onPricingChange={onPricingChange} />
      </div>

      {/* Rate Limits - extra left padding to detach visually from pricing */}
      <div className="relative flex items-center pl-4">
        <RateLimitCell model={model} onRateLimitChange={onRateLimitChange} t={t} />
      </div>

      {/* Actions */}
      <div className="flex items-center justify-end gap-0.5">
        {model.hasOverride && !model.isCustom && (
          <button
            type="button"
            onClick={() => onReset(model)}
            className="text-theme-secondary hover:text-theme-primary transition-colors p-1"
            title={t("modelConfig.resetModel")}
          >
            <RotateCcw className="w-3.5 h-3.5" />
          </button>
        )}
        <button
          type="button"
          onClick={() => onDelete(model)}
          className="text-theme-secondary hover:text-red-500 transition-colors p-1"
          title={t("modelConfig.deleteModel")}
        >
          <Trash2 className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}

/**
 * V156 - model categories the admin can manage independently. Order matches
 * the tab rendering order in the panel. Adding a new category here +
 * extending the i18n keys + extending the V156 CHECK reach is enough - the
 * backend is forward-compatible (any lowercase snake_case key passes the
 * shape regex; absent rows fall back to global ranking).
 */
const CATEGORIES = ['chat', 'browser_agent', 'image_generation'] as const;
type Category = typeof CATEGORIES[number];

export default function ModelManagementPanel({ t }: ModelManagementPanelProps) {
  const [models, setModels] = useState<ModelConfigEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [providerFilter, setProviderFilter] = useState<string>("all");
  /**
   * Active category tab. {@code 'chat'} mirrors the legacy global view -
   * writes go to the parent {@code model_config_overrides.ranking} column
   * via {@code bulkUpdateRankings} (no category param) so existing chat
   * behaviour is untouched. The other tabs target the V156 sidecar via
   * {@code bulkUpdateRankings(category)} + {@code setCategoryEnabled}.
   */
  const [category, setCategory] = useState<Category>('chat');

  // Sliding-indicator pill toggle for the category selector - mirrors the
  // page's connection-mode toggle so the two read as the same kind of control.
  const categoryTabRef = useRef<HTMLDivElement>(null);
  const [categorySliderStyle, setCategorySliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  useEffect(() => {
    const updateSlider = () => {
      if (!categoryTabRef.current) return;
      const activeButton = categoryTabRef.current.querySelector(`[data-category-id="${category}"]`) as HTMLButtonElement | null;
      if (activeButton) {
        const containerRect = categoryTabRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setCategorySliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };
    requestAnimationFrame(() => requestAnimationFrame(updateSlider));
    window.addEventListener('resize', updateSlider);
    return () => window.removeEventListener('resize', updateSlider);
  }, [category, loading]);

  const hasAnyOverride = useMemo(() => models.some(m => m.hasOverride), [models]);

  const providerOptions = useMemo(() => {
    const seen = new Set<string>();
    const list: string[] = [];
    for (const m of models) {
      if (!seen.has(m.provider)) {
        seen.add(m.provider);
        list.push(m.provider);
      }
    }
    list.sort((a, b) => a.localeCompare(b));
    return list;
  }, [models]);

  const visibleModels = useMemo(() => {
    let filtered = providerFilter === "all"
      ? models
      : models.filter(m => m.provider === providerFilter);
    // V156 - hide bridges on the per-category tabs because they don't expose
    // per-step chat completions for browser_agent and the image_generation
    // tool only routes through OpenAI/Google. Re-ranking or disabling a
    // bridge here has zero runtime effect, so the row would mislead. Chat
    // tab keeps showing them - full-session bridges DO work for chat.
    if (category === 'browser_agent' || category === 'image_generation') {
      filtered = filtered.filter(m => m.providerKind !== 'bridge');
    }
    return filtered;
  }, [models, providerFilter, category]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const fetchModels = useCallback(async (opts?: { silent?: boolean }) => {
    try {
      // A post-save refresh passes { silent: true } so the whole list isn't
      // swapped for the full-page spinner - that swap caused a visible reload
      // and a costly re-sort after every mutation. Only the initial load and
      // tab switch show the spinner (genuinely new datasets).
      if (!opts?.silent) setLoading(true);
      // V156 - chat tab keeps the legacy global read (no ?category= param)
      // for backward compatibility; other tabs fetch the per-category
      // overlaid view so the rendered enabled flag + ranking reflect the
      // active tab. Without this, toggling enabled in browser_agent would
      // write through but the panel would keep showing the chat-tab state.
      const categoryParam = category === 'chat' ? undefined : category;
      const data = await modelConfigService.getEffectiveModels(categoryParam);
      setModels(data);
      setError(null);
    } catch {
      setError(t("modelConfig.fetchError"));
    } finally {
      if (!opts?.silent) setLoading(false);
    }
  }, [t, category]);

  // Re-fetch on tab change. fetchModels is memoised on category so this is
  // exactly one fetch per tab switch - no thrash, no race window where the
  // user sees stale data from the previous tab.
  useEffect(() => {
    fetchModels();
  }, [fetchModels]);

  const saveAndRefresh = async (fn: () => Promise<void>) => {
    setSaving(true);
    try {
      await fn();
      clearModelsCache();
      // Silent: refresh the data without flashing the full-page spinner.
      await fetchModels({ silent: true });
    } catch {
      setError(t("modelConfig.saveError"));
    } finally {
      setSaving(false);
    }
  };

  const handleDragEnd = async (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    const oldIndex = models.findIndex(
      (m) => `${m.provider}:${m.id}` === active.id
    );
    const newIndex = models.findIndex(
      (m) => `${m.provider}:${m.id}` === over.id
    );
    if (oldIndex === -1 || newIndex === -1) return;

    const reordered = arrayMove(models, oldIndex, newIndex);
    setModels(reordered);

    const rankings = reordered.map((m, i) => ({
      provider: m.provider,
      modelId: m.id,
      ranking: i + 1,
    }));

    // V156 - chat tab keeps the legacy global ranking write so the chat
    // picker, agent.create, and every consumer that hasn't migrated to
    // ?category=chat continue to see the same value. Other tabs write
    // through the per-category sidecar (additive - global ranking stays
    // untouched, only the sidecar's `rank` column is updated).
    const categoryParam = category === 'chat' ? undefined : category;
    await saveAndRefresh(async () => {
      await modelConfigService.bulkUpdateRankings(rankings, categoryParam);
    });
  };

  const handleToggleRecommended = (model: ModelConfigEntry) => {
    saveAndRefresh(async () => {
      await modelConfigService.saveOverride({
        provider: model.provider,
        modelId: model.id,
        recommended: !model.recommended,
      });
    });
  };

  const handleToggleEnabled = (model: ModelConfigEntry) => {
    // currently disabled (enabled === false) → turn on; otherwise turn off.
    const nextEnabled = model.enabled === false;
    // Optimistic: flip the row in place so it reacts instantly. Toggling
    // enabled never changes ordering, so we DON'T refetch (no full-list reload
    // / re-sort - the user's complaint). Persist in the background; on failure
    // we roll the row back and surface the error.
    setModels((prev) =>
      prev.map((m) =>
        m.provider === model.provider && m.id === model.id
          ? { ...m, enabled: nextEnabled, hasOverride: true }
          : m,
      ),
    );
    setSaving(true);
    // chat = global enabled flag on the parent row (legacy);
    // other categories = sidecar enabled flag, leaves the parent row alone so
    // the same model stays usable in chat while disabled in (say) browser_agent.
    const persist =
      category === 'chat'
        ? modelConfigService.saveOverride({
            provider: model.provider,
            modelId: model.id,
            enabled: nextEnabled,
          })
        : modelConfigService.setCategoryEnabled(
            model.provider,
            model.id,
            category,
            nextEnabled,
          );
    persist
      .then(() => {
        clearModelsCache();
      })
      .catch(() => {
        // Roll back the optimistic flip and surface the error.
        setModels((prev) =>
          prev.map((m) =>
            m.provider === model.provider && m.id === model.id
              ? { ...m, enabled: model.enabled }
              : m,
          ),
        );
        setError(t("modelConfig.saveError"));
      })
      .finally(() => setSaving(false));
  };

  const handleTierChange = (model: ModelConfigEntry, tier: string) => {
    saveAndRefresh(async () => {
      await modelConfigService.saveOverride({
        provider: model.provider,
        modelId: model.id,
        tier,
      });
    });
  };

  const handleReasoningEffortChange = (model: ModelConfigEntry, effort: string) => {
    saveAndRefresh(async () => {
      await modelConfigService.saveOverride({
        provider: model.provider,
        modelId: model.id,
        // "" clears the default (backend saveOverride normalizes blank → null).
        defaultReasoningEffort: effort,
      });
    });
  };

  const handlePricingChange = (model: ModelConfigEntry, input: number, output: number) => {
    saveAndRefresh(async () => {
      await modelConfigService.saveOverride({
        provider: model.provider,
        modelId: model.id,
        priceInput: input,
        priceOutput: output,
      });
    });
  };

  const handleNameChange = (model: ModelConfigEntry, displayName: string) => {
    saveAndRefresh(async () => {
      await modelConfigService.saveOverride({
        provider: model.provider,
        modelId: model.id,
        displayName,
      });
    });
  };

  const handleRateLimitChange = (model: ModelConfigEntry, limits: {
    rateLimitTpm?: number | null;
    rateLimitRpm?: number | null;
    rateLimitTpmPerTenant?: number | null;
    rateLimitRpmPerTenant?: number | null;
  }) => {
    saveAndRefresh(async () => {
      await modelConfigService.saveOverride({
        provider: model.provider,
        modelId: model.id,
        ...limits,
      });
    });
  };

  const handleDelete = (model: ModelConfigEntry) => {
    if (model.isCustom) {
      saveAndRefresh(async () => {
        await modelConfigService.deleteOverride(model.provider, model.id);
      });
    } else {
      saveAndRefresh(async () => {
        await modelConfigService.saveOverride({
          provider: model.provider,
          modelId: model.id,
          enabled: false,
        });
      });
    }
  };

  const handleReset = (model: ModelConfigEntry) => {
    saveAndRefresh(async () => {
      await modelConfigService.deleteOverride(model.provider, model.id);
    });
  };

  const handleResetAll = () => {
    if (!confirm(t("modelConfig.resetConfirm"))) return;
    saveAndRefresh(async () => {
      await modelConfigService.resetAll();
    });
  };

  const handleAddModel = async (input: ModelConfigOverrideInput) => {
    const maxRanking = models.reduce((max, m) => Math.max(max, m.displayOrder ?? 0), 0);
    await saveAndRefresh(async () => {
      await modelConfigService.saveOverride({
        ...input,
        isCustom: true,
        ranking: maxRanking + 1,
      });
    });
    setShowAddDialog(false);
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  const sortableIds = visibleModels.map((m) => `${m.provider}:${m.id}`);

  return (
    // -mt-2 pulls the panel up under the page's connection-mode pill toggle so
    // the two stacked toggles read as one control group, while still keeping a
    // small breathing gap (~16px) between them instead of touching. A larger
    // -mt-4 glued them together; the full page space-y-6 (24px) felt detached.
    <div className="space-y-3 -mt-2">
      {/* V156 - category selector, styled as a centered pill toggle to match
          the page's connection-mode toggle. The chat tab is the legacy global
          view; the others target the per-category sidecar (rank + enabled).
          All tabs share the same model list - only the writes differ. */}
      <div className="flex justify-center">
        <div
          className="relative inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-full w-max"
          ref={categoryTabRef}
        >
          <div
            className="absolute top-1.5 bottom-1.5 rounded-full bg-[var(--bg-primary)] transition-all duration-300 ease-out"
            style={{
              left: categorySliderStyle.left,
              width: categorySliderStyle.width,
              opacity: categorySliderStyle.width ? 1 : 0,
            }}
          />
          {CATEGORIES.map((c) => (
            <button
              key={c}
              data-category-id={c}
              type="button"
              onClick={() => setCategory(c)}
              aria-pressed={category === c}
              className={cn(
                "relative z-10 flex flex-shrink-0 items-center px-4 py-2 rounded-full text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
                category === c
                  ? "text-[var(--text-primary)]"
                  : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
              )}
            >
              {t(`modelConfig.category.${c}.label`)}
            </button>
          ))}
        </div>
      </div>

      <div className="flex items-center justify-between">
        <p className="text-xs text-theme-secondary">
          {t(`modelConfig.category.${category}.hint`)}
        </p>
        <div className="flex items-center gap-2">
          {saving && <LoadingSpinner size="xs" />}
          <Select value={providerFilter} onValueChange={setProviderFilter}>
            {/* min-h-0 cancels SelectTrigger's default min-h-[44px] and
                rounded-full matches the action buttons, so the filter and the
                buttons share one height + pill shape. */}
            <SelectTrigger
              className="min-h-0 h-8 rounded-full px-3 text-sm min-w-[160px]"
              aria-label={t("modelConfig.filterByProvider")}
              title={t("modelConfig.filterByProvider")}
            >
              <SelectValue placeholder={t("modelConfig.filterByProvider")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t("modelConfig.allProviders")}</SelectItem>
              {providerOptions.map((p) => (
                <SelectItem key={p} value={p}>{p}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button
            size="sm"
            variant="outline"
            className="h-8 px-3 text-sm"
            onClick={handleResetAll}
            disabled={!hasAnyOverride}
          >
            <RotateCcw className="w-3.5 h-3.5 mr-1" />
            {t("modelConfig.resetAll")}
          </Button>
          <Button
            size="sm"
            className="h-8 px-3 text-sm"
            onClick={() => setShowAddDialog(true)}
          >
            <Plus className="w-3.5 h-3.5 mr-1" />
            {t("modelConfig.addModel")}
          </Button>
        </div>
      </div>

      {error && (
        <div className="flex items-center gap-2 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-3">
          <AlertTriangle className="w-3.5 h-3.5 text-red-500 flex-shrink-0" />
          <p className="text-sm text-red-800 dark:text-red-300">{error}</p>
        </div>
      )}

      {/* Column headers */}
      <div className="grid items-center gap-2 px-3 py-1 text-sm text-theme-secondary font-medium grid-cols-[40px_28px_auto_1fr_auto_auto_24px_100px_140px_52px]">
        <div>#</div>
        <div />
        <div>{t("modelConfig.columns.provider")}</div>
        <div>{t("modelConfig.columns.model")}</div>
        <div>{t("modelConfig.columns.tier")}</div>
        <div>{t("modelConfig.columns.effort")}</div>
        <div>
          <Star className="w-3.5 h-3.5" />
        </div>
        <div className="text-right">{t("modelConfig.columns.pricing")}</div>
        <div className="flex items-center justify-between gap-3 pl-4 text-xs uppercase tracking-wide">
          <span>TPM</span>
          <span>RPM</span>
        </div>
        <div />
      </div>

      {/* Sortable model list */}
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragEnd={handleDragEnd}
      >
        <SortableContext
          items={sortableIds}
          strategy={verticalListSortingStrategy}
        >
          <div className="space-y-1">
            {visibleModels.map((model) => (
              <SortableModelRow
                key={`${model.provider}:${model.id}`}
                model={model}
                index={models.indexOf(model)}
                onToggleEnabled={handleToggleEnabled}
                onToggleRecommended={handleToggleRecommended}
                onTierChange={handleTierChange}
                onReasoningEffortChange={handleReasoningEffortChange}
                onPricingChange={handlePricingChange}
                onRateLimitChange={handleRateLimitChange}
                onNameChange={handleNameChange}
                onDelete={handleDelete}
                onReset={handleReset}
                t={t}
              />
            ))}
          </div>
        </SortableContext>
      </DndContext>

      {visibleModels.length === 0 && (
        <div className="text-center py-8 text-sm text-theme-secondary">
          {t("modelConfig.noModels")}
        </div>
      )}

      {showAddDialog && (
        <AddModelDialog
          onSave={handleAddModel}
          onClose={() => setShowAddDialog(false)}
          t={t}
        />
      )}
    </div>
  );
}
