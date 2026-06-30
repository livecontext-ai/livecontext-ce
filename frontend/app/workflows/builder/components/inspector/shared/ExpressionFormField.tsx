/**
 * ExpressionFormField - Reusable expression field with connection handling.
 *
 * This component encapsulates the ExpressionEditor + connection props pattern
 * that is repeated in every form. It reduces ~20 lines per field to ~5 lines.
 *
 * Before (repeated 30+ times):
 * ```tsx
 * <ExpressionEditor
 *   value={getParamExpression('prompt')}
 *   onChange={(value) => handleParamExpressionChange('prompt', value)}
 *   unknownVariables={findUnknownVariables({ prompt: getParamExpression('prompt') })}
 *   handleId={`param-prompt-${node.id}`}
 *   connections={connections}
 *   onHandleClick={handleHandleClick}
 *   draggingFromHandle={draggingFromHandle}
 *   onHandleMouseDown={handleHandleMouseDown}
 *   onHandleMouseUp={handleHandleMouseUp}
 *   hoveredTargetHandle={hoveredTargetHandle}
 *   onSetHandleRef={handleSetHandleRef}
 *   isRequired={true}
 *   readOnly={isRunMode}
 * />
 * ```
 *
 * After:
 * ```tsx
 * <ExpressionFormField
 *   name="prompt"
 *   label="Prompt"
 *   required
 *   {...formProps}
 * />
 * ```
 */

'use client';

import * as React from 'react';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { ConnectionPropsBundle } from '../core/types';

interface ExpressionFormFieldProps {
  // Field identity
  name: string;
  label: string;

  // Field config
  required?: boolean;
  placeholder?: string;
  multiline?: boolean;
  rows?: number;

  // From InspectorFormProps (spread these)
  nodeId: string;
  isRunMode: boolean;
  getExpression: (field: string) => string;
  setExpression: (field: string, value: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  connectionProps: ConnectionPropsBundle;

  // Optional error
  error?: string;
}

export function ExpressionFormField({
  name,
  label,
  required = false,
  placeholder = 'Enter expression...',
  multiline = false,
  rows = 3,
  nodeId,
  isRunMode,
  getExpression,
  setExpression,
  findUnknownVariables,
  connectionProps,
  error,
}: ExpressionFormFieldProps) {
  const value = getExpression(name);
  const handleId = `param-${name}-${nodeId}`;
  const unknownVars = findUnknownVariables({ [name]: value });

  return (
    <label className="flex flex-col gap-2 relative">
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {label}
        </span>
        {required && (
          <span className="text-sm text-slate-500 dark:text-slate-400">
            Required
          </span>
        )}
      </div>
      <ExpressionEditor
        value={value}
        onChange={(newValue) => setExpression(name, newValue)}
        placeholder={placeholder}
        className="w-full"
        unknownVariables={unknownVars}
        handleId={handleId}
        connections={connectionProps.connections}
        onHandleClick={(hId, e) => connectionProps.handleHandleClick(hId, nodeId)}
        draggingFromHandle={connectionProps.draggingFromHandle}
        onHandleMouseDown={(hId, e) => connectionProps.handleHandleMouseDown(e, hId, nodeId)}
        onHandleMouseUp={(hId) => connectionProps.handleHandleMouseUp(hId, nodeId)}
        hoveredTargetHandle={connectionProps.hoveredTargetHandle}
        onSetHandleRef={(hId, el) => connectionProps.handleSetHandleRef(hId, el)}
        isRequired={required}
        readOnly={isRunMode}
      />
      {error && (
        <span className="text-xs text-red-500">{error}</span>
      )}
    </label>
  );
}

/**
 * Helper to spread form props for ExpressionFormField.
 * Use this to avoid repeating the same props for each field.
 */
export function getExpressionFieldProps(formProps: {
  node: { id: string };
  isRunMode: boolean;
  getExpression: (field: string) => string;
  setExpression: (field: string, value: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  connectionProps: ConnectionPropsBundle;
  errors: Record<string, string>;
}) {
  return {
    nodeId: formProps.node.id,
    isRunMode: formProps.isRunMode,
    getExpression: formProps.getExpression,
    setExpression: formProps.setExpression,
    findUnknownVariables: formProps.findUnknownVariables,
    connectionProps: formProps.connectionProps,
  };
}

export default ExpressionFormField;
