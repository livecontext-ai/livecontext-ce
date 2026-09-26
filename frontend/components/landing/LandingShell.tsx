import Link from 'next/link';
import LogoAnimate from '@/components/LogoAnimate';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import LandingNavAnchor from '@/app/[locale]/_landing/LandingNavAnchor';
import LandingLanguageSelect from '@/components/landing/LandingLanguageSelect';
import LandingThemeProvider from '@/components/landing/LandingThemeProvider';
import LandingThemeToggle from '@/components/landing/LandingThemeToggle';
import { docsHref } from '@/lib/docs/docsHostRewrite';
import { IS_CE } from '@/lib/edition/edition';
import FooterIntegrations from '@/components/landing/FooterIntegrations';
import { WELL_KNOWN_MODELS } from '@/lib/models/wellKnownModels';
import { PERSONA_FOOTER_LABELS, PERSONA_KEYS, personaHref, type PersonaKey } from '@/components/landing/personas/personas';
// The /models page owns its URL contract; the footer is a consumer of it, so the
// param name lives in one place. This is a server component, so pulling the
// catalogue in with it costs nothing in the browser bundle.
import { providerHref } from '@/app/models/_components/modelsQuery';

// Shared chrome (header + footer + base CSS vars) used by the landing page
// (`app/[locale]/page.tsx`) and the public sub-pages (`/about`, `/contact`,
// `/legal/*`, `/changelog`, `/docs`). The remaining header anchor targets
// `/#pricing` so it scrolls on the landing page AND navigates-then-scrolls from
// any other public page. Marketplace is NOT an anchor: it is a real page
// (`/marketplace`) listing every publication, so the header links to it.
//
// IMPORTANT: most of those sub-pages render at the app root, OUTSIDE the
// `[locale]` tree, so they have NO `NextIntlClientProvider`. Any component
// rendered here (header/footer children) MUST stay intl-context-free - calling
// next-intl's `useTranslations`/`useLocale`/`useRouter` (from `@/i18n/navigation`)
// throws "No intl context found" on those pages and crashes them into the error
// boundary. See `LandingNavAnchor` for the locale-context-free anchor pattern.

