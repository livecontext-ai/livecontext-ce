"use client";

import { useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import LoadingSpinner from "@/components/LoadingSpinner";
import { useAuth } from "@/lib/providers/smart-providers";

const IDP_HINT_PATTERN = /^[A-Za-z0-9._-]{1,120}$/;
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function expectedHintForOrg(orgId: string): string {
  return `org-${orgId.replaceAll("-", "").toLowerCase()}-saml`;
}

export default function SamlSsoStartPage() {
  const t = useTranslations("auth.sso");
  const searchParams = useSearchParams();
  const params = useParams();
  const { isLoading, loginWithRedirect } = useAuth();
  const [redirectError, setRedirectError] = useState<string | null>(null);
  const startedRef = useRef(false);
  const hint = searchParams.get("hint")?.trim() ?? "";
  const orgId = searchParams.get("org")?.trim() ?? "";
  const locale = (params?.locale as string) ?? "en";
  const hasInvalidParams = !IDP_HINT_PATTERN.test(hint)
    || !UUID_PATTERN.test(orgId)
    || hint !== expectedHintForOrg(orgId);
  const error = hasInvalidParams ? t("invalid") : redirectError;

  useEffect(() => {
    if (isLoading || startedRef.current || hasInvalidParams) return;

    const normalizedOrgId = orgId.toLowerCase();
    startedRef.current = true;
    loginWithRedirect({
      appState: { returnTo: `/${locale}/app/chat?ssoOrg=${encodeURIComponent(normalizedOrgId)}` },
      authorizationParams: { kc_idp_hint: hint },
    }).catch(() => {
      startedRef.current = false;
      setRedirectError(t("failed"));
    });
  }, [hasInvalidParams, hint, isLoading, locale, loginWithRedirect, orgId, t]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-[var(--bg-primary)] px-4">
      <div className="flex w-full max-w-sm flex-col items-center gap-4 text-center">
        {error ? (
          <p className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400">
            {error}
          </p>
        ) : (
          <>
            <LoadingSpinner size="lg" />
            <p className="text-sm text-theme-secondary">{t("redirecting")}</p>
          </>
        )}
      </div>
    </div>
  );
}
