'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { WorkflowRunResultModalContent } from '@/components/WorkflowRunResultModalContent';
import { extractAliasFromNodeId, extractStepAliasFromNode } from '../../services/idMatcherUtils';
import type { BuilderNodeData } from '../../types';

interface InspectorModalsProps {
  node: Node<BuilderNodeData> | null;
  workflowId?: string;
  runId?: string;
  // Logs modal state
  isResultsModalOpen: boolean;
  onResultsModalOpenChange: (open: boolean) => void;
  modalBreadcrumbItems: Array<{ label: string; onClick?: () => void; icon?: React.ComponentType<{ className?: string }> }>;
  onModalBreadcrumbChange: (items: Array<{ label: string; onClick?: () => void; icon?: React.ComponentType<{ className?: string }> }>) => void;
}

/**
 * Component that manages all modals in the inspector panel.
 * Includes the logs/results modal.
 */
export function InspectorModals({
  node,
  workflowId,
  runId,
  isResultsModalOpen,
  onResultsModalOpenChange,
  modalBreadcrumbItems,
  onModalBreadcrumbChange,
}: InspectorModalsProps) {
  const handleResultsModalOpenChange = React.useCallback((open: boolean) => {
    onResultsModalOpenChange(open);
    if (!open) {
      onModalBreadcrumbChange([]);
    }
  }, [onResultsModalOpenChange, onModalBreadcrumbChange]);

  return (
    <>
      {/* Logs Modal */}
      {workflowId && runId && (
        <Dialog open={isResultsModalOpen} onOpenChange={handleResultsModalOpenChange}>
          <DialogContent className="w-[90vw] max-w-[1400px] h-[80vh] max-h-[800px] overflow-hidden bg-theme-primary p-0 flex flex-col !rounded-3xl !border-0">
            <DialogHeader className="px-6 pt-4 pb-3 h-auto max-h-[90px] overflow-hidden">
              {modalBreadcrumbItems.length > 0 ? (
                <Breadcrumb
                  items={modalBreadcrumbItems}
                  variant="minimal"
                  separator="slash"
                  className="mb-0"
                />
              ) : (
                <DialogTitle className="text-base">
                  {node ? `Step Logs: ${node?.data?.label || extractAliasFromNodeId(node.data.id) || 'Unknown'}` : 'Workflow Steps'}
                </DialogTitle>
              )}
            </DialogHeader>
            <div className="px-6 pb-6 flex-1 min-h-0 overflow-hidden">
              <WorkflowRunResultModalContent
                workflowId={workflowId}
                runId={runId}
                onBreadcrumbChange={onModalBreadcrumbChange}
                initialStepAlias={node ? extractStepAliasFromNode(node) || undefined : undefined}
              />
            </div>
          </DialogContent>
        </Dialog>
      )}
    </>
  );
}
