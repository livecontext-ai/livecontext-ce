import { describe, it, expect } from 'vitest';
import {
  parseToolActivitiesFromMessage,
  groupMessagesByExecution,
  isOpenableVisualization,
  toAutoOpenDetail,
} from '@/lib/chat/messageActivity';
import type { Message } from '@/lib/api/conversation.types';
import type { ToolVisualization } from '@/contexts/StreamingContext';

const msg = (over: Partial<Message>): Message => ({
  id: Math.random().toString(36).slice(2),
  conversationId: 'c1',
  role: 'assistant',
  content: '',
  model: 'm',
  timestamp: '2026-06-28T10:00:00Z',
  ...over,
});

describe('parseToolActivitiesFromMessage', () => {
  it('extracts _meta reasoning duration (not as a tool row)', () => {
    const json = JSON.stringify([{ toolName: '_meta', reasoningDurationMs: 4200 }]);
    const { tools, reasoningDurationMs } = parseToolActivitiesFromMessage(json, 'k');
    expect(reasoningDurationMs).toBe(4200);
    expect(tools).toHaveLength(0);
  });

  it('maps _thinking entries to reasoning rows', () => {
    const json = JSON.stringify([
      { toolName: '_thinking', title: 'Planning', thinkingMessage: 'step 1', id: 'th1' },
    ]);
    const { tools } = parseToolActivitiesFromMessage(json, 'k');
    expect(tools).toHaveLength(1);
    expect(tools[0].toolName).toBe('_thinking');
    expect(tools[0].thinkingTitle).toBe('Planning');
    expect(tools[0].thinkingMessage).toBe('step 1');
  });

  it('hydrates status: explicit status wins, pending coerces to interrupted, success bool fallback, default success', () => {
    const json = JSON.stringify([
      { id: 't1', toolName: 'a', status: 'error', error: 'boom' },
      { id: 't2', toolName: 'b', status: 'pending', error: 'stale' },
      { id: 't3', toolName: 'c', success: true },
      { id: 't4', toolName: 'd', success: false },
      { id: 't5', toolName: 'e' },
    ]);
    const { tools } = parseToolActivitiesFromMessage(json, 'k');
    const byId = Object.fromEntries(tools.map(t => [t.id, t]));
    expect(byId.t1.status).toBe('error');
    expect(byId.t1.error).toBe('boom');
    // pending -> interrupted, stale error dropped
    expect(byId.t2.status).toBe('interrupted');
    expect(byId.t2.error).toBeUndefined();
    expect(byId.t3.status).toBe('success');
    expect(byId.t4.status).toBe('error');
    expect(byId.t5.status).toBe('success');
  });

  it('deduplicates rows sharing an id', () => {
    const json = JSON.stringify([
      { id: 'dup', toolName: 'a', success: true },
      { id: 'dup', toolName: 'a', success: true },
    ]);
    const { tools } = parseToolActivitiesFromMessage(json, 'k');
    expect(tools).toHaveLength(1);
  });

  it('returns nothing for empty / malformed payloads', () => {
    expect(parseToolActivitiesFromMessage('[]', 'k').tools).toHaveLength(0);
    expect(parseToolActivitiesFromMessage(undefined, 'k').tools).toHaveLength(0);
    expect(parseToolActivitiesFromMessage('not json', 'k').tools).toHaveLength(0);
  });
});

