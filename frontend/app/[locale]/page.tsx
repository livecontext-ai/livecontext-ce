import { redirect } from 'next/navigation';
import {
  ArrowRight,
  Workflow,
  Store,
  Sparkles,
  Coins,
  ShieldCheck,
  Eye,
  Gauge,
  Lock,
  Server,
  SlidersHorizontal,
  Github,
} from 'lucide-react';
import { LandingFooter, LandingHeader, landingChromeStyles } from '@/components/landing/LandingShell';
import PricingSection from './_landing/PricingSection';
import SignInButton from './_landing/SignInButton';
import MarketplacePreview from './_landing/MarketplacePreview';
import HeroPhotoStack from './_landing/HeroPhotoStack';
import HashScroller from './_landing/HashScroller';
import { isMonoDarkIconSlug } from '@/lib/credentials/monoIconSlugs';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { IS_CE as IS_CE_DEPLOY } from '@/lib/edition';
import { NODE_ICON_REGISTRY } from '@/app/workflows/builder/data/nodeVisuals';
import LandingThemeProvider from '@/components/landing/LandingThemeProvider';

export const metadata = {
  title: { absolute: 'LiveContext: The AI automation platform. Chat, workflows, agents, apps.' },
  description:
    'The AI automation platform: describe the job in chat and LiveContext builds the workflow in front of you, runs it with agents that have scoped access and credit budgets, and ships it as an app for your team. Full audit, SAML SSO and workspaces.',
  robots: IS_CE_DEPLOY ? { index: false, follow: false } : undefined,
};

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
    src: '/landing/hero-stack/mechanism-workflow.webp',
    alt: 'LiveContext workflow builder',
    label: 'Workflow',
    description: 'The same automation, drawn. Branch on a classification, fan out into parallel paths, count, notify. Every node, every connection, every iteration visible at once on one readable graph.',
  },
  {
    url: 'livecontext.ai/app/interface',
    src: '/landing/hero-stack/mechanism-app.webp',
    alt: 'LiveContext generated app interface',
    label: 'App',
    description: 'Wrap the automation in a real interface. Search, filters, cards, action buttons, all fully interactive. Share the link with your team or customers, or let an agent open it and click on its own.',
  },
  {
    url: 'livecontext.ai/app/agent',
    src: '/landing/hero-stack/mechanism-agent.webp',
    alt: 'LiveContext agent dashboard',
    label: 'Agent',
    description: 'A roster of always-on agents, one per job. Each gets its own model, its own workflow, a scoped set of tools and files you can restrict at any time, a credit budget it cannot exceed, and a full audit trail. Schedule them, call them from the chat, or let them delegate.',
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
    <LandingThemeProvider className="min-h-screen">
      <style>{landingChromeStyles}{landingStyles}</style>

      <LandingHeader />

      <main>
        <HashScroller />
        <Hero />
        <TrustStrip />
        <MarketplaceSection />
        <BuilderSection />
        <ComparisonStrip />
        <PricingBlock />
        <FinalCta />
      </main>

      <LandingFooter />
    </LandingThemeProvider>
  );
}

