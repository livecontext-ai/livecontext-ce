'use client';

/**
 * WorkflowPanelContent - Workflow panel with AI assistant, trigger and application tabs for the SidePanel.
 *
 * Extracts the inner content from WorkflowMessagesPanel to be rendered
 * inside the unified SidePanel tab system. Receives dynamic data
 * (triggerData, applicationConfigs) via CustomEvents from WorkflowDetailView.
 *
 * Wraps itself with WorkflowModeProvider so that child components
 * (ApplicationTabContent) can access workflow mode from the URL.
 */

import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { Sparkles, MessageSquare, FileText, AppWindow, Workflow } from 'lucide-react';
import { usePathname } from '@/i18n/navigation';
import { ChatCore } from '@/components/chat/ChatCore';
import { WelcomeTitle } from '@/app/shared/components';
import { ModelSelectorDropdown, PROVIDER_ICON_MAP } from '@/components/chat/ModelSelectorDropdown';
import { NoProviderCta } from '@/components/ai/NoProviderCta';
import { TriggerTabContent } from '@/components/chat/TriggerTabContent';
import { type ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import { ApplicationCarousel } from '@/components/chat/ApplicationCarousel';
import { useInterfacePaginationStore } from '@/lib/stores/interface-pagination-store';
import { cn } from '@/lib/utils';
import { useTranslations } from 'next-intl';
import { useWorkflowChat } from '@/hooks/useWorkflowChat';
import { useVisibleModels, AIModel, SelectedModel, EMPTY_SELECTED_MODEL, modelMatches, selectedModelFromAIModel, selectedModelEquals, getEffectiveDefaultSelectedModel } from '@/hooks/useModels';
import { useUnifiedAppSafe } from '@/contexts/UnifiedAppContext';
import { useStreaming } from '@/contexts/StreamingContext';
import { WorkflowModeProvider, useWorkflowMode } from '@/contexts/WorkflowModeContext';
import type { TriggerPanelConfig } from '@/app/workflows/builder/components/TriggerPanel';
import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';
import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

// ── Constants ──

const CHAT_TAB_ID = '__chat_ia__';
const APP_TAB_ID = '__application__';
export const WORKFLOW_TAB_ID = '__workflow__';

// ── Per-workflow cache for data that survives unmount/remount ──
// When the SidePanel closes, WorkflowPanelContent unmounts.
// When it reopens, we restore the latest trigger/application data from cache.
// Keyed by workflowId so multiple keepMounted panels don't contaminate each other.

interface CachedPanelData {
  triggerConfigs: TriggerPanelConfig[];
  triggerActiveId?: string;
  triggerReadySteps: Set<string>;
  triggerRunStatus?: string;
  /** Run id the canvas is bound to, reported via triggerData. Lets the
   *  Application/interface resolve its run when the run is bound IN PLACE
   *  (agent-launched overlay) - there is no /run/ URL to read it from. */
  triggerRunId?: string;
  triggerStepByStep: boolean;
  applicationConfigs: ApplicationConfig[];
  agentConfigs: unknown[];
}

function makeDefaultCache(): CachedPanelData {
  return {
    triggerConfigs: [],
    triggerReadySteps: new Set(),
    triggerStepByStep: false,
    applicationConfigs: [],
    agentConfigs: [],
  };
}

const cacheByWorkflow = new Map<string, CachedPanelData>();

function getCachedData(wfId: string): CachedPanelData {
  let c = cacheByWorkflow.get(wfId);
  if (!c) {
    c = makeDefaultCache();
    cacheByWorkflow.set(wfId, c);
  }
  return c;
}

const pendingActivateTabByWorkflow = new Map<string, string>();

export function setPendingActivateTab(tabId: string, workflowId?: string) {
  if (workflowId) {
    pendingActivateTabByWorkflow.set(workflowId, tabId);
  } else {
    pendingActivateTabByWorkflow.set('__default__', tabId);
  }
}

// Global listeners - always active, update per-workflow cache even when component is unmounted
if (typeof window !== 'undefined') {
  window.addEventListener('workflowPanelTriggerDataChange', ((event: CustomEvent) => {
    const d = event.detail;
    const wfId = d.workflowId;
    if (!wfId) return;
    const c = getCachedData(wfId);
    c.triggerConfigs = d.configs ?? [];
    c.triggerActiveId = d.activeTriggerId;
    c.triggerReadySteps = d.readySteps ?? new Set();
    c.triggerRunStatus = d.runStatus;
    c.triggerRunId = d.runId;
    c.triggerStepByStep = d.isStepByStepMode ?? false;
  }) as EventListener);

  window.addEventListener('workflowPanelApplicationConfigsChange', ((event: CustomEvent) => {
    const wfId = event.detail.workflowId;
    if (!wfId) return;
    getCachedData(wfId).applicationConfigs = event.detail.configs ?? [];
  }) as EventListener);

  window.addEventListener('workflowPanelAgentConfigsChange', ((event: CustomEvent) => {
    const wfId = event.detail.workflowId;
    if (!wfId) return;
    getCachedData(wfId).agentConfigs = event.detail.configs ?? [];
  }) as EventListener);

  // HMR-safe module-singleton subscriber.
  // Workspace switch clears all per-workflow caches.
  const HMR_KEY = Symbol.for('__lc_orgReset:WorkflowPanelContent');
  const g = globalThis as unknown as Record<symbol, (() => void) | undefined>;
  if (typeof g[HMR_KEY] === 'function') g[HMR_KEY]!();
  import('@/lib/stores/current-org-store').then(({ useCurrentOrgStore }) => {
    g[HMR_KEY] = useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => {
        cacheByWorkflow.clear();
        pendingActivateTabByWorkflow.clear();
      },
    );
  }).catch(() => {});
}

