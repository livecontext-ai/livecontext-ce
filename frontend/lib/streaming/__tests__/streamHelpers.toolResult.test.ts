import { describe, it, expect } from 'vitest';
import { detectStreamEventType } from '@/lib/streaming/streamHelpers';

/**
 * A live tool result is recognised by `success` + its call's `toolId`.
 *
 * The backend sends `resultId: null` on a live result, and the self-hosted monolith's shared
 * ObjectMapper drops null map values, so the key never arrives. Detection keyed on `resultId`
 * read every live tool result as a heartbeat there: tool rows stayed pending until the turn
 * ended, the builder did not follow the agent, and no side-panel tab opened.
 */
describe('streamHelpers - tool result detection', () => {
  it('regression: a live tool result with no resultId key is a tool_result', () => {
    const live = {
      streamId: 's1', toolId: 'call_init_workflow_1', toolName: 'workflow', success: true, durationMs: 31,
      result: '{}', visualization: { type: 'workflow', id: 'wf1', title: 'Flow' }, timestamp: 't',
    };
    expect(detectStreamEventType(live)).toBe('tool_result');
  });

  it('a result carrying resultId (cloud, or null kept) is still a tool_result', () => {
    expect(detectStreamEventType({ toolId: 'c1', success: false, resultId: null })).toBe('tool_result');
    expect(detectStreamEventType({ success: true, resultId: 'r1' })).toBe('tool_result');
  });

  it('a tool call (no success) is not mistaken for a result', () => {
    expect(detectStreamEventType({ toolName: 'workflow', toolId: 'c1', arguments: {} })).toBe('tool_call');
  });
});
