// @vitest-environment jsdom
/**
 * The public /partners page, rendered against the REAL dictionaries of all six languages with a
 * translator that throws on any missing key or malformed message: a key present in no locale
 * file, or an ICU placeholder that does not parse, fails here instead of reaching a visitor as a
 * raw key path.
 */
import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTranslator, type AbstractIntlMessages } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

const messages = { en, fr, de, es, pt, zh } as Record<string, AbstractIntlMessages>;
const state = vi.hoisted(() => ({ locale: 'en', isCe: false }));
const fetchPartnerTerms = vi.fn();
const fetchMarketplace = vi.fn();

vi.mock('server-only', () => ({}));
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return state.isCe; },
  get IS_MANAGED_CLOUD() { return !state.isCe; },
}));
vi.mock('next-intl/server', () => ({
  getTranslations: async ({ locale, namespace }: { locale: string; namespace: string }) => createTranslator({
    locale, messages: messages[locale], namespace,
    onError: (error) => { throw error; },
  }),
  getMessages: async ({ locale }: { locale: string }) => messages[locale],
  setRequestLocale: vi.fn(),
}));
vi.mock('@/i18n/resolveRequestLocale', () => ({ resolveRequestLocale: async () => state.locale }));
vi.mock('next/navigation', () => ({ notFound: () => { throw new Error('NOT_FOUND'); } }));
vi.mock('@/lib/partners/publicPartnerTerms', () => ({ fetchPartnerTerms: () => fetchPartnerTerms() }));
vi.mock('@/lib/marketplace/publicPublications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/marketplace/publicPublications')>()),
  fetchAllPublicPublications: () => fetchMarketplace(),
}));
// The chrome and the client islands have their own suites; here they only have to mount.
vi.mock('@/components/landing/LandingShell', () => ({
  LandingHeader: () => <header data-testid="landing-header" />,
  LandingFooter: () => <footer data-testid="landing-footer" />,
  landingChromeStyles: '',
}));
vi.mock('@/components/landing/LandingThemeProvider', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/app/[locale]/_landing/FaqItem', () => ({
  default: ({ children }: { children: React.ReactNode }) => <details>{children}</details>,
}));
vi.mock('@/components/partner/PartnerApplySection', () => ({
  PartnerApplySection: () => <div data-testid="apply-section" />,
}));
vi.mock('@/components/partner/PartnerEarningsCalculator', () => ({
  PartnerEarningsCalculator: (props: { founderOpen: boolean; founderUntilLabel: string | null; tiers: unknown[] }) => (
    <div data-testid="calculator" data-founder={String(props.founderOpen)} data-tiers={props.tiers.length} />
  ),
}));

import PartnersPage, { generateMetadata } from '../page';

const TIERS = [
  { tier: 'silver', commission_percent: 30, threshold_minor: 0 },
  { tier: 'gold', commission_percent: 40, threshold_minor: 500_000 },
  { tier: 'platinum', commission_percent: 50, threshold_minor: 2_500_000 },
];
const TERMS = {
  commission_percent: 30, commission_months: 12, hold_days: 14, audience_credits: 8000,
  tiers: TIERS, tier_currency: 'usd', tier_settle_days: 60, founder_until: '2100-01-01T00:00:00Z', founder_open: true,
};

function listing(id: string, title: string, useCount: number, extra: Record<string, unknown> = {}) {
  return {
    id, publicSlug: `${id}-slug`, title, description: `${title} description`, publisherName: `${title} Co`,
    publisherId: '7', publisherHandle: null, publisherAvatarUrl: null, categorySlug: null, categoryName: null,
    averageRating: 4.5, reviewCount: 3, useCount, publishedAt: null, updatedAt: null, publicationType: 'APPLICATION',
    categoryColor: null, displayMode: 'APPLICATION', creditsPerUse: 0, hasShowcase: true,
    nodeIcons: [{ nodeId: `${id}-n`, nodeKind: 'mcp', iconSlug: 'slack', isMcp: true }],
    agentCount: 0, interfaceCount: 1, workflowCount: 1, skillCount: 0, datasourceCount: 0, planSnapshot: null,
    ...extra,
  };
}

