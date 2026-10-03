import type { Metadata } from 'next';
import Link from 'next/link';
import { getTranslations, setRequestLocale } from 'next-intl/server';
import { CloudOff, SearchX } from 'lucide-react';
import { BrandTopbar } from '@/components/auth/BrandTopbar';
import LandingLanguageSelect from '@/components/landing/LandingLanguageSelect';
import { PartnerOfferView } from '@/components/partner/offer/PartnerOfferView';
import { PartnerOfferForClient } from '@/components/partner/offer/PartnerOfferForClient';
import { fetchPartnerOffer } from '@/lib/partners/publicPartnerOffer';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';
import { socialCard } from '@/lib/seo/socialCard';

/**
 * The title and card a shared link shows (a partner sends these in messages and emails): who
 * recommends which plan, never the client's name (the label stays the partner's). Never indexed.
 * A read that failed for a passing reason gets a neutral title: messaging apps cache previews,
 * and an outage must not leave a live link looking dead.
 */
export async function generateMetadata({ params }: { params: Promise<{ token: string }> }): Promise<Metadata> {
  const { token } = await params;
  const locale = await resolveRequestLocale();
  const [read, t, tCards] = await Promise.all([
    fetchPartnerOffer(token),
    getTranslations({ locale, namespace: 'partnerOffer' }),
    getTranslations({ locale, namespace: 'pricing.planCards' }),
  ]);
  const title = read.status === 'error'
    ? t('eyebrow')
    : read.status === 'gone'
      ? t('unavailableTitle')
      : read.offer.partner
        ? t('metaTitle', { partner: read.offer.partner.name, plan: tCards(`${read.offer.plan}.name`) })
        : t('metaTitleGeneric', { plan: tCards(`${read.offer.plan}.name`) });
  const description = t('metaDescription');
  return {
    title,
    description,
    ...socialCard({ title, description, path: `/offer/${encodeURIComponent(token)}` }),
    robots: { index: false, follow: false },
  };
}

/**
 * A partner's offer to one client, full screen: read on the server (the plan the partner chose,
 * the credits their code gives, their public identity), drawn by {@link PartnerOfferView}. An
 * offer that is unknown, deactivated, or whose partner code no longer brings sign-ups says so,
 * with a way to the plans: never a page promising what nobody would honour. A read that failed
 * for a passing reason says that instead, with a way to try again: the link may well be live.
 */
export default async function PartnerOfferPage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  // The locale is passed explicitly, as on every page outside the [locale] tree: the layout's
  // setRequestLocale is not guaranteed to run before this page renders.
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const [read, t, tShell] = await Promise.all([
    fetchPartnerOffer(token),
    getTranslations({ locale, namespace: 'partnerOffer' }),
    getTranslations({ locale, namespace: 'LandingShell' }),
  ]);

  return (
    <div className="min-h-screen flex flex-col bg-theme-primary">
      {/* No site navigation: a page made to choose and pay. The bar of the sign-in page the
          visitor is about to see (logo, language, light/dark), so the two read as one. */}
      <BrandTopbar
        className="px-4 py-4 md:px-9 md:py-6"
        themeLabels={{ toLight: tShell('toLightTheme'), toDark: tShell('toDarkTheme') }}
      >
        <LandingLanguageSelect label={tShell('language')} compact />
      </BrandTopbar>
      {read.status === 'ok' ? (
        <PartnerOfferView offer={read.offer} />
      ) : read.status === 'gone' ? (
        // Gone for an anonymous visitor; a client already attributed to the partner may still read it.
        <PartnerOfferForClient token={token}>
        <main className="flex flex-1 items-center justify-center px-4 py-16" data-testid="partner-offer-unavailable">
          <div className="max-w-md text-center">
            <SearchX className="mx-auto h-10 w-10 text-theme-secondary" aria-hidden />
            <h1 className="mt-4 text-xl font-semibold text-theme-primary">{t('unavailableTitle')}</h1>
            <p className="mt-2 text-sm text-theme-secondary">{t('unavailableBody')}</p>
            <Link href="/app/settings/pricing" className="mt-6 inline-flex rounded-xl bg-[var(--accent-primary)] px-4 py-2 text-sm font-medium text-[var(--accent-foreground)]">
              {t('unavailableCta')}
            </Link>
          </div>
        </main>
        </PartnerOfferForClient>
      ) : (
        <main className="flex flex-1 items-center justify-center px-4 py-16" data-testid="partner-offer-error">
          <div className="max-w-md text-center">
            <CloudOff className="mx-auto h-10 w-10 text-theme-secondary" aria-hidden />
            <h1 className="mt-4 text-xl font-semibold text-theme-primary">{t('loadErrorTitle')}</h1>
            <p className="mt-2 text-sm text-theme-secondary">{t('loadErrorBody')}</p>
            <a
              href={`/offer/${encodeURIComponent(token)}`}
              className="mt-6 inline-flex rounded-xl bg-[var(--accent-primary)] px-4 py-2 text-sm font-medium text-[var(--accent-foreground)]"
            >
              {t('retry')}
            </a>
          </div>
        </main>
      )}
    </div>
  );
}
