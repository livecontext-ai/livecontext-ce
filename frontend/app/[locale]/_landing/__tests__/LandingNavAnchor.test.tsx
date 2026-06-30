// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

import LandingNavAnchor from '../LandingNavAnchor';

// This component is rendered by the shared LandingShell header on the
// non-localized root pages (`/about`, `/changelog`, `/docs`, `/legal/*`) which
// have NO NextIntlClientProvider. Before the fix it called next-intl's
// `useRouter()` → `useLocale()` → `useIntlContext()`, which throws
// "No intl context found" during client render in production → error boundary
// ("Something went wrong"). The fix drops the intl-context dependency entirely.
// This suite renders the component with NO provider and NO next-intl mock; the
// static-import guard (below) is the deterministic contract that the dependency
// is gone (pre-fix, the component can't even mount here - it pulls in
// `@/i18n/navigation` → next-intl, which fails to resolve under vitest).
afterEach(cleanup);

describe('LandingNavAnchor', () => {
  it('renders without a NextIntlClientProvider (regression: no "No intl context found" throw)', () => {
    expect(() =>
      render(<LandingNavAnchor targetId="pricing">Pricing</LandingNavAnchor>),
    ).not.toThrow();
    expect(screen.getByText('Pricing')).toBeInTheDocument();
  });

  it('does not depend on any intl context (no next-intl / i18n navigation import)', () => {
    const src = readFileSync(path.resolve(__dirname, '../LandingNavAnchor.tsx'), 'utf8');
    expect(src).not.toMatch(/@\/i18n\/navigation/);
    expect(src).not.toMatch(/from ['"]next-intl/);
  });

  it('exposes a native anchor to /#<targetId> for the cross-page fallback', () => {
    render(<LandingNavAnchor targetId="marketplace">Marketplace</LandingNavAnchor>);
    expect(screen.getByText('Marketplace').closest('a')).toHaveAttribute('href', '/#marketplace');
  });

  it('smooth-scrolls and updates the hash when the section exists on the current page', () => {
    const section = document.createElement('div');
    section.id = 'pricing';
    const scrollIntoView = vi.fn();
    section.scrollIntoView = scrollIntoView as unknown as Element['scrollIntoView'];
    document.body.appendChild(section);
    const replaceState = vi.spyOn(window.history, 'replaceState');

    render(<LandingNavAnchor targetId="pricing">Pricing</LandingNavAnchor>);
    fireEvent.click(screen.getByText('Pricing').closest('a')!);

    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(replaceState).toHaveBeenCalledWith(null, '', '#pricing');

    replaceState.mockRestore();
    document.body.removeChild(section);
  });

  it('does not preventDefault when the section is absent (lets the native anchor navigate to home)', () => {
    render(<LandingNavAnchor targetId="absent-section">Docs</LandingNavAnchor>);
    // dispatchEvent returns false when preventDefault was called, true otherwise.
    const notPrevented = fireEvent.click(screen.getByText('Docs').closest('a')!);
    expect(notPrevented).toBe(true);
  });
});
