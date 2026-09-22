import { getTranslations } from 'next-intl/server';
import { Blocks, Bot, CalendarClock, Hammer, ShieldCheck, Sparkles, Store } from 'lucide-react';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import SelfHostLink from '@/app/[locale]/_landing/SelfHostLink';
import AgentsShowcase from '@/app/[locale]/_landing/AgentsShowcase';
import AgendaShowcase from '@/app/[locale]/_landing/AgendaShowcase';
import IntegrationsStrip from '@/app/[locale]/_landing/IntegrationsStrip';
import MarketplacePreview from '@/app/[locale]/_landing/MarketplacePreview';
import PricingSection from '@/app/[locale]/_landing/PricingSection';
import { CATALOG_INTEGRATIONS_CLAIM, CATALOG_OPERATIONS_CLAIM } from '@/lib/integrations/integrationCount';
import { GithubMark, LandingFooter, LandingHeader, landingChromeStyles } from '@/components/landing/LandingShell';
import { shellLabels } from '@/components/landing/shellLabels';
import { Section, SectionEyebrow, SectionH2, SectionLead } from '@/components/landing/LandingSections';
import { landingStyles } from '@/components/landing/landingStyles';
import LandingThemeProvider from '@/components/landing/LandingThemeProvider';
import type { PersonaKey } from './personas';
import { PERSONA_ICONS } from './personaIcons';
import HeroWorkflowShowcase from './HeroWorkflowShowcase';
import PersonaBuildGrid from './PersonaBuildGrid';
import PersonaBuildableBand from './PersonaBuildableBand';
import PersonaHeroNav from './PersonaHeroNav';
import { personaStyles } from './personaStyles';

