/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { useMessageQueueStore } from '@/lib/stores/message-queue-store';

const mocks = vi.hoisted(() => ({
  clearPendingAction: vi.fn(),
  streaming: {
    isStreamingConversation: vi.fn(),
    getStreamState: vi.fn(),
    getStreamContent: vi.fn(),
    getToolActivities: vi.fn(),
    getPendingServiceApprovals: vi.fn(),
    clearServiceApproval: vi.fn(),
    getPendingToolAuthorizations: vi.fn(),
    clearToolAuthorization: vi.fn(),
    stopStream: vi.fn(),
    checkAndReconnect: vi.fn(),
  },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => mocks.streaming,
  serviceApprovalKey: (_services: Array<{ serviceType: string }>, needsAttention = false) =>
    needsAttention ? 'svc:attention' : 'svc:connect',
  toolAuthorizationKey: (rule: string) => 'auth:' + rule,
  mergePendingServiceApprovals: (existing: any, incoming: any) => ({
    ...existing,
    services: [...existing.services, ...incoming.services],
    needsAttention: existing.needsAttention || incoming.needsAttention,
  }),
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
  conversationApi: { clearPendingAction: mocks.clearPendingAction },
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
  MessageComposer: () => <div data-testid="composer" />,
}));

function streamState(status: string) {
  return {
    status,
    streamId: 'stream-1',
    content: '',
    error: null,
    toolActivities: [],
    pendingServiceApprovals: [],
    pendingToolAuthorizations: [],
  };
}

describe('ChatCore Escape stop shortcut', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.clearPendingAction.mockResolvedValue(undefined);
    mocks.streaming.getStreamContent.mockReturnValue('');
    mocks.streaming.getToolActivities.mockReturnValue([]);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([]);
    mocks.streaming.getStreamState.mockReturnValue(streamState('streaming'));
  });

  it('calls the same stop handler as the Stop button when the current conversation is streaming', () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    const onStopStream = vi.fn();

    render(
      <ChatCore
        conversationId="conv-1"
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
        onStopStream={onStopStream}
      />,
    );

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onStopStream).toHaveBeenCalledTimes(1);
    expect(mocks.streaming.stopStream).not.toHaveBeenCalled();
  });

  it('falls back to streaming.stopStream when no external stop handler is provided', () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(true);

    render(<ChatCore conversationId="conv-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(mocks.streaming.stopStream).toHaveBeenCalledWith('conv-1');
  });

  it('also stops while a stream is starting before the stream is attached', () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    const onStopStream = vi.fn();

    render(
      <ChatCore
        conversationId={null}
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
        onStopStream={onStopStream}
        isStreamStarting
      />,
    );

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onStopStream).toHaveBeenCalledTimes(1);
  });

  it('ignores Escape when the conversation is idle', () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue(streamState('completed'));
    const onStopStream = vi.fn();

    render(
      <ChatCore
        conversationId="conv-1"
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
        onStopStream={onStopStream}
      />,
    );

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onStopStream).not.toHaveBeenCalled();
    expect(mocks.streaming.stopStream).not.toHaveBeenCalled();
  });
});
