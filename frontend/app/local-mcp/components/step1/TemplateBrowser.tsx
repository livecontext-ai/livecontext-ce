import React, { useMemo, useState } from 'react';
import { Search, Sparkles } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { LOCAL_MCP_CATEGORIES, POPULAR_MCP_TEMPLATES } from '../../types';
import { useTranslations } from 'next-intl';

interface TemplateBrowserProps {
  onSelect: (template: typeof POPULAR_MCP_TEMPLATES[0]) => void;
}

export const TemplateBrowser: React.FC<TemplateBrowserProps> = ({ onSelect }) => {
  const [searchTerm, setSearchTerm] = useState('');
  const t = useTranslations('localMcp');

  const filteredTemplates = useMemo(() => {
    const term = searchTerm.toLowerCase();
    return POPULAR_MCP_TEMPLATES.filter(template =>
      template.name.toLowerCase().includes(term) ||
      template.description.toLowerCase().includes(term)
    );
  }, [searchTerm]);

  return (
    <Card className="bg-theme-secondary border-theme">
      <CardHeader>
        <div className="flex items-center gap-2">
          <Sparkles className="w-6 h-6 text-blue-600" />
          <div>
            <CardTitle className="text-theme-primary">{t('templateBrowser.title')}</CardTitle>
            <CardDescription className="text-theme-secondary">
              {t('templateBrowser.description')}
            </CardDescription>
          </div>
        </div>
      </CardHeader>

      <CardContent>
        <div className="mb-6">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-theme-muted" />
            <Input
              placeholder={t('templateBrowser.searchPlaceholder')}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="pl-10 bg-theme-tertiary border-theme text-theme-primary"
            />
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredTemplates.map((template) => (
            <div
              key={template.name}
              className="bg-theme-tertiary p-5 rounded-xl border border-theme group hover:border-blue-500 transition-all cursor-pointer hover:shadow-lg transform hover:-translate-y-1"
              onClick={() => onSelect(template)}
            >
              <div className="flex items-start gap-3">
                <div className="text-3xl">{template.icon}</div>
                <div className="flex-1">
                  <h4 className="font-semibold text-theme-primary text-sm mb-1 group-hover:text-blue-600 transition-colors">
                    {template.name}
                  </h4>
                  <p className="text-xs text-theme-secondary mb-3 line-clamp-2">
                    {template.description}
                  </p>
                  <div className="flex flex-wrap gap-1 mb-3">
                    <span className="inline-block px-2 py-1 text-xs bg-blue-500/20 text-blue-600 dark:text-blue-400 rounded-full">
                      {LOCAL_MCP_CATEGORIES.find(c => c.id === template.category)?.name}
                    </span>
                    <span className="inline-block px-2 py-1 text-xs bg-gray-500/20 text-gray-600 dark:text-gray-400 rounded-full">
                      {template.toolType.replace('LOCAL_', '')}
                    </span>
                  </div>
                  <code className="text-xs bg-theme-primary/10 px-2 py-1 rounded text-theme-primary block truncate">
                    {template.command}
                  </code>
                </div>
              </div>

              <div className="mt-4 flex justify-end">
                <Button
                  size="sm"
                  className="bg-blue-600 hover:bg-blue-700 text-white opacity-0 group-hover:opacity-100 transition-opacity"
                >
                  {t('templateBrowser.use')}
                </Button>
              </div>
            </div>
          ))}
        </div>

        {filteredTemplates.length === 0 && (
          <div className="text-center py-8">
            <p className="text-theme-secondary">{t('templateBrowser.noResults', { search: searchTerm })}</p>
          </div>
        )}
      </CardContent>
    </Card>
  );
};

export default TemplateBrowser;
