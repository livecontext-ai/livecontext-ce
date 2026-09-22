// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { renderToString } from 'react-dom/server';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

// LandingLanguageSelect lives in the shared LandingFooter, which also renders on the
// non-localized public pages (no NextIntlClientProvider) - it must stay
// intl-context-free and use plain next/navigation. Mocked here so the test drives
// the current pathname and observes navigation.
const h = vi.hoisted(() => ({ push: vi.fn(), assign: vi.fn(), pathname: '/', search: '', hash: '' }));
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
    h.assign.mockClear();
    h.pathname = '/';
    h.search = '';
    h.hash = '';
    const browserWindow = window;
    vi.stubGlobal('window', new Proxy(browserWindow, {
      get(target, property) {
        return property === 'location'
          ? { assign: h.assign, search: h.search, hash: h.hash }
          : Reflect.get(target, property, target);
      },
    }));
    clearNextLocaleCookie();
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it('is named by the label it is handed, not by its own English', () => {
    // The receiving end, which every other check here left open: the component could accept
    // `label` and keep rendering aria-label="Language", and the bottom bar of /fr would be
    // English again with the whole suite green. The option NAMES stay native on purpose, they
    // are the languages themselves.
    render(<LandingLanguageSelect label="LANGUE" />);
    expect(screen.getByRole('combobox', { name: 'LANGUE' })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Language' })).toBeNull();
  });

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

  it('switches a bare persona landing to its translated sibling', () => {
    h.pathname = '/for/creator';
    document.cookie = 'NEXT_LOCALE=fr; path=/';
    render(<LandingLanguageSelect />);
    expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('en');
    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'fr' } });
    expect(h.assign).toHaveBeenCalledWith('/fr/for/creator');
    expect(document.cookie).toContain('NEXT_LOCALE=fr');
    expect(h.push).not.toHaveBeenCalled();
  });

  it('keeps the persona when changing between translated pages', () => {
    h.pathname = '/fr/for/sales';
    render(<LandingLanguageSelect />);
    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'de' } });
    expect(h.assign).toHaveBeenCalledWith('/de/for/sales');
    expect(h.push).not.toHaveBeenCalled();
  });

  it.each(['/for/creator', '/fr/for/creator'])('disables %s language selection until its handler is hydrated', (pathname) => {
    h.pathname = pathname;
    const serverMarkup = document.createElement('div');
    serverMarkup.innerHTML = renderToString(<LandingLanguageSelect />);
    expect(serverMarkup.querySelector('select')).toHaveAttribute('disabled');
    render(<LandingLanguageSelect />);
    expect(screen.getByRole('combobox', { name: 'Language' })).toBeEnabled();
  });

  it('loads the canonical English persona document while preserving query and fragment', () => {
    h.pathname = '/fr/for/creator';
    h.search = '?campaign=launch';
    h.hash = '#persona-workflow';
    render(<LandingLanguageSelect />);
    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'en' } });
    expect(h.assign).toHaveBeenCalledWith('/for/creator?campaign=launch#persona-workflow');
    expect(document.cookie).toContain('NEXT_LOCALE=en');
    expect(h.push).not.toHaveBeenCalled();
  });

  it('does not reload a persona when its current language is selected', () => {
    h.pathname = '/fr/for/support';
    render(<LandingLanguageSelect />);
    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'fr' } });
    expect(h.assign).not.toHaveBeenCalled();
    expect(h.push).not.toHaveBeenCalled();
  });

  it('keeps client routing for localized paths that only resemble persona routes', () => {
    h.pathname = '/fr/foreign/creator';
    render(<LandingLanguageSelect />);
    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'de' } });
    expect(h.assign).not.toHaveBeenCalled();
    expect(h.push).toHaveBeenCalledWith('/de/foreign/creator');
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
