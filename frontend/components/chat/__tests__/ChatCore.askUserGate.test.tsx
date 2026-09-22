/**
 * @vitest-environment jsdom
 *
 * Answering a question card has two outcomes, decided by the backend: the agent was still
 * HOLDING the ask_user call (the answers are its tool result, nothing else to do), or the
 * hold was gone (the park ran out: 25 s on a bridge session that declared no CLI wait, 150 s
 * on one that did, the gate's 240 s budget on the direct route) and the answers must travel
 * as the user's next
 * message so the agent still gets them. Both halves of `gateKey && released` are required,
 * exactly as for the authorization card.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { useMessageQueueStore } from '@/lib/stores/message-queue-store';

const mocks = vi.hoisted(() => ({
  clearPendingAction: vi.fn(),
  answerAskUser: vi.fn(),
  dismissAskUser: vi.fn(),
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
  serviceApprovalKey: (_services: unknown[], needsAttention = false) => (needsAttention ? 'svc:attention' : 'svc:connect'),
  toolAuthorizationKey: (rule: string, toolCallId?: string) => (toolCallId ? 'auth:' + rule + '#' + toolCallId : 'auth:' + rule),
  askUserKey: (toolCallId: string) => 'ask:' + toolCallId,
  mergePendingServiceApprovals: (existing: any, incoming: any) => ({ ...existing, services: [...existing.services, ...incoming.services] }),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: { deleteWorkflow: vi.fn(), deleteDataSource: vi.fn(), deleteInterface: vi.fn(), deleteAgent: vi.fn() },
}));

vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    clearPendingAction: mocks.clearPendingAction,
    answerAskUser: mocks.answerAskUser,
    dismissAskUser: mocks.dismissAskUser,
  },
}));

vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({ updateConfig: vi.fn(), config: {}, isLoading: false, isSaving: false, error: null, target: 'conversation' }),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: { getPublicationById: vi.fn() } }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({ useAnchorScrollToBottom: vi.fn() }));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div /> }));
vi.mock('@/components/chat/ServiceApprovalCard', () => ({ ServiceApprovalCard: () => null }));
vi.mock('@/components/chat/ToolAuthorizationCard', () => ({ ToolAuthorizationCard: () => null }));

vi.mock('@/components/chat/AskUserQuestionCard', () => ({
  AskUserQuestionCard: ({
    pendingQuestion, onSubmit, onDismiss,
  }: {
    pendingQuestion: { toolCallId: string; questions: Array<{ header: string }> };
    onSubmit?: (answers: Array<{ header: string; selected: string[]; freeText?: string }>, toolCallId: string) => void;
    onDismiss?: (toolCallId: string) => void;
  }) => (
    <div data-testid={`ask-card-${pendingQuestion.toolCallId}`}>
      <button
        type="button"
        onClick={() => onSubmit?.(
          [{ header: 'Tone', selected: ['Friendly'] }, { header: 'Channels', selected: ['X'], freeText: 'Newsletter' }],
          pendingQuestion.toolCallId)}
      >
        submit-{pendingQuestion.toolCallId}
      </button>
      <button type="button" onClick={() => onDismiss?.(pendingQuestion.toolCallId)}>skip-{pendingQuestion.toolCallId}</button>
    </div>
  ),
}));

vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({
    queuedMessages, onSendMessage,
  }: { queuedMessages?: Array<{ id: string; content: string }>; onSendMessage?: (content?: string) => void }) => (
    <div data-testid="composer">
      <button type="button" onClick={() => onSendMessage?.('something else entirely')}>type-fresh-message</button>
      {(queuedMessages ?? []).map((m) => (
        <div key={m.id} data-testid="queued-message">{m.content}</div>
      ))}
    </div>
  ),
}));

const questions = [
  { header: 'Tone', question: 'Which tone?', options: [{ label: 'Friendly' }, { label: 'Formal' }] },
  { header: 'Channels', question: 'Where?', options: [{ label: 'X' }, { label: 'Mail' }], multiSelect: true },
];

const heldQuestion = { toolCallId: 'call-7', questions, blocking: true, gateKey: 'call-7:ask', timestamp: 1 };
const unheldQuestion = { toolCallId: 'call-8', questions, timestamp: 1 };

function mockStream(asks: unknown[]) {
  mocks.streaming.isStreamingConversation.mockReturnValue(true);
  mocks.streaming.getStreamState.mockReturnValue({
    status: 'streaming', streamId: 'stream-1', content: 'working', error: null, toolActivities: [],
    pendingServiceApprovals: [], pendingToolAuthorizations: [], pendingAskUserQuestions: asks,
  });
  mocks.streaming.getStreamContent.mockReturnValue('working');
  mocks.streaming.getToolActivities.mockReturnValue([]);
  mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
  mocks.streaming.getPendingToolAuthorizations.mockReturnValue([]);
  mocks.streaming.getPendingAskUserQuestions.mockReturnValue(asks);
}

function renderChat(conversation: any = null) {
  return render(
    <ChatCore conversationId="conversation-1" conversation={conversation} messages={[]} onSendMessage={vi.fn()} />,
  );
}

const queued = () => useMessageQueueStore.getState().queues['conversation-1']?.items ?? [];

describe('ChatCore ask_user question cards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.clearPendingAction.mockResolvedValue(undefined);
    mocks.dismissAskUser.mockResolvedValue(true);
  });

  it('a HELD question that is released in time queues NO resume message', async () => {
    mocks.answerAskUser.mockResolvedValue(true);
    mockStream([heldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText('submit-call-7'));

    await waitFor(() => expect(mocks.answerAskUser).toHaveBeenCalledWith(
      'conversation-1', 'call-7', 'call-7:ask',
      [{ header: 'Tone', selected: ['Friendly'] }, { header: 'Channels', selected: ['X'], freeText: 'Newsletter' }],
    ));
    expect(mocks.streaming.clearAskUserQuestion).toHaveBeenCalledWith('conversation-1', 'ask:call-7');
    expect(queued()).toHaveLength(0);
  });

  it('a HELD question whose hold already timed out (not released) sends the answers as the next message', async () => {
    mocks.answerAskUser.mockResolvedValue(false);
    mockStream([heldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText('submit-call-7'));

    await waitFor(() => expect(queued()).toHaveLength(1));
    expect(queued()[0].content).toBe('askUser.resumeIntro\n- Tone: Friendly\n- Channels: X, Newsletter');
    expect(queued()[0].keepPendingActions).toBe(true);
  });

  it('a card that never claimed a hold always resumes by message and sends no gate key', async () => {
    mocks.answerAskUser.mockResolvedValue(false);
    mockStream([unheldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText('submit-call-8'));

    await waitFor(() => expect(mocks.answerAskUser).toHaveBeenCalledWith(
      'conversation-1', 'call-8', undefined, expect.any(Array)));
    await waitFor(() => expect(queued()).toHaveLength(1));
  });

  it('Skip dismisses with the gate key and never resumes', async () => {
    mockStream([heldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText('skip-call-7'));

    await waitFor(() => expect(mocks.dismissAskUser).toHaveBeenCalledWith('conversation-1', 'call-7', 'call-7:ask'));
    expect(mocks.streaming.clearAskUserQuestion).toHaveBeenCalledWith('conversation-1', 'ask:call-7');
    expect(queued()).toHaveLength(0);
  });

  it.each([heldQuestion, unheldQuestion])('Skip resumes an expired or unheld question ($toolCallId)', async (question) => {
    mocks.dismissAskUser.mockResolvedValue(false);
    mockStream([question]);
    renderChat();

    fireEvent.click(screen.getByText(`skip-${question.toolCallId}`));

    await waitFor(() => expect(queued()).toHaveLength(1));
    expect(queued()[0].content).toBe('askUser.resumeSkipped');
    expect(queued()[0].keepPendingActions).toBe(true);
    expect(mocks.streaming.clearAskUserQuestion).toHaveBeenCalledWith('conversation-1', `ask:${question.toolCallId}`);
  });

  it('a failed Skip preserves the question and permits retry instead of silently losing it', async () => {
    mocks.dismissAskUser.mockRejectedValueOnce(new Error('network'));
    mockStream([heldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText('skip-call-7'));

    await waitFor(() => expect(mocks.dismissAskUser).toHaveBeenCalledTimes(1));
    expect(mocks.streaming.clearAskUserQuestion).not.toHaveBeenCalled();
    expect(screen.getByTestId('ask-card-call-7')).toBeInTheDocument();
    expect(queued()).toHaveLength(0);

    mocks.dismissAskUser.mockResolvedValueOnce(false);
    fireEvent.click(screen.getByText('skip-call-7'));
    await waitFor(() => expect(queued()).toHaveLength(1));
  });

  it.each(['skip', 'submit'])('Stop prevents a late %s response from restarting the task', async (action) => {
    let finish!: (released: boolean) => void;
    const response = new Promise<boolean>(resolve => { finish = resolve; });
    (action === 'skip' ? mocks.dismissAskUser : mocks.answerAskUser).mockReturnValueOnce(response);
    mockStream([heldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText(`${action}-call-7`));
    fireEvent.keyDown(document, { key: 'Escape' });
    await act(async () => { finish(false); await response; });

    expect(mocks.streaming.stopStream).toHaveBeenCalledWith('conversation-1');
    expect(queued()).toHaveLength(0);
  });

  it('a fresh user message supersedes an in-flight Skip continuation', async () => {
    let finish!: (released: boolean) => void;
    const response = new Promise<boolean>(resolve => { finish = resolve; });
    mocks.dismissAskUser.mockReturnValueOnce(response).mockResolvedValue(false);
    mockStream([heldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText('skip-call-7'));
    fireEvent.click(screen.getByText('type-fresh-message'));
    await act(async () => { finish(false); await response; });

    expect(queued()).toHaveLength(0);
  });

  it('Stop removes queued question resumes but preserves the user message queue', async () => {
    mocks.dismissAskUser.mockResolvedValue(false);
    mockStream([heldQuestion]);
    renderChat();
    useMessageQueueStore.getState().enqueue('conversation-1', { content: 'User queued work', attachments: [] });

    fireEvent.click(screen.getByText('skip-call-7'));
    await waitFor(() => expect(queued()).toHaveLength(2));
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(queued().map(q => q.content)).toEqual(['User queued work']);
  });

  it('a failed submit keeps the card on screen (no clear, no resume) so the person can retry', async () => {
    mocks.answerAskUser.mockRejectedValueOnce(new Error('network'));
    mockStream([heldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText('submit-call-7'));

    await waitFor(() => expect(mocks.answerAskUser).toHaveBeenCalledTimes(1));
    expect(mocks.streaming.clearAskUserQuestion).not.toHaveBeenCalled();
    expect(screen.getByTestId('ask-card-call-7')).toBeInTheDocument();
    expect(queued()).toHaveLength(0);

    // The in-flight guard is released too: a second attempt reaches the backend.
    mocks.answerAskUser.mockResolvedValueOnce(true);
    fireEvent.click(screen.getByText('submit-call-7'));
    await waitFor(() => expect(mocks.answerAskUser).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(mocks.streaming.clearAskUserQuestion).toHaveBeenCalledWith('conversation-1', 'ask:call-7'));
  });

  it('typing a fresh message while a HELD question is open releases its park as dismissed, not just hides it', async () => {
    mockStream([heldQuestion, unheldQuestion]);
    renderChat();

    fireEvent.click(screen.getByText('type-fresh-message'));

    // The held one is released through the gate; the unheld one has nothing to release.
    await waitFor(() => expect(mocks.dismissAskUser).toHaveBeenCalledWith('conversation-1', 'call-7', 'call-7:ask'));
    expect(mocks.dismissAskUser).toHaveBeenCalledTimes(1);
    expect(mocks.streaming.clearAskUserQuestion).toHaveBeenCalledWith('conversation-1');
    await waitFor(() => expect(mocks.clearPendingAction).toHaveBeenCalledWith('conversation-1'));
  });

  it('a card persisted on the conversation row (after a reload) is rendered without a hold', () => {
    mockStream([]);
    renderChat({
      id: 'conversation-1',
      pendingActions: [{ waiting_for: 'user_question', tool_call_id: 'call-9', questions, created_at: '2026-09-05T10:00:00Z' }],
    });

    expect(screen.getByTestId('ask-card-call-9')).toBeInTheDocument();
  });

  it('two open questions render two cards, and answering one leaves the other', async () => {
    mocks.answerAskUser.mockResolvedValue(true);
    mockStream([heldQuestion, { ...heldQuestion, toolCallId: 'call-10', gateKey: 'call-10:ask' }]);
    renderChat();

    expect(screen.getByTestId('ask-card-call-7')).toBeInTheDocument();
    expect(screen.getByTestId('ask-card-call-10')).toBeInTheDocument();

    fireEvent.click(screen.getByText('submit-call-7'));

    await waitFor(() => expect(mocks.answerAskUser).toHaveBeenCalled());
    expect(screen.queryByTestId('ask-card-call-7')).not.toBeInTheDocument();
    expect(screen.getByTestId('ask-card-call-10')).toBeInTheDocument();
  });
});
