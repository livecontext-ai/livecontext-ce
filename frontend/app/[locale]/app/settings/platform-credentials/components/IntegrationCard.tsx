"use client";

import React, { useState, useEffect } from "react";
import { Check, X, Settings, ChevronDown, ChevronUp, DollarSign } from "lucide-react";
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { ServiceIcon } from "@/components/ui/service-icon";
import {
  catalogVisibilityService,
  type ToolVisibility,
} from "@/lib/api/services/catalog-visibility.service";
import type { PlatformCredential } from "@/lib/api/orchestrator/types";
import type { MergedIntegration } from "../page";

/**
 * Pre-resolved catalog variant - computed by the page, passed to the card.
 * - `variant` / `authType` are from `catalog.credentials` (source of truth
 *   for what auth methods the API exposes, independent of whether the admin
 *   has saved a secret for them yet).
 * - `enabled` is the effective state: admin override → saved row → default true.
 * - `saved` is the backing `auth.platform_credentials` row if one exists,
 *   exposed so the card can show per-variant hints (client id preview, etc.).
 */
export interface ResolvedCatalogVariant {
  variant: string;
  authType: string;
  enabled: boolean;
  saved?: PlatformCredential;
}

interface IntegrationCardProps {
  integration: MergedIntegration;
  onToggleApiVisibility: (enabled: boolean) => void;
  onConfigure: () => void;
  onManagePricing?: () => void;
  onToggleCredentialEnabled?: (enabled: boolean) => void;
  /**
   * Catalog-declared variants for this integration. Drives the per-variant
   * toggle strip - unlike the earlier `integration.variants` (saved rows
   * only), this lets the admin disable a variant BEFORE any secret has been
   * saved for it. Empty or single-element arrays suppress the strip.
   */
  catalogVariants?: ResolvedCatalogVariant[];
  /**
   * Per-variant toggle callback. Receives the V103 variant key (oauth2,
   * api_key, bearer_token, ...) rather than a saved row, because the strip
   * iterates catalog variants - the admin may be toggling a variant that
   * has no saved row yet (backend upserts a placeholder on demand).
   */
  onToggleVariantEnabled?: (variantName: string, enabled: boolean) => void;
  expanded?: boolean;
  onToggleExpand?: () => void;
}

