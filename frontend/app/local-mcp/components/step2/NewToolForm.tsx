import React from 'react';
import { Plus } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { LocalMcpTool, TOOL_CATEGORIES } from '../../types';
import { ToolTypeConfig } from './constants';
import { useTranslations } from 'next-intl';

interface NewToolFormProps {
  newTool: {
    name: string;
    description: string;
    toolCategory: string;
    toolType: LocalMcpTool['toolType'];
    command: string;
  };
  toolTypes: ToolTypeConfig[];
  onFieldChange: (field: keyof NewToolFormProps['newTool'], value: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
}

const getPlaceholder = (toolType: LocalMcpTool['toolType']) => {
  switch (toolType) {
    case 'LOCAL_COMMAND':
      return 'ls -la';
    case 'LOCAL_PYTHON':
      return 'python script.py';
    case 'LOCAL_NODEJS':
      return 'node script.js';
    case 'LOCAL_DATABASE':
      return 'psql -c "SELECT * FROM users"';
    default:
      return 'curl -X GET https://api.example.com/data';
  }
};

export const NewToolForm: React.FC<NewToolFormProps> = ({
  newTool,
  toolTypes,
  onFieldChange,
  onSubmit,
  onCancel
}) => {
  const t = useTranslations('localMcp');
  return (
  <Card className="bg-theme-secondary border-theme">
    <CardHeader>
      <CardTitle className="text-theme-primary">{t('newToolForm.title')}</CardTitle>
      <CardDescription className="text-theme-secondary">
        {t('newToolForm.description')}
      </CardDescription>
    </CardHeader>

    <CardContent className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div>
          <Label htmlFor="newToolName" className="text-theme-primary">
            {t('newToolForm.toolNameLabel')}
          </Label>
          <Input
            id="newToolName"
            value={newTool.name}
            onChange={(e) => onFieldChange('name', e.target.value)}
            placeholder={t('newToolForm.toolNamePlaceholder')}
            className="bg-theme-tertiary border-theme text-theme-primary"
          />
        </div>

        <div>
          <Label htmlFor="newToolCategory" className="text-theme-primary">
            {t('newToolForm.toolCategoryLabel')}
          </Label>
          <Select
            value={newTool.toolCategory}
            onValueChange={(value) => onFieldChange('toolCategory', value)}
          >
            <SelectTrigger className="bg-theme-tertiary border-theme text-theme-primary">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {TOOL_CATEGORIES.map(category => (
                <SelectItem key={category} value={category}>
                  {category}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <div>
        <Label htmlFor="newToolDescription" className="text-theme-primary">
          {t('newToolForm.descriptionLabel')}
        </Label>
        <Textarea
          id="newToolDescription"
          value={newTool.description}
          onChange={(e) => onFieldChange('description', e.target.value)}
          placeholder={t('newToolForm.descriptionPlaceholder')}
          rows={3}
          className="bg-theme-tertiary border-theme text-theme-primary"
        />
      </div>

      <div>
        <Label className="text-theme-primary mb-3 block">{t('newToolForm.toolTypeLabel')}</Label>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
          {toolTypes.map((type) => {
            const Icon = type.icon;
            return (
              <button
                type="button"
                key={type.value}
                onClick={() => onFieldChange('toolType', type.value)}
                className={`text-left p-4 rounded-lg border transition-all ${
                  newTool.toolType === type.value
                    ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/30'
                    : 'border-theme bg-theme-tertiary hover:border-blue-300'
                }`}
              >
                <div className="flex items-center gap-3">
                  <Icon className="w-6 h-6 text-blue-600" />
                  <div>
                    <h4 className="font-medium text-theme-primary text-sm">{type.label}</h4>
                    <p className="text-xs text-theme-secondary">{type.description}</p>
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      </div>

      <div>
        <Label htmlFor="newToolCommand" className="text-theme-primary">
          {t('newToolForm.commandLabel')}
        </Label>
        <Textarea
          id="newToolCommand"
          value={newTool.command}
          onChange={(e) => onFieldChange('command', e.target.value)}
          placeholder={getPlaceholder(newTool.toolType)}
          rows={2}
          className="bg-theme-tertiary border-theme text-theme-primary font-mono"
        />
      </div>

      <div className="flex gap-3">
        <Button
          onClick={onSubmit}
          disabled={!newTool.name || !newTool.command}
          className="bg-blue-600 hover:bg-blue-700 text-white"
        >
          <Plus className="w-4 h-4 mr-2" />
          {t('newToolForm.addTool')}
        </Button>

        <Button
          onClick={onCancel}
          variant="outline"
          className="bg-theme-tertiary border-theme text-theme-primary"
        >
          {t('newToolForm.cancel')}
        </Button>
      </div>
    </CardContent>
  </Card>
  );
};

export default NewToolForm;
