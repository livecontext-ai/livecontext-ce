'use client';

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { usePathname } from 'next/navigation';
import { useSidebarSafe } from '@/contexts/SidebarContext';
import { useCurrentView } from '@/hooks/useCurrentView';
import { useUnifiedAppSafe } from '@/contexts/UnifiedAppContext';
import { useBreadcrumbs } from '@/hooks/useBreadcrumbs';
import { ChatHeader } from '@/components/chat/ChatHeader';
import { GlobalSearchBar } from '@/components/search/GlobalSearchBar';
import { useVisibleModels, AIModel, SelectedModel, EMPTY_SELECTED_MODEL, selectedModelFromAIModel, modelMatches, selectedModelEquals, getEffectiveDefaultSelectedModel } from '@/hooks/useModels';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import { useSidePanelSafe, stripLocale } from '@/contexts/SidePanelContext';
import { useAuth } from '@/lib/providers/smart-providers';
import { useMobileDetection } from '@/hooks/useMobileDetection';
import { AgentPanelContent, AGENT_CONFIGURATION_TAB, AGENT_CONVERSATION_TAB } from '@/components/app/AgentPanelContent';
import { useDmPeerStore } from '@/lib/stores/dm-peer-store';
import {
  openAiChatTab,
  registerAiChatTab,
  isAiChatExcludedPath,
  AI_CHAT_TAB_ID,
} from '@/lib/sidePanel/openAiChatTab';
import { orchestratorApi } from '@/lib/api';
import { storageApi } from '@/lib/api/storage-api';
import { Workflow, Table, Monitor, Bot, Globe, Play } from 'lucide-react';
import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';

import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';
import { InterfacePanelContent } from '@/components/app/InterfacePanelContent';
import { buildApplicationPanelTab } from '@/lib/sidePanel/applicationPanelTab';
import { AgentBrowsePanelContent } from '@/components/app/AgentBrowsePanelContent';
import { EditMetadataModal, EditMetadataResourceType } from '@/components/app/EditMetadataModal';
import Toast, { useToast } from '@/components/Toast';
import { useTranslations } from 'next-intl';
import { PROVIDER_ICON_MAP } from '@/lib/ai-providers/providerIcons';
import { buildWorkflowPanelTab, useAutoRegisterWorkflowPanelTab } from '@/lib/sidePanel/workflowPanelTab';
import { buildAgentConfigPanelTab } from '@/lib/sidePanel/agentConfigPanelTab';
import { fetchLinkedAgent } from '@/lib/chat/linkedAgent';
import {
  emitFilesDetailCommand,
  emitFilesFolderNavigate,
  FILES_DETAIL_BACK,
  FILES_DETAIL_PREV,
  FILES_DETAIL_NEXT,
  FILES_DETAIL_DOWNLOAD,
} from '@/lib/files/filesHeaderBus';