// The public site owns a SELF-CONTAINED theme, decoupled from the app: the full
// palette (`--bg-*`, `--text-*`, `--border-color`, `--accent-*`, `--expression-color`)
// AND the decorative tokens are defined here for `.landing-root` (LIGHT) and
// `.landing-root.dark` (DARK). The `dark` class is driven by `LandingThemeProvider`
// (DEFAULT LIGHT, persisted under `landing-theme`), NOT by the app-wide ThemeProvider
// on <body>. This lets the public site default to a warm light theme for every
// visitor while the logged-in app keeps following the user's OS/preference. The
// LIGHT palette is deliberately WARM (cream whites, amber glows) as the light
// counterpart of the warm-neutral dark palette below; it intentionally diverges
// from `globals.css` `:root`, which stays cool for the app.
export const landingChromeStyles = `
  .landing-root {
    /* light palette - cool neutral (matches the app's light palette, no beige) */
    --bg-primary: #ffffff;
    --bg-secondary: #f5f6f8;
    --bg-tertiary: #eceff3;
    --bg-hover: #e5e7eb;
    --text-primary: #111827;
    --text-secondary: #4b5563;
    --text-muted: #6b7280;
    --border-color: #d5dbe4;
    --accent-primary: #0b0d16;
    --accent-secondary: #1d2330;
    --accent-hover: #151927;
    --accent-foreground: #f6f7f9;
    --expression-color: #1d4ed8;

    /* light decorative tokens - cool neutral (no amber/beige) */
    --landing-header-bg: rgba(255, 255, 255, 0.85);
    --landing-hero-glow: radial-gradient(ellipse 800px 400px at 50% 0%, rgba(17, 24, 39, 0.05) 0%, rgba(255, 255, 255, 0) 70%);
    --landing-cta-glow: radial-gradient(ellipse 720px 380px at 50% 0%, rgba(17, 24, 39, 0.05) 0%, rgba(245, 246, 248, 0) 70%);
    --landing-highlight-row: rgba(202, 158, 88, 0.12);
    --landing-dash-track: rgba(28, 26, 23, 0.14);
    --landing-dash-track-active: rgba(28, 26, 23, 0.22);
    --landing-card-shadow: 0 8px 32px rgba(28, 26, 23, 0.07);
    --landing-frame-shadow: 0 20px 60px rgba(28, 26, 23, 0.10);
    --landing-frame-shadow-strong: 0 30px 80px rgba(28, 26, 23, 0.14), 0 8px 18px rgba(28, 26, 23, 0.08);
    --landing-node-shadow: 0 6px 16px rgba(28, 26, 23, 0.10);
    --landing-icon-color: #1c1a17;

    background: var(--bg-primary);
    color: var(--text-primary);
    font-family: var(--font-inter), 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  }

  .landing-root.dark {
    /* dark palette - the PLATFORM's dark theme (mirrors globals.css .dark, warm
       neutral), so the public dark mode matches the app the visitor signs into */
    --bg-primary: #171614;
    --bg-secondary: #1f1e1b;
    --bg-tertiary: #2a2925;
    --bg-hover: #57534e;
    --text-primary: #edecea;
    --text-secondary: #a39f97;
    --text-muted: #736f67;
    --border-color: #5e5a54;
    --accent-primary: #edecea;
    --accent-secondary: #c7c4bd;
    --accent-hover: #d7d4ce;
    --accent-foreground: #171614;
    --expression-color: #38bdf8;

    /* dark decorative tokens */
    --landing-header-bg: rgba(23, 22, 20, 0.85);
    --landing-hero-glow: radial-gradient(ellipse 800px 400px at 50% 0%, rgba(58, 46, 31, 0.55) 0%, rgba(23, 22, 20, 0) 70%);
    --landing-cta-glow: radial-gradient(ellipse 720px 380px at 50% 0%, rgba(58, 46, 31, 0.6) 0%, rgba(31, 30, 27, 0) 70%);
    --landing-highlight-row: rgba(58, 46, 31, 0.25);
    --landing-dash-track: rgba(237, 236, 234, 0.16);
    --landing-dash-track-active: rgba(237, 236, 234, 0.22);
    --landing-card-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
    --landing-frame-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
    --landing-frame-shadow-strong: 0 30px 80px rgba(0, 0, 0, 0.48), 0 8px 18px rgba(0, 0, 0, 0.35);
    --landing-node-shadow: 0 6px 16px rgba(0, 0, 0, 0.28);
    --landing-icon-color: #f1f5f9;
  }

  /* Skip link (rendered only when a surface passes skipLinkTarget): off-screen
     until focused, then pinned top-left above the sticky header (WCAG 2.4.1). */
  .landing-root .landing-skip-link {
    position: absolute;
    left: 1rem;
    top: -4rem;
    z-index: 100;
    padding: 0.5rem 0.875rem;
    font-size: 0.875rem;
    font-weight: 600;
    color: var(--accent-foreground);
    background: var(--accent-primary);
    border-radius: 0.5rem;
  }

  .landing-root .landing-skip-link:focus {
    top: 0.75rem;
    outline: 2px solid var(--expression-color);
    outline-offset: 2px;
  }

  /* The cookie consent banner mounts in the locale layout, OUTSIDE .landing-root,
     so it would otherwise follow the APP theme while the public site is light-only.
     These styles only ship on public pages (landingChromeStyles): re-bind the theme
     vars it consumes (bg-theme-* / text-theme-* / border-theme / button accents) to
     the landing light palette so the banner matches the page. */
  div.cookie-consent-banner {
    --bg-primary: #ffffff;
    --bg-secondary: #f8f6f2;
    --bg-tertiary: #f0ede7;
    --bg-hover: #e7e3da;
    --text-primary: #1c1a17;
    --text-secondary: #55504a;
    --text-muted: #7d776e;
    --border-color: #ddd7cd;
    --accent-primary: #171614;
    --accent-secondary: #2a2925;
    --accent-hover: #33312c;
    --accent-foreground: #faf9f7;
  }
`;

// Prefix an in-app path with the main-site origin so the shared header/footer
// link to livecontext.ai when this chrome renders on a sub-host (the docs
// subdomain). `base` is passed only by the docs layout; elsewhere it is
// undefined → the path stays relative (unchanged behaviour).
function withBase(base: string | undefined, path: string): string {
  return base ? `${base}${path}` : path;
}


