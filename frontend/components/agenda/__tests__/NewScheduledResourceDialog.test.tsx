/**
 * @vitest-environment jsdom
 *
 * That the calendar's own answer - the slot the user clicked - is what the dialog opens on.
 *
 * A click carries information: a weekday and an hour from the time grids, a day of the
 * month from the month grid. Losing it and opening on "every day at 09:00" would make the
 * click decorative, which is the whole point of creating from the calendar rather than from
 * the workflow list. And the proposal is PERIODIC, because a calendar is where people go to
 * arrange the thing that repeats.
 *
 * <p>Everything below the proposal is the workflow builder's own: the same frequency
 * catalogue, the same pickers, the same backend-described preview. These tests assert the
 * seeding and the wiring; the catalogue's behaviour is covered where it lives.
 *
 * <p>The dialog answers for TWO kinds, and the second half of this file is about the ways
 * they must differ: an agent is not created here, and it arms at a different moment than a
 * workflow, so the same screen has to say a different thing.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { NewScheduledResourceDialog } from '../NewScheduledResourceDialog';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
}));

const validateCron = vi.fn().mockResolvedValue({ valid: true, description: 'ok' });
vi.mock('@/lib/api/orchestrator', () => ({
  scheduleSettingsService: { validateCron: (...args: unknown[]) => validateCron(...args) },
}));

/** Thursday 2026-09-03, midnight UTC: the instant a day cell carries. */
const THURSDAY = new Date('2026-09-03T00:00:00Z');

function renderDialog(
  slot: { day: Date; hour: number | null },
  onConfirm: (input: {
    kind: string;
    name: string;
    cron: string;
    timezone: string;
    cronDescription?: string;
  }) => void = () => {},
) {
  return render(
    <NewScheduledResourceDialog
      slot={slot}
      timezone="UTC"
      submitting={false}
      error={null}
      onCancel={() => {}}
      onConfirm={onConfirm}
    />,
  );
}

/**
 * The cron the dialog would submit, read off the confirm handler.
 *
 * <p>Settles the preview first, because Create is deliberately disabled while an answer is
 * in flight: without a verdict there is nothing to gate on, and the button being live in
 * that window is how an unchecked expression used to get through.
 */
async function submitted(onConfirm: ReturnType<typeof vi.fn>) {
  await settlePreview();
  fireEvent.change(screen.getByLabelText('create.nameLabel'), { target: { value: 'Report' } });
  fireEvent.click(screen.getByText('create.confirm'));
  return onConfirm.mock.calls[0][0];
}

