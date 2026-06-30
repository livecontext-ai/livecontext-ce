"use client";

/**
 * Landing page for an invitation link.
 *
 * Flow:
 *   1. Read ?token= from the URL and look up the invitation (public, no auth)
 *      via getInvitationInfo → {valid, email, organizationName, role, hasAccount}.
 *   2. If the visitor is authenticated → accept via
 *      organizationApi.acceptInvitation(token) (email match enforced server-side).
 *   3. If NOT authenticated and the invitation is valid:
 *        - hasAccount=false (brand-new invitee, CE invite-by-link) → render a
 *          REGISTER form (email locked to the invitation email) that registers
 *          WITH the invitationToken, bypassing the closed public-registration
 *          door and auto-joining the org. On success the user is logged in.
 *        - hasAccount=true → show the "sign in to accept" CTA.
 *   4. Invalid/expired/used token → an error/invalid card.
 *
 * Chrome: reuses {@link AuthLayout} (logo + theme toggle, borderless centered
 * stage) and the login/register field + button styling so an invitation link
 * presents the same visual identity as the rest of the CE auth flow.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams, useParams } from "next/navigation";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { ArrowRight } from "lucide-react";
import { useAuth } from "@/lib/providers/smart-providers";
import { embeddedRegister } from "@/lib/providers/embedded-auth-provider";
import { organizationApi, type Organization, type InvitationInfo } from "@/lib/api/organization-api";
import { IS_CE } from "@/lib/edition";
import { AuthLayout } from "@/components/auth/AuthLayout";

// Shared field/button styling, kept byte-identical to the login & register pages
// so the invitation flow looks like part of the same auth surface.
const INPUT_CLS =
  "block h-[46px] w-full rounded-[10px] border border-[var(--border-color)] bg-[var(--bg-secondary)] px-3.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)]/60 transition-colors focus:border-[var(--text-secondary)] focus:outline-none focus:ring-4 focus:ring-[var(--accent-primary)]/15 disabled:cursor-not-allowed disabled:opacity-70";
const LABEL_CLS = "mb-1.5 block text-[13px] font-medium text-[var(--text-secondary)]";
const PRIMARY_BTN =
  "mt-1.5 inline-flex h-[46px] w-full items-center justify-center gap-2.5 rounded-[10px] border border-[var(--accent-primary)] bg-[var(--accent-primary)] px-4 text-sm font-semibold text-[var(--accent-foreground)] shadow-[0_1px_2px_rgba(17,17,17,0.06),0_6px_16px_var(--shadow-color)] transition-all hover:-translate-y-px hover:shadow-[0_1px_2px_rgba(17,17,17,0.06),0_10px_22px_var(--shadow-color)] active:scale-[0.985] disabled:cursor-wait disabled:opacity-90";

type Status =
  | "idle"
  | "missing-token"
  | "loading-info"
  | "invalid"
  | "register"
  | "sign-in"
  | "accepting"
  | "accepted"
  | "error";

export default function AcceptInvitationPage() {
  const t = useTranslations("invitationAccept");
  const router = useRouter();
  const searchParams = useSearchParams();
  const params = useParams();
  const locale = (params?.locale as string) ?? "en";

  const { isAuthenticated, isLoading } = useAuth();

  const token = useMemo(() => searchParams.get("token") ?? "", [searchParams]);

  const [status, setStatus] = useState<Status>(token ? "idle" : "missing-token");
  const [errorMessage, setErrorMessage] = useState<string>("");
  const [acceptedOrg, setAcceptedOrg] = useState<Organization | null>(null);
  const [info, setInfo] = useState<InvitationInfo | null>(null);
  const submittedTokenRef = useRef<string | null>(null);
  const infoTokenRef = useRef<string | null>(null);

  // Register-form fields (brand-new invitee path).
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [registering, setRegistering] = useState(false);

  // Step 1: look up the invitation once the token is known. Drives the
  // register-vs-sign-in branch for unauthenticated visitors.
  useEffect(() => {
    if (!token) return;
    if (infoTokenRef.current === token) return;
    infoTokenRef.current = token;
    // The embedded invite-by-link flow (info lookup + register form) is CE-only.
    // In cloud (Keycloak) keep the original behavior: an unauthenticated invitee
    // signs in (Step 2 then accepts) - no info lookup, no embedded register form.
    if (!IS_CE) {
      setStatus("sign-in");
      return;
    }
    setStatus("loading-info");
    organizationApi
      .getInvitationInfo(token)
      .then((result) => setInfo(result))
      .catch(() => setInfo({ valid: false }));
  }, [token]);

  // Step 2: authenticated visitor → accept directly (email match enforced server-side).
  useEffect(() => {
    if (!token) return;
    if (isLoading) return;
    if (!isAuthenticated) return;
    if (submittedTokenRef.current === token) return;

    let cancelled = false;
    submittedTokenRef.current = token;
    queueMicrotask(() => {
      if (!cancelled) setStatus("accepting");
    });

    organizationApi
      .acceptInvitation(token)
      .then((org) => {
        if (cancelled) return;
        setAcceptedOrg(org);
        setStatus("accepted");
        setTimeout(() => {
          router.push(`/${locale}/app/settings/organization`);
        }, 1500);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : t("fallbackError");
        setErrorMessage(msg);
        setStatus("error");
      });

    return () => {
      cancelled = true;
    };
  }, [token, isAuthenticated, isLoading, router, locale, t]);

  // Step 3: unauthenticated visitor → derive register vs sign-in from the lookup.
  useEffect(() => {
    if (!token || isLoading || isAuthenticated || info === null) return;
    if (status === "accepting" || status === "accepted" || status === "error") return;
    if (!info.valid) {
      setStatus("invalid");
    } else if (info.hasAccount) {
      setStatus("sign-in");
    } else {
      setStatus("register");
    }
  }, [token, isLoading, isAuthenticated, info, status]);

  const handleRegister = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setErrorMessage("");
      if (password.length < 8) {
        setErrorMessage(t("passwordTooShort"));
        return;
      }
      if (password !== confirmPassword) {
        setErrorMessage(t("passwordMismatch"));
        return;
      }
      const email = info?.email;
      if (!email) return;

      setRegistering(true);
      const result = await embeddedRegister(email, password, firstName, lastName, token);
      if (result.success) {
        // Registered + auto-joined the org server-side; the user is now logged in.
        window.location.href = `/${locale}/app/settings/organization`;
      } else {
        setErrorMessage(result.error || t("fallbackError"));
        setRegistering(false);
      }
    },
    [password, confirmPassword, info, firstName, lastName, token, locale, t]
  );

  if (isLoading || status === "loading-info" || status === "idle") {
    return <AuthCard title={t("loading")} spinner />;
  }

  if (status === "missing-token") {
    return <AuthCard title={t("missingTokenTitle")} body={t("missingTokenBody")} />;
  }

  if (status === "invalid") {
    return <AuthCard title={t("invalidTitle")} body={t("invalidBody")} />;
  }

  // Brand-new invitee (no account yet) → register form, email locked.
  if (status === "register" && info?.valid) {
    return (
      <AuthCard
        title={t("registerTitle")}
        body={info.organizationName ? t("registerNamedBody", { org: info.organizationName }) : t("registerBody")}
      >
        <form onSubmit={handleRegister} className="space-y-3.5">
          {errorMessage && (
            <div className="rounded-[10px] border border-red-300/60 bg-red-50/70 px-3 py-2.5 text-[13px] text-red-700 dark:border-red-700/60 dark:bg-red-900/20 dark:text-red-400">
              {errorMessage}
            </div>
          )}
          <div>
            {/* Email comes from the invitation and cannot be changed. */}
            <label htmlFor="accept-email" className={LABEL_CLS}>
              {t("email")}
            </label>
            <input id="accept-email" type="email" value={info.email ?? ""} readOnly disabled className={INPUT_CLS} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="accept-firstName" className={LABEL_CLS}>
                {t("firstName")}
              </label>
              <input
                id="accept-firstName"
                type="text"
                required
                autoFocus
                value={firstName}
                onChange={(e) => setFirstName(e.target.value)}
                className={INPUT_CLS}
              />
            </div>
            <div>
              <label htmlFor="accept-lastName" className={LABEL_CLS}>
                {t("lastName")}
              </label>
              <input
                id="accept-lastName"
                type="text"
                required
                value={lastName}
                onChange={(e) => setLastName(e.target.value)}
                className={INPUT_CLS}
              />
            </div>
          </div>
          <div>
            <label htmlFor="accept-password" className={LABEL_CLS}>
              {t("password")}
            </label>
            <input
              id="accept-password"
              type="password"
              required
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={INPUT_CLS}
            />
          </div>
          <div>
            <label htmlFor="accept-confirmPassword" className={LABEL_CLS}>
              {t("confirmPassword")}
            </label>
            <input
              id="accept-confirmPassword"
              type="password"
              required
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              className={INPUT_CLS}
            />
          </div>
          <button type="submit" disabled={registering} className={PRIMARY_BTN}>
            {registering ? (
              <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent" />
            ) : (
              <>
                <span>{t("registerCta")}</span>
                <ArrowRight className="h-3.5 w-3.5" strokeWidth={2.5} />
              </>
            )}
          </button>
          <p className="mt-1 text-center text-[13px] text-[var(--text-secondary)]">
            {t("registerSignInHint")}{" "}
            <Link
              href={`/${locale}/login?returnTo=${encodeURIComponent(`/${locale}/invitations/accept?token=${token}`)}`}
              className="border-b border-[var(--border-color)] pb-px font-medium text-[var(--text-primary)] transition-colors hover:border-[var(--text-primary)]"
            >
              {t("signIn")}
            </Link>
          </p>
        </form>
      </AuthCard>
    );
  }

  // Valid invitation but the visitor already has an account → sign in to accept.
  if (status === "sign-in") {
    const returnTo = `/${locale}/invitations/accept?token=${encodeURIComponent(token)}`;
    return (
      <AuthCard title={t("signInTitle")} body={t("signInBody")}>
        <Link href={`/${locale}/login?returnTo=${encodeURIComponent(returnTo)}`} className={PRIMARY_BTN}>
          <span>{t("signIn")}</span>
          <ArrowRight className="h-3.5 w-3.5" strokeWidth={2.5} />
        </Link>
      </AuthCard>
    );
  }

  if (status === "accepting") {
    return <AuthCard title={t("acceptingTitle")} body={t("acceptingBody")} spinner />;
  }

  if (status === "accepted") {
    return (
      <AuthCard
        title={acceptedOrg?.name ? t("acceptedNamedTitle", { name: acceptedOrg.name }) : t("acceptedTitle")}
        body={t("acceptedBody")}
        spinner
      />
    );
  }

  // error
  return (
    <AuthCard title={t("errorTitle")} body={errorMessage}>
      <Link href={`/${locale}/app/settings/organization`} className={PRIMARY_BTN}>
        {t("goToOrganizations")}
      </Link>
    </AuthCard>
  );
}

/**
 * Card chrome shared by every invitation-accept state. Wraps the login/register
 * AuthLayout (logo + theme toggle + centered stage) and renders the same title /
 * subtitle typography, with an optional spinner for the transient states.
 */
function AuthCard({
  title,
  body,
  spinner,
  children,
}: {
  title: string;
  body?: string;
  spinner?: boolean;
  children?: React.ReactNode;
}) {
  return (
    <AuthLayout>
      <h1 className="mb-2 text-[28px] font-semibold leading-tight tracking-tight text-[var(--text-primary)]">
        {title}
      </h1>
      {body ? <p className="mb-7 max-w-[340px] text-sm text-[var(--text-secondary)]">{body}</p> : null}
      {spinner ? (
        <span className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-[var(--text-secondary)] border-t-transparent" />
      ) : null}
      {children}
    </AuthLayout>
  );
}
