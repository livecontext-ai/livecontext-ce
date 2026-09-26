import { routing } from '@/i18n/routing';
import { isDocsHost } from '@/lib/docs/docsHostRewrite';

// Public marketing/docs surfaces whose content MUST be server-rendered for
// SEO/GEO. On these paths the blocking auth UI in smart-providers (full-screen
// loading spinner + SessionGate) is skipped: during SSR `oidc.isLoading` is
// always true, so gating them used to ship spinner-only HTML for the entire
// public site (landing, /compare, /about, docs...), leaving nothing for
// crawlers that do not execute JavaScript. The auth context is still provided;
// public chrome (Sign in buttons) reads it and settles right after hydration.
// `/marketplace` is the public, crawlable listing tree (server-rendered, outside
// the `/app` SPA). It belongs here for exactly the reason above: it is the one
// public surface whose content comes from the backend rather than from the
// repo, so shipping spinner-only HTML would leave crawlers with nothing at all
// on the pages this whole effort exists to get indexed.
// `/u` is the public author page reached from a listing. It is server-rendered
// for the same reason and would hit the same spinner problem.
// `/status` belongs here for a stronger reason than SEO: it is the page someone
// opens BECAUSE the product is not working for them. Gating it behind the auth
// spinner means the one page that must answer during an incident answers with a
// spinner, and (since `oidc.isLoading` is always true during SSR) ships
// spinner-only HTML to anyone whose JavaScript or session is the problem.
// `/integrations` and `/models` are the two public catalogues (every integration,
// every model the platform runs). Both shipped WITHOUT being listed here and both
// served spinner-only HTML, measured on a production build: 49 KB and 67 KB of
// markup with not one integration or model in it. Nothing failed, which is the
// trap. The page renders perfectly once JavaScript runs, so a browser, a
// screenshot and a Playwright test that waits for hydration all agree it works,
// and only a crawler (or a `curl`) sees the spinner. `publicMarketingPathCoverage`
// in the tests now enumerates the pages that use the public chrome and fails when
// one is missing from this list, so the next public page cannot repeat it.
// `/videos` is the product-film library. Its pages exist to be READ (the problem,
// the answer, the chapters and the full transcript are the ranking asset; the film
// is the conversion asset), so spinner-only HTML would empty them of the only
// thing they are for. Measured before this entry: 52 KB and 127 KB of markup with
// the spinner at the top of the body.
const PUBLIC_MARKETING_PREFIXES = ['/compare', '/about', '/contact', '/legal', '/changelog', '/docs', '/marketplace', '/u', '/status', '/integrations', '/models', '/videos', '/for'];

// `host`: the documentation subdomain serves its pages at CLEAN paths (`/glossary`),
// rewritten onto the `/docs/*` routes. The server renders those pages under their
// `/docs/...` route (public by prefix), but in the browser `usePathname()` reports
// the clean path, which no prefix here covers. Without the host check the client
// took the page for a protected one during hydration and swapped it for the auth
// spinner: a React #418 hydration mismatch on every docs page but the Overview.
// Every docs page on that host is public, so the host answers for it. The one
// exception is the app itself (`/app`, with or without a locale): a few crafted URLs
// can still reach an app route on the docs host, and it must keep its auth gate
// there exactly as the server render does.
export function isPublicMarketingPath(pathname: string | null, host?: string | null): boolean {
  if (!pathname) return isDocsHost(host);
  const firstSegment = pathname.split('/')[1] ?? '';
  const withoutLocale = (routing.locales as readonly string[]).includes(firstSegment)
    ? pathname.slice(firstSegment.length + 1) || '/'
    : pathname;
  const isAppRoute = withoutLocale === '/app' || withoutLocale.startsWith('/app/');
  if (isDocsHost(host) && !isAppRoute) return true;
  if (withoutLocale === '/') return true;
  return PUBLIC_MARKETING_PREFIXES.some(
    (prefix) => withoutLocale === prefix || withoutLocale.startsWith(`${prefix}/`),
  );
}
