/**
 * @vitest-environment jsdom
 *
 * The sidebar ring and the wallet card describe ONE wallet. This file renders
 * both for the same account and refuses to let them disagree.
 *
 * It exists because they did. When the plan percentage first moved out of the
 * user menu it landed on the wallet card's Subscription bar, whose numerator is
 * the subscription BUCKET rather than the wallet. Every test in both suites was
 * green, because each suite only ever renders one of the two surfaces - and a
 * user holding a 10,000 grant plus a 5,000 top-up saw a gold "+50% over your
 * plan" in the sidebar and "100% of your plan remaining" on the page that gold
 * points them to.
 *
 * A per-component test cannot see that by construction, which is the whole
 * argument for this file: the invariant lives BETWEEN two components, so it
 * needs a test that holds both.
 */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';
import { computeCreditGauge, resolveMonthlyAllowance } from '@/lib/billing/credit-allowance';
import { resetCreditRingRevealForTests } from '@/lib/billing/credit-ring-reveal';

const mocks = vi.hoisted(() => ({ useCreditWallet: vi.fn(), isCe: { value: false } }));
vi.mock('@/lib/hooks/useCreditWallet', () => ({ useCreditWallet: mocks.useCreditWallet }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return mocks.isCe.value;
  },
}));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href, ...rest }: any) => (
    <a href={typeof href === 'string' ? href : '#'} {...rest}>
      {children}
    </a>
  ),
}));

import { SidebarCreditRing } from '../SidebarCreditBalance';
import { BalanceBreakdownCard } from '../BalanceBreakdown';

/**
 * One wallet, described the way each surface receives it.
 *
 * `allowance` is resolved through the SAME helper the app uses rather than
 * hardcoded, so a change to what a plan grants cannot make this file assert a
 * denominator the product no longer uses.
 */
interface Wallet {
  name: string;
  subBalance: number;
  paygBalance: number;
  planCode: string | null;
  creditTierIndex?: number;
}

const WALLETS: Wallet[] = [
  { name: 'a healthy FREE account', subBalance: 980, paygBalance: 0, planCode: 'FREE' },
  { name: 'a spent FREE account', subBalance: 0, paygBalance: 0, planCode: 'FREE' },
  // The case that was broken: the grant is untouched and a top-up sits on top,
  // so the wallet is over its cycle grant while the BUCKET is exactly at it.
  { name: 'a full grant plus a top-up', subBalance: 10_000, paygBalance: 5_000, planCode: 'PRO', creditTierIndex: 1 },
  // The mirror image: the grant is gone and only the top-up is left, so the
  // bucket is empty while the wallet is not.
  { name: 'a spent grant with a top-up left', subBalance: 0, paygBalance: 400, planCode: 'PRO', creditTierIndex: 1 },
  { name: 'a wallet at exactly its grant', subBalance: 10_000, paygBalance: 0, planCode: 'PRO', creditTierIndex: 1 },
  { name: 'a wallet one credit over', subBalance: 10_000, paygBalance: 1, planCode: 'PRO', creditTierIndex: 1 },
];

const withIntl = (node: React.ReactNode) =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      {node}
    </NextIntlClientProvider>,
  );

beforeEach(() => {
  mocks.isCe.value = false;
  resetCreditRingRevealForTests();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function surfaces(wallet: Wallet) {
  const balance = wallet.subBalance + wallet.paygBalance;
  const allowance = resolveMonthlyAllowance(wallet.planCode, wallet.creditTierIndex ?? 0);
  mocks.useCreditWallet.mockReturnValue({
    balance,
    subBalance: wallet.subBalance,
    paygBalance: wallet.paygBalance,
    allowance,
    gauge: computeCreditGauge(balance, allowance),
    isLoading: false,
  });

  withIntl(
    <SidebarCreditRing>
      <img data-testid="the-avatar" src="/a.png" alt="Owner" />
    </SidebarCreditRing>,
  );
  const ringLabel = screen.getByTestId('sidebar-credit-avatar').getAttribute('aria-label') ?? '';
  cleanup();

  withIntl(
    <BalanceBreakdownCard
      balance={balance}
      subBalance={wallet.subBalance}
      paygBalance={wallet.paygBalance}
      monthlyPlan={allowance === null ? undefined : { allowance }}
    />,
  );
  const cardShare = screen.getByTestId('wallet-plan-share');

  return { ringLabel, cardText: cardShare.textContent ?? '', cardIsGold: cardShare.style.color !== '' };
}

/** "96% of your plan used" -> 96. "+50% over your plan" -> 50. */
const figure = (sentence: string): number | null => {
  const match = /(-?[\d,]+)%/.exec(sentence);
  return match ? Number(match[1].replace(/,/g, '')) : null;
};

describe.each(WALLETS)('$name', (wallet) => {
  it('has the ring and the wallet card agree on whether the plan is exceeded', () => {
    // The ring's gold and the card's gold are the same claim. One saying "over"
    // while the other reports a remaining share is the exact contradiction that
    // shipped: same account, same screen, opposite statements.
    const { ringLabel, cardText, cardIsGold } = surfaces(wallet);

    const ringSaysOver = /over your plan|Just over/.test(ringLabel);
    const cardSaysOver = /over your plan|Just over/.test(cardText);

    expect(cardSaysOver).toBe(ringSaysOver);
    expect(cardIsGold).toBe(ringSaysOver);
  });

  it('has them state complementary figures for the same wallet', () => {
    // Under the plan the ring names the CONSUMED share and the card the
    // REMAINING one, so they must sum to 100 - each is right beside a figure of
    // its own kind. Over the plan they name the same surplus, so they must be
    // equal. Either way, one number: a numerator that drifted would break both.
    const { ringLabel, cardText } = surfaces(wallet);
    const ring = figure(ringLabel);
    const card = figure(cardText);

    // "Just over your plan" carries no figure on either side; the test above
    // already pinned that both surfaces are in that state together.
    if (ring === null || card === null) {
      expect(figure(ringLabel)).toBe(figure(cardText));
      return;
    }

    if (/over your plan/.test(cardText)) {
      expect(card).toBe(ring);
    } else {
      expect(card + ring).toBe(100);
    }
  });
});
