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

interface XmlParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for XML node parameters.
 * Configures XML-to-JSON or JSON-to-XML conversion.
 */
export function XmlParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: XmlParametersFormProps) {
  const t = useTranslations('workflowBuilder.xmlNode');

  const operation: string = (data as any).xmlOperation ?? 'xmlToJson';
  const input: string = (data as any).xmlInput ?? '';
  const rootElement: string = (data as any).xmlRootElement ?? '';

  const handleOperationChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      xmlOperation: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleInputChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      xmlInput: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleRootElementChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      xmlRootElement: value,
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
            <SelectItem value="xmlToJson">{t('operations.xmlToJson')}</SelectItem>
            <SelectItem value="jsonToXml">{t('operations.jsonToXml')}</SelectItem>
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
          unknownVariables={findUnknownVariables({ xmlInput: input })}
          handleId={`xml-input-${node.id}`}
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

      {/* Root element name (only for jsonToXml) */}
      {operation === 'jsonToXml' && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('rootElement')}</span>
          <ExpressionEditor
            value={rootElement}
            onChange={handleRootElementChange}
            placeholder={t('rootElementPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ xmlRootElement: rootElement })}
            handleId={`xml-root-element-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
          <p className="text-sm text-slate-400 dark:text-slate-500">
            {t('rootElementHelp')}
          </p>
        </div>
      )}
    </div>
  );
}
