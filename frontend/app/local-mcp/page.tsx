'use client';

import React, { useState, useCallback, useEffect } from 'react';
import { usePersistentState } from '@/hooks/usePersistentState';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/api-client';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import {
  CheckCircle,
  AlertCircle,
  Terminal,
  Zap,
  Database
} from 'lucide-react';
import { LocalMcpTool, McpTestResult, McpToolStats, POPULAR_MCP_TEMPLATES } from './types';
import StepIndicator from './components/StepIndicator';
import Step1 from './components/Step1';
import Step2 from './components/Step2';
import Step3 from './components/Step3';
import Step4 from './components/Step4';
import Confirmation from './components/Confirmation';

export default function LocalMcpPage() {
  const { user, isLoading } = useAuthGuard();
  const router = useRouter();
  const t = useTranslations('localMcp');
  
  // etats principaux
  const [currentStep, setCurrentStep] = usePersistentState<number>(
    'localMcp_currentStep',
    1,
    {
      serializer: (value) => value.toString(),
      deserializer: (value) => {
        const parsed = parseInt(value, 10);
        return Number.isNaN(parsed) ? 1 : parsed;
      }
    }
  );

  const [mcpTools, setMcpTools] = usePersistentState<LocalMcpTool[]>(
    'localMcp_tools',
    () => []
  );

  const [selectedCategory, setSelectedCategory] = usePersistentState('localMcp_selectedCategory', '');

  const [selectedSubcategory, setSelectedSubcategory] = usePersistentState('localMcp_selectedSubcategory', '');

  const [toolName, setToolName] = usePersistentState('localMcp_toolName', '');

  const [toolDescription, setToolDescription] = usePersistentState('localMcp_toolDescription', '');

  const [isSubmitting, setIsSubmitting] = useState(false);
  const [notification, setNotification] = useState<{
    type: 'success' | 'error' | 'info';
    message: string;
    visible: boolean;
  } | null>(null);

  const [stats, setStats] = useState<McpToolStats>({
    totalTools: 0,
    activeTools: 0,
    draftTools: 0,
    totalExecutions: 0,
    avgExecutionTime: 0
  });

  // Charger les statistiques utilisateur
  useEffect(() => {
    if (user?.sub) {
      loadUserStats();
    }
  }, [user]);

  const loadUserStats = async () => {
    try {
      const statsData = await apiClient.get<McpToolStats>(`/local-mcp-tools/user/${user?.sub}/stats`);
      setStats(statsData);
    } catch (error) {
      console.error('Error loading statistics:', error);
    }
  };

  // Notification management
  const showNotification = useCallback((type: 'success' | 'error' | 'info', message: string) => {
    setNotification({ type, message, visible: true });
    setTimeout(() => {
      setNotification(prev => prev ? { ...prev, visible: false } : null);
    }, 5000);
  }, []);

  // Navigation entre les etapes
  const goToNextStep = useCallback(() => {
    if (currentStep < 5) {
      setCurrentStep(currentStep + 1);
    }
  }, [currentStep, setCurrentStep]);

  const goToPrevStep = useCallback(() => {
    if (currentStep > 1) {
      setCurrentStep(currentStep - 1);
    }
  }, [currentStep, setCurrentStep]);

  const goToStep = useCallback((step: number) => {
    setCurrentStep(step);
  }, [setCurrentStep]);

  // Gestion des outils MCP
  const addMcpTool = useCallback((tool: Partial<LocalMcpTool>) => {
    const newTool: LocalMcpTool = {
      id: Date.now().toString(),
      userId: user?.sub || '',
      name: tool.name || '',
      slug: tool.slug || tool.name?.toLowerCase().replace(/[^a-z0-9]+/g, '-') || '',
      description: tool.description || '',
      category: tool.category || selectedCategory,
      subcategory: tool.subcategory || selectedSubcategory,
      toolCategory: tool.toolCategory || '',
      toolType: tool.toolType || 'LOCAL_COMMAND',
      command: tool.command || '',
      workingDirectory: tool.workingDirectory || '',
      environmentVariables: tool.environmentVariables || '',
      inputSchema: tool.inputSchema || '{}',
      outputSchema: tool.outputSchema || '{}',
      parameters: tool.parameters || '[]',
      headers: tool.headers || '[]',
      version: tool.version || '1.0.0',
      documentation: tool.documentation || '',
      rateLimit: tool.rateLimit || '',
      pricing: tool.pricing || 'FREE',
      status: 'DRAFT',
      isActive: true,
      isPublic: false,
      executionCount: 0,
      successCount: 0,
      errorCount: 0,
      createdAt: Date.now(),
      updatedAt: Date.now()
    };

    setMcpTools(prev => [...prev, newTool]);
    return newTool;
  }, [selectedCategory, selectedSubcategory, setMcpTools, user?.sub]);

  const updateMcpTool = useCallback((index: number, updatedTool: Partial<LocalMcpTool>) => {
    setMcpTools(prev => prev.map((tool, i) => (
      i === index
        ? {
            ...tool,
            ...updatedTool,
            updatedAt: Date.now()
          }
        : tool
    )));
  }, [setMcpTools]);

  const removeMcpTool = useCallback((index: number) => {
    setMcpTools(prev => prev.filter((_, i) => i !== index));
  }, [setMcpTools]);

  // Test d'un outil
  const testTool = useCallback(async (toolIndex: number): Promise<McpTestResult> => {
    const tool = mcpTools[toolIndex];
    const startTime = Date.now();

    try {
      // Mettre a jour le statut de test
      updateMcpTool(toolIndex, { testStatus: 'PENDING' });

      const result = await apiClient.post<any>(
        `/local-mcp-tools/user/${user?.sub}/tool/${tool.slug}/test`, {});
      const executionTime = Date.now() - startTime;

      const testResult: McpTestResult = {
        success: result.success,
        output: result.output,
        error: result.error,
        executionTime,
        testStatus: result.testStatus || (result.success ? 'SUCCESS' : 'ERROR')
      };

      // Mettre a jour l'outil avec les resultats du test
      updateMcpTool(toolIndex, {
        testStatus: testResult.success ? 'SUCCESS' : 'ERROR',
        testResult: testResult.error || 'Test successful',
        lastTestTime: Date.now(),
        testResponseTime: executionTime
      });

      return testResult;

    } catch (error) {
      const executionTime = Date.now() - startTime;
      const testResult: McpTestResult = {
        success: false,
        error: error instanceof Error ? error.message : 'Test error',
        executionTime,
        testStatus: 'ERROR'
      };

      updateMcpTool(toolIndex, {
        testStatus: 'ERROR',
        testResult: testResult.error,
        lastTestTime: Date.now(),
        testResponseTime: executionTime
      });

      return testResult;
    }
  }, [mcpTools, updateMcpTool, user?.sub]);

  // Test de tous les outils
  const testAllTools = useCallback(async () => {
    for (let i = 0; i < mcpTools.length; i++) {
      await testTool(i);
    }
  }, [mcpTools, testTool]);

  // Soumission finale
  const submitMcpConfiguration = useCallback(async () => {
    if (!user?.sub) {
      showNotification('error', t('errors.userNotConnected'));
      return false;
    }

    setIsSubmitting(true);
    try {
      // Soumettre chaque outil au backend
      for (const tool of mcpTools) {
        await apiClient.post(`/local-mcp-tools/user/${user.sub}`, tool);
      }

      // Nettoyer le localStorage
      if (typeof window !== 'undefined') {
        localStorage.removeItem('localMcp_tools');
        localStorage.removeItem('localMcp_currentStep');
        localStorage.removeItem('localMcp_selectedCategory');
        localStorage.removeItem('localMcp_selectedSubcategory');
        localStorage.removeItem('localMcp_toolName');
        localStorage.removeItem('localMcp_toolDescription');
      }

      showNotification('success', t('notifications.submitSuccess'));
      
      // Redirect to MCP settings after a delay
      setTimeout(() => {
        router.push('/app/settings/mcp?tab=local-tools');
      }, 2000);

      return true;

    } catch (error) {
      console.error('Error during submission:', error);
      showNotification('error', error instanceof Error ? error.message : t('errors.submissionError'));
      return false;
    } finally {
      setIsSubmitting(false);
    }
  }, [mcpTools, user?.sub, showNotification, router]);

  // Utiliser un template
  const handleUseTemplate = useCallback((template: typeof POPULAR_MCP_TEMPLATES[0]) => {
    const tool = addMcpTool({
      name: template.name,
      slug: template.slug,
      description: template.description,
      category: template.category,
      subcategory: template.subcategory,
      toolCategory: template.toolCategory,
      toolType: template.toolType,
      command: template.command,
      inputSchema: template.inputSchema,
      outputSchema: template.outputSchema,
      parameters: template.parameters,
      documentation: template.documentation
    });

    showNotification('success', t('notifications.templateAdded', { name: template.name }));
    return tool;
  }, [addMcpTool, showNotification]);

  // Verification de l'authentification
  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <LoadingSpinner size="md" />
      </div>
    );
  }

  if (!user) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-theme-primary mb-4">{t('unauthorizedTitle')}</h1>
          <p className="text-theme-secondary mb-6">{t('unauthorizedDescription')}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-theme-primary transition-colors duration-300">
      {/* Notification */}
      {notification && (
        <div className={`fixed top-20 right-4 z-50 p-4 rounded-lg shadow-lg transition-all duration-300 ${
          notification.visible ? 'translate-x-0 opacity-100' : 'translate-x-full opacity-0'
        } ${
          notification.type === 'success' ? 'bg-green-500 text-white' :
          notification.type === 'error' ? 'bg-red-500 text-white' :
          'bg-blue-500 text-white'
        }`}>
          <div className="flex items-center gap-2">
            {notification.type === 'success' && <CheckCircle className="w-5 h-5" />}
            {notification.type === 'error' && <AlertCircle className="w-5 h-5" />}
            <span>{notification.message}</span>
          </div>
        </div>
      )}

      {/* Header */}
      <section className="bg-theme-secondary py-12 transition-colors duration-300">
        <div className="container mx-auto px-4">
          <div className="text-center">
            <h1 className="text-4xl md:text-5xl font-bold text-theme-primary mb-4 transition-colors duration-300">
              {t('pageTitle')}
            </h1>
            <p className="text-xl text-theme-secondary transition-colors duration-300 max-w-3xl mx-auto">
              {t('pageDescription')}
            </p>
            
            {/* Statistiques rapides */}
            <div className="flex justify-center mt-8">
              <div className="grid grid-cols-2 md:grid-cols-4 gap-6">
                <div className="bg-theme-tertiary p-4 rounded-xl border border-theme">
                  <div className="flex items-center gap-2 justify-center">
                    <Terminal className="w-5 h-5 text-blue-600" />
                    <div className="text-center">
                      <div className="text-2xl font-bold text-theme-primary">{stats.totalTools}</div>
                      <div className="text-sm text-theme-secondary">{t('stats.tools')}</div>
                    </div>
                  </div>
                </div>
                <div className="bg-theme-tertiary p-4 rounded-xl border border-theme">
                  <div className="flex items-center gap-2 justify-center">
                    <CheckCircle className="w-5 h-5 text-green-600" />
                    <div className="text-center">
                      <div className="text-2xl font-bold text-theme-primary">{stats.activeTools}</div>
                      <div className="text-sm text-theme-secondary">{t('stats.active')}</div>
                    </div>
                  </div>
                </div>
                <div className="bg-theme-tertiary p-4 rounded-xl border border-theme">
                  <div className="flex items-center gap-2 justify-center">
                    <Zap className="w-5 h-5 text-orange-600" />
                    <div className="text-center">
                      <div className="text-2xl font-bold text-theme-primary">{stats.totalExecutions}</div>
                      <div className="text-sm text-theme-secondary">{t('stats.executions')}</div>
                    </div>
                  </div>
                </div>
                <div className="bg-theme-tertiary p-4 rounded-xl border border-theme">
                  <div className="flex items-center gap-2 justify-center">
                    <Database className="w-5 h-5 text-purple-600" />
                    <div className="text-center">
                      <div className="text-2xl font-bold text-theme-primary">{stats.avgExecutionTime.toFixed(0)}ms</div>
                      <div className="text-sm text-theme-secondary">{t('stats.avgTime')}</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Contenu principal */}
      <section className="py-12">
        <div className="container mx-auto px-4">
          <div className="max-w-6xl mx-auto">
            {/* Indicateur d'etapes */}
            <StepIndicator currentStep={currentStep} />

            {/* Contenu des etapes */}
            <div className="mt-12">
              {currentStep === 1 && (
                <Step1
                  selectedCategory={selectedCategory}
                  setSelectedCategory={setSelectedCategory}
                  selectedSubcategory={selectedSubcategory}
                  setSelectedSubcategory={setSelectedSubcategory}
                  toolName={toolName}
                  setToolName={setToolName}
                  toolDescription={toolDescription}
                  setToolDescription={setToolDescription}
                  onUseTemplate={handleUseTemplate}
                  goToNextStep={goToNextStep}
                />
              )}

              {currentStep === 2 && (
                <Step2
                  mcpTools={mcpTools}
                  addMcpTool={addMcpTool}
                  updateMcpTool={updateMcpTool}
                  removeMcpTool={removeMcpTool}
                  selectedCategory={selectedCategory}
                  selectedSubcategory={selectedSubcategory}
                  toolName={toolName}
                  toolDescription={toolDescription}
                  goToPrevStep={goToPrevStep}
                  goToNextStep={goToNextStep}
                />
              )}

              {currentStep === 3 && (
                <Step3
                  mcpTools={mcpTools}
                  updateMcpTool={updateMcpTool}
                  goToPrevStep={goToPrevStep}
                  goToNextStep={goToNextStep}
                />
              )}

              {currentStep === 4 && (
                <Step4
                  mcpTools={mcpTools}
                  testAllTools={testAllTools}
                  goToPrevStep={goToPrevStep}
                  goToNextStep={goToNextStep}
                />
              )}

              {currentStep === 5 && (
                <Confirmation
                  mcpTools={mcpTools}
                  submitMcpConfiguration={submitMcpConfiguration}
                  isSubmitting={isSubmitting}
                  goToPrevStep={goToPrevStep}
                  goToStep={goToStep}
                />
              )}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
