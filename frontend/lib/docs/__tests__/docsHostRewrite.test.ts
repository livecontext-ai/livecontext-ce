import { describe, it, expect } from 'vitest';
import { resolveDocsRoute, DOCS_HOST } from '../docsHostRewrite';

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