export function AppHeader() {
  const safeNavigate = useSafeNavigate();
  const pathname = usePathname();
  const normalizedPathname = stripLocale(pathname);
  const { models, defaultModel, isLoading: modelsLoading } = useVisibleModels();

  // Use native Next.js routing hooks
  const sidebarContext = useSidebarSafe();
  const sidebarOpen = sidebarContext?.isOpen ?? false;
  const setSidebarOpen = sidebarContext?.setOpen ?? (() => {});

  const { view: currentView, conversationId, workflowId, dataSourceId } = useCurrentView();
  const showProfileView = normalizedPathname?.startsWith('/app/settings') ?? false;

  // Transform models to the format expected by ChatHeader. We spread the full
  // AIModel so the chat header can reuse {@link ModelOptionDisplay} /
  // {@link ModelInfoPopover} (capability icons, tier badge, popover with rate
  // limits) - those components type on AIModel directly. providerSlug /
  // iconSlug / description are header-specific overlays.
  const availableModels = useMemo(() => {
    return models.map((model: AIModel) => ({
      ...model,
      provider: model.provider.charAt(0).toUpperCase() + model.provider.slice(1),
      providerSlug: model.provider.toLowerCase(),
      iconSlug: PROVIDER_ICON_MAP[model.provider.toLowerCase()] || model.provider.toLowerCase(),
      description: model.isDefault ? 'Default model' : '',
    }));
  }, [models]);

  const {
    breadcrumbItems,
    shouldShowBreadcrumb,
    isDataViewWithDataSource,
    isWorkflowViewWithWorkflow,
    isInterfacePage,
    interfaceId,
    interfaceType,
    isRunMode,
    isApplicationPage,
    isMarketplacePreview,
    previewPublicationId,
    isAgentMarketplacePreview,
    agentPreviewPublicationId,
    isAgentFleet,
    isFilesView,
    filesDetail,
  } = useBreadcrumbs();

  // Files focused-viewer: the header owns back/prev/next/info/download; it fires
  // commands at FileBrowser over the shared bus (FileBrowser owns the actual
  // detail state + download, and broadcasts it back into `filesDetail`).
  const isFileOpen = isFilesView && !!filesDetail?.open;
  // V313: the manual-folder trail the browser is inside (root → … → current).
  const folderTrail = filesDetail?.folderTrail ?? [];
  const insideFolder = isFilesView && folderTrail.length > 0;
  // Show the header Back when a file is open OR when we're inside a folder (so the
  // user can step up one level). The prev/next/download sub-controls stay gated to
  // an open file (passed as isFileOpen below).
  const isFilesDetail = isFileOpen || insideFolder;
  // Folder-aware back: close the viewer if a file is open, otherwise step up one
  // folder (to the parent folder, or to root when at a top-level folder). At the
  // very top with no file open there is nothing to do (the button isn't shown).
  const handleFilesBack = () => {
    if (isFileOpen) {
      emitFilesDetailCommand(FILES_DETAIL_BACK);
      return;
    }
    if (folderTrail.length > 0) {
      const parent = folderTrail[folderTrail.length - 2];
      emitFilesFolderNavigate(parent ? parent.id : null);
    }
  };

  // Use safe version of useUnifiedApp to handle cases where component is rendered outside provider
  const appContext = useUnifiedAppSafe();
  const defaultAIModel: AIModel | undefined = useMemo(
    () => (defaultModel ? models.find(m => m.id === defaultModel) : undefined) ?? models[0],
    [models, defaultModel],
  );
  const effectiveDefault: SelectedModel = useMemo(
    () => (defaultAIModel ? selectedModelFromAIModel(defaultAIModel) : getEffectiveDefaultSelectedModel()),
    [defaultAIModel],
  );
  const appState = appContext?.state ?? {
    selectedModel: effectiveDefault,
    reasoningEffort: '',
    showModelSelector: false,
  };
  const setShowModelSelector = appContext?.setShowModelSelector ?? (() => {});
  const setSelectedModel = appContext?.setSelectedModel ?? ((_: SelectedModel) => {});
  const setReasoningEffort = appContext?.setReasoningEffort ?? ((_: string) => {});

  // Validate that the stored selection still exists in the available-models list.
  // With the typed SelectedModel shape, the check is a single modelMatches call -
  // no string splitting, no "forgot to strip the prefix" class of bug.
  useEffect(() => {
    if (!appContext || models.length === 0 || !effectiveDefault.id) return;
    const sel = appState.selectedModel;
    const isValid = !!sel && !!sel.id && models.some(m => modelMatches(m, sel));
    if (!isValid && !selectedModelEquals(sel, effectiveDefault)) {
      console.log('[AppHeader] Invalid model detected, switching to default:', effectiveDefault);
      setSelectedModel(effectiveDefault);
    }
  }, [models, effectiveDefault, appState.selectedModel, setSelectedModel, appContext]);

  // Detect if we're on a conversation page with a specific conversation ID (/app/c/[cid])
  // Don't show agent config on /app or /app/chat (no conversation to link)
  const isConversationPage = normalizedPathname?.startsWith('/app/c/') || false;

  // Local state
  const [showMessagesPanel, setShowMessagesPanel] = useState(false);

  // Agent state for conversation-linked agent
  const [agentName, setAgentName] = useState<string | null>(null);
  const [agentAvatarUrl, setAgentAvatarUrl] = useState<string | null>(null);
  const [agentId, setAgentId] = useState<string | null>(null);
  const [agentModelName, setAgentModelName] = useState<string | null>(null);
  const [agentModelProvider, setAgentModelProvider] = useState<string | null>(null);
  // DM mode: while viewing a DM thread, the header shows the other participant's avatar + name in
  // place of the model selector (reusing the agent-avatar header slot). DmThreadView publishes the
  // peer to this store while the thread is open and clears it on unmount.
  const dmPeer = useDmPeerStore((s) => s.peer);
  const loadedAgentForConversationRef = useRef<string | null>(null);

  // Resolve agentId from shared conversations (agentId is stored on the conversation, not the agent entity)
  const sharedConversations = appContext?.state?.conversations;
  const resolvedAgentId = useMemo(() => {
    if (!conversationId || !sharedConversations) return null;
    const conv = sharedConversations.find(c => c.id === conversationId);
    return conv?.agentId || null;
  }, [conversationId, sharedConversations]);

  // Load linked agent when on a conversation page
  // Track as "conversationId:agentId" so we re-fetch when resolvedAgentId becomes available
  useEffect(() => {
    if (!conversationId) {
      if (loadedAgentForConversationRef.current !== null) {
        loadedAgentForConversationRef.current = null;
        setAgentName(null);
        setAgentAvatarUrl(null);
        setAgentId(null);
        setAgentModelName(null);
        setAgentModelProvider(null);
      }
      return;
    }

    const loadKey = `${conversationId}:${resolvedAgentId || ''}`;
    if (loadedAgentForConversationRef.current === loadKey) return;
    loadedAgentForConversationRef.current = loadKey;

    const applyAgent = (agent: import('@/lib/api/orchestrator').Agent | null) => {
      if (agent) {
        setAgentName(agent.name);
        setAgentAvatarUrl(agent.avatarUrl || null);
        setAgentId(agent.id);
        setAgentModelName(agent.modelName || null);
        setAgentModelProvider(agent.modelProvider || null);
      } else {
        setAgentName(null);
        setAgentAvatarUrl(null);
        setAgentId(null);
        setAgentModelName(null);
        setAgentModelProvider(null);
      }
    };

    // Prefer the conversation's forward link (conversations.agent_id, resolvedAgentId);
    // fall back to the reverse by-conversation lookup only when it is unknown.
    fetchLinkedAgent(orchestratorApi, { linkedAgentId: resolvedAgentId, conversationId })
      .then((agent) => applyAgent(agent ?? null))
      .catch(() => applyAgent(null));
  }, [conversationId, resolvedAgentId]);

  const sidePanel = useSidePanelSafe();
  const isMobile = useMobileDetection();
  const isAgentConfigOpen = sidePanel?.isOpen && sidePanel?.activeTabId?.startsWith('agent-');
  const { isLoading: authLoading } = useAuth();

  // Auto-register the AI Chat tab on every page EXCEPT pages that:
  //   1. embed AI Chat inside another panel (workflow / application / marketplace preview)
  //   2. ARE the chat (chat home / `/app/chat/*` / conversation pages) - duplicating would be redundant.
  // The SidePanelContext scope filter drops the tab on navigation into an excluded page;
  // this effect only re-adds on allowed pages.
  //
  // Stability: depends on `addTabFn` (stable useCallback ref), `hasAiChatTab` (derived
  // boolean - flips only when the AI Chat tab itself appears/disappears), and the path.
  // `sidePanel.tabs` is NOT a dependency, so unrelated tab mutations don't re-run this.
  const addTabFn = sidePanel?.addTab;
  const hasAiChatTab = !!sidePanel?.tabs.some(t => t.id === AI_CHAT_TAB_ID);
  useEffect(() => {
    if (authLoading) return;                          // wait for auth before fetch-driven content
    if (!addTabFn) return;
    if (hasAiChatTab) return;                         // already registered - no churn
    if (!normalizedPathname) return;                  // hydration window: defer registration
    if (isAiChatExcludedPath(normalizedPathname)) return;
    registerAiChatTab({ addTab: addTabFn });
  }, [authLoading, addTabFn, hasAiChatTab, normalizedPathname]);

  useAutoRegisterWorkflowPanelTab(isWorkflowViewWithWorkflow, workflowId);

  // Agent link/unlink handlers
  const handleAgentUpdated = useCallback((agent: import('@/lib/api/orchestrator').Agent) => {
    setAgentName(agent.name);
    setAgentAvatarUrl(agent.avatarUrl || null);
    setAgentId(agent.id);
  }, []);

  const handleAgentUnlinked = useCallback(() => {
    setAgentName(null);
    setAgentAvatarUrl(null);
    setAgentId(null);
    loadedAgentForConversationRef.current = null;
  }, []);

  // Side panel toggle - conversation pages: open agent tab when an agent is linked, otherwise just toggle
  const handleToggleRightPanel = useCallback(() => {
    if (!sidePanel) return;
    if (sidePanel.isOpen) {
      sidePanel.close();
    } else if (agentId) {
      sidePanel.openTab(buildAgentConfigPanelTab({ agentId, agentName, agentAvatarUrl }));
    } else {
      sidePanel.open();
    }
  }, [sidePanel, agentId, agentName, agentAvatarUrl]);

  // Routes where the AI Chat side-panel tab is hidden - used to wire the page-specific
  // toggle handler (open/close-only on these pages, since chat is the primary view or
  // already embedded). Single source of truth: AI_CHAT_EXCLUDE_SCOPE in openAiChatTab.tsx.
  const isAiChatHiddenPage = isAiChatExcludedPath(normalizedPathname);

  // Side panel toggle - non-chat pages: pinned "AI Chat" tab as first tab.
  // The AI Chat tab is auto-registered globally (see effect above) so this handler
  // just makes sure the tab is open + active when the user clicks the toggle.
  const handleToggleSidePanel = useCallback(() => {
    if (!sidePanel) return;
    if (sidePanel.isOpen) {
      sidePanel.close();
    } else {
      openAiChatTab(sidePanel);
    }
  }, [sidePanel]);

  // Side panel toggle - chat pages: no default tab, just open/close
  const handleTogglePanelOnly = useCallback(() => {
    if (!sidePanel) return;
    if (sidePanel.isOpen) {
      sidePanel.close();
    } else {
      sidePanel.open();
    }
  }, [sidePanel]);

  // Side panel toggle - workflow/application pages: pinned "Workflow Panel" tab
  // For application mode and marketplace preview, workflowId is not in the URL -
  // ApplicationDetailView creates the tab directly, so we just toggle the panel open/close.
  const handleToggleWorkflowPanel = useCallback(() => {
    if (!sidePanel) return;
    if (sidePanel.isOpen) {
      sidePanel.close();
    } else if (workflowId) {
      sidePanel.openTab(buildWorkflowPanelTab(workflowId));
    } else {
      // Application mode / marketplace preview: workflowId not in URL, tab already created by ApplicationDetailView
      sidePanel.open();
    }
  }, [sidePanel, workflowId]);

  // Listen for programmatic open requests from WorkflowDetailView
  // (e.g., when a trigger node is clicked and the panel isn't open yet)
  useEffect(() => {
    if (!isWorkflowViewWithWorkflow && !isApplicationPage && !isMarketplacePreview) return;

    const handler = (event: CustomEvent) => {
      if (!sidePanel) return;

      // Toggle mode: open if closed, close if open
      if (event.detail?.toggle) {
        if (sidePanel.isOpen) {
          sidePanel.close();
        } else if (workflowId) {
          sidePanel.openTab(buildWorkflowPanelTab(workflowId));
        } else {
          sidePanel.open();
        }
        return;
      }

      // Open-only mode (legacy)
      if (event.detail?.isOpen && !sidePanel.isOpen) {
        if (workflowId) {
          sidePanel.openTab(buildWorkflowPanelTab(workflowId));
        } else {
          sidePanel.open();
        }
      }
    };

    window.addEventListener('workflowViewToggleMessagesPanel', handler as EventListener);
    return () => window.removeEventListener('workflowViewToggleMessagesPanel', handler as EventListener);
  }, [isWorkflowViewWithWorkflow, isApplicationPage, isMarketplacePreview, sidePanel, workflowId]);

  // Handle toggle messages panel for data view
  // Uses CustomEvent for legacy support with existing components
  const handleToggleMessagesPanel = useCallback(() => {
    const newState = !showMessagesPanel;
    setShowMessagesPanel(newState);

    if (isDataViewWithDataSource) {
      window.dispatchEvent(new CustomEvent('dataViewToggleMessagesPanel', {
        detail: { isOpen: newState }
      }));
    }

  }, [showMessagesPanel, isDataViewWithDataSource, isInterfacePage]);

  // Handle open workflow messages panel (chat view)
  const handleOpenWorkflowInChat = useCallback(() => {
    window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
      detail: { isOpen: true, view: 'chat' }
    }));
  }, []);

  // Interface edit handler - dispatches event to InterfaceDetailPage which opens the modal
  const handleEditInterface = useCallback(() => {
    window.dispatchEvent(new CustomEvent('interfaceEdit'));
  }, []);

  // ============== METADATA EDIT MODAL (workflow / interface / datasource) ==============
  const tEdit = useTranslations('modals.editMetadata');
  const tAgentBrowse = useTranslations('agentBrowse');
  const { toasts: editToasts, addToast: addEditToast, removeToast: removeEditToast } = useToast();
  const [editMetadata, setEditMetadata] = useState<{
    resourceType: EditMetadataResourceType;
    id: string;
    name: string;
    description: string;
  } | null>(null);
  const [isSavingMetadata, setIsSavingMetadata] = useState(false);

  useEffect(() => {
    const handler = (event: CustomEvent) => {
      const detail = event.detail as { resourceType: EditMetadataResourceType; id: string; name: string; description?: string };
      if (!detail?.id || !detail?.resourceType) return;
      setEditMetadata({
        resourceType: detail.resourceType,
        id: detail.id,
        name: detail.name || '',
        description: detail.description || '',
      });
    };
    window.addEventListener('openMetadataEditModal', handler as EventListener);
    return () => window.removeEventListener('openMetadataEditModal', handler as EventListener);
  }, []);

  const handleSaveMetadata = useCallback(async (values: { name: string; description: string }) => {
    if (!editMetadata) return;
    setIsSavingMetadata(true);
    try {
      if (editMetadata.resourceType === 'workflow') {
        await orchestratorApi.updateWorkflow(editMetadata.id, { name: values.name, description: values.description } as any);
        // Notify workflow builder to update its in-memory plan name
        window.dispatchEvent(new CustomEvent('workflowNameChangeFromBreadcrumb', {
          detail: { workflowId: editMetadata.id, newName: values.name },
        }));
      } else if (editMetadata.resourceType === 'interface') {
        await orchestratorApi.updateInterface(editMetadata.id, { name: values.name, description: values.description } as any);
      } else if (editMetadata.resourceType === 'datasource') {
        await orchestratorApi.updateDataSource(editMetadata.id, { name: values.name, description: values.description } as any);
      } else if (editMetadata.resourceType === 'file') {
        await storageApi.renameEntry(editMetadata.id, values.name);
      }
      addEditToast({
        type: 'success',
        title: tEdit('successTitle'),
        message: tEdit('successMessage', { name: values.name }),
      });
      window.dispatchEvent(new CustomEvent('metadataEditSaved', { detail: { ...editMetadata, ...values } }));
      setEditMetadata(null);
    } catch (err) {
      console.error('[AppHeader] Failed to save metadata:', err);
      addEditToast({
        type: 'error',
        title: tEdit('errorTitle'),
        message: tEdit('errorMessage'),
      });
    } finally {
      setIsSavingMetadata(false);
    }
  }, [editMetadata, addEditToast, tEdit]);

  // Listen for panel state changes from DataView
  useEffect(() => {
    if (!isDataViewWithDataSource) return;

    const handler = (event: CustomEvent) => {
      setShowMessagesPanel(event.detail?.isOpen || false);
    };

    window.addEventListener('dataViewMessagesPanelStateChange', handler as EventListener);
    return () => window.removeEventListener('dataViewMessagesPanelStateChange', handler as EventListener);
  }, [isDataViewWithDataSource]);

  // Listen for panel state changes from WorkflowDetailView or ApplicationDetailView
  useEffect(() => {
    if (!isWorkflowViewWithWorkflow && !isApplicationPage) return;

    const handler = (event: CustomEvent) => {
      setShowMessagesPanel(event.detail?.isOpen || false);
    };

    window.addEventListener('workflowViewMessagesPanelStateChange', handler as EventListener);
    return () => window.removeEventListener('workflowViewMessagesPanelStateChange', handler as EventListener);
  }, [isWorkflowViewWithWorkflow, isApplicationPage]);

  // ============== SIDE PANEL AUTO-OPEN FROM CHAT ==============

  // Pages where the chat IS the primary view (chat home, conversation pages, chat sub-pages).
  // Distinct from `isAiChatHiddenPage` which also includes workflow/application/preview pages.
  const isChatPage = normalizedPathname?.startsWith('/app/c/') ||
                     normalizedPathname === '/app' ||
                     normalizedPathname?.startsWith('/app/chat');

  useEffect(() => {
    if (!isChatPage || !sidePanel) {
      return;
    }

    const handleAutoOpen = (event: CustomEvent<{
      type: string;
      id: string;
      title?: string;
      runId?: string;
      // M7 live-view: when the auto-open is triggered from a streaming
      // agent_browse_step event, the coords needed by BrowserLiveCdpPanel
      // are already in hand - no Interface fetch required.
      liveCoords?: {
        sessionId: string;
        cdpToken: string;
        cdpWsUrl: string;
        currentUrl: string;
        runId: string;
        nodeId: string;
      };
    }>) => {
      const { type, id, title, runId: eventRunId, liveCoords } = event.detail;
      if (!id) return;

      // On mobile, add the tab without opening the panel (peek animation instead)
      const openFn = isMobile ? sidePanel.openTabDeferred : sidePanel.openTab;

      switch (type) {
        case 'workflow': {
          const tabId = `workflow-${id}`;
          openFn({
            id: tabId,
            label: title || 'Workflow',
            icon: <Workflow className="w-4 h-4" />,
            content: <WorkflowBuilderPanelContent workflowId={id} readOnly={false} />,
            preferredWidth: 0.35,
            keepMounted: true,
          });
          break;
        }
        case 'table':
        case 'datasource': {
          openFn({
            id: `datasource-${id}`,
            label: title || 'Table',
            icon: <Table className="h-4 w-4" />,
            content: <DataSourcePanelContent dataSourceId={id} />,
            preferredWidth: 0.35,
          });
          break;
        }
        case 'interface': {
          openFn({
            id: `interface-${id}`,
            label: title || 'Interface',
            icon: <Monitor className="h-4 w-4" />,
            content: <InterfacePanelContent interfaceId={id} />,
            preferredWidth: 0.35,
          });
          break;
        }
        case 'application': {
          // buildApplicationPanelTab sets keepMounted:true so that when the
          // agent opens several apps at once, each stays mounted and resolves
          // (see helper for the "only the last app resolved" rationale).
          openFn(buildApplicationPanelTab({ publicationId: id, title, runId: eventRunId }));
          break;
        }
        case 'agent_browse': {
          // M7 live-view: opened either from a streaming agent_browse_step
          // event (with liveCoords) for mid-execution view, or from the
          // post-completion [visualize:agent_browse:{id}] marker (with
          // interfaceId only). AgentBrowsePanelContent handles both.
          // Key on the SESSION id when we have it (live path) so this
          // auto-opened tab is the SAME one the in-chat AgentBrowse cards
          // open (they key on sessionId too) - one tab, not two. Falls back
          // to `id` (interfaceId) on the post-completion marker path.
          const tabId = `agent-browse-${liveCoords?.sessionId ?? id}`;
          openFn({
            id: tabId,
            label: title || liveCoords?.currentUrl || 'Browser Agent',
            icon: <Globe className="w-4 h-4" />,
            content: liveCoords
              ? <AgentBrowsePanelContent liveCoords={liveCoords} />
              : <AgentBrowsePanelContent interfaceId={id} />,
            preferredWidth: 0.5,
            // keepMounted: true keeps the CDP WebSocket alive even when
            // the user switches tabs. Without it, the WS would tear down
            // and the live frames would freeze each time.
            keepMounted: true,
          });
          // Force-activate AFTER the next event-loop tick. openTab
          // already calls setActiveTabId, but if a stale auto-open
          // (e.g. an older websearch tab from earlier in the
          // conversation) fires its own activate on the same tick,
          // ours can lose. Async setActiveTab wins by ordering.
          window.setTimeout(() => {
            try { sidePanel.setActiveTab(tabId); } catch { /* noop */ }
          }, 0);
          break;
        }
        case 'agent': {
          openFn({
            id: `agent-${id}`,
            label: title || 'Agent',
            icon: <Bot className="w-4 h-4" />,
            content: <AgentPanelContent agentId={id} initialTab={AGENT_CONFIGURATION_TAB} />,
            preferredWidth: 0.35,
            // Auto-open is gated by `isChatPage` - scope the tab so it doesn't survive
            // navigation to non-chat pages (defense-in-depth for the agent-tab leak).
            scope: ['/app/c/*', '/app/chat', '/app$'],
          });
          break;
        }
        case 'workflow_run': {
          if (!eventRunId) break;
          const tabId = `workflow-${id}`;  // Same tab ID - openTab merges
          openFn({
            id: tabId,
            label: title || 'Workflow Run',
            icon: <Play className="w-4 h-4" />,
            content: <WorkflowBuilderPanelContent workflowId={id} runId={eventRunId} readOnly={false} />,
            preferredWidth: 0.45,
            keepMounted: true,
          });
          break;
        }
      }
    };

    window.addEventListener('sidePanelAutoOpen', handleAutoOpen as EventListener);

    // M7: when an agent_browse session ends (Chrome killed, runner
    // emitted `final` step, or reconnect exhausted), re-label the
    // existing live tab so the user can tell at a glance that the
    // tab is now stale. We don't remove the tab - user wants to
    // review the final state - just update its title.
    const handleLiveTabDisconnected = (event: CustomEvent<{ toolId?: string; sessionId?: string }>) => {
      // Prefer the session id so we re-label the same tab the cards + the
      // auto-open keyed on; fall back to toolId for older event shapes.
      const key = event.detail.sessionId || event.detail.toolId;
      if (!key) return;
      const tabId = `agent-browse-${key}`;
      try {
        sidePanel.updateTab?.(tabId, {
          label: tAgentBrowse('tabLabelDisconnected'),
        });
      } catch {
        /* tab no longer present - noop */
      }
    };
    window.addEventListener('agentBrowseLiveTabDisconnected', handleLiveTabDisconnected as EventListener);

    return () => {
      window.removeEventListener('sidePanelAutoOpen', handleAutoOpen as EventListener);
      window.removeEventListener('agentBrowseLiveTabDisconnected', handleLiveTabDisconnected as EventListener);
    };
  }, [isChatPage, sidePanel, isMobile, tAgentBrowse]);

  // Extract run ID from pathname (workflow routes only - application routes use snapshot-based context)
  const runId = pathname?.match(/\/workflow\/[^\/]+\/run\/([^\/]+)/)?.[1] || null;

  // The Messages route never shows a model selector - a DM thread shows the peer (below), the
  // "select a thread" landing shows nothing.
  const isMessagesPage = (pathname ?? '').includes('/app/messages');
  // While a DM thread is open but its peer profile is still resolving, the header slot shows a
  // skeleton (ChatHeader.agentSlotLoading) instead of flashing from empty/icon to the avatar.
  const isDmThreadOpen = /\/app\/messages\/[^/]+/.test(pathname ?? '');

  // In a DM, override the agent-avatar header slot with the peer - the header then shows their
  // avatar + name and no model selector. Outside a DM these fall through to the linked agent.
  const headerAgentName = dmPeer ? dmPeer.displayName : agentName;
  const headerAgentAvatarUrl = dmPeer ? `/api/users/${dmPeer.userId}/avatar` : agentAvatarUrl;
  const headerAgentModelName = dmPeer ? null : agentModelName;
  const headerAgentModelProvider = dmPeer ? null : agentModelProvider;

  return (
    <>
    <div className="fixed top-4 right-4 z-[10000] space-y-2 pointer-events-none">
      {editToasts.map((toast) => (
        <div key={toast.id} className="pointer-events-auto">
          <Toast
            id={toast.id}
            type={toast.type}
            title={toast.title}
            message={toast.message}
            duration={toast.duration}
            onClose={removeEditToast}
          />
        </div>
      ))}
    </div>
    {editMetadata && (
      <EditMetadataModal
        resourceType={editMetadata.resourceType}
        initialName={editMetadata.name}
        initialDescription={editMetadata.description}
        isSaving={isSavingMetadata}
        onClose={() => setEditMetadata(null)}
        onSave={handleSaveMetadata}
      />
    )}
    <ChatHeader
      searchSlot={<GlobalSearchBar />}
      mobileSearchSlot={<GlobalSearchBar variant="compact" />}
      selectedModel={appState.selectedModel}
      onModelChange={setSelectedModel}
      reasoningEffort={appState.reasoningEffort}
      onReasoningEffortChange={setReasoningEffort}
      availableModels={availableModels}
      showModelSelector={shouldShowBreadcrumb ? false : appState.showModelSelector}
      onShowModelSelector={setShowModelSelector}
      sidebarOpen={sidebarOpen}
      onSidebarToggle={() => setSidebarOpen(!sidebarOpen)}
      isStreaming={false}
      isStreamConnected={false}
      isStreamReconnecting={false}
      streamStatus={null}
      onStopStream={() => { }}
      onReconnectStream={() => { }}
      isDashboard={currentView === 'settings'}
      hideModelSelector={isMessagesPage}
      agentSlotLoading={isDmThreadOpen && !dmPeer}
      agentSlotNonInteractive={!!dmPeer}
      dashboardBreadcrumbItems={breadcrumbItems}
      showProfileView={showProfileView}
      isWorkflowExpanded={false}
      expandedWorkflowId={null}
      messages={[]}
      conversationTitle={null}
      conversationId={conversationId}
      isDataView={isDataViewWithDataSource}
      expandedDataSourceId={dataSourceId}
      showMessagesPanel={(isDataViewWithDataSource || isWorkflowViewWithWorkflow || isInterfacePage || isApplicationPage || isMarketplacePreview) ? showMessagesPanel || isAgentConfigOpen : false}
      onToggleMessagesPanel={(isDataViewWithDataSource || isInterfacePage) ? handleToggleMessagesPanel : undefined}
      isWorkflowPage={isWorkflowViewWithWorkflow}
      workflowId={(isWorkflowViewWithWorkflow || isApplicationPage) ? workflowId : null}
      runId={runId}
      onOpenWorkflowInChat={isWorkflowViewWithWorkflow ? handleOpenWorkflowInChat : undefined}
      isRunMode={isRunMode}
      isApplicationPage={isApplicationPage}
      isMarketplacePreview={isMarketplacePreview}
      previewPublicationId={previewPublicationId}
      isAgentMarketplacePreview={isAgentMarketplacePreview}
      agentPreviewPublicationId={agentPreviewPublicationId}
      isAgentFleet={isAgentFleet}
      isInterfacePage={isInterfacePage}
      interfaceId={interfaceId}
      onEditInterface={isInterfacePage && interfaceType !== 'web_search' ? handleEditInterface : undefined}
      // Files focused-viewer actions - header-driven back / prev-next / download.
      // Back is folder-aware (close viewer, else step up one folder); the
      // prev/next/download sub-controls stay gated to an open file (isFileOpen).
      isFilesDetail={isFilesDetail}
      isFileOpen={isFileOpen}
      onFilesBack={handleFilesBack}
      onFilesPrev={() => emitFilesDetailCommand(FILES_DETAIL_PREV)}
      onFilesNext={() => emitFilesDetailCommand(FILES_DETAIL_NEXT)}
      canFilesPrev={!!filesDetail?.canPrev}
      canFilesNext={!!filesDetail?.canNext}
      onFilesDownload={() => emitFilesDetailCommand(FILES_DETAIL_DOWNLOAD)}
      filesDownloading={!!filesDetail?.downloading}
      // Agent props - avatar and model replace model selector when agent is linked
      agentId={agentId}
      agentName={headerAgentName}
      agentAvatarUrl={headerAgentAvatarUrl}
      agentModelName={headerAgentModelName}
      agentModelProvider={headerAgentModelProvider}
      // Conversation Activity toggle - only on a real conversation page (/app/c/<id>)
      showActivityToggle={isConversationPage}
      // Right panel props - dock buttons always visible; behavior varies by page type
      isSidePanelOpen={sidePanel?.isOpen ?? false}
      showAgentConfigPanel={isConversationPage ? !!isAgentConfigOpen : (sidePanel?.isOpen ?? false)}
      onToggleAgentConfigPanel={
        isConversationPage
          ? handleToggleRightPanel
          : (isWorkflowViewWithWorkflow || isApplicationPage || isMarketplacePreview)
            ? handleToggleWorkflowPanel
            : isAiChatHiddenPage
              ? handleTogglePanelOnly
              : handleToggleSidePanel
      }
    />
    </>
  );
}
