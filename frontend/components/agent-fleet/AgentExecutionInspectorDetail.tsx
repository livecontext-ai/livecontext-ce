'use client';

import React, { useState, useMemo, useEffect } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import {
  CheckCircle2,
  AlertCircle,
  XCircle,
  Clock,
  Cpu,
  MessageSquare,
  Wrench,
  ChevronDown,
  ChevronRight,
  AlertTriangle,
  Zap,
  Brain,
  Database,
  GitBranch,
  FileText,
  Hash,
  Coins,
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { StatusBadge } from '@/components/ui/StatusBadge';
import { useAgentExecution } from './hooks/useAgentExecutionData';
import { useExecutionPagedResource } from '@/hooks/agent/useExecutionPagedResource';
import { LoadOlderSentinel } from '@/components/agent-fleet/LoadOlderSentinel';
import type {
  AgentExecutionRecord,
  AgentExecutionMessage,
  AgentExecutionToolCall,
} from '@/lib/api/orchestrator/agent-metrics.types';
import type { Agent } from '@/lib/api/orchestrator/types';
import { AvatarDisplay } from '@/components/agents/AvatarPicker';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { formatDuration, formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { formatCost, isCeMode } from '@/lib/format-cost';
import { StopReasonBadge } from '@/components/agents/StopReasonBadge';
import { isPartial, type AgentStopReason } from '@/types/agentStopReason';

// Set of stopReason values defined in the contract - anything else is treated
// as legacy data and ignored so old "end_turn" / "stop_sequence" values don't
// paint successful runs red via parseStopReason's ERROR fallback.
const KNOWN_STOP_REASONS = new Set([
  'COMPLETED', 'MAX_ITERATIONS', 'TIMEOUT', 'BUDGET_EXHAUSTED',
  'LOOP_DETECTED', 'STOPPED_BY_USER', 'CANCELLED', 'NO_TOOLS', 'ERROR',
]);

interface AgentExecutionInspectorDetailProps {
  executionId: string;
  agents?: Agent[];
}

const mapExecStatus = (status: string): 'completed' | 'failed' | 'running' | 'pending' | 'cancelled' => {
  const map: Record<string, 'completed' | 'failed' | 'running' | 'pending' | 'cancelled'> = {
    COMPLETED: 'completed',
    FAILED: 'failed',
    RUNNING: 'running',
    PENDING: 'pending',
    CANCELLED: 'cancelled',
  };
  return map[status] || 'pending';
};

function formatTokenCount(n: number | undefined | null): string {
  if (n == null || n === 0) return '0';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return n.toLocaleString(getClientLocale());
}

function formatTimestamp(ts?: string | null): string {
  if (!ts) return '';
  try {
    return formatUtcDateTime(ts);
  } catch {
    return ts;
  }
}

/**
 * Execution detail view - single scrollable layout with collapsible sections.
 */
export function AgentExecutionInspectorDetail({ executionId, agents = [] }: AgentExecutionInspectorDetailProps) {
  const t = useTranslations('fleetInspector');

  const { data: execution, isLoading: loadingExec } = useAgentExecution(executionId);
  // Lazy-load: page 0 = newest 30, scroll-up sentinel triggers older pages. Two independent
  // paginations because tool-call payloads carry MB-sized request/response bodies and the
  // conversation table can grow into thousands of rows on long agent loops.
  const toolCallsRes = useExecutionPagedResource<AgentExecutionToolCall>(
    executionId,
    agentService.getExecutionToolCallsPaged.bind(agentService),
  );
  const conversationRes = useExecutionPagedResource<AgentExecutionMessage>(
    executionId,
    agentService.getExecutionConversationPaged.bind(agentService),
  );
  const toolCalls = toolCallsRes.items;
  const conversation = conversationRes.items;

  if (loadingExec) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="animate-spin h-5 w-5 border-2 border-slate-300 border-t-slate-600 rounded-full" />
      </div>
    );
  }

  if (!execution) {
    return (
      <p className="text-sm text-theme-muted text-center py-8">{t('noExecutionSelected')}</p>
    );
  }

  const successRate = execution.totalToolCalls > 0
    ? Math.round((execution.successfulToolCalls / execution.totalToolCalls) * 100)
    : null;

  return (
    <div className="flex flex-col h-full overflow-y-auto px-4 py-3 space-y-4">
      {/* ── Header: Status + stopReason + model + source (centered) ── */}
      <div className="flex items-center justify-center gap-2 flex-wrap">
        {(() => {
          if (execution.status === 'RUNNING') {
            return <Clock className="h-3.5 w-3.5 text-blue-500 animate-spin" />;
          }
          if (execution.status === 'COMPLETED') {
            return <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />;
          }
          if (execution.stopReason === 'CANCELLED' || execution.status === 'CANCELLED') {
            return <XCircle className="h-3.5 w-3.5 text-purple-500" />;
          }
          if (execution.stopReason && isPartial(execution.stopReason as AgentStopReason)) {
            return <AlertTriangle className="h-3.5 w-3.5 text-amber-500" />;
          }
          return <AlertCircle className="h-3.5 w-3.5 text-red-500" />;
        })()}
        {/* Stop-reason badge: tells the user *why* the run terminated.
            Only shown when the persisted stopReason is a known contract value
            AND not plain COMPLETED. Legacy values ("end_turn", "stop_sequence")
            from before the contract refactor are ignored - the leading status
            icon already conveys success/failure. */}
        {execution.stopReason
          && execution.stopReason !== 'COMPLETED'
          && KNOWN_STOP_REASONS.has(execution.stopReason)
          && (
            <StopReasonBadge
              stopReason={execution.stopReason}
              scope={execution.budgetScope}
              showLabel
            />
          )}
        {execution.model && (
          <span className="text-sm font-medium text-theme-primary">{execution.model}</span>
        )}
        {execution.source && (
          <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-slate-100 dark:bg-slate-700/50 text-theme-muted flex-shrink-0">
            {execution.source}
          </span>
        )}
        {execution.agentType && execution.agentType !== 'agent' && (
          <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400 flex-shrink-0">
            {execution.agentType}
          </span>
        )}
      </div>

      {/* ── Error alert (prominent, at the top) ── */}
      {execution.errorMessage && (
        <div className="flex gap-2 p-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800">
          <AlertCircle className="h-4 w-4 text-red-500 flex-shrink-0 mt-0.5" />
          <pre className="text-sm text-red-700 dark:text-red-400 whitespace-pre-wrap break-words flex-1">
            {execution.errorMessage}
          </pre>
        </div>
      )}

      {/* ── Loop warning ── */}
      {execution.loopDetected && (
        <div className="flex items-center gap-2 p-2.5 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800">
          <AlertTriangle className="h-4 w-4 text-amber-500 flex-shrink-0" />
          <span className="text-sm text-amber-700 dark:text-amber-400">
            {t('loopDetected')}
            {execution.loopType && ` (${execution.loopType})`}
            {execution.loopToolName && ` - ${execution.loopToolName}`}
          </span>
        </div>
      )}

      {/* ── Key metrics row ── */}
      <div className="grid gap-2 grid-cols-5">
        <StatCell
          icon={<Clock className="h-3.5 w-3.5" />}
          value={formatDuration(execution.durationMs) || '-'}
          label={t('duration')}
        />
        <StatCell
          icon={<Zap className="h-3.5 w-3.5" />}
          value={formatTokenCount(execution.totalTokens)}
          label={t('tokens')}
        />
        <StatCell
          icon={<Wrench className="h-3.5 w-3.5" />}
          value={`${execution.successfulToolCalls}/${execution.totalToolCalls}`}
          label={t('toolCalls')}
        />
        <StatCell
          icon={<CheckCircle2 className="h-3.5 w-3.5" />}
          value={successRate != null ? `${successRate}%` : '-'}
          label={t('successRate')}
        />
        <StatCell
          icon={<Coins className="h-3.5 w-3.5" />}
          value={formatCost(execution.creditsConsumed ?? 0, 4)}
          label={isCeMode ? t('costLabel') : t('credits')}
        />
      </div>

      {/* ── Context row: timestamps, iterations, messages ── */}
      <div className="flex items-center gap-3 text-sm text-theme-muted flex-wrap">
        {execution.startedAt && (
          <span>{formatTimestamp(execution.startedAt)}</span>
        )}
        {execution.iterationCount > 0 && (
          <>
            <span className="text-theme-muted/30">|</span>
            <span>{execution.iterationCount} {t('iterations').toLowerCase()}</span>
          </>
        )}
        {execution.messageCount > 0 && (
          <>
            <span className="text-theme-muted/30">|</span>
            <span>{execution.messageCount} msgs</span>
          </>
        )}
      </div>

      {/* ── Token breakdown (collapsible) ── */}
      {execution.totalTokens > 0 && (
        <CollapsibleSection
          title={t('tokenBreakdown')}
          icon={<Zap className="h-3.5 w-3.5" />}
          count={formatTokenCount(execution.totalTokens)}
        >
          <div className="grid grid-cols-2 gap-1.5">
            <TokenRow label={t('promptTokens')} value={execution.totalPromptTokens} />
            <TokenRow label={t('completionTokens')} value={execution.totalCompletionTokens} />
            {(execution.totalReasoningTokens ?? 0) > 0 && (
              <TokenRow label={t('reasoningTokens')} value={execution.totalReasoningTokens} icon={<Brain className="h-3.5 w-3.5 text-violet-500" />} />
            )}
            {(execution.totalCachedTokens ?? 0) > 0 && (
              <TokenRow label={t('cachedTokens')} value={execution.totalCachedTokens} icon={<Database className="h-3.5 w-3.5 text-cyan-500" />} />
            )}
            {(execution.totalCacheCreationTokens ?? 0) > 0 && (
              <TokenRow label={t('cacheCreationTokens')} value={execution.totalCacheCreationTokens} icon={<Database className="h-3.5 w-3.5 text-cyan-500" />} />
            )}
            {(execution.totalCacheReadTokens ?? 0) > 0 && (
              <TokenRow label={t('cacheReadTokens')} value={execution.totalCacheReadTokens} icon={<Database className="h-3.5 w-3.5 text-cyan-500" />} />
            )}
          </div>
        </CollapsibleSection>
      )}

      {/* ── Tool calls (collapsible) ── */}
      <CollapsibleSection
        title={t('toolCalls')}
        icon={<Wrench className="h-3.5 w-3.5" />}
        count={execution.totalToolCalls > 0 ? String(execution.totalToolCalls) : undefined}
      >
        <LoadOlderSentinel
          hasMore={toolCallsRes.hasMore}
          loading={toolCallsRes.loadingOlder}
          onLoadOlder={toolCallsRes.loadOlder}
        />
        {toolCalls && toolCalls.length > 0 ? (
          <ToolCallsList toolCalls={toolCalls} t={t} />
        ) : (
          <p className="text-sm text-theme-muted py-2">{t('noToolCalls')}</p>
        )}
      </CollapsibleSection>

      {/* ── Conversation (collapsible) ── */}
      <CollapsibleSection
        title={t('conversation')}
        icon={<MessageSquare className="h-3.5 w-3.5" />}
        count={execution.messageCount > 0 ? String(execution.messageCount) : undefined}
      >
        <LoadOlderSentinel
          hasMore={conversationRes.hasMore}
          loading={conversationRes.loadingOlder}
          onLoadOlder={conversationRes.loadOlder}
        />
        {conversation && conversation.length > 0 ? (
          <ConversationView messages={conversation} t={t} />
        ) : (
          <p className="text-sm text-theme-muted py-2">{t('noConversation')}</p>
        )}
      </CollapsibleSection>

      {/* ── Configuration (collapsible) ── */}
      {(execution.model || execution.temperature != null || execution.maxTokensConfig != null || execution.maxIterationsConfig != null) && (
        <CollapsibleSection
          title={t('configuration')}
          icon={<Cpu className="h-3.5 w-3.5" />}
        >
          <div className="space-y-1.5">
            {execution.model && (
              <ConfigRow label={t('model')} value={`${execution.model}${execution.provider ? ` (${execution.provider})` : ''}`} />
            )}
            {execution.temperature != null && (
              <ConfigRow label={t('temperature')} value={String(execution.temperature)} />
            )}
            {execution.maxTokensConfig != null && (
              <ConfigRow label={t('maxTokens')} value={execution.maxTokensConfig.toLocaleString(getClientLocale())} />
            )}
            {execution.maxIterationsConfig != null && (
              <ConfigRow label={t('maxIter')} value={String(execution.maxIterationsConfig)} />
            )}
          </div>
        </CollapsibleSection>
      )}

      {/* ── Launched by (parent agent) ── */}
      {execution.callerAgentEntityId && (
        <LaunchedBySection
          callerAgentEntityId={execution.callerAgentEntityId}
          agents={agents}
          t={t}
        />
      )}

      {/* ── System prompt (collapsible) ── */}
      {execution.systemPrompt && (
        <CollapsibleSection
          title={t('systemPromptLabel')}
          icon={<FileText className="h-3.5 w-3.5" />}
        >
          <pre className="text-sm font-mono bg-theme-secondary/50 rounded p-2.5 max-h-48 overflow-y-auto whitespace-pre-wrap break-words text-theme-primary">
            {execution.systemPrompt}
          </pre>
        </CollapsibleSection>
      )}
    </div>
  );
}

