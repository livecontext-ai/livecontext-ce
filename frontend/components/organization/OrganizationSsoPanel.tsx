"use client";

import React, { useMemo, useState, useSyncExternalStore } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Copy,
  ExternalLink,
  KeyRound,
  Lock,
  Save,
  ShieldCheck,
  Trash2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import {
  organizationApi,
  type OrganizationRole,
  type OrganizationSamlConnection,
} from "@/lib/api/organization-api";
import { cn } from "@/lib/utils";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";

type Props = {
  orgId: string;
  currentUserRole: OrganizationRole;
  supportsTeam: boolean;
};

type FormState = {
  displayName: string;
  idpEntityId: string;
  ssoUrl: string;
  x509Certificate: string;
  hideOnLoginPage: boolean;
};

const EMPTY_FORM: FormState = {
  displayName: "",
  idpEntityId: "",
  ssoUrl: "",
  x509Certificate: "",
  hideOnLoginPage: true,
};

const STATUS_STYLES: Record<OrganizationSamlConnection["status"], string> = {
  NOT_CONFIGURED: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300",
  DRAFT: "bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400",
  ACTIVE: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-400",
  ERROR: "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400",
  DISABLED: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300",
};

function buildForm(connection: OrganizationSamlConnection | undefined): FormState {
  if (!connection) return EMPTY_FORM;
  return {
    displayName: connection.displayName || "",
    idpEntityId: connection.idpEntityId || "",
    ssoUrl: connection.ssoUrl || "",
    x509Certificate: "",
    hideOnLoginPage: connection.hideOnLoginPage,
  };
}

function formSourceKey(connection: OrganizationSamlConnection | undefined): string {
  if (!connection) return "empty";
  return [
    connection.configured,
    connection.status,
    connection.displayName ?? "",
    connection.idpEntityId ?? "",
    connection.ssoUrl ?? "",
    String(connection.hideOnLoginPage),
  ].join("|");
}

function localizedSsoPath(path: string, locale: string): string {
  if (!path) return "";
  return path.startsWith("/auth/sso") ? `/${locale}${path}` : path;
}

function statusTranslationKey(status: OrganizationSamlConnection["status"]): string {
  return `statuses.${status}`;
}

function subscribeToOriginChange(): () => void {
  return () => {};
}

function getBrowserOrigin(): string {
  return typeof window === "undefined" ? "" : window.location.origin;
}

function getServerOrigin(): string {
  return "";
}

