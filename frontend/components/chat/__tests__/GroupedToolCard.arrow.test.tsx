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
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';

// Force every useExpandedState (group + each call) open so CallTimelineItem - and
// thus its header arrow - renders. The shared toggle spy lets us prove the arrow
// click does NOT bubble to the call header's toggle (stopPropagation).
const hoisted = vi.hoisted(() => ({ toggle: vi.fn() }));
vi.mock('@/hooks/useExpandedState', () => ({
  useExpandedState: () => [true, hoisted.toggle],
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
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
import type { GroupedToolActivity } from '@/lib/utils/activityGrouping';

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
