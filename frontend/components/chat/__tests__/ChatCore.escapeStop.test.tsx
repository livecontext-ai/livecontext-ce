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

  // Escape anywhere on the page stopped the stream. Radix layers mark the Escape they use (see the
  // "already handled" case below); a hand-rolled overlay, stood in for here by a bare role, does not.
  it.each(['menu', 'listbox', 'dialog', 'alertdialog'])(
    'does not stop when the Escape closes an open %s overlay',
    (role) => {
      mocks.streaming.isStreamingConversation.mockReturnValue(true);
      const onStopStream = vi.fn();
      render(
        <ChatCore conversationId="conv-1" conversation={null} messages={[]}
          onSendMessage={vi.fn()} onStopStream={onStopStream} />,
      );
      // Radix portals its layers to <body>, outside the app tree.
      const overlay = document.createElement('div');
      overlay.setAttribute('role', role);
      document.body.appendChild(overlay);
      try {
        fireEvent.keyDown(document, { key: 'Escape' });
      } finally {
        overlay.remove();
      }

      expect(onStopStream).not.toHaveBeenCalled();
    },
  );

  it('still stops once the overlay is gone', () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    const onStopStream = vi.fn();
    render(
      <ChatCore conversationId="conv-1" conversation={null} messages={[]}
        onSendMessage={vi.fn()} onStopStream={onStopStream} />,
    );

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onStopStream).toHaveBeenCalledTimes(1);
  });

  it('does not stop when the chat sits in a full-screen side panel: that Escape leaves full screen', () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    const onStopStream = vi.fn();
    const panel = document.createElement('div');
    panel.setAttribute('data-side-panel-maximized', 'true');
    document.body.appendChild(panel);
    try {
      render(
        <ChatCore conversationId="conv-1" conversation={null} messages={[]}
          onSendMessage={vi.fn()} onStopStream={onStopStream} />,
        { container: panel },
      );

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(onStopStream).not.toHaveBeenCalled();
    } finally {
      panel.remove();
    }
  });

  it('does not stop a chat BEHIND a full-screen side panel either', () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    const onStopStream = vi.fn();
    const panel = document.createElement('div');
    panel.setAttribute('data-side-panel-maximized', 'true');
    document.body.appendChild(panel);
    try {
      // The chat is rendered in the page, not inside the panel.
      render(
        <ChatCore conversationId="conv-1" conversation={null} messages={[]}
          onSendMessage={vi.fn()} onStopStream={onStopStream} />,
      );

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(onStopStream).not.toHaveBeenCalled();
    } finally {
      panel.remove();
    }
  });

  it('does not stop on an Escape another surface already handled', () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    const onStopStream = vi.fn();
    render(
      <ChatCore conversationId="conv-1" conversation={null} messages={[]}
        onSendMessage={vi.fn()} onStopStream={onStopStream} />,
    );
    const handled = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    handled.preventDefault();

    document.dispatchEvent(handled);

    expect(onStopStream).not.toHaveBeenCalled();
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
