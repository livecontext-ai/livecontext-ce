/**
 * NavigationButtons wrapper for shared usage.
 *
 * This re-exports the existing NavigationButtons from outputs/
 * for use in the new OutputRenderer architecture.
 *
 * For the simplified OutputRenderer API, we provide default implementations
 * for checkNodeError, getLoopIdFromNode, and ArrowIcon.
 */

'use client';

import * as React from 'react';
import { ChevronRightIcon } from 'lucide-react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { NodeIcon, getIconSlug } from '../../nodes/shared';

// Re-export the original for backwards compatibility
export { NavigationButtons as NavigationButtonsAdvanced } from '../outputs/NavigationButtons';

interface SimpleNavigationButtonsProps {
  nodes: Node<BuilderNodeData>[];
  onNavigate?: (nodeId: string) => void;
}

/**
 * Simplified NavigationButtons for the new OutputRenderer.
 * Uses sensible defaults for error checking and icons.
 */
export function NavigationButtons({ nodes, onNavigate }: SimpleNavigationButtonsProps) {
  if (nodes.length === 0) return null;

  return (
    <div className="border-t border-slate-200 p-3 mt-auto">
      <div className="text-xs text-slate-500 mb-2">Next steps</div>
      <div className="flex flex-col gap-1">
        {nodes.map((node) => (
          <button
            key={node.id}
            onClick={(e) => {
              e.stopPropagation();
              e.preventDefault();
              onNavigate?.(node.id);
            }}
            className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 hover:text-slate-900 hover:bg-slate-100 w-full justify-between"
          >
            <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
            <span className="truncate text-sm">{node.data?.label || node.id}</span>
            <ChevronRightIcon className="h-4 w-4 flex-shrink-0" />
          </button>
        ))}
      </div>
    </div>
  );
}

export default NavigationButtons;
