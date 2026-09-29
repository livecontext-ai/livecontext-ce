'use client';

import * as React from 'react';
import { Bot, Settings, MessageSquare, Table, FolderOpen, Workflow } from 'lucide-react';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import dynamic from 'next/dynamic';
import { AGENT_CONVERSATION_TAB, AGENT_CONFIGURATION_TAB, type AgentPanelTab } from '@/components/app/agentPanelTabs';
import { openFilesPanel, type FilePanelTarget } from '@/lib/sidePanel/openFilesPanel';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { TriggerButtonVariant } from '../components/NodePlayButton';
import { openWorkflowBuilderTab, requestOpenRelatedWorkflow } from '@/lib/sidePanel/openWorkflowBuilderTab';
import { preloadNodePanels } from '@/lib/sidePanel/preloadNodePanels';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

// The panels these buttons open are loaded apart from the node: a static import put the
// agent fleet canvas, the conversation UI and the data table into every canvas that
// shows a node, the landing hero included, for buttons that only exist on hover. Where
// a side panel exists, the hook below preloads them so the first open is not blank.
const AgentPanelContent = dynamic(
  () => import('@/components/app/AgentPanelContent').then((m) => m.AgentPanelContent),
  { ssr: false },
);
const DataSourcePanelContent = dynamic(
  () => import('@/components/app/DataSourcePanelContent').then((m) => m.DataSourcePanelContent),
  { ssr: false },
);

/**
 * Centralized derivation of the node-type flags that drive the contextual
 * bottom-bar buttons (agent / table / sub-workflow / files) and the
 * trigger pin + play affordances. Pure - depends only on the node's data and
 * its resolved node-class id, so it can be reused both inside the canvas
 * (FlowNode, in-provider) and outside it (the run-info step popover, which is
 * a sibling of the StepByStepProvider).
 *
 * The logic mirrors the long-standing inline derivation in FlowNode; both
 * consume this single source of truth.
 */
export interface NodeContextFlags {
  isAiAgentNode: boolean;
  isSubWorkflowNode: boolean;
  isWorkflowsTriggerNode: boolean;
  isTableNode: boolean;
  isInterfaceNode: boolean;
  isStaticFileProducingNode: boolean;
  isTriggerNode: boolean;
  isManualTrigger: boolean;
  isChatTrigger: boolean;
  isFormTrigger: boolean;
  isWebhookTrigger: boolean;
  isScheduleTrigger: boolean;
  isTablesTrigger: boolean;
  isErrorTrigger: boolean;
  /** Trigger play-button icon variant ('play' for non-triggers). */
  triggerVariant: TriggerButtonVariant;
  referencedWorkflowId?: string;
  referencedWorkflowName: string;
}

// `isFireableTrigger` moved to ./fireableTrigger so a consumer can read the
// predicate without importing this module's dependency chain (see that file).

