import type { Metadata } from 'next';
import { getTranslations, setRequestLocale } from 'next-intl/server';
import { BrandTopbar } from '@/components/auth/BrandTopbar';
import LandingLanguageSelect from '@/components/landing/LandingLanguageSelect';
import { PersonalOfferView } from '@/components/billing/personal-offer/PersonalOfferView';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';

export async function generateMetadata(): Promise<Metadata> {
  const locale = await resolveRequestLocale();
  const t = await getTranslations({ locale, namespace: 'personalOfferPage' });
  return { title: t('metaTitle'), robots: { index: false, follow: false } };
}

/**
 * A personal offer, full screen (/offer/personal?lc_offer=<code>): the page the email a free
 * account receives once its credits run out opens. The links already sent point at the pricing
 * page with the same code; the proxy sends them here. Everything about the offer is read in the
 * browser, for the signed-in account it belongs to (no offer is ever readable by its code alone).
 */
export default async function PersonalOfferPage() {
  // The locale is passed explicitly, as on every page outside the [locale] tree.
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const tShell = await getTranslations({ locale, namespace: 'LandingShell' });

  return (
    <div className="min-h-screen flex flex-col bg-theme-primary">
      {/* No site navigation: a page made to choose and pay, under the sign-in page's own bar. */}
      <BrandTopbar
        className="px-4 py-4 md:px-9 md:py-6"
        themeLabels={{ toLight: tShell('toLightTheme'), toDark: tShell('toDarkTheme') }}
      >
        <LandingLanguageSelect label={tShell('language')} compact />
      </BrandTopbar>
      <PersonalOfferView />
    </div>
  );
}
