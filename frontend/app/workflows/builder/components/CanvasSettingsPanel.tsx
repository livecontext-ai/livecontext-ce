'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Panel, type Node, type Edge } from 'reactflow';
import { X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { canvasChromeSurfaceClass } from '@/components/ui/canvas-chrome';
import {
  Select,
  SelectTrigger,
  SelectContent,
  SelectItem,
  SelectValue,
} from '@/components/ui/select';
import { ConnectionTypeSelector, type ConnectionType } from './ConnectionTypeSelector';
import { WorkflowPlanGenerator } from './WorkflowPlanGenerator';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import {
  useWorkflowLayoutDirectionSafe,
  type WorkflowLayoutDirection,
} from '@/contexts/WorkflowLayoutDirectionContext';
import { useInspectorDockSafe, type InspectorDock } from '@/contexts/InspectorDockContext';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { useInspectorOpenModeSafe, type InspectorOpenMode } from '@/contexts/InspectorOpenModeContext';
import { applyDagreLayout, layoutConfigForDirection } from '../services/LayoutService';
import type { BuilderNodeData } from '../types';

interface CanvasSettingsPanelProps {
  isOpen: boolean;
  onClose: () => void;
  isRunMode: boolean;
  reactFlowConnectionType: ConnectionType;
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  onReactFlowConnectionTypeChange?: (type: ConnectionType) => void;
  onForceNodesUpdate?: (nodes: Node<BuilderNodeData>[]) => void;
  onForceEdgesUpdate?: (edges: Edge[]) => void;
}

export function CanvasSettingsPanel({
  isOpen,
  onClose,
  isRunMode,
  reactFlowConnectionType,
  nodes,
  edges,
  onReactFlowConnectionTypeChange,
  onForceNodesUpdate,
  onForceEdgesUpdate,
}: CanvasSettingsPanelProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const { isPreviewOnly } = useWorkflowMode();
  const { direction, setWorkflowDirection } = useWorkflowLayoutDirectionSafe();
  // Reachable from the workflow, not only from account settings - and LINKED to it:
  // there is a single stored preference, so moving the inspector here moves it in
  // Settings too, and everywhere else. Unlike the layout direction below, it is not
  // part of a workflow's identity (nothing about it is saved into the plan), which
  // is why it writes the account-level setter directly.
  const { dock: inspectorDock, setDock: setInspectorDock } = useInspectorDockSafe();
  // Docking is only offered where there is a panel to dock INTO. The standalone /workflows
  // route has no side panel, and the canvas falls back to the floating inspector there
  // whatever the preference says - so showing the control would be stating a choice the
  // surface cannot honour, which is a different thing from the documented "request, not a
  // guarantee": mobile and the marketplace preview never put the control in front of
  // anyone. The preference itself is untouched; only this control hides.
  const canDockInspector = useSidePanelSafe() !== null;
  // Same contract as the dock above: ONE stored preference, so choosing how much of the
  // inspector a node click opens here changes it in Settings and in every other workflow.
  const { openMode: inspectorOpenMode, setOpenMode: setInspectorOpenMode } = useInspectorOpenModeSafe();

  // The in-canvas toggle sets the direction of THIS workflow, never the account default
  // (that is Settings > Preferences). A workflow's direction is part of the workflow: it is
  // saved into its plan and every viewer, the marketplace included, reads it back from
  // there. A toggle that also rewrote the default re-oriented every other canvas that falls
  // back on it, and, when the provider was shared, the workflow open behind the side panel.
  //
  // It re-lays the graph out in the same event: positions only mean something in the
  // direction they were computed in. Both land in one render, so they make one undo step
  // (useHistory records the direction with the positions) and arm Save together; the
  // direction reaches the database with that Save (generateWorkflowPlan stamps it).
  const changeDirection = React.useCallback(
    (next: WorkflowLayoutDirection) => {
      if (next === direction) return;
      setWorkflowDirection(next);
      if (onForceNodesUpdate && nodes.length > 0) {
        onForceNodesUpdate(applyDagreLayout(nodes, edges, layoutConfigForDirection(next)));
      }
    },
    [direction, setWorkflowDirection, onForceNodesUpdate, nodes, edges],
  );

  if (!isOpen) return null;

  return (
    <Panel position="top-right" className="m-4 relative z-[200]">
      {/* A floating chrome surface like the toolbar it opens from, not a
          rounded-[32px] capsule: one radius step above the controls it holds. */}
      <div className={`w-72 overflow-hidden ${canvasChromeSurfaceClass}`}>
        {/* Header */}
        <div className="flex items-center justify-between px-5 pt-4 pb-3">
          <span className="text-sm font-semibold text-[var(--text-primary)]">{t('settings')}</span>
          <Button
            onClick={onClose}
            variant="ghost"
            size="icon"
            className="h-7 w-7 flex-shrink-0"
            title={t('closeSettings')}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        <div className="px-5 pb-5 space-y-4">
          {/* Connection Type Section */}
          <div className="space-y-3">
            <span className="text-sm font-medium text-[var(--text-secondary)] mb-1 block">
              {t('connectionStyle')}
            </span>
            {onReactFlowConnectionTypeChange && (
              <ConnectionTypeSelector
                value={reactFlowConnectionType}
                onChange={onReactFlowConnectionTypeChange}
              />
            )}
          </div>

          {/* Layout direction of THIS workflow (saved into its plan; the account default
              lives in Settings). Changing it re-lays the graph out (changeDirection) and
              re-measures the handles (DirectionHandleSync). Same Select control as
              Connection Style above. Hidden in the read-only preview (its layout is
              frozen). */}
          {!isPreviewOnly && (
            <div className="space-y-3">
              <span className="text-sm font-medium text-[var(--text-secondary)] mb-1 block">
                {t('layoutDirection')}
              </span>
              <Select value={direction} onValueChange={(v) => changeDirection(v as WorkflowLayoutDirection)}>
                <SelectTrigger
                  className="h-9 min-h-[36px] py-0 rounded-xl text-sm"
                  data-testid="layout-direction-select"
                  onClick={(e) => e.stopPropagation()}
                  onMouseDown={(e) => e.stopPropagation()}
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="horizontal">{t('layoutHorizontal')}</SelectItem>
                  <SelectItem value="vertical">{t('layoutVertical')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          )}

          {/* Inspector placement - floating over the canvas, or docked as a sub-tab
              of the side panel. Hidden in the read-only preview, and on any surface with
              no side panel to dock into. */}
          {!isPreviewOnly && canDockInspector && (
            <div className="space-y-3">
              <span className="text-sm font-medium text-[var(--text-secondary)] mb-1 block">
                {t('inspectorDock')}
              </span>
              <Select value={inspectorDock} onValueChange={(v) => setInspectorDock(v as InspectorDock)}>
                <SelectTrigger
                  className="h-9 min-h-[36px] py-0 rounded-xl text-sm"
                  data-testid="inspector-dock-select"
                  onClick={(e) => e.stopPropagation()}
                  onMouseDown={(e) => e.stopPropagation()}
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="canvas">{t('inspectorDockCanvas')}</SelectItem>
                  <SelectItem value="panel">{t('inspectorDockPanel')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          )}

          {/* How much of the inspector a node click opens: its settings alone, or the
              three-column view with what feeds the node and what it produced. Hidden in
              the read-only preview, whose nodes are not configurable. Run mode is not
              hidden but has no effect there: a run always opens the full view. */}
          {!isPreviewOnly && (
            <div className="space-y-3">
              <span className="text-sm font-medium text-[var(--text-secondary)] mb-1 block">
                {t('inspectorOpenMode')}
              </span>
              <Select
                value={inspectorOpenMode}
                onValueChange={(v) => setInspectorOpenMode(v as InspectorOpenMode)}
              >
                <SelectTrigger
                  className="h-9 min-h-[36px] py-0 rounded-xl text-sm"
                  data-testid="inspector-open-mode-select"
                  onClick={(e) => e.stopPropagation()}
                  onMouseDown={(e) => e.stopPropagation()}
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="simple">{t('inspectorOpenModeSimple')}</SelectItem>
                  <SelectItem value="advanced">{t('inspectorOpenModeAdvanced')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          )}

          {/* Developer Tools Section - hidden in marketplace preview (read-only snapshot) */}
          {!isPreviewOnly && (
            <>
              <div className="border-b border-gray-200/50 dark:border-gray-700/50" />
              <div className="space-y-3">
                <span className="text-sm font-medium text-[var(--text-secondary)] mb-1 block">
                  {t('developerTools')}
                </span>
                <WorkflowPlanGenerator
                  nodes={nodes}
                  edges={edges}
                  readOnly={isRunMode}
                  onNodesChange={(newNodes) => {
                    if (newNodes.length > 0 && onForceNodesUpdate) {
                      onForceNodesUpdate(newNodes);
                    }
                  }}
                  onEdgesChange={(newEdges) => {
                    const existingIds = new Set(edges.map(e => e.id));
                    const edgesToAdd = newEdges.filter(e => !existingIds.has(e.id));
                    if (edgesToAdd.length > 0 && onForceEdgesUpdate) {
                      onForceEdgesUpdate([...edges, ...edgesToAdd]);
                    }
                  }}
                />
              </div>
            </>
          )}
        </div>
      </div>
    </Panel>
  );
}
