/**
 * @vitest-environment jsdom
 *
 * Render tests for the credit-balance kit: the Balance panel (Monthly grant /
 * Remaining / optional Upgrade CTA), the ring dial, and the avatar ring's
 * opening sweep - in both the ordinary "under allowance" state and the gold
 * "over allowance" state.
 *
 * These assert what the maths tests cannot see: that the gold state actually
 * PAINTS gold, that each action appears only where a caller wires it, that the
 * readout itself is the link to the usage page, and that the ring draws itself
 * rather than appearing already finished.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import React from 'react';
import { render, screen, cleanup, fireEvent, createEvent, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';


// The app's locale-aware Link, stubbed the way every other suite stubs it: the
// real one pulls in next/navigation, which does not resolve under vitest. The
// stub records the href it is handed, which is the half this component owns -
// the locale prefix is the Link's own job.
const linkProps = vi.hoisted(() => ({ last: null as Record<string, unknown> | null }));

// The OS motion setting, made drivable. Mocked at the hook rather than through
// window.matchMedia: the hook subscribes with useSyncExternalStore, so faking
// the media query would also have to fake a subscription that re-renders, and
// the thing under test here is the ring's response to the answer, not the
// plumbing that fetches it (which has its own home in the changelog suite).
const reducedMotion = vi.hoisted(() => ({ value: false }));
vi.mock('@/hooks/usePrefersReducedMotion', () => ({
  usePrefersReducedMotion: () => reducedMotion.value,
}));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href, ...rest }: any) => {
    linkProps.last = { href, ...rest };
    return (
      <a href={typeof href === 'string' ? href : '#'} {...rest}>
        {children}
      </a>
    );
  },
}));

import { CreditAvatarRing, CreditBalancePanel, CreditRing, CreditRingBadge } from '../CreditBalance';
import { computeCreditGauge } from '@/lib/billing/credit-allowance';

const withIntl = (ui: React.ReactElement) =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      {ui}
    </NextIntlClientProvider>,
  );

/**
 * The panel's dial is its header mini-ring, and the arc is the SECOND circle:
 * the first is the track, which is drawn at full length in every state.
 */
const panelArc = (container: HTMLElement): SVGCircleElement | null =>
  (container.querySelectorAll('circle')[1] as SVGCircleElement | undefined) ?? null;

/** How far round the dial the arc is drawn, 0-1. */
const arcFraction = (arc: SVGCircleElement): number => {
  const [dash, circumference] = (arc.getAttribute('stroke-dasharray') || '').split(' ').map(Number);
  return dash / circumference;
};

describe('CreditBalancePanel - under allowance', () => {
  afterEach(cleanup);

  it('shows the plan grant as Total and the wallet as Remaining', () => {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );

    expect(screen.getByText('Balance')).toBeTruthy();
    expect(screen.getByText('10,000 credits')).toBeTruthy();
    expect(screen.getByTestId('balance-remaining').textContent).toBe('9,779');
  });

  it('draws the dial by the CONSUMED share, not the remaining one', () => {
    // 9,779 of 10,000 left is 2% used - the arc must be nearly absent, which is
    // the opposite of what drawing by "remaining" would paint.
    const { container } = withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    expect(arcFraction(panelArc(container)!)).toBeCloseTo(0.02, 3);
  });

  it('states NO plan percentage: that sentence lives on the wallet card now', () => {
    // The panel used to carry a second bar under the figures with "X% of your
    // plan used" written beneath it. A menu is a place people pass through, and
    // it was saying the same thing three ways in 200px. Pinned as an ABSENCE,
    // because the removal is the behaviour: re-adding the bar has to be a
    // deliberate act that fails here first.
    const { container } = withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    expect(screen.queryByTestId('balance-gauge-fill')).toBeNull();
    expect(screen.queryByTestId('balance-gauge-label')).toBeNull();
    expect(container.textContent).not.toMatch(/% of your plan/i);
  });

  it('hides Total and the dial entirely when there is no allowance to gauge against', () => {
    const { container } = withIntl(
      <CreditBalancePanel balance={420} allowance={null} gauge={computeCreditGauge(420, null)} />,
    );
    expect(screen.queryByTestId('balance-total-label')).toBeNull();
    expect(container.querySelectorAll('circle')).toHaveLength(0);
    // Remaining still shows: it is a fact about the wallet, not about a plan.
    expect(screen.getByTestId('balance-remaining').textContent).toBe('420');
  });
});

