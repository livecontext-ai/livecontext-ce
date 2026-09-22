import { describe, expect, it, vi } from 'vitest';
import { readdirSync, existsSync } from 'node:fs';
import path from 'node:path';

/**
 * The marketing pages whose titles are BARE, and therefore depend entirely on
 * the root layout's `title.template` to carry the brand.
 *
 * They used to spell the brand out themselves, which the template then
 * appended a second time: `/about` rendered "About - LiveContext -
 * LiveContext". Removing the suffix is the fix, and it moves the brand's
 * survival into the template. Drop or edit `title.template` in
 * `app/layout.tsx` and every page here silently loses its brand, in six
 * languages for `/contact`. Before this file existed nothing anywhere would
 * have failed: measured against Next 16.2.6's own resolver, a bare "About"
 * resolves to "About" once the template is gone.
 *
 * The sibling pages under `app/legal/` are deliberately NOT here. They keep
 * their own suffix because `app/legal/layout.tsx` sets a plain-string title at
 * a non-leaf position, which nulls the template for its children: a bare title
 * there would render with no brand at all. That was tried and measured before
 * being reverted.
 *
 * Listings and integration pages are not here either, for the opposite reason:
 * their titles use `title: { absolute }`, which is passed through verbatim and
 * cannot be affected by the template. See
 * `app/marketplace/[slug]/__tests__/listing-metadata.test.ts`.
 */

// The root layout is imported for its metadata only. Its font loaders are a
// build-time Next transform with no runtime implementation.
vi.mock('next/font/google', () => ({
  Inter: () => ({ variable: '--font-inter' }),
  Outfit: () => ({ variable: '--font-outfit' }),
}));

const APP_DIR = path.resolve(__dirname, '..');

import { metadata as rootMetadata } from '../layout';
import { metadata as aboutMetadata } from '../about/page';
import { metadata as changelogMetadata } from '../changelog/page';

import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

const LOCALES = { en, fr, de, es, pt, zh } as Record<
  string,
  { contact: { metadata: { title: string } } }
>;

/**
 * The bug is a title ENDING in the brand suffix, which the template then
 * appends a second time. Naming the product inside the sentence is fine and
 * deliberate: `/compare` is titled "Compare LiveContext vs Zapier, n8n and
 * Make" and renders correctly as that plus " - LiveContext". A blunter
 * "contains LiveContext" rule flags it, which is how this regex got written.
 */
const TRAILING_BRAND = /[-|]\s*LiveContext\s*$/;

describe('marketing page titles name the brand exactly once', () => {
  it('keeps the root template that supplies the brand', () => {
    // Every assertion below is only correct while this holds. Verified by
    // mutation: changing the separator, dropping `template`, or replacing the
    // whole `title` with a plain string each fail this.
    expect(rootMetadata.title).toMatchObject({ template: '%s - LiveContext' });
  });

  it('leaves the brand out of the static page titles', () => {
    expect(aboutMetadata.title).toBe('About');
    expect(changelogMetadata.title).toBe('Changelog');
  });

  it('leaves the brand out of the contact title in every locale', () => {
    // `/contact` takes its title from the message catalogue, so the double
    // brand had to be removed six times. A locale left behind would render
    // "Kontakt - LiveContext - LiveContext" for German readers only.
    for (const [locale, messages] of Object.entries(LOCALES)) {
      const title = messages.contact.metadata.title;
      expect(title, locale).not.toMatch(TRAILING_BRAND);
      expect(title.trim(), locale).not.toHaveLength(0);
    }
  });

  it('has no OTHER index page shipping the brand in its own title', async () => {
    // The shape of the bug rather than the two known instances: a page added
    // later with "- LiveContext" in a plain `title` gets it appended twice, and
    // the two assertions above would not notice. Derived from `app/` so a new
    // page is covered the day it lands.
    //
    // Only pages exporting a static `metadata` object can be read this way; one
    // built by `generateMetadata` needs params, and the two that matter
    // (marketplace and integration listings) use `title: { absolute }`, which
    // takes no template and is pinned beside them. `app/legal/*` is excluded
    // for the documented reason above: its layout nulls the template, so its
    // pages MUST carry the brand.
    const indexPages = readdirSync(APP_DIR, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .filter((name) => !['[locale]', 'api', '__tests__', 'legal'].includes(name))
      .filter((name) => existsSync(path.join(APP_DIR, name, 'page.tsx')));

    expect(indexPages.length).toBeGreaterThan(5);

    const checked: string[] = [];
    for (const name of indexPages) {
      const mod = await import(`../${name}/page.tsx`) as { metadata?: { title?: unknown } };
      const title = mod.metadata?.title;
      if (typeof title !== 'string') continue;
      checked.push(name);
      expect(title, `${name}/page.tsx`).not.toMatch(TRAILING_BRAND);
    }

    // Which pages were actually read, asserted rather than left to a silent
    // `continue`: without this the loop would still pass having skipped every
    // one of them, and the test would be decoration. `/contact`, `/local-mcp`,
    // `/redeem` and `/models` are the expected misses (no static `metadata`,
    // or built by `generateMetadata`); `/contact` is covered above instead.
    expect(checked.sort()).toEqual([
      'about', 'changelog', 'compare', 'docs', 'integrations', 'marketplace', 'status', 'videos',
    ]);
    // This case dynamically imports seven whole page module graphs, so it costs ~16s on its
    // own against the suite-wide 20s testTimeout. That margin is thin enough that adding
    // files to the same CI vitest invocation pushes it over, which is a timeout rather than
    // a failed assertion and reads as an unrelated regression to whoever hits it. The
    // generous per-case timeout is cheaper than making the coverage narrower.
  }, 90_000);
});
