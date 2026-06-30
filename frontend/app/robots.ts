import type { MetadataRoute } from 'next';
import { IS_CE } from '@/lib/edition';

const SITE_URL = 'https://livecontext.ai';

export default function robots(): MetadataRoute.Robots {
  // CE deployments are self-hosted and must never appear in public search results.
  // Disallow everything; no sitemap; no host hint (the build doesn't know the deployer's domain).
  if (IS_CE) {
    return {
      rules: [{ userAgent: '*', disallow: '/' }],
    };
  }

  return {
    rules: [
      {
        userAgent: '*',
        allow: '/',
        disallow: [
          '/api/',
          '/app/',
          '/en/app/',
          '/fr/app/',
          '/en/onboarding',
          '/fr/onboarding',
          '/en/ce-setup',
          '/fr/ce-setup',
          '/local-mcp',
          '/workflows/',
          '/billing/',
          '/f/',
          '/s/',
          '/w/embed',
        ],
      },
    ],
    sitemap: `${SITE_URL}/sitemap.xml`,
    host: SITE_URL,
  };
}
