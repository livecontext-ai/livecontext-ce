import { landingStyles } from "@/components/landing/landingStyles";
import { Section, SectionEyebrow, SectionH2, SectionLead } from "@/components/landing/LandingSections";
import type { Metadata } from 'next';
import { redirect } from 'next/navigation';
import { getTranslations, setRequestLocale } from 'next-intl/server';
import {
  Bot,
  CalendarClock,
  Workflow,
  Store,
  Sparkles,
  ShieldCheck,
  Eye,
  Gauge,
  Lock,
  Server,
  SlidersHorizontal,
  ChevronDown,
  MessageSquare,
  Share2,
  Quote,
  Users,
  UserCheck,
} from 'lucide-react';
import { GithubMark, LandingFooter, LandingHeader, landingChromeStyles } from '@/components/landing/LandingShell';
import { shellLabels } from '@/components/landing/shellLabels';
import PricingSection from './_landing/PricingSection';
import SignInButton from './_landing/SignInButton';
import MarketplacePreview from './_landing/MarketplacePreview';
import HeroPhotoStack from './_landing/HeroPhotoStack';
import HeroWorkflowShowcase from '@/components/landing/personas/HeroWorkflowShowcase';
import PersonaHeroNav from '@/components/landing/personas/PersonaHeroNav';
import { CATALOG_INTEGRATIONS_CLAIM } from '@/lib/integrations/integrationCount';
import { BuildableSection, HomeIntegrationsStrip, RolesSection } from './_landing/homeSections';
import AgentsShowcase from './_landing/AgentsShowcase';
// From a plain module, never from the showcase itself: its exports reach a Server Component
// as client references, so the array would not be one. See demoRosterIds.ts.
import { DEMO_AGENT_IDS } from './_landing/demoRosterIds';
import AgendaShowcase from './_landing/AgendaShowcase';
import PersonaTabs, { type Persona } from './_landing/PersonaTabs';
import {
  CUSTOMER_LOGOS,
  LANDING_METRICS,
  TESTIMONIALS,
  metricsReady,
  testimonialsReady,
} from './_landing/socialProof';
import HashScroller from './_landing/HashScroller';
import LandingSectionObserver from './_landing/LandingSectionObserver';
import SelfHostLink from './_landing/SelfHostLink';
import FaqItem from './_landing/FaqItem';
import { IS_CE as IS_CE_DEPLOY } from '@/lib/edition';
import { NODE_ICON_REGISTRY } from '@/app/workflows/builder/data/nodeVisuals';
import LandingThemeProvider from '@/components/landing/LandingThemeProvider';
import JsonLd from '@/components/seo/JsonLd';
import { homeAlternates, homeHref, ogAlternateLocales, ogLocale } from '@/lib/seo/siteUrl';
import { LANDING_FAQ_KEYS, landingJsonLd, type Copy } from '@/lib/seo/landingJsonLd';
import Link from 'next/link';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/**
 * ISR, deliberately NOT `force-dynamic`.
 *
 * <p>The landing now reads the marketplace catalogue so the section can be in
 * the server HTML, and the obvious way to guarantee a fresh read is
 * `force-dynamic` - which is what `/marketplace` and the sitemap do. It is the
 * wrong trade HERE: this is the most requested page on the domain and it is
 * currently served straight from the CDN (`x-nextjs-cache: HIT`,
 * `cf-cache-status: HIT`). A dynamic page emits `no-store`, so that would trade
 * an hour of catalogue staleness for losing edge caching on the homepage.
 *
 * <p>The known cost of ISR here is the one documented on `/marketplace`: the CI
 * builder cannot reach the gateway, so the BUILD-time render has no listings
 * (verified locally: the prerendered landing carries the block's fallback, i.e.
 * nothing) and each replica serves that until it first revalidates. That is
 * survivable for this block and not for that page, because the catalogue block
 * is a supplement: `/marketplace` (dynamic, linked from the header of every
 * public page) is what actually guarantees every listing is crawlable, and the
 * block renders NOTHING rather than an empty heading while the read is cold.
 *
 * <p>Ten minutes, not an hour, for exactly that reason: it is how long after a
 * deploy the landing goes without its catalogue, and `stale-while-revalidate`
 * means the visitor never waits for the refresh either way. The catalogue read
 * is given the SAME window rather than a longer one, because Next only lowers
 * a route's window from its fetches and never the Data Cache entry: a 3600 on
 * the read would have made the real staleness an hour while this line claimed
 * ten minutes.
 */
