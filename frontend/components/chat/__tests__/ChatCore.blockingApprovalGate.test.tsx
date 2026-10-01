/**
 * @vitest-environment jsdom
 *
 * When the agent HOLDS the call that raised a card, answering must release that call and
 * nothing else. The old flow queued a synthetic user message to restart the turn; doing
 * that on top of a still-running turn would queue a redundant second one, which is exactly
 * the machinery this change removes.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { useMessageQueueStore } from '@/lib/stores/message-queue-store';

const mocks = vi.hoisted(() => ({
  clearPendingAction: vi.fn(),
  resolveApprovalGate: vi.fn(),
  approveToolAuthorization: vi.fn(),
  denyToolAuthorization: vi.fn(),
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
  toolAuthorizationKey: (rule: string, toolCallId?: string) =>
    toolCallId ? 'auth:' + rule + '#' + toolCallId : 'auth:' + rule,
  askUserKey: (toolCallId: string) => 'ask:' + toolCallId,
  mergePendingServiceApprovals: (existing: any, incoming: any) => ({
    ...existing,
    services: [...existing.services, ...incoming.services],
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
    resolveApprovalGate: mocks.resolveApprovalGate,
    approveToolAuthorization: mocks.approveToolAuthorization,
    denyToolAuthorization: mocks.denyToolAuthorization,
  },
}));

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

vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({ useAnchorScrollToBottom: vi.fn() }));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div /> }));

vi.mock('@/components/chat/ServiceApprovalCard', () => ({
  ServiceApprovalCard: ({
    onApproved, onDenied,
  }: { onApproved?: (n: string[]) => void; onDenied?: (n: string[]) => void }) => (
    <>
      <button type="button" onClick={() => onApproved?.(['Gmail'])}>approve-service</button>
      <button type="button" onClick={() => onDenied?.(['Gmail'])}>deny-service</button>
    </>
  ),
}));

vi.mock('@/components/chat/ToolAuthorizationCard', () => ({
  ToolAuthorizationCard: ({
    pendingAuthorization, onApproved, onDenied,
  }: {
    pendingAuthorization: { rule: string; toolCallId?: string };
    onApproved?: (rule: string, blanket: boolean, toolCallId?: string) => void;
    onDenied?: (rule: string, toolCallId?: string) => void;
  }) => (
    <>
      <button
        type="button"
        onClick={() => onApproved?.(pendingAuthorization.rule, false, pendingAuthorization.toolCallId)}
      >
        authorize-tool
      </button>
      <button
        type="button"
        onClick={() => onDenied?.(pendingAuthorization.rule, pendingAuthorization.toolCallId)}
      >
        decline-tool
      </button>
    </>
  ),
}));

vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({ queuedMessages }: { queuedMessages?: Array<{ id: string; content: string }> }) => (
    <div data-testid="composer">
      {(queuedMessages ?? []).map((m) => (
        <div key={m.id} data-testid="queued-message">{m.content}</div>
      ))}
    </div>
  ),
}));

const heldApproval = {
  services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }],
  blocking: true,
  gateKeys: ['call-1'],
  timestamp: 1,
};

/** One card, two calls: what a merge of two parallel parks on the same service produces. */
const twiceHeldApproval = {
  services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }],
  blocking: true,
  gateKeys: ['call-1', 'call-2'],
  timestamp: 1,
};

const unheldApproval = {
  services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }],
  timestamp: 1,
};

const heldAuthorization = {
  rule: 'workflow:execute',
  toolName: 'workflow',
  action: 'execute',
  toolCallId: 'call-9',
  blocking: true,
  gateKey: 'call-9',
  timestamp: 1,
};

function mockStream(approvals: unknown[], authorizations: unknown[]) {
  mocks.streaming.isStreamingConversation.mockReturnValue(true);
  mocks.streaming.getStreamState.mockReturnValue({
    status: 'streaming',
    streamId: 'stream-1',
    content: 'working',
    error: null,
    toolActivities: [],
    pendingServiceApprovals: approvals,
    pendingToolAuthorizations: authorizations,
  });
  mocks.streaming.getStreamContent.mockReturnValue('working');
  mocks.streaming.getToolActivities.mockReturnValue([]);
  mocks.streaming.getPendingServiceApprovals.mockReturnValue(approvals);
  mocks.streaming.getPendingToolAuthorizations.mockReturnValue(authorizations);
}

function renderChat() {
  return render(
    <ChatCore conversationId="conversation-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />,
  );
}

