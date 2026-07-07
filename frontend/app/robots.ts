import type { MetadataRoute } from 'next';
import { routing } from '@/i18n/routing';
import { IS_CE } from '@/lib/edition';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

// Private surfaces that live under the `app/[locale]` tree. With
// `localePrefix: 'as-needed'` they are reachable both bare (default locale)
// and under every locale prefix, so the disallow list must cover all of them.
const LOCALIZED_PRIVATE_PATHS = ['/app/', '/onboarding', '/ce-setup', '/login', '/register', '/auth/'];

export default function robots(): MetadataRoute.Robots {
  // CE deployments are self-hosted and must never appear in public search results.
  // Disallow everything; no sitemap; no host hint (the build doesn't know the deployer's domain).
  if (IS_CE) {
    return {
      rules: [{ userAgent: '*', disallow: '/' }],
    };
  }

  const localizedDisallow = LOCALIZED_PRIVATE_PATHS.flatMap((path) => [
    path,
    ...routing.locales.map((locale) => `/${locale}${path}`),
  ]);

  return {
    rules: [
      {
        userAgent: '*',
        allow: '/',
        disallow: [
          '/api/',
          ...localizedDisallow,
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