// ── Inner content (rendered inside WorkflowModeProvider) ──

function WorkflowPanelInner({ workflowId, runId: runIdProp, workflowCanvasSlot, isPreviewOnly = false }: { workflowId: string; runId?: string; workflowCanvasSlot?: React.ReactNode; isPreviewOnly?: boolean }) {
  const t = useTranslations();
  const pathname = usePathname();

  // ── Model selector state ──
  const { models, defaultModel, isLoading: modelsLoading, error: modelsError } = useVisibleModels();
  // Same gate as ModelPicker: never show the no-provider empty state while the
  // catalog is loading or after a fetch error - only once it RESOLVED empty.
  const modelsResolvedEmpty = !modelsLoading && !modelsError;
  const appContext = useUnifiedAppSafe();
  const setSelectedModel = appContext?.setSelectedModel ?? ((_: SelectedModel) => {});
  const appSelectedModel: SelectedModel = appContext?.state.selectedModel ?? EMPTY_SELECTED_MODEL;

  const defaultAIModel: AIModel | undefined = useMemo(
    () => (defaultModel ? models.find(m => m.id === defaultModel) : undefined) ?? models[0],
    [models, defaultModel],
  );
  const effectiveDefault: SelectedModel = useMemo(
    () => (defaultAIModel ? selectedModelFromAIModel(defaultAIModel) : getEffectiveDefaultSelectedModel()),
    [defaultAIModel],
  );
  const isValidModel = models.length > 0 && !!appSelectedModel.id && models.some(m => modelMatches(m, appSelectedModel));
  const selectedModel: SelectedModel = isValidModel ? appSelectedModel : effectiveDefault;

  useEffect(() => {
    if (!appContext || isValidModel || !effectiveDefault.id) return;
    if (!selectedModelEquals(appSelectedModel, effectiveDefault)) {
      setSelectedModel(effectiveDefault);
    }
  }, [isValidModel, effectiveDefault, appSelectedModel, setSelectedModel, appContext]);

  const [showModelSelector, setShowModelSelector] = useState(false);

  const availableModels = useMemo(() => {
    // Spread the full AIModel so the dropdown's enriched display
    // (capability icons, context window, deprecation, rate-limit popover)
    // has the data it needs without a second round-trip.
    return models.map((model: AIModel) => ({
      ...model,
      provider: model.provider.charAt(0).toUpperCase() + model.provider.slice(1),
      providerSlug: model.provider.toLowerCase(),
      iconSlug: PROVIDER_ICON_MAP[model.provider.toLowerCase()] || model.provider.toLowerCase(),
    }));
  }, [models]);

  const selectedModelData = availableModels.find(m => modelMatches(m, selectedModel));

  // Model selector now lives in the composer (left of the mic). ModelSelectorDropdown
  // owns its own outside-click handling, so no effect is needed here.
  const leadingControl = (
    <ModelSelectorDropdown
      showModelSelector={showModelSelector}
      setShowModelSelector={setShowModelSelector}
      selectedModel={selectedModel}
      selectedModelData={selectedModelData}
      availableModels={availableModels}
      setSelectedModel={setSelectedModel}
      changeModelTitle={t('actions.changeModel')}
      noModelsLabel={modelsResolvedEmpty ? t('aiProviders.noProviderCta.noModels') : undefined}
      emptyState={modelsResolvedEmpty ? <NoProviderCta variant="menu" /> : undefined}
    />
  );

  // ── Workflow chat ──
  const {
    conversationId,
    messages,
    isLoading,
    sendMessage: sendChatMessage,
    loadConversation,
    stopStream,
  } = useWorkflowChat({ workflowId, model: selectedModel });

  // Streaming state → dispatch to canvas
  const streaming = useStreaming();
  const isStreaming = conversationId ? streaming.isStreamingConversation(conversationId) : false;

  useEffect(() => {
    window.dispatchEvent(new CustomEvent('workflowStreamingStateChange', {
      detail: { isStreaming },
    }));
  }, [isStreaming]);

  // Listen for stop stream request from canvas
  useEffect(() => {
    const handleStopRequest = () => {
      if (isStreaming) stopStream();
    };
    window.addEventListener('workflowStopStreamRequest', handleStopRequest);
    return () => window.removeEventListener('workflowStopStreamRequest', handleStopRequest);
  }, [isStreaming, stopStream]);

  // ── Dynamic data from WorkflowDetailView (via CustomEvents) ──
  // Initialize from per-workflow cache so data survives unmount/remount (panel close/open)
  const myCache = getCachedData(workflowId);
  const [triggerConfigs, setTriggerConfigs] = useState<TriggerPanelConfig[]>(() => myCache.triggerConfigs);
  const [triggerActiveId, setTriggerActiveId] = useState<string | undefined>(() => myCache.triggerActiveId);
  const [triggerReadySteps, setTriggerReadySteps] = useState<Set<string>>(() => myCache.triggerReadySteps);
  const [triggerRunStatus, setTriggerRunStatus] = useState<string | undefined>(() => myCache.triggerRunStatus);
  const [triggerRunId, setTriggerRunId] = useState<string | undefined>(() => myCache.triggerRunId);
  const [triggerStepByStep, setTriggerStepByStep] = useState(() => myCache.triggerStepByStep);
  const [applicationConfigs, setApplicationConfigs] = useState<ApplicationConfig[]>(() => myCache.applicationConfigs);

  // ── Reload chat messages when steps complete (SBS + AUTO) ──
  // The reloadConversation() call in TriggerTabContent fires right after trigger
  // execution - before response nodes have run. We watch readySteps/runStatus
  // changes to detect step completion and reload conversation messages
  // so assistant responses appear in the trigger chat tab.
  const loadConversationRef = useRef(loadConversation);
  loadConversationRef.current = loadConversation;

  const readyStepsKey = useMemo(() =>
    [...triggerReadySteps].sort().join(','),
    [triggerReadySteps]
  );

  // SBS mode: reload on every readySteps/runStatus change (each step completion)
  const sbsReloadKeyRef = useRef<string | null>(null);
  useEffect(() => {
    if (!triggerStepByStep) {
      sbsReloadKeyRef.current = null;
      return;
    }
    const key = `${readyStepsKey}|${triggerRunStatus ?? ''}`;
    if (sbsReloadKeyRef.current === null) {
      sbsReloadKeyRef.current = key;
      return;
    }
    if (key === sbsReloadKeyRef.current) return;
    sbsReloadKeyRef.current = key;
    loadConversationRef.current(true);
  }, [triggerStepByStep, readyStepsKey, triggerRunStatus]);

  // AUTO mode: reload when runStatus changes (e.g. RUNNING → WAITING_TRIGGER/COMPLETED)
  // This catches the moment all nodes have finished and response is in the DB.
  const autoReloadStatusRef = useRef<string | null>(null);
  useEffect(() => {
    if (triggerStepByStep) {
      autoReloadStatusRef.current = null;
      return;
    }
    const status = triggerRunStatus ?? '';
    if (autoReloadStatusRef.current === null) {
      autoReloadStatusRef.current = status;
      return;
    }
    if (status === autoReloadStatusRef.current) return;
    autoReloadStatusRef.current = status;
    loadConversationRef.current(true);
  }, [triggerStepByStep, triggerRunStatus]);

  // Listen for trigger data changes (scoped to this workflow)
  useEffect(() => {
    const handler = (event: CustomEvent) => {
      const d = event.detail;
      if (d.workflowId && d.workflowId !== workflowId) return;
      setTriggerConfigs(d.configs ?? []);
      setTriggerActiveId(d.activeTriggerId);
      setTriggerReadySteps(d.readySteps ?? new Set());
      setTriggerRunStatus(d.runStatus);
      setTriggerRunId(d.runId);
      setTriggerStepByStep(d.isStepByStepMode ?? false);
    };
    window.addEventListener('workflowPanelTriggerDataChange', handler as EventListener);
    return () => window.removeEventListener('workflowPanelTriggerDataChange', handler as EventListener);
  }, [workflowId]);

  // Listen for application configs changes (scoped to this workflow)
  useEffect(() => {
    const handler = (event: CustomEvent) => {
      if (event.detail.workflowId && event.detail.workflowId !== workflowId) return;
      setApplicationConfigs(event.detail.configs ?? []);
    };
    window.addEventListener('workflowPanelApplicationConfigsChange', handler as EventListener);
    return () => window.removeEventListener('workflowPanelApplicationConfigsChange', handler as EventListener);
  }, [workflowId]);

  // ── Internal tab state ──
  const hasWorkflowSlot = !!workflowCanvasSlot;
  const visibleTriggerConfigs = isPreviewOnly ? [] : triggerConfigs;
  // Application sub-tab visibility. It is a PERMANENT sub-tab (not user-closeable,
  // like the Trigger sub-tabs) that appears automatically once the run exposes an
  // interface. We still hide it BY DEFAULT in two contexts where it would be
  // redundant or unwanted: marketplace preview (where Trigger tabs are hidden too)
  // and the /app/applications route (the interface is already the main view there).
  // `isAppTabDismissed` is re-armed on org switch so a default-hide in workspace A
  // doesn't leak into workspace B if the panel survives the transition.
  const isApplicationRoute = pathname?.includes('/app/applications/') ?? false;
  const hideAppTabByDefault = hasWorkflowSlot && (isPreviewOnly || isApplicationRoute);
  const [isAppTabDismissed, setIsAppTabDismissed] = useState(hideAppTabByDefault);
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  useEffect(() => {
    setIsAppTabDismissed(hideAppTabByDefault);
  }, [currentOrgId, hideAppTabByDefault]);
  const showAppTab = applicationConfigs.length > 0 && !isAppTabDismissed;
  const hasExtraTabs = visibleTriggerConfigs.length > 0 || showAppTab || hasWorkflowSlot;
  const [activeTabId, setActiveTabId] = useState(hasWorkflowSlot ? WORKFLOW_TAB_ID : CHAT_TAB_ID);

  // Auto-select active trigger tab when activeTriggerId changes
  // Skip when hasWorkflowSlot (application mode) - keep Workflow tab focused
  const prevActiveTriggerId = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (hasWorkflowSlot) {
      prevActiveTriggerId.current = triggerActiveId;
      return;
    }
    if (triggerActiveId && triggerActiveId !== prevActiveTriggerId.current && triggerConfigs.length > 0) {
      const matchingConfig = triggerConfigs.find(c => c.triggerId === triggerActiveId);
      if (matchingConfig) {
        setActiveTabId(matchingConfig.triggerId);
      }
    }
    prevActiveTriggerId.current = triggerActiveId;
  }, [triggerActiveId, triggerConfigs, hasWorkflowSlot]);

  // Make the Application tab VISIBLE by default when interfaces become available
  // (not in app/preview mode). Symmetric with the Trigger tabs, which appear
  // automatically. Like the trigger auto-select (which is skipped when a
  // workflow canvas slot is present - see above), we DON'T steal focus to the
  // Application tab when the canvas is mounted: the user keeps watching the run
  // on the canvas and the Application tab simply becomes available in the bar.
  // Without a canvas slot there's nothing to stay on, so we focus it.
  const prevAppConfigCount = useRef(0);
  useEffect(() => {
    if (!hideAppTabByDefault && applicationConfigs.length > 0 && prevAppConfigCount.current === 0) {
      setIsAppTabDismissed(false);
      if (!hasWorkflowSlot) setActiveTabId(APP_TAB_ID);
    }
    prevAppConfigCount.current = applicationConfigs.length;
  }, [applicationConfigs.length, hideAppTabByDefault, hasWorkflowSlot]);

  // Reset to chat tab when all extra tabs disappear
  useEffect(() => {
    if (triggerConfigs.length === 0 && applicationConfigs.length === 0 && !hasWorkflowSlot && activeTabId !== CHAT_TAB_ID) {
      setActiveTabId(CHAT_TAB_ID);
    }
    // Reset application tab if configs are gone - fall back to Workflow tab if
    // a workflow slot is present, otherwise back to chat.
    if (applicationConfigs.length === 0 && activeTabId === APP_TAB_ID) {
      setActiveTabId(hasWorkflowSlot ? WORKFLOW_TAB_ID : CHAT_TAB_ID);
    }
  }, [triggerConfigs.length, applicationConfigs.length, activeTabId, hasWorkflowSlot]);

  // Consume pending tab activation (set before panel was opened)
  useEffect(() => {
    const pending = pendingActivateTabByWorkflow.get(workflowId) ?? pendingActivateTabByWorkflow.get('__default__');
    if (pending) {
      if (pending.startsWith('app-')) {
        const interfaceId = pending.replace('app-', '');
        const idx = applicationConfigs.findIndex(c => c.interfaceId === interfaceId);
        if (idx >= 0) {
          setActiveTabId(APP_TAB_ID);
          useInterfacePaginationStore.getState().setCarouselIndex(idx);
        }
      } else {
        setActiveTabId(pending);
      }
      pendingActivateTabByWorkflow.delete(workflowId);
      pendingActivateTabByWorkflow.delete('__default__');
    }
  }, [applicationConfigs, workflowId]);

  // Listen for trigger tab open requests from node shimmer buttons
  useEffect(() => {
    const handleOpenTriggerTab = (event: CustomEvent<{ nodeId: string; triggerType: 'chat' | 'form' | 'webhook' }>) => {
      const match = triggerConfigs.find(c => c.type === event.detail.triggerType);
      if (match) setActiveTabId(match.triggerId);
    };
    window.addEventListener('workflowOpenTriggerTab', handleOpenTriggerTab as EventListener);
    return () => window.removeEventListener('workflowOpenTriggerTab', handleOpenTriggerTab as EventListener);
  }, [triggerConfigs]);

  // Listen for application tab open requests from node clicks → open Application tab + navigate carousel.
  // Works even when a workflow canvas slot is mounted: switching to APP_TAB_ID hides the
  // workflow slot (its wrapper uses display:none when activeTabId !== WORKFLOW_TAB_ID) and
  // renders the ApplicationCarousel in its place inside the side panel.
  useEffect(() => {
    const handleOpenApplicationTab = (event: CustomEvent<{ interfaceId: string }>) => {
      const idx = applicationConfigs.findIndex(c => c.interfaceId === event.detail.interfaceId);
      if (idx >= 0) {
        setIsAppTabDismissed(false);
        setActiveTabId(APP_TAB_ID);
        useInterfacePaginationStore.getState().setCarouselIndex(idx);
      }
    };
    window.addEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    return () => window.removeEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
  }, [applicationConfigs]);

  // Listen for external tab activation (e.g. toggle button in ApplicationDetailView)
  useEffect(() => {
    const handler = (event: CustomEvent<{ tabId: string; workflowId?: string }>) => {
      if (event.detail.workflowId && event.detail.workflowId !== workflowId) return;
      setActiveTabId(event.detail.tabId);
    };
    window.addEventListener('workflowPanelActivateTab', handler as EventListener);
    return () => window.removeEventListener('workflowPanelActivateTab', handler as EventListener);
  }, [workflowId]);

  // ── Terminal run status check ──
  const isRunTerminal = useMemo(() => {
    const s = triggerRunStatus?.toLowerCase();
    return !!s && TERMINAL_STATUSES.has(s as any);
  }, [triggerRunStatus]);

  // ── Trigger disabled state ──
  // Simplified for parallel epochs: the backend controls trigger availability
  // via readyNodes. The trigger appears as ready when the concurrency limiter
  // has available slots. Also disabled when the run is in a terminal state.
  const isTriggerDisabled = useMemo(() => {
    if (activeTabId === CHAT_TAB_ID) return false;
    if (isRunTerminal) return true;
    const config = triggerConfigs.find(c => c.triggerId === activeTabId);
    if (!config) return false;
    const triggerIsReady = triggerReadySteps.has(config.triggerId);
    // Trigger is disabled only when not in readyNodes (backend gate)
    return !triggerIsReady;
  }, [activeTabId, triggerConfigs, triggerReadySteps, isRunTerminal]);

  // ── Trigger execution via event bridge ──
  const handleExecuteTrigger = useCallback(async (
    triggerId: string,
    triggerType: 'chat' | 'form' | 'webhook',
    payload: Record<string, any>
  ): Promise<string[] | undefined> => {
    return new Promise((resolve) => {
      const requestId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;

      const responseHandler = ((event: CustomEvent) => {
        if (event.detail.requestId === requestId) {
          window.removeEventListener('workflowExecuteTriggerResponse', responseHandler as EventListener);
          resolve(event.detail.result);
        }
      }) as EventListener;

      window.addEventListener('workflowExecuteTriggerResponse', responseHandler);

      window.dispatchEvent(new CustomEvent('workflowExecuteTriggerRequest', {
        detail: { requestId, triggerId, triggerType, payload },
      }));

      // Timeout after 30s
      setTimeout(() => {
        window.removeEventListener('workflowExecuteTriggerResponse', responseHandler);
        resolve(undefined);
      }, 30_000);
    });
  }, []);

  // ── Application action via event bridge ──
  const handleApplicationAction = useCallback(async (
    triggerRef: string,
    data: Record<string, unknown>
  ) => {
    // Navigate action: pure frontend carousel switch - no API call
    if (triggerRef.endsWith(':navigate')) {
      // Parse "interface:settings_page:navigate" → target normalized label = "settings_page"
      const parts = triggerRef.split(':');
      // Remove action suffix (:navigate) and prefix (interface:) to get the label
      const targetLabel = parts.length >= 3 ? parts.slice(1, -1).join(':') : null;
      if (targetLabel) {
        const normalizedTarget = normalizeLabel(targetLabel);
        const idx = applicationConfigs.findIndex(c => {
          const normalizedConfigLabel = normalizeLabel(c.label);
          return normalizedConfigLabel === normalizedTarget;
        });
        if (idx >= 0) {
          setActiveTabId(APP_TAB_ID);
          useInterfacePaginationStore.getState().setCarouselIndex(idx);
          return;
        }
        console.warn('[WorkflowPanelContent] Navigate target not found:', targetLabel, 'normalized:', normalizedTarget);
      }
      return;
    }

    window.dispatchEvent(new CustomEvent('workflowApplicationActionRequest', {
      detail: { triggerRef, data },
    }));
  }, [applicationConfigs]);

  // ── Suggestion prompt from canvas ──
  const [suggestionPrompt, setSuggestionPrompt] = useState<string | null>(null);

  useEffect(() => {
    const handler = (event: CustomEvent<{ prompt: string }>) => {
      setSuggestionPrompt(event.detail.prompt);
    };
    window.addEventListener('workflowSuggestionPrompt', handler as EventListener);
    return () => window.removeEventListener('workflowSuggestionPrompt', handler as EventListener);
  }, []);

  const handleSuggestionConsumed = useCallback(() => {
    setSuggestionPrompt(null);
  }, []);

  // ── Canvas message listener ──
  const handleSendMessageRef = useRef<((content: string) => Promise<void>) | null>(null);

  const handleSendMessage = useCallback(async (content: string) => {
    const msg = content.trim();
    if (!msg) return;
    await sendChatMessage(msg);
  }, [sendChatMessage]);

  useEffect(() => {
    handleSendMessageRef.current = handleSendMessage;
  }, [handleSendMessage]);

  useEffect(() => {
    const handleCanvasMessage = (event: CustomEvent<{ message: string }>) => {
      if (handleSendMessageRef.current && event.detail.message) {
        handleSendMessageRef.current(event.detail.message);
      }
    };
    window.addEventListener('workflowCanvasSendMessage', handleCanvasMessage as EventListener);
    return () => window.removeEventListener('workflowCanvasSendMessage', handleCanvasMessage as EventListener);
  }, []);

  // ── Run ID from pathname (fallback to prop for application mode and marketplace preview) ──
  // URL /run/<id> first (explicit run page), then the prop, then the in-place run
  // id reported by the canvas via triggerData (agent-launched overlay has no /run/
  // URL - without this the Application/interface has no run and shows "not available").
  const currentRunId = pathname?.match(/\/workflow\/[^\/]+\/run\/([^\/]+)/)?.[1] || runIdProp || triggerRunId || null;

  // ── Tab count & position ──
  const tabCount = 1 /* AI Chat */ + visibleTriggerConfigs.length
    + (hasWorkflowSlot ? 1 : 0)
    + (showAppTab ? 1 : 0);
  const tabsAtBottom = hasExtraTabs && tabCount >= 2;
  // When only 2 tabs (AI Chat + 1), no need for active border indicator
  const showTopActiveBorder = tabCount > 2;

  // ── Tab bar rendering (shared between top and bottom positions) ──
  const renderTabBar = () => (
    <div className={cn(
      "flex-shrink-0",
      tabsAtBottom
        ? "border-t border-theme bg-theme-secondary"
        : "border-b border-theme"
    )}>
      <div className={cn(
        "flex overflow-x-auto overflow-y-hidden",
        tabsAtBottom ? "" : "items-center gap-1 px-3 pt-2 pb-0"
      )}>
        {/* AI Chat tab */}
        <button
          type="button"
          onClick={() => setActiveTabId(CHAT_TAB_ID)}
          className={cn(
            "flex items-center gap-2 text-sm whitespace-nowrap transition-colors",
            tabsAtBottom
              ? "px-4 py-2.5"
              : cn("px-3 py-2 rounded-t-lg", showTopActiveBorder && "border-b-2"),
            activeTabId === CHAT_TAB_ID
              ? (tabsAtBottom
                  ? "bg-theme-primary text-theme-primary font-medium"
                  : cn("text-theme-primary font-medium bg-theme-secondary/50", showTopActiveBorder && "border-primary"))
              : (tabsAtBottom
                  ? "text-theme-muted hover:bg-theme-tertiary"
                  : cn("text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/30", showTopActiveBorder && "border-transparent"))
          )}
        >
          <Sparkles className="w-3.5 h-3.5 shrink-0" />
          {t('sidePanel.aiChat')}
        </button>

        {/* Workflow canvas tab - before triggers so it's in 2nd position */}
        {hasWorkflowSlot && (
          <button
            type="button"
            onClick={() => setActiveTabId(WORKFLOW_TAB_ID)}
            className={cn(
              "flex items-center gap-2 text-sm whitespace-nowrap transition-colors",
              tabsAtBottom
                ? "px-4 py-2.5"
                : cn("px-3 py-2 rounded-t-lg", showTopActiveBorder && "border-b-2"),
              activeTabId === WORKFLOW_TAB_ID
                ? (tabsAtBottom
                    ? "bg-theme-primary text-theme-primary font-medium"
                    : cn("text-theme-primary font-medium bg-theme-secondary/50", showTopActiveBorder && "border-primary"))
                : (tabsAtBottom
                    ? "text-theme-muted hover:bg-theme-tertiary"
                    : cn("text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/30", showTopActiveBorder && "border-transparent"))
            )}
          >
            <Workflow className="w-3.5 h-3.5 shrink-0" />
            {t('common.workflow')}
          </button>
        )}

        {/* Trigger tabs - hidden in preview mode */}
        {!isPreviewOnly && triggerConfigs.map((config, index) => {
          const isActive = activeTabId === config.triggerId;
          const isReady = triggerReadySteps.has(config.triggerId);
          return (
            <button
              key={`${config.triggerId}-${index}`}
              type="button"
              onClick={() => setActiveTabId(config.triggerId)}
              className={cn(
                "relative flex items-center gap-2 text-sm whitespace-nowrap transition-colors",
                tabsAtBottom
                  ? "px-4 py-2.5"
                  : cn("px-3 py-2 rounded-t-lg", showTopActiveBorder && "border-b-2"),
                isActive
                  ? (tabsAtBottom
                      ? "bg-theme-primary text-theme-primary font-medium"
                      : cn("text-theme-primary font-medium bg-theme-secondary/50", showTopActiveBorder && "border-primary"))
                  : (tabsAtBottom
                      ? "text-theme-muted hover:bg-theme-tertiary"
                      : cn("text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/30", showTopActiveBorder && "border-transparent"))
              )}
            >
              <span className="relative shrink-0">
                {config.type === 'chat' ? (
                  <MessageSquare className="w-3.5 h-3.5" />
                ) : (
                  <FileText className="w-3.5 h-3.5" />
                )}
                {isReady && (
                  <span
                    className="absolute -inset-1.5 rounded-full pointer-events-none overflow-hidden"
                    style={{
                      backgroundImage: `linear-gradient(90deg, transparent 0%, ${
                        config.type === 'chat' ? 'rgba(59, 130, 246, 0.3)' : 'rgba(147, 51, 234, 0.3)'
                      } 50%, transparent 100%)`,
                      backgroundSize: '200% 100%',
                      animation: 'shimmer-scan 2.5s ease-in-out infinite',
                    }}
                  />
                )}
              </span>
              <span className="truncate max-w-[150px]">{config.triggerLabel}</span>
            </button>
          );
        })}

        {/* Single Application tab (carousel of all interfaces). Rendered even when a
            workflow canvas slot is present so the user can swap the side-panel view
            between Workflow and Application without leaving the chat. A permanent,
            non-closeable <button> like the Trigger sub-tabs - it appears
            automatically once the run exposes an interface; clicking it (here or
            via the bottom Application button) focuses APP_TAB. */}
        {showAppTab && (
          <button
            type="button"
            onClick={() => setActiveTabId(APP_TAB_ID)}
            className={cn(
              "flex items-center gap-1 text-sm whitespace-nowrap transition-colors",
              tabsAtBottom
                ? "px-4 py-2.5"
                : cn("px-3 py-2 rounded-t-lg", showTopActiveBorder && "border-b-2"),
              activeTabId === APP_TAB_ID
                ? (tabsAtBottom
                    ? "bg-theme-primary text-theme-primary font-medium"
                    : cn("text-theme-primary font-medium bg-theme-secondary/50", showTopActiveBorder && "border-primary"))
                : (tabsAtBottom
                    ? "text-theme-muted hover:bg-theme-tertiary"
                    : cn("text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/30", showTopActiveBorder && "border-transparent"))
            )}
          >
            <AppWindow className="w-3.5 h-3.5 shrink-0" />
            <span className="flex items-center gap-2">{t('common.application')}</span>
          </button>
        )}

      </div>
    </div>
  );

  // ── Content rendering ──
  const renderContent = () => (
    <>
      {activeTabId === CHAT_TAB_ID ? (
        <ChatCore
          conversationId={conversationId}
          messages={messages}
          isLoading={isLoading}
          onSendMessage={handleSendMessage}
          onStopStream={stopStream}
          hideWorkflowToggle
          hideDataSourceToggle
          workflowId={workflowId}
          className="flex-1 min-h-0 min-w-0"
          externalInputValue={suggestionPrompt || undefined}
          onExternalInputConsumed={handleSuggestionConsumed}
          leadingControl={leadingControl}
          welcomeLayout
          welcomeTitle={<WelcomeTitle>{t('workflowBuilder.canvas.emptyTitle')}</WelcomeTitle>}
        />
      ) : activeTabId === APP_TAB_ID ? (
        <ApplicationCarousel
          configs={applicationConfigs}
          runId={currentRunId}
          workflowId={workflowId}
          onAction={handleApplicationAction}
        />
      ) : activeTabId === WORKFLOW_TAB_ID ? (
        null /* Workflow canvas rendered separately (always mounted) */
      ) : (
        triggerConfigs
          .filter(config => config.triggerId === activeTabId)
          .map(config => (
            <TriggerTabContent
              key={config.triggerId}
              config={config}
              disabled={isTriggerDisabled}
              workflowId={workflowId}
              runId={currentRunId || undefined}
              onExecuteTrigger={handleExecuteTrigger}
              conversationId={conversationId}
              chatMessages={messages}
              reloadConversation={loadConversation}
            />
          ))
      )}
    </>
  );

  // ── Render ──
  return (
    <div className="h-full flex flex-col min-w-0 overflow-hidden">
      {hasExtraTabs ? (
        tabsAtBottom ? (
          <>
            {renderContent()}
            {/* Workflow canvas slot - always mounted, display toggled */}
            {hasWorkflowSlot && (
              <div className="flex-1 min-h-0 flex flex-col overflow-x-auto" style={{ display: activeTabId === WORKFLOW_TAB_ID ? undefined : 'none' }}>
                {workflowCanvasSlot}
              </div>
            )}
            {renderTabBar()}
          </>
        ) : (
          <>
            {renderTabBar()}
            {renderContent()}
            {/* Workflow canvas slot - always mounted, display toggled */}
            {hasWorkflowSlot && (
              <div className="flex-1 min-h-0 flex flex-col overflow-x-auto" style={{ display: activeTabId === WORKFLOW_TAB_ID ? undefined : 'none' }}>
                {workflowCanvasSlot}
              </div>
            )}
          </>
        )
      ) : (
        <>
          {renderContent()}
        </>
      )}
    </div>
  );
}

