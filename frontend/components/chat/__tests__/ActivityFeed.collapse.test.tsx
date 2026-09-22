/**
 * @vitest-environment jsdom
 *
 * The reasoning feed's collapse contract, shared by the chat history and the
 * conversation side panel.
 *
 * <p>Regression guard for 7eea11d50, which replaced the collapse with a viewport
 * that is always mounted and bounded by height (`max-h-[min(35vh,16rem)]`), plus a
 * right-aligned "Show full history" / "Compact view" label in the header. Two
 * things broke with it: a stored conversation rendered a ~256px block of tools
 * under EVERY assistant message instead of one collapsed line, and the item cap
 * that kept a long turn readable was deleted, so all of a turn's tool rows mounted
 * at once.
 *
 * <p>What is pinned here: collapsed by default in stored history and unmounted
 * while collapsed; streaming / awaiting approval / a pending call opens it; only
 * the last VISIBLE_STEPS items render, in the live feed AND in stored history,
 * with the rest behind one inline row; and the header carries no text control
 * besides its own label.
 */
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';

// Translator stub that keeps the interpolated values visible, so an assertion can
// read the hidden-step count the component actually passed.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(',')}` : key,
}));
// Tool rows are open, which is both the streaming default for an allowlisted
// tool and the state a row returns in after the feed was collapsed.
vi.mock('@/hooks/useExpandedState', () => ({
  useExpandedState: () => [true, () => {}],
}));
// Identity grouping: one activity in, one timeline item out, so the cap
// arithmetic under test is the component's and not the grouper's.
vi.mock('@/hooks/useStableGroupedActivities', () => ({
  useStableGroupedActivities: (activities: unknown[]) => activities,
}));
vi.mock('@/lib/utils/activityGrouping', () => ({
  isGroupedTool: () => false,
  getToolDescription: (name: string) => name,
  getToolIconType: () => 'code',
}));
vi.mock('@/lib/api', () => ({ apiClient: { get: async () => ({ content: '' }) } }));
// Records how the result query was asked for. The point of the fix under test
// is that the request is driven by the row's EXPANDED STATE, not by the click
// that produced it - a row that comes back already expanded (the flag outlives
// the unmount, the fetched body would not) must still ask for its body.
const queryCalls: { enabled: boolean; key: unknown[] }[] = [];
vi.mock('@/lib/hooks/useResourceQuery', () => ({
  useResourceQuery: ({ enabled, queryKey }: { enabled: boolean; queryKey: unknown[] }) => {
    queryCalls.push({ enabled, key: queryKey });
    return { data: enabled ? 'the stored body' : undefined, isLoading: false, error: null };
  },
}));
vi.mock('next/image', () => ({ default: () => null }));
vi.mock('../GroupedToolCard', () => ({ GroupedToolCard: () => null }));
vi.mock('../TasksPreviewBlock', () => ({ TasksPreviewBlock: () => null }));
vi.mock('../DiffView', () => ({ default: () => null }));
vi.mock('../GitStatusView', () => ({ default: () => null }));
vi.mock('@/components/MarkdownRender', () => ({
  default: ({ text }: { text: string }) => <span>{text}</span>,
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));

import { ActivityFeed, type ToolActivity } from '../ActivityFeed';

// jsdom reports 0 for every layout box, so the reasoning clamp would never
// consider itself overflowing. These stand in for a real line box.
let clampedHeight = 96;
let fullHeight = 96;
const resizeCallbacks = new Set<ResizeObserverCallback>();
class TestResizeObserver {
  constructor(private callback: ResizeObserverCallback) { resizeCallbacks.add(callback); }
  observe() {}
  disconnect() { resizeCallbacks.delete(this.callback); }
}
beforeEach(() => {
  clampedHeight = 96;
  fullHeight = 96;
  vi.stubGlobal('ResizeObserver', TestResizeObserver);
  vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(() => fullHeight);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(() => clampedHeight);
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); resizeCallbacks.clear(); });

const tool = (id: string, status: ToolActivity['status'] = 'success'): ToolActivity => ({
  id, toolId: id, toolName: id, status, timestamp: 1, displayToolName: id,
});
// Hyphens, not underscores: the feed renders displayToolName with `_` swapped for a space.
const tools = (count: number) => Array.from({ length: count }, (_, i) => tool(`tool-${i}`));

const timeline = () => screen.queryByRole('region', { name: 'timeline' });
const header = () => screen.getByRole('button', { name: /^(thinking|duration)/ });

afterEach(cleanup);

