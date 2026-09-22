// @vitest-environment jsdom
import React from 'react';
import { readFileSync } from 'fs';
import { join } from 'path';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

/**
 * The price-sanity flag stopped blocking, and this panel is where that has to
 * be visible.
 *
 * Until this file existed the component had no test of its own: both
 * CatalogBundlesPanel suites `vi.mock` it away. That is the exact shape of a
 * green-but-dead feature, and the behaviours rewired here are invisible to
 * every backend test:
 *
 *   - Apply must stay ENABLED when rows are flagged. It used to be disabled
 *     until the operator ticked the override, which was right while one flagged
 *     price cancelled the whole refresh and is wrong now that the refresh lands
 *     without those rows.
 *   - The override checkbox must appear on `flagged.length > 0`. It used to be
 *     driven by a price-sanity entry in `guardFailures`, which the backend no
 *     longer emits, so reading it there would hide the checkbox forever and
 *     leave no way to accept a held-back price.
 *   - An override must never be sent once its checkbox is off screen, and the
 *     copy must never describe a state the panel is not in.
 *
 * Driven through the REAL message file rather than a stub translator, so a key
 * that does not exist shows up as its own path and fails the assertion instead
 * of silently rendering nothing.
 */

const dryRun = vi.fn();
const apply = vi.fn();

vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    catalogSyncDryRun: (...args: unknown[]) => dryRun(...args),
    catalogSyncApply: (...args: unknown[]) => apply(...args),
  },
}));

import { CatalogSyncPanel } from '../CatalogSyncPanel';

const messages = JSON.parse(
  readFileSync(
    join(__dirname, '..', '..', '..', '..', '..', '..', '..', 'messages', 'en.json'),
    'utf8',
  ),
);

/** A dry-run plan carrying one flagged row and NO guard failure at all. */
function planWithFlaggedRow(overrides: Record<string, unknown> = {}) {
  return {
    applied: false,
    inserted: 0,
    updatedCount: 0,
    deprecated: 0,
    syncLogId: 1,
    plan: {
      stats: {
        liteLlmKept: 10,
        openRouterKept: 10,
        liteLlmRejected: {},
        openRouterRejected: {},
      },
      added: [],
      updated: [{ provider: 'openrouter', modelId: 'z-ai/glm-5.2' }],
      unchanged: 3,
      flagged: [
        {
          provider: 'openrouter',
          modelId: 'z-ai/glm-5.2',
          reason: 'priceInput changed >50% (0.683200 → 1.4000)',
          oldPriceInput: '0.683200',
          newPriceInput: '1.4000',
          oldPriceOutput: '2.000000',
          newPriceOutput: '4.400000',
        },
      ],
      // The crux: empty. The backend stopped turning a flagged price into a
      // guard failure when it stopped cancelling the run over one.
      guardFailures: [],
      discovery: { models: [], discoveredByProvider: {}, skippedProviders: [] },
    },
    ...overrides,
  };
}

function renderPanel() {
  return render(
    <NextIntlClientProvider locale="en" messages={messages}>
      <CatalogSyncPanel />
    </NextIntlClientProvider>,
  );
}

async function openTheDryRunModal() {
  fireEvent.click(screen.getByRole('button', { name: /refresh/i }));
  await waitFor(() => expect(screen.getByRole('dialog')).toBeTruthy());
}