// ── Public component (wraps with WorkflowModeProvider for context) ──

interface WorkflowPanelContentProps {
  workflowId: string;
  runId?: string;
  /** Explicit preview-only flag - required because SidePanel renders outside WorkflowModeProvider */
  isPreviewOnly?: boolean;
  /** Workflow canvas ReactNode - rendered as an always-mounted sub-tab (replaces Application carousel tab) */
  workflowCanvasSlot?: React.ReactNode;
}

export function WorkflowPanelContent({ workflowId, runId, isPreviewOnly: isPreviewOnlyProp, workflowCanvasSlot }: WorkflowPanelContentProps) {
  // Try parent context first, then fall back to explicit prop.
  // SidePanel lives in AppLayout (outside WorkflowModeProvider), so the prop is needed for marketplace preview.
  const { isPreviewOnly: isPreviewFromContext, workflowId: parentWorkflowId } = useWorkflowMode();
  const isPreview = isPreviewOnlyProp ?? isPreviewFromContext;

  // If we're already inside a WorkflowModeProvider (workflow page layout sets workflowId),
  // reuse it so viewingEpoch and other state are shared with the canvas/RunInfo.
  // Only create a new provider when rendered outside (e.g. SidePanel in AppLayout).
  if (parentWorkflowId) {
    return <WorkflowPanelInner workflowId={workflowId} runId={runId} workflowCanvasSlot={workflowCanvasSlot} isPreviewOnly={isPreview} />;
  }

  return (
    <WorkflowModeProvider workflowId={workflowId} initialRunId={runId} readOnly={isPreview}>
      <WorkflowPanelInner workflowId={workflowId} runId={runId} workflowCanvasSlot={workflowCanvasSlot} isPreviewOnly={isPreview} />
    </WorkflowModeProvider>
  );
}
