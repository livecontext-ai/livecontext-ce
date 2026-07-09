"use client";

import React, { useMemo, useState, useEffect } from "react";
import { SelectedModel, AIModel } from "@/hooks/useModels";
import { PanelLeft, PanelRight, PanelBottom, ChevronLeft, ChevronRight, Home, Sparkles, Minimize2, Play, FileText, Pencil, Globe, ArrowLeft, Download, List } from "lucide-react";
import { useSidePanelLayoutSafe } from "@/contexts/SidePanelLayoutContext";
import { useConversationActivity } from "@/contexts/ConversationActivityContext";
import LoadingSpinner from "@/components/LoadingSpinner";
import { AvatarDisplay } from "@/components/agents/AvatarPicker";
import { Button } from "@/components/ui/button";
import { Breadcrumb } from "@/components/ui/breadcrumb";
import { useRouter, usePathname } from "next/navigation";
import { isWorkflowMessage } from "@/components/chat/workflowUtils";
import { isDataSourceMessage } from "@/components/chat/DataSourceMessage";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useWorkflowMode } from "@/contexts/WorkflowModeContext";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "@/components/ui/dialog";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { StepDataTable } from "@/app/workflows/builder/components/inspector/StepDataTable";
import { WorkflowRunResultModalContent } from "@/components/WorkflowRunResultModalContent";
import { orchestratorApi } from "@/lib/api";
import { useTranslations } from "next-intl";
import { PublishWorkflowModal } from "@/components/workflow/ShareWorkflowModal";
import { WorkflowSaveWithVersions } from "@/components/workflow/WorkflowVersionHistory";
import { MarketplaceHeaderActions } from "@/components/marketplace/MarketplaceHeaderActions";
import { NotificationBell } from "@/components/chat/NotificationBell";
import { ApplicationActivationButton } from "@/components/applications/ApplicationActivationButton";
import { useCanMutateInCurrentOrg } from "@/lib/stores/current-org-store";

/**
 * Header-side model row. Inherits the full {@link AIModel} payload (capability
 * flags, context window, rate limits, deprecation, …). The header-only overlay
 * carries pre-resolved icon + display strings to avoid re-running the lookup on
 * every render. Retained for the (now unused) availableModels prop shape so
 * existing callers keep type-checking.
 */
interface Model extends AIModel {
  providerSlug: string;
  iconSlug: string;
  description: string;
}

interface StreamStatus {
  conversationId: string;
  hasActiveStream: boolean;
  streamId: string | null;
  timestamp: string;
}

interface ChatHeaderProps {
  selectedModel: SelectedModel;
  onModelChange: (model: SelectedModel) => void;
  /** Per-conversation reasoning effort (CLI/bridge models). "" = inherit. */
  reasoningEffort?: string;
  onReasoningEffortChange?: (effort: string) => void;
  availableModels: Model[];
  showModelSelector: boolean;
  onShowModelSelector: (show: boolean) => void;
  sidebarOpen: boolean;
  onSidebarToggle: () => void;
  // Stream status props
  isStreaming?: boolean;
  isStreamConnected?: boolean;
  isStreamReconnecting?: boolean;
  streamStatus?: StreamStatus | null;
  onStopStream?: () => void;
  onReconnectStream?: () => void;
  // Messages panel props (only visible in workflow mode)
  showMessagesPanel?: boolean;
  onToggleMessagesPanel?: () => void;
  isWorkflowExpanded?: boolean;
  expandedWorkflowId?: string | null;
  messages?: any[];
  onMinimizeWorkflow?: () => void;
  conversationTitle?: string | null;
  conversationId?: string | null;
  // Dashboard props
  isDashboard?: boolean;
  /** Suppress the model selector entirely (no model name, no dropdown) - e.g. on the Messages
   *  route, where the header shows the DM peer (via agentAvatarUrl) or nothing, never a model. */
  hideModelSelector?: boolean;
  /** A DM peer (→ agentAvatarUrl/agentName) is still resolving: render a skeleton in the
   *  agent-avatar slot instead of flashing nothing → avatar (icon-swap blink). */
  agentSlotLoading?: boolean;
  /** Render the agent-avatar slot as plain text (no click). Used for DM peers: the
   *  click-to-open-right-panel affordance belongs to agent conversations (agent config
   *  panel) and is meaningless for a human peer. */
  agentSlotNonInteractive?: boolean;
  dashboardBreadcrumbItems?: Array<{ label: string; onClick?: () => void; icon?: React.ComponentType<{ className?: string }>; isLoading?: boolean; truncate?: boolean; editable?: boolean; onEditComplete?: (newValue: string) => void }>;
  showProfileView?: boolean;
  // Data view props
  isDataView?: boolean;
  expandedDataSourceId?: string | number | null;
  onMinimizeDataSource?: () => void;
  // Minimize button props
  showMinimizeButton?: boolean;
  onMinimize?: () => void;
  // Workflow page props (for opening workflow in chat from workflow page)
  isWorkflowPage?: boolean;
  workflowId?: string | null;
  runId?: string | null;
  onOpenWorkflowInChat?: () => void;
  // Workflow mode props
  isRunMode?: boolean;
  onViewModeChange?: (mode: 'configuration' | 'result') => void;
  viewMode?: 'configuration' | 'result';
  // Interface page props
  isInterfacePage?: boolean;
  interfaceId?: string | null;
  onEditInterface?: () => void;
  // Application page props (simplified header: only Logs + Messages toggle)
  isApplicationPage?: boolean;
  // Marketplace preview props
  isMarketplacePreview?: boolean;
  previewPublicationId?: string | null;
  // Agent marketplace preview props
  isAgentMarketplacePreview?: boolean;
  agentPreviewPublicationId?: string | null;
  // Agent fleet props
  isAgentFleet?: boolean;
  // Agent config panel props
  agentId?: string | null;
  agentName?: string | null;
  agentAvatarUrl?: string | null;
  agentModelName?: string | null;
  agentModelProvider?: string | null;
  showAgentConfigPanel?: boolean;
  onToggleAgentConfigPanel?: () => void;
  /** Show the Conversation Activity toggle (left of the bell) - true only in a conversation. */
  showActivityToggle?: boolean;
  // Files focused-viewer props - back/prev/next/download live in the header
  // (next to the bell) so the media itself renders full-bleed with no chrome.
  // `isFilesDetail` shows the Back button (file open OR inside a folder, V313);
  // `isFileOpen` gates the prev/next/download sub-controls to an actually-open
  // file (so they don't appear when merely browsing inside a folder).
  isFilesDetail?: boolean;
  isFileOpen?: boolean;
  onFilesBack?: () => void;
  onFilesPrev?: () => void;
  onFilesNext?: () => void;
  canFilesPrev?: boolean;
  canFilesNext?: boolean;
  onFilesDownload?: () => void;
  filesDownloading?: boolean;
}

