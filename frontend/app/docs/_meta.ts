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
  const shareTitle = `${opts.title} · LiveContext Docs`;
  // Next replaces `openGraph` and `twitter` as whole objects instead of merging
  // them with the root layout's, so the image, the site name and the twitter card
  // must be restated here: without them a docs page shared with no preview image
  // and carried the landing's twitter title and description.
  return {
    title: opts.title,
    description: opts.description,
    alternates: { canonical: url },
    openGraph: {
      title: shareTitle,
      description: opts.description,
      url,
      siteName: 'LiveContext',
      type: 'article',
      images: [{ url: '/og-image.jpg', width: 1200, height: 630, alt: shareTitle }],
    },
    twitter: {
      card: 'summary_large_image',
      title: shareTitle,
      description: opts.description,
      images: ['/og-image.jpg'],
    },
  };
}
