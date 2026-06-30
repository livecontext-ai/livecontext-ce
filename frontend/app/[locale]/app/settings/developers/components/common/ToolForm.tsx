import React, { useState } from 'react';
import { Plus, X } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { FormField, FormInput, FormSelect, FormTextarea, ActionButton } from './index';

interface ToolFormProps {
  toolData: {
    name: string;
    description: string;
    toolCategory: string;
  };
  onToolDataChange: (field: string, value: string) => void;
  onAddTool: () => void;
  canAddTool: boolean;
  predefinedToolNames: string[];
  toolCategories: string[];
  onAddNewToolName: (name: string) => void;
  onAddNewToolCategory: (category: string) => void;
}

const ToolForm: React.FC<ToolFormProps> = ({
  toolData,
  onToolDataChange,
  onAddTool,
  canAddTool,
  predefinedToolNames,
  toolCategories,
  onAddNewToolName,
  onAddNewToolCategory
}) => {
  const t = useTranslations('developers');
  const [showNewToolNameForm, setShowNewToolNameForm] = useState(false);
  const [newToolName, setNewToolName] = useState('');
  const [showNewToolCategoryForm, setShowNewToolCategoryForm] = useState(false);
  const [newToolCategory, setNewToolCategory] = useState('');

  const handleAddNewToolName = () => {
    if (newToolName.trim()) {
      onAddNewToolName(newToolName);
      setNewToolName('');
      setShowNewToolNameForm(false);
    }
  };

  const handleAddNewToolCategory = () => {
    if (newToolCategory.trim()) {
      onAddNewToolCategory(newToolCategory);
      setNewToolCategory('');
      setShowNewToolCategoryForm(false);
    }
  };

  // Prepare options for FormSelect
  const toolCategoryOptions = toolCategories.map(cat => ({
    value: cat,
    label: cat
  }));

  const toolNameOptions = predefinedToolNames.map(name => ({
    value: name,
    label: name
  }));

  return (
    <div className="p-6 bg-theme-tertiary rounded-xl border border-theme">
      <h4 className="text-lg font-medium text-theme-primary mb-4">{t('toolForm.title')}</h4>

      {/* Tool category */}
      <div className="mb-6">
        <div className="flex items-center justify-between mb-3">
          <FormField
            label={t('toolForm.toolCategory')}
            required
          >
            <div className="space-y-3">
              <FormSelect
                value={toolData.toolCategory}
                onChange={(value) => onToolDataChange('toolCategory', value)}
                options={toolCategoryOptions}
                placeholder={t('toolForm.selectToolCategory')}
              />
              
              <ActionButton
                onClick={() => setShowNewToolCategoryForm(!showNewToolCategoryForm)}
                variant="secondary"
                size="sm"
                icon={Plus}
                className="w-full"
              >
              </ActionButton>
            </div>
          </FormField>
        </div>

        {showNewToolCategoryForm && (
          <div className="mb-4 p-4 bg-theme-primary rounded-lg border border-theme">
            <div className="flex items-center space-x-3">
              <FormInput
                value={newToolCategory}
                onChange={(value) => setNewToolCategory(value)}
                placeholder={t('toolForm.categoryPlaceholder')}
                className="flex-1"
              />
              <ActionButton
                onClick={handleAddNewToolCategory}
                variant="primary"
                size="sm"
                className="min-w-[100px]"
              >
                {t('toolForm.add')}
              </ActionButton>
              <ActionButton
                onClick={() => setShowNewToolCategoryForm(false)}
                variant="secondary"
                size="sm"
                icon={X}
                className="min-w-[100px]"
              >
                {t('toolForm.cancel')}
              </ActionButton>
            </div>
          </div>
        )}
      </div>

      {/* Tool name */}
      <div className="mb-6">
        <div className="flex items-center justify-between mb-3">
          <FormField
            label={t('toolForm.toolName')}
            required
          >
            <div className="space-y-3">
              <FormSelect
                value={toolData.name}
                onChange={(value) => onToolDataChange('name', value)}
                options={toolNameOptions}
                placeholder={t('toolForm.selectToolName')}
              />
              
              <Button
                onClick={() => setShowNewToolNameForm(!showNewToolNameForm)}
                variant="ghost"
                size="icon"
                className="rounded-full w-10 h-10 min-w-[44px] min-h-[44px]"
              >
                <Plus className="h-4 w-4" />
              </Button>
            </div>
          </FormField>
        </div>

        {showNewToolNameForm && (
          <div className="mb-4 p-4 bg-theme-primary rounded-lg border border-theme">
            <div className="flex items-center space-x-3">
              <FormInput
                value={newToolName}
                onChange={(value) => setNewToolName(value)}
                placeholder={t('toolForm.toolNamePlaceholder')}
                className="flex-1"
              />
              <Button
                onClick={handleAddNewToolName}
                variant="default"
                size="sm"
                className="min-w-[100px]"
              >
                {t('toolForm.add')}
              </Button>
              <Button
                onClick={() => setShowNewToolNameForm(false)}
                variant="ghost"
                size="sm"
                className="min-w-[100px]"
              >
                <X className="w-4 h-4 mr-1" />
                {t('toolForm.cancel')}
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* Tool description */}
      <div className="mb-6">
        <FormField
          label={t('toolForm.toolDescription')}
          required
        >
          <FormTextarea
            value={toolData.description}
            onChange={(value) => onToolDataChange('description', value)}
            rows={3}
            placeholder={t('toolForm.descriptionPlaceholder')}
          />
        </FormField>
      </div>

      {/* Add button */}
      <div className="flex justify-end">
        <Button
          onClick={onAddTool}
          disabled={!canAddTool}
          variant="ghost"
          size="default"
          className="min-w-[200px]"
        >
          <Plus className="h-4 w-4 mr-2" />
          {t('toolForm.addToApi')}
        </Button>
      </div>
    </div>
  );
};

export default ToolForm;
