/**
 * @vitest-environment jsdom
 *
 * What ChatCore draws for a turn that reported an `error`.
 *
 * An `error` is not always the end of a turn (an execution-link fallback publishes `error`, then
 * retries under the same stream), yet the errored status used to HIDE the live bubble: whatever
 * had streamed vanished, and the reader was left with an empty turn. The bubble now stays until
 * the saved history holds a reply for the turn; from then on the saved row is the answer, and
 * the partial must not sit beside it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
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

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => mocks.streaming,
  askUserKey: (toolCallId: string) => 'ask:' + toolCallId,
  serviceApprovalKey: () => 'svc:connect',
  toolAuthorizationKey: (rule: string) => 'auth:' + rule,
  mergePendingServiceApprovals: (existing: unknown) => existing,
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { deleteWorkflow: vi.fn(), deleteDataSource: vi.fn(), deleteInterface: vi.fn(), deleteAgent: vi.fn() },
}));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: { clearPendingAction: vi.fn() } }));
vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({ updateConfig: vi.fn(), config: {}, isLoading: false, isSaving: false, error: null }),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: { getPublicationById: vi.fn() } }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({ useAnchorScrollToBottom: vi.fn() }));
// The history reports what ChatCore hands it: the live bubble and the rows it keeps.
vi.mock('@/components/chat/MessageHistory', () => ({
  MessageHistory: ({ streamingMessage, messages, toolActivities }: { streamingMessage?: string; messages: unknown[]; toolActivities?: unknown[] }) => (
    <div data-testid="history" data-rows={messages.length} data-tools={toolActivities?.length ?? 0}>
      {streamingMessage !== undefined && <div data-testid="live-bubble">{streamingMessage}</div>}
    </div>
  ),
}));
vi.mock('@/components/chat/ServiceApprovalCard', () => ({ ServiceApprovalCard: () => null }));
vi.mock('@/components/chat/ToolAuthorizationCard', () => ({ ToolAuthorizationCard: () => null }));
vi.mock('@/components/chat/MessageComposer', () => ({ MessageComposer: () => <div data-testid="composer" /> }));

const STREAMED = 'Looking up your order';

const errorMarker = { id: 'system-error-1', toolName: '_system_error', toolId: 'system-error-1', status: 'error', error: 'bridge link failed', timestamp: 1 };
const catalogCall = { id: 't1', toolName: 'catalog', toolId: 't1', status: 'success', timestamp: 0 };

function errored(content = STREAMED, activities: unknown[] = [errorMarker]) {
  mocks.streaming.getStreamState.mockReturnValue({
    status: 'error', streamId: 's1', content,
    error: { message: 'bridge link failed', retryable: true },
    toolActivities: activities, pendingServiceApprovals: [], pendingToolAuthorizations: [],
  });
  mocks.streaming.getStreamContent.mockReturnValue(content);
  mocks.streaming.getToolActivities.mockReturnValue(activities);
}

const userTurn = { role: 'user' as const, content: 'Where is my order?', timestamp: '2026-09-30T10:00:00Z' };

describe('ChatCore - the live bubble of a turn that reported an error', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([]);
    mocks.streaming.getPendingAskUserQuestions.mockReturnValue([]);
    errored();
  });

  it('keeps what was streamed on screen while no saved reply exists for the turn', () => {
    render(<ChatCore conversationId="conv-a" conversation={null} messages={[userTurn]} onSendMessage={vi.fn()} />);

    expect(screen.getByTestId('live-bubble')).toHaveTextContent(STREAMED);
    expect(screen.getByTestId('history')).toHaveAttribute('data-rows', '1');
  });

  it('gives way to the saved reply once the re-read history holds it', () => {
    const savedReply = { role: 'assistant' as const, content: 'Your order shipped on Monday.', timestamp: '2026-09-30T10:00:05Z' };

    render(<ChatCore conversationId="conv-a" conversation={null} messages={[userTurn, savedReply]} onSendMessage={vi.fn()} />);

    expect(screen.queryByTestId('live-bubble')).not.toBeInTheDocument();
    // The saved reply is kept in the history, not deduplicated away against the partial.
    expect(screen.getByTestId('history')).toHaveAttribute('data-rows', '2');
  });

  it('leaves no Error line for a turn that failed before streaming anything (the modal explains it)', () => {
    // Only the error marker: showing it would put a raw "Error: ..." line in the thread on top
    // of the modal, including when a conversation whose last turn just failed is reopened.
    errored('', [errorMarker]);

    render(<ChatCore conversationId="conv-a" conversation={null} messages={[userTurn]} onSendMessage={vi.fn()} />);

    expect(screen.queryByTestId('live-bubble')).not.toBeInTheDocument();
    expect(screen.getByTestId('history')).toHaveAttribute('data-tools', '0');
  });

  it('keeps the tools an errored turn really ran, even without text', () => {
    errored('', [catalogCall, errorMarker]);

    render(<ChatCore conversationId="conv-a" conversation={null} messages={[userTurn]} onSendMessage={vi.fn()} />);

    expect(screen.getByTestId('live-bubble')).toBeInTheDocument();
    expect(screen.getByTestId('history')).toHaveAttribute('data-tools', '2');
  });
});
