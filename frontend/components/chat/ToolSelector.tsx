'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { X, Search, ChevronDown, Check } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import ToolGroupDisplay from '@/app/shared/components/ToolGroupDisplay';

interface Tool {
  id: string;
  name: string;
  description: string;
  category: string;
  icon: any;
  isSelected: boolean;
  isRemote?: boolean;
  serverName?: string;
}

interface ToolGroup {
  serverName: string;
  serverType: 'mcp-local' | 'mcp-remote' | 'api-gateway';
  serverStatus: 'connected' | 'disconnected' | 'error';
  icon: any;
  tools: Tool[];
}

interface ToolSelectorProps {
  isOpen: boolean;
  onClose: () => void;
  mode: 'auto' | 'manual';
  viewMode: 'categories' | 'servers';
  onViewModeChange: (mode: 'categories' | 'servers') => void;
  selectedCategory: string;
  onCategoryChange: (category: string) => void;
  toolSearchQuery: string;
  onSearchChange: (query: string) => void;
  availableTools: Tool[];
  selectedTools: string[];
  onToolSelect: (toolId: string) => void;
  onDeselectAll: () => void;
  toolGroups: ToolGroup[];
}

export const ToolSelector: React.FC<ToolSelectorProps> = ({
  isOpen,
  onClose,
  mode,
  viewMode,
  onViewModeChange,
  selectedCategory,
  onCategoryChange,
  toolSearchQuery,
  onSearchChange,
  availableTools,
  selectedTools,
  onToolSelect,
  onDeselectAll,
  toolGroups
}) => {
  const t = useTranslations('chat.toolSelector');

  if (!isOpen) return null;

  // Filtered tools
  const filteredTools = availableTools.filter(tool => {
    if (selectedCategory === 'all') return true;
    if (selectedCategory === 'local') return !tool.isRemote;
    if (selectedCategory === 'remote') return tool.isRemote;
    return tool.category === selectedCategory;
  });

  // Get unique categories
  const categories = Array.from(new Set(availableTools.map(tool => tool.category))).sort();

  // Calculate tool counts
  const localCount = selectedTools.filter(toolId => {
    const tool = availableTools.find(t => t.id === toolId);
    return tool && !tool.isRemote;
  }).length;
  
  const remoteCount = selectedTools.filter(toolId => {
    const tool = availableTools.find(t => t.id === toolId);
    return tool && tool.isRemote;
  }).length;

  return (
    <div className="p-4 bg-theme-secondary flex-shrink-0 relative z-[60]">
      <div className="mb-4">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h4 className="text-lg font-bold text-theme-primary">{t('title')}</h4>
            <p className="text-sm text-theme-secondary mt-2">
              {mode === 'auto'
                ? t('smartModeHint')
                : t('manualModeHint')
              }
            </p>
            
            {/* Toggle for display mode */}
            <div className="flex items-center gap-2 mt-3">
              <span className="text-sm text-theme-secondary">{t('display')}</span>
              <div className="flex bg-theme-tertiary rounded-lg p-1">
                <Button
                  onClick={() => onViewModeChange('categories')}
                  variant={viewMode === 'categories' ? 'default' : 'ghost'}
                  size="sm"
                  className="text-sm"
                >
                  {t('byCategories')}
                </Button>
                <Button
                  onClick={() => onViewModeChange('servers')}
                  variant={viewMode === 'servers' ? 'default' : 'ghost'}
                  size="sm"
                  className="text-sm"
                >
                  {t('byServers')}
                </Button>
              </div>
            </div>
          </div>
          <Button
            onClick={onClose}
            variant="ghost"
            size="icon"
          >
            <X className="w-5 h-5" />
          </Button>
        </div>

        {/* Category Filters */}
        <div className="mb-4">
          <Select
            value={selectedCategory}
            onValueChange={(value) => onCategoryChange(value)}
          >
            <SelectTrigger className="w-full">
              <SelectValue placeholder={t('selectCategory')} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t('allTools')}</SelectItem>
              <SelectItem value="local">{t('local')}</SelectItem>
              <SelectItem value="remote">{t('remote')}</SelectItem>
              <SelectItem value="separator" disabled>
                ──────────
              </SelectItem>
              {categories.map(category => (
                <SelectItem key={category} value={category}>
                  {category}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* Search Bar */}
        <div className="mb-4">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-theme-secondary" />
            <input
              type="text"
              placeholder={viewMode === 'categories' ? t('searchTools') : t('searchByServer')}
              value={toolSearchQuery}
              onChange={(e) => onSearchChange(e.target.value)}
              className="w-full h-9 pl-10 pr-4 text-sm bg-theme-tertiary border border-theme rounded-lg text-theme-primary placeholder-theme-secondary focus:outline-none focus:ring-1 focus:ring-theme-primary/30 transition-all duration-200"
            />
          </div>
        </div>
      </div>

      {/* Conditional display based on mode */}
      {viewMode === 'categories' ? (
        /* View by categories */
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3 max-h-64 overflow-y-auto">
          {filteredTools
            .filter(tool =>
              tool.name.toLowerCase().includes(toolSearchQuery.toLowerCase()) ||
              tool.description.toLowerCase().includes(toolSearchQuery.toLowerCase()) ||
              tool.category.toLowerCase().includes(toolSearchQuery.toLowerCase())
            )
            .sort((a, b) => {
              // Prioritize selected tools
              const aSelected = selectedTools.includes(a.id) || (mode === 'auto' && !a.isRemote);
              const bSelected = selectedTools.includes(b.id) || (mode === 'auto' && !b.isRemote);
              if (aSelected && !bSelected) return -1;
              if (!aSelected && bSelected) return 1;
              return 0;
            })
            .map((tool) => (
              <button
                key={tool.id}
                onClick={() => onToolSelect(tool.id)}
                disabled={mode === 'auto'}
                className={`p-3 rounded-lg border transition-all duration-200 text-left group ${
                  selectedTools.includes(tool.id) || (mode === 'auto' && !tool.isRemote)
                    ? 'border-theme-primary bg-theme-primary/10'
                    : 'border-theme bg-theme-tertiary hover:bg-theme-primary/5 hover:border-theme-primary/30'
                }`}
              >
                <div className="flex items-center space-x-3">
                  <div className={`w-10 h-10 rounded-xl flex items-center justify-center transition-all duration-300 ${
                    selectedTools.includes(tool.id) || (mode === 'auto' && !tool.isRemote)
                      ? 'bg-theme-primary text-theme-secondary'
                      : 'bg-theme-primary/10 text-theme-primary group-hover:bg-theme-primary/20'
                  }`}>
                    <tool.icon className="w-5 h-5" />
                  </div>
                  <div className="flex-1">
                    <h4 className="font-semibold text-sm text-theme-primary">{tool.name}</h4>
                    <p className="text-sm mt-1.5 text-theme-secondary">{tool.description}</p>
                    <div className="flex items-center gap-2 mt-2">
                      <span className="text-xs px-2 py-1 rounded-full text-theme-secondary bg-theme-primary/10 border border-theme-primary/20">
                        {tool.category}
                      </span>
                      <span className={`text-xs px-2 py-1 rounded-full font-medium ${
                        tool.isRemote 
                          ? 'text-slate-600 bg-slate-100 border border-slate-200 dark:text-slate-400 dark:bg-slate-800/50 dark:border-slate-700' 
                          : 'text-slate-700 bg-slate-50 border border-slate-200 dark:text-slate-300 dark:bg-slate-800/30 dark:border-slate-600'
                      }`}>
                        {tool.isRemote ? t('remote') : t('local')}
                      </span>
                    </div>
                  </div>
                  {(selectedTools.includes(tool.id) || (mode === 'auto' && !tool.isRemote)) && (
                    <Check className="w-5 h-5 text-theme-primary" />
                  )}
                </div>
              </button>
            ))}
        </div>
      ) : (
        /* View by servers */
        <div className="max-h-64 overflow-y-auto">
          <ToolGroupDisplay 
            groups={toolGroups.map(group => ({
              ...group,
              tools: group.tools.filter(tool => {
                // Apply the same filters as the categories view
                const matchesCategory = selectedCategory === 'all' || 
                                      selectedCategory === 'local' && !tool.isRemote ||
                                      selectedCategory === 'remote' && tool.isRemote ||
                                      tool.category === selectedCategory;
                
                const matchesSearch = tool.category.toLowerCase().includes(toolSearchQuery.toLowerCase()) ||
                                    tool.name.toLowerCase().includes(toolSearchQuery.toLowerCase()) ||
                                    tool.description.toLowerCase().includes(toolSearchQuery.toLowerCase()) ||
                                    (tool.serverName && tool.serverName.toLowerCase().includes(toolSearchQuery.toLowerCase())) ||
                                    group.serverName.toLowerCase().includes(toolSearchQuery.toLowerCase());
                
                return matchesCategory && matchesSearch;
              }).sort((a, b) => {
                // Sort tools: selected first
                const aSelected = selectedTools.includes(a.id) || (mode === 'auto' && !a.isRemote);
                const bSelected = selectedTools.includes(b.id) || (mode === 'auto' && !b.isRemote);
                if (aSelected && !bSelected) return -1;
                if (!aSelected && bSelected) return 1;
                return 0;
              })
            })).filter(group => group.tools.length > 0) // Hide servers without tools
            .sort((a, b) => {
              // Sort groups: those with selected tools first
              const aSelectedCount = a.tools.filter(tool => 
                selectedTools.includes(tool.id) || (mode === 'auto' && !tool.isRemote)
              ).length;
              const bSelectedCount = b.tools.filter(tool => 
                selectedTools.includes(tool.id) || (mode === 'auto' && !tool.isRemote)
              ).length;
              if (aSelectedCount > bSelectedCount) return -1;
              if (aSelectedCount < bSelectedCount) return 1;
              return 0;
            })}
            selectedTools={selectedTools}
            onToolSelect={onToolSelect}
            mode={mode === 'auto' ? 'intelligent' : 'manual'}
          />
        </div>
      )}

      {/* Tool Selector Footer */}
      <div className="mt-4 pt-4 border-t border-theme/20">
        <div className="flex items-center justify-between">
          <div className="text-sm text-theme-secondary">
            <div className="flex items-center gap-4">
              {localCount > 0 && (
                <span className="flex items-center gap-1">
                  <span className="w-2 h-2 bg-slate-600 dark:bg-slate-400 rounded-full"></span>
                  {localCount} local{localCount > 1 ? 's' : ''}
                </span>
              )}
              {remoteCount > 0 && (
                <span className="flex items-center gap-1">
                  <span className="w-2 h-2 bg-slate-400 dark:bg-slate-500 rounded-full"></span>
                  {remoteCount} remote{remoteCount > 1 ? 's' : ''}
                </span>
              )}
              {localCount === 0 && remoteCount === 0 && (
                <span>{t('noToolSelected')}</span>
              )}
            </div>
          </div>
          {selectedTools.length > 0 && mode === 'manual' && (
            <Button
              onClick={onDeselectAll}
              variant="ghost"
              size="sm"
            >
              {t('deselectAll')}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
};
