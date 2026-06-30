/**
 * Single egress point for serialising a Message over the wire.
 *
 * Strips frontend-only optimistic flags (currently `pendingLocal`) so they
 * cannot leak into HTTP request bodies, analytics dumps, or any future
 * serializer that walks a Message object. The WireMessage type makes a
 * direct send of a raw Message a TypeScript compile error: any new
 * frontend-only field added to Message must also be omitted here.
 */

import type { Message } from './conversation.types';

export type WireMessage = Omit<Message, 'pendingLocal'>;

export function toWireMessage<M extends Partial<Message>>(msg: M): Omit<M, 'pendingLocal'> {
  const { pendingLocal: _omit, ...wire } = msg;
  return wire;
}
