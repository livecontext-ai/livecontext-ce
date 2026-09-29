/**
 * @vitest-environment jsdom
 *
 * ChatCore drives the composer's "back to the latest message" arrow, on the main
 * conversation and in the side panel alike (both render ChatCore): shown once the
 * reader scrolls away from the bottom, and only when the conversation has messages.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { useMessageQueueStore } from '@/lib/stores/message-queue-store';

const mocks = vi.hoisted(() => ({
  streaming: {
    isStreamingConversation: vi.fn(),
    getStreamState: vi.fn(),
    getStreamContent: vi.fn(),
    getToolActivities: vi.fn(),
    getPendingServiceApprovals: vi.fn(),
    clearServiceApproval: vi.fn(),
    getPendingToolAuthorizations: vi.fn(),
    clearToolAuthorization: vi.fn(),
    getPendingAskUserQuestions: vi.fn(),
    clearAskUserQuestion: vi.fn(),
    stopStream: vi.fn(),
    checkAndReconnect: vi.fn(),
  },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => mocks.streaming,
  askUserKey: (toolCallId: string) => 'ask:' + toolCallId,
  serviceApprovalKey: () => 'svc:connect',
  toolAuthorizationKey: (rule: string) => 'auth:' + rule,
  mergePendingServiceApprovals: (existing: any) => existing,
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    deleteWorkflow: vi.fn(),
    deleteDataSource: vi.fn(),
    deleteInterface: vi.fn(),
    deleteAgent: vi.fn(),
  },
}));
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: { clearPendingAction: vi.fn() },
}));
vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({ updateConfig: vi.fn(), config: {}, isLoading: false, isSaving: false, error: null }),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: vi.fn() },
}));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({ useAnchorScrollToBottom: vi.fn() }));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div data-testid="history" /> }));
vi.mock('@/components/chat/ServiceApprovalCard', () => ({ ServiceApprovalCard: () => null }));
vi.mock('@/components/chat/ToolAuthorizationCard', () => ({ ToolAuthorizationCard: () => null }));
vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: (props: { showScrollToBottom?: boolean; onScrollToBottom?: () => void }) => (
    <button
      data-testid="composer"
      data-show-arrow={String(!!props.showScrollToBottom)}
      onClick={() => props.onScrollToBottom?.()}
    />
  ),
}));

function streamState(status: string) {
  return { status, streamId: 's1', content: '', error: null, toolActivities: [], pendingServiceApprovals: [], pendingToolAuthorizations: [] };
}

type ChatMessages = React.ComponentProps<typeof ChatCore>['messages'];

const MESSAGES = [
  { role: 'user', content: 'hi', timestamp: '2026-01-01T00:00:00Z' },
  { role: 'assistant', content: 'hello', timestamp: '2026-01-01T00:00:01Z' },
] as ChatMessages;

/** Puts the messages container at a given scroll position and fires the scroll event. */
function scrollContainerTo(scrollTop: number) {
  const container = document.querySelector('.chat-messages-container') as HTMLElement;
  Object.defineProperty(container, 'scrollHeight', { configurable: true, value: 2000 });
  Object.defineProperty(container, 'clientHeight', { configurable: true, value: 500 });
  container.scrollTop = scrollTop;
  act(() => {
    fireEvent.scroll(container);
  });
  return container;
}

function renderChat(messages: ChatMessages) {
  return render(
    <ChatCore conversationId="conv-1" conversation={null} messages={messages} onSendMessage={vi.fn()} />,
  );
}

describe('ChatCore - back to the latest message arrow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamContent.mockReturnValue('');
    mocks.streaming.getToolActivities.mockReturnValue([]);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([]);
    mocks.streaming.getStreamState.mockReturnValue(streamState('idle'));
  });

  it('asks for the arrow once the reader scrolls up in a conversation with messages', () => {
    renderChat(MESSAGES);
    expect(screen.getByTestId('composer')).toHaveAttribute('data-show-arrow', 'false');

    scrollContainerTo(100);
    expect(screen.getByTestId('composer')).toHaveAttribute('data-show-arrow', 'true');
  });

  it('hides it again once the reader is back at the bottom', () => {
    renderChat(MESSAGES);
    scrollContainerTo(100);
    scrollContainerTo(1500);
    expect(screen.getByTestId('composer')).toHaveAttribute('data-show-arrow', 'false');
  });

  it('never asks for it when the conversation has no messages yet', () => {
    renderChat([]);
    scrollContainerTo(100);
    expect(screen.getByTestId('composer')).toHaveAttribute('data-show-arrow', 'false');
  });

  it('scrolls the messages container to the bottom when the arrow is pressed', () => {
    renderChat(MESSAGES);
    const container = scrollContainerTo(100);
    const scrollTo = vi.fn();
    container.scrollTo = scrollTo as unknown as typeof container.scrollTo;

    fireEvent.click(screen.getByTestId('composer'));
    expect(scrollTo).toHaveBeenCalledWith({ top: 2000, behavior: 'smooth' });
  });
});
