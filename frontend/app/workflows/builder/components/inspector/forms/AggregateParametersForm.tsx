'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import { Plus, Trash2, Info } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';
import { toSnakeCase } from '../../../utils/typeNormalizer';

interface AggregateField {
  id: string;
  label: string;
  expression: string;
}

interface AggregateParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  onUpdate: (data: Partial<BuilderNodeData>) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

function generateFieldId(): string {
  return `field_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form component for Aggregate node parameters.
 * Allows user to specify which fields to aggregate from incoming items.
 */
export function AggregateParametersForm({
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
}: AggregateParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  // Get fields from node data, initialize with one empty field if none exist
  const fields: AggregateField[] = React.useMemo(() => {
    const existingFields = (data as any).aggregateFields as AggregateField[] | undefined;
    if (existingFields && existingFields.length > 0) {
      return existingFields;
    }
    // Default: one empty field
    return [{ id: generateFieldId(), label: 'field_1', expression: '' }];
  }, [(data as any).aggregateFields]);

  const canDelete = fields.length > 1;

  const handleAddField = React.useCallback(() => {
    if (isRunMode) return;
    const newField: AggregateField = {
      id: generateFieldId(),
      label: `field_${fields.length + 1}`,
      expression: '',
    };
    const updatedFields = [...fields, newField];
    onUpdate({ aggregateFields: updatedFields } as any);
  }, [fields, isRunMode, onUpdate]);

  const handleDeleteField = React.useCallback((fieldId: string) => {
    if (isRunMode || fields.length <= 1) return;
    const updatedFields = fields.filter(f => f.id !== fieldId);
    onUpdate({ aggregateFields: updatedFields } as any);
  }, [fields, isRunMode, onUpdate]);

  const handleLabelChange = React.useCallback((fieldId: string, newLabel: string) => {
    if (isRunMode) return;
    const updatedFields = fields.map(f =>
      f.id === fieldId ? { ...f, label: newLabel } : f
    );
    onUpdate({ aggregateFields: updatedFields } as any);
  }, [fields, isRunMode, onUpdate]);

  const handleExpressionChange = React.useCallback((fieldId: string, newExpression: string) => {
    if (isRunMode) return;
    const updatedFields = fields.map(f =>
      f.id === fieldId ? { ...f, expression: newExpression } : f
    );
    onUpdate({ aggregateFields: updatedFields } as any);
  }, [fields, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('aggregate.fieldsTitle')}</span>
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
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('aggregate.title')}</p>
                <p>{t('aggregate.description')}</p>
                <ul className="list-disc list-inside space-y-1 text-xs">
                  <li>{t('aggregate.hint1')}</li>
                  <li>{t('aggregate.hint2')}</li>
                  <li>{t('aggregate.hint3')}</li>
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
              handleAddField();
            }}
            title={t('aggregate.addField')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      <div className="space-y-3">
        {fields.map((field, index) => {
          const normalizedLabel = toSnakeCase(field.label) || `field_${index + 1}`;

          return (
            <div key={field.id} className="flex flex-col gap-2 relative">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2 flex-1 min-w-0">
                  <Input
                    value={field.label}
                    onChange={(e) => handleLabelChange(field.id, e.target.value)}
                    className="text-sm font-semibold text-slate-500 dark:text-slate-400 h-6 px-2 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none"
                    placeholder={normalizedLabel}
                    readOnly={isRunMode}
                  />
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  {canDelete && !isRunMode && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="h-6 w-6 text-slate-400 hover:text-red-500"
                      onClick={() => handleDeleteField(field.id)}
                      title={t('aggregate.deleteField')}
                    >
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  )}
                </div>
              </div>
              <ExpressionEditor
                value={field.expression}
                onChange={(value) => handleExpressionChange(field.id, value)}
                placeholder=""
                className="w-full"
                unknownVariables={findUnknownVariables({ [field.id]: field.expression })}
                handleId={`aggregate-field-${field.id}`}
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
