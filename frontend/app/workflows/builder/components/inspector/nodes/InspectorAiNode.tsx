'use client';

import * as React from 'react';
import clsx from 'clsx';
import { ArrowRight } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { AI_TYPES } from '../nodeTypes';

interface InspectorAiNodeProps {
  // Navigation state
  aiNavigationLevel: 'ai' | 'types';
  aiSearchQuery: string;
  setAiSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  aiSelectedType: string | null;
  
  // Node detection
  isAiGenericNode: boolean;
  isAiAgent: boolean;
  isAiSummarize: boolean;
  isGuardrail: boolean;
  isClassify: boolean;
  
  // Handlers
  handleAiTypeClick: (aiType: typeof AI_TYPES[0]) => void;
  handleAiSelect: (aiType: typeof AI_TYPES[0]) => void;
  
  // Node
  node: any;
}

export function InspectorAiNode({
  aiNavigationLevel,
  aiSearchQuery,
  setAiSearchQuery,
  aiSelectedType,
  isAiGenericNode,
  isAiAgent,
  isAiSummarize,
  isGuardrail,
  isClassify,
  handleAiTypeClick,
  handleAiSelect,
  node,
}: InspectorAiNodeProps) {
  if (!isAiGenericNode) {
    return null; // Only show for generic AI nodes
  }

  return (
    <div className="space-y-4 pt-2">
      {/* Search */}
      <div className="relative">
        <Input
          type="text"
          placeholder="Search for an AI type..."
          value={aiSearchQuery}
          onChange={(e) => setAiSearchQuery(e.target.value)}
          className="w-full"
        />
      </div>

      {/* AI List - Show for generic AI node */}
      {aiNavigationLevel === 'ai' && (
        <div className="space-y-2 overflow-x-hidden">
          {AI_TYPES
            .filter(aiType => 
              !aiSearchQuery.trim() ||
              aiType.name.toLowerCase().includes(aiSearchQuery.toLowerCase()) ||
              aiType.description?.toLowerCase().includes(aiSearchQuery.toLowerCase())
            )
            .map((aiType) => {
              const AiIcon = aiType.icon;
              return (
                <div
                  key={aiType.id}
                  className="flex items-center gap-2 px-3 py-2 rounded-2xl hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer"
                  onClick={() => handleAiTypeClick(aiType)}
                >
                  <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-blue-100 dark:bg-blue-900/30">
                    <AiIcon className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                  </div>
                  <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                        {aiType.name}
                      </div>
                      {aiType.description && (
                        <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                          {aiType.description}
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

      {/* AI Type Details - Show when navigating to types (but not when already converted) */}
      {aiNavigationLevel === 'types' && !isAiAgent && !isAiSummarize && !isGuardrail && !isClassify && (
        <div className="space-y-2 overflow-x-hidden">
          {AI_TYPES
            .filter(aiType => 
              aiType.id === aiSelectedType ||
              aiType.id === (isAiAgent ? 'ai-agent' :
                            isAiSummarize ? 'ai-summarize' :
                            isGuardrail ? 'guardrail' :
                            isClassify ? 'classify' : null)
            )
            .map((aiType) => {
              const isSelected = aiType.id === (node?.data?.id || aiSelectedType);
              const AiIcon = aiType.icon;
              return (
                <div
                  key={aiType.id}
                  className={clsx(
                    "flex items-center gap-2 px-3 py-2 rounded-2xl transition-colors cursor-pointer",
                    isSelected 
                      ? "bg-blue-100 dark:bg-blue-900/30 border-2 border-blue-300 dark:border-blue-700"
                      : "hover:bg-gray-50 dark:hover:bg-gray-800/50"
                  )}
                  onClick={() => handleAiSelect(aiType)}
                >
                  <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-blue-100 dark:bg-blue-900/30">
                    <AiIcon className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                  </div>
                  <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                        {aiType.name}
                      </div>
                      {aiType.description && (
                        <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                          {aiType.description}
                        </div>
                      )}
                    </div>
                    {isSelected && (
                      <div className="text-xs text-blue-600 dark:text-blue-400 font-semibold">
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