export const revalidate = 600;

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }): Promise<Metadata> {
  const { locale } = await params;
  // Same as the persona route: declare the locale before reading messages, so metadata does
  // not become the one thing on the page that opts out of static rendering.
  setRequestLocale(locale);
  const t = await getTranslations({ locale, namespace: 'LandingHome.meta' });
  const url = `${SITE_URL.replace(/\/$/, '')}${homeHref(locale)}`;
  return {
    title: { absolute: t('title') },
    description: t('description'),
    // Open Graph is NOT inherited correctly here: the root layout declares one English
    // title, description and apex URL for the whole site, which was right while this page
    // was English on all six URLs. Sharing /fr then posted an English card pointing at the
    // English page. Each locale now shares itself, in its own language.
    openGraph: {
      type: 'website',
      siteName: 'LiveContext',
      url,
      title: t('title'),
      description: t('description'),
      locale: ogLocale(locale),
      alternateLocale: ogAlternateLocales(locale),
      images: [{ url: '/og-image.jpg', width: 1200, height: 630, alt: t('title') }],
    },
    twitter: {
      card: 'summary_large_image',
      title: t('title'),
      description: t('description'),
      images: ['/og-image.jpg'],
    },
    // Each locale is now its OWN canonical, inside one hreflang cluster.
    //
    // Until this page was translated, pointing all six at the apex was the right call and
    // the comment here said so: six URLs carrying the same English text are duplicates, and
    // the cluster asked Google to index one of them. They are six different pages now, in
    // six languages, so each one is the canonical of itself and they declare each other.
    // Leaving the old line in place would have told Google that five translations do not
    // exist, which is the opposite of why they were written.
    //
    // The cluster is shared with the sitemap rather than written twice: a sitemap that
    // advertises a URL the page canonicalises away is a crawler instruction cancelling itself.
    alternates: {
      canonical: `${SITE_URL.replace(/\/$/, '')}${homeHref(locale)}`,
      languages: homeAlternates(SITE_URL),
    },
    robots: IS_CE_DEPLOY ? { index: false, follow: false } : undefined,
  };
}

const HERO_PHOTOS = [
  {
    url: 'livecontext.ai/app/chat',
    src: '/landing/screenshots/builder-built-by-chat.webp',
    video: '/landing/videos/builder-built-by-chat.mp4',
    webm: '/landing/videos/builder-built-by-chat.webm',
    durationMs: 17000,
    alt: 'LiveContext building a support triage workflow from one chat message',
    label: 'Built by chat',
    description: 'Real footage. You type what you want done and the workflow builds itself in front of you: here, a support inbox where every email gets classified, tickets logged, and refunds prepped for approval.',
  },
  {
    url: 'livecontext.ai/app/workflow',
    src: '/landing/hero-stack/workflow-app.webp',
    alt: 'LiveContext workflow builder and the app it drives',
    label: 'Workflow + App',
    description: 'The workflow and the app it drives, in one view. Draw the automation as a readable graph, then wrap it in a real interface: forms, dashboards and live approval screens your team or an agent can act on.',
  },
  {
    url: 'livecontext.ai/app/agent',
    src: '/landing/hero-stack/agent.webp',
    alt: 'LiveContext agent roster',
    label: 'Agent',
    description: 'A roster of always-on agents, one per job. Each gets its own model, its own workflow, a scoped set of tools and files you can restrict at any time, a credit budget it cannot exceed, and a full audit trail. Schedule them, call them from the chat, or let them delegate.',
  },
  {
    url: 'livecontext.ai/app/tables',
    src: '/landing/hero-stack/table.webp',
    alt: 'LiveContext data tables',
    label: 'Table',
    description: 'Built-in data tables your workflows and agents read, write and enrich. Filter, search and export straight from the grid, with no external database to wire up.',
  },
  {
    url: 'livecontext.ai/app/agents/metrics',
    src: '/landing/hero-stack/data-metrics.webp',
    alt: 'LiveContext data metrics dashboard',
    label: 'Data metrics',
    description: 'Every run charted. Calls, tokens, success rate, average duration, all sliced per agent and per tool. Spot a regression in the daily bar chart and drill straight into the tool that slowed everything down.',
  },
];

