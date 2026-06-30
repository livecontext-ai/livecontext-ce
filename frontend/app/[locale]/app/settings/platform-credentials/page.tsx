"use client";

import React, { useState, useEffect, useCallback, useRef } from "react";
import { Shield, Plus, RefreshCw, Search, User, List, Settings, Filter } from "lucide-react";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useAuth } from "@/lib/providers/smart-providers";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import Toast, { useToast } from "@/components/Toast";
import { credentialService } from "@/lib/api/orchestrator/credential.service";
import {
  PlatformCredential,
  CategoryInfo,
  CreatePlatformCredentialRequest,
} from "@/lib/api/orchestrator/types";
import {
  catalogVisibilityService,
  type IntegrationVisibility,
} from "@/lib/api/services/catalog-visibility.service";
import {
  CategoryTabs,
  IntegrationCard,
  CredentialFormDialog,
  PricingModal,
  type AvailableIntegration,
} from "./components";

import type { CredentialFieldDef } from "@/lib/api/services/catalog-visibility.service";
import { IS_CE } from "@/lib/edition";

/** Merged view combining catalog visibility + orchestrator credential data */
export interface MergedIntegration {
  apiId: string;
  apiName: string;
  iconSlug: string;
  authType: string;
  credentialName?: string;
  isActive: boolean;
  toolCount: number;
  activeToolCount: number;
  category?: string;
  credentialFields?: CredentialFieldDef[];
  // From orchestrator (optional - only if secrets are configured).
  // `credential` is the canonical variant shown at the card header (enabled
  // one preferred, else the first). `variants` holds saved `auth.platform_credentials`
  // rows - empty when the admin hasn't configured anything yet.
  credential?: PlatformCredential;
  variants?: PlatformCredential[];
  // All variants declared in `catalog.credentials` for this `credential_name`
  // (source of truth for what auth methods the API actually supports). Used
  // to render the per-variant toggle strip for every declared variant, not
  // only the ones that happen to have a saved secret row - the admin needs
  // to disable e.g. `bearer_token` even before any secret is saved.
  catalogVariants?: Array<{ variant: string; authType: string }>;
}

