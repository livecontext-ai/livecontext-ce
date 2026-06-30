'use client';

import * as React from 'react';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';

interface InputMapping {
  id: string;
  key: string;
  value: string;
}

interface SubWorkflowParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Generate a unique ID for a new input mapping entry
 */
function generateMappingId(): string {
  return `mapping_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form component for SubWorkflow node parameters.
 * Configures the workflow ID, input mappings, timeout, and max depth.
 */
export function SubWorkflowParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
}: SubWorkflowParametersFormProps) {
  const t = useTranslations('workflowBuilder.subWorkflowNode');

  const workflowId: string = (data as any).subWorkflowId ?? (data as any).workflowData?.workflowId ?? '';
  const workflowName: string = (data as any).workflowData?.workflowName ?? '';

  // Sync workflowData.workflowId → subWorkflowId on first render if needed
  React.useEffect(() => {
    const wfData = (data as any).workflowData;
    if (wfData?.workflowId && !(data as any).subWorkflowId) {
      onUpdate({ ...data, subWorkflowId: wfData.workflowId } as BuilderNodeData);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const inputMapping: InputMapping[] = React.useMemo(() => {
    const existing = (data as any).subWorkflowInputMapping as InputMapping[] | undefined;
    if (existing && existing.length > 0) {
      return existing;
    }
    return [];
  }, [(data as any).subWorkflowInputMapping]);
  const timeout: number = (data as any).subWorkflowTimeout ?? 300;
  const maxDepth: number = (data as any).subWorkflowMaxDepth ?? 3;

  const handleWorkflowIdChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      subWorkflowId: event.target.value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleAddMapping = React.useCallback(() => {
    if (isRunMode) return;
    const newMapping: InputMapping = {
      id: generateMappingId(),
      key: '',
      value: '',
    };
    const updatedMappings = [...inputMapping, newMapping];
    onUpdate({
      ...data,
      subWorkflowInputMapping: updatedMappings,
    } as BuilderNodeData);
  }, [data, inputMapping, isRunMode, onUpdate]);

  const handleDeleteMapping = React.useCallback((mappingId: string) => {
    if (isRunMode) return;
    const updatedMappings = inputMapping.filter(m => m.id !== mappingId);
    onUpdate({
      ...data,
      subWorkflowInputMapping: updatedMappings,
    } as BuilderNodeData);
  }, [data, inputMapping, isRunMode, onUpdate]);

  const handleMappingKeyChange = React.useCallback((mappingId: string, newKey: string) => {
    if (isRunMode) return;
    const updatedMappings = inputMapping.map(m =>
      m.id === mappingId ? { ...m, key: newKey } : m
    );
    onUpdate({
      ...data,
      subWorkflowInputMapping: updatedMappings,
    } as BuilderNodeData);
  }, [data, inputMapping, isRunMode, onUpdate]);

  const handleMappingValueChange = React.useCallback((mappingId: string, newValue: string) => {
    if (isRunMode) return;
    const updatedMappings = inputMapping.map(m =>
      m.id === mappingId ? { ...m, value: newValue } : m
    );
    onUpdate({
      ...data,
      subWorkflowInputMapping: updatedMappings,
    } as BuilderNodeData);
  }, [data, inputMapping, isRunMode, onUpdate]);

  const handleTimeoutChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({
        ...data,
        subWorkflowTimeout: 1,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) {
      return;
    }

    onUpdate({
      ...data,
      subWorkflowTimeout: numValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleMaxDepthChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({
        ...data,
        subWorkflowMaxDepth: 1,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) {
      return;
    }

    onUpdate({
      ...data,
      subWorkflowMaxDepth: numValue,
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

      {/* Workflow ID */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('workflowId')}</span>
        {workflowName ? (
          <div className="flex items-center gap-2 px-3 py-2 bg-slate-50 dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
            <span className="text-sm text-theme-primary truncate flex-1">{workflowName}</span>
            <span className="text-xs text-theme-muted font-mono">{workflowId.slice(0, 8)}</span>
          </div>
        ) : (
          <Input
            value={workflowId}
            onChange={handleWorkflowIdChange}
            className="w-full"
            placeholder={t('workflowIdPlaceholder')}
            readOnly={isRunMode}
          />
        )}
      </div>

      {/* Input Mapping */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('inputMapping')}</span>
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
              title={t('addMapping')}
            >
              <Plus className="h-3 w-3" />
            </Button>
          )}
        </div>
        <div className="space-y-3">
          {inputMapping.map((mapping) => (
            <div key={mapping.id} className="flex items-center gap-2">
              <Input
                value={mapping.key}
                onChange={(e) => handleMappingKeyChange(mapping.id, e.target.value)}
                className="flex-1 min-w-0 text-sm"
                placeholder={t('mappingKeyPlaceholder')}
                readOnly={isRunMode}
              />
              <Input
                value={mapping.value}
                onChange={(e) => handleMappingValueChange(mapping.id, e.target.value)}
                className="flex-1 min-w-0 text-sm"
                placeholder={t('mappingValuePlaceholder')}
                readOnly={isRunMode}
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30"
                onClick={(e) => {
                  e.stopPropagation();
                  handleDeleteMapping(mapping.id);
                }}
                disabled={isRunMode}
                title={t('removeMapping')}
              >
                <Trash2 className="h-3 w-3" />
              </Button>
            </div>
          ))}
        </div>
      </div>

      {/* Timeout */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timeout')}</span>
        <Input
          type="number"
          min="1"
          value={timeout}
          onChange={handleTimeoutChange}
          className="w-full"
          placeholder={t('timeoutPlaceholder')}
          readOnly={isRunMode}
        />
        <p className="text-sm text-slate-400 dark:text-slate-500">
          {t('timeoutHelp')}
        </p>
      </div>

      {/* Max Depth */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('maxDepth')}</span>
        <Input
          type="number"
          min="1"
          value={maxDepth}
          onChange={handleMaxDepthChange}
          className="w-full"
          placeholder={t('maxDepthPlaceholder')}
          readOnly={isRunMode}
        />
        <p className="text-sm text-slate-400 dark:text-slate-500">
          {t('maxDepthHelp')}
        </p>
      </div>
    </div>
  );
}
