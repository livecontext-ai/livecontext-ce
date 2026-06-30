/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { useMessageQueueStore } from '@/lib/stores/message-queue-store';
import { useAppRunAutoOpenStore } from '@/lib/stores/app-run-autoopen-store';

const mocks = vi.hoisted(() => ({
  clearPendingAction: vi.fn(),
  approveToolAuthorization: vi.fn(),
  denyToolAuthorization: vi.fn(),
  updateConfig: vi.fn(),
  getPublicationById: vi.fn(),
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
  toolAuthorizationKey: (rule: string, toolCallId?: string) =>
    toolCallId ? 'auth:' + rule + '#' + toolCallId : 'auth:' + rule,
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
    approveToolAuthorization: mocks.approveToolAuthorization,
    denyToolAuthorization: mocks.denyToolAuthorization,
  },
}));

vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({
    updateConfig: mocks.updateConfig,
    config: {},
    isLoading: false,
    isSaving: false,
    error: null,
    target: 'conversation',
  }),
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: mocks.getPublicationById },
}));

vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({
  useAnchorScrollToBottom: vi.fn(),
}));

vi.mock('@/components/chat/MessageHistory', () => ({
  MessageHistory: () => <div data-testid="message-history" />,
}));

vi.mock('@/components/chat/ServiceApprovalCard', () => ({
  ServiceApprovalCard: () => <div data-testid="service-approval-card" />,
}));

// Expose the card actions as buttons so the test drives onApproved/onDenied with the
// real pending rule and the blanket flag directly.
vi.mock('@/components/chat/ToolAuthorizationCard', () => ({
  ToolAuthorizationCard: ({
    pendingAuthorization,
    onApproved,
    onDenied,
  }: {
    pendingAuthorization: { rule: string; toolCallId?: string };
    onApproved?: (rule: string, blanket: boolean, toolCallId?: string) => void;
    onDenied?: (rule: string, toolCallId?: string) => void;
  }) => (
    <div>
      <button type="button" onClick={() => onApproved?.(pendingAuthorization.rule, false, pendingAuthorization.toolCallId)}>approve</button>
      <button type="button" onClick={() => onApproved?.(pendingAuthorization.rule, true, pendingAuthorization.toolCallId)}>approve-blanket</button>
      <button type="button" onClick={() => onDenied?.(pendingAuthorization.rule, pendingAuthorization.toolCallId)}>decline-tool</button>
    </div>
  ),
}));

// Stub the marketplace install modal, exposing success / close as buttons.
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({
  default: ({ onSuccess, onClose }: { onSuccess?: (id: string) => void; onClose?: () => void }) => (
    <div data-testid="acquire-modal">
      <button type="button" onClick={() => onSuccess?.('wf-1')}>modal-success</button>
      <button type="button" onClick={() => onClose?.()}>modal-close</button>
    </div>
  ),
}));

vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({ queuedMessages }: { queuedMessages?: Array<{ id: string; content: string }> }) => (
    <div data-testid="composer">
      {(queuedMessages ?? []).map((message) => (
        <div key={message.id} data-testid="queued-message">{message.content}</div>
      ))}
    </div>
  ),
}));

const executeAuthorization = {
  rule: 'application:execute',
  toolName: 'application',
  action: 'execute',
  toolCallId: 'call-1',
  argsSummary: '{"action":"execute"}',
  timestamp: 1,
};

const acquireAuthorization = {
  rule: 'application:acquire',
  toolName: 'application',
  action: 'acquire',
  toolCallId: 'call-2',
  argsSummary: '{"action":"acquire"}',
  applicationId: 'pub-123',
  timestamp: 1,
};

const RESUME_CONTINUE = 'toolAuthorization.resumeContinue';
const RESUME_INSTALLED = 'toolAuthorization.resumeInstalled';

function setPending(auth: typeof executeAuthorization | typeof acquireAuthorization) {
  mocks.streaming.getStreamState.mockReturnValue({
    status: 'streaming', streamId: 'stream-1', content: 'working',
    error: null, toolActivities: [], pendingServiceApprovals: [], pendingToolAuthorizations: [auth],
  });
  mocks.streaming.getPendingToolAuthorizations.mockReturnValue([auth]);
}

