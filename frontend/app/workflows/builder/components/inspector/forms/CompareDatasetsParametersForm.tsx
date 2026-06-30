'use client';

import * as React from 'react';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { ConnectionProps } from '../ExpressionField';
import type { BuilderNodeData } from '../../../types';

interface MatchField {
  id: string;
  field: string;
}

interface CompareDatasetsParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Generate a unique ID for a new match field
 */
function generateFieldId(): string {
  return `match_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form component for Compare Datasets node parameters.
 * Configures two dataset inputs, match fields, and return flags.
 */
export function CompareDatasetsParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: CompareDatasetsParametersFormProps) {
  const t = useTranslations('workflowBuilder.compareDatasetsNode');

  const inputA: string = (data as any).compareInputA ?? '';
  const inputB: string = (data as any).compareInputB ?? '';
  const returnMatched: boolean = (data as any).compareReturnMatched ?? true;
  const returnOnlyA: boolean = (data as any).compareReturnOnlyA ?? false;
  const returnOnlyB: boolean = (data as any).compareReturnOnlyB ?? false;

  // Get match fields from node data, initialize with one empty field if none exist
  const matchFields: MatchField[] = React.useMemo(() => {
    const existingFields = (data as any).compareMatchFields as MatchField[] | undefined;
    if (existingFields && existingFields.length > 0) {
      return existingFields;
    }
    return [{ id: generateFieldId(), field: '' }];
  }, [(data as any).compareMatchFields]);

  const canDelete = matchFields.length > 1;

  const handleAddField = React.useCallback(() => {
    if (isRunMode) return;
    const newField: MatchField = {
      id: generateFieldId(),
      field: '',
    };
    const updatedFields = [...matchFields, newField];
    onUpdate({
      ...data,
      compareMatchFields: updatedFields,
    } as BuilderNodeData);
  }, [data, matchFields, isRunMode, onUpdate]);

  const handleDeleteField = React.useCallback((fieldId: string) => {
    if (isRunMode || matchFields.length <= 1) return;
    const updatedFields = matchFields.filter(f => f.id !== fieldId);
    onUpdate({
      ...data,
      compareMatchFields: updatedFields,
    } as BuilderNodeData);
  }, [data, matchFields, isRunMode, onUpdate]);

  const handleFieldNameChange = React.useCallback((fieldId: string, newName: string) => {
    if (isRunMode) return;
    const updatedFields = matchFields.map(f =>
      f.id === fieldId ? { ...f, field: newName } : f
    );
    onUpdate({
      ...data,
      compareMatchFields: updatedFields,
    } as BuilderNodeData);
  }, [data, matchFields, isRunMode, onUpdate]);

  const handleReturnMatchedChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      compareReturnMatched: value === 'true',
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleReturnOnlyAChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      compareReturnOnlyA: value === 'true',
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleReturnOnlyBChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      compareReturnOnlyB: value === 'true',
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
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

      {/* Dataset A */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('inputA')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={inputA}
          onChange={(val) => onUpdate({ ...data, compareInputA: val } as BuilderNodeData)}
          placeholder={t('inputAPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ compareInputA: inputA })}
          handleId={`compare-input-a-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
          isRequired={true}
        />
      </div>

      {/* Dataset B */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('inputB')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={inputB}
          onChange={(val) => onUpdate({ ...data, compareInputB: val } as BuilderNodeData)}
          placeholder={t('inputBPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ compareInputB: inputB })}
          handleId={`compare-input-b-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
          isRequired={true}
        />
      </div>

      {/* Match Fields */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('matchFields')}</span>
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
            title={t('addMatchField')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>
      <div className="space-y-3">
        {matchFields.map((matchField) => (
          <div key={matchField.id} className="flex items-center gap-2">
            <Input
              value={matchField.field}
              onChange={(e) => handleFieldNameChange(matchField.id, e.target.value)}
              className="flex-1 min-w-0 text-sm"
              placeholder={t('matchFieldPlaceholder')}
              readOnly={isRunMode}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
              onClick={(e) => {
                e.stopPropagation();
                handleDeleteField(matchField.id);
              }}
              disabled={!canDelete || isRunMode}
              title={t('addMatchField')}
            >
              <Trash2 className="h-3 w-3" />
            </Button>
          </div>
        ))}
        <p className="text-sm text-slate-400 dark:text-slate-500">
          {t('matchFieldsHelp')}
        </p>
      </div>

      {/* Return Flags */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('returnFlags')}</span>
      </div>

      {/* Return Matched */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('returnMatched')}</span>
        <Select value={String(returnMatched)} onValueChange={handleReturnMatchedChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="true">Yes</SelectItem>
            <SelectItem value="false">No</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Return Only in A */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('returnOnlyA')}</span>
        <Select value={String(returnOnlyA)} onValueChange={handleReturnOnlyAChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="true">Yes</SelectItem>
            <SelectItem value="false">No</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Return Only in B */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('returnOnlyB')}</span>
        <Select value={String(returnOnlyB)} onValueChange={handleReturnOnlyBChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="true">Yes</SelectItem>
            <SelectItem value="false">No</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