export const ChatHeader: React.FC<ChatHeaderProps> = ({
  // Model-selector + agent-avatar props (selectedModel, onModelChange,
  // availableModels, showModelSelector, onShowModelSelector, reasoningEffort,
  // onReasoningEffortChange, hideModelSelector) are no longer rendered here -
  // that UI moved into the message composer. The props stay on the interface so
  // existing callers (AppHeader, tests) keep compiling; they are simply not read.
  sidebarOpen,
  onSidebarToggle,
  // Stream status props
  isStreaming = false,
  isStreamConnected = false,
  isStreamReconnecting = false,
  streamStatus = null,
  onStopStream,
  onReconnectStream,
  // Messages panel props
  showMessagesPanel = false,
  onToggleMessagesPanel,
  isWorkflowExpanded = false,
  expandedWorkflowId = null,
  messages = [],
  onMinimizeWorkflow,
  conversationTitle = null,
  conversationId = null,
  // Dashboard props
  isDashboard = false,
  agentSlotLoading = false,
  agentSlotNonInteractive = false,
  showProfileView = false,
  dashboardBreadcrumbItems = [],
  // Data view props
  isDataView = false,
  expandedDataSourceId = null,
  onMinimizeDataSource,
  // Minimize button props
  showMinimizeButton = false,
  onMinimize,
  // Workflow page props
  isWorkflowPage = false,
  workflowId = null,
  runId = null,
  onOpenWorkflowInChat,
  // Workflow mode props
  isRunMode: isRunModeProp = false,
  onViewModeChange,
  viewMode: viewModeProp,
  // Interface page props
  isInterfacePage = false,
  interfaceId = null,
  onEditInterface,
  // Application page props
  isApplicationPage = false,
  // Marketplace preview props
  isMarketplacePreview = false,
  previewPublicationId = null,
  // Agent marketplace preview props
  isAgentMarketplacePreview = false,
  isAgentFleet = false,
  // Agent config panel props
  agentId = null,
  agentName = null,
  agentAvatarUrl = null,
  agentModelName = null,
  agentModelProvider = null,
  showAgentConfigPanel = false,
  onToggleAgentConfigPanel,
  showActivityToggle = false,
  // Files focused-viewer props
  isFilesDetail = false,
  isFileOpen = false,
  onFilesBack,
  onFilesPrev,
  onFilesNext,
  canFilesPrev = false,
  canFilesNext = false,
  onFilesDownload,
  filesDownloading = false,
}) => {
  // Dock position preference - the toggle icon mirrors where the panel opens
  // (right edge -> PanelRight, bottom edge -> PanelBottom).
  const { position: sidePanelPosition } = useSidePanelLayoutSafe();
  const SidePanelToggleIcon = sidePanelPosition === 'bottom' ? PanelBottom : PanelRight;
  const t = useTranslations();
  const { isOpen: isActivityOpen, toggle: toggleActivity } = useConversationActivity();

  const [isResultsModalOpen, setIsResultsModalOpen] = useState(false);
  const [selectedStepAlias, setSelectedStepAlias] = useState<string | null>(null);
  const [stepBreadcrumbItems, setStepBreadcrumbItems] = useState<Array<{ label: string; onClick?: () => void; icon?: React.ComponentType<{ className?: string }> }>>([]);

  // Share modal state
  const [isShareModalOpen, setIsShareModalOpen] = useState(false);
  const [workflowInterfaces, setWorkflowInterfaces] = useState<Array<{ id: string; name: string }>>([]);
  const [workflowDescriptionFromApi, setWorkflowDescriptionFromApi] = useState<string>('');

  // Get user for tenantId
  const { user, isAuthenticated, isLoading: authLoading } = useAuthGuard();
  const tenantId = user?.sub || user?.email || 'demo';

  // Get pathname early to determine run mode
  const pathname = usePathname();

  // Audit 2026-07-02 - VIEWER role in an org workspace is read-only: the Run
  // button is hidden because POST /v2/workflows/dag/execute auto-saves the plan
  // and the backend rejects VIEWER with 403 "Workflow access is read-only".
  const canMutate = useCanMutateInCurrentOrg();

  // Determine run mode from pathname (more reliable than prop)
  const isRunModeFromPath = pathname?.includes('/run/') || false;
  // Use WorkflowModeContext if available, otherwise use pathname or prop
  const { isRunMode: isRunModeFromContext } = useWorkflowMode();
  const isRunMode = isRunModeProp || isRunModeFromContext || isRunModeFromPath;

  // View mode state for run mode (configuration/result)
  const [localViewMode, setLocalViewMode] = useState<'configuration' | 'result'>('result');
  const viewMode = viewModeProp ?? localViewMode;



  // Workflow save status for button feedback (idle, saving, saved, error)
  const [workflowSaveStatus, setWorkflowSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');

  // Track whether the workflow has unsaved changes (from useDirtyState via CustomEvent)
  const [isWorkflowDirty, setIsWorkflowDirty] = useState(false);

  useEffect(() => {
    if (!isWorkflowPage) return;

    const handler = (e: CustomEvent) => {
      setIsWorkflowDirty(e.detail.isDirty);
    };
    window.addEventListener('workflowDirtyChange', handler as EventListener);
    return () => window.removeEventListener('workflowDirtyChange', handler as EventListener);
  }, [isWorkflowPage]);

  // Track whether the agent is currently streaming (disable Save during streaming)
  const [isAgentStreaming, setIsAgentStreaming] = useState(false);

  useEffect(() => {
    if (!isWorkflowPage) return;
    const handler = (event: CustomEvent<{ isStreaming: boolean }>) => {
      setIsAgentStreaming(event.detail.isStreaming);
    };
    window.addEventListener('workflowStreamingStateChange', handler as EventListener);
    return () => window.removeEventListener('workflowStreamingStateChange', handler as EventListener);
  }, [isWorkflowPage]);

  // Step-by-step mode state (allows saving even in run mode)
  const [isStepByStepMode, setIsStepByStepMode] = useState(false);

  // Listen for save completion events from BuilderCanvas
  useEffect(() => {
    if (!isWorkflowPage) return;

    const handleSaveComplete = (event: CustomEvent) => {
      const { success } = event.detail;
      setWorkflowSaveStatus(success ? 'saved' : 'error');
      if (success) {
        setIsWorkflowDirty(false);
      }
      // Reset to idle after 2 seconds
      setTimeout(() => setWorkflowSaveStatus('idle'), 2000);
    };

    window.addEventListener('workflowViewSaveComplete', handleSaveComplete as EventListener);
    return () => {
      window.removeEventListener('workflowViewSaveComplete', handleSaveComplete as EventListener);
    };
  }, [isWorkflowPage]);

  // Listen for step-by-step mode changes from workflow builder
  useEffect(() => {
    if (!isWorkflowPage) return;

    const handleStepByStepModeChange = (event: CustomEvent) => {
      const { isEnabled } = event.detail;
      setIsStepByStepMode(isEnabled || false);
    };

    window.addEventListener('workflowStepByStepModeChange', handleStepByStepModeChange as EventListener);
    return () => {
      window.removeEventListener('workflowStepByStepModeChange', handleStepByStepModeChange as EventListener);
    };
  }, [isWorkflowPage]);

  // RUN BUTTON VALIDATION - COMMENTED OUT
  // Original validation logic that disabled the Run button when validation errors existed:
  // const [hasValidationErrors, setHasValidationErrors] = useState(false);
  //
  // useEffect(() => {
  //   const handleValidationStateChange = (event: CustomEvent) => {
  //     const { hasErrors } = event.detail;
  //     setHasValidationErrors(hasErrors);
  //   };
  //   window.addEventListener('workflowValidationStateChange', handleValidationStateChange as EventListener);
  //   return () => {
  //     window.removeEventListener('workflowValidationStateChange', handleValidationStateChange as EventListener);
  //   };
  // }, []);
  //
  // useEffect(() => {
  //   if (!isRunMode) {
  //     setHasValidationErrors(false);
  //   }
  // }, [isRunMode]);
  //
  // The Run button is now always clickable. Validation errors are shown in the UI
  // but do not prevent the user from attempting to run the workflow.
  const hasValidationErrors = false; // Always false - button always enabled


  // NOTE: Validation state listener and reset logic have been commented out above
  // See "RUN BUTTON VALIDATION - COMMENTED OUT" section for original code

  // Initialize viewMode to 'result' when entering run mode
  useEffect(() => {
    if (isRunMode && !viewModeProp) {
      setLocalViewMode('result');
      // Dispatch event to sync with InspectorPanel
      window.dispatchEvent(new CustomEvent('workflowViewModeChange', {
        detail: { mode: 'result' }
      }));
    }
  }, [isRunMode, viewModeProp]);

  const handleViewModeChange = (mode: 'configuration' | 'result') => {
    if (onViewModeChange) {
      onViewModeChange(mode);
    } else {
      setLocalViewMode(mode);
    }
    // Dispatch event to InspectorPanel
    window.dispatchEvent(new CustomEvent('workflowViewModeChange', {
      detail: { mode }
    }));
  };

  const router = useRouter();


  // Extract workflowId and runId from pathname if present (e.g., /app/workflow/{id}/run/{runId}, /app/workflows/{id}/run/{runId})
  // Note: /app/applications/{publicationId} uses snapshot-based routing and does not expose workflowId/runId in URL
  const workflowPathMatch = pathname?.match(/\/app\/(?:workflow|workflows)\/([^\/]+)(?:\/run\/([^\/]+))?/);
  const pathnameWorkflowId = workflowPathMatch ? workflowPathMatch[1] : null;
  const [currentRunId, setCurrentRunId] = React.useState<string | null>(
    workflowPathMatch ? workflowPathMatch[2] : null
  );

  // Application pages: workflowId/runId come from ApplicationDetailView via event (not URL)
  const [appWorkflowId, setAppWorkflowId] = React.useState<string | null>(null);
  React.useEffect(() => {
    if (!isApplicationPage) return;
    // Check if data was set before listener registered
    const cached = (window as any).__applicationWorkflow;
    if (cached?.workflowId) setAppWorkflowId(cached.workflowId);
    if (cached?.runId) setCurrentRunId(cached.runId);
    const handler = (event: CustomEvent) => {
      if (event.detail?.workflowId) setAppWorkflowId(event.detail.workflowId);
      if (event.detail?.runId) setCurrentRunId(event.detail.runId);
    };
    window.addEventListener('applicationWorkflowReady', handler as EventListener);
    return () => window.removeEventListener('applicationWorkflowReady', handler as EventListener);
  }, [isApplicationPage]);

  // Listen for workflow run started event to update runId without rerender
  React.useEffect(() => {
    const handleWorkflowRunStarted = (event: CustomEvent) => {
      const targetWorkflowId = isWorkflowPage ? workflowId : pathnameWorkflowId;
      if (event.detail?.runId && targetWorkflowId && event.detail?.workflowId === targetWorkflowId) {
        setCurrentRunId(event.detail.runId);
      }
    };

    window.addEventListener('workflowRunStarted', handleWorkflowRunStarted as EventListener);
    return () => {
      window.removeEventListener('workflowRunStarted', handleWorkflowRunStarted as EventListener);
    };
  }, [isWorkflowPage, workflowId, pathnameWorkflowId]);

  // Also update from pathname when it changes (e.g., on navigation)
  // Skip for application pages - they don't encode runId in URL
  React.useEffect(() => {
    if (isApplicationPage) return;
    const match = pathname?.match(/\/(?:app\/workflow|dashboard\/workflows)\/([^\/]+)(?:\/run\/([^\/]+))?/);
    if (match) {
      setCurrentRunId(match[2] || null);
    } else {
      setCurrentRunId(null);
    }
  }, [pathname, isApplicationPage]);

  // Effective workflowId for application pages (received via event, not URL)
  const effectiveAppWorkflowId = isApplicationPage ? (appWorkflowId || workflowId) : null;

  // Use workflowId from pathname if on workflow page, otherwise use expandedWorkflowId
  const activeWorkflowId = (isWorkflowPage && workflowId) || pathnameWorkflowId || expandedWorkflowId;

  // Fetch workflow name from API
  const [workflowNameFromApi, setWorkflowNameFromApi] = React.useState<string>('');
  const [loadingWorkflowName, setLoadingWorkflowName] = React.useState(false);

  React.useEffect(() => {
    const fetchWorkflowData = async () => {
      // Wait for auth to be ready (CLAUDE.md requirement)
      if (authLoading || !isAuthenticated) {
        return;
      }

      if (!activeWorkflowId || activeWorkflowId === 'new' || !tenantId) {
        setWorkflowNameFromApi('');
        setWorkflowDescriptionFromApi('');
        setWorkflowInterfaces([]);
        return;
      }

      try {
        setLoadingWorkflowName(true);

        // Fetch workflow details and interfaces in parallel
        const [workflow, interfaces] = await Promise.all([
          orchestratorApi.getWorkflow(activeWorkflowId),
          orchestratorApi.getInterfaces(),
        ]);

        setWorkflowNameFromApi(workflow.name || `Workflow ${activeWorkflowId}`);
        setWorkflowDescriptionFromApi(workflow.description || '');
        // Filter interfaces that belong to this workflow
        const filteredInterfaces = interfaces
          .filter(iface => iface.workflowId === activeWorkflowId)
          .map(iface => ({ id: iface.id, name: iface.name }));
        setWorkflowInterfaces(filteredInterfaces);
      } catch (err) {
        console.error('Error fetching workflow data:', err);
        setWorkflowNameFromApi(`Workflow ${activeWorkflowId}`);
        setWorkflowDescriptionFromApi('');
        setWorkflowInterfaces([]);
      } finally {
        setLoadingWorkflowName(false);
      }
    };

    fetchWorkflowData();
  }, [activeWorkflowId, tenantId, authLoading, isAuthenticated]);

  // Build breadcrumb items for workflow view (similar to dashboard format)
  const workflowBreadcrumbItems = useMemo(() => {
    // Show breadcrumb if on workflow page or if workflow is expanded
    if (!activeWorkflowId && !isWorkflowPage) return [];
    if (!activeWorkflowId) return [];

    const items: Array<{ label: string; onClick?: () => void; icon?: React.ComponentType<{ className?: string }>; truncate?: boolean }> = [
      {
        label: '',
        icon: Home,
        onClick: () => {
          router.push('/app/chat');
        },
      },
      {
        label: t('breadcrumb.workflows'),
        onClick: () => {
          // Navigate to workflow list view using native routing
          router.push('/app/workflow');
        },
      },
    ];

    // Don't show breadcrumb for 'new' workflow - it shouldn't exist
    if (activeWorkflowId === 'new') {
      return items; // Just return the base items without the workflow name
    }

    // Use workflow name from API, fallback to ID if not loaded yet
    const workflowName = workflowNameFromApi || (loadingWorkflowName ? t('common.loading') : `Workflow ${activeWorkflowId}`);

    items.push({
      label: workflowName,
      truncate: true,
      onClick: () => {
        // Always redirect to workflow configuration page (not run page)
        const configPath = `/app/workflow/${activeWorkflowId}`;
        console.log('[Breadcrumb] Workflow clicked, redirecting to config:', configPath);
        router.push(configPath);
      },
    });

    // Add run ID if present in pathname
    if (currentRunId) {
      const workflowIdForRun = activeWorkflowId; // Capture in closure
      const runIdForRun = currentRunId; // Capture in closure
      items.push({
        label: currentRunId,
        onClick: () => {
          console.log('[Breadcrumb] Run onClick called', { workflowIdForRun, runIdForRun, activeWorkflowId, currentRunId });
          if (workflowIdForRun) {
            // Redirect to workflow configuration page
            const configPath = `/app/workflow/${workflowIdForRun}`;
            console.log('[Breadcrumb] Run clicked, redirecting to config:', configPath);
            router.push(configPath);
          } else {
            console.warn('[Breadcrumb] Run onClick: missing activeWorkflowId', { workflowIdForRun, activeWorkflowId });
          }
        },
      });
      console.log('[Breadcrumb] Added run item', { currentRunId, activeWorkflowId, hasOnClick: true });
    }

    return items;
  }, [isWorkflowExpanded, expandedWorkflowId, router, currentRunId, pathname, activeWorkflowId, workflowNameFromApi, loadingWorkflowName, isWorkflowPage]);

  // Build breadcrumb items for data view (similar to dashboard format)
  const dataBreadcrumbItems = useMemo(() => {
    if (!isDataView || !expandedDataSourceId) return [];

    const items: Array<{ label: string; onClick?: () => void; icon?: React.ComponentType<{ className?: string }> }> = [
      {
        label: '',
        icon: Home,
        onClick: () => {
          router.push('/app/chat');
        },
      },
      {
        label: t('breadcrumb.data'),
        onClick: () => {
          // Navigate to data tables list using native routing
          router.push('/app/tables');
        },
      },
    ];

    // If it's a new datasource
    if (expandedDataSourceId === 'new') {
      items.push({
        label: t('breadcrumb.newDataSource'),
      });
      return items;
    }

    // Try to find the datasource message to get its name
    const datasourceMessage = messages.find((msg) => {
      if (!isDataSourceMessage(msg.content)) return false;
      try {
        const parsed = JSON.parse(msg.content);
        return String(parsed.dataSourceId) === String(expandedDataSourceId);
      } catch {
        return false;
      }
    });

    // Get datasource name from message or use ID
    let datasourceName = 'DataSource';
    if (datasourceMessage) {
      try {
        const datasourceContent = typeof datasourceMessage.content === 'string'
          ? JSON.parse(datasourceMessage.content)
          : datasourceMessage.content;
        datasourceName = datasourceContent.name || datasourceContent.title || `DataSource ${expandedDataSourceId}`;
      } catch (e) {
        datasourceName = `DataSource ${expandedDataSourceId}`;
      }
    } else {
      datasourceName = `DataSource ${expandedDataSourceId}`;
    }

    items.push({
      label: datasourceName,
    });

    return items;
  }, [isDataView, expandedDataSourceId, messages, router]);

  // Files focused-viewer header actions - back / prev-next / download.
  // Modelled on the workflow + marketplace context buttons: a ghost Back next to
  // the bell, icon-only nav, and a primary Download. Desktop shows labels; mobile
  // is icon-only to fit the narrow bar. (The file metadata shows under the media
  // permanently, so there is no info toggle here.)
  const filesDetailActions = (compact: boolean) => isFilesDetail ? (
    <>
      <Button
        variant="ghost"
        size="sm"
        onClick={onFilesBack}
        className={compact ? "h-8 px-2" : "h-8 px-3 gap-1.5"}
        title={t('common.back')}
      >
        <ArrowLeft className="w-4 h-4" />
        {!compact && <span className="hidden sm:inline">{t('common.back')}</span>}
      </Button>
      {/* Prev/next/download only make sense with a file open - when merely browsing
          inside a folder (V313) the Back button alone steps up one level. */}
      {isFileOpen && (
        <>
          <div className="flex items-center">
            <Button
              variant="ghost"
              size="icon"
              onClick={onFilesPrev}
              disabled={!canFilesPrev}
              className="w-8 h-8"
              title={t('fileDetail.previousFile')}
              aria-label={t('fileDetail.previousFile')}
            >
              <ChevronLeft className="w-4 h-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={onFilesNext}
              disabled={!canFilesNext}
              className="w-8 h-8"
              title={t('fileDetail.nextFile')}
              aria-label={t('fileDetail.nextFile')}
            >
              <ChevronRight className="w-4 h-4" />
            </Button>
          </div>
          <Button
            variant="default"
            size="sm"
            onClick={onFilesDownload}
            disabled={filesDownloading}
            aria-busy={filesDownloading}
            className={compact ? "h-8 px-2" : "h-8 px-2 lg:px-3"}
            title={t('storageExplorer.download')}
          >
            {filesDownloading
              ? <LoadingSpinner size="xs" className={compact ? "" : "lg:mr-1"} />
              : <Download className={compact ? "w-4 h-4" : "w-4 h-4 lg:mr-1"} />}
            {!compact && <span className="hidden lg:inline">{t('storageExplorer.download')}</span>}
          </Button>
        </>
      )}
    </>
  ) : null;

  return (
    <div>
      <div
        className="h-14 bg-theme-secondary flex flex-nowrap items-center justify-between gap-2 px-4 flex-shrink-0"
      >
        {/* Desktop Layout - Model and Mode on left */}
        {/* No overflow-hidden here: the model selector dropdown is absolute and would be clipped.
            The inner breadcrumb wrapper already has its own overflow-hidden for truncation. */}
        <div className="hidden md:flex items-center gap-3 flex-1 min-w-0">
          {/* Breadcrumb - Desktop - wrapped in overflow-hidden to clip long breadcrumbs */}
          {dashboardBreadcrumbItems.length > 0 ? (
            <div className="flex-1 min-w-0 overflow-hidden">
              <Breadcrumb
                items={dashboardBreadcrumbItems}
                variant="minimal"
                separator="slash"
                className="mb-0"
              />
            </div>
          ) : (isWorkflowExpanded) && workflowBreadcrumbItems.length > 0 ? (
            <div className="flex-1 min-w-0 overflow-hidden">
              <Breadcrumb
                items={workflowBreadcrumbItems}
                variant="minimal"
                separator="slash"
                className="mb-0"
              />
            </div>
          ) : isDataView && dataBreadcrumbItems.length > 0 ? (
            <div className="flex-1 min-w-0 overflow-hidden">
              <Breadcrumb
                items={dataBreadcrumbItems}
                variant="minimal"
                separator="slash"
                className="mb-0"
              />
            </div>
          ) : null}
          {/* DM peer avatar - Desktop. The model selector + clickable agent avatar
              moved into the message composer; only the non-interactive DM peer
              remains in the header. */}
          {!isWorkflowExpanded && !isDataView && !isDashboard && !showProfileView && dashboardBreadcrumbItems.length === 0 && (
            agentSlotLoading && !agentAvatarUrl ? (
              /* DM peer still resolving - stable skeleton instead of an icon swap. */
              <div className="flex items-center gap-2 px-4 py-2.5" data-testid="header-peer-skeleton">
                <div className="h-8 w-8 animate-pulse rounded-full bg-theme-tertiary" />
                <div className="h-4 w-24 animate-pulse rounded bg-theme-tertiary" />
              </div>
            ) : (agentAvatarUrl && agentSlotNonInteractive) ? (
              /* DM peer - informational only, no panel to open. */
              <div
                className="flex items-center gap-2 px-4 py-2.5"
                title={agentName || ''}
                data-testid="header-peer-static"
              >
                <AvatarDisplay avatarUrl={agentAvatarUrl} name={agentName || undefined} size="sm" />
                <span className="text-base text-theme-primary">{agentName}</span>
              </div>
            ) : null
          )}
        </div>

        {/* Right side - Minimize Button and Messages Panel Toggle Button */}
        <div className="hidden md:flex items-center gap-1.5 lg:gap-2 flex-shrink-0">
          {showMinimizeButton && onMinimize && (
            <Button
              variant="ghost"
              size="icon"
              onClick={onMinimize}
              title={t('actions.minimize')}
              className="w-8 h-8"
            >
              <Minimize2 className="w-4 h-4" />
            </Button>
          )}
          {isInterfacePage && interfaceId && onEditInterface && (
            <Button
              variant="default"
              size="sm"
              onClick={onEditInterface}
              title={t('actions.edit')}
              className="h-8 px-2 lg:px-3"
            >
              <Pencil className="w-4 h-4 lg:mr-1.5" />
              <span className="hidden lg:inline">{t('actions.edit')}</span>
            </Button>
          )}
          {isAgentFleet && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => router.push('/app/agent')}
              className="h-8 px-3 gap-1.5"
            >
              <ArrowLeft className="w-4 h-4" />
              <span className="hidden sm:inline">{t('common.back')}</span>
            </Button>
          )}
          {(isMarketplacePreview || isAgentMarketplacePreview) && (
            <>
              {/* Marketplace preview (Desktop): Back + Install/Free + Info panel */}
              <Button
                variant="ghost"
                size="sm"
                onClick={() => router.push('/app/marketplace')}
                className="h-8 px-3 gap-1.5"
              >
                <ArrowLeft className="w-4 h-4" />
                <span className="hidden sm:inline">{t('common.back')}</span>
              </Button>
              {previewPublicationId && (
                <MarketplaceHeaderActions publicationId={previewPublicationId} />
              )}
            </>
          )}
          {isApplicationPage && effectiveAppWorkflowId && (
            <>
              {/* Application mode: Activation toggle + Logs button (Desktop) */}
              <ApplicationActivationButton workflowId={effectiveAppWorkflowId} />
              <Button
                variant="default"
                size="sm"
                onClick={() => setIsResultsModalOpen(true)}
                title={t('actions.logs')}
                className="h-8 px-2 lg:px-3"
              >
                <FileText className="w-4 h-4 lg:mr-1" />
                <span className="hidden lg:inline">{t('actions.logs')}</span>
              </Button>
              {effectiveAppWorkflowId && currentRunId && (
                <Dialog open={isResultsModalOpen} onOpenChange={(open) => {
                  setIsResultsModalOpen(open);
                  if (!open) {
                    setSelectedStepAlias(null);
                    setStepBreadcrumbItems([]);
                  }
                }}>
                  <DialogContent className="w-[90vw] max-w-[1400px] h-[80vh] max-h-[800px] overflow-hidden bg-theme-primary p-0 flex flex-col rounded-3xl">
                    <DialogHeader className="px-6 pt-4 pb-3 h-auto max-h-[90px] overflow-hidden">
                      {stepBreadcrumbItems.length > 0 ? (
                        <Breadcrumb
                          items={stepBreadcrumbItems}
                          variant="minimal"
                          separator="slash"
                          className="mb-0"
                        />
                      ) : (
                        <DialogTitle className="text-base">{t('breadcrumb.workflowSteps')}</DialogTitle>
                      )}
                    </DialogHeader>
                    <div className="px-6 pb-6 flex-1 min-h-0 overflow-hidden">
                      <WorkflowRunResultModalContent
                        workflowId={effectiveAppWorkflowId}
                        runId={currentRunId}
                        onBreadcrumbChange={setStepBreadcrumbItems}
                      />
                    </div>
                  </DialogContent>
                </Dialog>
              )}
            </>
          )}
          {isWorkflowPage && workflowId && !isApplicationPage && (
            <>
              {/* PUBLISH BUTTON (Desktop version) */}
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setIsShareModalOpen(true)}
                title={t('actions.share')}
                className="h-8 px-2 lg:px-3 gap-1.5 lg:gap-2"
              >
                <Globe className="w-4 h-4" />
                <span className="hidden lg:inline">{t('actions.share')}</span>
              </Button>
              {/* SAVE BUTTON WITH VERSION HISTORY (Desktop) - save disabled in run mode, version history still accessible */}
              <WorkflowSaveWithVersions
                workflowId={workflowId}
                saveStatus={workflowSaveStatus}
                isDirty={isWorkflowDirty}
                isAgentStreaming={isAgentStreaming}
                isRunMode={isRunMode}
                onSave={() => {
                  setWorkflowSaveStatus('saving');
                  window.dispatchEvent(new CustomEvent('workflowViewSave', {
                    detail: { workflowId }
                  }));
                }}
                desktop={true}
              />
              {isRunMode ? (
                <>
                  {/* Edit button removed - use WorkflowModeToggle instead */}
                  <Button
                    variant="default"
                    size="sm"
                    onClick={() => {
                      setIsResultsModalOpen(true);
                    }}
                    title={t('actions.logs')}
                    className="h-8 px-2 lg:px-3"
                  >
                    <FileText className="w-4 h-4 lg:mr-1" />
                    <span className="hidden lg:inline">{t('actions.logs')}</span>
                  </Button>
                  {workflowId && currentRunId && (
                    <Dialog open={isResultsModalOpen} onOpenChange={(open) => {
                      setIsResultsModalOpen(open);
                      if (!open) {
                        setSelectedStepAlias(null);
                        setStepBreadcrumbItems([]);
                      }
                    }}>
                      <DialogContent className="w-[90vw] max-w-[1400px] h-[80vh] max-h-[800px] overflow-hidden bg-theme-primary p-0 flex flex-col rounded-3xl">
                        <DialogHeader className="px-6 pt-4 pb-3 h-auto max-h-[90px] overflow-hidden">
                          {stepBreadcrumbItems.length > 0 ? (
                            <Breadcrumb
                              items={stepBreadcrumbItems}
                              variant="minimal"
                              separator="slash"
                              className="mb-0"
                            />
                          ) : (
                            <DialogTitle className="text-base">{t('breadcrumb.workflowSteps')}</DialogTitle>
                          )}
                        </DialogHeader>
                        <div className="px-6 pb-6 flex-1 min-h-0 overflow-hidden">
                          <WorkflowRunResultModalContent
                            workflowId={workflowId}
                            runId={currentRunId}
                            onBreadcrumbChange={setStepBreadcrumbItems}
                          />
                        </div>
                      </DialogContent>
                    </Dialog>
                  )}
                </>
              ) : (
                <>
                  {/* RUN BUTTON (Desktop version) - hidden for org VIEWERs: the
                      execute endpoint auto-saves the plan and 403s read-only roles. */}
                  {canMutate && (
                    <Button
                      variant="default"
                      size="sm"
                      onClick={(e) => {
                        e.stopPropagation();
                        window.dispatchEvent(new CustomEvent('workflowViewStart', {
                          detail: { workflowId }
                        }));
                      }}
                      title={t('actions.run')}
                      className="h-8 px-2 lg:px-3"
                    >
                      <Play className="w-4 h-4 lg:mr-1" />
                      <span className="hidden lg:inline">{t('actions.run')}</span>
                    </Button>
                  )}
                </>
              )}
              {/* History button removed - use WorkflowRunsHistoryPanel from toggle instead */}
            </>
          )}
          {/* Files focused-viewer actions (Desktop) - Back next to the bell. */}
          {filesDetailActions(false)}
          {/* Conversation Activity toggle - left of the bell, only in a
              conversation. Focused (accent) when the activity card is shown. */}
          {showActivityToggle && (
            <Button
              onClick={toggleActivity}
              variant="ghost"
              size="icon"
              aria-pressed={isActivityOpen}
              title={t('conversationActivity.toggle')}
              className={`w-8 h-8 ${isActivityOpen
                ? 'bg-black text-white hover:bg-black dark:bg-white dark:text-black dark:hover:bg-white'
                : 'text-black dark:text-white'}`}
            >
              <List className="w-4 h-4" />
            </Button>
          )}
          {/* Notification bell - Inbox + Activity tabs. Self-hides when
              both tabs are empty. Placed adjacent to the right-panel
              toggle so the chat-home page surfaces unread state without
              relying on the message composer. */}
          <NotificationBell />
          {/* Side panel toggle - hidden when panel is open (user closes via X in panel) */}
          {onToggleAgentConfigPanel && !showAgentConfigPanel && (
            <Button
              onClick={onToggleAgentConfigPanel}
              variant="ghost"
              size="icon"
              title={t('sidePanel.addTab')}
              className="w-8 h-8 text-black dark:text-white"
            >
              <SidePanelToggleIcon className="w-4 h-4" />
            </Button>
          )}
        </div>

        {/* Mobile Layout - Hamburger and Model Selector on left */}
        {/* No overflow-hidden here: same reason as desktop - would clip the model dropdown. */}
        <div className="md:hidden flex items-center gap-3 flex-1 min-w-0">
          <button
            onClick={onSidebarToggle}
            className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-theme-tertiary transition-colors duration-300 cursor-pointer"
          >
            <PanelLeft className="w-4 h-4 text-theme-primary" />
          </button>

          {/* Breadcrumb - Mobile - wrapped in overflow-hidden to clip long breadcrumbs
              and prevent overlap with right-side action buttons (matches desktop layout). */}
          {dashboardBreadcrumbItems.length > 0 ? (
            <div className="flex-1 min-w-0 overflow-hidden">
              <Breadcrumb
                items={dashboardBreadcrumbItems}
                variant="minimal"
                separator="slash"
                className="mb-0"
              />
            </div>
          ) : isWorkflowExpanded && workflowBreadcrumbItems.length > 0 ? (
            <div className="flex-1 min-w-0 overflow-hidden">
              <Breadcrumb
                items={workflowBreadcrumbItems}
                variant="minimal"
                separator="slash"
                className="mb-0"
              />
            </div>
          ) : isDataView && dataBreadcrumbItems.length > 0 ? (
            <div className="flex-1 min-w-0 overflow-hidden">
              <Breadcrumb
                items={dataBreadcrumbItems}
                variant="minimal"
                separator="slash"
                className="mb-0"
              />
            </div>
          ) : null}
          {/* DM peer avatar - Mobile. The model selector + clickable agent avatar
              moved into the message composer; only the non-interactive DM peer
              remains in the header. */}
          {!isWorkflowExpanded && !isDataView && !isDashboard && !showProfileView && dashboardBreadcrumbItems.length === 0 && (
            agentSlotLoading && !agentAvatarUrl ? (
              /* DM peer still resolving - stable skeleton instead of an icon swap. */
              <div className="flex items-center gap-2 px-4 py-2.5" data-testid="header-peer-skeleton-mobile">
                <div className="h-8 w-8 animate-pulse rounded-full bg-theme-tertiary" />
                <div className="h-4 w-24 animate-pulse rounded bg-theme-tertiary" />
              </div>
            ) : (agentAvatarUrl && agentSlotNonInteractive) ? (
              /* DM peer - informational only, no panel to open. */
              <div
                className="flex items-center gap-2 px-4 py-2.5"
                title={agentName || ''}
                data-testid="header-peer-static-mobile"
              >
                <AvatarDisplay avatarUrl={agentAvatarUrl} name={agentName || undefined} size="sm" />
                <span className="text-base text-theme-primary">{agentName}</span>
              </div>
            ) : null
          )}
        </div>

        {/* Mobile Layout - Messages Panel Toggle Button on right */}
        <div className="md:hidden flex items-center gap-1.5 flex-shrink-0">
          {isInterfacePage && interfaceId && onEditInterface && (
            <Button
              variant="default"
              size="sm"
              onClick={onEditInterface}
              title={t('actions.edit')}
              className="h-8 px-2 lg:px-3"
            >
              <Pencil className="w-4 h-4 lg:mr-1.5" />
              <span className="hidden lg:inline">{t('actions.edit')}</span>
            </Button>
          )}
          {isAgentFleet && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => router.push('/app/agent')}
              className="h-8 px-2"
              title={t('common.back')}
            >
              <ArrowLeft className="w-4 h-4" />
            </Button>
          )}
          {(isMarketplacePreview || isAgentMarketplacePreview) && (
            <>
              {/* Marketplace preview (Mobile): Back + Install/Free + Info panel */}
              <Button
                variant="ghost"
                size="sm"
                onClick={() => router.push('/app/marketplace')}
                className="h-8 px-2"
                title={t('common.back')}
              >
                <ArrowLeft className="w-4 h-4" />
              </Button>
              {previewPublicationId && (
                <MarketplaceHeaderActions publicationId={previewPublicationId} compact />
              )}
            </>
          )}
          {isApplicationPage && effectiveAppWorkflowId && (
            <>
              {/* Application mode: Activation toggle + Logs button (Mobile) */}
              <ApplicationActivationButton workflowId={effectiveAppWorkflowId} />
              <Button
                variant="default"
                size="sm"
                onClick={() => setIsResultsModalOpen(true)}
                title={t('actions.logs')}
                className="h-8 px-2"
              >
                <FileText className="w-4 h-4" />
              </Button>
              {effectiveAppWorkflowId && currentRunId && (
                <Dialog open={isResultsModalOpen} onOpenChange={(open) => {
                  setIsResultsModalOpen(open);
                  if (!open) {
                    setSelectedStepAlias(null);
                    setStepBreadcrumbItems([]);
                  }
                }}>
                  <DialogContent className="w-[90vw] max-w-[1400px] h-[80vh] max-h-[800px] overflow-hidden bg-theme-primary p-0 flex flex-col rounded-3xl">
                    <DialogHeader className="px-6 pt-4 pb-3 h-auto max-h-[90px] overflow-hidden">
                      {stepBreadcrumbItems.length > 0 ? (
                        <Breadcrumb
                          items={stepBreadcrumbItems}
                          variant="minimal"
                          separator="slash"
                          className="mb-0"
                        />
                      ) : (
                        <DialogTitle className="text-base">{t('breadcrumb.workflowSteps')}</DialogTitle>
                      )}
                    </DialogHeader>
                    <div className="px-6 pb-6 flex-1 min-h-0 overflow-hidden">
                      <WorkflowRunResultModalContent
                        workflowId={effectiveAppWorkflowId}
                        runId={currentRunId}
                        onBreadcrumbChange={setStepBreadcrumbItems}
                      />
                    </div>
                  </DialogContent>
                </Dialog>
              )}
            </>
          )}
          {isWorkflowPage && workflowId && !isApplicationPage && (
            <>
              {/* PUBLISH BUTTON (Mobile version) */}
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setIsShareModalOpen(true)}
                title={t('actions.share')}
                className="h-8 px-2"
              >
                <Globe className="w-4 h-4" />
              </Button>
              {/* SAVE BUTTON WITH VERSION HISTORY (Mobile) - save disabled in run mode, version history still accessible */}
              <WorkflowSaveWithVersions
                workflowId={workflowId}
                saveStatus={workflowSaveStatus}
                isDirty={isWorkflowDirty}
                isAgentStreaming={isAgentStreaming}
                isRunMode={isRunMode}
                onSave={() => {
                  setWorkflowSaveStatus('saving');
                  window.dispatchEvent(new CustomEvent('workflowViewSave', {
                    detail: { workflowId }
                  }));
                }}
                desktop={false}
              />
              {isRunMode ? (
                <>
                  {/* Edit button removed (mobile) - use WorkflowModeToggle instead */}
                  <Button
                    variant="default"
                    size="sm"
                    onClick={() => {
                      setIsResultsModalOpen(true);
                    }}
                    title={t('actions.logs')}
                    className="h-8 px-2"
                  >
                    <FileText className="w-4 h-4" />
                  </Button>
                  {workflowId && currentRunId && (
                    <Dialog open={isResultsModalOpen} onOpenChange={(open) => {
                      setIsResultsModalOpen(open);
                      if (!open) {
                        setSelectedStepAlias(null);
                        setStepBreadcrumbItems([]);
                      }
                    }}>
                      <DialogContent className="w-[90vw] max-w-[1400px] h-[80vh] max-h-[800px] overflow-hidden bg-theme-primary p-0 flex flex-col rounded-3xl">
                        <DialogHeader className="px-6 pt-4 pb-3 h-auto max-h-[90px] overflow-hidden">
                          {stepBreadcrumbItems.length > 0 ? (
                            <Breadcrumb
                              items={stepBreadcrumbItems}
                              variant="minimal"
                              separator="slash"
                              className="mb-0"
                            />
                          ) : (
                            <DialogTitle className="text-base">{t('breadcrumb.workflowSteps')}</DialogTitle>
                          )}
                        </DialogHeader>
                        <div className="px-6 pb-6 flex-1 min-h-0 overflow-hidden">
                          <WorkflowRunResultModalContent
                            workflowId={workflowId}
                            runId={currentRunId}
                            onBreadcrumbChange={setStepBreadcrumbItems}
                          />
                        </div>
                      </DialogContent>
                    </Dialog>
                  )}
                </>
              ) : (
                <>
                  {/* RUN BUTTON (Mobile version) - same VIEWER gate as desktop. */}
                  {canMutate && (
                    <Button
                      variant="default"
                      size="sm"
                      onClick={(e) => {
                        e.stopPropagation();
                        window.dispatchEvent(new CustomEvent('workflowViewStart', {
                          detail: { workflowId }
                        }));
                      }}
                      title={t('actions.run')}
                      className="h-8 px-2"
                    >
                      <Play className="w-4 h-4" />
                    </Button>
                  )}
                </>
              )}
              {/* History button removed - use WorkflowRunsHistoryPanel from toggle instead */}
            </>
          )}
          {/* Files focused-viewer actions (Mobile) - icon-only. */}
          {filesDetailActions(true)}
          {/* Conversation Activity toggle (mobile) - left of the bell, only in a
              conversation. Must mirror the desktop header or it vanishes on mobile. */}
          {showActivityToggle && (
            <Button
              onClick={toggleActivity}
              variant="ghost"
              size="icon"
              aria-pressed={isActivityOpen}
              title={t('conversationActivity.toggle')}
              className={`w-8 h-8 ${isActivityOpen
                ? 'bg-black text-white hover:bg-black dark:bg-white dark:text-black dark:hover:bg-white'
                : 'text-black dark:text-white'}`}
            >
              <List className="w-4 h-4" />
            </Button>
          )}
          {/* Notification bell (mobile) - same placement as desktop. */}
          <NotificationBell />
          {/* Side panel toggle (mobile) - hidden when panel is open */}
          {onToggleAgentConfigPanel && !showAgentConfigPanel && (
            <Button
              onClick={onToggleAgentConfigPanel}
              variant="ghost"
              size="icon"
              title={t('sidePanel.addTab')}
              className="w-8 h-8 text-black dark:text-white"
            >
              <SidePanelToggleIcon className="w-4 h-4" />
            </Button>
          )}
        </div>
      </div>

      {/* Share Workflow Modal */}
      {isWorkflowPage && workflowId && (
        <PublishWorkflowModal
          isOpen={isShareModalOpen}
          onClose={() => setIsShareModalOpen(false)}
          workflowId={workflowId}
          workflowName={workflowNameFromApi}
          workflowDescription={workflowDescriptionFromApi}
        />
      )}

      {/* Install modal + Info panel moved to the preview page overlay - kept out of the header */}
    </div>
  );
};
