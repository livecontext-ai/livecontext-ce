import { describe, it, expect } from 'vitest';
import { resolveDocsRoute, docsHref, isDocsHost, DOCS_HOST } from '../docsHostRewrite';

describe('resolveDocsRoute', () => {
  it('rewrites clean paths on the docs host onto the /docs routes', () => {
    expect(resolveDocsRoute('docs.livecontext.ai', '/')).toEqual({ kind: 'rewrite', pathname: '/docs' });
    expect(resolveDocsRoute('docs.livecontext.ai', '/agents')).toEqual({ kind: 'rewrite', pathname: '/docs/agents' });
    expect(resolveDocsRoute('docs.livecontext.ai', '/tables')).toEqual({ kind: 'rewrite', pathname: '/docs/tables' });
  });

  it('redirects a stray /docs-prefixed URL on the docs host to the clean path', () => {
    expect(resolveDocsRoute('docs.livecontext.ai', '/docs')).toEqual({ kind: 'redirect', url: '/' });
    expect(resolveDocsRoute('docs.livecontext.ai', '/docs/agents')).toEqual({ kind: 'redirect', url: '/agents' });
  });

  it('redirects apex /docs/* to the clean subdomain URL', () => {
    expect(resolveDocsRoute('livecontext.ai', '/docs')).toEqual({ kind: 'redirect', url: `https://${DOCS_HOST}/` });
    expect(resolveDocsRoute('livecontext.ai', '/docs/tables')).toEqual({ kind: 'redirect', url: `https://${DOCS_HOST}/tables` });
  });

  it('is a no-op for non-docs paths on the apex (and any other non-docs host)', () => {
    expect(resolveDocsRoute('livecontext.ai', '/')).toBeNull();
    expect(resolveDocsRoute('livecontext.ai', '/agents')).toBeNull();
    expect(resolveDocsRoute('livecontext.ai', '/fr')).toBeNull();
    expect(resolveDocsRoute('app.livecontext.ai', '/agents')).toBeNull();
  });

  it('ignores the port and is case-insensitive on the host', () => {
    expect(resolveDocsRoute('docs.livecontext.ai:3000', '/agents')).toEqual({ kind: 'rewrite', pathname: '/docs/agents' });
    expect(resolveDocsRoute('DOCS.LiveContext.ai', '/agents')).toEqual({ kind: 'rewrite', pathname: '/docs/agents' });
  });

  it('does not treat a host that merely contains "docs" as the docs subdomain', () => {
    // 'mydocs.' does not start with 'docs.', so a clean path there is a no-op.
    expect(resolveDocsRoute('mydocs.livecontext.ai', '/agents')).toBeNull();
  });
});

describe('isDocsHost', () => {
  // Exported for the proxy, which asks it before claiming a path name for the
  // main site: on the docs subdomain that name may belong to a docs page.
  it('is true for the docs subdomain, whatever the port or casing', () => {
    expect(isDocsHost('docs.livecontext.ai')).toBe(true);
    expect(isDocsHost('docs.livecontext.ai:3000')).toBe(true);
    expect(isDocsHost('DOCS.LiveContext.ai')).toBe(true);
  });

  it('is false for every other host, and for a missing one', () => {
    expect(isDocsHost('livecontext.ai')).toBe(false);
    expect(isDocsHost('app.livecontext.ai')).toBe(false);
    expect(isDocsHost('mydocs.livecontext.ai')).toBe(false);
    expect(isDocsHost(null)).toBe(false);
    expect(isDocsHost(undefined)).toBe(false);
  });
});

describe('docsHref', () => {
  const APEX = undefined;
  const ON_DOCS_HOST = 'https://livecontext.ai';

  it('links to the /docs tree from the apex', () => {
    expect(docsHref(APEX)).toBe('/docs');
    expect(docsHref(APEX, 'workflows')).toBe('/docs/workflows');
  });

  it('links to the clean path when the chrome renders on the docs host', () => {
    expect(docsHref(ON_DOCS_HOST)).toBe('/');
    expect(docsHref(ON_DOCS_HOST, 'workflows')).toBe('/workflows');
  });

  it('produces a link the router actually resolves, on either host', () => {
    // Apex: the /docs form is exactly what 308s to the canonical subdomain URL.
    expect(resolveDocsRoute('livecontext.ai', docsHref(APEX, 'agents'))).toEqual({
      kind: 'redirect',
      url: `https://${DOCS_HOST}/agents`,
    });
    // Docs host: the clean form is exactly what rewrites onto the real route.
    expect(resolveDocsRoute(DOCS_HOST, docsHref(ON_DOCS_HOST, 'agents'))).toEqual({
      kind: 'rewrite',
      pathname: '/docs/agents',
    });
  });
});
