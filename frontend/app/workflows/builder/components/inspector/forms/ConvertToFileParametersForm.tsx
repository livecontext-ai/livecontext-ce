'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { ConnectionProps } from '../ExpressionField';
import type { BuilderNodeData } from '../../../types';
import { InfoPopover } from '@/components/ui/info-popover';

interface ConvertToFileParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for ConvertToFile node parameters.
 * Configures output file format, file name, and format-specific options.
 */
export function ConvertToFileParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: ConvertToFileParametersFormProps) {
  const t = useTranslations('workflowBuilder.convertToFileNode');

  const value: string = (data as any).convertValue ?? '';
  const format: string = (data as any).convertFormat ?? 'csv';
  const fileName: string = (data as any).convertFileName ?? '';
  const delimiter: string = (data as any).convertDelimiter ?? ',';
  const includeHeaders: string = (data as any).convertIncludeHeaders ?? 'yes';

  const handleFormatChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      convertFormat: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleFileNameChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      convertFileName: event.target.value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleDelimiterChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      convertDelimiter: event.target.value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleIncludeHeadersChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      convertIncludeHeaders: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
        <InfoPopover label={t('title')} size="sm" side="right" align="start">
          <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
            <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
            <p>{t('infoDescription')}</p>
          </div>
        </InfoPopover>
      </div>

      {/* Value (data to convert) */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('value')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={value}
          onChange={(val) => onUpdate({ ...data, convertValue: val } as BuilderNodeData)}
          placeholder={t('valuePlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ convertValue: value })}
          handleId={`convert-value-${node.id}`}
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

      {/* Format */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('format')}</span>
        <Select value={format} onValueChange={handleFormatChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="csv">{t('formats.csv')}</SelectItem>
            <SelectItem value="xlsx">{t('formats.xlsx')}</SelectItem>
            <SelectItem value="json">{t('formats.json')}</SelectItem>
            <SelectItem value="txt">{t('formats.txt')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* File Name */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('fileName')}</span>
        <Input
          type="text"
          value={fileName}
          onChange={handleFileNameChange}
          className="w-full"
          placeholder={t('fileNamePlaceholder')}
          readOnly={isRunMode}
        />
      </div>

      {/* Delimiter (CSV only) */}
      {format === 'csv' && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('delimiter')}</span>
          <Input
            type="text"
            value={delimiter}
            onChange={handleDelimiterChange}
            className="w-full"
            placeholder={t('delimiterPlaceholder')}
            readOnly={isRunMode}
          />
          <p className="text-sm text-slate-400 dark:text-slate-500">
            {t('delimiterHelp')}
          </p>
        </div>
      )}

      {/* Include Headers (CSV only) */}
      {format === 'csv' && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('includeHeaders')}</span>
          <Select value={includeHeaders} onValueChange={handleIncludeHeadersChange} disabled={isRunMode}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="yes">Yes</SelectItem>
              <SelectItem value="no">No</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )}
    </div>
  );
}