describe('ChatCore blocking approval gate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.clearPendingAction.mockResolvedValue(undefined);
    // true = "the server released a call that was really holding". The frontend skips its
    // resume message on that answer alone, never on what the card claims.
    mocks.resolveApprovalGate.mockResolvedValue(true);
    mocks.approveToolAuthorization.mockResolvedValue(true);
    mocks.denyToolAuthorization.mockResolvedValue(true);
  });

  it('releasesTheHeldCallAndQueuesNoResumeMessageWhenConnectingAService', async () => {
    mockStream([heldApproval], []);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));

    await waitFor(() => {
      expect(mocks.resolveApprovalGate).toHaveBeenCalledWith('conversation-1', 'call-1', true);
    });
    // The turn is still running - a resume message would queue a redundant second turn.
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
  });

  it('stillQueuesAResumeMessageWhenNoCallIsHeld', async () => {
    mockStream([unheldApproval], []);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));

    // Pre-gate behaviour is preserved wherever nothing is parked.
    await waitFor(() => {
      expect(useMessageQueueStore.getState().getQueue('conversation-1')[0]?.content)
        .toBe('Configured credentials for Gmail');
    });
    expect(mocks.resolveApprovalGate).not.toHaveBeenCalled();
  });

  it('releasesTheHeldCallAsRefusedWhenDecliningAService', async () => {
    mockStream([heldApproval], []);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'deny-service' }));

    await waitFor(() => {
      expect(mocks.resolveApprovalGate).toHaveBeenCalledWith('conversation-1', 'call-1', false);
    });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
  });

  it('sendsTheGateKeyOnApproveAndQueuesNoResumeMessageForAHeldAction', async () => {
    mockStream([], [heldAuthorization]);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'authorize-tool' }));

    await waitFor(() => {
      expect(mocks.approveToolAuthorization)
        .toHaveBeenCalledWith('conversation-1', 'workflow:execute', false, 'call-9', false);
    });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
  });

  it('stillQueuesAResumeMessageForAnActionThatIsNotHeld', async () => {
    // The endpoint answers "released" for everyone here, which is exactly the trap: a card
    // that never claimed a hold must resume regardless of what the answer says, or every
    // ordinary authorization would silently lose its resume.
    mocks.approveToolAuthorization.mockResolvedValue(true);
    mockStream([], [{ ...heldAuthorization, blocking: false, gateKey: undefined }]);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'authorize-tool' }));

    await waitFor(() => {
      expect(mocks.approveToolAuthorization)
        .toHaveBeenCalledWith('conversation-1', 'workflow:execute', false, undefined, false);
    });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(1);
  });

  it('releasesEveryCallMergedIntoOneServiceCard', async () => {
    // Two parallel calls blocked on the same unconnected service share a single card.
    // Releasing only one leaves the other holding to its deadline, and half the work fails
    // several minutes later with nothing on screen to explain it.
    mockStream([twiceHeldApproval], []);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));

    await waitFor(() => {
      expect(mocks.resolveApprovalGate).toHaveBeenCalledWith('conversation-1', 'call-1', true);
      expect(mocks.resolveApprovalGate).toHaveBeenCalledWith('conversation-1', 'call-2', true);
    });
    expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(0);
  });

  it('refusesEveryCallMergedIntoOneServiceCard', async () => {
    mockStream([twiceHeldApproval], []);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'deny-service' }));

    await waitFor(() => {
      expect(mocks.resolveApprovalGate).toHaveBeenCalledWith('conversation-1', 'call-1', false);
      expect(mocks.resolveApprovalGate).toHaveBeenCalledWith('conversation-1', 'call-2', false);
    });
  });

  it('stillQueuesAResumeMessageWhenTheHoldHasAlreadyEndedOnAServiceCard', async () => {
    // The card still says "the agent is waiting for you", but the hold timed out while the
    // user was away. Without the fallback the click releases nothing, queues nothing, and
    // the conversation sits there forever - strictly worse than before the gate existed.
    mocks.resolveApprovalGate.mockResolvedValue(false);
    mockStream([heldApproval], []);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));

    await waitFor(() => {
      expect(useMessageQueueStore.getState().getQueue('conversation-1')[0]?.content)
        .toBe('Configured credentials for Gmail');
    });
  });

  it('stillQueuesAResumeMessageWhenTheHoldHasAlreadyEndedOnAnActionCard', async () => {
    mocks.approveToolAuthorization.mockResolvedValue(false);
    mockStream([], [heldAuthorization]);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'authorize-tool' }));

    await waitFor(() => {
      expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(1);
    });
  });

  it('stillQueuesAResumeMessageWhenReleasingTheHeldCallFails', async () => {
    // A network error is indistinguishable from "nobody was holding" as far as the user is
    // concerned: either way the agent must be given a way to continue.
    mocks.resolveApprovalGate.mockRejectedValue(new Error('offline'));
    mockStream([heldApproval], []);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'approve-service' }));

    await waitFor(() => {
      expect(useMessageQueueStore.getState().getQueue('conversation-1')).toHaveLength(1);
    });
  });

  it('sendsTheGateKeyOnDenySoTheHeldCallStopsImmediately', async () => {
    mockStream([], [heldAuthorization]);
    renderChat();

    fireEvent.click(screen.getByRole('button', { name: 'decline-tool' }));

    await waitFor(() => {
      expect(mocks.denyToolAuthorization)
        .toHaveBeenCalledWith('conversation-1', 'workflow:execute', 'call-9');
    });
  });
});
