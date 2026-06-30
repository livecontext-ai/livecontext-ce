"use client";

/**
 * Bridge Access Panel - admin-only gate for CLI bridge dispatch.
 *
 * Bridges run on a shared OS-level session (admin's Claude Pro / ChatGPT Plus
 * subscription). Without gating, any CE user can exhaust the rate limits and
 * break the bridge for everyone. Opt-in by default - every bridge ships
 * `disabled` (V118). The admin uses this panel to:
 *   - pick one of four access modes (disabled / admin_only / allowlist / all_users),
 *   - set per-user daily quota,
 *   - grant / revoke allowlist entries.
 *
 * The policy is enforced by {@code BridgeAccessGuard} inside shared-agent-lib:
 * every provider lookup through {@code LLMProviderFactory#getProviderForUser}
 * calls the guard, which fails CLOSED when auth-service is unreachable.
 */

import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Shield,
  ShieldAlert,
  ShieldCheck,
  UserPlus,
  Users,
  X,
  Save,
  CheckCircle2,
  Loader2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import LoadingSpinner from "@/components/LoadingSpinner";
import { bridgeAccessService } from "@/lib/api/orchestrator/bridge-access.service";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";
import type {
  BridgeAccessMode,
  BridgeAccessPolicy,
  BridgeAccessView,
  BridgeAccessAllowlistEntry,
} from "@/lib/api/orchestrator/types";
import { cn } from "@/lib/utils";

interface BridgeAccessPanelProps {
  /** Backend-side provider id: "claude-code" | "codex" | "gemini-cli" | "mistral-vibe". */
  bridgeProvider: string;
  t: (key: string, values?: Record<string, string>) => string;
}

const MODES: ReadonlyArray<{
  value: BridgeAccessMode;
  labelKey: string;
  hintKey: string;
  Icon: React.ComponentType<{ className?: string }>;
}> = [
  { value: "disabled", labelKey: "modeDisabled", hintKey: "modeDisabledHint", Icon: ShieldAlert },
  { value: "admin_only", labelKey: "modeAdminOnly", hintKey: "modeAdminOnlyHint", Icon: Shield },
  { value: "allowlist", labelKey: "modeAllowlist", hintKey: "modeAllowlistHint", Icon: ShieldCheck },
  { value: "all_users", labelKey: "modeAllUsers", hintKey: "modeAllUsersHint", Icon: Users },
];

function formatDate(iso: string | null | undefined): string {
  if (!iso) return "";
  try {
    return formatUtcDateTime(iso);
  } catch {
    return iso ?? "";
  }
}

