'use client';

import * as React from 'react';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useEdges, useNodes } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import { createDefaultMergeInputs, type MergeInputRow } from '../../nodes/MergeNode';

interface MergeParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Form component for Merge node parameters.
 * Shows connected source node labels (non-editable) with add/delete handles.
 */
export function MergeParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
}: MergeParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  // Get edges and nodes to find connected sources
  const edges = useEdges();
  const allNodes = useNodes();

  // Get merge inputs
  const inputs: MergeInputRow[] = React.useMemo(() => {
    const existingInputs = data.mergeInputs as MergeInputRow[] | undefined;
    if (existingInputs && existingInputs.length > 0) {
      return existingInputs;
    }
    return createDefaultMergeInputs(data.id);
  }, [data.mergeInputs, data.id]);

  // Map each input handle to its connected source node label
  const inputLabels = React.useMemo(() => {
    const labelMap = new Map<string, string>();
    inputs.forEach((input: MergeInputRow) => {
      const edge = edges.find(e => e.target === node.id && e.targetHandle === input.id);
      if (edge) {
        const sourceNode = allNodes.find(n => n.id === edge.source);
        const sourceData = sourceNode?.data as BuilderNodeData | undefined;
        if (sourceData?.label) {
          labelMap.set(input.id, sourceData.label);
        }
      }
    });
    return labelMap;
  }, [inputs, edges, allNodes, node.id]);

  // Minimum 2 inputs required for a merge
  const canDelete = inputs.length > 2;

  const handleAddInput = React.useCallback(() => {
    if (isRunMode) return;
    const newInput: MergeInputRow = {
      id: `${data.id}-input-${inputs.length + 1}`,
      label: '',
    };
    const updatedInputs = [...inputs, newInput];
    onUpdate({
      ...data,
      mergeInputs: updatedInputs,
    } as BuilderNodeData);
  }, [data, inputs, isRunMode, onUpdate]);

  const handleDeleteInput = React.useCallback((inputId: string) => {
    if (isRunMode || inputs.length <= 2) return;
    const updatedInputs = inputs.filter(i => i.id !== inputId);
    onUpdate({
      ...data,
      mergeInputs: updatedInputs,
    } as BuilderNodeData);
  }, [data, inputs, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Input handles header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('merge.inputHandles')}</span>
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
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('merge.title')}</p>
                <p>{t('merge.description')}</p>
                <ul className="list-disc list-inside space-y-1 text-xs">
                  <li>{t('merge.hint1')}</li>
                  <li>{t('merge.hint2')}</li>
                  <li>{t('merge.hint3')}</li>
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
              handleAddInput();
            }}
            title={t('merge.addHandle')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      {/* Input handles list - show connected source labels (non-editable) */}
      <div className="space-y-2">
        {inputs.map((input, index) => {
          const connectedLabel = inputLabels.get(input.id);
          return (
            <div key={input.id} className="flex items-center gap-2">
              <div className="w-6 h-6 rounded-full bg-slate-200 dark:bg-slate-700 flex items-center justify-center flex-shrink-0">
                <span className="text-xs font-medium text-slate-500 dark:text-slate-400">{index + 1}</span>
              </div>
              <div className={`flex-1 h-8 px-3 flex items-center rounded-md border text-sm ${
                connectedLabel
                  ? 'bg-slate-50 dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300'
                  : 'bg-slate-50 dark:bg-slate-800 border-dashed border-slate-300 dark:border-slate-600 text-slate-400 dark:text-slate-500 italic'
              }`}>
                {connectedLabel || t('merge.notConnected')}
              </div>
              {!isRunMode && (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteInput(input.id);
                  }}
                  disabled={!canDelete}
                  title={canDelete ? t('merge.deleteInput') : t('merge.minInputs')}
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
