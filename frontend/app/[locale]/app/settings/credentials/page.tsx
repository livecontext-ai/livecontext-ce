"use client";

import React, { useState, useEffect } from "react";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useAuth } from "@/lib/providers/smart-providers";
import { useSearchParams } from "next/navigation";
import { KeyRound, User } from "lucide-react";
import Toast, { useToast } from "@/components/Toast";
import { CredentialTemplate } from "@/lib/api/orchestrator";
import { useCurrentOrg } from "@/lib/stores/current-org-store";
import {
  CredentialTabs,
  CredentialTab,
  MyCredentialsList,
  AvailableCredentialsList,
  MyOAuthAppsSection,
} from "./components";
import {
  CredentialWizard,
  type CredentialWizardRequirement,
} from "@/components/credentials";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/settings";
import { CredentialsListSkeleton } from "@/components/skeletons";

export default function CredentialsPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const t = useTranslations("credentials");
  const tSettings = useTranslations("settings");
  const searchParams = useSearchParams();

  // State
  const [activeTab, setActiveTab] = useState<CredentialTab>("my");
  const [selectedTemplate, setSelectedTemplate] = useState<CredentialTemplate | null>(null);
  const [requirements, setRequirements] = useState<CredentialWizardRequirement[]>([]);
  const [isWizardOpen, setIsWizardOpen] = useState(false);
  // Forces the wizard to open in advanced (BYOK) mode for the
  // "Use my own OAuth app" per-row action - otherwise the wizard defaults
  // to 'standard' (LiveContext's verified OAuth client). Reset on close.
  const [wizardInitialMode, setWizardInitialMode] = useState<'standard' | 'advanced'>('standard');
  // "Add custom OAuth connection" entry point (MyOAuthAppsSection header) needs
  // an integration to attach the BYOK app to. Rather than building a separate
  // picker dialog, we route the user to the Available tab and remember that
  // their next pick should open the wizard in advanced mode. Reset after the
  // first Configure click. Keeps both surfaces (per-row action + section CTA)
  // unified on a single wizard entry.
  const [pendingAdvancedNext, setPendingAdvancedNext] = useState(false);
  // Phase 2: bumps every time MyOAuthAppsSection cascade-deletes a BYOK row,
  // forcing MyCredentialsList (which is not on React Query) to refetch and
  // surface the newly-needs_reauth dependents.
  // PR19: also bumped on workspace switch so the strict-isolation backend
  // (PR19 controller routes on X-Organization-ID) returns the active scope's
  // credentials. Without this bump the prior workspace's rows linger in the
  // local cache until the next interaction.
  const [credentialsRefreshSignal, setCredentialsRefreshSignal] = useState(0);
  const { toasts, addToast, removeToast } = useToast();
  const oauthCallbackHandledRef = React.useRef(false);

  // PR19 - Strict-isolation scope. The apiClient already injects
  // X-Active-Organization-ID per request, but MyCredentialsList caches its
  // fetch result locally; we force a re-fetch on workspace switch.
  const { currentOrgId } = useCurrentOrg();
  useEffect(() => {
    setCredentialsRefreshSignal((s) => s + 1);
  }, [currentOrgId]);

  // Handle OAuth2 callback - detect success/error query params
  useEffect(() => {
    const success = searchParams.get('success');
    const error = searchParams.get('error');
    const hasCallbackParams = success === 'true' || Boolean(error);

    if (!hasCallbackParams) {
      oauthCallbackHandledRef.current = false;
      return;
    }
    if (oauthCallbackHandledRef.current) return;
    oauthCallbackHandledRef.current = true;

    const cleanCallbackUrl = () => {
      const params = new URLSearchParams(window.location.search);
      params.delete('success');
      params.delete('error');
      params.delete('credentialId');
      const nextQuery = params.toString();
      const nextUrl = `${window.location.pathname}${nextQuery ? `?${nextQuery}` : ''}${window.location.hash}`;
      window.history.replaceState(window.history.state, '', nextUrl);
    };

    if (success === 'true') {
      // Show success notification
      addToast({
        type: 'success',
        title: t('toasts.credentialCreated'),
        message: t('toasts.credentialConfigured'),
        duration: 5000,
      });

      // Clean up URL query params
      cleanCallbackUrl();

      // Optionally switch to "My Credentials" tab to show the new credential
      setActiveTab('my');
    } else if (error) {
      // Show error notification
      addToast({
        type: 'error',
        title: t('toasts.connectionFailed'),
        message: decodeURIComponent(error),
        duration: 7000,
      });

      // Clean up URL query params
      cleanCallbackUrl();
    }
  }, [searchParams, addToast, t]);

  // Cancel a pending "open next configure in advanced mode" intent if the
  // user navigates away from Available before picking an integration. Without
  // this, a stale flag would silently flip the next per-row Reconnect (or
  // any other handleConfigure caller) into BYOK mode unexpectedly.
  useEffect(() => {
    if (activeTab !== 'available' && pendingAdvancedNext) {
      setPendingAdvancedNext(false);
    }
  }, [activeTab, pendingAdvancedNext]);

  // Extract iconSlug from template
  const extractIconSlug = (tmpl: CredentialTemplate): string => {
    if (tmpl.icon_url) {
      const match = tmpl.icon_url.match(/\/([^/]+)\.svg$/);
      if (match) return match[1];
    }
    return (tmpl.credential_name || tmpl.display_name || "")
      .toLowerCase()
      .replace(/\s+/g, "");
  };

  // Handle single template configuration
  const handleConfigure = (template: CredentialTemplate) => {
    setSelectedTemplate(template);
    setRequirements([]);
    // Honor the "Add custom OAuth connection" pendthrough - when the user
    // arrived here via that CTA, open the wizard directly in BYOK form.
    setWizardInitialMode(pendingAdvancedNext ? 'advanced' : 'standard');
    setPendingAdvancedNext(false);
    setIsWizardOpen(true);
  };

  // Handle multiple templates configuration
  const handleConfigureMultiple = (templates: CredentialTemplate[]) => {
    const reqs: CredentialWizardRequirement[] = templates.map((tmpl) => ({
      iconSlug: extractIconSlug(tmpl),
      serviceName: tmpl.display_name || tmpl.credential_name,
    }));
    setSelectedTemplate(null);
    setRequirements(reqs);
    setWizardInitialMode('standard');
    setIsWizardOpen(true);
  };

  // Phase 2: edit-existing-BYOK shortcut. Opens the wizard pre-targeted at
  // the integration so the user can re-enter clientId/clientSecret. The
  // backend's POST /my upserts on (tenantId, integrationName), so saving
  // updates the existing platform_credential row in place.
  const handleEditOAuthApp = (app: { iconSlug: string | null; displayName: string; integrationName: string }) => {
    setSelectedTemplate(null);
    setRequirements([
      {
        iconSlug: app.iconSlug || app.integrationName,
        serviceName: app.displayName,
      },
    ]);
    setWizardInitialMode('advanced');
    setIsWizardOpen(true);
  };

  // Resolve a credential row's iconSlug for the wizard requirement payload.
  // Row data may carry it on `iconSlug`, embedded in `icon_url`, or as a
  // fallback derived from `integration`/`name`.
  const resolveCredentialIconSlug = (cred: { integration?: string | null; iconSlug?: string | null; icon_url?: string | null; name: string }): string =>
    cred.iconSlug
      || (cred.icon_url ? cred.icon_url.match(/\/([^/]+)\.svg$/)?.[1] : undefined)
      || cred.integration
      || cred.name.toLowerCase().replace(/\s+/g, "");

  // Phase 2: reconnect shortcut for needs_reauth credentials. Same wizard
  // entry as Available-tab Configure - re-runs OAuth and creates a fresh
  // active credential. The needs_reauth row stays until user deletes it.
  const handleReconnectCredential = (cred: { integration?: string | null; iconSlug?: string | null; icon_url?: string | null; name: string }) => {
    setSelectedTemplate(null);
    setRequirements([
      {
        iconSlug: resolveCredentialIconSlug(cred),
        serviceName: cred.name,
      },
    ]);
    setWizardInitialMode('standard');
    setIsWizardOpen(true);
  };

  // Handle wizard completion - no restriction in settings page
  const handleWizardComplete = (_completedIconSlugs: string[]) => {
    addToast({
      type: "success",
      title: t("toasts.credentialCreated"),
      message: t("toasts.credentialConfigured"),
    });
  };

  // Handle credential added
  const handleCredentialAdded = (_iconSlug: string) => {
    // Optionally refresh the credentials list
  };

  // Content to show based on auth state
  const renderContent = () => {
    // Loading state - show skeleton only during initial auth check
    // Use isAuthChecking (not isLoading) for faster UI rendering
    if (isAuthChecking) {
      return <CredentialsListSkeleton />;
    }

    // Not authenticated
    if (!isAuthenticated) {
      return (
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
      );
    }

    // Authenticated - show tabs and content
    return (
      <>
        {/* Tabs */}
        <div className="flex justify-center">
          <CredentialTabs activeTab={activeTab} onTabChange={setActiveTab} />
        </div>

        {/* Content */}
        <div>
          {activeTab === "my" ? (
            <div className="space-y-10">
              <MyOAuthAppsSection
                onAddNew={() => {
                  // Route the user to the Available tab where they can pick
                  // an integration. Without an iconSlug the wizard has nothing
                  // to fetch and would render an infinite loading skeleton.
                  // The pendingAdvancedNext flag carries the BYOK intent
                  // through to handleConfigure so the wizard still opens
                  // directly on the Custom OAuth form once they pick.
                  setPendingAdvancedNext(true);
                  setActiveTab('available');
                }}
                onEdit={handleEditOAuthApp}
                onCascadeRevoke={() => setCredentialsRefreshSignal((s) => s + 1)}
                addToast={addToast}
              />
              <MyCredentialsList
                refreshSignal={credentialsRefreshSignal}
                addToast={addToast}
                onReconnect={handleReconnectCredential}
                focusCredentialId={searchParams.get('credentialId')}
              />
            </div>
          ) : (
            <AvailableCredentialsList
              onConfigure={handleConfigure}
              onConfigureMultiple={handleConfigureMultiple}
            />
          )}
        </div>

        {/* Unified Credential Wizard */}
        <CredentialWizard
          template={selectedTemplate}
          requirements={requirements}
          initialMode={wizardInitialMode}
          open={isWizardOpen}
          onOpenChange={(open) => {
            setIsWizardOpen(open);
            if (!open) {
              setSelectedTemplate(null);
              setRequirements([]);
              // Reset to default so the next opening (e.g. from Available
              // tab) doesn't inherit the previous "advanced" snapshot.
              setWizardInitialMode('standard');
            }
          }}
          onComplete={handleWizardComplete}
          onCredentialAdded={handleCredentialAdded}
        />
      </>
    );
  };

  return (
    <div className="space-y-8">
      {/* Header - Always visible immediately */}
      <PageHeader
        icon={KeyRound}
        title={t("title")}
        subtitle={t("subtitle")}
      />

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

      {/* Content - shows skeleton during loading, then tabs/list */}
      {renderContent()}
    </div>
  );
}
