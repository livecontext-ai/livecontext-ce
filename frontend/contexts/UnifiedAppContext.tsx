'use client';

/**
 * UnifiedAppContext - Single source of truth for app-wide state
 *
 * Merges functionality from:
 * - ChatSyncContext (UI state, localStorage persistence, navigation)
 * - ConversationContext (conversation management, pagination)
 *
 * This eliminates the duplicate currentConversationId and conversations state
 * that was causing synchronization issues.
 */

import React, { createContext, useContext, useState, useEffect, useCallback, useMemo, ReactNode, useRef } from 'react';
import { usePathname } from 'next/navigation';
import { SelectedModel, EMPTY_SELECTED_MODEL, toSelectedModel, formatSelectedModel } from '@/hooks/useModels';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { sortByRecency } from '@/lib/utils/conversationRecency';

// ============== TYPES ==============

export interface ConversationData {
  id: string;
  title: string;
  updatedAt?: string;
  createdAt?: string;
  workflowId?: string;
  agentId?: string;
}

interface UIState {
  sidebarOpen: boolean;
  sidebarCollapsed: boolean;
  isNavigatingToNewChat: boolean;
  isProfileOpen: boolean;
  showModelSelector: boolean;
  showToolSelector: boolean;
  /**
   * Typed { provider, id } pair. Single source of truth for the user's
   * model choice. Using an object (not a "provider:id" string) forces every
   * call-site to handle the two fields explicitly - the compiler catches
   * the class of bug where a call-site forgets to strip the "provider:"
   * prefix before sending {@code model} to the backend.
   */
  selectedModel: SelectedModel;
  /**
   * Per-conversation reasoning-effort override for CLI/bridge models
   * (minimal|low|medium|high|xhigh); "" = inherit (per-agent / per-model default).
   * Ephemeral - intentionally NOT persisted to localStorage.
   */
  reasoningEffort: string;
  selectedTools: string[];
  mode: 'auto' | 'manual';
  toolSearchQuery: string;
  selectedCategory: string;
}

interface ConversationState {
  currentConversationId: string | null;
  conversations: ConversationData[];
  hasMore: boolean;
  currentPage: number;
}

type AppState = UIState & ConversationState;

interface UnifiedAppContextType {
  // Full state access
  state: AppState;

  // UI State setters
  setSidebarOpen: (open: boolean) => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setIsNavigatingToNewChat: (navigating: boolean) => void;
  setIsProfileOpen: (open: boolean) => void;
  setShowModelSelector: (show: boolean) => void;
  setShowToolSelector: (show: boolean) => void;
  /**
   * Set the user's current model choice. Takes the typed pair only - callers
   * that hold a string (e.g. reading legacy localStorage) MUST normalise via
   * {@link toSelectedModel} at their boundary. Keeping the union out of this
   * signature is what prevents the "oops I passed the qualified string and
   * downstream tried to use .id which still has a colon" class of bug.
   */
  setSelectedModel: (model: SelectedModel) => void;
  setReasoningEffort: (effort: string) => void;
  setSelectedTools: (tools: string[]) => void;
  setMode: (mode: 'auto' | 'manual') => void;
  setToolSearchQuery: (query: string) => void;
  setSelectedCategory: (category: string) => void;

  // Conversation setters
  setCurrentConversationId: (id: string | null) => void;
  setConversations: (conversations: ConversationData[]) => void;
  addConversations: (conversations: ConversationData[]) => void;
  updateConversation: (conversationId: string, updates: Partial<{ title: string; updatedAt: string }>) => void;
  removeConversation: (conversationId: string) => void;
  setHasMore: (hasMore: boolean) => void;
  setCurrentPage: (page: number) => void;

  // Utility
  resetState: () => void;
}

// ============== INITIAL STATE ==============

const initialUIState: UIState = {
  sidebarOpen: false,
  sidebarCollapsed: false,
  isNavigatingToNewChat: false,
  isProfileOpen: false,
  showModelSelector: false,
  showToolSelector: false,
  selectedModel: EMPTY_SELECTED_MODEL,
  reasoningEffort: '',
  selectedTools: [],
  mode: 'auto',
  toolSearchQuery: '',
  selectedCategory: 'all',
};

const initialConversationState: ConversationState = {
  currentConversationId: null,
  conversations: [],
  hasMore: true,
  currentPage: 0,
};

const initialState: AppState = {
  ...initialUIState,
  ...initialConversationState,
};

// ============== CONTEXT ==============

const UnifiedAppContext = createContext<UnifiedAppContextType | null>(null);

// ============== PROVIDER ==============

