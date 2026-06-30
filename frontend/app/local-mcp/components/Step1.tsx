import React, { useState } from 'react';
import { ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { TemplateBrowser } from './step1/TemplateBrowser';
import { CustomToolForm } from './step1/CustomToolForm';
import { POPULAR_MCP_TEMPLATES } from '../types';

interface Step1Props {
  selectedCategory: string;
  setSelectedCategory: (category: string) => void;
  selectedSubcategory: string;
  setSelectedSubcategory: (subcategory: string) => void;
  toolName: string;
  setToolName: (name: string) => void;
  toolDescription: string;
  setToolDescription: (description: string) => void;
  onUseTemplate: (template: typeof POPULAR_MCP_TEMPLATES[0]) => void;
  goToNextStep: () => void;
}

export default function Step1({
  selectedCategory,
  setSelectedCategory,
  selectedSubcategory,
  setSelectedSubcategory,
  toolName,
  setToolName,
  toolDescription,
  setToolDescription,
  onUseTemplate,
  goToNextStep
}: Step1Props) {
  const [showCustomForm, setShowCustomForm] = useState(false);
  const t = useTranslations('localMcp');

  const handleCategorySelect = (categoryId: string) => {
    setSelectedCategory(categoryId);
    setSelectedSubcategory('');
  };

  const handleSubcategorySelect = (subcategoryId: string) => {
    setSelectedSubcategory(subcategoryId);
  };

  const handleTemplateUse = (template: typeof POPULAR_MCP_TEMPLATES[0]) => {
    onUseTemplate(template);
    setSelectedCategory(template.category);
    setSelectedSubcategory(template.subcategory);
    goToNextStep();
  };

  const handleCustomToolNext = () => {
    if (toolName && toolDescription && selectedCategory && selectedSubcategory) {
      goToNextStep();
    }
  };

  const isNextEnabled = showCustomForm
    ? toolName.trim() && toolDescription.trim() && selectedCategory && selectedSubcategory
    : false;

  return (
    <div className="space-y-8">
      <TemplateBrowser onSelect={handleTemplateUse} />

      <CustomToolForm
        showForm={showCustomForm}
        onToggleForm={() => setShowCustomForm(true)}
        selectedCategory={selectedCategory}
        onSelectCategory={handleCategorySelect}
        selectedSubcategory={selectedSubcategory}
        onSelectSubcategory={handleSubcategorySelect}
        toolName={toolName}
        onToolNameChange={setToolName}
        toolDescription={toolDescription}
        onToolDescriptionChange={setToolDescription}
      />

      {/* Navigation */}
      <div className="flex justify-end">
        <Button
          onClick={handleCustomToolNext}
          disabled={!isNextEnabled}
          className="bg-blue-600 hover:bg-blue-700 text-white"
          size="lg"
        >
            {t('navigation.continue')}
          <ChevronRight className="w-4 h-4 ml-2" />
        </Button>
      </div>
    </div>
  );
}
