import { describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/edition', () => ({ IS_CE: false }));

import { generateMetadata, generateStaticParams } from '../[slug]/page';
import { metadata as hubMetadata } from '../page';
import { COMPARISONS } from '../_lib/comparisons';

const SITE = 'https://livecontext.ai';

describe('/compare/[slug] metadata (cloud edition)', () => {
  it('statically generates exactly one page per comparison', () => {
    expect(generateStaticParams()).toEqual(COMPARISONS.map((c) => ({ slug: c.slug })));
  });

  it('every comparison page declares a self-referential canonical', async () => {
    for (const comparison of COMPARISONS) {
      const meta = await generateMetadata({ params: Promise.resolve({ slug: comparison.slug }) });
      expect(meta.alternates?.canonical).toBe(`${SITE}/compare/${comparison.slug}`);
      expect(meta.title).toBe(comparison.metaTitle);
      expect(meta.description).toBe(comparison.metaDescription);
      // Cloud edition: indexable (no robots override).
      expect(meta.robots).toBeUndefined();
    }
  });

  it('keeps the og:image on comparison pages (shallow openGraph override must not drop it)', async () => {
    const meta = await generateMetadata({ params: Promise.resolve({ slug: 'n8n-alternative' }) });
    const og = meta.openGraph as { images?: unknown[]; siteName?: string; url?: string };
    expect(og?.images?.length).toBeGreaterThan(0);
    expect(og?.siteName).toBe('LiveContext');
    expect(og?.url).toBe(`${SITE}/compare/n8n-alternative`);
  });

  it('returns empty metadata for an unknown slug', async () => {
    expect(await generateMetadata({ params: Promise.resolve({ slug: 'nope' }) })).toEqual({});
  });

  it('the /compare hub declares its own canonical and keeps an og:image', () => {
    expect(hubMetadata.alternates?.canonical).toBe(`${SITE}/compare`);
    const og = hubMetadata.openGraph as { images?: unknown[] };
    expect(og?.images?.length).toBeGreaterThan(0);
  });
});
