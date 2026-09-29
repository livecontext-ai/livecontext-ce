"use client";

/**
 * Landing page for an invitation link.
 *
 * Flow:
 *   1. Read ?token= from the URL and look up the invitation (public, no auth)
 *      via getInvitationInfo → {valid, email, organizationName, role, hasAccount}.
 *   2. If the visitor is authenticated → show WHO invites them to WHICH workspace
 *      at WHICH role, and wait for an explicit Accept (acceptInvitation) or
 *      Decline (declineInvitation) click. Nothing is accepted on page load: a
 *      link opened by mistake (or by a link-preview bot) must never join a
 *      workspace on the user's behalf. Email match AND a verified email are
 *      enforced server-side; an unverified email gets a dedicated message.
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
import {
  organizationApi,
  isInvitationEmailNotVerifiedError,
  type Organization,
  type InvitationInfo,
} from "@/lib/api/organization-api";
import { IS_CE } from "@/lib/edition";
import { AuthLayout } from "@/components/auth/AuthLayout";

// Shared field/button styling, kept byte-identical to the login & register pages
// so the invitation flow looks like part of the same auth surface.
const INPUT_CLS =
  "block h-[46px] w-full rounded-[10px] border border-[var(--border-color)] bg-[var(--bg-secondary)] px-3.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)]/60 transition-colors focus:border-[var(--text-secondary)] focus:outline-none focus:ring-4 focus:ring-[var(--accent-primary)]/15 disabled:cursor-not-allowed disabled:opacity-70";
const LABEL_CLS = "mb-1.5 block text-[13px] font-medium text-[var(--text-secondary)]";
const PRIMARY_BTN =
  "mt-1.5 inline-flex h-[46px] w-full items-center justify-center gap-2.5 rounded-[10px] border border-[var(--accent-primary)] bg-[var(--accent-primary)] px-4 text-sm font-semibold text-[var(--accent-foreground)] shadow-[0_1px_2px_rgba(17,17,17,0.06),0_6px_16px_var(--shadow-color)] transition-all hover:-translate-y-px hover:shadow-[0_1px_2px_rgba(17,17,17,0.06),0_10px_22px_var(--shadow-color)] active:scale-[0.985] disabled:cursor-wait disabled:opacity-90";
const SECONDARY_BTN =
  "mt-2.5 inline-flex h-[46px] w-full items-center justify-center gap-2.5 rounded-[10px] border border-[var(--border-color)] bg-transparent px-4 text-sm font-medium text-[var(--text-primary)] transition-colors hover:bg-[var(--bg-secondary)] disabled:cursor-wait disabled:opacity-70";

type Status =
  | "idle"
  | "missing-token"
  | "loading-info"
  | "invalid"
  | "register"
  | "sign-in"
  | "confirm"
  | "accepting"
  | "accepted"
  | "declining"
  | "declined"
  | "error";

/** Which action failed, so the error card can say "accept" or "decline". */
type FailedAction = "accept" | "decline";

