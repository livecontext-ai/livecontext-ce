import type { Metadata } from 'next';
import { notFound, redirect } from 'next/navigation';
import { getTranslations, setRequestLocale } from 'next-intl/server';
import PersonaLanding from '@/components/landing/personas/PersonaLanding';
import { isPersonaKey, PERSONA_KEYS, personaAlternates, personaHref } from '@/components/landing/personas/personas';
import JsonLd from '@/components/seo/JsonLd';
import { locales } from '@/i18n/routing';
import { IS_CE } from '@/lib/edition';
import { landingOgImage, SITE_URL } from '@/lib/seo/siteUrl';

type PageProps = { params: Promise<{ locale: string; persona: string }> };

export function generateStaticParams() {
  return locales.flatMap((locale) => PERSONA_KEYS.map((persona) => ({ locale, persona })));
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale, persona } = await params;
  if (!isPersonaKey(persona) || !locales.some((value) => value === locale)) notFound();
  setRequestLocale(locale);
  const t = await getTranslations({ locale, namespace: `PersonaLanding.personas.${persona}` });
  const title = t('metaTitle');
  const description = t('metaDescription');
  const url = `${SITE_URL.replace(/\/$/, '')}${personaHref(persona, locale)}`;
  return {
    title: { absolute: title },
    description,
    alternates: { canonical: url, languages: personaAlternates(persona, SITE_URL) },
    openGraph: {
      title, description, url, siteName: 'LiveContext', type: 'website',
      images: [{ url: landingOgImage(locale), width: 1200, height: 630, alt: title }],
    },
    twitter: { card: 'summary_large_image', title, description, images: [landingOgImage(locale)] },
    robots: IS_CE ? { index: false, follow: false } : { index: true, follow: true },
  };
}

export default async function PersonaPage({ params }: PageProps) {
  const { locale, persona } = await params;
  if (!isPersonaKey(persona) || !locales.some((value) => value === locale)) notFound();
  if (IS_CE) redirect(`/${locale}/app/chat`);
  setRequestLocale(locale);
  const t = await getTranslations({ locale, namespace: `PersonaLanding.personas.${persona}` });
  const url = `${SITE_URL.replace(/\/$/, '')}${personaHref(persona, locale)}`;
  const jsonLd = {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'WebPage',
        '@id': `${url}#webpage`,
        url,
        name: t('metaTitle'),
        description: t('metaDescription'),
        inLanguage: locale,
        isPartOf: { '@id': `${SITE_URL.replace(/\/$/, '')}/#website` },
        mainEntity: { '@id': `${url}#service` },
        breadcrumb: { '@id': `${url}#breadcrumb` },
      },
      // Two levels, which is the truth: there is no /for index page, so a three-step
      // trail naming one would be a crumb pointing at a 404.
      {
        '@type': 'BreadcrumbList',
        '@id': `${url}#breadcrumb`,
        itemListElement: [
          { '@type': 'ListItem', position: 1, name: 'LiveContext', item: `${SITE_URL.replace(/\/$/, '')}${locale === 'en' ? '/' : `/${locale}`}` },
          { '@type': 'ListItem', position: 2, name: t('name'), item: url },
        ],
      },
      {
        '@type': 'Service',
        '@id': `${url}#service`,
        name: t('title'),
        description: t('description'),
        provider: { '@type': 'Organization', name: 'LiveContext', url: SITE_URL },
        areaServed: 'Worldwide',
      },
    ],
  };
  return <><JsonLd data={jsonLd} /><PersonaLanding persona={persona} locale={locale} /></>;
}