// ─── Launched by sub-component (fetches agent if not in local list) ───

function LaunchedBySection({
  callerAgentEntityId,
  agents,
  t,
}: {
  callerAgentEntityId: string;
  agents: Agent[];
  t: ReturnType<typeof useTranslations>;
}) {
  const localAgent = agents.find(a => a.id === callerAgentEntityId);
  const [fetchedAgent, setFetchedAgent] = useState<Agent | null>(null);

  useEffect(() => {
    if (localAgent || !callerAgentEntityId) return;
    let cancelled = false;
    agentService.getAgent(callerAgentEntityId).then(agent => {
      if (!cancelled) setFetchedAgent(agent);
    }).catch(() => {
      // Agent may have been deleted - keep null
    });
    return () => { cancelled = true; };
  }, [callerAgentEntityId, localAgent]);

  const callerAgent = localAgent || fetchedAgent;

  return (
    <CollapsibleSection
      title={t('launchedBy')}
      icon={<GitBranch className="h-3.5 w-3.5" />}
      count={callerAgent?.name || callerAgentEntityId.slice(0, 8)}
    >
      <div className="flex items-center gap-3">
        {callerAgent?.avatarUrl ? (
          <AvatarDisplay avatarUrl={callerAgent.avatarUrl} name={callerAgent.name} size="md" />
        ) : (
          <div className="h-10 w-10 rounded-full bg-indigo-100 dark:bg-indigo-900/30 flex items-center justify-center flex-shrink-0">
            <GitBranch className="h-5 w-5 text-indigo-500" />
          </div>
        )}
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-theme-primary">
            {callerAgent?.name || t('unknownAgent')}
          </p>
          <p className="text-xs font-mono text-theme-muted truncate">
            {callerAgentEntityId}
          </p>
        </div>
      </div>
    </CollapsibleSection>
  );
}

