"use client";

import React, { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Clock, Copy, Globe, Plus, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { organizationApi, type OrganizationSsoDomain } from "@/lib/api/organization-api";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";

type Props = { orgId: string };

/**
 * Email domains the workspace proves it owns (DNS TXT). A SAML login only joins the workspace
 * with an address on a verified domain, and "Sign in with SSO" routes those addresses here.
 */
export default function OrganizationSsoDomainsSection({ orgId }: Props) {
  const t = useTranslations("settings.organization.sso.domains");
  const locale = useLocale();
  const queryClient = useQueryClient();
  const queryKey = ["org", orgId, "saml-sso", "domains"] as const;
  const [draft, setDraft] = useState("");
  const [message, setMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const domainsQuery = useQuery({
    queryKey,
    queryFn: () => organizationApi.listSsoDomains(orgId),
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey });
  const errorText = (err: unknown) => (err instanceof Error && err.message ? err.message : t("error"));

  const addMutation = useMutation({
    mutationFn: (domain: string) => organizationApi.addSsoDomain(orgId, domain),
    onSuccess: () => {
      setDraft("");
      setMessage(null);
      refresh();
    },
    onError: (err) => setMessage({ type: "error", text: errorText(err) }),
  });

  const verifyMutation = useMutation({
    mutationFn: (domainId: string) => organizationApi.verifySsoDomain(orgId, domainId),
    onSuccess: (result) => {
      setMessage(result.verified
        ? { type: "success", text: t("verifiedNotice", { domain: result.domain }) }
        : { type: "error", text: t("notFoundYet", { domain: result.domain }) });
      refresh();
    },
    onError: (err) => setMessage({ type: "error", text: errorText(err) }),
  });

  const deleteMutation = useMutation({
    mutationFn: (domainId: string) => organizationApi.deleteSsoDomain(orgId, domainId),
    onSuccess: () => {
      setMessage(null);
      refresh();
    },
    onError: (err) => setMessage({ type: "error", text: errorText(err) }),
  });

  const copy = async (key: string, value: string) => {
    await navigator.clipboard.writeText(value);
    setCopied(key);
    window.setTimeout(() => setCopied((current) => (current === key ? null : current)), 1500);
  };

  const domains: OrganizationSsoDomain[] = domainsQuery.data ?? [];
  const hasVerified = domains.some((d) => d.verified);

  return (
    <div className="rounded-lg border border-theme bg-theme-secondary p-4">
      <div className="flex items-start gap-2">
        <Globe className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-theme-primary" />
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-theme-primary">{t("title")}</h3>
          <p className="mt-1 text-xs text-theme-secondary">{t("description")}</p>
        </div>
      </div>

      {!domainsQuery.isLoading && !hasVerified && (
        <p className="mt-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:border-amber-900/50 dark:bg-amber-900/20 dark:text-amber-300">
          {t("noneVerified")}
        </p>
      )}

      {message && (
        <p
          role="status"
          className={
            message.type === "success"
              ? "mt-3 text-sm text-emerald-700 dark:text-emerald-400"
              : "mt-3 text-sm text-amber-700 dark:text-amber-400"
          }
        >
          {message.text}
        </p>
      )}

      <ul className="mt-3 space-y-3">
        {domains.map((d) => (
          <li key={d.id} className="rounded-md border border-theme bg-[var(--bg-primary)] p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex min-w-0 items-center gap-2">
                <span className="truncate font-mono text-sm text-theme-primary">{d.domain}</span>
                {d.verified ? (
                  <span className="inline-flex items-center gap-1 rounded-md bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-400">
                    <CheckCircle2 className="h-3 w-3" />
                    {t("verified")}
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 rounded-md bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-900/30 dark:text-amber-400">
                    <Clock className="h-3 w-3" />
                    {t("pending")}
                  </span>
                )}
              </div>
              <div className="flex items-center gap-1">
                {!d.verified && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="h-8 px-2"
                    disabled={verifyMutation.isPending}
                    onClick={() => verifyMutation.mutate(d.id)}
                  >
                    <RefreshCw className="h-3.5 w-3.5" />
                    {t("verify")}
                  </Button>
                )}
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="h-8 px-2"
                  disabled={deleteMutation.isPending}
                  onClick={() => deleteMutation.mutate(d.id)}
                  aria-label={t("remove", { domain: d.domain })}
                  title={t("remove", { domain: d.domain })}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </div>
            </div>

            {d.verified ? (
              d.verifiedAt && (
                <p className="mt-2 text-xs text-theme-secondary">
                  {t("verifiedAt", { date: formatUtcDateTime(d.verifiedAt, { locale }) })}
                </p>
              )
            ) : (
              <div className="mt-3 space-y-2">
                <p className="text-xs text-theme-secondary">{t("instructions")}</p>
                <RecordRow
                  label={t("recordName")}
                  value={d.txtRecordName}
                  copyLabel={copied === `${d.id}:name` ? t("copied") : t("copy")}
                  onCopy={() => copy(`${d.id}:name`, d.txtRecordName)}
                />
                <RecordRow
                  label={t("recordValue")}
                  value={d.txtRecordValue}
                  copyLabel={copied === `${d.id}:value` ? t("copied") : t("copy")}
                  onCopy={() => copy(`${d.id}:value`, d.txtRecordValue)}
                />
                {d.lastCheckedAt && (
                  <p className="text-xs text-theme-muted">
                    {t("lastChecked", { date: formatUtcDateTime(d.lastCheckedAt, { locale }) })}
                  </p>
                )}
              </div>
            )}
          </li>
        ))}
      </ul>

      <form
        className="mt-3 flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (draft.trim()) addMutation.mutate(draft.trim());
        }}
      >
        <Input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={t("placeholder")}
          aria-label={t("placeholder")}
          className="h-9"
        />
        <Button type="submit" size="sm" className="h-9 px-3" disabled={!draft.trim() || addMutation.isPending}>
          <Plus className="h-3.5 w-3.5" />
          {t("add")}
        </Button>
      </form>
    </div>
  );
}

function RecordRow({
  label,
  value,
  copyLabel,
  onCopy,
}: {
  label: string;
  value: string;
  copyLabel: string;
  onCopy: () => void;
}) {
  return (
    <div className="grid gap-1 md:grid-cols-[110px_minmax(0,1fr)_auto] md:items-center">
      <span className="text-xs font-medium uppercase tracking-wide text-theme-muted">{label}</span>
      <code className="min-w-0 break-all rounded-md bg-theme-secondary px-2 py-1.5 font-mono text-xs text-theme-primary">
        {value}
      </code>
      <Button type="button" variant="ghost" size="sm" className="h-8 px-2" onClick={onCopy}>
        <Copy className="h-3.5 w-3.5" />
        {copyLabel}
      </Button>
    </div>
  );
}
