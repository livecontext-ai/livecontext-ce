import { describe, it, expect, vi } from 'vitest';
import {
  fetchLinkedAgent,
  canFetchLinkedAgent,
  resolveConversationAgentId,
  type LinkedAgentApi,
} from '../linkedAgent';

function makeApi() {
  const agentById = { id: 'agent-fwd', name: 'Forward' } as never;
  const agentByConv = { id: 'agent-rev', name: 'Reverse' } as never;
  const getAgent = vi.fn(() => Promise.resolve(agentById));
  const getAgentByConversationId = vi.fn(() => Promise.resolve(agentByConv));
  const api: LinkedAgentApi = { getAgent, getAgentByConversationId };
  return { api, getAgent, getAgentByConversationId, agentById, agentByConv };
}

describe('fetchLinkedAgent - forward link preference', () => {
  it('uses getAgent(linkedAgentId) and NEVER the reverse lookup when the forward link is known', async () => {
    const { api, getAgent, getAgentByConversationId, agentById } = makeApi();

    const result = await fetchLinkedAgent(api, { linkedAgentId: 'agent-fwd', conversationId: 'conv-1' });

    expect(getAgent).toHaveBeenCalledWith('agent-fwd');
    // The reverse column is single-valued/often null for a multi-conversation agent, so it
    // must not run once we have the authoritative forward link.
    expect(getAgentByConversationId).not.toHaveBeenCalled();
    expect(result).toBe(agentById);
  });

  it('falls back to the reverse by-conversation lookup when no forward link', async () => {
    const { api, getAgent, getAgentByConversationId, agentByConv } = makeApi();

    const result = await fetchLinkedAgent(api, { linkedAgentId: null, conversationId: 'conv-1' });

    expect(getAgentByConversationId).toHaveBeenCalledWith('conv-1');
    expect(getAgent).not.toHaveBeenCalled();
    expect(result).toBe(agentByConv);
  });

  it('treats an empty-string forward link as unknown and falls back', async () => {
    const { api, getAgent, getAgentByConversationId } = makeApi();

    await fetchLinkedAgent(api, { linkedAgentId: '', conversationId: 'conv-1' });

    expect(getAgent).not.toHaveBeenCalled();
    expect(getAgentByConversationId).toHaveBeenCalledWith('conv-1');
  });

  it('resolves to null without any call when neither id is known', async () => {
    const { api, getAgent, getAgentByConversationId } = makeApi();

    const result = await fetchLinkedAgent(api, { linkedAgentId: null, conversationId: null });

    expect(result).toBeNull();
    expect(getAgent).not.toHaveBeenCalled();
    expect(getAgentByConversationId).not.toHaveBeenCalled();
  });

  it('propagates a getAgent rejection (e.g. 404) so the caller can fall back to the model selector', async () => {
    const err = Object.assign(new Error('not found'), { status: 404 });
    const api: LinkedAgentApi = {
      getAgent: vi.fn(() => Promise.reject(err)),
      getAgentByConversationId: vi.fn(() => Promise.resolve(null)),
    };

    await expect(fetchLinkedAgent(api, { linkedAgentId: 'gone', conversationId: 'c' })).rejects.toBe(err);
    // The reverse lookup must not run as a "recovery" - the forward id was authoritative.
    expect(api.getAgentByConversationId).not.toHaveBeenCalled();
  });
});

describe('canFetchLinkedAgent', () => {
  it('is true when a forward link is known even without a conversation', () => {
    expect(canFetchLinkedAgent({ linkedAgentId: 'a', conversationId: null })).toBe(true);
  });

  it('is true when only a conversation is known', () => {
    expect(canFetchLinkedAgent({ linkedAgentId: null, conversationId: 'c' })).toBe(true);
  });

  it('is false when neither is known', () => {
    expect(canFetchLinkedAgent({ linkedAgentId: null, conversationId: undefined })).toBe(false);
  });
});

describe('resolveConversationAgentId - forward link source resolution', () => {
  const CONV = 'conv-1';

  it('returns null for a brand-new chat (no conversationId)', () => {
    expect(resolveConversationAgentId({
      conversationId: null,
      currentConversation: { id: CONV, agentId: 'a' },
      conversations: [{ id: CONV, agentId: 'a' }],
    })).toBeNull();
  });

  it('prefers the freshly loaded currentConversation.agentId', () => {
    expect(resolveConversationAgentId({
      conversationId: CONV,
      currentConversation: { id: CONV, agentId: 'agent-current' },
      conversations: [{ id: CONV, agentId: 'agent-list' }],
    })).toBe('agent-current');
  });

  it('ignores currentConversation whose id does not match (mid-navigation guard)', () => {
    // currentConversation still points at the PREVIOUS conversation during a switch - its
    // agentId must not leak onto the new conversationId.
    expect(resolveConversationAgentId({
      conversationId: CONV,
      currentConversation: { id: 'other-conv', agentId: 'stale-agent' },
      conversations: null,
    })).toBeNull();
  });

  it('falls back to the sidebar list when currentConversation is not yet loaded', () => {
    // This is the case that lets the avatar resolve BEFORE the full conversation load,
    // avoiding a flash of the model selector for an agent conversation.
    expect(resolveConversationAgentId({
      conversationId: CONV,
      currentConversation: null,
      conversations: [{ id: 'x', agentId: 'nope' }, { id: CONV, agentId: 'agent-list' }],
    })).toBe('agent-list');
  });

  it('falls back to the sidebar list when currentConversation matches but has no agent', () => {
    expect(resolveConversationAgentId({
      conversationId: CONV,
      currentConversation: { id: CONV, agentId: null },
      conversations: [{ id: CONV, agentId: 'agent-list' }],
    })).toBe('agent-list');
  });

  it('returns null for a plain (non-agent) conversation absent from both sources', () => {
    expect(resolveConversationAgentId({
      conversationId: CONV,
      currentConversation: { id: CONV, agentId: null },
      conversations: [],
    })).toBeNull();
  });
});
