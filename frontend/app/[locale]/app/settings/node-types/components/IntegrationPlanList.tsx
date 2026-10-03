"use client";

import React from "react";
import { ArrowLeft, ChevronRight, Search } from "lucide-react";
import { useTranslations } from "next-intl";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import LoadingSpinner from "@/components/LoadingSpinner";
import { NodeIcon } from "@/app/workflows/builder/components/nodes/shared";
import { apiClient } from "@/lib/api";
import { PlanRequirementSelect } from "./PlanRequirementSelect";
import { urlPageIndex, useUrlSearchState, useUrlState } from "@/hooks/useUrlState";

interface CatalogApiRow {
  slug: string;
  apiName: string;
  description?: string;
  toolsCount?: number;
  iconSlug?: string;
}

interface CatalogToolRow {
  slug: string;
  name: string;
  description?: string;
  method?: string;
}

interface IntegrationPlanListProps {
  /** featureKey -> minPlan, for the whole gate. `api:` and `tool:` keys are read here. */
  requirements: Record<string, string>;
  onChangePlan: (featureKey: string, minPlan: string, label: string) => void;
  savingKeys: Set<string>;
  planOptions?: readonly string[];
}

const PAGE_SIZE = 30;

/**
 * The catalog integrations, and the plan each of their ENDPOINTS requires.
 *
 * <p><b>The endpoint is the unit that matters.</b> Publishing a video is what is
 * sold; reading a channel, listing videos and fetching comments are not, and
 * they live on the same API. So the drill-in is the primary screen and the
 * API-level control is the blunt instrument kept for the rare case where a whole
 * integration should be held back. When both exist the endpoint wins, which is
 * why an endpoint row shows its own value rather than inheriting one.
 *
 * <p>Reads the SAME lists the workflow palette reads
 * ({@code /workflow-inspector/apis} and {@code .../apis/:slug/tools}), so an
 * endpoint an admin can gate here is exactly one a builder can reach.
 */
