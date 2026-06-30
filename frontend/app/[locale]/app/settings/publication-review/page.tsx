"use client";

import React, { useState, useEffect, useCallback } from "react";
import { ShieldAlert, Shield, ClipboardCheck, Loader2, Inbox, Bot, Workflow, Clock, Coins, Eye, Monitor, Table2, Zap } from "lucide-react";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useAuth } from "@/lib/providers/smart-providers";
import { useTranslations } from "next-intl";
import Toast, { useToast } from "@/components/Toast";
import { PageHeader } from "@/components/settings/PageHeader";
import { publicationService } from "@/lib/api/orchestrator/publication.service";
import type { WorkflowPublication, ModerationStats } from "@/lib/api/orchestrator/types";
import { Button } from "@/components/ui/button";
import PublicationComparisonView from "./components/PublicationComparisonView";
import { PublisherAvatar } from "@/components/marketplace/PublisherAvatar";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";
import { IS_CE } from "@/lib/edition";

export default function PublicationReviewPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const t = useTranslations("publicationReview");
  const tMarket = useTranslations("marketplace");
  const { toasts, addToast, removeToast } = useToast();

  const [isAdmin, setIsAdmin] = useState<boolean | null>(null);
  const [publications, setPublications] = useState<WorkflowPublication[]>([]);
  const [stats, setStats] = useState<ModerationStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [reviewingPublication, setReviewingPublication] = useState<WorkflowPublication | null>(null);

  const { hasRole } = useAuth();

  // Publication review is a PLATFORM moderation feature: every moderation
  // endpoint is gated on the platform ADMIN role (X-User-Roles, via
  // AdminRoleGuard) - see PublicationModerationController. Gate the UI on the
  // SAME platform role (hasRole reads it from /users/status, the same source
  // the gateway uses to build X-User-Roles), NOT the active org's membership
  // role. The old per-org check denied a platform admin who switched into a
  // workspace where they are a regular member ("Admin access required"),
  // contradicting the backend which would have allowed them.
  useEffect(() => {
    if (!isAuthenticated || isAuthChecking) return;
    // CE never grants access (cloud-only feature; the render also short-circuits
    // to a "cloud-only" notice), which keeps the moderation endpoints unqueried
    // in self-hosted deployments.
    setIsAdmin(!IS_CE && hasRole("ADMIN"));
  }, [isAuthenticated, isAuthChecking, hasRole]);

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      const [pendingRes, statsRes] = await Promise.all([
        publicationService.getPendingPublications(page, 20),
        publicationService.getModerationStats(),
      ]);
      setPublications(pendingRes.publications);
      setTotalPages(pendingRes.totalPages);
      setStats(statsRes);
    } catch {
      addToast({ type: "error", title: t("loadFailed"), message: "" });
    } finally {
      setLoading(false);
    }
  }, [page, addToast]);

  useEffect(() => {
    if (isAdmin) fetchData();
  }, [isAdmin, fetchData]);

  // No useOrgScopedReset here: admin access is now platform-scoped (identical in
  // every workspace) and the moderation queue is platform-global (it lists every
  // org's pending publications, not the active org's), so a workspace switch
  // changes nothing on this page - there is no org-scoped state to clear.
  // (The previous reset set isAdmin=null on switch; because serverRoles is
  // fetched once at init, the admin effect would not re-run to restore it, which
  // could strand the page on the loading skeleton.)

  const handleApproved = () => {
    setReviewingPublication(null);
    addToast({ type: "success", title: t("approved"), message: "" });
    fetchData();
  };

  const handleRejected = () => {
    setReviewingPublication(null);
    addToast({ type: "success", title: t("rejected"), message: "" });
    fetchData();
  };

  // Cloud-only feature: publication review moderates the shared marketplace,
  // which does not exist in a self-hosted (CE) deployment. The settings nav
  // entry is hiddenInCE, so this is a defensive guard for a direct URL hit -
  // CE users get a clear "cloud-only" message, never the moderation queue.
  if (IS_CE) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <ShieldAlert className="h-12 w-12 text-theme-tertiary mb-4" />
        <h2 className="text-base font-medium text-theme-primary mb-2">{t("ceUnavailable.title")}</h2>
        <p className="text-sm text-theme-secondary">{t("ceUnavailable.body")}</p>
      </div>
    );
  }

  // Loading auth
  if (isAuthChecking || isAdmin === null) {
    return (
      <div className="space-y-6">
        <div className="h-5 bg-theme-tertiary rounded w-2/3 animate-pulse" />
        {[1, 2, 3].map((i) => (
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

  // Unauthorized
  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <ShieldAlert className="h-12 w-12 text-theme-tertiary mb-4" />
        <h2 className="text-base font-medium text-theme-primary mb-2">{t("unauthorized")}</h2>
        <p className="text-sm text-theme-secondary">{t("unauthorizedDescription")}</p>
      </div>
    );
  }

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return "-";
    return formatUtcDateTime(dateStr);
  };

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <PageHeader icon={ClipboardCheck} title={t("title")} subtitle={t("subtitle")} />
        <div className="flex items-center gap-2">
          <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 text-xs font-medium">
            <Shield className="w-3.5 h-3.5" />
            {t("adminOnlyBadge")}
          </div>
          {stats && stats.pendingCount > 0 && (
            <span className="inline-flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400">
              {t("pendingCount", { count: stats.pendingCount })}
            </span>
          )}
        </div>
      </div>

      {/* Content */}
      {loading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-theme-secondary" />
        </div>
      ) : publications.length === 0 ? (
        <div className="rounded-xl border border-theme p-12 flex flex-col items-center justify-center text-center">
          <div className="w-12 h-12 bg-theme-secondary rounded-full flex items-center justify-center mb-4">
            <Inbox className="h-6 w-6 text-theme-tertiary" />
          </div>
          <h3 className="text-sm font-medium text-theme-primary mb-1">{t("noPending")}</h3>
          <p className="text-xs text-theme-secondary">{t("noPendingDescription")}</p>
        </div>
      ) : (
        <div className="space-y-4">
          {publications.map((pub) => {
            const pubType: "WORKFLOW" | "AGENT" | "TABLE" | "INTERFACE" | "SKILL" = pub.publicationType || "WORKFLOW";
            const TypeIcon = pubType === "AGENT" ? Bot
              : pubType === "TABLE" ? Table2
              : pubType === "INTERFACE" ? Monitor
              : pubType === "SKILL" ? Zap
              : Workflow;
            const typeLabel = tMarket(`resourceType.${pubType}`);

            return (
              <div
                key={pub.id}
                className="rounded-xl border border-theme p-5 hover:border-[var(--accent-primary)] transition-colors"
              >
                <div className="flex items-start gap-4">
                  {/* Icon */}
                  <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center shrink-0">
                    <TypeIcon className="h-5 w-5 text-theme-primary" />
                  </div>

                  {/* Info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <h3 className="text-sm font-semibold text-theme-primary truncate">{pub.title}</h3>
                      <span className="text-xs px-1.5 py-0.5 rounded bg-theme-secondary text-theme-secondary shrink-0">
                        {pub.visibility}
                      </span>
                    </div>

                    {pub.description && (
                      <p className="text-xs text-theme-secondary line-clamp-2 mb-2">{pub.description}</p>
                    )}

                    <div className="flex items-center gap-4 flex-wrap">
                      {/* Publisher - avatar + display name only (never email/tenant id) */}
                      <span className="flex items-center gap-1.5 text-xs text-theme-secondary">
                        <PublisherAvatar userId={pub.publisherId} name={pub.publisherName} size={16} variant="neutral" />
                        <span className="truncate">{pub.publisherName || tMarket('anonymous')}</span>
                      </span>

                      {/* Resource type - icon + label */}
                      <span className="flex items-center gap-1 text-xs text-theme-tertiary">
                        <TypeIcon className="h-3 w-3" />
                        {typeLabel}
                      </span>

                      {/* Resources */}
                      {(pub.agentCount ?? 0) > 0 && (
                        <span className="flex items-center gap-1 text-xs text-theme-tertiary">
                          <Bot className="h-3 w-3" /> {pub.agentCount}
                        </span>
                      )}
                      {(pub.workflowCount ?? 0) > 0 && (
                        <span className="flex items-center gap-1 text-xs text-theme-tertiary">
                          <Workflow className="h-3 w-3" /> {pub.workflowCount}
                        </span>
                      )}
                      {(pub.creditsPerUse ?? 0) > 0 && (
                        <span className="flex items-center gap-1 text-xs text-theme-tertiary">
                          <Coins className="h-3 w-3" /> {pub.creditsPerUse}
                        </span>
                      )}

                      {/* Date */}
                      <span className="flex items-center gap-1 text-xs text-theme-tertiary">
                        <Clock className="h-3 w-3" />
                        {formatDate(pub.publishedAt)}
                      </span>
                    </div>
                  </div>

                  {/* Action */}
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setReviewingPublication(pub)}
                    className="shrink-0"
                  >
                    <Eye className="h-3.5 w-3.5 mr-1.5" />
                    {t("reviewPublication")}
                  </Button>
                </div>
              </div>
            );
          })}

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2 pt-2">
              <Button variant="outline" size="sm" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>
                {t("previousPage")}
              </Button>
              <span className="text-xs text-theme-secondary">{page + 1} / {totalPages}</span>
              <Button variant="outline" size="sm" onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}>
                {t("nextPage")}
              </Button>
            </div>
          )}
        </div>
      )}

      {/* Comparison wizard modal */}
      {reviewingPublication && (
        <PublicationComparisonView
          publication={reviewingPublication}
          onClose={() => setReviewingPublication(null)}
          onApproved={handleApproved}
          onRejected={handleRejected}
        />
      )}

      {/* Toasts */}
      <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
        {toasts.map((toast) => (
          <Toast key={toast.id} id={toast.id} type={toast.type} title={toast.title} message={toast.message} onClose={removeToast} />
        ))}
      </div>
    </div>
  );
}