export default async function HomePage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  // Defense-in-depth fallback if the middleware bypass (preferred path) did not
  // run for this request (e.g. middleware misconfigured, edge runtime skew).
  if (IS_CE_DEPLOY) {
    redirect(`/${locale}/app/chat`);
  }

  // Every section below reads its copy from `LandingHome`, so the page has to declare the
  // locale it is rendering for or it opts out of static rendering.
  setRequestLocale(locale);

  const meta = await getTranslations({ locale, namespace: 'LandingHome.meta' });
  const jsonLdCopy = await getTranslations({ locale, namespace: 'LandingHome.jsonLd' });
  // The FAQ copy reaches the structured data through the SAME translator the section
  // renders from, so the two cannot drift into saying different things.
  const faqCopy = await getTranslations({ locale, namespace: 'LandingHome.faq' });
  // The chrome renders on non-localised pages too, so it cannot translate itself: see shellLabels.
  const shell = await shellLabels(locale);
  // `as unknown as Copy` rather than `as never`: `never` is assignable to everything, so the
  // two translators could be swapped and neither tsc nor a test would object, and the page
  // would ship an offer blurb as its site description.
  const jsonLd = landingJsonLd(
    locale,
    meta as unknown as Copy,
    jsonLdCopy as unknown as Copy,
    faqCopy as unknown as Copy,
  );

  return (
    <LandingThemeProvider className="min-h-screen" respectStored lang={locale}>
      {/* ONE string child: React 19 only renders <style> content when it is a
          single string. Two expression children ({a}{b}) render an EMPTY style
          tag server-side and the full text client-side - a hydration mismatch
          (React #418) plus unstyled server HTML. */}
      <style>{landingChromeStyles + landingStyles}</style>
      {!IS_CE_DEPLOY && jsonLd.map((data, idx) => <JsonLd key={idx} data={data} />)}

      <LandingHeader labels={shell} />

      <main>
        <HashScroller />
        <LandingSectionObserver />
        {/* THE ORDER AND THE GROUNDS ARE ONE DECISION, and it is the persona pages' own.

            Those pages alternate ground and band all the way down (hero, strip, what you
            can build, marketplace, agents, agenda, buildable band, pricing, final call) and
            this page now lands on exactly the same rhythm, section for section. It did not
            before: the marketplace, the agenda and the buildable band sat on grounds that
            gave three pairs of same-coloured neighbours, so the middle of the page read as
            one long block with arbitrary seams.

            The role cards take the slot the persona pages give their own studio, which is
            also where they belong in the argument: the hero shows one workflow, the strip
            answers "with which tools", and this answers "for which job", with the screen
            each job ends on. */}
        <Hero locale={locale} />
        {/* The strip is back under the hero, and the two reasons its predecessor was
            removed are answered rather than repeated: it is a row of marks instead of a
            24-card grid with a search box, and it renders from the verified static list
            instead of reading the gateway. The full catalogue stays a PAGE, /integrations,
            which both the strip and the header link to. See IntegrationsStrip. */}
        <HomeIntegrationsStrip locale={locale} />
        {/* Both strips are thin bands on the same ground, so they sit together rather than
            one of them landing mid-rhythm and breaking the alternation when the marketing
            owner fills `socialProof.ts`. Its own top border keeps the two apart. */}
        <SocialProofStrip locale={locale} />
        <RolesSection locale={locale} />
        <MarketplaceSection locale={locale} />
        <AgentsSection locale={locale} />
        <AgendaSection locale={locale} />
        <BuildableSection locale={locale} />
        <TestimonialsSection />
        <PricingBlock locale={locale} />
        {/* After the prices, because that is where the questions it answers arise: three of
            the six are about cost, self-hosting and how this differs from Zapier. A visitor
            reaching the final call to action has had them answered rather than carried. */}
        <FaqSection locale={locale} />
        <FinalCta locale={locale} />
      </main>

      <LandingFooter labels={shell} />
    </LandingThemeProvider>
  );
}

/** The one word each hero line underlines. A tag, not two keys, so a translation can put
 *  it where its own sentence needs it: French underlines "en entrée", not "in". */
const underlined = (chunks: React.ReactNode) => <span className="underline underline-offset-8">{chunks}</span>;

async function Hero({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.hero' });
  const deployment = await getTranslations({ locale, namespace: 'pricing.deployment' });
  return (
    // No .hero-glow halo here: the radial read as a stray shadow behind the
    // showcase (same reason it was dropped from the agents section).
    <section id="hero" className="relative overflow-visible">
      <div className="max-w-7xl mx-auto px-6 pt-14 pb-12 md:pt-20 md:pb-16">
        <div className="max-w-3xl mx-auto text-center">
          <span className="eyebrow">{t('eyebrow')}</span>
          <h1 className="hero-h1 mt-4">
            {t.rich('titleLineOne', { u: underlined })}<br />
            {t.rich('titleLineTwo', { u: underlined })}
          </h1>
          <p className="mt-6 text-lg max-w-2xl mx-auto leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            {t('lead', { integrations: CATALOG_INTEGRATIONS_CLAIM })}
          </p>
          <div className="mt-7 flex flex-wrap justify-center gap-3">
            <SignInButton
              variant="primary"
              cta="hero_start_free"
              className="inline-flex items-center justify-center h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
            >
              {t('startFree')}
            </SignInButton>
            <SelfHostLink
              section="hero"
              className="inline-flex items-center gap-2 h-9 px-4 rounded-xl text-sm font-medium border transition-colors hover:bg-[var(--bg-secondary)] cursor-pointer"
              style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}
            >
              <GithubMark className="w-4 h-4" /> {deployment('selfHosted')}
            </SelfHostLink>
          </div>
        </div>
        {/* The demo the persona pages run, on its OPERATIONS scenario: a request typed
            in chat, the workflow drawn from it, then run with a human approval. The
            pills are the only in-page links to the /for/<persona> pages. */}
        <div className="mt-8 persona-hero-stage">
          <PersonaHeroNav current="ops" />
          <HeroWorkflowShowcase />
        </div>
      </div>
    </section>
  );
}

// Proof section: real screenshots + the live build recording, complementing
// the hero's looping replay with full-size product footage. The photo stack's
// focus tray hangs below the cards (position:absolute top:100%), hence the
// extra bottom padding.
function RealProductSection() {
  return (
    <section id="real-product" style={{ background: 'var(--bg-primary)' }}>
      <div className="max-w-6xl mx-auto px-6 pt-24 md:pt-32 pb-44 md:pb-52">
        <div className="text-center">
          <SectionEyebrow icon={Eye}>Real footage</SectionEyebrow>
          <SectionH2>This is the actual product.</SectionH2>
          <p className="mt-6 text-lg max-w-3xl mx-auto leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            No mockups here. Watch a support workflow build itself from one chat
            message, then flip through the generated app, the agents, the data
            tables and the run metrics.
          </p>
        </div>
        <div className="mt-12">
          <HeroPhotoStack photos={HERO_PHOTOS} />
        </div>
      </div>
    </section>
  );
}

