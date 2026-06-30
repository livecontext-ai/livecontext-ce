/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { MAX_QUEUE_SIZE, useMessageQueueStore } from '@/lib/stores/message-queue-store';

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
  useTranslations: () => (key: string, params?: Record<string, string>) => {
    if (key === 'credentials.toasts.credentialConfiguredResume') {
      return `Configured credentials for ${params?.services}`;
    }
    return key;
  },
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
  conversationApi: {
    clearPendingAction: mocks.clearPendingAction,
  },
}));

// ChatCore now reads conversation chat config (react-query) and can open the marketplace
// install modal - stub both so rendering ChatCore here needs no QueryClient / network.
vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({
    updateConfig: vi.fn(),
    config: {},
    isLoading: false,
    isSaving: false,
    error: null,
    target: 'conversation',
  }),
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: vi.fn() },
}));

vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({
  default: () => null,
}));

vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({
  useAnchorScrollToBottom: vi.fn(),
}));

vi.mock('@/components/chat/MessageHistory', () => ({
  MessageHistory: () => <div data-testid="message-history" />,
}));

vi.mock('@/components/chat/ServiceApprovalCard', () => ({
  ServiceApprovalCard: ({ onApproved }: { onApproved?: (serviceNames: string[]) => void }) => (
    <button type="button" onClick={() => onApproved?.(['Gmail'])}>
      approve-service
    </button>
  ),
}));

vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({ queuedMessages }: { queuedMessages?: Array<{ id: string; content: string }> }) => (
    <div data-testid="composer">
      {(queuedMessages ?? []).map((message) => (
        <div key={message.id} data-testid="queued-message">
          {message.content}
        </div>
      ))}
    </div>
  ),
}));

const pendingApproval = {
  services: [
    {
      serviceType: 'gmail',
      serviceName: 'Gmail',
      iconSlug: 'gmail',
      toolName: 'List Messages',
    },
  ],
  timestamp: 1,
};

describe('ChatCore service approval queue', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.clearPendingAction.mockResolvedValue(undefined);
    mocks.streaming.getStreamState.mockReturnValue({
      status: 'streaming',
      streamId: 'stream-1',
      content: 'working',
      error: null,
      toolActivities: [],
      pendingServiceApprovals: [pendingApproval],
      pendingToolAuthorizations: [],
    });
    mocks.streaming.getStreamContent.mockReturnValue('working');
    mocks.streaming.getToolActivities.mockReturnValue([]);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([pendingApproval]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('queuesCredentialResumeWhenApprovalHappensDuringActiveStream', async () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    const onSendMessage = vi.fn();

    render(
      <ChatCore
        conversationId="conversation-1"
        conversation={null}
        messages={[]}
        onSendMessage={onSendMessage}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));

    await waitFor(() => {
      expect(screen.getByTestId('queued-message')).toHaveTextContent('Configured credentials for Gmail');
    });
    expect(onSendMessage).not.toHaveBeenCalled();
    expect(useMessageQueueStore.getState().getQueue('conversation-1')[0]?.content).toBe('Configured credentials for Gmail');
    expect(screen.getByText('credentials.toasts.credentialConfigured')).toBeInTheDocument();
  });

  it('keepsCredentialResumeWhenTheExistingQueueIsFull', async () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    for (let i = 0; i < MAX_QUEUE_SIZE; i += 1) {
      useMessageQueueStore.getState().enqueue('conversation-1', {
        content: `Queued ${i}`,
        attachments: [],
      });
    }

    render(
      <ChatCore
        conversationId="conversation-1"
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));

    await waitFor(() => {
      expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(MAX_QUEUE_SIZE + 1);
    });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')[0]?.content).toBe('Configured credentials for Gmail');
  });

  it('drainsQueuedCredentialResumeWhenTheActiveStreamStops', async () => {
    vi.useFakeTimers();
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    const onSendMessage = vi.fn();
    const props = {
      conversationId: 'conversation-1',
      conversation: null,
      messages: [],
      onSendMessage,
    };

    const { rerender } = render(<ChatCore {...props} />);

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));
    expect(useMessageQueueStore.getState().getQueue('conversation-1')[0]?.content).toBe('Configured credentials for Gmail');

    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue({
      status: 'completed',
      streamId: 'stream-1',
      content: 'done',
      error: null,
      toolActivities: [],
      pendingServiceApprovals: [],
      pendingToolAuthorizations: [],
    });
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    rerender(<ChatCore {...props} />);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(350);
    });

    // Resume carries keepPendingActions so the backend keeps any sibling cards.
    expect(onSendMessage).toHaveBeenCalledWith('Configured credentials for Gmail', undefined, undefined, { keepPendingActions: true });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
  });

  it('drainsQueuedCredentialResumeWhenReturningAfterStreamAlreadyStopped', async () => {
    vi.useFakeTimers();
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue({
      status: 'completed',
      streamId: 'stream-1',
      content: 'done',
      error: null,
      toolActivities: [],
      pendingServiceApprovals: [],
      pendingToolAuthorizations: [],
    });
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    useMessageQueueStore.getState().enqueue('conversation-1', {
      content: 'Configured credentials for Gmail',
      attachments: [],
      keepPendingActions: true, // a resume message queued earlier
    }, {
      bypassLimit: true,
      position: 'front',
    });
    const onSendMessage = vi.fn();

    render(
      <ChatCore
        conversationId="conversation-1"
        conversation={null}
        messages={[]}
        onSendMessage={onSendMessage}
      />,
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(350);
    });

    // Resume carries keepPendingActions so the backend keeps any sibling cards.
    expect(onSendMessage).toHaveBeenCalledWith('Configured credentials for Gmail', undefined, undefined, { keepPendingActions: true });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
  });

  it('queuesCredentialResumeByDefaultEvenWhenNoStreamIsActive', async () => {
    vi.useFakeTimers();
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    const onSendMessage = vi.fn();

    render(
      <ChatCore
        conversationId="conversation-1"
        conversation={null}
        messages={[]}
        onSendMessage={onSendMessage}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));

    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(1);
    expect(useMessageQueueStore.getState().getQueue('conversation-1')[0]?.content).toBe('Configured credentials for Gmail');
    expect(onSendMessage).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(350);
    });

    expect(onSendMessage).toHaveBeenCalledWith('Configured credentials for Gmail', undefined, undefined, { keepPendingActions: true });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
  });
});
