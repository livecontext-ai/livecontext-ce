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
 *     held-back rows + any guard failures.
 *  2. Admin reviews; optionally ticks the override to push the held-back rows
 *     through as well; clicks Apply.
 *  3. Apply calls the apply endpoint (with overrideGuards when checked). On
 *     success, the parent panel refreshes the bundles list.
 *
 * A held-back row does NOT block Apply, and its override checkbox is therefore
 * driven by `flagged.length`, not by a guard failure. `count-floor` is the
 * opposite case and keeps its own checkbox: it DOES block, it is read out of
 * `guardFailures`, and without a control the dialog is a dead end. The backend stopped
 * emitting a price-sanity GuardFailure when it stopped cancelling the whole
 * refresh over one moved price: it now withholds that row and applies the rest,
 * so `guardFailures` means "nothing was applied" and reading price-sanity out
 * of it would leave this panel hiding the checkbox for a condition that no
 * longer exists, with no way left to accept a held-back price. It would NOT
 * disable Apply: the flag is never set, so the old disable term is inert, which
 * is also why the panel test records that it would have passed against the
 * pre-change component.
 */
export function CatalogSyncPanel({ onAfterApply }: { onAfterApply?: () => void }) {
  const t = useTranslations("aiProviders.catalogSync");

  const [loading, setLoading] = useState(false);
  const [applying, setApplying] = useState(false);
  const [open, setOpen] = useState(false);
  const [plan, setPlan] = useState<CatalogSyncResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [overridePriceSanity, setOverridePriceSanity] = useState(false);
  const [overrideCountFloor, setOverrideCountFloor] = useState(false);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const stats = plan?.plan.stats;
  const flagged = plan?.plan.flagged ?? [];
  const guardFailures = plan?.plan.guardFailures ?? [];
  // Rows the refresh will withhold unless the operator opts them in.
  const hasHeldBackRows = flagged.length > 0;
  // count-floor is the one guard that still blocks the whole apply, and
  // until now the dialog offered no way past it: the operator got a 412
  // banner and no control. That is reachable by design, not only by
  // accident - adding a variant suffix to the parser's drop list shrinks
  // the feed on purpose, and a deliberate shrink looks exactly like the
  // partial response this guard exists to reject. Measured on the live
  // feed, dropping ":batch" takes OpenRouter from 356 accepted rows to
  // 280 against a floor of 284, so the very first apply trips it.
  const hasCountFloorFailure = guardFailures.some((g) => g.guard === "count-floor");

  const handleDryRun = async () => {
    setLoading(true);
    setError(null);
    setSuccessMsg(null);
    setOverridePriceSanity(false);
    setOverrideCountFloor(false);
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
      // Gated on the control being VISIBLE, not just ticked. A tick can
      // outlive the checkbox that set it: an apply that fails inside the
      // merge answers 200 with applied:false and an EMPTY guardFailures, so
      // the count-floor box unmounts while its state stays true, and every
      // later Apply would keep sending an override nobody can see. The rule
      // is that the dialog never sends what it is not currently showing.
      const sendPriceSanity = overridePriceSanity && hasHeldBackRows;
      const sendCountFloor = overrideCountFloor && hasCountFloorFailure;
      const overrides = [
        ...(sendPriceSanity ? ["price-sanity"] : []),
        ...(sendCountFloor ? ["count-floor"] : []),
      ];
      const result = await modelConfigService.catalogSyncApply(overrides);
      setPlan(result);
      if (result.applied) {
        // Rows are only withheld when the operator did NOT opt them in; with
        // the override ticked the same list went through, so reporting it as
        // held back would describe the opposite of what just happened.
        const heldBack = sendPriceSanity ? 0 : (result.plan.flagged?.length ?? 0);
        const summary = t("applied", {
          inserted: result.inserted,
          updated: result.updatedCount,
          deprecated: result.deprecated,
        });
        // Said here because the dialog closes on success: without this line the
        // review queue exists only inside a modal the operator has just
        // dismissed, and nothing anywhere would say the refresh was partial.
        setSuccessMsg(
          heldBack > 0
            ? `${summary} ${t("appliedHeldBack", { count: heldBack })}`
            : summary
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

  // Sorted so the panel does not reshuffle between two runs that found the
  // same providers in a different map order.
  const discoveredEntries = Object.entries(plan?.plan.discovery?.discoveredByProvider ?? {}).sort(
    ([a], [b]) => a.localeCompare(b),
  );
  const discoverySkipped = plan?.plan.discovery?.skippedProviders ?? [];
  const discoveryNotAsked = plan?.plan.discovery?.notAskedProviders ?? [];

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

              {/* Vendor-endpoint discovery. These rows are already inside
                  "added" - what this block adds is the fact that they arrive
                  unpriced and stay disabled until an admin sets a rate, which
                  is not visible from the added count alone. */}
              {discoveredEntries.length > 0 && (
                <div className="rounded-lg bg-violet-50 dark:bg-violet-900/20 border border-violet-200 dark:border-violet-800 p-3">
                  <h4 className="text-sm font-medium text-violet-800 dark:text-violet-300 mb-1">
                    {t("discoveryTitle")}
                  </h4>
                  <p className="text-xs text-violet-800/80 dark:text-violet-300/80 mb-2">
                    {t("discoveryPricedFromOpenRouter")}
                  </p>
                  <ul className="space-y-1 text-sm text-violet-800 dark:text-violet-300">
                    {discoveredEntries.map(([provider, count]) => (
                      <li key={provider} className="flex gap-2">
                        <span className="font-mono text-xs bg-violet-200/60 dark:bg-violet-800/40 px-1.5 rounded">
                          {provider}
                        </span>
                        <span>{t("discoveryCount", { count })}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {discoverySkipped.length > 0 && (
                <p className="text-xs text-theme-secondary">
                  {t("discoverySkipped", { providers: discoverySkipped.join(", ") })}
                </p>
              )}

              {discoveryNotAsked.length > 0 && (
                <p className="text-xs text-theme-secondary">
                  {t("discoveryNotAsked", { providers: discoveryNotAsked.join(", ") })}
                </p>
              )}

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
                  {/*
                    Shown only while it is TRUE. The sentence promises that the
                    refresh is landing without these rows and that every other
                    row still lands. Three states falsify it, and all three are
                    reachable from this open dialog:
                      - the box below is ticked, so the rows are no longer left out;
                      - a blocking guard fired, so count-floor answers 412 and
                        writes nothing;
                      - the apply itself failed inside the merge, which answers
                        200 with an EMPTY guardFailures, so the two conditions
                        above both pass while nothing landed at all. `error` is
                        nulled when an apply starts, so a non-null one inside an
                        open dialog means exactly "this apply failed", and its
                        red banner sits in the card BEHIND the overlay where the
                        operator cannot see it.
                    A stale explanation sitting directly above the control that
                    invalidated it is worse than no explanation.
                  */}
                  {!overridePriceSanity && guardFailures.length === 0 && !error && (
                    <p className="px-3 py-2 text-xs text-theme-secondary border-b border-theme">
                      {t("flaggedRowsHint")}
                    </p>
                  )}
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

              {/*
                Not suppressed by a guard failure any more. A truncated feed can
                fire count-floor while every surviving row is unchanged, and the
                dialog then showed a checkbox above an Apply that can never
                enable, with no line saying why. "Nothing to apply" is exactly
                what the operator needs there.
              */}
              {plan.plan.added.length === 0 &&
                plan.plan.updated.length === 0 && (
                  <div className="rounded-lg border border-dashed border-theme p-6 text-center text-sm text-theme-secondary flex items-center justify-center gap-2">
                    <Info className="w-4 h-4" />
                    {t("noChanges")}
                  </div>
                )}

              {hasCountFloorFailure && (
                <label className="flex items-start gap-2 p-3 rounded-lg border border-orange-200 dark:border-orange-800 bg-orange-50/50 dark:bg-orange-900/10 cursor-pointer">
                  <Checkbox
                    checked={overrideCountFloor}
                    onCheckedChange={(v) => setOverrideCountFloor(v === true)}
                    className="mt-0.5"
                  />
                  <span className="text-sm text-theme-primary">
                    <span className="font-medium">{t("overrideCountFloor")}</span>
                    <span className="block text-xs text-theme-secondary mt-0.5">
                      {t("overrideCountFloorHint")}
                    </span>
                  </span>
                </label>
              )}

              {hasHeldBackRows && (
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

          {/*
            Repeated inside the dialog on purpose. The same message renders in
            the card behind the overlay, where a failed apply leaves the
            operator looking at an unchanged dialog and no explanation.
          */}
          {error && (
            <p className="text-sm text-red-600 dark:text-red-400" role="alert">
              {error}
            </p>
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
                  plan?.plan.updated.length === 0)
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
