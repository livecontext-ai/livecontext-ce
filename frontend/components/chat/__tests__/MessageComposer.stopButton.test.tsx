/**
 * @vitest-environment jsdom
 *
 * Regression test for the first-message startup gap: a new conversation starts
 * streaming under a temporary id before the real conversationId exists. The
 * composer must keep rendering Stop during that handoff, not fall back to a
 * disabled Send button.
 */
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: null }),
}));

vi.mock('@/hooks/useDefaultSkills', () => ({
  useDefaultSkills: () => ({
    activeSkillIds: new Set<string>(),
    setActiveSkillIds: vi.fn(),
    initializeDefaults: vi.fn(),
    hasExplicitSkillSelection: false,
  }),
}));

vi.mock('@/hooks/useMobileDetection', () => ({
  useMobileDetection: () => false,
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getAgentByConversationId: vi.fn(),
  },
}));

vi.mock('../AttachmentHandler', () => ({
  AttachmentHandler: () => null,
}));

import { MessageComposer } from '../MessageComposer';

describe('MessageComposer stop button', () => {
  it('shows Stop while a first conversation stream is starting without a conversationId', () => {
    const onStopStream = vi.fn();

    render(
      <MessageComposer
        inputValue=""
        onInputChange={() => undefined}
        onSendMessage={() => undefined}
        isStreamStarting
        onStopStream={onStopStream}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => undefined}
      />,
    );

    const stopButton = screen.getByTitle('chat.stop') as HTMLButtonElement;

    expect(stopButton.disabled).toBe(false);
    expect(screen.queryByTitle('chat.send')).toBeNull();

    fireEvent.click(stopButton);

    expect(onStopStream).toHaveBeenCalledTimes(1);
  });
});
