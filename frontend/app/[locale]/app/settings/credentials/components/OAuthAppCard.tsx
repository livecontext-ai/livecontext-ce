"use client";

import React, { useState } from "react";
import { getClientLocale } from '@/lib/utils/locale';
import { useTranslations } from "next-intl";
import { useQueryClient } from "@tanstack/react-query";
import { Pencil, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ServiceIcon } from "@/components/ui/service-icon";
import { ConfirmDeleteModal } from "@/components/datasource/ConfirmDeleteModal";
import { orchestratorApi } from "@/lib/api";
import { useToast, type ToastData } from "@/components/Toast";
import { formatDateTime } from "@/lib/utils/dateFormatters";
import type { MyOAuthApp, DeleteImpact } from "@/lib/api/orchestrator/types";

interface Props {
  app: MyOAuthApp;
  /** Callback after a successful delete so the parent list can refetch. */
  onChanged: () => void;
  /**
   * Optional click handler for the Edit button. When provided, the card
   * renders a Pencil icon next to Delete; clicking it bubbles the app up
   * so the parent page can open the wizard pre-filled for this integration.
   * The wizard's existing upsert semantics (POST /my with same
   * integrationName) handle re-saving secrets for an existing BYOK row.
   */
  onEdit?: (app: MyOAuthApp) => void;
  /**
   * Toast handler routed from the page-level container. {@code useToast} is
   * a per-component {@code useState}, so calling {@code addToast} from here
   * would otherwise land in a dead-letter array. Pass the page's
   * {@code addToast} so success/error toasts ("Deleted {name}", revoke count,
   * delete failure) actually render. When omitted, falls back to the local
   * hook (toasts will be silent - only kept for backwards compat).
   */
  addToast?: (toast: Omit<ToastData, 'id'>) => void;
}

/**
 * Single tenant-owned BYOK OAuth connection card. Renders the app name +
 * masked clientId + delete action. Delete flow:
 *
 * <ol>
 *   <li>Fetch /delete-impact to learn how many user credentials will be
 *       cascaded to needs_reauth.</li>
 *   <li>Show ConfirmDeleteModal with the count (or "couldn't fetch" fallback
 *       so the user is never silently shown zero).</li>
 *   <li>On confirm, call DELETE /my/{name} which atomically revokes deps
 *       BEFORE deleting the BYOK row.</li>
 *   <li>Invalidate the user-credentials React Query so the
 *       MyCredentialsList re-renders the now-needs_reauth rows.</li>
 * </ol>
 */
