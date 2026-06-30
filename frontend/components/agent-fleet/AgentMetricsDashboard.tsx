'use client';

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { Activity, Coins, Clock, CheckCircle2, AlertCircle, AlertTriangle, ChevronDown, ChevronRight, ChevronLeft, Wrench, TrendingUp, MessageSquare, BarChart3, Bot, Brain, Database, GitBranch, XCircle, Tag, Shield, Globe } from 'lucide-react';
import { useTranslations, useLocale } from 'next-intl';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import type {
  FleetSummary,
  ChatSummary,
  AgentExecutionRecord,
  ToolCallStats,
  DailyStats,
  PagedResponse,
} from '@/lib/api/orchestrator/agent-metrics.types';
import type { Agent } from '@/lib/api/orchestrator/types';
import { cn } from '@/lib/utils';
import { formatCost, formatCostCompact, isCeMode } from '@/lib/format-cost';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { useUnifiedAppSafe } from '@/contexts/UnifiedAppContext';
import { useModels, modelMatches } from '@/hooks/useModels';
import { AgentExecutionConversation } from './AgentExecutionDetail';
import { AvatarDisplay } from '@/components/agents';
import { AgentPanelContent, AGENT_CONFIGURATION_TAB } from '@/components/app/AgentPanelContent';
import { StopReasonBadge } from '@/components/agents/StopReasonBadge';
import { useThemeValue } from '@/hooks/useThemeSafely';
import { formatUtcDate, formatUtcDateTime, formatUtcTime, parseUtcAware, formatRelativeDateI18n } from '@/lib/utils/dateFormatters';
import {
  Select, SelectTrigger, SelectContent, SelectItem, SelectValue,
} from '@/components/ui/select';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';

/**
 * Agent Metrics Dashboard - fleet overview + per-agent drill-down.
 * Uses agent entity counter columns for instant fleet overview (zero aggregation).
 */
