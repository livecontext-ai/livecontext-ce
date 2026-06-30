'use client';

import * as React from 'react';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';
import { toSnakeCase } from '../../../utils/typeNormalizer';

interface TransformMapping {
  id: string;
  label: string;
  expression: string;
}

interface TransformParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

/**
 * Generate a unique ID for a new mapping
 */
function generateMappingId(): string {
  return `mapping_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form component for Transform node parameters.
 * Displays mappings where each mapping has a label (output name) and an expression.
 * Labels are mirrored to the output.
 */
export function TransformParametersForm({
  node,
  data,
  connections,
  isRunMode = false,
  onUpdate,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: TransformParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  // Get mappings from node data, initialize with one empty mapping if none exist
  const mappings: TransformMapping[] = React.useMemo(() => {
    const existingMappings = (data as any).transformMappings as TransformMapping[] | undefined;
    if (existingMappings && existingMappings.length > 0) {
      // Ensure every mapping has a unique id (imported plans may lack ids)
      return existingMappings.map((m, i) => m.id ? m : { ...m, id: `mapping_imported_${i}` });
    }
    // Default: one empty mapping
    return [{ id: generateMappingId(), label: 'field_1', expression: '' }];
  }, [(data as any).transformMappings]);

  const canDelete = mappings.length > 1;

  const handleAddMapping = React.useCallback(() => {
    if (isRunMode) return;
    const newMapping: TransformMapping = {
      id: generateMappingId(),
      label: `field_${mappings.length + 1}`,
      expression: '',
    };
    const updatedMappings = [...mappings, newMapping];
    onUpdate({
      ...data,
      transformMappings: updatedMappings,
    } as BuilderNodeData);
  }, [data, mappings, isRunMode, onUpdate]);

  const handleDeleteMapping = React.useCallback((mappingId: string) => {
    if (isRunMode || mappings.length <= 1) return;
    const updatedMappings = mappings.filter(m => m.id !== mappingId);
    onUpdate({
      ...data,
      transformMappings: updatedMappings,
    } as BuilderNodeData);
  }, [data, mappings, isRunMode, onUpdate]);

  const handleLabelChange = React.useCallback((mappingId: string, newLabel: string) => {
    if (isRunMode) return;
    const updatedMappings = mappings.map(m =>
      m.id === mappingId ? { ...m, label: newLabel } : m
    );
    onUpdate({
      ...data,
      transformMappings: updatedMappings,
    } as BuilderNodeData);
  }, [data, mappings, isRunMode, onUpdate]);

  const handleExpressionChange = React.useCallback((mappingId: string, newExpression: string) => {
    if (isRunMode) return;
    const updatedMappings = mappings.map(m =>
      m.id === mappingId ? { ...m, expression: newExpression } : m
    );
    onUpdate({
      ...data,
      transformMappings: updatedMappings,
    } as BuilderNodeData);
  }, [data, mappings, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('transform.mappings')}</span>
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
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('transform.title')}</p>
                <p>{t('transform.description')}</p>
                <ul className="list-disc list-inside space-y-1 text-xs">
                  <li>{t('transform.hint1')}</li>
                  <li>{t('transform.hint2')}</li>
                  <li>{t('transform.hint3')}</li>
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
              handleAddMapping();
            }}
            title={t('transform.addMapping')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>
      <div className="space-y-3">
        {mappings.map((mapping, index) => {
          const normalizedLabel = toSnakeCase(mapping.label) || `field_${index + 1}`;

          return (
            <div key={mapping.id} className="flex flex-col gap-2 relative">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2 flex-1 min-w-0">
                  <Input
                    value={mapping.label}
                    onChange={(e) => handleLabelChange(mapping.id, e.target.value)}
                    className="text-sm font-semibold text-slate-500 dark:text-slate-400 h-6 px-2 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none"
                    placeholder={normalizedLabel}
                    readOnly={isRunMode}
                  />
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDeleteMapping(mapping.id);
                    }}
                    disabled={!canDelete || isRunMode}
                    title={canDelete ? t('transform.deleteMapping') : t('transform.minMappings')}
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              </div>
              <ExpressionEditor
                value={mapping.expression}
                onChange={(value) => handleExpressionChange(mapping.id, value)}
                placeholder={t('enterExpression')}
                className="w-full"
                unknownVariables={findUnknownVariables({ [mapping.id]: mapping.expression })}
                handleId={`transform-${mapping.id}-${node.id}`}
                connections={connections}
                onHandleClick={handleHandleClick}
                draggingFromHandle={draggingFromHandle}
                onHandleMouseDown={handleHandleMouseDown}
                onHandleMouseUp={handleHandleMouseUp}
                hoveredTargetHandle={hoveredTargetHandle}
                onSetHandleRef={handleSetHandleRef}
                isRequired={true}
                readOnly={isRunMode}
              />
            </div>
          );
        })}
      </div>
    </div>
  );
}
