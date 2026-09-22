// @vitest-environment jsdom
/**
 * The Billing page states TWO dates, and the reason it has to is a yearly subscription.
 *
 * <p>"Next billing" was the only date on this page, so it was read as the answer to "when do
 * my credits come back". On a yearly plan that answer is eleven months wrong for most of the
 * year: the invoice is annual and the credit pack is granted monthly. Both dates are now
 * drawn, each labelled for what it is, and the credit one is whatever the backend named
 * ({@code nextCreditGrantAt}) rather than anything this page derives.
 *
 * <p>What these tests pin: the row is drawn for every plan that is owed a grant (a FREE
 * account's monthly reset included, which "next billing" never mentions), it is silent when
 * no grant is owed, and it never invents an amount for a plan that has none.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';

const state = vi.hoisted(() => ({
  subscription: null as Record<string, unknown> | null,
  isOwner: true,
  scheduledChange: null as Record<string, unknown> | null,
}));

vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({
    subscription: { subscription: state.subscription },
    isLoading: false,
    forceLoadSubscription: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useIsCurrentOrgOwner: () => state.isOwner,
  // OwnerOnlyBillingAction reads the store directly to detect the pre-hydration frame.
  // A non-null role means "hydrated", so the footer actions render as they do in the app.
  useCurrentOrgStore: (selector: (s: { currentOrgRole: string | null }) => unknown) =>
    selector({ currentOrgRole: 'OWNER' }),
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
  // The only query left on the page is the invoice list, which the row under test does not
  // read. The scheduled change now arrives through its own hook, mocked below, which is the
  // same hook the wallet card's page uses so the rule has one implementation.
  useQuery: () => ({ data: undefined, isLoading: false, refetch: vi.fn() }),
}));
vi.mock('@/lib/hooks/useScheduledPlanChange', () => ({
  useScheduledPlanChange: () => ({
    hasScheduledChange: state.scheduledChange !== null,
    scheduledChange: state.scheduledChange ?? undefined,
    isLoading: false,
  }),
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getInvoices: vi.fn(), getScheduledChange: vi.fn() },
}));
vi.mock('@/components/billing', () => ({ CancellationModal: () => null }));
vi.mock('@/components/settings/PageHeader', () => ({ default: () => null }));
vi.mock('@/components/skeletons/SettingsSkeletons', () => ({
  SettingsPageSkeleton: () => <div data-testid="skeleton" />,
}));

import BillingPage from '../page';

/** 2026-10-14, the shape /billing/me serves: a LocalDateTime with no zone designator. */
const CREDITS_BACK = '2026-10-14T23:53:09';
const NEXT_INVOICE = '2027-09-14T23:53:09';

function renderPage(subscription: Record<string, unknown> | null) {
  state.subscription = subscription;
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <BillingPage />
    </NextIntlClientProvider>,
  );
}

const creditsRow = () => screen.queryByTestId('billing-credits-refresh');

beforeEach(() => {
  state.isOwner = true;
  state.scheduledChange = null;
});
afterEach(cleanup);

