/**
 * @vitest-environment jsdom
 *
 * The ↗ "open in side panel" arrow on a grouped tool call. This is the surface
 * shared by BOTH the chat reasoning feed and the Conversation Activity card, so it
 * is pinned here (the card test mocks GroupedToolCard out).
 *
 * Pins: the arrow shows ONLY for an openable visualization; clicking it dispatches
 * the `sidePanelAutoOpen` event with the right detail; and it stops propagation so
 * it does NOT toggle the call's inline expand.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import * as React from 'react';

// Force every useExpandedState (group + each call) open so CallTimelineItem - and
// thus its header arrow - renders. The shared toggle spy lets us prove the arrow
// click does NOT bubble to the call header's toggle (stopPropagation).
const hoisted = vi.hoisted(() => ({ toggle: vi.fn() }));
vi.mock('@/hooks/useExpandedState', async () => {
  const React = await import('react');
  return {
    // Everything is forced open so the call rows render, EXCEPT the group's
    // call-history flag ("<group>:calls"), which is stored through this same
    // hook so a reveal survives the feed collapsing - forcing that one open
    // would disable the cap under test.
    useExpandedState: (id: string) => {
      const [open, setOpen] = React.useState(false);
      if (!id.endsWith(':calls')) return [true, hoisted.toggle];
      return [open, () => setOpen(previous => !previous)];
    },
  };
});
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(',')}` : key,
}));
vi.mock('next/image', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useResourceQuery', () => ({
  useResourceQuery: () => ({ data: undefined, isLoading: false, error: null }),
}));
vi.mock('@/lib/api', () => ({
  apiClient: { get: async () => ({ content: '' }) },
  orchestratorApi: { deleteWorkflow: async () => {} },
}));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({
  WorkflowActionIcon: () => null,
  getWorkflowActionIcon: () => null,
}));
vi.mock('@/app/workflows/builder/data/nodeVisuals', () => ({
  resolveNodeIcon: () => ({ icon: () => null, iconBg: '' }),
}));
vi.mock('@/lib/utils/extractWebSearchUrls', () => ({ extractWebSearchUrls: () => [] }));
vi.mock('@/lib/credentials/iconSlug', () => ({ normalizeIconSlug: (s: string) => s }));
vi.mock('@/components/MarkdownRender', () => ({ default: () => null }));
vi.mock('@/components/ui/FaviconStack', () => ({ FaviconStack: () => null }));
vi.mock('../TasksPreviewBlock', () => ({ TasksPreviewBlock: () => null }));
vi.mock('../DiffView', () => ({ default: () => null }));
vi.mock('../GitStatusView', () => ({ default: () => null }));
vi.mock('../CredentialCard', () => ({ CredentialCard: () => null }));
vi.mock('../ConfirmDeleteModal', () => ({ ConfirmDeleteModal: () => null }));
vi.mock('../AgentBrowseLivePreview', () => ({ AgentBrowseLivePreview: () => null }));
vi.mock('../ToolResultFileRefPreviews', () => ({ ToolResultFileRefPreviews: () => null }));

import { GroupedToolCard } from '../GroupedToolCard';
import { ActivityFeed, type ToolActivity } from '../ActivityFeed';
import type { GroupedToolActivity } from '@/lib/utils/activityGrouping';

afterEach(cleanup);

/**
 * The reasoning feed's cap, against REAL grouping (useStableGroupedActivities is
 * not stubbed in this file, and GroupedToolCard is the real component).
 *
 * <p>The cap works on two levels and both are needed: the feed keeps the last
 * VISIBLE_STEPS steps, and a step is a GROUP however many calls it holds. A turn
 * that calls one tool thirty times is a single step, so without the second level
 * thirty expanded call rows walk straight past the feed's cap - which is the
 * shape a bridge agent produces most often (30x Read, 20x catalog).
 */
const call = (id: string, toolName: string, status: ToolActivity['status'], displayToolName?: string): ToolActivity => ({
  id, toolId: id, toolName, status, timestamp: 2,
  displayToolName, arguments: JSON.stringify({ action: 'get' }),
});
const bigReasoning: ToolActivity = {
  id: 'reasoning', toolId: 'reasoning', toolName: '_thinking', status: 'success', timestamp: 1,
  thinkingMessage: 'Long reasoning '.repeat(1000),
};

it.each([true, false])('keeps a pending grouped tool visible after large reasoning (streaming=%s)', isStreaming => {
  const activities: ToolActivity[] = [
    bigReasoning,
    ...['a', 'b'].map(id => call(id, 'workflow', 'success')),
    call('latest', 'workflow', 'pending', 'Latest pending tool'),
  ];
  render(<ActivityFeed activities={activities} isStreaming={isStreaming} />);

  // A pending call opens the feed on its own, streaming or not.
  expect(screen.getByRole('button', { expanded: true })).toBeTruthy();
  // _thinking + one group of three workflow calls = 2 steps, under both caps.
  expect(screen.queryByText(/previousSteps/)).toBeNull();
  expect(screen.getByText('Latest pending tool')).toBeTruthy();
});

