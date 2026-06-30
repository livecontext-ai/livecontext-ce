import React, { useState } from 'react';
import { ChevronLeft, ChevronRight, Plus } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { LocalMcpTool } from '../types';
import { ToolStats } from './step2/ToolStats';
import { NewToolForm } from './step2/NewToolForm';
import { ConfiguredToolsList } from './step2/ConfiguredToolsList';
import { TOOL_TYPES } from './step2/constants';

interface Step2Props {
  mcpTools: LocalMcpTool[];
  addMcpTool: (tool: Partial<LocalMcpTool>) => LocalMcpTool;
  updateMcpTool: (index: number, tool: Partial<LocalMcpTool>) => void;
  removeMcpTool: (index: number) => void;
  selectedCategory: string;
  selectedSubcategory: string;
  toolName: string;
  toolDescription: string;
  goToPrevStep: () => void;
  goToNextStep: () => void;
}

export default function Step2({
  mcpTools,
  addMcpTool,
  updateMcpTool,
  removeMcpTool,
  selectedCategory,
  selectedSubcategory,
  toolName,
  toolDescription,
  goToPrevStep,
  goToNextStep
}: Step2Props) {
  const [showNewToolForm, setShowNewToolForm] = useState(mcpTools.length === 0);
  const t = useTranslations('localMcp');
  const [newTool, setNewTool] = useState({
    name: toolName || '',
    description: toolDescription || '',
    toolCategory: 'Autres',
    toolType: 'LOCAL_COMMAND' as LocalMcpTool['toolType'],
    command: ''
  });

  const handleAddTool = () => {
    if (newTool.name && newTool.command) {
      addMcpTool({
        name: newTool.name,
        description: newTool.description,
        toolCategory: newTool.toolCategory,
        toolType: newTool.toolType,
        command: newTool.command,
        category: selectedCategory,
        subcategory: selectedSubcategory
      });

      setNewTool({
        name: '',
        description: '',
        toolCategory: 'Autres',
        toolType: 'LOCAL_COMMAND',
        command: ''
      });
      setShowNewToolForm(false);
    }
  };

  const handleToolUpdate = (index: number, field: keyof LocalMcpTool, value: string | boolean) => {
    updateMcpTool(index, { [field]: value } as Partial<LocalMcpTool>);
  };

  const canProceed = mcpTools.length > 0 && mcpTools.every(tool =>
    tool.name && tool.command && tool.toolCategory
  );

  return (
    <div className="space-y-8">
      <Card className="bg-theme-secondary border-theme">
        <CardHeader>
          <CardTitle className="text-theme-primary">{t('step2.title')}</CardTitle>
          <CardDescription className="text-theme-secondary">
            {t('step2.description')}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ToolStats
            toolCount={mcpTools.length}
            typeCount={new Set(mcpTools.map(t => t.toolType)).size}
            categoryCount={new Set(mcpTools.map(t => t.toolCategory)).size}
          />
        </CardContent>
      </Card>

      {showNewToolForm ? (
        <NewToolForm
          newTool={newTool}
          toolTypes={TOOL_TYPES}
          onFieldChange={(field, value) => setNewTool(prev => ({ ...prev, [field]: value }))}
          onSubmit={handleAddTool}
          onCancel={() => setShowNewToolForm(false)}
        />
      ) : (
        <div className="text-center">
          <Button
            onClick={() => setShowNewToolForm(true)}
            className="bg-blue-600 hover:bg-blue-700 text-white"
            size="lg"
          >
            <Plus className="w-4 h-4 mr-2" />
            {t('step2.addNewTool')}
          </Button>
        </div>
      )}

      <ConfiguredToolsList
        tools={mcpTools}
        onUpdate={(index, field, value) => handleToolUpdate(index, field, value)}
        onRemove={removeMcpTool}
      />

      <div className="flex justify-between">
        <Button
          onClick={goToPrevStep}
          variant="outline"
          className="bg-theme-tertiary border-theme text-theme-primary"
        >
          <ChevronLeft className="w-4 h-4 mr-2" />
          {t('navigation.previous')}
        </Button>

        <Button
          onClick={goToNextStep}
          disabled={!canProceed}
          className="bg-blue-600 hover:bg-blue-700 text-white"
        >
          {t('navigation.continue')}
          <ChevronRight className="w-4 h-4 ml-2" />
        </Button>
      </div>
    </div>
  );
}
