'use client';

import React, { useState } from 'react';
import { WorkflowPreviewBlock } from './WorkflowPreviewBlock';
import { WorkflowRunBlock } from './WorkflowRunBlock';
import { ChatTableView } from './ChatTableView';
import { InterfacePreviewBlock } from './InterfacePreviewBlock';
import { CredentialCard } from './CredentialCard';
import { ApplicationVisualizeCard } from './ApplicationVisualizeCard';
import { AgentVisualizeCard } from './AgentVisualizeCard';
import { AgentBrowseVisualizeCard } from './AgentBrowseVisualizeCard';
import { ImageGenerationVisualizeCard } from './ImageGenerationVisualizeCard';
import { FileVisualizeCard } from './FileVisualizeCard';
import { AlertCircle } from 'lucide-react';
export interface VisualizeBlockProps {
  type: 'workflow' | 'workflow_run' | 'datasource' | 'table' | 'interface' | 'credential' | 'application' | 'agent' | 'web_search' | 'agent_browse' | 'image_generation' | 'file';
  id: string;
  title?: string;
  /** For workflow_run: the run ID */
  runId?: string;
  /** For workflow_run: the run index */
  runIndex?: number;
  /** For credential: the service icon slug (e.g., 'gmail', 'slack') */
  iconSlug?: string;
  /** For credential: the service name for display */
  serviceName?: string;
  onDelete?: (type: 'workflow' | 'datasource' | 'interface' | 'application' | 'agent' | 'web_search' | 'agent_browse' | 'image_generation', id: string) => void;
  onRunWorkflow?: (workflowId: string) => void;
  /** Read-only mode: renders a static card without API calls (used on public share pages) */
  readOnly?: boolean;
}

/**
 * Main component for rendering visualizations in chat.
 * Dispatches to the appropriate preview component based on type.
 */
export function VisualizeBlock({
  type,
  id,
  title,
  runId,
  runIndex,
  iconSlug,
  serviceName,
  onDelete,
  onRunWorkflow,
  readOnly,
}: VisualizeBlockProps) {
  const [error, setError] = useState<string | null>(null);

  if (error) {
    return (
      <div className="rounded-[18px] my-4 p-4 dark:border-red-800 bg-red-50 dark:bg-red-900/20">
        <div className="flex items-center gap-2 text-red-600 dark:text-red-400">
          <AlertCircle className="h-4 w-4" />
          <span className="text-sm font-medium">Failed to load {type}</span>
        </div>
        <p className="mt-1 text-sm text-red-500 dark:text-red-400">{error}</p>
      </div>
    );
  }

  // For datasource/table, render ChatTableView directly
  // Note: Backend may return 'table' or 'datasource' - both are valid
  if (type === 'datasource' || type === 'table') {
    const numericId = parseInt(id, 10);
    if (isNaN(numericId)) {
      return (
        <div className="my-4 p-4 bg-red-50 dark:bg-red-900/20 rounded-xl text-red-600 dark:text-red-400 text-sm">
          Invalid table ID
        </div>
      );
    }
    return (
      <ChatTableView
        dataSourceId={numericId}
        maxRows={10}
        className="w-full"
        onDelete={readOnly || !onDelete ? undefined : () => onDelete('datasource', id)}
        readOnly={readOnly}
      />
    );
  }

  // For interface, render InterfacePreviewBlock directly
  if (type === 'interface') {
    return (
      <InterfacePreviewBlock
        interfaceId={id}
        onError={setError}
        onDelete={readOnly || !onDelete ? undefined : () => onDelete('interface', id)}
      />
    );
  }

  // For workflow_run, render WorkflowRunBlock with lazy streaming connection
  if (type === 'workflow_run') {
    if (!runId) {
      return (
        <div className="my-4 p-4 bg-red-50 dark:bg-red-900/20 rounded-xl text-red-600 dark:text-red-400 text-sm">
          Missing run ID for workflow execution
        </div>
      );
    }
    return (
      <WorkflowRunBlock
        workflowId={id}
        runId={runId}
        runIndex={runIndex}
        workflowName={title}
        onError={setError}
      />
    );
  }

  // For credential, render CredentialCard to prompt user to connect
  if (type === 'credential') {
    return (
      <CredentialCard
        toolId={id}
        iconSlug={iconSlug}
        serviceName={serviceName}
        title={title}
        className="w-full"
      />
    );
  }

  // For application, render a clickable card that opens the side panel.
  // When runId is set (4-field marker emitted by ApplicationExecuteModule on
  // execute, and by ApplicationCrudModule.executeCreate on create), the card
  // switches to a live preview of THAT execution's run/epoch.
  if (type === 'application') {
    return (
      <ApplicationVisualizeCard
        publicationId={id}
        title={title}
        runId={runId}
        onDelete={readOnly || !onDelete ? undefined : () => onDelete('application', id)}
      />
    );
  }

  // For web_search, no inline card - search/fetch results are now shown as a
  // favicon stack on the tool-call row in GroupedToolCard. Return null so any
  // legacy [visualize:web_search:<id>] marker still emitted by the backend
  // collapses silently in the chat.
  if (type === 'web_search') {
    return null;
  }

  // For agent_browse, render the live browser-agent card. Same browser-chrome
  // preview style as web_search, but the side panel embeds the live CDP
  // canvas (BrowserLiveCdpPanel) instead of the static fetch screenshots.
  if (type === 'agent_browse') {
    return (
      <AgentBrowseVisualizeCard
        interfaceId={id}
        title={title}
      />
    );
  }

  // For image_generation, render the generated images grid + open the
  // side panel for full-size view. Backed by an Interface entity created
  // by ImageGenerationToolsProvider's persist hook (data.images[].base64).
  if (type === 'image_generation') {
    return (
      <ImageGenerationVisualizeCard
        interfaceId={id}
        title={title}
      />
    );
  }

  // For file, render a clickable card that opens the side-panel FileDetailView
  // for that specific stored file (preview + download). Same side-panel
  // mechanism as the image-generation card, but addressed by storage file_id.
  if (type === 'file') {
    return (
      <FileVisualizeCard
        fileId={id}
        title={title}
      />
    );
  }

  // For agent, render an agent visualization card
  if (type === 'agent') {
    return (
      <AgentVisualizeCard
        agentId={id}
        title={title}
        onDelete={readOnly || !onDelete ? undefined : () => onDelete('agent', id)}
      />
    );
  }

  // For workflow, render WorkflowPreviewBlock directly (same pattern as interface/table)
  return (
    <WorkflowPreviewBlock
      workflowId={id}
      onError={setError}
      onDelete={readOnly || !onDelete ? undefined : () => onDelete('workflow', id)}
      onRun={readOnly ? undefined : onRunWorkflow}
      readOnly={readOnly}
    />
  );
}
