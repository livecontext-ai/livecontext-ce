/**
 * @vitest-environment jsdom
 *
 * The Conversation Activity card's tool row has bespoke click behavior: openable
 * resources open the right side panel (sidePanelAutoOpen event), non-openable
 * tools do nothing, and reasoning rows render text. These pin that contract.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';

vi.mock('next/image', () => ({ default: () => null }));
// Light stubs for the shared icon map / name helper so we don't drag in
// ActivityFeed's transitive React/markdown graph.
vi.mock('@/components/chat/ActivityFeed', () => ({
  toolIcons: {},
  formatToolName: (n: string) => n,
}));
vi.mock('@/lib/utils/activityGrouping', () => ({
  getToolIconType: () => 'code',
  getToolDescription: () => 'Ran a tool',
}));
vi.mock('@/lib/credentials/iconSlug', () => ({ normalizeIconSlug: (s: string) => s }));

import { ActivityToolRow } from '@/components/chat/ActivityToolRow';
import type { ToolActivity } from '@/contexts/StreamingContext';

const tool = (over: Partial<ToolActivity>): ToolActivity =>
  ({ id: 't', toolName: 'x', toolId: 't', status: 'success', timestamp: 1, ...over } as ToolActivity);

describe('ActivityToolRow', () => {
  let events: CustomEvent[];
  const handler = (e: Event) => events.push(e as CustomEvent);

  beforeEach(() => {
    events = [];
    window.addEventListener('sidePanelAutoOpen', handler);
  });
  afterEach(() => {
    window.removeEventListener('sidePanelAutoOpen', handler);
  });

  it('opens the right side panel when the tool produced an openable resource', () => {
    render(
      <ActivityToolRow
        activity={tool({
          toolName: 'table',
          visualization: { type: 'table', id: 'tbl-1', title: 'Leads' } as ToolActivity['visualization'],
        })}
      />,
    );
    const button = screen.getByRole('button');
    fireEvent.click(button);
    expect(events).toHaveLength(1);
    expect(events[0].detail).toEqual({ type: 'table', id: 'tbl-1', title: 'Leads', runId: undefined });
  });

  it('opens the panel for a browser-agent (agent_browse) row', () => {
    render(
      <ActivityToolRow
        activity={tool({
          toolName: 'web_search',
          visualization: { type: 'agent_browse', id: 'if-9' } as ToolActivity['visualization'],
        })}
      />,
    );
    fireEvent.click(screen.getByRole('button'));
    expect(events).toHaveLength(1);
    expect(events[0].detail.type).toBe('agent_browse');
  });

  it('renders a non-interactive row (no button, no event) for a tool with no openable resource', () => {
    render(<ActivityToolRow activity={tool({ toolName: 'web_search' })} />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(events).toHaveLength(0);
  });

  it('renders reasoning text for a _thinking row and never opens a panel', () => {
    render(
      <ActivityToolRow
        activity={tool({ toolName: '_thinking', thinkingTitle: 'Planning', thinkingMessage: 'do the thing' })}
      />,
    );
    expect(screen.getByText('Planning')).toBeTruthy();
    expect(screen.getByText('do the thing')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });
});