/** The GitHub octocat mark (currentColor), shared by the header nav, the footer
 *  social circle and the landing's Self-host CTAs so they all use the SAME icon. */
export function GithubMark({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
    </svg>
  );
}


/**
 * Every piece of text in the header and footer, so a localised page can hand over its own.
 *
 * <p>`locale` is the one field that is not copy. It exists because the persona row links to
 * pages that HAVE a localised sibling: without it the French footer showed French anchor text
 * on an English href, and a crawler with no NEXT_LOCALE cookie followed it straight out of the
 * French tree. Every other chrome destination is single-language, so nothing else needs it.
 *
 * <p>The shell CANNOT read an intl context: it renders on the public pages that live outside
 * the `[locale]` tree (/docs, /legal, /marketplace, /about), where calling next-intl throws.
 * That is why its copy was English literals, and why it stayed English on /fr even after the
 * page inside it had been translated. A prop crosses that boundary without breaking it: the
 * localised pages pass labels, the others pass nothing and keep the English below.
 *
 * <p>Untranslated on purpose: LiveContext, Changelog, GitHub and the competitor names, plus
 * the model and integration names, which are catalogue data rather than copy. Changelog is here
 * because nothing else in the product translates it; Marketplace is NOT, because the signed-in
 * app does (`sidebar.nav.marketplace` is "Marktplatz" in de and "市场" in zh), and a visitor
 * meeting two different words for one destination is worse than either word. Everything else in the header and footer is here, INCLUDING the
 * copy that is not visible as body text: the `aria-label` of the GitHub link, of the theme
 * toggle and of the language select. A control with no text of its own is named by its
 * aria-label, so leaving one in English leaves the control in English.
 */
export interface ShellLabels {
  pricing: string; selfHosted: string; signIn: string; getStarted: string; docs: string;
  product: string; models: string; resources: string; compare: string; company: string; legal: string;
  /** Heading of the persona row. Not named `useCases`: a `useX` member trips the hooks lint. */
  personasHeading: string;
  marketplace: string;
  /** Locale prefix for the persona links. Not copy: see the note above. */
  locale?: string;
  workflows: string; agents: string; interfaces: string; tables: string; integrations: string;
  videos: string; status: string; allIntegrations: string;
  language: string; toLightTheme: string; toDarkTheme: string;
  about: string; careers: string; soon: string; contact: string;
  privacy: string; terms: string; notice: string;
  /** Takes the competitor's name, because the word order differs: "Alternative a Zapier". */
  alternativeTo: (brand: string) => string;
  tagline: string; rights: string;
  personas: Record<PersonaKey, string>;
}

/** What the shell shows when nobody hands it anything: the copy it always had. */
export const DEFAULT_SHELL_LABELS: ShellLabels = {
  pricing: 'Pricing', selfHosted: 'Self-hosted', signIn: 'Sign in', getStarted: 'Get started free', docs: 'Docs',
  product: 'Product', models: 'Models', resources: 'Resources', compare: 'Compare', company: 'Company', legal: 'Legal',
  personasHeading: 'Use cases', marketplace: 'Marketplace',
  // No locale: the pages that take the defaults are the ones outside the [locale] tree, whose
  // persona links are correctly the unprefixed English ones.
  workflows: 'Workflows', agents: 'Agents', interfaces: 'Interfaces & apps', tables: 'Tables & data', integrations: 'Integrations',
  videos: 'Videos', status: 'Status', allIntegrations: 'All integrations',
  language: 'Language', toLightTheme: 'Switch to light theme', toDarkTheme: 'Switch to dark theme',
  about: 'About', careers: 'Careers', soon: 'Soon', contact: 'Contact',
  privacy: 'Privacy Policy', terms: 'Terms of Service', notice: 'Legal Notice',
  alternativeTo: (brand) => `${brand} alternative`,
  tagline: 'The AI automation platform. Describe a job, watch the workflow build itself, and ship it as an app your team can use. Cloud or self-hosted.',
  rights: 'All rights reserved.',
  personas: PERSONA_FOOTER_LABELS,
};

