// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Agent OBSERVABILITY - fleet-inspector execution detail
 * (AgentExecutionInspectorDetail).
 *
 * This is the execution-detail view shown in the FLEET inspector (view=fleet →
 * agent node → Executions tab → row) - distinct from the metrics-dashboard
 * drill-down (AgentExecutionConversation) and previously covered by NOTHING (its
 * only neighbour test mocks it to `() => null`). It is reachable only through the
 * heavy fleet-canvas path, so we render it directly with its data hooks/service
 * mocked ("mock every result") and assert the branches that view alone owns: the
 * 5-way header status-icon resolver, the error + loop-detected blocks, the
 * conditional token-breakdown rows, tool calls grouped by iteration, and the
 * "Launched by" caller-fetch (incl. a deleted caller).
 */

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => Object.assign((k: string) => `${ns}.${k}`, { rich: (k: string) => k }),
}));
vi.mock('@/components/agents/AvatarPicker', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/ui/StatusBadge', () => ({ StatusBadge: () => null }));
// LoadOlderSentinel uses IntersectionObserver (absent in jsdom); its older-page
// path is covered by the e2e suite. Stub it out here.
vi.mock('@/components/agent-fleet/LoadOlderSentinel', () => ({ LoadOlderSentinel: () => null }));

const useAgentExecutionMock = vi.fn();
vi.mock('../hooks/useAgentExecutionData', () => ({
  useAgentExecution: (id: string) => useAgentExecutionMock(id),
}));

const usePagedMock = vi.fn();
vi.mock('@/hooks/agent/useExecutionPagedResource', () => ({
  useExecutionPagedResource: (id: string, fetchPage: unknown) => usePagedMock(id, fetchPage),
}));

const getAgentMock = vi.fn();
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getAgent: (id: string) => getAgentMock(id),
    // The component binds these and passes them to useExecutionPagedResource; we
    // discriminate the two paged calls by the bound function name, so the names
    // must be preserved.
    getExecutionToolCallsPaged: function getExecutionToolCallsPaged() {},
    getExecutionConversationPaged: function getExecutionConversationPaged() {},
  },
}));

import { AgentExecutionInspectorDetail } from '../AgentExecutionInspectorDetail';

type Json = Record<string, unknown>;

function exec(o: Json = {}): Json {
  return {
    id: 'exec-1', runId: 'r', nodeId: 'n', agentType: 'agent', status: 'COMPLETED',
    iterationCount: 3, totalToolCalls: 6, successfulToolCalls: 5, failedToolCalls: 1,
    messageCount: 8, initialHistorySize: 1,
    totalPromptTokens: 3000, totalCompletionTokens: 1200, totalTokens: 4200,
    totalCacheCreationTokens: 0, totalCacheReadTokens: 0, totalCachedTokens: 0, totalReasoningTokens: 0,
    durationMs: 4200, loopDetected: false, startedAt: '2026-06-13T10:00:00.000Z', depth: 0,
    creditsConsumed: 0.2, model: 'gpt-4o-mini', provider: 'openai',
    ...o,
  };
}

function pagedRes(items: Json[]) {
  return { items, loading: false, loadingOlder: false, hasMore: false, totalElements: items.length, loadOlder: vi.fn(), reload: vi.fn() };
}

function renderDetail(opts: { execution?: Json | null; isLoading?: boolean; toolCalls?: Json[]; conversation?: Json[]; agents?: Json[] }) {
  const { execution = null, isLoading = false, toolCalls = [], conversation = [], agents = [] } = opts;
  useAgentExecutionMock.mockReturnValue({ data: execution ?? undefined, isLoading });
  usePagedMock.mockImplementation((_id: string, fetchPage: { name?: string }) => {
    const isTools = /ToolCalls/i.test(fetchPage?.name ?? '');
    return pagedRes(isTools ? toolCalls : conversation);
  });
  return render(<AgentExecutionInspectorDetail executionId="exec-1" agents={agents as never} />);
}

