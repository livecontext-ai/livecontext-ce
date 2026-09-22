/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import BuildableAutomations, { passRepeats } from '../BuildableAutomations';
import { AUTOMATION_EXAMPLES } from '../automationExamples';
import { mediaBlock, rule } from './mediaBlocks';

/**
 * The "What you can build" band.
 *
 * <p><strong>The load-bearing cases here are the ones about attribution.</strong> This band
 * was built because a landing page wanted social proof and had no authorized customer quotes
 * to show. The honest answer was to describe the product instead of inventing the people who
 * use it, and that only stays true if nothing in the data drifts into sounding like a
 * testimonial: no quotation marks around a first-person claim, no person's name, no company.
 * A future edit that adds "- Sarah, Head of Ops" to a card turns the whole section into
 * fabricated customer stories, so it fails here instead.
 */

/** Both landing surfaces use this stylesheet, so the marquee seam is pinned here. */
const landingSource = readFileSync(join(process.cwd(), 'components', 'landing', 'landingStyles.ts'), 'utf8');

/**
 * How many times one row repeats its cards inside a single pass.
 *
 * <p>Read from the component rather than written here: the band is full-bleed, so one pass
 * has to be wider than the screen or the loop shows bare track at the end of every cycle, and
 * the repeat count is derived from how many cards there are.
 */
const REPEATS = passRepeats(Math.ceil(AUTOMATION_EXAMPLES.length / 2));