export function LandingHeader({ extra, siteBaseUrl, labels = DEFAULT_SHELL_LABELS }: { extra?: React.ReactNode; siteBaseUrl?: string; labels?: ShellLabels } = {}) {
  return (
    <header className="sticky top-0 z-50 backdrop-blur" style={{ background: 'var(--landing-header-bg)', borderBottom: '1px solid var(--border-color)' }}>
      {/* Below the nav's breakpoint the bar is the brand and the pill, nothing else, and at
          320px - the narrowest phone still in use - German needed 14px more than it had. Three
          things give way, each only where it costs nothing: the gap (sm), the brand's right
          margin, which exists to separate it from a nav that does not render below md, and the
          bar padding. The padding is scoped to `max-[374px]` rather than to `sm` on purpose:
          every other container on these pages is `px-6`, so a padding that yielded all the way
          to 639px would leave the brand 8px left of the hero, the footer and the copyright line
          on every phone. The shortfall itself runs out around 344px, and 374 is the nearest
          bound above it: a device between the two gets 8px it does not need, which is cheaper
          than picking the exact number and being wrong about it on another font. */}
      <div className="max-w-6xl mx-auto px-6 max-[374px]:px-4 h-20 flex items-center justify-between gap-2 sm:gap-3">
        <Link href={withBase(siteBaseUrl, '/')} className="flex items-center md:mr-1 shrink-0 group/logo relative cursor-pointer">
          <div className="relative flex items-center justify-center transition-opacity duration-300">
            {/* Decorative: the brand name is right beside it as real text, so announcing the
                mark as well made the link read "Logo LiveContext" in every language, with the
                first half of that never translated. */}
            <LogoAnimate size="md" className="text-theme-primary" decorative />
          </div>
          <span className="text-xl font-light text-theme-primary transition-colors duration-300 livecontext-title opacity-100 scale-100 w-auto">
            LiveContext
          </span>
        </Link>
        {/* gap-4 at md: the logo, the nav and the right-hand cluster do not fit at 768px
            with gap-8. Back to five entries since Integrations moved to the footer, which
            was already tight at that count, so the smaller gap stays. It went from gap-5
            to gap-4 when the labels stopped being English: every entry here is a
            translation, and the German nav ("Marktplatz", "Selbst gehostet") is ~40px
            wider than the English one it was measured against.
            `whitespace-nowrap`: without it the flex row resolves an overflow by WRAPPING
            the longest label, and a wrapped label inside the fixed-height pill next door
            is the bug this header shipped in French. Nothing here may wrap; the row is
            sized so it does not have to. */}
        <nav className="hidden md:flex items-center gap-4 lg:gap-8 text-sm whitespace-nowrap" style={{ color: 'var(--text-secondary)' }}>
          {/* A real destination, not an in-page anchor. The public marketplace
              (`/marketplace`) is the crawlable index of every published listing,
              so linking it from the chrome of every public page is what puts
              each listing one click from anywhere on the site. As an anchor
              this was a scroll on the landing and a bounce back to the landing
              from everywhere else, and it linked to no listing at all. */}
          {/* /integrations is NOT in the header. It stays one click away from every public
              page through the footer column and the landing's own section, which is what
              keeps the connector tree crawlable; in the header it was the sixth entry and
              pushed the nav to gap-5 to fit at 768px. */}
          <Link href={withBase(siteBaseUrl, '/marketplace')} className="hover:opacity-80 transition-opacity">{labels.marketplace}</Link>
          <LandingNavAnchor targetId="pricing" baseUrl={siteBaseUrl} className="hover:opacity-80 transition-opacity cursor-pointer">{labels.pricing}</LandingNavAnchor>
          <Link href={withBase(siteBaseUrl, '/changelog')} className="hover:opacity-80 transition-opacity">Changelog</Link>
          <Link href={docsHref(siteBaseUrl)} prefetch={false} className="hover:opacity-80 transition-opacity">{labels.docs}</Link>
          <a
            href="https://github.com/livecontext-ai"
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`${labels.selfHosted} (GitHub)`}
            className="inline-flex items-center gap-1.5 hover:opacity-80 transition-opacity"
          >
            <GithubMark className="w-4 h-4" />
            {labels.selfHosted}
          </a>
        </nav>
        {/* shrink-0: the two sign-in affordances are the point of the bar, so the nav gives
            way before they do. Without it the flex row takes the space back out of the pill,
            whose label then wraps to two lines inside a fixed h-9 box and spills out of it. */}
        <div className="flex items-center gap-2 lg:gap-3 shrink-0">
          {extra}
          {/* Hidden from 768px to 858px, and nowhere else. That is the band where the nav
              appears while the bar is still narrow, and it is the only place the row does not
              fit: measured with this link in, 768px is 15px short in French and Portuguese and
              32px short in German, and every locale has room again by 859px. This is the one
              entry in the bar that is not load-bearing - the pill beside it opens the same
              sign-in for a visitor who already has an account - so it is what goes, rather than
              the label being cut or wrapped.
              Written as `md:max-[859px]:hidden` rather than `md:hidden min-[860px]:inline-flex`:
              the second form loses the cascade (Tailwind emits the arbitrary min-width rule
              BEFORE `md:hidden`, so the link stayed hidden at 860, 900 and 1024 - verified in
              the browser), while stacking the two conditions on the one `hidden` cannot. */}
          <SignInButton variant="link" baseUrl={siteBaseUrl} className="hidden sm:inline-flex md:max-[859px]:hidden text-sm whitespace-nowrap cursor-pointer">
            {labels.signIn}
          </SignInButton>
          <SignInButton
            variant="primary"
            baseUrl={siteBaseUrl}
            className="inline-flex items-center gap-1 h-9 px-3 lg:px-4 rounded-xl text-sm font-medium whitespace-nowrap transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
          >
            {labels.getStarted}
          </SignInButton>
        </div>
      </div>
    </header>
  );
}

