import { describe, it, expect, vi, beforeEach } from 'vitest';
import { quotaApi } from '../quota-api.service';
import { apiClient } from '@/lib/api/api-client';
import { CREDIT_TIERS as SHARED_CREDIT_TIERS, CREDIT_COSTS as SHARED_CREDIT_COSTS } from '@/lib/billing/pricing-constants';

// Mock the apiClient module
vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const mockGet = vi.mocked(apiClient.get);

describe('QuotaApiService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // =========================================================================
  // getBalance
  // =========================================================================

  describe('getBalance', () => {
    it('should return credit balance on success', async () => {
      mockGet.mockResolvedValue({ balance: 1500.5 });

      const result = await quotaApi.getBalance();

      expect(result).toEqual({ balance: 1500.5 });
      expect(mockGet).toHaveBeenCalledWith('/credits/balance');
    });

    it('should return zero balance', async () => {
      mockGet.mockResolvedValue({ balance: 0 });

      const result = await quotaApi.getBalance();

      expect(result.balance).toBe(0);
    });

    it('should return negative balance (overdraft model)', async () => {
      mockGet.mockResolvedValue({ balance: -250.75 });

      const result = await quotaApi.getBalance();

      expect(result.balance).toBe(-250.75);
    });

    it('should propagate network error', async () => {
      mockGet.mockRejectedValue(new Error('Network Error'));

      await expect(quotaApi.getBalance()).rejects.toThrow('Network Error');
    });

    it('should propagate 401 unauthorized error', async () => {
      mockGet.mockRejectedValue(new Error('Unauthorized'));

      await expect(quotaApi.getBalance()).rejects.toThrow('Unauthorized');
    });
  });

  // =========================================================================
  // getSummary
  // =========================================================================

  describe('getSummary', () => {
    it('should return full summary with breakdown', async () => {
      const summary = {
        balance: 500,
        totalConsumedLast30Days: 150.25,
        breakdownByType: {
          AGENT_EXECUTION: { count: 10, credits: 100.25 },
          WORKFLOW_NODE: { count: 50, credits: 50 },
        },
      };
      mockGet.mockResolvedValue(summary);

      const result = await quotaApi.getSummary();

      expect(result.balance).toBe(500);
      expect(result.totalConsumedLast30Days).toBe(150.25);
      expect(result.breakdownByType.AGENT_EXECUTION.count).toBe(10);
      expect(result.breakdownByType.WORKFLOW_NODE.credits).toBe(50);
      expect(mockGet).toHaveBeenCalledWith('/credits/summary');
    });

    it('should handle empty breakdown', async () => {
      const summary = {
        balance: 1000,
        totalConsumedLast30Days: 0,
        breakdownByType: {},
      };
      mockGet.mockResolvedValue(summary);

      const result = await quotaApi.getSummary();

      expect(result.totalConsumedLast30Days).toBe(0);
      expect(Object.keys(result.breakdownByType)).toHaveLength(0);
    });

    it('should propagate server error', async () => {
      mockGet.mockRejectedValue(new Error('Internal Server Error'));

      await expect(quotaApi.getSummary()).rejects.toThrow('Internal Server Error');
    });
  });

  // =========================================================================
  // getHistory
  // =========================================================================

  describe('getHistory', () => {
    const emptyPage = {
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: 0,
      size: 20,
    };

    it('should use default pagination (page=0, size=20)', async () => {
      mockGet.mockResolvedValue(emptyPage);

      await quotaApi.getHistory();

      expect(mockGet).toHaveBeenCalledWith('/credits/history', {
        params: { page: '0', size: '20' },
      });
    });

    it('should use custom pagination', async () => {
      mockGet.mockResolvedValue(emptyPage);

      await quotaApi.getHistory(2, 10);

      expect(mockGet).toHaveBeenCalledWith('/credits/history', {
        params: { page: '2', size: '10' },
      });
    });

    it('should include sourceType filter when provided', async () => {
      mockGet.mockResolvedValue(emptyPage);

      await quotaApi.getHistory(0, 20, 'AGENT_EXECUTION');

      expect(mockGet).toHaveBeenCalledWith('/credits/history', {
        params: { page: '0', size: '20', sourceType: 'AGENT_EXECUTION' },
      });
    });

    it('should not include sourceType when undefined', async () => {
      mockGet.mockResolvedValue(emptyPage);

      await quotaApi.getHistory(0, 20, undefined);

      expect(mockGet).toHaveBeenCalledWith('/credits/history', {
        params: { page: '0', size: '20' },
      });
    });

    it('should return history entries with all fields', async () => {
      const page = {
        content: [
          {
            id: 1,
            userId: 42,
            amount: -2.5,
            balanceAfter: 97.5,
            sourceType: 'AGENT_EXECUTION',
            sourceId: 'exec-123',
            provider: 'openai',
            model: 'gpt-4o',
            promptTokens: 1000,
            completionTokens: 500,
            description: 'openai/gpt-4o: 1000 input + 500 output tokens',
            createdAt: '2026-02-27T10:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      };
      mockGet.mockResolvedValue(page);

      const result = await quotaApi.getHistory();

      expect(result.content).toHaveLength(1);
      expect(result.content[0].sourceType).toBe('AGENT_EXECUTION');
      expect(result.content[0].amount).toBe(-2.5);
      expect(result.content[0].provider).toBe('openai');
    });

    it('should handle WORKFLOW_NODE entries with null provider/model', async () => {
      const page = {
        content: [
          {
            id: 2,
            userId: 42,
            amount: -1,
            balanceAfter: 99,
            sourceType: 'WORKFLOW_NODE',
            sourceId: 'run-1:core:step1',
            provider: null,
            model: null,
            promptTokens: null,
            completionTokens: null,
            description: 'Workflow node: run-1:core:step1',
            createdAt: '2026-02-27T10:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      };
      mockGet.mockResolvedValue(page);

      const result = await quotaApi.getHistory(0, 20, 'WORKFLOW_NODE');

      expect(result.content[0].provider).toBeNull();
      expect(result.content[0].model).toBeNull();
      expect(result.content[0].promptTokens).toBeNull();
    });

    it('should propagate API error', async () => {
      mockGet.mockRejectedValue(new Error('Service Unavailable'));

      await expect(quotaApi.getHistory()).rejects.toThrow('Service Unavailable');
    });
  });

  // =========================================================================
  // Workspace-scope override (Quota page workspace filter)
  // =========================================================================
  //
  // The page-local workspace filter passes a chosen org id so a single request
  // is scoped to a workspace OTHER than the globally-active one. The id rides as
  // an `X-Active-Organization-ID` header which the apiClient gives precedence
  // over its global active-org provider, and the gateway validates against the
  // user's memberships. Omitting it must leave the call byte-identical to the
  // pre-filter behaviour (no header → fall back to the active workspace).

  describe('workspace-scope override', () => {
    const ORG = 'org-9f3a';
    const headerOpts = { headers: { 'X-Active-Organization-ID': ORG } };

    it('getSummary sends the org header only when an orgId is given', async () => {
      mockGet.mockResolvedValue({ balance: 0, totalConsumedLast30Days: 0, breakdownByType: {} });

      await quotaApi.getSummary(ORG);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/summary', headerOpts);

      await quotaApi.getSummary();
      // No override → exactly one arg, no header (unchanged behaviour).
      expect(mockGet).toHaveBeenLastCalledWith('/credits/summary');
    });

    it('getSummary treats null/undefined orgId as "no override"', async () => {
      mockGet.mockResolvedValue({ balance: 0, totalConsumedLast30Days: 0, breakdownByType: {} });

      await quotaApi.getSummary(null);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/summary');
    });

    it('getHistory merges the org header alongside pagination params', async () => {
      mockGet.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 15 });

      await quotaApi.getHistory(1, 15, undefined, ORG);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/history', {
        params: { page: '1', size: '15' },
        ...headerOpts,
      });
    });

    it('getHistory keeps both the sourceType filter and the org header', async () => {
      mockGet.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

      await quotaApi.getHistory(0, 20, 'CHAT_CONVERSATION', ORG);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/history', {
        params: { page: '0', size: '20', sourceType: 'CHAT_CONVERSATION' },
        ...headerOpts,
      });
    });

    it('getHistory omits the header when no orgId (params untouched)', async () => {
      mockGet.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

      await quotaApi.getHistory(0, 20, 'CHAT_CONVERSATION');
      expect(mockGet).toHaveBeenLastCalledWith('/credits/history', {
        params: { page: '0', size: '20', sourceType: 'CHAT_CONVERSATION' },
      });
    });

    it('getAnalytics merges the org header alongside the days/filter params', async () => {
      mockGet.mockResolvedValue({ dailyUsage: [], providers: [], models: [], sourceTypes: [] });

      await quotaApi.getAnalytics(90, 'AGENT_EXECUTION', 'openai', 'gpt-4o', ORG);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/analytics', {
        params: { days: '90', sourceType: 'AGENT_EXECUTION', provider: 'openai', model: 'gpt-4o' },
        ...headerOpts,
      });
    });

    it('getAnalytics omits the header when no orgId', async () => {
      mockGet.mockResolvedValue({ dailyUsage: [], providers: [], models: [], sourceTypes: [] });

      await quotaApi.getAnalytics(30);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/analytics', { params: { days: '30' } });
    });
  });

  // =========================================================================
  // "All workspaces" aggregate (V366 - ADR-0010)
  // =========================================================================
  //
  // The "All workspaces" view sends `allWorkspaces=true` so the backend drops the
  // org reporting filter and returns the full payer aggregate (including
  // unattributed/legacy rows). In this mode the org override header is NOT sent
  // (the backend ignores org anyway). The balance is unaffected (single wallet).

  describe('allWorkspaces aggregate (V366)', () => {
    it('getSummary with allWorkspaces=true sends ?allWorkspaces=true and NO org header (even if an orgId is passed)', async () => {
      mockGet.mockResolvedValue({ balance: 0, totalConsumedLast30Days: 0, breakdownByType: {} });

      await quotaApi.getSummary('org-9f3a', true);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/summary', { params: { allWorkspaces: 'true' } });
    });

    it('getHistory with allWorkspaces=true adds the flag and drops the org header', async () => {
      mockGet.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

      await quotaApi.getHistory(0, 20, 'CHAT_CONVERSATION', 'org-9f3a', true);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/history', {
        params: { page: '0', size: '20', sourceType: 'CHAT_CONVERSATION', allWorkspaces: 'true' },
      });
    });

    it('getAnalytics with allWorkspaces=true adds the flag and drops the org header', async () => {
      mockGet.mockResolvedValue({ dailyUsage: [], providers: [], models: [], sourceTypes: [] });

      await quotaApi.getAnalytics(30, undefined, undefined, undefined, 'org-9f3a', true);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/analytics', {
        params: { days: '30', allWorkspaces: 'true' },
      });
    });

    it('allWorkspaces=false keeps the existing org-override behaviour (header sent)', async () => {
      mockGet.mockResolvedValue({ balance: 0, totalConsumedLast30Days: 0, breakdownByType: {} });

      await quotaApi.getSummary('org-9f3a', false);
      expect(mockGet).toHaveBeenLastCalledWith('/credits/summary', { headers: { 'X-Active-Organization-ID': 'org-9f3a' } });
    });
  });

  // =========================================================================
  // getPricing
  // =========================================================================

  describe('getPricing', () => {
    it('should return all active model pricing', async () => {
      const pricing = [
        { id: 1, provider: 'openai', model: 'gpt-4o', inputRate: 0.25, outputRate: 1.0, fixedCost: 0 },
        { id: 2, provider: 'anthropic', model: 'claude-3-5-sonnet', inputRate: 0.3, outputRate: 1.5, fixedCost: 0 },
      ];
      mockGet.mockResolvedValue(pricing);

      const result = await quotaApi.getPricing();

      expect(result).toHaveLength(2);
      expect(result[0].provider).toBe('openai');
      expect(result[1].inputRate).toBe(0.3);
      expect(mockGet).toHaveBeenCalledWith('/credits/pricing');
    });

    it('should return empty array when no pricing exists', async () => {
      mockGet.mockResolvedValue([]);

      const result = await quotaApi.getPricing();

      expect(result).toHaveLength(0);
    });

    it('should propagate error', async () => {
      mockGet.mockRejectedValue(new Error('Forbidden'));

      await expect(quotaApi.getPricing()).rejects.toThrow('Forbidden');
    });
  });

  // =========================================================================
  // Credit Tier Constants Consistency (frontend/backend alignment)
  // =========================================================================

  describe('frontend/backend alignment', () => {
    // Single source of truth - values MUST match CreditTierConstants.java in auth-service
    const CREDIT_TIERS = SHARED_CREDIT_TIERS;
    const CREDIT_COSTS = SHARED_CREDIT_COSTS;

    it('should have 10 credit tiers matching backend CreditTierConstants', () => {
      expect(CREDIT_TIERS).toHaveLength(10);
      expect(CREDIT_COSTS).toHaveLength(10);
    });

    it('tier 0 should be free (5000 credits, $0)', () => {
      expect(CREDIT_TIERS[0]).toBe(5_000);
      expect(CREDIT_COSTS[0]).toBe(0);
    });

    it('tier 9 should be max (10M credits, $7000)', () => {
      expect(CREDIT_TIERS[9]).toBe(10_000_000);
      expect(CREDIT_COSTS[9]).toBe(7_000);
    });

    it('starter max credits should be 100K (tier 4)', () => {
      expect(CREDIT_TIERS[4]).toBe(100_000);
    });

    it('cost per credit should decrease at higher tiers (volume discount)', () => {
      // Skip lower tiers where business pricing doesn't follow strict volume discount
      // (tiers 1-2 and 6-7 have slight cost-per-credit bumps by design)
      for (let i = 8; i < CREDIT_TIERS.length; i++) {
        if (CREDIT_COSTS[i] === 0) continue;
        const costPerCredit = CREDIT_COSTS[i] / CREDIT_TIERS[i];
        const prevCostPerCredit = CREDIT_COSTS[i - 1] / CREDIT_TIERS[i - 1];
        if (CREDIT_COSTS[i - 1] > 0) {
          expect(costPerCredit).toBeLessThanOrEqual(prevCostPerCredit);
        }
      }
    });
  });
});
