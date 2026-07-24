'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';

interface SubWorkflowParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Form component for SubWorkflow node parameters.
 *
 * The backend `SubWorkflowConfig.inputMapping` is a SINGLE SpEL expression string that
 * resolves to the object handed to the sub-workflow's trigger (e.g. `{{core:build.output}}`
 * or `{{trigger.output.payload}}`), NOT a list of key/value pairs. Plans and MCP-built nodes
 * therefore carry a string here; rendering it as an array crashed the inspector, so this form
 * edits it as one expression field, matching the contract.
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

  // Coerce to string: back-compat with any legacy array-shaped value (never a valid backend
  // config) so the field never receives a non-string and never crashes.
  const rawMapping = (data as any).subWorkflowInputMapping;
  const inputMapping: string = typeof rawMapping === 'string' ? rawMapping : '';
  const timeout: number = (data as any).subWorkflowTimeoutSeconds ?? (data as any).subWorkflowTimeout ?? 300;
  const maxDepth: number = (data as any).subWorkflowMaxDepth ?? 3;

  const handleWorkflowIdChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    onUpdate({ ...data, subWorkflowId: event.target.value } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleInputMappingChange = React.useCallback((event: React.ChangeEvent<HTMLTextAreaElement>) => {
    if (isRunMode) return;
    onUpdate({ ...data, subWorkflowInputMapping: event.target.value } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleTimeoutChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({ ...data, subWorkflowTimeoutSeconds: 1 } as BuilderNodeData);
      return;
    }
    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) return;
    onUpdate({ ...data, subWorkflowTimeoutSeconds: numValue } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleMaxDepthChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({ ...data, subWorkflowMaxDepth: 1 } as BuilderNodeData);
      return;
    }
    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) return;
    onUpdate({ ...data, subWorkflowMaxDepth: numValue } as BuilderNodeData);
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

      {/* Input mapping (single SpEL expression) */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('inputMapping')}</span>
        <Textarea
          value={inputMapping}
          onChange={handleInputMappingChange}
          className="w-full font-mono text-sm min-h-[64px]"
          placeholder={t('inputMappingPlaceholder')}
          readOnly={isRunMode}
        />
        <p className="text-sm text-slate-400 dark:text-slate-500">{t('inputMappingHelp')}</p>
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
