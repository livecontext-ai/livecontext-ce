import { describe, expect, it } from 'vitest';
import { docsMetadata } from '@/app/docs/_meta';

/**
 * Next replaces `openGraph` and `twitter` wholesale instead of merging them with
 * the root layout's. A docs page that set only a title and a URL therefore
 * shared with no image, and inherited the landing's twitter title and
 * description.
 */
describe('docsMetadata social cards', () => {
  const meta = docsMetadata({ title: 'Workflows', description: 'Build workflows on the canvas.', path: '/docs/workflows' });

  it('gives the Open Graph card an image and a site name', () => {
    expect(meta.openGraph).toMatchObject({
      siteName: 'LiveContext',
      images: [expect.objectContaining({ url: '/og-image.jpg', width: 1200, height: 630 })],
    });
  });

  it('states its own twitter card instead of inheriting the landing one', () => {
    expect(meta.twitter).toMatchObject({
      card: 'summary_large_image',
      title: 'Workflows · LiveContext Docs',
      description: 'Build workflows on the canvas.',
      images: ['/og-image.jpg'],
    });
  });

  it('keeps the canonical and the share URL on the docs subdomain', () => {
    expect(meta.alternates?.canonical).toBe('https://docs.livecontext.ai/workflows');
    expect(meta.openGraph).toMatchObject({ url: 'https://docs.livecontext.ai/workflows' });
  });
});
