import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import { afterEach, describe, expect, it } from 'vitest';
import { PERSONA_KEYS, personaHref } from '@/components/landing/personas/personas';
import { locales } from '@/i18n/routing';

const html = readFileSync(new URL('../../../../public/hero-flow.html', import.meta.url), 'utf8');
const pages: JSDOM[] = [];

function load(query: string) {
  const page = new JSDOM(html, {
    url: `https://livecontext.test/hero-flow.html?${query}`,
    runScripts: 'dangerously',
    beforeParse(window) {
      Object.defineProperty(window, 'matchMedia', { value: () => ({ matches: false }) });
      window.requestAnimationFrame = () => 0;
      Object.defineProperty(window, 'ResizeObserver', { value: class { observe() {} } });
    },
  });
  pages.push(page);
  return page.window;
}

afterEach(() => { pages.splice(0).forEach((page) => page.window.close()); });

describe('hero persona navigation', () => {
  it.each(locales)('links every persona to its %s parent page using native links', (locale) => {
    const { document } = load(`locale=${locale}`);
    for (const persona of PERSONA_KEYS) {
      const link = document.querySelector(`[data-persona="${persona}"]`)!;
      expect(link.tagName).toBe('A');
      expect(link.getAttribute('href')).toBe(personaHref(persona, locale));
      expect(link.getAttribute('target')).toBe('_top');
      expect(link.getAttribute('tabindex')).toBeNull();
    }
    expect(document.querySelector('[aria-current]')).toBeNull();
  });

  it('falls back safely for unknown locale and persona parameters', () => {
    const { document } = load('locale=%2F%2Fevil.test&persona=invalid');
    expect(document.querySelector('[data-persona="creator"]')?.getAttribute('href')).toBe('/for/creator');
    expect(document.querySelector('.wfname')?.textContent).toBe('Refund approvals');
    expect(document.querySelector('[aria-current]')).toBeNull();
  });

  it('selects the requested graph and gives icon-only links translated accessible names', () => {
    const { document } = load('persona=creator&navigationLabel=Choisir&label-creator=Cr%C3%A9ateur');
    const link = document.querySelector('[data-persona="creator"]')!;
    expect(link.getAttribute('aria-current')).toBe('page');
    expect(link.getAttribute('aria-label')).toBe('Créateur');
    expect(link.querySelector('.pl')?.textContent).toBe('Créateur');
    expect(document.querySelector('#ptabs')?.getAttribute('aria-label')).toBe('Choisir');
    expect(document.querySelector('.wfname')?.textContent).toBe('Vertical video pipeline');
  });

  it('preserves initial dark theme and same-origin live theme updates', () => {
    const window = load('theme=dark');
    expect(window.document.documentElement.classList.contains('dark')).toBe(true);
    window.dispatchEvent(new window.MessageEvent('message', {
      origin: 'https://untrusted.test', data: { type: 'lc-theme', theme: 'light' },
    }));
    expect(window.document.documentElement.classList.contains('dark')).toBe(true);
    window.dispatchEvent(new window.MessageEvent('message', {
      origin: window.location.origin, data: { type: 'lc-theme', theme: 'light' },
    }));
    expect(window.document.documentElement.classList.contains('dark')).toBe(false);
  });
});
