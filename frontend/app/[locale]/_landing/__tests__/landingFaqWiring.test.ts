import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The FAQ section, at the wiring level.
 *
 * <p>Source-level for the same reason as `landingHeroWiring.test.ts`: the landing is a
 * 3,000-line server component whose module pulls in the whole marketing tree, and rendering
 * it in jsdom would test Framer motion rather than this wiring.
 *
 * <p><strong>Why this file exists at all.</strong> `FaqSection` was written, translated into
 * six languages, and never mounted. Nothing failed: the copy shipped to the browser inside
 * the i18n payload, so a `grep` of the served HTML found the questions and made the section
 * look present, while `id="faq"` appeared nowhere on the page. The only reason it surfaced is
 * that someone looked at the page with their eyes. That is the class of defect this file
 * closes, and it is why the assertions below are about the CALL SITE rather than the
 * component: a component can be perfect and still render nowhere.
 *
 * <p>The structured data depends on it. `landingJsonLd` emits a `FAQPage` node, and Google
 * requires that node's content to be visible on the page; it was removed once precisely
 * because it was not. Deleting the mount without deleting the node puts the site back to
 * advertising answers nobody can read, so the mount is now a tested invariant.
 */
const landingSource = readFileSync(path.resolve(__dirname, '../../page.tsx'), 'utf8');

describe('landing FAQ wiring', () => {
  it('mounts the section, which is the whole point: it was defined and never rendered', () => {
    expect(landingSource).toContain('<FaqSection locale={locale} />');
  });

  it('places it after the prices and before the final call to action', () => {
    // Three of the six questions are about cost, self-hosting and how this differs from
    // Zapier. They are answered where they arise, and the visitor reaches the CTA with them
    // behind him rather than carried.
    const pricing = landingSource.indexOf('<PricingBlock locale={locale} />');
    const faq = landingSource.indexOf('<FaqSection locale={locale} />');
    const cta = landingSource.indexOf('<FinalCta locale={locale} />');

    expect(pricing).toBeGreaterThan(-1);
    expect(faq).toBeGreaterThan(pricing);
    expect(cta).toBeGreaterThan(faq);
  });

  it('sits on the primary ground, so two dark bands never touch', () => {
    // The page alternates grounds and `Section` draws no border of its own, so an `alt`
    // section here would sit against PricingBlock's `alt` with nothing between them. On the
    // primary ground the only repetition left is this section and FinalCta, and FinalCta
    // carries its own borderTop - the pattern the two strips under the hero already use.
    expect(landingSource).toContain('<Section id="faq">');
    expect(landingSource).not.toContain('<Section alt id="faq">');
  });

  it('keeps its neighbours on the grounds that argument depends on', () => {
    // The reasoning above is only sound while PricingBlock is the dark band before it and
    // FinalCta the bordered light one after. Pin both, or the next person to re-skin a
    // neighbour leaves this section's comment describing a rhythm that no longer exists.
    expect(landingSource).toContain('<Section alt id="pricing">');
    expect(landingSource).toMatch(
      /id="final-cta"[^>]*background: 'var\(--bg-primary\)', borderTop: '1px solid var\(--border-color\)'/,
    );
  });

  it('reads its closing line from messages, like the rest of the section', () => {
    // The heading and eyebrow were moved to messages when the copy was translated; this one
    // sentence stayed an English literal. The section renders on six indexable locales, so
    // it would have put English prose under a French page - and locale fallback hides that
    // class of bug rather than reporting it.
    expect(landingSource).toContain("t.rich('more'");
    expect(landingSource).not.toContain('More detail in the');
    expect(landingSource).not.toContain('>comparison pages</Link>');
  });
});

/**
 * The rich-text contract between the page and the six translations.
 *
 * next-intl throws at render when a message uses a tag the caller did not supply, so a
 * translator who drops `<compare>` or renames it breaks that locale's page - and only that
 * locale's, which is exactly the failure a single-language check never sees. Key parity does
 * not cover it either: the key is present, its markup is wrong.
 */
describe('the FAQ closing line, per locale', () => {
  const HANDLERS = ['docs', 'compare'] as const;
  const locales = ['en', 'fr', 'de', 'es', 'pt', 'zh'] as const;

  it.each(locales)('uses exactly the tags the page provides, in %s', (locale) => {
    const messages = JSON.parse(
      readFileSync(path.resolve(__dirname, `../../../../messages/${locale}.json`), 'utf8'),
    );
    const more: string = messages.LandingHome.faq.more;

    expect(more.trim()).not.toBe('');
    for (const tag of HANDLERS) {
      expect(more, `${locale}.${tag}`).toContain(`<${tag}>`);
      expect(more, `${locale}.${tag}`).toContain(`</${tag}>`);
    }
    // And no OTHER tag, which would throw for want of a handler.
    const used = [...more.matchAll(/<\/?([a-zA-Z][\w-]*)>/g)].map((match) => match[1]);
    expect([...new Set(used)].sort()).toEqual([...HANDLERS].sort());
  });
});