describe('CatalogSyncPanel - held-back rows do not block the refresh', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dryRun.mockResolvedValue(planWithFlaggedRow());
  });

  it('leaves Apply enabled when a row is flagged and no guard failed', async () => {
    renderPanel();
    await openTheDryRunModal();

    const applyButton = screen.getByRole('button', { name: /^apply$/i });
    // Honest about its own reach: this fixture carries `guardFailures: []`, and
    // the pre-change component read price-sanity out of THAT, so it left Apply
    // enabled here too. This assertion would not have failed against it.
    //
    // What it pins is the forward rule: a flagged row must never disable Apply.
    // Re-adding a `hasHeldBackRows && !overridePriceSanity` term to `disabled`
    // makes it fail, and that term is what made a refresh all-or-nothing from
    // the operator's side.
    expect((applyButton as HTMLButtonElement).disabled).toBe(false);

    // And it stays enabled either way, so the button never depends on a choice
    // that is only about which rows go in.
    fireEvent.click(screen.getByRole('checkbox'));
    await waitFor(() =>
      expect(
        (screen.getByRole('button', { name: /^apply$/i }) as HTMLButtonElement).disabled,
      ).toBe(false),
    );
  });

  it('offers the override checkbox on a flagged row even though guardFailures is empty', async () => {
    renderPanel();
    await openTheDryRunModal();

    expect(screen.getByText(messages.aiProviders.catalogSync.overridePriceSanity)).toBeTruthy();
    // And says why those rows are missing from the run, rather than leaving the
    // operator to infer it from a count.
    expect(screen.getByText(messages.aiProviders.catalogSync.flaggedRowsHint)).toBeTruthy();

    // Ticking the box makes that sentence false - the rows are no longer being
    // left out - so it has to go. This assertion is the one that was missing
    // while the copy said the wrong thing for three review rounds.
    fireEvent.click(screen.getByRole('checkbox'));
    await waitFor(() =>
      expect(
        screen.queryByText(messages.aiProviders.catalogSync.flaggedRowsHint),
      ).toBeNull(),
    );
    // The COUNT stays, because it is a fact about the plan rather than a
    // promise about the run, and the operator still needs to see it.
    expect(screen.getByText(/1 flagged price change/i)).toBeTruthy();
  });

  it('hides the held-back explanation when a blocking guard failed, and offers a way past it', async () => {
    // The hint promises "every other row still lands". Under count-floor that
    // is false: the apply answers 412 and writes nothing. This is the half of
    // the gate that had no test, and it is reachable by design rather than by
    // accident - widening a parser's drop list shrinks the feed on purpose, and
    // a deliberate shrink looks exactly like the truncated response this guard
    // exists to reject.
    dryRun.mockResolvedValue(
      planWithFlaggedRow({
        plan: {
          ...planWithFlaggedRow().plan,
          guardFailures: [
            { guard: 'count-floor', detail: 'openrouter feed size 280 < 80% of 356', data: {} },
          ],
        },
      }),
    );
    renderPanel();
    await openTheDryRunModal();

    expect(
      screen.queryByText(messages.aiProviders.catalogSync.flaggedRowsHint),
    ).toBeNull();

    // And the dialog is not a dead end: count-floor blocks the whole apply, so
    // without a control the operator reads a 412 banner and has nowhere to go.
    const countFloorBox = screen.getByText(messages.aiProviders.catalogSync.overrideCountFloor);
    expect(countFloorBox).toBeTruthy();

    const boxes = screen.getAllByRole('checkbox');
    fireEvent.click(boxes[0]);
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));
    await waitFor(() => expect(apply).toHaveBeenCalledWith(['count-floor']));
  });

  it('sends BOTH overrides when both guards are in play, not just one', async () => {
    // This is the documented first-run state after a filter is widened:
    // count-floor fires on the shrunken feed AND rows are flagged. An overrides
    // array that let one win would put the operator straight back in the 412
    // loop having ticked the box that was supposed to get them out.
    dryRun.mockResolvedValue(
      planWithFlaggedRow({
        plan: {
          ...planWithFlaggedRow().plan,
          guardFailures: [
            { guard: 'count-floor', detail: 'openrouter feed size 280 < 80% of 356', data: {} },
          ],
        },
      }),
    );
    apply.mockResolvedValue(planWithFlaggedRow({ applied: true, inserted: 1, updatedCount: 1 }));
    renderPanel();
    await openTheDryRunModal();

    const boxes = screen.getAllByRole('checkbox');
    expect(boxes).toHaveLength(2);
    boxes.forEach((box) => fireEvent.click(box));
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));

    await waitFor(() => expect(apply).toHaveBeenCalledTimes(1));
    expect(apply.mock.calls[0][0]).toEqual(
      expect.arrayContaining(['price-sanity', 'count-floor']),
    );
    expect(apply.mock.calls[0][0]).toHaveLength(2);
  });

  it('never sends an override whose checkbox is not on screen', async () => {
    // A tick can outlive the control that set it. An apply that fails inside
    // the merge answers 200 with applied:false and an EMPTY guardFailures, so
    // the count-floor box unmounts while its state stays true - and every later
    // Apply would keep sending an override the operator can no longer see or
    // withdraw. The dialog must never send what it is not currently showing.
    dryRun.mockResolvedValue(
      planWithFlaggedRow({
        plan: {
          ...planWithFlaggedRow().plan,
          guardFailures: [{ guard: 'count-floor', detail: 'feed shrank', data: {} }],
        },
      }),
    );
    // First apply: overrides accepted, but the merge itself failed.
    apply.mockResolvedValueOnce(planWithFlaggedRow({ applied: false }));
    renderPanel();
    await openTheDryRunModal();

    screen.getAllByRole('checkbox').forEach((box) => fireEvent.click(box));
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));
    await waitFor(() => expect(apply).toHaveBeenCalledTimes(1));
    expect(apply.mock.calls[0][0]).toEqual(expect.arrayContaining(['count-floor']));

    // The failed response carries no guard failure, so the box is gone.
    await waitFor(() =>
      expect(
        screen.queryByText(messages.aiProviders.catalogSync.overrideCountFloor),
      ).toBeNull(),
    );

    apply.mockResolvedValue(planWithFlaggedRow({ applied: true, inserted: 1, updatedCount: 1 }));
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));
    await waitFor(() => expect(apply).toHaveBeenCalledTimes(2));
    expect(apply.mock.calls[1][0]).not.toContain('count-floor');
  });

  it('never sends the price-sanity override once its rows are gone either', async () => {
    // The mirror of the case above, and it needs saying separately: the two
    // overrides are gated by two different expressions, so pinning one proves
    // nothing about the other. Deleting `&& hasHeldBackRows` used to leave the
    // whole suite green while `&& hasCountFloorFailure` was covered.
    apply.mockResolvedValueOnce(
      planWithFlaggedRow({
        applied: false,
        plan: { ...planWithFlaggedRow().plan, flagged: [] },
      }),
    );
    renderPanel();
    await openTheDryRunModal();

    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));
    await waitFor(() => expect(apply).toHaveBeenCalledTimes(1));
    expect(apply.mock.calls[0][0]).toEqual(['price-sanity']);

    // The response carries no flagged rows, so the control is gone.
    await waitFor(() =>
      expect(
        screen.queryByText(messages.aiProviders.catalogSync.overridePriceSanity),
      ).toBeNull(),
    );

    apply.mockResolvedValue(planWithFlaggedRow({ applied: true, inserted: 1, updatedCount: 1 }));
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));
    await waitFor(() => expect(apply).toHaveBeenCalledTimes(2));
    expect(apply.mock.calls[1][0]).toEqual([]);

    // And the REPORT has to agree with the send. They are two readings of one
    // decision, so pinning only the send lets them drift: the override was not
    // sent, the rows really were withheld, and a summary computed from the raw
    // tick would tell the operator nothing was held back.
    await waitFor(() =>
      expect(screen.getByText(/1 price change was held back/i)).toBeTruthy(),
    );
  });

  it('says there is nothing to apply even while a blocking guard is showing', async () => {
    // A truncated feed can fire count-floor while every surviving row is
    // unchanged. The explanation used to be suppressed by the guard failure, so
    // the dialog showed a checkbox above an Apply that could never enable and
    // no line saying why.
    dryRun.mockResolvedValue(
      planWithFlaggedRow({
        plan: {
          ...planWithFlaggedRow().plan,
          added: [],
          updated: [],
          flagged: [],
          guardFailures: [{ guard: 'count-floor', detail: 'feed shrank', data: {} }],
        },
      }),
    );
    renderPanel();
    await openTheDryRunModal();

    expect(screen.getByText(messages.aiProviders.catalogSync.noChanges)).toBeTruthy();
    expect(
      (screen.getByRole('button', { name: /^apply$/i }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('shows a failed apply inside the dialog, where the operator is looking', async () => {
    // The same message renders in the card behind the overlay. Without this the
    // only visible change after a failed apply is a vanished sentence.
    apply.mockResolvedValue(planWithFlaggedRow({ applied: false }));
    renderPanel();
    await openTheDryRunModal();

    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));
    await waitFor(() => expect(apply).toHaveBeenCalledTimes(1));

    const alerts = await screen.findAllByRole('alert');
    expect(
      alerts.some((el) => el.textContent === messages.aiProviders.catalogSync.applyRejected),
    ).toBe(true);
  });

  it('stops promising a partial refresh once the apply itself has failed', async () => {
    // A merge failure answers 200 with applied:false and an EMPTY
    // guardFailures, so both of the hint's other conditions pass while nothing
    // landed at all. The dialog stays open and the red banner is in the card
    // BEHIND the overlay, so this sentence is the only thing the operator can
    // read - and it would be telling them the refresh applied without those
    // rows when it applied nothing.
    apply.mockResolvedValue(planWithFlaggedRow({ applied: false }));
    renderPanel();
    await openTheDryRunModal();

    expect(screen.getByText(messages.aiProviders.catalogSync.flaggedRowsHint)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));
    await waitFor(() => expect(apply).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(
        screen.queryByText(messages.aiProviders.catalogSync.flaggedRowsHint),
      ).toBeNull(),
    );
  });

  it('forgets a tick when a fresh refresh is started', async () => {
    // Each dry-run is a new decision. A tick surviving into an unrelated later
    // refresh would silently override a guard the operator never saw fire.
    dryRun.mockResolvedValue(
      planWithFlaggedRow({
        plan: {
          ...planWithFlaggedRow().plan,
          guardFailures: [{ guard: 'count-floor', detail: 'feed shrank', data: {} }],
        },
      }),
    );
    apply.mockResolvedValue(planWithFlaggedRow({ applied: true, inserted: 1, updatedCount: 1 }));
    renderPanel();
    await openTheDryRunModal();

    screen.getAllByRole('checkbox').forEach((box) => fireEvent.click(box));
    fireEvent.click(screen.getByRole('button', { name: /^cancel$/i }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    await openTheDryRunModal();
    screen
      .getAllByRole('checkbox')
      .forEach((box) => expect((box as HTMLInputElement).getAttribute('data-state')).toBe('unchecked'));

    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));
    await waitFor(() => expect(apply).toHaveBeenCalledTimes(1));
    expect(apply.mock.calls[0][0]).toEqual([]);
  });

  it('shows no count-floor control for a guard failure that is not count-floor', async () => {
    // The checkbox sends overrideGuards=count-floor specifically. Offering it
    // for any guard at all would invite an operator to override something the
    // control does not name and the request does not carry.
    dryRun.mockResolvedValue(
      planWithFlaggedRow({
        plan: {
          ...planWithFlaggedRow().plan,
          guardFailures: [{ guard: 'some-future-guard', detail: 'unrelated', data: {} }],
        },
      }),
    );
    renderPanel();
    await openTheDryRunModal();

    expect(
      screen.queryByText(messages.aiProviders.catalogSync.overrideCountFloor),
    ).toBeNull();
    expect(screen.getAllByRole('checkbox')).toHaveLength(1);
  });

  it('reports the withheld row after Apply, because the dialog closes on success', async () => {
    apply.mockResolvedValue(
      planWithFlaggedRow({ applied: true, inserted: 2, updatedCount: 5, deprecated: 0 }),
    );
    renderPanel();
    await openTheDryRunModal();

    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));

    await waitFor(() => expect(apply).toHaveBeenCalledWith([]));
    // The count is the point: without it the only trace of a partial refresh
    // lives in a modal the operator has just dismissed.
    await waitFor(() =>
      expect(screen.getByText(/1 price change was held back/i)).toBeTruthy(),
    );
  });

  it('does not call a withheld row withheld when the operator opted it in', async () => {
    apply.mockResolvedValue(
      planWithFlaggedRow({ applied: true, inserted: 2, updatedCount: 6, deprecated: 0 }),
    );
    renderPanel();
    await openTheDryRunModal();

    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }));

    // Same flagged list comes back on the response, but it went THROUGH, so
    // announcing it as held back would describe the opposite of what happened.
    await waitFor(() => expect(apply).toHaveBeenCalledWith(['price-sanity']));
    await waitFor(() => expect(screen.queryByText(/held back/i)).toBeNull());
  });
});
