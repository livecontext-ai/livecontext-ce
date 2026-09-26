// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';

// The shell's heavy children are irrelevant here: stub them so the test renders the
// real LandingShell markup order (skip link, header, main, footer).
vi.mock('next/link', () => {
  const React = require('react');
  return { default: ({ href, children, ...rest }: { href: unknown; children: React.ReactNode }) => React.createElement('a', { href: typeof href === 'string' ? href : '#', ...rest }, children) };
});
vi.mock('next/navigation', () => ({ usePathname: () => '/', useRouter: () => ({ push: vi.fn() }), useSearchParams: () => new URLSearchParams() }));
vi.mock('@/components/LogoAnimate', () => ({ default: () => null }));
vi.mock('@/app/[locale]/_landing/SignInButton', () => ({ default: () => null }));
vi.mock('@/app/[locale]/_landing/LandingNavAnchor', () => ({ default: () => null }));
vi.mock('@/components/landing/LandingLanguageSelect', () => ({ default: () => null }));
vi.mock('@/components/landing/LandingThemeToggle', () => ({ default: () => null }));
vi.mock('@/components/landing/FooterIntegrations', () => ({ default: () => null }));

import { readFileSync } from 'fs';
import path from 'path';
import { LandingShell, landingChromeStyles } from '@/components/landing/LandingShell';
import { DOCS_ARTICLE_ID } from '../_components/docsIds';

afterEach(cleanup);

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

describe('LandingShell skip link', () => {
  it('renders "Skip to content" as the very first focusable element when a target is given', () => {
    const { container } = render(
      <LandingShell skipLinkTarget="docs-article">
        <div id="docs-article" tabIndex={-1}>Article</div>
      </LandingShell>,
    );
    const first = container.querySelector<HTMLAnchorElement>(FOCUSABLE);
    expect(first).toHaveTextContent('Skip to content');
    expect(first).toHaveAttribute('href', '#docs-article');
    // It sits before the site header, so it is the first Tab stop of the page.
    const header = container.querySelector('header');
    expect(header).not.toBeNull();
    expect(first!.compareDocumentPosition(header!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('is wired to the article the docs layout renders, and the mobile menu targets the same element', () => {
    const read = (rel: string) => readFileSync(path.resolve(__dirname, '..', rel), 'utf8');
    const layout = read('layout.tsx');
    expect(layout).toMatch(/skipLinkTarget=\{DOCS_ARTICLE_ID\}/);
    expect(layout).toMatch(/id=\{DOCS_ARTICLE_ID\}/);
    expect(read('_components/DocsMobileNav.tsx')).toMatch(/getElementById\(DOCS_ARTICLE_ID\)/);
    expect(DOCS_ARTICLE_ID).toBe('docs-article');
  });

  it('styles the shared skip link in the chrome CSS, so it never depends on a surface stylesheet', () => {
    expect(landingChromeStyles).toMatch(/\.landing-root \.landing-skip-link \{[\s\S]*?top: -4rem;/);
    expect(landingChromeStyles).toMatch(/\.landing-root \.landing-skip-link:focus \{[\s\S]*?top: 0\.75rem;/);
  });

  it('renders no skip link on surfaces that do not ask for one (the marketing pages)', () => {
    const { container } = render(<LandingShell><p>Landing</p></LandingShell>);
    expect(container.querySelector('.landing-skip-link')).toBeNull();
  });
});