export default function PlatformCredentialsPage() {
  const { isAuthenticated, isAuthChecking, isLoading: isAuthLoading } = useAuthGuard();
  const { loginWithRedirect, hasRole } = useAuth();
  const t = useTranslations("platformCredentials");
  const tSettings = useTranslations("settings");
  const { toasts, addToast, removeToast } = useToast();
  // Data state
  const [integrations, setIntegrations] = useState<MergedIntegration[]>([]);
  const [categories, setCategories] = useState<CategoryInfo[]>([]);
  const [availableIntegrations, setAvailableIntegrations] = useState<AvailableIntegration[]>([]);
  const [loading, setLoading] = useState(true);

  // UI state
  const [viewMode, setViewMode] = useState<"configured" | "all">("all");
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedAuthType, setSelectedAuthType] = useState<string>("all");
  const [expandedCards, setExpandedCards] = useState<Set<string>>(new Set());
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingCredential, setEditingCredential] = useState<PlatformCredential | null>(null);
  // All saved variants for the integration currently in the dialog - passed
  // through to CredentialFormDialog so the tab strip can show per-variant
  // state (configured / disabled) on multi-variant APIs like Airtable.
  const [editingVariants, setEditingVariants] = useState<PlatformCredential[] | null>(null);
  const [preSelectedIntegration, setPreSelectedIntegration] = useState<{ iconSlug: string; apiName: string; authType: string; platformCredentialName?: string; credentialFields?: CredentialFieldDef[] } | null>(null);
  const [pricingModalOpen, setPricingModalOpen] = useState(false);
  const [pricingCredential, setPricingCredential] = useState<PlatformCredential | null>(null);
  // Transient optimistic state for per-variant toggles. Keyed by
  // `${credentialName}|${variant}` (lowercased). Needed because a variant can
  // be toggled BEFORE any `auth.platform_credentials` row exists for it: on
  // the first click we have nothing to flip in `integration.variants`, so we
  // layer an override on top until `fetchData()` reconciles with the real row
  // the backend just created. Cleared on both success and failure.
  const [variantOverrides, setVariantOverrides] = useState<Map<string, boolean>>(new Map());

  // Fetch data from both sources and merge
  const fetchData = useCallback(async () => {
    try {
      setLoading(true);

      const [visibilityData, credentialsData, categoriesData, templatesData] = await Promise.all([
        catalogVisibilityService.getIntegrations().catch(() => [] as IntegrationVisibility[]),
        credentialService.getPlatformCredentials(selectedCategory || undefined).catch(() => []),
        credentialService.getPlatformCredentialCategories().catch(() => []),
        // `includeInactive: true` - admins need to see variants even for APIs
        // they have already disabled (otherwise the toggle strip vanishes when
        // you most need it to flip a variant back on).
        credentialService
          .getCredentialTemplates({ pageSize: 1000, includeInactive: true })
          .catch(() => ({ credentials: [] } as unknown as Awaited<ReturnType<typeof credentialService.getCredentialTemplates>>)),
      ]);

      setCategories(categoriesData);

      // Index catalog variants by credential_name (lowercased) - one paginated
      // fetch returns the full `variants[]` per template thanks to the
      // GROUP BY / jsonb_agg in the backend, so we avoid N network calls.
      const catalogVariantsByName = new Map<string, Array<{ variant: string; authType: string }>>();
      for (const tpl of templatesData.credentials ?? []) {
        const key = tpl.credential_name?.toLowerCase().trim();
        if (!key) continue;
        const declared = (tpl.variants ?? [])
          .filter((v) => v?.variant)
          .map((v) => ({ variant: v.variant, authType: v.auth_type }));
        // Single-variant templates may omit variants[]; synthesize from top-level.
        if (declared.length === 0 && tpl.variant) {
          declared.push({ variant: tpl.variant, authType: tpl.auth_type });
        }
        if (declared.length > 0) {
          catalogVariantsByName.set(key, declared);
        }
      }

      // Index credentials by integrationName. After the catalog importer
      // sets `apis.platform_credential_name` from `normalizeForKey(apiName)`
      // (catalog-service-import 2026-04-09 fix), `vis.credentialName` is
      // guaranteed unique per API and the dialog stores the same value as
      // `integration_name`. After V103 an integration may have multiple rows
      // - one per variant - so we collect them all, then pick a canonical
      // "primary" for back-compat slots that still expect a single credential.
      const credentialsByName = new Map<string, PlatformCredential[]>();
      for (const cred of credentialsData) {
        const key = cred.integrationName?.toLowerCase().trim();
        if (!key) continue;
        const bucket = credentialsByName.get(key) ?? [];
        bucket.push(cred);
        credentialsByName.set(key, bucket);
      }
      const lookupVariants = (vis: IntegrationVisibility) => {
        const key = vis.credentialName?.toLowerCase().trim();
        return key ? credentialsByName.get(key) ?? [] : [];
      };
      // Canonical variant = enabled one (if any), else the first. Keeps the
      // "configured" badge / description / client-id preview deterministic
      // when variants differ.
      const pickPrimary = (variants: PlatformCredential[]): PlatformCredential | undefined => {
        if (variants.length === 0) return undefined;
        return variants.find((v) => v.isEnabled) ?? variants[0];
      };
      const lookupCredential = (vis: IntegrationVisibility) => pickPrimary(lookupVariants(vis));

      // Deduplicate visibility data by apiId (catalog may return duplicates)
      const uniqueVisibility = Array.from(
        new Map(visibilityData.map((vis) => [vis.apiId, vis])).values()
      );

      // Derive available integrations from deduplicated visibility data - duplicate apiIds
      // would produce duplicate React keys in the dialog's <SelectItem> list.
      const derived: AvailableIntegration[] = uniqueVisibility.map((vis) => ({
        id: vis.apiId,
        apiName: vis.apiName,
        apiSlug: vis.iconSlug,
        iconSlug: vis.iconSlug,
        authType: vis.authType,
        platformCredentialName: vis.credentialName || "",
        hasCredential: !!lookupCredential(vis),
      }));
      setAvailableIntegrations(derived);

      // Merge: catalog visibility is the primary list, credentials overlay.
      const merged: MergedIntegration[] = uniqueVisibility.map((vis) => {
        const variants = lookupVariants(vis);
        const cred = pickPrimary(variants);
        const catalogKey = vis.credentialName?.toLowerCase().trim();
        const catalogVariants = catalogKey ? catalogVariantsByName.get(catalogKey) : undefined;

        // Parse credential fields from JSON string
        let credentialFields: CredentialFieldDef[] | undefined;
        if (vis.credentialFields) {
          try {
            credentialFields = typeof vis.credentialFields === "string"
              ? JSON.parse(vis.credentialFields)
              : vis.credentialFields;
          } catch { /* ignore parse errors */ }
        }

        return {
          apiId: vis.apiId,
          apiName: vis.apiName,
          iconSlug: vis.iconSlug,
          authType: vis.authType,
          credentialName: vis.credentialName,
          isActive: vis.isActive,
          toolCount: vis.toolCount,
          activeToolCount: vis.activeToolCount,
          category: vis.category,
          credentialFields,
          credential: cred,
          variants,
          catalogVariants,
        };
      });

      setIntegrations(merged);
    } catch (error) {
      console.error("Error fetching platform credentials:", error);
      addToast({
        type: "error",
        title: t("errors.fetchFailed"),
        message: t("errors.fetchFailedMessage"),
      });
    } finally {
      setLoading(false);
    }
  }, [selectedCategory, addToast, t]);

  useEffect(() => {
    if (isAuthChecking) return;
    if (isAuthenticated) {
      fetchData();
    } else {
      setLoading(false);
    }
  }, [isAuthChecking, isAuthenticated, fetchData]);

  // Filter integrations
  const filteredIntegrations = integrations.filter((item) => {
    // View mode filter - must have actual secrets configured, not just a credential shell
    if (viewMode === "configured") {
      const hasSecrets = item.credential != null && (
        item.credential.hasClientSecret ||
        item.credential.hasApiKey ||
        item.credential.hasBasicAuth ||
        item.credential.hasCustomFields
      );
      if (!hasSecrets) return false;
    }
    if (selectedCategory && item.category?.toLowerCase() !== selectedCategory.toLowerCase()) {
      return false;
    }
    if (selectedAuthType !== "all" && item.authType?.toLowerCase() !== selectedAuthType.toLowerCase()) {
      return false;
    }
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      return (
        item.apiName.toLowerCase().includes(query) ||
        item.credentialName?.toLowerCase().includes(query) ||
        item.category?.toLowerCase().includes(query)
      );
    }
    return true;
  });

  // Group by category for display
  const groupedIntegrations = filteredIntegrations.reduce((acc, item) => {
    const category = item.category || "other";
    if (!acc[category]) acc[category] = [];
    acc[category].push(item);
    return acc;
  }, {} as Record<string, MergedIntegration[]>);

  // Derive unique auth types from data
  const availableAuthTypes = Array.from(
    new Set(integrations.map((i) => i.authType?.toLowerCase()).filter(Boolean))
  ).sort();

  // Derive categories from catalog data for tabs
  const derivedCategories: CategoryInfo[] = categories.length > 0
    ? categories
    : Object.entries(
        integrations.reduce((acc, item) => {
          const cat = item.category || "other";
          acc[cat] = (acc[cat] || 0) + 1;
          return acc;
        }, {} as Record<string, number>)
      ).map(([name, count]) => ({
        slug: name.toLowerCase(),
        name,
        integrationCount: count,
      }));

  // Handlers
  const handleConfigure = (
    credential?: PlatformCredential,
    preSelect?: { iconSlug: string; apiName: string; authType: string; platformCredentialName?: string; credentialFields?: CredentialFieldDef[] },
    variants?: PlatformCredential[],
  ) => {
    setEditingCredential(credential || null);
    setEditingVariants(variants ?? null);
    setPreSelectedIntegration(preSelect || null);
    setDialogOpen(true);
  };

  const handleSave = async (data: CreatePlatformCredentialRequest) => {
    await credentialService.savePlatformCredential(data);
    addToast({
      type: "success",
      title: t("success.saved"),
      message: t("success.savedMessage", { name: data.displayName }),
    });
    await fetchData();
  };

  const handleDelete = async () => {
    // Delete is only wired for single-variant integrations. The dialog hides
    // the Delete button for multi-variant APIs, so the admin uses the
    // per-variant toggle on the card to disable individual auth methods
    // without the risk of wiping sibling rows. No new backend endpoint is
    // introduced for variant-scoped delete - scope stayed intentionally
    // contained for this fix.
    if (!editingCredential) return;
    await credentialService.deletePlatformCredential(editingCredential.integrationName);
    addToast({
      type: "success",
      title: t("success.deleted"),
      message: t("success.deletedMessage", { name: editingCredential.displayName }),
    });
    await fetchData();
  };

  // Optimistic toggles: flip the single row in local state immediately, then hit the API.
  // On failure we roll back that one row - no full fetchData() reload, so expanded cards,
  // scroll position and search filters are preserved while toggling several APIs in a row.

  const handleToggleApiVisibility = async (item: MergedIntegration, enabled: boolean) => {
    const previous = item.isActive;
    setIntegrations((prev) =>
      prev.map((i) => (i.apiId === item.apiId ? { ...i, isActive: enabled } : i))
    );
    try {
      await catalogVisibilityService.toggleApi(item.apiId, enabled);
    } catch (error) {
      console.error("Failed to toggle API visibility:", error);
      setIntegrations((prev) =>
        prev.map((i) => (i.apiId === item.apiId ? { ...i, isActive: previous } : i))
      );
      addToast({
        type: "error",
        title: t("errors.toggleFailed"),
        message: t("errors.toggleFailedMessage"),
      });
    }
  };

  const handleToggleCredentialEnabled = async (credential: PlatformCredential, enabled: boolean) => {
    // Cascade: disabling a platform credential also hides the API from regular users'
    // /app/settings/credentials. The two concepts stay distinct server-side (credential
    // isEnabled vs API isActive), but from the admin's POV one switch controls both.
    const key = credential.integrationName?.toLowerCase().trim();
    const match = integrations.find(
      (i) => i.credential && i.credential.integrationName?.toLowerCase().trim() === key
    );
    const previousIsEnabled = credential.isEnabled;
    const previousIsActive = match?.isActive ?? true;

    setIntegrations((prev) =>
      prev.map((i) =>
        i.credential && i.credential.integrationName?.toLowerCase().trim() === key
          ? { ...i, isActive: enabled, credential: { ...i.credential, isEnabled: enabled } }
          : i
      )
    );

    try {
      if (enabled) {
        await credentialService.enablePlatformCredential(credential.integrationName);
      } else {
        await credentialService.disablePlatformCredential(credential.integrationName);
      }
      if (match) {
        await catalogVisibilityService.toggleApi(match.apiId, enabled);
      }
    } catch (error) {
      console.error("Failed to toggle credential:", error);
      setIntegrations((prev) =>
        prev.map((i) =>
          i.credential && i.credential.integrationName?.toLowerCase().trim() === key
            ? {
                ...i,
                isActive: previousIsActive,
                credential: { ...i.credential, isEnabled: previousIsEnabled },
              }
            : i
        )
      );
      addToast({
        type: "error",
        title: t("errors.toggleFailed"),
        message: t("errors.toggleFailedMessage"),
      });
    }
  };

  const variantOverrideKey = (credentialName: string, variant: string) =>
    `${credentialName.toLowerCase().trim()}|${variant}`;

  // Resolve the effective enabled state for a (catalog) variant: transient
  // override first, then the saved `auth.platform_credentials` row if any,
  // else default to enabled. "No saved row" = the admin hasn't touched this
  // variant → catalog-declared → user-facing (the backend gate in
  // `PlatformCredentialService` treats absent rows as "no admin-supplied
  // secret", NOT as "disabled for the user"; matches catalog intent).
  const resolveVariantEnabled = (item: MergedIntegration, variantName: string): boolean => {
    if (!item.credentialName) return true;
    const override = variantOverrides.get(variantOverrideKey(item.credentialName, variantName));
    if (override !== undefined) return override;
    const saved = item.variants?.find((v) => v.variant === variantName);
    return saved?.isEnabled ?? true;
  };

  // Per-variant toggle. Unlike `handleToggleCredentialEnabled`, this one does
  // NOT cascade to API visibility - the admin is disabling ONE auth method
  // (e.g. bearer_token) while the API itself stays exposed via its other
  // variants (oauth2, api_key...). `variantName` is the V103 variant key
  // (oauth2, api_key, bearer_token, ...) taken from `catalogVariants` so the
  // admin can flip a variant that has no saved row yet - the backend upserts
  // a placeholder on demand.
  const handleToggleVariantEnabled = async (
    item: MergedIntegration,
    variantName: string,
    enabled: boolean,
  ) => {
    const integrationName = item.credentialName;
    if (!integrationName) return;
    const overrideKey = variantOverrideKey(integrationName, variantName);
    setVariantOverrides((prev) => {
      const next = new Map(prev);
      next.set(overrideKey, enabled);
      return next;
    });
    try {
      if (enabled) {
        await credentialService.enablePlatformCredentialVariant(integrationName, variantName);
      } else {
        await credentialService.disablePlatformCredentialVariant(integrationName, variantName);
      }
      // Refresh so the synthesized row (first toggle) lands in `variants[]`
      // with a real id and the override can drop out cleanly.
      await fetchData();
    } catch (error) {
      console.error("Failed to toggle variant:", error);
      addToast({
        type: "error",
        title: t("errors.toggleFailed"),
        message: t("errors.toggleFailedMessage"),
      });
    } finally {
      setVariantOverrides((prev) => {
        if (!prev.has(overrideKey)) return prev;
        const next = new Map(prev);
        next.delete(overrideKey);
        return next;
      });
    }
  };

  const toggleCardExpand = (apiId: string) => {
    setExpandedCards((prev) => {
      const next = new Set(prev);
      if (next.has(apiId)) {
        next.delete(apiId);
      } else {
        next.add(apiId);
      }
      return next;
    });
  };

  // Loading skeleton - also wait for serverRoles fetch (isAuthLoading) so we
  // don't briefly render the "unauthorized" screen before hasRole resolves.
  if (isAuthChecking || isAuthLoading) {
    return (
      <div className="space-y-8 animate-pulse">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-theme-secondary rounded-full" />
          <div className="space-y-2">
            <div className="h-5 bg-theme-secondary rounded w-48" />
            <div className="h-4 bg-theme-secondary rounded w-64" />
          </div>
        </div>
        <div className="flex gap-2">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="h-8 w-24 bg-theme-secondary rounded-full" />
          ))}
        </div>
        <div className="space-y-4">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="h-24 bg-theme-secondary rounded-xl animate-pulse" />
          ))}
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="space-y-8">
        <div className="mx-auto max-w-4xl">
          <div className="min-h-[300px] flex items-center justify-center">
            <div className="text-center">
              <h1 className="text-2xl font-bold text-theme-primary mb-4">
                {tSettings('unauthorized')}
              </h1>
              <p className="text-theme-secondary mb-6">
                {tSettings('mustBeLoggedIn')}
              </p>
              <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
                <User className="w-4 h-4 mr-1" />
                {tSettings('signIn')}
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (!hasRole('ADMIN')) {
    return (
      <div className="min-h-[300px] flex items-center justify-center">
        <div className="text-center">
          <Shield className="w-10 h-10 text-theme-muted mx-auto mb-3" />
          <h2 className="text-lg font-semibold text-theme-primary mb-2">{tSettings('unauthorized')}</h2>
          <p className="text-sm text-theme-secondary">{t('errors.adminOnly') ?? "Only the platform administrator can manage credentials."}</p>
        </div>
      </div>
    );
  }

  const renderIntegrationCard = (item: MergedIntegration) => {
    // Pre-resolve per-variant state on the page side so IntegrationCard
    // stays presentational and never has to reason about the override map.
    const resolvedCatalogVariants = (item.catalogVariants ?? []).map((cv) => ({
      variant: cv.variant,
      authType: cv.authType,
      enabled: resolveVariantEnabled(item, cv.variant),
      saved: item.variants?.find((v) => v.variant === cv.variant),
    }));
    return (
      <IntegrationCard
        key={item.apiId}
        integration={item}
        onToggleApiVisibility={(enabled) => handleToggleApiVisibility(item, enabled)}
        onConfigure={item.credential
          ? () => handleConfigure(item.credential, undefined, item.variants)
          : () => handleConfigure(undefined, { iconSlug: item.iconSlug, apiName: item.apiName, authType: item.authType, platformCredentialName: item.credentialName || undefined, credentialFields: item.credentialFields }, item.variants)
        }
        onToggleCredentialEnabled={
          item.credential
            ? (enabled) => handleToggleCredentialEnabled(item.credential!, enabled)
            : undefined
        }
        catalogVariants={resolvedCatalogVariants}
        onToggleVariantEnabled={
          resolvedCatalogVariants.length > 1
            ? (variantName, enabled) => handleToggleVariantEnabled(item, variantName, enabled)
            : undefined
        }
        onManagePricing={
          item.credential?.id
            ? () => {
                setPricingCredential(item.credential!);
                setPricingModalOpen(true);
              }
            : undefined
        }
        expanded={expandedCards.has(item.apiId)}
        onToggleExpand={() => toggleCardExpand(item.apiId)}
      />
    );
  };

  return (
    <div className="space-y-6">
      {/* Toast notifications */}
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast
              key={toast.id}
              id={toast.id}
              type={toast.type}
              title={toast.title}
              message={toast.message}
              onClose={removeToast}
            />
          ))}
        </div>
      )}

      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
            <Shield className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h1 className="text-lg font-semibold text-theme-primary">{t("title")}</h1>
            <p className="text-sm text-theme-secondary">{t("subtitle")}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 text-xs font-medium">
            <Shield className="w-3.5 h-3.5" />
            {t("adminOnlyBadge")}
          </div>
          <Button variant="ghost" size="sm" onClick={fetchData} disabled={loading}>
            <RefreshCw className={`w-4 h-4 ${loading ? "animate-spin" : ""}`} />
          </Button>
          <Button onClick={() => handleConfigure()}>
            <Plus className="w-4 h-4 mr-2" />
            {t("addCredential")}
          </Button>
        </div>
      </div>

      {/* View Toggle + Search + Auth Type Filter */}
      <div className="flex flex-col sm:flex-row items-center gap-4">
        <ViewModeTabs viewMode={viewMode} onViewModeChange={setViewMode} />
        <div className="relative flex-1 w-full">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-theme-secondary" />
          <Input
            placeholder={t("searchPlaceholder")}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-10"
          />
        </div>
        {availableAuthTypes.length > 1 && (
          <Select value={selectedAuthType} onValueChange={setSelectedAuthType}>
            <SelectTrigger className="w-[180px] shrink-0">
              <Filter className="w-3.5 h-3.5 mr-2 text-theme-secondary" />
              <SelectValue placeholder={t("filter.authType")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t("filter.allTypes")}</SelectItem>
              {availableAuthTypes.map((type) => {
                const key = `filter.authTypes.${type}` as any;
                return (
                  <SelectItem key={type} value={type}>
                    {t.has(key) ? t(key) : type.toUpperCase()}
                  </SelectItem>
                );
              })}
            </SelectContent>
          </Select>
        )}
      </div>

      {/* Category Tabs - only show when there are 2+ real categories */}
      {derivedCategories.length > 1 && (
        <CategoryTabs
          categories={derivedCategories}
          selectedCategory={selectedCategory}
          onSelectCategory={setSelectedCategory}
          loading={loading}
        />
      )}

      {/* Integrations List */}
      {loading ? (
        <div className="space-y-4">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="h-24 bg-theme-secondary rounded-xl animate-pulse" />
          ))}
        </div>
      ) : filteredIntegrations.length === 0 ? (
        <div className="text-center py-12">
          <Shield className="w-12 h-12 mx-auto mb-4 text-theme-secondary opacity-50" />
          <p className="text-theme-secondary">
            {searchQuery
              ? t("noSearchResults")
              : viewMode === "configured"
                ? t("noConfiguredCredentials")
                : t("noCredentials")}
          </p>
          {!searchQuery && viewMode === "configured" && (
            <Button variant="outline" onClick={() => setViewMode("all")} className="mt-4">
              {t("viewMode.allIntegrations")}
            </Button>
          )}
          {!searchQuery && viewMode === "all" && (
            <Button onClick={() => handleConfigure()} className="mt-4">
              <Plus className="w-4 h-4 mr-2" />
              {t("addCredential")}
            </Button>
          )}
        </div>
      ) : selectedCategory === null && derivedCategories.length > 1 ? (
        // Show grouped by category (only when multiple categories exist)
        <div className="space-y-8">
          {Object.entries(groupedIntegrations).map(([category, items]) => (
            <div key={category}>
              <h2 className="text-sm font-medium text-theme-secondary mb-3 capitalize">
                {category} ({items.length})
              </h2>
              <div className="space-y-3">
                {items.map(renderIntegrationCard)}
              </div>
            </div>
          ))}
        </div>
      ) : (
        // Show flat list
        <div className="space-y-3">
          {filteredIntegrations.map(renderIntegrationCard)}
        </div>
      )}

      {/* Credential Form Dialog */}
      <CredentialFormDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        credential={editingCredential}
        variants={editingVariants ?? undefined}
        availableIntegrations={availableIntegrations}
        preSelectedIntegration={preSelectedIntegration}
        onSave={handleSave}
        onDelete={editingCredential ? handleDelete : undefined}
        onError={(message) => addToast({
          type: "error",
          title: t("errors.saveFailed"),
          message,
        })}
      />

      {/* Pricing Modal - stays on this page so admins can publish
          multiple versions and inspect history without navigating away. */}
      <PricingModal
        open={pricingModalOpen}
        onOpenChange={setPricingModalOpen}
        credential={pricingCredential}
        onNotify={(type, title, message) => addToast({ type, title, message: message ?? "" })}
      />
    </div>
  );
}

