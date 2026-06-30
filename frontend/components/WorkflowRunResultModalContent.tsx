'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { FileText } from 'lucide-react';
import StepTable, { WorkflowStep } from '@/components/StepTable';
import DataTable from '@/components/DataTable';
import { WorkflowStepTable } from '@/components/workflow/WorkflowStepTable';
import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';
import { normalizeId } from '@/app/workflows/builder/services/idMatcherUtils';
import { apiClient } from '@/lib/api';
import { getCanvasNodes } from '@/app/workflows/builder/services/canvasNodesStore';
import { nodeMatchesStep } from '@/app/workflows/builder/services/nodeMatcher';
import { getIconSlug, NodeIcon } from '@/app/workflows/builder/components/nodes/shared';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';

interface WorkflowRunResultModalContentProps {
  workflowId: string;
  runId: string;
  onBreadcrumbChange?: (items: Array<{ label: string; onClick?: () => void; icon?: React.ComponentType<{ className?: string }> }>) => void;
  onCloseModal?: () => void;
  initialStepAlias?: string;
}

export function WorkflowRunResultModalContent({
                                                workflowId,
                                                runId,
                                                onBreadcrumbChange,
                                                onCloseModal,
                                                initialStepAlias,
                                              }: WorkflowRunResultModalContentProps) {
  const router = useRouter();
  const [selectedStep, setSelectedStep] = useState<WorkflowStep | null>(null);
  const [selectedIndividualStep, setSelectedIndividualStep] = useState<any | null>(null);
  const [jsonPath, setJsonPath] = useState<string>('');
  const [steps, setSteps] = useState<WorkflowStep[]>([]);
  const [loadingSteps, setLoadingSteps] = useState(true);

  // stepAlias du step sélectionné (pour le breadcrumb et MergedCallsDataTable)
  const stepAlias = selectedStep?.stepAlias;

  // Charger les steps agrégés pour pouvoir sélectionner automatiquement initialStepAlias
  useEffect(() => {
    const fetchSteps = async () => {
      if (!runId) {
        setSteps([]);
        setLoadingSteps(false);
        return;
      }

      try {
        setLoadingSteps(true);
        const aggregatedData = await apiClient.get<Array<{
          status: string;
          alias: string;
          toolId: string;
          startTime: string | null;
          endTime: string | null;
          statusCounts?: {
            completed?: number;
            failed?: number;
            skipped?: number;
            running?: number;
          };
        }>>(`/v2/workflows/dag/instances/${runId}/steps/aggregated`);
        
        if (!aggregatedData || !Array.isArray(aggregatedData)) {
          setSteps([]);
          return;
        }
        
        const transformedSteps: WorkflowStep[] = aggregatedData.map((step) => ({
          id: step.alias,
          stepAlias: step.alias,
          toolId: step.toolId,
          status: step.status,
          startTime: step.startTime || new Date().toISOString(),
          endTime: step.endTime || undefined,
          statusCounts: step.statusCounts,
          runId: runId,
        }));
        
        setSteps(transformedSteps);
      } catch (err) {
        console.error('Error fetching aggregated steps:', err);
        setSteps([]);
      } finally {
        setLoadingSteps(false);
      }
    };

    fetchSteps();
  }, [runId]);

  // Sélectionner automatiquement le step correspondant à initialStepAlias
  useEffect(() => {
    if (initialStepAlias && steps.length > 0 && !selectedStep) {
      // initialStepAlias vient déjà de extractStepAliasFromNode qui a fait l'extraction
      // On l'utilise directement comme alias extrait
      const extractedAlias = initialStepAlias;
      
      // Normaliser l'alias initial pour la recherche (utiliser normalizeLabel et normalizeId comme dans nodeMatcher)
      // Pour les steps, on peut avoir soit le label normalisé, soit l'alias extrait depuis node.data.id
      // On essaie plusieurs variantes pour être sûr de trouver
      const normalizedInitialAlias = normalizeLabel(extractedAlias) || normalizeId(extractedAlias) || extractedAlias.toLowerCase().trim();
      const extractedAliasLower = extractedAlias.toLowerCase().trim();
      
      // Pour les triggers, aussi essayer sans le préfixe "trigger:" pour matcher avec les formats backend
      // Le backend sauvegarde les triggers avec juste le label normalisé (sans "trigger:")
      const triggerVariants: string[] = [];
      if (extractedAlias.startsWith('trigger:')) {
        const triggerId = extractedAlias.substring('trigger:'.length);
        // Le backend sauvegarde juste le label normalisé, pas "trigger:label"
        triggerVariants.push(triggerId); // "test" depuis "trigger:test"
        triggerVariants.push(normalizeLabel(triggerId) || triggerId); // version normalisée du label
        triggerVariants.push(normalizeId(triggerId)); // version normalisée avec normalizeId
      }
      
      // Chercher le step correspondant par alias exact ou par label normalisé
      const matchingStep = steps.find(step => {
        if (!step.stepAlias) return false;
        
        // Normaliser le stepAlias du step de la même manière
        const stepAliasNorm = normalizeLabel(step.stepAlias) || normalizeId(step.stepAlias) || step.stepAlias.toLowerCase().trim();
        const stepAliasLower = step.stepAlias.toLowerCase().trim();
        
        // Correspondance exacte (priorité 1)
        if (stepAliasNorm === normalizedInitialAlias) {
          return true;
        }
        
        // Pour les triggers, vérifier aussi les variantes sans préfixe (le backend sauvegarde sans "trigger:")
        if (triggerVariants.length > 0) {
          for (const variant of triggerVariants) {
            const variantLower = variant.toLowerCase().trim();
            // Correspondance exacte avec la variante
            if (stepAliasNorm === variantLower || 
                stepAliasLower === variantLower ||
                stepAliasNorm === normalizeLabel(variant) ||
                stepAliasNorm === normalizeId(variant)) {
              return true;
            }
            // Correspondance avec "trigger:" préfixe (au cas où)
            if (stepAliasLower === `trigger:${variantLower}` ||
                stepAliasNorm === `trigger:${variantLower}`) {
              return true;
            }
          }
        }
        
        // Correspondance par inclusion (priorité 2)
        if (stepAliasNorm.includes(normalizedInitialAlias) || normalizedInitialAlias.includes(stepAliasNorm)) {
          return true;
        }
        
        // Correspondance directe sans normalisation (fallback)
        if (stepAliasLower === extractedAliasLower || 
            stepAliasLower.includes(extractedAliasLower) || 
            extractedAliasLower.includes(stepAliasLower)) {
          return true;
        }
        
        // Correspondance avec extractedAlias tel quel (sans normalisation)
        if (step.stepAlias === extractedAlias || 
            step.stepAlias.toLowerCase() === extractedAliasLower) {
          return true;
        }
        
        return false;
      });
      
      if (matchingStep) {
        setSelectedStep(matchingStep);
      }
    }
  }, [initialStepAlias, steps, selectedStep]);

  const handleStepClick = useCallback((step: WorkflowStep) => {
    setSelectedStep(step);
    setSelectedIndividualStep(null);
    setJsonPath('');
  }, []);

  const handleNavigate = useCallback((newPath: string) => {
    console.log('[WorkflowRunResultModalContent] Navigating to:', newPath);
    setJsonPath(newPath);
  }, []);

  const handleBackToSteps = useCallback(() => {
    setSelectedStep(null);
    setSelectedIndividualStep(null);
    setJsonPath('');
  }, []);

  const handleIndividualStepClick = useCallback((step: any) => {
    setSelectedIndividualStep(step);
    setJsonPath('');
  }, []);

  const handleBackToStepRoot = useCallback(() => {
    setJsonPath('');
  }, []);

  const handleBreadcrumbPathClick = useCallback((pathUpToSegment: string) => {
    setJsonPath(pathUpToSegment);
  }, []);

  const handleRunClick = useCallback(() => {
    // Close modal first to avoid conflicts
    if (onCloseModal) {
      onCloseModal();
    }
    // Small delay to ensure modal closes before navigation
    setTimeout(() => {
      const configPath = `/app/workflow/${workflowId}`;
      console.log('[WorkflowRunResultModalContent] Run clicked, redirecting to config:', configPath);
      router.push(configPath);
    }, 100);
  }, [workflowId, router, onCloseModal]);

  const breadcrumbItems = useMemo(() => {
    const items: Array<{ label: string; onClick?: () => void; icon?: React.ComponentType<{ className?: string }> }> = [];

    // When at the list of steps, show the result icon (FileText) with runId as parent
    // When a step is selected, show the result icon + runId + step + jsonPath segments
    items.push({
      label: runId || 'Unknown',
      icon: FileText,
      onClick: handleBackToSteps,
    });

    if (selectedStep) {
      // Look up matching canvas node for icon and label
      const nodes = getCanvasNodes();
      const matched = nodes.find((n) => nodeMatchesStep(n, { stepAlias: selectedStep.stepAlias, id: selectedStep.stepAlias }));
      const d = matched?.data;
      const nc = d ? findNodeClassById(d.id || '') : null;

      // Create a wrapper icon component for the breadcrumb
      const stepIcon = d ? ({ className }: { className?: string }) => (
        <NodeIcon
          iconSlug={getIconSlug(d)}
          nodeId={d.id || ''}
          nodeKind={d.kind}
          nodeFamily={nc?.family}
          avatarUrl={(d as any)?.agentAvatarUrl}
          size="xs"
        />
      ) : undefined;

      const label = d?.label || selectedStep.stepAlias || 'Unknown';
      const capitalizedLabel = label.charAt(0).toUpperCase() + label.slice(1);

      items.push({
        label: capitalizedLabel,
        icon: stepIcon,
        onClick: selectedIndividualStep || jsonPath ? () => {
          setSelectedIndividualStep(null);
          setJsonPath('');
        } : undefined,
      });

      // Add jsonPath segments when in merged view (no individual step selected)
      if (!selectedIndividualStep && jsonPath) {
        const segments = jsonPath.split('.').filter((s) => s.length > 0);
        segments.forEach((segment, index) => {
          const pathUpToSegment = segments.slice(0, index + 1).join('.');
          items.push({
            label: segment,
            onClick: () => handleBreadcrumbPathClick(pathUpToSegment),
          });
        });
      }

      if (selectedIndividualStep) {
        items.push({
          label: `Call ${selectedIndividualStep.id || selectedIndividualStep.itemIndex || ''}`,
          onClick: jsonPath ? handleBackToStepRoot : undefined,
        });

        if (jsonPath) {
          const segments = jsonPath.split('.').filter((s) => s.length > 0);
          segments.forEach((segment, index) => {
            const pathUpToSegment = segments.slice(0, index + 1).join('.');
            items.push({
              label: segment,
              onClick: () => handleBreadcrumbPathClick(pathUpToSegment),
            });
          });
        }
      }
    }

    return items;
  }, [runId, selectedStep, selectedIndividualStep, jsonPath, handleBackToSteps, handleBackToStepRoot, handleBreadcrumbPathClick]);

  useEffect(() => {
    if (onBreadcrumbChange) {
      onBreadcrumbChange(breadcrumbItems);
    }
  }, [breadcrumbItems, onBreadcrumbChange]);

  // Memoize workflowContext for individual step to prevent infinite re-renders
  // Must be before any conditional returns to respect React's Rules of Hooks
  const individualStepContext = useMemo(() => ({
    workflowId,
    runId,
    stepId: selectedIndividualStep?.id,
  }), [workflowId, runId, selectedIndividualStep?.id]);

  // When no step is selected, show the list of aggregated steps
  if (!selectedStep) {
    return (
        <div className="w-full h-full flex flex-col overflow-hidden">
          <div className="flex-1 min-h-0 overflow-hidden">
            <StepTable
                workflowId={workflowId}
                runId={runId}
                onStepClick={handleStepClick}
            />
          </div>
        </div>
    );
  }

  // When a step is selected but no individual step, show all calls merged in a single table
  // Key based on stepAlias only - jsonPath changes are handled internally by DataTable
  if (!selectedIndividualStep) {
    return (
        <WorkflowStepTable
            key={`merged-table-${selectedStep.stepAlias}`}
            workflowId={workflowId}
            runId={runId}
            stepAlias={selectedStep.stepAlias}
            jsonPath={jsonPath}
            onNavigate={handleNavigate}
        />
    );
  }

  // When an individual step is selected, show its data table
  // Key based on jsonPath to force remount when path changes
  return (
      <div className="w-full h-full flex flex-col overflow-hidden">
        <div className="flex-1 min-h-0 overflow-hidden">
          <DataTable
              key={`individual-${selectedIndividualStep.id}-${jsonPath}`}
              dataSourceId={selectedIndividualStep.id}
              jsonPath={jsonPath}
              workflowContext={individualStepContext}
              onNavigate={handleNavigate}
              showIdColumn={true}
              embedded
          />
        </div>
      </div>
  );
}



