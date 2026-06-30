/**
 * @vitest-environment jsdom
 *
 * Core of the async-authorization feature: several approval/authorization cards can be
 * pending in PARALLEL during a single turn, and approving/denying one must remove ONLY
 * that card (by its canonical key) while the others stay pending.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ChatCore } from '../ChatCore';
import { useMessageQueueStore } from '@/lib/stores/message-queue-store';

const mocks = vi.hoisted(() => ({
  clearPendingAction: vi.fn(),
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
    services: [
      ...existing.services,
      ...incoming.services.filter((next: any) =>
        !existing.services.some((current: any) => current.serviceType === next.serviceType)),
    ],
    reason: [existing.reason, incoming.reason].filter(Boolean).join('\n') || undefined,
    needsAttention: existing.needsAttention || incoming.needsAttention,
  }),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    deleteWorkflow: vi.fn(), deleteDataSource: vi.fn(), deleteInterface: vi.fn(), deleteAgent: vi.fn(),
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
  useChatConfig: () => ({ updateConfig: vi.fn(), config: {}, isLoading: false, isSaving: false, error: null, target: 'conversation' }),
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: vi.fn() },
}));

vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({ useAnchorScrollToBottom: vi.fn() }));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div data-testid="message-history" /> }));
// Expose the composer's send action so a test can drive ChatCore.handleSendMessage.
vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({ onSendMessage }: { onSendMessage?: (content?: string) => void }) => (
    <button type="button" data-testid="composer-send" onClick={() => onSendMessage?.('a new message')}>
      send
    </button>
  ),
}));

// Each card renders an identifiable testid + a deny button so the test can drive per-card actions.
vi.mock('@/components/chat/ServiceApprovalCard', () => ({
  ServiceApprovalCard: ({ pendingApproval, onApproved, onDenied }: {
    pendingApproval: { services: Array<{ serviceType: string; serviceName: string }> };
    onApproved?: (names: string[]) => void;
    onDenied?: (names: string[]) => void;
  }) => {
    const type = pendingApproval.services.map((s) => s.serviceType).sort().join('-');
    return (
      <div data-testid={`svc-card-${type}`}>
        <button type="button" onClick={() => onApproved?.(pendingApproval.services.map((s) => s.serviceName))}>
          approve-svc-{type}
        </button>
        <button type="button" onClick={() => onDenied?.(pendingApproval.services.map((s) => s.serviceName))}>
          deny-svc-{type}
        </button>
      </div>
    );
  },
}));

vi.mock('@/components/chat/ToolAuthorizationCard', () => ({
  ToolAuthorizationCard: ({ pendingAuthorization, onDenied }: {
    pendingAuthorization: { rule: string };
    onDenied?: (rule: string) => void;
  }) => (
    <div data-testid={`auth-card-${pendingAuthorization.rule}`}>
      <button type="button" onClick={() => onDenied?.(pendingAuthorization.rule)}>
        deny-auth-{pendingAuthorization.rule}
      </button>
    </div>
  ),
}));

const gmail = { services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }], timestamp: 1 };
const slack = { services: [{ serviceType: 'slack', serviceName: 'Slack', iconSlug: 'slack' }], timestamp: 2 };
const acquire = { rule: 'application:acquire', toolName: 'application', timestamp: 3 };

describe('ChatCore parallel approval/authorization cards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.clearPendingAction.mockResolvedValue(undefined);
    mocks.denyToolAuthorization.mockResolvedValue(undefined);
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    mocks.streaming.getStreamState.mockReturnValue({
      status: 'streaming', streamId: 's1', content: 'working', error: null,
      toolActivities: [], pendingServiceApprovals: [gmail, slack], pendingToolAuthorizations: [acquire],
    });
    mocks.streaming.getStreamContent.mockReturnValue('working');
    mocks.streaming.getToolActivities.mockReturnValue([]);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([gmail, slack]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([acquire]);
  });

  it('renders one card per pending action - two service approvals + one tool authorization', () => {
    render(<ChatCore conversationId="conv-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);

    expect(screen.getByTestId('svc-card-gmail-slack')).toBeInTheDocument();
    expect(screen.getByTestId('auth-card-application:acquire')).toBeInTheDocument();
  });

  it('denying ONE card clears only its key and removes only that card from the view', () => {
    render(<ChatCore conversationId="conv-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'deny-svc-gmail-slack' }));

    // Backend + streaming cleared by the gmail card's canonical key only.
    expect(mocks.clearPendingAction).toHaveBeenCalledWith('conv-1', 'svc:connect');
    expect(mocks.streaming.clearServiceApproval).toHaveBeenCalledWith('conv-1', 'svc:connect');

    // The denied card is gone; the other two are untouched (locally dismissed by key).
    expect(screen.queryByTestId('svc-card-gmail-slack')).not.toBeInTheDocument();
    expect(screen.getByTestId('auth-card-application:acquire')).toBeInTheDocument();
  });

  it('denying the tool authorization clears only its rule key, leaving the service cards', () => {
    render(<ChatCore conversationId="conv-1" conversation={null} messages={[]} onSendMessage={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'deny-auth-application:acquire' }));

    expect(mocks.denyToolAuthorization).toHaveBeenCalledWith('conv-1', 'application:acquire');
    expect(mocks.streaming.clearToolAuthorization).toHaveBeenCalledWith('conv-1', 'auth:application:acquire');
    expect(screen.queryByTestId('auth-card-application:acquire')).not.toBeInTheDocument();
    expect(screen.getByTestId('svc-card-gmail-slack')).toBeInTheDocument();
  });

  it('approving ONE card keeps the OTHER cards and queues a resume with keepPendingActions', async () => {
    // Non-streaming still queues by default; the composer auto-drain sends it later.
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    const onSendMessage = vi.fn();

    render(<ChatCore conversationId="conv-1" conversation={null} messages={[]} onSendMessage={onSendMessage} />);
    expect(screen.getByTestId('svc-card-gmail-slack')).toBeInTheDocument();
    expect(screen.getByTestId('auth-card-application:acquire')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'approve-svc-gmail-slack' }));

    // Only gmail's card is cleared (by its key); the resume carries keepPendingActions so the
    // backend does NOT wipe the still-pending slack + acquire cards.
    expect(mocks.streaming.clearServiceApproval).toHaveBeenCalledWith('conv-1', 'svc:connect');
    expect(mocks.clearPendingAction).toHaveBeenCalledWith('conv-1', 'svc:connect');
    expect(onSendMessage).not.toHaveBeenCalled();
    expect(useMessageQueueStore.getState().getQueue('conv-1')).toHaveLength(1);
    expect(useMessageQueueStore.getState().getQueue('conv-1')[0]?.keepPendingActions).toBe(true);

    // The sibling cards remain visible; the approved one is gone.
    expect(screen.queryByTestId('svc-card-gmail-slack')).not.toBeInTheDocument();
    expect(screen.getByTestId('auth-card-application:acquire')).toBeInTheDocument();
  });

  it('sending a fresh message dismisses BOTH service and tool-authorization cards (no key = clear all)', async () => {
    // Only a tool-authorization card is pending - exercises the previously-missing both-kinds path.
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([acquire]);
    const onSendMessage = vi.fn();

    render(<ChatCore conversationId="conv-1" conversation={null} messages={[]} onSendMessage={onSendMessage} />);
    expect(screen.getByTestId('auth-card-application:acquire')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('composer-send'));

    // Both kinds are cleared with NO key (full dismiss), and the DB is cleared without a key.
    expect(mocks.streaming.clearToolAuthorization).toHaveBeenCalledWith('conv-1');
    expect(mocks.streaming.clearServiceApproval).toHaveBeenCalledWith('conv-1');
    expect(mocks.clearPendingAction).toHaveBeenCalledWith('conv-1');
    // onSendMessage fires after the awaited DB clear resolves; a FRESH message carries no
    // keepPendingActions opts (undefined) - the dismiss-all path ran.
    await waitFor(() => expect(onSendMessage).toHaveBeenCalledWith('a new message', undefined, undefined, undefined));
  });

  it('merges persisted conversation.pendingActions with the live streaming cards (deduped by key)', () => {
    // Live stream has only the gmail card; the DB carries gmail (dup) + a NEW notion card.
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([gmail]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([]);
    const conversation = {
      pendingActions: [
        { waiting_for: 'service_approval', services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }] },
        { waiting_for: 'service_approval', services: [{ serviceType: 'notion', serviceName: 'Notion', iconSlug: 'notion' }] },
      ],
    } as never;

    render(<ChatCore conversationId="conv-1" conversation={conversation} messages={[]} onSendMessage={vi.fn()} />);

    expect(screen.getByTestId('svc-card-gmail-notion')).toBeInTheDocument();
  });
});