export function IntegrationPlanList({
  requirements,
  onChangePlan,
  savingKeys,
  planOptions,
}: IntegrationPlanListProps) {
  const t = useTranslations("nodeTypeSettings");
  // The search and the page live in the address, so a reload reopens the same page of results.
  const [query, setQuery] = useUrlSearchState("q");
  const [apis, setApis] = React.useState<CatalogApiRow[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [page, setPage] = useUrlState("page", 0, { codec: urlPageIndex });
  const [hasMore, setHasMore] = React.useState(false);

  /** The integration whose endpoints are being edited, or null on the list. */
  const [openApi, setOpenApi] = React.useState<CatalogApiRow | null>(null);
  const [tools, setTools] = React.useState<CatalogToolRow[]>([]);
  const [toolsLoading, setToolsLoading] = React.useState(false);

  // The search is sent to the backend rather than filtered locally: the catalog
  // has hundreds of integrations and only one page of them is ever in memory, so
  // filtering here would search a window instead of the catalogue.
  React.useEffect(() => {
    if (openApi) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      setLoading(true);
      const params: Record<string, string | number> = { page, size: PAGE_SIZE };
      if (query.trim()) params.name = query.trim();
      apiClient
        .get<{ content?: CatalogApiRow[]; last?: boolean }>("/workflow-inspector/apis", {
          params: params as Record<string, string>,
        })
        .then((data) => {
          if (cancelled) return;
          const content = Array.isArray(data) ? (data as CatalogApiRow[]) : (data?.content ?? []);
          setApis(content);
          // The endpoint gives no total, so a page restored from the address that is past the
          // end can only be told by its emptiness: go back to the first page.
          if (content.length === 0 && page > 0) setPage(0);
          // The endpoint says whether this was the last page; the length check is only
          // the fallback for the array-shaped response it also sometimes returns.
          setHasMore(typeof data?.last === "boolean" ? !data.last : content.length >= PAGE_SIZE);
        })
        .catch(() => {
          if (cancelled) return;
          setApis([]);
          setHasMore(false);
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    }, query.trim() ? 300 : 0);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, page, openApi, setPage]);

  React.useEffect(() => {
    if (!openApi) return;
    let cancelled = false;
    setToolsLoading(true);
    apiClient
      .get<CatalogToolRow[]>(`/workflow-inspector/apis/${encodeURIComponent(openApi.slug)}/tools`)
      .then((data) => {
        if (!cancelled) setTools(Array.isArray(data) ? data : []);
      })
      .catch(() => {
        if (!cancelled) setTools([]);
      })
      .finally(() => {
        if (!cancelled) setToolsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [openApi]);

  if (openApi) {
    const apiKey = `api:${openApi.slug}`;
    return (
      <div className="space-y-4">
        <Button variant="outline" size="sm" onClick={() => setOpenApi(null)}>
          <ArrowLeft className="mr-1.5 h-3.5 w-3.5" />
          {t("integrations.back")}
        </Button>

        <div className="flex items-center gap-3 rounded-xl border border-theme p-3">
          <NodeIcon nodeId={openApi.slug} iconSlug={openApi.iconSlug} isMcp size="lg" alt={openApi.apiName} />
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium text-theme-primary">{openApi.apiName}</div>
            {/* The whole-integration control, kept but framed as the exception:
                it holds back reads as well as writes. */}
            <div className="truncate text-xs text-theme-tertiary">{t("integrations.apiLevelHint")}</div>
          </div>
          <PlanRequirementSelect
            value={requirements[apiKey] ?? null}
            onChange={(plan) => onChangePlan(apiKey, plan, openApi.apiName)}
            disabled={savingKeys.has(apiKey)}
            options={planOptions}
          />
        </div>

        {toolsLoading ? (
          <div className="flex justify-center py-10">
            <LoadingSpinner size="sm" />
          </div>
        ) : tools.length === 0 ? (
          <div className="py-12 text-center">
            <p className="text-sm text-theme-secondary">{t("noResults")}</p>
          </div>
        ) : (
          <div className="space-y-2">
            {tools.map((tool) => {
              const toolKey = `tool:${tool.slug}`;
              return (
                <div
                  key={tool.slug}
                  className="flex items-center gap-3 rounded-lg border border-theme px-3 py-2"
                >
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm text-theme-primary">{tool.name}</div>
                    <div className="truncate text-xs text-theme-tertiary">
                      {tool.method ? `${tool.method} · ` : ""}
                      {tool.slug}
                    </div>
                  </div>
                  <PlanRequirementSelect
                    value={requirements[toolKey] ?? null}
                    onChange={(plan) => onChangePlan(toolKey, plan, `${openApi.apiName} - ${tool.name}`)}
                    disabled={savingKeys.has(toolKey)}
                    options={planOptions}
                  />
                </div>
              );
            })}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-theme-tertiary" />
        <Input
          placeholder={t("integrations.searchPlaceholder")}
          value={query}
          onChange={(e) => {
            setPage(0);
            setQuery(e.target.value);
          }}
          className="pl-9 text-sm"
        />
      </div>

      <p className="text-sm text-theme-secondary">{t("integrations.hint")}</p>

      {loading ? (
        <div className="flex justify-center py-10">
          <LoadingSpinner size="sm" />
        </div>
      ) : apis.length === 0 ? (
        <div className="py-12 text-center">
          <p className="text-sm text-theme-secondary">{t("noResults")}</p>
        </div>
      ) : (
        <div className="space-y-2">
          {apis.map((api) => {
            const featureKey = `api:${api.slug}`;
            // How many of this integration's endpoints carry their own requirement.
            // Without it the list looks untouched even when every publishing endpoint
            // behind it is gated, since the API row itself stays on "Everyone".
            const gatedEndpoints = Object.keys(requirements).filter(
              (k) => k.startsWith(`tool:${api.slug}-`),
            ).length;
            return (
              <button
                type="button"
                key={api.slug}
                onClick={() => setOpenApi(api)}
                className="flex w-full items-center gap-3 rounded-xl border border-theme p-3 text-left transition-colors hover:border-[var(--accent-primary)]/50"
              >
                <NodeIcon nodeId={api.slug} iconSlug={api.iconSlug} isMcp size="lg" alt={api.apiName} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-theme-primary">{api.apiName}</div>
                  <div className="truncate text-xs text-theme-tertiary">
                    {requirements[featureKey]
                      ? t("integrations.wholeApiGated", { plan: requirements[featureKey] })
                      : gatedEndpoints > 0
                        ? t("integrations.gatedEndpoints", { count: gatedEndpoints })
                        : api.slug}
                  </div>
                </div>
                <ChevronRight className="h-4 w-4 flex-shrink-0 text-theme-tertiary" />
              </button>
            );
          })}
        </div>
      )}

      <div className="flex items-center justify-between">
        <Button
          variant="outline"
          size="sm"
          disabled={page === 0 || loading}
          onClick={() => setPage((p) => Math.max(0, p - 1))}
        >
          {t("integrations.previous")}
        </Button>
        <span className="text-xs text-theme-tertiary">{page + 1}</span>
        <Button
          variant="outline"
          size="sm"
          disabled={!hasMore || loading}
          onClick={() => setPage((p) => p + 1)}
        >
          {t("integrations.next")}
        </Button>
      </div>
    </div>
  );
}

export default IntegrationPlanList;
