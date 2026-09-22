/**
 * @vitest-environment jsdom
 *
 * That the bell's trigger-row menu costs the row no width, and that when it appears the
 * row gets out of its way.
 *
 * The button is invisible almost all the time - it is revealed by hovering the row - and
 * it used to sit in the row's flex flow anyway, holding a column open on every row for a
 * control almost never on screen. The workflow name and the fire-time label paid for it,
 * on the narrowest surface in the app.
 *
 * Floating it fixes that and creates the problem the last tests pin: the dots land ON the
 * fire-time column. A mask painted behind the button did not settle it - it needs a width,
 * and the text under it is `whitespace-nowrap` and a different length in every language -
 * so the CONTENT yields instead, on the row's side, via a rule this module exports. The two
 * files therefore have to agree about when the menu is showing, which is what
 * `mirrors the reveal` checks.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import { TriggerRowActions, TRIGGER_ROW_ACTIONS_YIELD, hasTriggerRowActions } from '../TriggerRowActions';
import type { ActiveAutomation } from '@/lib/api/orchestrator/dashboard.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

vi.mock('@/lib/api/orchestrator/agenda.service', () => ({
  agendaService: { runNow: vi.fn().mockResolvedValue({}) },
  // Kept, because agendaErrors reads it on the refusal path: a module mock replaces the
  // WHOLE module, so omitting it turned a refused run into a TypeError inside the catch
  // block whose behaviour the test was there to check.
  agendaFailureOf: () => ({}),
}));

vi.mock('@/lib/api/orchestrator/resource-control', () => ({
  canControlProductionResource: (resource: ActiveAutomation) =>
    resource.resourceType === 'AGENT' || Boolean(resource.productionRunIdPublic),
  productionResourceKind: (resourceType: ActiveAutomation['resourceType']) => (
    resourceType === 'AGENT' ? 'agent' : resourceType === 'APPLICATION' ? 'interface' : 'workflow'
  ),
  setProductionResourcePaused: vi.fn().mockResolvedValue(undefined),
}));

// Not mocked, deliberately. The refresh's whole difficulty is the KEY: `useOrgScopedQuery`
// prefixes it with the active workspace, so a hand-written `['home-status']` invalidates
// nothing while looking exactly right. A mocked hook would assert that some function was
// called; the real one is driven here, against a spied `invalidateQueries`, so what is pinned
// is the KEY - that the rows actually come back is pinned against a real cache in
// useRefreshHomeStatus.freshness.test.tsx.
import { agendaService } from '@/lib/api/orchestrator/agenda.service';
import { setProductionResourcePaused } from '@/lib/api/orchestrator/resource-control';

let queryClient: QueryClient;
let invalidate: ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.mocked(agendaService.runNow).mockReset()
    .mockResolvedValue({ success: true, occurrenceConsumed: true });
  vi.mocked(setProductionResourcePaused).mockReset().mockResolvedValue(undefined);
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  invalidate = vi.fn();
  queryClient.invalidateQueries = invalidate as unknown as QueryClient['invalidateQueries'];
});

function automation(overrides: Partial<ActiveAutomation> = {}): ActiveAutomation {
  return {
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: 'Daily report',
    triggerType: 'SCHEDULE',
    schedule: {
      cronExpression: '0 9 * * *',
      timezone: 'UTC',
      nextFireAt: '2026-09-03T09:00:00Z',
      executionCount: 4,
      scheduleId: 'sched-1',
      armed: true,
    },
    ...overrides,
  };
}

function renderRow(
  a: ActiveAutomation,
  onResult: (kind: 'success' | 'error', message: string) => void = () => {},
) {
  // The real row: `group` drives the reveal, `relative` is what the button positions
  // against. Rendering the button bare would make the position assertions meaningless.
  return render(
    <QueryClientProvider client={queryClient}>
      <div className="group relative">
        <TriggerRowActions automation={a} onNavigate={() => {}} onResult={onResult} />
      </div>
    </QueryClientProvider>,
  );
}

/** Open the menu and pick one of its two run choices. */
function runFromMenu(label: 'runNow' | 'runInstead') {
  fireEvent.click(trigger());
  fireEvent.click(screen.getByText(label));
}

/** The trigger itself. Named, because once the menu is open the row has several buttons. */
function trigger(): HTMLElement {
  return screen.getByRole('button', { name: 'label' });
}

