import Link from 'next/link';
import LogoAnimate from '@/components/LogoAnimate';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import LandingNavAnchor from '@/app/[locale]/_landing/LandingNavAnchor';
import LandingLanguageSelect from '@/components/landing/LandingLanguageSelect';
import LandingThemeProvider from '@/components/landing/LandingThemeProvider';
import LandingThemeToggle from '@/components/landing/LandingThemeToggle';

// Shared chrome (header + footer + base CSS vars) used by the landing page
// (`app/[locale]/page.tsx`) and the public sub-pages (`/about`, `/contact`,
// `/legal/*`, `/changelog`, `/docs`). Header anchors target `/#marketplace`
// and `/#pricing` so they scroll on the landing page AND navigate-then-scroll
// from any other public page.
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

  /* Mono-dark brand logos (github/openai/anthropic…) flip to white only when the
     LANDING theme is dark - keyed off the landing root, not the <body> .dark, so
     the public theme stays decoupled from the app theme. */
  .landing-root.dark .logo-mono {
    filter: brightness(0) invert(1);
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

export function LandingHeader({ extra, siteBaseUrl }: { extra?: React.ReactNode; siteBaseUrl?: string } = {}) {
  return (
    <header className="sticky top-0 z-50 backdrop-blur" style={{ background: 'var(--landing-header-bg)', borderBottom: '1px solid var(--border-color)' }}>
      <div className="max-w-6xl mx-auto px-6 h-20 flex items-center justify-between">
        <Link href={withBase(siteBaseUrl, '/')} className="flex items-center mr-1 group/logo relative cursor-pointer">
          <div className="relative flex items-center justify-center transition-opacity duration-300">
            <LogoAnimate size="md" className="text-theme-primary" />
          </div>
          <span className="text-xl font-light text-theme-primary transition-colors duration-300 livecontext-title opacity-100 scale-100 w-auto">
            LiveContext
          </span>
        </Link>
        <nav className="hidden md:flex items-center gap-8 text-sm" style={{ color: 'var(--text-secondary)' }}>
          <LandingNavAnchor targetId="marketplace" baseUrl={siteBaseUrl} className="hover:opacity-80 transition-opacity cursor-pointer">Marketplace</LandingNavAnchor>
          <LandingNavAnchor targetId="pricing" baseUrl={siteBaseUrl} className="hover:opacity-80 transition-opacity cursor-pointer">Pricing</LandingNavAnchor>
          <Link href={withBase(siteBaseUrl, '/blog')} className="hover:opacity-80 transition-opacity">Blog</Link>
          <Link href={withBase(siteBaseUrl, '/changelog')} className="hover:opacity-80 transition-opacity">Changelog</Link>
          <Link href={siteBaseUrl ? '/' : '/docs'} prefetch={false} className="hover:opacity-80 transition-opacity">Docs</Link>
          <a
            href="https://github.com/livecontext-ai"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="Self-hosted (GitHub)"
            className="inline-flex items-center gap-1.5 hover:opacity-80 transition-opacity"
          >
            <GithubMark className="w-4 h-4" />
            Self-hosted
          </a>
        </nav>
        <div className="flex items-center gap-3">
          {extra}
          <SignInButton variant="link" baseUrl={siteBaseUrl} className="hidden sm:inline-flex text-sm cursor-pointer">
            Sign in
          </SignInButton>
          <SignInButton
            variant="primary"
            baseUrl={siteBaseUrl}
            className="inline-flex items-center gap-1 h-9 px-4 rounded-xl text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
          >
            Get started free
          </SignInButton>
        </div>
      </div>
    </header>
  );
}

export function LandingFooter({ siteBaseUrl }: { siteBaseUrl?: string } = {}) {
  return (
    <footer style={{ borderTop: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}>
      <div className="max-w-6xl mx-auto px-6 py-14 flex flex-col gap-10 md:flex-row md:gap-12 text-sm">
        <div className="md:w-72 md:flex-shrink-0">
          <div className="flex items-center mr-1 group/logo relative">
            <div className="relative flex items-center justify-center transition-opacity duration-300">
              <LogoAnimate size="md" className="text-theme-primary" />
            </div>
            <span className="text-xl font-light text-theme-primary transition-colors duration-300 livecontext-title opacity-100 scale-100 w-auto">
              LiveContext
            </span>
          </div>
          <p className="mt-3 max-w-xs leading-relaxed" style={{ color: 'var(--text-muted)' }}>
            The AI automation platform. Describe a job, watch the workflow build itself, and ship it as
            an app your team can use. Cloud or self-hosted.
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
        <div className="flex-1 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-8 gap-y-10">
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>Product</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li>
              <SignInButton variant="link" returnTo="/app/marketplace" baseUrl={siteBaseUrl} className="cursor-pointer">
                Marketplace
              </SignInButton>
            </li>
            <li>
              <SignInButton variant="link" returnTo="/app/settings/pricing" baseUrl={siteBaseUrl} className="cursor-pointer">
                Pricing
              </SignInButton>
            </li>
          </ul>
        </div>
        {/* Everything the header nav links to, mirrored here (sim.ai-style
            Resources column) so the footer is a full site map on its own. */}
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>Resources</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={withBase(siteBaseUrl, '/blog')}>Blog</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/changelog')}>Changelog</Link></li>
            <li><Link href={siteBaseUrl ? '/' : '/docs'} prefetch={false}>Docs</Link></li>
            <li>
              <a href="https://github.com/livecontext-ai" target="_blank" rel="noopener noreferrer">
                Self-hosted
              </a>
            </li>
          </ul>
        </div>
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>Compare</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={withBase(siteBaseUrl, '/compare/zapier-alternative')}>Zapier alternative</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/n8n-alternative')}>n8n alternative</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/compare/make-alternative')}>Make alternative</Link></li>
          </ul>
        </div>
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>Company</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={withBase(siteBaseUrl, '/about')}>About</Link></li>
            <li>
              <span className="inline-flex items-center gap-1.5" style={{ color: 'var(--text-muted)' }}>
                Careers
                <span
                  className="inline-flex items-center rounded-full px-1.5 py-px text-[10px] font-medium uppercase tracking-wide"
                  style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)', border: '1px solid var(--border-color)' }}
                >
                  Soon
                </span>
              </span>
            </li>
            <li><Link href={withBase(siteBaseUrl, '/contact')}>Contact</Link></li>
            {/* No postal address here: a raw street line among nav links read as
                a stray entry. The registered office stays where it is legally
                required, on the Legal Notice / Terms / Privacy pages. */}
          </ul>
        </div>
        <div>
          <p className="text-[11px] uppercase tracking-wider mb-3" style={{ color: 'var(--text-muted)' }}>Legal</p>
          <ul className="space-y-2" style={{ color: 'var(--text-secondary)' }}>
            <li><Link href={withBase(siteBaseUrl, '/legal/privacy')}>Privacy Policy</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/legal/terms')}>Terms of Service</Link></li>
            <li><Link href={withBase(siteBaseUrl, '/legal/mentions')}>Legal Notice</Link></li>
          </ul>
        </div>
        </div>
      </div>
      <div className="max-w-6xl mx-auto px-6 pb-8 text-xs flex items-center justify-between gap-4" style={{ color: 'var(--text-muted)' }}>
        <p>© {new Date().getFullYear()} LIVECONTEXT SAS. All rights reserved.</p>
        <div className="flex items-center gap-2">
          <LandingLanguageSelect />
          <LandingThemeToggle />
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
}

export function LandingShell({ children, extraStyles, headerExtra, themeStorageKey, themeRespectStored = true, siteBaseUrl }: LandingShellProps) {
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
      <LandingHeader extra={headerExtra} siteBaseUrl={siteBaseUrl} />
      <main className="flex-1">{children}</main>
      <LandingFooter siteBaseUrl={siteBaseUrl} />
    </LandingThemeProvider>
  );
}
