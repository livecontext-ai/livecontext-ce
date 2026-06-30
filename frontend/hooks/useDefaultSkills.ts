'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { conversationApi } from '@/lib/api/conversationApi';
import { orchestratorApi } from '@/lib/api/orchestrator';
import type { SkillsSummary } from '@/lib/api/orchestrator/skill.service';

type ConversationConfigProjection = {
  chatConfig?: Record<string, unknown> | null;
} | null;

type UseDefaultSkillsResult = {
  activeSkillIds: Set<string>;
  setActiveSkillIds: (ids: Set<string>) => void;
  initializeDefaults: (allSkills: Array<{ id: string; defaultKey?: string | null }>) => void;
  isActive: (skillId: string) => boolean;
  hasExplicitSkillSelection: boolean;
};

function normalizeSkillIds(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null;
  return [...new Set(value.filter((id): id is string => typeof id === 'string' && id.length > 0))].sort();
}

function readPersistedSkillIds(conversation: ConversationConfigProjection): string[] | null {
  return normalizeSkillIds(conversation?.chatConfig?.defaultSkillIds);
}

function mergeSkillIdsIntoChatConfig(
  conversation: ConversationConfigProjection,
  skillIds: string[],
): Record<string, unknown> {
  return {
    ...(conversation?.chatConfig ?? {}),
    defaultSkillIds: skillIds,
  };
}

/**
 * DB-backed per-conversation skill selection.
 *
 * No localStorage is used here. With no conversation override, the UI mirrors
 * the backend's effective default-active resolver. Once the user touches the
 * composer skill list, the explicit set is stored in conversation.chatConfig
 * when a conversation exists, or kept only in memory until the first send
 * creates a persisted conversation.
 */
export function useDefaultSkills(conversationId?: string): UseDefaultSkillsResult {
  const queryClient = useQueryClient();
  const [pendingExplicitIds, setPendingExplicitIds] = useState<string[] | null>(null);

  const conversationQuery = useQuery<ConversationConfigProjection>({
    queryKey: ['conversation-config', conversationId],
    queryFn: async () => (
      conversationId
        ? ((await conversationApi.getConversation(conversationId)) as ConversationConfigProjection)
        : null
    ),
    enabled: !!conversationId,
    staleTime: 30_000,
  });

  const defaultSummaryQuery = useQuery<SkillsSummary>({
    queryKey: ['skills', 'default-active-summary'],
    queryFn: () => orchestratorApi.getDefaultActiveSkillsSummary(),
    staleTime: 30_000,
  });

  useEffect(() => {
    setPendingExplicitIds(null);
  }, [conversationId]);

  const persistedExplicitIds = useMemo(
    () => readPersistedSkillIds(conversationQuery.data ?? null),
    [conversationQuery.data],
  );

  const defaultActiveIds = useMemo<string[]>(
    () => (defaultSummaryQuery.data?.skills ?? []).map(skill => skill.id),
    [defaultSummaryQuery.data],
  );

  const effectiveIds = useMemo<string[]>(
    () => pendingExplicitIds ?? persistedExplicitIds ?? defaultActiveIds,
    [pendingExplicitIds, persistedExplicitIds, defaultActiveIds],
  );
  const hasExplicitSkillSelection = pendingExplicitIds !== null || persistedExplicitIds !== null;

  const activeSkillIds = useMemo<Set<string>>(() => new Set(effectiveIds), [effectiveIds]);

  const setActiveSkillIds = useCallback((ids: Set<string>) => {
    const nextIds = Array.from(ids).sort();
    setPendingExplicitIds(nextIds);

    if (!conversationId) return;

    const currentConversation = conversationQuery.data ?? null;
    const chatConfig = mergeSkillIdsIntoChatConfig(currentConversation, nextIds);
    const optimisticConversation = {
      ...(currentConversation ?? {}),
      chatConfig,
    };

    queryClient.setQueryData(['conversation-config', conversationId], optimisticConversation);
    void conversationApi.updateConversation(conversationId, { chatConfig })
      .then((updated) => {
        queryClient.setQueryData(['conversation-config', conversationId], updated);
        setPendingExplicitIds(null);
      })
      .catch((error) => {
        console.error('[useDefaultSkills] Failed to persist conversation skills', error);
        void queryClient.invalidateQueries({ queryKey: ['conversation-config', conversationId] });
      });
  }, [conversationId, conversationQuery.data, queryClient]);

  const initializeDefaults = useCallback((_allSkills: Array<{ id: string; defaultKey?: string | null }>) => {
    void _allSkills;
  }, []);

  const isActive = useCallback((skillId: string) => activeSkillIds.has(skillId), [activeSkillIds]);

  return {
    activeSkillIds,
    setActiveSkillIds,
    initializeDefaults,
    isActive,
    hasExplicitSkillSelection,
  };
}
