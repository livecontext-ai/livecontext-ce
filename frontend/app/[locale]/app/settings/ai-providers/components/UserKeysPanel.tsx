"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Eye, EyeOff, Save, Trash2, ExternalLink, Check, KeyRound, AlertCircle, ArrowUpRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import LoadingSpinner from "@/components/LoadingSpinner";
import { cn } from "@/lib/utils";
import { credentialService } from "@/lib/api/orchestrator/credential.service";
import { usePlanFeatureGate } from "@/hooks/usePlanFeatureGate";
import { OWN_LLM_KEY_FEATURE } from "@/lib/billing/ownKeyFeature";
import { clearModelsCache } from "@/hooks/useModels";
import { track } from "@/lib/analytics/analytics";
import OwnKeyFeeInfo from "./OwnKeyFeeInfo";
import type { Credential, LlmProviderDefinition } from "@/lib/api/orchestrator/types";
import { ServiceLogo } from '@/components/ui/service-logo';

/** The credential field the resolver reads: `no_proxy` = my key serves the call, `proxy` = the platform key. */
type KeyMode = "no_proxy" | "proxy";

type Translate = (key: string, values?: Record<string, string>) => string;

interface UserKeysPanelProps {
  definitions: LlmProviderDefinition[];
  t: Translate;
  /** The pricing page, for the upgrade prompt. */
  pricingHref: string;
}

/**
 * The user's OWN provider keys: one row per provider, the switch between "my key" and the
 * LiveContext key, and what each choice bills. Every cloud user sees this panel; below the
 * required plan the rows are read-only with an upgrade prompt.
 *
 * <p>Data model: a saved key is an `llm_<provider>` credential whose `credential_data.mode`
 * decides the route (`no_proxy` = mine, `proxy` = platform). The key itself never comes back
 * from the API; only whether one is saved and which mode it is in. The plan lock comes from
 * the shared {@link usePlanFeatureGate} hook (cached, fails open like the backend gate).
 */
