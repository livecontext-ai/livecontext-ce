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
// LandingThemeProvider (default light), decoupled from the app theme. It lives in
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

describe('the labels it is handed', () => {
  // The call site is pinned elsewhere; this is the other half. Without it the component can
  // accept `toLight`/`toDark` and keep rendering its English defaults, which is what the
  // bottom bar of /fr did before this prop existed and what no test would have noticed.
  it('uses them instead of its own English, for both the name and the tooltip', () => {
    render(
      <LandingThemeProvider>
        <LandingThemeToggle toLight="ECLAIRCIR" toDark="ASSOMBRIR" />
      </LandingThemeProvider>,
    );
    // Light is the public default, so the button offers the dark theme.
    const button = screen.getByRole('button', { name: 'ASSOMBRIR' });
    expect(button).toHaveAttribute('title', 'ASSOMBRIR');
    expect(screen.queryByRole('button', { name: /Switch to/ })).toBeNull();

    fireEvent.click(button);
    const flipped = screen.getByRole('button', { name: 'ECLAIRCIR' });
    expect(flipped).toHaveAttribute('title', 'ECLAIRCIR');
  });

  it('still says its English when nobody hands it anything', () => {
    // /docs, /legal and /marketplace reach it through DEFAULT_SHELL_LABELS, so the defaults
    // have to stay correct as well as unused-by-the-localised-pages.
    render(
      <LandingThemeProvider>
        <LandingThemeToggle />
      </LandingThemeProvider>,
    );
    expect(screen.getByRole('button', { name: 'Switch to dark theme' })).toBeInTheDocument();
  });
});

function renderInProvider() {
  return render(
    <LandingThemeProvider>
      <LandingThemeToggle />
    </LandingThemeProvider>,
  );
}

describe('LandingThemeToggle', () => {
  it('defaults to light → shows the Moon glyph and offers to switch to dark', () => {
    const { container } = renderInProvider();
    expect(screen.getByRole('button', { name: /switch to dark theme/i })).toBeInTheDocument();
    expect(container.querySelector('svg')?.getAttribute('class')).toMatch(/moon/i);
  });

  it('click flips to dark → Sun glyph, offers to switch to light, persists `landing-theme`', () => {
    const { container } = renderInProvider();
    fireEvent.click(screen.getByRole('button', { name: /switch to dark theme/i }));

    expect(screen.getByRole('button', { name: /switch to light theme/i })).toBeInTheDocument();
    expect(container.querySelector('svg')?.getAttribute('class')).toMatch(/sun/i);
    expect(localStorage.getItem('landing-theme')).toBe('dark');
  });

  it('without a provider, falls back to the light default and does not throw', () => {
    expect(() => render(<LandingThemeToggle />)).not.toThrow();
    expect(screen.getByRole('button', { name: /switch to dark theme/i })).toBeInTheDocument();
  });

  it('does not import any intl context (shared footer has no NextIntlClientProvider)', () => {
    const src = readFileSync(path.resolve(__dirname, '../LandingThemeToggle.tsx'), 'utf8');
    expect(src).not.toMatch(/from ['"]@\/i18n\/navigation['"]/);
    expect(src).not.toMatch(/from ['"]next-intl['"]/);
  });
});
