'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

interface CompressionParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for Compression node parameters.
 * Configures operation (compress/decompress), format, and input data.
 */
export function CompressionParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: CompressionParametersFormProps) {
  const t = useTranslations('workflowBuilder.compressionNode');

  const operation: string = (data as any).compressionOperation ?? 'compress';
  const format: string = (data as any).compressionFormat ?? 'gzip';
  const input: string = (data as any).compressionInput ?? '';

  const handleOperationChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      compressionOperation: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleFormatChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      compressionFormat: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleInputChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      compressionInput: value,
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

      {/* Operation */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('operation')}</span>
        <Select value={operation} onValueChange={handleOperationChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="compress">{t('operations.compress')}</SelectItem>
            <SelectItem value="decompress">{t('operations.decompress')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Format */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('format')}</span>
        <Select value={format} onValueChange={handleFormatChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="gzip">{t('formats.gzip')}</SelectItem>
            <SelectItem value="zip">{t('formats.zip')}</SelectItem>
            <SelectItem value="base64">{t('formats.base64')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Input */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('input')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={input}
          onChange={handleInputChange}
          placeholder={t('inputPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ compressionInput: input })}
          handleId={`compression-input-${node.id}`}
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
    </div>
  );
}
