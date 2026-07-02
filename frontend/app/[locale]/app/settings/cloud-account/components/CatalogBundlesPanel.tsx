"use client";

import React, { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import {
  CheckCircle2,
  AlertTriangle,
  RefreshCw,
  PackagePlus,
  CircleCheck,
  Clock,
  Loader2,
  Cloud,
  CloudOff,
  Shield,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  modelConfigService,
  type CatalogBundleSummary,
  type CatalogBundleListResponse,
  type CatalogBundleSyncStatus,
} from "@/lib/api/model-config.service";
import { CatalogSyncPanel } from "./CatalogSyncPanel";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";
import { IS_CE } from "@/lib/edition";
import { useCloudSyncGate } from "@/hooks/useCloudSyncGate";

// CE detection mirrors `IS_CE` in the parent page. This is the SINGLE source
// of truth for "is this instance pulling bundles vs publishing them". We do
// NOT key off the sync-status response's `schedulerEnabled` flag because that
// endpoint can fail (network blip, 500) and would silently flip the UI into
// cloud-publisher mode on a CE instance - letting an admin click "Build
// bundle" against a box with no keypair.
function formatInstant(iso: string | null): string {
  if (!iso) return "-";
  try {
    return formatUtcDateTime(iso, { withSeconds: true });
  } catch {
    return iso;
  }
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

// "Setup / not synced yet" states are informational (blue), NOT errors (red): a fresh or
// not-yet-bootstrapped CE legitimately sits here before its first sync - it must not look alarming.
function isInformationalStatus(status: string | null | undefined): boolean {
  return status === "NO_ACTIVE" || status === "NOT_LINKED" || status === "TRUST_UNCONFIGURED";
}

function StatusBadge({
  status,
  neverLabel,
  translateCode,
}: {
  status: string | null;
  neverLabel: string;
  translateCode: (code: string) => string;
}) {
  if (!status) {
    return (
      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-theme-tertiary text-theme-secondary">
        <Clock className="w-3 h-3" />
        {neverLabel}
      </span>
    );
  }
  const ok = status === "OK";
  const informational = isInformationalStatus(status);
  const cls = ok
    ? "bg-green-500/15 text-green-700 dark:text-green-400"
    : informational
      ? "bg-blue-500/15 text-blue-700 dark:text-blue-400"
      : "bg-red-500/15 text-red-700 dark:text-red-400";
  const Icon = ok ? CheckCircle2 : informational ? Cloud : AlertTriangle;
  return (
    <span className={cn("inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium", cls)}>
      <Icon className="w-3 h-3" />
      {translateCode(status)}
    </span>
  );
}

export default function CatalogBundlesPanel() {
  const tb = useTranslations("aiProviders.bundles");
  const tCode = useTranslations("aiProviders.bundles.statusCode");

  // Translate a backend status code (OK, SIGNATURE_INVALID, NETWORK_ERROR, …)
  // - falling back to the raw code if a future backend adds one we haven't
  // translated yet. Showing a raw code is better than showing a key path.
  const translateStatusCode = useCallback(
    (code: string): string => {
      try {
        return tCode(code);
      } catch {
        return code;
      }
    },
    [tCode],
  );

  const [syncStatus, setSyncStatus] = useState<CatalogBundleSyncStatus | null>(null);
  const [list, setList] = useState<CatalogBundleListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [syncingNow, setSyncingNow] = useState(false);
  const [building, setBuilding] = useState(false);
  const [activatingId, setActivatingId] = useState<number | null>(null);
  const { ensureCloudLinked } = useCloudSyncGate();

  const refresh = useCallback(async () => {
    setError(null);
    try {
      // Sync status only matters on CE; on cloud the scheduler bean doesn't
      // exist, so the endpoint returns schedulerEnabled=false and empty
      // fields. We still fetch it to populate the CE panel but skip the
      // UI-flipping contract - IS_CE drives that.
      const [status, bundles] = await Promise.all([
        IS_CE ? modelConfigService.getSyncStatus().catch(() => null) : Promise.resolve(null),
        modelConfigService.listBundles().catch(() => null),
      ]);
      setSyncStatus(status);
      setList(bundles);
      if (!bundles) {
        setError(tb("errorLoad"));
      }
    } finally {
      setLoading(false);
    }
  }, [tb]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const handleSyncNow = async () => {
    // Sync needs an active cloud link; unlinked CE → go Connect first.
    if (!ensureCloudLinked()) return;
    setSyncingNow(true);
    setError(null);
    try {
      const fresh = await modelConfigService.syncNow();
      setSyncStatus(fresh);
      const bundles = await modelConfigService.listBundles().catch(() => null);
      if (bundles) setList(bundles);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      setError(msg);
    } finally {
      setSyncingNow(false);
    }
  };

  const handleBuild = async () => {
    setBuilding(true);
    setError(null);
    try {
      await modelConfigService.buildBundle();
      await refresh();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      setError(msg);
    } finally {
      setBuilding(false);
    }
  };

  const handleActivate = async (id: number) => {
    setActivatingId(id);
    setError(null);
    try {
      await modelConfigService.activateBundle(id);
      await refresh();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      setError(msg);
    } finally {
      setActivatingId(null);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12 text-theme-secondary">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        <span className="text-sm">{tb("loading")}</span>
      </div>
    );
  }

  const bundles = list?.bundles ?? [];

  return (
    <div className="space-y-6">
      {error && (
        <div className="rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-4">
          <p className="text-sm text-red-800 dark:text-red-300">{error}</p>
        </div>
      )}

      {/* CE: sync-status panel */}
      {IS_CE && (
        <div className="rounded-xl border border-theme bg-theme-secondary/50 p-5">
          <div className="flex items-start justify-between gap-4 mb-4">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-theme-tertiary rounded-lg flex items-center justify-center">
                <Cloud className="w-5 h-5 text-theme-primary" />
              </div>
              <div>
                <h3 className="text-sm font-semibold text-theme-primary">{tb("syncStatusTitle")}</h3>
                <p className="text-xs text-theme-secondary">{tb("syncStatusSubtitle")}</p>
              </div>
            </div>
            <Button
              size="sm"
              onClick={handleSyncNow}
              disabled={syncingNow}
              className="h-8 px-3"
            >
              {syncingNow ? (
                <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" />
              ) : (
                <RefreshCw className="w-3.5 h-3.5 mr-1.5" />
              )}
              {tb("syncNow")}
            </Button>
          </div>

          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 text-sm">
            <div className="flex items-center justify-between">
              <dt className="text-theme-secondary">{tb("lastFetchStatus")}</dt>
              <dd>
                <StatusBadge
                  status={syncStatus?.lastFetchStatus ?? null}
                  neverLabel={tb("never")}
                  translateCode={translateStatusCode}
                />
              </dd>
            </div>
            <div className="flex items-center justify-between">
              <dt className="text-theme-secondary">{tb("lastFetchedAt")}</dt>
              <dd className="text-theme-primary">{formatInstant(syncStatus?.lastFetchAt ?? null)}</dd>
            </div>
            <div className="flex items-center justify-between">
              <dt className="text-theme-secondary">{tb("lastAppliedVersion")}</dt>
              <dd className="text-theme-primary font-mono">
                {syncStatus?.lastAppliedVersion ?? "-"}
              </dd>
            </div>
            <div className="flex items-center justify-between">
              <dt className="text-theme-secondary">{tb("lastAppliedAt")}</dt>
              <dd className="text-theme-primary">{formatInstant(syncStatus?.lastAppliedAt ?? null)}</dd>
            </div>
            <div className="flex items-center justify-between">
              <dt className="text-theme-secondary">{tb("consecutiveFailures")}</dt>
              <dd className={cn(
                "font-mono",
                (syncStatus?.consecutiveFailures ?? 0) === 0
                  ? "text-theme-primary"
                  : "text-red-600 dark:text-red-400",
              )}>
                {syncStatus?.consecutiveFailures ?? 0}
              </dd>
            </div>
          </dl>

          {syncStatus?.lastFetchError && (
            isInformationalStatus(syncStatus?.lastFetchStatus) ? (
              // Setup states (not linked / nothing published yet) are NORMAL on a
              // fresh install: explain instead of alarming - the red error box is
              // for real failures only.
              <div className="mt-4 rounded-lg bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 p-3">
                <div className="flex items-start gap-2">
                  <Cloud className="w-4 h-4 text-blue-600 dark:text-blue-400 flex-shrink-0 mt-0.5" />
                  <p className="text-xs text-blue-800 dark:text-blue-300 break-words">
                    {syncStatus.lastFetchStatus === "NOT_LINKED" ? tb("notLinkedHint") : syncStatus.lastFetchError}
                  </p>
                </div>
              </div>
            ) : (
              <div className="mt-4 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-3">
                <div className="flex items-start gap-2">
                  <AlertTriangle className="w-4 h-4 text-red-600 dark:text-red-400 flex-shrink-0 mt-0.5" />
                  <p className="text-xs text-red-800 dark:text-red-300 break-all font-mono">
                    {syncStatus.lastFetchError}
                  </p>
                </div>
              </div>
            )
          )}
        </div>
      )}

      {/* Cloud: build a new bundle */}
      {!IS_CE && (
        <>
          {/* Step 1: sync catalog rows from live provider feeds */}
          <CatalogSyncPanel onAfterApply={refresh} />

          {/* Step 2: build + activate a signed bundle for CE distribution */}
          <div className="rounded-xl border border-theme bg-theme-secondary/50 p-5">
            <div className="flex items-start justify-between gap-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 bg-theme-tertiary rounded-lg flex items-center justify-center">
                  <CloudOff className="w-5 h-5 text-theme-primary" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold text-theme-primary">{tb("publishTitle")}</h3>
                  <p className="text-xs text-theme-secondary">{tb("publishSubtitle")}</p>
                </div>
              </div>
              <Button
                size="sm"
                onClick={handleBuild}
                disabled={building}
                className="h-8 px-3"
              >
                {building ? (
                  <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" />
                ) : (
                  <PackagePlus className="w-3.5 h-3.5 mr-1.5" />
                )}
                {tb("buildBundle")}
              </Button>
            </div>
            {list?.signingKeyId && (
              <div className="mt-4 pt-4 border-t border-theme flex items-center gap-2 text-xs text-theme-secondary">
                <Shield className="w-3.5 h-3.5" />
                {tb("signingKey")}: <span className="font-mono text-theme-primary">{list.signingKeyId}</span>
              </div>
            )}
          </div>
        </>
      )}

      {/* Bundle history - shown on both CE and cloud */}
      <div>
        <h3 className="text-sm font-semibold text-theme-primary mb-3">
          {IS_CE ? tb("historyCeTitle") : tb("historyCloudTitle")}
        </h3>
        {bundles.length === 0 ? (
          <div className="rounded-xl border border-dashed border-theme p-8 text-center text-sm text-theme-secondary">
            {tb("historyEmpty")}
          </div>
        ) : (
          <div className="rounded-xl border border-theme bg-theme-secondary/50 overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-theme-tertiary/60 text-xs uppercase text-theme-secondary">
                <tr>
                  <th className="text-left px-4 py-2.5 font-medium">{tb("colVersion")}</th>
                  <th className="text-left px-4 py-2.5 font-medium">{tb("colModels")}</th>
                  <th className="text-left px-4 py-2.5 font-medium">{tb("colSize")}</th>
                  <th className="text-left px-4 py-2.5 font-medium">{tb("colImported")}</th>
                  <th className="text-left px-4 py-2.5 font-medium">{tb("colStatus")}</th>
                  {!IS_CE && <th className="text-right px-4 py-2.5 font-medium">{tb("colActions")}</th>}
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {bundles.map((b: CatalogBundleSummary) => (
                  <tr key={b.id} className={cn(b.isActive && "bg-green-500/5")}>
                    <td className="px-4 py-2.5 font-mono text-theme-primary">{b.version}</td>
                    <td className="px-4 py-2.5 text-theme-primary">{b.modelCount}</td>
                    <td className="px-4 py-2.5 text-theme-secondary">{formatBytes(b.rawBytesSize)}</td>
                    <td className="px-4 py-2.5 text-theme-secondary">{formatInstant(b.importedAt)}</td>
                    <td className="px-4 py-2.5">
                      {b.isActive ? (
                        <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-green-500/15 text-green-700 dark:text-green-400">
                          <CircleCheck className="w-3 h-3" />
                          {tb("statusActive")}
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-theme-tertiary text-theme-secondary">
                          {tb("statusInactive")}
                        </span>
                      )}
                    </td>
                    {!IS_CE && (
                      <td className="px-4 py-2.5 text-right">
                        {!b.isActive && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => handleActivate(b.id)}
                            disabled={activatingId === b.id}
                            className="h-7 px-2.5 text-xs"
                          >
                            {activatingId === b.id ? (
                              <Loader2 className="w-3 h-3 animate-spin" />
                            ) : (
                              tb("activate")
                            )}
                          </Button>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
