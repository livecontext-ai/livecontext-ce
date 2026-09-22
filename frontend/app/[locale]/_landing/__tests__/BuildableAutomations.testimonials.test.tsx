/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

/**
 * The band flips to a testimonial wall when, and only when, real quotes exist.
 *
 * <p>This is the case that keeps the whole design honest. The section was asked for as a
 * testimonial wall with faces and names; what it must never do is carry a face and a name
 * that belong to nobody, which is a fabricated testimonial and a prohibited commercial
 * practice. So the wall is real and the switch is the guard: sample data renders capability
 * cards attributed to nobody, authorized data renders people.
 *
 * <p>The module is mocked rather than the shipped array edited, so these cases keep working
 * once `socialProof.ts` holds real customers and cannot be broken by that edit.
 */

const REAL = [
  {
    quote: 'I stopped recopying Typeform leads into HubSpot every Monday. It is four nodes now.',
    name: 'Dana Okoye',
    role: 'Head of Ops',
    company: 'Northwind',
    avatar: '/avatars/customers/dana.jpg',
    integrations: ['hubspot', 'slack'],
  },
  {
    quote: 'The weekly report used to take half a day and now it lands as a PDF before I do.',
    name: 'Samir Haddad',
    role: 'Founder',
    integrations: ['google-sheets'],
  },
];

async function renderWith(testimonials: unknown[], ready: boolean) {
  vi.doMock('../socialProof', () => ({
    TESTIMONIALS: testimonials,
    testimonialsReady: () => ready,
    CUSTOMER_LOGOS: [],
    LANDING_METRICS: [],
    metricsReady: () => false,
    TESTIMONIAL_BRIEF: '',
  }));
  const { default: Band } = await import('../BuildableAutomations');
  render(<Band />);
}

describe('the band, once real customer quotes exist', () => {
  beforeEach(() => vi.resetModules());
  afterEach(() => vi.doUnmock('../socialProof'));

  it('shows the person, their role and their own words', async () => {
    await renderWith(REAL, true);

    // Two entries is below the marquee threshold, so this is the grid: one card each, no
    // duplicated pass.
    expect(screen.getByText('Dana Okoye')).toBeTruthy();
    expect(screen.getByText('Head of Ops, Northwind')).toBeTruthy();
    expect(screen.getByText(REAL[0].quote)).toBeTruthy();
  });

  it('marks a quote up as a quotation, which the capability cards never do', async () => {
    await renderWith(REAL, true);

    // The semantic difference between "someone said this" and "the product does this". A
    // blockquote here is correct; a blockquote on the fallback would be a false attribution.
    expect(document.querySelectorAll('blockquote').length).toBeGreaterThan(0);
  });

  it('shows the photograph when there is one', async () => {
    await renderWith(REAL, true);

    const avatars = Array.from(document.querySelectorAll('img'))
      .map((img) => img.getAttribute('src'))
      .filter((src) => src?.includes('/avatars/customers/'));
    expect(avatars).toContain('/avatars/customers/dana.jpg');
  });

  it('falls back to initials for a customer who gave a name but not a face', async () => {
    await renderWith(REAL, true);

    // Name and photograph are two separate permissions and a customer may grant only one.
    // Without this the card collapses, which pressures whoever is collecting quotes into
    // finding a picture from somewhere, and "somewhere" is how a stock face ends up
    // standing in for a real person.
    expect(screen.getAllByText('SH').length).toBeGreaterThan(0);
  });

  it('renders capability cards, and nobody, while the quotes are still samples', async () => {
    await renderWith(REAL, false);

    // The guard. Sample or unauthorized data must not produce a person on the page.
    expect(screen.queryByText('Dana Okoye')).toBeNull();
    expect(document.querySelectorAll('blockquote')).toHaveLength(0);
    expect(document.querySelectorAll('img[src*="/avatars/customers/"]')).toHaveLength(0);

    // And the band is still there rather than leaving a hole in the page.
    expect(document.querySelectorAll('article').length).toBeGreaterThan(0);
  });

  it('lays the first few quotes out as a grid, not as a half-empty marquee', async () => {
    await renderWith(REAL, true);

    // Collecting the first quotes is exactly when a two-row marquee looks broken: measured
    // with three entries, row one carried two cards, row two carried one, and the rest of
    // the loop was empty track. A grid is the right layout for that stage, not a fallback.
    expect(document.querySelectorAll('.build-row')).toHaveLength(0);
    expect(document.querySelectorAll('article')).toHaveLength(REAL.length);
  });

  it('switches to the two-row marquee once there are enough quotes to fill it', async () => {
    const many = Array.from({ length: 10 }, (_, i) => ({
      ...REAL[0],
      name: `Customer ${i}`,
      quote: `Quote number ${i} about what they automated and what it replaced.`,
    }));
    await renderWith(many, true);

    expect(document.querySelectorAll('.build-row')).toHaveLength(2);
    expect(document.querySelectorAll('.build-row-reverse')).toHaveLength(1);

    // The duplicates are hidden from assistive tech in the testimonial mode too, or a screen
    // reader reads every customer story again for each repeat. Asserted as a SUBTRACTION so
    // it stays true whatever the repeat count: exactly one copy of each quote is announced.
    const all = document.querySelectorAll('article').length;
    const hidden = document.querySelectorAll('[aria-hidden="true"] article').length;
    expect(all - hidden).toBe(many.length);
  });
});
