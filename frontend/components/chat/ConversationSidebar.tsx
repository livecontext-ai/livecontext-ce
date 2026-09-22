'use client';

import React, { memo, useState, useRef, useCallback, useEffect, useMemo } from 'react';
import { usePathname, useRouter } from '@/i18n/navigation';
import { useSearchParams } from 'next/navigation';
import { useSidebarConversations } from '@/hooks/conversation/useSidebarConversations';
import { Conversation, conversationApi } from '@/lib/api/conversationApi';
// Keys live in one place: a raw array here collides by prefix with conversations.detail(id) and is
// missed by the invalidation that heals the list after a conversation is created.
import { queryKeys } from '@/lib/query-client';
import { DeleteConversationModal } from './DeleteConversationModal';
import {
  Trash2,
  MessageCircle,
  MessagesSquare,
  ChevronDown,
  Plus,
  Search,
  Workflow,
  Bot,
  CalendarClock,
  Webhook,
  MoreVertical,
  ExternalLink,
  ListFilter,
  Briefcase,
  Share2,
  Eraser
} from 'lucide-react';
import { getProjectIcon } from '@/components/project/ProjectMultiStepModal';
import { conversationDisplayTitle } from '@/lib/utils/conversationTitle';
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
import { SidebarNavigation } from '@/components/app/SidebarNavigation';

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
  onNavigate?: (path: string) => void;
}

/**
 * The conversations half of the sidebar: projects, the chat list, and the
 * navigation block it draws through {@link SidebarNavigation}.
 *
 * memo()'d because its parent re-renders far more often than this list changes.
 * The shell subscribes to auth, the user profile, the subscription, the credit
 * balance, the theme and its own search-modal state; none of that is drawn here,
 * and every one of those used to redraw the whole list - every row, every
 * popover trigger.
 *
 * It is NOT a blanket shield: this component reads the unified app context
 * itself (through its conversation hook), so a change there still re-renders it,
 * as it should. What the memo removes is the churn that has nothing to do with
 * conversations. It only works while every prop the shell passes is stable -
 * one inline arrow up there defeats it entirely, which is why they are all
 * memoized (see AppSidebar).
 */
