import { describe, expect, it, vi, beforeEach } from 'vitest';

/**
 * Regression guard for the marketplace OG card.
 *
 * The card 502'd in production for EVERY real listing while a slug with no
 * publication answered 200, because Satori (the renderer behind
 * `ImageResponse`) rejects an element that has more than one child and no
 * explicit `display` - and `by {author}` is two children. Only a found
 * publication has an author, so the failing element only ever rendered on the
 * path that mattered.
 *
 * These tests therefore ACTUALLY RASTERISE the card: asserting on the returned
 * object alone would pass on the broken code, since `ImageResponse` builds its
 * headers before drawing anything (which is exactly why HEAD kept answering
 * 200 in production while GET failed).
 */

const fetchPublicationBySlug = vi.fn();

vi.mock('@/lib/marketplace/publicPublications', () => ({
  fetchPublicationBySlug: (slug: string) => fetchPublicationBySlug(slug),
}));

import OpengraphImage from '../opengraph-image';

const listing = {
  publicSlug: 'reconcile',
  title: 'Reconcile',
  description: 'Match bank lines against invoices and flag what does not add up.',
  publisherName: 'LiveContext Labs',
};

async function renderCard(slug: string): Promise<Buffer> {
  const response = await OpengraphImage({ params: Promise.resolve({ slug }) });
  return Buffer.from(await response.arrayBuffer());
}

describe('marketplace opengraph-image', () => {
  beforeEach(() => {
    fetchPublicationBySlug.mockReset();
  });

  it('rasterises a PNG for a listing that has a publisher name', async () => {
    fetchPublicationBySlug.mockResolvedValue(listing);

    const png = await renderCard('reconcile');

    // PNG magic number: proof the bytes were really drawn, not just promised.
    expect(png.subarray(0, 4).toString('hex')).toBe('89504e47');
    expect(png.byteLength).toBeGreaterThan(1_000);
  });

  it('rasterises a PNG when the listing has no publisher name', async () => {
    fetchPublicationBySlug.mockResolvedValue({ ...listing, publisherName: undefined });

    const png = await renderCard('reconcile');

    expect(png.subarray(0, 4).toString('hex')).toBe('89504e47');
  });

  it('rasterises the generic fallback card for an unknown slug', async () => {
    fetchPublicationBySlug.mockResolvedValue(null);

    const png = await renderCard('does-not-exist');

    expect(png.subarray(0, 4).toString('hex')).toBe('89504e47');
  });
});
