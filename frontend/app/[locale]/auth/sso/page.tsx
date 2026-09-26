"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import LoadingSpinner from "@/components/LoadingSpinner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { organizationApi } from "@/lib/api/organization-api";
import { IS_CE } from "@/lib/edition";
import { useAuth } from "@/lib/providers/smart-providers";
import { track } from "@/lib/analytics/analytics";

const IDP_HINT_PATTERN = /^[A-Za-z0-9._-]{1,120}$/;
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function expectedHintForOrg(orgId: string): string {
  return `org-${orgId.replaceAll("-", "").toLowerCase()}-saml`;
}

function isValidStart(orgId: string, hint: string): boolean {
  return IDP_HINT_PATTERN.test(hint) && UUID_PATTERN.test(orgId) && hint === expectedHintForOrg(orgId);
}

/**
 * Two ways in. A workspace link (`?org=...&hint=...`, copied from the SSO settings) goes
 * straight to that workspace's identity provider. Without parameters (the "Sign in with SSO"
 * button on the sign-in page), the person types their work email and a domain the workspace
 * has verified tells us which provider to use.
 */
export default function SamlSsoStartPage() {
  const t = useTranslations("auth.sso");
  const searchParams = useSearchParams();
  const params = useParams();
  const { isLoading, loginWithRedirect } = useAuth();
  const [redirectError, setRedirectError] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [looking, setLooking] = useState(false);
  const [redirecting, setRedirecting] = useState(false);
  const startedRef = useRef(false);
  // A workspace link is reported once per page, StrictMode's doubled effect included.
  const linkReportedRef = useRef(false);
  const hint = searchParams.get("hint")?.trim() ?? "";
  const orgId = searchParams.get("org")?.trim() ?? "";
  const locale = (params?.locale as string) ?? "en";
  const isLinkMode = Boolean(hint || orgId);
  const hasInvalidParams = isLinkMode && !isValidStart(orgId, hint);

  const start = useCallback((targetOrgId: string, idpHint: string, loginHint?: string) => {
    if (startedRef.current) return;
    startedRef.current = true;
    setRedirecting(true);
    const normalizedOrgId = targetOrgId.toLowerCase();
    loginWithRedirect({
      appState: { returnTo: `/${locale}/app/chat?ssoOrg=${encodeURIComponent(normalizedOrgId)}` },
      authorizationParams: loginHint
        ? { kc_idp_hint: idpHint, login_hint: loginHint }
        : { kc_idp_hint: idpHint },
    }).catch(() => {
      startedRef.current = false;
      setRedirecting(false);
      setRedirectError(t("failed"));
    });
  }, [locale, loginWithRedirect, t]);

  useEffect(() => {
    if (!isLinkMode || linkReportedRef.current) return;
    if (hasInvalidParams) {
      // A link that names no valid workspace is, for the reader, a workspace that was not found.
      linkReportedRef.current = true;
      track("sso_lookup_submitted", { result: "not_found", mode: "link" });
      return;
    }
    if (isLoading) return;
    linkReportedRef.current = true;
    track("sso_lookup_submitted", { result: "found", mode: "link" });
  }, [hasInvalidParams, isLinkMode, isLoading]);

  useEffect(() => {
    if (isLoading || !isLinkMode || hasInvalidParams) return;
    start(orgId, hint);
  }, [hasInvalidParams, hint, isLinkMode, isLoading, orgId, start]);

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    const address = email.trim();
    if (!address || looking) return;
    setLookupError(null);
    setLooking(true);
    try {
      const result = await organizationApi.discoverSso(address);
      // The result only: the address the person typed never leaves this page in an event.
      if (result.found && isValidStart(result.organizationId, result.idpHint)) {
        track("sso_lookup_submitted", { result: "found", mode: "email" });
        start(result.organizationId, result.idpHint, address);
      } else {
        track("sso_lookup_submitted", { result: "not_found", mode: "email" });
        setLookupError(t("notFound"));
      }
    } catch {
      track("sso_lookup_submitted", { result: "lookup_failed", mode: "email" });
      setLookupError(t("lookupFailed"));
    } finally {
      setLooking(false);
    }
  };

  const error = hasInvalidParams ? t("invalid") : redirectError;

  if (isLinkMode || redirecting) {
    return (
      <Centered>
        {error ? (
          <ErrorBox text={error} />
        ) : (
          <>
            <LoadingSpinner size="lg" />
            <p className="text-sm text-theme-secondary">{t("redirecting")}</p>
          </>
        )}
      </Centered>
    );
  }

  return (
    <Centered>
      <h1 className="text-xl font-semibold text-theme-primary">{t("title")}</h1>
      {IS_CE ? (
        <p className="text-sm text-theme-secondary">{t("ceUnavailable")}</p>
      ) : (
        <>
          <p className="text-sm text-theme-secondary">{t("subtitle")}</p>
          <form onSubmit={onSubmit} className="flex w-full flex-col gap-3 text-left">
            <label htmlFor="sso-email" className="text-sm font-medium text-theme-primary">
              {t("emailLabel")}
            </label>
            <Input
              id="sso-email"
              type="email"
              autoComplete="email"
              autoFocus
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder={t("emailPlaceholder")}
              className="h-10"
            />
            <Button type="submit" className="h-10" disabled={!email.trim() || looking}>
              {looking ? t("looking") : t("continue")}
            </Button>
          </form>
          {(lookupError || redirectError) && <ErrorBox text={(lookupError || redirectError) as string} />}
        </>
      )}
    </Centered>
  );
}

function Centered({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen flex items-center justify-center bg-[var(--bg-primary)] px-4">
      <div className="flex w-full max-w-sm flex-col items-center gap-4 text-center">{children}</div>
    </div>
  );
}

function ErrorBox({ text }: { text: string }) {
  return (
    <p className="w-full rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400">
      {text}
    </p>
  );
}