export default function BridgeAccessPanel({ bridgeProvider, t }: BridgeAccessPanelProps) {
  const [view, setView] = useState<BridgeAccessView | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Policy draft (uncommitted edits).
  const [draftMode, setDraftMode] = useState<BridgeAccessMode>("disabled");
  const [draftQuota, setDraftQuota] = useState<string>("");
  const [saving, setSaving] = useState(false);
  const [saveFeedback, setSaveFeedback] = useState<"saved" | "error" | null>(null);
  const [saveErrorMsg, setSaveErrorMsg] = useState<string | null>(null);

  // Allowlist local state.
  const [newUserId, setNewUserId] = useState("");
  const [granting, setGranting] = useState(false);
  const [grantError, setGrantError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const data = await bridgeAccessService.getPolicyView(bridgeProvider);
      setView(data);
      if (data) {
        setDraftMode(data.policy.accessMode);
        setDraftQuota(
          data.policy.maxRequestsPerUserPerDay == null
            ? ""
            : String(data.policy.maxRequestsPerUserPerDay),
        );
      }
    } catch (err) {
      console.error("Failed to load bridge access policy:", err);
      setLoadError(t("bridge.access.loadError"));
    } finally {
      setLoading(false);
    }
  }, [bridgeProvider, t]);

  useEffect(() => {
    load();
  }, [load]);

  const policy: BridgeAccessPolicy | null = view?.policy ?? null;
  const isDirty = useMemo(() => {
    if (!policy) return false;
    const quotaNorm =
      policy.maxRequestsPerUserPerDay == null
        ? ""
        : String(policy.maxRequestsPerUserPerDay);
    return (
      draftMode !== policy.accessMode ||
      draftQuota.trim() !== quotaNorm
    );
  }, [draftMode, draftQuota, policy]);

  const handleSave = async () => {
    setSaving(true);
    setSaveFeedback(null);
    setSaveErrorMsg(null);
    try {
      const quotaParsed = draftQuota.trim() === "" ? null : Number(draftQuota);
      if (quotaParsed != null && (Number.isNaN(quotaParsed) || quotaParsed < 0)) {
        throw new Error("invalid_quota");
      }
      await bridgeAccessService.updatePolicy(bridgeProvider, {
        accessMode: draftMode,
        maxRequestsPerUserPerDay: quotaParsed,
      });
      setSaveFeedback("saved");
      setTimeout(() => setSaveFeedback(null), 2000);
      await load();
    } catch (err) {
      console.error("Failed to save bridge access policy:", err);
      setSaveFeedback("error");
      setSaveErrorMsg(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  };

  const handleGrant = async () => {
    const uid = newUserId.trim();
    if (!uid) return;
    setGranting(true);
    setGrantError(null);
    try {
      await bridgeAccessService.grantAccess(bridgeProvider, uid);
      setNewUserId("");
      await load();
    } catch (err) {
      console.error("Failed to grant bridge access:", err);
      setGrantError(t("bridge.access.grantError"));
    } finally {
      setGranting(false);
    }
  };

  const handleRevoke = async (userId: string) => {
    try {
      await bridgeAccessService.revokeAccess(bridgeProvider, userId);
      await load();
    } catch (err) {
      console.error("Failed to revoke bridge access:", err);
      setGrantError(t("bridge.access.revokeError"));
    }
  };

  if (loading) {
    return (
      <div className="rounded-xl border border-theme bg-theme-secondary/50 p-6">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  if (loadError || !view) {
    return (
      <div className="rounded-xl border border-theme bg-theme-secondary/50 p-4 text-sm text-red-600 dark:text-red-400">
        {loadError ?? t("bridge.access.loadError")}
      </div>
    );
  }

  const allowlistActive = draftMode === "allowlist";

  return (
    <div className="rounded-xl border border-theme bg-theme-secondary/50 p-6 space-y-6">
      {/* Header */}
      <div className="flex items-start gap-3">
        <div className="w-10 h-10 bg-theme-tertiary rounded-lg flex items-center justify-center flex-shrink-0">
          <Shield className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h3 className="text-sm font-semibold text-theme-primary">{t("bridge.access.title")}</h3>
          <p className="text-sm text-theme-secondary">{t("bridge.access.subtitle")}</p>
        </div>
      </div>

      {/* Access mode selector */}
      <div className="space-y-3">
        <label className="text-sm font-medium text-theme-primary">{t("bridge.access.mode")}</label>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          {MODES.map(({ value, labelKey, hintKey, Icon }) => {
            const selected = draftMode === value;
            return (
              <button
                key={value}
                type="button"
                onClick={() => setDraftMode(value)}
                className={cn(
                  "flex items-start gap-3 p-3 rounded-lg border transition-colors text-left",
                  selected
                    ? "border-[var(--accent-primary)] bg-[var(--accent-primary)]/5"
                    : "border-theme bg-theme-primary hover:bg-theme-tertiary/40",
                )}
              >
                <Icon
                  className={cn(
                    "w-4 h-4 mt-0.5 flex-shrink-0",
                    selected ? "text-[var(--accent-primary)]" : "text-theme-secondary",
                  )}
                />
                <div className="min-w-0">
                  <div className="text-sm font-medium text-theme-primary">
                    {t(`bridge.access.${labelKey}`)}
                  </div>
                  <div className="text-xs text-theme-secondary mt-0.5">
                    {t(`bridge.access.${hintKey}`)}
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      </div>

      {/* Quota */}
      <div className="space-y-1">
        <label className="text-sm font-medium text-theme-primary" htmlFor="bridge-access-quota">
          {t("bridge.access.dailyQuota")}
        </label>
        <input
          id="bridge-access-quota"
          type="number"
          inputMode="numeric"
          min={0}
          step={1}
          placeholder={t("bridge.access.unlimited")}
          value={draftQuota}
          onChange={(e) => setDraftQuota(e.target.value)}
          className="w-full rounded-lg border border-theme bg-theme-primary px-3 py-2 text-sm text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]/60"
        />
        <p className="text-xs text-theme-secondary">{t("bridge.access.dailyQuotaHint")}</p>
      </div>

      {/* Save bar */}
      <div className="flex items-center justify-end gap-2">
        {saveFeedback === "saved" && (
          <span className="inline-flex items-center gap-1.5 text-xs text-emerald-600 dark:text-emerald-400">
            <CheckCircle2 className="w-3.5 h-3.5" />
            {t("bridge.access.saved")}
          </span>
        )}
        {saveFeedback === "error" && (
          <span
            className="text-xs text-red-600 dark:text-red-400 truncate max-w-[22rem]"
            title={saveErrorMsg ?? undefined}
          >
            {t("bridge.access.saveError")}
          </span>
        )}
        <Button
          onClick={handleSave}
          disabled={!isDirty || saving}
          size="sm"
          className="h-8 px-3"
        >
          {saving ? <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" /> : <Save className="w-3.5 h-3.5 mr-1.5" />}
          {saving ? t("bridge.access.saving") : t("bridge.access.save")}
        </Button>
      </div>

      {/* Allowlist */}
      <div className="space-y-3 pt-2 border-t border-theme">
        <div className="flex items-center justify-between">
          <h4 className="text-sm font-semibold text-theme-primary">{t("bridge.access.allowlistTitle")}</h4>
          <span className="text-xs text-theme-secondary">
            {view.allowlist.length} {view.allowlist.length === 1 ? "user" : "users"}
          </span>
        </div>

        {!allowlistActive && (
          <p className="text-xs text-theme-secondary italic">
            {t("bridge.access.allowlistHintDisabled")}
          </p>
        )}

        {/* Add new */}
        <div className={cn("flex items-center gap-2", !allowlistActive && "opacity-60")}>
          <input
            type="text"
            value={newUserId}
            onChange={(e) => setNewUserId(e.target.value)}
            placeholder={t("bridge.access.addUserPlaceholder")}
            disabled={!allowlistActive || granting}
            onKeyDown={(e) => {
              if (e.key === "Enter" && allowlistActive) handleGrant();
            }}
            className="flex-1 rounded-lg border border-theme bg-theme-primary px-3 py-2 text-sm text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]/60 disabled:cursor-not-allowed"
          />
          <Button
            onClick={handleGrant}
            disabled={!allowlistActive || granting || !newUserId.trim()}
            size="sm"
            className="h-8 px-3"
          >
            {granting ? (
              <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" />
            ) : (
              <UserPlus className="w-3.5 h-3.5 mr-1.5" />
            )}
            {t("bridge.access.addUser")}
          </Button>
        </div>

        {grantError && (
          <p className="text-xs text-red-600 dark:text-red-400">{grantError}</p>
        )}

        {/* List */}
        {view.allowlist.length === 0 ? (
          <p className="text-sm text-theme-secondary italic">{t("bridge.access.allowlistEmpty")}</p>
        ) : (
          <ul className="divide-y divide-theme rounded-lg border border-theme overflow-hidden">
            {view.allowlist.map((entry: BridgeAccessAllowlistEntry) => (
              <li
                key={entry.userId}
                className="flex items-center justify-between px-3 py-2 bg-theme-primary"
              >
                <div className="min-w-0">
                  <div className="text-sm font-medium text-theme-primary truncate">{entry.userId}</div>
                  <div className="text-xs text-theme-secondary truncate">
                    {t("bridge.access.grantedAt")} {formatDate(entry.grantedAt)} {t("bridge.access.grantedBy")}{" "}
                    {entry.grantedBy}
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => handleRevoke(entry.userId)}
                  className="flex-shrink-0 inline-flex items-center gap-1 px-2 py-1 rounded-md text-xs text-red-600 hover:bg-red-500/10 dark:text-red-400 transition-colors"
                  title={t("bridge.access.revoke")}
                >
                  <X className="w-3 h-3" />
                  {t("bridge.access.revoke")}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Usage */}
      <div className="space-y-2 pt-2 border-t border-theme">
        <h4 className="text-sm font-semibold text-theme-primary">{t("bridge.access.usageTitle")}</h4>
        {view.recentUsage.length === 0 ? (
          <p className="text-sm text-theme-secondary italic">{t("bridge.access.usageEmpty")}</p>
        ) : (
          <ul className="space-y-1">
            {view.recentUsage.map((u) => (
              <li
                key={u.userId}
                className="flex items-center justify-between text-sm text-theme-secondary"
              >
                <span className="truncate font-mono text-xs">{u.userId}</span>
                <span className="flex-shrink-0 text-xs">
                  {u.requestsToday} {t("bridge.access.usageRequests")} · {t("bridge.access.usageLastAt")}{" "}
                  {formatDate(u.lastRequestAt)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
