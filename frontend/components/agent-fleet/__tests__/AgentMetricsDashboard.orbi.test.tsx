// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import en from '@/messages/en.json';

// The general chat row is now Orbi: it must read "Orbi" and carry the Orbi logo, not the
// generic chat bubble. Everything the dashboard fetches is stubbed; only the chat summary
// matters here, because it is what makes the row appear.
const chatSummary = vi.hoisted(() => ({
  totalExecutions: 3, successCount: 3, failureCount: 0, cancelledCount: 0, loopDetectedCount: 0,
  totalTokensUsed: 10, totalToolCalls: 1, totalDurationMs: 300, totalCreditsConsumed: 0,
  avgDurationMs: 100, successRate: 100,
}));
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getAgents: vi.fn().mockResolvedValue([]),
    getFleetSummary: vi.fn().mockResolvedValue(null),
    getChatSummary: vi.fn().mockResolvedValue(chatSummary),
    getAgentTypeSummary: vi.fn().mockResolvedValue(null),
    getToolStats: vi.fn().mockResolvedValue([]),
    getDailyStats: vi.fn().mockResolvedValue([]),
    getChatDailyStats: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('next-intl', () => ({
  useTranslations: (ns: string) => (k: string) => {
    const table = (en as unknown as Record<string, Record<string, unknown>>)[ns] ?? {};
    const v = table[k];
    return typeof v === 'string' ? v : `${ns}.${k}`;
  },
  useLocale: () => 'en',
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/resources/resourceDeleted', () => ({ useResourceRowsDeleted: () => {} }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/hooks/useModels', () => ({ useModels: () => ({ models: [] }), modelMatches: () => false }));
vi.mock('@/hooks/useThemeSafely', () => ({ useThemeValue: () => 'light' }));
vi.mock('../AgentExecutionDetail', () => ({ AgentExecutionConversation: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null, AGENT_CONFIGURATION_TAB: 'config' }));
vi.mock('@/components/agents/StopReasonBadge', () => ({ StopReasonBadge: () => null }));
vi.mock('recharts', () => {
  const Stub = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  return new Proxy({}, { get: () => Stub });
});

import { AgentMetricsDashboard } from '../AgentMetricsDashboard';

afterEach(cleanup);

describe('AgentMetricsDashboard - the general chat is Orbi', () => {
  it('labels the general chat row "Orbi" with the Orbi logo', async () => {
    render(<AgentMetricsDashboard />);
    const label = await screen.findByText('Orbi', { selector: 'span.font-medium' });
    const row = label.closest('tr')!;
    expect(row.querySelector('[data-testid="orbi-logo"]')).toBeInTheDocument();
    expect(screen.queryByText('General Chat')).not.toBeInTheDocument();
  });
});
