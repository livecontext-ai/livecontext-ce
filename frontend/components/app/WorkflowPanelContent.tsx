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
import { Sparkles, MessageSquare, FileText, AppWindow, Workflow, Play, Plus, SlidersHorizontal } from 'lucide-react';
import { usePathname } from '@/i18n/navigation';
import { ChatCore } from '@/components/chat/ChatCore';
import { WelcomeTitle } from '@/app/shared/components';
import { ModelSelectorDropdown, PROVIDER_ICON_MAP } from '@/components/chat/ModelSelectorDropdown';
import { modelFilterLabelsFrom } from '@/components/chat/modelFilterLabels';
import { NoProviderCta } from '@/components/ai/NoProviderCta';
import { UpgradeRequiredNotice } from '@/components/billing/UpgradeRequiredBadge';
import { ComposerFreeTierBadge } from '@/components/billing/FreeTierBadge';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import { resolveFreeTierPreferredModel } from '@/lib/hooks/usePreferFreeTierModel';
import { TriggerTabContent } from '@/components/chat/TriggerTabContent';
import { type ApplicationConfig, type ApplicationTemplateSource } from '@/components/chat/ApplicationTabContent';
import { ApplicationCarousel } from '@/components/chat/ApplicationCarousel';
import { cn } from '@/lib/utils';
import { panelTabClass } from '@/components/ui/panel-tab';
import { useTranslations } from 'next-intl';
import { useWorkflowChat } from '@/hooks/useWorkflowChat';
import { useVisibleModels, AIModel, SelectedModel, EMPTY_SELECTED_MODEL, modelMatches, selectedModelFromAIModel, selectedModelEquals, getEffectiveDefaultSelectedModel } from '@/hooks/useModels';
import { useUnifiedAppSafe } from '@/contexts/UnifiedAppContext';
import { useStreaming } from '@/contexts/StreamingContext';
import { WorkflowModeProvider, useWorkflowMode } from '@/contexts/WorkflowModeContext';
import type { TriggerPanelConfig } from '@/app/workflows/builder/components/TriggerPanel';
import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';
import { isNavigateRef, navigateTargetLabel } from '@/app/workflows/builder/utils/interfaceActionRefs';
import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';
import { useCanMutateInCurrentOrg, useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { OPEN_TRIGGER_TAB_EVENT, findTriggerTabConfig, type OpenTriggerTabDetail } from '@/lib/workflow/triggerTabEvent';
import { NodeCreatorPanelContent } from '@/components/app/NodeCreatorPanelContent';
import {
  getInspectorDockState,
  isInspectorOpenRequestFor,
  setInspectorDockHost,
  subscribeInspectorDockState,
  OPEN_INSPECTOR_PANEL_EVENT,
  type InspectorDockState,
  type InspectorDockSurface,
  type OpenInspectorPanelDetail,
} from '@/lib/workflow/inspectorDockBus';
import { WorkflowPanelActions } from '@/components/app/WorkflowPanelActions';
import { RunPanelContent, type RunPanelView } from '@/components/workflow/run-panel/RunPanelContent';
import { RunActionButton, resolveRunAction } from '@/components/workflow/run-panel/RunActionButton';
import { useRunActions } from '@/components/workflow/run-panel/useRunActions';
import { resetEpochSelectionState } from '@/components/workflow/run-panel/useDefaultEpochSelection';
import {
  clearRunPanelCache,
  consumeRunPanelViewRequest,
  getCachedRunPanelData,
  getRunPanelViewRequest,
  subscribeRunPanelData,
  OPEN_NODE_CREATOR_EVENT,
  OPEN_RUN_PANEL_EVENT,
  type OpenRunPanelDetail,
  type RunPanelData,
} from '@/components/workflow/run-panel/runPanelBus';
import { WorkflowLogsPanelContent } from '@/components/workflow/WorkflowLogsPanelContent';
import {
  clearPendingWorkflowPanelLogs,
  consumePendingWorkflowPanelLogs,
  WORKFLOW_PANEL_OPEN_LOGS_EVENT,
  type WorkflowLogsNavigationTarget,
} from '@/lib/sidePanel/workflowLogsNavigation';
import { WorkflowPanelHostProvider } from '@/contexts/WorkflowPanelHostContext';

// ── Constants ──

const CHAT_TAB_ID = '__chat_ia__';
const APP_TAB_ID = '__application__';
export const WORKFLOW_TAB_ID = '__workflow__';
/** Run history + epochs + steps of the current run (run mode). */
export const RUN_TAB_ID = '__run__';
/** Logs of the bound run, with navigation back to its Run sub-tab. */
export const LOGS_TAB_ID = '__logs__';
/** Node palette (edit mode) - the former floating "Add node" panel. */
export const NODE_CREATOR_TAB_ID = '__add_node__';
/** Configuration of the node selected on the canvas - the docked inspector. */
export const INSPECTOR_TAB_ID = '__inspector__';

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
        clearRunPanelCache();
        clearPendingWorkflowPanelLogs();
        // The "user picked this run's epoch" flags are keyed by run id and would
        // otherwise survive the switch (and grow unbounded across a long session).
        resetEpochSelectionState();
      },
    );
  }).catch(() => {});
}

// ── Inner content (rendered inside WorkflowModeProvider) ──