describe('CreditBalancePanel - over allowance (gold)', () => {
  afterEach(cleanup);

  it('paints the dial gold instead of ink', () => {
    const { container } = withIntl(
      <CreditBalancePanel balance={1_400} allowance={1_000} gauge={computeCreditGauge(1_400, 1_000)} />,
    );
    const arc = panelArc(container)!;
    expect(arc.getAttribute('stroke')).toBe('var(--credit-gold-arc)');
    // The ink class must be gone, or the gold would be drawn under a black arc.
    expect(arc.getAttribute('class') ?? '').not.toContain('stroke-black');
  });

  it('leaves the dial ink while the wallet is inside its grant', () => {
    // The counterpart of the case above: painting gold under the grant would
    // announce a surplus that does not exist.
    const { container } = withIntl(
      <CreditBalancePanel balance={500} allowance={1_000} gauge={computeCreditGauge(500, 1_000)} />,
    );
    expect(panelArc(container)!.getAttribute('stroke')).toBeNull();
    expect(panelArc(container)!.getAttribute('class') ?? '').toContain('stroke-black');
  });

  it('draws the dial by the SURPLUS above the grant, capped at the full circle', () => {
    // 1,400 on a 1,000 grant is +40%, so the arc is 40% round; 35,000 is
    // +3,400%, which a circle cannot draw and therefore pins at full.
    const { container: small } = withIntl(
      <CreditBalancePanel balance={1_400} allowance={1_000} gauge={computeCreditGauge(1_400, 1_000)} />,
    );
    expect(arcFraction(panelArc(small)!)).toBeCloseTo(0.4, 3);

    cleanup();
    const { container: huge } = withIntl(
      <CreditBalancePanel balance={35_000} allowance={1_000} gauge={computeCreditGauge(35_000, 1_000)} />,
    );
    expect(arcFraction(panelArc(huge)!)).toBeCloseTo(1, 3);
  });

  it('marks the over state with a "+" glyph, not with the gold alone', () => {
    // The panel's 22px dial is one of only two surfaces that render in
    // production, and this glyph is the whole non-colour half of the signal: an
    // exhausted wallet and a wallet at double its grant both fill the dial
    // completely, so hue is the only other thing separating them. Passing
    // `showLabel={false}` here - which is what it used to do - is a legibility
    // regression that nothing else in this suite can see.
    withIntl(
      <CreditBalancePanel balance={1_400} allowance={1_000} gauge={computeCreditGauge(1_400, 1_000)} />,
    );
    const glyph = screen.getByTestId('credit-ring-label');
    expect(glyph.textContent).toBe('+');
    // And it is inked with the per-theme token, not a literal: the bright metal
    // that reads on the dark ground sits at 1.8:1 on white.
    expect(glyph.style.color).toContain('--credit-gold-ink');
  });

  it('draws NO glyph at that size while the wallet is inside its grant', () => {
    // The asymmetry is deliberate. A percentage needs 26px to be legible and
    // this dial is 22, so the ink state stays bare - the figures beside it are
    // what carry the ordinary case.
    withIntl(
      <CreditBalancePanel balance={500} allowance={1_000} gauge={computeCreditGauge(500, 1_000)} />,
    );
    expect(screen.queryByTestId('credit-ring-label')).toBeNull();
  });

  it('spells no surplus sentence either: the figures are what carry it here', () => {
    // "Monthly grant 1,000 / Remaining 1,400" already says the wallet is above
    // its grant, without colour. The sentence itself is on the wallet card and
    // in the ring's accessible name.
    withIntl(
      <CreditBalancePanel balance={1_400} allowance={1_000} gauge={computeCreditGauge(1_400, 1_000)} />,
    );
    expect(screen.queryByText(/over your plan/i)).toBeNull();
    expect(screen.getByTestId('balance-remaining').textContent).toBe('1,400');
  });
});

describe('CreditBalancePanel - Upgrade CTA', () => {
  afterEach(cleanup);

  it('is absent unless the caller wires it', () => {
    withIntl(
      <CreditBalancePanel balance={500} allowance={1_000} gauge={computeCreditGauge(500, 1_000)} />,
    );
    expect(screen.queryByTestId('balance-upgrade')).toBeNull();
  });

  it('renders for the menu copy, which does wire it', () => {
    withIntl(
      <CreditBalancePanel
        balance={500}
        allowance={1_000}
        gauge={computeCreditGauge(500, 1_000)}
        onUpgrade={() => {}}
      />,
    );
    expect(screen.getByTestId('balance-upgrade').textContent).toBe('Upgrade');
  });
});

