/**
 * @vitest-environment jsdom
 *
 * The activity card aggregates a conversation by execution: one collapsible group
 * per turn, newest expanded, a load-older affordance, and jump-to-message. These
 * pin that behavior (grouping itself is covered in messageActivity.test.ts).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';

// Translator echoes the key, and appends interpolation values so metric text
// (token/iteration counts) is assertable.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vals?: Record<string, unknown>) =>
    vals ? `${key}|${JSON.stringify(vals)}` : key,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));
vi.mock('@/lib/utils/dateFormatters', () => ({ formatUtcTime: (t: string) => t }));
// Metrics hook stubbed - mutable so tests can vary the execution record (e.g. status).
const hoisted = vi.hoisted(() => {
  const def = { totalTokens: 1234, iterationCount: 3, creditsConsumed: 0, durationMs: 5000 } as Record<string, unknown>;
  return { def, execution: { ...def } };
});
vi.mock('@/components/agent-fleet/hooks/useAgentExecutionData', () => ({
  useAgentExecution: () => ({ data: hoisted.execution }),
}));
// Render tool rows as markers so we can assert which group's body is expanded.
// Tools group exactly like the reasoning feed, so most rows render through
// GroupedToolCard (keyed on the first call's id); _thinking/agent/system tools
// render through ActivityToolRow. Both stub to `tool-<id>` so the same assertions hold.
vi.mock('@/components/chat/ActivityToolRow', () => ({
  ActivityToolRow: ({ activity }: { activity: { id: string; toolName: string } }) => (
    <div data-testid={`tool-${activity.id}`}>{activity.toolName}</div>
  ),
}));
vi.mock('@/components/chat/GroupedToolCard', () => ({
  GroupedToolCard: ({ group }: { group: { toolName: string; calls: { id: string }[] } }) => (
    <div data-testid={`tool-${group.calls[0].id}`}>{group.toolName}</div>
  ),
}));

import { ConversationActivityCard } from '@/components/chat/ConversationActivityCard';
import type { Message } from '@/lib/api/conversation.types';

const base = { conversationId: 'c1', model: 'm' } as const;
const messages: Message[] = [
  { ...base, id: 'u1', role: 'user', content: 'first question', createdAt: '2026-06-28T10:00:00Z', timestamp: '2026-06-28T10:00:00Z' },
  { ...base, id: 'a1', role: 'assistant', content: 'ans1', executionId: 'execA', createdAt: '2026-06-28T10:00:05Z', timestamp: '2026-06-28T10:00:05Z', toolCalls: JSON.stringify([{ id: 'toolA', toolName: 'table', success: true }]) },
  { ...base, id: 'u2', role: 'user', content: 'second question', createdAt: '2026-06-28T10:01:00Z', timestamp: '2026-06-28T10:01:00Z' },
  { ...base, id: 'a2', role: 'assistant', content: 'ans2', executionId: 'execB', createdAt: '2026-06-28T10:01:05Z', timestamp: '2026-06-28T10:01:05Z', toolCalls: JSON.stringify([{ id: 'toolB', toolName: 'interface', success: true }]) },
];

function renderCard(over: Partial<React.ComponentProps<typeof ConversationActivityCard>> = {}) {
  const props = {
    messages,
    liveToolActivities: [],
    isStreaming: false,
    hasMoreMessages: false,
    loadingOlderMessages: false,
    onLoadOlderMessages: vi.fn(),
    onJumpToMessage: vi.fn(),
    onClose: vi.fn(),
    ...over,
  };
  return { props, ...render(<ConversationActivityCard {...props} />) };
}

describe('ConversationActivityCard', () => {
  beforeEach(() => {
    hoisted.execution = { ...hoisted.def };
  });

  it('renders a group per execution with the sent-message preview', () => {
    renderCard();
    expect(screen.getByText('first question')).toBeTruthy();
    expect(screen.getByText('second question')).toBeTruthy();
  });

  it('expands the newest execution by default and collapses older ones', () => {
    renderCard();
    // Newest turn (execB) body is expanded -> its tool marker renders.
    expect(screen.getByTestId('tool-toolB')).toBeTruthy();
    // Older turn (execA) is collapsed -> its tool marker is absent.
    expect(screen.queryByTestId('tool-toolA')).toBeNull();
  });

  it('jumps to the sent message when its preview is clicked', () => {
    const { props } = renderCard();
    fireEvent.click(screen.getByText('first question'));
    expect(props.onJumpToMessage).toHaveBeenCalledWith('u1');
  });

  it('shows the load-older affordance only when more messages exist', () => {
    const { props } = renderCard({ hasMoreMessages: true });
    const loadOlder = screen.getByText('conversationActivity.loadOlder');
    fireEvent.click(loadOlder);
    expect(props.onLoadOlderMessages).toHaveBeenCalled();
  });

  it('renders an empty state with no messages', () => {
    renderCard({ messages: [] });
    expect(screen.getByText('conversationActivity.empty')).toBeTruthy();
  });

  it('docks top-right on desktop by default (right side panel closed), border removed at lg', () => {
    renderCard();
    const dialogCls = screen.getByRole('dialog').className;
    expect(dialogCls).toContain('lg:right-4');
    // Border only when centered; dropped at the desktop top-right dock.
    expect(dialogCls).toContain('lg:border-0');
  });

  it('centers (no right dock) with a border when the right side panel is open', () => {
    renderCard({ centered: true });
    const cls = screen.getByRole('dialog').className;
    expect(cls).not.toContain('lg:right-4');
    expect(cls).toContain('left-1/2');
    // Centered keeps the border at all sizes (no lg:border-0).
    expect(cls).toContain('border-theme');
    expect(cls).not.toContain('lg:border-0');
  });

  it('closes from the tablet/mobile focus backdrop (mirrors the side panel overlay)', () => {
    const { props, container } = renderCard();
    const backdrop = container.querySelector('.bg-black\\/50');
    expect(backdrop).toBeTruthy();
    fireEvent.click(backdrop!);
    expect(props.onClose).toHaveBeenCalled();
  });

  it('renders full observability metric values in the execution header', () => {
    renderCard();
    // Mocked hook returns totalTokens: 1234 -> locale-formatted "1,234".
    expect(screen.getAllByText(/1,234/).length).toBeGreaterThan(0);
  });

  it('disables the load-older affordance while older messages are loading', () => {
    renderCard({ hasMoreMessages: true, loadingOlderMessages: true });
    const btn = screen.getByTitle('conversationActivity.loadOlder') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
  });

  it('renders a non-clickable label for a group with no sent message (leading pre-group)', () => {
    const preGroup: Message[] = [
      { ...base, id: 'a0', role: 'assistant', content: 'greeting', createdAt: '2026-06-28T09:59:00Z', timestamp: '2026-06-28T09:59:00Z' },
    ];
    renderCard({ messages: preGroup });
    const label = screen.getByText('conversationActivity.executionFallback');
    expect(label.tagName).toBe('SPAN'); // not a button -> not clickable
  });

  // ----- streaming overlay -----

  it('overlays live tools onto the in-flight turn (newest matching the last user message)', () => {
    const inflight: Message[] = [
      ...messages,
      { ...base, id: 'u3', role: 'user', content: 'third question', createdAt: '2026-06-28T10:02:00Z', timestamp: '2026-06-28T10:02:00Z' },
    ];
    renderCard({
      messages: inflight,
      isStreaming: true,
      liveToolActivities: [{ id: 'liveX', toolName: 'web_search', toolId: 'liveX', status: 'pending', timestamp: 1 } as never],
    });
    // The in-flight turn (newest, expanded) shows the LIVE tool, not a persisted one.
    expect(screen.getByTestId('tool-liveX')).toBeTruthy();
    expect(screen.queryByTestId('tool-toolB')).toBeNull();
  });

  // ----- terminal marker reflects the authoritative execution status (reasoning style) -----

  it('closes a failed execution with a red Failed terminal marker (no Done)', () => {
    hoisted.execution = { ...hoisted.def, status: 'FAILED' };
    const { container } = renderCard(); // both turns have only successful tool rows
    // The execution record is authoritative: a model-level failure shows Failed, not Done.
    expect(screen.getByText('conversationActivity.failed')).toBeTruthy();
    expect(container.querySelector('.text-red-600')).toBeTruthy();
    expect(screen.queryByText('conversationActivity.done')).toBeNull();
  });

  it('closes a running execution with a reasoning-style blue pulse marker (label sr-only, no Done)', () => {
    hoisted.execution = { ...hoisted.def, status: 'RUNNING' };
    const { container } = renderCard();
    // Running mirrors the chat reasoning feed: a blue pulsing dot connected by the
    // tree line, with the "Working" label kept only for screen readers (sr-only),
    // instead of a visible spinner + text row.
    const workingLabel = screen.getByText('conversationActivity.working');
    expect(workingLabel.className).toContain('sr-only');
    expect(container.querySelectorAll('.animate-pulse.bg-blue-500').length).toBeGreaterThan(0);
    expect(screen.queryByText('conversationActivity.done')).toBeNull();
  });

  it('closes a completed execution with a Done terminal marker (reasoning style)', () => {
    const { container } = renderCard(); // default execution has no status, tools all succeed
    expect(screen.getByText('conversationActivity.done')).toBeTruthy();
    expect(container.querySelector('.text-slate-700')).toBeTruthy();
    expect(screen.queryByText('conversationActivity.failed')).toBeNull();
  });

  it('shows a synthetic live group when streaming with no persisted turn yet', () => {
    renderCard({
      messages: [],
      isStreaming: true,
      liveToolActivities: [{ id: 'liveY', toolName: 'table', toolId: 'liveY', status: 'pending', timestamp: 1 } as never],
    });
    expect(screen.getByTestId('tool-liveY')).toBeTruthy();
    // It is a live group, so the empty state must NOT show.
    expect(screen.queryByText('conversationActivity.empty')).toBeNull();
  });
});
