import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';

/**
 * The handler, not the policy.
 *
 * `robots.test.ts` covers what the file SAYS. This covers the three things only
 * the route can get wrong, and each of them ships green without a test: the
 * body reaching the wire as html, one host's answer being cached and served to
 * the other, and the request's host never being read at all, which is the exact
 * bug the move away from the metadata convention exists to fix.
 */
vi.mock('@/lib/edition', () => ({ IS_CE: false }));

function get(host: string | null) {
  const request = new NextRequest('https://livecontext.ai/robots.txt', {
    headers: host ? { host } : {},
  });
  return import('../robots.txt/route').then(({ GET }) => GET(request));
}

describe('robots.txt route', () => {
  beforeEach(() => vi.resetModules());

  it('serves plain text, not html', async () => {
    const response = await get('livecontext.ai');
    expect(response.headers.get('Content-Type')).toBe('text/plain; charset=utf-8');
    expect(await response.text()).toContain('User-agent: *');
  });

  it('varies on Host and is never rendered statically', async () => {
    // Two hostnames share one deployment and get DIFFERENT bodies. Without both
    // of these, an edge cache or a build-time render hands the docs subdomain
    // the apex answer, which is the bug this route exists to remove.
    const response = await get('livecontext.ai');
    expect(response.headers.get('Vary')).toBe('Host');

    const { dynamic } = await import('../robots.txt/route');
    expect(dynamic).toBe('force-dynamic');
  });

  it('reads the host off the REQUEST, so the docs subdomain gets its own answer', async () => {
    const apex = await (await get('livecontext.ai')).text();
    const docs = await (await get('docs.livecontext.ai')).text();

    expect(apex).toContain('Host: https://livecontext.ai');
    expect(docs).toContain('Host: https://docs.livecontext.ai');
    expect(apex).not.toBe(docs);
  });

  it('answers without a host header instead of failing the request', async () => {
    const response = await get(null);
    expect(response.status).toBe(200);
    expect(await response.text()).toContain('Host: https://livecontext.ai');
  });
});

describe('robots.txt route - community edition', () => {
  beforeEach(() => vi.resetModules());

  it('serves the shut-everything answer on a self-hosted install', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
    const { GET } = await import('../robots.txt/route');
    const body = await GET(new NextRequest('https://whatever.internal/robots.txt')).text();

    expect(body).toBe('User-agent: *\nDisallow: /\n');
  });
});