describe('what you can build', () => {
  it('shows every example', () => {
    render(<BuildableAutomations />);

    for (const example of AUTOMATION_EXAMPLES) {
      // Exactly `2 * repeats`: one pass is the card set repeated enough times to outrun the
      // widest screen (see passRepeats), and the row holds that pass twice so the -50%
      // translation lands on the seam. An earlier version asserted "at least once", which
      // would have passed with the duplicate pass gone and the seam broken.
      expect(screen.getAllByText(example.automation)).toHaveLength(2 * REPEATS);
    }
  });

  it('splits the examples across two rows', () => {
    render(<BuildableAutomations />);

    // Two rows moving in opposite directions is what makes a static list read as a catalogue
    // in motion. One row would leave the second half unrendered.
    expect(document.querySelectorAll('.build-row')).toHaveLength(2);
    expect(document.querySelectorAll('.build-row-reverse')).toHaveLength(1);
  });

  it('duplicates each row once so the loop has a seam to land on', () => {
    render(<BuildableAutomations />);

    // The animation translates by exactly -50%, which only lands on an identical card when
    // the row holds precisely two passes. Any other multiple makes the loop visibly jump.
    expect(document.querySelectorAll('article')).toHaveLength(AUTOMATION_EXAMPLES.length * 2 * REPEATS);
  });

  it('spaces the cards by margin, never by a gap on the row', () => {
    render(<BuildableAutomations />);

    // The bug this pins is silent and was shipped once: with `gap` on the flex row, 2N cards
    // carry 2N-1 gaps, so one pass is NOT half the row and -50% lands half a gap short. The
    // loop then snaps every cycle. Counting articles cannot see it, so the invariant is
    // asserted where it actually lives: no gap utility on the row, a margin rule on the card.
    for (const row of Array.from(document.querySelectorAll('.build-row'))) {
      expect(row.className).not.toMatch(/\bgap-/);
    }
    // Match ALL occurrences, not the first: `[^}]*` stops at the first closing brace, so
    // inspecting only the base rule left the @media (min-width: 768px) override unguarded and
    // moving the md spacing back to a row gap was invisible.
    const cardBlocks = Array.from(landingSource.matchAll(/\.landing-root \.build-card \{([^}]*)\}/g));
    expect(cardBlocks.length).toBeGreaterThanOrEqual(1);
    // The logical property is equivalent and is the better form, so the invariant is "an end
    // margin", not the physical spelling. Binding to `margin-right` failed a correct rename
    // to `margin-inline-end`.
    const END_MARGIN = /margin(?:-inline)?-(?:right|end)\s*:/;
    // The BASE rule is what makes one pass exactly half the row, so it always declares it.
    expect(cardBlocks[0][1]).toMatch(END_MARGIN);
    // Any block that has an opinion about spacing has to express it as an end margin. An
    // override that touches nothing else is fine and inherits the base (the phone block only
    // caps the width and adds scroll snapping), and a `gap` alongside the margin is fine too:
    // the card is itself `flex flex-col`, so a gap there spaces the card's OWN children and
    // cannot move the row. What breaks the seam is spacing that REPLACES the end margin.
    const SPACING = /\b(?:margin|gap|column-gap)\b/;
    for (const [, body] of cardBlocks) {
      if (SPACING.test(body)) expect(body).toMatch(END_MARGIN);
      // `\b` is the wrong boundary after a zero: it accepts `margin-right: 0` and misses
      // `0px`, which is the spelling anyone would actually type.
      expect(body).not.toMatch(new RegExp(END_MARGIN.source + '\\s*0(?![.\\d])'));
    }

    const rowBlocks = Array.from(landingSource.matchAll(/\.landing-root \.build-row(?:-reverse)? \{([^}]*)\}/g));
    expect(rowBlocks.length).toBeGreaterThanOrEqual(2);
    for (const [, body] of rowBlocks) {
      expect(body).not.toMatch(/\bgap\b/);
    }
  });

  it('announces each automation once however many times it is drawn', () => {
    render(<BuildableAutomations />);

    // The repeats exist for the animation, not for the reader. This is the assertion that
    // matters and it is written as a SUBTRACTION rather than a count of hidden cards: exactly
    // one copy of each card must be outside an aria-hidden subtree, whatever the repeat
    // count, or a screen reader reads the whole band through several times.
    const all = document.querySelectorAll('article').length;
    const hidden = document.querySelectorAll('[aria-hidden="true"] article').length;
    expect(all - hidden).toBe(AUTOMATION_EXAMPLES.length);
  });

  it('gives every duplicate a class the reduced-motion rule can hide', () => {
    render(<BuildableAutomations />);

    // With the animation stopped the row becomes a plain scroller and every repeat is just
    // the same cards again, so each one has to be addressable to be hidden. Per row: the
    // (REPEATS - 1) fills inside the pass, the echo of the whole pass, and that echo's own
    // copy of those fills, so 2 * REPEATS - 1 of them, twice over for the two rows. Nesting
    // is harmless because the rule hides the outermost one and everything under it.
    expect(document.querySelectorAll('.build-row-echo')).toHaveLength(2 * (2 * REPEATS - 1));
    expect(landingSource).toMatch(/\.landing-root \.build-row-echo \{[^}]*display: none/);
  });

  it('leaves the stopped row reachable by keyboard', () => {
    render(<BuildableAutomations />);

    // Under prefers-reduced-motion the viewport becomes overflow-x: auto. Without a tab stop
    // the cards past the fold are reachable by pointer only, so the fallback that exists to
    // help is the one that excludes people.
    for (const viewport of Array.from(document.querySelectorAll('.build-row-viewport'))) {
      expect(viewport.getAttribute('tabindex')).toBe('0');
      expect(viewport.getAttribute('aria-label')).toBeTruthy();
    }
  });

  it('claims no author for any example', () => {
    render(<BuildableAutomations />);

    // The line between "here is what the product does" and "here is what a customer told us"
    // is the difference between marketing copy and a fabricated testimonial. These describe
    // capability, so nothing here may be quoted, signed, or attributed.
    for (const example of AUTOMATION_EXAMPLES) {
      expect(example.automation).not.toMatch(/["“”]/);
      expect(example.automation).not.toMatch(/\bI\b|\bwe\b|\bour\b/i);
    }
    expect(document.querySelectorAll('blockquote')).toHaveLength(0);
    expect(document.querySelectorAll('figcaption')).toHaveLength(0);
  });

  it('names a job to be done rather than a company, on every card', () => {
    // A role is a reader recognising themselves. A company name is a customer claim, and
    // there is no authorization behind any name that would appear here.
    for (const example of AUTOMATION_EXAMPLES) {
      expect(example.role).not.toMatch(/\b(Inc|Ltd|LLC|GmbH|SAS|SARL|Corp|Co\.)\b/);
      expect(example.role.length).toBeLessThanOrEqual(24);
    }
  });

  it('advertises only integrations the catalogue actually carries', () => {
    // Each card renders brand marks from these slugs. An unverified one is a blank square
    // beside a claim that the platform connects to something it may not.
    const known = new Set(WELL_KNOWN_INTEGRATIONS.map((i) => i.slug));
    for (const example of AUTOMATION_EXAMPLES) {
      expect(example.integrations.length).toBeGreaterThan(0);
      for (const slug of example.integrations) {
        expect(known.has(slug)).toBe(true);
      }
    }
  });

  it('draws every mark from the icon key rather than the slug', () => {
    render(<BuildableAutomations />);

    // google-sheets is drawn by googlesheets. Deriving the path from the slug is the obvious
    // shortcut and it produces a blank square on the card.
    const byslug = new Map(WELL_KNOWN_INTEGRATIONS.map((i) => [i.slug, i.iconSlug]));
    const sources = Array.from(document.querySelectorAll('img')).map((img) => img.getAttribute('src'));
    for (const example of AUTOMATION_EXAMPLES) {
      for (const slug of example.integrations) {
        expect(sources).toContain(`/icons/services/${byslug.get(slug)}.svg`);
      }
    }
  });

  it('keeps every example concrete enough to be checked against a real week', () => {
    // The failure mode of a band like this is "streamline your workflow" filler, which tells
    // a visitor nothing and reads as padding. A real trigger and a real result take words.
    for (const example of AUTOMATION_EXAMPLES) {
      expect(example.automation.length).toBeGreaterThan(80);
      expect(example.automation.trim()).toMatch(/\.$/);
    }
  });

  it('uses no em-dash or en-dash anywhere in the copy', () => {
    // Project-wide ban: both read as machine-written, and this is user-facing marketing copy.
    for (const example of AUTOMATION_EXAMPLES) {
      expect(example.automation).not.toMatch(/[--]/);
      expect(example.role).not.toMatch(/[--]/);
    }
  });
});

