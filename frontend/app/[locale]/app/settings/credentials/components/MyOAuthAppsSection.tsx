"use client";

import React from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { CredentialsListSkeleton } from "@/components/skeletons";
import type { ToastData } from "@/components/Toast";
import { useMyOAuthApps } from "@/hooks/credentials/useMyOAuthApps";
import type { MyOAuthApp } from "@/lib/api/orchestrator/types";
import { OAuthAppCard } from "./OAuthAppCard";

interface Props {
  /** Click handler for "Add custom OAuth connection" - opens the wizard in advanced mode. */
  onAddNew: () => void;
  /**
   * Optional click handler for the per-card Edit button. When provided, each
   * OAuthAppCard renders a Pencil icon; clicking it bubbles the app up so
   * the page can open the wizard pre-filled for that integration.
   */
  onEdit?: (app: MyOAuthApp) => void;
  /**
   * Called after a successful BYOK cascade-delete so the parent can also
   * refresh sibling components that aren't on React Query (e.g.
   * {@code MyCredentialsList} below). The cascade flips dependent user
   * credentials to {@code needs_reauth}, and only this callback chain
   * surfaces those changes in-place without a route change.
   */
  onCascadeRevoke?: () => void;
  /**
   * Page-level toast handler forwarded to each {@code OAuthAppCard} so
   * success/error toasts actually render. Pass-through only - the section
   * itself does not raise toasts.
   */
  addToast?: (toast: Omit<ToastData, 'id'>) => void;
}

/**
 * "My Custom OAuth Connections" section.
 *
 * Lists tenant-owned BYOK platform_credential rows so a user can manage their
 * own OAuth apps (Google Cloud, GitHub OAuth App, etc.) registered with
 * LiveContext. Renders above {@code MyCredentialsList} in the "my" tab.
 *
 * <p><b>Discovery contract:</b> ~95% of users never register a custom OAuth
 * app. To stay invisible until needed, this component returns {@code null}
 * when {@code apps.length === 0} (no error, not loading) - the section's
 * empty state used to take ~120px of vertical space above the user's actual
 * credentials list, which inverted the page's information hierarchy. Two
 * always-available entry points cover the discovery gap:
 * <ul>
 *   <li><b>Reactive (a credential is broken):</b> the per-row "Use my own
 *     OAuth app" inline button on every {@code needs_reauth} row in
 *     {@code MyCredentialsList} opens the wizard pre-targeted at the
 *     integration in advanced/BYOK mode.</li>
 *   <li><b>Proactive (no broken credential yet):</b> the
 *     {@code CredentialWizard}'s built-in "standard ↔ advanced" mode
 *     toggle (see {@code messages.credentials.myCredentials.configureDialog.modeToggle})
 *     is reachable whenever the user configures or reconnects any
 *     credential.</li>
 * </ul>
 *
 * <p>Once {@code apps.length ≥ 1}, the full section reappears with the
 * "Add connection" CTA in its header - the user is now in the small subset
 * that actually manages BYOK apps, so the surface earns its real estate.
 */
export function MyOAuthAppsSection({ onAddNew, onEdit, onCascadeRevoke, addToast }: Props) {
  const t = useTranslations("myOAuthApps");
  const { data: apps, isLoading, error, refetch } = useMyOAuthApps();

  if (isLoading) {
    return <CredentialsListSkeleton />;
  }

  if (error) {
    return (
      <div role="alert" className="text-sm text-theme-error p-4 border border-theme rounded-xl">
        {t("error.loadFailed")}{" "}
        <Button size="sm" variant="ghost" onClick={() => refetch()}>
          {t("error.retry")}
        </Button>
      </div>
    );
  }

  const hasApps = apps && apps.length > 0;
  if (!hasApps) {
    // Stay invisible - the page-header overflow menu provides the always-on
    // entry point so users can still add their first BYOK app from here.
    return null;
  }

  return (
    <section aria-labelledby="custom-oauth-heading" className="space-y-4">
      <header className="flex items-center justify-between">
        <div>
          <h3 id="custom-oauth-heading" className="text-sm font-medium text-theme-primary">
            {t("title")}
          </h3>
          <p className="text-sm text-theme-secondary mt-1 max-w-xl">{t("subtitle")}</p>
        </div>
        <Button size="sm" variant="default" onClick={onAddNew} className="h-8 px-3">
          {t("addNew")}
        </Button>
      </header>

      <ul className="space-y-2">
        {apps!.map((app) => (
          <OAuthAppCard
            key={app.id}
            app={app}
            onChanged={() => {
              refetch();
              onCascadeRevoke?.();
            }}
            onEdit={onEdit}
            addToast={addToast}
          />
        ))}
      </ul>
    </section>
  );
}