function Hero() {
  return (
    <section className="relative overflow-visible">
      <div className="hero-glow" aria-hidden="true" />
      <div className="max-w-7xl mx-auto px-6 pt-24 pb-40 md:pt-32 md:pb-52">
        <div className="max-w-3xl mx-auto text-center">
          <span className="eyebrow">The AI automation platform</span>
          <h1 className="hero-h1 mt-4">
            One message <span className="underline underline-offset-8">in</span>.<br />
            A working automation <span className="underline underline-offset-8">out</span>.
          </h1>
          <div className="mt-8 flex flex-wrap justify-center gap-2">
            {[
              { Icon: SlidersHorizontal, label: 'You keep control' },
              { Icon: Gauge, label: 'Per-agent credit budgets' },
              { Icon: ShieldCheck, label: 'Scoped access, full audit' },
              { Icon: Eye, label: 'Per-agent metrics, no black box' },
              { Icon: Lock, label: 'SAML SSO & RBAC' },
              { Icon: Server, label: 'Cloud or self-hosted' },
              { Icon: Coins, label: 'Far fewer tokens' },
              { Icon: Store, label: 'Ready-to-use apps' },
            ].map(({ Icon, label }) => (
              <span
                key={label}
                className="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs"
                style={{ backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}
              >
                <Icon className="w-3.5 h-3.5" aria-hidden="true" /> {label}
              </span>
            ))}
          </div>
          <p className="mt-3 text-xs" style={{ color: 'var(--text-muted)' }}>
            Free tier · No credit card · Cancel any time
          </p>
          <div className="mt-6 flex flex-wrap justify-center gap-3">
            <SignInButton
              variant="primary"
              className="inline-flex items-center gap-2 h-12 px-6 rounded-full text-sm font-semibold transition-all hover:brightness-110 active:scale-[0.98] cursor-pointer"
            >
              Start free <ArrowRight className="w-4 h-4" />
            </SignInButton>
            <a
              href={SELF_HOSTED_GITHUB_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 h-12 px-6 rounded-full text-sm font-semibold transition-all cursor-pointer hover:brightness-110 bg-[var(--bg-tertiary)]"
              style={{ color: 'var(--text-primary)' }}
            >
              <Github className="w-4 h-4" /> Self-host
            </a>
          </div>
        </div>

        <div className="mt-10 md:mt-12">
          <HeroPhotoStack photos={HERO_PHOTOS} />
        </div>
      </div>
    </section>
  );
}

function ComparisonStrip() {
  const surfaces = ['Chat', 'Workflow', 'App', 'Agent'];
  const competitors = [
    { name: 'Zapier', has: [false, true, false, false] },
    { name: 'n8n', has: [false, true, false, false] },
    { name: 'Lindy', has: [true, false, false, true] },
    { name: 'Bubble', has: [false, false, true, false] },
    { name: 'LiveContext', has: [true, true, true, true], highlight: true },
  ];
  return (
    <section style={{ background: 'var(--bg-secondary)', borderTop: '1px solid var(--border-color)', borderBottom: '1px solid var(--border-color)' }}>
      <div className="max-w-5xl mx-auto px-6 py-16">
        <p className="text-center text-[11px] uppercase tracking-wider mb-2" style={{ color: 'var(--text-muted)' }}>
          Why most teams stitch their whole stack together
        </p>
        <h3 className="text-center text-2xl md:text-3xl font-bold mb-10" style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}>
          Get all four. Stitch nothing.
        </h3>

        <div className="rounded-2xl overflow-hidden" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}>
          <table className="w-full text-sm">
            <thead>
              <tr style={{ background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border-color)' }}>
                <th className="text-left p-4 text-[11px] uppercase tracking-wider font-semibold" style={{ color: 'var(--text-muted)' }}>Tool</th>
                {surfaces.map((s) => (
                  <th key={s} className="p-4 text-[11px] uppercase tracking-wider font-semibold" style={{ color: 'var(--text-muted)' }}>{s}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {competitors.map((c, idx) => (
                <tr key={c.name} style={{
                  borderBottom: idx < competitors.length - 1 ? '1px solid var(--border-color)' : 'none',
                  background: c.highlight ? 'var(--landing-highlight-row)' : 'transparent',
                }}>
                  <td className="p-4 font-semibold" style={{ color: c.highlight ? 'var(--text-primary)' : 'var(--text-secondary)' }}>
                    {c.name}
                  </td>
                  {c.has.map((ok, i) => (
                    <td key={i} className="p-4 text-center text-lg" style={{ color: ok ? (c.highlight ? 'var(--text-primary)' : 'var(--text-secondary)') : 'var(--text-muted)' }}>
                      {ok ? '✓' : '-'}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <p className="mt-6 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
          Build it once. It runs as all four, with every agent scoped, budgeted and audited.
        </p>
      </div>
    </section>
  );
}

function TrustStrip() {
  const trustLogos = [
    'gmail', 'slack', 'stripe', 'notion', 'github', 'salesforce', 'hubspot',
    'shopify', 'googledrive', 'airtable', 'openai', 'anthropic',
  ];
  return (
    <section style={{ borderTop: '1px solid var(--border-color)', borderBottom: '1px solid var(--border-color)', background: 'var(--bg-primary)' }}>
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
    <Section id="features">
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

function MarketplaceSection() {
  return (
    <Section alt id="marketplace">
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
    <Section id="pricing">
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
    <section className="relative overflow-hidden" style={{ background: 'var(--bg-secondary)', borderTop: '1px solid var(--border-color)' }}>
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
            className="inline-flex items-center gap-2 h-12 px-6 rounded-full text-sm font-semibold transition-all hover:brightness-110 active:scale-[0.98] cursor-pointer"
          >
            Start free <ArrowRight className="w-4 h-4" />
          </SignInButton>
          <a
            href={SELF_HOSTED_GITHUB_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 h-12 px-6 rounded-full text-sm font-semibold transition-all cursor-pointer hover:brightness-110 bg-[var(--bg-tertiary)]"
            style={{ color: 'var(--text-primary)' }}
          >
            <Github className="w-4 h-4" /> Self-host
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
    font-size: clamp(40px, 6vw, 64px);
    line-height: 1.05;
    letter-spacing: -0.025em;
    color: var(--text-primary);
  }

  .landing-root .hero-glow {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: var(--landing-hero-glow);
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
