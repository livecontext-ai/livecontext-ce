'use client';

import * as React from 'react';
import clsx from 'clsx';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { OptionalSection } from '../OptionalSection';
import { ToolDetailsSkeleton } from '../../SkeletonLoaders';
import { ParamFieldSwitcher } from './ParamFieldSwitcher';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';

interface ToolParameter {
  id?: string;
  name?: string;
  description?: string;
  dataType?: string;
  type?: string;
  isRequired?: boolean;
  required?: boolean;
  defaultValue?: string | null;
  allowedValues?: string[] | null;
  extras?: { picker?: { provider?: string; mimeType?: string } } | null;
}

interface ToolParametersFormProps {
  node: Node<BuilderNodeData>;
  connections: Connection[];
  isRunMode?: boolean;
  isLoading: boolean;
  toolParameters: ToolParameter[];
  showOptionalParams: boolean;
  onToggleOptionalParams: () => void;
  // Expression handlers
  getToolParamExpression: (paramName: string) => string;
  handleToolParamExpressionChange: (paramName: string, value: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

export function ToolParametersForm({
  node,
  connections,
  isRunMode = false,
  isLoading,
  toolParameters,
  showOptionalParams,
  onToggleOptionalParams,
  getToolParamExpression,
  handleToolParamExpressionChange,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: ToolParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  if (isLoading) {
    return <ToolDetailsSkeleton />;
  }

  if (toolParameters.length === 0) {
    return (
      <div className="text-center py-8 text-slate-500 dark:text-slate-400 text-sm">
        {t('tool.noParameters')}
      </div>
    );
  }

  const requiredParams = toolParameters.filter(
    (param) => param.isRequired === true || param.required === true
  );
  const optionalParams = toolParameters.filter(
    (param) => param.isRequired !== true && param.required !== true
  );

  const renderParam = (param: ToolParameter, index: number, isRequired: boolean) => {
    const paramName = param.name || param.id || '';
    const paramDescription = param.description || '';
    const paramType = param.dataType || param.type || 'string';

    return (
      <label key={`${param.id || paramName}-${index}`} className="flex flex-col gap-2 relative">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{paramName}</span>
            {paramType && (
              <span className="text-sm text-slate-400 dark:text-slate-500 font-mono">({paramType})</span>
            )}
          </div>
          <span className={clsx("text-xs", isRequired ? "text-slate-500 dark:text-slate-400" : "text-slate-400 dark:text-slate-500")}>
            {isRequired ? t('required') : t('optional')}
          </span>
        </div>
        {paramDescription && (
          <p className="text-sm text-slate-400 dark:text-slate-500 -mt-1">
            {paramDescription}
          </p>
        )}
        <ParamFieldSwitcher
          paramName={paramName}
          paramType={paramType}
          defaultValue={param.defaultValue ?? null}
          allowedValues={param.allowedValues ?? null}
          picker={param.extras?.picker ?? null}
          value={getToolParamExpression(paramName)}
          onChange={(value) => handleToolParamExpressionChange(paramName, value)}
          isRequired={isRequired}
          readOnly={isRunMode}
          unknownVariables={findUnknownVariables({ [paramName]: getToolParamExpression(paramName) })}
          handleId={`param-${paramName}-${node.id}`}
          connections={connections}
          onHandleClick={handleHandleClick}
          draggingFromHandle={draggingFromHandle}
          onHandleMouseDown={handleHandleMouseDown}
          onHandleMouseUp={handleHandleMouseUp}
          hoveredTargetHandle={hoveredTargetHandle}
          onSetHandleRef={handleSetHandleRef}
        />
      </label>
    );
  };

  return (
    <div className="space-y-4 pt-2">
      {requiredParams.map((param, index) => renderParam(param, index, true))}
      {optionalParams.length > 0 && (
        <OptionalSection
          isOpen={showOptionalParams}
          onToggle={onToggleOptionalParams}
          count={optionalParams.length}
        >
          {optionalParams.map((param, index) => renderParam(param, index, false))}
        </OptionalSection>
      )}
    </div>
  );
}
