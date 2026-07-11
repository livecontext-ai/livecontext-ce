import React, { useState } from 'react';
import { Database, CreditCard, Mail, MessageSquare, Zap, ChevronRight } from 'lucide-react';
import { AVAILABLE_MCP_TEMPLATES, getMcpTemplate } from '../templates/mcpTemplates';
import { McpTool } from '../types';

interface TemplateSelectorProps {
  onTemplateSelect: (tools: McpTool[], apiConfig: any) => void;
  onClose: () => void;
}

const TEMPLATE_ICONS = {
  'Database': Database,
  'Business': CreditCard,
  'Communication': Mail,
  'Messaging': MessageSquare,
  'default': Zap
};

export default function TemplateSelector({ onTemplateSelect, onClose }: TemplateSelectorProps) {
  const [selectedTemplate, setSelectedTemplate] = useState<string | null>(null);

  const handleTemplateSelect = (templateId: string) => {
    const template = getMcpTemplate(templateId);
    if (!template) return;

    // Convertir les outils du template au format McpTool
    const mcpTools: McpTool[] = template.tools.map((tool, index) => ({
      ...tool,
      category: template.category,
      subcategory: template.subcategory,
      pricing: 'free' as const,
      rateLimit: '1000 requests/hour',
      status: 'draft' as const,
      parameters: [],
      pathParameters: tool.pathParameters || [],
      queryParameters: tool.queryParameters || [],
      headers: tool.headers || [],
      bodyParams: tool.bodyParams || []
    }));

    // Base API configuration
    const apiConfig = {
      baseUrl: template.baseUrl,
      healthcheckEndpoint: '/health',
      authorization: {
        type: 'bearer' as const,
        headerName: 'Authorization',
        headerValue: '',
        description: 'Authentication token'
      }
    };

    onTemplateSelect(mcpTools, apiConfig);
  };

  const getTemplateIcon = (category: string) => {
    const IconComponent = TEMPLATE_ICONS[category as keyof typeof TEMPLATE_ICONS] || TEMPLATE_ICONS.default;
    return IconComponent;
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white dark:bg-gray-800 rounded-xl shadow-2xl max-w-4xl w-full max-h-[80vh] overflow-hidden">
        {/* Header */}
        <div className="p-6 border-b border-gray-200 dark:border-gray-700">
          <div className="flex justify-between items-center">
            <div>
              <h2 className="text-2xl font-bold text-gray-900 dark:text-white">
                MCP API Templates
              </h2>
              <p className="text-gray-600 dark:text-gray-400 mt-1">
                Select a template to quickly create a complete API
              </p>
            </div>
            <button
              onClick={onClose}
              className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
            >
              <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        {/* Templates Grid */}
        <div className="p-6 overflow-y-auto max-h-[60vh]">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {AVAILABLE_MCP_TEMPLATES.map((template) => {
              const IconComponent = getTemplateIcon(template.category);
              const isSelected = selectedTemplate === template.id;

              return (
                <div
                  key={template.id}
                  className={`p-6 rounded-lg border-2 cursor-pointer transition-all duration-200 hover:shadow-lg ${
                    isSelected
                      ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20'
                      : 'border-gray-200 dark:border-gray-700 hover:border-gray-300 dark:hover:border-gray-600'
                  }`}
                  onClick={() => setSelectedTemplate(template.id)}
                >
                  <div className="flex items-start gap-4">
                    <div className={`p-3 rounded-lg ${isSelected ? 'bg-blue-500' : 'bg-gray-100 dark:bg-gray-700'}`}>
                      <IconComponent className={`w-6 h-6 ${isSelected ? 'text-white' : 'text-gray-600 dark:text-gray-400'}`} />
                    </div>
                    
                    <div className="flex-1">
                      <h3 className="font-semibold text-gray-900 dark:text-white mb-2">
                        {template.name}
                      </h3>
                      <p className="text-sm text-gray-600 dark:text-gray-400 mb-3">
                        {template.description}
                      </p>
                      
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <span className="px-2 py-1 bg-gray-100 dark:bg-gray-700 text-xs rounded-full text-gray-600 dark:text-gray-400">
                            {template.category}
                          </span>
                          <span className="text-xs text-gray-500">
                            {template.toolsCount} tools
                          </span>
                        </div>
                        
                        {isSelected && (
                          <ChevronRight className="w-4 h-4 text-blue-500" />
                        )}
                      </div>
                    </div>
                  </div>
                  
                  {/* Tools preview */}
                  {isSelected && (
                    <div className="mt-4 pt-4 border-t border-gray-200 dark:border-gray-700">
                      <h4 className="text-sm font-medium text-gray-900 dark:text-white mb-2">
                        Outils inclus :
                      </h4>
                      <div className="space-y-1">
                        {getMcpTemplate(template.id)?.tools.slice(0, 3).map((tool, index) => (
                          <div key={index} className="text-xs text-gray-600 dark:text-gray-400 break-words">
                            <span className="hidden sm:inline">• {tool.name} ({tool.method})</span>
                            <span className="sm:hidden">
                              • {tool.name}
                              <br />
                              <span className="text-xs opacity-75 ml-2">({tool.method})</span>
                            </span>
                          </div>
                        ))}
                        {getMcpTemplate(template.id)?.tools.length! > 3 && (
                          <div className="text-xs text-gray-500">
                            + {getMcpTemplate(template.id)?.tools.length! - 3} autres...
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* Footer */}
        <div className="p-6 border-t border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-700/50">
          <div className="flex justify-end gap-3">
            <button
              onClick={onClose}
              className="px-4 py-2 text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-600 rounded-lg transition-colors"
            >
              Annuler
            </button>
            <button
              onClick={() => selectedTemplate && handleTemplateSelect(selectedTemplate)}
              disabled={!selectedTemplate}
              className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              Utiliser ce template
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
