"use client";

import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { getClientLocale } from '@/lib/utils/locale';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
  DragOverlay,
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
  List as VirtualList,
  useDynamicRowHeight,
  useListCallbackRef,
  type RowComponentProps,
} from "react-window";
import {
  GripVertical,
  Star,
  Trash2,
  RotateCcw,
  Plus,
  AlertTriangle,
  Gauge,
  Sparkles,
  KeyRound,
  Archive,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { IS_CE, IS_CLOUD } from "@/lib/edition/edition";
import { cn } from "@/lib/utils";
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  modelConfigService,
  type ModelConfigEntry,
  type ModelConfigOverrideInput,
  type ModelExecutionLink,
} from "@/lib/api/model-config.service";
import { clearModelsCache } from "@/hooks/useModels";
import { getProviderIconSrc } from "@/lib/ai-providers/providerIcons";
import { REASONING_EFFORT_LEVELS, supportsReasoningEffort } from "@/lib/ai-providers/reasoningEffort";
import AddModelDialog from "./AddModelDialog";
import ModelExecutionLinkCell from "./ModelExecutionLinkCell";
import { ServiceLogo } from '@/components/ui/service-logo';
import ToastContainer from '@/components/ToastContainer';
import { useToast } from '@/components/Toast';
import { formatUtcDateOrNull } from '@/lib/utils/dateFormatters';
import RetiredModelsPanel from "./RetiredModelsPanel";

interface ModelManagementPanelProps {
  /**
   * next-intl's translator. Values are part of the signature because the panel names a
   * provider inside a sentence; the ROW components below keep the narrower key-only shape,
   * since a row has nothing to interpolate and the narrower type says so.
   */
  t: (key: string, values?: Record<string, string>) => string;
}

type SortOrder = "rank" | "releaseAsc" | "releaseDesc";

/**
 * Order by release date (YYYY-MM-DD compares as text). Rows with no known date go LAST in
 * both directions: an unknown date is neither old nor new, and putting it first would bury
 * the models the admin is actually looking for. Stable, so ties keep their rank order.
 */
function sortByReleaseDate(rows: ModelConfigEntry[], order: Exclude<SortOrder, "rank">): ModelConfigEntry[] {
  const dir = order === "releaseAsc" ? 1 : -1;
  return [...rows].sort((a, b) => {
    const da = a.releaseDate ?? "";
    const db = b.releaseDate ?? "";
    if (!da || !db) return da ? -1 : db ? 1 : 0;
    return da < db ? -dir : da > db ? dir : 0;
  });
}

/** One shared empty array for the (many) models with no execution link. */
const NO_EXECUTION_LINKS: ModelExecutionLink[] = [];

/** Height of a plain row before it is measured (name line + control grid + gap), as rendered. */
const ESTIMATED_ROW_HEIGHT = 72;
/** The list scrolls inside once it would take more than this share of the viewport. */
const LIST_VIEWPORT_SHARE = 0.7;
/** Rows mounted beyond the viewport, so a short drag or a fast scroll finds them ready. */
const LIST_OVERSCAN = 8;

type VirtualModelRowProps = {
  rows: ModelConfigEntry[];
  renderRow: (model: ModelConfigEntry) => React.ReactNode;
  draggingKey: string | null;
};

/**
 * One slot of the virtual list. The slot is keyed by POSITION (react-window's choice) while
 * the row inside is keyed by model, so a row's local edit state never follows a slot onto a
 * different model after a reorder. The bottom padding is the gap the old `space-y-1` gave,
 * inside the slot so the measured height includes it.
 */
