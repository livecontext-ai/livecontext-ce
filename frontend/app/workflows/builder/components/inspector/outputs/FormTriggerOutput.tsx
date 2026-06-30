'use client';

/**
 * FormTriggerOutput - Dynamic output display for Form triggers
 *
 * Shows static fields (form_data, submitted_at) plus dynamic fields
 * based on the configured form fields.
 *
 * In run mode, displays actual execution data using RunDataPreview.
 */

import * as React from 'react';
import clsx from 'clsx';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { getFieldTypeColor } from '../../../types';
import { NavigationButtons } from './NavigationButtons';
import { RunDataPreview } from './RunDataPreview';

interface FormField {
  id: string;
  type: string;
  name: string;
  label: string;
}

interface FormTriggerData {
  fields?: FormField[];
}

interface FormTriggerOutputProps {
  currentNode: Node<BuilderNodeData>;
  nextNodes: Node<BuilderNodeData>[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode: (node: Node<BuilderNodeData>) => string | undefined;
  ArrowIcon: React.ComponentType<{ node: Node<BuilderNodeData> }>;
  // Run mode props
  isRunMode?: boolean;
  workflowId?: string;
  runId?: string;
  showExecutionData?: boolean;
}

// Map form field types to output types
function getOutputType(fieldType: string): string {
  switch (fieldType) {
    case 'number':
      return 'number';
    case 'checkbox':
      return 'boolean';
    case 'date':
    case 'datetime':
    case 'time':
      return 'datetime';
    case 'file':
      return 'file';
    case 'multiselect':
    case 'checkboxGroup':
      return 'array';
    default:
      return 'text';
  }
}

export function FormTriggerOutput({
  currentNode,
  nextNodes,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode = false,
  workflowId,
  runId,
  showExecutionData = true,
}: FormTriggerOutputProps) {
  // Get step alias for run data
  const stepAlias = currentNode?.data?.label;
  const canShowExecutionData = isRunMode && showExecutionData && workflowId && runId && stepAlias;

  // Get form trigger data from node
  const formTriggerData = (currentNode.data as any)?.formTriggerData as FormTriggerData | undefined;
  const formFields = formTriggerData?.fields || [];

  // Build outputs list: static fields + dynamic form fields
  const outputs = React.useMemo(() => {
    const result: Array<{ key: string; type: string; description: string }> = [
      { key: 'submission_id', type: 'text', description: 'Form submission identifier' },
      { key: 'submitted_at', type: 'datetime', description: 'When the form was submitted' },
      { key: 'form_data', type: 'object', description: 'All submitted field values as object' },
    ];

    // Add dynamic fields from form configuration
    formFields.forEach((field) => {
      if (field.name) {
        result.push({
          key: field.name,
          type: getOutputType(field.type),
          description: field.label || field.name,
        });
      }
    });

    return result;
  }, [formFields]);

  return (
    <div className="w-full space-y-2">
      <NavigationButtons
        nextNodes={nextNodes}
        onNavigateToNode={onNavigateToNode}
        checkNodeError={checkNodeError}
        getLoopIdFromNode={getLoopIdFromNode}
        ArrowIcon={ArrowIcon}
      />

      {canShowExecutionData ? (
        <RunDataPreview
          workflowId={workflowId}
          runId={runId}
          stepAlias={stepAlias}
          dataType="output"
          isDraggable={false}
        />
      ) : (
        <div className="space-y-1">
          {outputs.map((item) => (
            <div
              key={item.key}
              className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1"
              title={item.description}
            >
              <span className="truncate flex-1 min-w-0 text-sm" title={item.key}>
                {item.key}
              </span>
              <span
                className={clsx(
                  "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                  getFieldTypeColor(item.type)
                )}
              >
                {item.type}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
