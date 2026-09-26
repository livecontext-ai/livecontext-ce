/**
 * @vitest-environment jsdom
 */
/**
 * Camera follow during a run.
 *
 * What is worth pinning is not "the camera moves". It is that it moves for the right
 * SET of nodes, on the right CANVAS, exactly when that set changes, that a move which
 * could not be made is retried rather than silently skipped, and that it never travels
 * by a route that selects a node. Selecting swaps the side panel to the inspector,
 * which unmounts the run step list and this very hook, so there would be nothing left
 * to follow.
 */
import * as React from 'react';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, act, screen, fireEvent } from '@testing-library/react';
import type { Node } from 'reactflow';
import { computeFollowBounds, resolveFollowFrame } from '../services/runFollowBounds';
import {
  isRunCameraFollowEnabled,
  setRunCameraFollowEnabled,
  subscribeRunCameraFollow,
  __resetRunCameraFollowForTests,
} from '../services/runCameraFollowStore';
import { useRunCameraFollow } from '../hooks/useRunCameraFollow';
import {
  WORKFLOW_FOLLOW_NODES_EVENT,
  FOLLOW_DEBOUNCE_MS,
  FOLLOW_MIN_GAP_MS,
  FOLLOW_WAITING_SETTLE_MS,
} from '../services/runFollowEvent';
import { setCanvasNodes } from '../services/canvasNodesStore';
import { CanvasRunFollowToggleButton } from '../components/CanvasRunFollowToggleButton';
import type { StepEntry } from '@/components/workflow/run-panel/runFormatting';
import type { BuilderNodeData } from '../types';

const WF = 'wf-1';
/** Deliberately never published, and never in the reset list. */
const UNPUBLISHED_WF = 'unpublished-canvas';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

let editMode = false;
let previewOnly = false;
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isEditMode: editMode, isPreviewOnly: previewOnly }),
}));

function step(alias: string, status: string, statusCounts?: StepEntry['statusCounts']): StepEntry {
  return { alias, status, startTime: null, endTime: null, statusCounts };
}

