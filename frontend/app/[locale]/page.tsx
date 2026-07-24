import { redirect } from 'next/navigation';
import {
  Bot,
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
import PricingSection from './_landing/PricingSection';
import SignInButton from './_landing/SignInButton';
import MarketplacePreview from './_landing/MarketplacePreview';
import HeroPhotoStack from './_landing/HeroPhotoStack';
import HeroFlowShowcase from './_landing/HeroFlowShowcase';
import AgentsShowcase from './_landing/AgentsShowcase';
import PersonaTabs, { type Persona } from './_landing/PersonaTabs';
import {
  CUSTOMER_LOGOS,
  LANDING_METRICS,
  TESTIMONIALS,
  metricsReady,
  testimonialsReady,
} from './_landing/socialProof';
import HashScroller from './_landing/HashScroller';
import { isMonoDarkIconSlug } from '@/lib/credentials/monoIconSlugs';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { IS_CE as IS_CE_DEPLOY } from '@/lib/edition';
import { NODE_ICON_REGISTRY } from '@/app/workflows/builder/data/nodeVisuals';
import LandingThemeProvider from '@/components/landing/LandingThemeProvider';
import JsonLd from '@/components/seo/JsonLd';
import Link from 'next/link';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

export const metadata = {
  title: { absolute: 'LiveContext: The AI automation platform. Chat, workflows, agents, apps.' },
  description:
    'Describe the job in chat and LiveContext builds the automation and its app in front of you, runs it on schedule, and lets you share the result as a link, a PDF or an export. Every step visible, budgets per agent, cloud or self-hosted.',
  // The landing content is hardcoded English on every locale URL (/, /fr, /es, ...),
  // so all locale variants canonicalize to the apex: Google indexes ONE landing
  // page instead of flagging 5 duplicates. Give each locale its own canonical +
  // hreflang cluster only when the landing is actually translated.
  alternates: { canonical: SITE_URL },
  robots: IS_CE_DEPLOY ? { index: false, follow: false } : undefined,
};

const LANDING_FAQ = [
  {
    question: 'What is LiveContext?',
    answer:
      'LiveContext is an AI automation platform. You describe a job in chat, the workflow builds itself in front of you, AI agents run it with scoped tool access and hard credit budgets, and the result ships as an app your team or customers can use. It includes 600+ API integrations, built-in data tables, a browser-use agent and a marketplace of forkable automations.',
  },
  {
    question: 'Do I need to know how to code?',
    answer:
      'No. You build by describing the job in chat and refining on a visual canvas. When you want them, power tools are there: a code node, raw HTTP requests, database queries and custom API definitions.',
  },
  {
    question: 'Can I self-host LiveContext?',
    answer:
      'Yes. The Community Edition is free and self-hosted: one docker compose up on your own server, with the code public on GitHub. The cloud edition adds managed hosting, SAML SSO, workspaces and platform credits.',
  },
  {
    question: 'How is LiveContext different from Zapier, n8n or Make?',
    answer:
      'Those tools focus on the pipeline; LiveContext covers the whole loop: the chat that builds the workflow, the agents that run it under budgets and scoped access, the app your team opens, and the tables that hold the data. See the detailed comparisons at livecontext.ai/compare.',
  },
  {
    question: 'How does pricing work?',
    answer:
      'There is a free tier with no credit card required. Paid plans are credit-based: you pick a credit tier and can cap the spend per agent with a hard budget it cannot exceed. The self-hosted Community Edition is free.',
  },
  {
    question: 'Which AI models can I use?',
    answer:
      'Major providers are supported, including Anthropic Claude, OpenAI, Google Gemini and DeepSeek. Use platform credits or bring your own API keys, and pick the model per agent.',
  },
];

const LANDING_JSON_LD = [
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
    url: SITE_URL,
  },
  {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: 'LiveContext',
    applicationCategory: 'BusinessApplication',
    operatingSystem: 'Web',
    url: SITE_URL,
    description:
      'AI automation platform: build workflows by chat, run them with budgeted AI agents, and ship them as apps. 600+ integrations, built-in tables, marketplace, cloud or self-hosted.',
    offers: {
      '@type': 'Offer',
      price: '0',
      priceCurrency: 'USD',
      description: 'Free tier on cloud; free self-hosted Community Edition.',
    },
  },
  {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: LANDING_FAQ.map((item) => ({
      '@type': 'Question',
      name: item.question,
      acceptedAnswer: { '@type': 'Answer', text: item.answer },
    })),
  },
];

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

  return (
    <LandingThemeProvider className="min-h-screen" respectStored>
      {/* ONE string child: React 19 only renders <style> content when it is a
          single string. Two expression children ({a}{b}) render an EMPTY style
          tag server-side and the full text client-side - a hydration mismatch
          (React #418) plus unstyled server HTML. */}
      <style>{landingChromeStyles + landingStyles}</style>
      {!IS_CE_DEPLOY && LANDING_JSON_LD.map((data, idx) => <JsonLd key={idx} data={data} />)}

      <LandingHeader />

      <main>
        <HashScroller />
        <Hero />
        <SocialProofStrip />
        <TrustStrip />
        <MarketplaceSection />
        <AgentsSection />
        <TestimonialsSection />
        <PricingBlock />
        <FinalCta />
      </main>

      <LandingFooter />
    </LandingThemeProvider>
  );
}