export function LandingFooter({ siteBaseUrl, labels = DEFAULT_SHELL_LABELS }: { siteBaseUrl?: string; labels?: ShellLabels } = {}) {
  return (
    <footer style={{ borderTop: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}>
      <div className="max-w-6xl mx-auto px-6 py-14 flex flex-col gap-10 md:flex-row md:gap-12 text-sm">
        <div className="md:w-72 md:flex-shrink-0">
          <div className="flex items-center mr-1 group/logo relative">
            <div className="relative flex items-center justify-center transition-opacity duration-300">
              {/* Decorative, same reason as the header: the name is text beside it. */}
              <LogoAnimate size="md" className="text-theme-primary" decorative />
            </div>
            <span className="text-xl font-light text-theme-primary transition-colors duration-300 livecontext-title opacity-100 scale-100 w-auto">
              LiveContext
            </span>
          </div>
          <p className="mt-3 max-w-xs leading-relaxed" style={{ color: 'var(--text-muted)' }}>
            {labels.tagline}
          </p>

          <div className="mt-5 flex items-center justify-start gap-2">
            <a
              href="https://www.linkedin.com/company/livecontext/"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="LinkedIn"
              className="w-9 h-9 rounded-full flex items-center justify-center transition-colors duration-200 hover:brightness-125"
              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor"><path d="M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433a2.062 2.062 0 0 1-2.063-2.065 2.064 2.064 0 1 1 2.063 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z" /></svg>
            </a>
            <a
              href="https://x.com/livecontextai"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="X"
              className="w-9 h-9 rounded-full flex items-center justify-center transition-colors duration-200 hover:brightness-125"
              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor"><path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" /></svg>
            </a>
            <a
              href="https://www.instagram.com/livecontext.ai/"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="Instagram"
              className="w-9 h-9 rounded-full flex items-center justify-center transition-colors duration-200 hover:brightness-125"
              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect width="20" height="20" x="2" y="2" rx="5" ry="5" /><path d="M16 11.37A4 4 0 1 1 12.63 8 4 4 0 0 1 16 11.37z" /><line x1="17.5" x2="17.51" y1="6.5" y2="6.5" /></svg>
            </a>
            <a
              href="https://github.com/livecontext-ai"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="GitHub"
              className="w-9 h-9 rounded-full flex items-center justify-center transition-colors duration-200 hover:brightness-125"
              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
            >
              <GithubMark className="w-4 h-4" />
            </a>
            <a
              href="https://www.tiktok.com/@livecontextai"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="TikTok"
              className="w-9 h-9 rounded-full flex items-center justify-center transition-colors duration-200 hover:brightness-125"
              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor"><path d="M19.59 6.69a4.83 4.83 0 0 1-3.77-4.25V2h-3.45v13.67a2.89 2.89 0 0 1-2.88 2.5 2.89 2.89 0 0 1-2.89-2.89 2.89 2.89 0 0 1 2.89-2.89c.28 0 .54.04.79.1V9.01a6.27 6.27 0 0 0-.79-.05 6.34 6.34 0 0 0-6.34 6.34 6.34 6.34 0 0 0 6.34 6.34 6.34 6.34 0 0 0 6.34-6.34V8.75a8.18 8.18 0 0 0 4.76 1.52V6.84a4.84 4.84 0 0 1-1-.15z" /></svg>
            </a>
            <a
              href="https://www.youtube.com/@livecontext-ai"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="YouTube"
              className="w-9 h-9 rounded-full flex items-center justify-center transition-colors duration-200 hover:brightness-125"
              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor"><path d="M23.5 6.19a3.02 3.02 0 0 0-2.12-2.14C19.5 3.55 12 3.55 12 3.55s-7.5 0-9.38.5A3.02 3.02 0 0 0 .5 6.19C0 8.08 0 12 0 12s0 3.92.5 5.81a3.02 3.02 0 0 0 2.12 2.14c1.88.5 9.38.5 9.38.5s7.5 0 9.38-.5a3.02 3.02 0 0 0 2.12-2.14C24 15.92 24 12 24 12s0-3.92-.5-5.81zM9.55 15.57V8.43L15.82 12l-6.27 3.57z" /></svg>
            </a>
            <a
              href="https://discord.gg/5gTuUwhkJ"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="Discord"
              className="w-9 h-9 rounded-full flex items-center justify-center transition-colors duration-200 hover:brightness-125"
              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor"><path d="M20.317 4.3698a19.7913 19.7913 0 0 0-4.8851-1.5152.0741.0741 0 0 0-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 0 0-.0785-.037 19.7363 19.7363 0 0 0-4.8852 1.515.0699.0699 0 0 0-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 0 0 .0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 0 0 .0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 0 0-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 0 1-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 0 1 .0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 0 1 .0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 0 1-.0066.1276 12.2986 12.2986 0 0 1-1.873.8914.0766.0766 0 0 0-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 0 0 .0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 0 0 .0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 0 0-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z" /></svg>
            </a>
          </div>
        </div>
        {/* Seven columns since Models joined, but from xl rather than lg, and below that they
            wrap two, three or four at a time. Measured: this grid is 640px wide at a 1024px
            viewport and 768px from 1280px up, so seven columns are 64px each at 1024 and 82px
            from 1280. 64px holds "Gmail" and nothing longer: the German footer ran its Legal
            column off the right edge there and gave the whole PAGE a 63px horizontal scroll
            (French, 5px, was the same bug smaller). Four columns are 136px, which is the width
            at which every language reads normally - not a perfect fit, since
            "Datenschutzerklärung" is 146px and still hyphenates, but a hyphen inside a column
            instead of a page that scrolls sideways.
            `*:min-w-0` + `break-words hyphens-auto` is the belt to those braces: a grid column
            is min-width:auto by default, so it can never be narrower than its longest WORD, and
            one long compound in a future translation would push the document again. The column
            may now be narrower than the word, and the word hyphenates instead. `*:` rather than
            `[&>div]:` so it holds for any element a column is ever made of. Hyphen positions
            follow <html lang>, which this app sets to the page's locale on the client; the
            server ships `lang="en"`, so the first paint hyphenates by English rules. */}
        <div className="flex-1 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-7 gap-8 gap-y-10 break-words hyphens-auto *:min-w-0">
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>{labels.product}</p>
          {/* The capability entries point at the docs page that explains each one:
              a visitor who is not signed in yet has something to read, and the
              column stops being two sign-in prompts. Titles match the docs nav
              (`app/docs/_nav.ts`) so the label and the destination agree. */}
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={docsHref(siteBaseUrl, 'workflows')} prefetch={false}>{labels.workflows}</Link></li>
            <li><Link href={docsHref(siteBaseUrl, 'agents')} prefetch={false}>{labels.agents}</Link></li>
            <li><Link href={docsHref(siteBaseUrl, 'interfaces')} prefetch={false}>{labels.interfaces}</Link></li>
            <li><Link href={docsHref(siteBaseUrl, 'tables')} prefetch={false}>{labels.tables}</Link></li>
            <li><Link href={docsHref(siteBaseUrl, 'integrations')} prefetch={false}>{labels.integrations}</Link></li>
            {/* The PUBLIC marketplace, not a sign-in prompt: there is a
                crawlable page behind this word now, and a footer link from
                every public page is one of the cheapest ways to keep the whole
                listing tree reachable. */}
            <li><Link href={withBase(siteBaseUrl, '/marketplace')}>{labels.marketplace}</Link></li>
            <li>
              <SignInButton variant="link" returnTo="/app/settings/pricing" baseUrl={siteBaseUrl} className="cursor-pointer">
                {labels.pricing}
              </SignInButton>
            </li>
          </ul>
        </div>
        {/* Ranked by the node-usage ledger, not curated: see FooterIntegrations.
            Each name is a crawlable page, so the footer puts every public page one
            click from an integration page and vice versa. */}
        <FooterIntegrations siteBaseUrl={siteBaseUrl} heading={labels.integrations} allLabel={labels.allIntegrations} />
        {/* The families the platform runs on. Named rather than counted: "275 models" tells
            a visitor nothing, "Claude, GPT, Gemini, DeepSeek" answers the question they came
            with. Each is checked against the catalogue seed by wellKnownModels.test.ts.
            The destination is /models, not the docs: the column pointed at the docs because
            no public page listed the models, which stopped being true when /models shipped.
            A visitor clicking "Claude" wants the list and its prices, not the BYOK setup
            guide, and /models links on to the docs for the setup half. Each family carries
            its OWN provider filter, so the families are distinct destinations rather than one URL
            printed several times, and "DeepSeek" lands on DeepSeek. */}
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>{labels.models}</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            {WELL_KNOWN_MODELS.map((model) => (
              <li key={model.provider}>
                <Link href={withBase(siteBaseUrl, providerHref(model.provider))}>{model.label}</Link>
              </li>
            ))}
          </ul>
        </div>
        {/* Everything the header nav links to, mirrored here (sim.ai-style
            Resources column) so the footer is a full site map on its own. It is a
            superset, not a copy: /models and /status live here only. A sixth header
            item overflows the bar by 44px at 768px, the width where `md:flex` first
            shows the nav, so /models is reached from the Models column beside this
            one, from the sitemap, and not from the header. */}
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>{labels.resources}</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={withBase(siteBaseUrl, '/videos')}>{labels.videos}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/changelog')}>Changelog</Link></li>
            {/* Cloud only: /status reports the LiveContext cloud and 404s in a
                self-hosted build, so linking it there would be a dead entry. */}
            {!IS_CE && <li><Link href={withBase(siteBaseUrl, '/status')}>{labels.status}</Link></li>}
            <li><Link href={docsHref(siteBaseUrl)} prefetch={false}>{labels.docs}</Link></li>
            <li>
              <a href="https://github.com/livecontext-ai" target="_blank" rel="noopener noreferrer">
                {labels.selfHosted}
              </a>
            </li>
          </ul>
        </div>
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>{labels.compare}</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={withBase(siteBaseUrl, '/compare/zapier-alternative')}>{labels.alternativeTo('Zapier')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/n8n-alternative')}>{labels.alternativeTo('n8n')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/make-alternative')}>{labels.alternativeTo('Make')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/openclaw-alternative')}>{labels.alternativeTo('OpenClaw')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/hermes-agent-alternative')}>{labels.alternativeTo('Hermes Agent')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/muse-alternative')}>{labels.alternativeTo('Muse')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/grok-bot-alternative')}>{labels.alternativeTo('Grok Bot')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/agent-zero-alternative')}>{labels.alternativeTo('Agent Zero')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/autogpt-alternative')}>{labels.alternativeTo('AutoGPT')}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/manus-alternative')}>{labels.alternativeTo('Manus')}</Link></li>
          </ul>
        </div>
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>{labels.company}</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={withBase(siteBaseUrl, '/about')}>{labels.about}</Link></li>
            <li>
              <span className="inline-flex items-center gap-1.5" style={{ color: 'var(--text-muted)' }}>
                {labels.careers}
                <span
                  className="inline-flex items-center rounded-full px-1.5 py-px text-[10px] font-medium uppercase tracking-wide"
                  style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)', border: '1px solid var(--border-color)' }}
                >
                  {labels.soon}
                </span>
              </span>
            </li>
            <li><Link href={withBase(siteBaseUrl, '/contact')}>{labels.contact}</Link></li>
            {/* No postal address here: a raw street line among nav links read as
                a stray entry. The registered office stays where it is legally
                required, on the Legal Notice / Terms / Privacy pages. */}
          </ul>
        </div>
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>{labels.legal}</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={withBase(siteBaseUrl, '/legal/privacy')}>{labels.privacy}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/legal/terms')}>{labels.terms}</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/legal/mentions')}>{labels.notice}</Link></li>
          </ul>
        </div>
        </div>
      </div>
      {/* The six persona pages, linked from EVERY public page. Until this line their only
          internal links were the hero pills, which exist on the home page and on each
          other, so nothing on /integrations, /models, /compare, /marketplace or the docs
          pointed at them at all. They sit on their own row rather than in an eighth
          column: the seven above are already 82px wide at a 1440 viewport, and a column
          narrower than its own words is not a link anyone follows. */}
      <nav aria-label={labels.personasHeading} className="max-w-6xl mx-auto px-6 pb-6 text-sm">
        <div className="flex flex-wrap items-center gap-x-5 gap-y-2 pt-6" style={{ borderTop: '1px solid var(--border-color)' }}>
          <span className="text-[11px] uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>{labels.personasHeading}</span>
          {PERSONA_KEYS.map((persona) => (
            <Link key={persona} href={withBase(siteBaseUrl, personaHref(persona, labels.locale))} style={{ color: 'var(--text-secondary)' }} className="hover:opacity-80 transition-opacity">
              {labels.personas[persona]}
            </Link>
          ))}
        </div>
      </nav>
      <div className="max-w-6xl mx-auto px-6 pb-8 text-xs flex items-center justify-between gap-4" style={{ color: 'var(--text-muted)' }}>
        <p>© {new Date().getFullYear()} LIVECONTEXT SAS. {labels.rights}</p>
        <div className="flex items-center gap-2">
          <LandingLanguageSelect label={labels.language} />
          <LandingThemeToggle toLight={labels.toLightTheme} toDark={labels.toDarkTheme} />
        </div>
      </div>
    </footer>
  );
}

