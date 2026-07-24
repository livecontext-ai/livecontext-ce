'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';
import {
  clampPublicLinkTtlMinutes,
  PUBLIC_LINK_DISPOSITION_DEFAULT,
  PUBLIC_LINK_TTL_DEFAULT,
  PUBLIC_LINK_TTL_MAX,
  PUBLIC_LINK_TTL_MIN,
} from '../../../utils/publicLinkParams';

interface PublicLinkParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for Public Link node parameters.
 * Turns a file (FileRef) into a public, time-limited signed URL on the
 * platform's own storage - for APIs that pull media from a URL.
 */
export function PublicLinkParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: PublicLinkParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');

  // Get values from node data
  const fileExpression: string = (data as any).publicLinkFile ?? '';
  const ttlMinutes: number = (data as any).publicLinkTtlMinutes ?? PUBLIC_LINK_TTL_DEFAULT;
  const disposition: string = (data as any).publicLinkDisposition ?? PUBLIC_LINK_DISPOSITION_DEFAULT;

  // Local draft so the user can type freely; the value is clamped on blur.
  const [ttlDraft, setTtlDraft] = React.useState<string>(String(ttlMinutes));
  React.useEffect(() => {
    setTtlDraft(String(ttlMinutes));
  }, [ttlMinutes]);

  const handleFileChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      publicLinkFile: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleTtlBlur = React.useCallback(() => {
    if (isRunMode) return;
    const clamped = clampPublicLinkTtlMinutes(ttlDraft);
    setTtlDraft(String(clamped));
    onUpdate({
      ...data,
      publicLinkTtlMinutes: clamped,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate, ttlDraft]);

  const handleDispositionChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      publicLinkDisposition: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* File input (required) */}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('publicLink.file')}</span>
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
                  <p className="font-semibold text-slate-900 dark:text-slate-100">{t('publicLink.title')}</p>
                  <p>{t('publicLink.description')}</p>
                  <p className="text-xs text-slate-500 dark:text-slate-400">{t('publicLink.fileHint')}</p>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
        </div>
        <ExpressionEditor
          value={fileExpression}
          onChange={handleFileChange}
          placeholder={t('publicLink.filePlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ file: fileExpression })}
          handleId={`public-link-file-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          isRequired={true}
          readOnly={isRunMode}
        />
      </div>

      {/* TTL minutes */}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('publicLink.ttlMinutes')}</span>
          <span className="text-sm text-slate-400 dark:text-slate-500">{t('optional')}</span>
        </div>
        <Input
          type="number"
          min={PUBLIC_LINK_TTL_MIN}
          max={PUBLIC_LINK_TTL_MAX}
          value={ttlDraft}
          onChange={(e) => setTtlDraft(e.target.value)}
          onBlur={handleTtlBlur}
          disabled={isRunMode}
          className="w-full text-sm"
        />
        <span className="text-xs text-slate-400 dark:text-slate-500">{t('publicLink.ttlHint')}</span>
      </div>

      {/* Disposition */}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('publicLink.disposition')}</span>
          <span className="text-sm text-slate-400 dark:text-slate-500">{t('optional')}</span>
        </div>
        <Select value={disposition} onValueChange={handleDispositionChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="inline">{t('publicLink.dispositionInline')}</SelectItem>
            <SelectItem value="attachment">{t('publicLink.dispositionAttachment')}</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