type ViewMode = "all" | "configured";

function ViewModeTabs({ viewMode, onViewModeChange }: { viewMode: ViewMode; onViewModeChange: (mode: ViewMode) => void }) {
  const t = useTranslations("platformCredentials");
  const containerRef = useRef<HTMLDivElement>(null);
  const [sliderStyle, setSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  const tabs: { id: ViewMode; label: string; icon: typeof List }[] = [
    { id: "all", label: t("viewMode.allIntegrations"), icon: List },
    { id: "configured", label: t("viewMode.configuredOnly"), icon: Settings },
  ];

  useEffect(() => {
    const updateSlider = () => {
      if (!containerRef.current) return;
      const activeButton = containerRef.current.querySelector(`[data-tab-id="${viewMode}"]`) as HTMLButtonElement;
      if (activeButton) {
        const containerRect = containerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };
    updateSlider();
    window.addEventListener("resize", updateSlider);
    return () => window.removeEventListener("resize", updateSlider);
  }, [viewMode]);

  return (
    <div className="max-w-full overflow-x-auto scrollbar-hide -mx-1 px-1">
      <div className="relative inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-full w-max" ref={containerRef}>
        <div
          className="absolute top-1.5 bottom-1.5 rounded-full bg-[var(--bg-primary)] transition-all duration-300 ease-out"
          style={{
            left: sliderStyle.left,
            width: sliderStyle.width,
            opacity: sliderStyle.width ? 1 : 0,
          }}
        />
        {tabs.map((tab) => (
          <button
            key={tab.id}
            data-tab-id={tab.id}
            type="button"
            onClick={() => onViewModeChange(tab.id)}
            title={tab.label}
            className={cn(
              "relative z-10 flex flex-shrink-0 items-center gap-2 px-3 sm:px-4 py-2 rounded-full text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
              viewMode === tab.id
                ? "text-[var(--text-primary)]"
                : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
            )}
          >
            <tab.icon className={cn("w-4 h-4 flex-shrink-0 transition-colors duration-200", viewMode === tab.id ? "text-[var(--text-primary)]" : "text-current")} />
            <span className="hidden sm:inline whitespace-nowrap">{tab.label}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
