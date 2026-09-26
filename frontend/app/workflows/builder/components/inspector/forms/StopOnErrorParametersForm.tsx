'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';
import { InfoPopover } from '@/components/ui/info-popover';

interface StopOnErrorParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

export function StopOnErrorParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: StopOnErrorParametersFormProps) {
  const t = useTranslations('workflowBuilder.stopOnErrorNode');

  const errorMessage: string = (data as any).stopOnErrorMessage ?? '';
  const errorCode: string = (data as any).stopOnErrorCode ?? '';

  const handleChange = React.useCallback(
    (field: string, value: string) => {
      if (isRunMode) return;
      onUpdate({ ...data, [field]: value } as BuilderNodeData);
    },
    [data, isRunMode, onUpdate],
  );

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('title')}
        </span>
        <InfoPopover label={t('title')} size="md" side="right" align="start">
          <p className="font-semibold mb-1">{t('infoTitle')}</p>
          <p className="text-slate-500 dark:text-slate-400 text-xs">
            {t('infoDescription')}
          </p>
        </InfoPopover>
      </div>

      {/* Error Message */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('errorMessage')} <span className="text-red-500">*</span>
        </span>
        <ExpressionEditor
          value={errorMessage}
          onChange={(value) => handleChange('stopOnErrorMessage', value)}
          placeholder={t('errorMessagePlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ stopOnErrorMessage: errorMessage })}
          handleId={`stop-on-error-message-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
          isRequired
        />
      </div>

      {/* Error Code */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('errorCode')}
        </span>
        <span className="text-xs text-slate-400 dark:text-slate-500 ml-1">({t('optional')})</span>
        <ExpressionEditor
          value={errorCode}
          onChange={(value) => handleChange('stopOnErrorCode', value)}
          placeholder={t('errorCodePlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ stopOnErrorCode: errorCode })}
          handleId={`stop-on-error-code-${node.id}`}
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
    </div>
  );
}
