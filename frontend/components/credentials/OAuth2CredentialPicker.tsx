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
import { Search, ChevronDown, KeyRound, RefreshCw } from "lucide-react";
import {
  orchestratorApi,
  CredentialTemplate,
} from '@/lib/api/orchestrator';
import { useTranslations } from "next-intl";
import { CredentialWizard } from "./CredentialWizard";

const INITIAL_DISPLAY_COUNT = 12;
const LOAD_MORE_COUNT = 12;

export interface OAuth2CredentialPickerProps {
  /**
   * Callback when a credential is successfully created
   */
  onCredentialCreated?: (credentialId?: string) => void;
  /**
   * Callback when an error occurs
   */
  onError?: (error: string, description?: string) => void;
  /**
   * Filter templates by auth type (e.g., "oauth2", "apiKey")
   */
  authTypeFilter?: string;
  /**
   * Filter templates by integration name (partial match)
   */
  integrationFilter?: string;
  /**
   * Show only a specific template (by ID)
   */
  templateId?: string;
  /**
   * Compact mode - shows minimal UI
   */
  compact?: boolean;
  /**
   * Custom class name for the container
   */
  className?: string;
  /**
   * Show selection checkboxes
   */
  showSelection?: boolean;
  /**
   * Show search input
   */
  showSearch?: boolean;
  /**
   * Show auth type filter dropdown
   */
  showAuthTypeFilter?: boolean;
  /**
   * Maximum number of items to display initially
   */
  initialDisplayCount?: number;
  /**
   * Path to redirect after OAuth2 callback
   */
  redirectPath?: string;
}

/**
 * Reusable component for browsing and configuring OAuth2 credentials.
 *
 * @example
 * ```tsx
 * // Full-featured credential picker
 * <OAuth2CredentialPicker
 *   onCredentialCreated={(id) => console.log("Created:", id)}
 *   onError={(error) => console.error(error)}
 * />
 *
 * // Compact picker for a specific integration
 * <OAuth2CredentialPicker
 *   integrationFilter="Google"
 *   compact
 *   showSearch={false}
 * />
 *
 * // OAuth2-only picker
 * <OAuth2CredentialPicker
 *   authTypeFilter="oauth2"
 *   showAuthTypeFilter={false}
 * />
 * ```
 */
