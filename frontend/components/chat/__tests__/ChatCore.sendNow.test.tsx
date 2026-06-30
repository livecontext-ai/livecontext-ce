/**
 * @vitest-environment jsdom
 *
 * Regression coverage for ChatCore.handleSendNow - the "Send Now" / interrupt-and-relaunch
 * path on a queued message.
 *
 * Bug: while a turn was streaming (notably the long-running bridge path), Send-Now extracted
 * the queued message, fired a fire-and-forget stop + a FIXED 100ms wait, then resent through a
 * handleSendMessage captured when the stream was still active. That captured chain's
 * doSendMessage saw the pre-stop "streaming" state and silently bailed on its "already
 * streaming" guard - the stream stopped but the new message never relaunched, and (having been
 * extracted from the queue) it was lost.
 *
 * Fix: Send-Now stops, then WAITS until the stream is actually no longer active (polling the
 * live ref), and only THEN extracts + resends through the always-fresh ref chain. If the stop
 * never lands within the window the message is left queued (never extracted), so it is never
 * lost - the background auto-drain relaunches it once the stream ends.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { useMessageQueueStore } from '@/lib/stores/message-queue-store';
import type { PendingAttachment } from '@/lib/api/attachmentApi';

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

// MessageComposer is stubbed to render one Send-Now button per queued message, wired to the
// real onSendNow prop with the real queued-message id.
vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({
    queuedMessages,
    onSendNow,
  }: {
    queuedMessages?: Array<{ id: string; content: string }>;
    onSendNow?: (id: string) => void;
  }) => (
    <div data-testid="composer">
      {(queuedMessages ?? []).map((m) => (
        <button key={m.id} type="button" onClick={() => onSendNow?.(m.id)}>
          send-now:{m.content}
        </button>
      ))}
    </div>
  ),
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

describe('ChatCore Send-Now on a queued message', () => {
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

  it('waitsForTheStopToLandBeforeResendingTheQueuedMessage', async () => {
    vi.useFakeTimers();
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    mocks.streaming.getStreamState.mockReturnValue(streamState('streaming'));

    useMessageQueueStore.getState().enqueue('c1', { content: 'follow up', attachments: [] });

    const onSendMessage = vi.fn();
    const onStopStream = vi.fn();
    const props = {
      conversationId: 'c1',
      conversation: null,
      messages: [],
      onSendMessage,
      onStopStream,
    };
    const { rerender } = render(<ChatCore {...props} />);

    // Send-Now while the stream is active → it must request the stop.
    fireEvent.click(screen.getByRole('button', { name: 'send-now:follow up' }));
    expect(onStopStream).toHaveBeenCalledTimes(1);

    // Past the OLD fixed 100ms wait, with the stream STILL active, the message must NOT have
    // been resent yet - and must still be queued (not extracted into a doomed send). Pre-fix
    // this fired the resend here straight into the silent guard and lost the message.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(150);
    });
    expect(onSendMessage).not.toHaveBeenCalled();
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(1);

    // Stop lands: the stream is no longer active.
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue(streamState('stopped'));
    rerender(<ChatCore {...props} />);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60);
    });

    // Now the queued message is relaunched (not dropped).
    expect(onSendMessage).toHaveBeenCalledTimes(1);
    expect(onSendMessage).toHaveBeenCalledWith('follow up', undefined, undefined, undefined);
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(0);

    // And the background auto-drain (300ms timer) must NOT fire a second send - the
    // autoSendingRef interlock + the now-empty queue keep it to exactly one send.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400);
    });
    expect(onSendMessage).toHaveBeenCalledTimes(1);
  });

  it('leavesTheMessageQueuedWhenTheStopNeverLandsWithinTheWindow', async () => {
    vi.useFakeTimers();
    // Stream stays active forever (stop request never takes effect) - models a hung backend
    // stop. onStopStream does NOT flip the streaming flag.
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    mocks.streaming.getStreamState.mockReturnValue(streamState('streaming'));

    useMessageQueueStore.getState().enqueue('c1', { content: 'stuck msg', attachments: [] });

    const onSendMessage = vi.fn();
    const onStopStream = vi.fn();
    render(
      <ChatCore
        conversationId="c1"
        conversation={null}
        messages={[]}
        onSendMessage={onSendMessage}
        onStopStream={onStopStream}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'send-now:stuck msg' }));
    expect(onStopStream).toHaveBeenCalledTimes(1);

    // Advance well past the 2000ms poll deadline with the stream STILL active.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2200);
    });

    // The message must NOT have been sent (it would silently bail) and must NOT have been
    // lost - it stays queued for the background auto-drain to relaunch when the stream ends.
    expect(onSendMessage).not.toHaveBeenCalled();
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(1);
    expect(useMessageQueueStore.getState().getQueue('c1')[0]?.content).toBe('stuck msg');
  });

  it('uploadsAttachmentsAndSendsImmediatelyWhenNoStreamIsActive', async () => {
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamState.mockReturnValue(streamState('completed'));

    const attachment: PendingAttachment = {
      id: 'att-1',
      file: new File(['x'], 'doc.pdf', { type: 'application/pdf' }),
      uploadStatus: 'pending',
      type: 'PDF',
      mimeType: 'application/pdf',
      sizeBytes: 1,
    };
    useMessageQueueStore.getState().enqueue('c1', { content: 'with file', attachments: [attachment] });

    const onSendMessage = vi.fn();
    const onStopStream = vi.fn();
    render(
      <ChatCore
        conversationId="c1"
        conversation={null}
        messages={[]}
        onSendMessage={onSendMessage}
        onStopStream={onStopStream}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'send-now:with file' }));

    // No active stream → no stop; the queued attachment is uploaded then sent as a ref.
    await waitFor(() => expect(onSendMessage).toHaveBeenCalledTimes(1));
    expect(onStopStream).not.toHaveBeenCalled();
    expect(mocks.uploadAttachment).toHaveBeenCalledTimes(1);
    expect(onSendMessage).toHaveBeenCalledWith(
      'with file',
      [{ storageId: 'stored-1', type: 'PDF', fileName: 'doc.pdf', mimeType: 'application/pdf' }],
      undefined,
      undefined,
    );
    expect(useMessageQueueStore.getState().getQueue('c1')).toHaveLength(0);
  });
});
