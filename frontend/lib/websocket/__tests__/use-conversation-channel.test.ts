import { describe, expect, it } from 'vitest';
import { normalizeConversationChannelEvent } from '../use-conversation-channel';

describe('normalizeConversationChannelEvent', () => {
  it('keeps flat conversation events intact', () => {
    const message = { id: 'msg-1', role: 'assistant', content: 'Done' };
    const event = {
      type: 'message_added',
      conversationId: 'conv-1',
      message,
    };

    expect(normalizeConversationChannelEvent(event)).toEqual({
      eventType: 'message_added',
      data: event,
    });
  });

  it('does not unwrap a business payload field on flat events', () => {
    const event = {
      type: 'custom_event',
      payload: { keep: 'nested-business-data' },
      conversationId: 'conv-1',
    };

    expect(normalizeConversationChannelEvent(event)).toEqual({
      eventType: 'custom_event',
      data: event,
    });
  });

  it('unwraps standardized channel envelopes and carries the event type into inner data', () => {
    const envelope = {
      v: 1,
      type: 'sub_agent_content',
      id: 'evt-1',
      ts: 123,
      payload: {
        subAgent: { name: 'Worker', agentId: 'agent-1' },
        content: 'partial',
      },
    };

    expect(normalizeConversationChannelEvent(envelope)).toEqual({
      eventType: 'sub_agent_content',
      data: {
        subAgent: { name: 'Worker', agentId: 'agent-1' },
        content: 'partial',
        type: 'sub_agent_content',
      },
    });
  });
});