describe('AgentExecutionInspectorDetail (fleet inspector execution detail)', () => {
  beforeEach(() => {
    useAgentExecutionMock.mockReset();
    usePagedMock.mockReset();
    getAgentMock.mockReset();
  });
  afterEach(() => cleanup());

  it('CE-AGENT-INSP-001 shows a spinner while the execution header loads', () => {
    const { container } = renderDetail({ isLoading: true });
    expect(container.querySelector('.animate-spin')).toBeTruthy();
  });

  it('CE-AGENT-INSP-002 shows the empty message when no execution is selected', () => {
    renderDetail({ execution: null });
    expect(screen.getByText('fleetInspector.noExecutionSelected')).toBeInTheDocument();
  });

  it('CE-AGENT-INSP-003 renders the COMPLETED status icon (emerald) and the model', () => {
    const { container } = renderDetail({ execution: exec({ status: 'COMPLETED', model: 'gpt-4o-mini' }) });
    expect(container.querySelector('svg.text-emerald-500')).toBeTruthy();
    expect(screen.getByText('gpt-4o-mini')).toBeInTheDocument();
  });

  it('CE-AGENT-INSP-004 renders the RUNNING status icon (blue, spinning)', () => {
    const { container } = renderDetail({ execution: exec({ status: 'RUNNING' }) });
    expect(container.querySelector('svg.text-blue-500.animate-spin')).toBeTruthy();
  });

  it('CE-AGENT-INSP-005 renders the CANCELLED status icon (purple)', () => {
    const { container } = renderDetail({ execution: exec({ status: 'CANCELLED', stopReason: 'CANCELLED' }) });
    expect(container.querySelector('svg.text-purple-500')).toBeTruthy();
  });

  it('CE-AGENT-INSP-006 renders a partial stop reason (amber) with its scoped badge', () => {
    const { container } = renderDetail({ execution: exec({ status: 'FAILED', stopReason: 'BUDGET_EXHAUSTED', budgetScope: 'tenant' }) });
    expect(container.querySelector('svg.text-amber-500')).toBeTruthy();
    // The StopReasonBadge renders with the budget scope appended to its label.
    expect(container.querySelector('[aria-label*="(tenant)"]')).toBeTruthy();
  });

  it('CE-AGENT-INSP-007 renders the failure icon (red) and the prominent error alert', () => {
    const { container } = renderDetail({ execution: exec({ status: 'FAILED', stopReason: 'ERROR', errorMessage: 'Boom, the agent crashed' }) });
    expect(container.querySelector('svg.text-red-500')).toBeTruthy();
    expect(screen.getByText('Boom, the agent crashed')).toBeInTheDocument();
  });

  it('CE-AGENT-INSP-008 renders the loop-detected warning with its type and tool', () => {
    renderDetail({ execution: exec({ loopDetected: true, loopType: 'INFINITE', loopToolName: 'search_web' }) });
    expect(screen.getByText(/INFINITE/)).toBeInTheDocument();
    expect(screen.getByText(/search_web/)).toBeInTheDocument();
  });

  it('CE-AGENT-INSP-009 renders the key metric cells (tokens, tool calls, success rate)', () => {
    renderDetail({ execution: exec({ totalTokens: 4200, successfulToolCalls: 5, totalToolCalls: 6 }) });
    expect(screen.getAllByText('4.2k').length).toBeGreaterThan(0); // formatTokenCount(4200)
    expect(screen.getByText('5/6')).toBeInTheDocument();
    expect(screen.getByText('83%')).toBeInTheDocument(); // round(5/6*100)
  });

  it('CE-AGENT-INSP-010 token breakdown lists reasoning/cached rows only when non-zero', () => {
    renderDetail({ execution: exec({ totalTokens: 5000, totalReasoningTokens: 200, totalCachedTokens: 100, totalCacheCreationTokens: 0 }) });
    fireEvent.click(screen.getByRole('button', { name: /tokenBreakdown/ }));
    expect(screen.getByText('fleetInspector.reasoningTokens')).toBeInTheDocument();
    expect(screen.getByText('fleetInspector.cachedTokens')).toBeInTheDocument();
    expect(screen.queryByText('fleetInspector.cacheCreationTokens')).toBeNull(); // 0 → hidden
  });

  it('CE-AGENT-INSP-011 groups tool calls by iteration and expands arguments/output', () => {
    const toolCalls = [
      { id: 1, sequenceNumber: 1, iterationNumber: 1, toolName: 'get_weather', success: true, arguments: { city: 'Paris' }, content: 'Sunny', durationMs: 100, isRepeat: false, consecutiveCount: 1 },
      { id: 2, sequenceNumber: 2, iterationNumber: 2, toolName: 'send_email', success: false, arguments: { to: 'x' }, errorMessage: 'SMTP down', durationMs: 200, isRepeat: false, consecutiveCount: 1 },
    ];
    const { container } = renderDetail({ execution: exec({ totalToolCalls: 2 }), toolCalls });
    fireEvent.click(screen.getByRole('button', { name: /toolCalls/ }));
    expect(screen.getByText('fleetInspector.iteration 1')).toBeInTheDocument();
    expect(screen.getByText('fleetInspector.iteration 2')).toBeInTheDocument();
    fireEvent.click(screen.getByText('get_weather'));
    expect(screen.getByText(/"city": "Paris"/)).toBeInTheDocument();
    expect(screen.getByText('Sunny')).toBeInTheDocument();
    // The failed send_email row carries the red icon and reveals its error when expanded.
    expect(container.querySelector('svg.text-red-500')).toBeTruthy();
    fireEvent.click(screen.getByText('send_email'));
    expect(screen.getByText('SMTP down')).toBeInTheDocument();
  });

  it('CE-AGENT-INSP-012 conversation hides SYSTEM messages and labels roles', () => {
    const conversation = [
      { id: 1, sequenceNumber: 1, role: 'SYSTEM', content: 'SECRET-SYSTEM', iterationNumber: 0 },
      { id: 2, sequenceNumber: 2, role: 'USER', content: 'hello there', iterationNumber: 1 },
      { id: 3, sequenceNumber: 3, role: 'ASSISTANT', content: 'hi back', iterationNumber: 1 },
    ];
    renderDetail({ execution: exec({ messageCount: 3 }), conversation });
    fireEvent.click(screen.getByRole('button', { name: /conversation/i }));
    expect(screen.getByText('hello there')).toBeInTheDocument();
    expect(screen.getByText('hi back')).toBeInTheDocument();
    expect(screen.queryByText('SECRET-SYSTEM')).toBeNull();
    expect(screen.getByText('User')).toBeInTheDocument();
    expect(screen.getByText('Assistant')).toBeInTheDocument();
  });

  it('CE-AGENT-INSP-013 launched-by uses the caller from the local agents list (no fetch)', () => {
    renderDetail({ execution: exec({ callerAgentEntityId: 'caller-1' }), agents: [{ id: 'caller-1', name: 'Parent Agent' }] });
    expect(screen.getByText('Parent Agent')).toBeInTheDocument();
    expect(getAgentMock).not.toHaveBeenCalled();
  });

  it('CE-AGENT-INSP-014 launched-by fetches an absent caller and tolerates a deleted one', async () => {
    getAgentMock.mockResolvedValueOnce({ id: 'caller-2', name: 'Fetched Parent' });
    renderDetail({ execution: exec({ callerAgentEntityId: 'caller-2' }), agents: [] });
    await waitFor(() => expect(screen.getByText('Fetched Parent')).toBeInTheDocument());
    expect(getAgentMock).toHaveBeenCalledWith('caller-2');

    cleanup();
    getAgentMock.mockRejectedValueOnce(new Error('404'));
    renderDetail({ execution: exec({ callerAgentEntityId: 'caller-3' }), agents: [] });
    fireEvent.click(screen.getByRole('button', { name: /launchedBy/ }));
    await waitFor(() => expect(screen.getByText('fleetInspector.unknownAgent')).toBeInTheDocument());
  });

  it('CE-AGENT-INSP-015 renders the configuration and system-prompt sections', () => {
    renderDetail({ execution: exec({ model: 'gpt-4o', provider: 'openai', temperature: 0.7, maxTokensConfig: 4096, maxIterationsConfig: 12, systemPrompt: 'You are helpful.' }) });
    fireEvent.click(screen.getByRole('button', { name: /configuration/i }));
    expect(screen.getByText('gpt-4o (openai)')).toBeInTheDocument();
    expect(screen.getByText('0.7')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /systemPromptLabel/ }));
    expect(screen.getByText('You are helpful.')).toBeInTheDocument();
  });

  it('CE-AGENT-INSP-016 renders the source + non-default agent-type chip, hidden for plain agents', () => {
    const { container } = renderDetail({ execution: exec({ status: 'COMPLETED', source: 'workflow', agentType: 'classify' }) });
    expect(screen.getByText('workflow')).toBeInTheDocument();
    expect(screen.getByText('classify')).toBeInTheDocument();
    expect(container.querySelector('.bg-purple-100')).toBeTruthy(); // the agent-type chip
    cleanup();
    // A plain 'agent' execution shows NO agent-type chip.
    const { container: plain } = renderDetail({ execution: exec({ status: 'COMPLETED', source: undefined, agentType: 'agent' }) });
    expect(plain.querySelector('.bg-purple-100')).toBeFalsy();
  });

  it('CE-AGENT-INSP-017 falls back to the red failure icon for a PENDING execution with no stop reason', () => {
    const { container } = renderDetail({ execution: exec({ status: 'PENDING', stopReason: undefined, errorMessage: undefined }) });
    // Not running/completed/cancelled and no partial stopReason → the AlertCircle fallback.
    expect(container.querySelector('svg.text-red-500')).toBeTruthy();
  });
});
