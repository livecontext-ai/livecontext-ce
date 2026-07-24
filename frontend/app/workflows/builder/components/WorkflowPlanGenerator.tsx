'use client';

import * as React from 'react';
import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { useTranslations } from 'next-intl';
import { createPortal } from 'react-dom';
import { FileJson, Copy, Check, X, Upload, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { generateWorkflowPlan } from '../utils/workflowPlanGenerator';
import { WorkflowPlanImporter } from '../services/workflowPlanImporter/WorkflowPlanImporter';
import { InputValidationService } from '../services/workflowPlanImporter/InputValidationService';
import { ToolDataService, type ImportProgress } from '../services/workflowPlanImporter/ToolDataService';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';

interface WorkflowPlanGeneratorProps {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  readOnly?: boolean;
  onNodesChange: (nodes: Node<BuilderNodeData>[]) => void;
  onEdgesChange: (edges: Edge[]) => void;
}

export function WorkflowPlanGenerator({ nodes, edges, readOnly = false, onNodesChange, onEdgesChange }: WorkflowPlanGeneratorProps) {
  // A generated plan carries no positions, so dagre lays it out: it must use the
  // reading direction the canvas is wired for.
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  const t = useTranslations('workflowBuilder.canvas');
  const tenantId = 'google-oauth2|109706784165946220967';
  const [isOpen, setIsOpen] = React.useState(false);
  const [planJson, setPlanJson] = React.useState<string>('');
  const [copied, setCopied] = React.useState(false);
  const [isImportOpen, setIsImportOpen] = React.useState(false);
  const [importJson, setImportJson] = React.useState<string>('');
  const [isImporting, setIsImporting] = React.useState(false);
  const [importError, setImportError] = React.useState<string | null>(null);
  const [importValidation, setImportValidation] = React.useState<any>(null);
  const [importProgress, setImportProgress] = React.useState<ImportProgress | null>(null);
  const importedNodePositionsRef = React.useRef<Map<string, { x: number; y: number }>>(new Map());
  const shouldFixPositionsRef = React.useRef(false);

  // Schedule state - listen for schedule changes from ChatHeader
  const currentScheduleRef = React.useRef<{ cron?: string } | null>(null);

  React.useEffect(() => {
    const handleScheduleChange = (event: CustomEvent<{ schedule: { cron?: string } | null }>) => {
      currentScheduleRef.current = event.detail?.schedule || null;
    };

    window.addEventListener('workflowScheduleChanged', handleScheduleChange as EventListener);

    // Request current schedule (in case it was already set)
    window.dispatchEvent(new CustomEvent('requestCurrentSchedule'));

    return () => {
      window.removeEventListener('workflowScheduleChanged', handleScheduleChange as EventListener);
    };
  }, []);
  
  // Effect to fix positions after ReactFlow renders, if needed
  React.useEffect(() => {
    if (!shouldFixPositionsRef.current || importedNodePositionsRef.current.size === 0) {
      return;
    }
    
    // Wait for ReactFlow to render
    const timeoutId = setTimeout(() => {
      const nodesToFix = nodes.map(node => {
        const savedPosition = importedNodePositionsRef.current.get(node.id);
        if (savedPosition) {
          // Check if position was changed by ReactFlow
          const currentPos = node.positionAbsolute || node.position;
          const positionChanged = 
            !currentPos ||
            Math.abs(currentPos.x - savedPosition.x) > 1 ||
            Math.abs(currentPos.y - savedPosition.y) > 1;
          
          if (positionChanged) {
            return {
              ...node,
              position: savedPosition,
              positionAbsolute: savedPosition,
            };
          }
        }
        return node;
      });
      
      // Only update if positions were changed
      const hasChanges = nodesToFix.some((node, index) => {
        const original = nodes[index];
        const origPos = original.positionAbsolute || original.position;
        const newPos = node.positionAbsolute || node.position;
        return Math.abs(newPos.x - origPos.x) > 1 || 
               Math.abs(newPos.y - origPos.y) > 1;
      });
      
      if (hasChanges) {
        onNodesChange(nodesToFix);
      }
      
      shouldFixPositionsRef.current = false;
    }, 100); // Small delay to ensure ReactFlow has rendered
    
    return () => clearTimeout(timeoutId);
  }, [nodes, onNodesChange]);

  const generatePlan = React.useCallback(() => {
    try {
      const plan = generateWorkflowPlan(nodes, edges);
      const json = JSON.stringify(plan, null, 2);
      setPlanJson(json);
      setIsOpen(true);
    } catch (error) {
      console.error('Error generating workflow plan:', error);
      setPlanJson(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
      setIsOpen(true);
    }
  }, [nodes, edges]);

  const copyToClipboard = React.useCallback(async () => {
    try {
      await navigator.clipboard.writeText(planJson);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (error) {
      console.error('Failed to copy:', error);
    }
  }, [planJson]);

  const handleImport = React.useCallback(async () => {
    if (!importJson.trim()) {
      setImportError(t('invalidJsonPlan'));
      return;
    }

    setIsImporting(true);
    setImportError(null);
    setImportValidation(null);
    setImportProgress(null);

    // PHASE 3.3: Set up progress callback
    ToolDataService.setProgressCallback((progress) => {
      setImportProgress(progress);
    });

    try {
      const result = await WorkflowPlanImporter.importPlan(importJson, nodes, layoutDirection);
      
      if (result.success) {
        // Merge with existing nodes, avoiding duplicates by ID
        const existingNodeIds = new Set(nodes.map(n => n.id));
        const nodesToAdd = result.nodes.filter(n => !existingNodeIds.has(n.id));
        
        // Store positions of imported nodes for potential re-application
        nodesToAdd.forEach(node => {
          if (node.position) {
            importedNodePositionsRef.current.set(node.id, {
              x: node.position.x,
              y: node.position.y,
            });
          }
        });
        
        // Merge with existing edges, avoiding duplicates by ID
        const existingEdgeIds = new Set(edges.map(e => e.id));
        const edgesToAdd = result.edges.filter(e => !existingEdgeIds.has(e.id));
        
        // Update nodes and edges
        // Ensure positions are preserved when merging
        if (nodesToAdd.length > 0) {
          const mergedNodes = [...nodes, ...nodesToAdd];
          // Ensure all positions are valid numbers and preserve them
          const nodesWithValidPositions = mergedNodes.map(node => {
            // For imported nodes, preserve their exact position
            const isImportedNode = nodesToAdd.some(n => n.id === node.id);
            if (isImportedNode && node.position) {
              return {
                ...node,
                position: {
                  x: typeof node.position.x === 'number' && !isNaN(node.position.x) && isFinite(node.position.x) 
                    ? node.position.x 
                    : 0,
                  y: typeof node.position.y === 'number' && !isNaN(node.position.y) && isFinite(node.position.y) 
                    ? node.position.y 
                    : 0,
                },
                // Set positionAbsolute to the same value to prevent ReactFlow from recalculating
                positionAbsolute: {
                  x: typeof node.position.x === 'number' && !isNaN(node.position.x) && isFinite(node.position.x) 
                    ? node.position.x 
                    : 0,
                  y: typeof node.position.y === 'number' && !isNaN(node.position.y) && isFinite(node.position.y) 
                    ? node.position.y 
                    : 0,
                },
              };
            }
            // For existing nodes, preserve their current position and positionAbsolute
            // This prevents ReactFlow from recalculating positions when new nodes are added
            const existingPosition = node.positionAbsolute || node.position;
            if (existingPosition && 
                typeof existingPosition.x === 'number' && !isNaN(existingPosition.x) && isFinite(existingPosition.x) &&
                typeof existingPosition.y === 'number' && !isNaN(existingPosition.y) && isFinite(existingPosition.y)) {
              return {
                ...node,
                position: {
                  x: existingPosition.x,
                  y: existingPosition.y,
                },
                positionAbsolute: {
                  x: existingPosition.x,
                  y: existingPosition.y,
                },
              };
            }
            // If no valid position, return node as-is
            return node;
          });
          onNodesChange(nodesWithValidPositions);
          
          // Trigger position fix check after ReactFlow renders
          shouldFixPositionsRef.current = true;
        }
        if (edgesToAdd.length > 0) {
          onEdgesChange([...edges, ...edgesToAdd]);
        }
        
        setImportValidation(result.validation);
        
        // Show success message
        if (result.validation.isValid) {
          setIsImportOpen(false);
          setImportJson('');
        } else {
          // Keep modal open to show validation errors
          setImportError(InputValidationService.getValidationSummary(result.validation));
        }
      } else {
        setImportError(result.error || 'Import failed');
        setImportValidation(result.validation);
      }
    } catch (error) {
      setImportError(error instanceof Error ? error.message : 'Unknown error during import');
    } finally {
      setIsImporting(false);
      setImportProgress(null);
      // PHASE 3.3: Clean up progress callback
      ToolDataService.setProgressCallback(null);
    }
  }, [importJson, nodes, edges, onNodesChange, onEdgesChange, layoutDirection]);

  return (
    <>
      <div className="flex flex-col gap-2">
        <Button
          onClick={generatePlan}
          variant="default"
          size="sm"
          className="w-full flex items-center justify-center gap-2"
          title={t('generatePlan')}
        >
          <FileJson className="h-4 w-4" />
          {t('generatePlan')}
        </Button>
        {!readOnly && (
          <Button
            onClick={() => setIsImportOpen(true)}
            variant="outline"
            size="sm"
            className="w-full flex items-center justify-center gap-2"
            title={t('importPlan')}
          >
            <Upload className="h-4 w-4" />
            {t('importPlan')}
          </Button>
        )}
      </div>

      {isOpen && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setIsOpen(false)}
        >
          <div
            className="max-w-4xl w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Header */}
            <div className="px-8 pt-8 pb-4 flex items-start justify-between">
              <div>
                <h3 className="text-lg font-semibold text-theme-primary flex items-center gap-2">
                  <FileJson className="h-5 w-5 text-theme-secondary" />
                  {t('planJsonTitle')}
                </h3>
                <p className="text-sm text-theme-secondary mt-1">
                  {t('planJsonDescription')}
                </p>
              </div>
              <Button
                variant="ghost"
                size="icon"
                className="h-8 w-8 rounded-full shrink-0"
                onClick={() => setIsOpen(false)}
              >
                <X className="h-4 w-4" />
              </Button>
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto px-8 pb-4 min-h-0">
              <Textarea
                value={planJson}
                readOnly
                className="min-h-[400px] font-mono text-sm"
                style={{ resize: 'none' }}
              />
            </div>

            {/* Footer */}
            <div className="px-8 py-4 border-t border-theme flex justify-end">
              <Button
                onClick={copyToClipboard}
                variant="outline"
                className="flex items-center gap-2"
              >
                {copied ? (
                  <>
                    <Check className="h-4 w-4" />
                    {t('copied')}
                  </>
                ) : (
                  <>
                    <Copy className="h-4 w-4" />
                    {t('copy')}
                  </>
                )}
              </Button>
            </div>
          </div>
        </div>,
        document.body,
      )}

      {/* Import Modal */}
      {isImportOpen && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => {
            setIsImportOpen(false);
            setImportJson('');
            setImportError(null);
            setImportValidation(null);
          }}
        >
          <div
            className="max-w-4xl w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Header */}
            <div className="px-8 pt-8 pb-4 flex items-start justify-between">
              <div>
                <h3 className="text-lg font-semibold text-theme-primary flex items-center gap-2">
                  <Upload className="h-5 w-5 text-theme-secondary" />
                  {t('importPlan')}
                </h3>
                <p className="text-sm text-theme-secondary mt-1">
                  {t('importDescription')}
                </p>
              </div>
              <Button
                variant="ghost"
                size="icon"
                className="h-8 w-8 rounded-full shrink-0"
                onClick={() => {
                  setIsImportOpen(false);
                  setImportJson('');
                  setImportError(null);
                  setImportValidation(null);
                }}
              >
                <X className="h-4 w-4" />
              </Button>
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto px-8 pb-4 space-y-4 min-h-0">
              {/* Error alert */}
              {importError && (
                <div className="flex items-start gap-2 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl">
                  <AlertCircle className="h-4 w-4 text-red-600 dark:text-red-400 shrink-0 mt-0.5" />
                  <div className="flex-1">
                    <p className="text-sm font-medium text-red-800 dark:text-red-200">{t('error')}</p>
                    <p className="text-sm text-red-700 dark:text-red-300">{importError}</p>
                  </div>
                </div>
              )}

              {/* Validation warnings */}
              {importValidation && !importValidation.isValid && importValidation.errors.length > 0 && (
                <div className="flex flex-col gap-2 p-3 bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-xl">
                  <div className="flex items-center gap-2">
                    <AlertCircle className="h-4 w-4 text-yellow-600 dark:text-yellow-400 shrink-0" />
                    <p className="text-sm font-medium text-yellow-800 dark:text-yellow-200">
                      {t('validationErrors')} ({importValidation.errors.length})
                    </p>
                  </div>
                  <div className="max-h-32 overflow-y-auto">
                    <ul className="text-xs text-yellow-700 dark:text-yellow-300 list-disc list-inside space-y-1">
                      {importValidation.errors.slice(0, 5).map((error: any, index: number) => (
                        <li key={index}>
                          {error.nodeLabel}: {error.message}
                        </li>
                      ))}
                      {importValidation.errors.length > 5 && (
                        <li>... and {importValidation.errors.length - 5} more</li>
                      )}
                    </ul>
                  </div>
                </div>
              )}

              <Textarea
                value={importJson}
                onChange={(e) => {
                  setImportJson(e.target.value);
                  setImportError(null);
                  setImportValidation(null);
                }}
                placeholder={t('pastePlaceholder')}
                className="min-h-[400px] font-mono text-sm"
                style={{ resize: 'none' }}
              />

              {/* Progress indicator */}
              {isImporting && importProgress && importProgress.total > 0 && (
                <div className="flex flex-col gap-2 p-3 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-xl">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-blue-700 dark:text-blue-300">
                      {t('loadingTools', { completed: importProgress.completed, total: importProgress.total })}
                    </span>
                    <span className="text-blue-600 dark:text-blue-400 text-xs">
                      {Math.round((importProgress.completed / importProgress.total) * 100)}%
                    </span>
                  </div>
                  <div className="w-full bg-blue-200 dark:bg-blue-800 rounded-full h-2">
                    <div
                      className="bg-blue-600 dark:bg-blue-400 h-2 rounded-full transition-all duration-300"
                      style={{ width: `${(importProgress.completed / importProgress.total) * 100}%` }}
                    />
                  </div>
                  <span className="text-xs text-blue-500 dark:text-blue-400 truncate">
                    {importProgress.current}
                  </span>
                </div>
              )}
            </div>

            {/* Footer */}
            <div className="px-8 py-4 border-t border-theme flex justify-end">
              <Button
                onClick={handleImport}
                disabled={isImporting || !importJson.trim()}
              >
                {isImporting ? t('importing') : t('importPlan')}
              </Button>
            </div>
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}