beforeEach(() => {
  validateCron.mockClear();
  validateCron.mockResolvedValue({ valid: true, description: 'ok' });
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

/** Let the debounced preview fire and its promise settle. */
async function settlePreview() {
  await act(async () => {
    vi.advanceTimersByTime(500);
    await Promise.resolve();
  });
}

describe('creating a scheduled workflow from a slot', () => {
  it('proposes the clicked weekday and hour, as a weekly schedule', async () => {
    // The hour grid's click carries both facts. Thursday is cron weekday 4, and 16:00 is
    // the hour that was pointed at; a proposal that kept only one of them would be a
    // different request than the one the user made.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    expect((await submitted(onConfirm)).cron).toBe('0 16 * * 4');
  });

  it('proposes the clicked day of the month when the click carried no hour', async () => {
    // The month grid has no hours to click, so the day is what it knows. Inventing a
    // precise time would be pretending the user chose one; 09:00 is stated in the preview
    // and changeable in one control.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: null }, onConfirm);

    expect((await submitted(onConfirm)).cron).toBe('0 9 3 * *');
  });

  it('carries the calendar\'s display zone, not the browser\'s', async () => {
    // The agenda deliberately lets a workspace be read in another zone, and the schedule
    // has to be created in the zone the user was reading, or it fires an offset away.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    expect((await submitted(onConfirm)).timezone).toBe('UTC');
  });

  it('refuses to create without a name', async () => {
    // The only thing the slot cannot answer for the user.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);
    await settlePreview();

    fireEvent.click(screen.getByText('create.confirm'));

    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('refuses to create while the cron is still being checked', async () => {
    // The window the first gate missed. A verdict that has not arrived is not a verdict:
    // with Create live during the debounce, typing nonsense and clicking straight away
    // submitted it against the PREVIOUS cron's green answer.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);
    await settlePreview();
    fireEvent.change(screen.getByLabelText('create.nameLabel'), { target: { value: 'Report' } });

    fireEvent.click(screen.getByLabelText('frequency'));
    fireEvent.click(screen.getByText('freq_advanced'));
    fireEvent.change(screen.getByLabelText('cronExpression'), { target: { value: 'garbage' } });
    fireEvent.click(screen.getByText('create.confirm'));

    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('drops the previous verdict the moment the cron changes', async () => {
    // The other half: the stale answer must not survive the edit, or the gate is reading
    // about a schedule the user has already replaced.
    renderDialog({ day: THURSDAY, hour: 16 });
    await settlePreview();

    fireEvent.click(screen.getByLabelText('frequency'));
    fireEvent.click(screen.getByText('freq_advanced'));

    expect(screen.getByText('validating')).toBeTruthy();
  });

  it('asks the backend what the cron means, in the display zone', async () => {
    // The frontend never describes a cron itself; the preview is the same endpoint the
    // builder's inspector asks, so both surfaces say the same thing about the same schedule.
    renderDialog({ day: THURSDAY, hour: 16 });
    await settlePreview();

    expect(validateCron).toHaveBeenCalledWith('0 16 * * 4', 'UTC');
  });

  it('asks once for a cron being typed, not once per keystroke', async () => {
    // Advanced mode is a text field. Undebounced, a nine-character expression is nine round
    // trips, eight of which describe half-written crons nobody will ever see.
    renderDialog({ day: THURSDAY, hour: 16 });
    await settlePreview();
    validateCron.mockClear();

    fireEvent.click(screen.getByLabelText('frequency'));
    fireEvent.click(screen.getByText('freq_advanced'));
    const field = screen.getByLabelText('cronExpression');
    fireEvent.change(field, { target: { value: '0 9 * * ' } });
    fireEvent.change(field, { target: { value: '0 9 * * 1' } });
    await settlePreview();

    expect(validateCron).toHaveBeenCalledTimes(1);
    expect(validateCron).toHaveBeenLastCalledWith('0 9 * * 1', 'UTC');
  });

  it('refuses to create a cron the backend has rejected', async () => {
    // The failure this prevents is silent and late: the plan saves fine, and only when the
    // workflow is later set as production does the schedule sync throw - where it is caught
    // and logged. The user ends up with a workflow that looks armed, has no schedule row,
    // and was never told.
    validateCron.mockResolvedValue({ valid: false });
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);
    await settlePreview();

    fireEvent.change(screen.getByLabelText('create.nameLabel'), { target: { value: 'Report' } });
    fireEvent.click(screen.getByText('create.confirm'));

    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('still creates when the preview could not be reached', async () => {
    // Unknown is not invalid. Refusing on a network failure would make an offline blip look
    // like a bad schedule, and the builder does not do that either.
    validateCron.mockRejectedValue(new Error('offline'));
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);
    await settlePreview();

    fireEvent.change(screen.getByLabelText('create.nameLabel'), { target: { value: 'Report' } });
    fireEvent.click(screen.getByText('create.confirm'));

    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('warns that a monthly day past the 28th skips months', () => {
    // Clicking the 31st is easy and the consequence is invisible: February and every 30-day
    // month are silently skipped. Its OWN sentence, naming the day - the calendar's
    // `dayOfMonthUnsafe` says such a schedule CANNOT exist, which is the platform refusing a
    // move, not this dialog allowing a creation.
    renderDialog({ day: new Date('2026-08-31T00:00:00Z'), hour: null });

    expect(screen.getByText('create.monthlyDayGap:{"day":31}')).toBeTruthy();
    expect(screen.queryByText('errors.dayOfMonthUnsafe')).toBeNull();
  });

  it('does not warn on a day that exists in every month', () => {
    renderDialog({ day: THURSDAY, hour: null });

    expect(screen.queryByText(/create.monthlyDayGap/)).toBeNull();
  });

  it('keeps the time already chosen when switching between shapes that have one', async () => {
    // Weekly 16:00 -> daily must stay 16:00. Resetting to the catalogue default would throw
    // away the one thing the click actually told us.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    fireEvent.click(screen.getByLabelText('frequency'));
    fireEvent.click(screen.getByText('freq_daily_custom'));

    expect((await submitted(onConfirm)).cron).toBe('0 9 * * *');
  });

  it('takes a preset\'s cron exactly as the catalogue states it', async () => {
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    fireEvent.click(screen.getByLabelText('frequency'));
    fireEvent.click(screen.getByText('freq_every_15_minutes'));

    expect((await submitted(onConfirm)).cron).toBe('*/15 * * * *');
  });

  it('says the schedule is not live yet, because a draft never fires', () => {
    // A schedule row is armed only while the workflow has a production version. Without
    // this sentence the user leaves believing Thursday is handled.
    renderDialog({ day: THURSDAY, hour: 16 });

    expect(screen.getByText('create.notLiveYet')).toBeTruthy();
  });

  it('hands the whole catalogue over, not a calendar-sized subset of it', () => {
    // "Finer configuration, like in the workflow builder" is the request this answers: the
    // dropdown is the builder's own, so anything expressible there is expressible here.
    renderDialog({ day: THURSDAY, hour: 16 });

    fireEvent.click(screen.getByLabelText('frequency'));

    for (const option of ['freq_every_15_minutes', 'freq_every_weekday', 'freq_advanced']) {
      expect(screen.getByText(option)).toBeTruthy();
    }
  });
});

describe('choosing what the slot is filled with', () => {
  /** Press the Agent card, then name it and confirm; returns what the page was handed. */
  async function submitAsAgent(onConfirm: ReturnType<typeof vi.fn>) {
    await settlePreview();
    fireEvent.click(screen.getByText('create.kindAgent'));
    fireEvent.change(screen.getByLabelText('create.nameLabel'), { target: { value: 'Briefing' } });
    fireEvent.click(screen.getByText('create.agentConfirm'));
    return onConfirm.mock.calls[0][0];
  }

  it('defaults to the workflow, which is what this dialog used to be', async () => {
    // The kind travels on every confirm, including the one nobody touched: a page that has
    // to guess "no kind means workflow" is one refactor away from guessing wrong.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    expect((await submitted(onConfirm)).kind).toBe('WORKFLOW');
  });

  it('reports the agent kind once its card is picked', async () => {
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    expect((await submitAsAgent(onConfirm)).kind).toBe('AGENT');
  });

  it('keeps the clicked slot when the kind changes', async () => {
    // The slot is the answer the user already gave. Resetting the proposal on a kind switch
    // would throw away the click and quietly reschedule what they picked.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    expect((await submitAsAgent(onConfirm)).cron).toBe('0 16 * * 4');
  });

  it('says an agent starts on save, NOT that it waits for a production version', async () => {
    // The two kinds arm at opposite moments and this is the sentence that says which. The
    // workflow's "not live yet" shown over an agent would tell the user nothing will happen
    // on Thursday, minutes before something does.
    renderDialog({ day: THURSDAY, hour: 16 });
    await settlePreview();

    fireEvent.click(screen.getByText('create.kindAgent'));

    expect(screen.getByText('create.agentLiveOnSave')).toBeTruthy();
    expect(screen.queryByText('create.notLiveYet')).toBeNull();
  });

  it('labels the agent button Continue, because nothing is created by pressing it', async () => {
    // It opens the agent form. A "Create" label would promise a row that does not exist
    // until that form is saved, and a user who closes it would go looking for an agent.
    renderDialog({ day: THURSDAY, hour: 16 });
    await settlePreview();

    fireEvent.click(screen.getByText('create.kindAgent'));

    expect(screen.getByText('create.agentTitle')).toBeTruthy();
    expect(screen.getByText('create.agentConfirm')).toBeTruthy();
    expect(screen.queryByText('create.confirm')).toBeNull();
  });

  it('passes on what the platform said the cron means', async () => {
    // So the agent form can show the sentence this dialog showed instead of a raw
    // expression the user never typed.
    validateCron.mockResolvedValue({ valid: true, description: 'Every Thursday at 16:00' });
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    expect((await submitAsAgent(onConfirm)).cronDescription).toBe('Every Thursday at 16:00');
  });

  it('refuses an agent on a cron the backend rejected, exactly as it refuses a workflow', async () => {
    // The agent branch writes the cron IMMEDIATELY rather than at pin time, so letting a
    // refused expression through here buys a refusal one screen later, from the schedule
    // write, after the agent already exists.
    validateCron.mockResolvedValue({ valid: false });
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);
    await settlePreview();

    fireEvent.click(screen.getByText('create.kindAgent'));
    fireEvent.change(screen.getByLabelText('create.nameLabel'), { target: { value: 'Briefing' } });
    fireEvent.click(screen.getByText('create.agentConfirm'));

    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('goes back to the workflow wording when the kind is switched back', async () => {
    // The kind is a mode, not a one-way door. A sentence or a button label left behind from
    // the other kind is the dialog describing something it is no longer about to do.
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);
    await settlePreview();

    fireEvent.click(screen.getByText('create.kindAgent'));
    fireEvent.click(screen.getByText('create.kindWorkflow'));

    expect(screen.getByText('create.notLiveYet')).toBeTruthy();
    expect(screen.queryByText('create.agentLiveOnSave')).toBeNull();
    expect((await submitted(onConfirm)).kind).toBe('WORKFLOW');
  });

  it('drops a workflow refusal when the kind is switched to agent', async () => {
    // The dialog stays open on a refused workflow save so the user does not lose what they
    // typed. Switching to Agent then leaves a red "could not create the workflow" under a
    // dialog that is not going to attempt that write at all.
    const { rerender } = renderDialog({ day: THURSDAY, hour: 16 });
    await settlePreview();
    rerender(
      <NewScheduledResourceDialog
        slot={{ day: THURSDAY, hour: 16 }}
        timezone="UTC"
        submitting={false}
        error="errors.moveFailed"
        onCancel={() => {}}
        onConfirm={() => {}}
      />,
    );
    expect(screen.getByRole('alert')).toBeTruthy();

    fireEvent.click(screen.getByText('create.kindAgent'));

    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('passes on no description when the preview never answered', async () => {
    // A description is a verdict, not a decoration: handing one over that no verdict backs
    // would let the next screen describe a schedule nothing has checked.
    validateCron.mockRejectedValue(new Error('offline'));
    const onConfirm = vi.fn();
    renderDialog({ day: THURSDAY, hour: 16 }, onConfirm);

    expect((await submitAsAgent(onConfirm)).cronDescription).toBeUndefined();
  });
});