/**
 * The band on a phone.
 *
 * A marquee needs about four cards on screen to read as a wall in motion. A 431px viewport
 * fits one 300px card, so the band was two HALF cards sliding past with their sentences cut
 * on both sides and the edge mask fading what survived. Below `md` the row holds still and
 * the visitor swipes it, which is the regime reduced motion already asked for.
 */
describe('what you can build, on a phone', () => {
  const phone = () => mediaBlock(landingSource, '(max-width: 767px)', '.build-row-viewport');

  it('stops the animation and hands the row to the visitor', () => {
    expect(rule(phone(), '.landing-root .build-row')).toMatch(/animation:\s*none/);
    expect(rule(phone(), '.landing-root .build-row-viewport')).toMatch(/overflow-x:\s*auto/);
    expect(rule(phone(), '.landing-root .build-row-viewport')).toMatch(/scroll-snap-type:\s*x mandatory/);
    expect(rule(phone(), '.landing-root .build-card')).toMatch(/scroll-snap-align:/);
  });

  it('drops the duplicate pass, which only ever existed for the animation', () => {
    expect(rule(phone(), '.landing-root .build-row-echo')).toMatch(/display:\s*none/);
  });

  it('caps the card so a small phone gets the whole sentence', () => {
    // The card is a fixed 300px, wider than the content box of a 320px phone.
    expect(rule(phone(), '.landing-root .build-card')).toMatch(/max-width:\s*calc\(100vw - \d+px\)/);
  });

  it('drops the edge fade, which only made sense while the row was moving', () => {
    expect(rule(phone(), '.landing-root .build-row-viewport')).toMatch(/mask-image:\s*none/);
    // Still there above the breakpoint, where the band does move.
    expect(rule(landingSource, '.landing-root .build-row-viewport')).toMatch(/mask-image:\s*linear-gradient/);
  });

  it('gives a reduced-motion visitor the same stopped row, not a half-stopped one', () => {
    // Reduced motion reaches the SAME state by another route: the animation is off and the row
    // is a scroller. Every argument the phone rules make then applies to it, so the two blocks
    // have to agree. They did not: reduced motion stopped the row and kept the fade, which is
    // the state the phone block calls wrong, and got no snapping, on any width above 768px.
    const reduced = mediaBlock(landingSource, '(prefers-reduced-motion: reduce)', '.build-row-viewport');

    for (const block of [phone(), reduced]) {
      expect(rule(block, '.landing-root .build-row')).toMatch(/animation:\s*none/);
      expect(rule(block, '.landing-root .build-row-viewport')).toMatch(/overflow-x:\s*auto/);
      expect(rule(block, '.landing-root .build-row-viewport')).toMatch(/scroll-snap-type:\s*x mandatory/);
      expect(rule(block, '.landing-root .build-row-viewport')).toMatch(/mask-image:\s*none/);
      expect(rule(block, '.landing-root .build-row-echo')).toMatch(/display:\s*none/);
      expect(rule(block, '.landing-root .build-card')).toMatch(/scroll-snap-align:/);
    }
  });
});