export function UnifiedAppProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AppState>(initialState);
  const pathname = usePathname();
  const isInitializedRef = useRef(false);

  // ============== LOCALSTORAGE PERSISTENCE ==============

  // Load persisted UI state on mount
  useEffect(() => {
    if (isInitializedRef.current) return;
    isInitializedRef.current = true;

    const savedState = localStorage.getItem('unifiedAppState');
    if (savedState) {
      try {
        const parsed = JSON.parse(savedState);
        // Only restore UI preferences, not ephemeral state. selectedModel may
        // be a legacy string ("provider:id" or bare id) written by a previous
        // app version - toSelectedModel handles both string and typed object
        // inputs, so the in-memory shape is always the typed pair.
        setState(prev => ({
          ...prev,
          selectedModel: parsed.selectedModel
            ? toSelectedModel(parsed.selectedModel)
            : prev.selectedModel,
          selectedTools: parsed.selectedTools || prev.selectedTools,
          mode: parsed.mode || prev.mode,
          selectedCategory: parsed.selectedCategory || prev.selectedCategory,
          // Don't restore: sidebarOpen, sidebarCollapsed, currentConversationId, isNavigatingToNewChat
        }));
      } catch (error) {
        console.error('[UnifiedAppContext] Error loading state from localStorage:', error);
      }
    }
  }, []);

  // Save UI preferences to localStorage
  useEffect(() => {
    if (!isInitializedRef.current) return;

    const persistableState = {
      // Persist as "provider:id" string so an older app version reading the
      // key still finds a familiar shape. The loader above accepts both.
      selectedModel: formatSelectedModel(state.selectedModel),
      selectedTools: state.selectedTools,
      mode: state.mode,
      selectedCategory: state.selectedCategory,
    };
    localStorage.setItem('unifiedAppState', JSON.stringify(persistableState));
  }, [state.selectedModel, state.selectedTools, state.mode, state.selectedCategory]);

  // ============== PATH-AWARE NAVIGATION RESET ==============

  useEffect(() => {
    const pathWithoutLocale = pathname?.replace(/^\/[a-z]{2}(?=\/|$)/, '') || '';

    const isChatPage = pathWithoutLocale.startsWith('/chat') ||
                       pathWithoutLocale.startsWith('/app/c/') ||
                       pathWithoutLocale === '/app' ||
                       pathWithoutLocale === '/app/chat' ||
                       pathWithoutLocale === '';

    const isNewChatPage = pathWithoutLocale === '/app' ||
                          pathWithoutLocale === '/app/chat' ||
                          pathWithoutLocale === '';

    if (!isChatPage) {
      // Navigating away from chat entirely - reset conversation state (only if needed)
      setState(prev => {
        if (prev.currentConversationId === null && !prev.isNavigatingToNewChat) {
          return prev; // No change needed - avoid re-render
        }
        console.log('[UnifiedAppContext] Navigating away from chat - resetting conversation state');
        return {
          ...prev,
          currentConversationId: null,
          isNavigatingToNewChat: false,
        };
      });
    } else if (isNewChatPage && state.isNavigatingToNewChat) {
      // Navigation to new chat complete
      console.log('[UnifiedAppContext] New chat navigation complete');
      setState(prev => ({
        ...prev,
        isNavigatingToNewChat: false,
        currentConversationId: null,
      }));
    }
  }, [pathname, state.isNavigatingToNewChat]);

  // ============== WORKSPACE SWITCH RESET ==============
  // 2026-05-18 - when the active org changes, drop the cached conversation
  // list and the current conversation pointer. Without this, addConversations
  // (which only appends, never removes) leaks the previous workspace's
  // sidebar entries into the new workspace; the sidebar even prefers the
  // longer shared list over the freshly-fetched rawConversations, so stale
  // chats stay clickable across workspaces. Reacting on currentOrgId === null
  // covers the "switch back to personal" case too.
  useEffect(() => {
    return useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => {
        setState(prev => {
          if (prev.conversations.length === 0 && prev.currentConversationId === null) {
            return prev;
          }
          console.log('[UnifiedAppContext] Workspace switched - clearing cached conversations');
          return {
            ...prev,
            conversations: [],
            currentConversationId: null,
            hasMore: true,
            currentPage: 0,
          };
        });
      },
    );
  }, []);

  // ============== HELPER: SORT BY DATE ==============

  // Shared recency ordering (see lib/utils/conversationRecency) so the cached
  // conversation list here and the ConversationSidebar merge stay identically
  // ordered and cannot drift.
  const sortByDate = useCallback((convs: ConversationData[]) => sortByRecency(convs), []);

  // ============== UI STATE SETTERS ==============

  const setSidebarOpen = useCallback((open: boolean) => {
    setState(prev => ({ ...prev, sidebarOpen: open }));
  }, []);

  const setSidebarCollapsed = useCallback((collapsed: boolean) => {
    setState(prev => ({ ...prev, sidebarCollapsed: collapsed }));
  }, []);

  const setIsNavigatingToNewChat = useCallback((navigating: boolean) => {
    setState(prev => ({ ...prev, isNavigatingToNewChat: navigating }));
  }, []);

  const setIsProfileOpen = useCallback((open: boolean) => {
    setState(prev => ({ ...prev, isProfileOpen: open }));
  }, []);

  const setShowModelSelector = useCallback((show: boolean) => {
    setState(prev => ({ ...prev, showModelSelector: show }));
  }, []);

  const setShowToolSelector = useCallback((show: boolean) => {
    setState(prev => ({ ...prev, showToolSelector: show }));
  }, []);

  const setSelectedModel = useCallback((model: SelectedModel) => {
    // Defensive normalisation - toSelectedModel is idempotent on a well-formed
    // SelectedModel but guards against a caller passing a half-built object
    // with missing provider (e.g. straight from a deserialised API response).
    setState(prev => ({ ...prev, selectedModel: toSelectedModel(model) }));
  }, []);

  const setReasoningEffort = useCallback((effort: string) => {
    setState(prev => ({ ...prev, reasoningEffort: effort }));
  }, []);

  const setSelectedTools = useCallback((tools: string[]) => {
    setState(prev => ({ ...prev, selectedTools: tools }));
  }, []);

  const setMode = useCallback((mode: 'auto' | 'manual') => {
    setState(prev => ({ ...prev, mode }));
  }, []);

  const setToolSearchQuery = useCallback((query: string) => {
    setState(prev => ({ ...prev, toolSearchQuery: query }));
  }, []);

  const setSelectedCategory = useCallback((category: string) => {
    setState(prev => ({ ...prev, selectedCategory: category }));
  }, []);

  // ============== CONVERSATION STATE SETTERS ==============

  const setCurrentConversationId = useCallback((id: string | null) => {
    console.log('[UnifiedAppContext] setCurrentConversationId:', id);
    setState(prev => ({ ...prev, currentConversationId: id }));
  }, []);

  const setConversations = useCallback((conversations: ConversationData[]) => {
    setState(prev => ({ ...prev, conversations: sortByDate(conversations) }));
  }, [sortByDate]);

  const addConversations = useCallback((newConversations: ConversationData[]) => {
    setState(prev => {
      const existingIds = new Set(prev.conversations.map(c => c.id));
      const unique = newConversations.filter(c => !existingIds.has(c.id));

      if (unique.length === 0) {
        return prev; // No change needed
      }

      console.log('[UnifiedAppContext] Adding', unique.length, 'new conversations');
      return {
        ...prev,
        conversations: sortByDate([...prev.conversations, ...unique]),
      };
    });
  }, [sortByDate]);

  const updateConversation = useCallback((conversationId: string, updates: Partial<{ title: string; updatedAt: string }>) => {
    console.log('[UnifiedAppContext] updateConversation:', conversationId, updates);
    setState(prev => {
      const exists = prev.conversations.some(c => c.id === conversationId);
      if (!exists) {
        console.warn('[UnifiedAppContext] Conversation not found for update:', conversationId);
        return prev;
      }

      const updated = prev.conversations.map(c =>
        c.id === conversationId ? { ...c, ...updates } : c
      );
      return { ...prev, conversations: sortByDate(updated) };
    });
  }, [sortByDate]);

  const removeConversation = useCallback((conversationId: string) => {
    console.log('[UnifiedAppContext] removeConversation:', conversationId);
    setState(prev => ({
      ...prev,
      conversations: prev.conversations.filter(c => c.id !== conversationId),
      // Clear current if it was the removed one
      currentConversationId: prev.currentConversationId === conversationId ? null : prev.currentConversationId,
    }));
  }, []);

  const setHasMore = useCallback((hasMore: boolean) => {
    setState(prev => ({ ...prev, hasMore }));
  }, []);

  const setCurrentPage = useCallback((currentPage: number) => {
    setState(prev => ({ ...prev, currentPage }));
  }, []);

  // ============== UTILITY ==============

  const resetState = useCallback(() => {
    console.log('[UnifiedAppContext] Resetting state');
    setState(initialState);
  }, []);

  // ============== CONTEXT VALUE ==============

  const value = useMemo<UnifiedAppContextType>(() => ({
    state,
    // UI
    setSidebarOpen,
    setSidebarCollapsed,
    setIsNavigatingToNewChat,
    setIsProfileOpen,
    setShowModelSelector,
    setShowToolSelector,
    setSelectedModel,
    setReasoningEffort,
    setSelectedTools,
    setMode,
    setToolSearchQuery,
    setSelectedCategory,
    // Conversation
    setCurrentConversationId,
    setConversations,
    addConversations,
    updateConversation,
    removeConversation,
    setHasMore,
    setCurrentPage,
    // Utility
    resetState,
  }), [
    state,
    setSidebarOpen, setSidebarCollapsed, setIsNavigatingToNewChat,
    setIsProfileOpen, setShowModelSelector, setShowToolSelector,
    setSelectedModel, setReasoningEffort, setSelectedTools, setMode,
    setToolSearchQuery, setSelectedCategory,
    setCurrentConversationId, setConversations, addConversations,
    updateConversation, removeConversation, setHasMore, setCurrentPage,
    resetState,
  ]);

  return (
    <UnifiedAppContext.Provider value={value}>
      {children}
    </UnifiedAppContext.Provider>
  );
}

// ============== HOOKS ==============

/**
 * Main hook - throws if used outside provider
 */
export function useUnifiedApp() {
  const context = useContext(UnifiedAppContext);
  if (!context) {
    throw new Error('useUnifiedApp must be used within UnifiedAppProvider');
  }
  return context;
}

/**
 * Safe version - returns null if outside provider
 */
export function useUnifiedAppSafe(): UnifiedAppContextType | null {
  return useContext(UnifiedAppContext);
}

