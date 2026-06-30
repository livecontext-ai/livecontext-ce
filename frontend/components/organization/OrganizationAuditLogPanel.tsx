"use client";

/**
 * Audit log panel for Settings>Organization (PR-4b frontend).
 *
 * Eagerly fetches the first page on mount (OWNER/ADMIN only) and renders
 * nothing when the workspace has no audit events - keeps the page clean
 * for fresh workspaces. Once events exist, the panel always shows.
 *
 * Backend endpoint: GET /api/organizations/{orgId}/audit-log (PR-4b).
 * Only OWNER/ADMIN can read it ; for MEMBER/VIEWER the panel renders
 * nothing (gated by the caller via currentUserRole).
 */

import { useCallback, useEffect, useState } from "react";
import {
  organizationApi,
  type AuditLogEntry,
  type OrganizationRole,
} from "@/lib/api/organization-api";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ScrollText, RefreshCw } from "lucide-react";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";

interface Props {
  orgId: string;
  currentUserRole: OrganizationRole | undefined;
}

const PAGE_SIZE = 25;

export default function OrganizationAuditLogPanel({ orgId, currentUserRole }: Props) {
  const canRead = currentUserRole === "OWNER" || currentUserRole === "ADMIN";

  const [loading, setLoading] = useState(canRead);
  const [error, setError] = useState<string | null>(null);
  const [items, setItems] = useState<AuditLogEntry[]>([]);
  const [userNames, setUserNames] = useState<Record<string, string>>({});
  const [totalCount, setTotalCount] = useState(0);
  const [page, setPage] = useState(0);
  const [category, setCategory] = useState<string>("");
  // Gate for rendering - set once the first unfiltered fetch confirms the
  // workspace has events. Keeps the section visible after the user applies
  // a filter that returns zero matches.
  const [hasAnyEvents, setHasAnyEvents] = useState(false);
  // Distinguish "still loading the initial probe" from "probe done, empty".
  // Without this, we'd render the section header during the cold load,
  // only to unmount it when the probe resolves with totalCount=0.
  const [initialized, setInitialized] = useState(false);

  const fetchPage = useCallback(
    async (p: number, cat: string) => {
      setLoading(true);
      setError(null);
      try {
        const result = await organizationApi.getAuditLog(orgId, {
          category: cat || undefined,
          page: p,
          size: PAGE_SIZE,
        });
        setItems(result.items);
        setUserNames(result.userNames ?? {});
        setTotalCount(result.totalCount);
        setPage(result.page);
        if (!cat && result.totalCount > 0) {
          setHasAnyEvents(true);
        }
      } catch (e: unknown) {
        setError(e instanceof Error ? e.message : "Failed to load audit log");
      } finally {
        setLoading(false);
        setInitialized(true);
      }
    },
    [orgId],
  );

  // Eager initial probe - runs once per orgId for OWNER/ADMIN.
  useEffect(() => {
    if (!canRead) {
      setInitialized(true);
      setLoading(false);
      return;
    }
    fetchPage(0, "");
    // We intentionally do not depend on `fetchPage` to avoid double-fetches
    // when downstream state changes. orgId change re-runs the probe.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orgId, canRead]);

  if (!canRead) return null;
  // Wait for the initial probe before deciding whether to render. Avoids a
  // flash of the section header that then disappears on empty workspaces.
  if (!initialized) return null;
  // Hide entirely when the workspace has never produced audit events.
  if (!hasAnyEvents) return null;

  const totalPages = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));

  return (
    <section>
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
            <ScrollText className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-theme-primary">Audit log</h2>
            <p className="text-sm text-theme-secondary">
              {totalCount > 0
                ? `${totalCount} event${totalCount === 1 ? "" : "s"} recorded`
                : "Recent organization activity"}
            </p>
          </div>
        </div>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => fetchPage(page, category)}
          disabled={loading}
          title="Refresh"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
        </Button>
      </div>

      <div className="space-y-3">
        <div className="flex items-center gap-2">
          <label htmlFor="audit-category" className="text-sm text-theme-secondary">
            Filter:
          </label>
          <select
            id="audit-category"
            className="w-full max-w-xs px-3 py-2 text-sm rounded-lg border border-theme bg-theme-primary text-theme-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]"
            value={category}
            onChange={(e) => {
              setCategory(e.target.value);
              fetchPage(0, e.target.value);
            }}
          >
            <option value="">All categories</option>
            <option value="ORG_MEMBER_INVITED">Member invited</option>
            <option value="ORG_INVITE_ACCEPTED">Invite accepted</option>
            <option value="ORG_INVITE_CANCELLED">Invite cancelled</option>
            <option value="ORG_MEMBER_REMOVED">Member removed</option>
            <option value="ORG_MEMBER_LEFT">Member left</option>
            <option value="ORG_ROLE_CHANGED">Role changed</option>
            <option value="ORG_OWNERSHIP_TRANSFERRED">Ownership transferred</option>
            <option value="ORG_DELETED">Organization deleted</option>
            <option value="ORG_QUOTA_CAP_SET">Quota cap set</option>
            <option value="ORG_QUOTA_CAP_REMOVED">Quota cap removed</option>
            <option value="ORG_QUOTA_CAP_EXCEEDED">Quota cap exceeded</option>
          </select>
        </div>

        {error && (
          <p className="text-xs text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50 rounded p-2">
            {error}
          </p>
        )}

        {loading && items.length === 0 ? (
          <div className="space-y-2">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : items.length === 0 ? (
          <p className="text-sm text-theme-secondary italic">
            No events match the current filter.
          </p>
        ) : (
          <ul className="divide-y divide-slate-100 dark:divide-slate-800 border border-slate-200 dark:border-slate-700/50 rounded-xl overflow-hidden">
            {items.map((event) => (
              <li key={event.id} className="px-3 py-2 text-xs flex items-start gap-3">
                <span className="font-mono text-theme-muted shrink-0 w-44">
                  {formatUtcDateTime(event.createdAt)}
                </span>
                <span className="font-medium text-theme-primary shrink-0 w-56">
                  {prettyType(event.eventType)}
                </span>
                <span className="text-theme-secondary break-words">
                  {summarise(event, userNames)}
                </span>
              </li>
            ))}
          </ul>
        )}

        {totalPages > 1 && (
          <div className="flex items-center justify-between pt-2">
            <span className="text-xs text-theme-muted">
              Page {page + 1} of {totalPages}
            </span>
            <div className="flex gap-1">
              <Button
                size="sm"
                variant="outline"
                onClick={() => fetchPage(page - 1, category)}
                disabled={loading || page === 0}
              >
                Previous
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => fetchPage(page + 1, category)}
                disabled={loading || page >= totalPages - 1}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

function prettyType(t: string): string {
  // ORG_MEMBER_INVITED → "Member invited"
  return t
    .replace(/^ORG_/, "")
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/^./, (c) => c.toUpperCase());
}

function summarise(event: AuditLogEntry, userNames: Record<string, string>): string {
  const d = event.eventData ?? {};
  // Resolve a user id to its display name; fall back to "user #id" when the
  // name isn't in the page's map (e.g. a deleted user), or "?" when absent.
  const nameOf = (id: unknown): string => {
    if (id === null || id === undefined) return "?";
    return userNames[String(id)] ?? `user #${id}`;
  };
  const actor = event.actorUserId ? `by ${nameOf(event.actorUserId)}` : "(system)";
  switch (event.eventType) {
    case "ORG_MEMBER_INVITED":
      return `${d.email ?? "?"} as ${d.role ?? "?"} ${actor}`;
    case "ORG_INVITE_ACCEPTED":
      return `${d.email ?? "?"} (${d.autoAccepted ? "auto-accepted" : "via email link"}) ${actor}`;
    case "ORG_INVITE_CANCELLED":
      return `${d.email ?? "?"} ${actor}`;
    case "ORG_MEMBER_REMOVED":
      return `${nameOf(d.targetUserId)} (${d.targetRole ?? "?"}) ${actor}`;
    case "ORG_MEMBER_LEFT":
      return `(role=${d.role ?? "?"}, wasDefault=${d.wasDefault ? "yes" : "no"}) ${actor}`;
    case "ORG_ROLE_CHANGED":
      return `${nameOf(d.targetUserId)}: ${d.oldRole ?? "?"} → ${d.newRole ?? "?"} ${actor}`;
    case "ORG_OWNERSHIP_TRANSFERRED":
      return `${nameOf(d.previousOwnerUserId)} → ${nameOf(d.newOwnerUserId)}`;
    case "ORG_DELETED":
      return `${d.orgName ?? "(unnamed)"} ${actor}`;
    case "ORG_QUOTA_CAP_SET": {
      // PR11c - payload: {targetUserId, oldPeriodCredits, newPeriodCredits,
      //   oldPeriodStorageBytes, newPeriodStorageBytes,
      //   oldPeriodLlmTokens, newPeriodLlmTokens, isNew}
      const fmt = (v: unknown) => (v === null || v === undefined ? "∅" : String(v));
      const newAction = d.isNew ? "set" : "updated";
      return `${nameOf(d.targetUserId)} cap ${newAction} `
        + `[credits ${fmt(d.oldPeriodCredits)}→${fmt(d.newPeriodCredits)}, `
        + `storage ${fmt(d.oldPeriodStorageBytes)}→${fmt(d.newPeriodStorageBytes)}, `
        + `tokens ${fmt(d.oldPeriodLlmTokens)}→${fmt(d.newPeriodLlmTokens)}] ${actor}`;
    }
    case "ORG_QUOTA_CAP_REMOVED":
      return `${nameOf(d.targetUserId)} cap removed (rows=${d.deletedCount ?? "?"}) ${actor}`;
    case "ORG_QUOTA_CAP_EXCEEDED":
      // Reserved for future MemberQuotaService notification emission.
      return `${nameOf(d.targetUserId)} hit ${d.dimension ?? "?"} cap: ${d.consumed ?? "?"}/${d.cap ?? "?"} ${actor}`;
    default:
      return JSON.stringify(d);
  }
}