// Renders only when the marketing owner has filled `socialProof.ts` with real,
// authorized customer data (see the SAMPLE kill switch there): an unedited file
// ships a landing WITHOUT this strip rather than one with fake numbers.
async function SocialProofStrip({ locale }: { locale: string }) {
  const hasLogos = CUSTOMER_LOGOS.length > 0;
  const hasMetrics = metricsReady(LANDING_METRICS);
  if (!hasLogos && !hasMetrics) return null;
  const t = await getTranslations({ locale, namespace: 'LandingHome.socialProof' });

  return (
    <section id="social-proof" style={{ background: 'var(--bg-secondary)', borderTop: '1px solid var(--border-color)' }}>
      <div className="max-w-6xl mx-auto px-6 py-12">
        {hasLogos && (
          <>
            <p className="text-center text-[11px] uppercase tracking-wider mb-6" style={{ color: 'var(--text-muted)' }}>
              {t('logosLabel')}
            </p>
            <div className="flex flex-wrap items-center justify-center gap-x-10 gap-y-6">
              {CUSTOMER_LOGOS.map((logo) =>
                logo.src ? (
                  <img key={logo.name} src={logo.src} alt={logo.name} loading="lazy" height={24} className="h-6 w-auto logo-color" />
                ) : (
                  <span key={logo.name} className="text-sm font-semibold" style={{ color: 'var(--text-secondary)' }}>
                    {logo.name}
                  </span>
                ),
              )}
            </div>
          </>
        )}
        {hasMetrics && (
          <div className={`${hasLogos ? 'mt-10' : ''} grid grid-cols-1 sm:grid-cols-3 gap-6 text-center`}>
            {LANDING_METRICS.map((metric) => (
              <div key={metric.label}>
                <p className="metric-value">{metric.value}</p>
                <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>{metric.label}</p>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function HowItWorksSection() {
  const steps = [
    {
      Icon: MessageSquare,
      title: 'Describe the job',
      desc: 'Tell the chat what you want done, in plain words. No blank canvas, no node jargon: start from your own sentence or fork a marketplace automation that already works.',
    },
    {
      Icon: Eye,
      title: 'Watch it build itself',
      desc: 'The workflow and its interface appear in front of you, step by step. Refine by chatting. Everything the AI builds stays visible and editable, so you always know exactly what will run.',
    },
    {
      Icon: Share2,
      title: 'Share the result',
      desc: 'Open it as an app, send a public link, export a PDF, an image or a spreadsheet. Put it on a schedule and the result keeps arriving, for you, your team or your customers.',
    },
  ];
  return (
    <Section alt id="how-it-works">
      <div className="text-center">
        <SectionEyebrow icon={Sparkles}>How it works</SectionEyebrow>
        <SectionH2>From a sentence to something you can share.</SectionH2>
      </div>
      <div className="mt-12 grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-6">
        {steps.map(({ Icon, title, desc }, index) => (
          <div
            key={title}
            className="p-6 rounded-2xl"
            style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)', boxShadow: 'var(--landing-card-shadow)' }}
          >
            <div className="flex items-center gap-3">
              <span className="step-number">{index + 1}</span>
              <Icon className="w-5 h-5" style={{ color: 'var(--text-primary)' }} aria-hidden="true" />
            </div>
            <h3 className="mt-4 text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</h3>
            <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{desc}</p>
          </div>
        ))}
      </div>
    </Section>
  );
}

const PERSONAS: Persona[] = [
  {
    key: 'ops',
    icon: 'ops',
    label: 'Operations',
    intro: 'The repetitive backbone of the business, running itself: intake, sync, reporting, with a page your team actually opens.',
    examples: [
      {
        title: 'Onboard every new client the same way',
        desc: 'An intake form kicks off account setup, task assignments and the kickoff email. Nothing forgotten, nobody chasing a checklist.',
        output: 'A checklist app your team opens',
        outputKind: 'app',
      },
      {
        title: 'Keep orders and inventory in sync',
        desc: 'New orders update the stock table as they land, and you get pinged before anything runs out.',
        output: 'A live stock table, always current',
        outputKind: 'table',
      },
      {
        title: 'The weekly ops report, written for you',
        desc: 'It pulls the week’s numbers, drafts the summary and sends it before your Monday meeting.',
        output: 'A PDF in your inbox every Monday',
        outputKind: 'pdf',
      },
    ],
  },
  {
    key: 'marketing',
    icon: 'marketing',
    label: 'Marketing',
    intro: 'Watching, producing and reporting on autopilot, while you keep final approval on everything that goes out.',
    examples: [
      {
        title: 'Watch competitors so you don’t have to',
        desc: 'It browses competitor pages and pricing, spots what changed and writes the digest.',
        output: 'A digest link you can forward',
        outputKind: 'link',
      },
      {
        title: 'Turn one idea into a week of content',
        desc: 'Drafts per channel from a single brief, then waits for your approval before anything is published.',
        output: 'An approval page with one-click publish',
        outputKind: 'app',
      },
      {
        title: 'Campaign reporting without the copy-paste',
        desc: 'Metrics gathered across channels into one live view your whole team can read.',
        output: 'A dashboard app for the team',
        outputKind: 'app',
      },
    ],
  },
  {
    key: 'sales',
    icon: 'sales',
    label: 'Sales',
    intro: 'Every lead qualified, followed up and reported on, without a rep touching a spreadsheet.',
    examples: [
      {
        title: 'Qualify and route every lead',
        desc: 'Leads from forms and webhooks get enriched, scored and routed to the right person, CRM updated on the way.',
        output: 'A shared lead table, always current',
        outputKind: 'table',
      },
      {
        title: 'The pipeline review, pre-written',
        desc: 'A weekly summary of what moved, what stalled and which deals are at risk.',
        output: 'A PDF before your Monday meeting',
        outputKind: 'pdf',
      },
      {
        title: 'Follow up before leads go cold',
        desc: 'Drafts the follow-up for every quiet lead and queues it for your sign-off.',
        output: 'Approve each email from one page',
        outputKind: 'app',
      },
    ],
  },
  {
    key: 'support',
    icon: 'support',
    label: 'Support',
    intro: 'The inbox triaged, the known answers drafted, the edge cases escalated to a human, with full visibility.',
    examples: [
      {
        title: 'Triage the support inbox',
        desc: 'Every email classified, a ticket logged, refunds prepped and waiting for your approval.',
        output: 'A queue app with approve buttons',
        outputKind: 'app',
      },
      {
        title: 'Answer the FAQs, escalate the rest',
        desc: 'Drafts answers from your own docs and hands anything unusual to a person.',
        output: 'Every answer logged in a table',
        outputKind: 'table',
      },
      {
        title: 'Spot recurring issues early',
        desc: 'Tickets clustered weekly so a spike shows up as a trend, not a surprise.',
        output: 'A trends report as a shareable link',
        outputKind: 'link',
      },
    ],
  },
  {
    key: 'founders',
    icon: 'founders',
    label: 'Founders & agencies',
    intro: 'Client-facing tools and back-office chores, shipped in an afternoon instead of a sprint.',
    examples: [
      {
        title: 'Ship a client tool in an afternoon',
        desc: 'Describe the tool, watch it build, send the link. Bill the value, not the hours.',
        output: 'A working app under a public link',
        outputKind: 'link',
      },
      {
        title: 'One dashboard per client',
        desc: 'Each client’s data collected on schedule into its own page, ready before the check-in call.',
        output: 'A page you can send to each client',
        outputKind: 'app',
      },
      {
        title: 'Invoices and reminders on autopilot',
        desc: 'Invoices generated from your records and chased politely until they are paid.',
        output: 'PDFs generated and sent for you',
        outputKind: 'pdf',
      },
    ],
  },
];

function PersonasSection() {
  return (
    <Section alt id="use-cases">
      <div className="text-center">
        <SectionEyebrow icon={Users}>Made for your job</SectionEyebrow>
        <SectionH2>What will you automate first?</SectionH2>
        <p className="mt-6 text-lg max-w-3xl mx-auto leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          Real jobs, described in one message. Every one of them ends with something you
          can open, send or forward, not a log file.
        </p>
      </div>
      <div className="mt-12">
        <PersonaTabs personas={PERSONAS} />
      </div>
    </Section>
  );
}

// What people built, scrolling in two rows.
//
// The heading follows the band. When `socialProof.ts` holds real, authorized quotes the
// section is a testimonial wall and says so; until then it shows capability examples and
// says THAT instead. Announcing customer stories over cards attributed to nobody is the
// one thing this section must not do, and it would be a two-word edit away if the heading
// were fixed, so it is derived rather than written twice.
//
// `TestimonialsSection` below is the older three-card version of the same data. It stays
// hidden while this band is showing the fallback, and once quotes land the two would say
// the same thing twice, which is why it yields to this one.
// The older three-card testimonials block.
//
// Superseded by the scrolling band in BuildableSection, which renders the SAME
// `TESTIMONIALS` array with avatars. Kept rather than deleted because it is the plain
// layout to fall back to if the marquee is ever dropped, but it must not render beside
// the band: two sections quoting the same customers on one page reads as padding.
function TestimonialsSection() {
  return null;
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
function TestimonialsSectionPlain() {
  if (!testimonialsReady(TESTIMONIALS)) return null;

  return (
    <Section id="testimonials">
      <div className="text-center">
        <SectionEyebrow icon={Quote}>Customer stories</SectionEyebrow>
        <SectionH2>Teams already run on it.</SectionH2>
      </div>
      <div className="mt-12 grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-6">
        {TESTIMONIALS.map((t, index) => (
          <figure
            key={`${index}-${t.name}`}
            className="p-6 rounded-2xl flex flex-col"
            style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)', boxShadow: 'var(--landing-card-shadow)' }}
          >
            <Quote className="w-5 h-5" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
            <blockquote className="mt-3 text-sm leading-relaxed flex-1" style={{ color: 'var(--text-primary)' }}>
              {t.quote}
            </blockquote>
            <figcaption className="mt-4 pt-4 text-sm" style={{ borderTop: '1px solid var(--border-color)' }}>
              <span className="font-semibold" style={{ color: 'var(--text-primary)' }}>{t.name}</span>
              <span style={{ color: 'var(--text-muted)' }}>
                {' '}· {t.role}
                {t.company ? `, ${t.company}` : ''}
              </span>
            </figcaption>
          </figure>
        ))}
      </div>
    </Section>
  );
}

// Mounted between the pricing block and the final call to action.
//
// NOT `alt`, and that is the band rhythm rather than a preference. The page alternates
// grounds, and the run into the footer is BuildableSection (primary) -> PricingBlock (alt)
// -> FinalCta (primary): inserting an `alt` section here would put two dark bands against
// each other with nothing between them, since `Section` draws no border of its own. On the
// primary ground the only repetition left is this section and FinalCta, and FinalCta carries
// its own `borderTop` - the same way the two strips under the hero sit on one ground and are
// kept apart by a border.
//
// Its copy is read from messages in all six languages, heading, eyebrow and closing line
// included: this section is rendered on six indexable locales, so an English literal here
// would be English text under a French page.
async function FaqSection({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.faq' });
  return (
    <Section id="faq">
      <div className="text-center">
        <SectionEyebrow icon={Sparkles}>{t('eyebrow')}</SectionEyebrow>
        <SectionH2>{t('title')}</SectionH2>
      </div>
      <div className="mt-12 max-w-3xl mx-auto space-y-4">
        {LANDING_FAQ_KEYS.map((key, faqIndex) => (
          <FaqItem key={key} faqIndex={faqIndex} className="faq-item rounded-2xl p-6" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}>
            <summary className="flex items-center justify-between gap-4 cursor-pointer text-base font-semibold list-none" style={{ color: 'var(--text-primary)' }}>
              <span>{t(`${key}.question`)}</span>
              <ChevronDown className="faq-chevron h-5 w-5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
            </summary>
            <p className="mt-3 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{t(`${key}.answer`, { integrations: CATALOG_INTEGRATIONS_CLAIM })}</p>
          </FaqItem>
        ))}
      </div>
      <p className="mt-8 text-sm text-center" style={{ color: 'var(--text-muted)' }}>
        {t.rich('more', {
          docs: (chunks) => (
            <a href="https://docs.livecontext.ai" className="underline underline-offset-2 hover:opacity-80">{chunks}</a>
          ),
          compare: (chunks) => (
            <Link href="/compare" className="underline underline-offset-2 hover:opacity-80">{chunks}</Link>
          ),
        })}
      </p>
    </Section>
  );
}

function BuilderSection() {
  return (
    <Section alt id="features">
      <SectionEyebrow icon={Workflow}>The builder, in depth</SectionEyebrow>
      <SectionH2>As deep as your stack needs. Still readable at 50 steps.</SectionH2>
      <SectionLead wide>
        60+ building blocks: branching, loops, parallel fan-out, sub-workflows, code,
        HTTP, files, AI nodes. The depth of a dev-grade tool, on a canvas that stays
        readable when your automation grows from 5 steps to 50. And unlike a
        do-everything agent, the workflow decides exactly what each agent sees and what
        it ships: the same job runs about 10x cheaper, every step is auditable, and your
        business never sits inside a black box.
      </SectionLead>

      <div className="mt-12">
        <BrowserFrame url="livecontext.ai/app/workflow">
          <MediaSlot
            src="/landing/screenshots/form-workflow.webp"
            alt="Visual workflow builder running live"
          />
        </BrowserFrame>
      </div>

      <FeatureGrid cols={3}>
        <FeatureCard
          icons={[
            { nodeId: 'webhook-trigger', label: 'Webhook' },
            { nodeId: 'schedule-trigger', label: 'Schedule' },
            { nodeId: 'chat-trigger', label: 'Chat' },
            { nodeId: 'manual-trigger', label: 'Manual' },
          ]}
          title="Workflows trigger workflows"
          desc="A webhook, a schedule, a chat message, a manual click. Plus forms, new rows, inbound emails, or another automation finishing or failing. Your pipeline starts itself, nobody has to remember to run it."
        />
        <FeatureCard
          icons={[
            { nodeId: 'if-else', label: 'If / Else' },
            { nodeId: 'loop', label: 'Loop / While' },
            { nodeId: 'split', label: 'Split' },
            { nodeId: 'fork', label: 'Fork' },
          ]}
          title="If, else, repeat, all at once"
          desc="If a customer is VIP, route them here. Otherwise, there. Loop every order, split a list, fork five branches in parallel. Every shape your process needs, drawn on a canvas your whole team can read."
        />
        <FeatureCard
          icons={[
            { nodeId: 'agent', label: 'Agent' },
            { nodeId: 'classify', label: 'Classify' },
            { nodeId: 'guardrail', label: 'Guardrail' },
            { nodeId: 'browser_agent', label: 'Browser agent' },
          ]}
          title="Agents you can put in production"
          desc="Each agent gets a scoped set of tools, a credit budget it cannot exceed, and a full audit trail. The workflow feeds it exactly the context it needs and nothing else. One job per agent, a guardrail behind it, no surprises in production."
        />
        <FeatureCard
          icons={[
            { nodeId: 'table', label: 'Spreadsheet' },
            { nodeId: 'create-row', label: 'Create row' },
            { nodeId: 'find', label: 'Find row' },
            { nodeId: 'update-row', label: 'Update row' },
          ]}
          title="Built-in spreadsheets"
          desc="A spreadsheet your automations create, find, and update. Start a run when a row changes, search it like a shared memory. Your operational data lives next to the automation, not in a fifth tool."
        />
        <FeatureCard
          icons={[
            { nodeId: 'code', label: 'Code' },
            { nodeId: 'database', label: 'Database' },
            { nodeId: 'http_request', label: 'HTTP request' },
            { nodeId: 'sftp', label: 'SFTP / files' },
          ]}
          title="Power tools, when you need them"
          desc="Run custom code, hit any database, fire an HTTP request, move files over SFTP. The escape hatches a technical owner actually needs, one click away, without leaving the canvas."
        />
        <FeatureCard
          icons={[
            { nodeId: 'approval', label: 'User approval' },
            { nodeId: 'interface', label: 'Interface' },
            { nodeId: 'wait', label: 'Wait' },
            { nodeId: 'webhook-trigger', label: 'Wait for webhook' },
          ]}
          title="Wait for a human"
          desc="Pause until you approve, until a user fills a form, until tomorrow at 9am, or until an external webhook fires. Refunds wait for your sign-off while the rest flows through. It picks up exactly where it stopped."
        />
      </FeatureGrid>
    </Section>
  );
}

// The agents showcase: clean hero-like bg-primary background (no glow, it read
// as a stray shadow behind the floating window), with the section text on the
// left and a live interactive replica of the real /app/agent window on the
// right, both directly in the section (no wrapping card). The top border marks
// the seam with the marketplace section (also bg-primary), the border idiom this
// page uses between same-color neighbours.
async function AgentsSection({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.agents' });
  const roster = await getTranslations({ locale, namespace: 'LandingHome.agentTeam' });
  // Read from the showcase's own id list rather than a copy of it: the showcase pairs this
  // array with its demo agents BY POSITION, so a reorder there would otherwise put one
  // agent's avatar and model on another's translated name, with nothing failing.
  const team = DEMO_AGENT_IDS.map((id) => ({ name: roster(`${id}.name`), description: roster(`${id}.description`) }));
  return (
    <section
      id="agents"
      className="relative overflow-hidden"
      style={{ background: 'var(--bg-primary)', borderTop: '1px solid var(--border-color)' }}
    >
      <div className="relative max-w-6xl mx-auto px-6 py-24 md:py-32">
        <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,400px)_1fr] gap-10 lg:gap-14 items-center">
          <div>
            <SectionEyebrow icon={Bot}>{t('eyebrow')}</SectionEyebrow>
            <SectionH2>{t('title')}</SectionH2>
            <SectionLead>{t('lead')}</SectionLead>
            <div className="mt-7">
              <SignInButton
                variant="primary"
                cta="agents_create_first"
                returnTo="/app/agent"
                className="inline-flex items-center justify-center h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
              >
                {t('cta')}
              </SignInButton>
            </div>
          </div>
          {/* Same backdrop as the hero, and the window runs off its right and bottom
              edges, so the frame shows on the left and top only.
              The roster is handed over TRANSLATED: the showcase's built-in one is English
              data, so without this the card stayed English on the five other locales while
              the section around it had been translated. */}
          <div className="landing-demo-panel" data-bleed="right bottom">
            <AgentsShowcase team={team} locale={locale} />
          </div>
        </div>
      </div>
    </section>
  );
}

/**
 * The marketplace on the landing page: the marquee of live applications, the
 * exact same cards as the in-app marketplace. The crawlable text listing of
 * every publication lives on /marketplace (and in the sitemap), not here: the
 * long "everything published" list under the marquee was dropped on purpose,
 * it read as a wall of links on a page meant to sell the product.
 */
// The agenda. Same section vocabulary as the agents one (eyebrow, H2, lead, a
// live replica of the real page) on the same bg-primary with a top border for
// the seam with its same-coloured neighbour, but the replica sits UNDER the text
// at full width instead of beside it: a seven-column calendar squeezed into half
// a section gives every chip 90px, which is under the width the app itself needs
// before it will print a name.
//
// `nowIso` is the server's clock, handed to the client replica so the calendar
// rendered on the server matches the one React hydrates; the replica then
// corrects it to the visitor's own.
async function AgendaSection({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.agenda' });
  return (
    // Band. The persona pages already render the agenda on theirs, so this is the homepage
    // catching up with them rather than a new look for the section.
    <section
      id="agenda"
      className="relative overflow-hidden"
      style={{ background: 'var(--bg-secondary)', borderTop: '1px solid var(--border-color)' }}
    >
      <div className="relative max-w-6xl mx-auto px-6 py-24 md:py-32">
        <SectionEyebrow icon={CalendarClock}>{t('eyebrow')}</SectionEyebrow>
        <SectionH2>{t('title')}</SectionH2>
        <SectionLead>{t('lead')}</SectionLead>
        <div className="mt-7">
          <SignInButton
            variant="primary"
            cta="agenda_open"
            returnTo="/app/agenda"
            className="inline-flex items-center justify-center h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
          >
            {t('cta')}
          </SignInButton>
        </div>

        {/* Framed on the left, right and top; the calendar runs off the bottom. */}
        <div className="mt-12 landing-demo-panel" data-bleed="bottom" data-crop="agenda">
          <AgendaShowcase nowIso={new Date().toISOString()} locale={locale} />
        </div>
      </div>
    </section>
  );
}

async function MarketplaceSection({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.marketplace' });
  return (
    // Band, matching the persona pages, which put their marketplace on the band too.
    <Section alt id="marketplace">
      <SectionEyebrow icon={Store}>{t('eyebrow')}</SectionEyebrow>
      <SectionH2>{t('title')}</SectionH2>
      <SectionLead>{t('lead')}</SectionLead>

      <div className="mt-12">
        <MarketplacePreview />
      </div>

      <p className="mt-12 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
        {t('footnote')}
      </p>
    </Section>
  );
}

async function PricingBlock({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.pricing' });
  return (
    <Section alt id="pricing">
      <SectionEyebrow icon={Sparkles}>{t('eyebrow')}</SectionEyebrow>
      <SectionH2>{t('title')}</SectionH2>
      <SectionLead>{t('lead')}</SectionLead>

      <div className="mt-12">
        <PricingSection />
      </div>
    </Section>
  );
}

async function FinalCta({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'LandingHome.finalCta' });
  const hero = await getTranslations({ locale, namespace: 'LandingHome.hero' });
  const deployment = await getTranslations({ locale, namespace: 'pricing.deployment' });
  return (
    <section id="final-cta" className="relative overflow-hidden" style={{ background: 'var(--bg-primary)', borderTop: '1px solid var(--border-color)' }}>
      <div className="cta-glow" aria-hidden="true" />
      <div className="relative max-w-4xl mx-auto px-6 py-24 text-center">
        <h2
          className="text-4xl md:text-5xl font-bold tracking-tight"
          style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}
        >
          {t('titleLineOne')}<br />{t('titleLineTwo')}
        </h2>
        <p className="mt-4 text-lg" style={{ color: 'var(--text-secondary)' }}>
          {t('lead')}
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <SignInButton
            variant="primary"
            cta="final_start_free"
            className="inline-flex items-center justify-center h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
          >
            {hero('startFree')}
          </SignInButton>
          <SelfHostLink
            section="final_cta"
            className="inline-flex items-center gap-2 h-9 px-4 rounded-xl text-sm font-medium border transition-colors hover:bg-[var(--bg-secondary)] cursor-pointer"
            style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}
          >
            <GithubMark className="w-4 h-4" /> {deployment('selfHosted')}
          </SelfHostLink>
        </div>
      </div>
    </section>
  );
}

function FeatureGrid({ children, cols = 3 }: { children: React.ReactNode; cols?: 2 | 3 }) {
  const colsCls = cols === 2 ? 'md:grid-cols-2' : 'md:grid-cols-2 lg:grid-cols-3';
  return <div className={`mt-12 grid grid-cols-1 ${colsCls} gap-4 md:gap-6`}>{children}</div>;
}

type FeatureIcon = { nodeId: string; label: string };

function FeatureCard({
  icons,
  title,
  desc,
}: {
  icons: FeatureIcon[];
  title: string;
  desc: string;
}) {
  const resolved = icons
    .map(({ nodeId, label }) => ({ label, entry: NODE_ICON_REGISTRY[nodeId] }))
    .filter((x): x is { label: string; entry: NonNullable<typeof x.entry> } => Boolean(x.entry));

  return (
    <div
      className="feature-card p-6 rounded-2xl transition-all duration-300 hover:-translate-y-0.5 flex flex-col"
      style={{
        background: 'var(--bg-tertiary)',
        border: '1px solid var(--border-color)',
        boxShadow: 'var(--landing-card-shadow)',
      }}
    >
      <div className="feature-flow">
        {resolved.map(({ label, entry }, i) => {
          const { icon: Icon, iconBg } = entry;
          return (
            <span key={label} className="feature-flow-item">
              {i > 0 && <span className="feature-flow-link" aria-hidden="true" />}
              <span title={label} className={`feature-node ${iconBg}`}>
                <Icon className="w-5 h-5 feature-node-icon" strokeWidth={1.7} />
              </span>
            </span>
          );
        })}
      </div>
      <h3 className="mt-5 text-base font-semibold text-center" style={{ color: 'var(--text-primary)' }}>{title}</h3>
      <p className="mt-2 text-sm leading-relaxed text-center" style={{ color: 'var(--text-secondary)' }}>{desc}</p>
    </div>
  );
}

function BrowserFrame({
  url,
  className,
  children,
}: {
  url: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <figure className={`browser-frame ${className ?? ''}`}>
      <div className="browser-chrome">
        <span className="browser-dot" style={{ background: '#ff5f57' }} />
        <span className="browser-dot" style={{ background: '#febc2e' }} />
        <span className="browser-dot" style={{ background: '#28c840' }} />
        <span className="browser-url">{url}</span>
      </div>
      <div className="browser-body">{children}</div>
    </figure>
  );
}

function MediaSlot({
  src,
  alt,
}: {
  src: string;
  alt: string;
}) {
  return (
    <img
      src={src}
      alt={alt}
      loading="lazy"
      className="block w-full h-auto"
    />
  );
}
