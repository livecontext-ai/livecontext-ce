import * as React from 'react';
import { Bot, Monitor, Table } from 'lucide-react';
import type { SidePanelTab } from '@/contexts/SidePanelContext';
import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';
import { InterfacePanelContent } from '@/components/app/InterfacePanelContent';
import { AgentPanelContent, AGENT_CONFIGURATION_TAB } from '@/components/app/AgentPanelContent';
import { openWorkflowBuilderTab } from '@/lib/sidePanel/openWorkflowBuilderTab';

/**
 * The views action='present' opens in the side panel on ANY page, from the tool that owns each
 * resource (workflow, table, interface, agent, files). The agent chose
 * them, so they are never a side effect of a build step (unlike the chat-only auto-open).
 *
 * The two RUN views (present_application, present_run) already have homes: the chat page's
 * run tab (AppHeader auto-open) and the workflow page of that same workflow, which switches in
 * place (WorkflowDetailView). Everywhere else they open the run here, so a presentation never
 * reports success while nothing moves.
 */
export const PRESENTED_VIEW_TYPES = [
  'present_table', 'present_interface', 'present_agent', 'present_file', 'present_workflow',
  'present_application', 'present_run',
] as const;

interface PresentingPanel {
  openTab: (tab: SidePanelTab) => void;
}

export interface PresentedView {
  type: string;
  id: string;
  title?: string;
  runId?: string;
}

export interface PresentingPage {
  /** The workflow the current page shows, if it is a workflow page. */
  workflowPageId: string | null;
  /** Chat pages open the run views through their own auto-open. */
  isChatPage: boolean;
}

/**
 * Open the view the agent presented. Returns whether THIS call handled it: false for a view the
 * page already shows or that another surface owns on this page.
 */
export function openPresentedView(
  panel: PresentingPanel | null | undefined,
  { type, id, title, runId }: PresentedView,
  { workflowPageId, isChatPage }: PresentingPage,
): boolean {
  if (!panel || !id) return false;
  switch (type) {
    case 'present_application':
    case 'present_run': {
      if (!runId || isChatPage || workflowPageId === id) return false;
      openWorkflowBuilderTab(panel, { workflowId: id, runId, workflowName: title });
      if (type === 'present_application') {
        // Lazy: the workflow panel module is the whole builder side panel.
        void import('@/components/app/WorkflowPanelContent').then(({ requestPresentApplication }) => {
          requestPresentApplication(id, runId);
        });
      }
      return true;
    }
    case 'present_table':
      panel.openTab({
        id: `datasource-${id}`,
        label: title || id,
        icon: <Table className="h-4 w-4" />,
        content: <DataSourcePanelContent dataSourceId={id} />,
        preferredWidth: 0.35,
      });
      return true;
    case 'present_interface':
      panel.openTab({
        id: `interface-${id}`,
        label: title || id,
        icon: <Monitor className="h-4 w-4" />,
        content: <InterfacePanelContent interfaceId={id} />,
        preferredWidth: 0.35,
      });
      return true;
    case 'present_agent':
      panel.openTab({
        id: `agent-${id}`,
        label: title || id,
        icon: <Bot className="w-4 h-4" />,
        content: <AgentPanelContent agentId={id} initialTab={AGENT_CONFIGURATION_TAB} />,
        preferredWidth: 0.35,
      });
      return true;
    case 'present_file':
      // Lazy: the file view pulls in every previewer, and this module sits in the app header.
      void import('@/lib/sidePanel/openFilesPanel').then(({ openFilesPanel }) => {
        openFilesPanel(panel, { id, name: title });
      });
      return true;
    case 'present_workflow':
      if (workflowPageId === id) return false;
      openWorkflowBuilderTab(panel, { workflowId: id, workflowName: title });
      return true;
    default:
      return false;
  }
}
