"use client";

import React, { useState, useEffect, useCallback, useRef } from "react";
import { BotMessageSquare, Cloud, Key, Terminal, SlidersHorizontal, User, Shield, Info, Route } from "lucide-react";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useAuth } from "@/lib/providers/smart-providers";
import { credentialService } from "@/lib/api/orchestrator/credential.service";
import { cloudLinkService, type CloudLinkStatus } from "@/lib/api/cloud-link.service";
import { clearModelsCache } from "@/hooks/useModels";
import { Button } from "@/components/ui/button";
import { useLocale, useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import ProviderCard from "./components/ProviderCard";
import BridgeSetupPanel from "./components/BridgeSetupPanel";
import BridgeAccessPanel from "./components/BridgeAccessPanel";
import ModelManagementPanel from "./components/ModelManagementPanel";
import ModelExecutionLinksPanel from "./components/ModelExecutionLinksPanel";
import UserKeysPanel from "./components/UserKeysPanel";
import { ModelBundleSyncButton } from "./components/ModelBundleSyncButton";
import type { LlmProviderStatus, LlmProviderDefinition } from "@/lib/api/orchestrator/types";
import { IS_CE, IS_CLOUD } from "@/lib/edition";
import { isProviderHiddenInCe } from "@/lib/ai-providers/providerIcons";
import { track } from "@/lib/analytics/analytics";

const PROVIDER_DEFINITIONS: LlmProviderDefinition[] = [
  {
    providerName: "anthropic",
    integrationName: "llm_anthropic",
    displayName: "Anthropic (Claude)",
    docsUrl: "https://console.anthropic.com/settings/keys",
    placeholder: "sk-ant-...",
  },
  {
    providerName: "openai",
    integrationName: "llm_openai",
    displayName: "OpenAI (GPT)",
    docsUrl: "https://platform.openai.com/api-keys",
    placeholder: "sk-...",
  },
  {
    providerName: "google",
    integrationName: "llm_google",
    displayName: "Google (Gemini)",
    docsUrl: "https://aistudio.google.com/app/apikey",
    placeholder: "AIza...",
  },
  {
    providerName: "mistral",
    integrationName: "llm_mistral",
    displayName: "Mistral AI",
    docsUrl: "https://console.mistral.ai/api-keys/",
    placeholder: "...",
  },
  {
    providerName: "deepseek",
    integrationName: "llm_deepseek",
    displayName: "DeepSeek",
    docsUrl: "https://platform.deepseek.com/api_keys",
    placeholder: "sk-...",
  },
  {
    providerName: "xai",
    integrationName: "llm_xai",
    displayName: "xAI (Grok)",
    docsUrl: "https://console.x.ai/",
    placeholder: "xai-...",
  },
  {
    providerName: "perplexity",
    integrationName: "llm_perplexity",
    displayName: "Perplexity (Sonar)",
    docsUrl: "https://www.perplexity.ai/settings/api",
    placeholder: "pplx-...",
  },
  {
    providerName: "cohere",
    integrationName: "llm_cohere",
    displayName: "Cohere (Command R+)",
    docsUrl: "https://dashboard.cohere.com/api-keys",
    placeholder: "...",
  },
  {
    providerName: "zai",
    integrationName: "llm_zai",
    displayName: "Z.AI (GLM)",
    docsUrl: "https://z.ai/manage-apikey/apikey-list",
    placeholder: "...",
  },
  {
    providerName: "openrouter",
    integrationName: "llm_openrouter",
    displayName: "OpenRouter (Multi-provider)",
    docsUrl: "https://openrouter.ai/settings/keys",
    placeholder: "sk-or-...",
  },
  {
    providerName: "qwen",
    integrationName: "llm_qwen",
    displayName: "Qwen (Alibaba)",
    docsUrl: "https://bailian.console.alibabacloud.com/",
    placeholder: "sk-...",
  },
  {
    providerName: "moonshot",
    integrationName: "llm_moonshot",
    displayName: "Moonshot (Kimi)",
    docsUrl: "https://platform.moonshot.ai/console/api-keys",
    placeholder: "sk-...",
  },
  {
    providerName: "minimax",
    integrationName: "llm_minimax",
    displayName: "MiniMax",
    docsUrl: "https://platform.minimax.io/user-center/basic-information/interface-key",
    placeholder: "eyJ...",
  },
  {
    // A DECISION provider, not a chat one: its model returns a typed choice among the
    // categories a Classify node declares and cannot produce text. It has no place in a
    // chat or agent picker, and the catalogue keeps it out of them - but the KEY is
    // entered here like every other provider's, so it belongs in this list.
    //
    // Without an entry here the card simply does not render: the page intersects these
    // definitions with the backend status list, so a provider the backend advertises and
    // this file omits is dropped in silence, leaving no way to key it from the UI.
    providerName: "typesafe",
    integrationName: "llm_typesafe",
    displayName: "TypeSafe (Jev)",
    // The card labels this link "Get API key", so it points at the console where a key
    // is issued, not at the API reference (docs.typesafe.ai/api), which describes the
    // request and never says where the key comes from. The key's own format is not
    // published, so the placeholder promises no prefix rather than inventing one.
    docsUrl: "https://console.typesafe.ai/",
    placeholder: "...",
  },
];

function LoadingDot() {
  return <span className="h-4 w-4 rounded-full border-2 border-current border-t-transparent animate-spin" />;
}

export default function AiProvidersPage() {
  const { isAuthenticated, isAuthChecking, isLoading: isAuthLoading } = useAuthGuard();
  const { loginWithRedirect, hasRole } = useAuth();
  const t = useTranslations("aiProviders");
  const tSettings = useTranslations("settings");
  const locale = useLocale();
  // The pricing page the "upgrade" prompt of the own-keys panel leads to.
  const pricingHref = `/${locale}/app/settings/pricing`;
  // The providers a cloud user may bring a key for: the same list the admin manages, minus
  // the ones never offered to end users (aggregators the platform prices on its own).
  const userKeyDefinitions = PROVIDER_DEFINITIONS.filter((def) => !isProviderHiddenInCe(def.providerName));

  const [connectionMode, setConnectionMode] = useState<"api_key" | "claude_code" | "codex" | "gemini_cli" | "mistral_vibe" | "models" | "execution_links" | "your_keys">("api_key");
  const [statuses, setStatuses] = useState<LlmProviderStatus[]>([]);
  const [cloudLinkStatus, setCloudLinkStatus] = useState<CloudLinkStatus | null>(null);
  const [llmSource, setLlmSource] = useState<"CLOUD" | "BYOK">("BYOK");
  const [sourceLoading, setSourceLoading] = useState(false);
  const [sourceSaving, setSourceSaving] = useState<"CLOUD" | "BYOK" | null>(null);
  const [sourceError, setSourceError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const tabs = [
    { id: "api_key" as const, label: t("mode.apiKey"), icon: Key, iconSrc: null as string | null },
    { id: "claude_code" as const, label: t("mode.claudeCode"), icon: Terminal, iconSrc: "/icons/services/claude-code.svg" },
    { id: "codex" as const, label: t("mode.codex"), icon: Terminal, iconSrc: "/icons/services/codex.svg" },
    { id: "gemini_cli" as const, label: t("mode.geminiCli"), icon: Terminal, iconSrc: "/icons/services/gemini-cli.svg" },
    { id: "mistral_vibe" as const, label: t("mode.mistralVibe"), icon: Terminal, iconSrc: "/icons/services/mistral-vibe.svg" },
    { id: "models" as const, label: t("mode.models"), icon: SlidersHorizontal, iconSrc: null as string | null },
    // Execution links are a CLOUD-only monetization feature: hide the tab in CE.
    ...(IS_CLOUD
      ? [{ id: "execution_links" as const, label: t("mode.executionLinks"), icon: Route, iconSrc: null as string | null }]
      : []),
    // A cloud user's OWN provider keys (from PRO): the admin sees the tab among the others,
    // a non-admin cloud user sees this panel alone (below).
    ...(IS_CLOUD
      ? [{ id: "your_keys" as const, label: t("mode.yourKeys"), icon: User, iconSrc: null as string | null }]
      : []),
  ];

  const tabContainerRef = useRef<HTMLDivElement>(null);
  const [tabSliderStyle, setTabSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  useEffect(() => {
    const updateSlider = () => {
      if (!tabContainerRef.current) return;
      const activeButton = tabContainerRef.current.querySelector(`[data-tab-id="${connectionMode}"]`) as HTMLButtonElement;
      if (activeButton) {
        const containerRect = tabContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setTabSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };
    requestAnimationFrame(() => requestAnimationFrame(updateSlider));
    window.addEventListener('resize', updateSlider);
    return () => window.removeEventListener('resize', updateSlider);
  }, [connectionMode, loading, isAuthChecking]);

  const fetchStatus = useCallback(async () => {
    try {
      const data = await credentialService.getLlmProviderStatus();
      // Defensive: backend returns an array, but if anything upstream wraps
      // it in an error object the page would crash on `.find` below.
      setStatuses(Array.isArray(data) ? data : []);
      setError(null);
    } catch (err) {
      console.error("Failed to load LLM provider status:", err);
      setStatuses([]);
      setError(t("errors.fetchFailed"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (isAuthChecking) return;
    if (isAuthenticated && hasRole('ADMIN')) {
      // The platform status is an admin view (the endpoint answers 403 to anyone else);
      // a cloud user on their own-keys panel has nothing to fetch here.
      fetchStatus();
    } else {
      setLoading(false);
    }
  }, [isAuthChecking, isAuthenticated, fetchStatus, hasRole]);

  const fetchCloudLlmSource = useCallback(async () => {
    if (!IS_CE) return;
    setSourceLoading(true);
    try {
      const status = await cloudLinkService.getStatus();
      setCloudLinkStatus(status);
      setLlmSource(status.llmSource ?? "BYOK");
      setSourceError(null);
    } catch (err) {
      console.warn("Failed to load CE cloud LLM source:", err);
      setCloudLinkStatus(null);
      setLlmSource("BYOK");
    } finally {
      setSourceLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isAuthChecking || !isAuthenticated) return;
    fetchCloudLlmSource();
  }, [fetchCloudLlmSource, isAuthChecking, isAuthenticated]);

  const handleSetLlmSource = async (source: "CLOUD" | "BYOK") => {
    if (source === llmSource || sourceSaving) return;
    setSourceSaving(source);
    setSourceError(null);
    try {
      const saved = await cloudLinkService.setLlmSource(source);
      setLlmSource(saved);
      setCloudLinkStatus((current) => current ? { ...current, llmSource: saved } : current);
      // The available model catalog depends on the source (CLOUD vs BYOK) - drop
      // the cached list so the picker refetches the right one on next open.
      clearModelsCache();
    } catch (err) {
      console.error("Failed to save CE LLM source:", err);
      setSourceError(source === "CLOUD" ? t("cloudSource.linkRequired") : t("cloudSource.saveError"));
    } finally {
      setSourceSaving(null);
    }
  };

  const handleSave = async (integrationName: string, apiKey: string) => {
    const def = PROVIDER_DEFINITIONS.find((d) => d.integrationName === integrationName);
    if (!def) return;

    await credentialService.savePlatformCredential({
      integrationName,
      displayName: def.displayName,
      authType: "api_key",
      apiKey,
      category: "llm_provider",
    });
    track('ai_provider_key_saved', { integration_name: integrationName, provider_name: def.providerName });

    // Invalidate cache on agent-service side
    await credentialService.invalidateLlmCache(def.providerName);
    // A new key changes which models are executable (a first key flips the
    // pickers from the NoProviderCta empty state to this provider's catalog) -
    // drop the frontend model cache so pickers refetch on next mount.
    clearModelsCache();
    await fetchStatus();
  };

  const handleDelete = async (integrationName: string) => {
    const def = PROVIDER_DEFINITIONS.find((d) => d.integrationName === integrationName);
    await credentialService.deletePlatformCredential(integrationName);
    track('ai_provider_key_deleted', { integration_name: integrationName });

    if (def) {
      await credentialService.invalidateLlmCache(def.providerName);
    }
    // Symmetric with save: removing the last key must surface the empty state.
    clearModelsCache();
    await fetchStatus();
  };

  if (isAuthChecking || isAuthLoading || loading) {
    return (
      <div className="space-y-6">
        <div className="h-5 bg-theme-tertiary rounded w-2/3 animate-pulse" />
        {[1, 2, 3, 4, 5].map((i) => (
          <div key={i} className="rounded-xl border border-theme bg-theme-secondary/50 p-5 animate-pulse">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 bg-theme-tertiary rounded-lg" />
              <div className="space-y-2">
                <div className="h-4 bg-theme-tertiary rounded w-32" />
                <div className="h-3 bg-theme-tertiary rounded w-24" />
              </div>
            </div>
            <div className="h-9 bg-theme-tertiary rounded-lg" />
          </div>
        ))}
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="space-y-8">
        <div className="min-h-[300px] flex items-center justify-center">
          <div className="text-center">
            <h1 className="text-2xl font-bold text-theme-primary mb-4">
              {tSettings("unauthorized")}
            </h1>
            <p className="text-theme-secondary mb-6">
              {tSettings("mustBeLoggedIn")}
            </p>
            <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
              <User className="w-4 h-4 mr-1" />
              {tSettings("signIn")}
            </Button>
          </div>
        </div>
      </div>
    );
  }

  if (!hasRole('ADMIN') && IS_CLOUD) {
    // Not the platform's providers, only the user's own keys: the one part of this page
    // that is theirs to configure.
    return (
      <div className="space-y-6">
        {/* The icon is a fixed 40px tile, so it must be told not to shrink: in a flex row
            beside text it is the flexible item, and in the settings column - a third of the
            width on desktop, all of it on a phone - it was being squeezed into a sliver.
            The title carries `min-w-0` for the same reason, so long words wrap instead of
            pushing the row wider than its parent. */}
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 flex-shrink-0 bg-theme-tertiary rounded-xl flex items-center justify-center">
            <BotMessageSquare className="w-5 h-5 text-theme-primary" />
          </div>
          {/* The intro used to be repeated here AND at the top of the panel below, two
              paragraphs of the same sentence a few pixels apart. The panel states it once,
              where the price and the providers are. */}
          <h2 className="min-w-0 text-lg font-semibold text-theme-primary">{t("mode.yourKeys")}</h2>
        </div>
        <UserKeysPanel definitions={userKeyDefinitions} t={t} pricingHref={pricingHref} />
      </div>
    );
  }

  if (!hasRole('ADMIN')) {
    return (
      <div className="min-h-[300px] flex items-center justify-center">
        <div className="text-center">
          <Shield className="w-10 h-10 text-theme-muted mx-auto mb-3" />
          <h2 className="text-lg font-semibold text-theme-primary mb-2">{tSettings("unauthorized")}</h2>
          <p className="text-sm text-theme-secondary">{t("errors.adminOnly") ?? "Only the platform administrator can configure AI providers."}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-10 h-10 flex-shrink-0 bg-theme-tertiary rounded-xl flex items-center justify-center">
            <BotMessageSquare className="w-5 h-5 text-theme-primary" />
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <h2 className="text-lg font-semibold text-theme-primary">{t("title")}</h2>
              <div className="relative group">
                <Info className="w-3.5 h-3.5 text-theme-secondary hover:text-theme-primary cursor-help transition-colors" />
                <div className="absolute left-0 top-5 z-50 hidden group-hover:block w-72 max-w-[calc(100vw-2rem)] p-3 rounded-xl bg-theme-primary border border-theme shadow-lg text-xs text-theme-secondary leading-relaxed">
                  {t("infoBanner")}
                </div>
              </div>
            </div>
            <p className="text-sm text-theme-secondary">{t("subtitle")}</p>
          </div>
        </div>
        <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-md bg-amber-500/10 text-amber-700 dark:text-amber-400 text-xs font-medium">
          <Shield className="w-3.5 h-3.5" />
          {t("adminOnlyBadge")}
        </div>
      </div>

      {/* Connection mode toggle */}
      <div className="flex max-w-full overflow-x-auto scrollbar-hide -mx-1 px-1">
        <div className="relative mx-auto inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-2xl w-max" ref={tabContainerRef}>
          <div
            className="absolute top-1.5 bottom-1.5 rounded-xl bg-[var(--bg-primary)] transition-all duration-200 ease-out"
            style={{
              left: tabSliderStyle.left,
              width: tabSliderStyle.width,
              opacity: tabSliderStyle.width ? 1 : 0,
            }}
          />
          {tabs.map((tab) => (
            <button
              key={tab.id}
              data-tab-id={tab.id}
              type="button"
              onClick={() => setConnectionMode(tab.id)}
              title={tab.label}
              className={cn(
                "relative z-10 flex h-9 flex-shrink-0 items-center gap-2 px-3 sm:px-4 rounded-xl text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
                connectionMode === tab.id
                  ? "text-[var(--text-primary)]"
                  : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
              )}
            >
              {tab.iconSrc ? (
                <img src={tab.iconSrc} alt="" className="w-4 h-4 flex-shrink-0" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
              ) : (
                <tab.icon className={cn("w-4 h-4 flex-shrink-0 transition-colors duration-200", connectionMode === tab.id ? "text-[var(--text-primary)]" : "text-current")} />
              )}
              <span className="hidden sm:inline whitespace-nowrap">{tab.label}</span>
            </button>
          ))}
        </div>
      </div>

      {connectionMode === "api_key" && (
        <>
          {/*
            Cloud vs BYOK source toggle lives ONLY here, inside the API Keys
            tab: API providers are the only models the cloud can serve for you,
            so "use cloud defaults vs bring your own key" is a choice that makes
            sense for API keys alone. CLI providers (Claude Code, Codex, …) are
            local bridges the cloud can never provide, so they have no such toggle.
          */}
          {IS_CE && (
            <>
            <div className="flex flex-col items-center gap-2 text-center">
              <div className="relative inline-flex items-center gap-1 rounded-2xl bg-theme-tertiary p-1.5 w-max">
                <button
                  type="button"
                  onClick={() => handleSetLlmSource("CLOUD")}
                  disabled={sourceLoading || sourceSaving !== null}
                  className={cn(
                    "inline-flex h-9 items-center gap-2 rounded-xl px-4 text-sm font-medium transition-colors",
                    llmSource === "CLOUD"
                      ? "bg-[var(--bg-primary)] text-theme-primary shadow-sm"
                      : "text-theme-secondary hover:text-theme-primary"
                  )}
                >
                  {sourceSaving === "CLOUD" ? <LoadingDot /> : <Cloud className="h-4 w-4" />}
                  {t("cloudSource.cloud")}
                </button>
                <button
                  type="button"
                  onClick={() => handleSetLlmSource("BYOK")}
                  disabled={sourceLoading || sourceSaving !== null}
                  className={cn(
                    "inline-flex h-9 items-center gap-2 rounded-xl px-4 text-sm font-medium transition-colors",
                    llmSource === "BYOK"
                      ? "bg-[var(--bg-primary)] text-theme-primary shadow-sm"
                      : "text-theme-secondary hover:text-theme-primary"
                  )}
                >
                  {sourceSaving === "BYOK" ? <LoadingDot /> : <Key className="h-4 w-4" />}
                  {t("cloudSource.apiKeys")}
                </button>
              </div>
              <p className="text-sm text-theme-secondary max-w-md">
                {llmSource === "CLOUD" ? t("cloudSource.cloudHint") : t("cloudSource.byokHint")}
              </p>
              {llmSource === "CLOUD" && cloudLinkStatus?.cloudUsername && (
                <span className="inline-flex items-center gap-1.5 rounded-md bg-emerald-500/10 px-2.5 py-1 text-xs text-emerald-600 dark:text-emerald-400">
                  <Cloud className="h-3 w-3" />
                  {t("cloudSource.connectedAs", { username: cloudLinkStatus.cloudUsername })}
                </span>
              )}
              {sourceError && (
                <p className="text-sm text-red-600 dark:text-red-400">{sourceError}</p>
              )}
            </div>
            {/* Integration credential source (Cloud vs Local) lives on the Cloud
                Account settings page - it is a property of the cloud link, not of
                the LLM API keys. */}
            </>
          )}
          {error && (
            <div className="rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-4">
              <p className="text-sm text-red-800 dark:text-red-300">{error}</p>
            </div>
          )}

          <div className="space-y-4">
            {/*
              Backend is the source of truth for which API providers exist
              (`/api/llm-providers/status` derives them from LLMProviderFactory).
              The frontend keeps `PROVIDER_DEFINITIONS` purely for display
              metadata (label, docs URL, key placeholder) and intersects with
              the backend list - so adding a new provider only requires
              registering its bean in agent-service, and any provider not
              advertised by the backend is silently dropped from the UI.
            */}
            {PROVIDER_DEFINITIONS
              .filter((def) => statuses.some((s) => s.providerName === def.providerName))
              // CE boundary: never surface the multi-provider aggregator
              // (openrouter) or the curated-out cohere provider on a self-hosted
              // install. The backend /status already omits them in CE (see
              // CeBlockedProviders); this is defence in depth so a stray status
              // row can't re-expose them. Cloud shows every provider.
              .filter((def) => !(IS_CE && isProviderHiddenInCe(def.providerName)))
              .map((def) => (
                <ProviderCard
                  key={def.providerName}
                  definition={def}
                  status={statuses.find((s) => s.providerName === def.providerName)}
                  onSave={handleSave}
                  onDelete={handleDelete}
                  t={t}
                />
              ))}
          </div>
        </>
      )}

      {(connectionMode === "claude_code" || connectionMode === "codex" || connectionMode === "gemini_cli" || connectionMode === "mistral_vibe") && (
        <div className="space-y-6">
          <BridgeSetupPanel cli={
            connectionMode === "claude_code" ? "claudeCode" :
            connectionMode === "gemini_cli" ? "geminiCli" :
            connectionMode === "mistral_vibe" ? "mistralVibe" :
            "codex"
          } t={t} />
          {/*
            Second card: who can dispatch through this bridge. The bridge_provider
            string must match `BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID`
            keys (kebab-case) so the auth-service policy row lines up with what
            `LLMProviderFactory.getProviderForUser()` checks at runtime.
          */}
          <BridgeAccessPanel bridgeProvider={
            connectionMode === "claude_code" ? "claude-code" :
            connectionMode === "gemini_cli" ? "gemini-cli" :
            connectionMode === "mistral_vibe" ? "mistral-vibe" :
            "codex"
          } t={t} />
        </div>
      )}

      {connectionMode === "models" && (
        <div className="space-y-4">
          <ModelManagementPanel t={t} />
          {/* Update the model catalog bundle (cloud → CE) directly, with a link
              to the full Bundles tab - replaces the old "bundles moved" pointer. */}
          <ModelBundleSyncButton />
        </div>
      )}

      {IS_CLOUD && connectionMode === "execution_links" && (
        <ModelExecutionLinksPanel />
      )}

      {IS_CLOUD && connectionMode === "your_keys" && (
        <UserKeysPanel definitions={userKeyDefinitions} t={t} pricingHref={pricingHref} />
      )}
    </div>
  );
}
