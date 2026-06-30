'use client';

import React from 'react';
import { TestTube, Edit, ChevronDown, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { FormSection } from '../../common';
import { McpTool } from '../../../types';
import { ToolCard } from './ToolCard';

interface Step3SummaryProps {
  mcpTools: McpTool[];
  isExpanded: boolean;
  onToggle: () => void;
  onEdit: () => void;
  toolsExpanded: Record<string, boolean>;
  categoriesExpanded: Record<string, boolean>;
  onToggleTool: (toolKey: string) => void;
  onToggleCategory: (categoryName: string) => void;
}

export function Step3Summary({
  mcpTools,
  isExpanded,
  onToggle,
  onEdit,
  toolsExpanded,
  categoriesExpanded,
  onToggleTool,
  onToggleCategory
}: Step3SummaryProps) {
  const t = useTranslations('developers.confirmation');

  // Group tools by category
  const groupedTools = React.useMemo(() => {
    const groups: Record<string, McpTool[]> = {};
    mcpTools.forEach(tool => {
      const category = tool.toolCategory || 'Other';
      if (!groups[category]) {
        groups[category] = [];
      }
      groups[category].push(tool);
    });
    return groups;
  }, [mcpTools]);

  const sortedCategories = Object.keys(groupedTools).sort();

  return (
    <FormSection
      title={t('step3.title')}
      icon={TestTube}
      iconColor="text-green-500"
      collapsible
      isExpanded={isExpanded}
      onToggle={onToggle}
      actionButton={
        <Button
          onClick={onEdit}
          variant="ghost"
          size="icon"
          className="h-8 w-8"
          title={t('step3.edit')}
        >
          <Edit className="w-4 h-4" />
        </Button>
      }
    >
      <div className="space-y-4">
        <div className="text-sm text-theme-muted mb-4">
          {mcpTools.length === 1
            ? t('tools.singleConfigured', { name: mcpTools[0].name })
            : mcpTools.length > 1
              ? t('tools.multipleConfiguredList', { count: mcpTools.length, names: mcpTools.map(tool => tool.name).join(', ') })
              : t('tools.noneConfigured')
          }
        </div>

        {mcpTools.length === 0 ? (
          <div className="text-center py-8 text-theme-muted">
            <TestTube className="w-12 h-12 mx-auto mb-3 opacity-50" />
            <p>{t('tools.noTool')}</p>
          </div>
        ) : (
          <div className="space-y-6">
            {sortedCategories.map(category => (
              <div key={category} className="space-y-3">
                {/* Category header */}
                <div className="mb-4">
                  <div
                    className="flex items-center justify-between cursor-pointer group"
                    onClick={() => onToggleCategory(category)}
                  >
                    <div className="flex items-center space-x-3">
                      <div className="p-2 rounded-lg bg-theme-secondary group-hover:bg-theme-background transition-colors">
                        {categoriesExpanded[category] ? (
                          <ChevronDown className="w-4 h-4 text-theme-muted" />
                        ) : (
                          <ChevronRight className="w-4 h-4 text-theme-muted" />
                        )}
                      </div>
                      <h4 className="text-base font-medium text-theme-primary capitalize">
                        {category}
                      </h4>
                      <span className="px-2 py-1 bg-theme-secondary text-theme-muted text-xs rounded-full">
                        {groupedTools[category].length} tool{groupedTools[category].length > 1 ? 's' : ''}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Tools in category */}
                {categoriesExpanded[category] && (
                  <div className="space-y-3 pl-4">
                    {groupedTools[category].map((tool, index) => {
                      const toolKey = `${tool.name}-${index}`;
                      return (
                        <ToolCard
                          key={toolKey}
                          tool={tool}
                          toolKey={toolKey}
                          isExpanded={toolsExpanded[toolKey] || false}
                          onToggle={() => onToggleTool(toolKey)}
                        />
                      );
                    })}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </FormSection>
  );
}
