'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import dynamic from 'next/dynamic';
import { useRouter } from '@/i18n/navigation';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
  DndContext,
  DragOverlay,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { useDragSensors } from '@/lib/dnd/useDragSensors';
import { CalendarClock, Loader2 } from 'lucide-react';
import { AuthenticatedView } from './AuthenticatedView';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { useAgendaPreferences } from '@/hooks/useAgendaPreferences';
import { useRefreshHomeStatus } from '@/hooks/useHomeStatus';
import {
  agendaService,
  type Agenda,
  type AgendaMarker,
  type AgendaOccurrence,
  type MoveScope,
} from '@/lib/api/orchestrator/agenda.service';
import {
  productionResourceKind,
  setProductionResourcePaused,
} from '@/lib/api/orchestrator/resource-control';
import {
  addDays,
  addMonths,
  buildTimezoneOptions,
  dayKey,
  formatCompactDateRange,
  formatFullDate,
  formatMonthTitle,
  monthGridDays,
  startOfDay,
  weekGridDays,
  zonedParts,
} from '@/lib/utils/agendaTime';
import { AgendaHeader } from '@/components/agenda/AgendaHeader';
import { AgendaListView } from '@/components/agenda/AgendaListView';
import { MonthView } from '@/components/agenda/MonthView';
import { MoveOccurrenceDialog } from '@/components/agenda/MoveOccurrenceDialog';
import { OccurrenceMenu } from '@/components/agenda/OccurrenceMenu';
import { TimeGridView } from '@/components/agenda/TimeGridView';
import { occurrenceHref } from '@/components/agenda/agendaVisuals';
import { coverageBoundaryLabel } from '@/components/agenda/agendaCoverage';
import { markEpochPickedByUser } from '@/components/workflow/run-panel/useDefaultEpochSelection';
import { AGENDA_EMPTY_KEYS, selectAgendaEmptyState } from '@/components/agenda/agendaEmptyState';
import { resolveDropStart, type AgendaDropTarget } from '@/components/agenda/agendaDrag';
import { OccurrenceDragPreview } from '@/components/agenda/OccurrenceDragPreview';
import { agendaErrorText } from '@/components/agenda/agendaErrors';
import {
  agendaTriggerKey,
  occurrenceMatchesTriggerTypes,
  occurrenceUsesTrigger,
} from '@/components/agenda/agendaTriggerSelection';
import { AGENDA_KIND_ORDER, type AgendaKind } from '@/components/agenda/agendaLaunchKinds';
import {
  NewScheduledResourceDialog,
  type NewScheduleKind,
  type NewScheduleSlot,
} from '@/components/agenda/NewScheduledResourceDialog';
import { createScheduledWorkflowPlan } from '@/lib/workflows/defaultWorkflowPlan';
import { orchestratorApi } from '@/lib/api';
import { rememberWorkflowName } from '@/lib/workflows/recentWorkflowNames';
import { track } from '@/lib/analytics/analytics';

// Loaded only once an agent is actually being created. The agent form is the largest
// component in the app and pulls the model catalogue, the tool catalogue and the skills
// tree behind it; a calendar that fetched all of that on every page view to serve a button
// most visits never press would pay for the feature on every visit that does not use it.
const CreateAgentModal = dynamic(
  () => import('@/components/chat/CreateAgentModal').then((m) => m.CreateAgentModal),
  {
    ssr: false,
    // Confirming closes the slot dialog, so without this the screen simply goes back to the
    // calendar while the chunk downloads and the click reads as having done nothing.
    loading: () => (
      <div
        role="status"
        aria-live="polite"
        className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
      >
        <Loader2 className="h-6 w-6 animate-spin text-white" aria-hidden="true" />
      </div>
    ),
  },
);

/**
 * The agenda page: every scheduled workflow, application and agent in the workspace,
 * laid out over time and actionable in place.
 *
 * <p>This component owns the window (which dates are on screen), the fetch, and the three
 * writes. The views below it are presentational - they receive occurrences already
 * bucketed per day and hand back user intent.
 */
