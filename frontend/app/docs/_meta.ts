import type { Metadata } from 'next';
import { DOCS_HOST } from '@/lib/docs/docsHostRewrite';

// The docs are the home of `docs.livecontext.ai`, served at CLEAN paths. The apex
// `livecontext.ai/docs/*` 308-redirects to the subdomain, so canonical URLs point
// at the subdomain and the two hosts never split SEO.
const DOCS_URL = `https://${DOCS_HOST}`;

/** Per-page metadata helper. `path` is the route (`/docs/agents`); the public,
 *  canonical URL is the clean subdomain one (`https://docs.livecontext.ai/agents`). */
export function docsMetadata(opts: { title: string; description: string; path: string }): Metadata {
  const clean = opts.path === '/docs' ? '/' : opts.path.replace(/^\/docs/, '');
  const url = `${DOCS_URL}${clean}`;
  return {
    title: opts.title,
    description: opts.description,
    alternates: { canonical: url },
    openGraph: {
      title: `${opts.title} · LiveContext Docs`,
      description: opts.description,
      url,
      type: 'article',
    },
  };
}