function VirtualModelRow({
  index,
  style,
  ariaAttributes,
  rows,
  renderRow,
  draggingKey,
}: RowComponentProps<VirtualModelRowProps>) {
  const model = rows[index];
  const key = `${model.provider}:${model.id}`;
  return (
    <div
      style={{ ...style, zIndex: draggingKey === key ? 10 : undefined }}
      {...ariaAttributes}
      className="pb-1"
    >
      {renderRow(model)}
    </div>
  );
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

// Single source of truth for the table's grid template - the header row and
// every model row MUST use the same one (each row is its own grid container,
// so a child-count/template mismatch wraps the last cell onto an implicit
// second line and the whole table misaligns). Cloud gets two extra FIXED
// columns: the CE-ship chip (V381) and the free-tier chip (V493). Fixed (not
// auto) so their varying labels cannot shift the following columns from row to
// row. Both are absent on CE builds - a CE install ships no bundle and meters
// no credits, so neither chip means anything there.
//
// The model name is NOT a column: it sits on its own line above this grid, in
// every row. As a `1fr` column it only got what the other columns left over,
// and in the settings content width (~900px) the cloud row's fixed columns,
// gaps, provider badge, tier and effort selects add up to more than that, so
// on every CLI row (the only ones with an effort select) the name column was
// 0px wide and the name did not show at all. The provider column is the
// flexible one now; its badge truncates and keeps the full slug in its title.
const ROW_GRID_COLS = IS_CE
  ? "grid-cols-[28px_40px_28px_minmax(0,1fr)_auto_auto_24px_100px_140px_76px]"
  : "grid-cols-[28px_40px_28px_88px_60px_minmax(0,1fr)_auto_auto_24px_100px_140px_76px]";

function ProviderBadge({ provider }: { provider: string }) {
  const iconSrc = getProviderIconSrc(provider);
  return (
    <span
      className="inline-flex max-w-full items-center gap-1.5 text-xs text-theme-secondary bg-theme-tertiary px-1.5 py-0.5 rounded whitespace-nowrap"
      title={provider}
    >
      {iconSrc ? (
        // eslint-disable-next-line @next/next/no-img-element
        <ServiceLogo src={iconSrc} alt={provider} className="w-3.5 h-3.5 flex-shrink-0 object-contain" />
      ) : null}
      <span className="font-mono truncate">{provider}</span>
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
    // `block w-full` is what makes `truncate` do anything at all. A <button> is
    // shrink-to-fit, so its width is its TEXT: `overflow-hidden` then has nothing to
    // clip and the name simply ran out of the cell and under the tier and effort
    // selects, which paint their own background and so appeared to sit on top of it.
    // The grid column is `1fr` and its item already carries `min-w-0`, so the column
    // really is narrower than a long id: the name is the part that has to give.
    <button
      type="button"
      onClick={() => { setValue(model.name); setEditing(true); }}
      data-testid={`model-name-${model.provider}-${model.id}`}
      className="block w-full min-w-0 truncate text-left text-sm font-medium text-theme-primary hover:underline decoration-dotted underline-offset-2"
      // The full name, because a truncated one is unreadable otherwise, and the
      // action on the aria-label, which is where a screen reader looks for it.
      title={model.name}
      aria-label={`${t("modelConfig.editName")}: ${model.name}`}
    >
      {model.name}
    </button>
  );
}

/**
 * The rank, typed. Dragging only works for a short move: with hundreds of models, taking
 * #420 to #5 meant scrolling the whole catalogue with the button held, and in a virtualised
 * list the rows in between are not even mounted to drop on. Typing the target position is
 * the long move; the drag handle stays for nudging a row past its neighbours.
 */
function RankCell({
  model,
  rank,
  rankCount,
  onMoveToRank,
  t,
}: {
  model: ModelConfigEntry;
  /** 1-based position in the whole list of this tab, not in the filtered view. */
  rank: number;
  rankCount: number;
  onMoveToRank: (model: ModelConfigEntry, rank: number) => void;
  t: (key: string) => string;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(String(rank));
  /**
   * Enter and Escape both unmount the focused input, and Chromium then fires its blur
   * with the handlers of the last render. Without this latch Escape still moved the model
   * (blur committed the typed value) and Enter saved the order twice.
   */
  const settled = useRef(false);

  const commit = () => {
    if (settled.current) return;
    settled.current = true;
    setEditing(false);
    const typed = Number.parseInt(value, 10);
    if (!Number.isFinite(typed)) return;
    const target = Math.min(Math.max(typed, 1), rankCount);
    if (target !== rank) onMoveToRank(model, target);
  };

  if (editing) {
    return (
      <input
        type="number"
        min={1}
        max={rankCount}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") commit();
          else if (e.key === "Escape") {
            settled.current = true;
            setEditing(false);
          }
        }}
        aria-label={`${t("modelConfig.rankEdit")} (${rank}): ${model.name}`}
        data-testid={`model-rank-input-${model.provider}-${model.id}`}
        className="w-full h-6 px-0.5 text-sm tabular-nums rounded border border-theme bg-theme-primary text-theme-primary [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        autoFocus
      />
    );
  }

  return (
    <button
      type="button"
      onClick={() => { settled.current = false; setValue(String(rank)); setEditing(true); }}
      data-testid={`model-rank-${model.provider}-${model.id}`}
      title={t("modelConfig.rankEdit")}
      // The visible number is part of the name (WCAG 2.5.3), so it is read out too.
      aria-label={`${t("modelConfig.rankEdit")} (${rank}): ${model.name}`}
      className="text-sm text-theme-secondary tabular-nums hover:text-theme-primary hover:underline decoration-dotted underline-offset-2"
    >
      {rank}
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

  // No per-image rendering here: `getEffectiveModelList` mode-filters every
  // response (`modeFilterKey` maps the global read to `chat`), and neither
  // surviving category accepts `mode='image'`, so such a row cannot reach this
  // cell. It used to, when the image tab existed.

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

  const isFree = (model.pricing?.input ?? 0) === 0 && (model.pricing?.output ?? 0) === 0;

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

  const tpmLabel = formatCompactLimit(model.rateLimitTpm);
  const rpmLabel = formatCompactLimit(model.rateLimitRpm);
  const tooltip = hasLimits
    ? [
        `TPM: ${model.rateLimitTpm?.toLocaleString(getClientLocale()) ?? "-"}`,
        `RPM: ${model.rateLimitRpm?.toLocaleString(getClientLocale()) ?? "-"}`,
      ].join(" · ")
    : t("modelConfig.rateLimits.set");
  const inputClass = "w-full h-7 px-2 text-sm rounded border border-theme bg-theme-primary text-theme-primary";

  // The editor is PORTALLED (shared Popover), never an absolute child of the row. A disabled
  // model's row is `opacity-40`, and opacity makes the row a stacking context: an in-row
  // editor painted at 40% and its z-index could not lift it over the rows below, which
  // covered it. Most catalog rows are disabled, so on most rows the editor was effectively
  // invisible. Click outside saves (like PricingCell), Escape cancels.
  return (
    <Popover
      open={editing}
      onOpenChange={(open) => {
        if (open) setEditing(true);
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
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
      </PopoverTrigger>
      <PopoverContent
        align="end"
        className="w-56 p-3"
        data-testid={`rate-limit-editor-${model.provider}-${model.id}`}
        onInteractOutside={() => commitRef.current()}
        onEscapeKeyDown={(e) => {
          e.preventDefault();
          cancel();
        }}
      >
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
      </PopoverContent>
    </Popover>
  );
}

/** Separator between provider and model id in a Select value. Provider slugs never contain it. */
const REPLACEMENT_SEPARATOR = ":";
const REPLACEMENT_DEFAULT = "__platform_default__";

function ReplacementSelect({
  model,
  options,
  onChange,
  t,
}: {
  model: ModelConfigEntry;
  options: ModelConfigEntry[];
  onChange: (model: ModelConfigEntry, provider: string | null, modelId: string | null) => void;
  t: (key: string) => string;
}) {
  const current =
    model.replacementProvider && model.replacementModel
      ? `${model.replacementProvider}${REPLACEMENT_SEPARATOR}${model.replacementModel}`
      : REPLACEMENT_DEFAULT;
  // Keep a replacement that is not among the options (e.g. disabled since) selectable, so
  // the control shows what is stored instead of silently reading "platform default".
  const currentMissing =
    current !== REPLACEMENT_DEFAULT
    && !options.some((o) => `${o.provider}${REPLACEMENT_SEPARATOR}${o.id}` === current);
  return (
    <div
      className="flex flex-shrink-0 items-center gap-1 text-sm text-theme-secondary"
      title={t("modelConfig.replacementTooltip")}
      data-testid={`model-replacement-${model.provider}-${model.id}`}
    >
      <span className="whitespace-nowrap">{t("modelConfig.replacedBy")}</span>
      <Select
        value={current}
        onValueChange={(value) => {
          if (value === REPLACEMENT_DEFAULT) {
            onChange(model, null, null);
            return;
          }
          const cut = value.indexOf(REPLACEMENT_SEPARATOR);
          onChange(model, value.slice(0, cut), value.slice(cut + 1));
        }}
      >
        <SelectTrigger
          aria-label={t("modelConfig.replacedBy")}
          className="!h-6 !min-h-0 text-sm !rounded-lg !px-2 !py-0.5 min-w-0 max-w-[16rem] w-auto gap-0.5 [&>svg]:h-3 [&>svg]:w-3 bg-theme-tertiary text-theme-primary"
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={REPLACEMENT_DEFAULT}>{t("modelConfig.replacementDefault")}</SelectItem>
          {currentMissing && (
            <SelectItem value={current}>
              {`${model.replacementProvider} / ${model.replacementModel}`}
            </SelectItem>
          )}
          {options.map((o) => (
            <SelectItem
              key={`${o.provider}${REPLACEMENT_SEPARATOR}${o.id}`}
              value={`${o.provider}${REPLACEMENT_SEPARATOR}${o.id}`}
            >
              {`${o.name} (${o.provider})`}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

function SortableModelRow({
  model,
  index,
  rankCount,
  onMoveToRank,
  executionLinks,
  executionLinksLoaded,
  onExecutionLinksChanged,
  onExecutionLinkError,
  onToggleEnabled,
  onCycleBundleEnabled,
  onToggleFreeTier,
  onToggleRecommended,
  replacementOptions,
  showReplacement,
  onReplacementChange,
  onTierChange,
  onReasoningEffortChange,
  onPricingChange,
  onRateLimitChange,
  onNameChange,
  onDelete,
  onReset,
  onRetire,
  dragDisabled,
  showReleaseDate,
  selected,
  onSelectedChange,
  t,
}: {
  model: ModelConfigEntry;
  /** 0-based position in the whole list of this tab (filters do not renumber). */
  index: number;
  /** Size of the whole list, the highest rank that can be typed. */
  rankCount: number;
  onMoveToRank: (model: ModelConfigEntry, rank: number) => void;
  /** Execution links whose BILLED pair is this model - empty when unrouted. */
  executionLinks: ModelExecutionLink[];
  /** False while the list is unknown (never read, or the read failed). */
  executionLinksLoaded: boolean;
  onExecutionLinksChanged: () => Promise<void>;
  /** Surface a failed link write, or clear a previous one with null. */
  onExecutionLinkError: (message: string | null) => void;
  onToggleEnabled: (model: ModelConfigEntry) => void;
  onCycleBundleEnabled: (model: ModelConfigEntry) => void;
  onToggleFreeTier: (model: ModelConfigEntry) => void;
  onToggleRecommended: (model: ModelConfigEntry) => void;
  /** Enabled models this one can be replaced by while it is disabled (V515). */
  replacementOptions: ModelConfigEntry[];
  /**
   * Only on the chat tab, whose rows carry the GLOBAL enabled flag the runtime swap reads.
   * Other tabs show a per-category flag, so a replacement offered there would do nothing.
   */
  showReplacement: boolean;
  /** null/null = no explicit replacement (the platform default is used). */
  onReplacementChange: (model: ModelConfigEntry, provider: string | null, modelId: string | null) => void;
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
  /** V533: retire the model for good (asks for confirmation first). */
  onRetire: (model: ModelConfigEntry) => void;
  /** True while the list is sorted by release date: a drop position would mean nothing. */
  dragDisabled: boolean;
  /** Show the release date on the name line (while sorting by it, so the order reads). */
  showReleaseDate: boolean;
  selected: boolean;
  onSelectedChange: (model: ModelConfigEntry, selected: boolean) => void;
  /** Values: the release-date label names its date. */
  t: (key: string, values?: Record<string, string>) => string;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: `${model.provider}:${model.id}`, disabled: dragDisabled });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };
  const releasedOn = showReleaseDate ? formatUtcDateOrNull(model.releaseDate) : null;

  return (
    <div
      ref={setNodeRef}
      style={style}
      data-testid={`model-row-${model.provider}-${model.id}`}
      className={cn(
        "px-3 py-2 rounded-lg border border-theme bg-theme-primary transition-colors",
        isDragging && "opacity-50 shadow-lg z-50",
        model.enabled === false && "opacity-40"
      )}
    >
      {/* Model name, then the real id beside it, then the badges, on a line of their
          own so no column width can squeeze the name out (see ROW_GRID_COLS).

          The id is the point of the second label. The name is editable, so it is
          whatever an admin last typed, and with only that on screen there was no way
          to tell a renamed model from one still wearing its catalogue name, nor to
          find the id a workflow or an execution link actually refers to. It appears
          ONLY when the two differ, so its presence means "this one has been renamed". */}
      <div className="flex items-center gap-1.5 min-w-0 mb-1.5">
        <div className="min-w-0 max-w-full">
          <NameCell model={model} onNameChange={onNameChange} t={t} />
        </div>
        {model.name !== model.id && (
          <span
            className="min-w-0 flex-1 truncate text-xs font-mono text-theme-secondary"
            title={model.id}
            data-testid={`model-id-${model.provider}-${model.id}`}
          >
            {model.id}
          </span>
        )}
        {/* Both of these used to be text pills. On a catalogue where most rows carry at
            least one, the row read as a sentence of badges and the name lost the fight for
            attention. They keep their full wording in the tooltip. */}
        {model.isCustom && (
          <span
            title={t("modelConfig.custom")}
            aria-label={t("modelConfig.custom")}
            data-testid={`model-custom-${model.provider}-${model.id}`}
            className="flex-shrink-0 text-purple-600 dark:text-purple-400"
          >
            <Sparkles className="w-3.5 h-3.5" />
          </span>
        )}
        {/* Full catalog is listed for ranking even without a key. Mark rows the
            picker/runtime can't yet serve so it isn't confusing. Bridge rows use
            their own availability signal, so they're excluded here. */}
        {showReleaseDate && (
          <span
            className="flex-shrink-0 text-xs text-theme-secondary"
            data-testid={`model-release-${model.provider}-${model.id}`}
          >
            {releasedOn
              ? t("modelConfig.releasedOn", { date: releasedOn })
              : t("modelConfig.releaseUnknown")}
          </span>
        )}
        {model.available === false && model.providerKind !== 'bridge' && (
          <span
            title={t("modelConfig.notConfiguredTooltip")}
            aria-label={t("modelConfig.notConfigured")}
            data-testid={`model-unconfigured-${model.provider}-${model.id}`}
            className="flex-shrink-0 text-amber-600 dark:text-amber-400"
          >
            <KeyRound className="w-3.5 h-3.5" />
          </span>
        )}
        {/* Execution-link badge + one-click "route to the CLI" button. Cloud
            only: the execution-link endpoints are not loaded in CE, so the
            control would 404 there. Renders nothing for a model that is neither
            linked nor routable by a CLI. */}
        {IS_CLOUD && executionLinksLoaded && (
          <ModelExecutionLinkCell
            model={model}
            links={executionLinks}
            onChanged={onExecutionLinksChanged}
            onError={onExecutionLinkError}
          />
        )}
        {/* V515: a disabled model is not removed from the runs that already use it (agents,
            workflow nodes, chat endpoints): they run on this replacement instead, or on the
            platform default when none is chosen. Only shown while the model is disabled,
            the only state in which it does anything. */}
        {showReplacement && model.enabled === false && (
          <ReplacementSelect
            model={model}
            options={replacementOptions}
            onChange={onReplacementChange}
            t={t}
          />
        )}
      </div>

      <div className={cn("grid items-center gap-2", ROW_GRID_COLS)}>
      {/* Selection, for the bulk bar above the list. Its own column so the drag handle
          stays where the eye expects it. */}
      <Checkbox
        checked={selected}
        onCheckedChange={(v) => onSelectedChange(model, v === true)}
        aria-label={`${t("modelConfig.selectRow")} ${model.name}`}
        data-testid={`model-select-${model.provider}-${model.id}`}
      />

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
        <RankCell
          model={model}
          rank={index + 1}
          rankCount={rankCount}
          onMoveToRank={onMoveToRank}
          t={t}
        />
      </div>

      {/* Enable/disable toggle */}
      <Switch
        checked={model.enabled !== false}
        onCheckedChange={() => onToggleEnabled(model)}
        testId={`model-toggle-${model.provider}-${model.id}`}
        aria-label={model.id}
      />

      {/* Cloud-admin bundle override (V381): what the CE bundle ships for this
          model, independent of the cloud's enabled toggle. 3 states: inherit
          (follows enabled), always-on, always-off. Cloud only - a CE has no
          bundle to author. */}
      {!IS_CE && (
        <button
          type="button"
          onClick={() => onCycleBundleEnabled(model)}
          data-testid={`model-bundle-enabled-${model.provider}-${model.id}`}
          title={t("modelConfig.bundleShipTooltip")}
          className={cn(
            "w-full px-1.5 py-0.5 rounded-md text-xs font-medium border transition-colors whitespace-nowrap text-center",
            model.bundleEnabled == null
              ? "border-theme text-theme-secondary bg-theme-tertiary"
              : model.bundleEnabled
                ? "border-emerald-300 text-emerald-700 bg-emerald-50 dark:border-emerald-700 dark:text-emerald-400 dark:bg-emerald-900/20"
                : "border-red-300 text-red-700 bg-red-50 dark:border-red-700 dark:text-red-400 dark:bg-red-900/20"
          )}
        >
          {model.bundleEnabled == null
            ? t("modelConfig.bundleShipInherit")
            : model.bundleEnabled
              ? t("modelConfig.bundleShipOn")
              : t("modelConfig.bundleShipOff")}
        </button>
      )}

      {/* Free-tier opening (V493): whether a Free-plan account may spend its monthly
          credits on a chat / agent turn on this model. Off by default. Cloud only - a
          CE install meters nothing, so there is nothing to open. Only rendered on the
          chat and browser_agent tabs, whose source types the Free credits actually
          fund; image rows are mode-filtered out
          of both and can never reach this. */}
      {!IS_CE && (
        <button
          type="button"
          onClick={() => onToggleFreeTier(model)}
          data-testid={`model-free-tier-${model.provider}-${model.id}`}
          title={t("modelConfig.freeTierTooltip")}
          className={cn(
            "w-full px-1.5 py-0.5 rounded-md text-xs font-medium border transition-colors whitespace-nowrap text-center",
            model.freeTierEnabled
              ? "border-sky-300 text-sky-700 bg-sky-50 dark:border-sky-700 dark:text-sky-400 dark:bg-sky-900/20"
              : "border-theme text-theme-secondary bg-theme-tertiary"
          )}
        >
          {t("modelConfig.freeTierChip")}
        </button>
      )}

      {/* Provider badge - icon + name (icons distinguish CLI from API) */}
      <ProviderBadge provider={model.provider} />

      {/* Tier select */}
      <Select
        value={model.tier || "mid"}
        onValueChange={(value) => onTierChange(model, value)}
      >
        <SelectTrigger className={cn(
          "!h-6 !min-h-0 text-sm font-medium !border-0 !rounded-lg !px-2 !py-0.5 min-w-0 w-auto gap-0.5 [&>svg]:h-3 [&>svg]:w-3 !hover:bg-none hover:!bg-[unset]",
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
          <SelectTrigger className="!h-6 !min-h-0 text-sm !rounded-lg !px-2 !py-0.5 min-w-0 w-auto gap-0.5 [&>svg]:h-3 [&>svg]:w-3 bg-theme-tertiary text-theme-secondary">
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
          onClick={() => onRetire(model)}
          className="text-theme-secondary hover:text-amber-600 transition-colors p-1"
          title={t("modelConfig.retire.action")}
          aria-label={`${t("modelConfig.retire.action")}: ${model.name}`}
          data-testid={`model-retire-${model.provider}-${model.id}`}
        >
          <Archive className="w-3.5 h-3.5" />
        </button>
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
    </div>
  );
}

/**
 * V156 - model categories the admin can manage independently. Order matches
 * the tab rendering order in the panel. Adding a category here + its i18n keys
 * is enough for the backend, which is forward-compatible (any lowercase
 * snake_case key passes the shape regex; absent rows fall back to global
 * ranking). Adding one to this list is what gives a category a reader, so it is
 * also the decision that a ranking means something for it - see below.
 */
const CATEGORIES = [
  'chat',
  'browser_agent',
] as const;
type Category = typeof CATEGORIES[number];

// Deliberately NOT here: the five generation formats (image, video, audio,
// voice, music).
//
// This panel exists to choose between interchangeable models: rank them so a
// failed call falls through to the next, enable one, read its per-token price.
// None of that applies to a generation model. There is no fallback order (the
// caller names its model, and a video model is not a substitute for a voice
// one), nothing to enable (the model exists because the catalog endpoint
// exists), and its price is per image or per second and is published against a
// platform credential, not here.
//
// The tabs were added when image generation still ran on model-catalogue rows
// with a fallback ranking. That subsystem is gone, so what those tabs listed was
// models no code can execute, next to four tabs the LLM feed never fills.
// Platform Credentials is the one screen that decides whether a generation model
// is sellable and at what price; a read-only copy here would only be a second
// place for the same price to be stated, and to drift.
//
// The backend keeps accepting `?category=<format>_generation` on purpose: rows
// already written for those categories stay readable, and the CE contract specs
// that assert the parameter's shape keep their meaning. What went away is the
// only screen that offered them.
//
// One consequence worth stating plainly: a `mode='image'` row persisted in
// model_config_overrides is now unreachable from every admin screen. There is no
// way left to price, disable or delete one from the UI. That is accepted, since
// no code path can run such a model either, but it does mean the rows are
// leftovers, and clearing them is a data question rather than a UI one.

export default function ModelManagementPanel({ t }: ModelManagementPanelProps) {
  const [models, setModels] = useState<ModelConfigEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [providerFilter, setProviderFilter] = useState<string>("all");
  /**
   * Providers switched off entirely, lower-cased. Absent from the list means on; null means
   * the answer could not be read, which is NOT the same and must not draw a switch.
   */
  const [disabledProviders, setDisabledProviders] = useState<string[] | null>([]);
  /** Rows ticked for a bulk change, keyed `provider:id`. */
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(() => new Set());
  /** Free-text match on the model id and on the name an admin gave it. */
  const [search, setSearch] = useState<string>("");
  const [tierFilter, setTierFilter] = useState<string>("all");
  /** "all" | "on" | "off" - which side of the per-model switch to show. */
  const [stateFilter, setStateFilter] = useState<string>("all");
  /**
   * "rank" (the fallback order, the default) or a release-date order, which is how an admin
   * finds the old models worth retiring. Models with no known release date go last either way.
   */
  const [sortOrder, setSortOrder] = useState<SortOrder>("rank");
  /** Bumped after a retire so the Retired list re-reads. */
  const [retiredReload, setRetiredReload] = useState(0);
  const { toasts, addToast, removeToast } = useToast();
  /**
   * Active category tab. {@code 'chat'} mirrors the legacy global view -
   * writes go to the parent {@code model_config_overrides.ranking} column
   * via {@code bulkUpdateRankings} (no category param) so existing chat
   * behaviour is untouched. The other tabs target the V156 sidecar via
   * {@code bulkUpdateRankings(category)} + {@code setCategoryEnabled}.
   */
  const [category, setCategory] = useState<Category>('chat');
  /**
   * Every execution link, indexed per billed model below. Cloud only: the
   * endpoint is not loaded in CE, and the routing it configures does not exist
   * there either.
   */
  const [executionLinks, setExecutionLinks] = useState<ModelExecutionLink[]>([]);
  /**
   * Did the list actually load? An empty list because the read FAILED is not the same
   * fact as "this model is not routed", and the difference is a write: the row would
   * fall back to the create button, whose PUT upserts on (pair, scope) and would
   * overwrite an existing ALL link (its target, its execution model, its enabled flag)
   * that the admin never saw. Unknown renders no control at all.
   */
  const [executionLinksLoaded, setExecutionLinksLoaded] = useState(false);
  /**
   * Everything execution-link (a failed read, a failed write) reports here rather than
   * into the panel's shared error slot. Sharing needed a provenance flag to stop a link
   * write clearing a tier error, and that flag could not guard the other direction: a
   * successful tier write refreshes the model list, which clears the slot, so "the
   * routing failed to save" vanished on an unrelated success.
   */
  const [executionLinkError, setExecutionLinkError] = useState<string | null>(null);

  // Sliding-indicator pill toggle for the category selector - mirrors the
  // page's connection-mode toggle so the two read as the same kind of control.
  const categoryTabRef = useRef<HTMLDivElement>(null);
  const [categorySliderStyle, setCategorySliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  useEffect(() => {
    const updateSlider = () => {
      if (!categoryTabRef.current) return;
      const activeButton = categoryTabRef.current.querySelector(
        `[data-category-id="${category}"]`) as HTMLButtonElement | null;
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

  const isProviderOff = useCallback(
    (provider: string) => (disabledProviders ?? []).includes(provider.toLowerCase()),
    [disabledProviders],
  );

  /**
   * Switch the whole provider currently being filtered on. This is the move the panel had no
   * answer for: a provider the feed fills carries hundreds of rows (OpenRouter alone is 438),
   * so taking it out of the pickers one model at a time was not a real option.
   *
   * Optimistic like the per-model toggle, and rolled back with the server's own message on
   * failure. Each model's flag is untouched, so switching back on restores what was curated.
   */
  const handleToggleProvider = useCallback(
    (provider: string) => {
      const key = provider.toLowerCase();
      const nextEnabled = (disabledProviders ?? []).includes(key);
      setDisabledProviders((prev) =>
        nextEnabled ? (prev ?? []).filter((p) => p !== key) : [...(prev ?? []), key],
      );
      setSaving(true);
      modelConfigService
        .setProviderEnabled(provider, nextEnabled)
        .then(() => clearModelsCache())
        .catch((e) => {
          setDisabledProviders((prev) =>
            nextEnabled ? [...(prev ?? []), key] : (prev ?? []).filter((p) => p !== key),
          );
          setError(e instanceof Error && e.message ? e.message : t("modelConfig.saveError"));
        })
        .finally(() => setSaving(false));
    },
    [disabledProviders, t],
  );

  const providerOptions = useMemo(() => {
    const seen = new Set<string>();
    const list: string[] = [];
    for (const m of models) {
      if (!seen.has(m.provider)) {
        seen.add(m.provider);
        list.push(m.provider);
      }
    }
    // A provider switched off can be GONE from the catalogue it came from: one made only of
    // custom models is not in the base list at all, and the switch that removed it is only
    // reachable once it is picked here. Without this union such a provider is stuck off with
    // no route back in the UI.
    for (const p of disabledProviders ?? []) {
      if (!seen.has(p)) {
        seen.add(p);
        list.push(p);
      }
    }
    list.sort((a, b) => a.localeCompare(b));
    return list;
  }, [models, disabledProviders]);

  const visibleModels = useMemo(() => {
    let filtered = providerFilter === "all"
      ? models
      : models.filter(m => m.provider === providerFilter);
    // V156 - hide bridges on the browser_agent tab because they don't expose
    // the per-step chat completions it drives. Re-ranking or disabling a bridge
    // there has zero runtime effect, so the row would mislead. Chat tab keeps
    // showing them - full-session bridges DO work for chat.
    //
    // Named, not `!== 'chat'`: a category added later should have to state
    // whether bridges belong to it, rather than inherit the answer.
    if (category === 'browser_agent') {
      filtered = filtered.filter(m => m.providerKind !== 'bridge');
    }
    if (tierFilter !== "all") {
      filtered = filtered.filter(m => (m.tier ?? "") === tierFilter);
    }
    if (stateFilter !== "all") {
      const wantEnabled = stateFilter === "on";
      filtered = filtered.filter(m => (m.enabled !== false) === wantEnabled);
    }
    const needle = search.trim().toLowerCase();
    if (needle) {
      // Both the id and the name, because the two diverge as soon as an admin renames a
      // model, and searching for what you see on screen has to work either way.
      filtered = filtered.filter(m =>
        m.id.toLowerCase().includes(needle) || (m.name ?? "").toLowerCase().includes(needle),
      );
    }
    if (sortOrder !== "rank") {
      filtered = sortByReleaseDate(filtered, sortOrder);
    }
    return filtered;
  }, [models, providerFilter, category, tierFilter, stateFilter, search, sortOrder]);

  const filtersActive =
    providerFilter !== "all" || tierFilter !== "all" || stateFilter !== "all" || search.trim() !== "";

  const clearFilters = useCallback(() => {
    setProviderFilter("all");
    setTierFilter("all");
    setStateFilter("all");
    setSearch("");
  }, []);

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
      // Which providers are switched off ENTIRELY. Kept OUT of the await above, and out of
      // the model list itself, for two reasons. A disabled provider must keep every one of
      // its models listed here or there would be no way to switch it back on; and this is a
      // refinement of the list, never a reason to fail rendering it, so an install whose
      // agent-service predates the endpoint still gets a working panel instead of an error
      // where the catalogue should be. Promise.resolve().then so a synchronous throw lands
      // in the same place as a rejection.
      void Promise.resolve()
        .then(() => modelConfigService.getDisabledProviders())
        .then((off) => setDisabledProviders(off.map((p) => p.toLowerCase())))
        .catch(() => {
          // Unknown, not "none off". Treating a failed read as an empty list drew every
          // switch ON, so the first click on a provider that is already OFF wrote OFF again:
          // a no-op that flips the control and takes two clicks to undo. The switch is
          // hidden instead, and the banner says why.
          setDisabledProviders(null);
          setError(t("modelConfig.providerSwitchUnavailable"));
        });
    } catch {
      setError(t("modelConfig.fetchError"));
    } finally {
      if (!opts?.silent) setLoading(false);
    }
  }, [t, category]);

  const keyOf = (m: ModelConfigEntry) => `${m.provider}:${m.id}`;

  const allFilteredSelected =
    visibleModels.length > 0 && visibleModels.every((m) => selectedKeys.has(keyOf(m)));

  /** Tick or untick every row the current filters leave on screen, and only those. */
  const selectAllFiltered = useCallback(
    (next: boolean) => {
      setSelectedKeys((prev) => {
        const out = new Set(prev);
        for (const m of visibleModels) {
          if (next) out.add(`${m.provider}:${m.id}`);
          else out.delete(`${m.provider}:${m.id}`);
        }
        return out;
      });
    },
    [visibleModels],
  );

  const setRowSelected = useCallback((model: ModelConfigEntry, next: boolean) => {
    setSelectedKeys((prev) => {
      const out = new Set(prev);
      const key = `${model.provider}:${model.id}`;
      if (next) out.add(key);
      else out.delete(key);
      return out;
    });
  }, []);

  /** The selected rows, in the order they are on screen. */
  const selectedModels = useMemo(
    () => visibleModels.filter((m) => selectedKeys.has(`${m.provider}:${m.id}`)),
    [visibleModels, selectedKeys],
  );

  /**
   * Apply one change to every selected row, sequentially.
   *
   * <p>Sequential on purpose: these are admin writes against a shared catalogue, a selection
   * is tens of rows rather than hundreds (the provider switch is the answer for hundreds),
   * and a burst of parallel PUTs would only make a partial failure harder to read. The
   * panel reloads once at the end so the list reflects what the server actually took, and
   * says how many failed rather than claiming success.
   */
  const applyToSelection = useCallback(
    async (label: string, apply: (model: ModelConfigEntry) => Promise<unknown>) => {
      if (selectedModels.length === 0) return;
      setSaving(true);
      setError(null);
      let failed = 0;
      let lastMessage = "";
      for (const model of selectedModels) {
        try {
          await apply(model);
        } catch (e) {
          failed++;
          lastMessage = e instanceof Error && e.message ? e.message : "";
        }
      }
      clearModelsCache();
      // Reload FIRST: a successful reload clears the error banner, so reporting a partial
      // batch before it would wipe the one message that says the batch was partial.
      await fetchModels({ silent: true });
      if (failed > 0) {
        setError(
          t("modelConfig.bulkPartial", {
            action: label,
            failed: String(failed),
            total: String(selectedModels.length),
            reason: lastMessage,
          }),
        );
      }
      setSaving(false);
    },
    [selectedModels, t, fetchModels],
  );

  const bulkSetEnabled = useCallback(
    (enabled: boolean) =>
      applyToSelection(
        enabled ? t("modelConfig.bulkEnable") : t("modelConfig.bulkDisable"),
        (model) =>
          category === "chat"
            ? modelConfigService.saveOverride({
                provider: model.provider,
                modelId: model.id,
                enabled,
              })
            : modelConfigService.setCategoryEnabled(model.provider, model.id, category, enabled),
      ),
    [applyToSelection, category, t],
  );

  const bulkSetTier = useCallback(
    (tier: string) =>
      applyToSelection(t("modelConfig.bulkTier"), (model) =>
        modelConfigService.saveOverride({
          provider: model.provider,
          modelId: model.id,
          tier,
        }),
      ),
    [applyToSelection, t],
  );


  // Re-fetch on tab change. fetchModels is memoised on category so this is
  // exactly one fetch per tab switch - no thrash, no race window where the
  // user sees stale data from the previous tab.
  useEffect(() => {
    fetchModels();
  }, [fetchModels]);

  /**
   * Execution links are independent of the category tab (a link is keyed on the billed
   * pair, not on a surface tab), so the DATA does not change per tab. The tab is still a
   * dependency below, as the retry: a failed read hides every routing control, and
   * without it that would last the whole session.
   */
  const loadExecutionLinks = useCallback(async () => {
    if (!IS_CLOUD) return;
    try {
      const data = await modelConfigService.listExecutionLinks();
      setExecutionLinks(Array.isArray(data) ? data : []);
      setExecutionLinksLoaded(true);
      // Clear only the message this loader owns: a write failure re-reads the list on
      // its way out (that is how a half-done multi-row delete stops being displayed), so
      // a blanket clear here would erase the failure the admin has not read yet.
      setExecutionLinkError((prev) => (prev === t("executionLinks.loadError") ? null : prev));
    } catch (err) {
      // A model list that renders is worth more than the badges, so the failure is not
      // fatal - but it IS surfaced, and the routing controls stay hidden rather than
      // claiming every model is unrouted.
      console.error("Failed to load model execution links:", err);
      setExecutionLinks([]);
      setExecutionLinksLoaded(false);
      setExecutionLinkError(t("executionLinks.loadError"));
    }
    // `category` is not read here (a link is keyed on the billed pair, not on a tab),
    // it is the retry: switching tabs re-runs this, so a transient failure is not a
    // dead end for the rest of the session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t, category]);

  useEffect(() => {
    loadExecutionLinks();
  }, [loadExecutionLinks]);

  const executionLinksByModel = useMemo(() => {
    const byPair = new Map<string, ModelExecutionLink[]>();
    for (const link of executionLinks) {
      const key = `${link.billedProvider}:${link.billedModel}`;
      const existing = byPair.get(key);
      if (existing) existing.push(link);
      else byPair.set(key, [link]);
    }
    return byPair;
  }, [executionLinks]);

  /**
   * The list is virtualised: only the rows in view (plus an overscan) are mounted. A full
   * catalogue is several hundred rows, each carrying selects, inputs and a drag handle, and
   * every DISABLED row also carries a replacement select whose options are every enabled
   * model, so mounting them all at once is what made the tab slow to open.
   */
  // State-backed: the list replaces its handle once its element exists, and that has to
  // re-render the panel so the gutter below is read from a mounted element.
  const [listApi, setListApi] = useListCallbackRef(null);
  const rowHeight = useDynamicRowHeight({ defaultRowHeight: ESTIMATED_ROW_HEIGHT, key: category });
  const [viewportHeight, setViewportHeight] = useState(() =>
    typeof window === "undefined" ? 900 : window.innerHeight,
  );
  useEffect(() => {
    const onResize = () => setViewportHeight(window.innerHeight);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  /** Set by a typed rank, consumed once the list has re-rendered in its new order. */
  const pendingScrollKey = useRef<string | null>(null);
  /** Row being dragged, lifted above its siblings (each virtual row is its own layer). */
  const [draggingKey, setDraggingKey] = useState<string | null>(null);
  /**
   * Width of the list's own scrollbar gutter (0 with overlay scrollbars), read off the list
   * itself: the app styles its scrollbars, so a generic probe measures the wrong one.
   */
  const [scrollbarWidth, setScrollbarWidth] = useState(0);
  // Not from the list's onResize: that fires before the handle exists, and never again for
  // a list whose height is fixed. The gutter is `stable`, so it does not change with content.
  useEffect(() => {
    const el = listApi?.element;
    if (!el) return;
    const width = el.offsetWidth - el.clientWidth;
    setScrollbarWidth((prev) => (prev === width ? prev : width));
  }, [listApi, viewportHeight]);

  /** Rank = position in the whole list of this tab, so filters never renumber a row. */
  const rankIndex = useMemo(
    () => new Map(models.map((m, i) => [`${m.provider}:${m.id}`, i])),
    [models],
  );

  useEffect(() => {
    const key = pendingScrollKey.current;
    if (!key) return;
    pendingScrollKey.current = null;
    const i = visibleModels.findIndex((m) => `${m.provider}:${m.id}` === key);
    if (i !== -1) listApi?.scrollToRow({ index: i, align: "smart" });
  }, [visibleModels, listApi]);

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

    await persistOrder(arrayMove(models, oldIndex, newIndex));
  };

  /** Typed rank (1-based, already clamped by the cell) = the long move a drag cannot make. */
  const handleMoveToRank = async (model: ModelConfigEntry, rank: number) => {
    const oldIndex = models.findIndex((m) => m.provider === model.provider && m.id === model.id);
    if (oldIndex === -1 || oldIndex === rank - 1) return;
    // Follow the row to where it lands: in a virtualised list it would otherwise vanish
    // from the viewport, and the admin could not see that the move happened.
    pendingScrollKey.current = `${model.provider}:${model.id}`;
    await persistOrder(arrayMove(models, oldIndex, rank - 1));
  };

  const persistOrder = async (reordered: ModelConfigEntry[]) => {
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

  // Cloud-admin bundle override (V381): cycle inherit -> ship-on -> ship-off.
  // Decouples "what the CE bundle ships" from the cloud's own enabled flag, so
  // a model greyed on cloud can still reach CE users (and vice versa).
  const handleCycleBundleEnabled = (model: ModelConfigEntry) => {
    const current = model.bundleEnabled;
    const next = current == null ? true : current === true ? false : null;
    setModels((prev) =>
      prev.map((m) =>
        m.provider === model.provider && m.id === model.id
          ? { ...m, bundleEnabled: next, hasOverride: true }
          : m,
      ),
    );
    modelConfigService
      .saveOverride({ provider: model.provider, modelId: model.id, bundleEnabled: next })
      .catch((e) => {
        setModels((prev) =>
          prev.map((m) =>
            m.provider === model.provider && m.id === model.id
              ? { ...m, bundleEnabled: current }
              : m,
          ),
        );
        setError(e instanceof Error ? e.message : String(e));
      });
  };

  // Free-tier opening (V493): lets a Free-plan account fund a chat / agent turn on
  // this model from its monthly credits instead of the PAYG bucket alone. This is
  // what makes an agent usable for a visitor who has not topped up.
  const handleToggleFreeTier = (model: ModelConfigEntry) => {
    const current = model.freeTierEnabled === true;
    const next = !current;
    setModels((prev) =>
      prev.map((m) =>
        m.provider === model.provider && m.id === model.id
          ? { ...m, freeTierEnabled: next, hasOverride: true }
          : m,
      ),
    );
    modelConfigService
      .saveOverride({ provider: model.provider, modelId: model.id, freeTierEnabled: next })
      .catch((e) => {
        setModels((prev) =>
          prev.map((m) =>
            m.provider === model.provider && m.id === model.id
              ? { ...m, freeTierEnabled: current }
              : m,
          ),
        );
        setError(e instanceof Error ? e.message : String(e));
      });
  };

  // V515: what a disabled model's existing runs execute on instead. Optimistic like the
  // chips above; a refused pair (unknown, itself) comes back as the server's message.
  const handleReplacementChange = (
    model: ModelConfigEntry,
    provider: string | null,
    modelId: string | null,
  ) => {
    const previous = { replacementProvider: model.replacementProvider, replacementModel: model.replacementModel };
    const patch = { replacementProvider: provider ?? undefined, replacementModel: modelId ?? undefined };
    setModels((prev) =>
      prev.map((m) =>
        m.provider === model.provider && m.id === model.id ? { ...m, ...patch, hasOverride: true } : m,
      ),
    );
    modelConfigService
      .saveOverride({
        provider: model.provider,
        modelId: model.id,
        replacementProvider: provider,
        replacementModel: modelId,
      })
      .catch((e) => {
        setModels((prev) =>
          prev.map((m) =>
            m.provider === model.provider && m.id === model.id ? { ...m, ...previous } : m,
          ),
        );
        setError(e instanceof Error ? e.message : String(e));
      });
  };

  // Only models a run could actually use: enabled, configured, and not a cloud CLI bridge. The backend accepts any
  // catalog model, but at run time an unconfigured replacement is skipped for the platform
  // default, so offering one here would be a choice that silently does nothing.
  const replacementOptions = useMemo(
    () => models.filter((m) =>
      m.enabled !== false
      && m.available !== false
      // On cloud a CLI bridge is admin-only: every non-admin run swapped onto one would be
      // refused by the bridge access check, which is the failure this control exists to end.
      && !(IS_CLOUD && m.providerKind === 'bridge')),
    [models],
  );

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
      .catch((e) => {
        // Roll back the optimistic flip and surface the error.
        setModels((prev) =>
          prev.map((m) =>
            m.provider === model.provider && m.id === model.id
              ? { ...m, enabled: model.enabled }
              : m,
          ),
        );
        // The SERVER message when there is one. This used to discard it and show a generic
        // sentence, so a refusal that names its reason (an unpriced model cannot be enabled,
        // an unknown pair) reached the admin as "Failed to save changes" and nothing else.
        setError(e instanceof Error && e.message ? e.message : t("modelConfig.saveError"));
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

  /**
   * V533: retire models for good. They leave the admin list and every picker, the CE bundle
   * stops shipping them, and no feed sync, seed or bundle can bring them back; only a restore
   * from the Retired list does. Confirmed first because of that last part.
   */
  const handleRetire = async (targets: ModelConfigEntry[]) => {
    if (targets.length === 0) return;
    const question =
      targets.length === 1
        ? t("modelConfig.retire.confirmOne", { name: targets[0].name })
        : t("modelConfig.retire.confirmMany", { count: String(targets.length) });
    if (!confirm(question)) return;
    setSaving(true);
    try {
      const res = await modelConfigService.retireModels(
        targets.map((m) => ({ provider: m.provider, modelId: m.id })),
      );
      clearModelsCache();
      const gone = new Set(targets.map((m) => `${m.provider}:${m.id}`));
      setSelectedKeys((prev) => new Set([...prev].filter((k) => !gone.has(k))));
      addToast({
        type: "success",
        title: t("modelConfig.retire.doneTitle", { count: String(res?.retired ?? targets.length) }),
        message: t("modelConfig.retire.doneMessage"),
      });
      setRetiredReload((n) => n + 1);
      await fetchModels({ silent: true });
    } catch (e) {
      // The server's reason when it gave one, not a generic sentence.
      addToast({
        type: "error",
        title: t("modelConfig.retire.error"),
        message: e instanceof Error && e.message ? e.message : "",
      });
    } finally {
      setSaving(false);
    }
  };

  /** After a restore: the models are back (disabled) in the main list. */
  const handleRestored = async () => {
    clearModelsCache();
    await fetchModels({ silent: true });
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
  // Grows with the rows until it reaches most of the viewport, then scrolls inside, so a
  // filter that leaves three models does not sit in a mostly empty box.
  // Summed over the rows actually listed (measured, else the estimate): an average would
  // also count rows a previous filter measured, and leave a strip or a stray scrollbar.
  const listCap = Math.round(viewportHeight * LIST_VIEWPORT_SHARE);
  let listHeight = 0;
  for (let i = 0; i < visibleModels.length && listHeight < listCap; i++) {
    listHeight += rowHeight.getRowHeight(i) ?? ESTIMATED_ROW_HEIGHT;
  }
  listHeight = Math.min(listCap, Math.ceil(listHeight));
  const draggedModel = draggingKey
    ? visibleModels.find((m) => `${m.provider}:${m.id}` === draggingKey) ?? null
    : null;

  const renderRow = (model: ModelConfigEntry) => {
    const key = `${model.provider}:${model.id}`;
    return (
      <SortableModelRow
        key={key}
        model={model}
        index={rankIndex.get(key) ?? 0}
        rankCount={models.length}
        onMoveToRank={handleMoveToRank}
        executionLinks={executionLinksByModel.get(key) ?? NO_EXECUTION_LINKS}
        executionLinksLoaded={executionLinksLoaded}
        onExecutionLinksChanged={loadExecutionLinks}
        onExecutionLinkError={setExecutionLinkError}
        onToggleEnabled={handleToggleEnabled}
        onCycleBundleEnabled={handleCycleBundleEnabled}
        onToggleFreeTier={handleToggleFreeTier}
        onToggleRecommended={handleToggleRecommended}
        replacementOptions={replacementOptions}
        showReplacement={category === 'chat'}
        onReplacementChange={handleReplacementChange}
        onTierChange={handleTierChange}
        onReasoningEffortChange={handleReasoningEffortChange}
        onPricingChange={handlePricingChange}
        onRateLimitChange={handleRateLimitChange}
        onNameChange={handleNameChange}
        onDelete={handleDelete}
        onReset={handleReset}
        onRetire={(m) => handleRetire([m])}
        dragDisabled={sortOrder !== "rank"}
        showReleaseDate={sortOrder !== "rank"}
        selected={selectedKeys.has(key)}
        onSelectedChange={setRowSelected}
        t={t}
      />
    );
  };

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
          className="relative inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-2xl w-max"
          ref={categoryTabRef}
        >
          <div
            className="absolute top-1.5 bottom-1.5 rounded-xl bg-[var(--bg-primary)] transition-all duration-200 ease-out"
            style={{
              left: categorySliderStyle.left,
              width: categorySliderStyle.width,
              opacity: categorySliderStyle.width ? 1 : 0,
            }}
          />
          {CATEGORIES.map((tab) => (
            <button
              key={tab}
              data-category-id={tab}
              type="button"
              onClick={() => setCategory(tab)}
              aria-pressed={category === tab}
              className={cn(
                "relative z-10 flex h-9 flex-shrink-0 items-center px-4 rounded-xl text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
                category === tab
                  ? "text-[var(--text-primary)]"
                  : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
              )}
            >
              {t(`modelConfig.category.${tab}.label`)}
            </button>
          ))}
        </div>
      </div>

      <div className="flex items-center justify-between">
        <p className="text-xs text-theme-secondary">
          {t(`modelConfig.category.${category}.hint`)}
        </p>
        <div className="flex items-center gap-2 flex-wrap justify-end">
          {saving && <LoadingSpinner size="xs" />}
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("modelConfig.searchPlaceholder")}
            aria-label={t("modelConfig.searchPlaceholder")}
            data-testid="model-search"
            className="h-9 w-[200px] rounded-lg border border-theme bg-theme-primary px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60"
          />
          {/* Wrapped so a test can scope to THIS select: every row carries a tier select of
              its own, with the same option values. */}
          <div data-testid="tier-filter">
          <Select value={tierFilter} onValueChange={setTierFilter}>
            <SelectTrigger
              className="rounded-lg px-3 text-sm min-w-[120px]"
              aria-label={t("modelConfig.filterByTier")}
              title={t("modelConfig.filterByTier")}
            >
              <SelectValue placeholder={t("modelConfig.filterByTier")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t("modelConfig.allTiers")}</SelectItem>
              {TIER_OPTIONS.map((tier) => (
                <SelectItem key={tier.value} value={tier.value}>{tier.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          </div>
          <Select value={stateFilter} onValueChange={setStateFilter}>
            <SelectTrigger
              className="rounded-lg px-3 text-sm min-w-[120px]"
              aria-label={t("modelConfig.filterByState")}
              title={t("modelConfig.filterByState")}
            >
              <SelectValue placeholder={t("modelConfig.filterByState")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t("modelConfig.allStates")}</SelectItem>
              <SelectItem value="on">{t("modelConfig.stateOn")}</SelectItem>
              <SelectItem value="off">{t("modelConfig.stateOff")}</SelectItem>
            </SelectContent>
          </Select>
          <Select value={providerFilter} onValueChange={setProviderFilter}>
            {/* rounded-lg keeps a softly-squared edge (less pill-like than the
                action buttons); height inherits the standard h-9 control size. */}
            <SelectTrigger
              className="rounded-lg px-3 text-sm min-w-[160px]"
              aria-label={t("modelConfig.filterByProvider")}
              title={t("modelConfig.filterByProvider")}
            >
              <SelectValue placeholder={t("modelConfig.filterByProvider")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t("modelConfig.allProviders")}</SelectItem>
              {providerOptions.map((p) => (
                <SelectItem key={p} value={p}>
                  {isProviderOff(p) ? t("modelConfig.providerOffOption", { provider: p }) : p}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {/* Release-date order: the quickest way to find the old models worth retiring.
              Dragging is off while it is active, since a drop position would not be a rank. */}
          <div data-testid="sort-order">
          <Select value={sortOrder} onValueChange={(v) => setSortOrder(v as SortOrder)}>
            <SelectTrigger
              className="rounded-lg px-3 text-sm min-w-[160px]"
              aria-label={t("modelConfig.sort.label")}
              title={t("modelConfig.sort.label")}
            >
              <SelectValue placeholder={t("modelConfig.sort.label")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="rank">{t("modelConfig.sort.rank")}</SelectItem>
              <SelectItem value="releaseAsc">{t("modelConfig.sort.oldest")}</SelectItem>
              <SelectItem value="releaseDesc">{t("modelConfig.sort.newest")}</SelectItem>
            </SelectContent>
          </Select>
          </div>
          {/* The provider switch lives next to the provider filter on purpose: it acts on the
              provider you are looking at, and there is no sensible "all providers" version of
              it. Hidden until one is picked. */}
          {providerFilter !== "all" && disabledProviders !== null && (
            <div className="flex items-center gap-2 rounded-lg border border-theme px-3 h-9">
              <span className="text-sm text-theme-secondary whitespace-nowrap">
                {t("modelConfig.providerEnabled")}
              </span>
              <Switch
                checked={!isProviderOff(providerFilter)}
                onCheckedChange={() => handleToggleProvider(providerFilter)}
                testId={`provider-toggle-${providerFilter}`}
                aria-label={t("modelConfig.providerEnabled")}
              />
            </div>
          )}
          {filtersActive && (
            <Button size="sm" variant="ghost" onClick={clearFilters} data-testid="clear-filters">
              {t("modelConfig.clearFilters")}
            </Button>
          )}
          <Button
            size="sm"
            variant="outline"
            onClick={handleResetAll}
            disabled={!hasAnyOverride}
          >
            <RotateCcw className="w-3.5 h-3.5 mr-1" />
            {t("modelConfig.resetAll")}
          </Button>
          <Button
            size="sm"
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

      {/* Said once, here, rather than on every row: the list below still shows each model and
          its own switch, and none of them is offered anywhere while the provider is off. */}
      {providerFilter !== "all" && isProviderOff(providerFilter) && (
        <div
          className="flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3"
          role="status"
          data-testid="provider-off-notice"
        >
          <AlertTriangle className="w-3.5 h-3.5 text-amber-600 flex-shrink-0" />
          <p className="text-sm text-amber-700 dark:text-amber-400">
            {t("modelConfig.providerOffNotice", { provider: providerFilter })}
          </p>
        </div>
      )}

      {/* The pricing page, the plan cards and the comparison table all announce that
          the Free plan's monthly credits pay for chat and agents. Those credits can only
          be spent on the models opened below, so with none open the promise is real
          money the platform advertises and then refuses on the first turn. Nothing
          errors and no log fires - the only place this is visible is here, in front of
          the person who can fix it in one click. Cloud only; CE meters nothing.

          Scoped to the CURRENT tab, because the models list is: each tab is its own kind of
          turn (chat, browser agent) and each is refused independently, so "nothing open
          here" is the true and useful statement. The copy says "on this tab" for that
          reason. */}
      {!IS_CE && models.length > 0 && !models.some((m) => m.freeTierEnabled) && (
        <div
          data-testid="free-tier-none-open"
          className="flex items-center gap-2 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 p-3"
        >
          <AlertTriangle className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
          <p className="text-sm text-amber-800 dark:text-amber-300">
            {t("modelConfig.freeTierNoneOpen")}
          </p>
        </div>
      )}

      {/* Its own line, amber not red: the model list is fine, only the routing badges
          are missing, and this has to stay on screen for as long as they are. */}
      {executionLinkError && (
        <div className="flex items-center gap-2 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 p-3">
          <AlertTriangle className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
          <p className="text-sm text-amber-800 dark:text-amber-300">{executionLinkError}</p>
        </div>
      )}

      {/* Bulk bar. Only on screen when something is ticked, so it never takes room from the
          list it acts on. */}
      {selectedModels.length > 0 && (
        <div
          className="flex items-center gap-2 flex-wrap rounded-lg border border-theme bg-theme-secondary/40 px-3 py-2"
          data-testid="bulk-bar"
        >
          <span className="text-sm text-theme-primary font-medium">
            {t("modelConfig.bulkSelected", { count: String(selectedModels.length) })}
          </span>
          <Button size="sm" variant="outline" data-testid="bulk-enable" onClick={() => bulkSetEnabled(true)}>
            {t("modelConfig.bulkEnable")}
          </Button>
          <Button size="sm" variant="outline" data-testid="bulk-disable" onClick={() => bulkSetEnabled(false)}>
            {t("modelConfig.bulkDisable")}
          </Button>
          <div data-testid="bulk-tier">
            <Select value="" onValueChange={bulkSetTier}>
              <SelectTrigger className="rounded-lg px-3 text-sm min-w-[130px] h-8" aria-label={t("modelConfig.bulkTier")}>
                <SelectValue placeholder={t("modelConfig.bulkTier")} />
              </SelectTrigger>
              <SelectContent>
                {TIER_OPTIONS.map((tier) => (
                  <SelectItem key={tier.value} value={tier.value}>{tier.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Button
            size="sm"
            variant="outline"
            data-testid="bulk-retire"
            className="text-amber-700 dark:text-amber-400"
            onClick={() => handleRetire(selectedModels)}
          >
            <Archive className="w-3.5 h-3.5 mr-1" />
            {t("modelConfig.retire.bulk", { count: String(selectedModels.length) })}
          </Button>
          <Button size="sm" variant="ghost" data-testid="bulk-clear" onClick={() => setSelectedKeys(new Set())}>
            {t("modelConfig.bulkClear")}
          </Button>
        </div>
      )}

      {/* Column headers */}
      <div className={cn(
        "grid items-center gap-2 px-3 py-1 text-sm text-theme-secondary font-medium",
        ROW_GRID_COLS
      )}
        data-testid="model-list-header"
        // The list below always reserves its scrollbar gutter; the header gives up the same
        // width, or on a classic-scrollbar OS every column drifts from its heading.
        style={{ paddingRight: `calc(0.75rem + ${scrollbarWidth}px)` }}
      >
        <Checkbox
          checked={allFilteredSelected}
          onCheckedChange={(v) => selectAllFiltered(v === true)}
          aria-label={t("modelConfig.selectAll")}
          data-testid="model-select-all"
        />
        <div>#</div>
        <div />
        {/* CE-ship chip column (cloud only) - the chip labels itself, no header text */}
        {!IS_CE && <div />}
        {/* Free-tier chip column (cloud only) - same, the chip labels itself */}
        {!IS_CE && <div />}
        <div>{t("modelConfig.columns.provider")}</div>
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
        onDragStart={(e) => setDraggingKey(String(e.active.id))}
        onDragCancel={() => setDraggingKey(null)}
        onDragEnd={(e) => {
          setDraggingKey(null);
          return handleDragEnd(e);
        }}
      >
        <SortableContext
          items={sortableIds}
          strategy={verticalListSortingStrategy}
        >
          {visibleModels.length > 0 && (
            <VirtualList
              listRef={setListApi}
              rowComponent={VirtualModelRow}
              rowCount={visibleModels.length}
              rowHeight={rowHeight}
              rowProps={{ rows: visibleModels, renderRow, draggingKey }}
              overscanCount={LIST_OVERSCAN}
              style={{ height: listHeight, scrollbarGutter: "stable" }}
              data-testid="model-list"
            />
          )}
        </SortableContext>
        {/* What follows the pointer. Without it the moving element is the row itself, whose
            virtual slot unmounts once auto-scroll carries it past the overscan, and the row
            vanished mid-drag. */}
        <DragOverlay>
          {draggedModel && (
            <div
              data-testid="model-drag-overlay"
              className="flex items-center gap-2 px-3 py-2 rounded-lg border border-theme bg-theme-primary shadow-lg"
            >
              <GripVertical className="w-3.5 h-3.5 text-theme-secondary" />
              <span className="text-sm font-medium text-theme-primary truncate">{draggedModel.name}</span>
              <ProviderBadge provider={draggedModel.provider} />
            </div>
          )}
        </DragOverlay>
      </DndContext>

      {visibleModels.length === 0 && (
        <div className="text-center py-8 text-sm text-theme-secondary">
          {t("modelConfig.noModels")}
        </div>
      )}

      <RetiredModelsPanel
        t={t}
        reloadToken={retiredReload}
        onRestored={handleRestored}
        addToast={addToast}
      />

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />

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