/**
 * The three situations in which the menu is on screen, and what each side must declare.
 *
 * <p>The pairing is not symmetric and cannot be compared by string: the button says
 * `data-[state=open]` about ITSELF where the row has to say `group-has-[...]` about a
 * descendant, and the button's own `focus:` is subsumed by the row's `group-focus-within`.
 * What has to match is the SITUATIONS, which is what this table names.
 */
const REVEAL_SITUATIONS: ReadonlyArray<{ situation: string; menu: string; row: string }> = [
  { situation: 'pointer on the row', menu: 'group-hover:opacity-100', row: 'group-hover:opacity-0' },
  { situation: 'keyboard focus in the row', menu: 'group-focus-within:opacity-100', row: 'group-focus-within:opacity-0' },
  { situation: 'its own menu open', menu: 'data-[state=open]:opacity-100', row: 'group-has-[[data-state=open]]:opacity-0' },
];

describe('TriggerRowActions', () => {
  it('renders nothing at all on a row with no schedule to act on', () => {
    // Webhook, chat and form rows have no schedule. With the button out of the flow there
    // is nothing to keep them aligned WITH, so the right answer here is an empty row and
    // not a stand-in element.
    const { container } = renderRow(
      automation({ triggerType: 'WEBHOOK', schedule: undefined, webhook: {} }),
    );

    expect(screen.queryByRole('button')).toBeNull();
    expect(container.querySelector('.group')?.children).toHaveLength(0);
  });

  it('takes no width from the row', () => {
    // The whole point: out of the flow, so the name and the fire-time label get the space
    // back. `absolute` is the assertion that matters - a flow element with opacity-0 still
    // occupies its column.
    renderRow(automation());

    expect(trigger().className).toContain('absolute');
    expect(trigger().className).toContain('right-1');
  });

  it('stays hidden until the row is hovered', () => {
    renderRow(automation());

    expect(trigger().className).toContain('opacity-0');
    expect(trigger().className).toContain('group-hover:opacity-100');
  });

  it('is reachable without a mouse', () => {
    // `group-focus-within` is the only thing that puts this control on screen for a
    // keyboard user; deleting it leaves every test above green and the menu unreachable.
    renderRow(automation());

    expect(trigger().className).toContain('group-focus-within:opacity-100');
  });

  it('hovers to a light ground, like the bell above it', () => {
    // `ghostGray` inverts on hover too, so the three dots became a black tile the moment
    // the pointer reached them - on a row that is already tinted by its own hover.
    renderRow(automation());

    expect(trigger().className).toContain('hover:bg-surface-hover');
  });

  it('is the app\'s own row-menu button, not a locally styled one', () => {
    // What replaced the mask. The button used to declare a background of its own to hide
    // the label behind it; it now declares none, and takes its hover ground from the shared
    // `ghostGray` variant like every other row menu in the app. Asserting the VARIANT is
    // what makes that meaningful: an assertion that "no background class is present" would
    // pass whatever the variant painted, since the variant's classes are not on this
    // element at all.
    renderRow(automation());

    expect(trigger().getAttribute('data-variant')).toBe('ghostGray');
  });

  it('renders no menu, and asks for no yielding, on a row with no schedule', () => {
    // Regression, user-visible: the rule was applied to EVERY row while the button renders
    // only for schedules, so hovering a webhook row faded its own "Live" label away and put
    // nothing in its place. The predicate is what the bell asks before applying the rule.
    const webhook = automation({ triggerType: 'WEBHOOK', schedule: undefined, webhook: {} });

    expect(hasTriggerRowActions(webhook)).toBe(false);
    expect(hasTriggerRowActions(automation())).toBe(true);

    renderRow(webhook);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('pauses a manual workflow resource even though it has no schedule', async () => {
    const onResult = vi.fn();
    const manual = automation({
      triggerType: 'MANUAL',
      schedule: undefined,
      productionRunIdPublic: 'run-public-1',
    });

    expect(hasTriggerRowActions(manual)).toBe(true);
    renderRow(manual, onResult);
    fireEvent.click(trigger());
    fireEvent.click(screen.getByText('pauseResource:{"type":"workflow"}'));

    await waitFor(() => expect(setProductionResourcePaused).toHaveBeenCalledWith(manual, true));
    expect(onResult).toHaveBeenCalledWith('success', 'resourcePaused:{"type":"workflow"}');
    // Pausing the resource changes the SAME rows a run does: the row must stop counting down to
    // a fire that will not happen, and the bell's imminent ring with it.
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({
      queryKey: ['org', '__personal__', 'home-status'],
    }));
  });

  it.each(REVEAL_SITUATIONS)(
    'shows the dots and stands the row down together: $situation',
    ({ menu, row }) => {
      // The dots and the fire-time column live in two different files and have to be on
      // screen in exactly the same situations. Drop one from either side and the result is
      // silent: the dots sit on readable text again, or the countdown blanks with nothing
      // over it. Both sides are checked here, and the row's side is the exported rule the
      // bell actually applies, not a copy of it.
      renderRow(automation());

      expect(trigger().className).toContain(menu);
      expect(TRIGGER_ROW_ACTIONS_YIELD).toContain(row);
    },
  );

  it('declares no reveal condition the other side does not know about', () => {
    // The mirror of the table: an extra `xl:opacity-100` on either side would satisfy every
    // case above and still leave the two disagreeing. Counting them closes that.
    renderRow(automation());
    const conditions = (className: string, value: string) =>
      className.split(/\s+/).filter((token) => token.endsWith(':opacity-' + value));

    // The button also carries a bare `focus:opacity-100`, which the row covers with
    // `group-focus-within` - hence four against three.
    expect(conditions(trigger().className, '100')).toHaveLength(REVEAL_SITUATIONS.length + 1);
    expect(conditions(TRIGGER_ROW_ACTIONS_YIELD, '0')).toHaveLength(REVEAL_SITUATIONS.length);
  });

  it('stays visible while its own menu is open, even though the pointer has left the row', () => {
    renderRow(automation());
    const button = trigger();

    expect(button.getAttribute('data-state')).toBe('closed');
    fireEvent.click(button);

    // Radix stamps the open state on the trigger and the class keys off it, so the button
    // cannot fade out from under a menu that is still on screen. The row reads the same
    // attribute through `group-has-`, so the countdown stays out of the way with it.
    expect(button.getAttribute('data-state')).toBe('open');
    expect(button.className).toContain('data-[state=open]:opacity-100');
    expect(TRIGGER_ROW_ACTIONS_YIELD).toContain('group-has-[[data-state=open]]:opacity-0');
  });

  describe('the sentence follows what the platform actually did', () => {
    /**
     * The bell used to say "in place of the scheduled run" whenever the request came back
     * 2xx. The platform refuses to give the occurrence up in several ordinary situations -
     * the daemon claimed the fire first, the cron has no later slot, the write was refused
     * after the run had already started - and in every one of them the scheduled run still
     * happens. Announcing a replacement that did not occur is the same defect this work
     * exists to remove, moved from the schedule row into the copy.
     */
    it('claims the replacement only when the occurrence was consumed', async () => {
      const onResult = vi.fn();
      renderRow(automation(), onResult);
      runFromMenu('runInstead');

      await waitFor(() => expect(onResult).toHaveBeenCalledWith('success', 'ranReplacing'));
    });

    it('says the scheduled run still happens when it was NOT consumed', async () => {
      vi.mocked(agendaService.runNow).mockResolvedValue({
        success: true,
        occurrenceConsumed: false,
      });
      const onResult = vi.fn();
      renderRow(automation(), onResult);
      runFromMenu('runInstead');

      await waitFor(() => expect(onResult).toHaveBeenCalledWith('success', 'ranKeeping'));
    });

    it('says the same when the platform answers nothing about it', async () => {
      // An older backend, or any response that omits the field. Absent is not "yes": the
      // safe reading of silence is that the scheduled run still stands.
      vi.mocked(agendaService.runNow).mockResolvedValue({ success: true });
      const onResult = vi.fn();
      renderRow(automation(), onResult);
      runFromMenu('runInstead');

      await waitFor(() => expect(onResult).toHaveBeenCalledWith('success', 'ranKeeping'));
    });

    it('never claims a replacement on the run that keeps the occurrence', async () => {
      // Even if the platform were to answer true, "Run now" did not ask to consume
      // anything, and the menu promised the scheduled run would still happen.
      vi.mocked(agendaService.runNow).mockResolvedValue({
        success: true,
        occurrenceConsumed: true,
      });
      const onResult = vi.fn();
      renderRow(automation(), onResult);
      runFromMenu('runNow');

      await waitFor(() => expect(onResult).toHaveBeenCalledWith('success', 'ranKeeping'));
    });
  });

  describe('after a run, the row is asked for again', () => {
    /**
     * The row IS a countdown to a fire time these two actions move, and the list behind it
     * polls once a minute. Without asking again the user runs the occurrence, is told it
     * worked, and watches the same "in 4 min" for up to sixty seconds - which is
     * indistinguishable from the action having done nothing, and is exactly how the
     * run-instead defect was reported.
     */
    it('asks again after consuming the occurrence, so the countdown moves', async () => {
      renderRow(automation());
      runFromMenu('runInstead');

      await waitFor(() => expect(agendaService.runNow).toHaveBeenCalledWith('sched-1', false));
      await waitFor(() => expect(invalidate).toHaveBeenCalled());
    });

    it('asks again after an early run that KEEPS the occurrence', async () => {
      // "Run now" moves last-ran rather than next-fire, and that line is on the row too.
      renderRow(automation());
      runFromMenu('runNow');

      await waitFor(() => expect(agendaService.runNow).toHaveBeenCalledWith('sched-1', true));
      await waitFor(() => expect(invalidate).toHaveBeenCalled());
    });

    it('asks on the ORG-SCOPED key, which is the only one the cache answers to', async () => {
      // The trap this pins: `useOrgScopedQuery` prefixes every key with the active
      // workspace, so a plain `['home-status']` matches no cached query. It invalidates
      // nothing, throws nothing, and leaves the row frozen - the same symptom as having
      // written no refresh at all.
      renderRow(automation());
      runFromMenu('runInstead');

      await waitFor(() => expect(invalidate).toHaveBeenCalled());
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: ['org', '__personal__', 'home-status'],
      });
    });

    it('does not ask again when the run was refused - nothing changed', async () => {
      // A refusal leaves the schedule exactly as it was. Refetching would spend a round
      // trip to redraw an identical row, and would suggest something moved.
      vi.mocked(agendaService.runNow).mockRejectedValue(new Error('nope'));
      const onResult = vi.fn();
      renderRow(automation(), onResult);
      runFromMenu('runInstead');

      await waitFor(() => expect(onResult).toHaveBeenCalledWith('error', expect.anything()));
      expect(invalidate).not.toHaveBeenCalled();
    });
  });

  describe('a schedule its spending cap is refusing', () => {
    /** The row shape the server sends once a cap is holding the schedule back. */
    const capped = () => automation({
      resourceType: 'AGENT',
      resourceId: 'agent-1',
      name: 'Reporter',
      schedule: {
        cronExpression: '0 9 * * *',
        timezone: 'UTC',
        nextFireAt: '2026-09-03T09:00:00Z',
        executionCount: 4,
        scheduleId: 'sched-1',
        // Still ARMED, and that is the point: the schedule is healthy, the cap is what
        // refuses the run. A row that read `armed` alone would see nothing wrong.
        armed: true,
        budgetBlocked: true,
      },
    });

    it('does not let either run choice be clicked', () => {
      // The engine refuses the fire whoever asked for it, so an enabled button here is a
      // button whose only possible outcome is a failure toast.
      renderRow(capped());
      fireEvent.click(trigger());

      expect((screen.getByText('runNow').closest('button') as HTMLButtonElement).disabled).toBe(true);
      expect((screen.getByText('runInstead').closest('button') as HTMLButtonElement).disabled).toBe(true);
    });

    it('spends no round trip trying', () => {
      renderRow(capped());
      runFromMenu('runNow');

      expect(agendaService.runNow).not.toHaveBeenCalled();
    });

    it('says WHY, rather than just going grey', () => {
      // A disabled control with no explanation reads as a bug. The hint is the only place
      // the reason can appear on this surface.
      renderRow(capped());
      fireEvent.click(trigger());

      expect(screen.getAllByText('runNowBudgetBlocked').length).toBeGreaterThan(0);
    });

    it('leaves an unblocked row exactly as it was', () => {
      // The regression this kind of gate causes: every other row loses its actions.
      renderRow(automation());
      fireEvent.click(trigger());

      expect((screen.getByText('runNow').closest('button') as HTMLButtonElement).disabled).toBe(false);
      expect(screen.getByText('runNowHint')).toBeTruthy();
    });
  });
});
