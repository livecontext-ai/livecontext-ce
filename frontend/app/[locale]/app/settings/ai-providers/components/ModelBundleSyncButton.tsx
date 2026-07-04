"use client";

import React from "react";
import { Loader2, Package, RefreshCw, X } from "lucide-react";
import { Link } from "@/i18n/navigation";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { IS_CE } from "@/lib/edition";
import { modelConfigService } from "@/lib/api/model-config.service";
import { useCloudSyncGate } from "@/hooks/useCloudSyncGate";
import { useBundleSync } from "@/hooks/useBundleSync";

/**
 * Inline "update the model catalog bundle" control shown under the AI-Providers
 * model list (replaces the old "bundles have moved → open Bundles" pointer).
 *
 * On CE the button syncs the signed bundle from the cloud (gated: an unlinked
 * install is sent to Connect to Cloud first, since the sync is mandatory and
 * impossible without a link). The full bundle history/activation stays one click
 * away on the Cloud › Bundles tab. On cloud (which BUILDS bundles) only that
 * link is shown.
 *
 * The RUNNING state lives on the SERVER (in-memory, single-pod CE) and is read
 * back via {@link useBundleSync}, so leaving this page and returning while a sync
 * is still in flight RESUMES the spinner instead of losing it, and a Stop button
 * lets the operator abandon a slow sync.
 */
export function ModelBundleSyncButton() {
  const t = useTranslations("aiProviders.modelBundle");
  const { ensureCloudLinked } = useCloudSyncGate();
  const sync = useBundleSync({
    getStatus: () => modelConfigService.getSyncStatus(),
    syncNow: () => modelConfigService.syncNow(),
    syncCancel: () => modelConfigService.syncCancel(),
  });

  const handleUpdate = () => {
    // Sync requires an active cloud link → unlinked CE goes to Connect first.
    if (!ensureCloudLinked()) return;
    void sync.start();
  };

  // Outcome text derived from the SERVER status, so it persists across a page
  // navigation (not from a transient local success/error flag).
  const result: { ok: boolean; text: string } | null = sync.error
    ? { ok: false, text: sync.error }
    : sync.running || !sync.status?.lastFetchStatus
      ? null
      : sync.status.lastFetchStatus === "OK"
        ? { ok: true, text: t("synced") }
        : { ok: false, text: sync.status.lastFetchError || t("failed") };

  return (
    <div className="rounded-xl border border-theme bg-theme-secondary/50 p-4 flex items-center justify-between gap-3 flex-wrap">
      <div className="flex items-center gap-3 min-w-0">
        <div className="w-9 h-9 bg-theme-tertiary rounded-lg flex items-center justify-center flex-shrink-0">
          <Package className="w-4 h-4 text-theme-primary" />
        </div>
        <div className="min-w-0">
          <p className="text-sm font-medium text-theme-primary">{t("label")}</p>
          <p className="text-xs text-theme-secondary">{t("hint")}</p>
        </div>
      </div>
      <div className="flex items-center gap-3 flex-shrink-0">
        {result && (
          <span className={cn("text-xs", result.ok ? "text-green-600 dark:text-green-400" : "text-red-600 dark:text-red-400")}>
            {result.text}
          </span>
        )}
        {IS_CE && (
          sync.running ? (
            <div className="flex items-center gap-2">
              <span className="inline-flex items-center text-sm text-theme-secondary">
                <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" />
                {t("syncing")}
              </span>
              <Button size="sm" variant="ghost" onClick={() => void sync.stop()} className="h-8 px-2">
                <X className="w-3.5 h-3.5 mr-1" />
                {t("stop")}
              </Button>
            </div>
          ) : (
            <Button size="sm" onClick={handleUpdate} className="h-8 px-3">
              <RefreshCw className="w-3.5 h-3.5 mr-1.5" />
              {t("update")}
            </Button>
          )
        )}
        <Link
          href="/app/settings/cloud-account?tab=bundles"
          className="inline-flex items-center gap-1 text-xs text-[var(--accent-primary)] hover:underline"
        >
          <Package className="w-3.5 h-3.5" />
          {t("manage")}
        </Link>
      </div>
    </div>
  );
}
