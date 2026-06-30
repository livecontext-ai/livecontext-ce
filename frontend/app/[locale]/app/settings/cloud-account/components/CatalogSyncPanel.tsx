"use client";

import React, { useState } from "react";
import { useTranslations } from "next-intl";
import {
  RefreshCw,
  Loader2,
  Download,
  AlertTriangle,
  CheckCircle2,
  Info,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import {
  modelConfigService,
  type CatalogSyncResult,
  type CatalogSyncFlaggedRow,
} from "@/lib/api/model-config.service";

/**
 * Cloud-admin panel that triggers the live catalog sync
 * (LiteLLM + OpenRouter → agent.model_config_overrides).
 *
 * Flow:
 *  1. Admin clicks "Refresh from providers" → dry-run → modal shows diff +
 *     flagged rows + any guard failures.
 *  2. Admin reviews; optionally ticks "Override price-sanity" if a flagged
 *     price change is legitimate; clicks Apply.
 *  3. Apply calls the apply endpoint (with overrideGuards when checked). On
 *     success, the parent panel refreshes the bundles list.
 */
export function CatalogSyncPanel({ onAfterApply }: { onAfterApply?: () => void }) {
  const t = useTranslations("aiProviders.catalogSync");

  const [loading, setLoading] = useState(false);
  const [applying, setApplying] = useState(false);
  const [open, setOpen] = useState(false);
  const [plan, setPlan] = useState<CatalogSyncResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [overridePriceSanity, setOverridePriceSanity] = useState(false);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const handleDryRun = async () => {
    setLoading(true);
    setError(null);
    setSuccessMsg(null);
    setOverridePriceSanity(false);
    try {
      const result = await modelConfigService.catalogSyncDryRun();
      setPlan(result);
      setOpen(true);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  const handleApply = async () => {
    setApplying(true);
    setError(null);
    try {
      const overrides = overridePriceSanity ? ["price-sanity"] : [];
      const result = await modelConfigService.catalogSyncApply(overrides);
      setPlan(result);
      if (result.applied) {
        setSuccessMsg(
          t("applied", {
            inserted: result.inserted,
            updated: result.updatedCount,
            deprecated: result.deprecated,
          })
        );
        setOpen(false);
        onAfterApply?.();
      } else {
        setError(t("applyRejected"));
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setApplying(false);
    }
  };

  const stats = plan?.plan.stats;
  const flagged = plan?.plan.flagged ?? [];
  const guardFailures = plan?.plan.guardFailures ?? [];
  const hasPriceSanityFailure = guardFailures.some((g) => g.guard === "price-sanity");

  return (
    <>
      <div className="rounded-xl border border-theme bg-theme-secondary/50 p-5">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-theme-tertiary rounded-lg flex items-center justify-center">
              <Download className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-theme-primary">{t("title")}</h3>
              <p className="text-xs text-theme-secondary">{t("subtitle")}</p>
            </div>
          </div>
          <Button size="sm" onClick={handleDryRun} disabled={loading} className="h-8 px-3">
            {loading ? (
              <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" />
            ) : (
              <RefreshCw className="w-3.5 h-3.5 mr-1.5" />
            )}
            {t("refresh")}
          </Button>
        </div>
        {error && (
          <div className="mt-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-3 text-sm text-red-800 dark:text-red-300">
            {error}
          </div>
        )}
        {successMsg && (
          <div className="mt-3 rounded-lg bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 p-3 text-sm text-green-800 dark:text-green-300 flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 shrink-0" />
            {successMsg}
          </div>
        )}
      </div>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-3xl max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t("modalTitle")}</DialogTitle>
            <DialogDescription>{t("modalSubtitle")}</DialogDescription>
          </DialogHeader>

          {plan && (
            <div className="space-y-4">
              {/* Feed stats */}
              <div className="grid grid-cols-2 gap-3">
                <div className="rounded-lg border border-theme p-3">
                  <div className="text-xs uppercase text-theme-secondary mb-1">LiteLLM</div>
                  <div className="text-2xl font-semibold text-theme-primary">
                    {stats?.liteLlmKept ?? 0}
                  </div>
                  <div className="text-xs text-theme-secondary">{t("modelsKept")}</div>
                </div>
                <div className="rounded-lg border border-theme p-3">
                  <div className="text-xs uppercase text-theme-secondary mb-1">OpenRouter</div>
                  <div className="text-2xl font-semibold text-theme-primary">
                    {stats?.openRouterKept ?? 0}
                  </div>
                  <div className="text-xs text-theme-secondary">{t("modelsKept")}</div>
                </div>
              </div>

              {/* Diff buckets */}
              <div className="grid grid-cols-3 gap-3">
                <div className="rounded-lg bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 p-3">
                  <div className="text-xs uppercase text-green-700 dark:text-green-400 mb-1">{t("added")}</div>
                  <div className="text-2xl font-semibold text-green-800 dark:text-green-300">
                    {plan.plan.added.length}
                  </div>
                </div>
                <div className="rounded-lg bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 p-3">
                  <div className="text-xs uppercase text-blue-700 dark:text-blue-400 mb-1">{t("updated")}</div>
                  <div className="text-2xl font-semibold text-blue-800 dark:text-blue-300">
                    {plan.plan.updated.length}
                  </div>
                </div>
                <div className="rounded-lg border border-theme p-3">
                  <div className="text-xs uppercase text-theme-secondary mb-1">{t("unchanged")}</div>
                  <div className="text-2xl font-semibold text-theme-primary">{plan.plan.unchanged}</div>
                </div>
              </div>

              {/* Guard failures */}
              {guardFailures.length > 0 && (
                <div className="rounded-lg bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 p-3">
                  <div className="flex items-center gap-2 mb-2">
                    <AlertTriangle className="w-4 h-4 text-orange-700 dark:text-orange-400" />
                    <h4 className="text-sm font-medium text-orange-800 dark:text-orange-300">
                      {t("guardFailures")}
                    </h4>
                  </div>
                  <ul className="space-y-1 text-sm text-orange-800 dark:text-orange-300">
                    {guardFailures.map((g, i) => (
                      <li key={i} className="flex gap-2">
                        <span className="font-mono text-xs bg-orange-200/60 dark:bg-orange-800/40 px-1.5 rounded">
                          {g.guard}
                        </span>
                        <span>{g.detail}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {/* Flagged rows */}
              {flagged.length > 0 && (
                <div className="rounded-lg border border-theme">
                  <div className="px-3 py-2 bg-theme-tertiary/60 text-xs uppercase text-theme-secondary flex items-center gap-2">
                    <AlertTriangle className="w-3.5 h-3.5" />
                    {t("flaggedRows", { count: flagged.length })}
                  </div>
                  <table className="w-full text-sm">
                    <thead className="text-xs uppercase text-theme-secondary">
                      <tr>
                        <th className="text-left px-3 py-2 font-medium">{t("colProvider")}</th>
                        <th className="text-left px-3 py-2 font-medium">{t("colModel")}</th>
                        <th className="text-left px-3 py-2 font-medium">{t("colReason")}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-theme">
                      {flagged.map((r: CatalogSyncFlaggedRow, i) => (
                        <tr key={i}>
                          <td className="px-3 py-2 font-mono text-xs text-theme-primary">{r.provider}</td>
                          <td className="px-3 py-2 font-mono text-xs text-theme-primary">{r.modelId}</td>
                          <td className="px-3 py-2 text-theme-secondary">{r.reason}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {plan.plan.added.length === 0 &&
                plan.plan.updated.length === 0 &&
                guardFailures.length === 0 && (
                  <div className="rounded-lg border border-dashed border-theme p-6 text-center text-sm text-theme-secondary flex items-center justify-center gap-2">
                    <Info className="w-4 h-4" />
                    {t("noChanges")}
                  </div>
                )}

              {hasPriceSanityFailure && (
                <label className="flex items-start gap-2 p-3 rounded-lg border border-orange-200 dark:border-orange-800 bg-orange-50/50 dark:bg-orange-900/10 cursor-pointer">
                  <Checkbox
                    checked={overridePriceSanity}
                    onCheckedChange={(v) => setOverridePriceSanity(v === true)}
                    className="mt-0.5"
                  />
                  <span className="text-sm text-theme-primary">
                    <span className="font-medium">{t("overridePriceSanity")}</span>
                    <span className="block text-xs text-theme-secondary mt-0.5">
                      {t("overridePriceSanityHint")}
                    </span>
                  </span>
                </label>
              )}
            </div>
          )}

          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)} disabled={applying}>
              {t("cancel")}
            </Button>
            <Button
              onClick={handleApply}
              disabled={
                applying ||
                (plan?.plan.added.length === 0 &&
                  plan?.plan.updated.length === 0) ||
                (hasPriceSanityFailure && !overridePriceSanity)
              }
            >
              {applying ? (
                <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" />
              ) : null}
              {t("apply")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