it('caps the calls INSIDE one group, so repeating one tool cannot bypass the feed cap', () => {
  const activities: ToolActivity[] = [
    ...Array.from({ length: 7 }, (_, i) => call(`c${i}`, 'files', 'success', `read-${i}`)),
  ];
  render(<ActivityFeed activities={activities} isStreaming />);

  // One group = one step, so the FEED cap never fires here.
  for (const hidden of ['read-0', 'read-1', 'read-2']) {
    expect(screen.queryByText(hidden)).toBeNull();
  }
  for (const shown of ['read-3', 'read-4', 'read-5', 'read-6']) {
    expect(screen.getByText(shown)).toBeTruthy();
  }

  fireEvent.click(screen.getByText('previousSteps:3'));
  expect(screen.getByText('read-0')).toBeTruthy();

  fireEvent.click(screen.getByText('hidePreviousSteps'));
  expect(screen.queryByText('read-0')).toBeNull();
});

it('lets a pending GROUP carry the live cue on its own, without doubling it', () => {
  // The feed suppresses its standalone pulsing dot when a visible row already
  // shows one. For a group that row is the card header (spinner + count), which
  // only the grouped branch of that check can see.
  const activities: ToolActivity[] = [
    call('a', 'workflow', 'success'),
    call('b', 'workflow', 'pending', 'Still running'),
  ];
  render(<ActivityFeed activities={activities} isStreaming />);

  expect(screen.getByText('Still running')).toBeTruthy();
  // The group's own cue is its header spinner; the feed must not add its dot
  // on top of it.
  expect(document.querySelectorAll('.animate-spin')).toHaveLength(1);
  expect(document.querySelectorAll('.bg-blue-500.animate-pulse')).toHaveLength(0);
});

it('keeps a live cue when the step cap hides the pending GROUP', () => {
  const activities: ToolActivity[] = [
    call('slow', 'workflow', 'pending', 'Started first'),
    ...['table', 'interface', 'files', 'search', 'agent']
      .map((toolName, i) => call(`later-${i}`, toolName, 'success', `later-${i}`)),
  ];
  render(<ActivityFeed activities={activities} isStreaming />);

  expect(screen.queryByText('Started first')).toBeNull();
  expect(document.querySelectorAll('.bg-blue-500.animate-pulse')).toHaveLength(1);
});

it('caps the STEPS when the turn uses many different tools', () => {
  const activities: ToolActivity[] = ['table', 'interface', 'workflow', 'files', 'agent', 'search']
    .map((toolName, i) => call(`s${i}`, toolName, 'success', `step-${i}`));
  render(<ActivityFeed activities={activities} isStreaming />);

  // Six distinct tools group into six steps; the two oldest go behind the row.
  expect(screen.getByText('previousSteps:2')).toBeTruthy();
  expect(screen.queryByText('step-0')).toBeNull();
  expect(screen.getByText('step-5')).toBeTruthy();
});

function makeGroup(visualization: unknown): GroupedToolActivity {
  const call = {
    id: 'c1',
    toolName: 'workflow',
    status: 'success',
    timestamp: 1,
    durationMs: 100,
    arguments: JSON.stringify({ action: 'get' }),
    visualization,
  };
  return {
    type: 'group',
    id: 'group-c1',
    toolName: 'workflow',
    calls: [call],
    overallStatus: 'success',
    totalDurationMs: 100,
    timestamp: 1,
    visualizations: visualization ? [visualization] : [],
  } as unknown as GroupedToolActivity;
}

describe('GroupedToolCard ↗ open-in-side-panel arrow', () => {
  beforeEach(() => hoisted.toggle.mockClear());

  it('shows the arrow for an openable visualization and dispatches sidePanelAutoOpen on click', () => {
    const events: CustomEvent[] = [];
    const handler = (e: Event) => events.push(e as CustomEvent);
    window.addEventListener('sidePanelAutoOpen', handler);
    try {
      render(<GroupedToolCard group={makeGroup({ type: 'workflow', id: 'wf1', title: 'My WF' })} isStreaming />);
      const arrow = screen.getByTitle('openInSidePanel');
      fireEvent.click(arrow);

      expect(events).toHaveLength(1);
      expect(events[0].detail).toMatchObject({ type: 'workflow', id: 'wf1', title: 'My WF' });
      // stopPropagation: the arrow click must NOT toggle the call's inline expand.
      expect(hoisted.toggle).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('sidePanelAutoOpen', handler);
    }
  });

  it('renders no arrow for a non-openable call (no visualization)', () => {
    render(<GroupedToolCard group={makeGroup(undefined)} isStreaming />);
    expect(screen.queryByTitle('openInSidePanel')).toBeNull();
  });

  it('renders no arrow for a visualization whose type is not openable', () => {
    render(<GroupedToolCard group={makeGroup({ type: 'slide', id: 's1' })} isStreaming />);
    expect(screen.queryByTitle('openInSidePanel')).toBeNull();
  });
});
