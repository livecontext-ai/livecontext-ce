import type { ReactNode } from 'react';
import type { Metadata } from 'next';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from './_components/docsStyles';
import { DocsNav } from './_components/DocsNav';
import { DocsMobileNav } from './_components/DocsMobileNav';
import { DocsToc } from './_components/DocsToc';
import { DocsPrevNext } from './_components/DocsPrevNext';
import { IS_CE } from '@/lib/edition';
import { DOCS_ARTICLE_ID } from './_components/docsIds';

// Docs shell. Reuses the public `LandingShell` chrome (header, footer, light-by-
// default decoupled theme persisted under `docs-theme`, so flipping it here never
// changes the marketing pages) and injects the docs CSS via `extraStyles`, then
// lays out a sidebar / content / TOC grid inside it. The theme is switched from
// the footer toggle the whole public site already carries - the docs header adds
// no control of its own. English-only: this whole tree lives outside
// `app/[locale]`, so nothing here may call next-intl hooks.
export const metadata: Metadata = {
  title: {
    template: '%s · LiveContext Docs',
    default: 'Documentation · LiveContext',
  },
  description: 'Guides and reference for LiveContext - the AI automation platform.',
};

export default function DocsLayout({ children }: { children: ReactNode }) {
  // On the cloud the docs render on the docs.* subdomain, so the shared chrome's
  // links (privacy, about, marketplace, sign-in, ...) must point at the main site
  // (livecontext.ai). On CE there is no subdomain → relative links (undefined).
  const siteBaseUrl = IS_CE ? undefined : (process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai');
  return (
    <LandingShell
      extraStyles={docsStyles}
      themeStorageKey="docs-theme"
      themeRespectStored
      siteBaseUrl={siteBaseUrl}
      // First Tab stop of every docs page: jumps past the site header and the
      // sidebar straight to the article (WCAG 2.4.1 bypass blocks).
      skipLinkTarget={DOCS_ARTICLE_ID}
    >
      <div className="docs-layout">
        <aside className="docs-sidebar" aria-label="Documentation sidebar">
          <DocsNav />
        </aside>
        <div className="docs-content">
          <DocsMobileNav />
          {/* Skip-link and post-navigation focus target: the article itself, after the
              mobile Menu button, so the next Tab lands in the content. */}
          <div className="docs-article" id={DOCS_ARTICLE_ID} tabIndex={-1}>
            {children}
          </div>
          <DocsPrevNext />
        </div>
        {/* Plain column wrapper: the landmark is DocsToc's own <nav>, which renders
            only when the page has at least two headings (never an empty landmark). */}
        <div className="docs-toc">
          <DocsToc />
        </div>
      </div>
    </LandingShell>
  );
}
