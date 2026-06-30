"use client";

import React, { useState, useEffect, useMemo, useCallback } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Search, KeyRound, RefreshCw, CheckCircle2 } from "lucide-react";
import { ServiceIcon } from "@/components/ui/service-icon";
import {
  orchestratorApi,
  CredentialTemplate,
} from "@/lib/api/orchestrator";
import { useLazyLoadObserver } from "@/app/workflows/builder/components/palette/useLazyLoadObserver";
import { useTranslations } from "next-intl";

const INITIAL_DISPLAY_COUNT = 12;
const LOAD_MORE_COUNT = 12;

// Color-coded badge classes per auth method. The color stays consistent across
// the filter dropdown values and the row badges so a user scanning the table
// can map "every purple pill = bearer token" at a glance.
const AUTH_TYPE_STYLES: Record<string, string> = {
  oauth2: "bg-blue-500/10 text-blue-600 dark:text-blue-400",
  api_key: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  apikey: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  bearer_token: "bg-purple-500/10 text-purple-600 dark:text-purple-400",
  bearer: "bg-purple-500/10 text-purple-600 dark:text-purple-400",
  basic_auth: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  basic: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  custom: "bg-slate-500/10 text-slate-600 dark:text-slate-400",
};

function authTypeStyle(authType: string): string {
  return AUTH_TYPE_STYLES[authType.toLowerCase()] ??
    "bg-theme-tertiary text-theme-secondary";
}

function authTypeLabel(authType: string): string {
  switch (authType.toLowerCase()) {
    case "oauth2":
      return "OAuth 2.0";
    case "api_key":
    case "apikey":
      return "API Key";
    case "basic":
    case "basic_auth":
      return "Basic";
    case "bearer":
    case "bearer_token":
      return "Bearer";
    default:
      return authType;
  }
}

function collectAuthTypes(template: CredentialTemplate): string[] {
  const raw =
    template.variants && template.variants.length > 0
      ? template.variants.map((v) => v.auth_type).filter(Boolean)
      : template.auth_type
        ? [template.auth_type]
        : [];
  return Array.from(new Set(raw.map((t) => t!.toLowerCase())));
}

interface AvailableCredentialsListProps {
  onConfigure: (template: CredentialTemplate) => void;
  onConfigureMultiple?: (templates: CredentialTemplate[]) => void;
}

