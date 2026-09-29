/**
 * The text of `robots.txt`, built for one request.
 *
 * <p>This replaces the `app/robots.ts` metadata route, which never sees the
 * request, so it answered the SAME host hint on every hostname, telling
 * docs.livecontext.ai that its canonical host was livecontext.ai.
 *
 * <p>Crawl policy: every crawler, search engine or AI agent, training or
 * answering, may read the whole public site. Only the private surfaces below are
 * off limits, and they sit behind a login anyway. There is deliberately no
 * `Content-Signal` line: it is not part of the robots.txt standard, so
 * validators (Lighthouse's SEO audit among them) report it as an unknown
 * directive, and the only thing it would say here, "everything allowed", is what
 * `Allow: /` already says.
 *
 * <p>Pure and request-free on purpose: the route handler passes a host and an
 * edition, so every rule below is unit-testable without a request object.
 */
import { isDocsHost } from '@/lib/docs/docsHostRewrite';
import { sitemapUrls } from './sitemaps';
import { DOCS_ORIGIN } from './siteUrl';

export { DOCS_ORIGIN };

/**
 * Private surfaces that live under the `app/[locale]` tree. With
 * `localePrefix: 'as-needed'` they are reachable both bare (default locale) and
 * under every locale prefix, so the disallow list must cover all of them.
 */
const LOCALIZED_PRIVATE_PATHS = [
  '/app/',
  '/onboarding',
  '/ce-setup',
  '/login',
  '/register',
  '/auth/',
] as const;

/** Private surfaces that exist at one URL only, outside the locale tree. */
const BARE_PRIVATE_PATHS = [
  '/local-mcp',
  '/workflows/',
  '/billing/',
  '/f/',
  '/s/',
  '/w/embed',
] as const;

export interface RobotsTxtInput {
  /** Self-hosted editions must never appear in public search results. */
  isCe: boolean;
  /** The request `Host` header, which decides the host hint. */
  host: string | null | undefined;
  /** The public origin of the main site. */
  siteUrl: string;
  /** Every locale the router serves, so the disallow list covers all prefixes. */
  locales: readonly string[];
}

/**
 * Every path no crawler may fetch, in the order they are written out.
 *
 * Kept to a single `*` group on purpose: robots.txt groups do NOT inherit, so
 * naming a crawler in a group of its own would silently free it from every rule
 * listed here.
 */
export function privatePaths(locales: readonly string[]): string[] {
  const localized = LOCALIZED_PRIVATE_PATHS.flatMap((path) => [
    path,
    ...locales.map((locale) => `/${locale}${path}`),
  ]);
  return ['/api/', ...localized, ...BARE_PRIVATE_PATHS];
}

function everyAgent(directives: string[]): string {
  return ['User-agent: *', ...directives].join('\n');
}

export function buildRobotsTxt(input: RobotsTxtInput): string {
  // A self-hosted install is someone else's deployment on someone else's
  // domain. The build cannot know that domain, and none of it belongs in a
  // public index, so it gets the shortest possible answer.
  if (input.isCe) {
    return `${everyAgent(['Disallow: /'])}\n`;
  }

  const disallow = privatePaths(input.locales).map((path) => `Disallow: ${path}`);
  // The same question the proxy asks to decide whether a path belongs to the
  // docs, asked with the same function: a second copy of that rule would make
  // the two disagree the day the subdomain moves.
  const hostHint = isDocsHost(input.host) ? DOCS_ORIGIN : input.siteUrl;

  const blocks = [
    '# Crawl policy: every crawler, search engine or AI agent, may read the',
    '# public site. Only the private, logged-in surfaces are off limits.',
    '',
    everyAgent(['Allow: /', ...disallow]),
    '',
    // Legacy, and kept only because the site already served it: Yandex, the one
    // engine that ever read `Host`, dropped it in 2018. It is answered per host
    // rather than frozen to the apex because a file that states the wrong thing
    // is worse than one that states a dead thing.
    `Host: ${hostHint}`,
    // Always the main site's sitemaps, including from the docs host: they list
    // docs URLs, and a sitemap may carry another host's URLs only when that
    // host's own robots.txt declares it. This line is what makes that true.
    ...sitemapUrls(input.siteUrl).map((url) => `Sitemap: ${url}`),
  ];

  return `${blocks.join('\n')}\n`;
}
