'use client';

import { useEffect, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Bot, Info, Workflow } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { ChoiceCard } from '@/components/agenda/ChoiceCard';
import { Input } from '@/components/ui/input';
import {
  Select, SelectContent, SelectGroup, SelectItem, SelectLabel,
  SelectTrigger, SelectValue,
} from '@/components/ui/select';
import {
  DailyCustomPicker,
  MonthlyCustomPicker,
  ValidationFeedback,
  WeeklyCustomPicker,
  type ValidateCronResponse,
} from '@/components/schedule/ScheduleFrequencyPickers';
import {
  DEFAULT_CRONS,
  FREQUENCIES,
  FREQUENCY_BY_VALUE,
  GROUP_ORDER,
  buildDailyCron,
  buildMonthlyCron,
  buildWeeklyCron,
  cronToFrequencyValue,
  parseMonthlyCron,
  type FrequencyGroup,
} from '@/lib/schedule/cronFrequencies';
import { scheduleSettingsService } from '@/lib/api/orchestrator';
import { formatFullDate, formatTimeInZone, zonedParts, zonedTimeToInstant } from '@/lib/utils/agendaTime';

/** Matches the builder inspector's own pause before it asks what a typed cron means. */
const VALIDATE_DEBOUNCE_MS = 400;

/** The slot the user clicked: a day, and an hour when the click came from an hour grid. */
export interface NewScheduleSlot {
  day: Date;
  hour: number | null;
}

/**
 * What the slot is being filled with.
 *
 * <p>The two are not variants of one resource, and the dialog does not pretend they are:
 * a WORKFLOW is created here and then built, an AGENT is handed to the agent modal with
 * this schedule already set. What they share is the question the calendar answered - when
 * - which is why they share a dialog at all.
 */
export type NewScheduleKind = 'WORKFLOW' | 'AGENT';

interface NewScheduledResourceDialogProps {
  slot: NewScheduleSlot | null;
  timezone: string;
  submitting: boolean;
  error: string | null;
  onCancel: () => void;
  onConfirm: (input: {
    kind: NewScheduleKind;
    name: string;
    cron: string;
    timezone: string;
    /**
     * What the platform said that cron means, when it has answered. Passed on rather than
     * re-derived so the next surface can show the sentence this dialog already showed,
     * instead of a raw expression the user never typed.
     */
    cronDescription?: string;
  }) => void;
}

/**
 * Schedule something FROM the calendar, at the moment the user pointed at.
 *
 * <p>The gap between "I want something to happen on Wednesday at 16:00" and a thing that
 * does it used to be: leave the agenda, create the resource, find where triggers live, add
 * a schedule, find the frequency picker, set the time. Every step of that is somewhere
 * else. Clicking the empty slot starts from the answer the user already gave - the slot
 * itself - and asks only what kind of thing to run and what to call it.
 *
 * <p><b>Two kinds, because the calendar already shows two kinds.</b> A workflow is created
 * here and then built; an agent is handed to the agent modal with this schedule already
 * filled in, because an agent is a good deal more than a name and a time (a model, a
 * system prompt, tools, a budget) and inventing a second, thinner agent form here would be
 * a place for those decisions to quietly default.
 *
 * <p><b>The proposal is PERIODIC, seeded from the click.</b> A calendar click carries a
 * weekday and an hour, so the default is "every week on that weekday at that time" from an
 * hour grid, and "every month on that day" from the month grid, both of which preserve
 * exactly what was pointed at. Everything else the builder offers is one dropdown away,
 * with the same catalogue, the same pickers and the same free-text cron - because it IS the
 * builder's, imported rather than reimplemented.
 *
 * <p><b>The two kinds arm at different moments, and the dialog says which.</b> A workflow's
 * schedule row exists only while the workflow has a pinned production version, so it fires
 * after the user has built it and set it as production; an empty workflow has nothing to
 * run anyway. An agent's schedule is armed the moment it is saved, so it runs on its own
 * from the next occurrence. Telling the user which of the two they are getting is the
 * difference between knowing there is one step left and finding out on the Wednesday that
 * nothing happened - or that something did.
 */
/**
 * The periodic proposal a click carries.
 *
 * <p>An hour grid click knows a weekday AND an hour, so it proposes that weekday at that
 * hour, weekly. A month grid click knows only a day of the month, so it proposes that day,
 * monthly, at 09:00 - a time it did not choose and does not pretend to have. Dropping
 * either fact would make the click decorative, which is the whole reason to create from the
 * calendar rather than from the workflow list.
 */