export function OAuth2CredentialPicker({
  onCredentialCreated,
  onError,
  authTypeFilter: defaultAuthTypeFilter,
  integrationFilter,
  templateId,
  compact = false,
  className = "",
  showSelection = true,
  showSearch = true,
  showAuthTypeFilter = true,
  initialDisplayCount = INITIAL_DISPLAY_COUNT,
  redirectPath,
}: OAuth2CredentialPickerProps) {
  const t = useTranslations("credentials.available");
  const [templates, setTemplates] = useState<CredentialTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState("");
  const [authTypeFilterValue, setAuthTypeFilterValue] = useState<string>(
    defaultAuthTypeFilter || "all"
  );
  const [displayCount, setDisplayCount] = useState(initialDisplayCount);
  const [selectedTemplates, setSelectedTemplates] = useState<Set<string>>(
    new Set()
  );
  const [selectedTemplate, setSelectedTemplate] =
    useState<CredentialTemplate | null>(null);
  const [isConfigureOpen, setIsConfigureOpen] = useState(false);

  // Fetch templates
  const fetchTemplates = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await orchestratorApi.getCredentialTemplates({
        pageSize: 500,
      });
      setTemplates(response.credentials || []);
    } catch (err) {
      console.error("Error fetching credential templates:", err);
      setError("FAILED_TO_LOAD");
      setTemplates([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchTemplates();
  }, [fetchTemplates]);

  // Reset display count when filters change
  useEffect(() => {
    setDisplayCount(initialDisplayCount);
  }, [searchTerm, authTypeFilterValue, initialDisplayCount]);

  // Get unique auth types for filter
  const authTypes = useMemo(() => {
    const types = new Set<string>();
    templates.forEach((t) => {
      if (t.auth_type) {
        types.add(t.auth_type.toLowerCase());
      }
    });
    return Array.from(types).sort();
  }, [templates]);

  // Filter templates
  const filteredTemplates = useMemo(() => {
    let result = templates;

    // Filter by specific template ID
    if (templateId) {
      result = result.filter((t) => t.id === templateId);
    }

    // Filter by integration name
    if (integrationFilter) {
      const term = integrationFilter.toLowerCase();
      result = result.filter(
        (t) =>
          t.display_name?.toLowerCase().includes(term) ||
          t.credential_name?.toLowerCase().includes(term)
      );
    }

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

    // Filter by auth type
    if (authTypeFilterValue !== "all") {
      result = result.filter(
        (t) => t.auth_type?.toLowerCase() === authTypeFilterValue
      );
    }

    return result;
  }, [
    templates,
    templateId,
    integrationFilter,
    searchTerm,
    authTypeFilterValue,
  ]);

  // Templates to display (limited)
  const displayedTemplates = useMemo(() => {
    return filteredTemplates.slice(0, displayCount);
  }, [filteredTemplates, displayCount]);

  const hasMore = displayCount < filteredTemplates.length;
  const remainingCount = filteredTemplates.length - displayCount;

  const handleLoadMore = () => {
    setDisplayCount((prev) => prev + LOAD_MORE_COUNT);
  };

  // Selection handlers
  const toggleTemplateSelection = (id: string) => {
    setSelectedTemplates((prev) => {
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
    setSelectedTemplates(new Set(displayedTemplates.map((t) => t.id)));
  };

  const clearTemplateSelection = () => {
    setSelectedTemplates(new Set());
  };

  const handleConfigure = (template: CredentialTemplate) => {
    setSelectedTemplate(template);
    setIsConfigureOpen(true);
  };

  if (error) {
    return (
      <div
        className={`p-8 text-center border border-theme rounded-xl ${className}`}
      >
        <p className="text-red-500 mb-4">{t("failedToLoad")}</p>
        <Button
          onClick={fetchTemplates}
          variant="outline"
          className="rounded-xl"
        >
          <RefreshCw className="mr-2 h-4 w-4" />
          {t("retry")}
        </Button>
      </div>
    );
  }

  // Compact mode - simple list with buttons
  if (compact) {
    return (
      <div className={`space-y-2 ${className}`}>
        {loading ? (
          <div className="space-y-2">
            {Array.from({ length: 3 }).map((_, i) => (
              <div
                key={`skeleton-${i}`}
                className="h-12 bg-theme-tertiary rounded-lg animate-pulse"
              />
            ))}
          </div>
        ) : filteredTemplates.length === 0 ? (
          <div className="p-4 text-center text-theme-secondary text-sm">
            {t("noTemplatesAvailable")}
          </div>
        ) : (
          displayedTemplates.map((template) => (
            <button
              key={template.id}
              onClick={() => handleConfigure(template)}
              className="w-full flex items-center gap-3 p-3 border border-theme rounded-lg hover:bg-theme-tertiary transition-colors text-left"
            >
              {template.icon_url ? (
                <img
                  src={template.icon_url}
                  alt=""
                  className="h-6 w-6 rounded object-contain bg-white p-0.5"
                  onError={(e) => {
                    (e.target as HTMLImageElement).style.display = "none";
                  }}
                />
              ) : (
                <div className="h-6 w-6 rounded-full bg-theme-tertiary flex items-center justify-center">
                  <span className="text-xs font-bold text-theme-secondary">
                    {template.display_name?.charAt(0) || "?"}
                  </span>
                </div>
              )}
              <span className="flex-1 text-sm font-medium text-theme-primary truncate">
                {template.display_name || template.credential_name}
              </span>
              <span className="text-xs px-2 py-0.5 bg-theme-tertiary rounded-full text-theme-secondary">
                {template.auth_type}
              </span>
            </button>
          ))
        )}

        {/* Credential Wizard */}
        <CredentialWizard
          template={selectedTemplate}
          open={isConfigureOpen}
          onOpenChange={(open) => {
            setIsConfigureOpen(open);
            if (!open) setSelectedTemplate(null);
          }}
          onComplete={() => onCredentialCreated?.()}
        />
      </div>
    );
  }

  // Full mode - table with filters
  return (
    <div className={`space-y-4 ${className}`}>
      {/* Filters */}
      <div className="flex flex-col gap-3 md:flex-row md:items-center">
        {/* Search */}
        {showSearch && (
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute left-4 top-3.5 h-4 w-4 text-theme-secondary" />
            <Input
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder={t("searchPlaceholder")}
              className="pl-11 rounded-xl border border-theme bg-[var(--bg-primary)]"
            />
          </div>
        )}

        {/* Auth Type Filter */}
        {showAuthTypeFilter && !defaultAuthTypeFilter && (
          <Select
            value={authTypeFilterValue}
            onValueChange={setAuthTypeFilterValue}
          >
            <SelectTrigger className="w-full md:w-[180px] rounded-xl border border-theme bg-[var(--bg-primary)]">
              <SelectValue placeholder={t("allTypes")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t("allTypes")}</SelectItem>
              {authTypes.map((type) => (
                <SelectItem key={type} value={type}>
                  {type.charAt(0).toUpperCase() + type.slice(1)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      {/* Selection actions */}
      {showSelection && selectedTemplates.size > 0 && (
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            onClick={() => {
              selectedTemplates.forEach((id) => {
                const template = displayedTemplates.find((t) => t.id === id);
                if (template) handleConfigure(template);
              });
            }}
            className="rounded-full bg-black dark:bg-white text-white dark:text-black"
          >
            {t("connectCount", { count: selectedTemplates.size })}
          </Button>
          <Button variant="ghost" size="sm" onClick={clearTemplateSelection}>
            {t("clearSelection")}
          </Button>
        </div>
      )}

      {/* Templates Table */}
      <div className="border border-theme rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-theme-tertiary border-b border-theme">
            <tr>
              {showSelection && (
                <th className="px-3 py-3 text-center font-medium text-theme-primary w-12">
                  <input
                    type="checkbox"
                    checked={
                      displayedTemplates.length > 0 &&
                      displayedTemplates.every((t) =>
                        selectedTemplates.has(t.id)
                      )
                    }
                    onChange={
                      displayedTemplates.every((t) =>
                        selectedTemplates.has(t.id)
                      )
                        ? clearTemplateSelection
                        : selectAllTemplates
                    }
                    className="rounded border-theme"
                    onClick={(e) => e.stopPropagation()}
                    disabled={loading}
                  />
                </th>
              )}
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">
                {t("columnIntegration")}
              </th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">
                {t("columnType")}
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-theme">
            {loading && templates.length === 0 ? (
              Array.from({ length: 5 }).map((_, i) => (
                <tr key={`skeleton-${i}`}>
                  {showSelection && (
                    <td className="px-3 py-3 w-12">
                      <div className="h-4 bg-theme-tertiary rounded animate-pulse w-4 mx-auto" />
                    </td>
                  )}
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
                <td
                  colSpan={showSelection ? 3 : 2}
                  className="px-4 py-12 text-center"
                >
                  <KeyRound className="w-12 h-12 mx-auto mb-4 text-theme-muted" />
                  <p className="text-theme-secondary">
                    {searchTerm || authTypeFilterValue !== "all"
                      ? t("noMatchingIntegrations")
                      : t("noTemplatesAvailable")}
                  </p>
                </td>
              </tr>
            ) : (
              displayedTemplates.map((template) => (
                <tr
                  key={template.id}
                  className="hover:bg-theme-tertiary/50 transition-colors cursor-pointer"
                  onClick={() => handleConfigure(template)}
                >
                  {showSelection && (
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
                  )}
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      {template.icon_url ? (
                        <img
                          src={template.icon_url}
                          alt=""
                          className="h-8 w-8 rounded object-contain bg-white p-0.5"
                          onError={(e) => {
                            (e.target as HTMLImageElement).style.display =
                              "none";
                          }}
                        />
                      ) : (
                        <div className="h-8 w-8 rounded-full bg-theme-tertiary flex items-center justify-center">
                          <span className="text-sm font-bold text-theme-secondary">
                            {template.display_name?.charAt(0) || "?"}
                          </span>
                        </div>
                      )}
                      <div>
                        <div className="font-medium text-theme-primary">
                          {template.display_name || template.credential_name}
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
                    <span className="text-xs px-2 py-1 bg-theme-tertiary rounded-full text-theme-secondary">
                      {template.auth_type}
                    </span>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Load More Button */}
      {hasMore && (
        <div className="flex justify-center pt-4">
          <Button
            variant="outline"
            onClick={handleLoadMore}
            className="gap-2 rounded-full"
          >
            <ChevronDown className="h-4 w-4" />
            {t("showMore", { count: remainingCount })}
          </Button>
        </div>
      )}

      {/* Credential Wizard */}
      <CredentialWizard
        template={selectedTemplate}
        open={isConfigureOpen}
        onOpenChange={(open) => {
          setIsConfigureOpen(open);
          if (!open) setSelectedTemplate(null);
        }}
        onComplete={() => onCredentialCreated?.()}
      />
    </div>
  );
}
