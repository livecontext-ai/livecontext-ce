'use client';

import * as React from 'react';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

interface SetAssignment {
  id: string;
  name: string;
  value: string;
  type: 'string' | 'number' | 'boolean' | 'json' | 'auto';
}

interface SetParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

function generateId(): string {
  return `set_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form for the Set / Edit Fields node - no-code field assignment.
 * Every value uses ExpressionEditor so users can insert variable references
 * via the handle/picker system.
 */
export function SetParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: SetParametersFormProps) {
  const t = useTranslations('workflowBuilder.setNode');
  const tf = useTranslations('workflowBuilder.forms');

  const inputExpression: string = (data as any).setInput ?? '';
  const keepOnlySet: boolean = (data as any).setKeepOnlySet ?? false;
  const assignments: SetAssignment[] = React.useMemo(() => {
    const raw = (data as any).setAssignments;
    if (Array.isArray(raw) && raw.length > 0) {
      return raw.map((a: any) => ({
        id: a.id ?? generateId(),
        name: a.name ?? '',
        value: a.value ?? '',
        type: a.type ?? 'auto',
      }));
    }
    return [{ id: generateId(), name: '', value: '', type: 'auto' }];
  }, [data]);

  const canDelete = assignments.length > 1;

  const updateAssignments = React.useCallback(
    (next: SetAssignment[]) => {
      if (isRunMode) return;
      onUpdate({ ...data, setAssignments: next } as BuilderNodeData);
    },
    [data, isRunMode, onUpdate],
  );

  const handleAddAssignment = React.useCallback(() => {
    if (isRunMode) return;
    updateAssignments([
      ...assignments,
      { id: generateId(), name: '', value: '', type: 'auto' },
    ]);
  }, [assignments, isRunMode, updateAssignments]);

  const handleRemove = React.useCallback(
    (id: string) => {
      if (isRunMode || assignments.length <= 1) return;
      updateAssignments(assignments.filter((a) => a.id !== id));
    },
    [assignments, isRunMode, updateAssignments],
  );

  const handleFieldChange = React.useCallback(
    (id: string, field: keyof SetAssignment, value: string) => {
      if (isRunMode) return;
      updateAssignments(
        assignments.map((a) => (a.id === id ? { ...a, [field]: value } : a)),
      );
    },
    [assignments, isRunMode, updateAssignments],
  );

  const handleKeepOnlyChange = React.useCallback(
    (value: string) => {
      if (isRunMode) return;
      onUpdate({ ...data, setKeepOnlySet: value === 'true' } as BuilderNodeData);
    },
    [data, isRunMode, onUpdate],
  );

  return (
    <div className="space-y-4 pt-2">
      {/* Input data source */}
      <div className="space-y-1">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('inputLabel')}
          </span>
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
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
                <p>{t('infoDescription')}</p>
              </div>
            </PopoverContent>
          </Popover>
        </div>
        <ExpressionEditor
          value={inputExpression}
          onChange={(val) => onUpdate({ ...data, setInput: val } as BuilderNodeData)}
          placeholder={t('inputPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ setInput: inputExpression })}
          handleId={`set-input-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
        />
      </div>

      {/* keepOnlySet */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('keepOnlySetLabel')}
        </span>
        <Select
          value={String(keepOnlySet)}
          onValueChange={handleKeepOnlyChange}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="false">{t('mergeWithInput')}</SelectItem>
            <SelectItem value="true">{t('onlySetFields')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Assignments header + add */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('fieldsLabel')}
        </span>
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddAssignment();
            }}
            title={tf('addField')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      {/* Assignment rows */}
      <div className="space-y-3">
        {assignments.map((assignment) => (
          <div
            key={assignment.id}
            className="flex flex-col gap-2 relative rounded-lg border border-slate-200 dark:border-slate-700 p-3"
          >
            {/* Name */}
            <div className="space-y-1">
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('namePlaceholder')}
              </span>
              <Input
                value={assignment.name}
                onChange={(e) => handleFieldChange(assignment.id, 'name', e.target.value)}
                placeholder={t('namePlaceholder')}
                className="w-full text-sm"
                readOnly={isRunMode}
              />
            </div>

            {/* Value (ExpressionEditor - variable picker!) */}
            <div className="space-y-1">
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('valuePlaceholder')}
              </span>
              <ExpressionEditor
                value={assignment.value}
                onChange={(val) => handleFieldChange(assignment.id, 'value', val)}
                placeholder={t('valuePlaceholder')}
                className="w-full"
                unknownVariables={findUnknownVariables({ [`setAssign_${assignment.id}`]: assignment.value })}
                handleId={`set-assign-${assignment.id}-${node.id}`}
                connections={connectionProps.connections}
                onHandleClick={connectionProps.handleHandleClick}
                draggingFromHandle={connectionProps.draggingFromHandle}
                onHandleMouseDown={connectionProps.handleHandleMouseDown}
                onHandleMouseUp={connectionProps.handleHandleMouseUp}
                hoveredTargetHandle={connectionProps.hoveredTargetHandle}
                onSetHandleRef={connectionProps.handleSetHandleRef}
                readOnly={isRunMode}
              />
            </div>

            {/* Type */}
            <div className="space-y-1">
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('typeLabel')}
              </span>
              <Select
                value={assignment.type}
                onValueChange={(v) => handleFieldChange(assignment.id, 'type', v)}
                disabled={isRunMode}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="auto">auto</SelectItem>
                  <SelectItem value="string">string</SelectItem>
                  <SelectItem value="number">number</SelectItem>
                  <SelectItem value="boolean">boolean</SelectItem>
                  <SelectItem value="json">json</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {/* Delete button */}
            {!isRunMode && (
              <div className="flex justify-end">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleRemove(assignment.id);
                  }}
                  disabled={!canDelete}
                  title={canDelete ? tf('removeField') : undefined}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
