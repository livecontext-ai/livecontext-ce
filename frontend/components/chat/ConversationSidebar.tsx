'use client';

import React, { useState, useRef, useCallback, useEffect, useMemo } from 'react';
import { usePathname, useRouter } from '@/i18n/navigation';
import { useSearchParams } from 'next/navigation';
import { useConversationHistory } from '@/hooks/useConversationHistory';
import { Conversation, conversationApi } from '@/lib/api/conversationApi';
import { DeleteConversationModal } from './DeleteConversationModal';
import {
  Trash2,
  MessageCircle,
  MessagesSquare,
  ChevronDown,
  Plus,
  Search,
  Workflow,
  Table,
  Store,
  Monitor,
  Bot,
  MoreVertical,
  ExternalLink,
  AppWindow,
  Columns3,
  ListFilter,
  Briefcase,
  Share2,
  Eraser,
  Home,
  Folder
} from 'lucide-react';
import { getProjectIcon } from '@/components/project/ProjectMultiStepModal';
import { conversationDisplayTitle } from '@/lib/utils/conversationTitle';
import { sortByRecency } from '@/lib/utils/conversationRecency';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useTranslations } from 'next-intl';
import { useCurrentView } from '@/hooks/useCurrentView';
import { Button } from '@/components/ui/button';
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '@/components/ui/tooltip';
import { ConversationInfoPill } from './ConversationInfoPill';
import { SidebarSection } from './SidebarSection';
import { DmSidebarList } from '@/components/dm/DmSidebarList';
import { shouldAutoLoadConversations } from './shouldAutoLoadConversations';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useIsStreaming } from '@/hooks/useIsStreaming';
import { useQuery } from '@tanstack/react-query';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { AvatarDisplay } from '@/components/agents';
import { orchestratorApi } from '@/lib/api';
import { useProjects, useProjectMutations } from '@/hooks/useProjects';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { ProjectMultiStepModal } from '@/components/project/ProjectMultiStepModal';
import type { Project } from '@/lib/api/orchestrator/project.types';
import { ShareLinkDialog } from '@/components/sharing/ShareLinkDialog';

/**
 * Hook to check if conversation is streaming (for use in render callbacks)
 */
const ConversationStreamingIndicator = React.memo(({
  conversationId,
  isSynthesizing,
  children
}: {
  conversationId: string;
  isSynthesizing: boolean;
  children: (showShimmer: boolean) => React.ReactNode;
}) => {
  const isStreaming = useIsStreaming(conversationId);
  const showShimmer = isSynthesizing || isStreaming;
  return <>{children(showShimmer)}</>;
});
ConversationStreamingIndicator.displayName = 'ConversationStreamingIndicator';

interface ConversationSidebarProps {
  onConversationSelect: (conversation: Conversation | null) => void;
  currentConversationId?: string;
  className?: string;
  sidebarCollapsed?: boolean;
  onConversationCreated?: (conversationId: string, title: string | null, isTemporary: boolean) => void;
  onTitleUpdated?: (conversationId: string, title: string, isTemporary: boolean) => void;
  onNewChat?: () => void;
  onSearchClick?: () => void;
  onSearchWorkflows?: () => void;
  onSearchDataSources?: () => void;
  onMarketPlaceClick?: () => void;
  onNavigate?: (path: string) => void;
  onSignOut?: () => void;
  user?: any;
}

