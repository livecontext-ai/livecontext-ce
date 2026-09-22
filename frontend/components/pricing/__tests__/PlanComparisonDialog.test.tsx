// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, act, fireEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';

// Real messages, so a label the dialog asks for and no locale defines shows up
// here as a raw key rather than passing under an identity mock.

vi.mock('@/hooks/usePricingEvent', () => ({
  usePricingEvent: () => ({ event: null, serverTime: null, isLoading: false }),
}));

vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href, onClick }: any) => (
    <a href={typeof href === 'string' ? href : '#'} onClick={onClick}>
      {children}
    </a>
  ),
}));

import PlanComparisonDialog from '../PlanComparisonDialog';
import { openPlanComparison } from '@/lib/billing/plan-comparison-open';
import { calcPrice } from '@/lib/billing/pricing-constants';

function renderDialog(currentPlanCode: string | null = null) {
  return render(
    <NextIntlClientProvider locale="en" messages={en as any}>
      <PlanComparisonDialog currentPlanCode={currentPlanCode} />
    </NextIntlClientProvider>
  );
}

/** Opens the dialog the way every caller does: a window event, no props. */
function open(request?: Parameters<typeof openPlanComparison>[0]) {
  act(() => {
    openPlanComparison(request);
  });
}

beforeEach(() => {
  // jsdom has no layout, so the scroll the dialog performs on a highlighted row
  // would throw. Stubbing it also lets a test assert the row was scrolled to.
  Element.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('PlanComparisonDialog', () => {
  it('renders nothing until something asks for it', () => {
    const { container } = renderDialog();
    expect(container.innerHTML).toBe('');
    expect(screen.queryByText('Compare plans')).toBeNull();
  });

  it('opens on the event, with one column per plan', () => {
    renderDialog();
    open();

    for (const [planId, name] of [
      ['free', 'Free'],
      ['starter', 'Starter'],
      ['pro', 'Pro'],
      ['team', 'Team'],
      ['enterprise', 'Enterprise'],
    ]) {
      expect(screen.getByTestId(`plan-comparison-col-${planId}`).textContent, name).toContain(name);
    }
  });

  it('states each plan value in its own column', () => {
    renderDialog();
    open();

    const storageRow = screen.getByTestId('plan-comparison-row-storage');
    const cells = Array.from(storageRow.querySelectorAll('td')).map((c) => c.textContent?.trim());
    expect(cells).toEqual(['100 MB', '1 GB', '10 GB', '100 GB shared', '1 TB']);
  });

  it('states the Free AI allowance, and what the paid plans have instead', () => {
    // A blank cell on the paid columns would read as "this plan does not include
    // it", which is the opposite of true: their credits already fund agents. The
    // row therefore declares a fallback VALUE rather than leaving a cross, and
    // this is the only test that renders it - the data-level test can see the
    // fallback key resolves, not that the cell prints it.
    renderDialog();
    open();

    const row = screen.getByTestId('plan-comparison-row-aiCredits');
    const cells = Array.from(row.querySelectorAll('td')).map((c) => c.textContent?.trim());
    expect(cells).toEqual([
      '100 / month',
      'Included in credits',
      'Included in credits',
      'Included in credits',
      'Included in credits',
    ]);
  });

  it('quotes the allowance the caller passes, not the seeded constant', () => {
    // The pricing page reads the live plan row and hands it down, so an admin
    // raising the allowance to 250 must not leave this table saying 100 on the
    // same screen.
    render(
      <NextIntlClientProvider locale="en" messages={en as any}>
        <PlanComparisonDialog currentPlanCode={null} freeAiCredits={250} />
      </NextIntlClientProvider>
    );
    open();

    const row = screen.getByTestId('plan-comparison-row-aiCredits');
    expect(row.querySelector('td')?.textContent?.trim()).toBe('250 / month');
  });

  it('says "Not included" for Free when the allowance is closed, and leaves the paid columns alone', () => {
    // An admin setting included_ai_credits to 0 closes the free tier. The card drops
    // its bullet; this table must not keep printing '0 / month' beside it, which would
    // advertise a pot nobody has and make the two surfaces disagree on one screen.
    render(
      <NextIntlClientProvider locale="en" messages={en as any}>
        <PlanComparisonDialog currentPlanCode={null} freeAiCredits={0} />
      </NextIntlClientProvider>
    );
    open();

    const row = screen.getByTestId('plan-comparison-row-aiCredits');
    const cells = Array.from(row.querySelectorAll('td')).map((c) => c.textContent?.trim());
    // The same wording every other dimension uses for 'this plan does not have it',
    // not the paid plans' answer - their credits DO fund agents, a closed Free tier
    // funds nothing. The paid columns must keep their own answer, or closing the free
    // tier would quietly rewrite what every other plan says about agents.
    expect(cells[0]).toBe('Not included');
    expect(cells.slice(1)).toEqual([
      'Included in credits',
      'Included in credits',
      'Included in credits',
      'Included in credits',
    ]);
  });

  it('states node coverage as ONE step, at Starter', () => {
    renderDialog();
    open();

    // The catalogue holds back publishing and nothing else, and it drops at
    // STARTER (migration V458). Three different values across the paid plans
    // would describe a ladder that does not exist, and would tell someone
    // weighing Starter against Pro that Pro unlocks nodes it does not.
    const nodesRow = screen.getByTestId('plan-comparison-row-nodes');
    const cells = Array.from(nodesRow.querySelectorAll('td')).map((c) => c.textContent?.trim());
    expect(cells).toEqual([
      'All but publishing',
      'Everything',
      'Everything',
      'Everything',
      'Everything',
    ]);
  });

  it('explains a dimension whose cells cannot carry the qualification', () => {
    renderDialog();
    open();

    // "All but publishing" is not self-explanatory: what matters to a Free
    // reader is that READING those platforms is not what is held back.
    const nodesRow = screen.getByTestId('plan-comparison-row-nodes');
    expect(nodesRow.textContent).toContain('Nodes and integrations');
    expect(nodesRow.querySelector('button[aria-label="Nodes and integrations"]')).not.toBeNull();
  });

  it('shows a capability as included or not, with a name a screen reader gets', () => {
    renderDialog();
    open();

    const ssoRow = screen.getByTestId('plan-comparison-row-sso');
    const cells = Array.from(ssoRow.querySelectorAll('td')).map((c) => c.textContent?.trim());
    // Free / Starter / Pro do not have SSO; Team and Enterprise do.
    expect(cells).toEqual(['Not included', 'Not included', 'Not included', 'Included', 'Included']);
  });

  it('says "not included" where a plan answers a dimension with nothing', () => {
    renderDialog();
    open();

    // Free carries no analytics key at all, so its cell is not a value but an
    // absence, and must read as one rather than as a blank.
    const analyticsRow = screen.getByTestId('plan-comparison-row-analytics');
    const cells = Array.from(analyticsRow.querySelectorAll('td')).map((c) => c.textContent?.trim());
    expect(cells[0]).toBe('Not included');
    expect(cells[1]).toBe('Basic');
  });

  it('keeps the explanation a feature carries on the plan cards', () => {
    renderDialog();
    open();

    // 'cePlatformCreds' is one of the two keys with a tooltip; the row must
    // carry it through rather than dropping it on the way into the table.
    const row = screen.getByTestId('plan-comparison-row-cePlatformCreds');
    expect(row.textContent).toContain('Managed integration credentials');
    expect(row.querySelector('button[aria-label="Managed integration credentials"]')).not.toBeNull();
  });

  it('marks the plan the account is on, and only that one', () => {
    renderDialog('PRO');
    open();

    const badges = screen.getAllByText('Current plan');
    expect(badges).toHaveLength(1);
    expect(badges[0].closest('th')?.textContent).toContain('Pro');
  });

  it('maps an Enterprise SKU to the Enterprise column', () => {
    renderDialog('ENTERPRISE_PREMIUM');
    open();

    expect(screen.getByText('Current plan').closest('th')?.textContent).toContain('Enterprise');
  });

  it('marks no plan as current for a visitor who has none', () => {
    renderDialog(null);
    open();

    expect(screen.queryByText('Current plan')).toBeNull();
  });

  it('marks the plan that would lift a restriction', () => {
    renderDialog('FREE');
    open({ highlightPlan: 'STARTER' });

    const badge = screen.getByText('Unlocks this');
    expect(badge.closest('th')?.textContent).toContain('Starter');
    // The account's own plan keeps its own marker: the two never collide.
    expect(screen.getByText('Current plan').closest('th')?.textContent).toContain('Free');
  });

  it('brings the row a caller asked about into view', async () => {
    renderDialog();
    open({ highlightRow: 'storage' });

    const row = screen.getByTestId('plan-comparison-row-storage');
    expect(row.className).toContain('accent-primary');

    // The scroll is deferred one frame, so the row exists at its final size.
    await act(async () => {
      await new Promise((resolve) => requestAnimationFrame(() => resolve(null)));
    });
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it('highlights nothing when the caller names a row that does not exist', () => {
    renderDialog();
    open({ highlightRow: 'nope' });

    const highlighted = document.querySelectorAll('tr[class*="accent-primary"]');
    expect(highlighted).toHaveLength(0);
  });

  it('quotes the yearly price first, and switches to monthly on demand', () => {
    renderDialog();
    open();

    expect(screen.getByTestId('plan-comparison-col-starter').textContent).toContain(
      `$${calcPrice('starter', 'yearly', 0)}`
    );

    fireEvent.click(screen.getByRole('button', { name: 'Monthly' }));

    expect(screen.getByTestId('plan-comparison-col-starter').textContent).toContain(
      `$${calcPrice('starter', 'monthly', 0)}`
    );
  });

  it('quotes Enterprise rather than pricing it', () => {
    renderDialog();
    open();

    expect(screen.getByTestId('plan-comparison-col-enterprise').textContent).toContain(
      'Custom pricing'
    );
  });

  it('sends every plan but the current one to the plans page, and closes on the way', () => {
    renderDialog('PRO');
    open();

    const links = screen.getAllByRole('link');
    expect(links.length).toBe(4); // five columns, minus the one the account is on
    for (const link of links) {
      expect(link.getAttribute('href')).toBe('/app/settings/pricing');
    }

    fireEvent.click(links[0]);
    expect(screen.queryByTestId('plan-comparison-col-starter')).toBeNull();
  });

  it('closes without navigating anywhere', () => {
    renderDialog();
    open();
    expect(screen.getByTestId('plan-comparison-col-team')).toBeTruthy();

    fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' });

    expect(screen.queryByTestId('plan-comparison-col-team')).toBeNull();
  });

  it('reopens with a new emphasis rather than keeping the previous one', () => {
    renderDialog();
    open({ highlightRow: 'storage' });
    fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' });

    open({ highlightRow: 'users' });

    expect(screen.getByTestId('plan-comparison-row-users').className).toContain('accent-primary');
    expect(screen.getByTestId('plan-comparison-row-storage').className).not.toContain('accent-primary');
  });
});
