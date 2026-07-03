import * as React from 'react';
import { GripVertical, ChevronRight } from 'lucide-react';
import clsx from 'clsx';
import { normalizeColumnType } from '../../utils/typeNormalizer';
import { getFieldTypeColor, normalizeFieldType } from '../../types';

interface GlobalVariable {
  name: string;
  label: string;
  type: string;
  path: string;
  /** Render the label as a highlighted expression token (SpEL styling) - used for $vars chips. */
  expressionToken?: boolean;
  properties?: Array<{ name: string; label: string; type: string; path: string }>;
}

interface GlobalVariablesInspectorProps {
  variables: GlobalVariable[];
  isDraggable?: boolean;
}

export const GlobalVariablesInspector = React.memo(function GlobalVariablesInspector({
  variables,
  isDraggable = true,
}: GlobalVariablesInspectorProps) {
  const [expandedObjects, setExpandedObjects] = React.useState<Set<string>>(new Set());

  if (!variables || variables.length === 0) {
    return null;
  }

  const toggleExpand = (name: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExpandedObjects(prev => {
      const next = new Set(prev);
      if (next.has(name)) {
        next.delete(name);
      } else {
        next.add(name);
      }
      return next;
    });
  };

  const renderVariable = (variable: GlobalVariable, index: number, showBorder: boolean = false) => {
    const hasProperties = variable.properties && variable.properties.length > 0;
    const isExpanded = expandedObjects.has(variable.name);
    const normalizedType = normalizeColumnType(variable.type);

    return (
      <div key={`${variable.name}-${index}`} className={clsx(showBorder && "pl-3 border-l border-slate-200 dark:border-slate-700")}>
        <div className="flex flex-col gap-1">
          <div
            className={clsx(
              "flex items-center justify-between text-xs font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1",
              hasProperties
                ? "cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
                : isDraggable
                  ? "cursor-grab active:cursor-grabbing"
                  : "cursor-default"
            )}
            onClick={(e) => hasProperties && toggleExpand(variable.name, e)}
            draggable={isDraggable}
            onDragStart={(e) => {
              if (!isDraggable) return;
              e.stopPropagation();
              e.dataTransfer.setData('text/plain', variable.path);
              e.dataTransfer.effectAllowed = 'copy';
            }}
            title={variable.path}
          >
            <div className="flex items-center gap-2 flex-1 min-w-0">
              {isDraggable && <GripVertical className="h-3 w-3 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing" />}
              {variable.expressionToken ? (
                <code className="token-expression font-mono truncate flex-1 min-w-0 text-xs" title={variable.path}>
                  {variable.label}
                </code>
              ) : (
                <span className="truncate flex-1 min-w-0 text-xs" title={variable.label}>{variable.label}</span>
              )}
              {hasProperties && (
                <ChevronRight
                  className={clsx(
                    "h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform mr-2",
                    isExpanded && "rotate-90"
                  )}
                />
              )}
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              <span className={clsx(
                "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                getFieldTypeColor(normalizeFieldType(normalizedType))
              )}>{normalizeFieldType(normalizedType)}</span>
            </div>
          </div>
          {hasProperties && isExpanded && (
            <div className="pl-3 border-l border-slate-200 dark:border-slate-700">
              <div className="space-y-1">
                {variable.properties.map((prop, propIndex) => {
                  const propNormalizedType = normalizeColumnType(prop.type);
                  return (
                    <div
                      key={`${variable.name}-${prop.name}-${propIndex}`}
                      className={clsx(
                        "flex items-center justify-between text-xs font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1",
                        isDraggable
                          ? "cursor-grab active:cursor-grabbing"
                          : "cursor-default"
                      )}
                      draggable={isDraggable}
                      onDragStart={(e) => {
                        if (!isDraggable) return;
                        e.stopPropagation();
                        e.dataTransfer.setData('text/plain', prop.path);
                        e.dataTransfer.effectAllowed = 'copy';
                      }}
                      title={prop.path}
                    >
                      <div className="flex items-center gap-2 flex-1 min-w-0">
                        {isDraggable && <GripVertical className="h-3 w-3 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing" />}
                        <span className="truncate flex-1 min-w-0 text-xs" title={prop.label}>{prop.label}</span>
                      </div>
                      <div className="flex items-center gap-2 flex-shrink-0">
                        <span className={clsx(
                          "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                          getFieldTypeColor(normalizeFieldType(propNormalizedType))
                        )}>{normalizeFieldType(propNormalizedType)}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      </div>
    );
  };

  return (
    <div className="w-full mb-2">
      <div className="space-y-1">
        {variables.map((variable, index) => renderVariable(variable, index))}
      </div>
    </div>
  );
});

