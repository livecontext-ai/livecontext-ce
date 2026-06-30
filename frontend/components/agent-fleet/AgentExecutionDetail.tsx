'use client';

import React, { useState } from 'react';
import { MessageSquare, Wrench, ChevronDown, ChevronRight, AlertCircle, CheckCircle2, Clock } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import type { AgentExecutionMessage, AgentExecutionToolCall } from '@/lib/api/orchestrator/agent-metrics.types';
import { useExecutionPagedResource } from '@/hooks/agent/useExecutionPagedResource';
import { LoadOlderSentinel } from '@/components/agent-fleet/LoadOlderSentinel';
import { cn } from '@/lib/utils';

interface AgentExecutionConversationProps {
  executionId: string;
  systemPrompt?: string;
}

/**
 * SidePanel-embeddable component showing conversation replay and tool call timeline
 * for a single agent execution. Filters out SYSTEM messages (confidential).
 */
export function AgentExecutionConversation({ executionId, systemPrompt }: AgentExecutionConversationProps) {
  const t = useTranslations('agentMetrics');
  const [activeTab, setActiveTab] = useState<'conversation' | 'toolCalls'>('conversation');

  // Lazy-load: page 0 = newest 30, scroll-up sentinel triggers older pages.
  // Each tab paginates independently so users opening only the tool-calls tab
  // don't pay for the conversation fetch (and vice versa).
  const messagesRes = useExecutionPagedResource<AgentExecutionMessage>(
    executionId,
    agentService.getExecutionConversationPaged.bind(agentService),
  );
  const toolCallsRes = useExecutionPagedResource<AgentExecutionToolCall>(
    executionId,
    agentService.getExecutionToolCallsPaged.bind(agentService),
  );

  // Filter out SYSTEM messages (confidential system prompt)
  const visibleMessages = messagesRes.items.filter(m => m.role !== 'SYSTEM');
  const toolCalls = toolCallsRes.items;
  const loading = messagesRes.loading && toolCallsRes.loading;

  const activeRes = activeTab === 'conversation' ? messagesRes : toolCallsRes;

  return (
    <div className="flex flex-col h-full">
      {/* Content */}
      <div className="flex-1 overflow-y-auto px-4 py-4">
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="animate-spin h-5 w-5 border-2 border-slate-300 border-t-slate-600 rounded-full" />
          </div>
        ) : (
          <>
            <LoadOlderSentinel
              hasMore={activeRes.hasMore}
              loading={activeRes.loadingOlder}
              onLoadOlder={activeRes.loadOlder}
            />
            {activeTab === 'conversation' ? (
              <ConversationView messages={visibleMessages} systemPrompt={systemPrompt} t={t} />
            ) : (
              <ToolCallsView toolCalls={toolCalls} t={t} />
            )}
          </>
        )}
      </div>

      {/* Tabs - bottom bar (styled like WorkflowPanelContent bottom tabs) */}
      <div className="flex-shrink-0 border-t border-theme bg-theme-secondary">
        <div className="flex overflow-x-auto">
          <button
            onClick={() => setActiveTab('conversation')}
            className={cn(
              'flex items-center gap-2 px-4 py-2.5 text-sm whitespace-nowrap transition-colors',
              activeTab === 'conversation'
                ? 'bg-theme-primary text-theme-primary font-medium'
                : 'text-theme-muted hover:bg-theme-tertiary'
            )}
          >
            <MessageSquare className="h-3.5 w-3.5" />
            {t('conversation')} ({visibleMessages.length})
          </button>
          <button
            onClick={() => setActiveTab('toolCalls')}
            className={cn(
              'flex items-center gap-2 px-4 py-2.5 text-sm whitespace-nowrap transition-colors',
              activeTab === 'toolCalls'
                ? 'bg-theme-primary text-theme-primary font-medium'
                : 'text-theme-muted hover:bg-theme-tertiary'
            )}
          >
            <Wrench className="h-3.5 w-3.5" />
            {t('toolCalls')} ({toolCalls.length})
          </button>
        </div>
      </div>
    </div>
  );
}

