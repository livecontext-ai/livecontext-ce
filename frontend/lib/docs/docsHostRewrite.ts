/**
 * Routing decision for the documentation subdomain.
 *
 * The docs are the "home" of `docs.livecontext.ai`, served at CLEAN paths
 * (`docs.livecontext.ai/agents`), while the actual Next.js routes live under
 * `/docs/*`. This pure helper, called from the proxy (Next middleware), maps a
 * request `host` + `pathname` to one of:
 *
 *  - `{ kind: 'rewrite', pathname }` - on the docs host, a clean path is rewritten
 *    onto its `/docs/...` route so it renders (the browser URL stays clean).
 *  - `{ kind: 'redirect', url }` - make the subdomain canonical:
 *      • apex `livecontext.ai/docs/...` → 308 to `https://docs.livecontext.ai/...`
 *      • a stray `docs.livecontext.ai/docs/...` → 308 to the clean `/...`
 *  - `null` - leave the request untouched (every other host/path).
 *
 * Pure + side-effect free so it is unit-tested without a request object.
 */
export const DOCS_HOST = 'docs.livecontext.ai';

export type DocsRouteAction =
  | { kind: 'rewrite'; pathname: string }
  | { kind: 'redirect'; url: string }
  | null;

/** `/docs/agents` → `/agents`, `/docs` → `/`. */
function stripDocsPrefix(pathname: string): string {
  if (pathname === '/docs') return '/';
  return pathname.slice('/docs'.length);
}

export function resolveDocsRoute(host: string | null | undefined, pathname: string): DocsRouteAction {
  const hostname = (host ?? '').split(':')[0].toLowerCase();
  const isDocsHost = hostname.startsWith('docs.');
  const underDocs = pathname === '/docs' || pathname.startsWith('/docs/');

  if (isDocsHost) {
    // A stray `/docs`-prefixed URL on the subdomain → send it to the clean path.
    if (underDocs) return { kind: 'redirect', url: stripDocsPrefix(pathname) };
    // Clean path on the subdomain → render the underlying `/docs/...` route.
    return { kind: 'rewrite', pathname: pathname === '/' ? '/docs' : `/docs${pathname}` };
  }

  // Apex (or any non-docs host): the docs live on the subdomain now.
  if (underDocs) return { kind: 'redirect', url: `https://${DOCS_HOST}${stripDocsPrefix(pathname)}` };

  return null;
}
