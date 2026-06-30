'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { OptionalSection } from '../OptionalSection';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';

interface AgentParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  showOptionalParams: boolean;
  onToggleOptionalParams: () => void;
  onUpdate: (data: BuilderNodeData) => void;
  // Expression handlers
  getParamExpression: (paramName: string) => string;
  handleParamExpressionChange: (paramName: string, value: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

export function AgentParametersForm({
  node,
  data,
  connections,
  isRunMode = false,
  showOptionalParams,
  onToggleOptionalParams,
  onUpdate,
  getParamExpression,
  handleParamExpressionChange,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: AgentParametersFormProps) {
  return (
    <div className="space-y-4 pt-2">
      {/* Required parameters - always shown */}
      <label className="flex flex-col gap-2 relative">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Prompt</span>
          <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
        </div>
        <ExpressionEditor
          value={getParamExpression('prompt')}
          onChange={(value) => handleParamExpressionChange('prompt', value)}
          placeholder="Enter Expression..."
          className="w-full"
          unknownVariables={findUnknownVariables({ prompt: getParamExpression('prompt') })}
          handleId={`param-prompt-${node.id}`}
          connections={connections}
          onHandleClick={handleHandleClick}
          draggingFromHandle={draggingFromHandle}
          onHandleMouseDown={handleHandleMouseDown}
          onHandleMouseUp={handleHandleMouseUp}
          hoveredTargetHandle={hoveredTargetHandle}
          onSetHandleRef={handleSetHandleRef}
          isRequired={true}
          readOnly={isRunMode}
        />
      </label>

      <label className="flex flex-col gap-2 relative">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Model</span>
          <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
        </div>
        <ExpressionEditor
          value={getParamExpression('model')}
          onChange={(value) => handleParamExpressionChange('model', value)}
          placeholder="Enter Expression..."
          className="w-full"
          unknownVariables={findUnknownVariables({ model: getParamExpression('model') })}
          handleId={`param-model-${node.id}`}
          connections={connections}
          onHandleClick={handleHandleClick}
          draggingFromHandle={draggingFromHandle}
          onHandleMouseDown={handleHandleMouseDown}
          onHandleMouseUp={handleHandleMouseUp}
          hoveredTargetHandle={hoveredTargetHandle}
          onSetHandleRef={handleSetHandleRef}
          isRequired={true}
          readOnly={isRunMode}
        />
      </label>

      <OptionalSection
        isOpen={showOptionalParams}
        onToggle={onToggleOptionalParams}
        count={2}
      >
        <label className="flex flex-col gap-2 relative">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Temperature</span>
            <span className="text-sm text-slate-400 dark:text-slate-500">Optional</span>
          </div>
          <Input
            type="number"
            min="0"
            max="2"
            step="0.1"
            className="w-full"
            value={data.temperature ?? 0.7}
            onChange={(event) => onUpdate({ ...data, temperature: parseFloat(event.target.value) || 0.7 })}
            placeholder="0.7"
            readOnly={isRunMode}
          />
        </label>

        <label className="flex flex-col gap-2 relative">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Max Tokens</span>
            <span className="text-sm text-slate-400 dark:text-slate-500">Optional</span>
          </div>
          <ExpressionEditor
            value={getParamExpression('maxTokens')}
            onChange={(value) => handleParamExpressionChange('maxTokens', value)}
            placeholder="Enter Expression..."
            className="w-full"
            unknownVariables={findUnknownVariables({ maxTokens: getParamExpression('maxTokens') })}
            handleId={`param-maxTokens-${node.id}`}
            connections={connections}
            onHandleClick={handleHandleClick}
            draggingFromHandle={draggingFromHandle}
            onHandleMouseDown={handleHandleMouseDown}
            onHandleMouseUp={handleHandleMouseUp}
            hoveredTargetHandle={hoveredTargetHandle}
            onSetHandleRef={handleSetHandleRef}
            isRequired={false}
            readOnly={isRunMode}
          />
        </label>
      </OptionalSection>
    </div>
  );
}

/**
 * Helper to check if a node is an agent node
 */
export function isAgentNode(data: BuilderNodeData | undefined): boolean {
  if (!data) return false;
  return (
    data.id === 'agent' ||
    data.id?.startsWith('ai-agent') ||
    data.id?.startsWith('agent-') ||
    data.label?.toLowerCase().includes('agent')
  );
}
