/**
 * @vitest-environment jsdom
 *
 * ChatCore welcome-hero empty state (welcomeLayout).
 *
 * When `welcomeLayout` is on AND the conversation is empty (no conversationId,
 * no messages, not streaming/loading), the composer is rendered CENTERED with an
 * optional title above it - and the bottom-docked composer is suppressed (exactly
 * one composer mounted). Once a conversation is active, or when welcomeLayout is
 * off, the classic bottom composer is used and no welcome title renders.
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
    stopStream: vi.fn(),
    checkAndReconnect: vi.fn(),
  },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => mocks.streaming,
  serviceApprovalKey: () => 'svc:connect',
  toolAuthorizationKey: (rule: string) => 'auth:' + rule,
  mergePendingServiceApprovals: (existing: any) => existing,
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
  conversationApi: { clearPendingAction: vi.fn() },
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

const WELCOME_TITLE = 'How can I help you?';

function streamState(status: string) {
  return { status, streamId: 's1', content: '', error: null, toolActivities: [], pendingServiceApprovals: [], pendingToolAuthorizations: [] };
}

describe('ChatCore welcome-hero empty state', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMessageQueueStore.setState({ queues: {} });
    mocks.streaming.isStreamingConversation.mockReturnValue(false);
    mocks.streaming.getStreamContent.mockReturnValue('');
    mocks.streaming.getToolActivities.mockReturnValue([]);
    mocks.streaming.getPendingServiceApprovals.mockReturnValue([]);
    mocks.streaming.getPendingToolAuthorizations.mockReturnValue([]);
    mocks.streaming.getStreamState.mockReturnValue(streamState('idle'));
  });

  it('renders the welcome title and exactly one (centered) composer when empty', () => {
    render(
      <ChatCore
        conversationId={null}
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
        welcomeLayout
        welcomeTitle={<h2>{WELCOME_TITLE}</h2>}
      />,
    );

    // Title renders above the centered composer.
    expect(screen.getByText(WELCOME_TITLE)).toBeInTheDocument();
    // Exactly one composer is mounted (the centered one) - the bottom composer is suppressed.
    expect(screen.getAllByTestId('composer')).toHaveLength(1);
    // The small default empty-state placeholder is NOT shown in welcome layout.
    expect(screen.queryByText('chat.placeholder')).not.toBeInTheDocument();
  });

  it('does NOT use the welcome layout once a conversation is active (messages present)', () => {
    render(
      <ChatCore
        conversationId="conv-1"
        conversation={null}
        messages={[{ role: 'user', content: 'hi', timestamp: '2026-01-01T00:00:00Z' }]}
        onSendMessage={vi.fn()}
        welcomeLayout
        welcomeTitle={<h2>{WELCOME_TITLE}</h2>}
      />,
    );

    // Active conversation -> history + bottom composer, no welcome title.
    expect(screen.queryByText(WELCOME_TITLE)).not.toBeInTheDocument();
    expect(screen.getByTestId('history')).toBeInTheDocument();
    expect(screen.getAllByTestId('composer')).toHaveLength(1);
  });

  it('falls back to the classic bottom composer + placeholder when welcomeLayout is off', () => {
    render(
      <ChatCore
        conversationId={null}
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
      />,
    );

    // Default empty state: the small placeholder is shown, no welcome title.
    expect(screen.queryByText(WELCOME_TITLE)).not.toBeInTheDocument();
    expect(screen.getByText('chat.placeholder')).toBeInTheDocument();
    expect(screen.getAllByTestId('composer')).toHaveLength(1);
  });

  it('does not render the welcome hero for an actively streaming conversation with no messages yet', () => {
    // Note: ChatCore short-circuits `isStreamingThisConversation` to false when
    // conversationId is null (`conversationId ? isStreamingConversation(id) : false`),
    // so the streaming term of showEmptyState is only ever evaluated WITH a
    // conversationId - and a conversationId alone already exits the empty state.
    // This asserts the realistic scenario (a live turn that has not produced a
    // message row yet) still shows history, never the welcome hero.
    mocks.streaming.isStreamingConversation.mockReturnValue(true);
    mocks.streaming.getStreamState.mockReturnValue(streamState('streaming'));

    render(
      <ChatCore
        conversationId="conv-1"
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
        welcomeLayout
        welcomeTitle={<h2>{WELCOME_TITLE}</h2>}
      />,
    );

    expect(screen.queryByText(WELCOME_TITLE)).not.toBeInTheDocument();
    expect(screen.getByTestId('history')).toBeInTheDocument();
    expect(screen.getAllByTestId('composer')).toHaveLength(1);
  });

  it('does not render the welcome hero while loading (isLoading alone suppresses it)', () => {
    // Isolate the isLoading term of showEmptyState.
    render(
      <ChatCore
        conversationId={null}
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
        isLoading
        welcomeLayout
        welcomeTitle={<h2>{WELCOME_TITLE}</h2>}
      />,
    );

    expect(screen.queryByText(WELCOME_TITLE)).not.toBeInTheDocument();
    expect(screen.getAllByTestId('composer')).toHaveLength(1);
  });

  it('lets an explicit emptyStateContent take precedence over the welcome hero', () => {
    render(
      <ChatCore
        conversationId={null}
        conversation={null}
        messages={[]}
        onSendMessage={vi.fn()}
        welcomeLayout
        welcomeTitle={<h2>{WELCOME_TITLE}</h2>}
        emptyStateContent={<div data-testid="custom-empty" />}
      />,
    );

    // Custom empty-state content wins; the welcome hero is not used.
    expect(screen.getByTestId('custom-empty')).toBeInTheDocument();
    expect(screen.queryByText(WELCOME_TITLE)).not.toBeInTheDocument();
    // Composer docks at the bottom (welcome layout suppressed).
    expect(screen.getAllByTestId('composer')).toHaveLength(1);
  });
});