function canvasNode(id: string, x = 0, y = 0): Node<BuilderNodeData> {
  return {
    id,
    position: { x, y },
    width: 200,
    height: 60,
    data: { label: id } as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function Harness({
  steps,
  workflowId = WF,
  awaitingSignalAliases,
  isViewingHistoricalEpoch,
  isPreviewOnly,
  isRunActive,
}: {
  steps: StepEntry[] | undefined;
  /** null means "a canvas with no id", which a default parameter could not express. */
  workflowId?: string | null;
  awaitingSignalAliases?: Iterable<string> | null;
  isViewingHistoricalEpoch?: boolean;
  isPreviewOnly?: boolean;
  isRunActive?: boolean;
}) {
  useRunCameraFollow({
    steps,
    workflowId: workflowId ?? undefined,
    awaitingSignalAliases,
    isViewingHistoricalEpoch: isViewingHistoricalEpoch ?? false,
    isPreviewOnly: isPreviewOnly ?? false,
    // Default true: almost every case here models a run in progress.
    isRunActive: isRunActive ?? true,
  });
  return null;
}

describe('computeFollowBounds', () => {
  const opts = { pad: 100, fallbackWidth: 240, fallbackHeight: 80 };

  it('frames a single node with padding on every side', () => {
    expect(computeFollowBounds([{ position: { x: 500, y: 300 }, width: 200, height: 60 }], opts))
      .toEqual({ x: 400, y: 200, width: 400, height: 260 });
  });

  it('spans every running node when branches run in parallel, rather than picking one', () => {
    // A fork runs whole branches at once. A box covering only the first would leave the
    // others off screen for the length of the fan-out.
    expect(
      computeFollowBounds(
        [
          { position: { x: 0, y: 0 }, width: 100, height: 50 },
          { position: { x: 900, y: 0 }, width: 100, height: 50 },
          { position: { x: 450, y: 400 }, width: 100, height: 50 },
        ],
        opts,
      ),
    ).toEqual({ x: -100, y: -100, width: 1200, height: 650 });
  });

  it('falls back to a default size for a node ReactFlow has not measured', () => {
    // width 0 is a node mid-mount, not a zero-width node: taken literally it would
    // collapse the box onto a point.
    expect(computeFollowBounds([{ position: { x: 10, y: 20 }, width: 0, height: 0 }], opts))
      .toEqual({ x: -90, y: -80, width: 440, height: 280 });
  });

  it('skips an unplaced node instead of dragging the box to the origin', () => {
    expect(
      computeFollowBounds(
        [{ position: null, width: 200, height: 60 }, { position: { x: 800, y: 800 }, width: 200, height: 60 }],
        opts,
      ),
    ).toEqual({ x: 700, y: 700, width: 400, height: 260 });
  });

  it('returns null when there is nothing placed to frame', () => {
    expect(computeFollowBounds([], opts)).toBeNull();
    expect(computeFollowBounds([{ position: null }], opts)).toBeNull();
  });
});

describe('resolveFollowFrame (what a canvas does with an event)', () => {
  const opts = { pad: 100, fallbackWidth: 240, fallbackHeight: 80 };
  const nodes = [canvasNode('n1', 0, 0), canvasNode('n2', 500, 0)];

  it('frames the named nodes', () => {
    expect(resolveFollowFrame(nodes, { workflowId: WF, nodeIds: ['n2'] }, WF, opts))
      .toEqual({ x: 400, y: -100, width: 400, height: 260 });
  });

  it('IGNORES an event belonging to another workflow', () => {
    // The defect this guard exists for: several canvases are mounted at once, so an
    // unscoped event drags a canvas the user may be editing around for someone else's
    // run, and the edit-mode toolbar has no control to stop it.
    expect(resolveFollowFrame(nodes, { workflowId: 'other-wf', nodeIds: ['n1'] }, WF, opts)).toBeNull();
  });

  it('refuses to frame anything for a canvas with no id', () => {
    // Two undefined ids compare equal, so without the explicit guard any dispatcher
    // that omitted the field would be waved through.
    expect(resolveFollowFrame(nodes, { nodeIds: ['n1'] }, undefined, opts)).toBeNull();
  });

  it('ignores an event with no ids, and one whose ids match nothing here', () => {
    expect(resolveFollowFrame(nodes, { workflowId: WF, nodeIds: [] }, WF, opts)).toBeNull();
    expect(resolveFollowFrame(nodes, { workflowId: WF, nodeIds: ['gone'] }, WF, opts)).toBeNull();
    expect(resolveFollowFrame(nodes, null, WF, opts)).toBeNull();
  });
});

describe('runCameraFollowStore', () => {
  beforeEach(() => {
    __resetRunCameraFollowForTests();
    window.localStorage.clear();
  });

  // The storage spies below patch Storage.prototype globally. Restoring by hand at the
  // end of each test leaves every later test running against a throwing localStorage
  // the moment one assertion fails first.
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('defaults to ON when nothing is stored, so runs and agent builds are followed out of the box', () => {
    expect(isRunCameraFollowEnabled()).toBe(true);
  });

  it('respects a stored OFF across a reload, which is the only way it stays off', () => {
    setRunCameraFollowEnabled(false);
    __resetRunCameraFollowForTests();
    expect(isRunCameraFollowEnabled()).toBe(false);
  });

  it('SURVIVES a reload, which is the whole point of it being a preference', () => {
    setRunCameraFollowEnabled(false);
    setRunCameraFollowEnabled(true);
    // Drop every trace of the in-memory state, as a fresh page load would.
    __resetRunCameraFollowForTests();
    expect(isRunCameraFollowEnabled()).toBe(true);
  });

  it('persists being switched back off', () => {
    setRunCameraFollowEnabled(true);
    setRunCameraFollowEnabled(false);
    __resetRunCameraFollowForTests();
    expect(isRunCameraFollowEnabled()).toBe(false);
  });

  it('is stored under the documented key, which an e2e and the other builder preferences rely on', () => {
    setRunCameraFollowEnabled(false);
    expect(window.localStorage.getItem('workflow:runCameraFollow')).toBe('false');
    setRunCameraFollowEnabled(true);
    expect(window.localStorage.getItem('workflow:runCameraFollow')).toBe('true');
  });

  it('tells subscribers, once per real change', () => {
    const seen: boolean[] = [];
    const unsubscribe = subscribeRunCameraFollow((v) => seen.push(v));
    setRunCameraFollowEnabled(false);
    setRunCameraFollowEnabled(false);
    expect(seen).toEqual([false]);
    unsubscribe();
    setRunCameraFollowEnabled(true);
    expect(seen).toEqual([false]);
  });

  it('still works for the session when writing to storage throws', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(() => setRunCameraFollowEnabled(false)).not.toThrow();
    expect(isRunCameraFollowEnabled()).toBe(false);
    spy.mockRestore();
  });

  it('falls back to the default (on) when READING storage throws', () => {
    // A private window or blocked site data can throw on read too. The canvas must
    // still render, with the same default a fresh browser gets.
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(() => isRunCameraFollowEnabled()).not.toThrow();
    expect(isRunCameraFollowEnabled()).toBe(true);
    spy.mockRestore();
  });
});

describe('CanvasRunFollowToggleButton', () => {
  beforeEach(() => {
    __resetRunCameraFollowForTests();
    window.localStorage.clear();
    editMode = false;
    previewOnly = false;
  });

  it('renders in edit mode too, naming the agent build it follows there', () => {
    // In edit mode the camera follows the nodes an agent adds, so the control is live
    // there and its name must say so rather than talk about a running step.
    editMode = true;
    render(<CanvasRunFollowToggleButton />);
    const button = screen.getByTestId('canvas-toggle-run-follow');
    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(button.getAttribute('aria-label')).toBe('stopFollowingAgentBuild');
    fireEvent.click(button);
    expect(isRunCameraFollowEnabled()).toBe(false);
    expect(button.getAttribute('aria-label')).toBe('followAgentBuild');
  });

  it('renders nothing on a read-only preview canvas either', () => {
    // A marketplace preview can no more run a step than an edit canvas can, so the
    // control would be permanently inert and would describe a state you cannot reach.
    previewOnly = true;
    const { container } = render(<CanvasRunFollowToggleButton />);
    expect(container.innerHTML).toBe('');
  });

  it('shows the stored preference on mount, or it can never be switched back on again', () => {
    // Not cosmetic. After a reload with following off, a button that renders "on" sends
    // setRunCameraFollowEnabled(false) on the first click, which the store early-returns
    // as a no-op, so no listener fires and the user is stuck with a control that does
    // nothing.
    setRunCameraFollowEnabled(false);
    render(<CanvasRunFollowToggleButton />);
    expect(screen.getByTestId('canvas-toggle-run-follow').getAttribute('aria-pressed')).toBe('false');
  });

  it('starts pressed, flips the stored preference and reports its state', () => {
    render(<CanvasRunFollowToggleButton />);
    const button = screen.getByTestId('canvas-toggle-run-follow');
    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(button.getAttribute('aria-label')).toBe('stopFollowingRunningNode');

    fireEvent.click(button);
    expect(isRunCameraFollowEnabled()).toBe(false);
    expect(button.getAttribute('aria-pressed')).toBe('false');
    // The accessible name carries the ACTION, so it has to change with the state.
    expect(button.getAttribute('aria-label')).toBe('followRunningNode');
  });

  it('shows the state set elsewhere', () => {
    render(<CanvasRunFollowToggleButton />);
    act(() => setRunCameraFollowEnabled(false));
    expect(screen.getByTestId('canvas-toggle-run-follow').getAttribute('aria-pressed')).toBe('false');
  });
});

describe('useRunCameraFollow', () => {
  let events: CustomEvent[];
  const listener = (e: Event) => events.push(e as CustomEvent);

  beforeEach(() => {
    // 'performance' faked, Date deliberately NOT: the spacing floor must read the
    // MONOTONIC clock, so swapping it for Date.now() has to be detectable here.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] });
    events = [];
    __resetRunCameraFollowForTests();
    window.localStorage.clear();
    // The node store is module state: clear every id these cases touch, so a test
    // cannot pass on a leftover another one published.
    for (const id of [WF, 'other-wf', 'someone-else', 'never-published', 'wf-2']) {
      setCanvasNodes([], id);
    }
    setCanvasNodes([canvasNode('n-a'), canvasNode('n-b', 400)], WF);
    window.addEventListener(WORKFLOW_FOLLOW_NODES_EVENT, listener);
    window.addEventListener('workflowFocusNode', listener);
  });

  afterEach(() => {
    window.removeEventListener(WORKFLOW_FOLLOW_NODES_EVENT, listener);
    window.removeEventListener('workflowFocusNode', listener);
    vi.useRealTimers();
  });

  // Past the debounce AND past the spacing floor, so a test that expects a second
  // move is not silently measuring the gap instead. The gap has its own test.
  const settle = () => act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS + FOLLOW_MIN_GAP_MS + 20); });
  /** Long enough for a WAITING set to settle, twice over for a recheck that re-arms. */
  const settleWaiting = () => {
    act(() => { vi.advanceTimersByTime(FOLLOW_WAITING_SETTLE_MS + FOLLOW_MIN_GAP_MS + 20); });
    act(() => { vi.advanceTimersByTime(FOLLOW_WAITING_SETTLE_MS + FOLLOW_MIN_GAP_MS + 20); });
  };

  it('does not re-render its host when the toggle is off and other canvases publish', () => {
    // The real cost story. The node store notifies on EVERY publish of EVERY canvas, so
    // an ungated subscription would wake the hook's host (WorkflowBuilder, one of the
    // heaviest components here) on someone else's run. The counter has to live in the
    // component that OWNS the hook: a parent wrapper never re-renders, so counting
    // there measures nothing.
    let renders = 0;
    function CountingHost({ steps }: { steps: StepEntry[] }) {
      renders += 1;
      useRunCameraFollow({
        steps,
        workflowId: WF,
        awaitingSignalAliases: null,
        isViewingHistoricalEpoch: false,
        isPreviewOnly: false,
        isRunActive: true,
      });
      return null;
    }
    act(() => setRunCameraFollowEnabled(false));
    render(<CountingHost steps={[step('n-a', 'running')]} />);
    const before = renders;
    for (let i = 0; i < 5; i++) {
      act(() => setCanvasNodes([canvasNode(`x${i}`)], 'other-wf'));
    }
    expect(renders - before, 'a foreign canvas publish woke a disabled follow').toBe(0);
    settle();
    expect(events).toHaveLength(0);
  });

  it('is not woken by ANOTHER canvas publishing while it is following', () => {
    // Enabled this time: the identity compare is what keeps a second workflow's run
    // from re-rendering this host on every one of its publishes.
    act(() => setRunCameraFollowEnabled(true));
    let renders = 0;
    function CountingHost({ steps }: { steps: StepEntry[] }) {
      renders += 1;
      useRunCameraFollow({
        steps,
        workflowId: WF,
        awaitingSignalAliases: null,
        isViewingHistoricalEpoch: false,
        isPreviewOnly: false,
        isRunActive: true,
      });
      return null;
    }
    render(<CountingHost steps={[step('n-a', 'running')]} />);
    settle();
    const before = renders;
    for (let i = 0; i < 5; i++) {
      act(() => setCanvasNodes([canvasNode(`x${i}`)], 'other-wf'));
    }
    expect(renders - before, 'another workflow re-rendered this host').toBe(0);
  });


  it('is not woken by foreign publishes BEFORE its own canvas has published', () => {
    // The window this retry subscription exists for. getCanvasNodes hands back a fresh
    // empty array each call until this canvas publishes, so a bare identity compare is
    // never equal and every foreign publish would re-render the host.
    act(() => setRunCameraFollowEnabled(true));
    let renders = 0;
    function CountingHost({ steps }: { steps: StepEntry[] }) {
      renders += 1;
      useRunCameraFollow({
        steps,
        // An id the beforeEach reset does NOT publish, or the store hands back a stable
        // array and the plain identity compare short-circuits before the case this test
        // is about is ever reached. A CONSTANT: a fresh id per render would rebuild the
        // subscription on every render and the test would measure its own churn.
        workflowId: UNPUBLISHED_WF,
        awaitingSignalAliases: null,
        isViewingHistoricalEpoch: false,
        isPreviewOnly: false,
        isRunActive: true,
      });
      return null;
    }
    render(<CountingHost steps={[step('n-a', 'running')]} />);
    const before = renders;
    for (let i = 0; i < 5; i++) {
      act(() => setCanvasNodes([canvasNode(`x${i}`)], 'other-wf'));
    }
    expect(renders - before, 'an unpublished canvas woke on every foreign publish').toBe(0);
  });


  it('drops an armed move when its nodes stop resolving, and frames the step when they return', () => {
    // A plan reload or a relabel mid-run can take the running node off this canvas for
    // a moment. Letting the armed move fire would send ids nothing can frame AND stamp
    // the set as framed, so the step would never be framed at all, silently.
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    act(() => setCanvasNodes([canvasNode('unrelated')], WF));
    settle();
    expect(events, 'a move fired for nodes the canvas no longer had').toHaveLength(0);

    act(() => setCanvasNodes([canvasNode('n-a')], WF));
    settle();
    expect(events, 'the step was never framed after its node came back').toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-a']);
  });

  it('records what it ACTUALLY framed, so an unchanged canvas does not move twice', () => {
    // The move is retargeted mid-debounce; what gets recorded must be the geometry the
    // camera went to, not the one it was aimed at when armed.
    setCanvasNodes([canvasNode('n-a', 0)], WF);
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    act(() => setCanvasNodes([canvasNode('n-a', 5000)], WF));
    settle();
    expect(events).toHaveLength(1);

    // Republishing the SAME geometry it ended up framing must not move it again.
    act(() => setCanvasNodes([canvasNode('n-a', 5000)], WF));
    settle();
    expect(events, 'it re-framed a rectangle it was already on').toHaveLength(1);
  });

  it('sends a move under the canvas its ids were resolved against, even if the prop changed', () => {
    // The ids were looked up in one canvas's node list. Stamping a later workflow id on
    // them would point a different canvas at nodes that are not running there.
    setCanvasNodes([canvasNode('n-a')], WF);
    setCanvasNodes([canvasNode('n-a')], 'wf-2');
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    rerender(<Harness steps={[step('n-a', 'running')]} workflowId="wf-2" />);
    settle();

    expect(events).toHaveLength(1);
    expect(events[0].detail.workflowId, 'the move was stamped with a canvas it was not resolved against').toBe('wf-2');
    expect(events[0].detail.nodeIds).toEqual(['n-a']);
  });

  it('does not re-render its host when the toggle is off and its OWN canvas publishes', () => {
    // The own canvas is the dominant publisher: every node status change during any run
    // and every drag. Only exercising foreign publishes leaves the gate untested,
    // because a foreign publish returns an identical array anyway.
    let renders = 0;
    function CountingHost({ steps }: { steps: StepEntry[] }) {
      renders += 1;
      useRunCameraFollow({
        steps,
        workflowId: WF,
        awaitingSignalAliases: null,
        isViewingHistoricalEpoch: false,
        isPreviewOnly: false,
        isRunActive: true,
      });
      return null;
    }
    act(() => setRunCameraFollowEnabled(false));
    render(<CountingHost steps={[step('n-a', 'running')]} />);
    const before = renders;
    for (let i = 0; i < 5; i++) {
      act(() => setCanvasNodes([canvasNode('n-a'), canvasNode(`extra${i}`, 900)], WF));
    }
    expect(renders - before, 'a disabled follow woke on its own canvas publishing').toBe(0);
  });


  // A GRID, not one cadence. The previous version of this test used 125 ms running and
  // 125 ms idle, which starts the next step at 250 ms, inside the 260 ms debounce, so
  // the retarget path rescued every move and the test passed against code that froze
  // the camera for whole runs. The bug is phase dependent, so one timing proves nothing.
  for (const [runMs, idleMs] of [[100, 50], [150, 150], [200, 150], [200, 300], [300, 500]]) {
    it(`moves through a run of ${runMs} ms steps separated by ${idleMs} ms of idle`, () => {
      // The orchestrator sends a snapshot when a node completes and another when the
      // next starts, so "nothing running" arrives on its own between every pair.
      const ids = ['s1', 's2', 's3', 's4', 's5', 's6', 's7', 's8'];
      setCanvasNodes(ids.map((id, i) => canvasNode(id, i * 300)), WF);
      act(() => setRunCameraFollowEnabled(true));
      const { rerender } = render(<Harness steps={[step('s1', 'running')]} />);
      for (const id of ids.slice(1)) {
        act(() => { vi.advanceTimersByTime(runMs); });
        rerender(<Harness steps={[step('s1', 'completed')]} />); // the idle snapshot
        act(() => { vi.advanceTimersByTime(idleMs); });
        rerender(<Harness steps={[step(id, 'running')]} />);
      }
      // Asserted DURING the run, and as a RATE: "at least one move in eight steps" is
      // satisfied by a camera that follows almost nothing. The floor between moves is
      // FOLLOW_MIN_GAP_MS, so a run that followed properly gets roughly one move per
      // gap; half of that is a floor no frozen camera can reach.
      const elapsed = (runMs + idleMs) * 7;
      const expected = Math.floor(elapsed / FOLLOW_MIN_GAP_MS / 2);
      expect(events.length, 'the camera barely moved during the run').toBeGreaterThanOrEqual(
        Math.max(1, expected),
      );
    });
  }

  it('does not move when the run really has ENDED before the move matured', () => {
    // The other half of the same rule, and the reason an empty running set alone cannot
    // decide it: here the run is over, so the armed move must be abandoned.
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    rerender(<Harness steps={[step('n-a', 'completed')]} isRunActive={false} />);
    settle();
    expect(events, 'the camera moved although the run had ended').toHaveLength(0);
  });


  it('does not treat another canvas as already framed just because the geometry matches', () => {
    // A cloned or acquired workflow keeps its node ids and its layout, so the same key
    // and the same rounded positions occur on a second canvas. Without the workflow in
    // the framed state, the new canvas is silently considered done.
    setCanvasNodes([canvasNode('n-a', 100)], WF);
    setCanvasNodes([canvasNode('n-a', 100)], 'wf-2');
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);
    expect(events[0].detail.workflowId).toBe(WF);

    rerender(<Harness steps={[step('n-a', 'running')]} workflowId="wf-2" />);
    settle();
    expect(events, 'the second canvas was never framed').toHaveLength(2);
    expect(events[1].detail.workflowId).toBe('wf-2');
  });

  it('stays silent while the toggle is off', () => {
    act(() => setRunCameraFollowEnabled(false));
    render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(0);
  });

  it('never emits workflowFocusNode, which would select the node and hide the step list', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events.map((e) => e.type)).toEqual([WORKFLOW_FOLLOW_NODES_EVENT]);
  });

  it('names the workflow, so another canvas can ignore the move', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events[0].detail.workflowId).toBe(WF);
  });

  it('sends every running node, not just the first', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running'), step('n-b', 'running')]} />);
    settle();
    expect(events[0].detail.nodeIds).toEqual(['n-a', 'n-b']);
  });

  it('treats a step whose items are still running as running', () => {
    // A split reports `completed` at the top level while its items are in flight;
    // deriveEffectiveStatus is what tells them apart.
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'completed', { completed: 2, running: 1 })]} />);
    settle();
    expect(events[0].detail.nodeIds).toEqual(['n-a']);
  });

  it('debounces, so a chain of quick steps is one move and not a lurch', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    rerender(<Harness steps={[step('n-b', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-b']);
  });

  it('does not re-frame while the running set is unchanged', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    rerender(<Harness steps={[{ ...step('n-a', 'running'), executionTimeMs: 1200 }]} />);
    settle();
    expect(events).toHaveLength(1);
  });

  it('treats the same set in a different order as the same set', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running'), step('n-b', 'running')]} />);
    settle();
    rerender(<Harness steps={[step('n-b', 'running'), step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);
  });

  it('moves on when the running set changes', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    rerender(<Harness steps={[step('n-a', 'completed'), step('n-b', 'running')]} />);
    settle();
    expect(events.map((e) => e.detail.nodeIds)).toEqual([['n-a'], ['n-b']]);
  });

  it('holds the camera when the run ends rather than snapping somewhere arbitrary', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    rerender(<Harness steps={[step('n-a', 'completed')]} />);
    settle();
    expect(events).toHaveLength(1);
  });

  it('frames the same node again when it runs a second time', () => {
    // A loop body, or the first node of the next epoch. Treating it as already framed
    // would leave the user stranded wherever he had panned to.
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    rerender(<Harness steps={[step('n-a', 'completed')]} />);
    settle();
    rerender(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(2);
  });

  it('retries once the canvas publishes its nodes, instead of skipping the whole run', () => {
    // Opening a run link streams steps before the plan arrives. Recording the set as
    // framed at that point would leave a single long step unfollowed for its entire
    // duration, with nothing reporting a problem.
    setCanvasNodes([], WF);
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(0);

    act(() => setCanvasNodes([canvasNode('n-a')], WF));
    settle();
    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-a']);
  });

  it('does NOT lose a scheduled move to an unrelated re-render', () => {
    // The starvation that made this feature do nothing at all: steps arrive as a whole
    // new array several times a second, and if each re-render restarted the debounce
    // the timer would never be allowed to fire. Silent, and green.
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    for (let i = 0; i < 6; i++) {
      act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 3); });
      // Same running set, brand new objects: exactly what a WS snapshot looks like.
      rerender(<Harness steps={[{ ...step('n-a', 'running'), executionTimeMs: i }]} />);
    }
    // The burst lasts longer than the debounce, so the move must ALREADY have gone out.
    // Asserting only after settle() would pass on a timer that had been pushed back on
    // every tick, which is the starvation wearing a disguise.
    expect(events, 'the scheduled move was pushed back by the re-renders').toHaveLength(1);
    settle();
    expect(events).toHaveLength(1);
  });

  it('survives ITS OWN canvas republishing while a move is pending', () => {
    // Selecting a node, or any edit, republishes this workflow's nodes. That wakes the
    // hook with the running set unchanged, and a pending move must not be thrown away:
    // a canvas that republishes faster than the debounce would otherwise never move at
    // all, silently.
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    for (let i = 0; i < 6; i++) {
      act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 3); });
      act(() => setCanvasNodes([canvasNode('n-a'), canvasNode('n-b', 400)], WF));
    }
    expect(events, 'the republishes pushed the scheduled move back').toHaveLength(1);
    settle();
    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-a']);
  });

  it('does NOT lose a scheduled move when another workflow publishes its canvas', () => {
    // The node store notifies globally. A second workflow running in a side-panel tab
    // must not defer this one's camera move for as long as it runs.
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    for (let i = 0; i < 6; i++) {
      act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 3); });
      act(() => setCanvasNodes([canvasNode('foreign')], 'other-wf'));
    }
    expect(events, 'another workflow deferred this canvas move').toHaveLength(1);
    settle();
    expect(events).toHaveLength(1);
  });

  it('resolves aliases against ITS OWN workflow, not whichever canvas published last', () => {
    // Half the scoping lives on this side; the consumer check cannot catch a lookup
    // that silently used another canvas's nodes.
    setCanvasNodes([canvasNode('n-a')], WF);
    act(() => setCanvasNodes([canvasNode('someone-elses')], 'other-wf'));
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('someone-elses', 'running')]} />);
    settle();
    expect(events).toHaveLength(0);
  });

  it('spaces successive moves so one travel finishes before the next starts', () => {
    // The debounce alone is not smoothness: a set changing every few hundred ms clears
    // it every time and still cuts each 600 ms travel short.
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS + 10); });
    expect(events).toHaveLength(1);

    // The set changes again while that travel is still under way.
    rerender(<Harness steps={[step('n-b', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS + 10); });
    expect(events, 'second move cut the first travel short').toHaveLength(1);

    act(() => { vi.advanceTimersByTime(FOLLOW_MIN_GAP_MS); });
    expect(events).toHaveLength(2);
    expect(events[1].detail.nodeIds).toEqual(['n-b']);
  });

  it('fires nothing after unmount', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { unmount } = render(<Harness steps={[step('n-a', 'running')]} />);
    unmount();
    settle();
    expect(events).toHaveLength(0);
  });

  it('is quiet before any step has streamed', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={undefined} />);
    settle();
    expect(events).toHaveLength(0);
  });

  it('frames a node parked on a signal once nothing else runs, although the stream calls it running', () => {
    // The trap: a node waiting on an approval can keep reporting `running`, because the
    // last row written for it is the RUNNING one. The parked set is a SECOND channel,
    // and it ranks the node as WAITING: framed, because that is where the run stands.
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} awaitingSignalAliases={['n-a']} />);
    settleWaiting();
    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-a']);
  });

  it('frames an interface waiting on the user (awaiting_signal) when nothing runs', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'completed'), step('n-b', 'awaiting_signal')]} />);
    // Not in the first ordinary debounce: a waiting set must SETTLE first.
    settle();
    expect(events).toHaveLength(0);
    settleWaiting();
    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-b']);
  });

  it('frames a split whose items all wait, read from the status counts', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(
      <Harness
        steps={[step('n-b', 'pending', { completed: 1, failed: 0, skipped: 0, running: 0, awaitingSignal: 2 })]}
      />,
    );
    settleWaiting();
    expect(events.map((e) => e.detail.nodeIds)).toEqual([['n-b']]);
  });

  it('moves on to the next step when the waiting interface continues', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'awaiting_signal'), step('n-b', 'pending')]} />);
    settleWaiting();
    expect(events.map((e) => e.detail.nodeIds)).toEqual([['n-a']]);

    // __continue: the interface completes, a snapshot with nothing running arrives
    // between the two, then the next step starts.
    rerender(<Harness steps={[step('n-a', 'completed'), step('n-b', 'pending')]} />);
    rerender(<Harness steps={[step('n-a', 'completed'), step('n-b', 'running')]} />);
    settle();
    expect(events.map((e) => e.detail.nodeIds)).toEqual([['n-a'], ['n-b']]);
  });

  it('never mixes the tiers: a waiting interface does not widen the frame of the steps running after it', () => {
    // A non-blocking interface keeps waiting while the rest of the run goes on. A box
    // spanning both would zoom further out with every step.
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'awaiting_signal'), step('n-b', 'running')]} />);
    settleWaiting();
    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-b']);
  });

  // A GRID, for the same reason as the idle-gap grid below: the bounce is phase
  // dependent. A non-blocking interface keeps waiting while the steps after it run, so
  // every gap between two steps holds ONLY the waiting tier.
  for (const [runMs, gapMs] of [[100, 50], [200, 300], [300, 500], [600, 900]]) {
    it(`never bounces back to a waiting interface in the gaps between steps (${runMs} ms run, ${gapMs} ms gap)`, () => {
      act(() => setRunCameraFollowEnabled(true));
      act(() => setCanvasNodes(
        [canvasNode('n-a'), canvasNode('n-b', 400), canvasNode('n-c', 800), canvasNode('n-d', 1200)],
        WF,
      ));
      const iface = step('n-a', 'awaiting_signal');
      const { rerender } = render(<Harness steps={[iface, step('n-b', 'running')]} />);
      for (const next of ['n-c', 'n-d', 'n-b', 'n-c']) {
        act(() => { vi.advanceTimersByTime(runMs); });
        rerender(<Harness steps={[iface]} />);
        act(() => { vi.advanceTimersByTime(gapMs); });
        rerender(<Harness steps={[iface, step(next, 'running')]} />);
      }
      settle();
      expect(events.length).toBeGreaterThan(0);
      expect(events.map((e) => e.detail.nodeIds).flat(), 'the camera went back to the interface').not.toContain('n-a');
    });
  }

  it('does not go back to a waiting interface while a step this canvas does not draw is running', () => {
    // A sub-workflow's inner step, say: nothing to frame, but the run is moving, so the
    // wait has not settled.
    act(() => setRunCameraFollowEnabled(true));
    const iface = step('n-a', 'awaiting_signal');
    const { rerender } = render(<Harness steps={[iface, step('n-b', 'completed')]} />);
    act(() => { vi.advanceTimersByTime(300); });
    rerender(<Harness steps={[iface, step('undrawn', 'running')]} />);
    settleWaiting();
    expect(events).toHaveLength(0);
  });

  it('frames the interface once the steps after it are done and it is still waiting', () => {
    act(() => setRunCameraFollowEnabled(true));
    const iface = step('n-a', 'awaiting_signal');
    const { rerender } = render(<Harness steps={[iface, step('n-b', 'running')]} />);
    settle();
    rerender(<Harness steps={[iface, step('n-b', 'completed')]} />);
    settleWaiting();
    expect(events.map((e) => e.detail.nodeIds)).toEqual([['n-b'], ['n-a']]);
  });

  it('reaches a waiting node even when a running move was armed as the step before it ended', () => {
    // The running move keeps its aim through what looks like a gap; when that gap is a
    // real wait, the recheck after it lands is the only thing that frames the waiting node.
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-b', 'running')]} />);
    act(() => { vi.advanceTimersByTime(50); });
    rerender(<Harness steps={[step('n-b', 'completed'), step('n-a', 'awaiting_signal')]} />);
    settleWaiting();
    expect(events.map((e) => e.detail.nodeIds)).toEqual([['n-b'], ['n-a']]);
  });

  it('does not frame a node left waiting by a run that has ended', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'awaiting_signal')]} isRunActive={false} />);
    settleWaiting();
    expect(events).toHaveLength(0);
  });

  it('still follows the branch that is moving while another node waits on a signal', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(
      <Harness
        steps={[step('n-a', 'running'), step('n-b', 'running')]}
        awaitingSignalAliases={['n-a']}
      />,
    );
    settle();
    expect(events[0].detail.nodeIds).toEqual(['n-b']);
  });

  it('does not follow the live run while a PAST epoch is being read', () => {
    // The canvas paint is frozen on that epoch, so the camera would fly to nodes that
    // render as pending.
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} isViewingHistoricalEpoch />);
    settle();
    expect(events).toHaveLength(0);
  });

  it('follows nothing on a canvas with no workflow id, rather than guessing', () => {
    // getCanvasNodes(undefined) falls back to whichever canvas published last, so a
    // lookup there would resolve another workflow's nodes and the consumer guard
    // (undefined !== undefined) would wave it through.
    act(() => setCanvasNodes([canvasNode('n-a')], 'someone-else'));
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} workflowId={null} />);
    settle();
    expect(events).toHaveLength(0);
  });

  it('frames what it CAN when one step never resolves, instead of going silent', () => {
    // Unmatched steps are a live reality. Refusing to move until every alias resolves
    // turns one unmatchable step into a total outage with nothing reporting it.
    setCanvasNodes([canvasNode('n-a')], WF);
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running'), step('ghost', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-a']);
  });

  it('improves a partial frame once the missing node appears, and not otherwise', () => {
    setCanvasNodes([canvasNode('n-a')], WF);
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running'), step('n-b', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);

    // A publish that resolves nothing new must not move the camera again.
    act(() => setCanvasNodes([canvasNode('n-a')], WF));
    settle();
    expect(events).toHaveLength(1);

    act(() => setCanvasNodes([canvasNode('n-a'), canvasNode('n-b', 400)], WF));
    settle();
    expect(events).toHaveLength(2);
    expect(events[1].detail.nodeIds).toEqual(['n-a', 'n-b']);
  });

  it('lands on the set that is running when it fires, not the one it was armed for', () => {
    // n-c starts, then is skipped while n-a runs again. The armed move is retargeted
    // rather than cancelled: cancelling here was the third arm-time cancel, and on an
    // alternating set it killed every move for a whole run. What must never happen is
    // landing on n-c, which has stopped.
    setCanvasNodes([canvasNode('n-a'), canvasNode('n-c', 800)], WF);
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);

    rerender(<Harness steps={[step('n-a', 'completed'), step('n-c', 'running')]} />);
    act(() => { vi.advanceTimersByTime(30); });
    rerender(<Harness steps={[step('n-a', 'running'), step('n-c', 'skipped')]} />);
    settle();

    for (const e of events) {
      expect(e.detail.nodeIds, 'the camera landed on a node that had stopped').toEqual(['n-a']);
    }
  });

  for (const stepMs of [150, 200, 250]) {
    it(`follows a set that alternates every ${stepMs} ms, as a loop body does`, () => {
      // The shape no cadence grid exercised, because they all used monotonically new
      // step ids: a loop body against its loop node, or a fast fork branch against a
      // long one, makes the running set flip back to the one already framed.
      setCanvasNodes([canvasNode('a1'), canvasNode('a2', 600)], WF);
      act(() => setRunCameraFollowEnabled(true));
      const { rerender } = render(<Harness steps={[step('a1', 'running')]} />);
      for (let i = 0; i < 12; i++) {
        act(() => { vi.advanceTimersByTime(stepMs); });
        rerender(<Harness steps={[step(i % 2 === 0 ? 'a2' : 'a1', 'running')]} />);
      }
      // A camera that followed gets roughly one move per gap floor; one move in a run
      // this long is the frozen case.
      const expected = Math.max(2, Math.floor((stepMs * 12) / FOLLOW_MIN_GAP_MS / 2));
      expect(events.length, 'an alternating set froze the camera').toBeGreaterThanOrEqual(expected);
    });
  }

  it('moves during a FAST run, where every step is shorter than the debounce', () => {
    // The regression this pins: cancelling and restarting the timer on each set change
    // means a run of quick nodes (decision, transform, merge, table CRUD are all well
    // under 100 ms) never lets a timer mature, so the camera never moves once, for the
    // whole run, with the toggle reading as armed. Zero moves is worse than a wrong
    // move, because nothing reports it.
    setCanvasNodes(
      ['s1', 's2', 's3', 's4', 's5'].map((id, i) => canvasNode(id, i * 300)),
      WF,
    );
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('s1', 'running')]} />);
    for (const id of ['s2', 's3', 's4', 's5']) {
      act(() => { vi.advanceTimersByTime(150); });
      rerender(<Harness steps={[step(id, 'running')]} />);
    }
    act(() => { vi.advanceTimersByTime(150); });

    expect(events.length, 'a fast run never moved the camera').toBeGreaterThan(0);
    // And what landed names a step that really was running, never a stale one.
    for (const e of events) {
      expect(['s1', 's2', 's3', 's4', 's5']).toContain(e.detail.nodeIds[0]);
    }
  });

  it('a retargeted move lands on the CURRENT set, not the one it was armed for', () => {
    setCanvasNodes([canvasNode('n-a'), canvasNode('n-b', 400)], WF);
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    rerender(<Harness steps={[step('n-b', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds).toEqual(['n-b']);
  });

  it('drops a pending move when following is switched off mid-flight', () => {
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    act(() => setRunCameraFollowEnabled(false));
    settle();
    expect(events).toHaveLength(0);
  });

  it('drops a pending move when a past epoch is opened mid-flight', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    rerender(<Harness steps={[step('n-a', 'running')]} isViewingHistoricalEpoch />);
    settle();
    expect(events).toHaveLength(0);
  });

  it('re-frames when an auto-layout moves the node it is already following', () => {
    setCanvasNodes([canvasNode('n-a', 0)], WF);
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);

    // Same node, same running set, new position: the frame is now wrong.
    act(() => setCanvasNodes([canvasNode('n-a', 2000)], WF));
    settle();
    expect(events).toHaveLength(2);
  });


  it('reacts promptly after a quiet period, instead of paying the spacing floor again', () => {
    // The floor exists to stop one travel cutting the previous one short. Once the run
    // has been quiet for longer than a travel there is nothing to protect, so the next
    // move owes only the debounce. This also pins that the floor is measured on the
    // clock the timers run on: with a clock that does not advance, every gap looks like
    // zero and the floor would be charged forever.
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS + 10); });
    expect(events).toHaveLength(1);

    // Nothing happens for well over a full travel.
    act(() => { vi.advanceTimersByTime(FOLLOW_MIN_GAP_MS * 3); });
    rerender(<Harness steps={[step('n-b', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS + 10); });
    expect(events, 'the move waited the spacing floor although nothing preceded it').toHaveLength(2);
  });


  it('picks up a node that resolves DURING the debounce, instead of framing half the set', () => {
    // The plan can arrive between arming the move and it landing. If the payload is
    // frozen at arm time the second node stays off screen for the rest of both steps,
    // and the set is then recorded as framed so nothing re-arms.
    setCanvasNodes([canvasNode('n-a')], WF);
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running'), step('n-b', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    act(() => setCanvasNodes([canvasNode('n-a'), canvasNode('n-b', 400)], WF));
    settle();

    expect(events).toHaveLength(1);
    expect(events[0].detail.nodeIds, 'the move landed on the set frozen at arm time').toEqual(['n-a', 'n-b']);
  });

  it('holds the last drawn position when the run moves into steps this canvas does not draw', () => {
    // Inner-loop and sub-workflow steps are in the stream and match no node here. The
    // armed move is NOT dropped: its own node is still on screen, and cancelling on the
    // current set starved exactly as the idle branch used to, a run alternating a drawn
    // step with an undrawn one never moving the camera once. The feature's own standard
    // decides the tie: zero moves is worse than a move to the last place the run was
    // visible, because zero moves reports nothing.
    setCanvasNodes([canvasNode('n-a'), canvasNode('n-c', 800)], WF);
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);

    rerender(<Harness steps={[step('n-c', 'running')]} />);
    act(() => { vi.advanceTimersByTime(30); });
    rerender(<Harness steps={[step('core:while_x::step#1', 'running')]} />);
    settle();

    expect(events.map((e) => e.detail.nodeIds)).toEqual([['n-a'], ['n-c']]);
  });

  for (const stepMs of [30, 100, 200]) {
    it(`moves through a run alternating drawn and undrawn steps every ${stepMs} ms`, () => {
      // The sibling of the idle-snapshot starvation, and it was still open: judging the
      // armed move on whether the CURRENT set resolves cancels it every time the run
      // dips into a step this canvas does not draw.
      // Long enough that the run itself outlasts the debounce: a shorter run legitimately
      // has no time to move and would make this assert the wrong thing.
      const drawn = ['d1', 'd2', 'd3', 'd4', 'd5', 'd6', 'd7', 'd8'];
      setCanvasNodes(drawn.map((id, i) => canvasNode(id, i * 300)), WF);
      act(() => setRunCameraFollowEnabled(true));
      const { rerender } = render(<Harness steps={[step('d1', 'running')]} />);
      for (const id of drawn.slice(1)) {
        act(() => { vi.advanceTimersByTime(stepMs); });
        rerender(<Harness steps={[step('core:while_x::step#1', 'running')]} />);
        act(() => { vi.advanceTimersByTime(stepMs); });
        rerender(<Harness steps={[step(id, 'running')]} />);
      }
      expect(events.length, 'an undrawn step killed every move').toBeGreaterThan(0);
    });
  }

  it('follows nothing on a read-only canvas, whose toolbar has no control to stop it', () => {
    // A shared application mounts in run mode with steps streaming, and the preference
    // is a module singleton shared with the user's own canvas.
    act(() => setRunCameraFollowEnabled(true));
    render(<Harness steps={[step('n-a', 'running')]} isPreviewOnly />);
    settle();
    expect(events).toHaveLength(0);
  });

  it('drops an armed move when the run ends before it lands', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    act(() => { vi.advanceTimersByTime(FOLLOW_DEBOUNCE_MS / 2); });
    // The run is OVER, not merely between two steps: that distinction is the whole
    // reason the hook is told whether the run is still active.
    rerender(<Harness steps={[step('n-a', 'completed')]} isRunActive={false} />);
    settle();
    expect(events).toHaveLength(0);
  });

  it('re-frames what is running now when following is switched back on', () => {
    act(() => setRunCameraFollowEnabled(true));
    const { rerender } = render(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    act(() => setRunCameraFollowEnabled(false));
    rerender(<Harness steps={[step('n-a', 'running')]} />);
    settle();
    expect(events).toHaveLength(1);

    act(() => setRunCameraFollowEnabled(true));
    settle();
    expect(events).toHaveLength(2);
  });
});

