import { Blocks, Quote, Users } from 'lucide-react';
import { getTranslations } from 'next-intl/server';
import { Section, SectionEyebrow, SectionH2 } from '@/components/landing/LandingSections';
import PersonaRoleCards from '@/components/landing/personas/PersonaRoleCards';
import { CATALOG_INTEGRATIONS_CLAIM, CATALOG_OPERATIONS_CLAIM } from '@/lib/integrations/integrationCount';
import BuildableAutomations, { showingTestimonials } from './BuildableAutomations';
import IntegrationsStrip from './IntegrationsStrip';
import { translatedAutomationExamples } from './translatedAutomationExamples';

/**
 * The home page sections that COMPOSE another component by handing it translated props,
 * rather than just printing copy of their own.
 *
 * <p>They live here and not in `page.tsx` because that is the difference that matters for
 * testing: a section that prints three strings cannot be wrong in an interesting way, while
 * one that decides which set of cards to pass, which labels to hand a shared strip, or which
 * of two headings to pair with which body, can be wrong in a way that renders perfectly.
 * Every bug the translation pass fixed on this page was of that second kind, and none of it
 * was reachable from a test while these lived inside a Next page module (extra exports from
 * a page file are not something to rely on).
 */

/**
 * True when the band should show real customer quotes rather than capability cards.
 *
 * <p>The quotes in `socialProof.ts` are real customers in their own words, so they are in the
 * language the customer spoke: English. A French page carrying them under a French heading
 * would be worse than carrying none, so every other locale falls back to the translated
 * capability cards.
 *
 * <p>It is one exported function because the HEADING and the BODY must not decide separately:
 * a section that announces customer stories over cards attributed to nobody is the single
 * thing this band must never do. Add a translated-quotes field to `socialProof.ts` and this
 * becomes a per-locale check instead of an English-only one.
 */
export function showsTestimonials(locale: string, hasAuthorizedQuotes = showingTestimonials()): boolean {
  return hasAuthorizedQuotes && locale === 'en';
}

/**
 * The six roles, each showing the screen of the page it opens.
 *
 * <p>It sits on the GROUND half of the rhythm, in the slot a persona page gives its own
 * "what you can build" studio, which is the same content in its full form. The hero asks
 * which role you are (six pills on the demo) and this answers with the artefact each role
 * ends up holding.
 */
export async function RolesSection({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.roles' });
  return (
    <Section id="roles">
      <div className="text-center">
        <SectionEyebrow icon={Users}>{t('eyebrow')}</SectionEyebrow>
        <SectionH2>{t('title')}</SectionH2>
        <p className="mt-6 text-lg max-w-3xl mx-auto leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          {t('lead')}
        </p>
      </div>
      <PersonaRoleCards locale={locale} />
    </Section>
  );
}

/**
 * The integrations strip, with the home page's own labels.
 *
 * <p>The strip renders its English defaults when given none, which is what it did here and
 * is why this band stayed English on the five translated locales. The persona pages already
 * hand it translated labels; this does the same, from the same two shared keys, so the count
 * and the link text cannot drift between the seven pages that show the strip.
 */
export async function HomeIntegrationsStrip({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.integrations' });
  const common = await getTranslations({ locale, namespace: 'PersonaLanding.common' });
  return (
    <IntegrationsStrip
      labels={{
        heading: t('heading'),
        browseAll: common('integrationsBrowseAll', { integrations: CATALOG_INTEGRATIONS_CLAIM }),
        details: common('integrationsDetails', { operations: CATALOG_OPERATIONS_CLAIM }),
      }}
    />
  );
}

/**
 * The wall of automations, and the heading that describes whichever wall is showing.
 *
 * <p>The band is the section's `bleed`: it runs the full width of the page rather than being
 * clipped inside the 1104px content box, where it cut its cards mid-sentence. Ground,
 * matching the persona pages, which put the same band on theirs.
 */
export async function BuildableSection({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.build' });
  // The capability copy is the SAME text the persona pages show over the same cards, so it
  // is read from the keys those pages already own rather than written a second time here.
  const buildable = await getTranslations({ locale, namespace: 'PersonaLanding.buildable' });
  const common = await getTranslations({ locale, namespace: 'PersonaLanding.common' });

  const testimonials = showsTestimonials(locale);
  const examples = testimonials ? undefined : await translatedAutomationExamples(locale);
  // The rows are named for WHAT THEY HOLD. Handing the quote wall the capability label would
  // announce two scrollers of customer stories as "Example automations", which is the same
  // heading-versus-body mismatch `showsTestimonials` exists to prevent, one level down.
  const rowLabels: [string, string] = testimonials
    ? [t('testimonialsRow', { n: 1 }), t('testimonialsRow', { n: 2 })]
    : [buildable('bandRow', { n: 1 }), buildable('bandRow', { n: 2 })];

  return (
    <Section id="build" bleed={<BuildableAutomations examples={examples} rowLabels={rowLabels} />}>
      <div className="text-center">
        <SectionEyebrow icon={testimonials ? Quote : Blocks}>
          {testimonials ? t('testimonialsEyebrow') : common('buildEyebrow')}
        </SectionEyebrow>
        <SectionH2>{testimonials ? t('testimonialsTitle') : buildable('title')}</SectionH2>
        <p className="mt-6 text-lg max-w-2xl mx-auto leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          {testimonials ? t('testimonialsLead') : buildable('description')}
        </p>
      </div>
    </Section>
  );
}
