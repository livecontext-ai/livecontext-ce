/**
 * The text of `robots.txt`, built for one request.
 *
 * <p>This replaces the `app/robots.ts` metadata route, which could not express
 * two things this file needs. It cannot emit a `Content-Signal` line, which is
 * the machine-readable half of the crawl policy; and it never sees the request,
 * so it answered the SAME host hint on every hostname, telling
 * docs.livecontext.ai that its canonical host was livecontext.ai.
 *
 * <p>Pure and request-free on purpose: the route handler passes a host and an
 * edition, so every rule below is unit-testable without a request object.
 */
import { isDocsHost } from '@/lib/docs/docsHostRewrite';
import { ANSWER_ENGINE_CRAWLERS, CONTENT_SIGNAL, TRAINING_CRAWLERS } from './crawlers';
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
 * Exported because the answer-engine group has to repeat it: robots.txt groups
 * do NOT inherit, so naming a crawler in its own group silently frees it from
 * every rule under `*`. A test asserts the two lists stay identical.
 */
export function privatePaths(locales: readonly string[]): string[] {
  const localized = LOCALIZED_PRIVATE_PATHS.flatMap((path) => [
    path,
    ...locales.map((locale) => `/${locale}${path}`),
  ]);
  return ['/api/', ...localized, ...BARE_PRIVATE_PATHS];
}

function group(userAgents: readonly string[], directives: string[]): string {
  return [...userAgents.map((agent) => `User-agent: ${agent}`), ...directives].join('\n');
}

export function buildRobotsTxt(input: RobotsTxtInput): string {
  // A self-hosted install is someone else's deployment on someone else's
  // domain. The build cannot know that domain, and none of it belongs in a
  // public index, so it gets the shortest possible answer.
  if (input.isCe) {
    return `${group(['*'], ['Disallow: /'])}\n`;
  }

  const disallow = privatePaths(input.locales).map((path) => `Disallow: ${path}`);
  // The same question the proxy asks to decide whether a path belongs to the
  // docs, asked with the same function: a second copy of that rule would make
  // the two disagree the day the subdomain moves.
  const hostHint = isDocsHost(input.host) ? DOCS_ORIGIN : input.siteUrl;

  const blocks = [
    '# Crawl policy: answers yes, training no.',
    '# The crawlers that feed an answer someone is reading get the public site;',
    '# the ones that build a training corpus get nothing. Content-Signal states',
    '# the same decision for anything that reads signals rather than group names,',
    '# and ai-train=no is an express reservation of rights under Article 4 of EU',
    '# Directive 2019/790.',
    '',
    group(['*'], [`Content-Signal: ${CONTENT_SIGNAL}`, 'Allow: /', ...disallow]),
    '',
    '# Answer engines. The rules under * do NOT carry over to a named group, so',
    '# the private paths, and the signals, are repeated here rather than',
    '# inherited. Without the repeat these would be the only crawlers allowed to',
    '# fetch the content and the only ones never told what may be done with it.',
    group([...ANSWER_ENGINE_CRAWLERS], [`Content-Signal: ${CONTENT_SIGNAL}`, 'Allow: /', ...disallow]),
    '',
    '# Training crawlers.',
    group([...TRAINING_CRAWLERS], ['Disallow: /']),
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