export function ConversationSidebar({
  onConversationSelect,
  currentConversationId,
  className = '',
  sidebarCollapsed = false,
  onConversationCreated,
  onTitleUpdated,
  onNewChat,
  onSearchClick,
  onSearchWorkflows,
  onSearchDataSources,
  onMarketPlaceClick,
  onSignOut,
  user: userProp,
  onNavigate,
}: ConversationSidebarProps) {
  // Hooks must be called in the same order every render
  const t = useTranslations();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const router = useRouter();
  const [conversationsCollapsed, setConversationsCollapsed] = useState(false);
  // Chats ⇄ Messages (DM) toggle on the Chats header. Messages is a pure sidebar *view*,
  // not a standalone page: there is no bare /app/messages URL. Toggling only flips the list
  // and keeps the main panel on Home (new chat). Only opening a specific thread navigates
  // (DmSidebarList → /app/messages/[threadId]); the effect below re-pins the toggle to
  // Messages mode when such a thread route is active (deep link / right after selecting one).
  const [messagesMode, setMessagesMode] = useState(false);
  useEffect(() => {
    if (pathname?.includes('/app/messages')) setMessagesMode(true);
  }, [pathname]);
  const toggleMessagesMode = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    // Flip the sidebar list and return the main panel to Home (new chat) in BOTH directions:
    // entering Messages must not yank the user onto a dedicated page, and leaving it (the
    // black/active toggle) goes back to Home. Never navigate to a standalone /app/messages.
    setMessagesMode((prev) => !prev);
    onNewChat?.();
  }, [onNewChat]);
  const [chatFilter, setChatFilter] = useState<'all' | 'agents' | 'workflows'>('all');
  const [filterMenuOpen, setFilterMenuOpen] = useState(false);
  // Messages (DM) mode: header-driven conversation filter (workspace teammates vs other
  // conversations) + search toggle; both consumed by DmSidebarList.
  const [dmFilter, setDmFilter] = useState<'all' | 'teammates' | 'others'>('all');
  const [dmFilterMenuOpen, setDmFilterMenuOpen] = useState(false);
  const [dmSearchOpen, setDmSearchOpen] = useState(false);

  // Modal state
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [conversationToDelete, setConversationToDelete] = useState<Conversation | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  // Share state
  const [shareOpen, setShareOpen] = useState(false);
  const [conversationToShare, setConversationToShare] = useState<Conversation | null>(null);

  // Title synthesis state
  const [synthesizingTitles, setSynthesizingTitles] = useState<Set<string>>(new Set());
  const [temporaryTitles, setTemporaryTitles] = useState<Map<string, string>>(new Map());

  // Menu state for conversation actions
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);

  // Check authentication state
  const { isAuthenticated, isLoading: authLoading, user } = useAuthGuard();

  // Agent avatar map: lightweight (id, avatarUrl) projection - avoids loading
  // full agent entities (system_prompt LOB, config blob) when the sidebar only
  // needs the visual identity. Backed by GET /api/agents/avatars.
  // Phase 4 (2026-05-18) - org-scoped: agent avatars are workspace-bound
  // (different orgs see different agent sets).
  const { data: agentsForAvatars } = useOrgScopedQuery({
    queryKey: ['agents', 'avatars'] as const,
    queryFn: () => orchestratorApi.getAgentAvatars(),
    enabled: isAuthenticated,
    staleTime: 5 * 60 * 1000,
  });
  const agentAvatarMap = useMemo(() => {
    const map = new Map<string, string>();
    if (agentsForAvatars) {
      for (const agent of agentsForAvatars) {
        if (agent.avatarUrl) map.set(agent.id, agent.avatarUrl);
      }
    }
    return map;
  }, [agentsForAvatars]);
  const tenantId = user?.sub || user?.email || 'demo';

  // Use userProp if provided, otherwise use user from useAuthGuard
  const effectiveUser = userProp || user;

  // Projects state
  const [projectsCollapsed, setProjectsCollapsed] = useState(false);
  const [showProjectModal, setShowProjectModal] = useState(false);
  const [editingProject, setEditingProject] = useState<Project | null>(null);
  const [projectMenuId, setProjectMenuId] = useState<string | null>(null);
  const { projects, loading: projectsLoading } = useProjects();
  const { deleteProject } = useProjectMutations();
  // Split gating: CONVERSATION actions (delete/clear) are deliberately NOT
  // role-gated - conversation-service has no org-role write gate, chat history
  // is the caller's own. PROJECTS however are orchestrator ProjectService rows:
  // update/delete run the central canWrite gate (VIEWER 403s), so the project
  // edit/delete menu is hidden for a VIEWER. Project CREATE is ungated
  // backend-side, so the "+" stays for everyone.
  const canMutateProjects = useCanMutateInCurrentOrg();

  // Get current view from URL
  const { view: currentView, conversationId: urlConversationId, isDetailPage } = useCurrentView();
  const isConversationSurface = (
    currentView === 'chat' ||
    urlConversationId !== null ||
    pathname?.startsWith('/app/chat') ||
    pathname?.startsWith('/app/c/')
  );

  // Auto-load conversations on every primary app surface (incl. the aggregated Board)
  // so the conversation titles stay visible in the sidebar. Allowlist: shouldAutoLoadConversations.
  const shouldAutoLoad = shouldAutoLoadConversations({
    isAuthenticated,
    currentView,
    urlConversationId,
    pathname,
  });
  const [deferredAutoLoad, setDeferredAutoLoad] = useState(false);

  useEffect(() => {
    if (!shouldAutoLoad) {
      setDeferredAutoLoad(false);
      return;
    }

    if (isConversationSurface) {
      setDeferredAutoLoad(true);
      return;
    }

    setDeferredAutoLoad(false);
    const timer = setTimeout(() => setDeferredAutoLoad(true), 1200);
    return () => clearTimeout(timer);
  }, [shouldAutoLoad, isConversationSurface]);

  const {
    conversations: rawConversations,
    loading,
    error,
    hasMore,
    selectConversation,
    loadMessages,
    deleteConversation,
    loadMoreConversations,
    loadConversationById,
    clearMessages,
    forceRefreshConversations,
  } = useConversationHistory({ autoLoad: deferredAutoLoad });

  // Reconcile the conversation list with the server when the user LEAVES a
  // conversation surface (chat -> any other page).
  // The sidebar lives in the persistent /app layout, and after its first load
  // the React Query cache is intentionally sticky (staleTime: Infinity, no
  // refetch on mount/focus/reconnect). While on the chat surface the list is
  // kept current by live streaming updates (addConversations / updateConversation
  // emitted from the chat page); off that surface those updates stop. So a
  // conversation started right before navigating away can be missed (missing
  // row, placeholder title, wrong order) and, with no refetch, stay stale until
  // a full page reload. forceRefreshConversations rewinds to page 0 and
  // refetches from the server, healing membership, titles and order regardless
  // of which live event was dropped (and even when the user had paginated the
  // sidebar); the shared context backstops the rows during the refetch so the
  // list does not flicker. Gating on the surface transition (not every
  // navigation) keeps it to one refetch per chat exit.
  const forceRefreshConversationsRef = useRef(forceRefreshConversations);
  forceRefreshConversationsRef.current = forceRefreshConversations;
  const wasConversationSurfaceRef = useRef(isConversationSurface);
  useEffect(() => {
    const leftConversationSurface = wasConversationSurfaceRef.current && !isConversationSurface;
    wasConversationSurfaceRef.current = isConversationSurface;
    if (!leftConversationSurface) return;
    if (!isAuthenticated || !shouldAutoLoad) return;
    void forceRefreshConversationsRef.current();
  }, [isConversationSurface, isAuthenticated, shouldAutoLoad]);

  // Utiliser le context unifie pour les conversations partagees
  const { state: appState } = useUnifiedApp();
  const sharedConversations = appState.conversations;
  const sharedHasMore = appState.hasMore;

  // Combiner les conversations du hook et de la variable partagee
  // Utiliser rawConversations si sharedConversations est vide ou incomplet
  const conversationsToUse = sharedConversations.length >= rawConversations.length ? sharedConversations : rawConversations;

  // Fonctions pour gerer les evenements de titre
  const handleConversationCreated = useCallback((conversationId: string, title: string | null, isTemporary: boolean) => {
    console.log(`🔄 [SIDEBAR] Conversation created: ${conversationId}, title: ${title}, temporary: ${isTemporary}`);

    // Si pas de titre (null), on demarre le loading
    if (!title) {
      setSynthesizingTitles(prev => new Set(prev).add(conversationId));
    } else if (isTemporary) {
      setTemporaryTitles(prev => new Map(prev).set(conversationId, title));
      setSynthesizingTitles(prev => new Set(prev).add(conversationId));
    }

    // Appeler le callback parent
    onConversationCreated?.(conversationId, title || "", isTemporary);
  }, [onConversationCreated]);

  const handleTitleUpdated = useCallback((conversationId: string, title: string, isTemporary: boolean) => {
    console.log(`✅ [SIDEBAR] Title updated: ${conversationId}, title: ${title}, temporary: ${isTemporary}`);

    if (!isTemporary) {
      setTemporaryTitles(prev => {
        const newMap = new Map(prev);
        newMap.delete(conversationId);
        return newMap;
      });
      setSynthesizingTitles(prev => {
        const newSet = new Set(prev);
        newSet.delete(conversationId);
        return newSet;
      });
    }

    // Appeler le callback parent
    onTitleUpdated?.(conversationId, title, isTemporary);
  }, [onTitleUpdated]);

  // Fonction pour obtenir le titre a afficher (temporaire ou final).
  // A real, LLM/user-assigned title wins; otherwise fall back to the first user
  // message preview (for ANY conversation type, general chat included) so a row is
  // never stuck on the "Generating title..." placeholder when no title gets
  // generated (user stops early, generation fails, ...). See conversationTitle.ts.
  const getDisplayTitle = useCallback((conversation: Conversation) => {
    const tempTitle = temporaryTitles.get(conversation.id);
    if (tempTitle) return tempTitle;
    return conversationDisplayTitle(conversation, t('sidebar.generatingTitle'));
  }, [temporaryTitles, t]);

  // Fonction pour verifier si un titre est en cours de synthese
  const isTitleSynthesizing = useCallback((conversationId: string) => {
    return synthesizingTitles.has(conversationId);
  }, [synthesizingTitles]);

  // Dedupliquer les conversations par ID et convertir les conversations partagees
  // Utilise Map pour O(1) lookup et evite de creer de nouveaux objets si non necessaire
  const conversations = React.useMemo(() => {
    const seen = new Map<string, Conversation>();
    // sharedConversations only carries a compact projection (no timestamps /
    // provider / messageCount), so when an entry exists in rawConversations
    // - which holds the full DTO from the API - we prefer that copy. Without
    // this lookup the hover pill reads `updatedAt`/`createdAt` from the
    // synthetic placeholder below and every row reports the same relative
    // time.
    const rawById = new Map<string, Conversation>();
    for (const c of rawConversations) rawById.set(c.id, c);

    for (const conv of conversationsToUse) {
      if (seen.has(conv.id)) continue;

      const full = rawById.get(conv.id);
      if (full) {
        seen.set(conv.id, full);
        continue;
      }

      // Si c'est une conversation partagee (minimale), la convertir en Conversation complete
      if ('id' in conv && 'title' in conv && !('userId' in conv)) {
        seen.set(conv.id, {
          id: conv.id,
          title: conv.title,
          userId: '',
          model: '',
          provider: '',
          createdAt: (conv as any).createdAt ?? new Date().toISOString(),
          updatedAt: (conv as any).updatedAt ?? new Date().toISOString(),
          messageCount: 0,
          workflowId: (conv as any).workflowId,
          agentId: (conv as any).agentId
        } as Conversation);
      } else {
        seen.set(conv.id, conv as Conversation);
      }
    }

    // Order by most-recent activity (shared sortByRecency, same ordering as the
    // UnifiedAppContext cache). The merge above prefers the full server DTO when
    // present, so this sorts on authoritative timestamps and keeps a
    // just-created conversation (updatedAt = now) at the top. Without it the
    // display order followed the context's insertion order, which drifts from
    // the server when a conversation's activity is not reflected back into the
    // context.
    return sortByRecency(Array.from(seen.values()));
  }, [conversationsToUse, rawConversations]);

  // Check if filter chips should be shown (at least one agent or workflow conversation)
  const hasAgentConversations = useMemo(() => conversations.some(c => c.agentId), [conversations]);
  const hasWorkflowConversations = useMemo(() => conversations.some(c => c.workflowId), [conversations]);
  const showFilterChips = hasAgentConversations || hasWorkflowConversations;

  // Filter conversations based on selected chip
  const filteredConversations = useMemo(() => {
    if (chatFilter === 'agents') return conversations.filter(c => c.agentId);
    if (chatFilter === 'workflows') return conversations.filter(c => c.workflowId);
    return conversations;
  }, [conversations, chatFilter]);

  // Agent avatars are derived from the ['agents', 'avatars'] query above
  // (CreateAgentModal invalidates ['agents'] - prefix-matches and refreshes us)

  // Calculer hasMoreConversations apres la declaration de conversations
  const hasMoreConversations = sharedHasMore !== undefined ? sharedHasMore : hasMore;


  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const hasAutoLoadedRef = useRef(false);

  // Load more conversations with loading state
  const handleLoadMore = useCallback(async () => {
    console.log(`🔄 [HANDLE LOAD MORE] Called - isLoadingMore: ${isLoadingMore}, hasMoreConversations: ${hasMoreConversations}, conversations.length: ${conversations.length}`);

    if (isLoadingMore || !hasMoreConversations) {
      console.log(`⚠️ [HANDLE LOAD MORE] Skipping - isLoadingMore: ${isLoadingMore}, hasMoreConversations: ${hasMoreConversations}`);
      return;
    }

    console.log(`✅ [HANDLE LOAD MORE] Starting load more conversations`);
    setIsLoadingMore(true);
    try {
      await loadMoreConversations();
      console.log(`✅ [HANDLE LOAD MORE] Load more conversations completed`);
    } catch (error) {
      console.error('❌ [HANDLE LOAD MORE] Error loading more conversations:', error);
    } finally {
      setIsLoadingMore(false);
    }
  }, [isLoadingMore, hasMoreConversations, loadMoreConversations, conversations.length]);

  // Handle scroll to load more conversations
  const handleScroll = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container || isLoadingMore || !hasMoreConversations) return;

    const { scrollTop, scrollHeight, clientHeight } = container;
    const threshold = 100; // Load more when 100px from bottom

    if (scrollHeight - scrollTop - clientHeight < threshold) {
      console.log(`🔄 [SCROLL] Triggering load more - scrollTop: ${scrollTop}, scrollHeight: ${scrollHeight}, clientHeight: ${clientHeight}, threshold: ${threshold}`);
      handleLoadMore();
    }
  }, [isLoadingMore, hasMoreConversations, handleLoadMore]);


  // Add scroll listener
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    container.addEventListener('scroll', handleScroll);
    return () => container.removeEventListener('scroll', handleScroll);
  }, [handleScroll]);

  // Auto-load more conversations if the list is too short to scroll
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container || isLoadingMore || !hasMoreConversations || conversations.length === 0 || hasAutoLoadedRef.current) return;

    // Check if there's no scrollbar (content fits in container)
    const hasScrollbar = container.scrollHeight > container.clientHeight;

    if (!hasScrollbar && hasMoreConversations) {
      console.log('🔄 Auto-loading more conversations - no scrollbar detected');
      hasAutoLoadedRef.current = true;

      // Use a timeout to avoid immediate re-triggering
      setTimeout(() => {
        loadMoreConversations().catch(error => {
          console.error('Error auto-loading more conversations:', error);
          hasAutoLoadedRef.current = false; // Reset on error
        });
      }, 100);
    }
  }, [conversations.length, hasMoreConversations, isLoadingMore, loadMoreConversations]);

  // Note: Streaming state is now managed by StreamingContext
  // No need to fetch from backend - context tracks current stream


  const handleConversationClick = async (conversation: Conversation) => {
    console.log('🔄 Selecting conversation:', conversation.id, conversation.title);

    // Verifier si la conversation est dans la liste chargee
    const existingConversation = conversations.find(conv => conv.id === conversation.id);
    if (!existingConversation) {
      console.log('🔄 Conversation not in loaded list, attempting to load it...');

      // Essayer de charger la conversation manquante
      const loadedConversation = await loadConversationById(conversation.id);
      if (loadedConversation) {
        console.log('✅ Conversation loaded successfully:', loadedConversation.id);
        selectConversation(loadedConversation);
      } else {
        console.warn('⚠️ Could not load conversation, using provided data');
        selectConversation(conversation);
      }
    } else {
      selectConversation(conversation);
    }

    // Don't load messages here - let ChatPage handle it via route navigation
    // This prevents double loading when AppSidebar calls navigateToChat
    // The messages will be loaded by ChatPage's useEffect that reacts to conversationId changes

    // Notify parent to trigger navigation (AppSidebar will call navigateToChat)
    onConversationSelect?.(conversation);
  };

  const handleDeleteConversation = async (e: React.MouseEvent, conversation: Conversation) => {
    e.stopPropagation();
    setConversationToDelete(conversation);
    setShowDeleteModal(true);
  };

  const handleConfirmDelete = async () => {
    if (!conversationToDelete) return;

    setIsDeleting(true);
    try {
      if (conversationToDelete.agentId) {
        // Agent conversations: clear messages only, keep the conversation
        console.log('🧹 [SIDEBAR] Clearing messages for agent conversation:', conversationToDelete.id);
        await conversationApi.clearConversationMessages(conversationToDelete.id);

        if (currentConversationId === conversationToDelete.id) {
          clearMessages();
        }

        console.log('✅ [SIDEBAR] Agent conversation messages cleared');
      } else {
        // Regular/workflow conversations: delete the conversation
        console.log('🗑️ [SIDEBAR] Starting deletion of conversation:', conversationToDelete.id);
        await deleteConversation(conversationToDelete.id);

        if (currentConversationId === conversationToDelete.id) {
          onConversationSelect?.(null);
        }

        console.log('✅ [SIDEBAR] Conversation deleted successfully');
      }

      setShowDeleteModal(false);
      setConversationToDelete(null);
    } catch (error) {
      console.error('❌ [SIDEBAR] Error:', error);
    } finally {
      setIsDeleting(false);
    }
  };

  const handleCancelDelete = () => {
    setShowDeleteModal(false);
    setConversationToDelete(null);
  };


  // Render a single conversation item (reused across all groups)
  const renderConversationItem = (conversation: Conversation, index: number) => (
    <ConversationStreamingIndicator
      key={conversation.id || `conversation-${index}`}
      conversationId={conversation.id}
      isSynthesizing={isTitleSynthesizing(conversation.id)}
    >
      {(showShimmer) => (
        <Tooltip>
          <TooltipTrigger asChild>
        <div
          onClick={() => handleConversationClick(conversation)}
          className={`group relative cursor-pointer transition-all duration-200 rounded-lg px-1 py-1.5 my-0.5 ${
            currentConversationId === conversation.id
              ? 'bg-surface-hover'
              : 'bg-transparent hover:bg-surface-hover'
          }`}
        >
          <div className="flex items-center w-full min-w-0 pr-6">
            <h3 className={`text-sm font-normal truncate transition-colors min-w-0 ${showShimmer ? 'shimmer-text-visible' : 'text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium'}`}>
              {getDisplayTitle(conversation)}
            </h3>
            {conversation.workflowId && (
              <Workflow className="ml-1 w-3 h-3 text-theme-muted flex-shrink-0" />
            )}
            {conversation.agentId && (
              agentAvatarMap.get(conversation.agentId) ? (
                <div className="ml-1 flex-shrink-0">
                  <AvatarDisplay avatarUrl={agentAvatarMap.get(conversation.agentId)!} size="sm" className="!w-4 !h-4" />
                </div>
              ) : (
                <Bot className="ml-1 w-3 h-3 text-theme-muted flex-shrink-0" />
              )
            )}
          </div>

          {/* 3-dot menu button */}
          <Popover open={openMenuId === conversation.id} onOpenChange={(open) => setOpenMenuId(open ? conversation.id : null)}>
            <PopoverTrigger asChild>
              <Button
                onClick={(e) => e.stopPropagation()}
                variant="ghostGray"
                className="absolute right-1 top-1/2 -translate-y-1/2 w-5 h-5 p-0 rounded-full text-theme-muted opacity-0 group-hover:opacity-100 group-hover:bg-surface-hover transition-opacity"
                title={t('sidebar.conversationMenu')}
              >
                <MoreVertical className="w-3 h-3" />
              </Button>
            </PopoverTrigger>
            <PopoverContent
              align="end"
              sideOffset={5}
              className="w-auto min-w-[160px] p-2 bg-theme-primary rounded-2xl border border-gray-300/70 dark:border-gray-600/70"
            >
              <div className="space-y-1">
                {conversation.workflowId && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setOpenMenuId(null);
                      if (onNavigate) {
                        onNavigate(`/app/workflow/${conversation.workflowId}`);
                      } else {
                        router.push(`/app/workflow/${conversation.workflowId}`);
                      }
                    }}
                    className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                  >
                    <ExternalLink className="h-4 w-4" />
                    <span className="text-sm">{t('sidebar.navigateToWorkflow')}</span>
                  </button>
                )}
                {conversation.agentId && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setOpenMenuId(null);
                      if (onNavigate) {
                        onNavigate(`/app/agent/${conversation.agentId}`);
                      } else {
                        router.push(`/app/agent/${conversation.agentId}`);
                      }
                    }}
                    className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                  >
                    <ExternalLink className="h-4 w-4" />
                    <span className="text-sm">{t('sidebar.navigateToAgent')}</span>
                  </button>
                )}
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setOpenMenuId(null);
                    setConversationToShare(conversation);
                    setShareOpen(true);
                  }}
                  className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                >
                  <Share2 className="h-4 w-4" />
                  <span className="text-sm">{t('sidebar.shareConversation')}</span>
                </button>
                {/* NOT VIEWER-gated on purpose (audit 2026-07-02): conversations and
                    projects are the caller's own - conversation-service has no
                    org-role write gate, a VIEWER may delete/clear their own chat
                    history and manage their projects. */}
                <button
                  onClick={(e) => {
                    setOpenMenuId(null);
                    handleDeleteConversation(e, conversation);
                  }}
                  className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30"
                >
                  {conversation.agentId ? (
                    <>
                      <Eraser className="h-4 w-4" />
                      <span className="text-sm">{t('sidebar.clearMessages')}</span>
                    </>
                  ) : (
                    <>
                      <Trash2 className="h-4 w-4" />
                      <span className="text-sm">{t('sidebar.deleteConversation')}</span>
                    </>
                  )}
                </button>
              </div>
            </PopoverContent>
          </Popover>
        </div>
          </TooltipTrigger>
          <TooltipContent
            side="right"
            align="center"
            sideOffset={12}
            className="p-0 border-none bg-transparent shadow-none"
          >
            <ConversationInfoPill conversation={conversation} />
          </TooltipContent>
        </Tooltip>
      )}
    </ConversationStreamingIndicator>
  );

  // Render conversation list. Single TooltipProvider scoped to the sidebar so
  // every conversation row's hover pill shares one delayed timer + portal -
  // cheaper than mounting one provider per row.
  return (
    <TooltipProvider delayDuration={400} skipDelayDuration={200}>
    <div className={`bg-theme-secondary flex flex-col h-full relative overflow-hidden ${className}`}>
      {/* Conversation View */}
      <div className="flex flex-col h-full">
        {/* Home - opens the new-chat landing page (above Marketplace) */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={() => onNewChat?.()}
                  // Highlight Home whenever the main panel is actually on Home (new chat) - including
                  // while the sidebar is in Messages mode, since Messages is a pure view that keeps us
                  // on Home. Only an open DM thread (/app/messages/[threadId]) un-highlights it.
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'chat' && !isDetailPage && !currentConversationId && !pathname?.includes('/app/messages')
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <Home className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.home')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Market Place - Always visible, clickable */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={onMarketPlaceClick}
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'marketplace'
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <Store className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.marketplace')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Navigation block (Projects ... Files): height-capped at 40% of the
            sidebar with its own scrollbar, so the Chats list below always stays
            visible. Home + Marketplace above stay fixed, outside this block.
            The wrapper is a pure layout container (no !sidebarCollapsed guard):
            each inner section keeps its own guard, so when collapsed this div is
            simply empty (0 height) and the existing visibility logic is untouched. */}
        <div className="flex-shrink-0 max-h-[40%] overflow-y-auto sidebar-scroll">
        {/* Projects Section */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <SidebarSection
              title={t('sidebar.projects')}
              collapsed={projectsCollapsed}
              onToggleCollapse={() => setProjectsCollapsed(!projectsCollapsed)}
              items={projects}
              loading={projectsLoading}
              icon={sidebarCollapsed ? Briefcase : undefined}
              iconClassName="text-theme-muted group-hover:text-[var(--bg-primary)]"
              titleClassName="text-theme-muted"
              chevronClassName="text-theme-muted opacity-0 group-hover:opacity-100"
              actions={
                <Button
                  onClick={(e) => {
                    e.stopPropagation();
                    setEditingProject(null);
                    setShowProjectModal(true);
                  }}
                  variant="ghostGray"
                  className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
                  title={t('sidebar.newProject')}
                >
                  <Plus className="w-4 h-4 flex-shrink-0" />
                </Button>
              }
              renderItem={(project: Project) => (
                <div
                  key={project.id}
                  onClick={() => {
                    if (onNavigate) {
                      onNavigate(`/app/project/${project.id}`);
                    } else {
                      router.push(`/app/project/${project.id}`);
                    }
                  }}
                  className="group relative cursor-pointer transition-all duration-200 rounded-lg px-1 py-1.5 my-0.5 hover:bg-surface-hover"
                >
                  <div className="flex items-center w-full min-w-0 pr-6">
                    {(() => {
                      const IconComp = getProjectIcon(project.icon);
                      return <IconComp className="w-4 h-4 mr-2 flex-shrink-0" style={{ color: project.color }} />;
                    })()}
                    <h3 className="text-sm font-normal truncate text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary min-w-0">{project.name}</h3>
                  </div>

                  {/* 3-dot menu - hidden for VIEWER (ProjectService update/delete
                      run the central canWrite gate and would 403). */}
                  {canMutateProjects && (
                  <Popover
                    open={projectMenuId === project.id}
                    onOpenChange={(open) => setProjectMenuId(open ? project.id : null)}
                  >
                    <PopoverTrigger asChild>
                      <Button
                        onClick={(e) => e.stopPropagation()}
                        variant="ghostGray"
                        className="absolute right-1 top-1/2 -translate-y-1/2 w-5 h-5 p-0 rounded-full text-theme-muted opacity-0 group-hover:opacity-100 group-hover:bg-surface-hover transition-opacity"
                      >
                        <MoreVertical className="w-3 h-3" />
                      </Button>
                    </PopoverTrigger>
                    <PopoverContent
                      align="end"
                      sideOffset={5}
                      className="w-auto min-w-[160px] p-2 bg-theme-primary rounded-2xl border border-gray-300/70 dark:border-gray-600/70"
                    >
                      <div className="space-y-1">
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setProjectMenuId(null);
                            setEditingProject(project);
                            setShowProjectModal(true);
                          }}
                          className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                        >
                          <Briefcase className="h-4 w-4" />
                          <span className="text-sm">{t('project.editProject')}</span>
                        </button>
                        {project.currentUserRole === 'OWNER' && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setProjectMenuId(null);
                              if (window.confirm(t('project.deleteConfirm'))) {
                                deleteProject.mutate(project.id);
                              }
                            }}
                            className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30"
                          >
                            <Trash2 className="h-4 w-4" />
                            <span className="text-sm">{t('project.deleteProject')}</span>
                          </button>
                        )}
                      </div>
                    </PopoverContent>
                  </Popover>
                  )}
                </div>
              )}
              emptyMessage=""
              sidebarCollapsed={sidebarCollapsed}
              isAuthenticated={isAuthenticated}
            />
          </div>
        )}

        {/* Board Button - aggregated Tasks / Applications / Workflows board (above Agents) */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={() => {
                    if (onNavigate) {
                      onNavigate('/app/board');
                    } else {
                      router.push('/app/board');
                    }
                  }}
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'board'
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <Columns3 className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.board')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Agents Button */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={() => {
                    if (onNavigate) {
                      onNavigate('/app/agent');
                    } else {
                      router.push('/app/agent');
                    }
                  }}
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'agent'
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <Bot className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.agents')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Applications Button */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={() => {
                    if (onNavigate) {
                      onNavigate('/app/applications');
                    } else {
                      router.push('/app/applications');
                    }
                  }}
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'applications'
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <AppWindow className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.applications')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Workflows Button */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={() => {
                    if (onNavigate) {
                      onNavigate('/app/workflow');
                    } else {
                      router.push('/app/workflow');
                    }
                  }}
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'workflow'
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <Workflow className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.workflows')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Interface Button */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={() => {
                    if (onNavigate) {
                      onNavigate('/app/interface');
                    } else {
                      router.push('/app/interface');
                    }
                  }}
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'interface'
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <Monitor className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.interfaces')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Data Button */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={() => {
                    if (onNavigate) {
                      onNavigate('/app/tables');
                    } else {
                      router.push('/app/tables');
                    }
                  }}
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'data'
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <Table className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.tables')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Files Button */}
        {!sidebarCollapsed && (
          <div className="flex-shrink-0">
            <div className="">
              <div className="flex items-center px-4">
                <button
                  onClick={() => {
                    if (onNavigate) {
                      onNavigate('/app/files');
                    } else {
                      router.push('/app/files');
                    }
                  }}
                  className={`flex items-center group rounded-lg px-1 py-1.5 my-0.5 transition-all duration-200 cursor-pointer w-full ${currentView === 'files'
                    ? 'bg-surface-hover'
                    : 'bg-transparent hover:bg-surface-hover'
                    }`}
                >
                  <Folder className="w-4 h-4 text-theme-secondary mr-2 group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors" />
                  <h2 className="text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary group-[.bg-surface-hover]:font-medium transition-colors">{t('sidebar.nav.files')}</h2>
                </button>
              </div>
            </div>
          </div>
        )}
        </div>
        {/* end navigation block (Projects ... Files) */}

        {/* Conversations List */}
        {!sidebarCollapsed && (
          <div className="flex-1 flex flex-col min-h-0">

            {/* Chats Section - Takes remaining space */}
            <div className="flex-1 min-h-0 flex flex-col mt-3">
              <SidebarSection
                title={messagesMode ? t('dm.sidebarTitle') : t('sidebar.chats')}
                collapsed={conversationsCollapsed}
                onToggleCollapse={() => setConversationsCollapsed(!conversationsCollapsed)}
                items={conversations}
                loading={loading && conversations.length === 0}
                icon={sidebarCollapsed ? MessageCircle : undefined}
                iconClassName="text-theme-muted group-hover:text-[var(--bg-primary)]"
                titleClassName="text-theme-muted"
                chevronClassName="text-theme-muted opacity-0 group-hover:opacity-100"
                actions={
                  <>
                    {/* Chats ⇄ Messages toggle - ALWAYS visible; black/filled when active, and a
                        re-click while active returns to Home (see toggleMessagesMode). */}
                    <Button
                      onClick={toggleMessagesMode}
                      variant="ghostGray"
                      data-testid="dm-mode-toggle"
                      className={`w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full transition-all font-normal flex items-center justify-center opacity-100 ${
                        messagesMode
                          ? 'bg-[var(--text-primary)] text-[var(--bg-primary)] hover:opacity-90'
                          : 'text-theme-muted hover:text-[var(--bg-primary)]'
                      }`}
                      title={messagesMode ? t('dm.showChats') : t('dm.showMessages')}
                    >
                      <MessagesSquare className="w-3.5 h-3.5 flex-shrink-0" />
                    </Button>
                    {/* Messages mode: filter (teammates vs other conversations), conversation
                        search, and new-message (still a placeholder - wired later). */}
                    {messagesMode && (
                      <>
                        <Popover open={dmFilterMenuOpen} onOpenChange={setDmFilterMenuOpen}>
                          <PopoverTrigger asChild>
                            <Button
                              onClick={(e) => e.stopPropagation()}
                              variant="ghostGray"
                              data-testid="dm-filter-button"
                              className={`w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full transition-colors font-normal flex items-center justify-center ${
                                dmFilter !== 'all'
                                  ? 'bg-[var(--text-primary)] text-[var(--bg-primary)] hover:opacity-90'
                                  : 'text-theme-muted hover:text-[var(--bg-primary)]'
                              }`}
                              title={t('dm.filterMessages')}
                            >
                              <ListFilter className="w-3.5 h-3.5 flex-shrink-0" />
                            </Button>
                          </PopoverTrigger>
                          <PopoverContent
                            align="start"
                            sideOffset={5}
                            className="w-auto min-w-[140px] p-1.5 bg-theme-primary rounded-xl border border-gray-300/70 dark:border-gray-600/70"
                          >
                            <div className="space-y-0.5">
                              {(['all', 'teammates', 'others'] as const).map((filter) => (
                                <button
                                  key={filter}
                                  data-testid={`dm-filter-${filter}`}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setDmFilter(filter);
                                    setDmFilterMenuOpen(false);
                                  }}
                                  className={`w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs transition-colors ${
                                    dmFilter === filter
                                      ? 'bg-gray-100 dark:bg-gray-700 text-theme-primary'
                                      : 'text-theme-muted hover:bg-gray-100 dark:hover:bg-gray-700 hover:text-theme-primary'
                                  }`}
                                >
                                  {filter === 'all'
                                    ? t('dm.filterAll')
                                    : filter === 'teammates'
                                      ? t('dm.filterTeammates')
                                      : t('dm.filterOthers')}
                                </button>
                              ))}
                            </div>
                          </PopoverContent>
                        </Popover>
                        <Button
                          onClick={(e) => {
                            e.stopPropagation();
                            setDmSearchOpen((open) => !open);
                          }}
                          variant="ghostGray"
                          data-testid="dm-search-button"
                          className={`w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full transition-colors font-normal flex items-center justify-center ${
                            dmSearchOpen
                              ? 'bg-[var(--text-primary)] text-[var(--bg-primary)] hover:opacity-90'
                              : 'text-theme-muted hover:text-[var(--bg-primary)]'
                          }`}
                          title={t('dm.searchMessages')}
                        >
                          <Search className="w-3.5 h-3.5 flex-shrink-0" />
                        </Button>
                        <Button
                          onClick={(e) => e.stopPropagation()}
                          variant="ghostGray"
                          data-testid="dm-new-placeholder"
                          className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
                          title={t('dm.newMessage')}
                        >
                          <Plus className="w-4 h-4 flex-shrink-0" />
                        </Button>
                      </>
                    )}
                    {!messagesMode && showFilterChips && (
                      <Popover open={filterMenuOpen} onOpenChange={setFilterMenuOpen}>
                        <PopoverTrigger asChild>
                          <Button
                            onClick={(e) => e.stopPropagation()}
                            variant="ghostGray"
                            className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
                            title={t('sidebar.filterChats')}
                          >
                            <ListFilter className="w-3.5 h-3.5 flex-shrink-0" />
                          </Button>
                        </PopoverTrigger>
                        <PopoverContent
                          align="start"
                          sideOffset={5}
                          className="w-auto min-w-[140px] p-1.5 bg-theme-primary rounded-xl border border-gray-300/70 dark:border-gray-600/70"
                        >
                          <div className="space-y-0.5">
                            {(['all', 'agents', 'workflows'] as const).map((filter) => (
                              <button
                                key={filter}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setChatFilter(filter);
                                  setFilterMenuOpen(false);
                                }}
                                className={`w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs transition-colors ${
                                  chatFilter === filter
                                    ? 'bg-gray-100 dark:bg-gray-700 text-theme-primary'
                                    : 'text-theme-muted hover:bg-gray-100 dark:hover:bg-gray-700 hover:text-theme-primary'
                                }`}
                              >
                                {filter === 'all' ? t('sidebar.allChats') : filter === 'agents' ? t('sidebar.agentChats') : t('sidebar.workflowChats')}
                              </button>
                            ))}
                          </div>
                        </PopoverContent>
                      </Popover>
                    )}
                    {!messagesMode && conversations.length > 0 && onSearchClick && (
                      <Button
                        onClick={(e) => {
                          e.stopPropagation();
                          onSearchClick();
                        }}
                        variant="ghostGray"
                        className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
                        title={t('sidebar.searchChats')}
                      >
                        <Search className="w-3.5 h-3.5 flex-shrink-0" />
                      </Button>
                    )}
                    {!messagesMode && (
                      <Button
                        onClick={(e) => {
                          e.stopPropagation();
                          onNewChat?.();
                        }}
                        variant="ghostGray"
                        className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
                        title={t('sidebar.newChat')}
                      >
                        <Plus className="w-4 h-4 flex-shrink-0" />
                      </Button>
                    )}
                  </>
                }
                renderItem={() => null}
                emptyMessage={t('sidebar.noConversations')}
                sidebarCollapsed={sidebarCollapsed}
                isAuthenticated={isAuthenticated}
                scrollContainerRef={scrollContainerRef}
                isLoadingMore={isLoadingMore}
                error={error}
                customContent={
                  messagesMode ? (
                    <DmSidebarList filter={dmFilter} searchOpen={dmSearchOpen} />
                  ) : loading && conversations.length === 0 && sharedConversations.length === 0 ? (
                    // Skeleton loading for conversations
                    <div className="h-full flex flex-col px-4">
                      <div className="flex-1 space-y-0.5">
                        {Array.from({ length: 20 }).map((_, index) => (
                          <div key={`skeleton-${index}`} className="rounded-lg px-1 py-2">
                            <div className="flex items-center w-full min-w-0">
                              <div
                                className="h-4 bg-theme-tertiary rounded animate-pulse w-full"
                                style={{
                                  animationDelay: `${index * 50}ms`,
                                  maxWidth: `${60 + Math.random() * 30}%`
                                }}
                              ></div>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  ) : (
                    // Filtered conversations rendering
                    <div ref={scrollContainerRef} className="flex-1 min-h-0 sidebar-scroll">
                      <div className="space-y-0.5 px-4">
                        {error ? (
                          <div className="p-4 text-center text-red-500">
                            <p>{error}</p>
                            <button
                              onClick={() => window.location.reload()}
                              className="mt-2 text-sm text-blue-600 hover:text-blue-800"
                            >
                              Retry
                            </button>
                          </div>
                        ) : filteredConversations.length > 0 ? (
                          <>
                            {filteredConversations.map((conv, i) => renderConversationItem(conv, i))}
                            {isLoadingMore && (
                              <div className="p-2 text-center">
                                <LoadingSpinner size="sm" text="Loading more..." className="text-theme-secondary" />
                              </div>
                            )}
                          </>
                        ) : null}
                      </div>
                    </div>
                  )
                }
              />
            </div>
          </div>
        )}
      </div>

      {/* Delete Confirmation Modal */}
      <DeleteConversationModal
        isOpen={showDeleteModal}
        onClose={handleCancelDelete}
        onConfirm={handleConfirmDelete}
        conversationTitle={conversationToDelete?.title}
        isLoading={isDeleting}
        clearMode={!!conversationToDelete?.agentId}
      />

      {/* Share Link Dialog */}
      <ShareLinkDialog
        open={shareOpen}
        onOpenChange={setShareOpen}
        resourceType="CONVERSATION"
        resourceToken={conversationToShare?.id || ''}
        resourceName={conversationToShare?.title || ''}
      />

      {/* Project Multi-Step Modal */}
      {showProjectModal && (
        <ProjectMultiStepModal
          project={editingProject || undefined}
          onClose={() => {
            setShowProjectModal(false);
            setEditingProject(null);
          }}
          onSuccess={() => {
            setShowProjectModal(false);
            setEditingProject(null);
          }}
          onDelete={() => {
            setShowProjectModal(false);
            setEditingProject(null);
          }}
        />
      )}
    </div>
    </TooltipProvider>
  );
}
