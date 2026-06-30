'use client';

import * as React from 'react';
import clsx from 'clsx';
import { getFieldTypeColor } from '../../../types';
import { normalizeLabel } from '../../../utils/labelNormalizer';

interface OutputFieldRowProps {
  fieldName: string;
  fieldType: string;
  nodeLabel?: string;
  nodePrefix?: 'mcp' | 'trigger' | 'agent' | 'core';
  isDraggable?: boolean;
  isRunMode?: boolean;
}

/**
 * OutputFieldRow - Reusable component for displaying a single output field
 * with optional drag functionality for expression building
 */
export function OutputFieldRow({
  fieldName,
  fieldType,
  nodeLabel,
  nodePrefix = 'mcp',
  isDraggable = true,
  isRunMode = false,
}: OutputFieldRowProps) {
  const canDrag = isDraggable && !isRunMode;

  const handleDragStart = (e: React.DragEvent) => {
    if (!canDrag || !nodeLabel) return;
    const normalizedNodeLabel = normalizeLabel(nodeLabel);
    const dragValue = `{{${nodePrefix}:${normalizedNodeLabel}.output.${fieldName}}}`;
    e.dataTransfer.setData('text/plain', dragValue);
    e.dataTransfer.setData('application/x-field-type', fieldType);
    e.dataTransfer.effectAllowed = 'copy';
  };

  return (
    <div
      draggable={canDrag}
      onDragStart={handleDragStart}
      className={clsx(
        "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded",
        canDrag && "cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800"
      )}
    >
      <span className="text-sm">{fieldName}</span>
      <span className={clsx(
        "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono",
        getFieldTypeColor(fieldType)
      )}>
        {fieldType}
      </span>
    </div>
  );
}

interface OutputFieldsGroupProps {
  fields: Array<{ name: string; type: string }>;
  nodeLabel?: string;
  nodePrefix?: 'mcp' | 'trigger' | 'agent' | 'core';
  isDraggable?: boolean;
  isRunMode?: boolean;
}

/**
 * OutputFieldsGroup - Renders a group of output fields
 */
export function OutputFieldsGroup({
  fields,
  nodeLabel,
  nodePrefix = 'mcp',
  isDraggable = true,
  isRunMode = false,
}: OutputFieldsGroupProps) {
  return (
    <div className="space-y-1">
      {fields.map((field) => (
        <OutputFieldRow
          key={field.name}
          fieldName={field.name}
          fieldType={field.type}
          nodeLabel={nodeLabel}
          nodePrefix={nodePrefix}
          isDraggable={isDraggable}
          isRunMode={isRunMode}
        />
      ))}
    </div>
  );
}
