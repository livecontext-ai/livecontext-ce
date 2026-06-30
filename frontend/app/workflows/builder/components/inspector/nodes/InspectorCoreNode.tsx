'use client';

import * as React from 'react';
import clsx from 'clsx';
import { ArrowRight, Code } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { CORE_LOGIC_TYPES, CORE_DIRECT_TYPES } from '../nodeTypes';

interface InspectorCoreNodeProps {
  // Navigation state
  coreNavigationLevel: 'core' | 'logic' | 'types';
  coreSearchQuery: string;
  setCoreSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  coreSelectedType: string | null;
  
  // Node detection
  isCoreNode: boolean;
  isCoreGenericNode: boolean;
  isLogicSubcategory: boolean;
  isIfElse: boolean;
  isUserApproval: boolean;
  isWhile: boolean;
  isTransform: boolean;
  isHttpRequest: boolean;
  isWebhook: boolean;
  
  // Handlers
  handleCoreLogicClick: () => void;
  handleCoreTypeClick: (coreType: typeof CORE_LOGIC_TYPES[0] | typeof CORE_DIRECT_TYPES[0]) => void;
  handleCoreSelect: (coreType: typeof CORE_LOGIC_TYPES[0] | typeof CORE_DIRECT_TYPES[0]) => void;
  
  // Node
  node: any;
}

export function InspectorCoreNode({
  coreNavigationLevel,
  coreSearchQuery,
  setCoreSearchQuery,
  coreSelectedType,
  isCoreNode,
  isCoreGenericNode,
  isLogicSubcategory,
  isIfElse,
  isUserApproval,
  isWhile,
  isTransform,
  isHttpRequest,
  isWebhook,
  handleCoreLogicClick,
  handleCoreTypeClick,
  handleCoreSelect,
  node,
}: InspectorCoreNodeProps) {
  if (!isCoreNode) {
    return null;
  }

  return (
    <div className="space-y-4 pt-2">
      {/* Search - Hide for specific Core types */}
      {!isLogicSubcategory && !isIfElse && !isUserApproval && !isWhile && !isTransform && !isHttpRequest && !isWebhook && (
        <div className="relative">
          <Input
            type="text"
            placeholder="Search for a Core type..."
            value={coreSearchQuery}
            onChange={(e) => setCoreSearchQuery(e.target.value)}
            className="w-full"
          />
        </div>
      )}

      {/* Core List - Show for generic Core node */}
      {coreNavigationLevel === 'core' && isCoreGenericNode && (
        <div className="space-y-2 overflow-x-hidden">
          {/* Logic subcategory */}
          <div
            className="flex items-center gap-2 px-3 py-2 rounded-2xl hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer"
            onClick={handleCoreLogicClick}
          >
            <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-orange-100 dark:bg-orange-900/30">
              <Code className="w-4 h-4 text-gray-700 dark:text-gray-300" />
            </div>
            <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
              <div className="flex-1 min-w-0 flex flex-col justify-center">
                <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                  Logic
                </div>
                <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                  Control flow, conditions, loops, and data transformations
                </div>
              </div>
              <ArrowRight className="w-4 h-4 text-slate-400 dark:text-slate-500 flex-shrink-0" />
            </div>
          </div>
          
          {/* Direct Core nodes */}
          {CORE_DIRECT_TYPES
            .filter(coreType => 
              !coreSearchQuery.trim() ||
              coreType.name.toLowerCase().includes(coreSearchQuery.toLowerCase()) ||
              coreType.description?.toLowerCase().includes(coreSearchQuery.toLowerCase())
            )
            .map((coreType) => {
              const CoreIcon = coreType.icon;
              return (
                <div
                  key={coreType.id}
                  className="flex items-center gap-2 px-3 py-2 rounded-2xl hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer"
                  onClick={() => handleCoreTypeClick(coreType)}
                >
                  <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-gray-100 dark:bg-gray-800">
                    <CoreIcon className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                  </div>
                  <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                        {coreType.name}
                      </div>
                      {coreType.description && (
                        <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                          {coreType.description}
                        </div>
                      )}
                    </div>
                    <ArrowRight className="w-4 h-4 text-slate-400 dark:text-slate-500 flex-shrink-0" />
                  </div>
                </div>
              );
            })}
        </div>
      )}

      {/* Logic subcategory - Show when navigating to Logic */}
      {coreNavigationLevel === 'logic' && (
        <div className="space-y-2 overflow-x-hidden">
          {CORE_LOGIC_TYPES
            .filter(coreType => 
              !coreSearchQuery.trim() ||
              coreType.name.toLowerCase().includes(coreSearchQuery.toLowerCase()) ||
              coreType.description?.toLowerCase().includes(coreSearchQuery.toLowerCase())
            )
            .map((coreType) => {
              const CoreIcon = coreType.icon;
              return (
                <div
                  key={coreType.id}
                  className="flex items-center gap-2 px-3 py-2 rounded-2xl hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer"
                  onClick={() => handleCoreTypeClick(coreType)}
                >
                  <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-orange-100 dark:bg-orange-900/30">
                    <CoreIcon className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                  </div>
                  <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                        {coreType.name}
                      </div>
                      {coreType.description && (
                        <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                          {coreType.description}
                        </div>
                      )}
                    </div>
                    <ArrowRight className="w-4 h-4 text-slate-400 dark:text-slate-500 flex-shrink-0" />
                  </div>
                </div>
              );
            })}
        </div>
      )}

      {/* Core Type Details - Show when navigating to types */}
      {coreNavigationLevel === 'types' && !isCoreGenericNode && !isLogicSubcategory && (
        <div className="space-y-2 overflow-x-hidden">
          {[...CORE_LOGIC_TYPES, ...CORE_DIRECT_TYPES]
            .filter(coreType => 
              coreType.id === coreSelectedType ||
              coreType.id === (isIfElse ? 'if-else' :
                              isUserApproval ? 'user-approval' :
                              isWhile ? 'while' :
                              isTransform ? 'transform' :
                              isHttpRequest ? 'http-request' :
                              isWebhook ? 'webhook' : null)
            )
            .map((coreType) => {
              const isSelected = coreType.id === (node?.data?.id || coreSelectedType);
              const CoreIcon = coreType.icon;
              return (
                <div
                  key={coreType.id}
                  className={clsx(
                    "flex items-center gap-2 px-3 py-2 rounded-2xl transition-colors cursor-pointer",
                    isSelected 
                      ? "bg-gray-100 dark:bg-gray-800 border-2 border-gray-300 dark:border-gray-700"
                      : "hover:bg-gray-50 dark:hover:bg-gray-800/50"
                  )}
                  onClick={() => handleCoreSelect(coreType)}
                >
                  <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-gray-100 dark:bg-gray-800">
                    <CoreIcon className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                  </div>
                  <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                        {coreType.name}
                      </div>
                      {coreType.description && (
                        <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                          {coreType.description}
                        </div>
                      )}
                    </div>
                    {isSelected && (
                      <div className="text-xs text-gray-600 dark:text-gray-400 font-semibold">
                        Selected
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
        </div>
      )}
    </div>
  );
}

