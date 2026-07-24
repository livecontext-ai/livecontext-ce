'use client';

import * as React from 'react';
import { Bot, Settings, MessageSquare, Table, FolderOpen, Workflow } from 'lucide-react';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentPanelContent, AGENT_CONVERSATION_TAB, AGENT_CONFIGURATION_TAB } from '@/components/app/AgentPanelContent';
import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';
import { openFilesPanel, type FilePanelTarget } from '@/lib/sidePanel/openFilesPanel';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { TriggerButtonVariant } from '../components/NodePlayButton';

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
    // mix/extract_audio/concat/overlay produce audio or video, frame an image
    // (probe outputs none - the FileRef walker simply finds nothing to display).
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
  const buttons: NodeContextualButton[] = [];

  // Agent buttons - open the agent side panel on its config or conversation tab.
  if (flags.isAiAgentNode && (data as any)?.agentConfigId) {
    const agentCfgId = (data as any).agentConfigId;
    const agentName = (data as any).agentConfigName || data.label || 'Agent';
    const tabId = `agent-${agentCfgId}`;
    const openAgentTab = (initialTab: typeof AGENT_CONFIGURATION_TAB | typeof AGENT_CONVERSATION_TAB) => {
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
        if (isRunMode) {
          window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', { detail: { workflowId: referencedWorkflowId, workflowName: referencedWorkflowName, nodeId: nodeUiId } }));
        } else {
          import('@/components/app/WorkflowBuilderPanelContent').then(({ WorkflowBuilderPanelContent }) => {
            sidePanel?.openTab({ id: `workflow-builder-${referencedWorkflowId}`, label: referencedWorkflowName, icon: <Workflow className="w-4 h-4" />, content: React.createElement(WorkflowBuilderPanelContent, { workflowId: referencedWorkflowId }), preferredWidth: 0.5, keepMounted: true });
          });
        }
      },
    });
  }

  return buttons;
}
