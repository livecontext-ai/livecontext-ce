/**
 * @vitest-environment jsdom
 *
 * Regression coverage for the message-queue ORDERING bug in ChatCore's background auto-drain.
 *
 * Bug: a queued message was auto-sent as soon as `isStreamingConversation` flipped false (the
 * client 'completed' event) + a 300ms debounce. But 'completed' fires BEFORE onStreamComplete's
 * loadMessages commits the assistant reply into messages[] - so the queued user message (stamped
 * at send time) was appended/persisted AHEAD of the prior turn's reply, rendering out of order:
 *   [queued user message] → [previous answer] → [new answer in progress].
 *
 * Fix: the drain now waits - bounded - until the prior reply has landed in messages[]
 * (priorReplyPendingCommit === false) before dequeuing, so the queued message can never jump
 * ahead of it. A 3s fallback guarantees a reply that never commits can't strand the queue, and a
 * stopped turn with no assistant output (nothing to commit) is never blocked.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { useMessageQueueStore } from '@/lib/stores/message-queue-store';
import type { Message } from '@/lib/api/conversationApi';

const mocks = vi.hoisted(() => ({
  uploadAttachment: vi.fn(),
  revokePreviewUrl: vi.fn(),
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
  serviceApprovalKey: (services: Array<{ serviceType: string }>) =>
    'svc:' + services.map((s) => s.serviceType).sort().join(','),
  toolAuthorizationKey: (rule: string) => 'auth:' + rule,
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: { clearPendingAction: vi.fn() } }));
vi.mock('@/hooks/useChatConfig', () => ({ useChatConfig: () => ({ updateConfig: vi.fn() }) }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: { getPublicationById: vi.fn() } }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({ useAnchorScrollToBottom: vi.fn() }));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div data-testid="message-history" /> }));
vi.mock('@/components/chat/ServiceApprovalCard', () => ({ ServiceApprovalCard: () => null }));
vi.mock('@/components/chat/ToolAuthorizationCard', () => ({ ToolAuthorizationCard: () => null }));
vi.mock('@/lib/api/attachmentApi', () => ({
  attachmentApi: {
    uploadAttachment: mocks.uploadAttachment,
    revokePreviewUrl: mocks.revokePreviewUrl,
  },
}));

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

function msg(id: string, role: Message['role'], content: string, timestamp: string): Message {
  return { id, conversationId: 'c1', role, content, model: 'deepseek-chat', timestamp };
}

describe('ChatCore queue ordering - hold the drain until the prior reply commits', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.uploadAttachment.mockResolvedValue({ storageId: 'stored-1' });
    mocks.streaming.getStreamContent.mockReturnValue('');
    mocks.streaming.getToolActivities.mockReturnValue([]);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('holdsTheQueuedMessageUntilThePriorReplyIsCommittedToMessages', async () => {
    vi.useFakeTimers();
    // Stream just completed client-side (isStreaming false), the reply is shown live via
    // streamContent, but it has NOT yet landed in messages[] - the last message is still the
    // user turn (isStreamContentDuplicate false → priorReplyPendingCommit true).
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue(streamState('completed'));
    mocks.streaming.getStreamContent.mockReturnValue('the answer to one');

    useMessageQueueStore.getState().enqueue('c1', { content: 'second question', attachments: [] });

    const onSendMessage = vi.fn();
    const baseProps = {
      conversationId: 'c1',
      conversation: null,
      onSendMessage,
      onStopStream: vi.fn(),
    };
    const pending = [msg('m1', 'user', 'first question', '2026-01-01T00:00:00.000Z')];
    const { rerender } = render(<ChatCore {...baseProps} messages={pending} />);

    // Past the 300ms drain debounce + several poll cycles: the reply is still uncommitted, so
    // the queued message must NOT have been sent and must remain queued (the ordering gate).
    await act(async () => { await vi.advanceTimersByTimeAsync(800); });
    expect(onSendMessage).not.toHaveBeenCalled();
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(1);

    // The reply commits into messages[] (loadMessages landed it as the last assistant message,
    // its content matching streamContent → isStreamContentDuplicate true → pending false).
    const committed = [
      pending[0],
      msg('m2', 'assistant', 'the answer to one', '2026-01-01T00:00:01.000Z'),
    ];
    rerender(<ChatCore {...baseProps} messages={committed} />);

    // The poll observes the commit and releases exactly one send, in order.
    await act(async () => { await vi.advanceTimersByTimeAsync(200); });
    expect(onSendMessage).toHaveBeenCalledTimes(1);
    expect(onSendMessage).toHaveBeenCalledWith('second question', undefined, undefined, undefined);
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(0);
  });

  it('fallsBackToSendingAfterTheBoundedWaitIfThePriorReplyNeverCommits', async () => {
    vi.useFakeTimers();
    // Reply shown live but messages[] never gains it → priorReplyPendingCommit stays true.
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue(streamState('completed'));
    mocks.streaming.getStreamContent.mockReturnValue('reply that never commits');

    useMessageQueueStore.getState().enqueue('c1', { content: 'queued', attachments: [] });

    const onSendMessage = vi.fn();
    render(
      <ChatCore
        conversationId="c1"
        conversation={null}
        messages={[msg('m1', 'user', 'q', '2026-01-01T00:00:00.000Z')]}
        onSendMessage={onSendMessage}
        onStopStream={vi.fn()}
      />,
    );

    // Before the 3s fallback deadline elapses: still held.
    await act(async () => { await vi.advanceTimersByTimeAsync(1500); });
    expect(onSendMessage).not.toHaveBeenCalled();
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(1);

    // Past the 300ms debounce + 3s fallback: the queue must drain so the message is never
    // stranded by a reply that never commits.
    await act(async () => { await vi.advanceTimersByTimeAsync(2200); });
    expect(onSendMessage).toHaveBeenCalledTimes(1);
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(0);
  });

  it('drainsACredentialResumePromptlyEvenWhileThePriorReplyIsStillUncommitted', async () => {
    vi.useFakeTimers();
    // The ordering gate IS active: a completed reply is shown live (streamContent) and has NOT
    // yet committed to messages[] (last message is the user turn) → priorReplyPendingCommit true.
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue(streamState('completed'));
    mocks.streaming.getStreamContent.mockReturnValue('partial reply not yet committed');

    // ...but the head is a credential-resume (keepPendingActions): it continues the SAME turn and
    // must skip the commit-wait. If the `&& !head.keepPendingActions` skip were removed, the gate
    // would hold it until the 3s fallback and this 450ms assertion would fail.
    useMessageQueueStore.getState().enqueue(
      'c1',
      { content: 'Configured credentials for Gmail', attachments: [], keepPendingActions: true },
      { bypassLimit: true, position: 'front' },
    );

    const onSendMessage = vi.fn();
    render(
      <ChatCore
        conversationId="c1"
        conversation={null}
        messages={[msg('m1', 'user', 'q', '2026-01-01T00:00:00.000Z')]}
        onSendMessage={onSendMessage}
        onStopStream={vi.fn()}
      />,
    );

    // Past the 300ms debounce but far short of the 3s fallback → the resume must already be sent.
    await act(async () => { await vi.advanceTimersByTimeAsync(450); });
    expect(onSendMessage).toHaveBeenCalledTimes(1);
    expect(onSendMessage).toHaveBeenCalledWith(
      'Configured credentials for Gmail', undefined, undefined, { keepPendingActions: true },
    );
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(0);
  });

  it('drainsPromptlyWhenThereIsNoStreamingReplyToCommit', async () => {
    vi.useFakeTimers();
    // Turn ended with no assistant output (e.g. stopped before any content) → hasStreamingData
    // false → priorReplyPendingCommit false → only the 300ms debounce applies, no 3s wait.
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue(streamState('stopped'));
    mocks.streaming.getStreamContent.mockReturnValue('');

    useMessageQueueStore.getState().enqueue('c1', { content: 'after stop', attachments: [] });

    const onSendMessage = vi.fn();
    render(
      <ChatCore
        conversationId="c1"
        conversation={null}
        messages={[msg('m1', 'user', 'q', '2026-01-01T00:00:00.000Z')]}
        onSendMessage={onSendMessage}
        onStopStream={vi.fn()}
      />,
    );

    await act(async () => { await vi.advanceTimersByTimeAsync(450); });
    expect(onSendMessage).toHaveBeenCalledTimes(1);
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(0);
  });
});
