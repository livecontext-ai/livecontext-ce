"use client";

import React, { useState } from "react";
import { Loader2, Package, RefreshCw } from "lucide-react";
import { Link } from "@/i18n/navigation";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { IS_CE } from "@/lib/edition";
import { modelConfigService } from "@/lib/api/model-config.service";
import { useCloudSyncGate } from "@/hooks/useCloudSyncGate";

/**
 * Inline "update the model catalog bundle" control shown under the AI-Providers
 * model list (replaces the old "bundles have moved → open Bundles" pointer).
 *
 * On CE the button syncs the signed bundle from the cloud (gated: an unlinked
 * install is sent to Connect to Cloud first, since the sync is mandatory and
 * impossible without a link). The full bundle history/activation stays one click
 * away on the Cloud › Bundles tab. On cloud (which BUILDS bundles) only that
 * link is shown.
 */
export function ModelBundleSyncButton() {
  const t = useTranslations("aiProviders.modelBundle");
  const { ensureCloudLinked } = useCloudSyncGate();
  const [syncing, setSyncing] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  const handleUpdate = async () => {
    // Sync requires an active cloud link → unlinked CE goes to Connect first.
    if (!ensureCloudLinked()) return;
    setSyncing(true);
    setResult(null);
    try {
      await modelConfigService.syncNow();
      setResult({ ok: true, text: t("synced") });
    } catch (e: unknown) {
      setResult({ ok: false, text: e instanceof Error ? e.message : t("failed") });
    } finally {
      setSyncing(false);
    }
  };

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
          <Button size="sm" onClick={handleUpdate} disabled={syncing} className="h-8 px-3">
            {syncing ? <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5 mr-1.5" />}
            {t("update")}
          </Button>
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