describe('ActivityFeed collapse', () => {
  it('keeps stored history collapsed, rendering no tool row until the reader asks', () => {
    render(<ActivityFeed activities={tools(3)} isStreaming={false} />);

    expect(timeline()).toBeNull();
    expect(screen.queryByText('tool-0')).toBeNull();
    // Collapsed is a real unmount, not a hidden box: nothing of the timeline is
    // in the DOM, which is what makes a long conversation cheap to render.
    expect(header().getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(header());

    expect(timeline()).not.toBeNull();
    expect(screen.getByText('tool-0')).toBeTruthy();
  });

  it.each([
    ['streaming', { isStreaming: true }],
    ['awaiting approval', { awaitingApproval: true }],
  ])('opens on its own while %s', (_label, props) => {
    render(<ActivityFeed activities={tools(2)} {...props} />);

    expect(timeline()).not.toBeNull();
    expect(screen.getByText('tool-0')).toBeTruthy();
  });

  it('opens a collapsed stored feed when a call goes pending again', () => {
    const { rerender } = render(<ActivityFeed activities={tools(2)} isStreaming={false} />);
    expect(timeline()).toBeNull();

    rerender(<ActivityFeed activities={[...tools(2), tool('running', 'pending')]} isStreaming={false} />);

    expect(timeline()).not.toBeNull();
    expect(screen.getByText('running')).toBeTruthy();
  });

  it('collapses again from the Done row', () => {
    render(<ActivityFeed activities={tools(2)} isStreaming />);
    expect(timeline()).not.toBeNull();

    fireEvent.click(screen.getByText('done'));

    expect(timeline()).toBeNull();
  });

  it('keeps a collapse the reader performed, when the pending call resolves', () => {
    const { rerender } = render(
      <ActivityFeed activities={[...tools(2), tool('running', 'pending')]} isStreaming />);
    fireEvent.click(header());
    expect(timeline()).toBeNull();

    // hasPending flips, which is exactly what the auto-expand effect watches.
    rerender(<ActivityFeed activities={[...tools(2), tool('running')]} isStreaming />);

    expect(timeline()).toBeNull();
  });

  it('forgets that collapse once the feed is cleared for the next turn', () => {
    const { rerender } = render(<ActivityFeed activities={tools(2)} isStreaming />);
    fireEvent.click(header());
    expect(timeline()).toBeNull();

    rerender(<ActivityFeed activities={[]} thinkingMessage="Thinking..." isStreaming />);
    rerender(<ActivityFeed activities={tools(2)} isStreaming />);

    expect(timeline()).not.toBeNull();
  });

  it('carries no text control in the header, only the reasoning label', () => {
    // The rejected affordance: a right-aligned "Show full history" / "Compact
    // view" label. The header's whole text must stay the reasoning label.
    render(<ActivityFeed activities={tools(2)} isStreaming />);

    expect(header().textContent).toBe('duration:< 1s');
    expect(screen.queryByText(/expand|compact/)).toBeNull();
  });
});

describe('ActivityFeed step cap', () => {
  it.each([true, false])('renders only the last 4 items (streaming=%s)', isStreaming => {
    render(<ActivityFeed activities={tools(9)} isStreaming={isStreaming} />);
    if (!isStreaming) fireEvent.click(header());

    // The cap applies to stored history too. Before this it applied only while
    // streaming, so re-opening a finished turn mounted every row it ever had.
    for (const hidden of ['tool-0', 'tool-1', 'tool-2', 'tool-3', 'tool-4']) {
      expect(screen.queryByText(hidden)).toBeNull();
    }
    for (const shown of ['tool-5', 'tool-6', 'tool-7', 'tool-8']) {
      expect(screen.getByText(shown)).toBeTruthy();
    }
    expect(screen.getByText('previousSteps:5')).toBeTruthy();
  });

  it('reveals every older step on one click, and keeps them after new activity arrives', () => {
    const { rerender } = render(<ActivityFeed activities={tools(6)} isStreaming />);

    fireEvent.click(screen.getByText('previousSteps:2'));

    expect(screen.getByText('tool-0')).toBeTruthy();
    expect(screen.queryByText(/^previousSteps/)).toBeNull();

    rerender(<ActivityFeed activities={[...tools(6), tool('tool-6')]} isStreaming />);

    // Re-hiding what the reader just asked for would make the control useless
    // on a live feed, where a new tool lands every few seconds.
    expect(screen.getByText('tool-0')).toBeTruthy();
    expect(screen.getByText('tool-6')).toBeTruthy();
  });

  it('hides the older steps again from the same control', () => {
    // Every other disclosure in this feed is two-way; this one used to be a
    // one-way door, which silently disabled the cap for the rest of the turn.
    render(<ActivityFeed activities={tools(6)} isStreaming />);

    fireEvent.click(screen.getByText('previousSteps:2'));
    expect(screen.getByText('tool-0')).toBeTruthy();

    fireEvent.click(screen.getByText('hidePreviousSteps'));

    expect(screen.queryByText('tool-0')).toBeNull();
    expect(screen.getByText('previousSteps:2')).toBeTruthy();
  });

  it('starts capped again on the next turn, after a reveal', () => {
    const { rerender } = render(<ActivityFeed activities={tools(6)} isStreaming />);
    fireEvent.click(screen.getByText('previousSteps:2'));
    expect(screen.getByText('tool-0')).toBeTruthy();

    rerender(<ActivityFeed activities={[]} thinkingMessage="Thinking..." isStreaming />);
    rerender(<ActivityFeed activities={tools(6)} isStreaming />);

    expect(screen.getByText('previousSteps:2')).toBeTruthy();
    expect(screen.queryByText('tool-0')).toBeNull();
  });

  it('holds the boundary: 4 steps need no control, 5 hide exactly one', () => {
    const { rerender } = render(<ActivityFeed activities={tools(4)} isStreaming />);

    expect(screen.queryByText(/^previousSteps/)).toBeNull();
    expect(screen.getByText('tool-0')).toBeTruthy();

    rerender(<ActivityFeed activities={tools(5)} isStreaming />);

    expect(screen.getByText('previousSteps:1')).toBeTruthy();
    expect(screen.queryByText('tool-0')).toBeNull();
  });

  it('keeps the newest call visible even while it is still pending', () => {
    render(<ActivityFeed activities={[...tools(5), tool('running', 'pending')]} isStreaming />);

    expect(screen.getByText('running')).toBeTruthy();
    // The pending row carries its own pulsing dot, so the standalone dot that
    // used to stand in for the hidden pending call must not double it.
    expect(document.querySelectorAll('.bg-blue-500.animate-pulse')).toHaveLength(1);
  });

  it('still marks the stream as working when the cap hides the pending call', () => {
    // A parallel batch: the slow call started first and the later ones finished,
    // so the pending row is the one the cap drops. Suppressing the standalone
    // dot as well would leave a streaming feed with no live cue at all.
    render(<ActivityFeed activities={[tool('running', 'pending'), ...tools(5)]} isStreaming />);

    expect(screen.queryByText('running')).toBeNull();
    expect(document.querySelectorAll('.bg-blue-500.animate-pulse')).toHaveLength(1);
  });

  it('still marks the stream as working when the pending state has no row of its own', () => {
    // MessageHistory passes a thinking message with no pending activity between
    // two tool calls; the standalone dot is then the only live cue.
    render(<ActivityFeed activities={tools(2)} thinkingMessage="Thinking..." isStreaming />);

    expect(document.querySelectorAll('.bg-blue-500.animate-pulse')).toHaveLength(1);
    expect(screen.queryByText('done')).toBeNull();
  });
});

describe('ActivityFeed result bodies across a collapse', () => {
  const withResult = (): ToolActivity => ({
    id: 'stored', toolId: 'stored', toolName: 'files', status: 'success',
    timestamp: 1, displayToolName: 'stored', resultId: 'result-1',
  });

  it('asks for the body whenever the row is expanded, not only when it is clicked', () => {
    queryCalls.length = 0;
    render(<ActivityFeed activities={[withResult()]} isStreaming />);

    // useExpandedState is stubbed open here, which is exactly the state a row
    // comes back in after the feed was collapsed and re-opened.
    expect(queryCalls.some(c => c.enabled)).toBe(true);
    expect(queryCalls[0].key).toEqual(['tool-result', 'result-1']);
    expect(screen.getByText('the stored body')).toBeTruthy();
  });

  it('shows the body again after the feed is collapsed and re-opened', () => {
    render(<ActivityFeed activities={[withResult()]} isStreaming />);
    expect(screen.getByText('the stored body')).toBeTruthy();

    fireEvent.click(header());
    expect(timeline()).toBeNull();
    fireEvent.click(header());

    // Pre-fix this rendered the "no content" fallback over a result that exists.
    expect(screen.getByText('the stored body')).toBeTruthy();
    expect(screen.queryByText('tool.noContent')).toBeNull();
  });
});

describe('ActivityFeed reasoning length', () => {
  const thinking = (message: string): ToolActivity => ({
    id: 'reasoning', toolId: 'reasoning', toolName: '_thinking', status: 'success',
    timestamp: 1, thinkingMessage: message,
  });

  it('clamps an overflowing reasoning block behind a control that opens and closes it', () => {
    fullHeight = 900;   // the block is far taller than its clamped box
    render(<ActivityFeed activities={[thinking('Long reasoning '.repeat(200))]} isStreaming />);

    expect(screen.getByText(/Long reasoning/).className).toContain('line-clamp-6');

    fireEvent.click(screen.getByText('activityFeed.showMore'));

    // A clamp that cannot be opened would lose the model's reasoning outright.
    expect(screen.getByText(/Long reasoning/).className).not.toContain('line-clamp-6');

    fireEvent.click(screen.getByText('activityFeed.showLess'));

    expect(screen.getByText(/Long reasoning/).className).toContain('line-clamp-6');
  });

  it('offers no control when the clamp hides nothing, however long the text is', () => {
    // The decision is about LAYOUT, not length: a block that still fits inside
    // six lines must not get a button that visibly does nothing.
    fullHeight = 96;
    render(<ActivityFeed activities={[thinking('Long reasoning '.repeat(50))]} isStreaming />);

    expect(screen.queryByText('activityFeed.showMore')).toBeNull();
  });

  it('notices that a block started overflowing when the pane narrows', () => {
    render(<ActivityFeed activities={[thinking('Long reasoning '.repeat(50))]} isStreaming />);
    expect(screen.queryByText('activityFeed.showMore')).toBeNull();

    fullHeight = 480;
    act(() => { resizeCallbacks.forEach(callback => callback([], {} as ResizeObserver)); });

    expect(screen.getByText('activityFeed.showMore')).toBeTruthy();
  });
});