const TEAM_KEYS = ['first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'seventh', 'eighth', 'ninth', 'tenth', 'eleventh', 'twelfth'] as const;
const INTEGRATION_SLUGS: Record<PersonaKey, string[]> = {
  ops: ['slack', 'google-sheets', 'notion', 'shopify', 'gmail', 'airtable'],
  support: ['zendesk', 'gmail', 'slack', 'stripe', 'notion', 'shopify'],
  creator: ['instagram', 'tiktok', 'youtube-data-api', 'linkedin', 'reddit', 'twitter-x', 'threads', 'pinterest', 'facebook', 'openai', 'google-drive', 'google-calendar', 'notion'],
  sales: ['hubspot', 'salesforce', 'gmail', 'linkedin', 'google-sheets', 'google-calendar'],
  marketing: ['mailchimp', 'instagram', 'linkedin', 'openai', 'notion', 'google-sheets'],
  recruiting: ['gmail', 'google-calendar', 'slack', 'notion', 'airtable', 'google-drive'],
};

// The exact homepage CTA geometry, shared across every persona surface.
const PRIMARY_CTA = 'inline-flex items-center justify-center h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer';
const SECONDARY_CTA = 'inline-flex items-center gap-2 h-9 px-4 rounded-xl text-sm font-medium border transition-colors hover:bg-[var(--bg-secondary)] cursor-pointer';

export default async function PersonaLanding({ persona, locale }: { persona: PersonaKey; locale: string }) {
  const t = await getTranslations({ locale, namespace: `PersonaLanding.personas.${persona}` });
  const common = await getTranslations({ locale, namespace: 'PersonaLanding.common' });
  const deployment = await getTranslations({ locale, namespace: 'pricing.deployment' });
  const buildable = await getTranslations({ locale, namespace: 'PersonaLanding.buildable' });
  // The chrome renders on non-localised pages too, so it cannot translate itself: see shellLabels.
  const shell = await shellLabels(locale);
  const Icon = PERSONA_ICONS[persona];
  // The roster this persona would actually hire, not a generic twelve shared by all six.
  // The names are the jobs the page has been selling since its hero, and the ORDER is part of
  // the copy: `PERSONA_AVATARS[persona]` in AgentsShowcase is read by index, so entry three
  // wears the third avatar of that row. That pairing is what puts a headset on a support card
  // and a camera on a creator one, and it silently misaligns the whole roster if either list
  // is reordered on its own.
  const team = TEAM_KEYS.map((key) => ({ name: t(`team.${key}.name`), description: t(`team.${key}.description`) }));
  return (
    <LandingThemeProvider respectStored lang={locale} className={`persona-page persona-${persona} min-h-screen`}>
      <style>{landingChromeStyles + landingStyles + personaStyles}</style>
      <LandingHeader labels={shell} />
      <main>
        <section id="hero" className="relative overflow-visible" style={{ background: 'var(--persona-ground, var(--bg-primary))' }}>
          <div className="max-w-7xl mx-auto px-6 pt-14 pb-12 md:pt-20 md:pb-16">
            <div className="max-w-3xl mx-auto text-center">
              <SectionEyebrow icon={Icon}>{t('eyebrow')}</SectionEyebrow>
              <h1 className="hero-h1 mt-4">{t('title')}</h1>
              <p className="mt-6 text-lg max-w-2xl mx-auto leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{t('description')}</p>
              <div className="mt-7 flex flex-wrap justify-center gap-3">
                <SignInButton cta={`persona_${persona}_hero`} returnTo={`/${locale}/app/chat`} className={PRIMARY_CTA}>{common('cta')}</SignInButton>
                <SelfHostLink section="hero" className={SECONDARY_CTA} style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}><GithubMark className="w-4 h-4" />{deployment('selfHosted')}</SelfHostLink>
              </div>
              <p className="mt-5 text-sm inline-flex items-center justify-center gap-2" style={{ color: 'var(--text-muted)' }}><ShieldCheck className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />{common('approvalLabel')}</p>
            </div>
            {/* The hero runs this persona's own workflow, drawn from the request in
                chat. The pills that used to live inside the old hero iframe stay with
                it: they are the cross-links between the persona pages. */}
            <div id="persona-workflow" className="mt-8 persona-hero-demo persona-hero-stage">
              <PersonaHeroNav current={persona} onPage />
              <HeroWorkflowShowcase persona={persona} />
            </div>
          </div>
        </section>

        <div className="persona-integrations">
          <IntegrationsStrip prioritySlugs={INTEGRATION_SLUGS[persona]} labels={{
            heading: t('previewDescription'),
            browseAll: common('integrationsBrowseAll', { integrations: CATALOG_INTEGRATIONS_CLAIM }),
            details: common('integrationsDetails', { operations: CATALOG_OPERATIONS_CLAIM }),
          }} />
        </div>

        {/* What else this persona can build. The hero plays ONE example end to end, so
            without this the other two or three the page owns are only in the sitemap. */}
        <Section id="persona-build">
          <SectionEyebrow icon={Blocks}>{common('buildEyebrow')}</SectionEyebrow>
          <SectionH2>{t('workflowShowcase.title')}</SectionH2>
          <SectionLead>{t('workflowShowcase.description')}</SectionLead>
          <div className="mt-7"><SignInButton cta={`persona_${persona}_build`} className={PRIMARY_CTA} returnTo={`/${locale}/app/chat`}>{common('cta')}</SignInButton></div>
          <div className="mt-12"><PersonaBuildGrid persona={persona} locale={locale} /></div>
        </Section>

        <Section alt id="marketplace">
          <SectionEyebrow icon={Store}>{common('marketplaceEyebrow')}</SectionEyebrow>
          <SectionH2>{common('marketplaceTitle')}</SectionH2>
          <SectionLead>{common('marketplaceDescription')}</SectionLead>
          <div className="mt-12"><MarketplacePreview persona={persona} /></div>
        </Section>

        <section id="persona-agents" className="relative overflow-hidden" style={{ background: 'var(--persona-ground, var(--bg-primary))', borderTop: '1px solid var(--border-color)' }}>
          <div className="relative max-w-6xl mx-auto px-6 py-24 md:py-32">
            <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,400px)_1fr] gap-10 lg:gap-14 items-center">
              <div>
                <SectionEyebrow icon={Bot}>{t('workforce.eyebrow')}</SectionEyebrow>
                <SectionH2>{t('workforce.title')}</SectionH2>
                <SectionLead>{t('workforce.description')}</SectionLead>
              </div>
              {/* Same backdrop as the hero; the window runs off its right and bottom edges. */}
              <div className="landing-demo-panel" data-bleed="right bottom"><AgentsShowcase team={team} persona={persona} locale={locale} /></div>
            </div>
          </div>
        </section>

        <section id="persona-agenda" className="relative overflow-hidden" style={{ background: 'var(--persona-band, var(--bg-secondary))', borderTop: '1px solid var(--border-color)' }}>
          <div className="relative max-w-6xl mx-auto px-6 py-24 md:py-32">
            <SectionEyebrow icon={CalendarClock}>{t('previewTitle')}</SectionEyebrow>
            <SectionH2>{t('benefits.second.title')}</SectionH2>
            <SectionLead>{t('benefits.second.description')}</SectionLead>
            <div className="mt-7"><SignInButton cta={`persona_${persona}_agenda`} className={PRIMARY_CTA} returnTo={`/${locale}/app/agenda`}>{common('cta')}</SignInButton></div>
            <div className="mt-12 landing-demo-panel" data-bleed="bottom" data-crop="agenda"><AgendaShowcase nowIso={new Date().toISOString()} persona={persona} locale={locale} /></div>
          </div>
        </section>

        {/* Same band, same reason for the bleed as on the home page: clipped at the content
            box it cut its cards mid-sentence inside the layout. */}
        <Section id="persona-buildable" bleed={<PersonaBuildableBand persona={persona} locale={locale} />}>
          <div className="text-center">
            <div className="inline-flex"><SectionEyebrow icon={Hammer}>{buildable('eyebrow')}</SectionEyebrow></div>
            <SectionH2>{buildable('title')}</SectionH2>
            <p className="mt-6 text-lg max-w-2xl mx-auto leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{buildable('description')}</p>
          </div>
        </Section>

        <Section alt id="pricing">
          <SectionEyebrow icon={Sparkles}>{common('pricingEyebrow')}</SectionEyebrow>
          <SectionH2>{common('pricingTitle')}</SectionH2>
          <SectionLead>{common('pricingDescription')}</SectionLead>
          <div className="mt-12"><PricingSection /></div>
        </Section>
        <section id="final-cta" className="relative overflow-hidden" style={{ background: 'var(--persona-ground, var(--bg-primary))', borderTop: '1px solid var(--border-color)' }}>
          <div className="cta-glow" aria-hidden="true" />
          <div className="relative max-w-4xl mx-auto px-6 py-24 text-center">
            <h2 className="text-4xl md:text-5xl font-bold tracking-tight" style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}>{t('ctaTitle')}</h2>
            <p className="mt-4 text-lg" style={{ color: 'var(--text-secondary)' }}>{t('ctaDescription')}</p>
            <div className="mt-8 flex flex-wrap justify-center gap-3"><SignInButton cta={`persona_${persona}_final`} className={PRIMARY_CTA} returnTo={`/${locale}/app/chat`}>{common('cta')}</SignInButton><SelfHostLink section="final_cta" className={SECONDARY_CTA} style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}><GithubMark className="w-4 h-4" />{deployment('selfHosted')}</SelfHostLink></div>
          </div>
        </section>
      </main>
      <LandingFooter labels={shell} />
    </LandingThemeProvider>
  );
}