interface LandingShellProps {
  children: React.ReactNode;
  /** Extra CSS appended after the base chrome styles. Used by the landing page
   *  to inject hero/feature/comparison selectors without forking the chrome. */
  extraStyles?: string;
  /** Optional element rendered in the header's right cluster (e.g. the docs theme toggle). */
  headerExtra?: React.ReactNode;
  /** Theme persistence for this surface. The public site defaults to light on
   *  first visit and restores the visitor's footer-toggle choice afterwards. */
  themeStorageKey?: string;
  themeRespectStored?: boolean;
  /** Prefix for the chrome's in-app links, so the header/footer target the main
   *  site when this shell renders on a sub-host (the docs subdomain). Undefined
   *  elsewhere → links stay relative. */
  siteBaseUrl?: string;
  /** Rendered as a "Skip to content" link BEFORE the header (the first Tab stop),
   *  pointing at this element id. Styled here, in the shared chrome CSS (`.landing-skip-link`). */
  skipLinkTarget?: string;
}

export function LandingShell({ children, extraStyles, headerExtra, themeStorageKey, themeRespectStored = true, siteBaseUrl, skipLinkTarget }: LandingShellProps) {
  return (
    <LandingThemeProvider
      className="min-h-screen flex flex-col"
      storageKey={themeStorageKey}
      respectStored={themeRespectStored}
    >
      {/* ONE string child: React 19 only renders <style> content when it is a
          single string. Two expression children ({a}{b}) render an EMPTY style
          tag server-side and the full text client-side - a hydration mismatch
          (React #418) plus unstyled server HTML. */}
      <style>{landingChromeStyles + (extraStyles ?? '')}</style>
      {skipLinkTarget ? (
        <a href={`#${skipLinkTarget}`} className="landing-skip-link">
          Skip to content
        </a>
      ) : null}
      <LandingHeader extra={headerExtra} siteBaseUrl={siteBaseUrl} />
      <main className="flex-1">{children}</main>
      <LandingFooter siteBaseUrl={siteBaseUrl} />
    </LandingThemeProvider>
  );
}