// ─── Shared sub-components ───

function StatCell({ icon, value, label }: { icon: React.ReactNode; value: string; label: string }) {
  return (
    <div className="rounded-lg bg-theme-secondary/50 px-2.5 py-2.5 text-center">
      <div className="flex items-center justify-center gap-1 text-theme-muted mb-1">
        {icon}
      </div>
      <p className="text-sm font-semibold text-theme-primary tabular-nums leading-tight">{value}</p>
      <p className="text-xs text-theme-muted leading-tight mt-0.5">{label}</p>
    </div>
  );
}

function TokenRow({ label, value, icon }: { label: string; value: number | undefined | null; icon?: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between px-2.5 py-1.5 rounded bg-theme-secondary/30">
      <div className="flex items-center gap-1.5">
        {icon}
        <span className="text-sm text-theme-muted">{label}</span>
      </div>
      <span className="text-sm font-semibold text-theme-primary tabular-nums">
        {(value ?? 0).toLocaleString(getClientLocale())}
      </span>
    </div>
  );
}

function ConfigRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between px-2.5 py-1.5 rounded bg-theme-secondary/30">
      <span className="text-sm text-theme-muted">{label}</span>
      <span className="text-sm font-medium text-theme-primary truncate ml-2">{value}</span>
    </div>
  );
}

