import { CATALOG_INTEGRATIONS_CLAIM } from '@/lib/integrations/integrationCount';
import { SITE_URL, homeHref } from './siteUrl';

/** The FAQ keys, in the order the page and its structured data list them. */
export const LANDING_FAQ_KEYS = ['what', 'code', 'selfHost', 'compare', 'pricing', 'models'] as const;

/**
 * The page's structured data, IN THE PAGE'S LANGUAGE.
 *
 * <p>It used to be a module-level English constant emitted on all six locales. That was
 * defensible while they were byte-identical duplicates canonicalising to the apex; it stopped
 * being defensible the moment each locale became its own canonical, because a French page now
 * declared itself French to a crawler through hreflang and handed it an English description
 * with no `inLanguage` at all. `app/[locale]/for/[persona]/page.tsx` already does this right,
 * and the locale layout's comment leans on it being right here too.
 *
 * <p>Every node carries `inLanguage`, and everything with prose in it is read from messages.
 * What stays hardcoded is what is not language: the URLs, the social profiles, the price and
 * the currency.
 */
/** A next-intl translator, narrowed to what this module calls. */
export type Copy = (key: string, values?: Record<string, string>) => string;

export function landingJsonLd(locale: string, meta: Copy, jsonLd: Copy, faq: Copy) {
  const url = `${SITE_URL.replace(/\/$/, '')}${homeHref(locale)}`;
  return [
    {
      '@context': 'https://schema.org',
      '@type': 'Organization',
      name: 'LiveContext',
      url: SITE_URL,
      logo: `${SITE_URL}/liveContext-logo.png`,
      sameAs: [
        'https://www.linkedin.com/company/livecontext/',
        'https://x.com/livecontextai',
        'https://www.instagram.com/livecontext.ai/',
        'https://github.com/livecontext-ai',
        'https://www.tiktok.com/@livecontextai',
      ],
    },
    {
      '@context': 'https://schema.org',
      '@type': 'WebSite',
      name: 'LiveContext',
      url,
      inLanguage: locale,
      description: meta('description'),
    },
    {
      '@context': 'https://schema.org',
      '@type': 'SoftwareApplication',
      name: 'LiveContext',
      applicationCategory: 'BusinessApplication',
      operatingSystem: 'Web',
      url,
      inLanguage: locale,
      description: jsonLd('softwareDescription', { integrations: CATALOG_INTEGRATIONS_CLAIM }),
      offers: {
        '@type': 'Offer',
        price: '0',
        priceCurrency: 'USD',
        description: jsonLd('offerDescription'),
      },
    },
    {
      '@context': 'https://schema.org',
      '@type': 'FAQPage',
      inLanguage: locale,
      mainEntity: LANDING_FAQ_KEYS.map((key) => ({
        '@type': 'Question',
        name: faq(`${key}.question`),
        acceptedAnswer: {
          '@type': 'Answer',
          text: faq(`${key}.answer`, { integrations: CATALOG_INTEGRATIONS_CLAIM }),
        },
      })),
    },
  ];
}

// THE FAQPage NODE IS BACK, because the section it describes is now on the page.
//
// It was removed while `FaqSection` was defined and never mounted: Google requires FAQPage
// content to be VISIBLE on the page, so the markup was non-compliant, and it would have
// shipped that on six indexable locales instead of one. Mounting the section is what makes
// the node legitimate again, and the two must move together - a reader who deletes
// `<FaqSection />` from the page owes this node the same deletion.
//
// It buys indexable content, not a rich result: FAQ rich results have been restricted to
// government and health sites since 2023. The value is that six questions a visitor actually
// asks are now text a crawler reads, in its own language, rather than strings sitting unused
// in the i18n payload.
//
// The answers are built with the SAME `{integrations}` substitution the page renders, which
// is not a detail: structured data that does not match the visible text is the other half of
// the compliance rule, and a placeholder left unsubstituted here would say
// "{integrations} integrations" to a crawler under a page that says a number.
