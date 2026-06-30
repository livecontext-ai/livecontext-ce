// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

// LandingLanguageSelect lives in the shared LandingFooter, which also renders on the
// non-localized public pages (no NextIntlClientProvider) - it must stay
// intl-context-free and use plain next/navigation. Mocked here so the test drives
// the current pathname and observes navigation.
const h = vi.hoisted(() => ({ push: vi.fn(), pathname: '/' }));
vi.mock('next/navigation', () => ({
  usePathname: () => h.pathname,
  useRouter: () => ({ push: h.push }),
}));

import LandingLanguageSelect from '../LandingLanguageSelect';

function clearNextLocaleCookie() {
  document.cookie = 'NEXT_LOCALE=; path=/; max-age=0';
}

describe('LandingLanguageSelect', () => {
  beforeEach(() => {
    h.push.mockClear();
    h.pathname = '/';
    clearNextLocaleCookie();
  });
  afterEach(cleanup);

  it('offers all supported locales with their native names', () => {
    render(<LandingLanguageSelect />);
    const select = screen.getByRole('combobox', { name: 'Language' });
    const labels = Array.from(select.querySelectorAll('option')).map((o) => o.textContent);
    expect(labels).toEqual(['English', 'Français', 'Español', 'Deutsch', 'Português', '中文']);
  });

  it('reflects the locale of the current path', () => {
    h.pathname = '/fr';
    render(<LandingLanguageSelect />);
    expect((screen.getByRole('combobox', { name: 'Language' }) as HTMLSelectElement).value).toBe('fr');
  });

  it('switches language: persists NEXT_LOCALE and navigates to the same path in the new locale', () => {
    h.pathname = '/fr';
    render(<LandingLanguageSelect />);

    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'de' } });

    expect(document.cookie).toContain('NEXT_LOCALE=de');
    expect(h.push).toHaveBeenCalledWith('/de');
  });

  it('keeps the rest of the path when switching from a localized sub-path', () => {
    h.pathname = '/en/app/marketplace';
    render(<LandingLanguageSelect />);

    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'es' } });

    expect(h.push).toHaveBeenCalledWith('/es/app/marketplace');
  });

  it('regression: on the default-locale landing at `/` (localePrefix as-needed), switching DOES navigate', () => {
    h.pathname = '/';
    render(<LandingLanguageSelect />);

    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'fr' } });

    expect(document.cookie).toContain('NEXT_LOCALE=fr');
    expect(h.push).toHaveBeenCalledWith('/fr');
  });

  it('on `/` the select shows the default locale (en) even when the cookie says otherwise', async () => {
    document.cookie = 'NEXT_LOCALE=fr; path=/';
    h.pathname = '/';
    render(<LandingLanguageSelect />);

    // The cookie-read effect must not override the path-derived locale on `/`.
    expect((screen.getByRole('combobox', { name: 'Language' }) as HTMLSelectElement).value).toBe('en');
  });

  it('on a non-localized public page the select reflects the NEXT_LOCALE cookie', async () => {
    document.cookie = 'NEXT_LOCALE=zh; path=/';
    h.pathname = '/about';
    render(<LandingLanguageSelect />);

    expect(await screen.findByDisplayValue('中文')).toBeInTheDocument();
  });

  it('on a non-localized public page: sets the cookie but does NOT navigate (single English version)', () => {
    h.pathname = '/about';
    render(<LandingLanguageSelect />);

    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'pt' } });

    expect(document.cookie).toContain('NEXT_LOCALE=pt');
    expect(h.push).not.toHaveBeenCalled();
  });

  it('does not import any intl context (shared footer has no NextIntlClientProvider)', () => {
    const src = readFileSync(path.resolve(__dirname, '../LandingLanguageSelect.tsx'), 'utf8');
    expect(src).not.toMatch(/from ['"]@\/i18n\/navigation['"]/);
    expect(src).not.toMatch(/from ['"]next-intl['"]/);
  });
});
