import React from 'react';
import { Save, SquarePen } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ConfigTabProps, HTTP_METHODS } from './types';
import { UriInput } from '../common';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

const ConfigTab: React.FC<ConfigTabProps> = ({
  tool,
  toolIndex,
  onToolUpdate,
  apiConfig,
  isEditingDescription,
  onToggleEditingDescription,
  onEndpointChange
}) => {
  const t = useTranslations('developers');

  const handleMethodChange = (value: string) => {
    const updatedTool = { ...tool, method: value as typeof tool.method };
    onToolUpdate(toolIndex, updatedTool);
  };

  const handleDescriptionChange = (description: string) => {
    const updatedTool = { ...tool, description };
    onToolUpdate(toolIndex, updatedTool);
  };

  const canSaveDescription = tool.description && tool.description.trim();

  return (
    <div className="space-y-0">
      {/* HTTP Method and Endpoint - Desktop */}
      <div className="hidden sm:flex gap-6">
        <div className="flex-shrink-0">
          <label className="block text-sm font-medium text-theme-primary mb-2">
            {t('configTab.httpMethod')} *
          </label>
          <Select value={tool.method} onValueChange={handleMethodChange}>
            <SelectTrigger className="w-32">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {HTTP_METHODS.map(method => (
                <SelectItem key={method} value={method}>{method}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="flex-1">
          <UriInput
            value={tool.endpoint}
            onChange={onEndpointChange}
            baseUrl={apiConfig.baseUrl || 'https://api.example.com'}
            placeholder="/api/users"
            label={t('configTab.relativeEndpoint')}
            required={true}
            showFullUrl={true}
            showValidation={true}
          />
        </div>
      </div>

      {/* HTTP Method and Endpoint - Mobile */}
      <div className="sm:hidden space-y-4">
        <div className="space-y-2">
          <label className="block text-sm font-medium text-theme-primary">
            {t('configTab.httpMethod')} *
          </label>
          <select
            value={tool.method}
            onChange={(e) => handleMethodChange(e.target.value)}
            className="w-full h-9 px-4 text-sm bg-theme-primary border border-theme rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition-all duration-300"
          >
            {HTTP_METHODS.map(method => (
              <option key={method} value={method}>{method}</option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <label className="block text-sm font-medium text-theme-primary">
            {t('configTab.relativeEndpoint')} *
          </label>
          <UriInput
            value={tool.endpoint}
            onChange={onEndpointChange}
            baseUrl={apiConfig.baseUrl || 'https://api.example.com'}
            placeholder="/api/users"
            label=""
            required={true}
            showFullUrl={true}
            showValidation={true}
          />
        </div>
      </div>

      {/* Tool Description */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <label className="block text-sm font-medium text-theme-primary">
            {t('configTab.toolDescription')} *
          </label>
          <button
            type="button"
            onClick={() => {
              if (isEditingDescription) {
                if (canSaveDescription) {
                  onToggleEditingDescription();
                  onToolUpdate(toolIndex, tool);
                } else {
                  alert(t('configTab.enterDescriptionAlert'));
                }
              } else {
                onToggleEditingDescription();
              }
            }}
            className={`p-2 rounded transition-colors duration-200 ${
              isEditingDescription
                ? (canSaveDescription
                    ? 'text-theme-muted hover:text-theme-primary'
                    : 'text-theme-muted cursor-not-allowed')
                : 'text-theme-muted hover:text-theme-primary'
            }`}
            disabled={isEditingDescription && !canSaveDescription}
            title={isEditingDescription ? t('configTab.save') : t('configTab.edit')}
          >
            {isEditingDescription ? (
              <Save className="w-4 h-4" />
            ) : (
              <SquarePen className="w-4 h-4" />
            )}
          </button>
        </div>

        {isEditingDescription ? (
          <div>
            <textarea
              value={tool.description || ''}
              onChange={(e) => handleDescriptionChange(e.target.value)}
              rows={3}
              className="w-full px-4 py-3 bg-theme-primary border border-theme rounded-lg text-theme-primary placeholder-theme-muted focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition-all duration-300 resize-none"
              placeholder={t('configTab.descriptionPlaceholder')}
            />
            <div className="flex justify-between items-center mt-1">
              <span className={`text-xs ${(tool.description?.length || 0) > 250 ? 'text-red-500' : 'text-gray-500'}`}>
                {tool.description?.length || 0}/250 {t('configTab.characters')}
              </span>
            </div>
          </div>
        ) : (
          <div className={`px-4 py-3 border rounded-lg transition-all duration-300 ${
            canSaveDescription
              ? 'bg-gray-100 dark:bg-gray-800 border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300'
              : 'bg-gray-50 dark:bg-gray-900 border-gray-200 dark:border-gray-700 text-gray-400 dark:text-gray-500'
          }`}>
            {(tool.description && tool.description.trim()) || t('configTab.noDescription')}
          </div>
        )}
      </div>
    </div>
  );
};

export default ConfigTab;
