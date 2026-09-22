import { locales, type Locale } from '@/i18n/routing';

/**
 * The personas, ORDERED BY WEIGHT for the platform, because this list is the order of the
 * pill nav and of the sitemap: operations first (the broadest buyer, and what the homepage
 * hero opens on), then creator, which is the one a visitor recognises fastest and the
 * section with the most to show, then support, sales, marketing, and recruiting, which is
 * the narrowest.
 */
export const PERSONA_KEYS = ['ops', 'creator', 'support', 'sales', 'marketing', 'recruiting'] as const;
export type PersonaKey = (typeof PERSONA_KEYS)[number];
export const CREATOR_STORY = { src: '/landing/creator/creator-ugc-coffee-v2.png', width: 1080, height: 1920 } as const;

export const CREATOR_EXAMPLE_KEYS = ['product', 'reel', 'cuts', 'cafe'] as const;
export type CreatorExampleKey = (typeof CREATOR_EXAMPLE_KEYS)[number];
const CREATOR_REEL_MEDIA = { src: 'https://assets.mixkit.co/active_storage/video_items/100345/1723061931/100345-video-720.mp4', poster: 'https://assets.mixkit.co/active_storage/video_items/100345/1723061931/100345-video-thumb-720-0.jpg', width: 1080, height: 1920, kind: 'video' } as const;
export const CREATOR_EXAMPLES: Record<CreatorExampleKey, { kind: 'image' | 'video'; src: string; poster?: string; width: number; height: number }> = {
  product: { src: '/landing/creator/morrow-ugc-product.png', width: 1080, height: 1920, kind: 'image' },
  reel: CREATOR_REEL_MEDIA,
  cuts: { src: 'https://assets.mixkit.co/videos/10428/10428-720.mp4', poster: 'https://assets.mixkit.co/videos/10428/10428-thumb-720-0.jpg', width: 1080, height: 1920, kind: 'video' },
  cafe: { ...CREATOR_STORY, kind: 'image' },
};

/**
 * Where each example ENDS: the tools the approved result is sent to, as slugs of
 * `WELL_KNOWN_INTEGRATIONS`. The hero draws them as the fan under the approval, the build
 * grid lists them on the card, and both read this so a card cannot promise a destination
 * the animation does not show.
 */
export const CREATOR_DESTINATION_SLUGS = ['instagram', 'tiktok', 'youtube-data-api', 'linkedin', 'reddit', 'twitter-x'] as const;

export type BusinessPersona = Exclude<PersonaKey, 'creator'>;
export const BUSINESS_EXAMPLE_KEYS = {
  ops: ['report', 'intake', 'stock'],
  support: ['reply', 'refund', 'incident'],
  sales: ['quote', 'prospect', 'followup'],
  marketing: ['campaign', 'seo', 'listening'],
  recruiting: ['shortlist', 'interview', 'onboarding'],
} as const satisfies Record<BusinessPersona, readonly string[]>;
export type BusinessExampleKey = (typeof BUSINESS_EXAMPLE_KEYS)[BusinessPersona][number];
export const BUSINESS_DESTINATIONS = {
  report: ['slack', 'google-sheets', 'gmail'], intake: ['notion', 'slack', 'gmail'], stock: ['shopify', 'google-sheets', 'slack'],
  reply: ['zendesk', 'gmail', 'slack'], refund: ['stripe', 'zendesk', 'gmail'], incident: ['linear', 'slack', 'zendesk'],
  prospect: ['hubspot', 'gmail', 'slack'], quote: ['hubspot', 'google-drive', 'gmail'], followup: ['hubspot', 'gmail', 'slack'],
  campaign: ['mailchimp', 'instagram', 'linkedin'], seo: ['notion', 'google-sheets', 'slack'], listening: ['notion', 'slack', 'gmail'],
  shortlist: ['notion', 'gmail', 'slack'], interview: ['google-calendar', 'gmail', 'notion'], onboarding: ['notion', 'slack', 'gmail'],
} as const satisfies Record<BusinessExampleKey, readonly string[]>;
export const BUSINESS_PREVIEW_VIEWPORT = { width: 1020, height: 1080 } as const;
/**
 * The single example the HERO plays, per persona. The hero tells one story and has
 * no example switcher; the showcase further down the page keeps the full set.
 * The `satisfies` clause is what stops an example drifting onto a persona that does
 * not own it, which would resolve its translation keys to nothing.
 */
export const HERO_EXAMPLE_KEYS = {
  ops: 'report',
  support: 'refund',
  creator: 'product',
  sales: 'quote',
  marketing: 'campaign',
  recruiting: 'shortlist',
} as const satisfies { [P in PersonaKey]: P extends 'creator' ? CreatorExampleKey : (typeof BUSINESS_EXAMPLE_KEYS)[Exclude<P, 'creator'>][number] };

export const SUPPORT_EXAMPLE_KEYS = BUSINESS_EXAMPLE_KEYS.support;
export type SupportExampleKey = (typeof SUPPORT_EXAMPLE_KEYS)[number];
export const SUPPORT_PREVIEW_VIEWPORT = BUSINESS_PREVIEW_VIEWPORT;

export function isPersonaKey(value: string): value is PersonaKey {
  return PERSONA_KEYS.some((persona) => persona === value);
}

export function personaHref(persona: PersonaKey, locale: string = 'en') {
  return `${locale === 'en' ? '' : `/${locale}`}/for/${persona}`;
}

/**
 * The anchor text the site chrome links each persona page with.
 *
 * <p>English, and the DEFAULT rather than the only value: the footer renders on pages that
 * live OUTSIDE the `[locale]` tree (docs, legal, marketplace), where there is no intl
 * context at all, so it cannot translate itself and falls back to these. The localised pages
 * hand it their own through `ShellLabels.personas` (see `shellLabels.ts`). They are longer
 * than the pill labels because a footer link's anchor text is the only thing telling a
 * crawler, or a reader, what is behind it, and "Sales" alone says nothing.
 */
export const PERSONA_FOOTER_LABELS: Record<PersonaKey, string> = {
  ops: 'Operations teams',
  creator: 'Content creators',
  support: 'Customer support',
  sales: 'Sales teams',
  marketing: 'Marketing teams',
  recruiting: 'Recruiting teams',
};

export function personaAlternates(persona: PersonaKey, siteUrl: string) {
  const origin = siteUrl.replace(/\/$/, '');
  return Object.fromEntries([
    ...locales.map((locale: Locale) => [locale, `${origin}${personaHref(persona, locale)}`]),
    ['x-default', `${origin}${personaHref(persona)}`],
  ]);
}

/**
 * One hue per persona, as an RGB triplet so it can be mixed at several strengths.
 *
 * <p>It lives here rather than in `personaStyles.ts` because two surfaces now read it: the
 * persona pages, whose grounds, wash and card borders are all derived from it, and the home
 * page's role cards, which paint each card in the colour of the page it links to. Written
 * twice, the two would drift and a card would promise a page a different colour.
 *
 * <p>Creator runs warm because its pages are about visual work; the other five take the hue
 * their own cards and studio stages already use, so a section sits on a paler version of
 * what it contains.
 */
export const PERSONA_TINTS: Record<PersonaKey, string> = {
  ops: '20,108,148',
  creator: '221,110,80',
  support: '124,92,246',
  sales: '217,140,20',
  marketing: '226,72,153',
  recruiting: '45,120,230',
};