describe('the Billing page separates the invoice date from the credit date', () => {
  it('REGRESSION: a yearly plan shows the monthly credit date beside its annual invoice date', () => {
    renderPage({
      planCode: 'TEAM',
      planName: 'Team',
      cadence: 'yearly',
      status: 'active',
      cancelAtPeriodEnd: false,
      currentPeriodEnd: NEXT_INVOICE,
      nextCreditGrantAt: CREDITS_BACK,
      creditTierIndex: 4,
    });

    // The invoice is a year out; the credits are a month out. Before this row, only the
    // first was on screen and it was the one people planned against.
    expect(screen.getByText(/Next billing: Sep 14, 2027/)).toBeInTheDocument();
    expect(creditsRow()).toHaveTextContent('Credits refresh: Oct 14, 2026');
    // "per cycle", not "+": this row has no popover to qualify a plus sign with, and the
    // renewal REPLACES the balance rather than adding to it.
    expect(creditsRow()).toHaveTextContent('100,000 credits per cycle');
    expect(creditsRow()!.textContent).not.toContain('+');
  });

  it('draws the row on a FREE plan too, where no billing date is shown at all', () => {
    // A free account's monthly reset is a real grant, and this page said nothing about it:
    // the "next billing" line is suppressed for FREE.
    renderPage({
      planCode: 'FREE',
      planName: 'Free',
      cadence: 'monthly',
      status: 'active',
      cancelAtPeriodEnd: false,
      currentPeriodEnd: CREDITS_BACK,
      nextCreditGrantAt: CREDITS_BACK,
      creditTierIndex: 0,
    });

    expect(screen.queryByText(/Next billing/)).not.toBeInTheDocument();
    expect(creditsRow()).toHaveTextContent('Credits refresh: Oct 14, 2026');
    expect(creditsRow()).toHaveTextContent('1,000 credits per cycle');
  });

  it('draws the row on a monthly plan, where the two dates deliberately coincide', () => {
    // Not conditioned on the dates DIFFERING: a monthly subscriber reading the same date
    // twice learns that the two events are one, where a row that appeared only for yearly
    // customers would leave everyone else inferring.
    renderPage({
      planCode: 'PRO',
      planName: 'Pro',
      cadence: 'monthly',
      status: 'active',
      cancelAtPeriodEnd: false,
      currentPeriodEnd: CREDITS_BACK,
      nextCreditGrantAt: CREDITS_BACK,
      creditTierIndex: 1,
    });

    expect(screen.getByText(/Next billing: Oct 14, 2026/)).toBeInTheDocument();
    expect(creditsRow()).toHaveTextContent('Credits refresh: Oct 14, 2026');
    expect(creditsRow()).toHaveTextContent('10,000 credits per cycle');
  });

  it('says nothing when the backend named no date - a cancelling MONTHLY plan is owed no grant', () => {
    renderPage({
      planCode: 'PRO',
      planName: 'Pro',
      cadence: 'monthly',
      status: 'active',
      cancelAtPeriodEnd: true,
      currentPeriodEnd: CREDITS_BACK,
      nextCreditGrantAt: null,
      creditTierIndex: 1,
    });

    // The absence of a date is the ONLY gate: the page does not read the cancel flag itself,
    // because a cancelling yearly row is still owed monthly packs (see the case above).
    expect(screen.getByText(/Access ends on Oct 14, 2026/)).toBeInTheDocument();
    expect(creditsRow()).toBeNull();
  });

  it('REGRESSION: a cancelling YEARLY subscriber still sees the packs the paid year owes them', () => {
    // The backend keeps naming the monthly cycle for these rows because the scheduler keeps
    // granting it, so the page must not suppress the row on the cancel flag of its own
    // accord. "Do I lose this month's credits if I cancel?" is the question, and the answer
    // is drawn right under the sentence that says when access ends.
    renderPage({
      planCode: 'TEAM',
      planName: 'Team',
      cadence: 'yearly',
      status: 'active',
      cancelAtPeriodEnd: true,
      currentPeriodEnd: NEXT_INVOICE,
      nextCreditGrantAt: CREDITS_BACK,
      creditTierIndex: 4,
    });

    expect(screen.getByText(/Access ends on Sep 14, 2027/)).toBeInTheDocument();
    expect(creditsRow()).toHaveTextContent('Credits refresh: Oct 14, 2026');
  });

  it('says nothing under a SCHEDULED CHANGE, whose target tier it cannot price', () => {
    // The amount on this row is the CURRENT tier. With a credit-tier change scheduled, the
    // grant on that date is the TARGET tier, so printing this one is a wrong number about
    // money. The page's own precedence (scheduled change first) already says to stand down,
    // and the banner names the change and its date.
    state.scheduledChange = { effectiveDate: CREDITS_BACK, changeType: 'credit_tier_change' };
    renderPage({
      planCode: 'TEAM',
      planName: 'Team',
      cadence: 'monthly',
      status: 'active',
      cancelAtPeriodEnd: false,
      currentPeriodEnd: CREDITS_BACK,
      nextCreditGrantAt: CREDITS_BACK,
      creditTierIndex: 4,
    });

    expect(screen.getByText(/A plan change is scheduled/)).toBeInTheDocument();
    expect(creditsRow()).toBeNull();
  });

  it('names the date without an amount when the plan grant is unknown', () => {
    // An unrecognised plan code resolves to no allowance at all. The date is still true and
    // still worth stating; inventing a number beside it would not be.
    renderPage({
      planCode: 'SOMETHING_NEW',
      planName: 'Something New',
      cadence: 'monthly',
      status: 'active',
      cancelAtPeriodEnd: false,
      currentPeriodEnd: CREDITS_BACK,
      nextCreditGrantAt: CREDITS_BACK,
      creditTierIndex: 99,
    });

    expect(creditsRow()).toHaveTextContent('Credits refresh: Oct 14, 2026');
    // Asserted on the AMOUNT, not on the word: `not.toContain('credits')` passed only because
    // the row's other label happens to be capitalised, so a copy tweak would have turned a
    // healthy build red for no reason.
    expect(creditsRow()!.textContent).not.toMatch(/\d[\d,.\s]*\s*credits/i);
  });

  it('says nothing rather than "Credits refresh: -" when the date cannot be read', () => {
    // safeDate, which every other date on this card goes through, cannot express "render
    // nothing": its fallback is read as `|| '-'`. A row whose entire content is one date has
    // nothing left to say once that date is unreadable.
    renderPage({
      planCode: 'PRO',
      planName: 'Pro',
      cadence: 'monthly',
      status: 'active',
      cancelAtPeriodEnd: false,
      currentPeriodEnd: CREDITS_BACK,
      nextCreditGrantAt: 'not-a-date',
      creditTierIndex: 1,
    });

    expect(creditsRow()).toBeNull();
  });

  it('ignores a non-string credit date instead of handing it to a formatter', () => {
    renderPage({
      planCode: 'PRO',
      planName: 'Pro',
      cadence: 'monthly',
      status: 'active',
      cancelAtPeriodEnd: false,
      currentPeriodEnd: CREDITS_BACK,
      nextCreditGrantAt: [2026, 10, 14] as unknown as string,
      creditTierIndex: 1,
    });

    expect(creditsRow()).toBeNull();
  });

  it('stays silent on an older payload that carries no credit date at all', () => {
    // A frontend deployed ahead of the backend reads undefined here. Nothing may be
    // substituted for it: currentPeriodEnd is the wrong date on exactly the plan that
    // needed this row.
    renderPage({
      planCode: 'PRO',
      planName: 'Pro',
      cadence: 'yearly',
      status: 'active',
      cancelAtPeriodEnd: false,
      currentPeriodEnd: NEXT_INVOICE,
      creditTierIndex: 1,
    });

    expect(creditsRow()).toBeNull();
  });
});
