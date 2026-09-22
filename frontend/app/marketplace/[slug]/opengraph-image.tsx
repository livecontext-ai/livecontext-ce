import { ImageResponse } from 'next/og';
import { fetchPublicationBySlug } from '@/lib/marketplace/publicPublications';
import { metaDescription } from '@/lib/marketplace/indexability';

/**
 * Per-listing OpenGraph card.
 *
 * The whole site otherwise shares one static og-image.jpg, so every shared
 * marketplace link looked identical in Slack, X and LinkedIn regardless of what
 * was being shared. This renders the listing's own title and author instead,
 * which is what makes a shared link worth clicking.
 *
 * Deliberately text-only: no remote images, no custom font fetch. Both would
 * add a network dependency to a route that must stay fast and can never fail,
 * since a broken OG image silently degrades every share of that page.
 *
 * EVERY text element below takes exactly ONE child, and interpolation is done
 * in the template string rather than by placing text beside an expression.
 * Satori (what `ImageResponse` renders with) throws on any element that has
 * more than one child and no explicit `display`, and JSX splits `by {author}`
 * into TWO children ('by ' and the value). That threw for every listing that
 * had a publisher name, which is all of them.
 *
 * Measured in production, and worth writing down because none of it points at
 * this file: GET answered 502 for every real slug, a slug with NO publication
 * answered 200 (that path renders no author, so it never built the bad
 * element), and HEAD answered 200 even for the broken slugs. So the symptom
 * read as an infrastructure fault, while the whole marketplace shared broken
 * preview cards.
 */
export const size = { width: 1200, height: 630 };
export const contentType = 'image/png';
export const alt = 'LiveContext marketplace listing';

export default async function OpengraphImage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const publication = await fetchPublicationBySlug(slug);

  const title = publication?.title ?? 'LiveContext Marketplace';
  const description = publication
    ? metaDescription(publication, 120)
    : 'Ready-made AI agents, workflows and apps.';
  const author = publication?.publisherName;

  return new ImageResponse(
    (
      <div
        style={{
          height: '100%',
          width: '100%',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'space-between',
          backgroundColor: '#0b0d16',
          padding: '72px',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: '28px' }}>
          <div style={{ fontSize: 28, color: '#9aa4b8', letterSpacing: '0.08em' }}>
            LIVECONTEXT MARKETPLACE
          </div>
          <div style={{ fontSize: 68, color: '#ffffff', lineHeight: 1.1, fontWeight: 700 }}>
            {title}
          </div>
          <div style={{ fontSize: 30, color: '#c3cad8', lineHeight: 1.35 }}>{description}</div>
        </div>
        {author && <div style={{ fontSize: 28, color: '#9aa4b8' }}>{`by ${author}`}</div>}
      </div>
    ),
    size,
  );
}
