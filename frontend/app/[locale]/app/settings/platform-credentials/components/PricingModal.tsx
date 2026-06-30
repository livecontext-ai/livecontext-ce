"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown, ChevronRight, Plus, Trash2 } from "lucide-react";
import { useTranslations } from "next-intl";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { credentialService } from "@/lib/api/orchestrator/credential.service";
import {
  catalogVisibilityService,
  type IntegrationVisibility,
  type ToolVisibility,
} from "@/lib/api/services/catalog-visibility.service";
import type { PlatformCredential, PricingVersion } from "@/lib/api/orchestrator/types";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";

interface OverrideRow {
  id: string;
  apiToolId: string;
  markup: string;
}

function genId(): string {
  return Math.random().toString(36).slice(2);
}

interface PricingModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  credential: PlatformCredential | null;
  onNotify: (type: "success" | "error" | "info", title: string, message?: string) => void;
}

export function PricingModal({ open, onOpenChange, credential, onNotify }: PricingModalProps) {
  const credentialId = credential?.id;
  const t = useTranslations("platformCredentials.pricing");
  const tSettings = useTranslations("settings");
  const onNotifyRef = useRef(onNotify);

  const [versions, setVersions] = useState<PricingVersion[]>([]);
  const [tools, setTools] = useState<ToolVisibility[]>([]);
  const [loading, setLoading] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [expandedVersionId, setExpandedVersionId] = useState<number | null>(null);

  // Empty string = "no API-wide default". Per-tool overrides then carry the
  // whole pricing for this version; tools not listed get a zero markup.
  const [defaultMarkup, setDefaultMarkup] = useState("");
  const [overrideRows, setOverrideRows] = useState<OverrideRow[]>([]);

  useEffect(() => {
    onNotifyRef.current = onNotify;
  }, [onNotify]);

  const toolById = useMemo(() => {
    const m = new Map<string, ToolVisibility>();
    for (const tool of tools) m.set(tool.toolId, tool);
    return m;
  }, [tools]);

  const loadAll = useCallback(async () => {
    if (!credential || !Number.isFinite(credentialId)) return;
    setLoading(true);
    try {
      const [versionsData, integrationsData] = await Promise.all([
        credentialService.listPricingVersions(credentialId!).catch(() => [] as PricingVersion[]),
        catalogVisibilityService.getIntegrations().catch(() => [] as IntegrationVisibility[]),
      ]);
      setVersions(versionsData);

      const match = integrationsData.find(
        (v) => v.credentialName?.toLowerCase().trim() === credential.integrationName.toLowerCase().trim(),
      );
      if (match) {
        const toolList = await catalogVisibilityService.getApiTools(match.apiId).catch(() => [] as ToolVisibility[]);
        setTools(toolList);
      } else {
        setTools([]);
      }
    } catch (err) {
      onNotifyRef.current("error", t("errors.fetchFailed"), err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [credential, credentialId, t]);

  // Reset + reload every time a new credential opens.
  useEffect(() => {
    if (open && credential) {
      setDefaultMarkup("");
      setOverrideRows([]);
      setExpandedVersionId(null);
      loadAll();
    }
  }, [open, credential, loadAll]);

  const addOverrideRow = () => {
    setOverrideRows((rows) => [...rows, { id: genId(), apiToolId: "", markup: "0.00" }]);
  };

  const removeOverrideRow = (id: string) => {
    setOverrideRows((rows) => rows.filter((r) => r.id !== id));
  };

  const updateOverrideRow = (id: string, patch: Partial<OverrideRow>) => {
    setOverrideRows((rows) => rows.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  };

  const copyFromVersion = (v: PricingVersion) => {
    // Null default → leave the field empty so the admin can see at a glance
    // that this version billed purely via per-tool overrides.
    setDefaultMarkup(v.defaultMarkupCredits == null ? "" : String(v.defaultMarkupCredits));
    setOverrideRows(
      Object.entries(v.overrides).map(([apiToolId, markup]) => ({
        id: genId(),
        apiToolId,
        markup,
      })),
    );
    onNotify("info", t("copiedFromVersion", { version: v.version }));
  };

  const handlePublish = async () => {
    if (!credentialId) return;

    // Empty default = "no API-wide rate, only overrides apply". Any other
    // non-numeric / negative input is a typo and gets rejected up-front.
    const trimmedDefault = defaultMarkup.trim();
    let defaultMarkupPayload: string | null = null;
    if (trimmedDefault !== "") {
      const parsed = Number(trimmedDefault);
      if (!Number.isFinite(parsed) || parsed < 0) {
        onNotify("error", t("errors.invalidDefault"));
        return;
      }
      defaultMarkupPayload = String(parsed);
    }

    const seen = new Set<string>();
    const overrides: Record<string, string> = {};
    for (const row of overrideRows) {
      const tool = row.apiToolId.trim();
      if (!tool) continue;
      if (seen.has(tool)) {
        onNotify("error", t("errors.duplicateTool"));
        return;
      }
      const m = Number(row.markup);
      if (!Number.isFinite(m) || m < 0) {
        onNotify("error", t("errors.invalidOverride", { tool }));
        return;
      }
      seen.add(tool);
      overrides[tool] = String(m);
    }

    // Backend rejects "no default + no overrides" (degenerate version), but
    // surface it here too so the admin gets an immediate, clearer error.
    if (defaultMarkupPayload === null && Object.keys(overrides).length === 0) {
      onNotify("error", t("errors.invalidDefault"));
      return;
    }

    setPublishing(true);
    try {
      const created = await credentialService.publishPricingVersion(credentialId, {
        defaultMarkupCredits: defaultMarkupPayload,
        overrides,
      });
      onNotify("success", t("success"), t("publishedVersion", { version: created.version }));
      setOverrideRows([]);
      await loadAll();
    } catch (err) {
      onNotify(
        "error",
        t("errors.publishFailed"),
        err instanceof Error ? err.message : String(err),
      );
    } finally {
      setPublishing(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {t("title")}
            {credential ? ` - ${credential.displayName}` : ""}
          </DialogTitle>
          <p className="text-sm text-theme-secondary">{t("subtitle")}</p>
        </DialogHeader>

        {loading && (
          <div className="py-8 text-center text-sm text-theme-secondary">
            {tSettings("common.loading")}
          </div>
        )}

        {!loading && !credential && (
          <div className="py-8 text-center text-sm text-theme-secondary">
            {t("errors.credentialNotFound")}
          </div>
        )}

        {!loading && credential && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <section className="border border-theme rounded-lg p-4 bg-theme-secondary">
              <h2 className="text-sm font-semibold text-theme-primary mb-3">{t("history")}</h2>
              {versions.length === 0 ? (
                <p className="text-sm text-theme-secondary">{t("noVersions")}</p>
              ) : (
                <ul className="divide-y divide-theme">
                  {versions.map((v) => {
                    const expanded = expandedVersionId === v.id;
                    const overrideCount = Object.keys(v.overrides).length;
                    return (
                      <li key={v.id} className="py-2">
                        <button
                          type="button"
                          onClick={() => setExpandedVersionId(expanded ? null : v.id)}
                          className="w-full flex items-center gap-2 text-left"
                        >
                          {expanded ? (
                            <ChevronDown className="w-4 h-4" />
                          ) : (
                            <ChevronRight className="w-4 h-4" />
                          )}
                          <span className="text-sm font-medium text-theme-primary w-12">
                            v{v.version}
                          </span>
                          <span className="text-sm text-theme-secondary flex-1">
                            {t("defaultMarkupLabel")}{" "}
                            {v.defaultMarkupCredits == null ? "-" : String(v.defaultMarkupCredits)}
                            {" · "}
                            {t("overridesLabel", { count: overrideCount })}
                          </span>
                          <span className="text-xs text-theme-muted">
                            {v.createdAt ? formatUtcDateTime(v.createdAt) : ""}
                          </span>
                        </button>
                        {expanded && (
                          <div className="mt-2 ml-6 space-y-2">
                            {v.createdBy && (
                              <p className="text-xs text-theme-muted">
                                {t("createdBy")}: {v.createdBy}
                              </p>
                            )}
                            {overrideCount > 0 ? (
                              <ul className="text-sm space-y-2">
                                {Object.entries(v.overrides).map(([toolId, markup]) => {
                                  const tool = toolById.get(toolId);
                                  const primary =
                                    tool?.toolName?.trim() ||
                                    tool?.toolSlug?.trim() ||
                                    t("unknownTool");
                                  return (
                                    <li key={toolId} className="flex items-start gap-2">
                                      <div className="flex-1 min-w-0">
                                        <div className="text-sm text-theme-primary font-medium truncate">
                                          {primary}
                                        </div>
                                        {tool?.description && (
                                          <div className="text-xs text-theme-muted line-clamp-2">
                                            {tool.description}
                                          </div>
                                        )}
                                      </div>
                                      <span className="text-theme-secondary shrink-0">{markup}</span>
                                    </li>
                                  );
                                })}
                              </ul>
                            ) : (
                              <p className="text-xs text-theme-muted">{t("noOverrides")}</p>
                            )}
                            <Button variant="outline" size="sm" onClick={() => copyFromVersion(v)}>
                              {t("copyToForm")}
                            </Button>
                          </div>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>

            <section className="border border-theme rounded-lg p-4 bg-theme-secondary">
              <h2 className="text-sm font-semibold text-theme-primary mb-3">{t("publishNew")}</h2>
              <p className="text-xs text-theme-muted mb-4">{t("warningInFlightRuns")}</p>

              <div className="space-y-3">
                <div>
                  <label className="block text-sm text-theme-primary mb-1">
                    {t("defaultMarkup")}
                  </label>
                  {/* Empty = no API-wide default; overrides then drive the
                      whole pricing. Placeholder renders as - to match how
                      the history list renders a null default. */}
                  <Input
                    type="number"
                    step="0.0001"
                    min="0"
                    value={defaultMarkup}
                    onChange={(e) => setDefaultMarkup(e.target.value)}
                    placeholder="-"
                  />
                </div>

                <div>
                  <div className="flex items-center justify-between mb-2">
                    <label className="block text-sm text-theme-primary">{t("overrides")}</label>
                    <Button variant="ghost" size="sm" onClick={addOverrideRow}>
                      <Plus className="w-3.5 h-3.5 mr-1" />
                      {t("addOverride")}
                    </Button>
                  </div>
                  {overrideRows.length === 0 && (
                    <p className="text-xs text-theme-muted">{t("noOverridesInForm")}</p>
                  )}
                  <div className="space-y-2">
                    {overrideRows.map((row) => (
                      <div
                        key={row.id}
                        className="flex flex-wrap items-start gap-2"
                      >
                        <div className="flex-1 min-w-[180px]">
                          <Select
                            value={row.apiToolId || undefined}
                            onValueChange={(v) =>
                              updateOverrideRow(row.id, { apiToolId: v })
                            }
                          >
                            <SelectTrigger className="h-10 min-h-0 rounded-md py-2">
                              <SelectValue placeholder={t("selectTool")} />
                            </SelectTrigger>
                            <SelectContent>
                              {tools.map((tool) => (
                                <SelectItem
                                  key={tool.toolId}
                                  value={tool.toolId}
                                  description={tool.description}
                                >
                                  {tool.toolName?.trim() ||
                                    tool.toolSlug?.trim() ||
                                    t("unknownTool")}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                        <Input
                          type="number"
                          step="0.0001"
                          min="0"
                          value={row.markup}
                          onChange={(e) =>
                            updateOverrideRow(row.id, { markup: e.target.value })
                          }
                          className="w-24 shrink-0"
                        />
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => removeOverrideRow(row.id)}
                          className="shrink-0"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </Button>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="pt-2">
                  <Button onClick={handlePublish} disabled={publishing}>
                    {publishing ? t("publishing") : t("publish")}
                  </Button>
                </div>
              </div>
            </section>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