describe('ChatCore tool authorization', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    useAppRunAutoOpenStore.setState({ armedAt: null });
    mocks.clearPendingAction.mockResolvedValue(undefined);
    mocks.approveToolAuthorization.mockResolvedValue(undefined);
    mocks.denyToolAuthorization.mockResolvedValue(undefined);
    mocks.getPublicationById.mockResolvedValue({ id: 'pub-123', title: 'Invoice Parser' });
    mocks.streaming.getStreamContent.mockReturnValue('working');
    mocks.streaming.getToolActivities.mockReturnValue([]);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    setPending(executeAuthorization);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('executeApproveGrantsOnceArmsAutoOpenAndQueuesResume', async () => {
    setPending(executeAuthorization);
    mocks.streaming.isStreamingConversation.mockReturnValue(true);

    render(<ChatCore conversationId="conversation-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'approve' }));

    await waitFor(() => {
      expect(mocks.approveToolAuthorization).toHaveBeenCalledWith('conversation-1', 'application:execute', false);
    });
    await waitFor(() => {
      expect(screen.getByTestId('queued-message')).toHaveTextContent(RESUME_CONTINUE);
    });
    // The user asked that approving an execute opens the right side panel - the one-shot is armed.
    expect(useAppRunAutoOpenStore.getState().armedAt).not.toBeNull();
    // Only THIS card is cleared (by its (rule, toolCallId) key), leaving any siblings pending.
    expect(mocks.streaming.clearToolAuthorization).toHaveBeenCalledWith('conversation-1', 'auth:application:execute#call-1');
  });

  it('acquireApproveOpensInstallModalAndResumesOnlyOnSuccess', async () => {
    setPending(acquireAuthorization);
    mocks.streaming.isStreamingConversation.mockReturnValue(true);

    render(<ChatCore conversationId="conversation-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'approve' }));

    // Install modal opens; nothing is granted/resumed yet (the USER installs).
    await waitFor(() => expect(screen.getByTestId('acquire-modal')).toBeInTheDocument());
    expect(mocks.approveToolAuthorization).not.toHaveBeenCalled();
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
    expect(useAppRunAutoOpenStore.getState().armedAt).toBeNull(); // acquire never auto-opens a run

    // Install completes → grant once + contextual "installed" resume.
    fireEvent.click(screen.getByRole('button', { name: 'modal-success' }));
    await waitFor(() => {
      expect(mocks.approveToolAuthorization).toHaveBeenCalledWith('conversation-1', 'application:acquire', false);
    });
    await waitFor(() => {
      expect(screen.getByTestId('queued-message')).toHaveTextContent(RESUME_INSTALLED);
    });
  });

  it('acquireInstallModalCancelDeclinesWithoutGranting', async () => {
    setPending(acquireAuthorization);
    mocks.streaming.isStreamingConversation.mockReturnValue(false);

    render(<ChatCore conversationId="conversation-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'approve' }));
    await waitFor(() => expect(screen.getByTestId('acquire-modal')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'modal-close' }));
    await waitFor(() => {
      expect(mocks.denyToolAuthorization).toHaveBeenCalledWith('conversation-1', 'application:acquire');
    });
    expect(mocks.approveToolAuthorization).not.toHaveBeenCalled();
  });

  it('blanketCheckboxPersistsAutoAuthorizeToolsToggle', async () => {
    setPending(executeAuthorization);
    mocks.streaming.isStreamingConversation.mockReturnValue(true);

    render(<ChatCore conversationId="conversation-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'approve-blanket' }));

    await waitFor(() => {
      expect(mocks.updateConfig).toHaveBeenCalledWith({ autoAuthorizeTools: true });
    });
  });

  it('executeApproveWithNoActiveStreamQueuesResumeByDefaultThenAutoDrains', async () => {
    vi.useFakeTimers();
    setPending(executeAuthorization);
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    const onSendMessage = vi.fn();

    render(<ChatCore conversationId="conversation-1" conversation={null} messages={[]} onSendMessage={onSendMessage} />);
    fireEvent.click(screen.getByRole('button', { name: 'approve' }));

    await act(async () => {
      await Promise.resolve();
    });

    expect(mocks.approveToolAuthorization).toHaveBeenCalledWith('conversation-1', 'application:execute', false);
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(1);
    expect(useMessageQueueStore.getState().getQueue('conversation-1')[0]?.content).toBe(RESUME_CONTINUE);
    expect(onSendMessage).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(350);
    });

    expect(onSendMessage).toHaveBeenCalledWith(RESUME_CONTINUE, undefined, undefined, { keepPendingActions: true });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
  });

  it('F16: two cards of the SAME rule (different toolCallId) both render and clear independently', () => {
    // Pre-fix the identity was the rule alone, so a second workflow:execute in the
    // same conversation was deduped away (and an earlier dismissal of that rule
    // suppressed it) - the 2nd card never rendered. Now identity is (rule, toolCallId).
    const first = { rule: 'workflow:execute', toolName: 'workflow', action: 'execute', toolCallId: 'call-A', argsSummary: '{}', timestamp: 1 };
    const second = { rule: 'workflow:execute', toolName: 'workflow', action: 'execute', toolCallId: 'call-B', argsSummary: '{}', timestamp: 2 };
    mocks.streaming.getStreamState.mockReturnValue({
      status: 'streaming', streamId: 'stream-1', content: 'working',
      error: null, toolActivities: [], pendingServiceApprovals: [], pendingToolAuthorizations: [first, second],
    });
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([first, second]);
    mocks.streaming.isStreamingConversation.mockReturnValue(true);

    render(<ChatCore conversationId="conversation-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);

    // BOTH same-rule cards render (pre-fix: only one, deduped by rule).
    const approveButtons = screen.getAllByRole('button', { name: 'approve' });
    expect(approveButtons).toHaveLength(2);

    // Approving the FIRST clears only its (rule, toolCallId) key - the second card survives.
    fireEvent.click(approveButtons[0]!);
    expect(mocks.streaming.clearToolAuthorization).toHaveBeenCalledWith('conversation-1', 'auth:workflow:execute#call-A');
    expect(mocks.streaming.clearToolAuthorization).not.toHaveBeenCalledWith('conversation-1', 'auth:workflow:execute#call-B');
  });

  it('declineStopsTheAgentWithoutResumingAndDisarmsAutoOpen', async () => {
    setPending(executeAuthorization);
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    // A stale arm from an earlier turn must not survive a decline.
    useAppRunAutoOpenStore.setState({ armedAt: Date.now() });
    const onSendMessage = vi.fn();

    render(<ChatCore conversationId="conversation-1" conversation={null} messages={[]} onSendMessage={onSendMessage} />);
    fireEvent.click(screen.getByRole('button', { name: 'decline-tool' }));

    await waitFor(() => {
      expect(mocks.denyToolAuthorization).toHaveBeenCalledWith('conversation-1', 'application:execute');
    });
    expect(mocks.streaming.clearToolAuthorization).toHaveBeenCalledWith('conversation-1', 'auth:application:execute#call-1');
    expect(useAppRunAutoOpenStore.getState().armedAt).toBeNull();
    expect(onSendMessage).not.toHaveBeenCalled();
    expect(mocks.approveToolAuthorization).not.toHaveBeenCalled();
  });
});