function ConversationView({
  messages,
  systemPrompt,
  t,
}: {
  messages: AgentExecutionMessage[];
  systemPrompt?: string;
  t: ReturnType<typeof useTranslations>;
}) {
  const [systemPromptOpen, setSystemPromptOpen] = useState(false);
  const hasSystemPrompt = !!systemPrompt && systemPrompt.trim().length > 0;

  if (messages.length === 0 && !hasSystemPrompt) {
    return (
      <p className="text-sm text-theme-muted text-center py-8">{t('noConversation')}</p>
    );
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
      {hasSystemPrompt && (
        <div className="rounded-lg border border-theme overflow-hidden">
          <button
            onClick={() => setSystemPromptOpen(o => !o)}
            className="w-full flex items-center gap-2 px-3 py-2 hover:bg-theme-secondary/50 transition-colors text-left"
          >
            {systemPromptOpen ? (
              <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
            ) : (
              <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
            )}
            <span className="text-xs font-semibold uppercase text-theme-muted">
              {t('systemPrompt')}
            </span>
          </button>
          {systemPromptOpen && (
            <div className="px-3 pb-3 border-t border-theme pt-2">
              <pre className="text-sm text-theme-primary whitespace-pre-wrap break-words leading-relaxed font-sans">
                {systemPrompt}
              </pre>
            </div>
          )}
        </div>
      )}
      {messages.map(msg => (
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
            {msg.iterationNumber != null && (
              <span className="text-xs text-theme-muted">
                #{msg.iterationNumber}
              </span>
            )}
            {msg.toolName && (
              <span className="text-xs font-mono text-theme-muted">
                {msg.toolName}
              </span>
            )}
          </div>
          {msg.content && (
            <div
              className="text-sm text-theme-primary whitespace-pre-wrap break-words leading-relaxed overflow-y-auto resize-y min-h-[1.5rem]"
              style={{ maxHeight: '15rem' }}
              onMouseDown={(e) => {
                // When user starts resizing, remove maxHeight so they can expand beyond
                const el = e.currentTarget;
                if (el.offsetHeight > 0 && e.nativeEvent.offsetY > el.offsetHeight - 12) {
                  el.style.maxHeight = 'none';
                  el.style.height = `${el.offsetHeight}px`;
                }
              }}
            >
              {msg.content}
            </div>
          )}
          {msg.toolCallsRequested && msg.toolCallsRequested.length > 0 && (
            <div className="mt-1.5 space-y-0.5">
              {msg.toolCallsRequested.map((tc, i) => (
                <div key={i} className="text-sm text-theme-primary">
                  {tc.toolName}({Object.keys(tc.arguments || {}).join(', ')})
                </div>
              ))}
            </div>
          )}
          {msg.contentStorageId && (
            <span className="text-xs text-theme-muted italic mt-1 block">
              [{t('contentTruncated')}]
            </span>
          )}
        </div>
      ))}
    </div>
  );
}

function ToolCallsView({
  toolCalls,
  t,
}: {
  toolCalls: AgentExecutionToolCall[];
  t: ReturnType<typeof useTranslations>;
}) {
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());

  if (toolCalls.length === 0) {
    return (
      <p className="text-sm text-theme-muted text-center py-8">{t('noToolCalls')}</p>
    );
  }

  const toggleExpand = (id: number) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  return (
    <div className="space-y-2">
      {toolCalls.map(tc => {
        const expanded = expandedIds.has(tc.id);
        return (
          <div
            key={tc.id}
            className="rounded-lg border border-theme overflow-hidden"
          >
            <button
              onClick={() => toggleExpand(tc.id)}
              className="w-full flex items-center gap-2 px-3 py-2.5 hover:bg-theme-secondary/50 transition-colors text-left"
            >
              {expanded ? (
                <ChevronDown className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
              ) : (
                <ChevronRight className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
              )}

              {tc.success ? (
                <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500 flex-shrink-0" />
              ) : (
                <AlertCircle className="h-3.5 w-3.5 text-red-500 flex-shrink-0" />
              )}

              <span className="text-sm font-medium text-theme-primary truncate flex-1">
                {tc.toolName}
              </span>

              {tc.isRepeat && (
                <span className="text-xs bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400 px-1.5 py-0.5 rounded-full">
                  {t('repeat')} x{tc.consecutiveCount}
                </span>
              )}

              {tc.durationMs != null && (
                <span className="flex items-center gap-1 text-xs text-theme-muted">
                  <Clock className="h-3 w-3" />
                  {tc.durationMs}ms
                </span>
              )}

              <span className="text-xs text-theme-muted">
                #{tc.iterationNumber}
              </span>
            </button>

            {expanded && (
              <div className="px-3 pb-3 border-t border-theme space-y-2 pt-2">
                {tc.arguments && Object.keys(tc.arguments).length > 0 && (
                  <div>
                    <span className="text-xs font-semibold text-theme-muted uppercase">{t('arguments')}</span>
                    <pre className="text-xs font-mono bg-theme-secondary rounded-xl p-2 mt-1 overflow-x-auto max-h-40 overflow-y-auto">
                      {JSON.stringify(tc.arguments, null, 2)}
                    </pre>
                  </div>
                )}
                {tc.content && (
                  <div>
                    <span className="text-xs font-semibold text-theme-muted uppercase">{t('output')}</span>
                    <pre className="text-xs font-mono bg-theme-secondary rounded-xl p-2 mt-1 overflow-x-auto max-h-40 overflow-y-auto whitespace-pre-wrap break-words">
                      {tc.content}
                    </pre>
                  </div>
                )}
                {tc.errorMessage && (
                  <div>
                    <span className="text-xs font-semibold text-red-500 uppercase">{t('error')}</span>
                    <pre className="text-xs font-mono bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 rounded p-2 mt-1">
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
  );
}

