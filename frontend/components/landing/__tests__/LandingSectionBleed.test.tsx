// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Section } from '../LandingSections';
import { landingStyles } from '../landingStyles';
import { passRepeats } from '@/app/[locale]/_landing/BuildableAutomations';
import { AUTOMATION_EXAMPLES } from '@/app/[locale]/_landing/automationExamples';

afterEach(cleanup);

/**
 * The regression this file exists for: the scrolling band of automation cards used to be
 * rendered INSIDE the section's 1104px content box, so on every screen wider than that it
 * clipped its cards mid-sentence two inches inside the layout, and at 768px it cut the
 * whole right-hand card. It reads as a broken card rather than as a row running off the
 * screen, which is what "the section overflows" meant when it was reported.
 */
describe('Section, full-bleed slot', () => {
  it('keeps normal children inside the centred content box', () => {
    render(<Section id="s"><p>inside</p></Section>);
    const box = screen.getByText('inside').parentElement!;
    expect(box.className).toContain('max-w-6xl');
    expect(box.className).toContain('pb-24');
  });

  it('renders bleed content OUTSIDE that box, as a direct child of the section', () => {
    render(<Section id="s" bleed={<div data-testid="band" />}><p>heading</p></Section>);
    const band = screen.getByTestId('band');
    // Nothing between the band and the section may re-impose the content width, or the
    // clipping moves back inside the layout and the bug returns.
    let node: HTMLElement | null = band;
    while (node && node.tagName !== 'SECTION') {
      expect(node.className).not.toContain('max-w-6xl');
      node = node.parentElement;
    }
    expect(node?.tagName).toBe('SECTION');
  });

  it('moves the bottom padding to the bleed wrapper instead of adding a second one', () => {
    render(<Section id="s" bleed={<div data-testid="band" />}><p>heading</p></Section>);
    const box = screen.getByText('heading').parentElement!;
    expect(box.className).toContain('pt-24');
    expect(box.className).not.toContain('pb-24');
    expect(screen.getByTestId('band').parentElement!.className).toContain('pb-24');
  });
});

describe('the band never runs out of track', () => {
  // Made reachable by the bleed: the row used to be clipped to the 1104px content box, so the
  // width of one pass could never matter. Full-bleed makes the viewport the limit, and the
  // animation ends each cycle with the content's right edge at exactly one pass width, so a
  // pass narrower than the screen shows bare track for the tail of every loop.
  it('fills the widest screen it claims to support with one pass', () => {
    const cardsPerRow = Math.ceil(AUTOMATION_EXAMPLES.length / 2);
    expect(cardsPerRow * passRepeats(cardsPerRow) * 360).toBeGreaterThanOrEqual(4320);
  });

  it('repeats more when there are fewer cards, so trimming the list cannot reopen the gap', () => {
    expect(passRepeats(6)).toBe(2);
    expect(passRepeats(3)).toBe(4);
    expect(passRepeats(12)).toBe(1);
    expect(passRepeats(0)).toBe(1);
  });
});

describe('the band clipping itself', () => {
  it('fades a fixed number of pixels, not a share of the width', () => {
    // A percentage fade scaled the wrong way: it gave 54px at 768, where the cards are the
    // same size and need more, and ate a whole card at 1920.
    expect(landingStyles).toContain('mask-image: linear-gradient(to right, transparent, black 96px, black calc(100% - 96px), transparent)');
    expect(landingStyles).not.toContain('black 7%');
  });

  // Not a regression for the bleed change (it held before it too): a standing guard on the
  // trap the bleed was deliberately built to avoid, since the obvious way to widen this row
  // is the one that gives every desktop browser a horizontal scrollbar.
  it('never escapes its container with a viewport unit, which would add a page scrollbar', () => {
    const viewportRule = landingStyles.slice(
      landingStyles.indexOf('.landing-root .build-row-viewport {'),
      landingStyles.indexOf('.landing-root .build-row {'),
    );
    expect(viewportRule).not.toContain('100vw');
    expect(viewportRule).toContain('overflow: hidden');
  });

  it('gutters the row only where it has stopped, so the animated seam stays at exactly half', () => {
    // padding on an animating row widens it, and one pass stops being the 50% the
    // translation lands on, which makes the loop snap on every cycle. Matched on the
    // declaration rather than on a literal block: indentation is not the contract.
    const gutters = landingStyles.match(/\.build-row\s*\{[^}]*padding-inline:\s*24px/g) ?? [];
    expect(gutters).toHaveLength(2);
    // Anchored on the DECLARATION that defines the animated regime, not on the block's own
    // text: anchoring on the latter made this assertion vacuous, because moving the gutter in
    // (the exact failure it guards) also moved the anchor, indexOf returned -1, and slice(-1)
    // handed the assertion an empty string that passes.
    const animationAt = landingStyles.indexOf('animation: build-scroll-left');
    expect(animationAt).toBeGreaterThan(-1);
    const ruleStart = landingStyles.lastIndexOf('{', animationAt);
    const animated = landingStyles.slice(ruleStart, landingStyles.indexOf('}', animationAt));
    expect(animated).toContain('width: max-content');
    expect(animated).not.toContain('padding-inline');
  });

  it('does not claim a gutter from scroll-padding, which a centred snap ignores', () => {
    // It was set alongside scroll-snap-align: center, where a symmetric inset leaves the
    // snapport's centre exactly where the scrollport's was: two dead declarations and a
    // comment crediting them for the gutter the padding actually provides.
    // The DECLARATION, not the word: the comment that replaced it names it on purpose.
    expect(landingStyles).not.toMatch(/scroll-padding-[a-z-]*\s*:/);
  });
});