describe('CreditBalancePanel - the readout IS the route to usage', () => {
  afterEach(() => {
    cleanup();
    linkProps.last = null;
  });

  // The panel used to end with a separate "View usage" link. The figures are
  // what a reader points at when they want to know where the credits went, so
  // they carry the navigation now and the extra link is gone.

  /** A selection that begins inside `node`, which is what a drag leaves behind. */
  const selectionStartingIn = (node: Node) =>
    vi
      .spyOn(window, 'getSelection')
      .mockReturnValue({ isCollapsed: false, anchorNode: node } as unknown as Selection);

  const wired = (over: { onNavigate?: () => void; allowance?: number | null; balance?: number } = {}) => {
    const balance = over.balance ?? 9_779;
    const allowance = over.allowance === undefined ? 10_000 : over.allowance;
    const onNavigate = over.onNavigate ?? vi.fn();
    withIntl(
      <CreditBalancePanel
        balance={balance}
        allowance={allowance}
        gauge={computeCreditGauge(balance, allowance)}
        viewUsage={{ href: '/app/settings/quota', onNavigate }}
      />,
    );
    return { onNavigate, readout: () => screen.getByTestId('balance-view-usage') };
  };

  it('routes client-side when a figure row is clicked', () => {
    const onNavigate = vi.fn();
    wired({ onNavigate });

    const label = screen.getByTestId('balance-remaining-label');
    const click = createEvent.click(label, { bubbles: true });
    fireEvent(label, click);

    expect(onNavigate).toHaveBeenCalledTimes(1);
    // The app owns the plain click, so the browser must not also follow the href.
    expect(click.defaultPrevented).toBe(true);
  });

  it('is a REAL link, so the usage page can be opened in a new tab or copied', () => {
    // This readout is the only route to that page from the cloud user menu, so
    // it carries the URL rather than only a handler.
    const { readout } = wired();
    expect(readout().tagName).toBe('A');
    expect(readout().getAttribute('href')).toBe('/app/settings/quota');
    // Text selection over the figures, rather than picking the link up.
    expect(readout().getAttribute('draggable')).toBe('false');
  });

  it("hands the app's Link the plain path, instead of building the URL itself", () => {
    // An unprefixed /app URL is redirected to a hardcoded /en, so a French
    // reader opening this in a new tab would land on the English page. The fix
    // is to route it through the app's locale-aware Link and let that add the
    // prefix - never to concatenate one here.
    wired();

    expect(linkProps.last?.href).toBe('/app/settings/quota');
  });

  it('routes client-side on a plain pointer click, selection collapsed', () => {
    // The ordinary gesture, distinct from the keyboard path above: a click with
    // detail 1 and nothing selected must reach the app's navigation.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate });
    const selection = vi
      .spyOn(window, 'getSelection')
      .mockReturnValue({ isCollapsed: true, anchorNode: readout() } as unknown as Selection);

    fireEvent(readout(), createEvent.click(readout(), { bubbles: true, detail: 1 }));

    expect(onNavigate).toHaveBeenCalledTimes(1);
    selection.mockRestore();
  });

  it.each(['ctrlKey', 'metaKey', 'shiftKey', 'altKey'])(
    'leaves a %s-click to the browser instead of swallowing it',
    (modifier) => {
      // One per flag: cmd is the macOS new-tab gesture, and a handler that drops
      // it while keeping ctrl would ship green on a suite testing only ctrl.
      const onNavigate = vi.fn();
      const { readout } = wired({ onNavigate });

      const click = createEvent.click(readout(), { bubbles: true, [modifier]: true });
      fireEvent(readout(), click);

      expect(onNavigate).not.toHaveBeenCalled();
      expect(click.defaultPrevented).toBe(false);
    },
  );

  it('does not navigate when the reader was selecting the figures to copy', () => {
    // A drag that ends inside the link still fires a click, and these are
    // numbers people copy: highlighting the balance must not route away.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate });
    const selection = selectionStartingIn(readout());

    // detail 1 = a pointer click, which is the gesture a drag ends with.
    fireEvent(readout(), createEvent.click(readout(), { bubbles: true, detail: 1 }));

    expect(onNavigate).not.toHaveBeenCalled();
    selection.mockRestore();
  });

  it('still activates from the keyboard while a selection is lying around', () => {
    // Keyboard activation fires a click with detail 0. Treating it like a drag
    // would turn a stale selection anywhere on the page into a dead link.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate });
    const selection = selectionStartingIn(readout());

    fireEvent(readout(), createEvent.click(readout(), { bubbles: true, detail: 0 }));

    expect(onNavigate).toHaveBeenCalledTimes(1);
    selection.mockRestore();
  });

  it('still navigates when the selection began elsewhere, a select-all included', () => {
    // A page-wide selection CONTAINS this link, so a containment test would
    // leave that reader with a link that does nothing at all. Only a selection
    // that started inside it is someone copying the balance.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate });
    const selection = selectionStartingIn(document.body);

    fireEvent(readout(), createEvent.click(readout(), { bubbles: true, detail: 1 }));

    expect(onNavigate).toHaveBeenCalledTimes(1);
    selection.mockRestore();
  });

  it('wraps BOTH figure rows, not just one of them', () => {
    // Clicking the number a reader is looking at has to work too - the whole
    // readout is the target, which is what makes it findable without a link.
    // Both rows, because either one alone leaves the other a dead patch inside
    // a rectangle that highlights on hover.
    const { readout } = wired();
    expect(readout().contains(screen.getByTestId('balance-remaining'))).toBe(true);
    expect(readout().contains(screen.getByTestId('balance-total-label'))).toBe(true);
  });

  it('leaves the Upgrade CTA OUTSIDE the readout, so one click cannot fire both', () => {
    // The refactor made this newly possible: a CTA moved inside the frame would
    // navigate to pricing AND to usage from a single press.
    const onUpgrade = vi.fn();
    const onNavigate = vi.fn();
    withIntl(
      <CreditBalancePanel
        balance={500}
        allowance={1_000}
        gauge={computeCreditGauge(500, 1_000)}
        onUpgrade={onUpgrade}
        viewUsage={{ href: '/app/settings/quota', onNavigate }}
      />,
    );

    const readout = screen.getByTestId('balance-view-usage');
    const upgrade = screen.getByTestId('balance-upgrade');
    expect(readout.contains(upgrade)).toBe(false);

    upgrade.click();
    expect(onUpgrade).toHaveBeenCalledTimes(1);
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('keeps the bucket rows outside it, where the hover surface stops at the rule', () => {
    const onNavigate = vi.fn();
    withIntl(
      <CreditBalancePanel
        balance={1_400}
        allowance={1_000}
        gauge={computeCreditGauge(1_400, 1_000)}
        subBalance={1_000}
        paygBalance={400}
        viewUsage={{ href: '/app/settings/quota', onNavigate }}
      />,
    );

    const readout = screen.getByTestId('balance-view-usage');
    expect(readout.contains(screen.getByTestId('bucket-payg'))).toBe(false);
  });

  it('does not let the click reach whatever the panel is dropped into', () => {
    // Its home is a portalled menu, where React events bubble along the React
    // tree: a container must not receive this click as its own.
    const onAncestorClick = vi.fn();
    withIntl(
      <div onClick={onAncestorClick}>
        <CreditBalancePanel
          balance={500}
          allowance={1_000}
          gauge={computeCreditGauge(500, 1_000)}
          viewUsage={{ href: '/app/settings/quota', onNavigate: () => {} }}
        />
      </div>,
    );

    screen.getByTestId('balance-remaining-label').click();
    expect(onAncestorClick).not.toHaveBeenCalled();
  });

  it('stays a link with no allowance, where there is no dial to aim at', () => {
    // A guest's wallet has no denominator, so nothing is drawn - the figures
    // still have to lead somewhere.
    const onNavigate = vi.fn();
    const { readout } = wired({ onNavigate, allowance: null, balance: 42_000 });

    expect(screen.queryByTestId('balance-total-label')).toBeNull();
    expect(readout().getAttribute('href')).toBe('/app/settings/quota');
    screen.getByTestId('balance-remaining').click();
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it('names the action for a screen reader without hiding the figures', () => {
    // An aria-label would REPLACE the contents for the accessible name, so the
    // numbers a speech-input user reads on screen would not be in it. The name
    // is built from the contents instead, with the action hidden visually only.
    const { readout } = wired({ balance: 500, allowance: 1_000 });

    const name = readout().getAttribute('aria-label') ?? readout().textContent ?? '';
    expect(name.startsWith('View usage')).toBe(true);
    expect(name).toContain('500');
    expect(screen.getByText('View usage').className).toContain('sr-only');
  });

  it('shows the keyboard where it is, on a surface that matches the rows below', () => {
    // Both were regressions once: no focus ring on a link this menu depends on,
    // and a hover fill louder than the menu rows underneath it.
    const { readout } = wired();
    expect(readout().className).toContain('focus-visible:ring-2');
    expect(readout().className).toContain('focus-visible:ring-offset-1');
    expect(readout().className).toContain('hover:bg-gray-100');
    expect(readout().className).toContain('dark:hover:bg-gray-800');
    // Same corner as the menu rows the rectangle is aligned to.
    expect(readout().className).toContain('rounded-xl');
  });

  it('adds no control at all when the caller wires nowhere to go', () => {
    withIntl(
      <CreditBalancePanel balance={500} allowance={1_000} gauge={computeCreditGauge(500, 1_000)} />,
    );

    expect(screen.queryByTestId('balance-view-usage')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.queryByText('View usage')).toBeNull();
  });
});

describe('CreditBalancePanel - bucket breakdown', () => {
  afterEach(cleanup);

  it('splits subscription vs top-up only when a top-up exists', () => {
    // Without a top-up the split would just restate Remaining twice.
    withIntl(
      <CreditBalancePanel
        balance={500}
        allowance={1_000}
        gauge={computeCreditGauge(500, 1_000)}
        subBalance={500}
        paygBalance={0}
      />,
    );
    expect(screen.queryByText('PAYG top-up')).toBeNull();
  });

  it('shows both buckets when a top-up is what pushed the wallet over its grant', () => {
    withIntl(
      <CreditBalancePanel
        balance={1_400}
        allowance={1_000}
        gauge={computeCreditGauge(1_400, 1_000)}
        subBalance={1_000}
        paygBalance={400}
      />,
    );
    expect(screen.getByText('Subscription')).toBeTruthy();
    expect(screen.getByText('PAYG top-up')).toBeTruthy();
  });
});

describe('CreditRing', () => {
  afterEach(cleanup);

  it('labels itself with the percentage at header size', () => {
    withIntl(<CreditRing percent={2} />);
    expect(screen.getByText('2%')).toBeTruthy();
  });

  it('drops the label at badge size, where it would be unreadable', () => {
    const { container } = withIntl(<CreditRing percent={2} size={16} />);
    expect(container.textContent).toBe('');
  });

  it('clamps a percentage above 100 so the arc cannot wrap past full', () => {
    const { container } = withIntl(<CreditRing percent={340} />);
    const arc = container.querySelectorAll('circle')[1] as SVGCircleElement;
    const [dash, circumference] = (arc.getAttribute('stroke-dasharray') || '').split(' ').map(Number);
    expect(dash).toBeCloseTo(circumference, 5);
    expect(screen.getByText('100%')).toBeTruthy();
  });

  it('shows a "+" instead of a percentage when the wallet is OVER its grant', () => {
    // An ink "100%" means the wallet is empty; a gold "100%" would mean it holds
    // double the grant. Same four glyphs, opposite meanings, told apart only by
    // hue - so the gold state uses a different glyph, not a different colour.
    withIntl(<CreditRing percent={100} gold />);
    expect(screen.getByTestId('credit-ring-label').textContent).toBe('+');
    expect(screen.queryByText('100%')).toBeNull();
  });

  it('shows a percentage, never a "+", while credits are being consumed', () => {
    withIntl(<CreditRing percent={100} />);
    expect(screen.getByTestId('credit-ring-label').textContent).toBe('100%');
  });

  it('switches the arc stroke to gold in the over-allowance state', () => {
    const { container } = withIntl(<CreditRing percent={40} gold />);
    const arc = container.querySelectorAll('circle')[1] as SVGCircleElement;
    expect(arc.getAttribute('stroke')).toBe('var(--credit-gold-arc)');
    expect(arc.getAttribute('class') ?? '').not.toContain('stroke-black');
  });
});

describe('CreditRingBadge', () => {
  afterEach(cleanup);

  it('renders the compact wallet amount beside the ring', () => {
    withIntl(<CreditRingBadge balance={9_779} gauge={computeCreditGauge(9_779, 10_000)} hasAllowance />);
    expect(screen.getByText('9.8K')).toBeTruthy();
  });

  it('drops a meaningless trailing .0 on a whole sub-1,000 balance', () => {
    // The shared compact formatter always keeps a decimal below 1,000, which
    // put "980.0" next to the ring - noise at badge size.
    withIntl(<CreditRingBadge balance={980} gauge={computeCreditGauge(980, 1_000)} hasAllowance />);
    expect(screen.getByText('980')).toBeTruthy();
  });

  it('keeps a REAL fraction, which is spendable and must not be rounded away', () => {
    withIntl(<CreditRingBadge balance={980.4} gauge={computeCreditGauge(980.4, 1_000)} hasAllowance />);
    expect(screen.getByText('980.4')).toBeTruthy();
  });
});

describe('CreditRingBadge - no denominator', () => {
  afterEach(cleanup);

  it('drops the ring entirely rather than drawing an empty dial', () => {
    // With no allowance a 0% ring would be a statement about the account, and
    // "we cannot compute one" is exactly the opposite statement.
    const { container } = withIntl(
      <CreditRingBadge balance={42_000} gauge={computeCreditGauge(42_000, null)} hasAllowance={false} />,
    );
    expect(container.querySelectorAll('circle')).toHaveLength(0);
    expect(screen.getByText('42.0K')).toBeTruthy();
  });

  it('keeps the ring when there IS an allowance', () => {
    const { container } = withIntl(
      <CreditRingBadge balance={500} gauge={computeCreditGauge(500, 1_000)} hasAllowance />,
    );
    expect(container.querySelectorAll('circle')).toHaveLength(2);
  });
});

describe('CreditBalancePanel - exact amounts', () => {
  afterEach(cleanup);

  it('keeps a spendable fraction on Remaining instead of rounding it up', () => {
    // Rounding half-up would turn 980.6 into "981" and OVERSTATE the balance,
    // and it would disagree with the compact badge that triggers this panel.
    withIntl(
      <CreditBalancePanel balance={980.6} allowance={1_000} gauge={computeCreditGauge(980.6, 1_000)} />,
    );
    expect(screen.getByTestId('balance-remaining').textContent).toBe('980.6');
  });

  it('groups a large amount for the app locale', () => {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    expect(screen.getByTestId('balance-remaining').textContent).toBe('9,779');
  });

  it('hides the header mini-ring too when there is no allowance', () => {
    // The panel and its trigger must agree: if no dial can be drawn, none is.
    const { container } = withIntl(
      <CreditBalancePanel balance={42_000} allowance={null} gauge={computeCreditGauge(42_000, null)} />,
    );
    expect(container.querySelectorAll('circle')).toHaveLength(0);
    expect(screen.queryByTestId('balance-total-label')).toBeNull();
  });
});

describe('CreditBalancePanel - the app locale, not the runner default', () => {
  afterEach(cleanup);

  // These components read next-intl's useLocale(), the app locale the provider
  // carries - not the browser's and not the runner's. Asserting only the `en`
  // form would pass on an en-default runner even if the code called a bare
  // toLocaleString(), so each case drives a real provider locale and expects
  // that locale's separators.
  const withLocale = (locale: string, ui: React.ReactElement) =>
    render(
      <NextIntlClientProvider locale={locale} messages={messages as Record<string, unknown>}>
        {ui}
      </NextIntlClientProvider>,
    );

  it('groups Total and Remaining with English separators under en', () => {
    withLocale('en',
      <CreditBalancePanel balance={9_779.4} allowance={10_000} gauge={computeCreditGauge(9_779.4, 10_000)} />);

    expect(screen.getByTestId('balance-remaining').textContent).toBe('9,779.4');
    expect(screen.getByText('10,000 credits')).toBeTruthy();
  });

  it('groups them with German separators under de', () => {
    withLocale('de',
      <CreditBalancePanel balance={9_779.4} allowance={10_000} gauge={computeCreditGauge(9_779.4, 10_000)} />);

    expect(screen.getByTestId('balance-remaining').textContent).toBe('9.779,4');
  });

  it('gives the badge the SAME decimal separator as the panel it opens', () => {
    // The shared compact formatter uses toFixed (always a dot), so a German
    // reader used to get "980.4" in the badge and "980,4" one hover away.
    withLocale('de',
      <CreditRingBadge balance={980.4} gauge={computeCreditGauge(980.4, 1_000)} hasAllowance />);

    expect(screen.getByText('980,4')).toBeTruthy();
  });

  // The surplus SENTENCE moved out of this panel with the bar that carried it.
  // Its locale handling and its "+0%" edge are now exercised where the sentence
  // actually renders: the ring's accessible name (SidebarCreditBalance.test)
  // and the wallet card (BalanceBreakdown.planPercent.test).
});

describe('CreditBalancePanel - bucket split edge', () => {
  afterEach(cleanup);

  it('stays hidden when the sub bucket is unknown, even with a top-up present', () => {
    // Dropping `subBalance !== null` from the condition would render a row
    // labelled "Subscription" with a dash in it.
    withIntl(
      <CreditBalancePanel
        balance={400}
        allowance={1_000}
        gauge={computeCreditGauge(400, 1_000)}
        subBalance={null}
        paygBalance={400}
      />,
    );
    expect(screen.queryByText('PAYG top-up')).toBeNull();
  });
});

describe('CreditBalancePanel - the two buckets are not interchangeable', () => {
  it('prints the renewal grant and the top-up against the RIGHT labels', () => {
    // Asserting only that the two labels exist let the two VALUES be swapped
    // with the whole suite green. The buckets behave in opposite ways - the
    // subscription one is wiped at the next renewal, the top-up survives it -
    // so showing them the wrong way round inverts the decision a user makes
    // about whether they need to top up.
    withIntl(
      <CreditBalancePanel
        balance={1_400}
        allowance={1_000}
        gauge={computeCreditGauge(1_400, 1_000)}
        subBalance={1_000}
        paygBalance={400}
      />,
    );

    expect(screen.getByTestId('bucket-sub').textContent).toBe('1.0K');
    expect(screen.getByTestId('bucket-payg').textContent).toBe('400');
  });

  it('keeps them apart when the top-up is the larger of the two', () => {
    // Above, the grant is the bigger number; here it is the smaller one, so a
    // swap cannot slip through on a coincidence of magnitude.
    withIntl(
      <CreditBalancePanel
        balance={35_000}
        allowance={1_000}
        gauge={computeCreditGauge(35_000, 1_000)}
        subBalance={1_000}
        paygBalance={34_000}
      />,
    );

    expect(screen.getByTestId('bucket-sub').textContent).toBe('1.0K');
    expect(screen.getByTestId('bucket-payg').textContent).toBe('34.0K');
  });
});

describe('CreditRing - the label has to fit the hole it sits in', () => {
  // Three glyph counts, three sizes: the gold "+" is one glyph and reads
  // largest, "42%" is three, and "100%" is four and has to step down. Collapsing
  // them to one constant changed nothing in any test, while making the emptiest
  // wallet - the state that matters most - the least legible label on the dial.
  const fontPx = (el: HTMLElement) => parseFloat(el.style.fontSize);

  it('gives the gold "+" the largest relative size', () => {
    withIntl(<CreditRing percent={100} size={28} gold showLabel />);
    expect(fontPx(screen.getByTestId('credit-ring-label'))).toBe(14);
  });

  it('uses the standard size for a two-digit percentage', () => {
    withIntl(<CreditRing percent={42} size={28} showLabel />);
    expect(fontPx(screen.getByTestId('credit-ring-label'))).toBe(9);
  });

  it('steps down for "100%", but not all the way to unreadable', () => {
    withIntl(<CreditRing percent={100} size={28} showLabel />);
    const px = fontPx(screen.getByTestId('credit-ring-label'));
    expect(px).toBeLessThan(9);
    expect(px).toBeGreaterThanOrEqual(8);
  });

  it('scales with the ring, so a bigger dial gets bigger type', () => {
    withIntl(<CreditRing percent={42} size={40} showLabel />);
    expect(fontPx(screen.getByTestId('credit-ring-label'))).toBeGreaterThan(9);
  });
});

describe('CreditRing - the label never goes below a legible floor', () => {
  it('keeps the rail-sized gold "+" at 8px, not 7', () => {
    // 14 x 0.5 rounds to 7px, which is the exact size the sizing note rejects
    // for "100%" as the least legible label on the dial. A "+" is one glyph and
    // has the room, so the floor costs it nothing.
    withIntl(<CreditRing percent={100} size={14} gold showLabel />);
    expect(parseFloat(screen.getByTestId('credit-ring-label').style.fontSize)).toBe(8);
  });

  it('leaves larger rings above the floor untouched', () => {
    // The floor must not flatten the three regimes it sits under.
    withIntl(<CreditRing percent={100} size={28} gold showLabel />);
    expect(parseFloat(screen.getByTestId('credit-ring-label').style.fontSize)).toBe(14);
  });
});

describe('CreditBalancePanel - long labels wrap, numbers do not', () => {
  it('lets the label shrink and wrap rather than truncate', () => {
    // Truncating was worse than wrapping here: the sidebar card is 228px of
    // content, so a German label already overflows at the ordinary tier and
    // would have ellipsised with no title to recover the text from.
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    const label = screen.getByTestId('balance-total-label');
    expect(label.className).toContain('min-w-0');
    expect(label.className).not.toContain('truncate');
  });

  it('keeps both value columns unbreakable, so the label is what gives way', () => {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    expect(screen.getByTestId('balance-remaining').className).toContain('whitespace-nowrap');
    expect(screen.getByText('10,000 credits').className).toContain('whitespace-nowrap');
  });
});

describe('CreditBalancePanel - both rows behave the same under the same pressure', () => {
  // Round 9 replaced `truncate` with wrapping on the grant row, and only that
  // row was pinned - so reintroducing `truncate` on the Remaining label, or
  // dropping `items-start`, passed the whole suite while leaving the two
  // adjacent rows to behave differently, which is how one of them ends up
  // looking broken.
  function labels() {
    withIntl(
      <CreditBalancePanel balance={9_779} allowance={10_000} gauge={computeCreditGauge(9_779, 10_000)} />,
    );
    return {
      grant: screen.getByTestId('balance-total-label'),
      remaining: screen.getByTestId('balance-remaining-label'),
    };
  }

  it('lets BOTH labels shrink and wrap rather than truncate', () => {
    const { grant, remaining } = labels();
    for (const el of [grant, remaining]) {
      expect(el.className).toContain('min-w-0');
      expect(el.className).not.toContain('truncate');
    }
  });

  it('keeps BOTH value columns unbreakable', () => {
    labels();
    expect(screen.getByTestId('balance-remaining').className).toContain('whitespace-nowrap');
    expect(screen.getByText('10,000 credits').className).toContain('whitespace-nowrap');
  });

  it('aligns both rows to the TOP, so a wrapped label keeps its amount on line one', () => {
    // items-center would drift the amount to the middle of a two-line label,
    // which is the reason wrapping was chosen over truncating in the first place.
    const { grant, remaining } = labels();
    expect(grant.parentElement!.className).toContain('items-start');
    expect(remaining.parentElement!.className).toContain('items-start');
    expect(grant.parentElement!.className).not.toContain('items-baseline');
  });
});

describe('CreditAvatarRing - it draws itself on arrival', () => {
  afterEach(() => {
    cleanup();
    reducedMotion.value = false;
  });

  const track = (container: HTMLElement) =>
    container.querySelector('[data-testid="credit-avatar-ring-track"]') as SVGCircleElement;
  const arc = (container: HTMLElement) =>
    container.querySelector('[data-testid="credit-avatar-ring-arc"]') as SVGCircleElement;
  const num = (el: SVGCircleElement, attr: string) => Number(el.getAttribute(attr));
  const delayMs = (el: SVGCircleElement) => Number(/(\d+)ms\s*$/.exec(el.style.transition)?.[1]);
  const durationMs = (el: SVGCircleElement) =>
    Number(/stroke-dashoffset\s+(\d+)ms/.exec(el.style.transition)?.[1]);

  it('starts at zero and only then travels to its position', async () => {
    // The whole point: an arc caught mid-travel reads as a quantity being
    // measured, where the same arc already at rest reads as a border. Rendering
    // the final offset on the first pass would look identical in every OTHER
    // assertion in this file, so it is pinned here.
    //
    // What this canNOT see is the two-frame wait. jsdom does not paint, so one
    // requestAnimationFrame and two are indistinguishable to it while a browser
    // tells them apart (a transition needs its starting value painted before the
    // value changes, or it does not run at all). That mutation is caught by a
    // source-shape assertion in credit-surface-contract.test.ts instead - stated
    // plainly here so nobody reads this test as covering it.
    const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} />);
    const circle = arc(container);
    const circumference = num(circle, 'stroke-dasharray');

    // First paint: the offset hides the arc completely.
    expect(num(circle, 'stroke-dashoffset')).toBeCloseTo(circumference, 5);

    await waitFor(() => {
      expect(num(arc(container), 'stroke-dashoffset')).toBeCloseTo(circumference * 0.6, 5);
    });
  });

  it('draws the TRACK round the avatar first, which is the reveal', async () => {
    // The arc measures credits CONSUMED, so a healthy wallet's arc is a couple
    // of percent: sweeping it from zero travels two percent of a circle and
    // nobody sees it. The full circle is what says "there is a gauge here", and
    // it is the same length for every account. Dropping the track's own
    // transition - the obvious "simplification", since the track has no value
    // to show - is what makes the whole animation invisible to most users.
    const { container } = withIntl(<CreditAvatarRing percent={2} avatarSize={32} />);

    expect(num(track(container), 'stroke-dashoffset')).toBeCloseTo(
      num(track(container), 'stroke-dasharray'),
      5,
    );
    await waitFor(() => {
      expect(num(track(container), 'stroke-dashoffset')).toBe(0);
    });
    // The offsets alone are identical with or without a transition, so without
    // this line the "simplification" the comment warns about - dropping the
    // track's own style - passes. It cost a mutation run to find that out.
    expect(track(container).style.transition).toContain('stroke-dashoffset');
  });

  it('starts the arc just BEFORE the track lands, so the two read as one gesture', async () => {
    // Overlapping is the difference between a dial appearing and then filling,
    // and two animations queued back to back. Asserted as an ORDER rather than
    // as literal numbers, so the timings stay tunable.
    //
    // Awaited, because the opening timings only exist while the reveal is
    // running: the first frame carries no transition at all (nothing to travel
    // from yet) and the settled state carries a different, undelayed one.
    const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} />);
    await waitFor(() => expect(delayMs(track(container))).toBeGreaterThan(0));
    const trackEnds = delayMs(track(container)) + durationMs(track(container));

    expect(delayMs(arc(container))).toBeGreaterThan(delayMs(track(container)));
    expect(delayMs(arc(container))).toBeLessThan(trackEnds);
  });

  it('is over quickly enough to be an entrance, not a loading state', async () => {
    // A reader arrives, looks at the sidebar, and the ring should already be
    // settling. Past about a second and a half it stops reading as an entrance
    // and starts reading as something still loading.
    const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} />);
    await waitFor(() => expect(delayMs(arc(container))).toBeGreaterThan(0));
    expect(delayMs(arc(container)) + durationMs(arc(container))).toBeLessThanOrEqual(1_500);
  });

  it('moves the OFFSET, keeping the dash pattern the whole circle', () => {
    // Animating `stroke-dasharray` instead means interpolating a two-value list,
    // which engines do inconsistently. Pinned because swapping back to a
    // `${dash} ${circumference}` pair still draws the right arc at rest, so
    // nothing but this notices - until the transition stops working.
    const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} />);
    const circle = arc(container);
    const radius = num(circle, 'r');

    expect(num(circle, 'stroke-dasharray')).toBeCloseTo(2 * Math.PI * radius, 5);
    expect(circle.getAttribute('stroke-dasharray')).not.toContain(' ');
  });

  it('carries a transition on the offset, which is what makes it travel', async () => {
    const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} />);
    await waitFor(() => {
      expect(arc(container).style.transition).toContain('stroke-dashoffset');
    });
  });

  it('reaches the same position with the transition dropped under reduced motion', async () => {
    // Reduced motion removes the TRAVEL, never the answer: a reader with the
    // setting on must still see how much of their plan is left, and must still
    // get a complete track to read it against.
    reducedMotion.value = true;
    const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} />);

    await waitFor(() => {
      const circle = arc(container);
      expect(num(circle, 'stroke-dashoffset')).toBeCloseTo(num(circle, 'stroke-dasharray') * 0.6, 5);
    });
    expect(num(track(container), 'stroke-dashoffset')).toBe(0);
    expect(arc(container).style.transition).toBe('');
    expect(track(container).style.transition).toBe('');
  });

  it('renders the SAME first frame either way, so hydration cannot disagree', () => {
    // The reduced-motion answer is false on the server and true in that
    // reader's browser, so branching the MARKUP on it would give React two
    // different trees to reconcile. Only the transition may differ.
    //
    // Compared as WHOLE markup with the style attributes stripped, not one
    // attribute on one element: a branch on the track's offset, on a class, on
    // strokeLinecap or on whether the gradient is emitted would all be
    // hydration mismatches too, and an offset-only check sees none of them.
    const frame = (reduced: boolean) => {
      reducedMotion.value = reduced;
      const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} gold />);
      const html = container.innerHTML.replace(/ style="[^"]*"/g, '');
      cleanup();
      return html;
    };

    // The gradient id is a useId, which differs per render by design.
    const normalise = (html: string) => html.replace(/id="credit-gold-[^"]*"|#credit-gold-[^)]*/gi, 'ID');
    expect(normalise(frame(true))).toBe(normalise(frame(false)));
  });

  it('draws nothing at all at 0%, rather than a round cap sitting on the track', async () => {
    // AFTER the sweep, which is the whole assertion. Reading the offset
    // synchronously would hold for every percentage alike - the ring starts
    // fully retracted no matter what it is about to draw - so that version
    // passed while the implementation drew a `Math.max(dash, 3)` stub, which is
    // exactly the round cap the title says must not be there.
    const { container: empty } = withIntl(<CreditAvatarRing percent={0} avatarSize={32} />);
    await waitFor(() => {
      expect(arc(empty).style.transition).not.toBe('');
    });
    expect(num(arc(empty), 'stroke-dashoffset')).toBeCloseTo(num(arc(empty), 'stroke-dasharray'), 5);

    // Contrasted against a percentage that DOES draw, so "nothing is ever
    // drawn" cannot pass this either.
    cleanup();
    const { container: some } = withIntl(<CreditAvatarRing percent={5} avatarSize={32} />);
    await waitFor(() => {
      expect(num(arc(some), 'stroke-dashoffset')).toBeLessThan(num(arc(some), 'stroke-dasharray'));
    });
  });

  /**
   * Wait until the reveal has handed over to the settled timings.
   *
   * The first version of the two tests below waited only for the reveal to
   * START (two frames), which left both assertions running in the OPENING
   * phase - so they observed the opening transition and a mutation that made
   * the settled state jump outright passed them both. The hand-over is what
   * has to be waited for.
   */
  const settled = async (container: HTMLElement) => {
    // BOTH halves, because each phase fails exactly one of them: 'hidden'
    // carries no transition at all, 'opening' carries one with a trailing
    // delay, and only 'settled' has a transition with none. Waiting on the
    // delay alone matched 'hidden' too and returned on the first frame.
    await waitFor(
      () => {
        const transition = arc(container).style.transition;
        expect(transition).toContain('stroke-dashoffset');
        expect(transition).not.toMatch(/\d+ms\s*$/);
      },
      { timeout: 3_000 },
    );
  };

  it('glides to a LATER balance instead of jumping to it', async () => {
    // Credits are spent while the app is open, and a refetch on window focus
    // brings the new figure in. Nothing remounts, so the ring has to carry the
    // change itself, and it must still be a glide: a dial that teleports reads
    // as a re-render, not as a number changing.
    const { container, rerender } = withIntl(<CreditAvatarRing percent={20} avatarSize={32} />);
    const C = num(arc(container), 'stroke-dasharray');
    await settled(container);
    expect(num(arc(container), 'stroke-dashoffset')).toBeCloseTo(C * 0.8, 5);

    rerender(
      <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
        <CreditAvatarRing percent={75} avatarSize={32} />
      </NextIntlClientProvider>,
    );

    expect(num(arc(container), 'stroke-dashoffset')).toBeCloseTo(C * 0.25, 5);
    // The assertion the earlier version missed: in the settled phase there IS
    // still a transition. Dropping it here is a hard jump on every balance
    // change for the rest of the session.
    expect(arc(container).style.transition).toContain('stroke-dashoffset');
    expect(durationMs(arc(container))).toBeGreaterThan(0);
  });

  it('drops the opening delay once the reveal is behind it', async () => {
    // The opening stagger holds the arc back 620ms so it follows the track. Left
    // in place afterwards, every later change to the balance would sit visibly
    // motionless for two thirds of a second before acknowledging a number that
    // had already changed.
    const { container } = withIntl(<CreditAvatarRing percent={20} avatarSize={32} />);
    await waitFor(() => expect(delayMs(arc(container))).toBeGreaterThan(0));
    await settled(container);
    expect(arc(container).style.transition).toContain('stroke-dashoffset');
    expect(arc(container).style.transition).not.toMatch(/\d+ms\s*$/);
  });

  it('draws the answer with no travel at all when the reveal is already spent', async () => {
    // `animate={false}` is how the sidebar says "this reader has seen it this
    // visit". It must not retract first: a ring that empties itself and refills
    // is worse than one that never moved.
    const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} animate={false} />);

    expect(num(arc(container), 'stroke-dashoffset')).toBeCloseTo(
      num(arc(container), 'stroke-dasharray') * 0.6,
      5,
    );
    expect(num(track(container), 'stroke-dashoffset')).toBe(0);
    // And it stays put: no frame arrives later to start a sweep.
    await waitFor(() => expect(arc(container).style.transition).not.toBe(''));
    expect(delayMs(arc(container))).toBeNaN();
  });

  it('sweeps the gold arc too, which is the state worth noticing most', async () => {
    const { container } = withIntl(<CreditAvatarRing percent={40} avatarSize={32} gold />);
    expect(container.querySelector('linearGradient')).not.toBeNull();
    await waitFor(() => {
      const circle = arc(container);
      expect(num(circle, 'stroke-dashoffset')).toBeCloseTo(num(circle, 'stroke-dasharray') * 0.6, 5);
    });
  });

  it('clamps a percentage above 100 so the arc cannot wrap past full', async () => {
    const { container } = withIntl(<CreditAvatarRing percent={340} avatarSize={32} />);
    await waitFor(() => {
      expect(num(arc(container), 'stroke-dashoffset')).toBeCloseTo(0, 5);
    });
  });
});