export default function OrganizationSsoPanel({ orgId, currentUserRole, supportsTeam }: Props) {
  const t = useTranslations("settings.organization.sso");
  const locale = useLocale();
  const queryClient = useQueryClient();
  const canManage = currentUserRole === "OWNER" || currentUserRole === "ADMIN";
  const queryKey = ["org", orgId, "saml-sso"] as const;
  const [formState, setFormState] = useState<{ sourceKey: string; form: FormState }>(() => ({
    sourceKey: formSourceKey(undefined),
    form: EMPTY_FORM,
  }));
  const [notice, setNotice] = useState<{ type: "success" | "error"; text: string } | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const origin = useSyncExternalStore(subscribeToOriginChange, getBrowserOrigin, getServerOrigin);

  const samlQuery = useQuery({
    queryKey,
    queryFn: () => organizationApi.getSamlConnection(orgId),
    enabled: supportsTeam && canManage,
  });

  const connection = samlQuery.data;
  const isConfigured = Boolean(connection?.configured);
  const connectionFormSource = formSourceKey(connection);
  const form = formState.sourceKey === connectionFormSource ? formState.form : buildForm(connection);
  const ssoStartPath = connection?.ssoStartPath ?? "";
  const publicSsoUrl = useMemo(() => {
    if (!ssoStartPath) return "";
    return `${origin}${localizedSsoPath(ssoStartPath, locale)}`;
  }, [locale, origin, ssoStartPath]);

  const setForm = (updater: React.SetStateAction<FormState>) => {
    setFormState((previous) => {
      const currentForm = previous.sourceKey === connectionFormSource ? previous.form : buildForm(connection);
      const nextForm = typeof updater === "function" ? updater(currentForm) : updater;
      return { sourceKey: connectionFormSource, form: nextForm };
    });
  };

  const saveMutation = useMutation({
    mutationFn: () =>
      organizationApi.saveSamlConnection(orgId, {
        displayName: form.displayName.trim(),
        idpEntityId: form.idpEntityId.trim(),
        ssoUrl: form.ssoUrl.trim(),
        x509Certificate: form.x509Certificate.trim(),
        hideOnLoginPage: form.hideOnLoginPage,
    }),
    onSuccess: (saved) => {
      queryClient.setQueryData(queryKey, saved);
      setFormState({ sourceKey: formSourceKey(saved), form: buildForm(saved) });
      setNotice({ type: "success", text: t("saved") });
    },
    onError: (err) => {
      queryClient.invalidateQueries({ queryKey });
      setNotice({ type: "error", text: err instanceof Error ? err.message : t("errorGeneric") });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => organizationApi.deleteSamlConnection(orgId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey });
      setFormState({ sourceKey: formSourceKey(undefined), form: EMPTY_FORM });
      setNotice({ type: "success", text: t("deleted") });
    },
    onError: (err) => {
      setNotice({ type: "error", text: err instanceof Error ? err.message : t("errorGeneric") });
    },
  });

  const canSave = Boolean(
    form.displayName.trim()
    && form.idpEntityId.trim()
    && form.ssoUrl.trim()
    && (isConfigured || form.x509Certificate.trim())
  );

  const copyValue = async (key: string, value: string) => {
    if (!value) return;
    await navigator.clipboard.writeText(value);
    setCopiedKey(key);
    window.setTimeout(() => setCopiedKey((current) => (current === key ? null : current)), 1500);
  };

  const renderCopyButton = (key: string, value: string) => (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      className="h-8 px-2"
      onClick={() => copyValue(key, value)}
      disabled={!value}
      title={t("copy")}
    >
      <Copy className="h-3.5 w-3.5" />
      {copiedKey === key ? t("copied") : t("copy")}
    </Button>
  );

  if (!supportsTeam) {
    return (
      <section className="rounded-xl border border-theme bg-theme-secondary p-6">
        <div className="flex items-start gap-4">
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-theme-tertiary">
            <Lock className="h-5 w-5 text-theme-primary" />
          </div>
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-theme-primary">{t("title")}</h2>
            <p className="mt-1 text-sm text-theme-secondary">{t("lockedDescription")}</p>
          </div>
        </div>
      </section>
    );
  }

  if (!canManage) {
    return (
      <section className="rounded-xl border border-theme bg-theme-secondary p-6">
        <div className="flex items-start gap-4">
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-theme-tertiary">
            <ShieldCheck className="h-5 w-5 text-theme-primary" />
          </div>
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-theme-primary">{t("title")}</h2>
            <p className="mt-1 text-sm text-theme-secondary">{t("ownerOnly")}</p>
          </div>
        </div>
      </section>
    );
  }

  const status: OrganizationSamlConnection["status"] = samlQuery.isError
    ? "ERROR"
    : connection?.status ?? "NOT_CONFIGURED";
  const statusLabel = samlQuery.isLoading ? t("loading") : t(statusTranslationKey(status));

  return (
    <Collapsible open={expanded} onOpenChange={setExpanded} asChild>
      <section className="overflow-hidden rounded-xl border border-theme">
        <CollapsibleTrigger asChild>
          <button
            type="button"
            className="flex w-full items-center justify-between gap-4 p-6 text-left transition-colors hover:bg-theme-secondary/60"
            aria-expanded={expanded}
          >
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-theme-secondary">
                <KeyRound className="h-5 w-5 text-theme-primary" />
              </div>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-lg font-semibold text-theme-primary">{t("title")}</h2>
                  <span className={cn("inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium", STATUS_STYLES[status])}>
                    {statusLabel}
                  </span>
                </div>
                <p className="mt-1 text-sm text-theme-secondary">{t("description")}</p>
              </div>
            </div>
            {expanded ? (
              <ChevronDown className="h-5 w-5 flex-shrink-0 text-theme-muted" />
            ) : (
              <ChevronRight className="h-5 w-5 flex-shrink-0 text-theme-muted" />
            )}
          </button>
        </CollapsibleTrigger>

        <CollapsibleContent className="border-t border-theme px-6 pb-6 pt-5">
          <div className="mb-5 flex justify-end">
            {isConfigured && (
              <Button
                type="button"
                variant="destructive"
                size="sm"
                className="h-8 px-3"
                disabled={deleteMutation.isPending}
                onClick={() => {
                  if (window.confirm(t("deleteConfirm"))) {
                    deleteMutation.mutate();
                  }
                }}
              >
                <Trash2 className="h-3.5 w-3.5" />
                {deleteMutation.isPending ? t("deleting") : t("delete")}
              </Button>
            )}
          </div>

          {samlQuery.isLoading ? (
            <div className="h-24 rounded-lg bg-theme-secondary" />
          ) : (
            <div className="space-y-6">
              {(notice || connection?.lastError || samlQuery.isError) && (
                <div
                  className={cn(
                    "rounded-lg border px-4 py-3 text-sm",
                    notice?.type === "success"
                      ? "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900/50 dark:bg-emerald-900/20 dark:text-emerald-300"
                      : "border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-900/50 dark:bg-amber-900/20 dark:text-amber-300"
                  )}
                >
                  <div className="flex gap-2">
                    {notice?.type === "success" ? (
                      <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" />
                    ) : (
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" />
                    )}
                    <span>{notice?.text || connection?.lastError || t("errorGeneric")}</span>
                  </div>
                </div>
              )}

              <div className="grid gap-4 lg:grid-cols-2">
                <div>
                  <label htmlFor="saml-display-name" className="mb-1.5 block text-sm font-medium text-theme-primary">
                    {t("displayName")}
                  </label>
                  <Input
                    id="saml-display-name"
                    value={form.displayName}
                    onChange={(e) => setForm((prev) => ({ ...prev, displayName: e.target.value }))}
                    placeholder={t("displayNamePlaceholder")}
                    className="h-10"
                  />
                </div>

                <div>
                  <label htmlFor="saml-sso-url" className="mb-1.5 block text-sm font-medium text-theme-primary">
                    {t("ssoUrl")}
                  </label>
                  <Input
                    id="saml-sso-url"
                    value={form.ssoUrl}
                    onChange={(e) => setForm((prev) => ({ ...prev, ssoUrl: e.target.value }))}
                    placeholder={t("ssoUrlPlaceholder")}
                    className="h-10"
                  />
                </div>
              </div>

              <div>
                <label htmlFor="saml-idp-entity-id" className="mb-1.5 block text-sm font-medium text-theme-primary">
                  {t("idpEntityId")}
                </label>
                <Input
                  id="saml-idp-entity-id"
                  value={form.idpEntityId}
                  onChange={(e) => setForm((prev) => ({ ...prev, idpEntityId: e.target.value }))}
                  placeholder={t("idpEntityIdPlaceholder")}
                  className="h-10"
                />
              </div>

              <div>
                <label htmlFor="saml-certificate" className="mb-1.5 block text-sm font-medium text-theme-primary">
                  {t("certificate")}
                </label>
                <Textarea
                  id="saml-certificate"
                  value={form.x509Certificate}
                  onChange={(e) => setForm((prev) => ({ ...prev, x509Certificate: e.target.value }))}
                  placeholder={t("certificatePlaceholder")}
                  className="min-h-[130px] font-mono text-xs"
                />
                <p className="mt-1.5 text-xs text-theme-secondary">{t("certificateHelp")}</p>
              </div>

              <div className="flex items-start justify-between gap-4 rounded-lg border border-theme bg-theme-secondary p-4">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-theme-primary">{t("hideOnLoginPage")}</p>
                  <p className="mt-1 text-xs text-theme-secondary">{t("hideOnLoginPageHelp")}</p>
                </div>
                <Switch
                  checked={form.hideOnLoginPage}
                  onCheckedChange={(checked) => setForm((prev) => ({ ...prev, hideOnLoginPage: checked }))}
                  aria-label={t("hideOnLoginPage")}
                />
              </div>

              <div className="rounded-lg border border-theme bg-theme-secondary p-4">
                <h3 className="text-sm font-semibold text-theme-primary">{t("serviceProviderTitle")}</h3>
                <dl className="mt-3 space-y-3">
                  <MetadataRow
                    label={t("serviceProviderEntityId")}
                    value={connection?.serviceProviderEntityId ?? ""}
                    action={renderCopyButton("entityId", connection?.serviceProviderEntityId ?? "")}
                  />
                  <MetadataRow
                    label={t("assertionConsumerServiceUrl")}
                    value={connection?.assertionConsumerServiceUrl ?? ""}
                    action={renderCopyButton("acs", connection?.assertionConsumerServiceUrl ?? "")}
                  />
                  {isConfigured && connection?.serviceProviderMetadataUrl && (
                    <MetadataRow
                      label={t("metadataUrl")}
                      value={connection.serviceProviderMetadataUrl}
                      action={
                        <div className="flex items-center gap-1">
                          {renderCopyButton("metadata", connection.serviceProviderMetadataUrl)}
                          <Button type="button" variant="ghost" size="sm" className="h-8 px-2" asChild>
                            <a href={connection.serviceProviderMetadataUrl} target="_blank" rel="noreferrer" title={t("open")}>
                              <ExternalLink className="h-3.5 w-3.5" />
                            </a>
                          </Button>
                        </div>
                      }
                    />
                  )}
                  <MetadataRow
                    label={t("startUrl")}
                    value={publicSsoUrl}
                    action={renderCopyButton("startUrl", publicSsoUrl)}
                  />
                  {connection?.certificateFingerprintSha256 && (
                    <MetadataRow label={t("fingerprint")} value={connection.certificateFingerprintSha256} />
                  )}
                  {connection?.lastSyncedAt && (
                    <MetadataRow
                      label={t("lastSynced")}
                      value={formatUtcDateTime(connection.lastSyncedAt, { locale })}
                    />
                  )}
                </dl>
              </div>

              <div className="flex justify-end">
                <Button
                  type="button"
                  size="sm"
                  className="h-9 px-4"
                  disabled={!canSave || saveMutation.isPending}
                  onClick={() => {
                    setNotice(null);
                    saveMutation.mutate();
                  }}
                >
                  <Save className="h-3.5 w-3.5" />
                  {saveMutation.isPending ? t("saving") : t("save")}
                </Button>
              </div>
            </div>
          )}
        </CollapsibleContent>
      </section>
    </Collapsible>
  );
}

function MetadataRow({
  label,
  value,
  action,
}: {
  label: string;
  value: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="grid gap-2 md:grid-cols-[170px_minmax(0,1fr)_auto] md:items-center">
      <dt className="text-xs font-medium uppercase tracking-wide text-theme-muted">{label}</dt>
      <dd className="min-w-0 break-all rounded-md bg-[var(--bg-primary)] px-3 py-2 font-mono text-xs text-theme-primary">
        {value || "-"}
      </dd>
      {action && <div className="flex justify-start md:justify-end">{action}</div>}
    </div>
  );
}
