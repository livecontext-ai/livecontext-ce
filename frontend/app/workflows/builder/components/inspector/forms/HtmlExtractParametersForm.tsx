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

interface HtmlExtractField {
  id: string;
  name: string;
  selector: string;
  attribute: string;
  transform: 'none' | 'trim' | 'lowercase' | 'uppercase' | 'number';
  required: boolean;
  defaultValue: string;
}

interface HtmlExtractParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

function generateId(): string {
  return `htmlx_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form for the HTML Extract node - parses HTML via CSS selectors using jsoup on the backend.
 * Uses ExpressionEditor for the source HTML so users can bind it to upstream outputs.
 */
export function HtmlExtractParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: HtmlExtractParametersFormProps) {
  const t = useTranslations('workflowBuilder.htmlExtractNode');
  const tf = useTranslations('workflowBuilder.forms');

  const sourceHtml: string = (data as any).htmlExtractSource ?? '';
  const extractionMode: string = (data as any).htmlExtractMode ?? 'single';
  const rootSelector: string = (data as any).htmlExtractRootSelector ?? '';
  const cleanWhitespace: boolean = (data as any).htmlExtractCleanWhitespace ?? true;
  const fields: HtmlExtractField[] = React.useMemo(() => {
    const raw = (data as any).htmlExtractFields;
    if (Array.isArray(raw) && raw.length > 0) {
      return raw.map((f: any) => ({
        id: f.id ?? generateId(),
        name: f.name ?? '',
        selector: f.selector ?? '',
        attribute: f.attribute ?? 'text',
        transform: f.transform ?? 'none',
        required: !!f.required,
        defaultValue: f.defaultValue ?? '',
      }));
    }
    return [
      { id: generateId(), name: '', selector: '', attribute: 'text', transform: 'none', required: false, defaultValue: '' },
    ];
  }, [data]);

  const canDelete = fields.length > 1;

  const updateFields = React.useCallback(
    (next: HtmlExtractField[]) => {
      if (isRunMode) return;
      onUpdate({ ...data, htmlExtractFields: next } as BuilderNodeData);
    },
    [data, isRunMode, onUpdate],
  );

  const handleAdd = React.useCallback(() => {
    if (isRunMode) return;
    updateFields([
      ...fields,
      { id: generateId(), name: '', selector: '', attribute: 'text', transform: 'none', required: false, defaultValue: '' },
    ]);
  }, [fields, isRunMode, updateFields]);

  const handleRemove = React.useCallback(
    (id: string) => {
      if (isRunMode || fields.length <= 1) return;
      updateFields(fields.filter((f) => f.id !== id));
    },
    [fields, isRunMode, updateFields],
  );

  const handleFieldChange = React.useCallback(
    (id: string, key: keyof HtmlExtractField, value: string | boolean) => {
      if (isRunMode) return;
      updateFields(fields.map((f) => (f.id === id ? { ...f, [key]: value } : f)));
    },
    [fields, isRunMode, updateFields],
  );

  return (
    <div className="space-y-4 pt-2">
      {/* Source HTML - ExpressionEditor (this is THE primary binding point) */}
      <div className="space-y-1">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('sourceHtmlLabel')} <span className="text-red-500">*</span>
          </span>
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
        <ExpressionEditor
          value={sourceHtml}
          onChange={(val) => onUpdate({ ...data, htmlExtractSource: val } as BuilderNodeData)}
          placeholder={t('sourceHtmlPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ htmlExtractSource: sourceHtml })}
          handleId={`html-extract-source-${node.id}`}
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

      {/* Mode + clean whitespace */}
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('modeLabel')}
          </span>
          <Select
            value={extractionMode}
            onValueChange={(v) => onUpdate({ ...data, htmlExtractMode: v } as BuilderNodeData)}
            disabled={isRunMode}
          >
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="single">{t('modeSingle')}</SelectItem>
              <SelectItem value="multiple">{t('modeMultiple')}</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('cleanWhitespaceLabel')}
          </span>
          <Select
            value={String(cleanWhitespace)}
            onValueChange={(v) => onUpdate({ ...data, htmlExtractCleanWhitespace: v === 'true' } as BuilderNodeData)}
            disabled={isRunMode}
          >
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="true">{t('cleanOn')}</SelectItem>
              <SelectItem value="false">{t('cleanOff')}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>

      {/* Root selector (only in multiple mode) */}
      {extractionMode === 'multiple' && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('rootSelectorLabel')}
          </span>
          <Input
            value={rootSelector}
            onChange={(e) => onUpdate({ ...data, htmlExtractRootSelector: e.target.value } as BuilderNodeData)}
            placeholder={t('rootSelectorPlaceholder')}
            className="w-full text-sm"
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Fields header + add */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('fieldsLabel')}
        </span>
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAdd();
            }}
            title={tf('addField')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      {/* Field rows */}
      <div className="space-y-3">
        {fields.map((field) => (
          <div
            key={field.id}
            className="flex flex-col gap-2 relative rounded-lg border border-slate-200 dark:border-slate-700 p-3"
          >
            {/* Name */}
            <div className="space-y-1">
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('namePlaceholder')}
              </span>
              <Input
                value={field.name}
                onChange={(e) => handleFieldChange(field.id, 'name', e.target.value)}
                placeholder={t('namePlaceholder')}
                className="w-full text-sm"
                readOnly={isRunMode}
              />
            </div>

            {/* Selector (plain Input - CSS selectors are not templates) */}
            <div className="space-y-1">
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('selectorPlaceholder')}
              </span>
              <Input
                value={field.selector}
                onChange={(e) => handleFieldChange(field.id, 'selector', e.target.value)}
                placeholder={t('selectorPlaceholder')}
                className="w-full text-sm font-mono"
                readOnly={isRunMode}
              />
            </div>

            {/* Attribute + Transform */}
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1">
                <span className="text-xs text-slate-400 dark:text-slate-500">
                  {t('attributePlaceholder')}
                </span>
                <Select
                  value={field.attribute || 'text'}
                  onValueChange={(v) => handleFieldChange(field.id, 'attribute', v)}
                  disabled={isRunMode}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="text">text</SelectItem>
                    <SelectItem value="html">html</SelectItem>
                    <SelectItem value="href">href</SelectItem>
                    <SelectItem value="src">src</SelectItem>
                    <SelectItem value="alt">alt</SelectItem>
                    <SelectItem value="title">title</SelectItem>
                    <SelectItem value="value">value</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <span className="text-xs text-slate-400 dark:text-slate-500">transform</span>
                <Select
                  value={field.transform}
                  onValueChange={(v) => handleFieldChange(field.id, 'transform', v)}
                  disabled={isRunMode}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="none">none</SelectItem>
                    <SelectItem value="trim">trim</SelectItem>
                    <SelectItem value="lowercase">lowercase</SelectItem>
                    <SelectItem value="uppercase">uppercase</SelectItem>
                    <SelectItem value="number">number</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            {/* Required + default value */}
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1">
                <span className="text-xs text-slate-400 dark:text-slate-500">required</span>
                <Select
                  value={String(field.required)}
                  onValueChange={(v) => handleFieldChange(field.id, 'required', v === 'true')}
                  disabled={isRunMode}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="false">false</SelectItem>
                    <SelectItem value="true">true</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <span className="text-xs text-slate-400 dark:text-slate-500">default</span>
                <Input
                  value={field.defaultValue}
                  onChange={(e) => handleFieldChange(field.id, 'defaultValue', e.target.value)}
                  placeholder=""
                  className="w-full text-sm"
                  readOnly={isRunMode}
                />
              </div>
            </div>

            {/* Delete */}
            {!isRunMode && (
              <div className="flex justify-end">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleRemove(field.id);
                  }}
                  disabled={!canDelete}
                  title={canDelete ? tf('removeField') : undefined}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
