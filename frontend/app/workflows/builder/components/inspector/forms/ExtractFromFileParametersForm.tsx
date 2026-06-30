'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Switch } from '@/components/ui/switch';
import type { ConnectionProps } from '../ExpressionField';
import type { BuilderNodeData } from '../../../types';

interface ExtractFromFileParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

const STRUCTURED_FORMATS = ['csv', 'xlsx', 'json'] as const;
const TEXT_FORMATS = ['pdf', 'html', 'docx', 'txt'] as const;

/**
 * Form component for ExtractFromFile node parameters.
 * Supports structured mode (CSV/XLSX/JSON) and text mode (PDF/HTML/DOCX/TXT) with chunking.
 */
export function ExtractFromFileParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: ExtractFromFileParametersFormProps) {
  const t = useTranslations('workflowBuilder.extractFromFileNode');

  const value: string = (data as any).extractValue ?? '';
  const mode: string = (data as any).extractMode ?? 'structured';
  const format: string = (data as any).extractFormat ?? (mode === 'text' ? 'pdf' : 'csv');
  const delimiter: string = (data as any).extractDelimiter ?? ',';
  const hasHeaders: string = (data as any).extractHasHeaders ?? 'yes';
  const sheetName: string = (data as any).extractSheetName ?? '';
  const chunking: boolean = (data as any).extractChunking ?? false;
  const chunkSize: string = (data as any).extractChunkSize ?? '500';
  const overlap: string = (data as any).extractOverlap ?? '50';
  const chunkingStrategy: string = (data as any).extractChunkingStrategy ?? 'fixed_size';
  const separator: string = (data as any).extractSeparator ?? '\\n\\n';
  const chunkUnit: string = (data as any).extractChunkUnit ?? 'char';
  const isTokenUnit = chunkUnit === 'token';

  const isTextMode = mode === 'text';
  const availableFormats = isTextMode ? TEXT_FORMATS : STRUCTURED_FORMATS;

  const handleUpdate = React.useCallback((fields: Record<string, any>) => {
    if (isRunMode) return;
    onUpdate({ ...data, ...fields } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleModeChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    const defaultFormat = val === 'text' ? 'pdf' : 'csv';
    onUpdate({
      ...data,
      extractMode: val,
      extractFormat: defaultFormat,
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

      {/* Value (file content/URL) */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('value')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={value}
          onChange={(val) => handleUpdate({ extractValue: val })}
          placeholder={t('valuePlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ extractValue: value })}
          handleId={`extract-value-${node.id}`}
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

      {/* Mode */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('mode')}</span>
        <Select value={mode} onValueChange={handleModeChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="structured">{t('modes.structured')}</SelectItem>
            <SelectItem value="text">{t('modes.text')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Format */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('format')}</span>
        <Select value={format} onValueChange={(val) => handleUpdate({ extractFormat: val })} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {availableFormats.map((f) => (
              <SelectItem key={f} value={f}>{t(`formats.${f}`)}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* === Structured mode options === */}
      {!isTextMode && (
        <>
          {/* Delimiter -- only for CSV */}
          {format === 'csv' && (
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('delimiter')}</span>
              <Input
                type="text"
                value={delimiter}
                onChange={(e) => handleUpdate({ extractDelimiter: e.target.value })}
                className="w-full"
                placeholder={t('delimiterPlaceholder')}
                readOnly={isRunMode}
              />
              <p className="text-sm text-slate-400 dark:text-slate-500">
                {t('delimiterHelp')}
              </p>
            </div>
          )}

          {/* Has headers -- for CSV and XLSX */}
          {(format === 'csv' || format === 'xlsx') && (
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('hasHeaders')}</span>
              <Select value={hasHeaders} onValueChange={(val) => handleUpdate({ extractHasHeaders: val })} disabled={isRunMode}>
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

          {/* Sheet name -- only for XLSX */}
          {format === 'xlsx' && (
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('sheetName')}</span>
              <Input
                type="text"
                value={sheetName}
                onChange={(e) => handleUpdate({ extractSheetName: e.target.value })}
                className="w-full"
                placeholder={t('sheetNamePlaceholder')}
                readOnly={isRunMode}
              />
              <p className="text-sm text-slate-400 dark:text-slate-500">
                {t('sheetNameHelp')}
              </p>
            </div>
          )}
        </>
      )}

      {/* === Text mode options === */}
      {isTextMode && (
        <>
          {/* Chunking toggle */}
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chunking')}</span>
              <p className="text-sm text-slate-400 dark:text-slate-500">{t('chunkingHelp')}</p>
            </div>
            <Switch
              checked={chunking}
              onCheckedChange={(checked) => handleUpdate({ extractChunking: checked })}
              disabled={isRunMode}
            />
          </div>

          {/* Chunking options */}
          {chunking && (
            <div className="space-y-3 pl-2 border-l-2 border-slate-200 dark:border-slate-700">
              {/* Strategy */}
              <div className="space-y-1">
                <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chunkingStrategy')}</span>
                <Select value={chunkingStrategy} onValueChange={(val) => handleUpdate({ extractChunkingStrategy: val })} disabled={isRunMode}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="fixed_size">{t('chunkingStrategies.fixed_size')}</SelectItem>
                    <SelectItem value="recursive">{t('chunkingStrategies.recursive')}</SelectItem>
                    <SelectItem value="separator">{t('chunkingStrategies.separator')}</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {/* Size unit (characters vs tokens) */}
              <div className="space-y-1">
                <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chunkUnit')}</span>
                <Select value={chunkUnit} onValueChange={(val) => handleUpdate({ extractChunkUnit: val })} disabled={isRunMode}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="char">{t('chunkUnits.char')}</SelectItem>
                    <SelectItem value="token">{t('chunkUnits.token')}</SelectItem>
                  </SelectContent>
                </Select>
                <p className="text-sm text-slate-400 dark:text-slate-500">{t('chunkUnitHelp')}</p>
              </div>

              {/* Chunk size */}
              <div className="space-y-1">
                <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chunkSize')}</span>
                <Input
                  type="number"
                  value={chunkSize}
                  onChange={(e) => handleUpdate({ extractChunkSize: e.target.value })}
                  className="w-full"
                  placeholder={t('chunkSizePlaceholder')}
                  readOnly={isRunMode}
                  min={1}
                />
                <p className="text-sm text-slate-400 dark:text-slate-500">{isTokenUnit ? t('chunkSizeHelpToken') : t('chunkSizeHelp')}</p>
              </div>

              {/* Overlap */}
              <div className="space-y-1">
                <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('overlap')}</span>
                <Input
                  type="number"
                  value={overlap}
                  onChange={(e) => handleUpdate({ extractOverlap: e.target.value })}
                  className="w-full"
                  placeholder={t('overlapPlaceholder')}
                  readOnly={isRunMode}
                  min={0}
                />
                <p className="text-sm text-slate-400 dark:text-slate-500">{isTokenUnit ? t('overlapHelpToken') : t('overlapHelp')}</p>
              </div>

              {/* Separator -- only for separator strategy */}
              {chunkingStrategy === 'separator' && (
                <div className="space-y-1">
                  <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('separator')}</span>
                  <Input
                    type="text"
                    value={separator}
                    onChange={(e) => handleUpdate({ extractSeparator: e.target.value })}
                    className="w-full"
                    placeholder={t('separatorPlaceholder')}
                    readOnly={isRunMode}
                  />
                  <p className="text-sm text-slate-400 dark:text-slate-500">{t('separatorHelp')}</p>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