function Hero() {
  return (
    // No .hero-glow halo here: the radial read as a stray shadow behind the
    // showcase (same reason it was dropped from the agents section).
    <section className="relative overflow-visible">
      <div className="max-w-7xl mx-auto px-6 pt-14 pb-12 md:pt-20 md:pb-16">
        <div className="max-w-3xl mx-auto text-center">
          <span className="eyebrow">The AI Automation platform</span>
          <h1 className="hero-h1 mt-4">
            One message <span className="underline underline-offset-8">in</span>.<br />
            A working automation <span className="underline underline-offset-8">out</span>.
          </h1>
          <p className="mt-6 text-lg max-w-2xl mx-auto leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            600+ integrations and every major LLM, cloud or self-hosted. Build, deploy, and run
            your agents visually, in chat, or with code, and add a human in the loop wherever you want.
          </p>
          <div className="mt-7 flex flex-wrap justify-center gap-3">
            <SignInButton
              variant="primary"
              className="inline-flex items-center justify-center h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
            >
              Start free
            </SignInButton>
            <a
              href={SELF_HOSTED_GITHUB_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 h-9 px-4 rounded-xl text-sm font-medium border transition-colors hover:bg-[var(--bg-secondary)] cursor-pointer"
              style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}
            >
              <GithubMark className="w-4 h-4" /> Self-host
            </a>
          </div>
        </div>
        <div className="mt-8">
          <HeroFlowShowcase />
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
function SocialProofStrip() {
  const hasLogos = CUSTOMER_LOGOS.length > 0;
  const hasMetrics = metricsReady(LANDING_METRICS);
  if (!hasLogos && !hasMetrics) return null;

  return (
    <section style={{ background: 'var(--bg-secondary)', borderTop: '1px solid var(--border-color)' }}>
      <div className="max-w-6xl mx-auto px-6 py-12">
        {hasLogos && (
          <>
            <p className="text-center text-[11px] uppercase tracking-wider mb-6" style={{ color: 'var(--text-muted)' }}>
              They automate with LiveContext
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

// Renders only when `socialProof.ts` holds real customer quotes (see the SAMPLE
// kill switch there).
function TestimonialsSection() {
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

function FaqSection() {
  return (
    <Section alt id="faq">
      <div className="text-center">
        <SectionEyebrow icon={Sparkles}>FAQ</SectionEyebrow>
        <SectionH2>Frequently asked questions</SectionH2>
      </div>
      <div className="mt-12 max-w-3xl mx-auto space-y-4">
        {LANDING_FAQ.map((item) => (
          <details key={item.question} className="faq-item rounded-2xl p-6" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}>
            <summary className="flex items-center justify-between gap-4 cursor-pointer text-base font-semibold list-none" style={{ color: 'var(--text-primary)' }}>
              <span>{item.question}</span>
              <ChevronDown className="faq-chevron h-5 w-5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
            </summary>
            <p className="mt-3 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{item.answer}</p>
          </details>
        ))}
      </div>
      <p className="mt-8 text-sm text-center" style={{ color: 'var(--text-muted)' }}>
        More detail in the{' '}
        <a href="https://docs.livecontext.ai" className="underline underline-offset-2 hover:opacity-80">documentation</a>
        {' '}or the{' '}
        <Link href="/compare" className="underline underline-offset-2 hover:opacity-80">comparison pages</Link>.
      </p>
    </Section>
  );
}

function TrustStrip() {
  const trustLogos = [
    'gmail', 'slack', 'stripe', 'notion', 'github', 'salesforce', 'hubspot',
    'shopify', 'googledrive', 'airtable', 'openai', 'anthropic',
  ];
  return (
    <section style={{ borderTop: '1px solid var(--border-color)', borderBottom: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}>
      <div className="max-w-6xl mx-auto px-6 py-10">
        <p className="text-center text-[11px] uppercase tracking-wider mb-6" style={{ color: 'var(--text-muted)' }}>
          Connects to the tools your team already uses
        </p>
        <div className="flex flex-wrap items-center justify-center gap-x-10 gap-y-6">
          {trustLogos.map((slug) => (
            <img
              key={slug}
              src={`/icons/services/${slug}.svg`}
              alt={slug}
              loading="lazy"
              width={22}
              height={22}
              className={`logo-color ${isMonoDarkIconSlug(slug) ? 'logo-mono' : ''}`}
            />
          ))}
          <span className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
            + 14,000 tools
          </span>
        </div>
      </div>
    </section>
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
// the seam with the marketplace section (also bg-primary), the same border
// idiom TrustStrip uses between same-color neighbors.
function AgentsSection() {
  return (
    <section
      id="agents"
      className="relative overflow-hidden"
      style={{ background: 'var(--bg-primary)', borderTop: '1px solid var(--border-color)' }}
    >
      <div className="relative max-w-6xl mx-auto px-6 py-24 md:py-32">
        <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,400px)_1fr] gap-10 lg:gap-14 items-center">
          <div>
            <SectionEyebrow icon={Bot}>The agent workforce</SectionEyebrow>
            <SectionH2>Your whole agent team, on one screen.</SectionH2>
            <SectionLead>
              Every agent gets a face, a model you choose, a scoped set of tools, and
              a credit budget it cannot exceed. This is the real agents view, live:
              star a favorite, select a few cards.
            </SectionLead>
            <div className="mt-7">
              <SignInButton
                variant="primary"
                returnTo="/app/agent"
                className="inline-flex items-center justify-center h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
              >
                Create your first agent
              </SignInButton>
            </div>
          </div>
          <AgentsShowcase />
        </div>
      </div>
    </section>
  );
}

function MarketplaceSection() {
  return (
    <Section id="marketplace">
      <SectionEyebrow icon={Store}>Community marketplace</SectionEyebrow>
      <SectionH2>Fork what others built. Ship yours in a click.</SectionH2>
      <SectionLead>
        A living marketplace of automations that actually run, published by the people who
        use them every day. Every card is the real thing in motion, not a screenshot. Fork
        one and the whole stack comes with it: the workflow, the agents inside, the pages,
        the spreadsheets, even the files. Make it yours, then share it back for the next
        person to build on.
      </SectionLead>

      <div className="mt-12">
        <MarketplacePreview />
      </div>

      <p className="mt-12 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
        Publish what you build. Fork what others share. The whole community ships faster.
      </p>
    </Section>
  );
}

function PricingBlock() {
  return (
    <Section alt id="pricing">
      <SectionEyebrow icon={Sparkles}>Pricing</SectionEyebrow>
      <SectionH2>Simple pricing. Cap the spend per agent.</SectionH2>
      <SectionLead>
        Pick a plan, choose your credit tier, and set a budget per agent it cannot
        exceed. You only pay for what you run, with no hidden fees and no surprise overages.
      </SectionLead>

      <div className="mt-12">
        <PricingSection />
      </div>
    </Section>
  );
}

function FinalCta() {
  return (
    <section className="relative overflow-hidden" style={{ background: 'var(--bg-primary)', borderTop: '1px solid var(--border-color)' }}>
      <div className="cta-glow" aria-hidden="true" />
      <div className="relative max-w-4xl mx-auto px-6 py-24 text-center">
        <h2
          className="text-4xl md:text-5xl font-bold tracking-tight"
          style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}
        >
          Stop juggling tools.<br />Start with one.
        </h2>
        <p className="mt-4 text-lg" style={{ color: 'var(--text-secondary)' }}>
          Chat with it. Build with it. Ship with it. Send it to do the work for you.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <SignInButton
            variant="primary"
            className="inline-flex items-center justify-center h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
          >
            Start free
          </SignInButton>
          <a
            href={SELF_HOSTED_GITHUB_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 h-9 px-4 rounded-xl text-sm font-medium border transition-colors hover:bg-[var(--bg-secondary)] cursor-pointer"
            style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}
          >
            <GithubMark className="w-4 h-4" /> Self-host
          </a>
        </div>
      </div>
    </section>
  );
}

function Section({
  children,
  alt,
  id,
}: {
  children: React.ReactNode;
  alt?: boolean;
  id?: string;
}) {
  return (
    <section id={id} style={{ background: alt ? 'var(--bg-secondary)' : 'var(--bg-primary)' }}>
      <div className="max-w-6xl mx-auto px-6 py-24 md:py-32">{children}</div>
    </section>
  );
}

function SectionEyebrow({ icon: Icon, children }: { icon: React.ComponentType<React.SVGProps<SVGSVGElement>>; children: React.ReactNode }) {
  return (
    <div className="inline-flex items-center gap-2 eyebrow">
      <Icon className="w-3.5 h-3.5" />
      {children}
    </div>
  );
}

function SectionH2({ children }: { children: React.ReactNode }) {
  return (
    <h2
      className="mt-4 text-3xl md:text-5xl font-bold tracking-tight"
      style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em', lineHeight: 1.1 }}
    >
      {children}
    </h2>
  );
}

function SectionLead({ children, wide }: { children: React.ReactNode; wide?: boolean }) {
  return (
    <p
      className={`mt-6 text-lg ${wide ? 'max-w-5xl' : 'max-w-3xl'} leading-relaxed`}
      style={{ color: 'var(--text-secondary)' }}
    >
      {children}
    </p>
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

const landingStyles = `
  /* FAQ accordion: the chevron points down when collapsed and flips up when the
     <details> is open, so it reads as expandable. */
  .landing-root .faq-chevron {
    transition: transform 200ms ease;
  }

  .landing-root details.faq-item[open] .faq-chevron {
    transform: rotate(180deg);
  }

  .landing-root .eyebrow {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    font-weight: 600;
    color: var(--text-muted);
  }

  .landing-root .hero-h1 {
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: clamp(32px, 4.4vw, 50px);
    line-height: 1.08;
    letter-spacing: -0.025em;
    color: var(--text-primary);
  }

  .landing-root .hero-glow {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: var(--landing-hero-glow);
  }

  .landing-root .hero-prompt-caret {
    display: inline-block;
    width: 1.5px;
    height: 1.05em;
    margin-left: 2px;
    vertical-align: text-bottom;
    background: var(--text-primary);
    animation: hero-prompt-blink 1.05s steps(1) infinite;
  }

  @keyframes hero-prompt-blink {
    0%, 55% { opacity: 1; }
    56%, 100% { opacity: 0; }
  }

  /* ---- Persona showcase: the animated build-and-run scene in the hero. ---- */
  .landing-root .pshow-shell {
    max-width: 1100px;
    margin: 0 auto;
  }

  /* ---- "You, augmented": the agent squad + the selected agent's profile ---- */
  .landing-root .augment-squad {
    display: flex;
    justify-content: center;
    align-items: flex-end;
    padding: 26px 0 18px;
  }

  /* A stylised agent figure (head + shoulders bust), not a photo. The selected
     agent steps forward in colour; the others recede and grey out, so it reads
     as a squad standing behind you. */
  .landing-root .squad-bust {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 118px;
    margin: 0 -10px;
    padding: 10px 8px 12px;
    border-radius: 18px;
    border: 1px solid transparent;
    background: transparent;
    cursor: pointer;
    transform: rotate(var(--squad-rot, 0deg)) translateY(var(--squad-drop, 0px)) scale(0.9);
    transform-origin: 50% 120%;
    filter: grayscale(1);
    opacity: 0.55;
    transition: transform 340ms cubic-bezier(0.22, 1, 0.36, 1), filter 340ms ease,
      opacity 340ms ease, background 340ms ease, border-color 340ms ease;
  }

  .landing-root .squad-bust:hover {
    opacity: 0.82;
    filter: grayscale(0.4);
  }

  .landing-root .squad-bust.active {
    transform: rotate(0deg) translateY(-14px) scale(1.12);
    filter: none;
    opacity: 1;
    border-color: var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-frame-shadow);
  }

  .landing-root .squad-figure {
    position: relative;
    width: 78px;
    height: 78px;
    margin-bottom: 8px;
  }

  .landing-root .squad-figure-head {
    position: absolute;
    top: 2px;
    left: 50%;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    transform: translateX(-50%);
    background: linear-gradient(160deg, var(--bg-tertiary), var(--bg-hover));
  }

  .landing-root .squad-figure-body {
    position: absolute;
    bottom: 0;
    left: 50%;
    width: 68px;
    height: 46px;
    border-radius: 34px 34px 14px 14px;
    transform: translateX(-50%);
    background: linear-gradient(160deg, var(--bg-tertiary), var(--bg-hover));
  }

  .landing-root .squad-figure-badge {
    position: absolute;
    bottom: 6px;
    left: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    transform: translateX(-50%);
    color: var(--text-muted);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .squad-bust.active .squad-figure-head,
  .landing-root .squad-bust.active .squad-figure-body {
    background: var(--accent-primary);
  }

  .landing-root .squad-bust.active .squad-figure-badge {
    color: var(--accent-primary);
  }

  .landing-root .squad-label {
    font-size: 13px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .squad-role {
    font-size: 10px;
    color: var(--text-muted);
    opacity: 0;
    transition: opacity 220ms ease;
  }

  .landing-root .squad-bust.active .squad-role {
    opacity: 1;
  }

  @media (max-width: 640px) {
    .landing-root .augment-squad { flex-wrap: wrap; gap: 6px; }
    .landing-root .squad-bust { margin: 0; transform: scale(0.9); }
    .landing-root .squad-bust.active { transform: translateY(-4px) scale(1.02); }
  }

  /* The selected agent's profile: story on the left, live app (the star) on the
     right. Fixed columns so the layout is stable. */
  .landing-root .augment-stage {
    display: grid;
    grid-template-columns: minmax(0, 0.82fr) minmax(0, 1.18fr);
    gap: 22px;
    align-items: stretch;
    max-width: 1000px;
    margin: 8px auto 0;
  }

  @media (max-width: 899px) {
    .landing-root .augment-stage { grid-template-columns: 1fr; gap: 16px; }
  }

  .landing-root .augment-brief {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-width: 0;
  }

  .landing-root .augment-line {
    font-size: 15px;
    line-height: 1.5;
    color: var(--text-secondary);
  }

  .landing-root .augment-line-you {
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .augment-access {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 14px 16px;
    border-radius: 16px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .augment-access-label {
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--text-muted);
  }

  .landing-root .augment-access-logos {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  /* Each tool chip lights up (grey -> colour, scale) as the agent connects. */
  .landing-root .access-chip {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 42px;
    height: 42px;
    border-radius: 12px;
    border: 1px solid var(--border-color);
    background: var(--bg-secondary);
    filter: grayscale(1);
    opacity: 0.4;
    transform: scale(0.9);
    transition: filter 320ms ease, opacity 320ms ease,
      transform 320ms cubic-bezier(0.22, 1, 0.36, 1), box-shadow 320ms ease,
      border-color 320ms ease;
  }

  .landing-root .access-chip img {
    width: 22px;
    height: 22px;
    display: block;
  }

  .landing-root .access-chip.on {
    filter: none;
    opacity: 1;
    transform: scale(1);
    border-color: rgba(16, 185, 129, 0.45);
    box-shadow: 0 4px 12px -6px rgba(16, 185, 129, 0.4);
  }

  .landing-root .augment-app {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
  }

  /* The app card (reuses .pflow-inode chrome) fills the column and is the star. */
  .landing-root .augment-app .pflow-inode {
    height: 360px;
  }

  .landing-root .augment-underhood {
    font-size: 12px;
    text-align: center;
    color: var(--text-muted);
  }

  @media (max-width: 899px) {
    .landing-root .augment-app .pflow-inode { height: 320px; }
  }

  /* ---- "Meet your agents": cartoon roster + focused agent spotlight ---- */
  .landing-root .agentverse {
    max-width: 1080px;
    margin: 0 auto;
  }

  /* Your team: cartoon agents in a row, the focused one lifted, plus a "+1k"
     tile showing the team scales without limit. */
  .landing-root .roster-row {
    display: flex;
    justify-content: center;
    align-items: flex-start;
    gap: 4px;
    padding: 6px 0 8px;
  }

  .landing-root .roster {
    display: flex;
    justify-content: center;
    gap: 10px;
  }

  .landing-root .roster-agent {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 6px;
    background: transparent;
    border: none;
    cursor: pointer;
    opacity: 0.6;
    filter: grayscale(0.55);
    transition: opacity 260ms ease, filter 260ms ease,
      transform 260ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .landing-root .roster-avatar {
    display: block;
    width: 62px;
    height: 62px;
    border-radius: 50%;
    overflow: hidden;
    background: var(--bg-tertiary);
    border: 2px solid transparent;
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .roster-avatar img {
    width: 100%;
    height: 100%;
    display: block;
  }

  .landing-root .roster-name {
    font-size: 11px;
    font-weight: 600;
    color: var(--text-muted);
  }

  .landing-root .roster-agent:hover {
    opacity: 0.9;
    filter: none;
  }

  .landing-root .roster-agent.active {
    opacity: 1;
    filter: none;
    transform: translateY(-4px) scale(1.12);
  }

  .landing-root .roster-agent.active .roster-avatar {
    border-color: var(--accent-primary);
    box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.16), var(--landing-frame-shadow);
  }

  .landing-root .roster-agent.active .roster-name {
    color: var(--text-primary);
    font-weight: 700;
  }

  /* "+1k and more" tile: the agent team scales without limit. */
  .landing-root .roster-more {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 6px;
    align-self: center;
  }

  .landing-root .roster-more-stack {
    position: relative;
    display: flex;
    align-items: center;
    height: 62px;
  }

  .landing-root .roster-more-face {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    border: 2px solid var(--bg-primary);
    background: var(--bg-tertiary);
    margin-left: -18px;
    filter: grayscale(1);
    opacity: 0.6;
  }

  .landing-root .roster-more-face:first-child {
    margin-left: 0;
  }

  .landing-root .roster-more-plus {
    margin-left: -12px;
    z-index: 2;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 36px;
    height: 34px;
    padding: 0 9px;
    border-radius: 999px;
    background: var(--accent-primary);
    color: var(--accent-foreground);
    font-size: 12px;
    font-weight: 700;
  }

  .landing-root .roster-caption {
    max-width: 540px;
    margin: 0 auto 16px;
    text-align: center;
    font-size: 12px;
    color: var(--text-muted);
  }

  .landing-root .spotlight {
    display: grid;
    grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
    gap: 22px;
    align-items: start;
    max-width: 1000px;
    margin: 0 auto;
  }

  @media (max-width: 899px) {
    .landing-root .spotlight { grid-template-columns: 1fr; gap: 18px; }
  }

  /* The focused agent + its possibilities. */
  .landing-root .agent-profile {
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding: 20px;
    border-radius: 18px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .agent-hero {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  .landing-root .agent-hero-avatar {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 84px;
    height: 84px;
    border-radius: 50%;
    overflow: hidden;
    flex: none;
  }

  .landing-root .agent-hero-avatar img {
    width: 100%;
    height: 100%;
    display: block;
  }

  .landing-root .agent-hero-id {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .landing-root .agent-hero-name {
    font-size: 18px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .agent-hero-role {
    font-size: 13px;
    color: var(--text-secondary);
  }

  .landing-root .agent-hero-model {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-top: 2px;
    font-size: 11px;
    color: var(--text-muted);
  }

  .landing-root .agent-hero-augment {
    font-size: 14px;
    line-height: 1.5;
    color: var(--text-secondary);
  }

  .landing-root .agent-hero-you {
    font-weight: 700;
    color: var(--text-primary);
  }

  /* Customize affordance: a dashed, editable-looking button that says the whole
     agent is yours to shape. */
  .landing-root .agent-customize-row {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .landing-root .agent-customize {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    align-self: flex-start;
    font-size: 13px;
    font-weight: 600;
    color: var(--accent-primary);
    background: var(--bg-secondary);
    border: 1px dashed var(--accent-primary);
    border-radius: 10px;
    padding: 8px 14px;
    cursor: pointer;
    transition: background 200ms ease, transform 200ms ease;
  }

  .landing-root .agent-customize:hover {
    background: var(--bg-tertiary);
    transform: translateY(-1px);
  }

  .landing-root .agent-customize-hint {
    font-size: 11px;
    color: var(--text-muted);
  }

  .landing-root .agent-kit {
    display: flex;
    flex-direction: column;
    gap: 14px;
    padding-top: 16px;
    border-top: 1px solid var(--border-color);
  }

  .landing-root .agent-kit-section {
    display: flex;
    flex-direction: column;
    gap: 7px;
  }

  .landing-root .agent-kit-label {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: var(--text-muted);
  }

  .landing-root .agent-kit-resources {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .landing-root .agent-kit-resource {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 12px;
    color: var(--text-secondary);
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    border-radius: 9px;
    padding: 4px 9px;
  }

  .landing-root .agent-kit-tools {
    display: flex;
    gap: 8px;
  }

  .landing-root .agent-kit-tool {
    width: 26px;
    height: 26px;
  }

  .landing-root .agent-kit-foot {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .landing-root .agent-kit-chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 11px;
    color: var(--text-secondary);
    background: var(--bg-tertiary);
    border-radius: 999px;
    padding: 4px 10px;
  }

  .landing-root .agent-credit {
    margin-top: 14px;
    text-align: center;
    font-size: 10px;
    color: var(--text-muted);
    opacity: 0.7;
  }

  /* The workpanel avatar now holds the cartoon avatar image. */
  .landing-root .workpanel-avatar {
    overflow: hidden;
    background: var(--bg-tertiary);
  }

  .landing-root .workpanel-avatar img {
    width: 100%;
    height: 100%;
    display: block;
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .roster-agent { transition: none; }
  }

  .landing-root .agentverse-stage {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 24px;
    align-items: center;
  }

  @media (max-width: 899px) {
    .landing-root .agentverse-stage { grid-template-columns: 1fr; gap: 18px; }
  }

  /* You in the middle, agents around you on a ring. */
  .landing-root .constellation {
    position: relative;
    width: 100%;
    max-width: 470px;
    margin: 0 auto;
    aspect-ratio: 1 / 0.92;
  }

  .landing-root .constellation-links {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }

  .landing-root .constellation-link {
    stroke: var(--border-color);
    stroke-width: 0.4;
    stroke-dasharray: 1.4 1.6;
    opacity: 0.7;
  }

  .landing-root .constellation-link.active {
    stroke: #10b981;
    stroke-width: 0.6;
    opacity: 1;
    animation: constellation-flow 1s linear infinite;
  }

  @keyframes constellation-flow {
    to { stroke-dashoffset: -6; }
  }

  .landing-root .you-figure {
    position: absolute;
    left: 50%;
    top: 52%;
    transform: translate(-50%, -50%);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    z-index: 2;
    pointer-events: none;
  }

  .landing-root .you-svg {
    width: 58px;
    height: 58px;
  }

  .landing-root .you-svg circle,
  .landing-root .you-svg path {
    fill: none;
    stroke: var(--text-muted);
    stroke-width: 2;
    stroke-dasharray: 3 3;
    stroke-linecap: round;
    opacity: 0.6;
  }

  .landing-root .you-label {
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--text-muted);
  }

  .landing-root .agent-node {
    position: absolute;
    transform: translate(-50%, -50%);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 0;
    background: transparent;
    border: none;
    cursor: pointer;
    z-index: 3;
    opacity: 0.72;
    transition: transform 280ms cubic-bezier(0.22, 1, 0.36, 1), opacity 280ms ease;
  }

  .landing-root .agent-node-avatar {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 54px;
    height: 54px;
    border-radius: 50%;
    background: var(--bg-primary);
    color: var(--text-secondary);
    border: 1px solid var(--border-color);
    box-shadow: var(--landing-card-shadow);
    transition: background 260ms ease, color 260ms ease, border-color 260ms ease,
      box-shadow 260ms ease;
  }

  .landing-root .agent-node-name {
    font-size: 11px;
    font-weight: 600;
    color: var(--text-muted);
  }

  .landing-root .agent-node:hover,
  .landing-root .agent-node.hovered {
    opacity: 1;
  }

  .landing-root .agent-node:hover .agent-node-avatar,
  .landing-root .agent-node.hovered .agent-node-avatar {
    border-color: var(--accent-primary);
  }

  .landing-root .agent-node.active {
    opacity: 1;
    transform: translate(-50%, -50%) scale(1.14);
    z-index: 6;
  }

  .landing-root .agent-node.active .agent-node-avatar {
    background: var(--accent-primary);
    color: var(--accent-foreground);
    border-color: var(--accent-primary);
    box-shadow: 0 0 0 4px rgba(16, 185, 129, 0.18), var(--landing-frame-shadow);
  }

  .landing-root .agent-node.active .agent-node-name {
    color: var(--text-primary);
    font-weight: 700;
  }

  /* Hover profile card, anchored above the agent. */
  .landing-root .agent-fiche-anchor {
    position: absolute;
    transform: translate(var(--fiche-shift, -50%), calc(-100% - 14px));
    z-index: 20;
    pointer-events: none;
  }

  .landing-root .agent-fiche {
    width: 226px;
    padding: 12px;
    border-radius: 14px;
    background: var(--bg-primary);
    border: 1px solid var(--border-color);
    box-shadow: var(--landing-frame-shadow);
    display: flex;
    flex-direction: column;
    gap: 10px;
    animation: fiche-in 180ms ease;
  }

  @keyframes fiche-in {
    from { opacity: 0; transform: translateY(4px); }
    to { opacity: 1; transform: none; }
  }

  .landing-root .agent-fiche-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .landing-root .agent-fiche-name {
    font-size: 13px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .agent-fiche-model {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    font-size: 10px;
    color: var(--text-muted);
    white-space: nowrap;
  }

  .landing-root .agent-fiche-section {
    display: flex;
    flex-direction: column;
    gap: 5px;
  }

  .landing-root .agent-fiche-label {
    font-size: 9px;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: var(--text-muted);
  }

  .landing-root .agent-fiche-resources {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }

  .landing-root .agent-fiche-resource {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: var(--text-secondary);
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    border-radius: 8px;
    padding: 3px 7px;
  }

  .landing-root .agent-fiche-tools {
    display: flex;
    gap: 6px;
  }

  .landing-root .agent-fiche-tool {
    width: 20px;
    height: 20px;
  }

  .landing-root .agent-fiche-foot {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }

  .landing-root .agent-fiche-chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 10px;
    color: var(--text-secondary);
    background: var(--bg-tertiary);
    border-radius: 999px;
    padding: 3px 8px;
  }

  /* The focused agent at work. */
  .landing-root .agentverse-work {
    min-width: 0;
  }

  .landing-root .workpanel {
    display: flex;
    flex-direction: column;
    border-radius: 18px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
    overflow: hidden;
  }

  .landing-root .workpanel-head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 13px 16px;
    border-bottom: 1px solid var(--border-color);
    background: var(--bg-secondary);
  }

  .landing-root .workpanel-avatar {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    background: var(--accent-primary);
    color: var(--accent-foreground);
    flex: none;
  }

  .landing-root .workpanel-title {
    display: flex;
    flex-direction: column;
    min-width: 0;
    flex: 1;
  }

  .landing-root .workpanel-name {
    font-size: 13px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .workpanel-sub {
    font-size: 11px;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .workpanel-you {
    color: var(--text-secondary);
    font-weight: 600;
  }

  .landing-root .workpanel-status {
    font-size: 10px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: #3b82f6;
    background: rgba(59, 130, 246, 0.12);
    border-radius: 999px;
    padding: 3px 9px;
    flex: none;
  }

  .landing-root .workpanel-status.done {
    color: #059669;
    background: rgba(16, 185, 129, 0.14);
  }

  .landing-root .workpanel-body {
    display: flex;
    flex-direction: column;
  }

  .landing-root .workpanel-timeline {
    padding: 12px 14px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .landing-root .work-step {
    display: flex;
    align-items: center;
    gap: 10px;
    animation: work-step-in 280ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  @keyframes work-step-in {
    from { opacity: 0; transform: translateX(-6px); }
    to { opacity: 1; transform: none; }
  }

  .landing-root .work-step-glyph {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    border-radius: 8px;
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    color: var(--text-secondary);
    flex: none;
  }

  .landing-root .agentverse-step-logo {
    width: 16px;
    height: 16px;
    display: block;
  }

  .landing-root .work-step-text {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-width: 0;
    flex: 1;
  }

  .landing-root .work-step-name {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-primary);
  }

  .landing-root .work-step-detail {
    font-size: 12px;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .work-step.is-reason .work-step-text {
    font-size: 12px;
    color: var(--text-muted);
    font-style: italic;
  }

  .landing-root .work-step.is-reason .work-step-glyph {
    background: transparent;
    border: none;
    color: var(--text-muted);
  }

  .landing-root .work-step-status {
    flex: none;
    color: #059669;
    display: flex;
    align-items: center;
  }

  .landing-root .work-step.running .work-step-status {
    color: #3b82f6;
  }

  .landing-root .work-spinner {
    display: block;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    border: 2px solid rgba(59, 130, 246, 0.25);
    border-top-color: #3b82f6;
    animation: pshow-spin 720ms linear infinite;
  }

  /* The delivered interface, revealed at the very end like a generated image,
     shown as the content of a browser-style tab (no info header). */
  .landing-root .work-canvas {
    margin: 6px 14px 14px;
    border: 1px solid var(--border-color);
    border-radius: 12px;
    overflow: hidden;
    background: var(--bg-secondary);
    box-shadow: var(--landing-node-shadow);
    animation: work-step-in 300ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .landing-root .work-canvas-tabs {
    display: flex;
    align-items: flex-end;
    gap: 8px;
    padding: 6px 8px 0;
  }

  .landing-root .work-canvas-tab {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    font-size: 11px;
    font-weight: 600;
    color: var(--text-primary);
    background: var(--bg-primary);
    border: 1px solid var(--border-color);
    border-bottom: none;
    border-radius: 9px 9px 0 0;
    padding: 6px 12px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .work-canvas-body {
    height: 150px;
    overflow: hidden;
    background: #fbfaf8;
    border-top: 1px solid var(--border-color);
  }

  /* Image-generation reveal: blur -> sharp with a scan sweep. The iframe is
     rendered tall (fully laid out, no inner scroll / autofocus jump) and clipped
     by the body so the recognisable top of the app shows. */
  .landing-root .work-gen {
    position: relative;
    width: 100%;
    height: 100%;
    animation: work-gen-clear 1000ms ease-out;
  }

  .landing-root .work-gen .pshow-app-iframe {
    width: 100%;
    height: 460px;
    border: 0;
    display: block;
  }

  .landing-root .work-gen-scan {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: linear-gradient(120deg, transparent 34%, rgba(16, 185, 129, 0.3) 50%, transparent 66%);
    background-size: 300% 100%;
    animation: work-gen-scan 1000ms ease-out forwards;
  }

  @keyframes work-gen-clear {
    0% { filter: blur(16px) saturate(0.7); opacity: 0; transform: scale(1.03); }
    55% { filter: blur(5px); opacity: 1; }
    100% { filter: blur(0); opacity: 1; transform: none; }
  }

  @keyframes work-gen-scan {
    0% { background-position: 120% 0; opacity: 1; }
    100% { background-position: -140% 0; opacity: 0; }
  }

  /* Sub-agent avatars in the resource chips and the activity timeline. */
  .landing-root .agent-kit-resource-avatar {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    background: var(--bg-tertiary);
    display: block;
  }

  .landing-root .agentverse-step-avatar {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: var(--bg-tertiary);
    display: block;
  }

  /* Bare delivered-app content (no header / no tab): the whole app, scaled down
     to fit so nothing is cropped. */
  .landing-root .work-view {
    margin: 8px 14px 14px;
    border: 1px solid var(--border-color);
    border-radius: 12px;
    overflow: hidden;
    background: #fbfaf8;
    box-shadow: var(--landing-node-shadow);
  }

  .landing-root .work-view-scale {
    position: relative;
    width: 100%;
    overflow: hidden;
  }

  .landing-root .work-view-iframe {
    border: 0;
    display: block;
    transform-origin: top left;
    background: #fbfaf8;
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .work-canvas,
    .landing-root .work-gen { animation: none; filter: none; }
    .landing-root .work-gen-scan { display: none; }
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .constellation-link.active,
    .landing-root .work-step,
    .landing-root .work-spinner,
    .landing-root .agent-fiche {
      animation: none;
    }
    .landing-root .agent-node { transition: none; }
  }

  /* Fixed-size conversation card: constant height and width, so it never grows
     or shrinks with the message content (the prompt and the agent's status line
     always sit in the same box). */
  .landing-root .pshow-chat {
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 10px;
    height: 128px;
    padding: 16px 18px;
    border-radius: 16px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
  }

  .landing-root .pshow-agent-line {
    min-width: 0;
  }

  /* Persona fan: cards spread like a hand of cards, active card lifted upright.
     Extra top padding gives the rotated cards room so nothing clips against the
     section above. */
  .landing-root .pshow-fan {
    display: flex;
    justify-content: center;
    align-items: flex-end;
    padding: 22px 0 16px;
  }

  .landing-root .pshow-fan-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 120px;
    margin: 0 -9px;
    padding: 8px 8px 10px;
    border-radius: 16px;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-card-shadow);
    cursor: pointer;
    transform: rotate(var(--fan-rot, 0deg)) translateY(var(--fan-drop, 0px));
    transform-origin: 50% 130%;
    transition: transform 320ms cubic-bezier(0.22, 1, 0.36, 1), box-shadow 320ms ease, border-color 320ms ease;
  }

  .landing-root .pshow-fan-card:hover {
    transform: rotate(var(--fan-rot, 0deg)) translateY(calc(var(--fan-drop, 0px) - 8px));
  }

  .landing-root .pshow-fan-card.active {
    z-index: 2;
    border-color: var(--accent-primary);
    box-shadow: var(--landing-frame-shadow);
    transform: rotate(0deg) translateY(-14px) scale(1.06);
  }

  /* On the fanned (inactive) cards the role line would collide with the
     neighbour card; show it only on the lifted active card. */
  .landing-root .pshow-fan-role {
    opacity: 0;
    transition: opacity 220ms ease;
  }

  .landing-root .pshow-fan-card.active .pshow-fan-role {
    opacity: 1;
  }

  .landing-root .pshow-fan-media {
    position: relative;
    display: block;
    width: 100%;
    aspect-ratio: 4 / 3;
    border-radius: 10px;
    overflow: hidden;
    background: var(--bg-tertiary);
  }

  .landing-root .pshow-fan-img {
    position: absolute;
    inset: 0;
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: opacity 240ms ease;
  }

  .landing-root .pshow-fan-fallback {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-muted);
    background: linear-gradient(135deg, var(--bg-tertiary) 0%, var(--bg-hover) 100%);
  }

  .landing-root .pshow-fan-label {
    margin-top: 8px;
    font-size: 12px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .landing-root .pshow-fan-role {
    font-size: 10px;
    color: var(--text-muted);
  }

  @media (max-width: 640px) {
    .landing-root .pshow-fan {
      flex-wrap: wrap;
      gap: 8px;
    }

    .landing-root .pshow-fan-card {
      width: 96px;
      margin: 0;
      transform: none;
    }

    .landing-root .pshow-fan-card.active {
      transform: translateY(-4px) scale(1.04);
    }
  }

  .landing-root .pshow-stage {
    display: flex;
    flex-direction: column;
    gap: 14px;
    max-width: 980px;
    margin: 16px auto 0;
  }

  /* Work row: the builder canvas on the left, a handoff arrow, and the interface
     card on the right. The columns are fixed so the layout is stable; the
     interface card fills in when the run reaches it. */
  .landing-root .pshow-work {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 28px 344px;
    align-items: stretch;
    gap: 8px;
  }

  .landing-root .pshow-handoff {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-muted);
    opacity: 0.5;
    transition: color 320ms ease, opacity 320ms ease;
  }

  .landing-root .pshow-work.showapp .pshow-handoff {
    color: #10b981;
    opacity: 1;
  }

  .landing-root .pshow-side {
    min-width: 0;
    height: 340px;
  }

  /* The interface app, revealed inside the card when the run completes. */
  .landing-root .pshow-app-reveal {
    width: 100%;
    height: 100%;
    animation: pshow-reveal 440ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  @media (max-width: 899px) {
    .landing-root .pshow-work {
      grid-template-columns: 1fr;
      gap: 10px;
    }

    .landing-root .pshow-handoff {
      transform: rotate(90deg);
    }

    .landing-root .pshow-side {
      height: 300px;
    }
  }

  /* The live builder canvas frame: a fixed-height, self-contained box that holds
     the read-only ReactFlow. Its height stays constant across every phase, so
     the whole scene has a stable footprint. */
  .landing-root .pflow-frame {
    position: relative;
    width: 100%;
    height: 340px;
    border-radius: 16px;
    border: 1px solid var(--border-color);
    background: var(--bg-secondary);
    box-shadow: var(--landing-card-shadow);
    overflow: hidden;
  }

  .landing-root .pflow-frame-skeleton {
    width: 100%;
    height: 100%;
    background: var(--bg-secondary);
    animation: pshow-breathe 2.4s ease-in-out infinite;
  }

  /* ReactFlow root: transparent so the frame surface and dotted Background show
     through. */
  .landing-root .pflow-canvas {
    width: 100%;
    height: 100%;
    background: transparent;
  }

  @media (max-width: 640px) {
    .landing-root .pflow-frame {
      height: 300px;
    }
  }

  .landing-root .showcase-bubble {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    max-width: 92%;
    padding: 10px 14px;
    border-radius: 14px;
    font-size: 13px;
    line-height: 1.45;
    text-align: left;
  }

  .landing-root .showcase-bubble-user {
    align-self: flex-end;
    min-height: 40px;
    background: var(--accent-primary);
    color: var(--accent-foreground);
    border-bottom-right-radius: 4px;
  }

  .landing-root .showcase-bubble-user .hero-prompt-caret {
    background: var(--accent-foreground);
  }

  .landing-root .showcase-bubble-agent {
    align-self: flex-start;
    background: var(--bg-tertiary);
    color: var(--text-secondary);
    border-bottom-left-radius: 4px;
    animation: pshow-pop 320ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  /* Real builder node card on the ReactFlow canvas: same shell the builder
     renders (rounded, border-2, ~190px, surface, p-3) + the real NodeHeader
     inside. The border color comes from the platform's own getStatusBorderColor,
     so pending/running/completed match a live run exactly. */
  .landing-root .pflow-node {
    position: relative;
    width: 190px;
    padding: 12px;
    border-radius: 16px;
    border: 2px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-node-shadow);
    /* Entrance: each node slides up + scales in as the workflow creates it. */
    opacity: 0;
    transform: translateY(16px) scale(0.92);
    transition: border-color 200ms ease, box-shadow 200ms ease,
      opacity 340ms cubic-bezier(0.22, 1, 0.36, 1),
      transform 340ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .landing-root .pflow-node.is-visible {
    opacity: 1;
    transform: translateY(0) scale(1);
  }

  .landing-root .pflow-node[data-status='running'] {
    box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15), var(--landing-node-shadow);
  }

  /* ReactFlow handles as round dots, same look as the builder (dot in the border
     color, ring in the surface color). */
  .landing-root .pflow-handle.react-flow__handle {
    width: 10px;
    height: 10px;
    min-width: 0;
    min-height: 0;
    border-radius: 999px;
    background: var(--border-color);
    border: 2px solid var(--bg-primary);
  }

  /* Blue running "scan" overlay, identical to the canvas node's shimmer. */
  .landing-root .pshow-node-shimmer {
    position: absolute;
    inset: 0;
    pointer-events: none;
    border-radius: 16px;
    background: linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%);
    background-size: 200% 100%;
    animation: shimmer-scan 2.5s ease-in-out infinite;
    z-index: 5;
  }

  /* Run-status chip, bottom-right like the builder's NodeStatusBadge. */
  .landing-root .pshow-node-status {
    position: absolute;
    bottom: 8px;
    right: 8px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 999px;
    color: #059669;
    z-index: 6;
  }

  .landing-root .pflow-node[data-status='completed'] .pshow-node-status {
    background: rgba(16, 185, 129, 0.14);
  }

  .landing-root .pshow-spinner {
    width: 12px;
    height: 12px;
    border-radius: 999px;
    border: 2px solid rgba(59, 130, 246, 0.25);
    border-top-color: #3b82f6;
    animation: pshow-spin 720ms linear infinite;
  }

  /* The interface NODE on the canvas: same shell as the builder's
     InterfacePreviewNode (rounded, status border-2, node header on top, the live
     interface filling the body). Fixed size so the ReactFlow layout is stable. */
  .landing-root .pflow-inode {
    position: relative;
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
    border-radius: 16px;
    border: 2px solid var(--border-color);
    background: var(--bg-primary);
    box-shadow: var(--landing-node-shadow);
    overflow: hidden;
    transition: border-color 300ms ease;
  }

  .landing-root .pshow-work.showapp .pflow-inode {
    animation: pshow-card-pop 440ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .landing-root .pshow-inode-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    border-bottom: 1px solid var(--border-color);
    background: var(--bg-secondary);
  }

  .landing-root .pshow-inode-label {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-primary);
    white-space: nowrap;
  }

  .landing-root .pshow-inode-url {
    flex: 1;
    min-width: 0;
    text-align: right;
    font-size: 11px;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .pshow-inode-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 999px;
    background: rgba(16, 185, 129, 0.14);
    color: #059669;
    flex: none;
  }

  .landing-root .pshow-inode-body {
    flex: 1;
    min-height: 0;
    display: flex;
  }

  /* The live interface iframe (real HTML/CSS/JS, sandboxed). */
  .landing-root .pshow-app-iframe {
    width: 100%;
    height: 100%;
    min-height: 0;
    border: 0;
    display: block;
    background: #fbfaf8;
  }

  /* Placeholder shown in the interface node body until the run completes. */
  .landing-root .pflow-inode-wait {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--bg-secondary);
  }

  .landing-root .pshow-placeholder-label {
    font-size: 13px;
    color: var(--text-muted);
    animation: pshow-breathe 2.4s ease-in-out infinite;
  }

  @keyframes pshow-pop {
    0% { opacity: 0; transform: translateY(8px) scale(0.97); }
    100% { opacity: 1; transform: translateY(0) scale(1); }
  }

  @keyframes pshow-grow {
    0% { transform: scaleY(0); }
    100% { transform: scaleY(1); }
  }

  @keyframes pshow-flow {
    0% { background-position: 0 -100%; }
    100% { background-position: 0 100%; }
  }

  @keyframes pshow-spin {
    to { transform: rotate(360deg); }
  }

  @keyframes pshow-breathe {
    0%, 100% { opacity: 0.55; }
    50% { opacity: 1; }
  }

  @keyframes pshow-reveal {
    0% { opacity: 0; transform: translateY(10px) scale(0.98); }
    100% { opacity: 1; transform: translateY(0) scale(1); }
  }

  @keyframes pshow-card-pop {
    0% { transform: scale(0.985); }
    55% { transform: scale(1.012); }
    100% { transform: scale(1); }
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .showcase-bubble-agent,
    .landing-root .pshow-placeholder-label,
    .landing-root .pshow-spinner,
    .landing-root .pshow-app-reveal,
    .landing-root .pshow-work.showapp .pflow-inode {
      animation: none;
    }

    /* Nodes appear in place, no slide. */
    .landing-root .pflow-node {
      opacity: 1;
      transform: none;
      transition: none;
    }
  }

  /* Aggregate counters (SocialProofStrip). */
  .landing-root .metric-value {
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: clamp(28px, 3.4vw, 38px);
    letter-spacing: -0.02em;
    color: var(--text-primary);
  }

  /* HowItWorks step badges. */
  .landing-root .step-number {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 999px;
    font-size: 13px;
    font-weight: 700;
    background: var(--accent-primary);
    color: var(--accent-foreground);
  }

  /* Persona tabs. */
  .landing-root .persona-tab-row {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
  }

  .landing-root .persona-tab {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 9px 16px;
    border-radius: 999px;
    font-size: 14px;
    font-weight: 600;
    border: 1px solid var(--border-color);
    background: var(--bg-primary);
    color: var(--text-secondary);
    cursor: pointer;
    transition: background 180ms ease, color 180ms ease, border-color 180ms ease;
  }

  .landing-root .persona-tab:hover {
    color: var(--text-primary);
    border-color: var(--text-muted);
  }

  .landing-root .persona-tab.active {
    background: var(--accent-primary);
    border-color: var(--accent-primary);
    color: var(--accent-foreground);
  }

  .landing-root .persona-card {
    transition: transform 300ms ease, box-shadow 300ms ease;
  }

  .landing-root .persona-card:hover {
    transform: translateY(-2px);
  }

  .landing-root .cta-glow {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: var(--landing-cta-glow);
  }

  /* Offset in-page anchor scrolling for the sticky header (h-20 = 80px). */
  .landing-root section[id] {
    scroll-margin-top: 96px;
  }

  .landing-root .feature-flow {
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .landing-root .feature-flow-item {
    display: inline-flex;
    align-items: center;
  }

  .landing-root .feature-flow-link {
    flex: 0 0 16px;
    width: 16px;
    height: 2px;
    border-radius: 2px;
    background: linear-gradient(90deg, rgba(94, 90, 84, 0) 0%, var(--border-color) 35%, var(--border-color) 65%, rgba(94, 90, 84, 0) 100%);
  }

  .landing-root .feature-node {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    border-radius: 9999px;
    overflow: hidden;
    box-shadow: var(--landing-node-shadow);
    transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  /* Node-flow icon color follows the LANDING theme (decoupled from <body> .dark). */
  .landing-root .feature-node-icon {
    color: var(--landing-icon-color);
  }

  .landing-root .feature-card:hover .feature-node {
    transform: translateY(-2px);
  }

  .landing-root .hero-stack-shell {
    position: relative;
    perspective: 2400px;
    perspective-origin: 50% 50%;
  }

  .landing-root .hero-stack {
    position: relative;
    width: 100%;
    max-width: 960px;
    margin: 0 auto;
    display: grid;
    grid-template-areas: "stack";
    transform-style: preserve-3d;
  }

  .landing-root .hero-stack-card {
    grid-area: stack;
    border-radius: 16px;
    cursor: pointer;
    transform-origin: center;
    will-change: transform, opacity, filter, box-shadow;
    transition:
      transform 900ms cubic-bezier(0.22, 1, 0.36, 1),
      opacity 700ms ease-out,
      filter 700ms ease-out,
      box-shadow 700ms ease-out;
  }

  .landing-root .hero-stack-card[data-pos="0"] {
    z-index: 5;
    transform: translate3d(0, 0, 0);
    opacity: 1;
    filter: none;
  }

  .landing-root .hero-stack-card[data-pos="1"] {
    z-index: 4;
    transform: translate3d(40px, 22px, -50px) rotateY(-3deg) scale(0.96);
    opacity: 0.85;
    filter: saturate(0.95);
  }

  .landing-root .hero-stack-card[data-pos="2"] {
    z-index: 3;
    transform: translate3d(80px, 44px, -100px) rotateY(-5deg) scale(0.92);
    opacity: 0.6;
    filter: saturate(0.85) brightness(0.95);
  }

  .landing-root .hero-stack-card[data-pos="3"] {
    z-index: 2;
    transform: translate3d(120px, 66px, -150px) rotateY(-7deg) scale(0.88);
    opacity: 0.35;
    filter: saturate(0.7) brightness(0.92);
  }

  .landing-root .hero-stack-card[data-pos="4"] {
    z-index: 1;
    transform: translate3d(160px, 88px, -200px) rotateY(-9deg) scale(0.84);
    opacity: 0.18;
    filter: saturate(0.5) brightness(0.88);
  }

  .landing-root .hero-stack-card .browser-frame {
    box-shadow: var(--landing-frame-shadow-strong);
  }

  .landing-root .hero-stack-card .browser-body {
    aspect-ratio: 16 / 9;
  }

  .landing-root .hero-stack-photo {
    position: relative;
    width: 100%;
    height: 100%;
    overflow: hidden;
    background: var(--bg-primary);
    background-position: center;
    background-repeat: no-repeat;
    background-size: cover;
  }

  .landing-root .hero-stack-video {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    background: var(--bg-primary);
  }

  .landing-root .hero-stack-card[data-active="true"] {
    cursor: zoom-in;
  }

  .landing-root .hero-stack-expand {
    position: absolute;
    top: 44px;
    right: 12px;
    z-index: 5;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 8px;
    border: 1px solid rgba(237, 236, 234, 0.25);
    background: rgba(23, 22, 20, 0.65);
    color: #edecea;
    cursor: pointer;
    opacity: 0;
    transition: opacity 160ms ease, background 160ms ease;
  }

  .landing-root .hero-stack-card[data-active="true"]:hover .hero-stack-expand,
  .landing-root .hero-stack-expand:focus-visible {
    opacity: 1;
  }

  .landing-root .hero-stack-expand:hover {
    background: rgba(23, 22, 20, 0.9);
  }

  /* Prev/next arrows - large screens only (the dot tray handles smaller widths).
     The focus tray is position:absolute top:100%, so the shell height equals the
     card height → top:50% centers the arrows on the active screenshot. */
  .landing-root .hero-stack-arrow {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    z-index: 6;
    display: none;
    opacity: 0;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    border-radius: 999px;
    border: 1px solid rgba(255, 255, 255, 0.16);
    background: rgba(23, 22, 20, 0.72);
    color: #fff;
    cursor: pointer;
    backdrop-filter: blur(6px);
    -webkit-backdrop-filter: blur(6px);
    transition: opacity 200ms ease, background 200ms ease, transform 200ms ease;
  }

  .landing-root .hero-stack-arrow:hover {
    background: rgba(23, 22, 20, 0.92);
    transform: translateY(-50%) scale(1.06);
  }

  .landing-root .hero-stack-arrow:focus-visible {
    outline: 2px solid var(--landing-accent, #fff);
    outline-offset: 2px;
  }

  .landing-root .hero-stack-arrow-prev {
    left: max(8px, calc(50% - 532px));
  }

  .landing-root .hero-stack-arrow-next {
    right: max(8px, calc(50% - 532px));
  }

  @media (min-width: 1024px) {
    .landing-root .hero-stack-arrow {
      display: flex;
    }

    .landing-root .hero-stack-shell:hover .hero-stack-arrow,
    .landing-root .hero-stack-arrow:focus-visible {
      opacity: 1;
    }
  }

  .landing-root .hero-lightbox {
    position: fixed;
    inset: 0;
    z-index: 200;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 32px;
    background: rgba(10, 9, 8, 0.88);
    backdrop-filter: blur(6px);
    cursor: zoom-out;
  }

  .landing-root .hero-lightbox-content {
    max-width: min(1720px, 96vw);
    max-height: 92vh;
    cursor: default;
    border-radius: 12px;
    overflow: hidden;
    box-shadow: 0 40px 120px rgba(0, 0, 0, 0.6);
  }

  .landing-root .hero-lightbox-media {
    display: block;
    max-width: min(1720px, 96vw);
    max-height: 92vh;
    width: auto;
    height: auto;
  }

  .landing-root .hero-lightbox-close {
    position: absolute;
    top: 20px;
    right: 24px;
    z-index: 201;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: 50%;
    border: 1px solid rgba(237, 236, 234, 0.3);
    background: rgba(23, 22, 20, 0.7);
    color: #edecea;
    font-size: 16px;
    cursor: pointer;
  }

  .landing-root .hero-lightbox-close:hover {
    background: rgba(42, 41, 37, 0.95);
  }

  /* Prev/next arrows inside the enlarged view - large screens only. */
  .landing-root .hero-lightbox-arrow {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    z-index: 201;
    display: none;
    align-items: center;
    justify-content: center;
    width: 52px;
    height: 52px;
    border-radius: 50%;
    border: 1px solid rgba(237, 236, 234, 0.3);
    background: rgba(23, 22, 20, 0.7);
    color: #edecea;
    cursor: pointer;
    backdrop-filter: blur(6px);
    -webkit-backdrop-filter: blur(6px);
    transition: background 200ms ease, transform 200ms ease;
  }

  .landing-root .hero-lightbox-arrow:hover {
    background: rgba(42, 41, 37, 0.95);
    transform: translateY(-50%) scale(1.06);
  }

  .landing-root .hero-lightbox-arrow:focus-visible {
    outline: 2px solid var(--landing-accent, #fff);
    outline-offset: 2px;
  }

  .landing-root .hero-lightbox-arrow-prev {
    left: 24px;
  }

  .landing-root .hero-lightbox-arrow-next {
    right: 24px;
  }

  @media (min-width: 1024px) {
    .landing-root .hero-lightbox-arrow {
      display: flex;
    }
  }

  .landing-root .marketplace-marquee {
    height: 920px;
    overflow: hidden;
    -webkit-mask-image: linear-gradient(to bottom, transparent, black 6%, black 94%, transparent);
    mask-image: linear-gradient(to bottom, transparent, black 6%, black 94%, transparent);
  }

  .landing-root .marketplace-col {
    display: flex;
    flex-direction: column;
    animation: marketplace-scroll-up 48s linear infinite;
  }

  .landing-root .marketplace-col.scroll-down {
    animation-name: marketplace-scroll-down;
    animation-duration: 56s;
  }

  .landing-root .marketplace-col-item {
    margin-bottom: 24px;
  }

  .landing-root .marketplace-marquee:hover .marketplace-col {
    animation-play-state: paused;
  }

  @keyframes marketplace-scroll-up {
    from { transform: translateY(0); }
    to { transform: translateY(-50%); }
  }

  @keyframes marketplace-scroll-down {
    from { transform: translateY(-50%); }
    to { transform: translateY(0); }
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .marketplace-col {
      animation: none;
    }
  }

  .landing-root .hero-focus-tray {
    position: absolute;
    top: 100%;
    left: 50%;
    transform: translateX(-50%);
    width: min(640px, calc(100% - 48px));
    margin: 48px 0 0 0;
    display: grid;
    gap: 12px;
  }

  .landing-root .hero-focus-copy {
    position: relative;
    min-height: 58px;
  }

  .landing-root .hero-focus-panel {
    display: grid;
    gap: 7px;
    align-items: start;
    justify-items: center;
    text-align: center;
  }

  .landing-root .hero-focus-heading {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    width: 100%;
    min-width: 0;
    font-family: inherit;
    font-size: 12px;
    line-height: 1.25;
    text-transform: none;
    letter-spacing: 0;
  }

  .landing-root .hero-focus-count {
    display: inline-grid;
    grid-template-columns: 2ch auto 2ch;
    column-gap: 7px;
    align-items: center;
    justify-items: center;
    min-width: 62px;
    text-align: center;
  }

  .landing-root .hero-focus-index {
    color: var(--text-primary);
    font-weight: 700;
    justify-self: end;
  }

  .landing-root .hero-focus-slash {
    color: var(--text-muted);
  }

  .landing-root .hero-focus-total {
    justify-self: start;
  }

  .landing-root .hero-focus-total,
  .landing-root .hero-focus-separator {
    color: var(--text-muted);
  }

  .landing-root .hero-focus-separator {
    margin: 0 2px;
  }

  .landing-root .hero-focus-label {
    color: var(--text-primary);
    font-weight: 700;
  }

  .landing-root .hero-focus-description {
    max-width: 430px;
    margin: 0 auto;
    font-size: 13px;
    line-height: 1.45;
    text-align: center;
    color: var(--text-secondary);
  }

  .landing-root .hero-focus-dashes {
    display: inline-flex;
    gap: 6px;
    width: auto;
    justify-self: center;
  }

  .landing-root .hero-focus-dash {
    position: relative;
    height: 3px;
    width: 18px;
    flex: 0 0 18px;
    overflow: hidden;
    border-radius: 2px;
    border: 0;
    background: var(--landing-dash-track);
    cursor: pointer;
    padding: 0;
    transition: width 240ms ease-out, flex-basis 240ms ease-out, background 240ms ease-out;
  }

  .landing-root .hero-focus-dash.active {
    width: 56px;
    flex-basis: 56px;
    background: var(--landing-dash-track-active);
  }

  .landing-root .hero-focus-dash-fill {
    position: absolute;
    inset: 0;
    width: 100%;
    border-radius: inherit;
    background: var(--text-primary);
    transform: scaleX(0);
    transform-origin: left;
  }

  .landing-root .hero-focus-dash.active .hero-focus-dash-fill {
    animation: hero-focus-dash-fill 4200ms linear forwards;
  }

  .landing-root .hero-stack-shell.is-paused .hero-focus-dash-fill {
    animation-play-state: paused;
  }

  @keyframes hero-focus-dash-fill {
    0% {
      transform: scaleX(0);
    }
    100% {
      transform: scaleX(1);
    }
  }

  @media (max-width: 1023px) {
    .landing-root .hero-stack-shell {
      perspective: 1800px;
    }
  }

  @media (max-width: 1023px) {
    .landing-root .hero-stack {
      max-width: 720px;
    }
  }

  @media (max-width: 820px) {
    .landing-root .hero-stack-shell {
      perspective: 1500px;
    }

    .landing-root .hero-stack-card[data-pos="0"] {
      transform: translate3d(0, 0, 0) rotateY(-3deg) rotateX(1deg) scale(1.015);
    }

    .landing-root .hero-stack-card[data-pos="1"] {
      transform: translate3d(0, 10px, -35px) rotateY(-4deg) rotateX(1deg) scale(0.97);
    }

    .landing-root .hero-stack-card[data-pos="2"] {
      transform: translate3d(0, 20px, -70px) rotateY(-5deg) rotateX(1deg) scale(0.94);
    }

    .landing-root .hero-stack-card[data-pos="3"] {
      transform: translate3d(0, 30px, -105px) rotateY(-6deg) rotateX(1deg) scale(0.91);
    }

    .landing-root .hero-stack-card[data-pos="4"] {
      transform: translate3d(0, 40px, -140px) rotateY(-7deg) rotateX(1deg) scale(0.88);
    }
  }

  @media (min-width: 721px) and (max-width: 820px) {
    .landing-root .hero-stack-shell {
      perspective: 1800px;
    }

    .landing-root .hero-stack-card[data-pos="0"] {
      transform: translate3d(0, 0, 0) rotateY(-6deg) rotateX(2deg) scale(1.02);
    }

    .landing-root .hero-stack-card[data-pos="1"] {
      transform: translate3d(42px, 18px, -50px) rotateY(-8deg) rotateX(2deg) scale(0.96);
    }

    .landing-root .hero-stack-card[data-pos="2"] {
      transform: translate3d(84px, 36px, -100px) rotateY(-10deg) rotateX(2deg) scale(0.92);
    }

    .landing-root .hero-stack-card[data-pos="3"] {
      transform: translate3d(126px, 54px, -150px) rotateY(-12deg) rotateX(2deg) scale(0.88);
    }

    .landing-root .hero-stack-card[data-pos="4"] {
      transform: translate3d(168px, 72px, -200px) rotateY(-14deg) rotateX(2deg) scale(0.84);
    }

    .landing-root .hero-focus-tray {
      margin-top: 86px;
    }
  }

  @media (max-width: 720px) {
    .landing-root .hero-stack-shell {
      perspective: 1200px;
    }

    .landing-root .hero-focus-tray {
      width: calc(100% - 16px);
      margin: 34px 0 0 16px;
    }

    .landing-root .hero-focus-copy {
      min-height: 82px;
    }

    .landing-root .hero-focus-description {
      font-size: 12px;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root .hero-stack-card {
      transition: none;
    }

    .landing-root .hero-focus-dash,
    .landing-root .hero-focus-dash-fill {
      animation: none;
      transition: none;
    }
  }

  .landing-root .hero-flow {
    display: inline-flex;
    align-items: center;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.12em;
    color: var(--text-muted);
  }

  .landing-root .hero-flow > span {
    color: var(--text-secondary);
    font-weight: 600;
  }

  .landing-root .browser-frame {
    border-radius: 16px;
    overflow: hidden;
    border: 1px solid var(--border-color);
    background: var(--bg-secondary);
    box-shadow: var(--landing-frame-shadow);
    margin: 0;
  }

  .landing-root .browser-chrome {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 36px;
    padding: 0 12px;
    background: var(--bg-tertiary);
    border-bottom: 1px solid var(--border-color);
  }

  .landing-root .browser-dot {
    width: 11px;
    height: 11px;
    border-radius: 50%;
    display: inline-block;
    flex-shrink: 0;
  }

  .landing-root .browser-url {
    flex: 1;
    text-align: center;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 11px;
    color: var(--text-muted);
    background: var(--bg-secondary);
    padding: 4px 12px;
    border-radius: 6px;
    max-width: 70%;
    margin: 0 auto;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .landing-root .browser-body {
    background: var(--bg-primary);
  }

  .landing-root .logo-tile {
    transition: all 0.2s ease;
  }

  .landing-root .logo-tile:hover {
    background: var(--bg-tertiary) !important;
    transform: translateY(-2px);
  }

  .landing-root .install-pill {
    transition: background 0.2s ease, color 0.2s ease;
  }

  .landing-root a:hover .install-pill {
    background: var(--accent-primary) !important;
    color: var(--accent-foreground) !important;
  }

  .landing-root .org-pipe {
    width: 1px;
    height: 16px;
    background: var(--border-color);
  }

  .landing-root .logo-color {
    opacity: 0.85;
    transition: opacity 0.2s ease;
  }

  .landing-root .logo-color:hover {
    opacity: 1;
  }

  @media (prefers-reduced-motion: reduce) {
    .landing-root *, .landing-root *::before, .landing-root *::after {
      animation-duration: 0.01ms !important;
      transition-duration: 0.01ms !important;
    }
  }
`;
