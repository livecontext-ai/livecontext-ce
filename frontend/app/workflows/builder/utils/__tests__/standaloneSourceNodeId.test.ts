import { describe, it, expect } from 'vitest';
import { buildStandaloneSourceNodeId } from '../standaloneSourceNodeId';

describe('buildStandaloneSourceNodeId', () => {
  it('prefixes the node id with the kind so webhook/schedule/chat/form share a namespace', () => {
    expect(buildStandaloneSourceNodeId('webhook', 'node-123')).toBe('webhook-node-123');
    expect(buildStandaloneSourceNodeId('schedule', 'node-123')).toBe('schedule-node-123');
    expect(buildStandaloneSourceNodeId('chat', 'node-123')).toBe('chat-node-123');
    expect(buildStandaloneSourceNodeId('form', 'node-123')).toBe('form-node-123');
  });

  it('is deterministic - the same (kind, nodeId) always produces the same string', () => {
    // This is the invariant the backend dedup `(tenant_id, source_node_id)` relies on.
    const a = buildStandaloneSourceNodeId('webhook', 'abc');
    const b = buildStandaloneSourceNodeId('webhook', 'abc');
    expect(a).toBe(b);
  });

  it('does not mix namespaces - the same node id under a different kind is a different key', () => {
    expect(buildStandaloneSourceNodeId('webhook', 'n1')).not.toBe(buildStandaloneSourceNodeId('chat', 'n1'));
    expect(buildStandaloneSourceNodeId('schedule', 'n1')).not.toBe(buildStandaloneSourceNodeId('form', 'n1'));
  });
});
