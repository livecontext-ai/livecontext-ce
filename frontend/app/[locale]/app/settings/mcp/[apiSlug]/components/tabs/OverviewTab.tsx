import React, { useState, useCallback, useEffect } from 'react';
import {
  Globe,
  Shield,
  Settings,
  Eye,
  Save,
  Info,
  Cog,
  SquarePen,
  TestTube,
  CheckCircle,
  XCircle,
  Clock
} from 'lucide-react';
import { useTranslations } from 'next-intl';

// Import RichTextarea from developers
import RichTextarea from '@/app/[locale]/app/settings/developers/components/common/RichTextarea';
import { useExternalTesting } from '@/hooks/useExternalTesting';
import { useCategories } from '@/hooks/useCatalogData';
import { useSubcategories } from '@/hooks/useSubcategories';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Button } from '@/components/ui/button';

interface Category {
  id: string;
  name: string;
}

interface Subcategory {
  id: string;
  name: string;
  categoryId: string;
}

interface OverviewTabProps {
  apiData: any;
  apiConfig?: any;
  onSaveConfig?: (config: any) => void;
}

const OverviewTab: React.FC<OverviewTabProps> = ({ apiData, apiConfig, onSaveConfig }) => {
  const t = useTranslations('mcp.overview');
  const { testEndpoint: testExternalEndpoint, isLoading: testingEndpoints, error: testError } = useExternalTesting();
  const { data: categories = [] } = useCategories();
  const [isConfigEditing, setIsConfigEditing] = useState(false);
  const [isBasicInfoEditing, setIsBasicInfoEditing] = useState(false);
  const [showSecrets, setShowSecrets] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [hasBasicInfoChanges, setHasBasicInfoChanges] = useState(false);
  const [hasConfigChanges, setHasConfigChanges] = useState(false);
  const [endpointTests, setEndpointTests] = useState<{
    [key: string]: {
      status: 'pending' | 'success' | 'error',
      message?: string,
      responseTime?: number,
      response?: any,
      error?: any,
      statusCode?: number
    }
  }>({});
  const [allEndpointsTested, setAllEndpointsTested] = useState(false);
  const [updatingResponses, setUpdatingResponses] = useState(false);
  const [testingIndividualEndpoint, setTestingIndividualEndpoint] = useState<string | null>(null);

  const [editData, setEditData] = useState({
    apiName: apiData?.apiName || '',
    description: apiData?.description || '',
    category: apiData?.categoryName || apiData?.category || '',
    subcategory: apiData?.subcategoryName || '',
    isPublic: false // Not used in UI anymore, but kept for data consistency
  });

  // Trouver l'ID de la categorie selectionnee
  const selectedCategoryId = categories?.find(cat => cat.name === editData.category)?.id;
  const { data: subcategories = [], isLoading: loadingSubcategories } = useSubcategories(selectedCategoryId);

  const [config, setConfig] = useState(apiConfig || {
    baseUrl: '',
    healthcheckEndpoint: '/health',
    visibility: 'public',
    authorization: {
      type: 'none',
      headerName: '',
      headerValue: '',
      password: ''
    },
    rateLimit: {
      requests: 1000,
      period: 'hour'
    }
  });

  // Update config when apiConfig changes
  React.useEffect(() => {
    if (apiConfig) {
      console.log('🔄 OverviewTab: Updating config with apiConfig:', apiConfig);
      setConfig(apiConfig);
    }
  }, [apiConfig]);

  // Update editData when apiData changes
  React.useEffect(() => {
    if (apiData) {
      console.log('🔄 OverviewTab: Updating editData with apiData:', apiData);
      setEditData({
        apiName: apiData.apiName || '',
        description: apiData.description || '',
        category: apiData.categoryName || apiData.category || '',
        subcategory: apiData.subcategoryName || '',
        isPublic: apiData.isPublic || false
      });
    }
  }, [apiData]);

  // Fonction de sauvegarde automatique pour les informations de base
  const saveBasicInfo = useCallback(async (data: any) => {
    try {
      const updateData = {
        description: data.description || '',
        category: data.category || '',
        subcategory: data.subcategory || ''
      };

      console.log('🔄 Saving basic info:', updateData);
      await unifiedApiService.updateApiBasicInfo(apiData.id, updateData);
      console.log('✅ Basic info saved successfully');
    } catch (error) {
      console.error('❌ Error saving basic info:', error);
      throw error; // Re-throw to allow error handling in parent components
    }
  }, [apiData?.id]);

  // Fonction pour mettre a jour les reponses des outils apres sauvegarde de la configuration API
  const updateToolResponses = async () => {
    if (!apiData?.tools || apiData.tools.length === 0) {
      return;
    }

    setUpdatingResponses(true);

    let successCount = 0;
    let errorCount = 0;

    try {
      // Pour chaque outil, mettre a jour sa reponse basee sur les tests d'endpoints
      for (const tool of apiData.tools) {
        const testKey = `${tool.method || 'GET'}:${tool.endpoint}`;
        const testResult = endpointTests[testKey];


        if (testResult && testResult.status === 'success') {
          // Verifier que l'ID de l'outil est valide
          if (!tool.id) {
            console.error(`❌ Tool ${tool.name} has no valid ID, skipping`);
            errorCount++;
            continue;
          }

          const responseData = {
            tool_id: tool.id,
            example: JSON.stringify({
              success: true,
              data: testResult.response || {},
              message: 'Request successful',
              status: testResult.statusCode || 200,
              responseTime: testResult.responseTime || 0
            }),
            status_code: testResult.statusCode || 200
          };


          try {
            // D'abord, essayer de recuperer les reponses existantes pour cet outil
            const existingResponses = await unifiedApiService.getToolResponses(tool.id);

            if (existingResponses.length > 0) {
              // Mettre a jour la premiere reponse existante
              const existingResponse = existingResponses[0];

              const result = await unifiedApiService.updateToolResponse(apiData.id, existingResponse.id, responseData);
            } else {
              // Creer une nouvelle reponse

              const result = await unifiedApiService.createToolResponse(apiData.id, responseData);
            }

            successCount++;
          } catch (toolError) {
            console.error(`❌ Failed to update response for tool: ${tool.name}`, toolError);
            console.error(`Error details:`, {
              message: toolError.message,
              status: toolError.status,
              response: toolError.response
            });
            errorCount++;
          }
        } else {
        }
      }


      // Afficher un message a l'utilisateur
      if (successCount > 0) {
      }
      if (errorCount > 0) {
        console.warn(`⚠️ Failed to update ${errorCount} tool response(s)`);
      }
    } catch (error) {
      console.error('❌ Error updating tool responses:', error);
      // Ne pas faire echouer la sauvegarde de la configuration si les reponses echouent
    } finally {
      setUpdatingResponses(false);
    }
  };

  // Fonction de sauvegarde automatique pour la configuration API
  const saveApiConfig = useCallback(async (configData: any) => {
    try {

      const updateData = {
        baseUrl: configData.baseUrl,
        healthcheckEndpoint: configData.healthcheckEndpoint,
        visibility: configData.visibility,
        authType: configData.authorization?.type,
        authHeaderName: configData.authorization?.headerName,
        authHeaderValue: configData.authorization?.headerValue
      };

      const configResult = await unifiedApiService.updateApiConfig(apiData.id, updateData);

      // Update tool responses after successful API configuration save
      if (apiData?.tools && apiData.tools.length > 0) {

        await updateToolResponses();
      } else {
      }
    } catch (error) {
      console.error('❌ Error saving API config:', error);
      throw error;
    }
  }, [apiData?.id, apiData?.tools, endpointTests]);

  const handleSave = async () => {
    setIsSaving(true);
    try {
      await saveBasicInfo(editData);
      setIsBasicInfoEditing(false);
      setHasBasicInfoChanges(false);
    } catch (error) {
      console.error('❌ Error saving basic info:', error);
      // Show user-friendly error message
      alert(`Failed to save basic info: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsSaving(false);
    }
  };

  const handleBasicInfoCancel = () => {
    // Reset to original data
    setEditData({
      apiName: apiData?.apiName || '',
      description: apiData?.description || '',
      category: apiData?.categoryName || apiData?.category || '',
      subcategory: apiData?.subcategoryName || '',
      isPublic: false // Not used anymore, but kept for consistency
    });
    setIsBasicInfoEditing(false);
    setHasBasicInfoChanges(false);
  };

  const handleConfigSave = async () => {
    setIsSaving(true);
    try {
      await saveApiConfig(config);
      setIsConfigEditing(false);
      setHasConfigChanges(false);
    } catch (error) {
      console.error('Error saving configuration:', error);
    } finally {
      setIsSaving(false);
    }
  };

  const handleConfigCancel = () => {
    setConfig(apiConfig || {
      baseUrl: '',
      healthcheckEndpoint: '/health',
      visibility: 'public',
      authorization: {
        type: 'none',
        headerName: '',
        headerValue: '',
        password: ''
      },
      rateLimit: {
        requests: 1000,
        period: 'hour'
      }
    });
    setIsConfigEditing(false);
    setHasConfigChanges(false);
    // Reset endpoint tests when canceling
    setEndpointTests({});
    setAllEndpointsTested(false);
  };

  // Gestionnaires de changement simples (sans sauvegarde automatique)
  const handleBasicInfoChange = useCallback((field: string, value: any) => {
    // Ignorer les changements sur category et subcategory car ils sont desactives
    if (field === 'category' || field === 'subcategory') {
      return;
    }

    const newData = { ...editData, [field]: value };
    setEditData(newData);
    setHasBasicInfoChanges(true);
  }, [editData]);

  const handleConfigChange = useCallback((field: string, value: any) => {
    const newConfig = { ...config, [field]: value };
    setConfig(newConfig);
    setHasConfigChanges(true);
  }, [config]);

  const handleAuthChange = useCallback((field: string, value: any) => {
    const newConfig = {
      ...config,
      authorization: {
        ...config.authorization,
        [field]: value
      }
    };
    setConfig(newConfig);
    setHasConfigChanges(true);
  }, [config]);

  const toggleSecretVisibility = () => {
    setShowSecrets(!showSecrets);
  };

  // Les sous-categories sont maintenant gerees par le hook useSubcategories

  // Effect to update allEndpointsTested when endpointTests change
  useEffect(() => {
    if (apiData?.tools && apiData.tools.length > 0) {
      const allToolsTested = apiData.tools.every((tool: any) => {
        const key = `${tool.method || 'GET'}:${tool.endpoint}`;
        return endpointTests[key] !== undefined;
      });
      setAllEndpointsTested(allToolsTested);
    }
  }, [endpointTests, apiData?.tools]);

  // Fonction pour tester un endpoint
  const testEndpoint = async (tool: any, isIndividualTest: boolean = false) => {
    const testKey = `${tool.method || 'GET'}:${tool.endpoint}`;

    // Prevent multiple simultaneous individual tests
    if (isIndividualTest && testingIndividualEndpoint) {
      return;
    }

    try {
      if (isIndividualTest) {
        setTestingIndividualEndpoint(testKey);
      }

      let testUrl = `${config.baseUrl}${tool.endpoint}`;
      const startTime = Date.now();

      // Replace path parameters using example values
      if (tool.parameters) {
        tool.parameters.forEach((param: any) => {
          if (param.name && param.exampleValue) {
            testUrl = testUrl.replace(`{${param.name}}`, encodeURIComponent(param.exampleValue));
          }
        });
      }

      const response = await testExternalEndpoint({
        url: testUrl,
        method: tool.method || 'GET',
        headers: {
          'Content-Type': 'application/json',
          ...(config.authorization?.type === 'apikey' && config.authorization?.headerName && config.authorization?.headerValue && {
            [config.authorization.headerName]: config.authorization.headerValue
          }),
          ...(config.authorization?.type === 'bearer' && config.authorization?.headerValue && {
            'Authorization': `Bearer ${config.authorization.headerValue}`
          })
        },
        timeout: 10000
      });

      const responseTime = Date.now() - startTime;

      // La reponse d'external-proxy a cette structure: { status, statusText, data, url, method }
      const statusCode = response.status;
      const isSuccess = statusCode >= 200 && statusCode < 300;

      if (isSuccess) {
        const data = response.data;
        setEndpointTests(prev => ({
          ...prev,
          [testKey]: {
            status: 'success',
            message: `Status: ${statusCode}`,
            responseTime,
            response: data,
            statusCode: statusCode
          }
        }));
      } else {
        setEndpointTests(prev => ({
          ...prev,
          [testKey]: {
            status: 'error',
            message: `Status: ${statusCode} - ${response.statusText || 'Error'}`,
            responseTime,
            error: response.data || response,
            statusCode: statusCode
          }
        }));
      }
    } catch (error) {
      setEndpointTests(prev => ({
        ...prev,
        [testKey]: {
          status: 'error',
          message: `Error: ${error instanceof Error ? error.message : 'Unknown error'}`,
          responseTime: 0,
          error: error instanceof Error ? error.message : 'Unknown error',
          statusCode: 0
        }
      }));
    } finally {
      if (isIndividualTest) {
        setTestingIndividualEndpoint(null);
      }
    }
  };

  // Fonction pour tester tous les endpoints
  const testAllEndpoints = async () => {
    if (!apiData?.tools || apiData.tools.length === 0) {
      setAllEndpointsTested(true);
      return;
    }

    setEndpointTests({});
    setAllEndpointsTested(false);

    // Test each endpoint sequentially with a delay to avoid spam
    for (let i = 0; i < apiData.tools.length; i++) {
      const tool = apiData.tools[i];
      await testEndpoint(tool);

      // Add a delay between tests (except for the last one)
      if (i < apiData.tools.length - 1) {
        await new Promise(resolve => setTimeout(resolve, 1000)); // 1 second delay
      }
    }

    setAllEndpointsTested(true);
  };

  // Verifier si tous les endpoints ont ete testes avec succes
  const areAllEndpointsValid = () => {
    const testResults = Object.values(endpointTests);
    return testResults.length > 0 && testResults.every(test => test.status === 'success');
  };

  return (
    <div className="space-y-8">
      {/* Basic Information */}
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
              <Info className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h3 className="text-lg font-semibold text-theme-primary">{t('basicInfo.title')}</h3>
              <p className="text-sm text-theme-secondary">{t('basicInfo.description')}</p>
            </div>
          </div>

          <div className="flex items-center space-x-2">
            {!isBasicInfoEditing ? (
              <Button
                onClick={() => setIsBasicInfoEditing(true)}
                variant="ghost"
                size="icon"
                title={t('common.edit')}
              >
                <SquarePen className="w-4 h-4" />
              </Button>
            ) : (
              <div className="flex items-center gap-2">
                <Button
                  onClick={handleBasicInfoCancel}
                  variant="outline"
                >
                  {t('common.cancel')}
                </Button>
                <Button
                  onClick={handleSave}
                  disabled={isSaving || !hasBasicInfoChanges}
                  variant="default"
                >
                  <Save className="w-4 h-4 mr-2" />
                  {isSaving ? t('common.saving') : t('common.saveChanges')}
                </Button>
              </div>
            )}
          </div>
        </div>

        <div className="space-y-6 mt-6">
          {/* API Name */}
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('fields.apiName')} <span className="text-red-500">*</span>
            </label>
            {isBasicInfoEditing ? (
              <Input
                type="text"
                value={editData.apiName}
                onChange={(e) => handleBasicInfoChange('apiName', e.target.value)}
                className="w-full bg-gray-100 border border-theme text-gray-500 cursor-not-allowed"
                placeholder={t('fields.apiNamePlaceholder')}
                disabled
              />
            ) : (
              <Button
                variant="readonly"
                disabled
                className="w-full justify-start rounded-lg"
              >
                {editData.apiName || t('common.notSet')}
              </Button>
            )}
          </div>

          {/* Description */}
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('fields.description')} <span className="text-red-500">*</span>
            </label>
            {isBasicInfoEditing ? (
              <RichTextarea
                value={editData.description}
                onChange={(value) => handleBasicInfoChange('description', value)}
                rows={4}
                placeholder={t('fields.descriptionPlaceholder')}
                className="w-full"
                resizable={true}
              />
            ) : (
              <RichTextarea
                value={editData.description || t('fields.noDescription')}
                onChange={() => {
                }} // Pas de modification en mode lecture
                rows={4}
                placeholder={t('fields.noDescription')}
                className="w-full"
                resizable={false}
                forcePreview={true}
                disabled={true}
              />
            )}
          </div>

          {/* Category and Subcategory */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t('fields.category')} <span className="text-red-500">*</span>
              </label>
              {isBasicInfoEditing ? (
                <Select
                  value={editData.category || '__unset__'}
                  onValueChange={(value) => handleBasicInfoChange('category', value === '__unset__' ? '' : value)}
                  disabled={true}
                >
                  <SelectTrigger className="w-full bg-gray-100 border border-theme text-gray-500 cursor-not-allowed">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {/* Radix UI forbids empty string as Select.Item value (it's reserved for clearing the selection). */}
                    <SelectItem value={editData.category || '__unset__'}>
                      {editData.category || t('common.notSet')}
                    </SelectItem>
                  </SelectContent>
                </Select>
              ) : (
                <Button
                  variant="readonly"
                  disabled
                  className="w-full justify-start rounded-lg capitalize"
                >
                  {editData.category || t('common.notSet')}
                </Button>
              )}
            </div>
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t('fields.subcategory')} <span className="text-red-500">*</span>
              </label>
              {isBasicInfoEditing ? (
                <Select
                  value={editData.subcategory || '__unset__'}
                  onValueChange={(value) => handleBasicInfoChange('subcategory', value === '__unset__' ? '' : value)}
                  disabled={true}
                >
                  <SelectTrigger className="w-full bg-gray-100 border border-theme text-gray-500 cursor-not-allowed">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {/* Radix UI forbids empty string as Select.Item value (it's reserved for clearing the selection). */}
                    <SelectItem value={editData.subcategory || '__unset__'}>
                      {editData.subcategory || t('common.notSet')}
                    </SelectItem>
                  </SelectContent>
                </Select>
              ) : (
                <Button
                  variant="readonly"
                  disabled
                  className="w-full justify-start rounded-lg capitalize"
                >
                  {editData.subcategory || t('common.notSet')}
                </Button>
              )}
            </div>
          </div>
        </div>

        {/* Endpoint Testing Section - Only show when in config editing mode and there are changes */}
        {isConfigEditing && hasConfigChanges && (
          <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-xl p-6 shadow-lg">
            <div className="flex items-center justify-between mb-6">
              <div className="flex items-center space-x-3">
                <div className="w-10 h-10 bg-yellow-100 dark:bg-yellow-800/50 rounded-lg flex items-center justify-center">
                  <TestTube className="w-5 h-5 text-yellow-600 dark:text-yellow-400" />
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-theme-primary dark:text-white">{t('apiConfig.endpointTesting.title')}</h3>
                  <p className="text-sm text-theme-secondary dark:text-gray-300">{t('apiConfig.endpointTesting.description')}</p>
                </div>
              </div>
              <div className="flex items-center space-x-3">
                <button
                  onClick={testAllEndpoints}
                  disabled={testingEndpoints || !apiData?.tools || apiData.tools.length === 0}
                  className={`flex items-center space-x-2 px-4 py-2 rounded-lg transition-colors duration-200 ${testingEndpoints
                    ? 'text-blue-500 dark:text-blue-400 cursor-wait bg-blue-50 dark:bg-blue-900/20'
                    : !apiData?.tools || apiData.tools.length === 0
                      ? 'text-gray-400 dark:text-gray-500 cursor-not-allowed bg-gray-50 dark:bg-gray-800'
                      : 'text-white bg-green-600 hover:bg-green-700 dark:bg-theme-primary dark:hover:bg-theme-primary/90 shadow-md hover:shadow-lg'
                    }`}
                  title={!apiData?.tools || apiData.tools.length === 0 ? t('apiConfig.endpointTesting.noToolsToTest') : testingEndpoints ? t('apiConfig.endpointTesting.testingAll') : t('apiConfig.endpointTesting.testAll')}
                >
                  <TestTube className="w-4 h-4" />
                  <span className="text-sm font-medium">
                    {testingEndpoints ? t('apiConfig.endpointTesting.testing') : t('apiConfig.endpointTesting.testAll')}
                  </span>
                </button>
                {testingEndpoints && (
                  <div
                    className="px-3 py-1 rounded-full text-xs font-medium bg-yellow-100 dark:bg-yellow-800/50 text-yellow-800 dark:text-yellow-200">
                    {t('apiConfig.endpointTesting.testingAll')}
                  </div>
                )}
              </div>
            </div>

            {/* Test Results */}
            {apiData?.tools && apiData.tools.length > 0 && (
              <div className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                  {apiData.tools.map((tool: any, index: number) => {
                    const testKey = `${tool.method || 'GET'}:${tool.endpoint}`;
                    const testResult = endpointTests[testKey];

                    return (
                      <div key={index}
                        className="bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg p-4">
                        <div className="flex items-center justify-between mb-2">
                          <div className="flex items-center space-x-2">
                            <span
                              className="text-xs font-medium px-2 py-1 bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 rounded">
                              {tool.method || 'GET'}
                            </span>
                            <span className="text-sm font-medium text-gray-900 dark:text-white truncate">
                              {tool.name}
                            </span>
                          </div>
                          <div className="flex items-center space-x-2">
                            {/* Status indicator */}
                            <div className="flex items-center space-x-1">
                              {testingIndividualEndpoint === `${tool.method || 'GET'}:${tool.endpoint}` && (
                                <Clock className="w-4 h-4 text-blue-500 animate-spin" />
                              )}
                              {!testingIndividualEndpoint && testResult?.status === 'success' && (
                                <CheckCircle className="w-4 h-4 text-green-500" />
                              )}
                              {!testingIndividualEndpoint && testResult?.status === 'error' && (
                                <XCircle className="w-4 h-4 text-red-500" />
                              )}
                              {!testingIndividualEndpoint && testResult?.status === 'pending' && (
                                <Clock className="w-4 h-4 text-yellow-500" />
                              )}
                              {!testingIndividualEndpoint && !testResult && (
                                <div className="w-4 h-4 rounded-full bg-gray-300 dark:bg-gray-600" />
                              )}
                            </div>

                            {/* Individual test button */}
                            <button
                              onClick={() => testEndpoint(tool, true)}
                              disabled={testingEndpoints || testingIndividualEndpoint === `${tool.method || 'GET'}:${tool.endpoint}`}
                              className="p-1.5 text-gray-400 hover:text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20 rounded-lg transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
                              title={testingIndividualEndpoint === `${tool.method || 'GET'}:${tool.endpoint}` ? t('apiConfig.endpointTesting.testing') : t('apiConfig.endpointTesting.testThisEndpoint')}
                            >
                              <TestTube className="w-4 h-4" />
                            </button>
                          </div>
                        </div>
                        <div className="text-xs text-gray-500 dark:text-gray-400 mb-2 truncate">
                          {tool.endpoint}
                        </div>
                        {testResult && (
                          <div className="text-xs">
                            <div className={`font-medium ${testResult.status === 'success' ? 'text-green-600 dark:text-green-400' :
                              testResult.status === 'error' ? 'text-red-600 dark:text-red-400' :
                                'text-yellow-600 dark:text-yellow-400'
                              }`}>
                              {testResult.message}
                            </div>
                            {testResult.responseTime && (
                              <div className="text-gray-500 dark:text-gray-400 mt-1">
                                {testResult.responseTime}ms
                              </div>
                            )}
                          </div>
                        )}

                        {/* Affichage de la reponse si le test a reussi */}
                        {testResult?.status === 'success' && testResult.response && (
                          <div className="mt-3 pt-3 border-t border-gray-200 dark:border-gray-700">
                            <div className="flex items-center justify-between mb-2">
                              <span className="text-xs font-medium text-gray-700 dark:text-gray-300">{t('apiConfig.endpointTesting.response')}</span>
                              <span className="text-xs text-gray-500 dark:text-gray-400">
                                Status: {testResult.statusCode}
                              </span>
                            </div>
                            <div className="bg-gray-900 dark:bg-gray-950 rounded p-2 overflow-x-auto max-h-32">
                              <pre className="text-xs text-green-400 dark:text-green-300 whitespace-pre-wrap">
                                {typeof testResult.response === 'string'
                                  ? testResult.response
                                  : JSON.stringify(testResult.response, null, 2)
                                }
                              </pre>
                            </div>
                          </div>
                        )}

                        {/* Affichage de l'erreur si le test a echoue */}
                        {testResult?.status === 'error' && testResult.error && (
                          <div className="mt-3 pt-3 border-t border-gray-200 dark:border-gray-700">
                            <div className="flex items-center justify-between mb-2">
                              <span className="text-xs font-medium text-red-600 dark:text-red-400">{t('apiConfig.endpointTesting.errorDetails')}</span>
                              <span className="text-xs text-red-500 dark:text-red-400">
                                Status: {testResult.statusCode || 'Unknown'}
                              </span>
                            </div>
                            <div className="bg-red-900 dark:bg-red-950 rounded p-2 overflow-x-auto max-h-32">
                              <pre className="text-xs text-red-300 dark:text-red-200 whitespace-pre-wrap">
                                {testResult.error}
                              </pre>
                            </div>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>

                {/* Summary */}
                <div className="mt-4 pt-4 border-t border-gray-200 dark:border-gray-700">
                  <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-xs">
                    <div className="flex items-center space-x-2">
                      <div className="w-3 h-3 bg-blue-500 rounded-full"></div>
                      <span className="text-gray-600 dark:text-gray-300">
                        {apiData.tools.length} {t('apiConfig.endpointTesting.summary.totalEndpoints')}
                      </span>
                    </div>
                    <div className="flex items-center space-x-2">
                      <div className="w-3 h-3 bg-green-500 rounded-full"></div>
                      <span className="text-gray-600 dark:text-gray-300">
                        {Object.values(endpointTests).filter(test => test.status === 'success').length} {t('apiConfig.endpointTesting.summary.successful')}
                      </span>
                    </div>
                    <div className="flex items-center space-x-2">
                      <div className="w-3 h-3 bg-red-500 rounded-full"></div>
                      <span className="text-gray-600 dark:text-gray-300">
                        {Object.values(endpointTests).filter(test => test.status === 'error').length} {t('apiConfig.endpointTesting.summary.failed')}
                      </span>
                    </div>
                    <div className="flex items-center space-x-2">
                      <div className="w-3 h-3 bg-yellow-500 rounded-full"></div>
                      <span className="text-gray-600 dark:text-gray-300">
                        {Object.values(endpointTests).filter(test => test.status === 'pending').length} {t('apiConfig.endpointTesting.summary.pending')}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {(!apiData?.tools || apiData.tools.length === 0) && (
              <div className="text-center py-8">
                <TestTube className="w-12 h-12 text-gray-300 mx-auto mb-4" />
                <p className="text-gray-500 text-sm">{t('apiConfig.endpointTesting.noToolsConfigured')}</p>
              </div>
            )}
          </div>
        )
        }

        {/* API Configuration Section */}
        <div className="space-y-6">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-3">
              <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                <Cog className="w-5 h-5 text-theme-primary" />
              </div>
              <div>
                <h3 className="text-lg font-semibold text-theme-primary">{t('apiConfig.title')}</h3>
                <p className="text-sm text-theme-secondary">{t('apiConfig.description')}</p>
              </div>
            </div>
            <div className="flex items-center space-x-2">
              {!isConfigEditing ? (
                <Button
                  onClick={() => setIsConfigEditing(true)}
                  variant="ghost"
                  size="icon"
                  title={t('common.edit')}
                >
                  <SquarePen className="w-4 h-4" />
                </Button>
              ) : (
                <div className="flex items-center gap-2">
                  <Button
                    onClick={handleConfigCancel}
                    variant="outline"
                  >
                    {t('common.cancel')}
                  </Button>
                  <Button
                    onClick={handleConfigSave}
                    disabled={isSaving || updatingResponses || !hasConfigChanges || (hasConfigChanges && (!allEndpointsTested || !areAllEndpointsValid()))}
                    variant="default"
                  >
                    <Save className="w-4 h-4 mr-2" />
                    {isSaving ? t('common.saving') : t('common.saveChanges')}
                  </Button>
                </div>
              )}
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mt-6">
            {/* Basic Settings Column */}
            <div className="space-y-4">
              <h4 className="text-md font-medium text-theme-primary mb-3">{t('apiConfig.basicSettings')}</h4>

              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('apiConfig.baseUrl')}
                </label>
                {isConfigEditing ? (
                  <Input
                    type="url"
                    value={config.baseUrl || ''}
                    onChange={(e) => handleConfigChange('baseUrl', e.target.value)}
                    placeholder="https://api.example.com"
                  />
                ) : (
                  <Button
                    variant="readonly"
                    disabled
                    className="w-full justify-start rounded-lg font-mono"
                  >
                    {config.baseUrl || t('common.notConfigured')}
                  </Button>
                )}
              </div>

              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('apiConfig.healthCheck')}
                </label>
                {isConfigEditing ? (
                  <Input
                    type="text"
                    value={config.healthcheckEndpoint || ''}
                    onChange={(e) => handleConfigChange('healthcheckEndpoint', e.target.value)}
                    placeholder="/health"
                  />
                ) : (
                  <Button
                    variant="readonly"
                    disabled
                    className="w-full justify-start rounded-lg font-mono"
                  >
                    {config.healthcheckEndpoint || '/health'}
                  </Button>
                )}
              </div>

              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('apiConfig.visibility')}
                </label>
                {isConfigEditing ? (
                  <Select
                    value={config.visibility || 'public'}
                    onValueChange={(value) => handleConfigChange('visibility', value)}
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="public">{t('apiConfig.public')}</SelectItem>
                      <SelectItem value="private">{t('apiConfig.private')}</SelectItem>
                    </SelectContent>
                  </Select>
                ) : (
                  <Button
                    variant="readonly"
                    disabled
                    className="w-full justify-start rounded-lg capitalize"
                  >
                    {config.visibility || 'public'}
                  </Button>
                )}
              </div>
            </div>

            {/* Authentication Settings Column */}
            <div className="space-y-4">
              <h4 className="text-md font-medium text-theme-primary mb-3">{t('apiConfig.authentication')}</h4>

              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('apiConfig.authType')}
                </label>
                {isConfigEditing ? (
                  <Select
                    value={config.authorization?.type || 'none'}
                    onValueChange={(value) => handleAuthChange('type', value)}
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="none">{t('apiConfig.authTypes.none')}</SelectItem>
                      <SelectItem value="apikey">{t('apiConfig.authTypes.apiKey')}</SelectItem>
                      <SelectItem value="bearer">{t('apiConfig.authTypes.bearer')}</SelectItem>
                      <SelectItem value="basic">{t('apiConfig.authTypes.basic')}</SelectItem>
                      <SelectItem value="oauth2">{t('apiConfig.authTypes.oauth2')}</SelectItem>
                    </SelectContent>
                  </Select>
                ) : (
                  <Button
                    variant="readonly"
                    disabled
                    className="w-full justify-start rounded-lg capitalize"
                  >
                    {config.authorization?.type || 'none'}
                  </Button>
                )}
              </div>

              {config.authorization?.type !== 'none' && (
                <>
                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-2">
                      {t('apiConfig.headerName')}
                    </label>
                    {isConfigEditing ? (
                      <Input
                        type="text"
                        value={config.authorization?.headerName || ''}
                        onChange={(e) => handleAuthChange('headerName', e.target.value)}
                        placeholder="Authorization"
                      />
                    ) : (
                      <Button
                        variant="readonly"
                        disabled
                        className="w-full justify-start rounded-lg font-mono"
                      >
                        {config.authorization?.headerName || t('common.notSet')}
                      </Button>
                    )}
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-2">
                      {config.authorization?.type === 'basic' ? t('apiConfig.username') : t('apiConfig.apiKeyToken')}
                    </label>
                    <div className="relative">
                      {isConfigEditing ? (
                        <Input
                          type={showSecrets ? 'text' : 'password'}
                          value={config.authorization?.headerValue || ''}
                          onChange={(e) => handleAuthChange('headerValue', e.target.value)}
                          placeholder="Enter your API key or token"
                        />
                      ) : (
                        <Button
                          variant="readonly"
                          disabled
                          className="w-full justify-start rounded-lg font-mono pr-10"
                        >
                          {showSecrets ? (config.authorization?.headerValue || t('common.notSet')) : '••••••••••••••••'}
                        </Button>
                      )}
                      <button
                        type="button"
                        onClick={toggleSecretVisibility}
                        className="absolute right-3 top-1/2 transform -translate-y-1/2 text-theme-secondary hover:text-theme-primary transition-colors duration-200"
                      >
                        {showSecrets ? <Eye className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default OverviewTab;