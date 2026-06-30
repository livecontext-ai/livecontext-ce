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

interface DedupField {
  id: string;
  field: string;
}

interface RemoveDuplicatesParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Generate a unique ID for a new dedup field
 */
function generateFieldId(): string {
  return `dedup_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form component for RemoveDuplicates node parameters.
 * Allows defining one or more field names for deduplication and a keep strategy (first/last).
 */
export function RemoveDuplicatesParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: RemoveDuplicatesParametersFormProps) {
  const t = useTranslations('workflowBuilder.removeDuplicatesNode');
  const tf = useTranslations('workflowBuilder.forms');

  // Get dedup fields from node data, initialize with one empty field if none exist
  const fields: DedupField[] = React.useMemo(() => {
    const existingFields = (data as any).dedupFields as DedupField[] | undefined;
    if (existingFields && existingFields.length > 0) {
      return existingFields;
    }
    return [{ id: generateFieldId(), field: '' }];
  }, [(data as any).dedupFields]);

  const keep: string = (data as any).dedupKeep ?? 'first';

  const canDelete = fields.length > 1;

  const handleAddField = React.useCallback(() => {
    if (isRunMode) return;
    const newField: DedupField = {
      id: generateFieldId(),
      field: '',
    };
    const updatedFields = [...fields, newField];
    onUpdate({
      ...data,
      dedupFields: updatedFields,
    } as BuilderNodeData);
  }, [data, fields, isRunMode, onUpdate]);

  const handleDeleteField = React.useCallback((fieldId: string) => {
    if (isRunMode || fields.length <= 1) return;
    const updatedFields = fields.filter(f => f.id !== fieldId);
    onUpdate({
      ...data,
      dedupFields: updatedFields,
    } as BuilderNodeData);
  }, [data, fields, isRunMode, onUpdate]);

  const handleFieldNameChange = React.useCallback((fieldId: string, newName: string) => {
    if (isRunMode) return;
    const updatedFields = fields.map(f =>
      f.id === fieldId ? { ...f, field: newName } : f
    );
    onUpdate({
      ...data,
      dedupFields: updatedFields,
    } as BuilderNodeData);
  }, [data, fields, isRunMode, onUpdate]);

  const handleKeepChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dedupKeep: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Input data source */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('inputData')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={(data as any).dedupInput ?? ''}
          onChange={(val) => onUpdate({...data, dedupInput: val} as BuilderNodeData)}
          placeholder={tf('expressionPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ dedupInput: (data as any).dedupInput ?? '' })}
          handleId={`dedup-input-${node.id}`}
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
        {fields.map((dedupField) => (
          <div key={dedupField.id} className="flex items-center gap-2">
            <Input
              value={dedupField.field}
              onChange={(e) => handleFieldNameChange(dedupField.id, e.target.value)}
              className="flex-1 min-w-0 text-sm"
              placeholder={t('fieldPlaceholder')}
              readOnly={isRunMode}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
              onClick={(e) => {
                e.stopPropagation();
                handleDeleteField(dedupField.id);
              }}
              disabled={!canDelete || isRunMode}
              title={canDelete ? t('removeField') : t('noFields')}
            >
              <Trash2 className="h-3 w-3" />
            </Button>
          </div>
        ))}
      </div>

      {/* Keep strategy */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('keep')}</span>
        <Select value={keep} onValueChange={handleKeepChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="first">{t('keepFirst')}</SelectItem>
            <SelectItem value="last">{t('keepLast')}</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
