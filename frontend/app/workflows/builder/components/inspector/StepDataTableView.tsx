'use client';

import * as React from 'react';
import DataTable from '@/components/DataTable';

interface StepDataTableViewProps {
  stepId?: number;
  stepAlias?: string;
  workflowId: string;
  runId: string;
  jsonPath?: string;
  onNavigate?: (path: string) => void;
  showIdColumn?: boolean;
}

/**
 * Composant dédié pour afficher les données d'un step dans une DataTable
 * Encapsule la logique DataTable spécifique aux workflow steps
 * 
 * Peut être utilisé avec:
 * - stepId: pour afficher les données d'un step spécifique
 * - stepAlias: pour afficher les données merged de tous les steps avec cet alias
 */
export function StepDataTableView({
  stepId,
  stepAlias,
  workflowId,
  runId,
  jsonPath = '',
  onNavigate,
  showIdColumn = true,
}: StepDataTableViewProps) {
  const handleNavigate = React.useCallback((newPath: string) => {
    if (onNavigate) {
      onNavigate(newPath);
    }
  }, [onNavigate]);

  // Si stepAlias est fourni sans stepId, utiliser dataSourceId=0 et passer stepAlias dans workflowContext
  // Cela permet d'utiliser l'endpoint merged qui retourne toutes les colonnes importantes
  const dataSourceId = stepId ?? 0;

  // Memoize workflowContext to prevent infinite re-renders
  const workflowContext = React.useMemo(() => ({
    workflowId,
    runId,
    ...(stepId && { stepId }),
    ...(stepAlias && { stepAlias }),
  }), [workflowId, runId, stepId, stepAlias]);

  return (
    <div className="w-full h-full flex flex-col overflow-hidden">
      <div className="flex-1 min-h-0">
        <DataTable
          dataSourceId={dataSourceId}
          jsonPath={jsonPath}
          workflowContext={workflowContext}
          onNavigate={handleNavigate}
          showIdColumn={showIdColumn}
          embedded
        />
      </div>
    </div>
  );
}

