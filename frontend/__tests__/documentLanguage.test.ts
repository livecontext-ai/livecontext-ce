import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { routing } from '@/i18n/routing';

/**
 * What language the document says it is in.
 *
 * <p>The ROOT layout is shared with every route outside the `[locale]` tree (marketplace,
 * docs, models, legal), so it renders a fixed `<html lang="en">`: it cannot read the locale
 * param, and reading a header instead would opt the entire site out of static rendering.
 * Every translated page therefore declared itself English, which a screen reader obeys (it
 * pronounces French with an English voice) and which `PlanLimitToastListener` reads to pick
 * its wording. The locale layout stamps the real value before hydration.
 *
 * <p>Source-level on purpose: jsdom renders neither layout, and the thing being protected
 * is one line that is easy to delete while every rendering test stays green.
 */
const root = path.resolve(__dirname, '..');
const localeLayout = readFileSync(path.join(root, 'app/[locale]/layout.tsx'), 'utf8');
const rootLayout = readFileSync(path.join(root, 'app/layout.tsx'), 'utf8');

describe('document language', () => {
  it('stamps the served page with the locale it was rendered for', () => {
    expect(localeLayout).toContain('document.documentElement.lang=${JSON.stringify(locale)}');
  });

  it('takes the value from the route, never from the browser', () => {
    // navigator.language is the BROWSER's language: using it here would relabel a page
    // the site serves in French as German for a German-configured browser.
    expect(localeLayout).not.toContain('navigator.language');
  });

  it('records that the root layout is the one that cannot know it', () => {
    // If this ever becomes dynamic, the stamp is redundant and should go with it. The
    // fixed attribute is what makes the stamp necessary, so it is pinned here.
    expect(rootLayout).toContain('<html lang="en"');
    expect(routing.locales.length).toBeGreaterThan(1);
  });
});
