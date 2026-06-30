'use client';

import React from 'react';
import { useTranslations, useLocale } from 'next-intl';
import type { Conversation } from '@/lib/api/conversation.types';
import { formatRelativeDateI18n } from '@/lib/utils/dateFormatters';

/**
 * Hover popover for a conversation sidebar row.
 *
 * Mirrors the {@link RunInfoBadge} pill on the workflow run canvas (rounded-full
 * white/dark pill, dot-separated chips) so the visual vocabulary stays
 * consistent between the run header and the sidebar quick-info.
 *
 * Pure presentational + memoized: Radix Tooltip only mounts the content on
 * open, so the whole subtree pays nothing until the user actually hovers. The
 * memo guard prevents re-render when the parent sidebar re-renders for an
 * unrelated reason (filter change, scroll, etc.).
 */
function ConversationInfoPillImpl({ conversation }: { conversation: Conversation }) {
  const t = useTranslations('sidebar.infoPill');
  // Relative time via the shared localized helper (runs.* keys + APP locale),
  // so the pill no longer mixes translated type chips with an English "5m ago".
  const tRuns = useTranslations('runs');
  const locale = useLocale();

  // Type chip - closest analogue to the run status pill. Conversations don't
  // have RUNNING/COMPLETED, so we surface what's actionable: awaiting human
  // input takes precedence over the static type label.
  const { typeLabel, typeClasses } = React.useMemo(() => {
    if (conversation.pendingAction?.needs_attention) {
      return {
        typeLabel: t('awaiting'),
        typeClasses: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-300',
      };
    }
    if (conversation.agentId) {
      return {
        typeLabel: t('typeAgent'),
        typeClasses: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300',
      };
    }
    if (conversation.workflowId) {
      return {
        typeLabel: t('typeWorkflow'),
        typeClasses: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300',
      };
    }
    return {
      typeLabel: t('typeChat'),
      typeClasses: 'bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300',
    };
  }, [conversation.pendingAction?.needs_attention, conversation.agentId, conversation.workflowId, t]);

  const relativeTime = React.useMemo(
    () => formatRelativeDateI18n(conversation.updatedAt || conversation.createdAt, tRuns, locale),
    [conversation.updatedAt, conversation.createdAt, tRuns, locale],
  );

  const providerModel = React.useMemo(() => {
    if (conversation.provider && conversation.model) return `${conversation.provider}/${conversation.model}`;
    return conversation.provider || conversation.model || null;
  }, [conversation.provider, conversation.model]);

  return (
    <div className="flex flex-col gap-1 px-3 py-2 bg-white/95 dark:bg-gray-800/95 backdrop-blur rounded-2xl overflow-hidden">
      <div className="flex items-center gap-2">
        <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${typeClasses}`}>
          {typeLabel}
        </div>

        <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
        <span className="text-xs text-gray-600 dark:text-gray-400 whitespace-nowrap">
          {relativeTime}
        </span>

        {conversation.messageCount > 0 && (
          <>
            <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
            <span className="text-xs text-gray-600 dark:text-gray-400 whitespace-nowrap">
              {t('messageCount', { count: conversation.messageCount })}
            </span>
          </>
        )}
      </div>

      {providerModel && (
        <span className="text-xs text-gray-600 dark:text-gray-400 break-all">
          {providerModel}
        </span>
      )}
    </div>
  );
}

export const ConversationInfoPill = React.memo(ConversationInfoPillImpl);
ConversationInfoPill.displayName = 'ConversationInfoPill';