/** The first page of the public marketplace: four usable apps, a workflow and an app with no icons. */
const MARKETPLACE = [
  listing('m1', 'Lead Scoring Studio', 400),
  listing('m2', 'Content Calendar', 900),
  listing('m3', 'Order Tracker', 150),
  listing('m4', 'Rarely Used App', 5),
  listing('w1', 'A Plain Workflow', 5000, { publicationType: 'WORKFLOW', displayMode: null }),
  listing('m5', 'No Icons App', 8000, { nodeIcons: [] }),
];

async function renderPage() {
  render(await PartnersPage());
}

const main = () => document.querySelector('main') as HTMLElement;
/** What a visitor reads in the page: the text of <main> without its inline stylesheets (the brand
 *  mark animates with keyframes such as "0%, 100%", which are not copy). `programOnly` also
 *  leaves out the example app's screen: a picture of an app, whose invoice amounts say nothing
 *  about the program. */
const visibleText = ({ programOnly = false } = {}) => {
  const copy = main().cloneNode(true) as HTMLElement;
  copy.querySelectorAll(programOnly ? 'style, [data-testid="partner-example-app-screen"]' : 'style').forEach((node) => node.remove());
  return copy.textContent ?? '';
};
const text = (testId: string) => screen.getByTestId(testId).textContent ?? '';