export default function AcceptInvitationPage() {
  const t = useTranslations("invitationAccept");
  const tRole = useTranslations("invitationsInbox");
  const router = useRouter();
  const searchParams = useSearchParams();
  const params = useParams();
  const locale = (params?.locale as string) ?? "en";

  const { isAuthenticated, isLoading } = useAuth();

  const token = useMemo(() => searchParams.get("token") ?? "", [searchParams]);

  const [status, setStatus] = useState<Status>(token ? "idle" : "missing-token");
  const [errorMessage, setErrorMessage] = useState<string>("");
  const [failedAction, setFailedAction] = useState<FailedAction>("accept");
  const [emailNotVerified, setEmailNotVerified] = useState(false);
  const [acceptedOrg, setAcceptedOrg] = useState<Organization | null>(null);
  const [info, setInfo] = useState<InvitationInfo | null>(null);
  const infoTokenRef = useRef<string | null>(null);

  // Register-form fields (brand-new invitee path).
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [registering, setRegistering] = useState(false);

  // Step 1: look up the invitation once the token and the auth state are known.
  // CE needs it for every visitor (register-vs-sign-in); cloud only for a signed-in
  // invitee, who must see the workspace, role and inviter before consenting. In
  // cloud an unauthenticated invitee simply signs in first (Keycloak), as before.
  useEffect(() => {
    if (!token || isLoading) return;
    if (!IS_CE && !isAuthenticated) {
      setStatus("sign-in");
      return;
    }
    if (infoTokenRef.current === token) return;
    infoTokenRef.current = token;
    setStatus("loading-info");
    organizationApi
      .getInvitationInfo(token)
      .then((result) => setInfo(result))
      .catch(() => setInfo({ valid: false }));
  }, [token, isLoading, isAuthenticated]);

  // Step 2: derive the screen from the lookup. A signed-in invitee gets the
  // explicit consent card; an anonymous CE visitor registers or signs in.
  useEffect(() => {
    if (!token || isLoading || info === null) return;
    if (
      status === "accepting" ||
      status === "accepted" ||
      status === "declining" ||
      status === "declined" ||
      status === "error"
    ) {
      return;
    }
    if (!info.valid) {
      setStatus("invalid");
    } else if (isAuthenticated) {
      setStatus("confirm");
    } else if (info.hasAccount) {
      setStatus("sign-in");
    } else {
      setStatus("register");
    }
  }, [token, isLoading, isAuthenticated, info, status]);

  const failWith = useCallback(
    (action: FailedAction, e: unknown) => {
      setFailedAction(action);
      // Never show the backend message (English, technical): map the refusal
      // to a translated explanation the invitee can act on.
      const status = typeof e === "object" && e !== null ? (e as { status?: unknown }).status : undefined;
      if (isInvitationEmailNotVerifiedError(e)) {
        setEmailNotVerified(true);
        setErrorMessage(t("emailNotVerifiedBody"));
      } else {
        setEmailNotVerified(false);
        setErrorMessage(
          status === 403
            ? t("errorWrongAccount")
            : action === "decline"
              ? t("declineFallbackError")
              : t("errorGeneric")
        );
      }
      setStatus("error");
    },
    [t]
  );

  // Step 3: the user clicked Accept.
  const handleAccept = useCallback(async () => {
    setStatus("accepting");
    try {
      const org = await organizationApi.acceptInvitation(token);
      setAcceptedOrg(org);
      setStatus("accepted");
      setTimeout(() => {
        router.push(`/${locale}/app/settings/organization`);
      }, 1500);
    } catch (e: unknown) {
      failWith("accept", e);
    }
  }, [token, router, locale, failWith]);

  // Step 3 (alternative): the user clicked Decline.
  const handleDecline = useCallback(async () => {
    setStatus("declining");
    try {
      await organizationApi.declineInvitation(token);
      setStatus("declined");
    } catch (e: unknown) {
      failWith("decline", e);
    }
  }, [token, failWith]);

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

  // Signed-in invitee: explicit consent. Show the workspace, the role and the
  // inviter, and act only on a click.
  if (status === "confirm" && info?.valid) {
    const org = info.organizationName;
    const role = info.role ? tRole(`role.${info.role}`) : null;
    return (
      <AuthCard
        title={org ? t("confirmTitle", { org }) : t("confirmTitleGeneric")}
        body={t("confirmBody")}
      >
        <dl className="mb-6 space-y-2 rounded-[10px] border border-[var(--border-color)] bg-[var(--bg-secondary)] px-3.5 py-3 text-sm">
          {org ? (
            <div className="flex justify-between gap-4">
              <dt className="text-[var(--text-secondary)]">{t("workspaceLabel")}</dt>
              <dd className="font-medium text-[var(--text-primary)]">{org}</dd>
            </div>
          ) : null}
          {role ? (
            <div className="flex justify-between gap-4">
              <dt className="text-[var(--text-secondary)]">{t("roleLabel")}</dt>
              <dd className="font-medium text-[var(--text-primary)]">{role}</dd>
            </div>
          ) : null}
          {info.inviterName ? (
            <div className="flex justify-between gap-4">
              <dt className="text-[var(--text-secondary)]">{t("invitedByLabel")}</dt>
              <dd className="font-medium text-[var(--text-primary)]">{info.inviterName}</dd>
            </div>
          ) : null}
        </dl>
        <button type="button" onClick={handleAccept} className={PRIMARY_BTN}>
          <span>{t("acceptCta")}</span>
          <ArrowRight className="h-3.5 w-3.5" strokeWidth={2.5} />
        </button>
        <button type="button" onClick={handleDecline} className={SECONDARY_BTN}>
          {t("declineCta")}
        </button>
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

  if (status === "declining") {
    return <AuthCard title={t("decliningTitle")} spinner />;
  }

  if (status === "declined") {
    return (
      <AuthCard title={t("declinedTitle")} body={t("declinedBody")}>
        <Link href={`/${locale}/app/settings/organization`} className={PRIMARY_BTN}>
          {t("goToOrganizations")}
        </Link>
      </AuthCard>
    );
  }

  // error
  const errorTitle = emailNotVerified
    ? t("emailNotVerifiedTitle")
    : failedAction === "decline"
      ? t("declineErrorTitle")
      : t("errorTitle");
  return (
    <AuthCard title={errorTitle} body={errorMessage}>
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
