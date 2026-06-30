import React, { useState } from 'react';
import { ParameterList } from './step3/ParameterList';
import { ToolSelector } from './step3/ToolSelector';
import { SchemaEditor } from './step3/SchemaEditor';
import { AdvancedSettings } from './step3/AdvancedSettings';
import { ChevronLeft, ChevronRight, Code, FileText } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { LocalMcpTool, McpParameter } from '../types';
import { useTranslations } from 'next-intl';

interface Step3Props {
  mcpTools: LocalMcpTool[];
  updateMcpTool: (index: number, tool: Partial<LocalMcpTool>) => void;
  goToPrevStep: () => void;
  goToNextStep: () => void;
}

const PARAMETER_TYPES = [
  { value: 'string', label: 'Text' },
  { value: 'number', label: 'Number' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'object', label: 'Object' },
  { value: 'array', label: 'Array' }
];

export default function Step3({
  mcpTools,
  updateMcpTool,
  goToPrevStep,
  goToNextStep
}: Step3Props) {
  const [selectedToolIndex, setSelectedToolIndex] = useState(0);
  const [activeTab, setActiveTab] = useState('parameters');
  const t = useTranslations('localMcp');

  const selectedTool = mcpTools[selectedToolIndex];

  const getParameters = (): McpParameter[] => {
    try {
      return JSON.parse(selectedTool?.parameters || '[]');
    } catch {
      return [];
    }
  };

  const updateParameters = (parameters: McpParameter[]) => {
    updateMcpTool(selectedToolIndex, { 
      parameters: JSON.stringify(parameters) 
    });
  };

  const addParameter = () => {
    const parameters = getParameters();
    const newParameter: McpParameter = {
      name: `param${parameters.length + 1}`,
      type: 'string',
      description: '',
      required: false
    };
    updateParameters([...parameters, newParameter]);
  };

  const updateParameter = (index: number, field: keyof McpParameter, value: string | boolean | string[]) => {
    const parameters = getParameters();
    parameters[index] = { ...parameters[index], [field]: value };
    updateParameters(parameters);
  };

  const removeParameter = (index: number) => {
    const parameters = getParameters();
    updateParameters(parameters.filter((_, i) => i !== index));
  };

  const generateInputSchema = () => {
    const parameters = getParameters();
    const properties: Record<string, any> = {};
    const required: string[] = [];

    parameters.forEach(param => {
      properties[param.name] = {
        type: param.type,
        description: param.description || undefined
      };
      
      if (param.enum && param.enum.length > 0) {
        properties[param.name].enum = param.enum;
      }
      
      if (param.required) {
        required.push(param.name);
      }
    });

    const schema = {
      type: 'object',
      properties,
      ...(required.length > 0 ? { required } : {})
    };

    updateMcpTool(selectedToolIndex, { 
      inputSchema: JSON.stringify(schema, null, 2) 
    });
  };

  const generateOutputSchema = () => {
    const defaultSchema = {
      type: 'object',
      properties: {
        success: { type: 'boolean', description: 'Indicates if the operation succeeded' },
        data: { type: 'object', description: 'Returned data' },
        error: { type: 'string', description: 'Error message if applicable' }
      }
    };

    updateMcpTool(selectedToolIndex, { 
      outputSchema: JSON.stringify(defaultSchema, null, 2) 
    });
  };

  const canProceed = mcpTools.every(tool => {
    try {
      JSON.parse(tool.inputSchema);
      JSON.parse(tool.outputSchema);
      return true;
    } catch {
      return false;
    }
  });

  if (mcpTools.length === 0) {
    return (
      <div className="text-center py-12">
        <p className="text-theme-secondary">{t('step3.noTools')}</p>
        <Button onClick={goToPrevStep} className="mt-4">
          <ChevronLeft className="w-4 h-4 mr-2" />
          {t('navigation.back')}
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <ToolSelector
        tools={mcpTools}
        selectedIndex={selectedToolIndex}
        onSelect={setSelectedToolIndex}
      />

      {/* Detailed configuration */}
      {selectedTool && (
        <Card className="bg-theme-secondary border-theme">
          <CardContent className="p-0">
            <Tabs value={activeTab} onValueChange={setActiveTab}>
              <div className="p-6 pb-0">
                <TabsList className="bg-theme-tertiary border border-theme">
                  <TabsTrigger value="parameters" className="data-[state=active]:bg-theme-secondary">
                    {t('step3.tabs.parameters')}
                  </TabsTrigger>
                  <TabsTrigger value="schemas" className="data-[state=active]:bg-theme-secondary">
                    {t('step3.tabs.schemas')}
                  </TabsTrigger>
                  <TabsTrigger value="advanced" className="data-[state=active]:bg-theme-secondary">
                    {t('step3.tabs.advanced')}
                  </TabsTrigger>
                </TabsList>
              </div>

              <div className="p-6">
                <TabsContent value="parameters" className="space-y-6">
                  <ParameterList
                    parameters={getParameters()}
                    parameterTypes={PARAMETER_TYPES}
                    onAdd={addParameter}
                    onUpdate={(index, field, value) => updateParameter(index, field as keyof McpParameter, value)}
                    onRemove={removeParameter}
                  />

                  <div className="flex gap-2">
                    <Button onClick={generateInputSchema} variant="outline" className="bg-theme-tertiary border-theme text-theme-primary">
                      <Code className="w-4 h-4 mr-2" />
                      {t('step3.generateInputSchema')}
                    </Button>
                    <Button onClick={generateOutputSchema} variant="outline" className="bg-theme-tertiary border-theme text-theme-primary">
                      <FileText className="w-4 h-4 mr-2" />
                      {t('step3.generateOutputSchema')}
                    </Button>
                  </div>
                </TabsContent>

                <TabsContent value="schemas" className="space-y-6">
                  <SchemaEditor
                    inputSchema={selectedTool.inputSchema}
                    outputSchema={selectedTool.outputSchema}
                    onInputChange={(value) => updateMcpTool(selectedToolIndex, { inputSchema: value })}
                    onOutputChange={(value) => updateMcpTool(selectedToolIndex, { outputSchema: value })}
                  />
                </TabsContent>

                <TabsContent value="advanced" className="space-y-6">
                  <AdvancedSettings
                    tool={selectedTool}
                    onChange={(changes) => updateMcpTool(selectedToolIndex, changes)}
                  />
                </TabsContent>
              </div>
            </Tabs>
          </CardContent>
        </Card>
      )}

      {/* Navigation */}
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
