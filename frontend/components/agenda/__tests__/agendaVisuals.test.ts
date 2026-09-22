import { describe, expect, it } from 'vitest';
import {
  STATUS_ACCENT,
  isActionable,
  isMovable,
  occurrenceAccent,
  occurrenceHref,
} from '../agendaVisuals';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

/**
 * Where an occurrence sends you, and whether it can be acted on.
 *
 * `occurrenceHref` decides the click target for every chip and every rail pill. The
 * application branch is the one that matters: that route is keyed by PUBLICATION id, so
 * sending the workflow id is a 404 the user meets instead of their app. `AgendaService`
 * has a backend test proving it emits `publicationId` on application fires; nothing
 * proved the frontend actually routes on it.
 */

function occurrence(overrides: Partial<AgendaOccurrence> = {}): AgendaOccurrence {
  return {
    id: 'wf-1:sched-1@1',
    kind: 'PLANNED',
    startAt: '2026-09-03T09:00:00Z',
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: 'Daily report',
    scheduleId: 'sched-1',
    armed: true,
    isNextFire: true,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
    ...overrides,
  };
}

describe('occurrenceHref', () => {
  it('routes an APPLICATION on its publication id, never its workflow id', () => {
    // /app/applications/[publicationId] - routing on resourceId is a 404.
    expect(occurrenceHref({
      resourceType: 'APPLICATION',
      resourceId: 'wf-99',
      publicationId: 'pub-42',
      runIdPublic: 'run_1',
    })).toBe('/app/applications/pub-42');
  });

  it('falls back to the workflow route for a legacy APPLICATION with no publication id', () => {
    // Older application rows carry no source_publication_id. A dead link is worse than a
    // link to the underlying workflow.
    expect(occurrenceHref({ resourceType: 'APPLICATION', resourceId: 'wf-99' }))
      .toBe('/app/workflow/wf-99');
  });

  it('routes a WORKFLOW into RUN mode when a production run resolved', () => {
    // Run mode is where the occurrence the user clicked is actually visible; the edit
    // canvas shows the plan, not the run.
    expect(occurrenceHref({ resourceType: 'WORKFLOW', resourceId: 'wf-1', runIdPublic: 'run_7' }))
      .toBe('/app/workflow/wf-1/run/run_7');
  });

  it('falls back to edit mode for a workflow with no trusted run', () => {
    expect(occurrenceHref({ resourceType: 'WORKFLOW', resourceId: 'wf-1' }))
      .toBe('/app/workflow/wf-1');
  });

  it('routes an AGENT to the agent-panel deep link, ignoring any run id', () => {
    // Agents have no pinning and no production run, and no per-agent page either:
    // `/app/agent/<id>` 404s, so the row opens the panel via `?openAgent=`.
    expect(occurrenceHref({ resourceType: 'AGENT', resourceId: 'ag-1', runIdPublic: 'run_7' }))
      .toBe('/app/agent?openAgent=ag-1');
  });

  it('routes a past AGENT RUN to the conversation it happened in', () => {
    // That is where the prompt, the answer and the tool calls are. The agent panel only
    // says the agent exists, which answers nothing about the run that was clicked.
    expect(occurrenceHref({ resourceType: 'AGENT', resourceId: 'ag-1', conversationId: 'conv-5' }))
      .toBe('/app/c/conv-5');
  });

  it('falls back to the agent panel for a run with no conversation', () => {
    // A workflow agent node runs inside a workflow run and has no conversation of its own.
    expect(occurrenceHref({ resourceType: 'AGENT', resourceId: 'ag-1', conversationId: undefined }))
      .toBe('/app/agent?openAgent=ag-1');
  });

  it('never sends a workflow to a conversation, whatever it carries', () => {
    expect(occurrenceHref({ resourceType: 'WORKFLOW', resourceId: 'wf-1', conversationId: 'conv-5' }))
      .toBe('/app/workflow/wf-1');
  });
});

describe('occurrenceAccent', () => {
  it('draws a stopped agent run in its own colour, not the failure red', () => {
    // CANCELLED reaches the calendar from agent runs only. Painting it red would send
    // someone looking for a fault in a run a person chose to end.
    const stopped = occurrenceAccent(occurrence({ kind: 'PAST', status: 'CANCELLED' }));
    const failed = occurrenceAccent(occurrence({ kind: 'PAST', status: 'FAILED' }));

    expect(stopped.chip).toBeTruthy();
    expect(stopped.chip).not.toBe(failed.chip);
    expect(stopped.chip).not.toContain('red');
  });

  it('gives every past outcome the calendar can report an accent of its own', () => {
    // A status with no entry falls back to the RESOURCE colour, which silently reads as
    // "planned" on a past chip.
    for (const status of ['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'FIRED'] as const) {
      expect(STATUS_ACCENT[status], status).toBeTruthy();
    }
  });
});

describe('isActionable', () => {
  it('is true only for a planned occurrence that has a schedule to address', () => {
    expect(isActionable(occurrence())).toBe(true);
  });

  it('is false for a PAST fire - it already happened', () => {
    // This is what stops a history chip being draggable. Making it drag and then refusing
    // on drop would teach the gesture and then take it away.
    expect(isActionable(occurrence({ kind: 'PAST', scheduleId: undefined }))).toBe(false);
    expect(isActionable(occurrence({ kind: 'PAST' }))).toBe(false);
  });

  it('is false when there is no schedule id to act on', () => {
    expect(isActionable(occurrence({ scheduleId: undefined }))).toBe(false);
  });

  it('is false while the production resource is paused', () => {
    expect(isActionable(occurrence({ resourcePaused: true }))).toBe(false);
  });
});

describe('isMovable', () => {
  it('is true for the next fire - the one occurrence "this one only" can address', () => {
    expect(isMovable(occurrence({ isNextFire: true, moveAllSupported: false }))).toBe(true);
  });

  it('is true for a later occurrence when the cron can be rewritten', () => {
    // Not the pending fire, so NEXT is unavailable, but "move them all" can shift the
    // whole schedule - a real move, just a wider one.
    expect(isMovable(occurrence({ isNextFire: false, moveAllSupported: true }))).toBe(true);
  });

  it('is FALSE when neither scope can move it', () => {
    // Chips 2..n of any interval schedule (*/15 and friends) land here, which is a very
    // common shape rather than a corner. They stayed draggable, so the gesture opened a
    // dialog with both scopes disabled and Confirm greyed out: the UI taught a gesture and
    // then refused it, with nothing on screen saying why.
    expect(isMovable(occurrence({ isNextFire: false, moveAllSupported: false }))).toBe(false);
  });

  it('is false for a PAST fire whatever the flags say', () => {
    expect(isMovable(occurrence({ kind: 'PAST', isNextFire: true, moveAllSupported: true })))
      .toBe(false);
  });

  it('is narrower than isActionable, never wider', () => {
    // Running early acts on the SCHEDULE, not on the occurrence, so it stays available on
    // every planned chip. Collapsing the two predicates back into one would either restore
    // the dead-end drag or take away a run-early that works fine.
    const stuck = occurrence({ isNextFire: false, moveAllSupported: false });
    expect(isActionable(stuck)).toBe(true);
    expect(isMovable(stuck)).toBe(false);
  });
});
