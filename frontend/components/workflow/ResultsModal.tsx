'use client';

import * as React from 'react';
import { Dialog, DialogContent, DialogHeader } from '@/components/ui/dialog';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import DataTable from '@/components/DataTable';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useStepData } from '@/app/workflows/builder/hooks/useStepData';

interface ResultsModalProps {
  isOpen: boolean;
  onClose: () => void;
  workflowId: string;
  runId: string;
}

type ViewMode = 'aggregated' | 'step';

interface NavigationState {
  mode: ViewMode;
  selectedAlias?: string;
  jsonPath?: string;
}

export function ResultsModal({ isOpen, onClose, workflowId, runId }: ResultsModalProps) {
  const [navigation, setNavigation] = React.useState<NavigationState>({ mode: 'aggregated' });
  
  // Fetch step data when viewing a specific step
  const { stepData, loading: loadingSteps, error: errorSteps } = useStepData(
    navigation.mode === 'step' && navigation.selectedAlias ? runId : undefined,
    navigation.mode === 'step' && navigation.selectedAlias ? navigation.selectedAlias : undefined
  );

  // Handle row click in DataTable
  const handleRowClick = React.useCallback((row: any) => {
    if (navigation.mode === 'aggregated') {
      // Click on aggregated step -> go to step details
      const alias = row.data?.alias;
      if (alias) {
        setNavigation({ mode: 'step', selectedAlias: alias });
      }
    }
    // Navigation vers step details supprimée - à refaire avec DataTable
  }, [navigation.mode]);

  // Handle navigation within JSON path
  const handleNavigate = React.useCallback((newPath: string) => {
    // Mettre à jour le jsonPath pour la navigation nested
    setNavigation(prev => ({ ...prev, jsonPath: newPath }));
  }, []);

  // Build breadcrumb items
  const breadcrumbItems = React.useMemo(() => {
    const items: Array<{ label: string; onClick?: () => void }> = [];
    
    // Always start with "Steps"
    items.push({
      label: 'Steps',
      onClick: navigation.mode !== 'aggregated' ? () => setNavigation({ mode: 'aggregated' }) : undefined,
    });
    
    // If viewing a specific step alias
    if (navigation.selectedAlias) {
      items.push({
        label: navigation.selectedAlias,
      });
    }
    
    return items;
  }, [navigation]);

  // Reset navigation when modal closes
  React.useEffect(() => {
    if (!isOpen) {
      setNavigation({ mode: 'aggregated' });
    }
  }, [isOpen]);

  // Render aggregated steps using DataTable
  const renderAggregatedSteps = () => {
    return (
      <div className="w-full h-full flex flex-col overflow-hidden">
        <div className="flex-1 min-h-0">
          <DataTable
            dataSourceId={-999} // Special ID for aggregated steps
            workflowContext={{
              workflowId: workflowId,
              runId: runId,
              stepId: -999 // Special ID to indicate aggregated view
            }}
            onRowClick={handleRowClick}
            embedded
          />
        </div>
      </div>
    );
  };

  // Render step details using DataTable
  const renderStepDetails = () => {
    if (loadingSteps) {
      return (
        <div className="flex items-center justify-center py-8">
          <LoadingSpinner size="md" />
        </div>
      );
    }

    if (errorSteps) {
      return (
        <div className="text-sm text-red-600 p-4">
          Error loading step data: {errorSteps}
        </div>
      );
    }

    if (!stepData || stepData.length === 0) {
      return (
        <div className="text-sm text-slate-400 p-4 text-center">
          No step data available for alias: {navigation.selectedAlias}
        </div>
      );
    }

    // Use DataTable with special stepId = -998 to show all steps for this alias
    const firstStepId = stepData[0].id;
    
    return (
      <div className="w-full h-full flex flex-col overflow-hidden">
        <div className="flex-1 min-h-0">
          <DataTable
            dataSourceId={firstStepId}
            jsonPath={navigation.jsonPath}
            workflowContext={{
              workflowId: workflowId,
              runId: runId,
              stepId: -998 // Special ID to show all steps (will be filtered by alias in the controller)
            }}
            onRowClick={handleRowClick}
            onNavigate={handleNavigate}
            embedded
          />
        </div>
      </div>
    );
  };


  return (
    <Dialog open={isOpen} onOpenChange={(open) => {
      if (!open) {
        onClose();
      }
    }}>
      <DialogContent className="w-[90vw] max-w-[1400px] h-[80vh] max-h-[800px] overflow-hidden bg-theme-primary p-0 flex flex-col rounded-3xl">
        <DialogHeader className="px-6 pt-4 pb-3 h-auto max-h-[90px] overflow-hidden flex-shrink-0">
          <div className="flex items-center justify-end">
            {breadcrumbItems.length > 0 ? (
              <Breadcrumb
                items={breadcrumbItems}
                variant="minimal"
                separator="slash"
                className="mb-0"
              />
            ) : (
              <h2 className="text-base font-semibold">Workflow Steps</h2>
            )}
          </div>
        </DialogHeader>
        <div className="px-6 pb-6 flex-1 min-h-0 overflow-hidden">
          {navigation.mode === 'aggregated' && renderAggregatedSteps()}
          {navigation.mode === 'step' && renderStepDetails()}
        </div>
      </DialogContent>
    </Dialog>
  );
}