export function AvailableCredentialsList({
  onConfigure,
  onConfigureMultiple,
}: AvailableCredentialsListProps) {
  const t = useTranslations('credentials.available');
  const [templates, setTemplates] = useState<CredentialTemplate[]>([]);
  // Set of integration keys (normalized) the current user has already configured.
  // Used to flag templates in the list with a "Configured" badge, same idea as
  // the admin platform-credentials page.
  const [configuredKeys, setConfiguredKeys] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState("");
  const [authTypeFilter, setAuthTypeFilter] = useState<string>("all");
  const [displayCount, setDisplayCount] = useState(INITIAL_DISPLAY_COUNT);
  const [selectedTemplates, setSelectedTemplates] = useState<Set<string>>(new Set());

  // Normalize an integration identifier (iconSlug, credential_name, Credential.integration)
  // to a single lowercase/trimmed key so template ↔ existing credential matching is stable.
  const normalizeKey = (raw: string | undefined | null): string =>
    (raw ?? "").toLowerCase().trim();

  // Fetch templates + existing user credentials in parallel so we can flag already-added ones.
  const fetchTemplates = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [templatesResponse, credentialsResponse] = await Promise.all([
        orchestratorApi.getCredentialTemplates({ pageSize: 1000 }),
        orchestratorApi.getCredentials({ pageSize: 1000 }).catch(() => ({ credentials: [] })),
      ]);
      setTemplates(templatesResponse.credentials || []);

      const keys = new Set<string>();
      for (const cred of credentialsResponse.credentials || []) {
        const k = normalizeKey(cred.integration);
        if (k) keys.add(k);
      }
      setConfiguredKeys(keys);
    } catch (err) {
      console.error("Error fetching credential templates:", err);
      setError("FAILED_TO_LOAD"); // i18n key handled in render
      setTemplates([]);
      setConfiguredKeys(new Set());
    } finally {
      setLoading(false);
    }
  }, []);

  // A template is considered "already added" when its iconSlug or credential_name
  // matches any of the user's existing credentials' integration field.
  const isTemplateConfigured = useCallback(
    (template: CredentialTemplate): boolean => {
      const iconKey = normalizeKey(template.icon_slug);
      const nameKey = normalizeKey(template.credential_name);
      return (iconKey && configuredKeys.has(iconKey)) || (nameKey && configuredKeys.has(nameKey));
    },
    [configuredKeys]
  );

  useEffect(() => {
    fetchTemplates();
  }, [fetchTemplates]);

  // Reset display count when filters change
  useEffect(() => {
    setDisplayCount(INITIAL_DISPLAY_COUNT);
  }, [searchTerm, authTypeFilter]);

  // Collect every auth_type the user could filter by, including variants - a multi-variant
  // API (e.g. Alchemy = api_key + bearer_token) must appear under BOTH filters, not only
  // under whichever canonical row the backend picks.
  const authTypes = useMemo(() => {
    const types = new Set<string>();
    templates.forEach((t) => {
      if (t.variants && t.variants.length > 0) {
        t.variants.forEach((v) => {
          if (v.auth_type) types.add(v.auth_type.toLowerCase());
        });
      } else if (t.auth_type) {
        types.add(t.auth_type.toLowerCase());
      }
    });
    return Array.from(types).sort();
  }, [templates]);

  // Filter templates
  const filteredTemplates = useMemo(() => {
    let result = templates;

    // Filter by search term
    if (searchTerm.trim()) {
      const term = searchTerm.toLowerCase();
      result = result.filter(
        (t) =>
          t.display_name?.toLowerCase().includes(term) ||
          t.credential_name?.toLowerCase().includes(term) ||
          t.description?.toLowerCase().includes(term)
      );
    }

    // Auth type filter checks ALL variants so a multi-variant API shows up under any of
    // its supported methods.
    if (authTypeFilter !== "all") {
      result = result.filter((t) => {
        if (t.variants && t.variants.length > 0) {
          return t.variants.some((v) => v.auth_type?.toLowerCase() === authTypeFilter);
        }
        return t.auth_type?.toLowerCase() === authTypeFilter;
      });
    }

    return result;
  }, [templates, searchTerm, authTypeFilter]);

  // Templates to display (limited)
  const displayedTemplates = useMemo(() => {
    return filteredTemplates.slice(0, displayCount);
  }, [filteredTemplates, displayCount]);

  const hasMore = displayCount < filteredTemplates.length;

  const handleLoadMore = useCallback(() => {
    setDisplayCount((prev) => prev + LOAD_MORE_COUNT);
  }, []);

  // Infinite scroll: reveal the next batch when the bottom sentinel scrolls
  // into view, instead of requiring a "Show more" click. All templates are
  // already fetched client-side (pageSize 1000), so revealing more is a
  // synchronous slice bump - `isLoading` stays false; `isInitialLoading`
  // gates the trigger only while the first fetch is in flight.
  const loadMoreRef = useLazyLoadObserver({
    enabled: true,
    hasMore,
    isLoading: false,
    isInitialLoading: loading,
    dataLength: displayedTemplates.length,
    onLoadMore: handleLoadMore,
  });

  // Selection handlers
  const toggleTemplateSelection = (id: string) => {
    setSelectedTemplates(prev => {
      const newSet = new Set(prev);
      if (newSet.has(id)) {
        newSet.delete(id);
      } else {
        newSet.add(id);
      }
      return newSet;
    });
  };

  const selectAllTemplates = () => {
    setSelectedTemplates(new Set(displayedTemplates.map(t => t.id)));
  };

  const clearTemplateSelection = () => {
    setSelectedTemplates(new Set());
  };

  if (error) {
    return (
      <div className="p-8 text-center border border-theme rounded-xl">
        <p className="text-red-500 mb-4">{t('failedToLoad')}</p>
        <Button onClick={fetchTemplates} variant="outline" className="rounded-xl">
          <RefreshCw className="mr-2 h-4 w-4" />
          {t('retry')}
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Filters */}
      <div className="flex flex-col gap-3 md:flex-row md:items-center">
        {/* Search */}
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-4 top-3.5 h-4 w-4 text-theme-secondary" />
          <Input
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder={t('searchPlaceholder')}
            autoComplete="off"
            className="pl-11 rounded-xl border border-theme bg-[var(--bg-primary)]"
          />
        </div>

        {/* Auth Type Filter */}
        <Select value={authTypeFilter} onValueChange={setAuthTypeFilter}>
          <SelectTrigger className="w-full md:w-[180px] rounded-xl border border-theme bg-[var(--bg-primary)]">
            <SelectValue placeholder={t('allTypes')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{t('allTypes')}</SelectItem>
            {authTypes.map((type) => (
              <SelectItem key={type} value={type}>
                {authTypeLabel(type)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Selection actions */}
      {selectedTemplates.size > 0 && (
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            onClick={() => {
              const selectedList = Array.from(selectedTemplates)
                .map(id => displayedTemplates.find(t => t.id === id))
                .filter((t): t is CredentialTemplate => t !== undefined);

              if (selectedList.length > 1 && onConfigureMultiple) {
                onConfigureMultiple(selectedList);
              } else if (selectedList.length === 1) {
                onConfigure(selectedList[0]);
              }
              clearTemplateSelection();
            }}
            className="rounded-full bg-black dark:bg-white text-white dark:text-black"
          >
            {t('connectCount', { count: selectedTemplates.size })}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={clearTemplateSelection}
          >
            {t('clearSelection')}
          </Button>
        </div>
      )}

      {/* Templates Table */}
      <div className="border border-theme rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-theme-tertiary border-b border-theme">
            <tr>
              <th className="px-3 py-3 text-center font-medium text-theme-primary w-12">
                <input
                  type="checkbox"
                  checked={displayedTemplates.length > 0 && displayedTemplates.every(t => selectedTemplates.has(t.id))}
                  onChange={displayedTemplates.every(t => selectedTemplates.has(t.id)) ? clearTemplateSelection : selectAllTemplates}
                  className="rounded border-theme"
                  onClick={(e) => e.stopPropagation()}
                  disabled={loading}
                />
              </th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">{t('columnIntegration')}</th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">{t('columnType')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-theme">
            {loading && templates.length === 0 ? (
              Array.from({ length: 5 }).map((_, i) => (
                <tr key={`skeleton-${i}`}>
                  <td className="px-3 py-3 w-12">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-4 mx-auto" />
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <div className="h-8 w-8 bg-theme-tertiary rounded-full animate-pulse" />
                      <div className="space-y-2">
                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-32" />
                        <div className="h-3 bg-theme-tertiary rounded animate-pulse w-48" />
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-5 bg-theme-tertiary rounded-full animate-pulse w-16" />
                  </td>
                </tr>
              ))
            ) : filteredTemplates.length === 0 ? (
              <tr>
                <td colSpan={3} className="px-4 py-12 text-center">
                  <KeyRound className="w-12 h-12 mx-auto mb-4 text-theme-muted" />
                  <p className="text-theme-secondary">
                    {searchTerm || authTypeFilter !== "all"
                      ? t('noMatchingIntegrations')
                      : t('noTemplatesAvailable')}
                  </p>
                </td>
              </tr>
            ) : (
              displayedTemplates.map((template) => {
                const configured = isTemplateConfigured(template);
                return (
                  <tr
                    key={template.id}
                    className="hover:bg-theme-tertiary/50 transition-colors cursor-pointer"
                    onClick={() => onConfigure(template)}
                  >
                    <td className="px-3 py-3 w-12">
                      <div className="flex items-center justify-center">
                        <input
                          type="checkbox"
                          checked={selectedTemplates.has(template.id)}
                          onChange={() => toggleTemplateSelection(template.id)}
                          onClick={(e) => e.stopPropagation()}
                          className="rounded border-theme cursor-pointer"
                        />
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-3">
                        <ServiceIcon
                          iconUrl={template.icon_url}
                          size="lg"
                          fallbackIcon={
                            <div className="h-8 w-8 rounded-full bg-theme-tertiary flex items-center justify-center">
                              <span className="text-sm font-bold text-theme-secondary">
                                {template.display_name?.charAt(0) || "?"}
                              </span>
                            </div>
                          }
                        />
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-theme-primary truncate">
                              {template.display_name || template.credential_name}
                            </span>
                            {configured && (
                              <span
                                className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-600 dark:text-emerald-400"
                                title={t('configuredTooltip')}
                              >
                                <CheckCircle2 className="h-3 w-3" />
                                {t('configuredBadge')}
                              </span>
                            )}
                          </div>
                          {template.description && (
                            <div className="text-sm text-theme-secondary truncate max-w-[300px]">
                              {template.description}
                            </div>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1.5">
                        {collectAuthTypes(template).map((type) => {
                          const active = authTypeFilter === type;
                          return (
                            <button
                              type="button"
                              key={type}
                              onClick={(e) => {
                                e.stopPropagation();
                                setAuthTypeFilter(active ? "all" : type);
                              }}
                              className={`text-xs px-2 py-1 rounded-full transition-all ${authTypeStyle(type)} ${active ? "ring-2 ring-offset-1 ring-current" : "hover:ring-1 hover:ring-current/40"}`}
                              title={t('filterByAuthMethod', { method: authTypeLabel(type) })}
                            >
                              {authTypeLabel(type)}
                            </button>
                          );
                        })}
                      </div>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* Infinite-scroll sentinel: the IntersectionObserver in useLazyLoadObserver
          watches this div and reveals the next batch as it scrolls into view. */}
      {hasMore && (
        <div
          ref={loadMoreRef}
          data-testid="available-templates-load-more"
          className="h-1 w-full"
          aria-hidden="true"
        />
      )}
    </div>
  );
}