export function OAuthAppCard({ app, onChanged, onEdit, addToast: addToastProp }: Props) {
  const t = useTranslations("myOAuthApps");
  const locale = getClientLocale();
  const queryClient = useQueryClient();
  // Page-level toast handler is preferred so toasts actually render - the
  // local useToast() returns a per-component useState that has no view
  // binding here (only page.tsx renders its own toasts array).
  const { addToast: addToastLocal } = useToast();
  const addToast = addToastProp ?? addToastLocal;

  const [confirmOpen, setConfirmOpen] = useState(false);
  const [impact, setImpact] = useState<DeleteImpact | null>(null);
  const [impactError, setImpactError] = useState(false);
  const [impactLoading, setImpactLoading] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  const openDeleteFlow = async () => {
    setConfirmOpen(true);
    setImpact(null);
    setImpactError(false);
    setImpactLoading(true);
    try {
      const result = await orchestratorApi.getDeleteImpact(app.integrationName);
      setImpact(result);
    } catch (err) {
      console.error("Failed to fetch delete impact:", err);
      setImpactError(true);
    } finally {
      setImpactLoading(false);
    }
  };

  const handleConfirmDelete = async () => {
    setIsDeleting(true);
    try {
      const result = await orchestratorApi.deleteMyOAuthApp(app.integrationName);
      addToast({
        type: "success",
        title: t("toast.deleted", { name: app.displayName }),
        message:
          result.revokedCredentialCount > 0
            ? t("toast.revokedCount", { count: result.revokedCredentialCount })
            : "",
      });
      // Invalidate React Query caches that DO subscribe under these keys:
      //  - ["my-oauth-apps"]          → useMyOAuthApps in MyOAuthAppsSection
      //  - ["user-credentials-all"]   → useCredentialCheck (chat / inspector)
      //
      // MyCredentialsList is NOT on React Query (useState + imperative
      // fetch); it gets refreshed via the `onChanged` callback chain that
      // bubbles up to page.tsx and bumps a refresh signal - see prop
      // `onCascadeRevoke` on MyOAuthAppsSection.
      queryClient.invalidateQueries({ queryKey: ["my-oauth-apps"] });
      queryClient.invalidateQueries({ queryKey: ["user-credentials-all"] });
      setConfirmOpen(false);
      onChanged();
    } catch (err) {
      console.error("Failed to delete BYOK row:", err);
      addToast({
        type: "error",
        title: t("toast.deleteFailed"),
        message: err instanceof Error ? err.message : "",
      });
    } finally {
      setIsDeleting(false);
    }
  };

  const renderModalBody = (): React.ReactNode => {
    if (impactLoading) {
      return (
        <div className="h-5 w-3/4 mx-auto bg-theme-tertiary rounded animate-pulse" />
      );
    }
    if (impactError) {
      // Never silently degrade to zero - explicitly tell the user we don't know.
      return (
        <p role="alert" className="text-sm">
          {t("deleteImpact.unknown")}
        </p>
      );
    }
    if (impact) {
      const count = impact.affectedCredentialCount;
      const suffix = impact.truncated ? "+" : "";
      return (
        <p className="text-sm">
          {count === 0
            ? t("deleteImpact.zero")
            : t("deleteImpact.cascade", { count, suffix })}
        </p>
      );
    }
    return null;
  };

  const confirmLabel = (() => {
    if (impactLoading || impactError || !impact) return t("delete");
    if (impact.affectedCredentialCount === 0) return t("delete");
    return t("deleteAndDisconnect", {
      count: impact.affectedCredentialCount,
      suffix: impact.truncated ? "+" : "",
    });
  })();

  return (
    <>
      <li className="flex items-center justify-between gap-4 p-4 border border-theme rounded-xl bg-[var(--bg-primary)]">
        <div className="flex items-center gap-3 min-w-0">
          <ServiceIcon iconSlug={app.iconSlug ?? undefined} className="h-8 w-8 shrink-0" />
          <div className="min-w-0">
            <div className="text-sm font-medium text-theme-primary truncate">
              {app.displayName}
            </div>
            <div className="text-xs text-theme-secondary flex items-center gap-2">
              <span className="uppercase">{app.authType}</span>
              {app.clientIdMasked && (
                <>
                  <span>·</span>
                  <span className="font-mono">{app.clientIdMasked}</span>
                </>
              )}
              <span>·</span>
              <span>{t("createdLine", { date: formatDateTime(app.createdAt, { locale }) })}</span>
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1">
          {onEdit && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => onEdit(app)}
              aria-label={t("editAriaLabel", { name: app.displayName })}
              className="h-8 px-3"
            >
              <Pencil className="h-4 w-4" />
            </Button>
          )}
          <Button
            size="sm"
            variant="ghost"
            onClick={openDeleteFlow}
            aria-label={t("deleteAriaLabel", { name: app.displayName })}
            className="h-8 px-3"
          >
            <Trash2 className="h-4 w-4" />
          </Button>
        </div>
      </li>

      <ConfirmDeleteModal
        isOpen={confirmOpen}
        onClose={() => (isDeleting ? undefined : setConfirmOpen(false))}
        onConfirm={handleConfirmDelete}
        title={t("deleteConfirm.title", { name: app.displayName })}
        customBody={renderModalBody()}
        confirmLabel={confirmLabel}
        isLoading={isDeleting}
      />
    </>
  );
}