function proposalFor(
  slot: NewScheduleSlot | null,
  timeZone: string,
): { cron: string; frequency: string } {
  if (!slot) return { cron: DEFAULT_CRONS['weekly-custom'], frequency: 'weekly_custom' };
  const parts = zonedParts(slot.day, timeZone);
  if (slot.hour === null) {
    return { cron: buildMonthlyCron(9, 0, parts.day), frequency: 'monthly_custom' };
  }
  return {
    cron: buildWeeklyCron(slot.hour, 0, [String(parts.weekday)]),
    frequency: 'weekly_custom',
  };
}

export function NewScheduledResourceDialog({
  slot,
  timezone,
  submitting,
  error,
  onCancel,
  onConfirm,
}: NewScheduledResourceDialogProps) {
  const t = useTranslations('agenda');
  // The frequency catalogue's own strings, where they already live - the same labels the
  // builder's inspector shows, so "Every weekday" means one thing in this product.
  const tSchedule = useTranslations('workflowBuilder.inspector.scheduleTrigger');

  // Seeded at MOUNT, not in an effect. The page gives this dialog a key derived from the
  // slot, so each click mounts a fresh one: no effect has to notice that the slot changed
  // and reset four pieces of state, and there is no first render showing the previous
  // click's proposal.
  const [{ cron: seededCron, frequency: seededFrequency }] = useState(
    () => proposalFor(slot, timezone),
  );
  // Workflow first, because that is what this dialog did before the agent option existed
  // and it is the kind that cannot be created anywhere else in two fields.
  // Named for the RESOURCE, because `kind` is already taken a few lines down by the
  // frequency picker's own shape - two different questions that both answer to "kind".
  const [resourceKind, setResourceKind] = useState<NewScheduleKind>('WORKFLOW');
  const [name, setName] = useState('');
  const [cron, setCron] = useState(seededCron);
  const [frequency, setFrequency] = useState(seededFrequency);
  const [validation, setValidation] = useState<ValidateCronResponse | null>(null);
  const [validating, setValidating] = useState(false);

  // What the schedule means, and when it would actually run, answered by the same endpoint
  // the builder asks. The frontend never describes a cron itself.
  //
  // Debounced like the builder's: in Advanced mode the cron is TYPED, and a request per
  // keystroke is nine round trips to describe one expression. The in-flight flag is passed
  // to the panel so it says "validating" instead of confidently describing the PREVIOUS
  // cron under the new selection.
  useEffect(() => {
    if (!slot) return;
    // A new expression invalidates the old answer BEFORE anything is asked about it. Left
    // standing, the previous cron's verdict is what the Create gate reads: type nonsense
    // into the advanced field and click inside the debounce window, and the button is still
    // enabled on a `{valid:true}` that described a different schedule - the exact silent
    // failure the gate exists to stop.
    setValidation(null);
    if (!cron.trim()) {
      setValidating(false);
      return;
    }
    let cancelled = false;
    setValidating(true);
    const timer = setTimeout(() => {
      scheduleSettingsService
        .validateCron(cron, timezone)
        .then((result) => { if (!cancelled) setValidation(result); })
        .catch(() => { if (!cancelled) setValidation(null); })
        .finally(() => { if (!cancelled) setValidating(false); });
    }, VALIDATE_DEBOUNCE_MS);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [slot, cron, timezone]);

  const groupedFrequencies = useMemo(() => {
    const groups: Record<FrequencyGroup, typeof FREQUENCIES> = {
      minutes: [], hours: [], daily: [], weekly: [], monthly: [], advanced: [],
    };
    for (const f of FREQUENCIES) groups[f.group].push(f);
    return groups;
  }, []);

  const kind = FREQUENCY_BY_VALUE[frequency]?.kind ?? 'preset';

  const selectFrequency = (value: string) => {
    const option = FREQUENCY_BY_VALUE[value];
    if (!option) return;
    setFrequency(value);
    if (option.kind === 'preset') {
      setCron(option.cron ?? DEFAULT_CRONS.preset);
      return;
    }
    // A configurator keeps the current cron only when it ALREADY has that shape - reopening
    // "custom weekly" on a weekly cron must not reset the days and time the user chose.
    // Changing shape resets to the catalogue default: weekly 16:00 -> daily gives 09:00, not
    // 16:00. That is the builder inspector's rule, and the two surfaces sharing one
    // catalogue is worth more here than carrying an hour across a shape change on one of them.
    const alreadyThatShape = cronToFrequencyValue(cron) === value;
    if (!alreadyThatShape) setCron(DEFAULT_CRONS[option.kind]);
  };

  if (!slot) return null;

  const slotParts = zonedParts(slot.day, timezone);
  const slotLabel = slot.hour === null
    ? formatFullDate(slot.day, timezone)
    : t('create.slotWithTime', {
      date: formatFullDate(slot.day, timezone),
      // Resolved through the zone rather than by adding hours to midnight: on the day the
      // clocks change, midnight plus 16 hours is 15:00 or 17:00, and the label would name
      // an hour the user did not click.
      time: formatTimeInZone(
        zonedTimeToInstant(timezone, slotParts.year, slotParts.month, slotParts.day, slot.hour, 0),
        timezone,
      ),
    });

  // A cron the backend has REFUSED must not be creatable. The panel below already says it
  // is invalid, but Create stayed enabled, and the failure that follows is silent and late:
  // the plan saves, and only when the workflow is later set as production does the schedule
  // sync throw, get logged and swallowed - a workflow that looks armed, with no schedule row
  // and no error anywhere. Unknown (not yet validated, or the endpoint unreachable) stays
  // creatable: the builder is the same, and refusing on a network failure would be worse.
  //
  // That last allowance holds for the AGENT kind too, where the cron is written immediately
  // instead of at pin time, because the thing that is unreachable is the endpoint that
  // DESCRIBES a cron, not the one that stores it: a perfectly good expression must not be
  // refused because a preview timed out. What it costs is that the refusal can arrive one
  // screen later, from the schedule write - which is why that write reports back and the
  // calendar stays silent rather than announcing a schedule it was never told exists.
  const cronRejected = validation !== null && !validation.valid;
  // `!validating` is half the gate, not a nicety: while an answer is in flight there is no
  // verdict to read, so without it the button is enabled during every debounce window on
  // exactly the expression nobody has checked yet.
  const canSubmit = name.trim().length > 0
    && cron.trim().length > 0
    && !cronRejected
    && !validating
    && !submitting;

  // A monthly day past the 28th does not exist in every month, so the schedule skips some.
  // The same sentence the calendar uses when it refuses to MOVE a schedule there.
  const monthlyDayUnsafe = kind === 'monthly-custom' && parseMonthlyCron(cron).dayOfMonth > 28;

  const isAgent = resourceKind === 'AGENT';

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onCancel(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{isAgent ? t('create.agentTitle') : t('create.title')}</DialogTitle>
          {/* "Proposed from", not "it will run on": the slot seeds the proposal and the
              user is free to change the frequency straight away, at which point a sentence
              promising Thursday 16:00 is simply false. What it WILL do is stated by the
              preview panel below, which is read back from the platform. */}
          <DialogDescription>{t('create.description', { slot: slotLabel })}</DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          {/* The first question, because everything under it reads differently depending on
              the answer: the same Wednesday 16:00 means "once you set it as production" for
              a workflow and "from the next occurrence" for an agent. */}
          {/* Not disabled while submitting: on a ChoiceCard `disabled` means the answer is
              REFUSABLE, and a card drawn plain because the form is busy stops showing which
              kind is being committed at the moment it is committed. Neither can be pressed
              into a second save anyway - the confirm button is what `submitting` gates. */}
          <div className="grid gap-2 sm:grid-cols-2">
            <ChoiceCard
              active={!isAgent}
              icon={Workflow}
              title={t('create.kindWorkflow')}
              description={t('create.kindWorkflowHint')}
              onSelect={() => setResourceKind('WORKFLOW')}
            />
            <ChoiceCard
              active={isAgent}
              icon={Bot}
              title={t('create.kindAgent')}
              description={t('create.kindAgentHint')}
              onSelect={() => setResourceKind('AGENT')}
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="agenda-new-name" className="text-sm text-theme-secondary">
              {t('create.nameLabel')}
            </label>
            <Input
              id="agenda-new-name"
              value={name}
              autoFocus
              disabled={submitting}
              placeholder={isAgent ? t('create.agentNamePlaceholder') : t('create.namePlaceholder')}
              onChange={(event) => setName(event.target.value)}
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-sm text-theme-secondary">{tSchedule('frequency')}</label>
            <Select value={frequency} onValueChange={selectFrequency} disabled={submitting}>
              <SelectTrigger className="h-9 text-sm" aria-label={tSchedule('frequency')}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {GROUP_ORDER.map((group) => (
                  <SelectGroup key={group}>
                    <SelectLabel>{tSchedule(`group_${group}`)}</SelectLabel>
                    {groupedFrequencies[group].map((option) => (
                      <SelectItem key={option.value} value={option.value} className="text-sm">
                        {tSchedule(`freq_${option.value}`)}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                ))}
              </SelectContent>
            </Select>
          </div>

          {kind === 'daily-custom' && (
            <DailyCustomPicker
              cron={cron}
              disabled={submitting}
              t={tSchedule}
              onChange={(hour, minute) => setCron(buildDailyCron(hour, minute))}
            />
          )}
          {kind === 'weekly-custom' && (
            <WeeklyCustomPicker
              cron={cron}
              disabled={submitting}
              t={tSchedule}
              onChange={(hour, minute, days) => setCron(buildWeeklyCron(hour, minute, days))}
            />
          )}
          {kind === 'monthly-custom' && (
            <MonthlyCustomPicker
              cron={cron}
              disabled={submitting}
              t={tSchedule}
              onChange={(hour, minute, dayOfMonth) => setCron(buildMonthlyCron(hour, minute, dayOfMonth))}
            />
          )}
          {kind === 'advanced' && (
            <div className="space-y-1.5">
              <label htmlFor="agenda-new-cron" className="text-sm text-theme-secondary">
                {tSchedule('cronExpression')}
              </label>
              <Input
                id="agenda-new-cron"
                value={cron}
                disabled={submitting}
                onChange={(event) => setCron(event.target.value)}
                className="font-mono text-sm"
              />
              <p className="text-xs text-theme-muted">{tSchedule('cronHelp')}</p>
            </div>
          )}

          {monthlyDayUnsafe && (
            <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-100">
              {/* Its own sentence, not the calendar's move refusal: that one says a monthly
                  schedule CANNOT land after the 28th, which is true when the platform is
                  rewriting an existing cron and false here, where this is simply allowed.
                  Telling the user it is impossible and then doing it is worse than silence. */}
              {t('create.monthlyDayGap', { day: parseMonthlyCron(cron).dayOfMonth })}
            </p>
          )}

          <ValidationFeedback
            validation={validation}
            isValidating={validating}
            timezone={timezone}
            cronIsNonEmpty={cron.trim().length > 0}
            hasPersistedSchedule={false}
            t={tSchedule}
          />

          {/* Not a warning, a fact the user needs BEFORE they walk away, and the two kinds
              need OPPOSITE facts: nothing on this calendar changes for a workflow until it
              is built and set as production, while an agent's schedule is armed the moment
              the agent is saved. Showing the workflow's sentence for both would tell half
              the users their agent is inert when it is about to run. */}
          <p className="flex items-start gap-2 rounded-lg bg-theme-secondary p-2.5 text-xs text-theme-secondary">
            <Info className="mt-0.5 h-3.5 w-3.5 shrink-0 text-theme-muted" aria-hidden="true" />
            {isAgent ? t('create.agentLiveOnSave') : t('create.notLiveYet')}
          </p>

          {/* Only ever a WORKFLOW refusal: the agent branch writes nothing here, so it
              clears this and never sets it. Left showing after a switch, a red "could not
              create the workflow" would sit under an Agent dialog that is not going to
              attempt that write. */}
          {error && !isAgent && (
            <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-900 dark:bg-red-950/40 dark:text-red-100">
              {error}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onCancel} disabled={submitting}>
            {t('common.cancel')}
          </Button>
          <Button
            onClick={() => onConfirm({
              kind: resourceKind,
              name: name.trim(),
              cron: cron.trim(),
              timezone,
              // Only from a verdict that says VALID: an invalid cron's description would
              // describe nothing, and the gate above has already refused that case anyway.
              cronDescription: validation?.valid ? validation.description : undefined,
            })}
            disabled={!canSubmit}
          >
            {/* The agent button says CONTINUE because nothing is created when it is
                pressed: it opens the agent form with this schedule filled in, and the agent
                exists only once that form is saved. Labelling it "Create" would promise a
                row that does not exist yet, and a user who then closes the agent form would
                reasonably go looking for an agent nobody made. */}
            {submitting
              ? t('common.working')
              : (isAgent ? t('create.agentConfirm') : t('create.confirm'))}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
