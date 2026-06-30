'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Panel, type Node, type Edge } from 'reactflow';
import { X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ConnectionTypeSelector, type ConnectionType } from './ConnectionTypeSelector';
import { WorkflowPlanGenerator } from './WorkflowPlanGenerator';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
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
  if (!isOpen) return null;

  return (
    <Panel position="top-right" className="m-4 relative z-[200]">
      <div className="w-72 rounded-[32px] bg-white/80 dark:bg-gray-800/80 backdrop-blur overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-5 pt-5 pb-3">
          <span className="text-sm font-semibold text-slate-900 dark:text-slate-100">{t('settings')}</span>
          <Button
            onClick={onClose}
            variant="ghost"
            size="icon"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('closeSettings')}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        <div className="px-5 pb-5 space-y-4">
          {/* Connection Type Section */}
          <div className="space-y-3">
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-1 block">
              {t('connectionStyle')}
            </span>
            {onReactFlowConnectionTypeChange && (
              <ConnectionTypeSelector
                value={reactFlowConnectionType}
                onChange={onReactFlowConnectionTypeChange}
              />
            )}
          </div>

          {/* Developer Tools Section - hidden in marketplace preview (read-only snapshot) */}
          {!isPreviewOnly && (
            <>
              <div className="border-b border-gray-200/50 dark:border-gray-700/50" />
              <div className="space-y-3">
                <span className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-1 block">
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
