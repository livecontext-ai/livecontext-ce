"use client";

import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { createPortal } from "react-dom";
import { useTranslations } from "next-intl";
import { Shield, ChevronDown, ChevronRight, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { orchestratorApi } from "@/lib/api/orchestrator";
import { storageApi, S3_FILES_FILTER } from "@/lib/api/storage-api";
import { projectService } from "@/lib/api/orchestrator/project.service";
import { publicationService } from "@/lib/api/orchestrator/publication.service";
import { orgAccessService, type ResourceRestriction } from "@/lib/api/orchestrator/org-access.service";
import type { OrganizationMember } from "@/lib/api/organization-api";
import FileAccessSection from "./FileAccessSection";
import ResourceAccessTriState, { type AccessLevel } from "./ResourceAccessTriState";

interface MemberAccessModalProps {
  orgId: string;
  member: OrganizationMember;
  onClose: () => void;
}

interface ResourceItem {
  id: string;
  name: string;
}

type ResourceType = "workflow" | "application" | "interface" | "agent" | "datasource" | "project" | "file" | "skill";

const RESOURCE_TYPES: ResourceType[] = ["workflow", "application", "interface", "agent", "datasource", "project", "file", "skill"];

/** Page size + safety cap for a full resource sweep, so a member can be restricted
 *  on ANY resource - not just the first page. Caps total to SWEEP_PAGE * MAX_SWEEP_PAGES. */
const SWEEP_PAGE = 100;
const MAX_SWEEP_PAGES = 50;

/** Sweep EVERY real S3 file id in the workspace (paging past the 100-row server cap) so
 *  "Block all" can deny the whole set - not just the loaded page. s3Only hides the
 *  observability TEXT blobs (tool_call_result.txt, …) exactly like the Files page. */
export async function fetchAllFiles(): Promise<ResourceItem[]> {
  const out: ResourceItem[] = [];
  for (let page = 0; page < MAX_SWEEP_PAGES; page++) {
    const res = await storageApi.getExplorerEntries({ page, size: SWEEP_PAGE, ...S3_FILES_FILTER });
    const content = res.content || [];
    for (const f of content) out.push({ id: f.id, name: f.fileName || f.contentType || f.id });
    const total = res.totalPages ?? 0;
    if (content.length < SWEEP_PAGE || page + 1 >= total) break;
  }
  return out;
}

/** Load EVERY workflow (paging past the 100-row list cap) so "Block all" covers the
 *  whole type, not just the first 100 - the other resource types already return in full. */
export async function fetchAllWorkflows(): Promise<ResourceItem[]> {
  const out: ResourceItem[] = [];
  for (let page = 0; page < MAX_SWEEP_PAGES; page++) {
    const res = await orchestratorApi.getWorkflowsPage({ page, size: SWEEP_PAGE });
    const wf = res.workflows || [];
    for (const w of wf) out.push({ id: w.id, name: w.name });
    if (wf.length < SWEEP_PAGE || out.length >= (res.totalCount ?? out.length)) break;
  }
  return out;
}

/** Load EVERY application the workspace owns - own published-as-application publications PLUS
 *  acquired ones - keyed by PUBLICATION id. That is the identity /app/applications shows AND the
 *  exact id the "application" deny-list is keyed on server-side: publication-service filters the
 *  my/acquired lists and gates writes by publication id, and ProjectService restricts project
 *  applications by publication id. Deduped by publication id (own-published wins, matching
 *  /app/applications). Pages past the 100-row cap so "Block all" covers the whole set. */
export async function fetchAllApplications(): Promise<ResourceItem[]> {
  const byId = new Map<string, string>(); // publicationId -> display name
  // 1. Own published-as-application publications (applicationOnly drops standalone agent/table/… pubs).
  for (let page = 0; page < MAX_SWEEP_PAGES; page++) {
    const res = await publicationService.getMyPublicationsPage({ applicationOnly: true, page, size: SWEEP_PAGE });
    const items = res.items || [];
    for (const p of items) {
      if (p.id) byId.set(String(p.id), p.title || String(p.id));
    }
    if (items.length < SWEEP_PAGE || byId.size >= (res.totalCount ?? byId.size)) break;
  }
  // 2. Acquired applications, keyed by their source publication id (own-published already won above).
  for (let page = 0; page < MAX_SWEEP_PAGES; page++) {
    const res = await publicationService.getAcquiredApplicationsPage({ page, size: SWEEP_PAGE });
    const items = res.items || [];
    for (const a of items) {
      if (a.sourcePublicationId && !byId.has(String(a.sourcePublicationId))) {
        byId.set(String(a.sourcePublicationId), a.name || a.publication?.title || String(a.sourcePublicationId));
      }
    }
    if (items.length < SWEEP_PAGE) break;
  }
  return Array.from(byId, ([id, name]) => ({ id, name }));
}

export default function MemberAccessModal({ orgId, member, onClose }: MemberAccessModalProps) {
  const t = useTranslations("settings");
  const tFiles = useTranslations("files");
  const [mounted, setMounted] = useState(false);

  const [restrictions, setRestrictions] = useState<ResourceRestriction[]>([]);
  const [resources, setResources] = useState<Record<ResourceType, ResourceItem[]>>({
    workflow: [],
    application: [],
    interface: [],
    agent: [],
    datasource: [],
    project: [],
    file: [],
    skill: [],
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  // Lazy loading: resource lists (esp. files - a full paginated sweep) are fetched only when
  // their section is first expanded, not all-at-once on open. loadedRef tracks completed types
  // synchronously (avoids a double-fetch race); loadingTypes drives the per-section spinner.
  const loadedRef = useRef<Set<ResourceType>>(new Set());
  const [loadingTypes, setLoadingTypes] = useState<Set<ResourceType>>(new Set());
  const [expandedSections, setExpandedSections] = useState<Set<ResourceType>>(new Set(["workflow"]));
  const [searchTerms, setSearchTerms] = useState<Record<ResourceType, string>>({
    workflow: "",
    application: "",
    interface: "",
    agent: "",
    datasource: "",
    project: "",
    file: "",
    skill: "",
  });

  // Track restricted IDs per resource type (local state for toggling). A member is
  // restricted from a resource if its id is in restrictedIds[type]. For files the
  // restriction can be read-only (id also in readOnlyIds.file) vs full deny.
  const [restrictedIds, setRestrictedIds] = useState<Record<ResourceType, Set<string>>>({
    workflow: new Set(),
    application: new Set(),
    interface: new Set(),
    agent: new Set(),
    datasource: new Set(),
    project: new Set(),
    file: new Set(),
    skill: new Set(),
  });
  const [readOnlyIds, setReadOnlyIds] = useState<Record<ResourceType, Set<string>>>({
    workflow: new Set(),
    application: new Set(),
    interface: new Set(),
    agent: new Set(),
    datasource: new Set(),
    project: new Set(),
    file: new Set(),
    skill: new Set(),
  });

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Load ONLY the member's existing restrictions up-front (cheap) - these drive the restricted
  // sets, per-section badges and Save. The actual resource lists are fetched lazily per section.
  const fetchRestrictions = useCallback(async () => {
    try {
      setLoading(true);
      const restrictionData = await orgAccessService.getMemberRestrictions(orgId, String(member.userId));
      setRestrictions(restrictionData);

      const restricted: Record<ResourceType, Set<string>> = {
        workflow: new Set(), application: new Set(), interface: new Set(), agent: new Set(),
        datasource: new Set(), project: new Set(), file: new Set(), skill: new Set(),
      };
      const readOnly: Record<ResourceType, Set<string>> = {
        workflow: new Set(), application: new Set(), interface: new Set(), agent: new Set(),
        datasource: new Set(), project: new Set(), file: new Set(), skill: new Set(),
      };
      for (const r of restrictionData) {
        const type = r.resourceType as ResourceType;
        if (restricted[type]) {
          restricted[type].add(r.resourceId);
          if (r.permission === "READ") readOnly[type].add(r.resourceId);
        }
      }
      setRestrictedIds(restricted);
      setReadOnlyIds(readOnly);
    } catch (err) {
      console.error("Failed to fetch member restrictions:", err);
    } finally {
      setLoading(false);
    }
  }, [orgId, member.userId]);

  // Lazily fetch ONE resource type's items the first time its section is opened. Idempotent
  // (loadedRef guards re-fetch); on error the type is un-marked so a later expand can retry.
  const loadResourceType = useCallback(async (type: ResourceType) => {
    if (loadedRef.current.has(type)) return;
    loadedRef.current.add(type);
    setLoadingTypes((prev) => new Set(prev).add(type));
    try {
      let items: ResourceItem[] = [];
      switch (type) {
        case "workflow":
          items = await fetchAllWorkflows();
          break;
        case "application":
          items = await fetchAllApplications();
          break;
        case "interface":
          items = (await orchestratorApi.getInterfaces()).map((i: { id: string; name: string }) => ({ id: i.id, name: i.name }));
          break;
        case "agent":
          items = (await orchestratorApi.getAgents()).map((a: { id: string; name: string }) => ({ id: a.id, name: a.name }));
          break;
        case "datasource":
          items = (await orchestratorApi.getDataSources()).map((d: { id: string | number; name: string }) => ({ id: String(d.id), name: d.name }));
          break;
        case "project":
          items = (await projectService.getProjects()).map((p) => ({ id: p.id, name: p.name }));
          break;
        case "skill":
          items = (await orchestratorApi.getSkills()).map((s: { id: string; name: string }) => ({ id: s.id, name: s.name }));
          break;
        // 'file' is handled by FileAccessSection (paginated + infinite scroll), never here.
      }
      setResources((prev) => ({ ...prev, [type]: items }));
    } catch (err) {
      console.error(`Failed to load ${type} resources:`, err);
      loadedRef.current.delete(type);
    } finally {
      setLoadingTypes((prev) => {
        const next = new Set(prev);
        next.delete(type);
        return next;
      });
    }
  }, []);

  // On open: load restrictions, then eagerly hydrate only the initially-expanded section(s).
  useEffect(() => {
    fetchRestrictions();
    for (const type of expandedSections) loadResourceType(type);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchRestrictions, loadResourceType]);

  const toggleSection = (type: ResourceType) => {
    const willExpand = !expandedSections.has(type);
    setExpandedSections((prev) => {
      const next = new Set(prev);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
    // Files self-load (paginated/infinite-scroll) inside FileAccessSection via its `expanded` prop.
    if (willExpand && type !== "file") loadResourceType(type);
  };

  // Tri-state for ANY resource type: 'full' (no restriction) | 'read' (read-only,
  // write-blocked) | 'deny' (fully blocked). Backend enforces READ as read-only for every
  // type, so all types expose the same control.
  const getLevel = (type: ResourceType, resourceId: string): AccessLevel => {
    if (!restrictedIds[type].has(resourceId)) return "full";
    return readOnlyIds[type].has(resourceId) ? "read" : "deny";
  };

  const setLevel = (type: ResourceType, resourceId: string, level: AccessLevel) => {
    setRestrictedIds((prev) => {
      const next = { ...prev };
      const set = new Set(next[type]);
      if (level === "full") set.delete(resourceId);
      else set.add(resourceId);
      next[type] = set;
      return next;
    });
    setReadOnlyIds((prev) => {
      const next = { ...prev };
      const set = new Set(next[type]);
      if (level === "read") set.add(resourceId);
      else set.delete(resourceId);
      next[type] = set;
      return next;
    });
  };

  // Bulk per-type controls. "Block all" = DENY every resource of the type (full no-access, the
  // inverse of "Allow all" which clears every restriction). Files are paginated, so "Block all"
  // sweeps EVERY file id on demand (s3Only) - not just the loaded page.
  const blockAll = async (type: ResourceType) => {
    const ids =
      type === "file"
        ? (await fetchAllFiles()).map((r) => r.id)
        : resources[type].map((r) => r.id);
    setRestrictedIds((prev) => ({ ...prev, [type]: new Set(ids) }));
    // Block = full DENY, so drop any read-only flags for this type.
    setReadOnlyIds((prev) => ({ ...prev, [type]: new Set<string>() }));
  };

  const allowAll = (type: ResourceType) => {
    setRestrictedIds((prev) => ({ ...prev, [type]: new Set<string>() }));
    setReadOnlyIds((prev) => ({ ...prev, [type]: new Set<string>() }));
  };

  const handleSave = async () => {
    try {
      setSaving(true);
      // Save restrictions for each resource type, stamping the per-resource permission
      // (READ for read-only files, DENY otherwise).
      await Promise.all(
        RESOURCE_TYPES.map((type) => {
          const ids = Array.from(restrictedIds[type]);
          const permissions: Record<string, "DENY" | "READ"> = {};
          for (const id of ids) {
            permissions[id] = readOnlyIds[type].has(id) ? "READ" : "DENY";
          }
          return orgAccessService.setRestrictions(orgId, String(member.userId), type, ids, permissions);
        })
      );
      onClose();
    } catch (err) {
      console.error("Failed to save restrictions:", err);
    } finally {
      setSaving(false);
    }
  };

  const totalRestrictions = useMemo(() => {
    return RESOURCE_TYPES.reduce((sum, type) => sum + restrictedIds[type].size, 0);
  }, [restrictedIds]);

  const getFilteredResources = (type: ResourceType) => {
    const search = searchTerms[type].toLowerCase();
    if (!search) return resources[type];
    return resources[type].filter((r) => r.name.toLowerCase().includes(search));
  };

  const getTypeLabel = (type: ResourceType) => {
    switch (type) {
      case "workflow": return "Workflows";
      case "application": return "Applications";
      case "interface": return "Interfaces";
      case "agent": return "Agents";
      case "datasource": return "Tables";
      case "project": return "Projects";
      case "file": return tFiles("title");
      case "skill": return "Skills";
    }
  };

  if (!mounted) return null;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="max-w-lg w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[80vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="p-6">
          <div className="flex flex-col items-center text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mb-4">
              <Shield className="h-7 w-7 text-theme-primary" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary">
              {t("organization.accessTitle", { name: member.displayName })}
            </h2>
            <p className="text-sm text-theme-secondary mt-1">
              {t("organization.accessDescription")}
            </p>
          </div>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-6 pb-2 space-y-3">
          {loading ? (
            <div className="flex items-center justify-center py-8">
              <div className="animate-spin rounded-full h-6 w-6 border-b-2 border-[var(--accent-primary)]" />
            </div>
          ) : (
            RESOURCE_TYPES.map((type) => {
              // Files: real server-side pagination + infinite scroll + search (s3Only) - a workspace
              // can hold thousands, so we never load them all at once.
              if (type === "file") {
                return (
                  <FileAccessSection
                    key={type}
                    label={getTypeLabel("file")!}
                    expanded={expandedSections.has("file")}
                    onToggleExpanded={() => toggleSection("file")}
                    restrictedCount={restrictedIds.file.size}
                    restrictionCountLabel={(c) => t("organization.restrictionCount", { count: c })}
                    allAccessLabel={t("organization.allAccess")}
                    searchPlaceholder={t("organization.searchPlaceholder")}
                    emptyLabel={t("organization.noRestrictions")}
                    accessFull={tFiles("accessFull")}
                    accessRead={tFiles("accessRead")}
                    accessNone={tFiles("accessNone")}
                    allowAllLabel={t("organization.allowAll")}
                    blockAllLabel={t("organization.blockAll")}
                    getLevel={(id) => getLevel("file", id)}
                    onSetLevel={(id, level) => setLevel("file", id, level)}
                    onAllowAll={() => allowAll("file")}
                    onBlockAll={() => blockAll("file")}
                  />
                );
              }
              const items = getFilteredResources(type);
              const isExpanded = expandedSections.has(type);
              const restrictedCount = restrictedIds[type].size;

              return (
                <div key={type} className="border border-theme rounded-lg overflow-hidden">
                  {/* Section header */}
                  <button
                    onClick={() => toggleSection(type)}
                    className="w-full flex items-center justify-between px-3 py-2.5 hover:bg-black/[0.02] dark:hover:bg-white/[0.02] transition-colors"
                  >
                    <div className="flex items-center gap-2">
                      {isExpanded ? (
                        <ChevronDown className="h-3.5 w-3.5 text-theme-secondary" />
                      ) : (
                        <ChevronRight className="h-3.5 w-3.5 text-theme-secondary" />
                      )}
                      <span className="text-sm font-medium text-theme-primary">{getTypeLabel(type)}</span>
                      <span className="text-xs text-theme-secondary">({resources[type].length})</span>
                    </div>
                    {restrictedCount > 0 ? (
                      <span className="text-xs px-2 py-0.5 bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 rounded-full">
                        {t("organization.restrictionCount", { count: restrictedCount })}
                      </span>
                    ) : (
                      <span className="text-xs text-emerald-600 dark:text-emerald-400">
                        {t("organization.allAccess")}
                      </span>
                    )}
                  </button>

                  {/* Section body */}
                  {isExpanded && (
                    <div className="border-t border-theme">
                      {loadingTypes.has(type) ? (
                        <div className="flex items-center justify-center py-6">
                          <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-[var(--accent-primary)]" />
                        </div>
                      ) : (
                      <>
                      {/* Bulk per-type controls - one click to grant or revoke the whole type. */}
                      <div className="flex items-center justify-end gap-2 px-3 py-1.5 border-b border-theme bg-black/[0.01] dark:bg-white/[0.01]">
                        <button
                          type="button"
                          onClick={() => allowAll(type)}
                          className="text-xs px-2 py-0.5 rounded-md text-emerald-700 dark:text-emerald-400 hover:bg-emerald-50 dark:hover:bg-emerald-900/20 transition-colors"
                        >
                          {t("organization.allowAll")}
                        </button>
                        <button
                          type="button"
                          onClick={() => blockAll(type)}
                          className="text-xs px-2 py-0.5 rounded-md text-red-700 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
                        >
                          {t("organization.blockAll")}
                        </button>
                      </div>
                      {resources[type].length > 5 && (
                        <div className="px-3 py-2 border-b border-theme">
                          <div className="relative">
                            <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 text-theme-secondary" />
                            <Input
                              value={searchTerms[type]}
                              onChange={(e) => setSearchTerms((prev) => ({ ...prev, [type]: e.target.value }))}
                              placeholder={t("organization.searchPlaceholder")}
                              className="h-7 pl-7 text-xs"
                            />
                          </div>
                        </div>
                      )}
                      <div className="max-h-48 overflow-y-auto">
                        {items.length === 0 ? (
                          <p className="text-xs text-theme-secondary px-3 py-3 text-center">
                            {t("organization.noRestrictions")}
                          </p>
                        ) : (
                          items.map((resource) => {
                            const level = getLevel(type, resource.id);
                            const nameClass = cn(
                              "text-sm truncate max-w-[260px]",
                              level === "deny"
                                ? "text-theme-secondary line-through"
                                : level === "read"
                                  ? "text-theme-secondary italic"
                                  : "text-theme-primary"
                            );
                            return (
                              <div
                                key={resource.id}
                                className="flex items-center justify-between px-3 py-2 hover:bg-black/[0.02] dark:hover:bg-white/[0.02]"
                              >
                                <span className={nameClass}>{resource.name}</span>
                                <ResourceAccessTriState
                                  level={level}
                                  onSetLevel={(lvl) => setLevel(type, resource.id, lvl)}
                                  fullLabel={t("organization.accessFull")}
                                  readLabel={t("organization.accessRead")}
                                  denyLabel={t("organization.accessNone")}
                                />
                              </div>
                            );
                          })
                        )}
                      </div>
                      </>
                      )}
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between p-6 border-t border-theme">
          <span className="text-xs text-theme-secondary">
            {totalRestrictions > 0
              ? t("organization.restricted") + `: ${totalRestrictions}`
              : t("organization.allAccess")}
          </span>
          <div className="flex gap-3">
            <Button variant="outline" onClick={onClose}>
              {t("organization.cancel")}
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {saving ? "..." : t("organization.save")}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
