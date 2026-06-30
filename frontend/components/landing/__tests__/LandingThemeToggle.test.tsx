// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

import LandingThemeProvider from '../LandingThemeProvider';
import LandingThemeToggle from '../LandingThemeToggle';

// LandingThemeToggle reads the SELF-CONTAINED public-site theme from
// LandingThemeProvider (default dark), decoupled from the app theme. It lives in
// the shared LandingFooter, which also renders on the non-localized public pages
// (no NextIntlClientProvider), so it must stay intl-context-free.
beforeEach(() => {
  try {
    localStorage.clear();
  } catch {
    /* noop */
  }
});
afterEach(cleanup);

function renderInProvider() {
  return render(
    <LandingThemeProvider>
      <LandingThemeToggle />
    </LandingThemeProvider>,
  );
}

describe('LandingThemeToggle', () => {
  it('defaults to dark → shows the Sun glyph and offers to switch to light', () => {
    const { container } = renderInProvider();
    expect(screen.getByRole('button', { name: /switch to light theme/i })).toBeInTheDocument();
    expect(container.querySelector('svg')?.getAttribute('class')).toMatch(/sun/i);
  });

  it('click flips to light → Moon glyph, offers to switch to dark, persists `landing-theme`', () => {
    const { container } = renderInProvider();
    fireEvent.click(screen.getByRole('button', { name: /switch to light theme/i }));

    expect(screen.getByRole('button', { name: /switch to dark theme/i })).toBeInTheDocument();
    expect(container.querySelector('svg')?.getAttribute('class')).toMatch(/moon/i);
    expect(localStorage.getItem('landing-theme')).toBe('light');
  });

  it('without a provider, falls back to the dark default and does not throw', () => {
    expect(() => render(<LandingThemeToggle />)).not.toThrow();
    expect(screen.getByRole('button', { name: /switch to light theme/i })).toBeInTheDocument();
  });

  it('does not import any intl context (shared footer has no NextIntlClientProvider)', () => {
    const src = readFileSync(path.resolve(__dirname, '../LandingThemeToggle.tsx'), 'utf8');
    expect(src).not.toMatch(/from ['"]@\/i18n\/navigation['"]/);
    expect(src).not.toMatch(/from ['"]next-intl['"]/);
  });
});