function CollapsibleSection({
  title,
  icon,
  count,
  children,
  defaultOpen = false,
}: {
  title: string;
  icon: React.ReactNode;
  count?: string;
  children: React.ReactNode;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="rounded-lg border border-theme overflow-hidden">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center gap-2 px-3 py-2.5 hover:bg-theme-secondary/30 transition-colors"
      >
        {open ? (
          <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
        ) : (
          <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
        )}
        <span className="text-theme-muted">{icon}</span>
        <span className="text-sm font-semibold text-theme-primary">{title}</span>
        {count && (
          <span className="text-sm text-theme-muted tabular-nums ml-auto">{count}</span>
        )}
      </button>
      {open && (
        <div className="px-3 pb-3 pt-1">
          {children}
        </div>
      )}
    </div>
  );
}

// ─── Tool Calls List (flat, compact) ───

function ToolCallsList({
  toolCalls,
  t,
}: {
  toolCalls: AgentExecutionToolCall[];
  t: ReturnType<typeof useTranslations>;
}) {
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());

  const groupedByIteration = useMemo(() => {
    const groups = new Map<number, AgentExecutionToolCall[]>();
    for (const tc of toolCalls) {
      const iter = tc.iterationNumber ?? 0;
      if (!groups.has(iter)) groups.set(iter, []);
      groups.get(iter)!.push(tc);
    }
    return [...groups.entries()].sort(([a], [b]) => a - b);
  }, [toolCalls]);

  const toggleExpand = (id: number) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  return (
    <div className="space-y-3">
      {groupedByIteration.map(([iterNum, calls]) => (
        <div key={iterNum}>
          {groupedByIteration.length > 1 && (
            <div className="flex items-center gap-1.5 mb-1.5">
              <Hash className="h-3.5 w-3.5 text-theme-muted" />
              <span className="text-xs font-semibold text-theme-muted uppercase">
                {t('iteration')} {iterNum}
              </span>
            </div>
          )}
          <div className="space-y-1">
            {calls.map(tc => {
              const expanded = expandedIds.has(tc.id);
              return (
                <div key={tc.id}>
                  <button
                    onClick={() => toggleExpand(tc.id)}
                    className="w-full flex items-center gap-2 px-2.5 py-2 rounded-md hover:bg-theme-secondary/50 transition-colors text-left"
                  >
                    {tc.success ? (
                      <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500 flex-shrink-0" />
                    ) : (
                      <AlertCircle className="h-3.5 w-3.5 text-red-500 flex-shrink-0" />
                    )}
                    <span className="text-sm font-medium text-theme-primary truncate flex-1">
                      {tc.toolName}
                    </span>
                    {tc.isRepeat && (
                      <span className="text-xs text-amber-600 dark:text-amber-400 tabular-nums">
                        x{tc.consecutiveCount}
                      </span>
                    )}
                    {tc.durationMs != null && (
                      <span className="text-xs text-theme-muted tabular-nums">
                        {formatDuration(tc.durationMs)}
                      </span>
                    )}
                    {expanded ? (
                      <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
                    ) : (
                      <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
                    )}
                  </button>
                  {expanded && (
                    <div className="ml-6 mr-1 mb-2 space-y-2 border-l-2 border-theme pl-3 py-1.5">
                      {tc.arguments && Object.keys(tc.arguments).length > 0 && (
                        <div>
                          <span className="text-xs font-semibold text-theme-muted uppercase">{t('arguments')}</span>
                          <pre className="text-sm font-mono bg-theme-secondary rounded-xl p-2 mt-1 overflow-x-auto max-h-40 overflow-y-auto">
                            {JSON.stringify(tc.arguments, null, 2)}
                          </pre>
                        </div>
                      )}
                      {tc.content && (
                        <div>
                          <span className="text-xs font-semibold text-theme-muted uppercase">{t('output')}</span>
                          <pre className="text-sm font-mono bg-theme-secondary rounded-xl p-2 mt-1 overflow-x-auto max-h-40 overflow-y-auto whitespace-pre-wrap break-words">
                            {tc.content}
                          </pre>
                        </div>
                      )}
                      {tc.errorMessage && (
                        <div>
                          <span className="text-xs font-semibold text-red-500 uppercase">{t('error')}</span>
                          <pre className="text-sm font-mono bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 rounded p-2 mt-1">
                            {tc.errorMessage}
                          </pre>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Conversation View ───

function ConversationView({
  messages,
  t,
}: {
  messages: AgentExecutionMessage[];
  t: ReturnType<typeof useTranslations>;
}) {
  const visibleMessages = messages.filter(m => m.role !== 'SYSTEM');

  if (visibleMessages.length === 0) {
    return <p className="text-sm text-theme-muted py-2">{t('noConversation')}</p>;
  }

  const roleColors: Record<string, string> = {
    USER: 'border-l-blue-400',
    ASSISTANT: 'border-l-emerald-400',
    TOOL: 'border-l-amber-400',
  };

  const roleLabels: Record<string, string> = {
    USER: 'User',
    ASSISTANT: 'Assistant',
    TOOL: 'Tool',
  };

  return (
    <div className="space-y-2.5">
      {visibleMessages.map(msg => (
        <div
          key={msg.id}
          className={cn(
            'border-l-2 pl-3 py-1.5',
            roleColors[msg.role] || 'border-l-slate-300',
          )}
        >
          <div className="flex items-center gap-2 mb-1">
            <span className="text-xs font-semibold uppercase text-theme-muted">
              {roleLabels[msg.role] || msg.role}
            </span>
            {msg.toolName && (
              <span className="text-xs font-mono text-theme-muted">
                {msg.toolName}
              </span>
            )}
          </div>
          {msg.content && (
            <p className="text-sm text-theme-primary whitespace-pre-wrap break-words leading-relaxed max-h-48 overflow-y-auto">
              {msg.content}
            </p>
          )}
          {msg.toolCallsRequested && msg.toolCallsRequested.length > 0 && (
            <div className="mt-1.5 space-y-0.5">
              {msg.toolCallsRequested.map((tc: any, i: number) => (
                <div key={i} className="text-sm text-theme-primary">
                  {tc.toolName}({Object.keys(tc.arguments || {}).join(', ')})
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
