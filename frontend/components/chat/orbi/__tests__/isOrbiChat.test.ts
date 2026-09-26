import { describe, expect, it } from 'vitest';
import { isOrbiChat } from '../isOrbiChat';

describe('isOrbiChat: Orbi is the chat with no agent', () => {
  it('the home page with no agent is Orbi', () => {
    expect(isOrbiChat({ conversationId: null, conversation: null, agentId: null })).toBe(true);
  });

  it('the home page opened for an agent (?agentId=) is not Orbi', () => {
    expect(isOrbiChat({ conversationId: null, conversation: null, agentId: 'a1' })).toBe(false);
  });

  it('a loaded chat conversation with no agent is Orbi', () => {
    expect(isOrbiChat({ conversationId: 'c1', conversation: { id: 'c1' }, agentId: null })).toBe(true);
  });

  it('an agent conversation is not Orbi, whether the agent comes from the page or the row', () => {
    expect(isOrbiChat({ conversationId: 'c1', conversation: { id: 'c1' }, agentId: 'a1' })).toBe(false);
    expect(isOrbiChat({ conversationId: 'c1', conversation: { id: 'c1', agentId: 'a1' }, agentId: null })).toBe(false);
  });

  it('a studio conversation is not Orbi', () => {
    expect(isOrbiChat({ conversationId: 'c1', conversation: { id: 'c1', kind: 'studio' }, agentId: null })).toBe(false);
  });

  it('a conversation not loaded yet is not Orbi, so the mascot never flashes on an agent thread', () => {
    expect(isOrbiChat({ conversationId: 'c1', conversation: null, agentId: null })).toBe(false);
    expect(isOrbiChat({ conversationId: 'c1', conversation: { id: 'other' }, agentId: null })).toBe(false);
  });
  it('a conversation the page just started from Orbi stays Orbi before it loads (Enter on the home page)', () => {
    expect(isOrbiChat({ conversationId: 'c1', conversation: null, agentId: null, startedFromOrbi: true })).toBe(true);
  });

  it('once loaded, the real conversation wins over startedFromOrbi', () => {
    expect(isOrbiChat({ conversationId: 'c1', conversation: { id: 'c1', agentId: 'a1' }, agentId: null, startedFromOrbi: true })).toBe(false);
    expect(isOrbiChat({ conversationId: 'c1', conversation: { id: 'c1', kind: 'studio' }, agentId: null, startedFromOrbi: true })).toBe(false);
  });

  it('an agent on the page still wins over startedFromOrbi', () => {
    expect(isOrbiChat({ conversationId: 'c1', conversation: null, agentId: 'a1', startedFromOrbi: true })).toBe(false);
  });
});
