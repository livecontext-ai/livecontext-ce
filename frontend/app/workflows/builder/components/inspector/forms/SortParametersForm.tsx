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

interface SortField {
  id: string;
  field: string;
  direction: 'asc' | 'desc';
}

interface SortParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Generate a unique ID for a new sort field
 */
function generateFieldId(): string {
  return `sort_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form component for Sort node parameters.
 * Allows defining one or more sort fields with field name and direction (asc/desc).
 */
export function SortParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: SortParametersFormProps) {
  const t = useTranslations('workflowBuilder.sortNode');
  const tf = useTranslations('workflowBuilder.forms');

  // Get sort fields from node data, initialize with one empty field if none exist
  const fields: SortField[] = React.useMemo(() => {
    const existingFields = (data as any).sortFields as SortField[] | undefined;
    if (existingFields && existingFields.length > 0) {
      return existingFields.map((f: any, i: number) => ({
        id: f.id || `sort_${i}_${Date.now()}`,
        field: f.field ?? '',
        direction: f.direction ?? 'asc',
      }));
    }
    return [{ id: generateFieldId(), field: '', direction: 'asc' }];
  }, [(data as any).sortFields]);

  const canDelete = fields.length > 1;

  const handleAddField = React.useCallback(() => {
    if (isRunMode) return;
    const newField: SortField = {
      id: generateFieldId(),
      field: '',
      direction: 'asc',
    };
    const updatedFields = [...fields, newField];
    onUpdate({
      ...data,
      sortFields: updatedFields,
    } as BuilderNodeData);
  }, [data, fields, isRunMode, onUpdate]);

  const handleDeleteField = React.useCallback((fieldId: string) => {
    if (isRunMode || fields.length <= 1) return;
    const updatedFields = fields.filter(f => f.id !== fieldId);
    onUpdate({
      ...data,
      sortFields: updatedFields,
    } as BuilderNodeData);
  }, [data, fields, isRunMode, onUpdate]);

  const handleFieldNameChange = React.useCallback((fieldId: string, newName: string) => {
    if (isRunMode) return;
    const updatedFields = fields.map(f =>
      f.id === fieldId ? { ...f, field: newName } : f
    );
    onUpdate({
      ...data,
      sortFields: updatedFields,
    } as BuilderNodeData);
  }, [data, fields, isRunMode, onUpdate]);

  const handleDirectionChange = React.useCallback((fieldId: string, newDirection: 'asc' | 'desc') => {
    if (isRunMode) return;
    const updatedFields = fields.map(f =>
      f.id === fieldId ? { ...f, direction: newDirection } : f
    );
    onUpdate({
      ...data,
      sortFields: updatedFields,
    } as BuilderNodeData);
  }, [data, fields, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Input data source */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('inputData')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={(data as any).sortInput ?? ''}
          onChange={(val) => onUpdate({...data, sortInput: val} as BuilderNodeData)}
          placeholder={tf('expressionPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ sortInput: (data as any).sortInput ?? '' })}
          handleId={`sort-input-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
        />
        <p className="text-xs text-slate-400">{tf('requiredArrayReference')}</p>
      </div>

      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('fields')}</span>
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
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddField();
            }}
            title={t('addField')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>
      <div className="space-y-3">
        {fields.map((sortField) => (
          <div key={sortField.id} className="flex items-center gap-2">
            <Input
              value={sortField.field}
              onChange={(e) => handleFieldNameChange(sortField.id, e.target.value)}
              className="flex-1 min-w-0 text-sm"
              placeholder={t('fieldPlaceholder')}
              readOnly={isRunMode}
            />
            <Select
              value={sortField.direction}
              onValueChange={(value: 'asc' | 'desc') => handleDirectionChange(sortField.id, value)}
              disabled={isRunMode}
            >
              <SelectTrigger className="w-[140px] text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="asc">{t('directionAsc')}</SelectItem>
                <SelectItem value="desc">{t('directionDesc')}</SelectItem>
              </SelectContent>
            </Select>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
              onClick={(e) => {
                e.stopPropagation();
                handleDeleteField(sortField.id);
              }}
              disabled={!canDelete || isRunMode}
              title={canDelete ? t('removeField') : t('noFields')}
            >
              <Trash2 className="h-3 w-3" />
            </Button>
          </div>
        ))}
      </div>
    </div>
  );
}
