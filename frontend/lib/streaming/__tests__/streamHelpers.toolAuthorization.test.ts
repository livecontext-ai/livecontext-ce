import { describe, it, expect } from 'vitest';
import { detectStreamEventType, mapV2EventToV1 } from '@/lib/streaming/streamHelpers';

describe('streamHelpers - tool authorization event', () => {
  it('detects tool_authorization_required from the toolAuthorization discriminator', () => {
    const data = {
      streamId: 's1',
      toolAuthorization: { rule: 'application:acquire', toolName: 'application', action: 'acquire' },
      timestamp: '2026-06-01T00:00:00Z',
    };
    expect(detectStreamEventType(data)).toBe('tool_authorization_required');
  });

  it('still detects service_approval_required (no regression from the new check)', () => {
    const data = { services: [{ serviceType: 'gmail' }], reason: 'needed' };
    expect(detectStreamEventType(data)).toBe('service_approval_required');
  });

  it('maps the event into the toolAuthorization payload', () => {
    const data = {
      toolAuthorization: {
        rule: 'catalog:execute',
        toolName: 'catalog',
        action: 'execute',
        toolCallId: 'call-1',
        argsSummary: '{"action":"execute"}',
      },
    };
    const mapped = mapV2EventToV1(data, 'tool_authorization_required', null);
    expect(mapped.type).toBe('tool_authorization_required');
    expect(mapped.toolAuthorization?.rule).toBe('catalog:execute');
    expect(mapped.toolAuthorization?.toolCallId).toBe('call-1');
  });

  it('carries applicationId through for application:acquire (install modal target)', () => {
    const data = {
      toolAuthorization: {
        rule: 'application:acquire',
        toolName: 'application',
        action: 'acquire',
        applicationId: 'pub-123',
      },
    };
    const mapped = mapV2EventToV1(data, 'tool_authorization_required', null);
    expect(mapped.toolAuthorization?.applicationId).toBe('pub-123');
  });
});