/** A `?date` deep link, or null when it is absent or not a date. */
function parseLinkedDate(value: string | null): Date | null {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

interface AgendaViewProps {
  /** Render inside the app side panel, whose shell already owns authentication and padding. */
  embedded?: boolean;
}

export function AgendaView({ embedded = false }: AgendaViewProps = {}) {
  const t = useTranslations('agenda');
  const router = useRouter();
  const { toasts, addToast, removeToast } = useToast();
  const canMutate = useCanMutateInCurrentOrg();
  const { preferences, update, reset, toggleResourceType, hydrated } = useAgendaPreferences();

  // The clocks live in the surfaces that draw time (`useNow` in the bar and the hour grid,
  // `useDayKey` in the month grid and the list), NOT here. Held at this level, a minute
  // tick would re-render the whole calendar - every droppable cell and every draggable
  // chip - to move a line that two of the four views do not even draw. The ticks are
  // epoch-aligned, so surfaces that ask for the same interval still change together.
  //
  // What the page owns is the request to go BACK to the present, which no clock can express:
  // pressing Today while already on today leaves the period identical, so the hour grid
  // needs to be told rather than to notice.
  const [recenterSignal, setRecenterSignal] = useState(0);

  // Deep link from the notification bell: `?date` decides which period opens and `?focus`
  // rings that schedule's occurrences. The two need OPPOSITE treatment, which is why both
  // used to be seeded once and only one of them was right to be.
  const searchParams = useSearchParams();
  const linkedDate = searchParams?.get('date') ?? null;

  // `?focus` is only a highlight, so it is read live. Seeding it at mount meant a link
  // INTO the agenda from a page that already IS the agenda - the bell's "open in agenda"
  // while the agenda is open - changed the URL and did nothing at all.
  const focusScheduleId = searchParams?.get('focus') ?? null;

  const [anchor, setAnchor] = useState<Date>(() => parseLinkedDate(linkedDate) ?? new Date());

  // `?date` moves the whole view, so it must NOT be read live: paging forward would be
  // undone on the next render, pinning the user to the linked period. It re-anchors only
  // when the link itself changes, which is the one case the mount-time seed missed.
  const appliedLinkedDate = useRef(linkedDate);
  useEffect(() => {
    if (linkedDate === appliedLinkedDate.current) return;
    appliedLinkedDate.current = linkedDate;
    const parsed = parseLinkedDate(linkedDate);
    if (parsed) setAnchor(parsed);
  }, [linkedDate]);
  const [agenda, setAgenda] = useState<Agenda | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedTriggerKey, setSelectedTriggerKey] = useState<string | null>(null);
  // Every kind on by default, agent launch kinds included: the calendar's job is to show
  // what the workspace did, and a filter that starts partly off hides work without saying
  // so. AGENDA_KIND_ORDER, not TRIGGER_KIND_ORDER: seeding from the eight workflow kinds
  // would make every agent run invisible from the first paint. Its LENGTH is separately
  // what selectAgendaEmptyState compares against to decide whether anything is filtered.
  const [triggerTypes, setTriggerTypes] = useState<AgendaKind[]>([...AGENDA_KIND_ORDER]);
  const [reloadKey, setReloadKey] = useState(0);

  // Menu + dialog state.
  const [selected, setSelected] = useState<AgendaOccurrence | null>(null);
  const [menuAnchor, setMenuAnchor] = useState<{ x: number; y: number } | null>(null);
  const [moveTarget, setMoveTarget] = useState<{ occurrence: AgendaOccurrence; start: Date } | null>(null);
  // The empty slot the user clicked, and the refusal to report if creating from it fails.
  const [newSlot, setNewSlot] = useState<NewScheduleSlot | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  // The schedule an agent is being created WITH, and the only thing that makes the agent
  // form appear on this page. Null until the slot dialog is confirmed on the agent kind.
  const [agentSeed, setAgentSeed] = useState<
    { name: string; cron: string; timezone: string; description?: string } | null
  >(null);
  const [moveError, setMoveError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Only the newest window may write its result: switching months quickly otherwise lets a
  // slow earlier response land on top of the one the user is looking at.
  const requestIdRef = useRef(0);

  const { timezone, view, weekStartsOn } = preferences;

  /** The instants the visible grid spans. The server is never asked to guess the view. */
  const visibleWindow = useMemo(() => {
    if (view === 'month') {
      const days = monthGridDays(anchor, timezone, weekStartsOn);
      return { from: days[0], to: addDays(days[days.length - 1], 1, timezone) };
    }
    if (view === 'week' || view === 'list') {
      const days = weekGridDays(anchor, timezone, weekStartsOn);
      return { from: days[0], to: addDays(days[days.length - 1], 1, timezone) };
    }
    const start = startOfDay(anchor, timezone);
    return { from: start, to: addDays(start, 1, timezone) };
  }, [anchor, timezone, view, weekStartsOn]);

  const reload = useCallback(() => setReloadKey((key) => key + 1), []);
  // `reload` refreshes the CALENDAR. Every mutation below also changes what the notification
  // bell lists as armed and what its imminent-fire ring pulses for, and that payload is
  // invalidated by nothing - so each of them asks for it again, exactly as the bell's own
  // schedule row menu does after the same calls.
  const refreshAutomations = useRefreshHomeStatus();
  useOrgScopedReset(reload);

  useEffect(() => {
    // Preferences decide the window, so fetching before they are hydrated would request
    // one window in the default timezone and then immediately refetch another.
    if (!hydrated) return;
    const requestId = ++requestIdRef.current;
    let cancelled = false;
    setLoading(true);

    // A window entirely in the future has no history to look up; skipping the epoch scan
    // is free and keeps a month-ahead view off the heaviest query on the page.
    const includePast = preferences.showPast && visibleWindow.from.getTime() < Date.now();

    agendaService
      .getAgenda(visibleWindow.from, visibleWindow.to, includePast)
      .then((result) => {
        if (cancelled || requestId !== requestIdRef.current) return;
        setAgenda(result);
        setLoadFailed(false);
      })
      .catch(() => {
        if (cancelled || requestId !== requestIdRef.current) return;
        setAgenda(null);
        // Recorded, not just toasted: without it the page cannot tell an empty workspace
        // from one it failed to read, and once the toast auto-dismisses it says the former.
        setLoadFailed(true);
        addToast({ type: 'error', title: t('errors.loadTitle'), message: t('errors.loadMessage') });
      })
      .finally(() => {
        if (!cancelled && requestId === requestIdRef.current) setLoading(false);
      });

    return () => { cancelled = true; };
  }, [hydrated, visibleWindow.from, visibleWindow.to, preferences.showPast, reloadKey, addToast, t]);

  const triggerCandidates = useMemo(() => {
    if (!agenda) return [];
    return (agenda.markers ?? []).filter((trigger) => {
      if (!preferences.resourceTypes.includes(trigger.resourceType)) return false;
      if (!triggerTypes.includes(trigger.triggerType)) return false;
      if (!preferences.showPaused && (!trigger.armed || trigger.resourcePaused)) return false;
      return true;
    });
  }, [agenda, preferences.resourceTypes, preferences.showPaused, triggerTypes]);

  const selectedTrigger = useMemo(
    () => triggerCandidates.find((trigger) => agendaTriggerKey(trigger) === selectedTriggerKey) ?? null,
    [selectedTriggerKey, triggerCandidates],
  );

  /** Occurrences after the on-screen filters, which never round-trip to the server. */
  const visibleOccurrences = useMemo(() => {
    if (!agenda) return [];
    return agenda.occurrences.filter((occurrence) => {
      if (!preferences.resourceTypes.includes(occurrence.resourceType)) return false;
      if (!preferences.showPast && occurrence.kind === 'PAST') return false;
      if (!occurrenceMatchesTriggerTypes(occurrence, triggerTypes)) return false;
      if (selectedTrigger
        && !occurrenceUsesTrigger(occurrence, selectedTrigger, agenda.markers ?? [])) return false;
      return true;
    });
  }, [agenda, preferences.resourceTypes, preferences.showPast, selectedTrigger, triggerTypes]);

  const occurrencesByDay = useMemo(() => {
    const map = new Map<string, AgendaOccurrence[]>();
    for (const occurrence of visibleOccurrences) {
      const key = dayKey(new Date(occurrence.startAt), timezone);
      const bucket = map.get(key);
      if (bucket) bucket.push(occurrence);
      else map.set(key, [occurrence]);
    }
    return map;
  }, [visibleOccurrences, timezone]);

  const timezoneOptions = useMemo(
    // The chosen zone is included even when nothing is drawn in it. Options were derived
    // from the CURRENT window's occurrences alone, so paging to a month where that
    // schedule does not fire dropped the selected value out of the list and the control
    // rendered an empty box - while the calendar was still, correctly, drawn in that zone.
    () => buildTimezoneOptions([
      preferences.timezone,
      ...((agenda?.occurrences ?? []).map((o) => o.timezone).filter(Boolean) as string[]),
    ]),
    [agenda, preferences.timezone],
  );

  /* ---------------------------------------------------------------- *
   *  Navigation
   * ---------------------------------------------------------------- */

  const step = useCallback(
    (direction: -1 | 1) => {
      setAnchor((current) => {
        if (view === 'month') return addMonths(current, direction, timezone);
        if (view === 'day') return addDays(current, direction, timezone);
        return addDays(current, direction * 7, timezone);
      });
    },
    [view, timezone],
  );

  const title = useMemo(() => {
    if (view === 'month') return formatMonthTitle(anchor, timezone);
    if (view === 'day') return formatFullDate(anchor, timezone);
    const days = weekGridDays(anchor, timezone, weekStartsOn);
    return formatCompactDateRange(days[0], days[6], timezone);
  }, [view, anchor, timezone, weekStartsOn]);

  /* ---------------------------------------------------------------- *
   *  Interaction
   * ---------------------------------------------------------------- */

  // What it takes to pick a chip up, shared with every other card grid in the app rather
  // than restated here. A few pixels of travel for a mouse, so a press that does not move
  // stays a click and one chip can both open its menu and be dragged; a quarter-second HOLD
  // for a finger, which is the only thing that works on touch - a distance constraint loses
  // the gesture to the page's own scroll long before it is met, and this calendar is a grid
  // the user scrolls, so the agenda's private pointer sensor meant a chip could not be
  // dragged with a finger at all.
  const sensors = useDragSensors();

  // The occurrence under the pointer during a drag, and where it would land, driving the
  // floating preview. Both are needed: the overlay renders the chip, and the target is what
  // the reader is dragging in order to decide. Set on start, refined on every cell change,
  // cleared on end and on cancel - a stale value here would leave a card stuck on screen.
  const [activeDrag, setActiveDrag] = useState<{
    occurrence: AgendaOccurrence;
    proposedStart: Date | null;
  } | null>(null);

  const handleDragStart = useCallback((event: DragStartEvent) => {
    const occurrence = event.active.data.current as AgendaOccurrence | undefined;
    // No target yet: dnd-kit reports nothing under the pointer until the first move, and
    // showing the chip's own time as if it were the proposal would state a move that has
    // not been aimed anywhere.
    setActiveDrag(occurrence ? { occurrence, proposedStart: null } : null);
  }, []);

  const handleDragOver = useCallback(
    (event: DragOverEvent) => {
      // The SAME rule the drop uses, so the preview cannot promise one time and the dialog
      // open on another: the month grid drops on a day and keeps the hour, the time grids
      // drop on a day AND hour.
      const proposedStart = resolveDropStart(
        event.active.data.current as AgendaOccurrence | undefined,
        event.over?.data.current as AgendaDropTarget | undefined,
        timezone,
      );
      // dnd-kit dispatches this from an effect keyed on the id of what is UNDER the
      // pointer, so it fires once per cell crossed and not on every pointer move. A
      // de-duplicating guard was written here first and was dead code: a new cell always
      // means a new proposed time. The re-render it costs is one per cell, the same order
      // as the cells' own `isOver` ring, which dnd-kit re-renders on exactly the same
      // events.
      setActiveDrag((current) => (current ? { ...current, proposedStart } : current));
    },
    [timezone],
  );

  const openResource = useCallback(
    // Typed against the fields occurrenceHref and the epoch hand-off actually read,
    // conversationId INCLUDED. Listing a subset used to work only because every caller
    // happens to pass the whole occurrence and TypeScript does not strip extra
    // properties at runtime: anyone rebuilding this object from a pick would have sent
    // every agent run back to the agent panel, with no type error anywhere.
    (target: Pick<AgendaOccurrence, 'resourceType' | 'resourceId'>
      & Partial<Pick<AgendaOccurrence, 'runIdPublic' | 'publicationId' | 'epoch' | 'conversationId'>>) => {
      // A past fire opens the run ON that fire. A run is a sequence of fires and its
      // surfaces default to the cumulative view of ALL of them - right when you open a
      // run, wrong when you clicked one dot on a calendar: the user pointed at Tuesday
      // 09:00 and got every Tuesday at once.
      //
      // Recorded through the run panel's own memory rather than a new URL parameter. That
      // memory exists precisely to carry an explicit pick across the surface that made it,
      // it is keyed by run id, and the panel reads it on mount - so a pick made here
      // survives the navigation without inventing a second way to say the same thing.
      if (target.epoch != null && target.runIdPublic) {
        markEpochPickedByUser(target.runIdPublic, target.epoch);
      }
      router.push(occurrenceHref(target));
    },
    [router],
  );

  const handleSelect = useCallback((occurrence: AgendaOccurrence, event: React.MouseEvent<HTMLButtonElement>) => {
    // EVERY chip opens its menu, past fires included. A past fire used to navigate on the
    // first click, on the reasoning that its menu held one item and the second click was
    // therefore wasted. What that reasoning missed is that leaving the page is the most
    // expensive thing a click on this calendar can do: it is the one action with no undo
    // on a surface built for scanning, and a mis-click on a dense month grid took the user
    // out of the agenda entirely. The menu costs a click and says what it is about to do -
    // it names the run, the day and the outcome first.
    const rect = event.currentTarget.getBoundingClientRect();
    setMenuAnchor({ x: rect.left, y: rect.bottom });
    setSelected(occurrence);
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      // First, unconditionally: every path out of this function must take the preview with
      // it, including the two that return early below.
      setActiveDrag(null);
      const occurrence = event.active.data.current as AgendaOccurrence | undefined;
      // Where it lands is resolveDropStart's rule: the month grid drops on a day and the
      // time grids on a day AND hour, and the two must mean different things. Everything
      // finer is settled in the dialog, which has to open anyway to ask about scope.
      const start = resolveDropStart(
        occurrence,
        event.over?.data.current as AgendaDropTarget | undefined,
        timezone,
      );
      if (!occurrence || !start) return;

      setMoveError(null);
      setMoveTarget({ occurrence, start });
    },
    [timezone],
  );

  /**
   * Turn a refusal into the sentence that says what to do instead.
   *
   * <p>The mapping itself lives in agendaErrors, where its branches can be exercised;
   * this only resolves the key against the page's translations.
   */
  const describeError = useCallback(
    (error: unknown): string => {
      const { key, detail } = agendaErrorText(error);
      return key ? t(key) : (detail as string);
    },
    [t],
  );

  /**
   * Create the workflow the empty slot was asking for, and open it.
   *
   * <p>Creation goes through the same call the workflow list makes, with the same plan
   * shape, plus the schedule trigger the user just described - there is no agenda-specific
   * creation endpoint and there should not be one. Then it NAVIGATES: what was created is
   * an empty workflow with a time attached, so leaving the user on a calendar that (rightly)
   * does not show it yet would be the least useful possible answer. The builder is where
   * the next thing they have to do is.
   */
  const createScheduledWorkflow = useCallback(
    async ({ name, cron, timezone: zone }: { name: string; cron: string; timezone: string }) => {
      setBusy(true);
      setCreateError(null);
      try {
        const workflowId = crypto.randomUUID();
        const plan = createScheduledWorkflowPlan({
          id: workflowId,
          name,
          cron,
          timezone: zone,
          triggerLabel: name,
        });
        // `workflowId` at the top level: the backend ignores `plan.id` and would otherwise
        // file the row under a server-generated UUID, leaving the navigation below pointing
        // at nothing.
        const result = await orchestratorApi.saveWorkflowPlan({
          planJson: JSON.stringify(plan),
          dataInputs: {},
          workflowId,
        });
        // The response echoes the authoritative id; prefer it so the navigation always
        // targets the row that actually exists. Same three precautions the other two
        // creation call sites take (CreateWorkflowModal, the template gallery) - they are
        // not optional extras, they are what makes the landing page correct.
        const createdId = (typeof result?.workflowId === 'string' && result.workflowId)
          ? result.workflowId
          : workflowId;
        // Prime the breadcrumb with the name we already have: this navigates straight into
        // the builder, so it meets the transient "Workflow {uuid}" title more often than a
        // creation that stays on a list, not less.
        rememberWorkflowName(createdId, name);
        track('workflow_created', { workflow_id: createdId, source: 'agenda_slot' });
        setNewSlot(null);
        addToast({
          type: 'success',
          title: t('create.createdTitle'),
          message: t('create.createdMessage', { name }),
        });
        router.push(`/app/workflow/${createdId}`);
      } catch (error) {
        setCreateError(describeError(error));
      } finally {
        setBusy(false);
      }
    },
    [addToast, describeError, router, t],
  );

  /**
   * What the slot dialog's confirm does, which depends on WHAT is being scheduled.
   *
   * <p>A workflow is created here and then opened in the builder: two fields are enough to
   * describe one, and the thing the user still has to do lives somewhere else.
   *
   * <p>An agent is NOT created here. It is handed to the agent form with this schedule
   * already set, because an agent needs a model, a system prompt, a tool grant and a
   * budget, and a second, thinner agent form on this page would be a place for all four to
   * default silently. So this path writes nothing: it swaps one dialog for another, and
   * the agent exists only if that form is saved.
   */
  const confirmNewScheduled = useCallback(
    (input: {
      kind: NewScheduleKind;
      name: string;
      cron: string;
      timezone: string;
      cronDescription?: string;
    }) => {
      if (input.kind === 'AGENT') {
        setAgentSeed({
          name: input.name,
          cron: input.cron,
          timezone: input.timezone,
          description: input.cronDescription,
        });
        setNewSlot(null);
        setCreateError(null);
        return;
      }
      void createScheduledWorkflow(input);
    },
    [createScheduledWorkflow],
  );

  const confirmMove = useCallback(
    async (startAt: Date, scope: MoveScope) => {
      if (!moveTarget?.occurrence.scheduleId) return;
      setBusy(true);
      setMoveError(null);
      try {
        await agendaService.move(
          moveTarget.occurrence.scheduleId,
          startAt,
          scope,
          // Only meaningful for a NEXT move; the server uses it to refuse a move aimed at
          // an occurrence that is not the pending fire.
          scope === 'NEXT' ? new Date(moveTarget.occurrence.startAt) : undefined,
        );
        setMoveTarget(null);
        setSelected(null);
        addToast({
          type: 'success',
          title: t('toasts.movedTitle'),
          message: scope === 'ALL' ? t('toasts.movedAll') : t('toasts.movedNext'),
        });
        reload();
        refreshAutomations();
      } catch (error) {
        setMoveError(describeError(error));
      } finally {
        setBusy(false);
      }
    },
    [moveTarget, describeError, addToast, t, reload, refreshAutomations],
  );

  const runNow = useCallback(
    async (occurrence: AgendaOccurrence) => {
      if (!occurrence.scheduleId) return;
      setBusy(true);
      try {
        await agendaService.runNow(occurrence.scheduleId, true);
        setSelected(null);
        addToast({ type: 'success', title: t('toasts.ranTitle'), message: t('toasts.ranMessage') });
        reload();
        refreshAutomations();
      } catch (error) {
        addToast({
          type: 'error',
          title: t('toasts.runFailedTitle'),
          message: describeError(error),
        });
      } finally {
        setBusy(false);
      }
    },
    [addToast, describeError, t, reload, refreshAutomations],
  );

  const togglePause = useCallback(
    async (scheduleId: string, enabled: boolean) => {
      setBusy(true);
      try {
        await agendaService.toggle(scheduleId, enabled);
        setSelected(null);
        addToast({
          type: 'success',
          title: enabled ? t('toasts.resumedTitle') : t('toasts.pausedTitle'),
          message: enabled ? t('toasts.resumedMessage') : t('toasts.pausedMessage'),
        });
        reload();
        refreshAutomations();
      } catch (error) {
        addToast({
          type: 'error',
          title: t('toasts.toggleFailedTitle'),
          message: describeError(error),
        });
      } finally {
        setBusy(false);
      }
    },
    [addToast, describeError, t, reload, refreshAutomations],
  );

  const toggleResourcePause = useCallback(async (resource: AgendaMarker | AgendaOccurrence) => {
    setBusy(true);
    const resourceKind = productionResourceKind(resource.resourceType);
    try {
      const paused = !resource.resourcePaused;
      await setProductionResourcePaused(resource, paused);
      setSelected(null);
      addToast({
        type: 'success',
        title: paused
          ? t('toasts.resourcePausedTitle', { type: resourceKind })
          : t('toasts.resourceResumedTitle', { type: resourceKind }),
        message: paused
          ? t('toasts.resourcePausedMessage', { type: resourceKind })
          : t('toasts.resourceResumedMessage', { type: resourceKind }),
      });
      reload();
      refreshAutomations();
    } catch (error) {
      addToast({
        type: 'error',
        title: t('toasts.resourceToggleFailedTitle', { type: resourceKind }),
        message: describeError(error),
      });
    } finally {
      setBusy(false);
    }
  }, [addToast, describeError, reload, t, refreshAutomations]);


  /* ---------------------------------------------------------------- *
   *  Render
   * ---------------------------------------------------------------- */

  const truncatedCount = agenda?.truncatedScheduleIds.length ?? 0;

  /**
   * Runs that fall on a hidden weekend. The hour crop honours "a calendar that hides runs
   * is worse than one that scrolls" by collecting the overflow into a strip; the weekend
   * toggle removes whole COLUMNS, so it cannot. Counting them and saying so is the same
   * contract kept a different way - the toggle stays useful and nothing disappears silently.
   */
  const hiddenByWeekend = useMemo(() => {
    // Day view draws whatever day the user navigated to, weekend or not, so nothing is
    // hidden there and the banner would point at chips visible right below it.
    if (preferences.showWeekends || view === 'list' || view === 'day') return 0;
    return visibleOccurrences.filter((occurrence) => {
      const weekday = zonedParts(new Date(occurrence.startAt), timezone).weekday;
      return weekday === 0 || weekday === 6;
    }).length;
  }, [preferences.showWeekends, view, visibleOccurrences, timezone]);

  // The empty-state rule lives in one testable place: which of the four messages is TRUE
  // here is easy to get subtly wrong and impossible to notice, because the page renders a
  // confident sentence either way.
  const emptyState = selectAgendaEmptyState({
    failed: loadFailed,
    loading,
    resourceTypes: preferences.resourceTypes,
    search: selectedTrigger ? selectedTrigger.name : '',
    triggerTypes,
    showPast: preferences.showPast,
    showPaused: preferences.showPaused,
    occurrenceCount: visibleOccurrences.length,
  });

  const content = (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
        <AgendaHeader
          title={title}
          anchor={anchor}
          preferences={preferences}
          timezoneOptions={timezoneOptions}
          search={search}
          triggers={triggerCandidates}
          triggerCatalogue={agenda?.markers ?? []}
          occurrences={agenda?.occurrences ?? []}
          selectedTriggerKey={selectedTriggerKey}
          triggerTypes={triggerTypes}
          busy={busy}
          canMutate={canMutate}
          onSearchChange={setSearch}
          onSelectTrigger={setSelectedTriggerKey}
          onToggleTriggerType={(type) => setTriggerTypes((current) => current.includes(type)
            ? current.filter((candidate) => candidate !== type)
            : [...current, type])}
          onToggleResourcePause={(trigger) => void toggleResourcePause(trigger)}
          onOpenTrigger={(trigger) => router.push(occurrenceHref(trigger))}
          onPrevious={() => step(-1)}
          onNext={() => step(1)}
          onToday={() => {
            setAnchor(new Date());
            setRecenterSignal((n) => n + 1);
          }}
          onPickDate={setAnchor}
          onUpdate={update}
          onToggleResourceType={toggleResourceType}
          onResetPreferences={reset}
        />

        {hiddenByWeekend > 0 && (
          <button
            type="button"
            onClick={() => update({ showWeekends: true })}
            className="shrink-0 rounded-lg bg-amber-50 px-3 py-1.5 text-left text-xs text-amber-900
                       hover:brightness-95 dark:bg-amber-950/40 dark:text-amber-100"
          >
            {t('hiddenOnWeekends', { n: hiddenByWeekend })}
          </button>
        )}

        {/* Both notices, not one or the other: they describe DIFFERENT missing things (a
            schedule drawn as a prefix vs. history cut off), so collapsing them let an
            incomplete day read as complete whenever a schedule happened to be capped too. */}
        {truncatedCount > 0 && (
          <p className="shrink-0 rounded-lg bg-amber-50 px-3 py-1.5 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-100">
            {t('truncated.schedules', { n: truncatedCount })}
          </p>
        )}
        {/* Naming the date matters more than the warning. "Some older runs are not
            shown" beside a month whose first three weeks are empty reads as "nothing ran
            then"; naming the day coverage starts at tells the user which part of the grid
            is a fact and which part is a gap. The server only sends it when it knows. */}
        {/* Three different facts, three different sentences. "Some older runs are not
            shown" is only true of a CAP; when the agent source could not be read at all
            the missing rows include today's, and sending the user to look back in time
            for them is the wrong instruction. The dated form comes last because it is
            the only one that can name where coverage starts.

            The unavailable case is its own condition rather than a branch inside the
            truncation one: the backend ORs it into pastTruncated today, and nesting made
            the sentence silently disappear the day that stopped being true. The DTO
            documents them as separate facts, so the page reads them as separate facts. */}
        {agenda?.agentHistoryUnavailable && (
          <p className="shrink-0 rounded-lg bg-amber-50 px-3 py-1.5 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-100">
            {t('truncated.agentHistoryUnavailable')}
          </p>
        )}
        {/* Both, when both are true. An unreachable agent source and a capped epoch scan
            are two independent gaps, and picking one to mention dropped the other: a
            month could be missing its older workflow fires AND every agent run, and the
            user would only be told about the agents. */}
        {agenda?.pastTruncated && !agenda?.agentHistoryUnavailable && (
          <p className="shrink-0 rounded-lg bg-amber-50 px-3 py-1.5 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-100">
            {agenda.pastCoveredFrom
              ? t('truncated.pastFrom', {
                  date: coverageBoundaryLabel(agenda.pastCoveredFrom, timezone),
                })
              : t('truncated.past')}
          </p>
        )}
        {/* The agent source being unreadable already cancels pastCoveredFrom, so when
            both hold there is no boundary to name and the second sentence is the plain
            one. */}
        {agenda?.agentHistoryUnavailable && agenda?.pastTruncated && (
          <p className="shrink-0 rounded-lg bg-amber-50 px-3 py-1.5 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-100">
            {t('truncated.past')}
          </p>
        )}

        {/* The message about having nothing goes ABOVE the calendar, never INSTEAD of it.
            Replacing the grid took the page's only creation gesture away exactly when it
            was most needed: a workspace with no schedules got no grid, so there was no
            empty slot to click, so there was no way to make the first schedule from the
            calendar - the emptier the agenda, the less it could do about it. The same held
            for a search that matched nothing, where the grid also carried the answer to
            "then when IS free?".

            A `failed` load keeps the strongest treatment it can have while the grid stays:
            the honesty contract is that an agenda which could not be READ must never look
            like an agenda with nothing in it, and a red banner over an empty grid says
            that, where a silently empty grid would not. */}
        {emptyState !== 'none' && (
          <div
            role="status"
            className={`flex shrink-0 items-start gap-2 rounded-lg px-3 py-2 text-xs ${
              emptyState === 'failed'
                ? 'bg-red-50 text-red-900 dark:bg-red-950/40 dark:text-red-100'
                : 'bg-theme-secondary text-theme-secondary'
            }`}
          >
            <CalendarClock className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <span className="min-w-0">
              <span className="font-medium">{t(AGENDA_EMPTY_KEYS[emptyState].title)}</span>
              {' '}
              <span className="opacity-80">{t(AGENDA_EMPTY_KEYS[emptyState].description)}</span>
            </span>
          </div>
        )}

        <DndContext
          sensors={sensors}
          onDragStart={handleDragStart}
          onDragOver={handleDragOver}
          onDragEnd={handleDragEnd}
          // A drag abandoned with Escape, or cancelled by the browser claiming the pointer,
          // never reaches onDragEnd. Without this the card stayed under the cursor with
          // nothing moving it.
          onDragCancel={() => setActiveDrag(null)}
        >
            {view === 'month' && (
              <MonthView
                anchor={anchor}
                timezone={timezone}
                weekStartsOn={weekStartsOn}
                showWeekends={preferences.showWeekends}
                compact={preferences.density === 'compact'}
                occurrencesByDay={occurrencesByDay}
                focusScheduleId={focusScheduleId}
                canMutate={canMutate}
                onSelect={handleSelect}
                onCreate={canMutate ? (day) => { setCreateError(null); setNewSlot({ day, hour: null }); } : undefined}
              />
            )}
            {(view === 'week' || view === 'day') && (
              <TimeGridView
                days={
                  view === 'day'
                    ? [startOfDay(anchor, timezone)]
                    : weekGridDays(anchor, timezone, weekStartsOn).filter((day) => {
                        if (preferences.showWeekends) return true;
                        const weekday = zonedParts(day, timezone).weekday;
                        return weekday !== 0 && weekday !== 6;
                      })
                }
                timezone={timezone}
                startHour={preferences.dayStartHour}
                endHour={preferences.dayEndHour}
                compact={preferences.density === 'compact'}
                occurrencesByDay={occurrencesByDay}
                recenterSignal={recenterSignal}
                focusScheduleId={focusScheduleId}
                canMutate={canMutate}
                onSelect={handleSelect}
                onCreate={canMutate ? (day, hour) => { setCreateError(null); setNewSlot({ day, hour }); } : undefined}
              />
            )}
            {view === 'list' && (
              <AgendaListView
                occurrences={visibleOccurrences}
                timezone={timezone}
                focusScheduleId={focusScheduleId}
                onSelect={handleSelect}
              />
            )}
            {/* What the user actually sees while dragging. `dropAnimation={null}`: the drop
                opens a dialog asking about scope, so animating the card back to a cell it is
                not going to stay in would be a small lie played over the question. */}
            <DragOverlay dropAnimation={null}>
              {activeDrag && (
                <OccurrenceDragPreview
                  occurrence={activeDrag.occurrence}
                  proposedStart={activeDrag.proposedStart}
                  timezone={timezone}
                />
              )}
            </DragOverlay>
        </DndContext>

        <OccurrenceMenu
          occurrence={selected}
          timezone={timezone}
          anchor={menuAnchor}
          busy={busy}
          canMutate={canMutate}
          onClose={() => setSelected(null)}
          onRunNow={runNow}
          onMove={(occurrence) => {
            setMoveError(null);
            setMoveTarget({ occurrence, start: new Date(occurrence.startAt) });
            setSelected(null);
          }}
          onTogglePause={(occurrence) => {
            // Always a pause: an occurrence is only ever drawn for an armed schedule.
            // Resuming is offered on the rail's greyed marker.
            if (occurrence.scheduleId) void togglePause(occurrence.scheduleId, false);
          }}
          onToggleResourcePause={(occurrence) => void toggleResourcePause(occurrence)}
          onOpenResource={openResource}
        />

        <NewScheduledResourceDialog
          // A key per slot, so a second click on a different hour mounts a fresh dialog
          // rather than asking one to notice its prop changed and reset itself.
          key={newSlot ? `${newSlot.day.getTime()}:${newSlot.hour}` : 'none'}
          slot={newSlot}
          timezone={timezone}
          submitting={busy}
          error={createError}
          onCancel={() => { setNewSlot(null); setCreateError(null); }}
          onConfirm={confirmNewScheduled}
        />

        {agentSeed && (
          <CreateAgentModal
            // A name, and no id: the form reads `agent.id` to decide whether it is editing,
            // so an id here would open it against an agent that does not exist.
            agent={{ name: agentSeed.name }}
            initialSchedule={{
              cron: agentSeed.cron,
              timezone: agentSeed.timezone,
              description: agentSeed.description,
            }}
            onClose={() => setAgentSeed(null)}
            onAgentCreated={(agentId, result) => {
              setAgentSeed(null);
              if (!agentId) return;
              track('agent_created', { agent_id: agentId, source: 'agenda_slot' });
              // Unlike a workflow, an agent's schedule is armed the moment it is saved, so
              // the calendar is re-read rather than left showing the gap. The bell counts
              // armed triggers from its own payload, which nothing invalidates, so it is
              // asked again too - the same pair every mutation on this page performs.
              reload();
              refreshAutomations();
              // The agent exists either way; its SCHEDULE may not, and "check the calendar
              // for its next run" over an agent that has no next run sends the user to look
              // at nothing. Only a schedule the form says it WROTE earns that sentence:
              // `false` is a refusal (the form has already said so in its own words) and
              // `undefined` means no schedule was saved at all, which on this page can only
              // be the user switching the seeded one off before saving.
              if (result?.scheduleSaved !== true) return;
              addToast({
                type: 'success',
                title: t('create.agentCreatedTitle'),
                // A schedule with no instruction is answered at fire time by the agent's
                // assigned tasks, and a brand-new agent has none - so on this page, which
                // only ever CREATES, it is skipped on every fire until someone sends it
                // work. Pointing that user at the calendar for a next run is the exact
                // silent-agent state this feature documents, announced as success.
                message: result.scheduleHasPrompt
                  ? t('create.agentCreatedMessage')
                  : t('create.agentCreatedNoPromptMessage'),
              });
            }}
          />
        )}

        <MoveOccurrenceDialog
          occurrence={moveTarget?.occurrence ?? null}
          proposedStart={moveTarget?.start ?? null}
          timezone={timezone}
          submitting={busy}
          error={moveError}
          onCancel={() => { setMoveTarget(null); setMoveError(null); }}
          onConfirm={confirmMove}
        />

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );

  if (embedded) {
    return (
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden p-3">
        {content}
      </div>
    );
  }

  return (
    <AuthenticatedView maxWidth="max-w-[1600px]" overflow>
      {content}
    </AuthenticatedView>
  );
}
