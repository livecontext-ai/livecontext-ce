import { describe, it, expect } from 'vitest';
import {
  shouldReplaceAutoOpen,
  enqueueAutoOpen,
  flushAutoOpen,
  autoOpenKey,
  autoOpenEventDetail,
  AUTO_OPEN_TYPES,
  type AutoOpenVisualization,
} from '../sidePanelAutoOpen';
import { vi } from 'vitest';

/**
 * Guards the side-panel auto-open queue. Two bugs motivated this:
 *  1. A single debounce timer kept ONLY the last visualization, so when the
 *     agent opened several apps in one burst every tab but the last vanished.
 *  2. A same-resource showcase marker (no runId) could overwrite the execute
 *     marker (runId set), leaving the panel open but empty.
 * The queue now keeps every DISTINCT resource and the best marker per resource.
 */

const app = (id: string, runId?: string): AutoOpenVisualization => ({
  type: 'application', id, runId, title: `App ${id}`,
});

describe('shouldReplaceAutoOpen', () => {
  it('queues the first marker for a resource', () => {
    expect(shouldReplaceAutoOpen(undefined, app('a'))).toBe(true);
  });

  it('never downgrades a live-run marker to a showcase marker (runId → no runId)', () => {
    expect(shouldReplaceAutoOpen(app('a', 'run-1'), app('a'))).toBe(false);
  });

  it('lets a live-run marker replace a showcase marker (no runId → runId)', () => {
    expect(shouldReplaceAutoOpen(app('a'), app('a', 'run-1'))).toBe(true);
  });

  it('lets a newer live-run marker replace an older one', () => {
    expect(shouldReplaceAutoOpen(app('a', 'run-1'), app('a', 'run-2'))).toBe(true);
  });

  it('lets the latest showcase marker win when neither has a runId', () => {
    expect(shouldReplaceAutoOpen(app('a'), app('a'))).toBe(true);
  });
});

describe('enqueueAutoOpen', () => {
  it('keeps EVERY distinct app from a burst (the multi-app fix)', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, app('a', 'run-a'));
    enqueueAutoOpen(pending, app('b', 'run-b'));
    enqueueAutoOpen(pending, app('c', 'run-c'));
    const flushed = Array.from(pending.values());
    expect(flushed.map((v) => v.id)).toEqual(['a', 'b', 'c']);
  });

  it('collapses same-resource markers, keeping the execute marker regardless of order', () => {
    // showcase first, then execute
    const p1 = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(p1, app('a'));
    enqueueAutoOpen(p1, app('a', 'run-1'));
    expect(p1.size).toBe(1);
    expect(p1.get(autoOpenKey(app('a')))?.runId).toBe('run-1');

    // execute first, then showcase - execute must still win
    const p2 = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(p2, app('a', 'run-1'));
    enqueueAutoOpen(p2, app('a'));
    expect(p2.size).toBe(1);
    expect(p2.get(autoOpenKey(app('a')))?.runId).toBe('run-1');
  });

  it('treats same id but different type as distinct resources', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, { type: 'application', id: 'x' });
    enqueueAutoOpen(pending, { type: 'workflow', id: 'x' });
    expect(pending.size).toBe(2);
  });

  it('ignores non-auto-open visualization types', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, { type: 'web_search', id: 'q' });
    enqueueAutoOpen(pending, { type: 'credential', id: 'c' });
    expect(pending.size).toBe(0);
  });

  it('keeps the existing entry untouched when a downgrade is rejected', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, app('a', 'run-1'));
    enqueueAutoOpen(pending, app('a')); // downgrade attempt
    expect(pending.get(autoOpenKey(app('a')))?.runId).toBe('run-1');
  });
});

describe('flushAutoOpen', () => {
  it('emits ONE event per queued resource, in insertion order (multi-app open)', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, app('a', 'run-a'));
    enqueueAutoOpen(pending, app('b', 'run-b'));
    const emit = vi.fn();
    const count = flushAutoOpen(pending, emit);
    expect(count).toBe(2);
    expect(emit).toHaveBeenCalledTimes(2);
    expect(emit.mock.calls.map((c) => c[0].id)).toEqual(['a', 'b']);
  });

  it('drains the pending map so a second flush emits nothing', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, app('a', 'run-a'));
    flushAutoOpen(pending, () => {});
    expect(pending.size).toBe(0);
    const emit = vi.fn();
    expect(flushAutoOpen(pending, emit)).toBe(0);
    expect(emit).not.toHaveBeenCalled();
  });

  it('emits the collapsed best marker for a same-resource burst', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, app('a'));            // showcase, no runId
    enqueueAutoOpen(pending, app('a', 'run-1'));   // execute, runId
    const emit = vi.fn();
    flushAutoOpen(pending, emit);
    expect(emit).toHaveBeenCalledTimes(1);
    expect(emit.mock.calls[0][0].runId).toBe('run-1');
  });
});

describe('AUTO_OPEN_TYPES', () => {
  it('includes application but excludes web_search', () => {
    expect(AUTO_OPEN_TYPES).toContain('application');
    expect(AUTO_OPEN_TYPES).not.toContain('web_search');
  });

  it('carries interface, which only a side-panel chat opens (AppHeader keeps it click-only on chat pages)', () => {
    expect(AUTO_OPEN_TYPES).toContain('interface');
  });
});

describe('AUTO_OPEN_TYPES - workflow(action=present)', () => {
  it('auto-opens every view the agent can present', () => {
    for (const type of ['present_application', 'present_table', 'present_workflow', 'present_interface', 'present_agent', 'present_file']) {
      expect(AUTO_OPEN_TYPES).toContain(type);
    }
  });
});

describe('enqueueAutoOpen - a plan change survives the debounce window', () => {
  const wf = (planChanged: boolean): AutoOpenVisualization => ({ type: 'workflow', id: 'wf-1', planChanged });

  it('keeps planChanged when a later load/present marker of the same workflow replaces the edit', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, wf(true));   // modify
    enqueueAutoOpen(pending, wf(false));  // load, in the same window
    expect(pending.get('workflow:wf-1')?.planChanged).toBe(true);
  });

  it("does not credit one chat's edit to another chat's marker in the same window", () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, { ...wf(true), conversationId: 'conv-a' });
    enqueueAutoOpen(pending, { ...wf(false), conversationId: 'conv-b' });
    expect(pending.get('workflow:wf-1')).toMatchObject({ planChanged: false, conversationId: 'conv-b' });
  });

  it('stays false when nothing in the window changed the plan', () => {
    const pending = new Map<string, AutoOpenVisualization>();
    enqueueAutoOpen(pending, wf(false));
    enqueueAutoOpen(pending, wf(false));
    expect(pending.get('workflow:wf-1')?.planChanged).toBe(false);
  });
});

describe('autoOpenEventDetail', () => {
  it('keeps every field the workflow page decides on (a dropped one fails silently)', () => {
    const detail = autoOpenEventDetail({
      type: 'workflow_run', id: 'wf-1', title: 'T', runId: 'r1',
      planChanged: true, planVersion: 13, conversationId: 'conv-1',
    });
    expect(detail).toMatchObject({ planChanged: true, planVersion: 13, conversationId: 'conv-1', runId: 'r1' });
  });
});
