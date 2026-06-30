import { describe, expect, it, vi } from 'vitest';
import type React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

vi.mock('next/navigation', () => ({
  usePathname: () => '/app/tasks',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
  redirect: vi.fn(),
  notFound: vi.fn(),
}));

vi.mock('@/i18n/navigation', () => ({
  Link: ({ children }: { children: React.ReactNode }) => children,
  redirect: vi.fn(),
  usePathname: () => '/app/tasks',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  getPathname: () => '/app/tasks',
}));

import {
  initialStreamingState,
  streamingReducer,
} from '../ConversationPanelContent';

describe('ConversationPanelContent streaming reducer', () => {
  it('recovers streaming state from content when stream_started was missed', () => {
    const state = streamingReducer(initialStreamingState, {
      type: 'CONTENT',
      chunk: 'The agent is still working.',
    });

    expect(state.isStreaming).toBe(true);
    expect(state.content).toBe('The agent is still working.');
  });

  it('recovers streaming state from a tool call when stream_started was missed', () => {
    const state = streamingReducer(initialStreamingState, {
      type: 'TOOL_CALL',
      toolName: 'agent',
      toolId: 'tool-1',
      arguments: '{"action":"task_get_context"}',
      thinkingMessage: 'Checking the task context.',
    });

    expect(state.isStreaming).toBe(true);
    expect(state.toolActivities).toHaveLength(1);
    expect(state.toolActivities[0]).toMatchObject({
      toolName: 'agent',
      toolId: 'tool-1',
      status: 'pending',
      thinkingMessage: 'Checking the task context.',
    });
  });

  it('turns streaming off only when the stream terminal event arrives', () => {
    const streaming = streamingReducer(initialStreamingState, {
      type: 'TOOL_CALL',
      toolName: 'agent',
      toolId: 'tool-1',
    });

    const withResult = streamingReducer(streaming, {
      type: 'TOOL_RESULT',
      toolName: 'agent',
      toolId: 'tool-1',
      success: true,
      durationMs: 42,
    });

    expect(withResult.isStreaming).toBe(true);
    expect(withResult.toolActivities[0]).toMatchObject({
      status: 'success',
      durationMs: 42,
    });

    const completed = streamingReducer(withResult, { type: 'COMPLETED' });

    expect(completed.isStreaming).toBe(false);
  });
});
