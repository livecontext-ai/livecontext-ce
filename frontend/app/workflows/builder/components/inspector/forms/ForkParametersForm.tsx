'use client';

import * as React from 'react';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useEdges, useNodes } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import { createDefaultForkOutputs } from '../../../types';
import type { ForkOutputRow } from '../../nodes/ForkNode';

interface ForkParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Form component for Fork node parameters.
 * Shows connected target node labels (non-editable) with add/delete handles.
 */
export function ForkParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
}: ForkParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  // Get edges and nodes to find connected targets
  const edges = useEdges();
  const allNodes = useNodes();

  // Get fork outputs
  const outputs: ForkOutputRow[] = React.useMemo(() => {
    const existingOutputs = data.forkOutputs as ForkOutputRow[] | undefined;
    if (existingOutputs && existingOutputs.length > 0) {
      return existingOutputs;
    }
    return createDefaultForkOutputs(data.id);
  }, [data.forkOutputs, data.id]);

  // Map each output handle to its connected target node label
  const outputLabels = React.useMemo(() => {
    const labelMap = new Map<string, string>();
    outputs.forEach((output: ForkOutputRow) => {
      const edge = edges.find(e => e.source === node.id && e.sourceHandle === output.id);
      if (edge) {
        const targetNode = allNodes.find(n => n.id === edge.target);
        const targetData = targetNode?.data as BuilderNodeData | undefined;
        if (targetData?.label) {
          labelMap.set(output.id, targetData.label);
        }
      }
    });
    return labelMap;
  }, [outputs, edges, allNodes, node.id]);

  // Minimum 2 outputs required for a fork
  const canDelete = outputs.length > 2;

  const handleAddOutput = React.useCallback(() => {
    if (isRunMode) return;
    const newOutput: ForkOutputRow = {
      id: `${data.id}-output-${outputs.length + 1}`,
      label: `Branch ${outputs.length + 1}`,
    };
    const updatedOutputs = [...outputs, newOutput];
    onUpdate({
      ...data,
      forkOutputs: updatedOutputs,
    } as BuilderNodeData);
  }, [data, outputs, isRunMode, onUpdate]);

  const handleDeleteOutput = React.useCallback((outputId: string) => {
    if (isRunMode || outputs.length <= 2) return;
    const updatedOutputs = outputs.filter(o => o.id !== outputId);
    onUpdate({
      ...data,
      forkOutputs: updatedOutputs,
    } as BuilderNodeData);
  }, [data, outputs, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Output handles header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('fork.outputBranches')}</span>
          <Popover>
            <PopoverTrigger asChild>
              <button
                type="button"
                className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
              >
                <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
              </button>
            </PopoverTrigger>
            <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
              <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('fork.title')}</p>
                <p>{t('fork.description')}</p>
                <ul className="list-disc list-inside space-y-1 text-xs">
                  <li>{t('fork.hint1')}</li>
                  <li>{t('fork.hint2')}</li>
                  <li>{t('fork.hint3')}</li>
                </ul>
              </div>
            </PopoverContent>
          </Popover>
        </div>
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddOutput();
            }}
            title={t('fork.addBranch')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      {/* Output handles list - show connected target labels (non-editable) */}
      <div className="space-y-2">
        {outputs.map((output, index) => {
          const connectedLabel = outputLabels.get(output.id);
          return (
            <div key={output.id} className="flex items-center gap-2">
              <div className="w-6 h-6 rounded-full bg-indigo-100 dark:bg-indigo-900/30 flex items-center justify-center flex-shrink-0">
                <span className="text-xs font-medium text-indigo-600 dark:text-indigo-400">{index + 1}</span>
              </div>
              <div className={`flex-1 h-8 px-3 flex items-center rounded-md border text-sm ${
                connectedLabel
                  ? 'bg-slate-50 dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300'
                  : 'bg-slate-50 dark:bg-slate-800 border-dashed border-slate-300 dark:border-slate-600 text-slate-400 dark:text-slate-500 italic'
              }`}>
                {connectedLabel || output.label || t('fork.notConnected')}
              </div>
              {!isRunMode && (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteOutput(output.id);
                  }}
                  disabled={!canDelete}
                  title={canDelete ? t('fork.deleteBranch') : t('fork.minBranches')}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
