'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import type { Connection } from '../useInspectorConnections';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import { InfoPopover } from '@/components/ui/info-popover';

interface CodeParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

/**
 * Form component for Code node parameters.
 * Configures language, code content, and execution timeout.
 */
export function CodeParametersForm({
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
}: CodeParametersFormProps) {
  const t = useTranslations('workflowBuilder.codeNode');

  const language: string = (data as any).codeLanguage ?? 'javascript';
  const code: string = (data as any).codeContent ?? '';
  const timeout: number = (data as any).codeTimeout ?? 30;

  const handleLanguageChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      codeLanguage: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleCodeChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      codeContent: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleTimeoutChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({
        ...data,
        codeTimeout: 1,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) {
      return;
    }

    onUpdate({
      ...data,
      codeTimeout: numValue,
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

      {/* Language */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('language')}</span>
        <Select value={language} onValueChange={handleLanguageChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="javascript">{t('languages.javascript')}</SelectItem>
            <SelectItem value="python">{t('languages.python')}</SelectItem>
            <SelectItem value="typescript">{t('languages.typescript')}</SelectItem>
            <SelectItem value="bash">{t('languages.bash')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Code */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('code')}</span>
        <ExpressionEditor
          value={code}
          onChange={handleCodeChange}
          placeholder={t('codePlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ code })}
          handleId={`code-content-${node.id}`}
          connections={connections}
          onHandleClick={handleHandleClick}
          draggingFromHandle={draggingFromHandle}
          onHandleMouseDown={handleHandleMouseDown}
          onHandleMouseUp={handleHandleMouseUp}
          hoveredTargetHandle={hoveredTargetHandle}
          onSetHandleRef={handleSetHandleRef}
          readOnly={isRunMode}
        />
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
    </div>
  );
}