function WorkflowPanelInner({ workflowId, runId: runIdProp, workflowCanvasSlot, isPreviewOnly = false, allowRunHistory: allowRunHistoryProp, runSurfaceId, hostTabId, applicationFirst = false, initialApplicationConfigs, applicationTemplateSource, canEditWorkflow = true }: { workflowId: string; runId?: string; workflowCanvasSlot?: React.ReactNode; isPreviewOnly?: boolean; allowRunHistory?: boolean; runSurfaceId?: string; hostTabId?: string; applicationFirst?: boolean; initialApplicationConfigs?: ApplicationConfig[]; applicationTemplateSource?: ApplicationTemplateSource; canEditWorkflow?: boolean }) {
  const t = useTranslations();
  const pathname = usePathname();

  // ── Model selector state ──
  const { models, defaultModel, isLoading: modelsLoading, error: modelsError } = useVisibleModels();
  // Same gate as ModelPicker: never show the no-provider empty state while the
  // catalog is loading or after a fetch error - only once it RESOLVED empty.
  const modelsResolvedEmpty = !modelsLoading && !modelsError;
  // Asked once for the whole menu: the answer is about the account, not
  // about any one model.
  const { blocked: creditsCannotPay, blockedForModel, freeTierForModel, prefersFreeTierModels, verdictReady } =
    useMonthlyCreditsCannotPay();
  const appContext = useUnifiedAppSafe();
  const setSelectedModel = appContext?.setSelectedModel ?? ((_: SelectedModel) => {});
  const appSelectedModel: SelectedModel = appContext?.state.selectedModel ?? EMPTY_SELECTED_MODEL;

  // V494: a free-tier account opens on a model its allowance covers, when one
  // exists. Without this the composer opens on the admin's global #1 and the very
  // first turn of a fresh signup can be refused - the moment the allowance is for.
  const defaultAIModel: AIModel | undefined = useMemo(
    () => resolveFreeTierPreferredModel(
      models,
      (defaultModel ? models.find(m => m.id === defaultModel) : undefined) ?? models[0],
      prefersFreeTierModels,
    ),
    [models, defaultModel, prefersFreeTierModels],
  );
  const effectiveDefault: SelectedModel = useMemo(
    () => (defaultAIModel ? selectedModelFromAIModel(defaultAIModel) : getEffectiveDefaultSelectedModel()),
    [defaultAIModel],
  );
  const isValidModel = models.length > 0 && !!appSelectedModel.id && models.some(m => modelMatches(m, appSelectedModel));
  const selectedModel: SelectedModel = isValidModel ? appSelectedModel : effectiveDefault;

  useEffect(() => {
    if (!appContext || isValidModel || !effectiveDefault.id) return;
    // V494: wait for the plan verdict before WRITING. prefersFreeTierModels is false
    // while the balance request is in flight, which is indistinguishable from a paid
    // account - and if the models land first, this effect pins the catalogue default,
    // isValidModel flips true, and the free-tier answer arriving a tick later never
    // applies. The selection is persisted, so that wrong default is permanent.
    if (!verdictReady) return;
    if (!selectedModelEquals(appSelectedModel, effectiveDefault)) {
      setSelectedModel(effectiveDefault);
    }
  }, [isValidModel, effectiveDefault, appSelectedModel, setSelectedModel, appContext, verdictReady]);

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
      filterLabels={modelFilterLabelsFrom(t)}
      emptyState={modelsResolvedEmpty ? <NoProviderCta variant="menu" /> : undefined}
      upgradeRequired={creditsCannotPay}
      blockedForModel={blockedForModel}
      freeTierForModel={freeTierForModel}
      prefersFreeTierModels={prefersFreeTierModels}
      upgradeNotice={<UpgradeRequiredNotice blocked={creditsCannotPay} />}
      freeTierBadge={<ComposerFreeTierBadge />}
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
    // Named: two panels (a page one and a side-panel one) can stream at once, and
    // the Save controls disable themselves on THEIR workflow's stream only.
    window.dispatchEvent(new CustomEvent('workflowStreamingStateChange', {
      detail: { isStreaming, workflowId },
    }));
  }, [isStreaming, workflowId]);

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
  // Seeded from the host when it has already resolved the interfaces (the
  // application panel does, before it mounts this). Without a seed the
  // Application sub-tab only exists once the canvas has loaded its plan and
  // emitted them, so an application panel would open on the canvas and jump to
  // the interface a second later.
  const [applicationConfigs, setApplicationConfigs] = useState<ApplicationConfig[]>(
    () => (myCache.applicationConfigs.length > 0 ? myCache.applicationConfigs : (initialApplicationConfigs ?? [])),
  );

  // ── Run identity (drives the Run sub-tab and the edit/run split) ──
  // Published by WorkflowRunCanvas through the run-panel bus, with a cache so a
  // panel that mounts after the run started still gets the current state.
  //
  // Only the three PRIMITIVES this component actually reads are kept in state.
  // The snapshot itself changes identity on every streamed step batch (several
  // times a second on a live run); storing it whole re-rendered the entire panel
  // tree - chat included - on each tick. The Run tab subscribes separately for
  // the full snapshot, and only while it is mounted.
  const [runData, setRunData] = useState(() => {
    const cached = getCachedRunPanelData(workflowId, runSurfaceId);
    return { runId: cached.runId, hasRunInfo: !!cached.runInfo, isPreviewOnly: cached.isPreviewOnly };
  });
  useEffect(() => {
    const adopt = (data: RunPanelData) => {
      setRunData(prev => (
        prev.runId === data.runId
          && prev.hasRunInfo === !!data.runInfo
          && prev.isPreviewOnly === data.isPreviewOnly
          ? prev
          : { runId: data.runId, hasRunInfo: !!data.runInfo, isPreviewOnly: data.isPreviewOnly }
      ));
    };
    adopt(getCachedRunPanelData(workflowId, runSurfaceId));
    return subscribeRunPanelData(workflowId, adopt, runSurfaceId);
  }, [workflowId, runSurfaceId]);

  // ── Run ID the sub-tabs render against ──
  // The CANVAS's own run wins: it is the run actually bound, whether it came from
  // the /run/<id> URL, from an agent-launched overlay, or from the user picking a
  // run in the panel's history (bound in place, so the URL still names the run the
  // page was opened with). Reading the URL first left the Application carousel and
  // the Trigger tabs on the PREVIOUS run after such a switch.
  // Falls back to the URL, then to the prop (application mode / marketplace preview).
  const runIdFromPath = pathname?.match(/\/workflow\/[^\/]+\/run\/([^\/]+)/)?.[1] || null;
  const currentRunId = runData.runId || triggerRunId || runIdFromPath || runIdProp || null;

  /**
   * Which page of the application to show, named by INTERFACE rather than by
   * position.
   *
   * The carousel already resolves this itself (`targetInterfaceId`), and going
   * through it beats writing an index into the store: the store is keyed by
   * workflow AND run, so a panel opened before its run was bound wrote the index
   * under one key and the carousel then read another - the requested page
   * silently became page one. An interface id does not move when the run does.
   */
  const [targetInterfaceId, setTargetInterfaceId] = useState<string | null>(null);
  const clearCarouselTarget = useCallback(() => setTargetInterfaceId(null), []);

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
  // `applicationFirst` overrides both: a panel opened ON an application exists to
  // show that application, preview included.
  const hideAppTabByDefault = !applicationFirst && hasWorkflowSlot && (isPreviewOnly || isApplicationRoute);
  const [isAppTabDismissed, setIsAppTabDismissed] = useState(hideAppTabByDefault);
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  useEffect(() => {
    setIsAppTabDismissed(hideAppTabByDefault);
  }, [currentOrgId, hideAppTabByDefault]);
  const showAppTab = applicationConfigs.length > 0 && !isAppTabDismissed;

  // ── Run / Add Node sub-tabs ──
  // A run is bound (URL run, in-place run, sub-workflow run, frozen preview run)
  // → the Run tab holds the run history, the epochs and the steps. No run → we
  // are editing, so the node palette takes that slot instead. They are mutually
  // exclusive because the canvas is in exactly one of the two modes.
  const hasRun = runData.hasRunInfo || !!runData.runId;
  const showRunTab = hasRun;
  // The palette is an editing tool, so it follows the same permission as the Save
  // beside it: on a workflow the caller may not change (an installed application's
  // frozen clone, someone else's published workflow) a dropped node could never be
  // saved, and the panel would be inviting an edit it has no Save for.
  const showNodeCreatorTab = !hasRun && !isPreviewOnly && canEditWorkflow;

  // ── Inspector sub-tab (the node inspector, docked out of the canvas) ──
  // Published by the canvas this panel is paired with, when the user's preference
  // puts the inspector here and a node is selected. Read from the module cache on
  // mount, not just from the next publish: the panel body is unmounted while the
  // side panel is closed, so reopening it on a canvas that already has a node
  // selected must still find the tab.
  //
  // Which canvas is "paired" is exactly what the surface says, and this panel
  // knows it from a fact it already has. Hosting a canvas (`workflowCanvasSlot`)
  // means the Application panel or a sub-workflow tab: the canvas is a sub-tab of
  // THIS panel, so the inspector becomes its sibling and the two are shown one at
  // a time. No canvas slot means the panel beside a workflow page, whose canvas is
  // the page itself. Listening on both would make one panel answer for the other.
  const inspectorDockSurface: InspectorDockSurface = hasWorkflowSlot ? 'embedded' : 'page';
  const [inspectorDock, setInspectorDock] = useState<InspectorDockState>(
    () => getInspectorDockState(workflowId, inspectorDockSurface),
  );
  useEffect(() => {
    setInspectorDock(getInspectorDockState(workflowId, inspectorDockSurface));
    return subscribeInspectorDockState(workflowId, inspectorDockSurface, setInspectorDock);
  }, [workflowId, inspectorDockSurface]);
  const showInspectorTab = inspectorDock.docked && !isPreviewOnly;

  // The slot itself. A ref callback rather than an effect so the element is
  // published in the same commit it is attached, and withdrawn on unmount - a
  // detached node left in the registry would silently swallow the portal.
  const registerInspectorHost = useCallback((el: HTMLDivElement | null) => {
    setInspectorDockHost(workflowId, inspectorDockSurface, el);
  }, [workflowId, inspectorDockSurface]);
  // Run history is a navigation between runs of the SAME workflow. The default
  // is "only the standalone workflow page", because an embedded canvas whose host
  // CHOSE the run for it - the application panel, the marketplace preview - must
  // stay on that run. A host that can rebind its own canvas overrides it: the
  // sub-workflow tab does, and without the override its run detail had no way
  // back up to the list of runs it came from. Preview is never negotiable.
  const allowRunHistory = (allowRunHistoryProp ?? !hasWorkflowSlot) && !isPreviewOnly;

  /**
   * Tab to show when nothing else applies: the Application on a panel opened on
   * an application, the canvas when this panel hosts one, else the AI chat.
   * Also where the fallback effect below lands when the active tab disappears.
   */
  const defaultTabId = applicationFirst
    ? APP_TAB_ID
    : (hasWorkflowSlot ? WORKFLOW_TAB_ID : CHAT_TAB_ID);
  const [activeTabId, setActiveTabId] = useState(defaultTabId);
  const [logsTarget, setLogsTarget] = useState<WorkflowLogsNavigationTarget | null>(null);
  const showLogsTab = (showRunTab && !!currentRunId) || !!logsTarget;
  const hasExtraTabs = visibleTriggerConfigs.length > 0 || showAppTab || hasWorkflowSlot || showRunTab || showLogsTab || showNodeCreatorTab || showInspectorTab;

  useEffect(() => {
    setLogsTarget((target) => (
      target
        && target.targetTabId === hostTabId
        && target.workflowId === workflowId
        && target.runId === currentRunId
        ? target
        : null
    ));
  }, [currentRunId, hostTabId, workflowId]);

  const selectPanelTab = useCallback((tabId: string) => {
    setActiveTabId(tabId);
  }, []);

  const openRunLogs = useCallback(() => {
    if (!currentRunId) return;
    setLogsTarget({ targetTabId: hostTabId ?? '', workflowId, runId: currentRunId });
    setActiveTabId(LOGS_TAB_ID);
  }, [currentRunId, hostTabId, workflowId]);

  // Logs have their own sub-tab, scoped to the run. Requests target one outer
  // workflow tab so several mounted workflows cannot steal each other's view.
  useEffect(() => {
    if (!hostTabId) return;

    const showLogs = (target: WorkflowLogsNavigationTarget) => {
      if (target.targetTabId !== hostTabId || target.workflowId !== workflowId) return;
      setActiveTabId(LOGS_TAB_ID);
      setLogsTarget(target);
    };
    const handleOpenLogs = (event: CustomEvent<WorkflowLogsNavigationTarget>) => {
      if (event.detail.targetTabId === hostTabId) {
        consumePendingWorkflowPanelLogs(hostTabId);
      }
      showLogs(event.detail);
    };

    window.addEventListener(WORKFLOW_PANEL_OPEN_LOGS_EVENT, handleOpenLogs as EventListener);
    const pending = consumePendingWorkflowPanelLogs(hostTabId);
    if (pending) showLogs(pending);

    return () => window.removeEventListener(WORKFLOW_PANEL_OPEN_LOGS_EVENT, handleOpenLogs as EventListener);
  }, [hostTabId, workflowId]);
  /** Which level the Run tab should show (history vs run detail). */
  const [runViewRequest, setRunViewRequest] = useState<{ view: RunPanelView; seq: number } | null>(
    () => {
      const pending = getRunPanelViewRequest(workflowId);
      return pending ? { view: pending.view, seq: pending.seq } : null;
    },
  );
  // Adopted at mount above; drop it from the bus so a remount much later does not
  // replay a level the user asked for one navigation ago.
  useEffect(() => {
    consumeRunPanelViewRequest(workflowId);
  }, [workflowId]);

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
      // ...unless this panel was opened ON the application, where the canvas is
      // the secondary view and the interface is what the user asked for.
      if (!hasWorkflowSlot || applicationFirst) setActiveTabId(APP_TAB_ID);
    }
    prevAppConfigCount.current = applicationConfigs.length;
  }, [applicationConfigs.length, hideAppTabByDefault, hasWorkflowSlot, applicationFirst]);

  // Fall back to a still-available sub-tab when the active one disappears - a
  // trigger that vanished, an interface that is gone, the run that ended (Run
  // tab) or the run that started (Add Node tab). Without this the panel renders
  // an empty body. The Application tab only falls back when its CONFIGS are
  // gone: a user-dismissed app tab keeps showing its content.
  useEffect(() => {
    const isTriggerTab = triggerConfigs.some(c => c.triggerId === activeTabId);
    const isActiveTabAvailable =
      activeTabId === CHAT_TAB_ID ||
      isTriggerTab ||
      (activeTabId === WORKFLOW_TAB_ID && hasWorkflowSlot) ||
      (activeTabId === APP_TAB_ID && applicationConfigs.length > 0) ||
      (activeTabId === RUN_TAB_ID && showRunTab) ||
      (activeTabId === LOGS_TAB_ID && !!logsTarget) ||
      (activeTabId === NODE_CREATOR_TAB_ID && showNodeCreatorTab) ||
      (activeTabId === INSPECTOR_TAB_ID && showInspectorTab);
    if (!isActiveTabAvailable) {
      // An application-first panel whose interfaces have not arrived yet keeps
      // waiting on the Application tab rather than flashing the canvas.
      setActiveTabId(activeTabId === LOGS_TAB_ID && showRunTab
        ? RUN_TAB_ID
        : applicationFirst && hasWorkflowSlot ? APP_TAB_ID : defaultTabId);
    }
  }, [triggerConfigs, applicationConfigs.length, activeTabId, hasWorkflowSlot, showRunTab, showNodeCreatorTab, showInspectorTab, applicationFirst, defaultTabId, logsTarget]);

  // Consume pending tab activation (set before panel was opened)
  useEffect(() => {
    const pending = pendingActivateTabByWorkflow.get(workflowId) ?? pendingActivateTabByWorkflow.get('__default__');
    if (pending) {
      if (pending.startsWith('app-')) {
        const interfaceId = pending.replace('app-', '');
        if (applicationConfigs.some(c => c.interfaceId === interfaceId)) {
          setActiveTabId(APP_TAB_ID);
          setTargetInterfaceId(interfaceId);
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
    const handleOpenTriggerTab = (event: CustomEvent<OpenTriggerTabDetail>) => {
      const match = findTriggerTabConfig(triggerConfigs, event.detail);
      if (match) selectPanelTab(match.triggerId);
    };
    window.addEventListener(OPEN_TRIGGER_TAB_EVENT, handleOpenTriggerTab as EventListener);
    return () => window.removeEventListener(OPEN_TRIGGER_TAB_EVENT, handleOpenTriggerTab as EventListener);
  }, [selectPanelTab, triggerConfigs]);

  // Listen for application tab open requests from node clicks → open Application tab + navigate carousel.
  // Works even when a workflow canvas slot is mounted: switching to APP_TAB_ID hides the
  // workflow slot (its wrapper uses display:none when activeTabId !== WORKFLOW_TAB_ID) and
  // renders the ApplicationCarousel in its place inside the side panel.
  useEffect(() => {
    const handleOpenApplicationTab = (event: CustomEvent<{ interfaceId: string }>) => {
      if (applicationConfigs.some(c => c.interfaceId === event.detail.interfaceId)) {
        setIsAppTabDismissed(false);
        selectPanelTab(APP_TAB_ID);
        setTargetInterfaceId(event.detail.interfaceId);
      }
    };
    window.addEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    return () => window.removeEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
  }, [applicationConfigs, selectPanelTab]);

  // Listen for external tab activation (e.g. toggle button in ApplicationDetailView)
  useEffect(() => {
    const handler = (event: CustomEvent<{ tabId: string; workflowId?: string }>) => {
      if (event.detail.workflowId && event.detail.workflowId !== workflowId) return;
      // Same refusal as the canvas "+": the palette is not on offer here, and
      // honouring it would move the reader off their tab for a commit before the
      // availability effect took it back.
      if (event.detail.tabId === NODE_CREATOR_TAB_ID && !showNodeCreatorTab) return;
      selectPanelTab(event.detail.tabId);
    };
    window.addEventListener('workflowPanelActivateTab', handler as EventListener);
    return () => window.removeEventListener('workflowPanelActivateTab', handler as EventListener);
  }, [workflowId, showNodeCreatorTab, selectPanelTab]);

  // Listen for "open the Run tab" requests (canvas history button, version chip).
  // Scoped by workflowId so a sub-workflow tab and the main panel never steal
  // each other's request - each shows ITS OWN run.
  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<OpenRunPanelDetail>).detail ?? {};
      if (detail.workflowId && detail.workflowId !== workflowId) return;
      setRunViewRequest(prev => ({ view: detail.view ?? 'run', seq: (prev?.seq ?? 0) + 1 }));
      selectPanelTab(RUN_TAB_ID);
      // Handled live, so drop it from the bus too: the mount-time consume never
      // runs for a panel that is ALREADY mounted, and a request left behind is
      // replayed on the next remount - the level the user asked for one
      // navigation ago, applied to whatever they are looking at now.
      consumeRunPanelViewRequest(workflowId);
    };
    window.addEventListener(OPEN_RUN_PANEL_EVENT, handler);
    return () => window.removeEventListener(OPEN_RUN_PANEL_EVENT, handler);
  }, [workflowId, selectPanelTab]);

  // Same for the canvas "+" - and it needs its own listener for the same reason
  // the Run tab does: when the panel is ALREADY open on this tab, the page-level
  // handler's setActiveTab is a no-op and nothing would react to the click.
  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<{ workflowId?: string }>).detail ?? {};
      if (detail.workflowId && detail.workflowId !== workflowId) return;
      // Refuse it where the tab itself is not on offer. Leaving it to the
      // availability effect is not equivalent: that effect resets to the DEFAULT
      // tab, so a "+" the panel cannot honour would still take the reader off
      // whatever tab they were on and drop them at the canvas.
      if (!showNodeCreatorTab) return;
      selectPanelTab(NODE_CREATOR_TAB_ID);
    };
    window.addEventListener(OPEN_NODE_CREATOR_EVENT, handler);
    return () => window.removeEventListener(OPEN_NODE_CREATOR_EVENT, handler);
  }, [workflowId, showNodeCreatorTab, selectPanelTab]);

  // Selecting a node on the canvas focuses the Inspector sub-tab. Same listener
  // rationale as the two above: with the panel already open on this tab, the
  // page-level handler cannot react, only an in-panel listener can. Focus is
  // taken on the OPEN request, never on the dock state itself - the state also
  // changes when the selected node's LABEL is edited, and stealing the tab back
  // mid-rename would fight the user.
  //
  // The request is RECORDED, not applied on the spot. It arrives beside the
  // publish that makes the tab exist, and where the two do not land in the same
  // commit, setting the active tab first means setting one the "active tab
  // vanished" fallback below immediately undoes - the tab appears and the panel
  // stays on the canvas. Holding it until the tab is really available removes the
  // ordering assumption instead of betting on it.
  const [inspectorFocusRequest, setInspectorFocusRequest] = useState(0);
  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<OpenInspectorPanelDetail>).detail;
      if (!isInspectorOpenRequestFor(detail, workflowId, inspectorDockSurface)) return;
      setInspectorFocusRequest(n => n + 1);
    };
    window.addEventListener(OPEN_INSPECTOR_PANEL_EVENT, handler);
    return () => window.removeEventListener(OPEN_INSPECTOR_PANEL_EVENT, handler);
  }, [workflowId, inspectorDockSurface]);

  useEffect(() => {
    if (inspectorFocusRequest === 0 || !showInspectorTab) return;
    setInspectorFocusRequest(0);
    selectPanelTab(INSPECTOR_TAB_ID);
  }, [inspectorFocusRequest, showInspectorTab, selectPanelTab]);


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
        detail: { requestId, triggerId, triggerType, payload, workflowId },
      }));

      // Timeout after 30s
      setTimeout(() => {
        window.removeEventListener('workflowExecuteTriggerResponse', responseHandler);
        resolve(undefined);
      }, 30_000);
    });
  }, [workflowId]);

  // ── Application action via event bridge ──
  const handleApplicationAction = useCallback(async (
    triggerRef: string,
    data: Record<string, unknown>
  ) => {
    // Navigate action: pure frontend carousel switch - no API call.
    // "interface:settings_page:navigate" → target normalized label = "settings_page"
    if (isNavigateRef(triggerRef)) {
      const targetLabel = navigateTargetLabel(triggerRef);
      if (targetLabel) {
        const normalizedTarget = normalizeLabel(targetLabel);
        const target = applicationConfigs.find(c => normalizeLabel(c.label) === normalizedTarget);
        if (target) {
          selectPanelTab(APP_TAB_ID);
          setTargetInterfaceId(target.interfaceId);
          return;
        }
        console.warn('[WorkflowPanelContent] Navigate target not found:', targetLabel, 'normalized:', normalizedTarget);
      }
      return;
    }

    window.dispatchEvent(new CustomEvent('workflowApplicationActionRequest', {
      detail: { triggerRef, data, workflowId },
    }));
  }, [applicationConfigs, workflowId, selectPanelTab]);

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

  // ── Tab count & position ──
  const tabCount = 1 /* AI Chat */ + visibleTriggerConfigs.length
    + (hasWorkflowSlot ? 1 : 0)
    + (showAppTab ? 1 : 0)
    + (showRunTab ? 1 : 0)
    + (showLogsTab ? 1 : 0)
    + (showNodeCreatorTab ? 1 : 0)
    + (showInspectorTab ? 1 : 0);
  // `hasExtraTabs` implies at least one extra tab, so tabCount is always >= 2 here:
  // tabsAtBottom is currently equivalent to hasExtraTabs, and renderTabBar() is only
  // reached under it. The top-position branch below is therefore unreachable today,
  // kept as the wiring for a future top-docked variant.
  const tabsAtBottom = hasExtraTabs && tabCount >= 2;

  /**
   * Shared class recipe for a sub-tab button.
   *
   * Delegates to `panelTabClass` so every sub-tab - including the Run and Add
   * Node tabs this file adds - inherits the Button-derived shape, height,
   * transitions and focus ring from one place, instead of each tab bar carrying
   * its own copy of the active/inactive pattern.
   */
  const focusWorkflowTab = useCallback(() => selectPanelTab(WORKFLOW_TAB_ID), [selectPanelTab]);

  const subTabClass = (isActive: boolean) => cn(
    panelTabClass(isActive, 'sm'),
    "flex-shrink-0",
  );

  /**
   * Share / Save / Run of the embedded canvas, docked at the end of the sub-tab
   * row while the Workflow tab is the one showing.
   *
   * Only when this panel HOSTS a canvas: on the standalone workflow page the
   * page header already carries these three, and duplicating them there would
   * put two Save buttons on screen. They are pinned to the Workflow tab because
   * that is the tab they act on - in AI Chat or in the Application they would be
   * three controls with no visible subject. And never in a marketplace preview,
   * which can neither save, run nor publish someone else's frozen snapshot -
   * checked here as well as inside, so the bar's separator does not survive as an
   * empty bordered box.
   */
  const canHostCanvasActions = hasWorkflowSlot && !isPreviewOnly && canEditWorkflow;
  const showCanvasActions = canHostCanvasActions && activeTabId === WORKFLOW_TAB_ID;

  /**
   * Stop the run, from whichever sub-tab has no stop of its own.
   *
   * Launching a workflow lands the user somewhere the run controls never
   * reached: the form or chat tab of the trigger that needs a payload, the AI
   * Chat, the node palette. A live run had no stop on those tabs at all - the
   * user had to know the control existed on a different tab. The tab bar is the
   * one piece of chrome every sub-tab shares, so it lives here.
   *
   * It is here on EVERY sub-tab, including the three that carry a stop of their
   * own. Suppressing it on those was tried and was wrong twice over, because
   * "this tab has a stop" is never unconditional:
   *  - the Application tab's stop lives in the interface controls, COLLAPSED
   *    behind a grip by default, which is the state a user actually finds;
   *  - the canvas pill needs run mode and no settings drawer open;
   *  - the Run tab shows the run identity bar only on its RUN level - walk up to
   *    the run history and the panel has no action control at all.
   * Each exception needs a condition the tab bar cannot see, and getting one
   * wrong costs the user the only control on screen. A duplicate button is a
   * cosmetic cost; an absent one is this entire bug.
   *
   * Deliberately the STOP alone (RUNNING / PAUSED). Cancel and reactivate belong
   * to the run's lifecycle, which the Run tab and the canvas pill present in
   * full; a permanent red square beside the tabs for every idle WAITING_TRIGGER
   * run would be noise, and a destructive one. Gated on the workspace role
   * because stopping a run is a mutation - deliberately NOT on `canEditWorkflow`
   * like the Save/Run cluster, since stopping your own run of an application you
   * acquired is not editing its publisher's workflow.
   */
  // Given the run this PANEL resolved, like its two sibling surfaces do. Reading
  // the bus alone leaves the control absent on a /run/<id> deep link until the
  // canvas publishes, and targets the bus' run if the panel ever shows another.
  const runActions = useRunActions(workflowId, currentRunId, runSurfaceId);
  const canMutate = useCanMutateInCurrentOrg();
  // Same exclusion the application controls make, and for the same reason: the
  // gateway's share allow-list does not cover stopping a run, so a visitor's
  // click would 403. What keeps this panel off a share page today is a
  // `display:none` wrapper, which is a layout detail, not a guard - the control
  // is still in the visitor's DOM.
  const isPublicShareRoute = (pathname ?? '').startsWith('/s/');
  const showStopInTabBar = canMutate
    && !isPublicShareRoute
    && !isPreviewOnly
    && !runData.isPreviewOnly
    && resolveRunAction(runActions.status) === 'stop';

  // ── Tab bar rendering (shared between top and bottom positions) ──
  const renderTabBar = () => (
    <div className={cn(
      "flex-shrink-0 bg-theme-primary flex items-center",
      tabsAtBottom ? "border-t border-theme" : "border-b border-theme"
    )}>
      <div className="flex-1 min-w-0 flex items-center gap-1 px-2 py-1.5 overflow-x-auto overflow-y-hidden">
        {/* AI Chat tab */}
        <button
          type="button"
          aria-pressed={activeTabId === CHAT_TAB_ID}
          data-testid="panel-sub-tab"
          data-active={activeTabId === CHAT_TAB_ID ? 'true' : undefined}
          onClick={() => selectPanelTab(CHAT_TAB_ID)}
          className={subTabClass(activeTabId === CHAT_TAB_ID)}
        >
          <Sparkles className="w-3.5 h-3.5 shrink-0" />
          {t('sidePanel.aiChat')}
        </button>

        {/* Workflow canvas tab - before triggers so it's in 2nd position */}
        {hasWorkflowSlot && (
          <button
            type="button"
            aria-pressed={activeTabId === WORKFLOW_TAB_ID}
            data-testid="panel-sub-tab"
            data-active={activeTabId === WORKFLOW_TAB_ID ? 'true' : undefined}
            onClick={() => selectPanelTab(WORKFLOW_TAB_ID)}
            className={subTabClass(activeTabId === WORKFLOW_TAB_ID)}
          >
            <Workflow className="w-3.5 h-3.5 shrink-0" />
            {t('common.workflow')}
          </button>
        )}

        {/* Run tab - run history, epochs and steps of the bound run */}
        {showRunTab && (
          <button
            type="button"
            data-run-tab-button
            aria-pressed={activeTabId === RUN_TAB_ID}
            data-testid="panel-sub-tab"
            data-active={activeTabId === RUN_TAB_ID ? 'true' : undefined}
            onClick={() => selectPanelTab(RUN_TAB_ID)}
            className={subTabClass(activeTabId === RUN_TAB_ID)}
          >
            <Play className="w-3.5 h-3.5 shrink-0" />
            {t('sidePanel.runTab')}
          </button>
        )}

        {showLogsTab && (
          <button
            type="button"
            data-logs-tab-button
            aria-pressed={activeTabId === LOGS_TAB_ID}
            data-testid="panel-sub-tab"
            data-active={activeTabId === LOGS_TAB_ID ? 'true' : undefined}
            onClick={() => logsTarget ? selectPanelTab(LOGS_TAB_ID) : openRunLogs()}
            className={subTabClass(activeTabId === LOGS_TAB_ID)}
          >
            <FileText className="w-3.5 h-3.5 shrink-0" />
            {t('actions.logs')}
          </button>
        )}

        {/* Add Node tab - the node palette (edit mode) */}
        {showNodeCreatorTab && (
          <button
            type="button"
            data-node-creator-tab-button
            aria-pressed={activeTabId === NODE_CREATOR_TAB_ID}
            data-testid="panel-sub-tab"
            data-active={activeTabId === NODE_CREATOR_TAB_ID ? 'true' : undefined}
            onClick={() => selectPanelTab(NODE_CREATOR_TAB_ID)}
            className={subTabClass(activeTabId === NODE_CREATOR_TAB_ID)}
          >
            <Plus className="w-3.5 h-3.5 shrink-0" />
            {t('workflowBuilder.canvas.addNode')}
          </button>
        )}

        {/* Inspector tab - the configuration of the node selected on the canvas.
            Captioned with the node's own label so a panel with several sub-tabs
            still says WHICH node is being configured. */}
        {showInspectorTab && (
          <button
            type="button"
            data-inspector-tab-button
            aria-pressed={activeTabId === INSPECTOR_TAB_ID}
            data-testid="panel-sub-tab"
            data-active={activeTabId === INSPECTOR_TAB_ID ? 'true' : undefined}
            onClick={() => selectPanelTab(INSPECTOR_TAB_ID)}
            className={subTabClass(activeTabId === INSPECTOR_TAB_ID)}
          >
            <SlidersHorizontal className="w-3.5 h-3.5 shrink-0" />
            <span className="truncate max-w-[150px]">
              {inspectorDock.label || t('sidePanel.inspectorTab')}
            </span>
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
              aria-pressed={isActive}
              data-testid="panel-sub-tab"
              data-active={isActive ? 'true' : undefined}
              onClick={() => selectPanelTab(config.triggerId)}
              className={cn(
                panelTabClass(isActive, 'sm'),
                "flex-shrink-0"
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
                    className="absolute -inset-1.5 rounded-md pointer-events-none overflow-hidden"
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
            aria-pressed={activeTabId === APP_TAB_ID}
            data-testid="panel-sub-tab"
            data-active={activeTabId === APP_TAB_ID ? 'true' : undefined}
            onClick={() => selectPanelTab(APP_TAB_ID)}
            className={subTabClass(activeTabId === APP_TAB_ID)}
          >
            <AppWindow className="w-3.5 h-3.5 shrink-0" />
            <span className="flex items-center gap-2">{t('common.application')}</span>
          </button>
        )}

      </div>

      {/* Outside the scrolling track on purpose: the one control that stops a
          live run must stay on screen when the tabs overflow. */}
      {showStopInTabBar && (
        <div className="flex-shrink-0 flex items-center pr-2" data-testid="panel-run-stop">
          <RunActionButton
            status={runActions.status}
            onStop={() => runActions.perform('stop')}
            pendingAction={runActions.pending}
            failed={runActions.failed}
            size="panel"
          />
        </div>
      )}

      {/* Mounted for as long as the panel hosts a canvas, and merely HIDDEN off
          the Workflow tab. Unmounting it looked equivalent and was not: its
          dirty flag, save status and streaming gate are fed by events that fire
          on CHANGE only, so a remount came back believing the canvas was clean
          and left Save greyed out over unsaved work. Same reason the canvas slot
          below is display-toggled rather than conditionally rendered. */}
      {canHostCanvasActions && (
        <div
          className="flex-shrink-0 flex items-center gap-1 border-l border-theme pl-1.5 pr-2 py-1.5"
          data-testid="panel-canvas-actions"
          style={{ display: showCanvasActions ? undefined : 'none' }}
        >
          <WorkflowPanelActions
            workflowId={workflowId}
            /* The canvas' own run state (run-panel bus), the same signal that
               decides between the Run and Add-node sub-tabs - so the bar and the
               tabs can never disagree about which mode the canvas is in. */
            isRunMode={hasRun}
            isPreviewOnly={isPreviewOnly}
            canEdit={canEditWorkflow}
          />
        </div>
      )}
    </div>
  );

  // ── Content rendering ──
  const renderContent = () => (
    <>
      {activeTabId === LOGS_TAB_ID && logsTarget ? (
        <WorkflowLogsPanelContent
          key={`${logsTarget.workflowId}:${logsTarget.runId}:${logsTarget.initialStepAlias ?? 'all-steps'}`}
          workflowId={logsTarget.workflowId}
          runId={logsTarget.runId}
          initialStepAlias={logsTarget.initialStepAlias}
          onBack={() => selectPanelTab(RUN_TAB_ID)}
        />
      ) : activeTabId === CHAT_TAB_ID ? (
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
          runSurfaceId={runSurfaceId}
          onAction={handleApplicationAction}
          targetInterfaceId={targetInterfaceId}
          onTargetConsumed={clearCarouselTarget}
          /* A marketplace preview must not be able to fire the publisher's
             workflow: the carousel hides Launch/Continue under this flag. */
          previewMode={isPreviewOnly}
          /* Where the application IS what the user opened, the newest fire is
             what they came for; a workflow panel keeps the cumulative view. */
          openOnLatestEpoch={applicationFirst}
          templateSource={applicationTemplateSource}
        />
      ) : activeTabId === RUN_TAB_ID ? (
        <RunPanelContent
          workflowId={workflowId}
          allowHistory={allowRunHistory}
          viewRequest={runViewRequest ?? undefined}
          surfaceId={runSurfaceId}
          /* Only when there IS a canvas sub-tab to go back to: without a slot the
             Workflow tab does not exist and the button would lead nowhere. */
          onBackToWorkflow={hasWorkflowSlot ? focusWorkflowTab : undefined}
          onOpenLogs={currentRunId ? openRunLogs : undefined}
        />
      ) : /* Both event listeners refuse the palette themselves; the pending-tab handoff
             above does not, and it lands straight in `activeTabId` - the availability
             effect only takes it back on the NEXT commit, so without this the palette
             would be mounted for that one commit. No producer records a pending palette
             for a locked panel today (the only one is the workflow page, whose own panel
             is always editable), so this guards a state only a future caller can reach.
             It is one condition, and it is pinned. */
        activeTabId === NODE_CREATOR_TAB_ID && showNodeCreatorTab ? (
        <NodeCreatorPanelContent workflowId={workflowId} />
      ) : activeTabId === INSPECTOR_TAB_ID ? (
        null /* Inspector portalled into its slot below (always mounted) */
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
            {/* Inspector slot - always mounted, display toggled. The canvas
                portals the real inspector in here. */}
            {showInspectorTab && (
              <div
                ref={registerInspectorHost}
                data-testid="inspector-dock-slot"
                className="flex-1 min-h-0 min-w-0 flex flex-col"
                style={{ display: activeTabId === INSPECTOR_TAB_ID ? undefined : 'none' }}
              />
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
            {/* Inspector slot - always mounted, display toggled. The canvas
                portals the real inspector in here. */}
            {showInspectorTab && (
              <div
                ref={registerInspectorHost}
                data-testid="inspector-dock-slot"
                className="flex-1 min-h-0 min-w-0 flex flex-col"
                style={{ display: activeTabId === INSPECTOR_TAB_ID ? undefined : 'none' }}
              />
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
  /** Outer side-panel tab hosting this workflow hierarchy. */
  hostTabId?: string;
  /** Explicit preview-only flag - required because SidePanel renders outside WorkflowModeProvider */
  isPreviewOnly?: boolean;
  /** Workflow canvas ReactNode - rendered as an always-mounted sub-tab (replaces Application carousel tab) */
  workflowCanvasSlot?: React.ReactNode;
  /**
   * Let the Run tab walk back up to the run history of this workflow.
   *
   * Defaults to "only the standalone workflow page", because a canvas embedded in
   * a host that CHOSE the run for it (the application panel, the marketplace
   * preview) must stay on that run. A host that can rebind its canvas - the
   * sub-workflow tab does, through the run-panel bind event - opts back in here.
   */
  allowRunHistory?: boolean;
  /**
   * Identifies the surface hosting this panel, so a run picked in its history
   * binds THIS surface's canvas. Omitted on the workflow page (which owns the
   * route); a side-panel workflow tab passes its own id.
   */
  runSurfaceId?: string;
  /**
   * This panel was opened ON an application: the Application sub-tab is its
   * default view and is never hidden by default, while the canvas becomes the
   * secondary "what is it doing" tab. Without it an application tab in the side
   * panel opens on the canvas, and in a preview context the Application sub-tab
   * would not even be offered.
   */
  applicationFirst?: boolean;
  /**
   * Interfaces the host has ALREADY resolved, used until the canvas emits its
   * own. The application panel resolves them to open its tab at all, so handing
   * them over avoids a second of canvas before the interface appears.
   */
  initialApplicationConfigs?: ApplicationConfig[];
  /**
   * Publication this application was installed from - enables the interface
   * toolbar's template actions (load the example inputs, reset the data).
   * Forwarded verbatim to the carousel; omit it where those do not apply.
   */
  applicationTemplateSource?: ApplicationTemplateSource;
  /**
   * The caller may change this workflow. False wherever a save would be refused:
   * an INSTALLED application (its plan is a frozen clone the backend will not
   * write), a publication the caller does not own (which resolves to the
   * PUBLISHER's workflow), and a marketplace preview. It withholds the Share /
   * Save / Run bar and the node palette, while leaving the canvas readable and
   * the application interactive. It does NOT gate the AI Chat sub-tab: the
   * builder agent refuses its own plan-mutating actions on a frozen application,
   * and the tab is still the place to ask about the workflow you are reading.
   */
  canEditWorkflow?: boolean;
}

export function WorkflowPanelContent({ workflowId, runId, hostTabId, isPreviewOnly: isPreviewOnlyProp, workflowCanvasSlot, allowRunHistory, runSurfaceId, applicationFirst, initialApplicationConfigs, applicationTemplateSource, canEditWorkflow }: WorkflowPanelContentProps) {
  // Try parent context first, then fall back to explicit prop.
  // SidePanel lives in AppLayout (outside WorkflowModeProvider), so the prop is needed for marketplace preview.
  const { isPreviewOnly: isPreviewFromContext, workflowId: parentWorkflowId } = useWorkflowMode();
  const isPreview = isPreviewOnlyProp ?? isPreviewFromContext;

  // If we're already inside a WorkflowModeProvider (workflow page layout sets workflowId),
  // reuse it so viewingEpoch and other state are shared with the canvas/RunInfo.
  // Only create a new provider when rendered outside (e.g. SidePanel in AppLayout).
  if (parentWorkflowId) {
    return (
      <WorkflowPanelHostProvider workflowId={workflowId} hostTabId={hostTabId} runSurfaceId={runSurfaceId}>
        <WorkflowPanelInner workflowId={workflowId} runId={runId} workflowCanvasSlot={workflowCanvasSlot} isPreviewOnly={isPreview} allowRunHistory={allowRunHistory} runSurfaceId={runSurfaceId} hostTabId={hostTabId} applicationFirst={applicationFirst} initialApplicationConfigs={initialApplicationConfigs} applicationTemplateSource={applicationTemplateSource} canEditWorkflow={canEditWorkflow} />
      </WorkflowPanelHostProvider>
    );
  }

  return (
    <WorkflowPanelHostProvider workflowId={workflowId} hostTabId={hostTabId} runSurfaceId={runSurfaceId}>
      <WorkflowModeProvider workflowId={workflowId} initialRunId={runId} readOnly={isPreview}>
        <WorkflowPanelInner workflowId={workflowId} runId={runId} workflowCanvasSlot={workflowCanvasSlot} isPreviewOnly={isPreview} allowRunHistory={allowRunHistory} runSurfaceId={runSurfaceId} hostTabId={hostTabId} applicationFirst={applicationFirst} initialApplicationConfigs={initialApplicationConfigs} applicationTemplateSource={applicationTemplateSource} canEditWorkflow={canEditWorkflow} />
      </WorkflowModeProvider>
    </WorkflowPanelHostProvider>
  );
}
