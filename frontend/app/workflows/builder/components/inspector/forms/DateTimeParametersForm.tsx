'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
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
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

const OPERATIONS = [
  'parse',
  'format',
  'convertTimezone',
  'add',
  'subtract',
  'difference',
  'extract',
  'now',
] as const;

const DURATION_UNITS = [
  'years',
  'months',
  'days',
  'hours',
  'minutes',
  'seconds',
] as const;

const EXTRACT_PARTS = [
  'year',
  'month',
  'day',
  'hour',
  'minute',
  'second',
  'dayOfWeek',
  'dayOfYear',
] as const;

interface DateTimeParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for DateTime node parameters.
 * Configures date/time operations: parse, format, convert timezone, add/subtract duration,
 * difference between dates, extract parts, and get current time.
 */
export function DateTimeParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: DateTimeParametersFormProps) {
  const t = useTranslations('workflowBuilder.dateTimeNode');

  const operation: string = (data as any).dateTimeOperation ?? 'parse';
  const value: string = (data as any).dateTimeValue ?? '';
  const inputFormat: string = (data as any).dateTimeInputFormat ?? '';
  const outputFormat: string = (data as any).dateTimeOutputFormat ?? '';
  const timezone: string = (data as any).dateTimeTimezone ?? '';
  const targetTimezone: string = (data as any).dateTimeTargetTimezone ?? '';
  const durationUnit: string = (data as any).dateTimeDurationUnit ?? 'days';
  const durationAmount: number = (data as any).dateTimeDurationAmount ?? 1;
  const secondValue: string = (data as any).dateTimeSecondValue ?? '';
  const extractPart: string = (data as any).dateTimeExtractPart ?? 'year';

  const handleOperationChange = React.useCallback((newValue: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeOperation: newValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleValueChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeValue: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleInputFormatChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeInputFormat: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleOutputFormatChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeOutputFormat: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleTimezoneChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeTimezone: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleTargetTimezoneChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeTargetTimezone: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleDurationUnitChange = React.useCallback((newValue: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeDurationUnit: newValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleDurationAmountChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const val = event.target.value;
    if (val === '') {
      onUpdate({
        ...data,
        dateTimeDurationAmount: 1,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(val, 10);
    if (isNaN(numValue)) {
      return;
    }

    onUpdate({
      ...data,
      dateTimeDurationAmount: numValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleSecondValueChange = React.useCallback((val: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeSecondValue: val,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleExtractPartChange = React.useCallback((newValue: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      dateTimeExtractPart: newValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  // Conditional visibility helpers
  const showValue = operation !== 'now';
  const showInputFormat = operation === 'parse';
  const showOutputFormat = operation === 'format' || operation === 'now';
  const showTimezone = operation === 'convertTimezone' || operation === 'now';
  const showTargetTimezone = operation === 'convertTimezone';
  const showDuration = operation === 'add' || operation === 'subtract';
  const showSecondValue = operation === 'difference';
  const showExtractPart = operation === 'extract';

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
            {OPERATIONS.map((op) => (
              <SelectItem key={op} value={op}>
                {t(`operations.${op}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Value */}
      {showValue && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('value')}</span>
          <ExpressionEditor
            value={value}
            onChange={handleValueChange}
            placeholder={t('valuePlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ dateTimeValue: value })}
            handleId={`datetime-value-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Input Format */}
      {showInputFormat && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('inputFormat')}</span>
          <ExpressionEditor
            value={inputFormat}
            onChange={handleInputFormatChange}
            placeholder={t('inputFormatPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ dateTimeInputFormat: inputFormat })}
            handleId={`datetime-input-format-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Output Format */}
      {showOutputFormat && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('outputFormat')}</span>
          <ExpressionEditor
            value={outputFormat}
            onChange={handleOutputFormatChange}
            placeholder={t('outputFormatPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ dateTimeOutputFormat: outputFormat })}
            handleId={`datetime-output-format-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Timezone */}
      {showTimezone && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timezone')}</span>
          <ExpressionEditor
            value={timezone}
            onChange={handleTimezoneChange}
            placeholder={t('timezonePlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ dateTimeTimezone: timezone })}
            handleId={`datetime-timezone-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Target Timezone */}
      {showTargetTimezone && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('targetTimezone')}</span>
          <ExpressionEditor
            value={targetTimezone}
            onChange={handleTargetTimezoneChange}
            placeholder={t('targetTimezonePlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ dateTimeTargetTimezone: targetTimezone })}
            handleId={`datetime-target-timezone-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Duration Unit */}
      {showDuration && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('durationUnit')}</span>
          <Select value={durationUnit} onValueChange={handleDurationUnitChange} disabled={isRunMode}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {DURATION_UNITS.map((unit) => (
                <SelectItem key={unit} value={unit}>
                  {t(`durationUnits.${unit}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Duration Amount */}
      {showDuration && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('durationAmount')}</span>
          <Input
            type="number"
            value={durationAmount}
            onChange={handleDurationAmountChange}
            className="w-full"
            placeholder={t('durationAmountPlaceholder')}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Second Value */}
      {showSecondValue && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('secondValue')}</span>
          <ExpressionEditor
            value={secondValue}
            onChange={handleSecondValueChange}
            placeholder={t('secondValuePlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ dateTimeSecondValue: secondValue })}
            handleId={`datetime-second-value-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Extract Part */}
      {showExtractPart && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('extractPart')}</span>
          <Select value={extractPart} onValueChange={handleExtractPartChange} disabled={isRunMode}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {EXTRACT_PARTS.map((part) => (
                <SelectItem key={part} value={part}>
                  {t(`extractParts.${part}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
    </div>
  );
}
