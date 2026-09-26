"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import { ArchiveRestore, ChevronDown, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import LoadingSpinner from "@/components/LoadingSpinner";
import type { ToastData } from "@/components/Toast";
import {
  modelConfigService,
  type ModelRef,
  type RetiredModelEntry,
} from "@/lib/api/model-config.service";
import { formatUtcDate, formatUtcDateOrNull } from "@/lib/utils/dateFormatters";

interface RetiredModelsPanelProps {
  t: (key: string, values?: Record<string, string>) => string;
  /** Bumped by the parent after it retired models, so this list re-reads. */
  reloadToken: number;
  /** Called after a successful restore: the restored models are back in the main list. */
  onRestored: () => void | Promise<void>;
  addToast: (toast: Omit<ToastData, "id">) => void;
}

const keyOf = (m: { provider: string; modelId: string }) => `${m.provider}:${m.modelId}`;

/** The server's own reason when it gave one (a 400/409 carries it), else nothing. */
function errorMessage(e: unknown): string {
  return e instanceof Error && e.message ? e.message : "";
}

/**
 * The retired models (V533). They are filtered out of the admin model list and out of every
 * picker, so this is the only place they can be seen, and restored. A restored model comes
 * back DISABLED: the admin enables it in the main list like any other model.
 */
export default function RetiredModelsPanel({ t, reloadToken, onRestored, addToast }: RetiredModelsPanelProps) {
  /** null = not read yet. */
  const [items, setItems] = useState<RetiredModelEntry[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await modelConfigService.listRetiredModels();
      const list = Array.isArray(data) ? data : [];
      setItems(list);
      setLoadError(false);
      // Drop ticks on rows that are no longer retired, so "Restore selected (N)" never
      // counts a model that is not on screen.
      const present = new Set(list.map(keyOf));
      setSelected((prev) => new Set([...prev].filter((k) => present.has(k))));
    } catch {
      setLoadError(true);
    }
  }, []);

  // One read on mount, one more each time the parent retires something.
  useEffect(() => {
    void load();
  }, [load, reloadToken]);

  const selectedRefs = useMemo<ModelRef[]>(
    () =>
      (items ?? [])
        .filter((m) => selected.has(keyOf(m)))
        .map((m) => ({ provider: m.provider, modelId: m.modelId })),
    [items, selected],
  );

  const allSelected = (items?.length ?? 0) > 0 && items!.every((m) => selected.has(keyOf(m)));

  const restore = async (refs: ModelRef[]) => {
    if (refs.length === 0) return;
    setBusy(true);
    try {
      const res = await modelConfigService.restoreModels(refs);
      addToast({
        type: "success",
        title: t("modelConfig.retired.restoredTitle", { count: String(res?.restored ?? refs.length) }),
        message: t("modelConfig.retired.restoredMessage"),
      });
      setSelected(new Set());
      await load();
      await onRestored();
    } catch (e) {
      addToast({ type: "error", title: t("modelConfig.retired.restoreError"), message: errorMessage(e) });
    } finally {
      setBusy(false);
    }
  };

  const count = items?.length;
  const title =
    count === undefined
      ? t("modelConfig.retired.title")
      : t("modelConfig.retired.titleWithCount", { count: String(count) });

  return (
    <section className="rounded-lg border border-theme" data-testid="retired-models">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        data-testid="retired-models-toggle"
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium text-theme-primary hover:bg-theme-tertiary/50 rounded-lg"
      >
        {expanded ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
        <span>{title}</span>
        {busy && <LoadingSpinner size="xs" />}
      </button>

      {expanded && (
        <div className="space-y-2 border-t border-theme px-3 py-2">
          <p className="text-sm text-theme-secondary">{t("modelConfig.retired.hint")}</p>

          {loadError && (
            <p className="text-sm text-red-700 dark:text-red-400" role="alert" data-testid="retired-models-error">
              {t("modelConfig.retired.loadError")}
            </p>
          )}

          {items !== null && items.length === 0 && !loadError && (
            <p className="py-4 text-center text-sm text-theme-secondary" data-testid="retired-models-empty">
              {t("modelConfig.retired.empty")}
            </p>
          )}

          {items !== null && items.length > 0 && (
            <>
              <div className="flex items-center gap-2">
                <Checkbox
                  checked={allSelected}
                  onCheckedChange={(v) =>
                    setSelected(v === true ? new Set(items.map(keyOf)) : new Set())
                  }
                  aria-label={t("modelConfig.retired.selectAll")}
                  data-testid="retired-select-all"
                />
                <span className="text-sm text-theme-secondary">{t("modelConfig.retired.selectAll")}</span>
                {selectedRefs.length > 0 && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="ml-auto"
                    disabled={busy}
                    onClick={() => restore(selectedRefs)}
                    data-testid="retired-restore-selected"
                  >
                    <ArchiveRestore className="w-3.5 h-3.5 mr-1" />
                    {t("modelConfig.retired.restoreSelected", { count: String(selectedRefs.length) })}
                  </Button>
                )}
              </div>

              <ul className="space-y-1">
                {items.map((m) => {
                  const key = keyOf(m);
                  const released = formatUtcDateOrNull(m.releaseDate);
                  return (
                    <li
                      key={key}
                      className="flex items-center gap-2 rounded-lg border border-theme bg-theme-primary px-3 py-2"
                      data-testid={`retired-row-${m.provider}-${m.modelId}`}
                    >
                      <Checkbox
                        checked={selected.has(key)}
                        onCheckedChange={(v) =>
                          setSelected((prev) => {
                            const out = new Set(prev);
                            if (v === true) out.add(key);
                            else out.delete(key);
                            return out;
                          })
                        }
                        aria-label={`${t("modelConfig.retired.selectRow")} ${m.displayName || m.modelId}`}
                        data-testid={`retired-select-${m.provider}-${m.modelId}`}
                      />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium text-theme-primary" title={m.displayName || m.modelId}>
                          {m.displayName || m.modelId}
                        </p>
                        <p className="truncate text-xs font-mono text-theme-secondary" title={`${m.provider} / ${m.modelId}`}>
                          {`${m.provider} / ${m.modelId}`}
                        </p>
                      </div>
                      <div className="flex-shrink-0 text-right text-xs text-theme-secondary">
                        <p>{t("modelConfig.retired.retiredOn", { date: formatUtcDate(m.retiredAt) })}</p>
                        {released && <p>{t("modelConfig.releasedOn", { date: released })}</p>}
                      </div>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => restore([{ provider: m.provider, modelId: m.modelId }])}
                        data-testid={`retired-restore-${m.provider}-${m.modelId}`}
                      >
                        <ArchiveRestore className="w-3.5 h-3.5 mr-1" />
                        {t("modelConfig.retired.restore")}
                      </Button>
                    </li>
                  );
                })}
              </ul>
            </>
          )}
        </div>
      )}
    </section>
  );
}
