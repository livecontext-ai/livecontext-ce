import { describe, it, expect, expectTypeOf } from 'vitest';
import { toWireMessage, type WireMessage } from '../messageWire';
import type { Message } from '../conversation.types';

describe('toWireMessage', () => {
  it('toWireMessageStripsPendingLocalEvenIfTrue', () => {
    const msg: Message = {
      id: 'm-1',
      conversationId: 'c-1',
      role: 'user',
      content: 'hello',
      model: 'gpt-4',
      timestamp: '2026-01-01T00:00:00Z',
      pendingLocal: true,
    };

    const wire = toWireMessage(msg);

    expect('pendingLocal' in wire).toBe(false);
    expect(wire.id).toBe('m-1');
    expect(wire.content).toBe('hello');
  });

  it('passes through messages without pendingLocal unchanged in shape', () => {
    const msg = {
      role: 'user' as const,
      content: 'plain',
      model: 'gpt-4',
      timestamp: '2026-01-01T00:00:00Z',
    };

    const wire = toWireMessage(msg);

    expect(wire).toEqual(msg);
  });

  it('also strips pendingLocal: false (the field itself, not the truthy state)', () => {
    const msg: Partial<Message> = {
      content: 'x',
      pendingLocal: false,
    };

    const wire = toWireMessage(msg);

    expect('pendingLocal' in wire).toBe(false);
  });

  it('WireMessage type rejects pendingLocal at compile time', () => {
    // Compile-time guarantee: WireMessage cannot include pendingLocal.
    // This is structurally enforced - adding pendingLocal to WireMessage
    // would break the Omit<Message, 'pendingLocal'> contract.
    expectTypeOf<WireMessage>().not.toHaveProperty('pendingLocal');
  });
});