export const ConversationSidebar = memo(function ConversationSidebar({
  onConversationSelect,
  currentConversationId,
  className = '',
  sidebarCollapsed = false,
  onConversationCreated,
  onTitleUpdated,
  onNewChat,
  onSearchClick,
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
  const [chatFilter, setChatFilter] = useState<'all' | 'agents' | 'workflows' | 'studio'>('all');
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
  const { isAuthenticated, isLoading: authLoading } = useAuthGuard();

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
  // Per-agent trigger flags (schedule / webhook) so a conversation's agent shows a small
  // badge next to its avatar. Backed by the lightweight GET /api/agents/triggers batch.
  const { data: fleetTriggers } = useOrgScopedQuery({
    queryKey: ['agents', 'triggers'] as const,
    queryFn: () => orchestratorApi.getFleetTriggers(),
    enabled: isAuthenticated,
    staleTime: 5 * 60 * 1000,
  });
  const agentTriggerMap = useMemo(() => {
    const map = new Map<string, { hasSchedule: boolean; hasWebhook: boolean }>();
    if (fleetTriggers) {
      for (const t of fleetTriggers) {
        if (t.hasSchedule || t.hasWebhook) map.set(t.agentId, { hasSchedule: t.hasSchedule, hasWebhook: t.hasWebhook });
      }
    }
    return map;
  }, [fleetTriggers]);
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

  // Get current view from URL. Which navigation entry is ACTIVE is not decided
  // here any more: SidebarNavigation resolves that once for the rail and the
  // panel together. What is left is the question this component owns - whether
  // the surface is one that lists conversations.
  const { view: currentView, conversationId: urlConversationId } = useCurrentView();
  const isConversationSurface = (
    currentView === 'chat' ||
    // The studio lists the same conversations in the same sidebar. It used to be covered only
    // because /app/studio fell through to the 'chat' view; giving it a view of its own would
    // otherwise have moved the studio home onto the 1200ms deferred load AND made every switch
    // between /app and /app/studio count as LEAVING a conversation surface, firing a full refresh.
    currentView === 'studio' ||
    urlConversationId !== null ||
    pathname?.startsWith('/app/chat') ||
    pathname?.startsWith('/app/c/') ||
    pathname?.startsWith('/app/studio')
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
    conversations,
    loading,
    error,
    hasMore: hasMoreFromServer,
    loadMore: loadMoreConversations,
    loadConversationById,
    deleteConversation,
    clearMessages,
    refresh: refreshConversations,
  } = useSidebarConversations({ autoLoad: deferredAutoLoad, currentConversationId });

  // Reconcile the conversation list with the server when the user LEAVES a
  // conversation surface (chat -> any other page).
  // The sidebar lives in the persistent /app layout, and after its first load
  // the React Query cache is intentionally sticky (staleTime: Infinity, no
  // refetch on mount/focus/reconnect). While on the chat surface the list is
  // kept current by live streaming updates (addConversations / updateConversation
  // emitted from the chat page); off that surface those updates stop. So a
  // conversation started right before navigating away can be missed (missing
  // row, placeholder title, wrong order) and, with no refetch, stay stale until
  // a full page reload. refresh() rewinds to page 0 and refetches from the
  // server, healing membership, titles and order regardless of which live event
  // was dropped (and even when the user had paginated the sidebar); the shared
  // context backstops the rows during the refetch so the list does not flicker.
  // Gating on the surface transition (not every navigation) keeps it to one
  // refetch per chat exit.
  const refreshConversationsRef = useRef(refreshConversations);
  refreshConversationsRef.current = refreshConversations;
  const wasConversationSurfaceRef = useRef(isConversationSurface);
  useEffect(() => {
    const leftConversationSurface = wasConversationSurfaceRef.current && !isConversationSurface;
    wasConversationSurfaceRef.current = isConversationSurface;
    if (!leftConversationSurface) return;
    if (!isAuthenticated || !shouldAutoLoad) return;
    void refreshConversationsRef.current();
  }, [isConversationSurface, isAuthenticated, shouldAutoLoad]);

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

  // Check if filter chips should be shown (at least one agent, workflow or studio conversation)
  const hasAgentConversations = useMemo(() => conversations.some(c => c.agentId), [conversations]);
  const hasWorkflowConversations = useMemo(() => conversations.some(c => c.workflowId), [conversations]);
  // Whether this workspace has ANY studio conversation, asked of the server rather than read off the
  // loaded page. Deriving it from the page would hide the chip from exactly the reader who needs it:
  // someone with forty studio threads and twenty recent chats has none in the window, so the chip
  // would not appear and the correctly-filtered list behind it would be unreachable.
  //
  // One row is enough to answer it, and the answer holds for the session.
  const { data: anyStudioPage } = useQuery({
    queryKey: queryKeys.conversations.hasKind('studio'),
    queryFn: () => conversationApi.getConversations(0, 1, 'studio'),
    enabled: isAuthenticated && deferredAutoLoad,
    // Never stale on its own. The answer only flips when a studio conversation is created, and that
    // path already invalidates ['conversations'], which prefix-matches this key. A time window
    // would buy nothing and cost a request per window.
    staleTime: Infinity,
  });
  const hasStudioConversations = useMemo(() => {
    const content = (anyStudioPage as { content?: unknown[] } | undefined)?.content;
    return Array.isArray(content) && content.length > 0;
  }, [anyStudioPage]);
  const showFilterChips = hasAgentConversations || hasWorkflowConversations || hasStudioConversations;

  // Studio conversations are fetched with the filter IN THE QUERY rather than narrowed out of the
  // list above.
  //
  // The list above is a page chosen by recency. Narrowing it answers "the studio conversations
  // among the most recent ones", which is empty for anyone whose recent activity is chat and looks
  // exactly like having none. The agents and workflows filters have that shape and are left alone
  // here: changing them is a separate decision about a shipped behaviour, and this one is new.
  //
  // Its own query key, so it neither reads nor disturbs the shared sidebar cache, and it is not
  // requested at all until the reader picks the filter.
  const { data: studioPage, isLoading: studioLoading } = useQuery({
    queryKey: queryKeys.conversations.ofKind('studio'),
    queryFn: () => conversationApi.getConversations(0, 50, 'studio'),
    enabled: isAuthenticated && chatFilter === 'studio',
    staleTime: 30_000,
  });
  const studioConversations = useMemo(() => {
    const content = (studioPage as { content?: Conversation[] } | undefined)?.content;
    return Array.isArray(content) ? content : [];
  }, [studioPage]);
  // While the studio query is in flight the list is empty, and an empty list renders the same
  // "nothing here" as a workspace that really has none. The loading state is kept distinct so the
  // list below shows the spinner it already has for the unfiltered case.
  const listLoading = chatFilter === 'studio' ? studioLoading : loading;

  // Filter conversations based on selected chip
  const filteredConversations = useMemo(() => {
    if (chatFilter === 'agents') return conversations.filter(c => c.agentId);
    if (chatFilter === 'workflows') return conversations.filter(c => c.workflowId);
    if (chatFilter === 'studio') return studioConversations;
    return conversations;
  }, [conversations, chatFilter, studioConversations]);

  // Agent avatars are derived from the ['agents', 'avatars'] query above
  // (CreateAgentModal invalidates ['agents'] - prefix-matches and refreshes us)

  // The studio filter reads its own query, not the paged list below it. Leaving the pager armed
  // would page the UNFILTERED list in the background - invisible work that never adds a row to what
  // is on screen, and an auto-loader that keeps firing because the visible list stays short.
  //
  // The studio list is therefore one page. That is a real cap, not a hidden one: it is the same
  // page size the sidebar shows for everything else, and the conversation search reaches the rest.
  const hasMoreConversations = chatFilter === 'studio' ? false : hasMoreFromServer;


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

    // A row the list has not cached yet (reached from search, or created in
    // another tab): pull it in so it stays in the sidebar behind the surface
    // that is about to open it. Fire-and-forget - navigation does not wait on it.
    if (!conversations.some((conv) => conv.id === conversation.id)) {
      console.log('🔄 Conversation not in loaded list, attempting to load it...');
      void loadConversationById(conversation.id);
    }

    // Messages are NOT loaded here: the chat surface loads them from the route
    // it is about to be on, and doing it twice raced two fetches for the same
    // conversation.
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
        // Agent conversations: clear messages only, keep the conversation.
        // clearMessages announces the wipe, so the surface actually SHOWING the
        // transcript empties it. The sidebar used to clear a private copy of the
        // messages that nothing rendered, leaving the open conversation on
        // screen until a reload.
        console.log('🧹 [SIDEBAR] Clearing messages for agent conversation:', conversationToDelete.id);
        await clearMessages(conversationToDelete.id);

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
            {conversation.agentId && agentTriggerMap.has(conversation.agentId) && (
              <span className="ml-0.5 inline-flex items-center gap-0.5 flex-shrink-0">
                {agentTriggerMap.get(conversation.agentId)!.hasSchedule && (
                  <CalendarClock className="w-3 h-3 text-theme-muted" aria-label={t('sidebar.scheduledAgent')} />
                )}
                {agentTriggerMap.get(conversation.agentId)!.hasWebhook && (
                  <Webhook className="w-3 h-3 text-theme-muted" aria-label={t('sidebar.webhookAgent')} />
                )}
              </span>
            )}
          </div>

          {/* 3-dot menu button */}
          <Popover open={openMenuId === conversation.id} onOpenChange={(open) => setOpenMenuId(open ? conversation.id : null)}>
            <PopoverTrigger asChild>
              <Button
                onClick={(e) => e.stopPropagation()}
                variant="ghostGray"
                className="absolute right-1 top-1/2 -translate-y-1/2 w-5 h-5 p-0 rounded-lg text-theme-muted opacity-0 group-hover:opacity-100 group-hover:bg-surface-hover transition-opacity"
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
                      // No per-agent page exists: `/app/agent/<id>` 404s. The agent opens
                      // in the right-side panel through the `?openAgent=<id>` deep link
                      // handled by AgentTable (same entry point as the notification rows).
                      const target = `/app/agent?openAgent=${conversation.agentId}`;
                      if (onNavigate) {
                        onNavigate(target);
                      } else {
                        router.push(target);
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

  // Projects: the sidebar's own content, drawn inside the navigation block's
  // scroll region. Handed to SidebarNavigation as a slot so that component stays
  // the only thing that knows the block's shape, while the rows keep living here.
  //
  // Deliberately NOT memoized, though it is a prop of a memo()'d component. A
  // `useMemo` here would need a hand-written list of everything the block reads
  // (`projectMenuId` among them, which decides whether a project's menu is
  // open), and a value missing from that list renders as a dead button rather
  // than as a caching bug - invisible to the tests, because the `next-intl`
  // mock returns a fresh translator each render and so defeats the memo in the
  // test environment specifically. The saving would be small anyway: what is
  // expensive is the ROWS, and those are memoized per entry inside
  // SidebarNavigation with stable props, so they short-circuit whether or not
  // their parent re-renders.
  const projectsSection = !sidebarCollapsed ? (
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
            className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-lg text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
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
                  className="absolute right-1 top-1/2 -translate-y-1/2 w-5 h-5 p-0 rounded-lg text-theme-muted opacity-0 group-hover:opacity-100 group-hover:bg-surface-hover transition-opacity"
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
  ) : null;

  // The two things a navigation entry can do, in the panel exactly as in the
  // rail: open a fresh conversation, or go to a page under the app's navigation
  // guard. Both are handed to SidebarNavigation, which adds the click reporting.
  const handleNewChat = useCallback(() => onNewChat?.(), [onNewChat]);
  const handleNavItemNavigate = useCallback(
    (path: string) => {
      if (onNavigate) {
        onNavigate(path);
      } else {
        router.push(path);
      }
    },
    [onNavigate, router],
  );

  // Render conversation list. Single TooltipProvider scoped to the sidebar so
  // every conversation row's hover pill shares one delayed timer + portal -
  // cheaper than mounting one provider per row.
  return (
    <TooltipProvider delayDuration={400} skipDelayDuration={200}>
    <div className={`bg-theme-secondary flex flex-col h-full relative overflow-hidden ${className}`}>
      {/* Conversation View */}
      <div className="flex flex-col h-full">
        {/* Every navigation entry the sidebar draws, in the panel's shape. The
            SAME component draws the collapsed rail in the header above (see
            AppSidebar), off the same resolved list, so the two states of the
            sidebar cannot show different pages or light different entries. */}
        <SidebarNavigation
          variant="panel"
          active={!sidebarCollapsed}
          currentConversationId={currentConversationId}
          onNewChat={handleNewChat}
          onNavigate={handleNavItemNavigate}
          projectsSlot={projectsSection}
        />

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
                      className={`w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-lg transition-all font-normal flex items-center justify-center opacity-100 ${
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
                              className={`w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-lg transition-colors font-normal flex items-center justify-center ${
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
                          className={`w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-lg transition-colors font-normal flex items-center justify-center ${
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
                          className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-lg text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
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
                            className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-lg text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
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
                            {(['all', 'agents', 'workflows', 'studio'] as const).map((filter) => (
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
                                {filter === 'all'
                                  ? t('sidebar.allChats')
                                  : filter === 'agents'
                                    ? t('sidebar.agentChats')
                                    : filter === 'workflows'
                                      ? t('sidebar.workflowChats')
                                      : t('sidebar.studioChats')}
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
                        className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-lg text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
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
                        className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-lg text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
                        title={t('sidebar.nav.newChat')}
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
                  ) : loading && conversations.length === 0 ? (
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
                        ) : listLoading ? (
                          // An empty list and a list still arriving look identical otherwise, and
                          // the first reads as "you have none" for conversations that exist. Only
                          // reachable for a filter that fetches its own rows; the unfiltered list is
                          // already on screen by the time this renders.
                          <div className="p-2 text-center">
                            <LoadingSpinner size="sm" className="text-theme-secondary" />
                          </div>
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
});