export default function UserKeysPanel({ definitions, t, pricingHref }: UserKeysPanelProps) {
  const [credentials, setCredentials] = useState<Credential[]>([]);
  // Lower-cased provider names this install exposes models for. Null until the answer is in,
  // which is NOT the same as the empty list: an empty list is a real "nothing to offer".
  const [offering, setOffering] = useState<string[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const gate = usePlanFeatureGate();
  const lock = gate.lockFor([OWN_LLM_KEY_FEATURE]);
  // Read through a ref so a translator identity change never re-runs the load.
  const tRef = useRef(t);
  tRef.current = t;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // Every credential of the workspace, not a page of them: a key saved among a hundred
      // other credentials must still be found, and the resolver reads the same set.
      const all = await credentialService.getAllCredentials();
      setCredentials(all.filter((c) => c.integration?.startsWith("llm_")));
      // Which providers are worth a key at all. Fetched here rather than derived from the
      // model catalogue because that one hides every provider the caller has no key for,
      // which is precisely the set this panel exists to offer.
      setOffering(await credentialService.getProvidersOfferingModels());
    } catch (err) {
      // A provider list we could not read must not fall back to the hardcoded one: naming a
      // provider that serves nothing is the defect this call was added to fix. Empty is the
      // safe answer, and the saved-key union below still keeps existing keys reachable.
      setOffering([]);
      setError(err instanceof Error ? err.message : tRef.current("yourKeys.errors.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const byIntegration = useMemo(() => {
    const map = new Map<string, Credential>();
    for (const c of credentials) {
      // The DEFAULT credential is the one the resolver reads; fall back to any saved one.
      const existing = map.get(c.integration);
      if (!existing || (c.is_default && !existing.is_default)) map.set(c.integration, c);
    }
    return map;
  }, [credentials]);

  /**
   * The providers this user may be shown. A provider whose models an admin has all switched
   * off is not named at all: the user must not learn it exists here when no picker will ever
   * offer it.
   *
   * The one exception is a provider they ALREADY hold a key for. Hiding that would strand the
   * key: invisible, unusable and impossible to delete. They plainly know it exists.
   */
  const visibleDefinitions = useMemo(() => {
    if (offering === null) return [];
    const offered = new Set(offering.map((p) => p.toLowerCase()));
    return definitions.filter(
      (def) =>
        offered.has(def.providerName.toLowerCase()) || byIntegration.has(def.integrationName),
    );
  }, [definitions, offering, byIntegration]);

  if (loading || gate.isLoading) {
    return (
      <div className="flex justify-center py-10">
        <LoadingSpinner size="md" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-theme bg-theme-secondary/30 p-4 text-sm text-theme-secondary">
        <div className="mb-1 flex items-center gap-1.5">
          <p className="font-medium text-theme-primary">{t("yourKeys.title")}</p>
          {/* The offer says a flat fee is charged per turn; this is what it is, per price
              band. Beside the promise rather than under it, so the sentence reads as one
              thing with a figure behind it. */}
          <OwnKeyFeeInfo />
        </div>
        <p>{t("yourKeys.intro")}</p>
      </div>

      {error && (
        <div className="flex items-center gap-2 rounded-xl border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-600 dark:text-red-400" role="alert">
          <AlertCircle className="h-3.5 w-3.5 flex-shrink-0" aria-hidden />
          {error}
        </div>
      )}

      {visibleDefinitions.length === 0 ? (
        <p className="rounded-xl border border-theme bg-theme-secondary/30 p-4 text-sm text-theme-secondary">
          {t("yourKeys.noProviders")}
        </p>
      ) : (
        // Two columns only once there is room for two. This page renders inside the settings
        // column, which at the `md` breakpoint is about 500px wide because the nav takes 192 of
        // them - so `md:grid-cols-2` gave each card ~240px to hold a logo, a provider name, a
        // route chip, a toggle, a key field and two buttons, and every one of them was crushed.
        // `xl` is the first width where a card gets its ~430px.
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          {visibleDefinitions.map((def) => (
            <UserKeyRow
              key={def.integrationName}
              definition={def}
              credential={byIntegration.get(def.integrationName)}
              locked={lock.locked}
              t={t}
              onChanged={load}
              onError={setError}
            />
          ))}
        </div>
      )}

      {/* Upgrade card below the required plan, same look as the Organization upsells
          (Members / Workspaces) so every plan prompt in Settings reads as one family. */}
      {lock.locked && lock.requiredPlan && (
        <section className="bg-theme-secondary rounded-xl p-6 border border-theme">
          <div className="flex items-start gap-4">
            <div className="w-10 h-10 bg-black dark:bg-white rounded-xl flex items-center justify-center flex-shrink-0">
              <KeyRound className="h-5 w-5 text-white dark:text-black" aria-hidden />
            </div>
            <div className="flex-1">
              <h3 className="text-lg font-semibold text-theme-primary mb-1">
                {t("yourKeys.requiresPlan", { plan: lock.requiredPlan })}
              </h3>
              <p className="text-sm text-theme-secondary mb-4">
                {t("yourKeys.requiresPlanDescription", { plan: lock.requiredPlan })}
              </p>
              <Button asChild size="sm" className="h-8 px-4">
                <Link
                  href={pricingHref}
                  onClick={() => track("byok_upgrade_clicked", { required_plan: lock.requiredPlan?.toLowerCase() })}
                >
                  {t("yourKeys.upgrade", { plan: lock.requiredPlan })}
                  <ArrowUpRight className="h-3.5 w-3.5 ml-1.5" aria-hidden />
                </Link>
              </Button>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}

interface UserKeyRowProps {
  definition: LlmProviderDefinition;
  credential: Credential | undefined;
  locked: boolean;
  t: Translate;
  onChanged: () => Promise<void>;
  onError: (message: string | null) => void;
}

function modeOf(credential: Credential | undefined): KeyMode | null {
  if (!credential) return null;
  const raw = credential.credential_data?.mode;
  return raw === "proxy" ? "proxy" : "no_proxy";
}

/**
 * Ask the provider whether the key is accepted. A rejection blocks the save; a provider that
 * could not be asked (agent-service down, an unknown provider on this deployment, or the
 * per-user budget of checks exhausted: a 429) never does: the key is then saved unverified,
 * like the backend contract says. Do not turn that catch into a hard block: the budget
 * protects the vendor from a key-testing script, not the user from saving their own key.
 */
async function checkKey(providerName: string, apiKey: string): Promise<{ valid: boolean; verified: boolean; error?: string }> {
  try {
    return await credentialService.validateLlmKey(providerName, apiKey);
  } catch {
    return { valid: true, verified: false };
  }
}

function UserKeyRow({ definition, credential, locked, t, onChanged, onError }: UserKeyRowProps) {
  const [apiKey, setApiKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState<"save" | "delete" | "mode" | null>(null);
  const [saved, setSaved] = useState(false);

  const mode = modeOf(credential);
  const hasKey = credential !== undefined;
  // What actually serves this user's next execution on this provider.
  const runsOnMine = hasKey && mode === "no_proxy" && !locked;

  const handleSave = async () => {
    const trimmed = apiKey.trim();
    if (!trimmed) return;
    setBusy("save");
    onError(null);
    try {
      // A wrong, exhausted or region-locked key fails HERE, in Settings, not three
      // nodes deep in a run at 3am.
      const check = await checkKey(definition.providerName, trimmed);
      if (!check.valid) {
        onError(t("yourKeys.errors.invalidKey", { provider: definition.displayName, reason: check.error ?? "" }));
        return;
      }
      // Replacing is create THEN delete, never the reverse: a failure between the two leaves
      // the user with a key (two rows for a moment), not without one. The new row is made
      // the default explicitly (the resolver reads the default), and keeps the route the
      // old one had, so replacing a key never silently switches whose key runs.
      const created = await credentialService.createCredential({
        name: definition.displayName,
        integration: definition.integrationName,
        type: "API Key",
        environment: "Production",
        status: "active",
        credential_data: { api_key: trimmed, mode: mode ?? "no_proxy" },
        scopes: [],
        tags: [],
      });
      if (credential) {
        await credentialService.deleteCredential(credential.id);
      }
      if (created?.id != null && !created.is_default) {
        await credentialService.setDefaultCredential(created.id);
      }
      track("own_llm_key_saved", { provider_name: definition.providerName, replaced: credential !== undefined, verified: check.verified });
      await credentialService.invalidateMyLlmCacheIfLlmKey(definition.integrationName);
      clearModelsCache();
      setApiKey("");
      setShowKey(false);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
      await onChanged();
    } catch (err) {
      onError(err instanceof Error ? err.message : t("yourKeys.errors.saveFailed"));
    } finally {
      setBusy(null);
    }
  };

  const handleDelete = async () => {
    if (!credential) return;
    setBusy("delete");
    onError(null);
    try {
      await credentialService.deleteCredential(credential.id);
      track("own_llm_key_deleted", { provider_name: definition.providerName });
      await credentialService.invalidateMyLlmCacheIfLlmKey(definition.integrationName);
      clearModelsCache();
      await onChanged();
    } catch (err) {
      onError(err instanceof Error ? err.message : t("yourKeys.errors.deleteFailed"));
    } finally {
      setBusy(null);
    }
  };

  const handleToggle = async () => {
    if (!credential || !mode) return;
    const next: KeyMode = mode === "no_proxy" ? "proxy" : "no_proxy";
    setBusy("mode");
    onError(null);
    try {
      await credentialService.setLlmKeyMode(credential.id, next);
      track("own_llm_key_mode_changed", { provider_name: definition.providerName, mode: next });
      await credentialService.invalidateMyLlmCacheIfLlmKey(definition.integrationName);
      await onChanged();
    } catch (err) {
      onError(err instanceof Error ? err.message : t("yourKeys.errors.switchFailed"));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className={cn("rounded-xl border border-theme bg-theme-secondary/50 p-5", locked && "opacity-70")}>
      {/* Stacked until there is room to sit side by side. The route chip spells out a whole
          sentence ("Runs on the LiveContext key (yours is saved)"), so on a narrow card it and
          the provider name fought for the same line and both lost: the logo tile collapsed and
          the name wrapped mid-word. Below `sm` the chip simply takes its own line. */}
      <div className="flex flex-col gap-2 mb-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-10 h-10 flex-shrink-0 bg-theme-tertiary rounded-lg flex items-center justify-center">
            <ServiceLogo
              src={`/icons/services/${definition.providerName}.svg`}
              alt={definition.displayName}
              className="w-6 h-6"
              onError={(e) => {
                (e.target as HTMLImageElement).style.display = "none";
              }}
            />
          </div>
          <div className="min-w-0">
            <h3 className="truncate text-sm font-semibold text-theme-primary" title={definition.displayName}>{definition.displayName}</h3>
            <a
              href={definition.docsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="text-xs text-theme-secondary hover:text-theme-primary inline-flex items-center gap-1"
            >
              {t("getDocs")}
              <ExternalLink className="w-3 h-3" />
            </a>
          </div>
        </div>

        {/* Which key serves the next execution on this provider */}
        <div
          className={cn(
            "inline-flex items-center gap-1.5 self-start px-2.5 py-1 rounded-md text-xs font-medium",
            runsOnMine
              ? "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400"
              : "bg-theme-tertiary text-theme-secondary"
          )}
          data-testid={`own-key-route-${definition.providerName}`}
        >
          <KeyRound className="w-3 h-3" />
          {runsOnMine
            ? t("yourKeys.route.mine")
            : hasKey
              ? t("yourKeys.route.platformSaved")
              : t("yourKeys.route.platform")}
        </div>
      </div>

      {/* The one sentence that says who pays */}
      <p className="text-sm text-theme-secondary mb-3">
        {runsOnMine
          ? t("yourKeys.billing.mine", { provider: definition.displayName })
          : t("yourKeys.billing.platform")}
      </p>

      {hasKey && !locked && (
        <div className="flex items-center justify-between rounded-lg border border-theme bg-theme-primary/40 px-3 py-2 mb-3">
          <span className="text-sm text-theme-primary">{t("yourKeys.useMyKey")}</span>
          <button
            type="button"
            role="switch"
            aria-checked={mode === "no_proxy"}
            aria-label={t("yourKeys.useMyKey")}
            disabled={busy !== null}
            onClick={handleToggle}
            className={cn(
              "relative inline-flex h-5 w-9 items-center rounded-full transition-colors",
              mode === "no_proxy" ? "bg-[var(--accent-primary)]" : "bg-theme-tertiary"
            )}
          >
            <span
              className={cn(
                "inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform",
                mode === "no_proxy" ? "translate-x-4" : "translate-x-0.5"
              )}
            />
          </button>
        </div>
      )}

      <div className="space-y-3">
        <div className="relative">
          <input
            type={showKey ? "text" : "password"}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder={hasKey ? t("yourKeys.replaceKey") : definition.placeholder}
            disabled={locked}
            className="w-full h-9 px-3 pr-9 text-sm rounded-lg border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-secondary/60 focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]/40 disabled:cursor-not-allowed"
            onKeyDown={(e) => e.key === "Enter" && handleSave()}
          />
          <button
            type="button"
            onClick={() => setShowKey(!showKey)}
            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-theme-secondary hover:text-theme-primary"
            aria-label={showKey ? t("yourKeys.hideKey") : t("yourKeys.showKey")}
          >
            {showKey ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
          </button>
        </div>

        <div className="flex items-center justify-end gap-2">
          <Button
            onClick={handleSave}
            disabled={locked || !apiKey.trim() || busy !== null}
            size="sm"
            variant={saved ? "outline" : "default"}
            className="h-8 px-3"
          >
            {busy === "save" ? (
              <LoadingSpinner size="sm" className="mr-1.5" />
            ) : saved ? (
              <Check className="w-3.5 h-3.5 mr-1.5" />
            ) : (
              <Save className="w-3.5 h-3.5 mr-1.5" />
            )}
            {saved ? t("saved") : hasKey ? t("yourKeys.replace") : t("yourKeys.addKey")}
          </Button>

          {hasKey && (
            <Button
              onClick={handleDelete}
              disabled={busy !== null}
              variant="outline"
              size="sm"
              className="h-8 px-3 text-red-600 hover:text-red-700 dark:text-red-400 dark:hover:text-red-300"
            >
              {busy === "delete" ? (
                <LoadingSpinner size="sm" className="mr-1.5" />
              ) : (
                <Trash2 className="w-3.5 h-3.5 mr-1.5" />
              )}
              {t("removeKey")}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
