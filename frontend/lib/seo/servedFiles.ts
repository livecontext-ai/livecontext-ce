/**
 * Which dotted URLs are a file this site actually serves.
 *
 * <p><strong>Why this exists.</strong> The middleware turns an unknown path into
 * a real 404, because the dynamic `[locale]` segment otherwise answers 200 with
 * the prerendered landing page (see PUBLIC_INDEX_SEGMENTS in `proxy.ts`). That
 * guard skipped every path containing a dot, which left a whole class open:
 * `/indexnow.txt`, `/BingSiteAuth.xml`, `/sitemap_index.xml`, `/wp-login.php`
 * and any other invented name with an extension all answered 200 with an HTML
 * body. Measured in production, and reported by search consoles as Soft 404.
 *
 * <p>It also made a verification file impossible to check: asking for
 * `/indexnow.txt` on a deployment that has none returns 200 either way, so
 * "the key is installed" could not be told from "the key is missing".
 *
 * <p><strong>Every rule here matches a WHOLE path or a directory prefix, never
 * a suffix.</strong> A suffix rule reads as harmless and is not: matching
 * "anything ending in sitemap.xml" hands 200 to `/wp-content/sitemap.xml` and
 * `/wp-admin/robots.txt`, which are among the most probed URLs on the web, and
 * re-opens exactly the class this module closes.
 *
 * <p>An allow list is the only shape available (the bad set is unbounded, and
 * middleware cannot stat the filesystem), so it rots the moment someone adds a
 * file to `public/`. `__tests__/servedFiles.test.ts` reads that directory from
 * disk and fails when the two disagree, which is what keeps the rot a CI
 * failure instead of a file that silently 404s.
 *
 * <p><strong>`/.well-known/*` is refused, which includes `acme-challenge`.</strong>
 * This deployment serves nothing there: TLS terminates upstream on both the
 * cloud ingress and the edge, so no HTTP-01 challenge ever reaches Next, and CE
 * disallows the whole site to crawlers anyway. A self-hoster who put Next
 * directly in front of an HTTP-01 issuer would need an entry here.
 */
import { SITEMAP_PATHS } from './sitemaps';

/**
 * Directories under `frontend/public/`. Everything below one of these is served
 * from disk, so the whole prefix is waved through without naming each file.
 */
export const PUBLIC_ASSET_DIRECTORIES = [
  'avatars',
  'changelog',
  'examples',
  'icons',
  'landing',
  'videos',
] as const;

/**
 * Files that sit at the ROOT of `frontend/public/`.
 *
 * Named one by one because the root is also where an invented name lands: a
 * prefix rule here would wave through exactly the paths this module exists to
 * refuse.
 */
export const PUBLIC_ROOT_FILES = [
  // The mark signed at the foot of every lifecycle email (deploy/lifecycle-emails).
  'email-signature-mark.png',
  'favicon.ico',
  'hero-flow.html',
  'liveContext-logo-light.png',
  'liveContext-logo.png',
  'liveContext-logo.svg',
  'llms.txt',
  // The IndexNow key file. Its whole job is to be fetchable at this exact
  // path: a submission whose key cannot be read is rejected.
  '4ad6f065a0c6c00ee09d73874348013f.txt',
  'og-image.jpg',
  'widget-demo.html',
] as const;

/**
 * Dotted paths Next serves from a ROUTE rather than from `public/`, matched
 * WHOLE.
 *
 * The sitemaps come from the list `robots.txt` advertises, so a section added
 * there cannot be advertised and then 404 here.
 */
export const METADATA_ROUTES: readonly string[] = ['/robots.txt', ...SITEMAP_PATHS];

/**
 * Build output and the framework's own paths, which never reach `public/`.
 *
 * Both prefixes need their separator: without one, `/__nextjsfoo.php` passes.
 */
function isFrameworkPath(pathname: string): boolean {
  return pathname.startsWith('/_next/') || pathname.startsWith('/__nextjs_');
}

/*
 * NOT here, and deliberately: Next's flight suffixes (`.rsc`, `.segment.rsc`,
 * `.segments/`, declared in `.next/routes-manifest.json`).
 *
 * They were added once, as a defence against a future adapter that requests
 * them on the page URL rather than through the `RSC` header (see the RSC note
 * in `proxy.ts`), and removed again the same day. A suffix rule is the one
 * shape this module forbids, for the reason at the top of the file, and this
 * one bought `/wp-login.rsc` and `/x.segments` back at 200 to defend against a
 * case that does not exist on this server. If the transport ever changes,
 * client navigation fails loudly and visibly, which is a better outcome than a
 * standing hole.
 */

/**
 * True when this dotted path is something the site serves, and false when it is
 * a name nobody published.
 *
 * Only ever asked about paths containing a dot: an extensionless path is
 * decided by `isKnownRoute` in the proxy, which is the older and broader guard.
 */
export function isServedFilePath(pathname: string): boolean {
  if (isFrameworkPath(pathname)) return true;
  if (METADATA_ROUTES.includes(pathname)) return true;

  const segments = pathname.split('/').filter(Boolean);
  if (segments.length === 0) return false;

  if (segments.length === 1) {
    return (PUBLIC_ROOT_FILES as readonly string[]).includes(segments[0]);
  }

  return (PUBLIC_ASSET_DIRECTORIES as readonly string[]).includes(segments[0]);
}