export function IntegrationCard({
  integration,
  onToggleApiVisibility,
  onConfigure,
  onManagePricing,
  onToggleCredentialEnabled,
  catalogVariants,
  onToggleVariantEnabled,
  expanded = false,
  onToggleExpand,
}: IntegrationCardProps) {
  const t = useTranslations("platformCredentials");
  const hasCredentials = integration.credential != null && (
    integration.credential.hasClientSecret ||
    integration.credential.hasApiKey ||
    integration.credential.hasBasicAuth
  );
  const authTypeLabel = getAuthTypeLabel(integration.authType);

  // Lazy-loaded tools
  const [tools, setTools] = useState<ToolVisibility[]>([]);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [toolsLoaded, setToolsLoaded] = useState(false);

  useEffect(() => {
    if (expanded && !toolsLoaded) {
      setToolsLoading(true);
      catalogVisibilityService.getApiTools(integration.apiId)
        .then((data) => {
          setTools(data);
          setToolsLoaded(true);
        })
        .catch(() => setTools([]))
        .finally(() => setToolsLoading(false));
    }
  }, [expanded, toolsLoaded, integration.apiId]);

  const handleToggleTool = async (toolId: string, isActive: boolean) => {
    try {
      await catalogVisibilityService.toggleTool(toolId, isActive);
      setTools((prev) =>
        prev.map((tool) => (tool.toolId === toolId ? { ...tool, isActive } : tool))
      );
    } catch (error) {
      console.error("Failed to toggle tool:", error);
    }
  };

  const toolCountLabel = integration.toolCount > 0
    ? `${integration.activeToolCount}/${integration.toolCount}`
    : undefined;

  return (
    <div className="border border-theme rounded-xl bg-theme-secondary/50 overflow-hidden">
      {/* Header */}
      <div className="p-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <ServiceIcon iconSlug={integration.iconSlug} size="lg" />
            <div>
              <div className="flex items-center gap-2">
                <p className="font-medium text-theme-primary">
                  {integration.apiName}
                </p>
                <span className="text-xs px-2 py-0.5 rounded-full bg-theme-tertiary text-theme-secondary">
                  {authTypeLabel}
                </span>
              </div>
              <div className="flex items-center gap-2 mt-0.5">
                {integration.credentialName && (
                  <p className="text-xs text-theme-secondary">
                    {integration.credentialName}
                  </p>
                )}
                {toolCountLabel && (
                  <span className="text-xs text-theme-muted">
                    {t("visibility.toolCount", { active: integration.activeToolCount, total: integration.toolCount })}
                  </span>
                )}
              </div>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* Secrets Status Badge */}
            {hasCredentials ? (
              <span className="flex items-center gap-1.5 text-xs text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-900/20 px-2 py-1 rounded-full">
                <Check className="w-3 h-3" />
                {t("visibility.configured")}
              </span>
            ) : (
              <span className="flex items-center gap-1.5 text-xs text-yellow-600 dark:text-yellow-400 bg-yellow-50 dark:bg-yellow-900/20 px-2 py-1 rounded-full">
                <X className="w-3 h-3" />
                {t("visibility.notConfigured")}
              </span>
            )}

            {/* API Visibility Toggle (main on/off) */}
            <Switch
              checked={integration.isActive}
              onCheckedChange={onToggleApiVisibility}
              aria-label={`${t("visibility.toggleApi")} ${integration.apiName}`}
            />

            {/* Configure Button */}
            <Button variant="outline" size="sm" onClick={onConfigure}>
              <Settings className="w-4 h-4 mr-1.5" />
              {hasCredentials ? t("visibility.edit") : t("visibility.configure")}
            </Button>

            {/* Pricing Button - only visible when credentials are configured (pricing needs an id) */}
            {onManagePricing && integration.credential?.id && (
              <Button variant="ghost" size="sm" onClick={onManagePricing}>
                <DollarSign className="w-4 h-4 mr-1.5" />
                {t("pricing.manage")}
              </Button>
            )}

            {/* Expand Tools */}
            {integration.toolCount > 0 && onToggleExpand && (
              <Button variant="ghost" size="sm" onClick={onToggleExpand}>
                {expanded ? (
                  <ChevronUp className="w-4 h-4" />
                ) : (
                  <ChevronDown className="w-4 h-4" />
                )}
              </Button>
            )}
          </div>
        </div>

        {integration.credential?.description && (
          <p className="text-sm text-theme-secondary mt-2 ml-11">
            {integration.credential.description}
          </p>
        )}

        {integration.credential?.clientIdMasked && (
          <p className="text-xs text-theme-muted mt-1 ml-11 font-mono">
            Client ID: {integration.credential.clientIdMasked}
          </p>
        )}

        {/* Per-variant toggles - rendered when the integration exposes more
            than one auth method in the CATALOG (not merely more than one
            saved secret row). Each row flips exactly its variant, independent
            of the outer API visibility switch. A variant with no saved row
            still appears here as "enabled" and can be toggled OFF to hide
            that auth method from end-users without creating secrets first. */}
        {onToggleVariantEnabled && catalogVariants && catalogVariants.length > 1 && (
          <div className="mt-3 ml-11 flex flex-col gap-1.5">
            <p className="text-xs font-medium text-theme-secondary">
              {t("variants.heading")}
            </p>
            {catalogVariants.map((cv) => (
              <div
                key={cv.variant}
                className="flex items-center justify-between py-1.5 px-2 rounded bg-theme-tertiary/40"
              >
                <div className="flex items-center gap-2">
                  <span className="text-sm text-theme-primary">
                    {getAuthTypeLabel(cv.variant)}
                  </span>
                  <span className="text-xs text-theme-muted font-mono">
                    {cv.variant}
                  </span>
                  {!cv.saved && (
                    <span className="text-xs text-theme-muted italic">
                      {t("variants.noSecrets")}
                    </span>
                  )}
                </div>
                <Switch
                  checked={cv.enabled}
                  onCheckedChange={(enabled) => onToggleVariantEnabled(cv.variant, enabled)}
                  aria-label={`${t("variants.toggle")} ${cv.variant}`}
                />
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Tools List (Expanded, lazy-loaded) */}
      {expanded && (
        <div className="border-t border-theme px-4 py-3 bg-theme-tertiary/30">
          <p className="text-xs font-medium text-theme-secondary mb-2">
            {t("visibility.endpoints")} ({toolsLoaded ? tools.length : integration.toolCount})
          </p>
          {toolsLoading ? (
            <div className="flex items-center justify-center py-4">
              <LoadingSpinner size="xs" />
            </div>
          ) : (
            <div className="space-y-2">
              {tools.map((tool) => (
                <ToolRow
                  key={tool.toolId}
                  tool={tool}
                  onToggle={(enabled) => handleToggleTool(tool.toolId, enabled)}
                />
              ))}
              {tools.length === 0 && toolsLoaded && (
                <p className="text-xs text-theme-muted py-2">
                  {t("visibility.noTools")}
                </p>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

interface ToolRowProps {
  tool: ToolVisibility;
  onToggle: (enabled: boolean) => void;
}

function ToolRow({ tool, onToggle }: ToolRowProps) {
  return (
    <div className="flex items-center justify-between py-1.5 px-2 rounded hover:bg-theme-secondary/50">
      <div className="flex items-center gap-2">
        {tool.method && (
          <span
            className={`text-xs font-mono px-1.5 py-0.5 rounded ${getMethodColor(tool.method)}`}
          >
            {tool.method}
          </span>
        )}
        <span className="text-sm text-theme-primary">
          {tool.toolName || tool.toolSlug}
        </span>
        {tool.toolSlug && (
          <span className="text-xs text-theme-muted font-mono">
            {tool.toolSlug}
          </span>
        )}
      </div>
      <Switch
        checked={tool.isActive}
        onCheckedChange={onToggle}
        aria-label={`Enable ${tool.toolName}`}
      />
    </div>
  );
}

function getAuthTypeLabel(authType: string | undefined): string {
  if (!authType) return "";
  switch (authType) {
    case "oauth2":
      return "OAuth 2.0";
    case "api_key":
    case "apikey":
      return "API Key";
    case "basic":
    case "basic_auth":
      return "Basic Auth";
    case "bearer":
    case "bearer_token":
      return "Bearer Token";
    case "custom":
      return "Custom";
    case "primary":
      return "Primary";
    default:
      return authType;
  }
}

function getMethodColor(method: string): string {
  switch (method.toUpperCase()) {
    case "GET":
      return "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400";
    case "POST":
      return "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400";
    case "PUT":
    case "PATCH":
      return "bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400";
    case "DELETE":
      return "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400";
    default:
      return "bg-gray-100 text-gray-700 dark:bg-gray-900/30 dark:text-gray-400";
  }
}
