import React from 'react';
import { Bot, Workflow, Table, Monitor } from 'lucide-react';
import type { SidePanelTab } from '@/contexts/SidePanelContext';

export interface FleetSidePanelAction {
  type: 'agent' | 'workflow' | 'table' | 'interface';
  resourceId: string;
  label: string;
  avatarUrl?: string | null;
}

/** Icon component for each side-panel-openable resource type */
export const FLEET_SIDE_PANEL_ICONS: Record<
  FleetSidePanelAction['type'],
  React.ComponentType<{ className?: string }>
> = {
  agent: Bot,
  workflow: Workflow,
  table: Table,
  interface: Monitor,
};

/**
 * Opens the appropriate side panel tab for a fleet resource.
 *
 * Content components are imported lazily inside the function body to break
 * the circular import chain:
 *   FleetInspectorPanel → FleetInspectorActionButtons → this file
 *     → AgentPanelContent → FleetInspectorPanel
 */
export function openFleetSidePanelTab(
  sidePanel: { openTab: (tab: SidePanelTab) => void },
  action: FleetSidePanelAction,
) {
  const { type, resourceId, label } = action;
  const iconClass = 'w-4 h-4';

  switch (type) {
    case 'agent': {
      // Lazy require to break circular dependency with AgentPanelContent
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const { AgentPanelContent, AGENT_CONFIGURATION_TAB } = require('@/components/app/AgentPanelContent');
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const { AvatarDisplay } = require('@/components/agents/AvatarPicker');
      const agentIcon = action.avatarUrl
        ? React.createElement('div', { className: 'w-4 h-4 flex-shrink-0' },
            React.createElement(AvatarDisplay, { avatarUrl: action.avatarUrl, name: label, size: 'sm', className: '!w-4 !h-4' }))
        : React.createElement(Bot, { className: iconClass });
      sidePanel.openTab({
        id: `agent-${resourceId}`,
        label,
        icon: agentIcon,
        content: React.createElement(AgentPanelContent, { agentId: resourceId, initialTab: AGENT_CONFIGURATION_TAB }),
        preferredWidth: 0.35,
      });
      break;
    }
    case 'workflow': {
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const { WorkflowBuilderPanelContent } = require('@/components/app/WorkflowBuilderPanelContent');
      sidePanel.openTab({
        id: `workflow-${resourceId}`,
        label,
        icon: React.createElement(Workflow, { className: iconClass }),
        content: React.createElement(WorkflowBuilderPanelContent, { workflowId: resourceId }),
        preferredWidth: 0.35,
      });
      break;
    }
    case 'table': {
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const { DataSourcePanelContent } = require('@/components/app/DataSourcePanelContent');
      sidePanel.openTab({
        id: `datasource-${resourceId}`,
        label,
        icon: React.createElement(Table, { className: iconClass }),
        content: React.createElement(DataSourcePanelContent, { dataSourceId: resourceId }),
        preferredWidth: 0.35,
      });
      break;
    }
    case 'interface': {
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const { InterfacePanelContent } = require('@/components/app/InterfacePanelContent');
      sidePanel.openTab({
        id: `interface-${resourceId}`,
        label,
        icon: React.createElement(Monitor, { className: iconClass }),
        content: React.createElement(InterfacePanelContent, { interfaceId: resourceId }),
        preferredWidth: 0.35,
      });
      break;
    }
  }
}
