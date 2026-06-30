import * as React from 'react';
import { RefreshCcw } from 'lucide-react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, FieldType } from '../../types';
import { getFieldTypeColor } from '../../types';
import { normalizeLabel } from '../../utils/labelNormalizer';

interface SourceSplitInspectorProps {
  node: Node<BuilderNodeData>;
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  isRunMode?: boolean;
}

/**
 * Displays a Split node as a source with its exit output fields.
 * Uses the same style as SourceNodeInspector for consistency.
 */
export const SourceSplitInspector = React.memo(function SourceSplitInspector({
  node,
  onNavigateToNode,
  isRunMode = false
}: SourceSplitInspectorProps) {
  const normalizedNodeLabel = normalizeLabel(node.data.label || node.id);

  const handleNavigate = (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    onNavigateToNode?.(node.id);
  };

  // Split exit output fields (aligned with SplitOutputSchemaMapper)
  const splitExitFields: Array<{ name: string; type: FieldType }> = [
    { name: 'current_item', type: 'object' },
    { name: 'current_index', type: 'number' },
    { name: 'items', type: 'text' },
    { name: 'item_count', type: 'number' },
    { name: 'split_id', type: 'text' },
    { name: 'spawn_reason', type: 'text' },
    { name: 'terminated', type: 'boolean' },
  ];

  return (
    <div className="mb-3">
      {/* Parent label with navigation */}
      <div className="flex items-center mb-2">
        <button
          onClick={handleNavigate}
          className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
          title={`Go to ${node.data.label}`}
        >
          <RefreshCcw className="h-3.5 w-3.5" />
          <span className="truncate max-w-[200px] font-medium">{node.data.label}</span>
        </button>
      </div>
      {/* Split exit fields - draggable */}
      <div className="space-y-1 pl-4">
        {splitExitFields.map((field) => (
          <div
            key={field.name}
            draggable={true}
            onDragStart={(e) => {
              if (true) {
                const dragValue = `{{core:${normalizedNodeLabel}.output.${field.name}}}`;
                e.dataTransfer.setData('text/plain', dragValue);
                e.dataTransfer.effectAllowed = 'copy';
              }
            }}
            className={`flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded cursor-grab hover:bg-slate-50 dark:hover:bg-slate-800`}
          >
            <span className="text-sm">{field.name}</span>
            <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
          </div>
        ))}
      </div>
    </div>
  );
});
