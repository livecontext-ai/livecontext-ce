import * as React from 'react';
import clsx from 'clsx';
import { GripVertical, ChevronRight } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { getFieldTypeColor, normalizeFieldType } from '../../types';
import { EmptyState } from '../shared/EmptyState';

export interface StructureNode {
  key: string;
  type: string;
  hasChildren: boolean;
}

interface StructureExplorerProps {
  nodes: StructureNode[];
  path: string[];
  onPathChange: (path: string[]) => void;
  loading?: boolean;
  rootLabel?: string;
  baseLabel?: string;
  onBaseClick?: () => void;
  dragPrefix?: string;
  headerActions?: React.ReactNode;
  isDraggable?: boolean;
  variant?: 'card' | 'plain';
  showBreadcrumb?: boolean;
}

export const StructureExplorer = ({ 
  nodes, 
  path, 
  onPathChange, 
  loading, 
  rootLabel = "root", 
  baseLabel, 
  onBaseClick, 
  dragPrefix,
  headerActions,
  isDraggable = true,
  variant = 'plain',
  showBreadcrumb = true
}: StructureExplorerProps) => {
  const itemBaseClass = variant === 'card'
    ? "rounded-lg bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 px-2 py-2"
    : "px-1 py-1";

  return (
    <div className="w-full">
      {/* Breadcrumb */}
      <div className="flex items-center justify-between w-full mb-2">
        {showBreadcrumb ? (
          <div className="flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400 flex-wrap">
            {baseLabel && (
              <>
                <button 
                  onClick={onBaseClick} 
                  className="hover:text-slate-800 dark:hover:text-slate-200 hover:underline text-slate-500 dark:text-slate-400 truncate max-w-[100px]"
                  title={`Go to ${baseLabel}`}
                >
                  {baseLabel}
                </button>
                <span>/</span>
              </>
            )}
            <button 
              onClick={() => onPathChange([])} 
              className={clsx(
                "hover:text-slate-800 dark:hover:text-slate-200 hover:underline", 
                path.length === 0 && "font-bold text-slate-700 dark:text-slate-300"
              )}
            >
              {rootLabel}
            </button>
            {path.map((p, i) => (
              <React.Fragment key={i}>
                <span>/</span>
                <button onClick={() => onPathChange(path.slice(0, i + 1))} className={clsx("hover:text-slate-800 dark:hover:text-slate-200 hover:underline", i === path.length - 1 && "font-bold text-slate-700 dark:text-slate-300")}>{p}</button>
              </React.Fragment>
            ))}
          </div>
        ) : (
          <div className="text-xs text-slate-500 dark:text-slate-400 truncate">
            {[rootLabel, ...path].filter(Boolean).join(' / ') || rootLabel}
          </div>
        )}
        {headerActions && <div>{headerActions}</div>}
      </div>

      {/* Content */}
      {loading ? (
         <div className="space-y-2 w-full">
           {[1, 2, 3].map((i) => (
             <div key={i} className="h-9 bg-slate-100 dark:bg-slate-700 rounded-lg animate-pulse w-full border border-slate-100 dark:border-slate-600" />
           ))}
         </div>
      ) : nodes.length > 0 ? (
         <div className="space-y-1 max-h-[300px] overflow-y-auto pr-1 custom-scrollbar">
           {nodes.map(item => (
             <div 
               key={item.key}
               draggable={isDraggable}
               onDragStart={(e) => {
                  if (!isDraggable) return;
                  e.stopPropagation();
                  
                  let fullPath;
                  if (dragPrefix) {
                      fullPath = [dragPrefix, ...path, item.key].join('.');
                  } else {
                      fullPath = [...path, item.key].join('.');
                  }
                  
                  e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
                  e.dataTransfer.effectAllowed = 'copy';
               }}
               className={clsx(
                 "flex items-center justify-between text-xs font-medium text-slate-700 dark:text-slate-300 w-full transition-colors group",
                 itemBaseClass,
                 item.hasChildren
                   ? "cursor-pointer hover:text-slate-900 dark:hover:text-slate-100"
                   : isDraggable
                     ? "cursor-grab active:cursor-grabbing hover:text-slate-900 dark:hover:text-slate-100"
                     : "cursor-default"
               )}
               onClick={() => item.hasChildren && onPathChange([...path, item.key])}
             >
               <div className="flex items-center gap-2 flex-1 min-w-0">
                 {isDraggable && <GripVertical className="h-3 w-3 text-slate-400 dark:text-slate-500 cursor-grab active:cursor-grabbing flex-shrink-0" />}
                 <span className="truncate mr-2 flex-1 min-w-0" title={item.key}>{item.key}</span>
               </div>
               
               <div className="flex items-center gap-2 flex-shrink-0">
                  {item.type === null || item.type === 'null' || item.type === 'unknown' ? (
                     <span className="text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0 bg-gray-200 dark:bg-gray-700 text-gray-500 dark:text-gray-400">unknown</span>
                  ) : (
                     <span className={clsx(
                       "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                       getFieldTypeColor(normalizeFieldType(item.type))
                     )}>{normalizeFieldType(item.type)}</span>
                  )}

                  {item.hasChildren && <ChevronRight className="h-3 w-3 text-slate-400 dark:text-slate-500" />}
               </div>
             </div>
           ))}
         </div>
      ) : (
         <EmptyState message="No data structure" className="text-xs" />
      )}
    </div>
  );
};