describe('the call site', () => {
  // A callsite invariant, in the spirit of the repo's ParamAliasCreatorParityTest and
  // MemoryInjectionCallsiteInvariantTest. Everything else here tests the hook; nothing
  // tested that anything CALLS it, and deleting the call left the feature dead with the
  // whole suite green. Asserting the EXPRESSIONS, not merely that a key is present:
  // `isViewingHistoricalEpoch: false` would satisfy a presence check and disable the
  // guard it stands for.
  const source = fs.readFileSync(
    path.join(process.cwd(), 'app/workflows/builder/components/WorkflowBuilder.tsx'),
    'utf8',
  );
  const call = (() => {
    const at = source.indexOf('useRunCameraFollow({');
    return at === -1 ? '' : source.slice(at, source.indexOf('});', at));
  })();
  const codeOnly = call.replace(/^\s*\/\/.*$/gm, '');

  it('is in WorkflowBuilder, which owns the canvas, not in a side-panel tab', () => {
    // The run panel is one tab among several: following mounted there dies the moment
    // the user opens Chat or collapses the panel, with the toggle still reading armed.
    expect(call).not.toBe('');
  });


  it('sits in the View-controls group, so a run cannot hide the control that steers it', () => {
    // Counting toolbar buttons cannot see this: wrapping the group in a run-mode
    // condition would make a RUN-ONLY feature invisible during every run, with the
    // button count unchanged and every test green.
    const toolbar = fs.readFileSync(
      path.join(process.cwd(), 'app/workflows/builder/components/CanvasToolbar.tsx'),
      'utf8',
    );
    const group = toolbar.slice(
      toolbar.indexOf("title={t('fitView')}"),
      toolbar.indexOf("title={t('autoLayout')}"),
    );
    expect(group, 'the toggle left the Focus group').toContain('<CanvasRunFollowToggleButton />');
  });

  it('feeds it the aggregated streamed steps', () => {
    expect(call).toContain('steps: streamedSteps');
  });

  it('names the workflow, without which the feature is inert', () => {
    // Stripped of comments, so the word appearing in the prose above an argument
    // cannot satisfy this.
    expect(codeOnly).toContain('workflowId,');
  });

  it('feeds it the parked-on-signal set, which the step stream cannot express', () => {
    // A node waiting on an approval keeps reporting `running`, so without this channel
    // the camera parks on something that will never move.
    expect(call).toContain('awaitingSignalAliases: pauseResumeState.awaitingSignalSteps');
  });

  it('tells it whether the RUN is still going, which the step set cannot say', () => {
    // An empty running set means both "between two steps" and "the run is over".
    // Hard-coding this input either way silently restores one of the two failures the
    // signal exists to separate, and the suite would not notice.
    expect(call).toContain('isRunActive: isRunStatusActive(');
  });

  it('tells it when a past epoch is being read', () => {
    expect(call).toContain('isViewingHistoricalEpoch: viewingEpoch != null');
  });

  it('tells it when the canvas is read-only', () => {
    expect(codeOnly).toContain('isPreviewOnly,');
  });
});
