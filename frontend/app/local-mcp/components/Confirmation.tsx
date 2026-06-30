import React, { useState } from 'react';
import { ChevronLeft, Send, CheckCircle, AlertCircle, Eye, Settings, Code, Database } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { LocalMcpTool } from '../types';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';

interface ConfirmationProps {
  mcpTools: LocalMcpTool[];
  submitMcpConfiguration: () => Promise<boolean>;
  isSubmitting: boolean;
  goToPrevStep: () => void;
  goToStep: (step: number) => void;
}

export default function Confirmation({
  mcpTools,
  submitMcpConfiguration,
  isSubmitting,
  goToPrevStep,
  goToStep
}: ConfirmationProps) {
  const [expandedTool, setExpandedTool] = useState<string | null>(null);
  const t = useTranslations('localMcp');

  const toggleToolExpanded = (toolId: string) => {
    setExpandedTool(expandedTool === toolId ? null : toolId);
  };

  const getToolTypeIcon = (toolType: string) => {
    switch (toolType) {
      case 'LOCAL_COMMAND': return <Settings className="w-4 h-4" />;
      case 'LOCAL_PYTHON': return <Code className="w-4 h-4" />;
      case 'LOCAL_NODEJS': return <Code className="w-4 h-4" />;
      case 'LOCAL_DATABASE': return <Database className="w-4 h-4" />;
      case 'LOCAL_API': return <Settings className="w-4 h-4" />;
      default: return <Settings className="w-4 h-4" />;
    }
  };

  const getStatusBadge = (tool: LocalMcpTool) => {
    if (!tool.testStatus) {
      return <Badge variant="secondary">{t('confirmation.notTested')}</Badge>;
    }
    
    switch (tool.testStatus) {
      case 'SUCCESS':
        return <Badge className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200">{t('confirmation.tested')}</Badge>;
      case 'ERROR':
      case 'TIMEOUT':
        return <Badge className="bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200">{t('confirmation.error')}</Badge>;
      default:
        return <Badge variant="secondary">{t('confirmation.pending')}</Badge>;
    }
  };

  const totalTools = mcpTools.length;
  const testedTools = mcpTools.filter(tool => tool.testStatus === 'SUCCESS').length;
  const failedTools = mcpTools.filter(tool => tool.testStatus === 'ERROR' || tool.testStatus === 'TIMEOUT').length;
  const toolCategories = new Set(mcpTools.map(tool => tool.toolCategory)).size;
  const toolTypes = new Set(mcpTools.map(tool => tool.toolType)).size;

  const canSubmit = totalTools > 0;

  const handleSubmit = async () => {
    const success = await submitMcpConfiguration();
    if (!success) {
      // Error handling is already done in submitMcpConfiguration
    }
  };

  return (
    <div className="space-y-8">
      {/* Configuration summary */}
      <Card className="bg-theme-secondary border-theme">
        <CardHeader>
          <CardTitle className="text-theme-primary">{t('confirmation.summaryTitle')}</CardTitle>
          <CardDescription className="text-theme-secondary">
            {t('confirmation.summaryDescription')}
          </CardDescription>
        </CardHeader>
        
        <CardContent>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div className="bg-theme-tertiary p-4 rounded-lg border border-theme text-center">
              <div className="text-2xl font-bold text-theme-primary">{totalTools}</div>
              <div className="text-sm text-theme-secondary">{t('confirmation.configuredTools')}</div>
            </div>
            <div className="bg-theme-tertiary p-4 rounded-lg border border-theme text-center">
              <div className="text-2xl font-bold text-green-600">{testedTools}</div>
              <div className="text-sm text-theme-secondary">{t('confirmation.successfulTests')}</div>
            </div>
            <div className="bg-theme-tertiary p-4 rounded-lg border border-theme text-center">
              <div className="text-2xl font-bold text-theme-primary">{toolCategories}</div>
              <div className="text-sm text-theme-secondary">{t('confirmation.categories')}</div>
            </div>
            <div className="bg-theme-tertiary p-4 rounded-lg border border-theme text-center">
              <div className="text-2xl font-bold text-theme-primary">{toolTypes}</div>
              <div className="text-sm text-theme-secondary">{t('confirmation.toolTypes')}</div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Detailed tool list */}
      <Card className="bg-theme-secondary border-theme">
        <CardHeader>
          <CardTitle className="text-theme-primary">{t('confirmation.toolsTitle')}</CardTitle>
          <CardDescription className="text-theme-secondary">
            {t('confirmation.toolsDescription')}
          </CardDescription>
        </CardHeader>
        
        <CardContent>
          <div className="space-y-4">
            {mcpTools.map((tool, index) => (
              <div key={tool.id} className="bg-theme-tertiary rounded-lg border border-theme overflow-hidden">
                <div 
                  className="p-4 cursor-pointer hover:bg-theme-primary/5 transition-colors"
                  onClick={() => toggleToolExpanded(tool.id)}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 bg-blue-600 rounded-lg flex items-center justify-center text-white">
                        {getToolTypeIcon(tool.toolType)}
                      </div>
                      <div>
                        <h4 className="font-medium text-theme-primary">{tool.name}</h4>
                        <p className="text-sm text-theme-secondary">{tool.toolCategory} • {tool.toolType.replace('LOCAL_', '')}</p>
                      </div>
                    </div>
                    
                    <div className="flex items-center gap-2">
                      {getStatusBadge(tool)}
                      <Button 
                        variant="outline" 
                        size="sm"
                        className="bg-theme-secondary border-theme text-theme-primary"
                      >
                        <Eye className="w-4 h-4 mr-1" />
                        {expandedTool === tool.id ? t('confirmation.collapse') : t('confirmation.details')}
                      </Button>
                    </div>
                  </div>
                </div>

                {expandedTool === tool.id && (
                  <div className="border-t border-theme p-4 bg-theme-secondary/50">
                    <div className="space-y-4">
                      <div>
                        <h5 className="font-medium text-theme-primary mb-2">{t('confirmation.descriptionLabel')}</h5>
                        <p className="text-sm text-theme-secondary">{tool.description || t('confirmation.noDescription')}</p>
                      </div>

                      <div>
                        <h5 className="font-medium text-theme-primary mb-2">{t('confirmation.commandLabel')}</h5>
                        <code className="text-sm bg-theme-primary/10 px-2 py-1 rounded text-theme-primary block">
                          {tool.command}
                        </code>
                      </div>

                      {tool.workingDirectory && (
                        <div>
                          <h5 className="font-medium text-theme-primary mb-2">{t('confirmation.workingDirectory')}</h5>
                          <code className="text-sm bg-theme-primary/10 px-2 py-1 rounded text-theme-primary">
                            {tool.workingDirectory}
                          </code>
                        </div>
                      )}

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                          <h5 className="font-medium text-theme-primary mb-2">{t('confirmation.inputSchema')}</h5>
                          <pre className="text-xs bg-theme-primary/5 p-2 rounded border max-h-32 overflow-y-auto text-theme-primary">
                            {JSON.stringify(JSON.parse(tool.inputSchema || '{}'), null, 2)}
                          </pre>
                        </div>
                        <div>
                          <h5 className="font-medium text-theme-primary mb-2">{t('confirmation.outputSchema')}</h5>
                          <pre className="text-xs bg-theme-primary/5 p-2 rounded border max-h-32 overflow-y-auto text-theme-primary">
                            {JSON.stringify(JSON.parse(tool.outputSchema || '{}'), null, 2)}
                          </pre>
                        </div>
                      </div>

                      {tool.testStatus && tool.testResult && tool.testStatus === 'ERROR' && (
                        <div>
                          <h5 className="font-medium text-red-600 mb-2">{t('confirmation.testError')}</h5>
                          <Alert className="border-red-200 bg-red-50 dark:bg-red-900/30">
                            <AlertCircle className="h-4 w-4 text-red-600" />
                            <AlertDescription className="text-red-800 dark:text-red-200">
                              {tool.testResult}
                            </AlertDescription>
                          </Alert>
                        </div>
                      )}

                      <div className="flex gap-2 pt-2">
                        <Button 
                          onClick={() => goToStep(2)}
                          variant="outline"
                          size="sm"
                          className="bg-theme-tertiary border-theme text-theme-primary"
                        >
                          {t('confirmation.edit')}
                        </Button>
                        <Button
                          onClick={() => goToStep(3)}
                          variant="outline"
                          size="sm"
                          className="bg-theme-tertiary border-theme text-theme-primary"
                        >
                          {t('confirmation.configuration')}
                        </Button>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Warnings and recommendations */}
      {failedTools > 0 && (
        <Alert className="border-yellow-200 bg-yellow-50 dark:bg-yellow-900/30">
          <AlertCircle className="h-4 w-4 text-yellow-600" />
          <AlertDescription className="text-yellow-800 dark:text-yellow-200">
            <div className="font-medium mb-2">{t('confirmation.failedToolsWarning', { count: failedTools })}</div>
            <p>{t('confirmation.failedToolsHelp')}</p>
          </AlertDescription>
        </Alert>
      )}

      {totalTools === 0 && (
        <Alert className="border-red-200 bg-red-50 dark:bg-red-900/30">
          <AlertCircle className="h-4 w-4 text-red-600" />
          <AlertDescription className="text-red-800 dark:text-red-200">
            <div className="font-medium mb-2">{t('confirmation.noToolsTitle')}</div>
            <p>{t('confirmation.noToolsDescription')}</p>
          </AlertDescription>
        </Alert>
      )}

      {canSubmit && (
        <Alert className="border-blue-200 bg-blue-50 dark:bg-blue-900/30">
          <CheckCircle className="h-4 w-4 text-blue-600" />
          <AlertDescription className="text-blue-800 dark:text-blue-200">
            <div className="font-medium mb-2">{t('confirmation.readyTitle')}</div>
            <p>{t('confirmation.readyDescription')}</p>
          </AlertDescription>
        </Alert>
      )}

      {/* Navigation and submission */}
      <div className="flex justify-between">
        <Button 
          onClick={goToPrevStep}
          variant="outline"
          className="bg-theme-tertiary border-theme text-theme-primary"
          disabled={isSubmitting}
        >
          <ChevronLeft className="w-4 h-4 mr-2" />
          {t('navigation.previous')}
        </Button>
        
        <Button 
          onClick={handleSubmit}
          disabled={!canSubmit || isSubmitting}
          className="bg-green-600 hover:bg-green-700 text-white"
          size="lg"
        >
          {isSubmitting ? (
            <>
              <LoadingSpinner size="sm" className="mr-2" />
              {t('confirmation.submitting')}
            </>
          ) : (
            <>
              <Send className="w-4 h-4 mr-2" />
              {t('confirmation.submit')}
            </>
          )}
        </Button>
      </div>
    </div>
  );
}