export function deriveNodeContextFlags(data: BuilderNodeData, nodeClassId?: string | null): NodeContextFlags {
  const nodeId = data.id || '';
  const canonicalNodeId = nodeClassId || nodeId;
  const nodeKind = data.kind;

  const isAiAgentNode =
    nodeId === 'ai-agent' ||
    nodeId === 'agent' ||
    nodeId.startsWith('ai-agent-') ||
    nodeId.startsWith('agent-') ||
    (data.label?.toLowerCase().includes('agent') ?? false);
  const isSubWorkflowNode = nodeKind === 'sub_workflow' || canonicalNodeId === 'sub_workflow';
  const isWorkflowsTriggerNode =
    nodeId === 'workflows-trigger' ||
    nodeId.startsWith('workflows-trigger-') ||
    (nodeKind === 'entry' && !!(data as any)?.workflowData?.workflowId);
  const referencedWorkflowId: string | undefined =
    (data as any)?.subWorkflowId || (data as any)?.workflowData?.workflowId || undefined;
  const referencedWorkflowName: string =
    (data as any)?.workflowData?.workflowName || data.label || 'Workflow';
  const hasDataSourceData = (data as any)?.dataSourceData !== undefined;

  const isWebhookTrigger = nodeId === 'webhook-trigger' || nodeId.startsWith('webhook-trigger-');
  const isScheduleTrigger = nodeId === 'schedule-trigger' || nodeId.startsWith('schedule-trigger-');
  const isManualTrigger = nodeId === 'manual-trigger' || nodeId.startsWith('manual-trigger-');
  const isChatTrigger = nodeId === 'chat-trigger' || nodeId.startsWith('chat-trigger-');
  const isFormTrigger = nodeId === 'form-trigger' || nodeId.startsWith('form-trigger-');
  const isErrorTrigger = nodeId === 'error-trigger' || nodeId.startsWith('error-trigger-');
  const isTablesTriggerId = nodeId === 'tables-trigger' || nodeId.startsWith('tables-trigger-');
  const isTablesTrigger = isTablesTriggerId && nodeKind === 'entry';
  const isTriggerGenericNode =
    (nodeId === 'triggers' || nodeId.startsWith('triggers-')) &&
    !isWebhookTrigger && !isScheduleTrigger && !isManualTrigger && !isTablesTrigger && !isChatTrigger && !isFormTrigger;
  const isGenericEntryTrigger =
    nodeKind === 'entry' &&
    !isWebhookTrigger && !isScheduleTrigger && !isManualTrigger && !isTablesTrigger && !isChatTrigger && !isFormTrigger &&
    !nodeId.includes('-trigger-');
  const isTriggerNode =
    nodeKind === 'entry' || isTriggerGenericNode || isGenericEntryTrigger ||
    isWebhookTrigger || isScheduleTrigger || isManualTrigger || isTablesTrigger || isChatTrigger || isFormTrigger;

  const isTableNode = hasDataSourceData;
  const isStaticFileProducingNode =
    data.kind === 'download_file' ||
    data.kind === 'convert_to_file' ||
    data.kind === 'compression' ||
    data.kind === 'sftp' ||
    // media outputs a FileRef `file` for every operation except probe: mux_audio/
    // mix/extract_audio/concat/overlay/subtitles produce audio or video, frame an
    // image (probe outputs none - the FileRef walker finds nothing to display).
    data.kind === 'media';
  const isInterfaceNode = nodeId === 'interface' || nodeId.startsWith('interface-');

  const triggerVariant: TriggerButtonVariant = isManualTrigger ? 'lightning'
    : isChatTrigger ? 'message'
    : isFormTrigger ? 'form'
    : isWebhookTrigger ? 'webhook'
    : isScheduleTrigger ? 'schedule'
    : isWorkflowsTriggerNode ? 'workflow'
    : isTablesTrigger ? 'table'
    : isErrorTrigger ? 'error'
    : 'play';

  return {
    isAiAgentNode,
    isSubWorkflowNode,
    isWorkflowsTriggerNode,
    isTableNode,
    isInterfaceNode,
    isStaticFileProducingNode,
    isTriggerNode,
    isManualTrigger,
    isChatTrigger,
    isFormTrigger,
    isWebhookTrigger,
    isScheduleTrigger,
    isTablesTrigger,
    isErrorTrigger,
    triggerVariant,
    referencedWorkflowId,
    referencedWorkflowName,
  };
}

export interface NodeContextualButton {
  key: string;
  icon: React.ReactNode;
  title: string;
  onClick: (e: React.MouseEvent) => void;
}

interface UseNodeContextualButtonsParams {
  /** Node payload (provides agentConfigId / dataSourceData / labels). */
  data: BuilderNodeData;
  /** React Flow node id - used for the run-mode sub-workflow open event. */
  nodeUiId: string;
  /** Whether the canvas is in run mode (changes sub-workflow open behavior). */
  isRunMode: boolean;
  /** Node-type flags from {@link deriveNodeContextFlags}. */
  flags: NodeContextFlags;
  /**
   * Include the "Files" button (download/convert/compression/sftp nodes, or any
   * node with a resolved FileRef). Only the canvas bottom bar passes true - the
   * run-info popover excludes Files by design.
   */
  includeFiles?: boolean;
  /** Resolved file target for the Files button (FlowNode runtime state). */
  currentFile?: FilePanelTarget | null;
}

/**
 * Builds the shared contextual side-panel buttons (agent config + conversation,
 * table data, sub-workflow, optionally files) for a node. Behavior is identical
 * to the canvas bottom bar so the run-info step popover stays in lock-step.
 *
 * Reads {@link useSidePanelSafe} - works both in and out of the StepByStep
 * provider since the SidePanel provider lives at the app-layout level.
 */
