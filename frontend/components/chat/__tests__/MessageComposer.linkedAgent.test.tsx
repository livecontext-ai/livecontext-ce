// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Regression: the linked-agent lookup must PREFER the conversation's forward link
// (`linkedAgentId`, from conversations.agent_id) over the reverse by-conversation lookup.
// The reverse `getAgentByConversationId` reads the single-valued agents.conversation_id
// column, which is null for an agent owning several conversations - so without the
// forward-link preference the composer scopes skills/options to the conversation instead
// of the agent (and the avatar falls back to the model selector).

// Capture the config passed to useQuery so we can invoke its queryFn directly.
let capturedQuery: { queryKey?: unknown; queryFn?: () => unknown; enabled?: boolean } | null = null;
vi.mock('@tanstack/react-query', () => ({
  useQuery: (cfg: { queryKey?: unknown; queryFn?: () => unknown; enabled?: boolean }) => {
    capturedQuery = cfg;
    return { data: null, isPending: false };
  },
}));

const getAgent = vi.fn(() => Promise.resolve({ id: 'agent-1', name: 'A' }));
const getAgentByConversationId = vi.fn(() => Promise.resolve(null));
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getAgent: (...args: unknown[]) => getAgent(...(args as [])),
    getAgentByConversationId: (...args: unknown[]) => getAgentByConversationId(...(args as [])),
  },
}));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/hooks/useDefaultSkills', () => ({
  useDefaultSkills: () => ({
    activeSkillIds: new Set<string>(),
    setActiveSkillIds: vi.fn(),
    initializeDefaults: vi.fn(),
    hasExplicitSkillSelection: false,
  }),
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/components/chat/AttachmentHandler', () => ({ AttachmentHandler: () => null }));
vi.mock('@/components/chat/QueuedMessageBar', () => ({ QueuedMessageBar: () => null }));

import { MessageComposer } from '../MessageComposer';

function renderComposer(props: { conversationId?: string; linkedAgentId?: string | null }) {
  capturedQuery = null;
  return render(
    <MessageComposer
      inputValue=""
      onInputChange={() => {}}
      onSendMessage={() => {}}
      showAttachmentMenu={false}
      onShowAttachmentMenu={() => {}}
      conversationId={props.conversationId}
      linkedAgentId={props.linkedAgentId}
    />,
  );
}

describe('MessageComposer - linked-agent forward-link preference', () => {
  beforeEach(() => {
    getAgent.mockClear();
    getAgentByConversationId.mockClear();
  });
  afterEach(cleanup);

  it('loads the agent by id (forward link) when linkedAgentId is provided', () => {
    renderComposer({ conversationId: 'conv-1', linkedAgentId: 'agent-1' });
    expect(capturedQuery).not.toBeNull();
    expect(capturedQuery!.enabled).toBe(true);
    // The query key must include the linked agent id so it re-fetches when it changes.
    expect(capturedQuery!.queryKey).toEqual(['linked-agent', 'conv-1', 'agent-1']);

    capturedQuery!.queryFn!();
    expect(getAgent).toHaveBeenCalledWith('agent-1');
    // The reverse lookup must NOT run when the forward link is known - it would miss the
    // agent whose agents.conversation_id is null.
    expect(getAgentByConversationId).not.toHaveBeenCalled();
  });

  it('falls back to the reverse by-conversation lookup when no linkedAgentId', () => {
    renderComposer({ conversationId: 'conv-1', linkedAgentId: null });
    expect(capturedQuery!.enabled).toBe(true);
    expect(capturedQuery!.queryKey).toEqual(['linked-agent', 'conv-1', null]);

    capturedQuery!.queryFn!();
    expect(getAgentByConversationId).toHaveBeenCalledWith('conv-1');
    expect(getAgent).not.toHaveBeenCalled();
  });

  it('is enabled by the forward link even without a conversationId', () => {
    renderComposer({ conversationId: undefined, linkedAgentId: 'agent-9' });
    expect(capturedQuery!.enabled).toBe(true);

    capturedQuery!.queryFn!();
    expect(getAgent).toHaveBeenCalledWith('agent-9');
    expect(getAgentByConversationId).not.toHaveBeenCalled();
  });

  it('stays disabled when neither a conversation nor a linked agent is known', () => {
    renderComposer({ conversationId: undefined, linkedAgentId: null });
    expect(capturedQuery!.enabled).toBe(false);
  });
});
