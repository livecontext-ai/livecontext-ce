import { type Locale } from '@/i18n/routing';
import { getClientLocale } from '@/lib/utils/locale';

/**
 * Localized copy for the session-gate interstitial rendered by
 * `ResourceManagerProvider` in `smart-providers.tsx`.
 *
 * That gate renders ABOVE `NextIntlClientProvider` (it lives in the root layout's
 * provider tree and replaces all children while the user is unauthenticated), so
 * next-intl hooks are NOT available here, exactly like `app/error.tsx` and
 * `app/not-found.tsx`. We therefore mirror the canonical strings from
 * `messages/<locale>.json` (`auth.login.title` / `subtitle` / `sessionExpired` /
 * `submit`) into this tiny synchronous table.
 *
 * `authGateMessages.test.ts` asserts this table stays byte-for-byte in sync with
 * `messages/*.json`, so any translation edit there fails the test until mirrored
 * here. There is no silent drift, and no 400 KB locale chunk is pulled just to
 * render this gate.
 */
export interface AuthGateStrings {
  /** Heading. `auth.login.title`. */
  title: string;
  /** Neutral call to action. `auth.login.subtitle`. */
  subtitle: string;
  /** Prefix shown only when a real session ended. `auth.login.sessionExpired`. */
  sessionExpired: string;
  /** Sign-in button label. `auth.login.submit`. */
  submit: string;
}

export const AUTH_GATE_STRINGS: Record<Locale, AuthGateStrings> = {
  en: { title: "Welcome back.", subtitle: "Sign in to continue where you left off.", sessionExpired: "Your session has expired.", submit: "Sign in" },
  fr: { title: "Bon retour.", subtitle: "Connectez-vous pour reprendre où vous en étiez.", sessionExpired: "Votre session a expiré.", submit: "Se connecter" },
  es: { title: "Bienvenido de nuevo.", subtitle: "Inicia sesión para continuar donde lo dejaste.", sessionExpired: "Tu sesión ha expirado.", submit: "Iniciar sesión" },
  de: { title: "Willkommen zurück.", subtitle: "Melden Sie sich an, um dort weiterzumachen, wo Sie aufgehört haben.", sessionExpired: "Ihre Sitzung ist abgelaufen.", submit: "Anmelden" },
  pt: { title: "Bem-vindo de volta.", subtitle: "Entre para continuar de onde parou.", sessionExpired: "Sua sessão expirou.", submit: "Entrar" },
  zh: { title: "欢迎回来。", subtitle: "登录以从上次离开的地方继续。", sessionExpired: "您的会话已过期。", submit: "登录" },
};

/**
 * Resolve the gate strings for the active app locale (URL prefix -> NEXT_LOCALE
 * cookie -> 'en'), falling back to English for any unknown locale.
 */
export function getAuthGateStrings(locale: string = getClientLocale()): AuthGateStrings {
  return AUTH_GATE_STRINGS[locale as Locale] ?? AUTH_GATE_STRINGS.en;
}

/**
 * Body copy for the session gate.
 *
 * `sessionExpired === true` means a previously-valid session ended (cross-tab
 * logout, the persisted OIDC user vanished, or the login-redirect loop breaker
 * tripped): we prepend the "Your session has expired." sentence. `false` means
 * the user is simply not signed in yet (cold first visit on a fresh slot / CE
 * with no prior login, or a transient post-signin backend 401) - none of which
 * is an expiry - so we show only the neutral subtitle and never falsely claim a
 * session expired.
 *
 * The two sentences are joined with a space, except when the expiry sentence
 * already ends in CJK full-width terminal punctuation (e.g. zh "。"), where a
 * space would be a typographic error - those scripts do not space between
 * sentences.
 */
export function authGateBody(strings: AuthGateStrings, sessionExpired: boolean): string {
  if (!sessionExpired) return strings.subtitle;
  const separator = /[。！？]$/.test(strings.sessionExpired) ? '' : ' ';
  return `${strings.sessionExpired}${separator}${strings.subtitle}`;
}
