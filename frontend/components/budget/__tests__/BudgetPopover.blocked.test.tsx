// @vitest-environment jsdom
/**
 * "Your automation is stopped" - who is allowed to say it, and on what evidence.
 *
 * The popover has always been able to draw that sentence, but only from the SPEND
 * FIGURE (`spent >= cap`), which is trustworthy for a workflow and not for an agent:
 * an agent's counter is zeroed lazily, by the resolver that enforces the budget, and
 * that only runs when the agent next executes. So an agent that reached a monthly cap
 * in September still reads at the cap all through October and will run fine. The
 * component's answer was `spendIsCurrent={false}` - say nothing at all - which is
 * honest and useless: the agents list could show a figure at 3 / 1 credits and not
 * one word about the schedule that has stopped firing.
 *
 * The server now resolves that verdict (AgentBudgetRule, applied with the pending
 * reset), so the claim can be made from evidence instead of guessed. These cases pin
 * the three-way contract: verdict wins, guess fills in, silence when neither holds.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { BudgetChip } from '../BudgetChip';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}));

const popover = () => document.querySelector('[data-testid="budget-popover"]');

async function openPopover() {
  fireEvent.focus(screen.getByTestId('budget-chip'));
  await waitFor(() => expect(popover()).not.toBeNull());
}

const stoppedText = () => popover()?.textContent ?? '';

afterEach(() => cleanup());

describe('BudgetPopover - the stopped claim', () => {
  it('says STOPPED on a server verdict even when the figure is not trusted', async () => {
    // The agents-list shape exactly: the figure may be stale, the verdict is not.
    // Before the verdict existed this rendered no stopped line at all, which is the
    // bug a user reports as "I set a limit and nothing tells me anything".
    render(
      <BudgetChip
        spent={3}
        cap={1}
        periodMode="cumulative"
        fallbackPeriod="cumulative"
        spendIsCurrent={false}
        blocked
      />,
    );
    await openPopover();

    expect(stoppedText()).toContain('budget.popoverStoppedForGood');
  });

  it('stays SILENT when the server says the agent is not blocked, whatever the figure says', async () => {
    // The rolled-over agent: 10 spent against a cap of 10, and its next run resets the
    // counter and proceeds. The figure alone would call this stopped, and would be
    // wrong for a whole month. An explicit false must beat the arithmetic, which is
    // why the override is `blocked ?? guess` and not `blocked || guess`.
    render(
      <BudgetChip
        spent={10}
        cap={10}
        periodMode="monthly"
        fallbackPeriod="cumulative"
        spendIsCurrent={false}
        blocked={false}
      />,
    );
    await openPopover();

    expect(stoppedText()).not.toContain('budget.popoverStopped');
  });

  it('falls back to the figure when no verdict is supplied, for a workflow', async () => {
    // Workflows send no verdict and do not need one: their period counter is rolled
    // server-side on every read, so the figure IS current. This is the regression that
    // would be easy to cause while adding the override.
    render(<BudgetChip spent={10} cap={10} periodMode="monthly" />);
    await openPopover();

    expect(stoppedText()).toContain('budget.popoverStopped');
  });

  it('keeps saying nothing when there is no verdict AND the figure is untrusted', async () => {
    // The pre-existing agent behaviour, unchanged: during a rolling deploy the agents
    // list is served by a build that sends no verdict, and inventing one from a stale
    // figure is what this whole file exists to prevent.
    render(
      <BudgetChip
        spent={3}
        cap={1}
        periodMode="cumulative"
        fallbackPeriod="cumulative"
        spendIsCurrent={false}
      />,
    );
    await openPopover();

    expect(stoppedText()).not.toContain('budget.popoverStopped');
  });

  it('names the date when the cap lifts, so waiting is a visible option', async () => {
    render(
      <BudgetChip
        spent={3}
        cap={1}
        periodMode="monthly"
        fallbackPeriod="cumulative"
        spendIsCurrent={false}
        blocked
        resetsAt="2026-10-01T00:00:00Z"
      />,
    );
    await openPopover();

    expect(stoppedText()).toContain('budget.popoverResetsOn');
    // A cap that comes back must NOT read as permanent.
    expect(stoppedText()).not.toContain('budget.popoverStoppedForGood');
  });
});

describe('BudgetChip - the colour the verdict paints', () => {
  it('paints the figure as over-cap on a server verdict, not just the popover', async () => {
    // The case the ratio cannot see: 6 spent against a cap of 10 is a calm 0.6, and the run
    // is refused anyway because a sub-agent in flight holds the other 4. Leaving the chip
    // grey while its own hover says the automation is stopped is the two halves of one
    // control disagreeing in front of the reader.
    render(
      <BudgetChip
        spent={6}
        cap={10}
        periodMode="cumulative"
        fallbackPeriod="cumulative"
        spendIsCurrent={false}
        blocked
      />,
    );

    expect(screen.getByTestId('budget-chip').className).toContain('text-red-600');
  });

  it('leaves an unblocked chip alone, whatever the popover would have guessed', () => {
    render(<BudgetChip spent={6} cap={10} periodMode="monthly" blocked={false} />);

    expect(screen.getByTestId('budget-chip').className).not.toContain('text-red-600');
  });

  it('still reddens from the figure when no verdict is supplied, as a workflow does', () => {
    render(<BudgetChip spent={10} cap={10} periodMode="monthly" />);

    expect(screen.getByTestId('budget-chip').className).toContain('text-red-600');
  });
});