describe('/partners', () => {
  beforeEach(() => {
    state.locale = 'en';
    state.isCe = false;
    fetchPartnerTerms.mockReset();
    fetchPartnerTerms.mockResolvedValue(TERMS);
    fetchMarketplace.mockReset();
    fetchMarketplace.mockResolvedValue({ publications: MARKETPLACE, truncated: false });
  });
  afterEach(cleanup);

  it('leads with the TOP rate, read live from the backend, never with the entry rate', async () => {
    fetchPartnerTerms.mockResolvedValue({
      ...TERMS,
      commission_months: 18,
      tiers: [{ ...TIERS[0], commission_percent: 25 }, { ...TIERS[1], commission_percent: 35 }, { ...TIERS[2], commission_percent: 45 }],
    });
    await renderPage();

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Earn up to 45% of every invoice your clients pay');
    expect(screen.getByText('Up to 45%')).toBeTruthy();
    expect(screen.getByText('18 months')).toBeTruthy();
    // The entry rate is not a headline figure anywhere above the tiers.
    expect(document.getElementById('hero')?.textContent).not.toMatch(/\b25%/);
  });

  it('builds every earnings example on a REAL plan and credit tier, at the list price, never on a made-up average', async () => {
    await renderPage();

    const scenarios = screen.getByTestId('partner-scenarios');
    // Team with 5M credits: $49 + $4,000 = $4,049 a month; half of it is $2,024.50.
    const single = within(scenarios.querySelector('[data-scenario="single"]') as HTMLElement);
    expect(single.getByText('One client on Team with 5M credits')).toBeTruthy();
    expect(single.getByText('$4,049 a month per client')).toBeTruthy();
    expect(single.getByText('$2,025')).toBeTruthy();
    // regression: the year follows the month as shown ($2,025 x 12), not the unrounded $2,024.50 x 12.
    expect(single.getByText('$24,300 a year')).toBeTruthy();
    // Pro with 250K credits: $24 + $185 = $209; 30 clients at 50%.
    const agency = within(scenarios.querySelector('[data-scenario="agency"]') as HTMLElement);
    expect(agency.getByText('30 clients on Pro with 250K credits')).toBeTruthy();
    expect(agency.getByText('$209 a month per client')).toBeTruthy();
    expect(agency.getByText('$3,135')).toBeTruthy();
    expect(agency.getByText('$37,620 a year')).toBeTruthy();
    // Team with 1M credits: $49 + $825 = $874; 40 clients at 50%.
    const large = within(scenarios.querySelector('[data-scenario="large"]') as HTMLElement);
    expect(large.getByText('40 clients on Team with 1M credits')).toBeTruthy();
    expect(large.getByText('$17,480')).toBeTruthy();
    expect(large.getByText('$209,760 a year')).toBeTruthy();
    // regression: the old examples ran on a flat "$60 a month" nobody is billed.
    expect(visibleText()).not.toContain('$60');
  });

  it('every example price opens the in-app pricing preset to it (monthly, its credit tier), the hidden 5M tier unlocked, in the page\x27s language', async () => {
    await renderPage();

    const bill = (key: string) => (screen.getByTestId('partner-scenarios')
      .querySelector(`[data-scenario="${key}"] [data-testid="partner-scenario-bill"]`) as HTMLAnchorElement).getAttribute('href');
    // Not the public /pricing: on cloud it redirects to the landing, which has no credit slider.
    const preset = '/en/app/settings/pricing?pricingMode=subscription&billingCycle=monthly&creditTierIndex=';
    expect(bill('single')).toBe(`${preset}8&tiers=full`);
    expect(bill('agency')).toBe(`${preset}5`);
    expect(bill('large')).toBe(`${preset}7`);
  });

  it('regression: a French page opens the French pricing (an unprefixed app link sent everyone to /en)', async () => {
    state.locale = 'fr';
    await renderPage();

    const href = (screen.getByTestId('partner-scenarios')
      .querySelector('[data-scenario="agency"] [data-testid="partner-scenario-bill"]') as HTMLAnchorElement).getAttribute('href');
    expect(href).toBe('/fr/app/settings/pricing?pricingMode=subscription&billingCycle=monthly&creditTierIndex=5');
  });

  it('regression: a one-month commission and a one-day hold read in the singular, in the hero, the steps and the FAQ', async () => {
    fetchPartnerTerms.mockResolvedValue({ ...TERMS, commission_months: 1, hold_days: 1, tier_settle_days: 1 });
    await renderPage();

    const text = visibleText();
    expect(text).toContain('for 1 month per client');
    expect(text).toContain('for 1 month, payable after a refund window of 1 day');
    expect(text).toContain('held 1 day in case of a refund');
    expect(text).toContain('once it is 1 day old');
    expect(text).not.toMatch(/\b1 (months|days)\b/);
  });

  it('regression: a tier reachable with ONE client says "About 1 client", never "1 clients"', async () => {
    fetchPartnerTerms.mockResolvedValue({ ...TERMS, tiers: [TIERS[0], { ...TIERS[1], threshold_minor: 200_000 }, TIERS[2]] });
    await renderPage();

    // $2,000 against $209 a month for a year ($2,508): one client is enough.
    expect(visibleText()).toContain('About 1 client on Pro');
    expect(visibleText()).not.toMatch(/\b1 clients\b/);
  });

  it('the steps are a real ordered list: the decorative line sits beside it, the <ol> holds only <li>', async () => {
    await renderPage();

    const lists = Array.from(main().querySelectorAll('ol'));
    expect(lists.length).toBeGreaterThan(0);
    for (const ol of lists) expect(Array.from(ol.children).every((c) => c.tagName === 'LI')).toBe(true);
  });

  it('the hero partner is a founding partner while founders are named: Platinum from month one, on the real Pro price', async () => {
    await renderPage();

    const card = screen.getByTestId('partner-hero-earnings');
    // 30 clients x $209 x 50% in month 12; 198 client-months x $104.50 over the year.
    expect(text('partner-hero-month')).toBe('$3,135');
    expect(text('partner-hero-year')).toBe('$20,691');
    expect(within(card).getByText('Founding partner, Platinum from day one')).toBeTruthy();
    expect(within(card).getByText('from 30 clients on Pro with 250K credits')).toBeTruthy();
    expect(within(card).getByText('50% (Platinum)')).toBeTruthy();
    expect(within(card).getByRole('img', { name: /grow from 3 to 30/ })).toBeTruthy();
  });

  it('once founders are no longer named, the hero partner climbs on revenue: Gold in month 6, Platinum in month 12', async () => {
    fetchPartnerTerms.mockResolvedValue({ ...TERMS, founder_open: false });
    await renderPage();

    const card = screen.getByTestId('partner-hero-earnings');
    // Months 1-4 settle $5,434 by month 6; months 1-10 settle $29,260 by month 12.
    // Year: $8,151 x 30% + $26,961 x 40% + $6,270 x 50% = $16,364.70.
    expect(text('partner-hero-month')).toBe('$3,135');
    expect(text('partner-hero-year')).toBe('$16,365');
    expect(within(card).getByText('Partner for one year')).toBeTruthy();
    expect(within(card).getByText('50% (Platinum)')).toBeTruthy();
  });

  it('explains how to move up: each tier, the revenue that reaches it in dollars AND in clients at a real price, and the rules', async () => {
    await renderPage();

    const track = screen.getByTestId('partner-tier-track');
    expect(within(track).getByText('30%')).toBeTruthy();
    expect(within(track).getByText('From the day you are approved')).toBeTruthy();
    expect(within(track).getByText('40%')).toBeTruthy();
    expect(within(track).getByText('Once your clients have paid $5,000')).toBeTruthy();
    // $5,000 / ($209 x 12) = 1.99 and $25,000 / $2,508 = 9.97, rounded up.
    expect(within(track).getByText('About 2 clients on Pro with 250K credits ($209 a month), for a year')).toBeTruthy();
    expect(within(track).getByText('50%')).toBeTruthy();
    expect(within(track).getByText('Once your clients have paid $25,000')).toBeTruthy();
    expect(within(track).getByText('About 10 clients on Pro with 250K credits ($209 a month), for a year')).toBeTruthy();
    expect(within(track).getByText('Never goes down')).toBeTruthy();
    expect(within(track).getByText('An invoice counts toward a tier once it is 60 days old and was not refunded.')).toBeTruthy();
  });

  it('draws Platinum as the destination: the top-tier card, the only one labelled so', async () => {
    await renderPage();

    const track = screen.getByTestId('partner-tier-track');
    const platinum = track.querySelector('[data-tier="platinum"]') as HTMLElement;
    expect(within(platinum).getByText('Top tier')).toBeTruthy();
    expect(within(track).getAllByText('Top tier')).toHaveLength(1);
  });

  it('while the founder window is open: founders go straight to Platinum, said in the hero, the track, its own band and the FAQ', async () => {
    await renderPage();

    expect(text('partner-founder-banner')).toContain('Founding partners: 50% from day one, for life');
    expect(screen.getByTestId('partner-founder-banner').getAttribute('href')).toBe('#founder');
    expect(text('partner-founder-shortcut')).toBe('Founding partners start here');
    const band = screen.getByTestId('partner-founder-band');
    expect(within(band).getByText('50% from day one, for life.')).toBeTruthy();
    // Founder status is the team's choice, never automatic on applying: the copy must say so.
    expect(within(band).getByText('The team chooses founding partners among the applications received before Jan 01, 2100. '
      + 'Applying does not make you one automatically. “For life” means for the whole of your participation in the program, '
      + 'unless we end it under the Partner Program Terms (for example after 12 months without any commission).')).toBeTruthy();
    expect(Number(text('partner-founder-days'))).toBeGreaterThan(0);
    expect(screen.getByText('What is a founding partner?')).toBeTruthy();
    expect(screen.getByTestId('calculator').getAttribute('data-founder')).toBe('true');
  });

  it('regression: terms cached before the deadline do not keep the founder offer alive after it', async () => {
    fetchPartnerTerms.mockResolvedValue({ ...TERMS, founder_open: true, founder_until: '2020-01-01T00:00:00Z' });
    await renderPage();

    expect(screen.queryByTestId('partner-founder-banner')).toBeNull();
    expect(screen.queryByTestId('partner-founder-band')).toBeNull();
    expect(visibleText()).not.toMatch(/founding partner/i);
  });

  it('once the founder window has closed, no founder promise is made anywhere', async () => {
    fetchPartnerTerms.mockResolvedValue({ ...TERMS, founder_open: false });
    await renderPage();

    expect(screen.queryByTestId('partner-founder-banner')).toBeNull();
    expect(screen.queryByTestId('partner-founder-band')).toBeNull();
    expect(screen.queryByTestId('partner-founder-shortcut')).toBeNull();
    expect(visibleText()).not.toMatch(/founding partner/i);
    expect(screen.getByTestId('calculator').getAttribute('data-founder')).toBe('false');
  });

  it('without terms it states no figure at all rather than guessing one, and drops the sections built on them', async () => {
    fetchPartnerTerms.mockResolvedValue(null);
    await renderPage();

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Earn a share of every invoice your clients pay');
    // The visible page only: the inlined stylesheet legitimately contains percentages.
    expect(visibleText()).not.toMatch(/\d+\s?%/);
    expect(visibleText({ programOnly: true })).not.toMatch(/\$\d/);
    // The example app keeps its picture: its amounts are invoices, not program figures.
    expect(screen.getByTestId('partner-example-app-screen')).toBeTruthy();
    expect(screen.queryByLabelText('Program terms')).toBeNull();
    expect(screen.queryByTestId('partner-hero-earnings')).toBeNull();
    expect(screen.queryByTestId('partner-scenarios')).toBeNull();
    expect(screen.queryByTestId('partner-tier-track')).toBeNull();
    expect(screen.queryByTestId('calculator')).toBeNull();
    // The benefits still show, without their figures.
    expect(within(screen.getByTestId('partner-benefits')).queryByText('+8,000')).toBeNull();
    expect(screen.getByTestId('apply-section')).toBeTruthy();
  });

  it('with terms but an incomplete tier ladder, no tier and no earning is shown rather than a partial one', async () => {
    fetchPartnerTerms.mockResolvedValue({ ...TERMS, tiers: [] });
    await renderPage();

    expect(screen.queryByTestId('partner-tier-track')).toBeNull();
    expect(screen.queryByTestId('partner-scenarios')).toBeNull();
    expect(screen.queryByTestId('partner-hero-earnings')).toBeNull();
    expect(screen.queryByTestId('calculator')).toBeNull();
    expect(screen.queryByText('How do the tiers work?')).toBeNull();
    // regression: no sentence falls back to a zero rate ("0% to 0%", "0% as Silver", "0% for life").
    expect(visibleText()).not.toMatch(/\b0\s?%/);
    expect(screen.queryByTestId('partner-founder-banner')).toBeNull();
    // The figures that do not depend on the ladder still show.
    expect(screen.getByText('12 months')).toBeTruthy();
    expect(within(screen.getByTestId('partner-benefits')).getByText('+8,000')).toBeTruthy();
  });

  it('the form is on the page itself: every call to action scrolls to it, no detour through the app', async () => {
    await renderPage();

    expect(screen.getByRole('link', { name: 'Become a partner' }).getAttribute('href')).toBe('#apply');
    expect(screen.getByRole('link', { name: /Apply as a founding partner/ }).getAttribute('href')).toBe('#apply');
    expect(screen.getByRole('link', { name: 'See what you could earn' }).getAttribute('href')).toBe('#earnings');
    expect(document.getElementById('apply')?.querySelector('[data-testid="apply-section"]')).toBeTruthy();
    expect(screen.getByText('Applications received before Jan 01, 2100 are also considered for founding partner: 50% from day one.')).toBeTruthy();
    expect(main().innerHTML).not.toContain('/app/settings/partner');
  });

  it('puts the partner app in the real marketplace: the two most used real apps cropped on each side, greyed and unclickable', async () => {
    await renderPage();

    const spotlight = screen.getByTestId('partner-badge-spotlight');
    expect(within(spotlight).getByText('LiveContext marketplace')).toBeTruthy();
    expect(within(spotlight).getByText('Your app')).toBeTruthy();
    // The only listing assistive tech reads is the partner's own.
    expect(within(spotlight).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Invoice Autopilot']);
    const neighbours = within(spotlight).getAllByTestId('partner-spotlight-neighbour');
    // Apps with an icon row only, most used first; the workflow and the icon-less app are left out.
    expect(neighbours.map((n) => n.querySelector('h3')?.textContent)).toEqual(['Content Calendar', 'Lead Scoring Studio']);
    for (const n of neighbours) {
      expect(n.getAttribute('aria-hidden')).toBe('true');
      expect(n.hasAttribute('inert')).toBe(true);
      expect(n.style.filter).toBe('grayscale(1)');
    }
    // Scenery, not destinations: no link anywhere in the spotlight.
    expect(spotlight.querySelector('a')).toBeNull();
    expect(within(spotlight).getAllByRole('img', { name: 'Official LiveContext partner' }).length).toBeGreaterThanOrEqual(2);
  });

  it('zooms in on the partner profile as the real one reads: name, gold badge, tier chip, handle, Subscribe and Message', async () => {
    await renderPage();

    const profile = screen.getByTestId('partner-spotlight-profile');
    expect(within(profile).getByText('Your profile')).toBeTruthy();
    expect(within(profile).getByText('Northwind Automation')).toBeTruthy();
    expect(within(profile).getByRole('img', { name: 'Official LiveContext partner' }).getAttribute('width')).toBe('26');
    expect(within(profile).getByText('@northwind')).toBeTruthy();
    // The labels are the real profile's own ("profile" namespace), not a copy that could drift.
    expect(within(profile).getByText(en.profile.partnerTier.platinum)).toBeTruthy();
    expect(within(profile).getByText(en.profile.follow)).toBeTruthy();
    expect(within(profile).getByText(en.profile.message)).toBeTruthy();
    expect(within(profile).getByText('12 published apps')).toBeTruthy();
    expect(within(profile).getByText('4.9 (41 reviews)')).toBeTruthy();
    // A picture of the actions, not live controls: nothing to click on a marketing page.
    expect(within(profile).queryAllByRole('button')).toHaveLength(0);
  });

  it('shows the check on the marketplace card itself, lit up, and no tier there: the tier lives on the profile only', async () => {
    await renderPage();

    const spotlight = screen.getByTestId('partner-badge-spotlight');
    const card = spotlight.querySelector('.lc-spotlight-card') as HTMLElement;
    expect(card.querySelector('[data-badge="partner"]')).toBeTruthy();
    expect(within(card).queryByText(en.profile.partnerTier.platinum)).toBeNull();
    expect(spotlight.querySelector('style')?.textContent).toContain('.lc-spotlight-card [data-badge="partner"]');
  });

  it('the partner app card shows the app at work, not a bare row of icons: its counters and latest invoices', async () => {
    await renderPage();

    const card = screen.getByTestId('partner-badge-spotlight').querySelector('.lc-spotlight-card') as HTMLElement;
    const app = within(card).getByTestId('partner-example-app-screen');
    const words = en.partnersLanding.badge.screen;
    // A picture: the card's heading and description already say what the app is.
    expect(app.getAttribute('aria-hidden')).toBe('true');
    expect(app.textContent).toContain(words.running);
    expect(app.textContent).toContain(`${words.booked}$48,920`);
    expect(within(app).getAllByText(words.statusBooked)).toHaveLength(3);
    expect(within(app).getByText(words.statusReview)).toBeTruthy();
  });

  it('when the marketplace cannot be read, quiet placeholders stand in for the neighbours, and the page still renders', async () => {
    fetchMarketplace.mockResolvedValue({ publications: [], truncated: true });
    await renderPage();

    const neighbours = within(screen.getByTestId('partner-badge-spotlight')).getAllByTestId('partner-spotlight-neighbour');
    expect(neighbours).toHaveLength(2);
    expect(neighbours.every((n) => n.querySelector('h3') === null)).toBe(true);
    expect(screen.getByRole('heading', { level: 3, name: 'Invoice Autopilot' })).toBeTruthy();
  });

  it('no "Example" labels, but the fictional partner is disclosed next to it (the earnings and reviews it shows are invented)', async () => {
    await renderPage();

    expect(visibleText()).not.toMatch(/\bExample\b/);
    const heroCard = (en as { partnersLanding: { heroCard: { disclosure: string } } }).partnersLanding.heroCard;
    const badge = (en as { partnersLanding: { badge: { disclosure: string } } }).partnersLanding.badge;
    expect(text('partner-hero-disclosure')).toBe(heroCard.disclosure);
    expect(text('partner-hero-disclosure')).toMatch(/fictional partner.*not a promise of results/);
    expect(text('partner-spotlight-disclosure')).toBe(badge.disclosure);
    expect(text('partner-spotlight-disclosure')).toMatch(/fictional/);
  });

  it('founders: "for life" is defined where it is promised, by the Partner Program Terms, never as an unconditional "never"', async () => {
    await renderPage();

    // Earned tiers never go down while the partnership lasts (the tiers section says so), but the
    // founder promise is the one the contract defines: the band says so in its points and fine print.
    const band = screen.getByTestId('partner-founder-band').textContent ?? '';
    expect(band).toContain('Platinum for life, under the Partner Program Terms');
    // The definition names the main way it ends, and the band links the contract itself.
    expect(band).toContain('unless we end it under the Partner Program Terms (for example after 12 months without any commission)');
    expect(band).not.toMatch(/never goes down/i);
    expect(screen.getByTestId('partner-founder-terms').getAttribute('href')).toBe('/legal/partners');
    // The FAQ answer gives the same definition, not another one.
    expect(visibleText()).toContain('keeps it for life: for the whole of their participation, unless it is ended under the Partner Program Terms');
    expect(visibleText()).not.toMatch(/as long as the program runs/);
  });

  it('a partner carries ONE badge, the gold one: never the blue verified check beside it', async () => {
    await renderPage();

    expect(screen.queryByRole('img', { name: 'Verified account' })).toBeNull();
    expect(screen.getAllByRole('img', { name: 'Official LiveContext partner' }).every((img) => img.getAttribute('data-badge') === 'partner')).toBe(true);
  });

  it('shows what else a partner gets with a picture per benefit, its figures read from the terms', async () => {
    await renderPage();

    const benefits = screen.getByTestId('partner-benefits');
    expect(within(benefits).getByText('+8,000')).toBeTruthy();
    expect(within(benefits).getAllByRole('heading', { level: 3 })).toHaveLength(6);
    // The team answers under the company's own mark, not under initials.
    expect(within(benefits).getByTestId('partner-benefits-team-logo').querySelector('svg[viewBox="0 0 1024 1024"]')).toBeTruthy();
    expect(within(benefits).queryByText('LC')).toBeNull();
    // The tiles tell one story: the hero's 30 clients, this month's $3,135 earned, and paid rows
    // only for months already past the 14-day hold (months 11 and 10), never this month's.
    const tracking = within(screen.getByTestId('partner-benefits-tracking'));
    expect(tracking.getByText('30')).toBeTruthy();
    expect(tracking.getByText('$3,135')).toBeTruthy();
    const payout = screen.getByTestId('partner-benefits-payout');
    expect(within(payout).getByText('$2,926')).toBeTruthy();
    expect(within(payout).getByText('$2,613')).toBeTruthy();
    expect(payout.textContent).not.toContain('$3,135');
  });

  it.each(['en', 'fr', 'de', 'es', 'pt', 'zh'])('renders completely in %s, with and without terms', async (locale) => {
    state.locale = locale;
    await renderPage();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/50/);
    expect(screen.getByTestId('partner-tier-track')).toBeTruthy();
    expect(screen.getByTestId('partner-founder-band')).toBeTruthy();
    expect(screen.getByTestId('partner-benefits')).toBeTruthy();
    cleanup();

    fetchPartnerTerms.mockResolvedValue(null);
    await renderPage();
    expect(screen.getByRole('heading', { level: 1 }).textContent).not.toMatch(/50/);

    const meta = await generateMetadata();
    expect(String(meta.title)).not.toMatch(/[-|]\s*LiveContext\s*$/);
    expect(String(meta.description).length).toBeGreaterThan(40);
  });

  it('writes rates, amounts and credit counts the way each language does (a space before % in French)', async () => {
    state.locale = 'fr';
    await renderPage();

    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/jusqu’à 50\s%/);
    expect(within(screen.getByTestId('partner-tier-track')).getByText('30 %')).toBeTruthy();
    expect(text('partner-hero-month')).toMatch(/^3\s135\s\$US$/);
    // The app's screen too: its amounts are written the French way.
    expect(text('partner-example-app-screen')).toMatch(/48\s920\s\$US/);
    expect(within(screen.getByTestId('partner-scenarios')).getByText(/^30 clients en Pro, 250\sk crédits$/)).toBeTruthy();
  });

  it('regression: each language has its own URL, canonical to itself, in one hreflang cluster with an English x-default', async () => {
    state.locale = 'fr';
    const meta = await generateMetadata();

    expect(meta.alternates?.canonical).toBe('/fr/partners');
    expect(meta.alternates?.languages).toEqual({
      en: 'https://livecontext.ai/partners',
      fr: 'https://livecontext.ai/fr/partners',
      es: 'https://livecontext.ai/es/partners',
      de: 'https://livecontext.ai/de/partners',
      pt: 'https://livecontext.ai/pt/partners',
      zh: 'https://livecontext.ai/zh/partners',
      'x-default': 'https://livecontext.ai/partners',
    });
    const og = meta.openGraph as Record<string, unknown>;
    expect(og.url).toBe('https://livecontext.ai/fr/partners');
    expect(og.locale).toBe('fr_FR');
    expect(og.alternateLocale).not.toContain('fr_FR');

    state.locale = 'en';
    expect((await generateMetadata()).alternates?.canonical).toBe('/partners');
  });

  it('the search snippet quotes the top rate the terms pay, with its percent sign, stays under 155 characters, and names no figure without terms', async () => {
    // regression: the rate is formatted as a bare number, and the snippet read "up to 50 of every invoice".
    const rate: Record<string, string> = { en: 'up to 50% of', fr: 'jusqu’à 50 % de', de: 'bis zu 50 % jeder', es: 'hasta un 50 % de', pt: 'até 50% de', zh: '最多获得 50% 分成' };
    for (const locale of ['en', 'fr', 'de', 'es', 'pt', 'zh']) {
      state.locale = locale;
      fetchPartnerTerms.mockResolvedValue(TERMS);
      const withRate = String((await generateMetadata()).description);
      expect(withRate, locale).toContain(rate[locale]);
      expect(withRate.length, locale).toBeLessThanOrEqual(155);

      fetchPartnerTerms.mockResolvedValue(null);
      const without = String((await generateMetadata()).description);
      expect(without, locale).not.toMatch(/\d/);
      expect(without.length, locale).toBeLessThanOrEqual(155);
    }
  });

  it('regression: the cloud page sets no robots of its own, so the site-wide directives (large image previews) apply', async () => {
    expect('robots' in (await generateMetadata())).toBe(false);
  });

  it('describes itself to search engines: a WebPage in the page language and a two-step breadcrumb', async () => {
    state.locale = 'de';
    await renderPage();

    const block = document.querySelector('script[type="application/ld+json"]');
    expect(block).not.toBeNull();
    const graph = JSON.parse(block!.textContent ?? '{}')['@graph'];
    const page = graph.find((node: { '@type': string }) => node['@type'] === 'WebPage');
    expect(page.url).toBe('https://livecontext.ai/de/partners');
    expect(page.inLanguage).toBe('de');
    const crumbs = graph.find((node: { '@type': string }) => node['@type'] === 'BreadcrumbList').itemListElement;
    expect(crumbs.map((c: { item: string }) => c.item)).toEqual(['https://livecontext.ai/de', 'https://livecontext.ai/de/partners']);
  });

  it('does not exist on a self-hosted build, and is never indexed there', async () => {
    state.isCe = true;

    await expect(PartnersPage()).rejects.toThrow('NOT_FOUND');
    expect((await generateMetadata()).robots).toEqual({ index: false, follow: false });
    expect(fetchPartnerTerms).not.toHaveBeenCalled();
    expect(fetchMarketplace).not.toHaveBeenCalled();
  });
});