export function useNodeContextualButtons({
  data,
  nodeUiId,
  isRunMode,
  flags,
  includeFiles = false,
  currentFile = null,
}: UseNodeContextualButtonsParams): NodeContextualButton[] {
  const sidePanel = useSidePanelSafe();
  const canOpenPanels = sidePanel != null;
  React.useEffect(() => {
    if (canOpenPanels) preloadNodePanels();
  }, [canOpenPanels]);
  // Which workflow this button belongs to, so a run-mode sub-workflow request is
  // answered by the view hosting THIS canvas rather than by every mounted one.
  // The hook is used outside a provider too (the run-info popover), where this is
  // undefined - and an unaddressed request still reaches every listener.
  const { workflowId: hostWorkflowId } = useWorkflowMode();
  const buttons: NodeContextualButton[] = [];

  // Agent buttons - open the agent side panel on its config or conversation tab.
  if (flags.isAiAgentNode && (data as any)?.agentConfigId) {
    const agentCfgId = (data as any).agentConfigId;
    const agentName = (data as any).agentConfigName || data.label || 'Agent';
    const tabId = `agent-${agentCfgId}`;
    const openAgentTab = (initialTab: AgentPanelTab) => {
      const existing = sidePanel?.tabs?.some((t) => t.id === tabId);
      if (existing) {
        sidePanel?.updateTab(tabId, { content: <AgentPanelContent agentId={agentCfgId} initialTab={initialTab} /> });
        sidePanel?.setActiveTab(tabId);
        sidePanel?.open();
      } else {
        sidePanel?.openTab({ id: tabId, label: agentName, icon: <Bot className="w-4 h-4" />, content: <AgentPanelContent agentId={agentCfgId} initialTab={initialTab} />, preferredWidth: 0.35 });
      }
    };
    buttons.push(
      { key: 'agent-config', icon: <Settings className="h-3 w-3" strokeWidth={2} />, title: 'Configuration', onClick: () => openAgentTab(AGENT_CONFIGURATION_TAB) },
      { key: 'agent-conv', icon: <MessageSquare className="h-3 w-3" strokeWidth={2} />, title: 'Conversation', onClick: () => openAgentTab(AGENT_CONVERSATION_TAB) },
    );
  }

  // Table node button - view data in side panel.
  if (flags.isTableNode && (data as any)?.dataSourceData?.dataSourceId) {
    const dsId = (data as any).dataSourceData.dataSourceId;
    const dsName = (data as any).dataSourceData.dataSourceName || data.label;
    buttons.push({
      key: 'table-data',
      icon: <Table className="h-3 w-3" strokeWidth={2} />,
      title: dsName,
      onClick: () => {
        sidePanel?.openTab({
          id: `datasource-${dsId}`,
          label: dsName,
          icon: <Table className="w-4 h-4" />,
          content: <DataSourcePanelContent dataSourceId={dsId} />,
          preferredWidth: 0.35,
        });
      },
    });
  }

  // File-producing nodes - open the side-panel "Files" tab. Canvas-only, and
  // ONLY while no file strip is on screen: currentFile means FileNodePreview is
  // showing its pill under the node, and that pill already carries the very same
  // openFilesPanel button. The two must never coexist - the strip then takes the
  // bar's row (calc(100% + 8px)) and the bar is lowered a row by FlowNode. Edit
  // mode (and any run with no resolved file) keeps the button: static core file
  // nodes (download_file, convert_to_file, compression, sftp, media) still need
  // a way into their files with no strip to click.
  if (includeFiles && flags.isStaticFileProducingNode && !currentFile) {
    buttons.push({
      key: 'files',
      icon: <FolderOpen className="h-3 w-3" strokeWidth={2} />,
      title: 'Files',
      // No target file by construction: this button only exists while no strip is
      // up, i.e. while nothing has resolved a FileRef. It opens the Files tab plain.
      onClick: () => openFilesPanel(sidePanel),
    });
  }

  // Sub-workflow button - open the referenced workflow (run: dedicated panel via
  // event; edit: lazy-loaded builder panel).
  if ((flags.isSubWorkflowNode || flags.isWorkflowsTriggerNode) && flags.referencedWorkflowId) {
    const referencedWorkflowId = flags.referencedWorkflowId;
    const referencedWorkflowName = flags.referencedWorkflowName;
    buttons.push({
      key: 'subworkflow',
      icon: <Workflow className="h-3 w-3" strokeWidth={2} />,
      title: referencedWorkflowName,
      onClick: () => {
        // Run mode goes through the view, which resolves the target's PINNED RUN first; edit mode
        // opens its builder straight away. Both shapes live in one place now.
        if (isRunMode) {
          requestOpenRelatedWorkflow(referencedWorkflowId, referencedWorkflowName, nodeUiId, hostWorkflowId ?? undefined);
        } else {
          openWorkflowBuilderTab(sidePanel, { workflowId: referencedWorkflowId, workflowName: referencedWorkflowName });
        }
      },
    });
  }

  return buttons;
}