describe('groupMessagesByExecution', () => {
  it('groups by turn and attaches the executionId from the turn messages', () => {
    const messages: Message[] = [
      msg({ id: 'u1', role: 'user', content: 'hi A', createdAt: '2026-06-28T10:00:00Z' }),
      msg({
        id: 'a1',
        role: 'assistant',
        executionId: 'exec-A',
        createdAt: '2026-06-28T10:00:05Z',
        toolCalls: JSON.stringify([{ id: 'tA', toolName: 'table', success: true }]),
      }),
      msg({ id: 'u2', role: 'user', content: 'hi B', createdAt: '2026-06-28T10:01:00Z' }),
      msg({
        id: 'a2',
        role: 'assistant',
        executionId: 'exec-B',
        createdAt: '2026-06-28T10:01:05Z',
        toolCalls: JSON.stringify([{ id: 'tB', toolName: 'interface', status: 'error' }]),
      }),
    ];
    const groups = groupMessagesByExecution(messages);
    expect(groups).toHaveLength(2);

    expect(groups[0].executionId).toBe('exec-A');
    expect(groups[0].key).toBe('exec-A');
    expect(groups[0].firstUserMessage?.id).toBe('u1');
    expect(groups[0].firstUserMessage?.preview).toBe('hi A');
    expect(groups[0].tools.map(t => t.toolName)).toEqual(['table']);
    expect(groups[0].status).toBe('success');
    expect(groups[0].startedAt).toBe('2026-06-28T10:00:00Z');
    expect(groups[0].endedAt).toBe('2026-06-28T10:00:05Z');

    expect(groups[1].executionId).toBe('exec-B');
    expect(groups[1].status).toBe('error');
  });

  it('falls back to a turn key when no executionId is present', () => {
    const messages: Message[] = [
      msg({ id: 'u1', role: 'user', content: 'hello' }),
      msg({ id: 'a1', role: 'assistant', content: 'hi' }),
    ];
    const groups = groupMessagesByExecution(messages);
    expect(groups).toHaveLength(1);
    expect(groups[0].executionId).toBeUndefined();
    expect(groups[0].key).toBe('turn-u1');
  });

  it('omits _system_stop / _system_error UI markers from a group while keeping real tools', () => {
    const messages: Message[] = [
      msg({ id: 'u1', role: 'user', content: 'go' }),
      msg({
        id: 'a1',
        role: 'assistant',
        executionId: 'exec-X',
        toolCalls: JSON.stringify([
          { id: 'real', toolName: 'table', success: true },
          { id: 'sys', toolName: '_system_error', status: 'error' },
        ]),
      }),
    ];
    const [group] = groupMessagesByExecution(messages);
    expect(group.tools.map(t => t.toolName)).toEqual(['table']);
  });

  it('puts messages before the first user message into a leading group', () => {
    const messages: Message[] = [
      msg({ id: 'a0', role: 'assistant', content: 'greeting' }),
      msg({ id: 'u1', role: 'user', content: 'q' }),
      msg({ id: 'a1', role: 'assistant', content: 'a' }),
    ];
    const groups = groupMessagesByExecution(messages);
    expect(groups).toHaveLength(2);
    expect(groups[0].key).toBe('pre-a0');
    expect(groups[0].firstUserMessage).toBeUndefined();
    expect(groups[1].firstUserMessage?.id).toBe('u1');
  });
});

describe('isOpenableVisualization', () => {
  const v = (over: Partial<ToolVisualization>): ToolVisualization =>
    ({ type: 'table', id: 'x', ...over } as ToolVisualization);

  it('treats panel-backed resources as openable (incl agent_browse / interface)', () => {
    for (const type of ['workflow', 'table', 'datasource', 'interface', 'application', 'agent', 'agent_browse']) {
      expect(isOpenableVisualization(v({ type: type as ToolVisualization['type'] }))).toBe(true);
    }
  });

  it('requires a runId for workflow_run', () => {
    expect(isOpenableVisualization(v({ type: 'workflow_run' }))).toBe(false);
    expect(isOpenableVisualization(v({ type: 'workflow_run', runId: 'r1' }))).toBe(true);
  });

  it('treats web_search / credential / image_generation / missing as NOT openable', () => {
    expect(isOpenableVisualization(v({ type: 'web_search' }))).toBe(false);
    expect(isOpenableVisualization(v({ type: 'credential' }))).toBe(false);
    expect(isOpenableVisualization(v({ type: 'image_generation' as ToolVisualization['type'] }))).toBe(false);
    expect(isOpenableVisualization(undefined)).toBe(false);
    expect(isOpenableVisualization(v({ id: '' }))).toBe(false);
  });
});

describe('toAutoOpenDetail', () => {
  it('projects the side-panel event detail', () => {
    const detail = toAutoOpenDetail({ type: 'workflow_run', id: 'wf1', title: 'Run', runId: 'r9' } as ToolVisualization);
    expect(detail).toEqual({ type: 'workflow_run', id: 'wf1', title: 'Run', runId: 'r9' });
  });
});
