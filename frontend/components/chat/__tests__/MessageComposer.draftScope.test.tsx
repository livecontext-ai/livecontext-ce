/**
 * @vitest-environment jsdom
 *
 * Regression coverage for composer draft scoping. The parent ChatCore input state
 * is shared by the mounted chat surface, so a conversation switch can render once
 * with the previous conversation's inputValue. MessageComposer must ignore that
 * stale value and hydrate only the target conversation draft slot.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';
import { readDraft, writeDraft } from '@/lib/chat/draftStorage';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: null, isPending: false }),
}));

vi.mock('@/hooks/useDefaultSkills', () => ({
  useDefaultSkills: () => ({
    activeSkillIds: new Set<string>(),
    setActiveSkillIds: vi.fn(),
    initializeDefaults: vi.fn(),
    hasExplicitSkillSelection: false,
  }),
}));

vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: { getAgentByConversationId: vi.fn() },
}));

vi.mock('../AttachmentHandler', () => ({ AttachmentHandler: () => null }));

import { MessageComposer } from '../MessageComposer';

function renderComposer(props: {
  conversationId?: string;
  inputValue?: string;
  onInputChange?: (value: string) => void;
}) {
  return render(
    <MessageComposer
      inputValue={props.inputValue ?? ''}
      onInputChange={props.onInputChange ?? (() => undefined)}
      onSendMessage={() => undefined}
      showAttachmentMenu={false}
      onShowAttachmentMenu={() => undefined}
      conversationId={props.conversationId}
    />,
  );
}

describe('MessageComposer draft scope', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(10_000);
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('clears stale parent input when switching to a conversation with no draft', async () => {
    const onInputChange = vi.fn();
    const view = renderComposer({ conversationId: 'conv-a', onInputChange });
    const input = screen.getByPlaceholderText('chat.placeholder') as HTMLTextAreaElement;

    fireEvent.change(input, { target: { value: 'draft for A' } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    expect(readDraft('conv-a')).toBe('draft for A');

    await act(async () => {
      view.rerender(
        <MessageComposer
          inputValue="draft for A"
          onInputChange={onInputChange}
          onSendMessage={() => undefined}
          showAttachmentMenu={false}
          onShowAttachmentMenu={() => undefined}
          conversationId="conv-b"
        />,
      );
    });

    expect(input.value).toBe('');
    expect(onInputChange).toHaveBeenLastCalledWith('');
    expect(readDraft('conv-a')).toBe('draft for A');
    expect(readDraft('conv-b')).toBeNull();
  });

  it('restores only the target conversation draft when parent input is stale', async () => {
    const onInputChange = vi.fn();
    writeDraft('conv-b', 'draft for B', 1_000);

    const view = renderComposer({ conversationId: 'conv-a', inputValue: 'draft for A', onInputChange });
    await act(async () => {
      view.rerender(
        <MessageComposer
          inputValue="draft for A"
          onInputChange={onInputChange}
          onSendMessage={() => undefined}
          showAttachmentMenu={false}
          onShowAttachmentMenu={() => undefined}
          conversationId="conv-b"
        />,
      );
    });

    const input = screen.getByPlaceholderText('chat.placeholder') as HTMLTextAreaElement;
    expect(input.value).toBe('draft for B');
    expect(onInputChange).toHaveBeenLastCalledWith('draft for B');
    expect(readDraft('conv-a')).toBe('draft for A');
  });
});
