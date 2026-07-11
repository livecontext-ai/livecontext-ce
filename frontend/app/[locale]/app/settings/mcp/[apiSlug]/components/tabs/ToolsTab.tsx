'use client';

import React, { useState, useMemo } from 'react';
import { useRouter, useParams } from 'next/navigation';
import { Search, Wrench, Plus } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

interface Tool {
  id: string;
  name: string;
  description: string;
  endpoint: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  status: 'active' | 'paused' | 'error';
  toolCategory?: string;
  subcategory?: string;
  lastTested?: string;
  successRate?: number;
  responseTime?: number;
}

interface ToolsTabProps {
  tools: Tool[];
}

const ToolsTab: React.FC<ToolsTabProps> = ({ tools }) => {
  const t = useTranslations('mcp.tools');
  const router = useRouter();
  const params = useParams();
  const apiId = params.apiSlug as string;
  const [searchQuery, setSearchQuery] = useState('');

  // Filter tools
  const filteredTools = useMemo(() => {
    const term = searchQuery.trim().toLowerCase();
    return tools.filter((tool) => {
      if (term.length === 0) return true;
      return (
        [tool.name, tool.description || '', tool.endpoint || '', tool.method || '']
          .join(' ')
          .toLowerCase()
          .includes(term)
      );
    });
  }, [tools, searchQuery]);

  // Group tools by category
  const uncategorizedLabel = t('uncategorized');
  const groupedTools = useMemo(() => {
    return filteredTools.reduce((groups, tool) => {
      const category = tool.toolCategory && tool.toolCategory.trim() !== '' ? tool.toolCategory : uncategorizedLabel;
      if (!groups[category]) {
        groups[category] = [];
      }
      groups[category].push(tool);
      return groups;
    }, {} as Record<string, Tool[]>);
  }, [filteredTools, uncategorizedLabel]);

  const handleToolClick = (toolId: string) => {
    router.push(`/app/settings/mcp/${apiId}/${toolId}`);
  };

  const getMethodColor = (method: string) => {
    switch (method) {
      case 'GET':
        return 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300';
      case 'POST':
        return 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-300';
      case 'PUT':
        return 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300';
      case 'DELETE':
        return 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300';
      default:
        return 'bg-gray-100 text-gray-800 dark:bg-gray-800/50 dark:text-gray-300';
    }
  };

  return (
    <div className="space-y-4 w-full overflow-visible">
      {/* Header with title and button */}
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
            <Wrench className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
            <p className="text-sm text-theme-secondary">{t('count', { count: tools.length })}</p>
          </div>
        </div>
        <Button
          onClick={() => {/* TODO: Add tool modal */ }}
          variant="default"
          size="sm"
          className="h-9 px-3"
        >
          <Plus className="h-4 w-4 mr-1.5" />
          {t('addTool')}
        </Button>
      </div>

      {/* Search bar */}
      <div className="flex flex-col gap-4 md:flex-row md:items-center">
        <div className="relative flex-1 overflow-visible">
          <Search className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
          <Input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t('searchPlaceholder')}
            className="flex w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 text-sm text-[var(--text-primary)] ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0 disabled:cursor-not-allowed disabled:opacity-50 pl-11"
          />
        </div>
      </div>

      {/* Tools grouped by category */}
      <div className="space-y-6">
        {Object.keys(groupedTools).length === 0 ? (
          <div className="text-center py-8 text-theme-secondary">
            <Wrench className="w-12 h-12 mx-auto mb-4 text-theme-muted" />
            <p>{t('noToolsFound')}</p>
            <p className="text-sm mt-2 text-theme-muted">
              {tools.length === 0
                ? t('addFirstTool')
                : t('noMatchingTools')}
            </p>
          </div>
        ) : (
          Object.entries(groupedTools).map(([category, categoryTools]) => (
            <div key={category} className="space-y-3">
              {/* Category header */}
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-medium text-theme-secondary">{category}</h3>
                <span className="px-2 py-0.5 bg-theme-tertiary text-theme-secondary text-xs rounded-full">
                  {categoryTools.length}
                </span>
              </div>

              {/* Table for this category */}
              <div className="w-full overflow-x-auto border border-theme rounded-xl overflow-hidden">
                <table className="w-full text-sm" style={{ tableLayout: 'auto' }}>
                  <thead className="bg-theme-secondary border-b border-theme">
                    <tr>
                      <th className="px-3 py-3 text-left font-medium text-theme-secondary min-w-[200px]">{t('table.name')}</th>
                      <th className="px-3 py-3 text-left font-medium text-theme-secondary w-20">{t('table.method')}</th>
                      <th className="px-3 py-3 text-left font-medium text-theme-secondary min-w-[200px]">{t('table.endpoint')}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-theme">
                    {categoryTools.map((tool) => (
                      <tr
                        key={tool.id}
                        className="border border-transparent cursor-pointer transition-colors hover-row-datasource group min-h-[62px] h-[62px]"
                        onClick={() => handleToolClick(tool.id)}
                      >
                        <td className="px-3 py-2">
                          <div className="min-w-0">
                            <div className="font-medium text-theme-primary truncate">{tool.name}</div>
                            <div className="text-sm text-theme-secondary truncate">{tool.description || ''}</div>
                          </div>
                        </td>
                        <td className="px-3 py-2">
                          <span className={`px-2 py-1 text-xs font-medium rounded-full ${getMethodColor(tool.method)}`}>
                            {tool.method}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <span className="text-sm text-theme-secondary truncate block">{tool.endpoint}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

export default ToolsTab;