export function AgentMetricsDashboard() {
  const t = useTranslations('agentMetrics');
  const tRuns = useTranslations('runs');
  const locale = useLocale();
  // Localized relative time (runs.* keys + APP locale) so the "last execution"
  // values render in the UI language instead of a hardcoded English "5m ago".
  const formatRel = (dateStr: string) => formatRelativeDateI18n(dateStr, tRuns, locale);
  const theme = useThemeValue();
  const isDark = theme === 'dark';
  // Recharts is HTML-based - Tailwind dark: classes can't reach SVG/inline styles,
  // so we resolve theme-aware colours here once and pass concrete hex values to
  // the chart's tooltip/cursor/legend props.
  const chartColours = {
    tooltipBg:     isDark ? '#1e293b' : '#ffffff',  // slate-800 / white
    tooltipBorder: isDark ? '#334155' : '#e2e8f0',  // slate-700 / slate-200
    tooltipText:   isDark ? '#e2e8f0' : '#0f172a',  // slate-200 / slate-900
    cursorFill:    isDark ? 'rgba(148,163,184,0.12)' : 'rgba(15,23,42,0.06)',
    axisStroke:    isDark ? '#64748b' : '#94a3b8',  // slate-500 / slate-400
  };
  const sidePanel = useSidePanelSafe();
  const appContext = useUnifiedAppSafe();
  const { models } = useModels();
  const chatModel = useMemo(() => {
    const selected = appContext?.state.selectedModel;
    if (!selected || !selected.id) return null;
    const found = models.find(m => modelMatches(m, selected));
    return found
      ? { name: found.name, provider: found.provider }
      : { name: selected.id, provider: selected.provider || null };
  }, [appContext?.state.selectedModel, models]);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [fleetSummary, setFleetSummary] = useState<FleetSummary | null>(null);
  const [chatSummary, setChatSummary] = useState<ChatSummary | null>(null);
  const [toolStats, setToolStats] = useState<ToolCallStats[]>([]);
  const [expandedAgentId, setExpandedAgentId] = useState<string | null>(null);
  const [agentExecutions, setAgentExecutions] = useState<PagedResponse<AgentExecutionRecord> | null>(null);
  const [chatExecutions, setChatExecutions] = useState<PagedResponse<AgentExecutionRecord> | null>(null);
  const [classifySummary, setClassifySummary] = useState<ChatSummary | null>(null);
  const [guardrailSummary, setGuardrailSummary] = useState<ChatSummary | null>(null);
  const [browserAgentSummary, setBrowserAgentSummary] = useState<ChatSummary | null>(null);
  const [classifyExecutions, setClassifyExecutions] = useState<PagedResponse<AgentExecutionRecord> | null>(null);
  const [guardrailExecutions, setGuardrailExecutions] = useState<PagedResponse<AgentExecutionRecord> | null>(null);
  const [browserAgentExecutions, setBrowserAgentExecutions] = useState<PagedResponse<AgentExecutionRecord> | null>(null);
  const [dailyStats, setDailyStats] = useState<DailyStats[]>([]);
  const [chartPeriod, setChartPeriod] = useState(30);
  const [chartAgentId, setChartAgentId] = useState<string | null>(null);
  const [overviewAgentId, setOverviewAgentId] = useState<string | null>(null);
  const [toolStatsAgentId, setToolStatsAgentId] = useState<string | null>(null);
  const [filteredToolStats, setFilteredToolStats] = useState<ToolCallStats[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingChart, setLoadingChart] = useState(false);
  const [loadingToolStats, setLoadingToolStats] = useState(false);
  const [loadingExecutions, setLoadingExecutions] = useState(false);
  const [loadingChatExecutions, setLoadingChatExecutions] = useState(false);
  const [loadingClassifyExecutions, setLoadingClassifyExecutions] = useState(false);
  const [loadingGuardrailExecutions, setLoadingGuardrailExecutions] = useState(false);
  const [loadingBrowserAgentExecutions, setLoadingBrowserAgentExecutions] = useState(false);

  // Phase 6 (2026-05-18) - clear all workspace-bound metric/execution state
  // on workspace switch. The dashboard stays mounted under /app/agents/metrics;
  // without this reset the previous workspace's stats remain visible until
  // the user changes the time range or agent selector.
  useOrgScopedReset(() => {
    setAgents([]);
    setFleetSummary(null);
    setChatSummary(null);
    setToolStats([]);
    setExpandedAgentId(null);
    setAgentExecutions(null);
    setChatExecutions(null);
    setClassifySummary(null);
    setGuardrailSummary(null);
    setBrowserAgentSummary(null);
    setClassifyExecutions(null);
    setGuardrailExecutions(null);
    setBrowserAgentExecutions(null);
    setDailyStats([]);
    setChartAgentId(null);
    setOverviewAgentId(null);
    setToolStatsAgentId(null);
    setFilteredToolStats(null);
  });

  // Initial load - everything
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      // Browser-agent summary is fetched in the same parallel batch but
      // tolerated to fail independently. The backend now recognises the
      // 'browser_agent' agent_type filter (added to ALLOWED_AGENT_TYPES in
      // AgentMetricsQueryService), but we keep the .catch(()=>null) for
      // transitional safety: pre-restart instances or upstream failures
      // surface null (hides the row + dropdown entry) rather than blowing
      // up the whole metrics dashboard.
      const browserAgentP = agentService.getAgentTypeSummary('browser_agent').catch(() => null);
      const [agentsData, summary, chatSum, classifySum, guardrailSum, browserAgentSum, tools, daily] = await Promise.all([
        agentService.getAgents(),
        agentService.getFleetSummary(),
        agentService.getChatSummary(),
        agentService.getAgentTypeSummary('classify'),
        agentService.getAgentTypeSummary('guardrail'),
        browserAgentP,
        agentService.getToolStats(),
        agentService.getDailyStats(chartPeriod, chartAgentId || undefined),
      ]);
      setAgents(agentsData);
      setFleetSummary(summary);
      setChatSummary(chatSum);
      setClassifySummary(classifySum);
      setGuardrailSummary(guardrailSum);
      setBrowserAgentSummary(browserAgentSum);
      setToolStats(tools);
      setDailyStats(daily);
    } catch (error) {
      console.error('Failed to load agent metrics:', error);
    } finally {
      setLoading(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Chart-only reload when period or agent filter changes
  const loadChart = useCallback(async () => {
    setLoadingChart(true);
    setDailyStats([]);
    try {
      const daily = chartAgentId === '__chat__'
        ? await agentService.getChatDailyStats(chartPeriod)
        : await agentService.getDailyStats(chartPeriod, chartAgentId || undefined);
      setDailyStats(daily);
    } catch (error) {
      console.error('Failed to load daily stats:', error);
    } finally {
      setLoadingChart(false);
    }
  }, [chartPeriod, chartAgentId]);

  // Skip initial render (loadData handles it), only react to filter changes
  const initialRef = React.useRef(true);
  useEffect(() => {
    if (initialRef.current) {
      initialRef.current = false;
      return;
    }
    loadChart();
  }, [loadChart]);

  const loadAgentExecutionsPage = useCallback(async (agentId: string, page: number) => {
    setLoadingExecutions(true);
    try {
      const executions = await agentService.getAgentExecutions(agentId, page, 10);
      setAgentExecutions(executions);
    } catch (error) {
      console.error('Failed to load agent executions:', error);
    } finally {
      setLoadingExecutions(false);
    }
  }, []);

  const handleExpandAgent = useCallback(async (agentId: string) => {
    if (expandedAgentId === agentId) {
      setExpandedAgentId(null);
      setAgentExecutions(null);
      return;
    }
    setExpandedAgentId(agentId);
    setAgentExecutions(null);
    loadAgentExecutionsPage(agentId, 0);
  }, [expandedAgentId, loadAgentExecutionsPage]);

  const handleOpenAgentPanel = useCallback((agent: Agent) => {
    if (!sidePanel) return;
    sidePanel.openTab({
      id: `agent-${agent.id}`,
      label: agent.name || 'Agent',
      icon: agent.avatarUrl
        ? <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name || 'Agent'} size="sm" className="!w-4 !h-4" />
        : <Bot className="w-4 h-4" />,
      pinned: true,
      scope: ['/app/agent/*'],
      content: <AgentPanelContent agentId={agent.id} initialTab={AGENT_CONFIGURATION_TAB} />,
    });
  }, [sidePanel]);

  const loadChatExecutionsPage = useCallback(async (page: number) => {
    setLoadingChatExecutions(true);
    try {
      const executions = await agentService.getChatExecutions(page, 10);
      setChatExecutions(executions);
    } catch (error) {
      console.error('Failed to load chat executions:', error);
    } finally {
      setLoadingChatExecutions(false);
    }
  }, []);

  const handleExpandChat = useCallback(async () => {
    if (expandedAgentId === '__chat__') {
      setExpandedAgentId(null);
      setChatExecutions(null);
      return;
    }
    setExpandedAgentId('__chat__');
    setAgentExecutions(null);
    setChatExecutions(null);
    setClassifyExecutions(null);
    setGuardrailExecutions(null);
    loadChatExecutionsPage(0);
  }, [expandedAgentId, loadChatExecutionsPage]);

  const loadClassifyExecutionsPage = useCallback(async (page: number) => {
    setLoadingClassifyExecutions(true);
    try {
      const executions = await agentService.getAgentTypeExecutions('classify', page, 10);
      setClassifyExecutions(executions);
    } catch (error) {
      console.error('Failed to load classify executions:', error);
    } finally {
      setLoadingClassifyExecutions(false);
    }
  }, []);

  const loadGuardrailExecutionsPage = useCallback(async (page: number) => {
    setLoadingGuardrailExecutions(true);
    try {
      const executions = await agentService.getAgentTypeExecutions('guardrail', page, 10);
      setGuardrailExecutions(executions);
    } catch (error) {
      console.error('Failed to load guardrail executions:', error);
    } finally {
      setLoadingGuardrailExecutions(false);
    }
  }, []);

  // Browser-agent loader mirrors classify/guardrail. Backend filters by
  // agent_type='browser_agent' (the same column the observability work uses).
  // Failures are logged but do not break the surrounding dashboard render.
  const loadBrowserAgentExecutionsPage = useCallback(async (page: number) => {
    setLoadingBrowserAgentExecutions(true);
    try {
      const executions = await agentService.getAgentTypeExecutions('browser_agent', page, 10);
      setBrowserAgentExecutions(executions);
    } catch (error) {
      console.error('Failed to load browser_agent executions:', error);
    } finally {
      setLoadingBrowserAgentExecutions(false);
    }
  }, []);

  const handleExpandClassify = useCallback(async () => {
    if (expandedAgentId === '__classify__') {
      setExpandedAgentId(null);
      setClassifyExecutions(null);
      return;
    }
    setExpandedAgentId('__classify__');
    setAgentExecutions(null);
    setChatExecutions(null);
    setGuardrailExecutions(null);
    setClassifyExecutions(null);
    loadClassifyExecutionsPage(0);
  }, [expandedAgentId, loadClassifyExecutionsPage]);

  const handleExpandGuardrail = useCallback(async () => {
    if (expandedAgentId === '__guardrail__') {
      setExpandedAgentId(null);
      setGuardrailExecutions(null);
      return;
    }
    setExpandedAgentId('__guardrail__');
    setAgentExecutions(null);
    setChatExecutions(null);
    setClassifyExecutions(null);
    setGuardrailExecutions(null);
    loadGuardrailExecutionsPage(0);
  }, [expandedAgentId, loadGuardrailExecutionsPage]);

  const handleExpandBrowserAgent = useCallback(async () => {
    if (expandedAgentId === '__browser_agent__') {
      setExpandedAgentId(null);
      setBrowserAgentExecutions(null);
      return;
    }
    setExpandedAgentId('__browser_agent__');
    setAgentExecutions(null);
    setChatExecutions(null);
    setClassifyExecutions(null);
    setGuardrailExecutions(null);
    setBrowserAgentExecutions(null);
    loadBrowserAgentExecutionsPage(0);
  }, [expandedAgentId, loadBrowserAgentExecutionsPage]);

  const handleExecutionClick = useCallback((exec: AgentExecutionRecord, agentName?: string) => {
    if (sidePanel) {
      sidePanel.openTab({
        id: `exec-${exec.id}`,
        label: `${t('execution')} ${agentName || exec.model || exec.id.slice(0, 8)}`,
        icon: <MessageSquare className="h-3.5 w-3.5" />,
        content: <AgentExecutionConversation executionId={exec.id} systemPrompt={exec.systemPrompt} />,
      });
    }
  }, [sidePanel, t]);

  // Aggregate daily stats by date and re-bucket into the 3-category model:
  //   success = completedCount
  //   warning = cancelledCount (TIMEOUT/STOPPED_BY_USER/system-CANCELLED) + loopDetectedCount
  //   failure = failedCount (true errors only - backend already excludes partials)
  const chartData = useMemo(() => {
    const byDate = new Map<string, { date: string; success: number; warning: number; failure: number }>();
    for (const entry of dailyStats) {
      const success = entry.completedCount;
      const warning = (entry.cancelledCount ?? 0) + entry.loopDetectedCount;
      const failure = entry.failedCount;
      const existing = byDate.get(entry.executionDate);
      if (existing) {
        existing.success += success;
        existing.warning += warning;
        existing.failure += failure;
      } else {
        byDate.set(entry.executionDate, { date: entry.executionDate, success, warning, failure });
      }
    }
    return Array.from(byDate.values()).sort((a, b) => a.date.localeCompare(b.date));
  }, [dailyStats]);

  // Combined fleet + chat summary for the overview section
  const combinedSummary = useMemo(() => {
    if (!fleetSummary) return null;
    const chat = chatSummary;
    const totalExec = fleetSummary.totalExecutions + (chat?.totalExecutions ?? 0);
    const totalSuccess = fleetSummary.successCount + (chat?.successCount ?? 0);
    const totalDur = fleetSummary.totalDurationMs + (chat?.totalDurationMs ?? 0);
    return {
      totalExecutions: totalExec,
      totalTokensUsed: fleetSummary.totalTokensUsed + (chat?.totalTokensUsed ?? 0),
      totalCachedTokens: (fleetSummary.totalCachedTokens ?? 0) + (chat?.totalCachedTokens ?? 0),
      totalToolCalls: fleetSummary.totalToolCalls + (chat?.totalToolCalls ?? 0),
      totalCreditsConsumed: (fleetSummary.totalCreditsConsumed ?? 0) + (chat?.totalCreditsConsumed ?? 0),
      avgDurationMs: totalExec > 0 ? Math.round(totalDur / totalExec) : 0,
      successCount: totalSuccess,
      failureCount: fleetSummary.failureCount + (chat?.failureCount ?? 0),
      cancelledCount: fleetSummary.cancelledCount + (chat?.cancelledCount ?? 0),
      loopDetectedCount: fleetSummary.loopDetectedCount + (chat?.loopDetectedCount ?? 0),
      // warning = partial-outcome bucket: backend's cancelledCount currently bundles
      // TIMEOUT/STOPPED_BY_USER/system-CANCELLED, plus loop-detected runs.
      warningCount: (fleetSummary.cancelledCount + (chat?.cancelledCount ?? 0))
                  + (fleetSummary.loopDetectedCount + (chat?.loopDetectedCount ?? 0)),
      successRate: totalExec > 0 ? Math.round((totalSuccess * 100) / totalExec) : 0,
      totalAgents: fleetSummary.totalAgents,
    };
  }, [fleetSummary, chatSummary]);

  // Overview stats filtered by selected agent
  const overviewStats = useMemo(() => {
    if (!overviewAgentId) return combinedSummary;
    // Summaries keyed by virtual ID
    const summaryMap: Record<string, ChatSummary | null> = {
      '__chat__': chatSummary,
      '__classify__': classifySummary,
      '__guardrail__': guardrailSummary,
      '__browser_agent__': browserAgentSummary,
    };
    const selectedSummary = summaryMap[overviewAgentId];
    if (selectedSummary !== undefined) {
      if (!selectedSummary) return null;
      const totalExec = selectedSummary.totalExecutions;
      return {
        totalExecutions: totalExec,
        totalTokensUsed: selectedSummary.totalTokensUsed,
        totalCachedTokens: selectedSummary.totalCachedTokens ?? 0,
        totalToolCalls: selectedSummary.totalToolCalls,
        totalCreditsConsumed: selectedSummary.totalCreditsConsumed ?? 0,
        avgDurationMs: selectedSummary.avgDurationMs,
        successCount: selectedSummary.successCount,
        failureCount: selectedSummary.failureCount,
        cancelledCount: selectedSummary.cancelledCount,
        loopDetectedCount: selectedSummary.loopDetectedCount,
        warningCount: selectedSummary.cancelledCount + selectedSummary.loopDetectedCount,
        successRate: totalExec > 0 ? Math.round((selectedSummary.successCount * 100) / totalExec) : 0,
        totalAgents: 0,
      };
    }
    const agent = agents.find(a => a.id === overviewAgentId);
    if (!agent) return null;
    const totalExec = agent.totalExecutions ?? 0;
    const successCount = agent.successCount ?? 0;
    return {
      totalExecutions: totalExec,
      totalTokensUsed: agent.totalTokensUsed ?? 0,
      // The agents-table counter has no cached-token column; per-agent cached
      // tokens are not rolled up there, so default to 0 for this branch.
      totalCachedTokens: 0,
      totalToolCalls: agent.totalToolCalls ?? 0,
      totalCreditsConsumed: agent.creditsConsumed ?? 0,
      avgDurationMs: totalExec > 0 ? Math.round((agent.totalDurationMs ?? 0) / totalExec) : 0,
      successCount,
      failureCount: agent.failureCount ?? 0,
      cancelledCount: agent.cancelledCount ?? 0,
      loopDetectedCount: agent.loopDetectedCount ?? 0,
      warningCount: (agent.cancelledCount ?? 0) + (agent.loopDetectedCount ?? 0),
      successRate: totalExec > 0 ? Math.round((successCount * 100) / totalExec) : 0,
      totalAgents: 1,
    };
  }, [overviewAgentId, combinedSummary, chatSummary, classifySummary, guardrailSummary, browserAgentSummary, agents]);

  // Tool stats filtered by selected agent (API call)
  const loadFilteredToolStats = useCallback(async (id: string | null) => {
    setToolStatsAgentId(id);
    if (!id) { setFilteredToolStats(null); return; }
    setLoadingToolStats(true);
    try {
      if (id === '__chat__') {
        const raw = await agentService.getChatToolStats();
        setFilteredToolStats(raw.map(r => ({
          ...r,
          successRatePct: r.totalCalls > 0
            ? Math.round((r.successCount * 100) / r.totalCalls)
            : 0,
          executionCount: r.totalCalls,
          repeatCallCount: r.repeatCallCount ?? 0,
          lastUsedAt: r.lastUsedAt as unknown as string,
        })));
      } else {
        setFilteredToolStats(await agentService.getAgentToolStats(id));
      }
    } catch { setFilteredToolStats([]); }
    finally { setLoadingToolStats(false); }
  }, []);

  const displayToolStats = toolStatsAgentId ? (filteredToolStats ?? []) : toolStats;

  if (loading) {
    return (
      <div className="space-y-8 pt-4 px-2 sm:px-0">
        {/* Skeleton: Fleet overview */}
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="rounded-lg bg-theme-secondary/50 px-2.5 py-2.5 animate-pulse text-center">
              <div className="h-3.5 w-3.5 bg-theme-tertiary rounded mx-auto mb-1" />
              <div className="h-4 w-12 bg-theme-tertiary rounded mx-auto" />
              <div className="h-3 w-16 bg-theme-tertiary rounded mx-auto mt-0.5" />
            </div>
          ))}
        </div>
        {/* Skeleton: Table */}
        <div className="bg-theme-secondary rounded-xl p-4 animate-pulse space-y-3">
          <div className="h-5 w-40 bg-theme-tertiary rounded" />
          <div className="space-y-2">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="h-10 bg-theme-tertiary rounded" />
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-8 pt-4 px-2 sm:px-0">
      {/* Overview Section (combined fleet + chat) */}
      {combinedSummary && overviewStats && (
        <div className="space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center flex-shrink-0">
                <TrendingUp className="h-5 w-5 text-theme-primary" />
              </div>
              <div className="min-w-0">
                <h3 className="text-lg font-semibold text-theme-primary">{t('overview')}</h3>
                <p className="text-sm text-theme-secondary truncate">{t('overviewDesc')}</p>
              </div>
            </div>
            <Select
              value={overviewAgentId || '__all__'}
              onValueChange={(v) => setOverviewAgentId(v === '__all__' ? null : v)}
            >
              <SelectTrigger className="min-h-0 h-7 w-auto min-w-[120px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
                <SelectValue placeholder={t('allAgents')} />
              </SelectTrigger>
              <SelectContent className="rounded-lg">
                <SelectItem value="__all__" className="text-xs rounded-md py-1.5">
                  {t('allAgents')}
                </SelectItem>
                {chatSummary && chatSummary.totalExecutions > 0 && (
                  <SelectItem value="__chat__" className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <MessageSquare className="h-3.5 w-3.5" />
                      {t('generalChat')}
                    </span>
                  </SelectItem>
                )}
                {classifySummary && classifySummary.totalExecutions > 0 && (
                  <SelectItem value="__classify__" className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <Tag className="h-3.5 w-3.5" />
                      {t('classify')}
                    </span>
                  </SelectItem>
                )}
                {guardrailSummary && guardrailSummary.totalExecutions > 0 && (
                  <SelectItem value="__guardrail__" className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <Shield className="h-3.5 w-3.5" />
                      {t('guardrail')}
                    </span>
                  </SelectItem>
                )}
                {browserAgentSummary && browserAgentSummary.totalExecutions > 0 && (
                  <SelectItem value="__browser_agent__" className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <Globe className="h-3.5 w-3.5" />
                      {t('browserAgent')}
                    </span>
                  </SelectItem>
                )}
                {agents.map(a => (
                  <SelectItem key={a.id} value={a.id} className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-4 !h-4" />
                      {a.name}
                    </span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-2">
            <FleetStatCell
              icon={<Activity className="h-3.5 w-3.5" />}
              value={formatNumber(overviewStats.totalExecutions)}
              label={t('totalExecutions')}
            />
            <FleetStatCell
              icon={<Coins className="h-3.5 w-3.5" />}
              value={formatNumber(overviewStats.totalTokensUsed)}
              label={t('totalTokens')}
              subLabel={(overviewStats.totalCachedTokens ?? 0) > 0
                ? t('cachedTokensHint', { count: formatNumber(overviewStats.totalCachedTokens ?? 0) })
                : undefined}
            />
            <FleetStatCell
              icon={<Clock className="h-3.5 w-3.5" />}
              value={formatDuration(overviewStats.avgDurationMs)}
              label={t('avgDuration')}
            />
            <FleetStatCell
              icon={<Wrench className="h-3.5 w-3.5" />}
              value={formatNumber(overviewStats.totalToolCalls)}
              label={t('totalToolCalls')}
            />
            <FleetStatCell
              icon={<CheckCircle2 className="h-3.5 w-3.5" />}
              value={`${overviewStats.successRate}%`}
              label={t('successRate')}
            />
          </div>

          {/* 3-bucket overview: Success / Warning / Failure
              Warning aggregates the partial-outcome stop reasons that the backend
              currently bundles under cancelledCount (TIMEOUT, STOPPED_BY_USER,
              system CANCELLED) plus loopDetected runs. Failure stays as the strict
              "real error" bucket the backend already reports. */}
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-2">
            <FleetStatCell
              icon={<Bot className="h-3.5 w-3.5" />}
              value={formatNumber(overviewStats.totalAgents)}
              label={t('totalAgents')}
            />
            <FleetStatCell
              icon={<Coins className="h-3.5 w-3.5 text-amber-500" />}
              value={formatCostCompact(overviewStats.totalCreditsConsumed ?? 0)}
              valueTitle={formatCost(overviewStats.totalCreditsConsumed ?? 0, 4)}
              label={isCeMode ? t('totalCostConsumed') : t('totalCreditsConsumed')}
            />
            <FleetStatCell
              icon={<CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />}
              value={formatNumber(overviewStats.successCount)}
              label={t('successCount')}
            />
            <FleetStatCell
              icon={<AlertTriangle className="h-3.5 w-3.5 text-amber-500" />}
              value={formatNumber(overviewStats.warningCount)}
              label={t('warningCount')}
            />
            <FleetStatCell
              icon={<AlertCircle className="h-3.5 w-3.5 text-red-500" />}
              value={formatNumber(overviewStats.failureCount)}
              label={t('failureCount')}
            />
          </div>
        </div>
      )}

      {/* Per-Agent Table */}
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center">
            <Activity className="h-5 w-5 text-theme-primary" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-theme-primary">{t('agentPerformance')}</h3>
            <p className="text-sm text-theme-secondary">{t('agentPerformanceDesc')}</p>
          </div>
        </div>

        <div className="overflow-x-auto rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700/50 -mx-2 sm:mx-0">
          <table className="w-full table-fixed" style={{ borderSpacing: '0', minWidth: '800px' }}>
            <colgroup>
              <col className="w-[20%]" />
              <col className="w-[8%]" />
              <col className="w-[10%]" />
              <col className="w-[8%]" />
              <col className="w-[8%]" />
              <col className="w-[16%]" />
              <col className="w-[10%]" />
              <col className="w-[8%]" />
              <col className="w-[12%]" />
            </colgroup>
            <thead>
              <tr className="bg-theme-secondary border-b border-slate-200 dark:border-slate-700/50">
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('agent')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('executions')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('tokens')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('toolCallsCol')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{isCeMode ? t('costCol') : t('creditsCol')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm truncate">{t('modelCol')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('duration')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('success')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('lastExecution')}</th>
              </tr>
            </thead>
            <tbody>
              {/* General Chat row */}
              {chatSummary && chatSummary.totalExecutions > 0 && (() => {
                const chatSuccessRate = chatSummary.totalExecutions > 0
                  ? Math.round((chatSummary.successCount * 100) / chatSummary.totalExecutions)
                  : 0;
                const isChatExpanded = expandedAgentId === '__chat__';
                return (
                  <React.Fragment key="__chat__">
                    <tr
                      onClick={handleExpandChat}
                      className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150 cursor-pointer"
                    >
                      <td className="px-4 py-2.5 text-sm text-theme-primary">
                        <div className="flex items-center gap-2 min-w-0">
                          {isChatExpanded ? (
                            <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
                          ) : (
                            <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
                          )}
                          <div className="w-8 h-8 bg-theme-tertiary rounded-full flex items-center justify-center flex-shrink-0">
                            <MessageSquare className="h-3.5 w-3.5 text-theme-primary" />
                          </div>
                          <span className="font-medium truncate">{t('generalChat')}</span>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">
                        {formatNumber(chatSummary.totalExecutions)}
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">
                        {formatNumber(chatSummary.totalTokensUsed)}
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">
                        {formatNumber(chatSummary.totalToolCalls)}
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums" title={formatCost(chatSummary.totalCreditsConsumed ?? 0, 4)}>
                        {formatCostCompact(chatSummary.totalCreditsConsumed ?? 0)}
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary">
                        {chatModel ? (
                          <div className="min-w-0">
                            <span className="truncate block">{chatModel.name}</span>
                            {chatModel.provider && (
                              <span className="text-xs text-theme-muted truncate block">{chatModel.provider}</span>
                            )}
                          </div>
                        ) : (
                          <span className="text-theme-muted">-</span>
                        )}
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted text-right">
                        {formatDuration(chatSummary.avgDurationMs)}
                      </td>
                      <td className="px-4 py-2.5 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          <div className="w-12 h-1.5 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden">
                            <div
                              className="h-full rounded-full bg-emerald-500"
                              style={{ width: `${chatSuccessRate}%` }}
                            />
                          </div>
                          <span className="text-xs text-theme-muted tabular-nums w-8 text-right">
                            {chatSuccessRate}%
                          </span>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-xs text-theme-muted text-right">
                        {chatSummary.lastExecutionAt
                          ? formatRel(chatSummary.lastExecutionAt)
                          : t('never')}
                      </td>
                    </tr>

                    {/* Expanded: skeleton loading */}
                    {isChatExpanded && loadingChatExecutions && (
                      <tr>
                        <td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                          <div className="animate-pulse space-y-2">
                            <div className="h-3 w-32 bg-theme-tertiary rounded" />
                            {Array.from({ length: 3 }).map((_, i) => (
                              <div key={i} className="flex items-center gap-3 px-3 py-2">
                                <div className="h-3.5 w-3.5 bg-theme-tertiary rounded-full" />
                                <div className="h-3 w-16 bg-theme-tertiary rounded" />
                                <div className="h-3 w-24 bg-theme-tertiary rounded flex-1" />
                                <div className="h-3 w-12 bg-theme-tertiary rounded" />
                                <div className="h-3 w-12 bg-theme-tertiary rounded" />
                              </div>
                            ))}
                          </div>
                        </td>
                      </tr>
                    )}

                    {/* Expanded: recent executions */}
                    {isChatExpanded && !loadingChatExecutions && chatExecutions && (
                      <tr>
                        <td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                          <div className="w-full overflow-hidden">
                          <h4 className="text-xs font-semibold text-theme-muted uppercase tracking-wider mb-2">
                            {t('recentExecutions')}
                          </h4>
                          {chatExecutions.content.length === 0 ? (
                            <p className="text-sm text-theme-muted">{t('noMetrics')}</p>
                          ) : (
                            <div className="space-y-1 overflow-x-auto">
                              {/* Header row */}
                              <div className="grid grid-cols-[72px_1fr_48px_48px_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-1">
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">ID</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">{t('model')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('iter')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('tools')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('tokensCol')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{isCeMode ? t('costCol') : t('creditsCol')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('duration')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('date')}</span>
                              </div>
                              {chatExecutions.content.map(exec => (
                                <button
                                  key={exec.id}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    handleExecutionClick(exec, t('generalChat'));
                                  }}
                                  className="w-full text-left"
                                >
                                  <div className="grid grid-cols-[72px_1fr_48px_48px_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-2 rounded-lg hover:bg-theme-secondary transition-colors">
                                    <div className="flex items-center gap-1.5 min-w-0">
                                      {/* SINGLE status icon for the row - synthesised from stopReason
                                          when present, otherwise derived from status. The model column no
                                          longer renders a duplicate badge so users see exactly one icon
                                          per execution and its colour is always consistent with the row. */}
                                      <StopReasonBadge
                                        stopReason={resolveRowStopReason(exec)}
                                        scope={exec.budgetScope}
                                      />
                                      <span className="text-xs text-theme-muted font-mono truncate">
                                        {exec.id.slice(0, 8)}
                                      </span>
                                    </div>
                                    <div className="flex items-center gap-1.5 min-w-0 overflow-hidden">
                                      <span className="text-sm text-theme-primary truncate">
                                        {exec.model || exec.provider}
                                      </span>
                                    </div>
                                    <span className="text-xs text-theme-muted tabular-nums text-right">
                                      {exec.iterationCount}
                                    </span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right">
                                      {exec.totalToolCalls}
                                    </span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right">
                                      {formatNumber(exec.totalTokens)}
                                      {(exec.totalCachedTokens ?? 0) > 0 ? (
                                        <span className="block text-[10px] text-theme-muted/70 leading-tight">{t('cachedTokensHint', { count: formatNumber(exec.totalCachedTokens) })}</span>
                                      ) : null}
                                    </span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right" title={formatCost(exec.creditsConsumed ?? 0, 4)}>
                                      {formatCostCompact(exec.creditsConsumed ?? 0)}
                                    </span>
                                    <span className="text-xs text-theme-muted text-right">
                                      {exec.durationMs != null ? formatDuration(exec.durationMs) : '-'}
                                    </span>
                                    <span className="text-xs text-theme-muted text-right" title={formatFullDate(exec.startedAt)}>
                                      {formatStartedAt(exec.startedAt)}
                                    </span>
                                  </div>
                                </button>
                              ))}
                              {/* Pagination controls */}
                              {chatExecutions.totalPages > 1 && (
                                <div className="flex items-center justify-between px-3 pt-2">
                                  <button
                                    onClick={(e) => { e.stopPropagation(); loadChatExecutionsPage(chatExecutions.number - 1); }}
                                    disabled={chatExecutions.first || loadingChatExecutions}
                                    className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                                  >
                                    <ChevronLeft className="h-3.5 w-3.5" />
                                    {t('prev')}
                                  </button>
                                  <span className="text-xs text-theme-muted">
                                    {t('pageInfo', { current: chatExecutions.number + 1, total: chatExecutions.totalPages })}
                                  </span>
                                  <button
                                    onClick={(e) => { e.stopPropagation(); loadChatExecutionsPage(chatExecutions.number + 1); }}
                                    disabled={chatExecutions.last || loadingChatExecutions}
                                    className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                                  >
                                    {t('next')}
                                    <ChevronRight className="h-3.5 w-3.5" />
                                  </button>
                                </div>
                              )}
                            </div>
                          )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })()}

              {/* Classify row */}
              {classifySummary && classifySummary.totalExecutions > 0 && (() => {
                const rate = classifySummary.totalExecutions > 0
                  ? Math.round((classifySummary.successCount * 100) / classifySummary.totalExecutions) : 0;
                const isExp = expandedAgentId === '__classify__';
                return (
                  <React.Fragment key="__classify__">
                    <tr onClick={handleExpandClassify}
                        className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150 cursor-pointer">
                      <td className="px-4 py-2.5 text-sm text-theme-primary">
                        <div className="flex items-center gap-2 min-w-0">
                          {isExp ? <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" /> : <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />}
                          <div className="w-8 h-8 bg-purple-100 dark:bg-purple-900/30 rounded-full flex items-center justify-center flex-shrink-0">
                            <Tag className="h-3.5 w-3.5 text-purple-600 dark:text-purple-400" />
                          </div>
                          <span className="font-medium truncate">{t('classify')}</span>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">{formatNumber(classifySummary.totalExecutions)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">{formatNumber(classifySummary.totalTokensUsed)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted text-right">-</td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums" title={formatCost(classifySummary.totalCreditsConsumed ?? 0, 4)}>{formatCostCompact(classifySummary.totalCreditsConsumed ?? 0)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted">-</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted text-right">{formatDuration(classifySummary.avgDurationMs)}</td>
                      <td className="px-4 py-2.5 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          <div className="w-12 h-1.5 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden">
                            <div className="h-full rounded-full bg-emerald-500" style={{ width: `${rate}%` }} />
                          </div>
                          <span className="text-xs text-theme-muted tabular-nums w-8 text-right">{rate}%</span>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-xs text-theme-muted text-right">
                        {classifySummary.lastExecutionAt ? formatRel(classifySummary.lastExecutionAt) : t('never')}
                      </td>
                    </tr>
                    {isExp && loadingClassifyExecutions && (
                      <tr><td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                        <div className="animate-pulse space-y-2">
                          <div className="h-3 w-32 bg-theme-tertiary rounded" />
                          {Array.from({ length: 3 }).map((_, i) => (
                            <div key={i} className="flex items-center gap-3 px-3 py-2">
                              <div className="h-3.5 w-3.5 bg-theme-tertiary rounded-full" />
                              <div className="h-3 w-16 bg-theme-tertiary rounded" />
                              <div className="h-3 w-24 bg-theme-tertiary rounded flex-1" />
                              <div className="h-3 w-12 bg-theme-tertiary rounded" />
                            </div>
                          ))}
                        </div>
                      </td></tr>
                    )}
                    {isExp && !loadingClassifyExecutions && classifyExecutions && (
                      <tr><td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                        <div className="w-full overflow-hidden">
                          <h4 className="text-xs font-semibold text-theme-muted uppercase tracking-wider mb-2">{t('recentExecutions')}</h4>
                          {classifyExecutions.content.length === 0 ? (
                            <p className="text-sm text-theme-muted">{t('noMetrics')}</p>
                          ) : (
                            <div className="space-y-1 overflow-x-auto">
                              <div className="grid grid-cols-[72px_1fr_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-1">
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">ID</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">{t('model')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('tokensCol')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{isCeMode ? t('costCol') : t('creditsCol')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('duration')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('date')}</span>
                              </div>
                              {classifyExecutions.content.map(exec => (
                                <button key={exec.id} onClick={(e) => { e.stopPropagation(); handleExecutionClick(exec, t('classify')); }} className="w-full text-left">
                                  <div className="grid grid-cols-[72px_1fr_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-2 rounded-lg hover:bg-theme-secondary transition-colors">
                                    <div className="flex items-center gap-1.5 min-w-0">
                                      <StopReasonBadge stopReason={resolveRowStopReason(exec)} scope={exec.budgetScope} />
                                      <span className="text-xs text-theme-muted font-mono truncate">{exec.id.slice(0, 8)}</span>
                                    </div>
                                    <span className="text-sm text-theme-primary truncate">{exec.model || exec.provider}</span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right">{formatNumber(exec.totalTokens)}{(exec.totalCachedTokens ?? 0) > 0 ? <span className="block text-[10px] text-theme-muted/70 leading-tight">{t('cachedTokensHint', { count: formatNumber(exec.totalCachedTokens) })}</span> : null}</span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right" title={formatCost(exec.creditsConsumed ?? 0, 4)}>{formatCostCompact(exec.creditsConsumed ?? 0)}</span>
                                    <span className="text-xs text-theme-muted text-right">{exec.durationMs != null ? formatDuration(exec.durationMs) : '-'}</span>
                                    <span className="text-xs text-theme-muted text-right" title={formatFullDate(exec.startedAt)}>{formatStartedAt(exec.startedAt)}</span>
                                  </div>
                                </button>
                              ))}
                              {classifyExecutions.totalPages > 1 && (
                                <div className="flex items-center justify-between px-3 pt-2">
                                  <button onClick={() => loadClassifyExecutionsPage(classifyExecutions.number - 1)} disabled={classifyExecutions.first || loadingClassifyExecutions}
                                    className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
                                    <ChevronLeft className="h-3.5 w-3.5" />{t('prev')}
                                  </button>
                                  <span className="text-xs text-theme-muted">{t('pageInfo', { current: classifyExecutions.number + 1, total: classifyExecutions.totalPages })}</span>
                                  <button onClick={() => loadClassifyExecutionsPage(classifyExecutions.number + 1)} disabled={classifyExecutions.last || loadingClassifyExecutions}
                                    className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
                                    {t('next')}<ChevronRight className="h-3.5 w-3.5" />
                                  </button>
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      </td></tr>
                    )}
                  </React.Fragment>
                );
              })()}

              {/* Guardrail row */}
              {guardrailSummary && guardrailSummary.totalExecutions > 0 && (() => {
                const rate = guardrailSummary.totalExecutions > 0
                  ? Math.round((guardrailSummary.successCount * 100) / guardrailSummary.totalExecutions) : 0;
                const isExp = expandedAgentId === '__guardrail__';
                return (
                  <React.Fragment key="__guardrail__">
                    <tr onClick={handleExpandGuardrail}
                        className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150 cursor-pointer">
                      <td className="px-4 py-2.5 text-sm text-theme-primary">
                        <div className="flex items-center gap-2 min-w-0">
                          {isExp ? <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" /> : <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />}
                          <div className="w-8 h-8 bg-amber-100 dark:bg-amber-900/30 rounded-full flex items-center justify-center flex-shrink-0">
                            <Shield className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400" />
                          </div>
                          <span className="font-medium truncate">{t('guardrail')}</span>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">{formatNumber(guardrailSummary.totalExecutions)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">{formatNumber(guardrailSummary.totalTokensUsed)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted text-right">-</td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums" title={formatCost(guardrailSummary.totalCreditsConsumed ?? 0, 4)}>{formatCostCompact(guardrailSummary.totalCreditsConsumed ?? 0)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted">-</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted text-right">{formatDuration(guardrailSummary.avgDurationMs)}</td>
                      <td className="px-4 py-2.5 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          <div className="w-12 h-1.5 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden">
                            <div className="h-full rounded-full bg-emerald-500" style={{ width: `${rate}%` }} />
                          </div>
                          <span className="text-xs text-theme-muted tabular-nums w-8 text-right">{rate}%</span>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-xs text-theme-muted text-right">
                        {guardrailSummary.lastExecutionAt ? formatRel(guardrailSummary.lastExecutionAt) : t('never')}
                      </td>
                    </tr>
                    {isExp && loadingGuardrailExecutions && (
                      <tr><td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                        <div className="animate-pulse space-y-2">
                          <div className="h-3 w-32 bg-theme-tertiary rounded" />
                          {Array.from({ length: 3 }).map((_, i) => (
                            <div key={i} className="flex items-center gap-3 px-3 py-2">
                              <div className="h-3.5 w-3.5 bg-theme-tertiary rounded-full" />
                              <div className="h-3 w-16 bg-theme-tertiary rounded" />
                              <div className="h-3 w-24 bg-theme-tertiary rounded flex-1" />
                              <div className="h-3 w-12 bg-theme-tertiary rounded" />
                            </div>
                          ))}
                        </div>
                      </td></tr>
                    )}
                    {isExp && !loadingGuardrailExecutions && guardrailExecutions && (
                      <tr><td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                        <div className="w-full overflow-hidden">
                          <h4 className="text-xs font-semibold text-theme-muted uppercase tracking-wider mb-2">{t('recentExecutions')}</h4>
                          {guardrailExecutions.content.length === 0 ? (
                            <p className="text-sm text-theme-muted">{t('noMetrics')}</p>
                          ) : (
                            <div className="space-y-1 overflow-x-auto">
                              <div className="grid grid-cols-[72px_1fr_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-1">
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">ID</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">{t('model')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('tokensCol')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{isCeMode ? t('costCol') : t('creditsCol')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('duration')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('date')}</span>
                              </div>
                              {guardrailExecutions.content.map(exec => (
                                <button key={exec.id} onClick={(e) => { e.stopPropagation(); handleExecutionClick(exec, t('guardrail')); }} className="w-full text-left">
                                  <div className="grid grid-cols-[72px_1fr_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-2 rounded-lg hover:bg-theme-secondary transition-colors">
                                    <div className="flex items-center gap-1.5 min-w-0">
                                      <StopReasonBadge stopReason={resolveRowStopReason(exec)} scope={exec.budgetScope} />
                                      <span className="text-xs text-theme-muted font-mono truncate">{exec.id.slice(0, 8)}</span>
                                    </div>
                                    <span className="text-sm text-theme-primary truncate">{exec.model || exec.provider}</span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right">{formatNumber(exec.totalTokens)}{(exec.totalCachedTokens ?? 0) > 0 ? <span className="block text-[10px] text-theme-muted/70 leading-tight">{t('cachedTokensHint', { count: formatNumber(exec.totalCachedTokens) })}</span> : null}</span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right" title={formatCost(exec.creditsConsumed ?? 0, 4)}>{formatCostCompact(exec.creditsConsumed ?? 0)}</span>
                                    <span className="text-xs text-theme-muted text-right">{exec.durationMs != null ? formatDuration(exec.durationMs) : '-'}</span>
                                    <span className="text-xs text-theme-muted text-right" title={formatFullDate(exec.startedAt)}>{formatStartedAt(exec.startedAt)}</span>
                                  </div>
                                </button>
                              ))}
                              {guardrailExecutions.totalPages > 1 && (
                                <div className="flex items-center justify-between px-3 pt-2">
                                  <button onClick={() => loadGuardrailExecutionsPage(guardrailExecutions.number - 1)} disabled={guardrailExecutions.first || loadingGuardrailExecutions}
                                    className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
                                    <ChevronLeft className="h-3.5 w-3.5" />{t('prev')}
                                  </button>
                                  <span className="text-xs text-theme-muted">{t('pageInfo', { current: guardrailExecutions.number + 1, total: guardrailExecutions.totalPages })}</span>
                                  <button onClick={() => loadGuardrailExecutionsPage(guardrailExecutions.number + 1)} disabled={guardrailExecutions.last || loadingGuardrailExecutions}
                                    className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
                                    {t('next')}<ChevronRight className="h-3.5 w-3.5" />
                                  </button>
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      </td></tr>
                    )}
                  </React.Fragment>
                );
              })()}

              {/* Browser Agent row - mirrors Classify/Guardrail.
                  TODO(browser_agent): per-agent drill-down (sessions list) is
                  intentionally skipped for v1. The drill-down still lists raw
                  AgentExecutionRecord rows, which is enough for usage/budget
                  triage; per-session timeline UI lands with PR #6 backend. */}
              {browserAgentSummary && browserAgentSummary.totalExecutions > 0 && (() => {
                const rate = browserAgentSummary.totalExecutions > 0
                  ? Math.round((browserAgentSummary.successCount * 100) / browserAgentSummary.totalExecutions) : 0;
                const isExp = expandedAgentId === '__browser_agent__';
                return (
                  <React.Fragment key="__browser_agent__">
                    <tr onClick={handleExpandBrowserAgent}
                        className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150 cursor-pointer">
                      <td className="px-4 py-2.5 text-sm text-theme-primary">
                        <div className="flex items-center gap-2 min-w-0">
                          {isExp ? <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" /> : <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />}
                          <div className="w-8 h-8 bg-blue-100 dark:bg-blue-900/30 rounded-full flex items-center justify-center flex-shrink-0">
                            <Globe className="h-3.5 w-3.5 text-blue-600 dark:text-blue-400" />
                          </div>
                          <span className="font-medium truncate">{t('browserAgent')}</span>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">{formatNumber(browserAgentSummary.totalExecutions)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">{formatNumber(browserAgentSummary.totalTokensUsed)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted text-right">-</td>
                      <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums" title={formatCost(browserAgentSummary.totalCreditsConsumed ?? 0, 4)}>{formatCostCompact(browserAgentSummary.totalCreditsConsumed ?? 0)}</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted">-</td>
                      <td className="px-4 py-2.5 text-sm text-theme-muted text-right">{formatDuration(browserAgentSummary.avgDurationMs)}</td>
                      <td className="px-4 py-2.5 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          <div className="w-12 h-1.5 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden">
                            <div className="h-full rounded-full bg-emerald-500" style={{ width: `${rate}%` }} />
                          </div>
                          <span className="text-xs text-theme-muted tabular-nums w-8 text-right">{rate}%</span>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-xs text-theme-muted text-right">
                        {browserAgentSummary.lastExecutionAt ? formatRel(browserAgentSummary.lastExecutionAt) : t('never')}
                      </td>
                    </tr>
                    {isExp && loadingBrowserAgentExecutions && (
                      <tr><td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                        <div className="animate-pulse space-y-2">
                          <div className="h-3 w-32 bg-theme-tertiary rounded" />
                          {Array.from({ length: 3 }).map((_, i) => (
                            <div key={i} className="flex items-center gap-3 px-3 py-2">
                              <div className="h-3.5 w-3.5 bg-theme-tertiary rounded-full" />
                              <div className="h-3 w-16 bg-theme-tertiary rounded" />
                              <div className="h-3 w-24 bg-theme-tertiary rounded flex-1" />
                              <div className="h-3 w-12 bg-theme-tertiary rounded" />
                            </div>
                          ))}
                        </div>
                      </td></tr>
                    )}
                    {isExp && !loadingBrowserAgentExecutions && browserAgentExecutions && (
                      <tr><td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                        <div className="w-full overflow-hidden">
                          <h4 className="text-xs font-semibold text-theme-muted uppercase tracking-wider mb-2">{t('recentExecutions')}</h4>
                          {browserAgentExecutions.content.length === 0 ? (
                            <p className="text-sm text-theme-muted">{t('noMetrics')}</p>
                          ) : (
                            <div className="space-y-1 overflow-x-auto">
                              <div className="grid grid-cols-[72px_1fr_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-1">
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">ID</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">{t('model')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('tokensCol')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{isCeMode ? t('costCol') : t('creditsCol')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('duration')}</span>
                                <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('date')}</span>
                              </div>
                              {browserAgentExecutions.content.map(exec => (
                                <button key={exec.id} onClick={(e) => { e.stopPropagation(); handleExecutionClick(exec, t('browserAgent')); }} className="w-full text-left">
                                  <div className="grid grid-cols-[72px_1fr_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-2 rounded-lg hover:bg-theme-secondary transition-colors">
                                    <div className="flex items-center gap-1.5 min-w-0">
                                      <StopReasonBadge stopReason={resolveRowStopReason(exec)} scope={exec.budgetScope} />
                                      <span className="text-xs text-theme-muted font-mono truncate">{exec.id.slice(0, 8)}</span>
                                    </div>
                                    <span className="text-sm text-theme-primary truncate">{exec.model || exec.provider}</span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right">{formatNumber(exec.totalTokens)}{(exec.totalCachedTokens ?? 0) > 0 ? <span className="block text-[10px] text-theme-muted/70 leading-tight">{t('cachedTokensHint', { count: formatNumber(exec.totalCachedTokens) })}</span> : null}</span>
                                    <span className="text-xs text-theme-muted tabular-nums text-right" title={formatCost(exec.creditsConsumed ?? 0, 4)}>{formatCostCompact(exec.creditsConsumed ?? 0)}</span>
                                    <span className="text-xs text-theme-muted text-right">{exec.durationMs != null ? formatDuration(exec.durationMs) : '-'}</span>
                                    <span className="text-xs text-theme-muted text-right" title={formatFullDate(exec.startedAt)}>{formatStartedAt(exec.startedAt)}</span>
                                  </div>
                                </button>
                              ))}
                              {browserAgentExecutions.totalPages > 1 && (
                                <div className="flex items-center justify-between px-3 pt-2">
                                  <button onClick={() => loadBrowserAgentExecutionsPage(browserAgentExecutions.number - 1)} disabled={browserAgentExecutions.first || loadingBrowserAgentExecutions}
                                    className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
                                    <ChevronLeft className="h-3.5 w-3.5" />{t('prev')}
                                  </button>
                                  <span className="text-xs text-theme-muted">{t('pageInfo', { current: browserAgentExecutions.number + 1, total: browserAgentExecutions.totalPages })}</span>
                                  <button onClick={() => loadBrowserAgentExecutionsPage(browserAgentExecutions.number + 1)} disabled={browserAgentExecutions.last || loadingBrowserAgentExecutions}
                                    className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
                                    {t('next')}<ChevronRight className="h-3.5 w-3.5" />
                                  </button>
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      </td></tr>
                    )}
                  </React.Fragment>
                );
              })()}

              {agents.length === 0 && !(chatSummary && chatSummary.totalExecutions > 0) ? (
                <tr>
                  <td colSpan={9} className="text-center py-8 text-sm text-theme-muted">{t('noMetrics')}</td>
                </tr>
              ) : (
                agents.map(agent => {
                  const totalExec = agent.totalExecutions ?? 0;
                  const successRate = totalExec > 0
                    ? Math.round(((agent.successCount ?? 0) * 100) / totalExec)
                    : 0;
                  const avgDur = totalExec > 0
                    ? Math.round((agent.totalDurationMs ?? 0) / totalExec)
                    : 0;
                  const isExpanded = expandedAgentId === agent.id;

                  return (
                    <React.Fragment key={agent.id}>
                      <tr
                        onClick={() => handleExpandAgent(agent.id)}
                        className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150 cursor-pointer"
                      >
                        <td className="px-4 py-2.5 text-sm text-theme-primary">
                          <div className="flex items-center gap-2 min-w-0">
                            {isExpanded ? (
                              <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
                            ) : (
                              <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
                            )}
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                handleOpenAgentPanel(agent);
                              }}
                              className="flex-shrink-0 rounded-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                              aria-label={`Open ${agent.name} details`}
                            >
                              <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" />
                            </button>
                            <span className="font-medium truncate">{agent.name}</span>
                          </div>
                        </td>
                        <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">
                          {formatNumber(totalExec)}
                        </td>
                        <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">
                          {formatNumber(agent.totalTokensUsed ?? 0)}
                        </td>
                        <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">
                          {formatNumber(agent.totalToolCalls ?? 0)}
                        </td>
                        <td className="px-4 py-2.5 text-sm text-right">
                          {agent.creditBudget != null ? (() => {
                            // Gauge + percentage (mirrors success-rate styling) with a hover title
                            // carrying the precise 4-decimal figures - four decimals matches the
                            // backend NUMERIC(19,4) storage. `reserved` is the transient in-flight
                            // counter: non-zero means a descendant is still running
                            // (§4.2 AGENT_BUDGET_HIERARCHY.md); it's shown as a stacked amber
                            // segment on top of the emerald consumed bar.
                            const consumed = agent.creditsConsumed ?? 0;
                            const reserved = agent.creditsReserved ?? 0;
                            const total = agent.creditBudget;
                            const over = (consumed + reserved) >= total;
                            const pct = total > 0 ? Math.min(100, Math.round((consumed / total) * 100)) : 0;
                            const reservedPct = total > 0 ? Math.min(100 - pct, Math.round((reserved / total) * 100)) : 0;
                            const title = `${t('creditsUsed')}: ${formatCost(consumed, 4)} · ${t('creditsReservedLabel')}: ${formatCost(reserved, 4)} / ${formatCost(total, 4)}`;
                            return (
                              <div className="flex items-center justify-end gap-1.5" title={title}>
                                <div className="w-12 h-1.5 flex rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden">
                                  <div
                                    className={cn('h-full', over ? 'bg-red-500' : 'bg-emerald-500')}
                                    style={{ width: `${pct}%` }}
                                  />
                                  {reservedPct > 0 && (
                                    <div
                                      className="h-full bg-amber-500"
                                      style={{ width: `${reservedPct}%` }}
                                    />
                                  )}
                                </div>
                                <span className={cn(
                                  'text-xs tabular-nums w-9 text-right',
                                  over ? 'text-red-500 font-medium' : 'text-theme-muted'
                                )}>
                                  {pct}%
                                </span>
                              </div>
                            );
                          })() : (
                            <span
                              className="text-xs tabular-nums text-theme-primary"
                              title={`${t('creditsUsed')}: ${formatCost(agent.creditsConsumed ?? 0, 4)} · ${t('noLimit')}`}
                            >
                              {formatCostCompact(agent.creditsConsumed ?? 0)}
                            </span>
                          )}
                        </td>
                        <td className="px-4 py-2.5 text-sm text-theme-primary">
                          {agent.modelName ? (
                            <div className="min-w-0">
                              <span className="truncate block">{agent.modelName}</span>
                              {agent.modelProvider && (
                                <span className="text-xs text-theme-muted truncate block">{agent.modelProvider}</span>
                              )}
                            </div>
                          ) : (
                            <span className="text-theme-muted">-</span>
                          )}
                        </td>
                        <td className="px-4 py-2.5 text-sm text-theme-muted text-right">
                          {formatDuration(avgDur)}
                        </td>
                        <td className="px-4 py-2.5 text-right">
                          <div className="flex items-center justify-end gap-1.5">
                            <div className="w-12 h-1.5 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden">
                              <div
                                className="h-full rounded-full bg-emerald-500"
                                style={{ width: `${successRate}%` }}
                              />
                            </div>
                            <span className="text-xs text-theme-muted tabular-nums w-8 text-right">
                              {successRate}%
                            </span>
                          </div>
                        </td>
                        <td className="px-4 py-2.5 text-xs text-theme-muted text-right">
                          {agent.lastExecutionAt
                            ? formatRel(agent.lastExecutionAt)
                            : t('never')}
                        </td>
                      </tr>

                      {/* Expanded: skeleton loading */}
                      {isExpanded && loadingExecutions && (
                        <tr>
                          <td colSpan={9} className="bg-theme-secondary/30 px-6 py-3 border-b border-slate-200 dark:border-slate-700/50">
                            <div className="animate-pulse space-y-2">
                              <div className="h-3 w-32 bg-theme-tertiary rounded" />
                              {Array.from({ length: 3 }).map((_, i) => (
                                <div key={i} className="flex items-center gap-3 px-3 py-2">
                                  <div className="h-3.5 w-3.5 bg-theme-tertiary rounded-full" />
                                  <div className="h-3 w-16 bg-theme-tertiary rounded" />
                                  <div className="h-3 w-24 bg-theme-tertiary rounded flex-1" />
                                  <div className="h-3 w-12 bg-theme-tertiary rounded" />
                                  <div className="h-3 w-12 bg-theme-tertiary rounded" />
                                </div>
                              ))}
                            </div>
                          </td>
                        </tr>
                      )}

                      {/* Expanded: recent executions */}
                      {isExpanded && !loadingExecutions && agentExecutions && (
                        <tr>
                          <td colSpan={9} className="bg-theme-secondary/30 px-0 py-3 border-b border-slate-200 dark:border-slate-700/50 max-w-0">
                            <div className="px-6">
                            <h4 className="text-xs font-semibold text-theme-muted uppercase tracking-wider mb-2">
                              {t('recentExecutions')}
                            </h4>
                            {agentExecutions.content.length === 0 ? (
                              <p className="text-sm text-theme-muted">{t('noMetrics')}</p>
                            ) : (
                              <div className="space-y-1 overflow-x-auto">
                                {/* Header row */}
                                <div className="grid grid-cols-[72px_1fr_48px_48px_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-1">
                                  <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">ID</span>
                                  <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider">{t('model')}</span>
                                  <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('iter')}</span>
                                  <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('tools')}</span>
                                  <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('tokensCol')}</span>
                                  <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{isCeMode ? t('costCol') : t('creditsCol')}</span>
                                  <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('duration')}</span>
                                  <span className="text-[11px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('date')}</span>
                                </div>
                                {agentExecutions.content.map(exec => (
                                  <button
                                    key={exec.id}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      handleExecutionClick(exec, agent.name);
                                    }}
                                    className="w-full text-left"
                                  >
                                    <div className="grid grid-cols-[72px_1fr_48px_48px_60px_60px_64px_96px] w-full overflow-hidden items-center gap-x-3 px-3 py-2 rounded-lg hover:bg-theme-secondary transition-colors">
                                      <div className="flex items-center gap-1.5 min-w-0">
                                        <StopReasonBadge
                                          stopReason={resolveRowStopReason(exec)}
                                          scope={exec.budgetScope}
                                        />
                                        <span className="text-xs text-theme-muted font-mono truncate">
                                          {exec.id.slice(0, 8)}
                                        </span>
                                      </div>
                                      <div className="flex items-center gap-1.5 min-w-0 overflow-hidden">
                                        <span className="text-sm text-theme-primary truncate">
                                          {exec.model || exec.provider}
                                        </span>
                                        {exec.source && (
                                          <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-slate-100 dark:bg-slate-700/50 text-theme-muted truncate">
                                            {exec.source}
                                          </span>
                                        )}
                                        {exec.agentType && exec.agentType !== 'agent' && (
                                          <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400 truncate">
                                            {exec.agentType}
                                          </span>
                                        )}
                                        {exec.depth > 0 && exec.callerAgentEntityId && (() => {
                                          const parentAgent = agents.find(a => a.id === exec.callerAgentEntityId);
                                          return (
                                            <span className="inline-flex items-center gap-0.5 text-[10px] text-theme-muted flex-shrink-0" title={parentAgent ? `${t('launchedBy')} ${parentAgent.name}` : exec.callerAgentEntityId}>
                                              <AvatarDisplay avatarUrl={parentAgent?.avatarUrl} name={parentAgent?.name} size="sm" className="!w-4 !h-4" />
                                            </span>
                                          );
                                        })()}
                                      </div>
                                      <span className="text-xs text-theme-muted tabular-nums text-right">
                                        {exec.iterationCount}
                                      </span>
                                      <span className="text-xs text-theme-muted tabular-nums text-right">
                                        {exec.totalToolCalls}
                                      </span>
                                      <span className="text-xs text-theme-muted tabular-nums text-right">
                                        {formatNumber(exec.totalTokens)}
                                        {(exec.totalCachedTokens ?? 0) > 0 ? (
                                          <span className="block text-[10px] text-theme-muted/70 leading-tight">{t('cachedTokensHint', { count: formatNumber(exec.totalCachedTokens) })}</span>
                                        ) : null}
                                      </span>
                                      <span className="text-xs text-theme-muted tabular-nums text-right" title={formatCost(exec.creditsConsumed ?? 0, 4)}>
                                        {formatCostCompact(exec.creditsConsumed ?? 0)}
                                      </span>
                                      <span className="text-xs text-theme-muted text-right">
                                        {exec.durationMs != null ? formatDuration(exec.durationMs) : '-'}
                                      </span>
                                      <span className="text-xs text-theme-muted text-right" title={formatFullDate(exec.startedAt)}>
                                        {formatStartedAt(exec.startedAt)}
                                      </span>
                                    </div>
                                    {exec.errorMessage && (
                                      <div className="px-3 pb-1 pl-8">
                                        <span className="text-xs text-red-500 truncate block max-w-md">
                                          {exec.errorMessage.length > 60
                                            ? `${exec.errorMessage.slice(0, 60)}…`
                                            : exec.errorMessage}
                                        </span>
                                      </div>
                                    )}
                                  </button>
                                ))}
                                {/* Pagination controls */}
                                {agentExecutions.totalPages > 1 && (
                                  <div className="flex items-center justify-between px-3 pt-2">
                                    <button
                                      onClick={(e) => { e.stopPropagation(); loadAgentExecutionsPage(agent.id, agentExecutions.number - 1); }}
                                      disabled={agentExecutions.first || loadingExecutions}
                                      className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                                    >
                                      <ChevronLeft className="h-3.5 w-3.5" />
                                      {t('prev')}
                                    </button>
                                    <span className="text-xs text-theme-muted">
                                      {t('pageInfo', { current: agentExecutions.number + 1, total: agentExecutions.totalPages })}
                                    </span>
                                    <button
                                      onClick={(e) => { e.stopPropagation(); loadAgentExecutionsPage(agent.id, agentExecutions.number + 1); }}
                                      disabled={agentExecutions.last || loadingExecutions}
                                      className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                                    >
                                      {t('next')}
                                      <ChevronRight className="h-3.5 w-3.5" />
                                    </button>
                                  </div>
                                )}
                              </div>
                            )}
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Daily Activity Chart */}
      <div className="space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center flex-shrink-0">
              <BarChart3 className="h-5 w-5 text-theme-primary" />
            </div>
            <div className="min-w-0">
              <h3 className="text-lg font-semibold text-theme-primary">{t('dailyActivity')}</h3>
              <p className="text-sm text-theme-secondary truncate">{t('dailyActivityDesc')}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Select
              value={chartAgentId || '__all__'}
              onValueChange={(v) => setChartAgentId(v === '__all__' ? null : v)}
            >
              <SelectTrigger className="min-h-0 h-7 w-auto min-w-[120px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
                <SelectValue placeholder={t('allAgents')} />
              </SelectTrigger>
              <SelectContent className="rounded-lg">
                <SelectItem value="__all__" className="text-xs rounded-md py-1.5">
                  {t('allAgents')}
                </SelectItem>
                {chatSummary && chatSummary.totalExecutions > 0 && (
                  <SelectItem value="__chat__" className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <MessageSquare className="h-3.5 w-3.5" />
                      {t('generalChat')}
                    </span>
                  </SelectItem>
                )}
                {agents.map(a => (
                  <SelectItem key={a.id} value={a.id} className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-4 !h-4" />
                      {a.name}
                    </span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <div className="flex gap-1 bg-[var(--bg-primary)] rounded-lg p-0.5 border border-slate-200 dark:border-slate-700/50">
              {[7, 30, 90].map(d => (
                <button
                  key={d}
                  onClick={() => setChartPeriod(d)}
                  className={cn(
                    'px-3 py-1 text-xs rounded-md transition-colors',
                    chartPeriod === d
                      ? 'bg-[var(--bg-tertiary)] text-theme-primary font-medium'
                      : 'text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-secondary)]'
                  )}
                >
                  {t(`period${d}d`)}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="bg-theme-secondary rounded-xl p-4 border border-slate-200 dark:border-slate-700/50 relative">
          {loadingChart && (
            <div className="absolute inset-0 bg-theme-secondary/60 rounded-xl flex items-center justify-center z-10">
              <div className="h-5 w-5 border-2 border-theme-muted border-t-theme-primary rounded-full animate-spin" />
            </div>
          )}
          {chartData.length === 0 && !loadingChart ? (
            <div className="flex items-center justify-center h-48 text-sm text-theme-muted">
              {t('noChartData')}
            </div>
          ) : (
            <ResponsiveContainer width="100%" height={280}>
              <BarChart data={chartData} margin={{ top: 5, right: 5, left: -15, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={chartColours.tooltipBorder} opacity={0.5} />
                <XAxis
                  dataKey="date"
                  tick={{ fontSize: 11, fill: chartColours.tooltipText }}
                  tickFormatter={(v: string) => {
                    // UTC daily bucket - match the backend DATE(started_at AT TIME ZONE 'UTC').
                    const d = new Date(v);
                    return `${d.getUTCDate()}/${d.getUTCMonth() + 1}`;
                  }}
                  stroke={chartColours.axisStroke}
                />
                <YAxis
                  allowDecimals={false}
                  tick={{ fontSize: 11, fill: chartColours.tooltipText }}
                  stroke={chartColours.axisStroke}
                />
                <Tooltip
                  cursor={{ fill: chartColours.cursorFill }}
                  contentStyle={{
                    backgroundColor: chartColours.tooltipBg,
                    border: `1px solid ${chartColours.tooltipBorder}`,
                    borderRadius: '8px',
                    fontSize: '12px',
                    color: chartColours.tooltipText,
                    boxShadow: isDark
                      ? '0 4px 12px rgba(0,0,0,0.4)'
                      : '0 4px 12px rgba(15,23,42,0.08)',
                  }}
                  itemStyle={{ color: chartColours.tooltipText }}
                  labelStyle={{ color: chartColours.tooltipText, fontWeight: 600, marginBottom: 4 }}
                  labelFormatter={(v: string) => formatUtcDate(v)}
                />
                <Legend wrapperStyle={{ fontSize: '12px', color: chartColours.tooltipText }} />
                {/* 3-bucket stack: success (green) → warning (amber) → failure (red).
                    Warning combines partial-outcome reasons (cancelled bucket already
                    contains TIMEOUT/STOPPED_BY_USER) and loop-detected runs. */}
                <Bar
                  dataKey="success"
                  name={t('successCount')}
                  stackId="a"
                  fill="#10b981"
                  radius={[0, 0, 0, 0]}
                />
                <Bar
                  dataKey="warning"
                  name={t('warningCount')}
                  stackId="a"
                  fill="#f59e0b"
                  radius={[0, 0, 0, 0]}
                />
                <Bar
                  dataKey="failure"
                  name={t('failureCount')}
                  stackId="a"
                  fill="#ef4444"
                  radius={[4, 4, 0, 0]}
                />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      {/* Tool Stats */}
      {(toolStats.length > 0 || toolStatsAgentId) && (
        <div className="space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-center gap-3 flex-shrink-0 min-w-0">
              <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center flex-shrink-0">
                <Wrench className="h-5 w-5 text-theme-primary" />
              </div>
              <div className="min-w-0">
                <h3 className="text-lg font-semibold text-theme-primary truncate">{t('toolStats')}</h3>
                <p className="text-sm text-theme-secondary truncate">{t('toolStatsDesc')}</p>
              </div>
            </div>
            <Select
              value={toolStatsAgentId || '__all__'}
              onValueChange={(v) => loadFilteredToolStats(v === '__all__' ? null : v)}
            >
              <SelectTrigger className="min-h-0 h-7 w-auto min-w-[120px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
                <SelectValue placeholder={t('allAgents')} />
              </SelectTrigger>
              <SelectContent className="rounded-lg">
                <SelectItem value="__all__" className="text-xs rounded-md py-1.5">
                  {t('allAgents')}
                </SelectItem>
                {chatSummary && chatSummary.totalExecutions > 0 && (
                  <SelectItem value="__chat__" className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <MessageSquare className="h-3.5 w-3.5" />
                      {t('generalChat')}
                    </span>
                  </SelectItem>
                )}
                {agents.map(a => (
                  <SelectItem key={a.id} value={a.id} className="text-xs rounded-md py-1.5">
                    <span className="flex items-center gap-2">
                      <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-4 !h-4" />
                      {a.name}
                    </span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="overflow-x-auto rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700/50 relative">
            {loadingToolStats && (
              <div className="absolute inset-0 bg-theme-secondary/60 rounded-xl flex items-center justify-center z-10">
                <div className="h-5 w-5 border-2 border-theme-muted border-t-theme-primary rounded-full animate-spin" />
              </div>
            )}
            <table className="min-w-full" style={{ borderSpacing: '0' }}>
              <thead>
                <tr className="bg-theme-secondary border-b border-slate-200 dark:border-slate-700/50">
                  <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('tool')}</th>
                  <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('calls')}</th>
                  <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('successRatePct')}</th>
                  <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('avgDurationMs')}</th>
                  <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('repeatCalls')}</th>
                  <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('maxDuration')}</th>
                  <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('lastUsed')}</th>
                </tr>
              </thead>
              <tbody>
                {displayToolStats.length === 0 && !loadingToolStats ? (
                  <tr>
                    <td colSpan={7} className="text-center py-8 text-sm text-theme-muted">{t('noMetrics')}</td>
                  </tr>
                ) : displayToolStats.slice(0, 10).map(ts => (
                  <tr
                    key={ts.toolName}
                    className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150"
                  >
                    <td className="px-4 py-2.5 text-sm text-theme-primary truncate">{ts.toolName}</td>
                    <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">{ts.totalCalls}</td>
                    <td className="px-4 py-2.5 text-sm text-theme-primary text-right tabular-nums">{ts.successRatePct}%</td>
                    <td className="px-4 py-2.5 text-sm text-theme-muted text-right">{formatDuration(ts.avgDurationMs)}</td>
                    <td className={cn(
                      'px-4 py-2.5 text-sm text-right tabular-nums',
                      ts.repeatCallCount > 0 ? 'text-amber-600' : 'text-theme-muted'
                    )}>
                      {ts.repeatCallCount}
                    </td>
                    <td className="px-4 py-2.5 text-sm text-theme-muted text-right">
                      {ts.maxDurationMs != null ? formatDuration(ts.maxDurationMs) : '-'}
                    </td>
                    <td className="px-4 py-2.5 text-xs text-theme-muted text-right">
                      {ts.lastUsedAt ? formatRel(ts.lastUsedAt) : '-'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

function FleetStatCell({ icon, value, label, subLabel, valueTitle }: { icon: React.ReactNode; value: string; label: string; subLabel?: string; valueTitle?: string }) {
  return (
    <div className="rounded-lg bg-theme-secondary/50 px-2.5 py-2.5 text-center">
      <div className="flex items-center justify-center gap-1 text-theme-muted mb-1">
        {icon}
      </div>
      <p className="text-sm font-semibold text-theme-primary tabular-nums leading-tight" title={valueTitle}>{value}</p>
      <p className="text-xs text-theme-muted leading-tight mt-0.5">{label}</p>
      {subLabel ? (
        <p className="text-xs text-theme-muted/70 leading-tight mt-0.5 tabular-nums">{subLabel}</p>
      ) : null}
    </div>
  );
}

function formatNumber(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return n.toString();
}

// Set of stopReason values defined in the contract. Anything outside this set
// (legacy bridge values like "end_turn", "stop_sequence", "tool_use", null) is
// considered "unknown" and we fall back to the execution status.
const KNOWN_STOP_REASONS = new Set([
  'COMPLETED', 'MAX_ITERATIONS', 'TIMEOUT', 'BUDGET_EXHAUSTED',
  'LOOP_DETECTED', 'STOPPED_BY_USER', 'CANCELLED', 'NO_TOOLS', 'ERROR',
]);

/**
 * Resolve the stopReason to display for one execution row.
 *
 * The execution status is the authoritative outcome flag (COMPLETED / FAILED /
 * CANCELLED / RUNNING / PENDING). The stopReason is only displayed when it's
 * a known contract value AND it agrees with the status - otherwise we synthesise
 * one from the status. This avoids the legacy-data bug where rows persisted
 * before the contract refactor carry stopReason values like "end_turn" that
 * parseStopReason maps to ERROR, painting successful runs red.
 */
function resolveRowStopReason(exec: { status: string; stopReason?: string | null; loopDetected?: boolean; errorMessage?: string | null }): string {
  // When a recognised stopReason is present it always wins - it carries more
  // detail than the coarse status. PARTIAL outcomes (BUDGET_EXHAUSTED,
  // MAX_ITERATIONS, TIMEOUT, LOOP_DETECTED) are stored as status=COMPLETED
  // but should display their specific stopReason badge, not a green checkmark.
  if (exec.stopReason && KNOWN_STOP_REASONS.has(exec.stopReason)) {
    return exec.stopReason;
  }

  // No usable stopReason - fall back to status-based synthesis.
  if (exec.status === 'COMPLETED') return 'COMPLETED';
  if (exec.status === 'CANCELLED') return 'CANCELLED';
  if (exec.loopDetected) return 'LOOP_DETECTED';
  if (exec.status === 'FAILED') {
    // Heuristic: legacy rows (pre stop-cascade contract) where the user
    // cancelled mid-stream have status=FAILED with errorMessage="Streaming
    // error: …" or "cancelled". Display them as STOPPED_BY_USER (warning,
    // hand icon) rather than ERROR (failure, red) so users don't see their
    // own STOP clicks reported as platform errors.
    const msg = (exec.errorMessage || '').toLowerCase();
    if (msg.includes('cancel') || msg.includes('stopped by user') || msg.includes('stream stopped')) {
      return 'STOPPED_BY_USER';
    }
    return 'ERROR';
  }
  // Non-terminal (RUNNING / PENDING) and no usable stopReason → no icon.
  return '';
}

function formatDuration(ms: number): string {
  if (ms >= 60_000) return `${(ms / 60_000).toFixed(1)}m`;
  if (ms >= 1_000) return `${(ms / 1_000).toFixed(1)}s`;
  return `${ms}ms`;
}

// All absolute timestamps are rendered in UTC project-wide. Relative branches
// stay timezone-neutral; absolute fallbacks go through formatUtcDate / formatUtcTime
// so the user sees a consistent " UTC" suffix on every page.

function formatStartedAt(dateStr: string): string {
  // Always UTC. Short form for "today in UTC", full form otherwise. The
  // " UTC" suffix is preserved so users in any local zone read the same value.
  const date = parseUtcAware(dateStr);
  const nowUtcDay = new Date().toISOString().slice(0, 10);
  const dateUtcDay = date.toISOString().slice(0, 10);
  if (nowUtcDay === dateUtcDay) {
    return formatUtcTime(date);
  }
  return formatUtcDateTime(date);
}

function formatFullDate(dateStr: string): string {
  return formatUtcDateTime(dateStr, { withSeconds: true });
}
