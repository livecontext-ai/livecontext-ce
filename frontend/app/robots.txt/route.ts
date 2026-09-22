/**
 * `robots.txt`, served per host.
 *
 * <p>A route handler rather than the `app/robots.ts` metadata convention,
 * because the answer depends on the request: docs.livecontext.ai and the apex
 * are two hosts behind one deployment, and the metadata route, which never sees
 * a request, told the docs subdomain that its canonical host was the apex.
 *
 * <p>All of the policy lives in `lib/seo/robotsTxt.ts`, which is pure. This file
 * is the two lines that read the request.
 */
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { IS_CE } from '@/lib/edition';
import { routing } from '@/i18n/routing';
import { buildRobotsTxt } from '@/lib/seo/robotsTxt';
import { SITE_URL } from '@/lib/seo/siteUrl';

/** The body differs per host, so it must never be cached across hosts. */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  const body = buildRobotsTxt({
    isCe: IS_CE,
    host: request.headers.get('host'),
    siteUrl: SITE_URL,
    locales: routing.locales,
  });

  return new NextResponse(body, {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
      // Same window the metadata route used, plus the host in the cache key:
      // an edge cache in front of this serves two hostnames from one origin.
      'Cache-Control': 'public, max-age=0, must-revalidate',
      Vary: 'Host',
    },
  });
}
