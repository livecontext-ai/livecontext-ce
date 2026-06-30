import React, { useState, useEffect, useMemo } from 'react';
import { Plus, X, AlertCircle, Info, Settings, Shield, Code, FileJson, FileText, TestTube } from 'lucide-react';
import { Button } from '@/components/ui/button';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Step3Props, PathParameter, QueryParameter } from '../types';
import { validateParameterValue } from '../utils';
import { useTranslations } from 'next-intl';
// Removed mock data imports - using dynamic data from API
import { useToolCategories, useToolNamesByCategory, useAllToolNames, useToolNamesBySubcategory, useToolNamesByToolCategoryAndSubcategory } from '@/hooks/useToolCategories';
import { useCategoriesContext } from '@/lib/hooks/smart-hooks-complete';
import ToolCategoryGroup from './ToolCategoryGroup';
import {
  FormSection,
  FormField,
  FormInput,
  FormSelect,
  FormTextarea,
  RichTextarea,
  FormGrid,
  ActionButton,
  InfoBox
} from './common';

const Step3: React.FC<Step3Props> = ({
  mcpTools,
  setMcpTools,
  apiConfig,
  setApiConfig,
  apiName,
  selectedCategory,
  selectedSubcategory,
  testEndpoint,
  testAllEndpoints
}) => {
  const t = useTranslations('developers.step3');
  // console.log('🏗️ [Step3] Component rendering/initializing');
  const { subcategories } = useCategoriesContext();
  const [showNewToolNameForm, setShowNewToolNameForm] = useState(false);
  const [newToolName, setNewToolName] = useState('');
  const [showNewToolCategoryForm, setShowNewToolCategoryForm] = useState(false);
  const [newToolCategory, setNewToolCategory] = useState('');
  const [isTestingAll, setIsTestingAll] = useState(false);
  const [toolCategorySearchTerm, setToolCategorySearchTerm] = useState('');

  const [parameterErrors, setParameterErrors] = useState<Record<string, string>>({});

  const [toolData, setToolData] = useState({
    id: '',
    name: '',
    description: '',
    category: '',
    subcategory: '',
    toolCategory: '',
    toolNameId: '', // ID du nom d'outil selectionne
    isCustomCategory: false, // Indique si c'est une categorie personnalisee
    isCustomToolName: false, // Indique si c'est un nom d'outil personnalise
    endpoint: '', // Endpoint de l'outil
    headers: [],
    parameters: [],
    pathParameters: [],
    queryParameters: [],
    bodyParams: [],
    bodySchema: '',
    response: { success: {}, error: {}, description: '', type: 'json' as const },
    status: 'draft' as const
  });

  // Hooks pour recuperer les donnees dynamiques
  // console.log('🔗 [Step3] About to call useToolCategories hook');

  // Essayer de recuperer l'ID de la subcategory depuis le localStorage
  const [selectedSubcategoryId, setSelectedSubcategoryId] = useState(() => {
    if (typeof window !== 'undefined') {
      return localStorage.getItem('livecontext_selectedSubcategoryId') || null;
    }
    return null;
  });

  // Sauvegarder l'ID quand on trouve une subcategory
  useEffect(() => {
    if (selectedSubcategory && subcategories.length > 0) {
      const subcategory = subcategories.find(sub => sub.name === selectedSubcategory);
      if (subcategory && subcategory.id !== selectedSubcategoryId) {
        setSelectedSubcategoryId(subcategory.id);
        if (typeof window !== 'undefined') {
          localStorage.setItem('livecontext_selectedSubcategoryId', subcategory.id);
        }
      }
    }
  }, [selectedSubcategory, subcategories, selectedSubcategoryId]);

  console.log('🎯 [Step3] Using subcategory ID:', selectedSubcategoryId);

  const { categories: dbCategories, loading: categoriesLoading, error: categoriesError } = useToolCategories(selectedSubcategoryId);

  // Charger les toolCategories au montage du composant
  useEffect(() => {
    // console.log('🔄 Step3: Loading tool categories on component mount');
  }, []);

  // Trouver l'ID de la categorie selectionnee
  const selectedCategoryId = useMemo(() => {
    return toolData.toolCategory
      ? dbCategories.find(cat => cat.name === toolData.toolCategory)?.id || ''
      : '';
  }, [toolData.toolCategory, dbCategories]);

  const { toolNames: allToolNames, isLoading: allToolNamesLoading } = useAllToolNames();

  // Utiliser le hook pour recuperer les noms d'outils specifiques a la categorie selectionnee
  const { toolNames: categoryToolNames, isLoading: categoryToolNamesLoading } = useToolNamesByCategory(selectedCategoryId);

  // Utiliser le nouveau hook pour recuperer les tool names par subcategory
  const { toolNames: subcategoryToolNames, isLoading: subcategoryToolNamesLoading } = useToolNamesBySubcategory(selectedSubcategoryId);

  // Utiliser le hook pour le filtrage combine (tool_category ET subcategory)
  const { toolNames: combinedToolNames, isLoading: combinedToolNamesLoading } = useToolNamesByToolCategoryAndSubcategory(selectedCategoryId, selectedSubcategoryId);

  // Utiliser les categories de la DB + les categories personnalisees
  const allToolCategories = useMemo(() => [
    ...dbCategories.map(cat => cat.name),
    ...(toolData.toolCategory && !dbCategories.some(cat => cat.name === toolData.toolCategory)
      ? [toolData.toolCategory]
      : [])
  ], [dbCategories, toolData.toolCategory]);

  // Filtrer les categories d'outils base sur le terme de recherche
  const toolCategories = useMemo(() => {
    if (!toolCategorySearchTerm.trim()) {
      return allToolCategories;
    }

    const searchTerm = toolCategorySearchTerm.toLowerCase().trim();
    return allToolCategories.filter(category =>
      category.toLowerCase().includes(searchTerm)
    );
  }, [allToolCategories, toolCategorySearchTerm]);

  // Auto-selectionner la categorie si il n'en reste qu'une seule apres filtrage
  useEffect(() => {
    if (toolCategories.length === 1 && toolCategorySearchTerm.trim()) {
      setToolData(prev => ({
        ...prev,
        toolCategory: toolCategories[0]
      }));
      // Vider le terme de recherche pour montrer la selection
      setToolCategorySearchTerm('');
    }
  }, [toolCategories, toolCategorySearchTerm]);

  // Filtrage des noms d'outils - utiliser UNIQUEMENT l'endpoint combine (tool_category ET subcategory)
  const filteredToolNames = useMemo(() => {
    // Utiliser UNIQUEMENT le filtrage combine (endpoint: /tool-categories/{toolCategoryId}/subcategory/{subcategoryId}/tool-names)
    if (selectedCategoryId && selectedSubcategoryId) {
      return [
        ...combinedToolNames.map(tool => tool.name),
        ...(toolData.name && !combinedToolNames.some(tool => tool.name === toolData.name)
          ? [toolData.name]
          : [])
      ];
    }

    // Si pas de tool_category ET subcategory, retourner un array vide
    return [];
  }, [selectedCategoryId, selectedSubcategoryId, combinedToolNames, toolData.name]);

  // Effet pour reinitialiser le nom de l'outil quand la tool_category ou subcategory change
  useEffect(() => {
    if ((selectedCategoryId || selectedSubcategoryId) && !toolData.isCustomToolName && toolData.name) {
      // Utiliser le filtrage combine si disponible, sinon subcategory seule
      const currentToolNames = selectedCategoryId && selectedSubcategoryId ? combinedToolNames : subcategoryToolNames;
      const isToolNameValid = currentToolNames.some(tool => tool.name === toolData.name);
      if (!isToolNameValid) {
        setToolData(prev => ({ ...prev, name: '' }));
      }
    }
  }, [selectedCategoryId, selectedSubcategoryId, combinedToolNames, subcategoryToolNames, toolData.isCustomToolName, toolData.name]);

  // Charger les toolNames quand une toolCategory est selectionnee
  useEffect(() => {
    if (toolData.toolCategory) {
      // console.log('🔄 Step3: Tool category selected, loading tool names for:', toolData.toolCategory);
      // Le chargement des toolNames est gere par le hook useToolNamesByCategory
      // qui se declenche automatiquement quand selectedCategoryId change
    }
  }, [toolData.toolCategory]);

  // States to manage section expansion (Step1 style)
  const [sectionsExpanded, setSectionsExpanded] = useState({
    newTool: true,
    toolsList: true,
    testSummary: true
  });

  const toggleSection = (section: keyof typeof sectionsExpanded) => {
    setSectionsExpanded(prev => ({
      ...prev,
      [section]: !prev[section]
    }));
  };

  const addNewToolName = () => {
    if (newToolName.trim()) {
      // Verifier les doublons avant d'ajouter
      if (isCustomToolNameDuplicate(newToolName, toolData.toolCategory)) {
        alert(t('alerts.customToolDuplicate', { name: newToolName, category: toolData.toolCategory }));
        return;
      }

      // Ajouter le nom a la liste des options et le selectionner
      const customEndpoint = `/${newToolName.toLowerCase().replace(/_/g, '-')}`;
      const customPathParameters = extractPathParameters(customEndpoint);
      const customQueryParameters = extractQueryParameters(customEndpoint);

      setToolData(prev => ({
        ...prev,
        name: newToolName,
        toolNameId: '', // Pas d'ID car c'est un nom personnalise
        isCustomToolName: true, // Marquer comme personnalise
        // Remplir la description avec le nom de l'outil personnalise
        description: newToolName,
        // Remplir l'endpoint avec un pattern par defaut
        endpoint: customEndpoint,
        // Remplir les path parameters (sera vide pour un endpoint simple)
        pathParameters: customPathParameters,
        // Remplir les query parameters (sera vide pour un endpoint simple)
        queryParameters: customQueryParameters
      }));
      setNewToolName('');
      setShowNewToolNameForm(false);
    }
  };

  const addNewToolCategory = () => {
    if (newToolCategory.trim()) {
      // Juste definir la categorie dans toolData, ne pas l'ajouter a la liste
      setToolData(prev => ({
        ...prev,
        toolCategory: newToolCategory,
        isCustomCategory: true // Marquer comme personnalisee
      }));
      setNewToolCategory('');
      setShowNewToolCategoryForm(false);
    }
  };

  // Fonction pour verifier si un nom d'outil existe deja dans la meme categorie
  const isToolNameDuplicate = (toolName: string, toolCategory: string) => {
    return mcpTools.some(tool =>
      tool.name.toLowerCase() === toolName.toLowerCase() &&
      tool.toolCategory === toolCategory
    );
  };

  // Fonction pour verifier si un nom d'outil personnalise existe deja dans la meme categorie
  const isCustomToolNameDuplicate = (toolName: string, toolCategory: string) => {
    return mcpTools.some(tool =>
      tool.name.toLowerCase() === toolName.toLowerCase() &&
      tool.toolCategory === toolCategory &&
      tool.isCustomToolName === true
    );
  };

  // Fonction pour extraire les path parameters d'un endpoint pattern
  const extractPathParameters = (endpointPattern: string): PathParameter[] => {
    const pathParams: PathParameter[] = [];
    const paramRegex = /\{([^}]+)\}/g;
    let match;

    while ((match = paramRegex.exec(endpointPattern)) !== null) {
      const paramName = match[1];
      pathParams.push({
        name: paramName,
        type: 'string',
        required: true,
        description: `The ${paramName} parameter`,
        example: `example_${paramName}`
      });
    }

    return pathParams;
  };

  // Fonction pour extraire les query parameters d'un endpoint pattern
  const extractQueryParameters = (endpointPattern: string): QueryParameter[] => {
    const queryParams: QueryParameter[] = [];

    // Extraire la partie query de l'URL (apres le ?)
    const queryString = endpointPattern.split('?')[1];
    if (!queryString) return queryParams;

    // Diviser par & pour obtenir chaque parametre
    const paramPairs = queryString.split('&');

    for (const paramPair of paramPairs) {
      if (!paramPair.trim()) continue;

      // Gerer les differents formats de parametres
      let paramName = '';
      let defaultValue = '';
      let isRequired = false;

      if (paramPair.includes('=')) {
        // Format: param=value ou param={value}
        const [name, value] = paramPair.split('=', 2);
        paramName = name.trim();

        if (value) {
          // Verifier si c'est un placeholder {value}
          const placeholderMatch = value.match(/^\{([^}]+)\}$/);
          if (placeholderMatch) {
            // C'est un placeholder, le parametre est requis
            isRequired = true;
            defaultValue = `example_${placeholderMatch[1]}`;
          } else {
            // C'est une valeur par defaut
            defaultValue = value;
            isRequired = false;
          }
        } else {
          // param= (vide)
          isRequired = false;
          defaultValue = '';
        }
      } else {
        // Format: juste param (sans =)
        paramName = paramPair.trim();
        isRequired = false;
        defaultValue = '';
      }

      // eviter les doublons
      if (paramName && !queryParams.some(p => p.name === paramName)) {
        queryParams.push({
          name: paramName,
          type: 'string',
          required: isRequired,
          description: `The ${paramName} parameter`,
          example: defaultValue || `example_${paramName}`,
          defaultValue: isRequired ? undefined : defaultValue || undefined
        });
      }
    }

    return queryParams;
  };

  // Fonction wrapper pour testAllEndpoints avec gestion de l'etat
  const handleTestAllEndpoints = () => {
    setIsTestingAll(true);
    testAllEndpoints();

    // Reset l'etat apres un delai pour permettre aux tests de se terminer
    setTimeout(() => {
      setIsTestingAll(false);
    }, 3000); // 3 secondes pour permettre aux tests de se terminer
  };

  const addMcpTool = () => {
    // More robust manual validation
    if (!toolData.name.trim()) {
      alert(t('alerts.selectToolName'));
      return;
    }
    if (!toolData.description.trim()) {
      alert(t('alerts.enterDescription'));
      return;
    }
    if (toolData.description.length > 250) {
      alert(t('alerts.descriptionTooLong'));
      return;
    }
    if (!toolData.toolCategory.trim()) {
      alert(t('alerts.selectCategory'));
      return;
    }
    if (!selectedCategory || !selectedSubcategory) {
      alert(t('alerts.configureCategories'));
      return;
    }

    // Verifier les doublons
    if (isToolNameDuplicate(toolData.name, toolData.toolCategory)) {
      alert(t('alerts.toolDuplicate', { name: toolData.name, category: toolData.toolCategory }));
      return;
    }

    const newTool = {
      ...toolData,
      id: toolData.toolNameId || `tool_${Date.now()}`, // Utiliser l'ID du nom d'outil de la DB ou generer un ID pour les personnalises
      toolNameId: toolData.toolNameId, // ID du nom d'outil de la DB
      isCustomCategory: toolData.isCustomCategory, // Marquer si c'est une categorie personnalisee
      isCustomToolName: toolData.isCustomToolName, // Marquer si c'est un nom d'outil personnalise
      category: selectedCategory,
      subcategory: selectedSubcategory,
      method: 'GET' as const,
      endpoint: toolData.endpoint || '', // Utiliser l'endpoint de toolData
      pricing: 'free' as const,
      rateLimit: '1000 requests/hour',
      isUsable: !toolData.isCustomCategory && !toolData.isCustomToolName // Non utilisable si personnalise
    };

    setMcpTools([...(mcpTools as any[]), newTool]);

    // Reset form for next tool
    setToolData({
      id: '',
      name: '',
      description: '',
      category: '',
      subcategory: '',
      toolCategory: '',
      toolNameId: '',
      isCustomCategory: false,
      isCustomToolName: false,
      endpoint: '',
      headers: [],
      parameters: [],
      pathParameters: [],
      queryParameters: [],
      bodyParams: [],
      bodySchema: '',
      response: { success: {}, error: {}, description: '', type: 'json' },
      status: 'draft'
    });

    // Reset add forms
    setShowNewToolNameForm(false);
    setShowNewToolCategoryForm(false);
    setNewToolName('');
    setNewToolCategory('');

    // Prevent automatic focus on required fields
    setTimeout(() => {
      // Scroll to top of tools section to show newly added tool
      const toolsSection = document.querySelector('[data-tools-section]');
      if (toolsSection) {
        toolsSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    }, 100);
  };

  return (
    <div className="space-y-6">
      {/* New Tool Configuration Section */}
      <FormSection
        title={t('newTool.title')}
        description={t('newTool.description')}
        icon={Settings}
        collapsible
        isExpanded={sectionsExpanded.newTool}
        onToggle={() => toggleSection('newTool')}
      >
        {/* Warning message if configuration incomplete */}
        {!selectedCategory || !selectedSubcategory ? (
          <InfoBox
            type="warning"
            title={t('newTool.incompleteConfig')}
          >
            <p>
              {t('newTool.configureFirst')}
            </p>
          </InfoBox>
        ) : null}

        {/* Subsection: New tool configuration */}
        <div className="space-y-4">

          {/* Tool category */}
          <FormField
            label={t('category.label')}
            required
            description={categoriesLoading ? t('category.loading') : t('category.description')}
          >
            <div className="space-y-4">
              {showNewToolCategoryForm && (
                <div className="p-4 bg-theme-tertiary rounded-xl border border-theme space-y-4">
                  <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-0">
                    <div className="flex-1 sm:mr-4">
                      <FormInput
                        type="text"
                        value={newToolCategory}
                        onChange={setNewToolCategory}
                        placeholder={t('category.placeholder')}
                      />
                    </div>
                    <div className="flex flex-col sm:flex-row gap-2 sm:gap-0 sm:flex-shrink-0">
                      <Button
                        variant="default"
                        size="sm"
                        onClick={addNewToolCategory}
                        disabled={!newToolCategory.trim()}
                        className="w-full sm:w-auto"
                      >
                        {t('common.add')}
                      </Button>
                      <div className="sm:ml-2">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setShowNewToolCategoryForm(false)}
                          className="w-full sm:w-auto"
                        >
                          <X className="w-4 h-4 mr-1" />
                          {t('common.cancel')}
                        </Button>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* Search field for tool categories */}
              <div className="space-y-2">
                <div className="relative">
                  <FormInput
                    type="text"
                    value={toolCategorySearchTerm}
                    onChange={setToolCategorySearchTerm}
                    placeholder={t('category.searchPlaceholder')}
                    className="w-full pr-10"
                  />
                  {toolCategorySearchTerm && (
                    <button
                      type="button"
                      onClick={() => setToolCategorySearchTerm('')}
                      className="absolute right-3 top-1/2 transform -translate-y-1/2 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
                      title={t('common.clearSearch')}
                    >
                      <X className="w-4 h-4" />
                    </button>
                  )}
                </div>
                {toolCategorySearchTerm && (
                  <div className="text-xs text-gray-500 dark:text-gray-400">
                    {t('category.showingCount', { shown: toolCategories.length, total: allToolCategories.length })}
                  </div>
                )}
              </div>

              <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-0">
                <div className="flex-1 sm:mr-4">
                  <div className="relative">
                    <FormSelect
                      value={toolData.toolCategory}
                      onChange={(value) => {
                        const selectedCategory = dbCategories.find(cat => cat.name === value);
                        setToolData(prev => ({
                          ...prev,
                          toolCategory: value,
                          // Ne reset le nom que si ce n'est pas un nom personnalise
                          ...(prev.isCustomToolName ? {} : { name: '', toolNameId: '' }),
                          isCustomCategory: !selectedCategory // Marquer comme personnalise si pas trouve dans la DB
                        }));
                        // Effacer le terme de recherche apres selection
                        setToolCategorySearchTerm('');
                      }}
                      options={toolCategories.map(cat => ({ value: cat, label: cat }))}
                      placeholder={
                        categoriesLoading
                          ? t('category.loading')
                          : toolCategorySearchTerm && toolCategories.length === 0
                            ? t('category.noMatch')
                            : t('category.selectFirst')
                      }
                      disabled={categoriesLoading}
                    />
                    {toolData.isCustomCategory && (
                      <div className="absolute -top-2 -right-2 bg-yellow-500 text-white text-xs px-2 py-1 rounded-full">
                        {t('common.custom')}
                      </div>
                    )}
                  </div>
                </div>
                <div className="flex-shrink-0 sm:self-start">
                  <Button
                    variant="ghost"
                    size="icon"
                    className="rounded-full w-10 h-10 sm:w-10 sm:h-10 min-w-[44px] min-h-[44px]"
                    onClick={() => setShowNewToolCategoryForm(!showNewToolCategoryForm)}
                  >
                    <Plus className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
          </FormField>

          {/* Tool name */}
          <FormField
            label={t('toolName.label')}
            required
            description={t('toolName.description')}
          >
            <div className="space-y-4">
              {showNewToolNameForm && (
                <div className="p-4 bg-theme-tertiary rounded-xl border border-theme space-y-4">
                  {/* Message d'avertissement pour les doublons de noms personnalises */}
                  {newToolName.trim() && toolData.toolCategory.trim() && isCustomToolNameDuplicate(newToolName, toolData.toolCategory) && (
                    <div className="p-3 bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-700 rounded-lg">
                      <div className="flex items-center space-x-2">
                        <AlertCircle className="w-4 h-4 text-yellow-600 dark:text-yellow-400" />
                        <span className="text-sm text-yellow-800 dark:text-yellow-200">
                          {t('toolName.duplicateWarning', { name: newToolName, category: toolData.toolCategory })}
                        </span>
                      </div>
                    </div>
                  )}

                  <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-0">
                    <div className="flex-1 sm:mr-4">
                      <FormInput
                        type="text"
                        value={newToolName}
                        onChange={setNewToolName}
                        placeholder={t('toolName.placeholder')}
                      />
                    </div>
                    <div className="flex flex-col sm:flex-row gap-2 sm:gap-0 sm:flex-shrink-0">
                      <Button
                        variant="default"
                        size="sm"
                        onClick={addNewToolName}
                        disabled={!newToolName.trim() || !toolData.toolCategory || isCustomToolNameDuplicate(newToolName, toolData.toolCategory)}
                        className="w-full sm:w-auto"
                      >
                        {t('common.add')}
                      </Button>
                      <div className="sm:ml-2">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setShowNewToolNameForm(false)}
                          className="w-full sm:w-auto"
                        >
                          <X className="w-4 h-4 mr-1" />
                          {t('common.cancel')}
                        </Button>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-0">
                <div className="flex-1 sm:mr-4">
                  <div className="relative">
                    <FormSelect
                      value={toolData.name}
                      onChange={(value) => {
                        // Chercher l'outil d'abord dans le filtrage combine, puis subcategory, puis tous les noms disponibles
                        const selectedTool = combinedToolNames.find(tool => tool.name === value) ||
                          subcategoryToolNames.find(tool => tool.name === value) ||
                          allToolNames.find(tool => tool.name === value);
                        const endpointPattern = selectedTool?.endpointPattern || '';
                        const pathParameters = endpointPattern ? extractPathParameters(endpointPattern) : [];
                        const queryParameters = endpointPattern ? extractQueryParameters(endpointPattern) : [];

                        setToolData(prev => ({
                          ...prev,
                          name: value,
                          toolNameId: selectedTool?.id || '',
                          isCustomToolName: !selectedTool, // Marquer comme personnalise si pas trouve dans la DB
                          // Remplir automatiquement la description avec la description du toolname
                          description: selectedTool?.description || prev.description,
                          // Remplir automatiquement l'endpoint avec l'endpoint du toolname
                          endpoint: endpointPattern || prev.endpoint,
                          // Remplir automatiquement les path parameters
                          pathParameters: pathParameters,
                          // Remplir automatiquement les query parameters
                          queryParameters: queryParameters
                        }));
                      }}
                      options={filteredToolNames.map(toolName => ({ value: toolName, label: toolName }))}
                      placeholder={
                        !selectedSubcategoryId
                          ? t('toolName.selectSubcategoryFirst')
                          : combinedToolNamesLoading || subcategoryToolNamesLoading
                            ? t('toolName.loading')
                            : filteredToolNames.length === 0
                              ? t('toolName.noToolsAvailable')
                              : t('toolName.selectName')
                      }
                      disabled={categoryToolNamesLoading || !toolData.toolCategory}
                    />
                    {toolData.isCustomToolName && (
                      <div className="absolute -top-2 -right-2 bg-yellow-500 text-white text-xs px-2 py-1 rounded-full">
                        {t('common.custom')}
                      </div>
                    )}
                  </div>
                </div>
                <div className="flex-shrink-0 sm:self-start">
                  <Button
                    variant="ghost"
                    size="icon"
                    className="rounded-full w-10 h-10 sm:w-10 sm:h-10 min-w-[44px] min-h-[44px]"
                    onClick={() => setShowNewToolNameForm(!showNewToolNameForm)}
                  >
                    <Plus className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
          </FormField>

          {/* Tool description */}
          <FormField
            label={t('toolDescription.label')}
            required
          >
            <div className="space-y-3">
              <InfoBox
                type="info"
                title={t('toolDescription.infoTitle')}
              >
                <p>
                  {t('toolDescription.infoText')}
                </p>
              </InfoBox>

              <FormTextarea
                value={toolData.description}
                onChange={(value) => {
                  if (value.length <= 250) {
                    setToolData(prev => ({ ...prev, description: value }));
                  }
                }}
                rows={3}
                placeholder={t('toolDescription.placeholder')}
              />
              <div className="flex justify-end">
                <span className={`text-xs ${toolData.description.length > 200 ? 'text-red-500' : 'text-gray-500'}`}>
                  {t('toolDescription.charCount', { count: toolData.description.length })}
                </span>
              </div>
            </div>
          </FormField>

          {/* Message d'avertissement pour les doublons */}
          {toolData.name.trim() && toolData.toolCategory.trim() && isToolNameDuplicate(toolData.name, toolData.toolCategory) && (
            <div className="p-3 bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-700 rounded-lg">
              <div className="flex items-center space-x-2">
                <AlertCircle className="w-4 h-4 text-yellow-600 dark:text-yellow-400" />
                <span className="text-sm text-yellow-800 dark:text-yellow-200">
                  {t('toolName.duplicateWarning', { name: toolData.name, category: toolData.toolCategory })}
                </span>
              </div>
            </div>
          )}

          <div className="flex justify-center">
            <Button
              variant="ghost"
              onClick={addMcpTool}
              disabled={
                !toolData.name.trim() ||
                !toolData.description.trim() ||
                toolData.description.length > 250 ||
                !toolData.toolCategory.trim() ||
                !selectedCategory ||
                !selectedSubcategory ||
                isToolNameDuplicate(toolData.name, toolData.toolCategory)
              }
              className="h-12 px-6"
            >
              <Plus className="w-4 h-4 mr-2" />
              {t('newTool.addButton')}
            </Button>
          </div>
        </div>
      </FormSection>

      {/* Tools List Section */}
      <FormSection
        title={t('toolsList.title')}
        description={t('toolsList.description', { count: mcpTools.length })}
        icon={Code}
        collapsible
        isExpanded={sectionsExpanded.toolsList}
        onToggle={() => toggleSection('toolsList')}
      >
        <div className={mcpTools.length === 0 ? "opacity-50 pointer-events-none" : ""}>
          {mcpTools.length === 0 ? (
            <div className="text-center py-8">
              <p className="text-gray-400 mb-4">{t('toolsList.empty')}</p>
            </div>
          ) : (
            <div className="space-y-6" data-tools-section>
              {/* Group tools by category */}
              {Object.entries(
                mcpTools.reduce((acc, tool) => {
                  if (!acc[tool.toolCategory]) {
                    acc[tool.toolCategory] = [];
                  }
                  acc[tool.toolCategory].push(tool);
                  return acc;
                }, {} as Record<string, any[]>)
              ).map(([category, tools]) => (
                <ToolCategoryGroup
                  key={category}
                  category={category}
                  tools={tools}
                  onToolUpdate={(toolIndex, updatedTool) => {
                    console.log(`🔧 Updating tool: localIndex=${toolIndex}, toolName=${updatedTool.name}, toolCategory=${updatedTool.toolCategory}`);

                    // Approche simplifiee : trouver l'index global directement
                    let globalIndex = -1;

                    // Methode 1: Recherche par ID unique (priorite)
                    if (updatedTool.id) {
                      globalIndex = mcpTools.findIndex(t => t.id === updatedTool.id);
                    }

                    // Methode 2: Recherche par proprietes uniques (sans endpoint)
                    if (globalIndex === -1) {
                      globalIndex = mcpTools.findIndex(t =>
                        t.name === updatedTool.name &&
                        t.toolCategory === updatedTool.toolCategory &&
                        t.category === updatedTool.category &&
                        t.subcategory === updatedTool.subcategory &&
                        t.method === updatedTool.method
                      );
                    }

                    // Methode 2: Calcul base sur la position dans le tableau groupe
                    if (globalIndex === -1) {
                      // Reconstruire le meme groupement que dans le render
                      const groupedTools = mcpTools.reduce((acc, t) => {
                        if (!acc[t.toolCategory]) {
                          acc[t.toolCategory] = [];
                        }
                        acc[t.toolCategory].push(t);
                        return acc;
                      }, {} as Record<string, any[]>);

                      // Trouver l'index de la categorie courante
                      const categoryNames = Object.keys(groupedTools);
                      const currentCategoryIndex = categoryNames.indexOf(category);

                      // Compter les outils des categories precedentes
                      let toolsBeforeCurrentCategory = 0;
                      for (let i = 0; i < currentCategoryIndex; i++) {
                        toolsBeforeCurrentCategory += groupedTools[categoryNames[i]].length;
                      }

                      globalIndex = toolsBeforeCurrentCategory + toolIndex;
                    }

                    console.log(`✅ Found global index for update: ${globalIndex} for tool: ${updatedTool.name}`);

                    if (globalIndex !== -1 && globalIndex < mcpTools.length) {
                      const newTools = [...mcpTools];
                      newTools[globalIndex] = updatedTool;
                      setMcpTools(newTools);
                      console.log(`✅ Updated tool at global index ${globalIndex}: ${updatedTool.name}`);
                    } else {
                      console.error(`❌ Could not find valid global index for tool update: ${updatedTool.name}. GlobalIndex: ${globalIndex}, mcpTools.length: ${mcpTools.length}`);
                    }
                  }}
                  onTestEndpoint={(localToolIndex) => {
                    const tool = tools[localToolIndex];
                    console.log(`🔍 Testing tool: localIndex=${localToolIndex}, toolName=${tool.name}, toolCategory=${tool.toolCategory}`);

                    // Approche simplifiee : trouver l'index global directement
                    let globalIndex = -1;

                    // Methode 1: Recherche par reference d'objet (plus fiable)
                    globalIndex = mcpTools.findIndex(t => t === tool);

                    // Methode 2: Si pas trouve par reference, recherche par proprietes uniques
                    if (globalIndex === -1) {
                      globalIndex = mcpTools.findIndex(t =>
                        t.name === tool.name &&
                        t.toolCategory === tool.toolCategory &&
                        t.category === tool.category &&
                        t.subcategory === tool.subcategory &&
                        t.method === tool.method &&
                        t.endpoint === tool.endpoint
                      );
                    }

                    // Methode 3: Calcul base sur la position dans le tableau groupe
                    if (globalIndex === -1) {
                      // Reconstruire le meme groupement que dans le render
                      const groupedTools = mcpTools.reduce((acc, t) => {
                        if (!acc[t.toolCategory]) {
                          acc[t.toolCategory] = [];
                        }
                        acc[t.toolCategory].push(t);
                        return acc;
                      }, {} as Record<string, any[]>);

                      // Trouver l'index de la categorie courante
                      const categoryNames = Object.keys(groupedTools);
                      const currentCategoryIndex = categoryNames.indexOf(category);

                      // Compter les outils des categories precedentes
                      let toolsBeforeCurrentCategory = 0;
                      for (let i = 0; i < currentCategoryIndex; i++) {
                        toolsBeforeCurrentCategory += groupedTools[categoryNames[i]].length;
                      }

                      globalIndex = toolsBeforeCurrentCategory + localToolIndex;
                    }

                    console.log(`✅ Found global index: ${globalIndex} for tool: ${tool.name}`);

                    if (globalIndex !== -1 && globalIndex < mcpTools.length) {
                      console.log(`🚀 Testing tool at global index ${globalIndex}: ${mcpTools[globalIndex].name}`);
                      testEndpoint(globalIndex);
                    } else {
                      console.error(`❌ Could not find valid global index for tool: ${tool.name}. GlobalIndex: ${globalIndex}, mcpTools.length: ${mcpTools.length}`);
                    }
                  }}
                  apiConfig={apiConfig}
                  mcpTools={mcpTools}
                  setMcpTools={setMcpTools}
                />
              ))}
            </div>
          )}
        </div>
      </FormSection>

      {/* Test Summary Section */}
      <FormSection
        title={t('testSummary.title')}
        description={t('testSummary.description')}
        icon={TestTube}
        collapsible
        isExpanded={sectionsExpanded.testSummary}
        onToggle={() => toggleSection('testSummary')}
      >
        <div className={mcpTools.length === 0 ? "opacity-50 pointer-events-none" : ""}>
          <div className="flex items-center justify-end mb-4">
            <div className="flex items-center space-x-3">
              <button
                type="button"
                onClick={handleTestAllEndpoints}
                disabled={isTestingAll || mcpTools.length === 0}
                className={`flex items-center space-x-2 px-3 py-2 rounded transition-colors duration-200 ${isTestingAll
                    ? 'text-blue-500 cursor-wait'
                    : mcpTools.length === 0
                      ? 'text-gray-400 cursor-not-allowed'
                      : 'text-theme-muted hover:text-green-500'
                  }`}
                title={mcpTools.length === 0 ? t('testSummary.addToolsFirst') : isTestingAll ? t('testSummary.testingAll') : t('testSummary.testAllEndpoints')}
              >
                <TestTube className="w-4 h-4" />
                <span className="text-sm font-medium">
                  {t('testSummary.testAll')}
                </span>
              </button>
              {isTestingAll && (
                <div className="px-2 py-1 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800">
                  {t('testSummary.testingAllBadge')}
                </div>
              )}
            </div>
          </div>

          {/* Validation indicator */}
          {mcpTools.length > 0 && (
            <div className={`p-3 rounded-lg border ${mcpTools.every(tool => tool.testStatus === 'success') && !mcpTools.some(tool => !tool.testStatus)
                ? 'bg-green-50 dark:bg-green-900/20 border-green-200 dark:border-green-700'
                : mcpTools.some(tool => tool.testStatus === 'error')
                  ? 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-700'
                  : 'bg-yellow-50 dark:bg-yellow-900/20 border-yellow-200 dark:border-yellow-700'
              }`}>
              <div className="flex items-center space-x-2">
                {mcpTools.every(tool => tool.testStatus === 'success') && !mcpTools.some(tool => !tool.testStatus) ? (
                  <>
                    <div className="w-5 h-5 bg-green-500 rounded-full flex items-center justify-center">
                      <span className="text-xs text-white font-bold">✓</span>
                    </div>
                    <span className="text-sm font-medium text-green-800 dark:text-green-200">
                      {t('testSummary.allValidated')}
                    </span>
                  </>
                ) : mcpTools.some(tool => tool.testStatus === 'error') ? (
                  <>
                    <div className="w-5 h-5 bg-red-500 rounded-full flex items-center justify-center">
                      <span className="text-xs text-white font-bold">✗</span>
                    </div>
                    <span className="text-sm font-medium text-red-800 dark:text-red-200">
                      {t('testSummary.someTestsFailed')}
                    </span>
                  </>
                ) : (
                  <>
                    <div className="w-5 h-5 bg-yellow-500 rounded-full flex items-center justify-center">
                      <span className="text-xs text-white font-bold">!</span>
                    </div>
                    <span className="text-sm font-medium text-yellow-800 dark:text-yellow-200">
                      {t('testSummary.notTestedYet', { count: mcpTools.filter(t => !t.testStatus).length })}
                    </span>
                  </>
                )}
              </div>

              {/* Additional endpoint information */}
              <div className="mt-3 pt-3 border-t border-gray-200 dark:border-gray-600">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                  <div className="flex items-center space-x-2">
                    <div className="w-3 h-3 bg-blue-500 rounded-full"></div>
                    <span className="text-gray-600 dark:text-gray-400">
                      {t('testSummary.stats.withEndpoint', { count: mcpTools.filter(t => t.endpoint && t.endpoint.trim() !== '').length })}
                    </span>
                  </div>
                  <div className="flex items-center space-x-2">
                    <div className="w-3 h-3 bg-gray-400 rounded-full"></div>
                    <span className="text-gray-600 dark:text-gray-400">
                      {t('testSummary.stats.withoutEndpoint', { count: mcpTools.filter(t => !t.endpoint || t.endpoint.trim() === '').length })}
                    </span>
                  </div>
                  <div className="flex items-center space-x-2">
                    <div className="w-3 h-3 bg-green-500 rounded-full"></div>
                    <span className="text-gray-600 dark:text-gray-400">
                      {t('testSummary.stats.testedSuccess', { count: mcpTools.filter(t => t.testStatus === 'success').length })}
                    </span>
                  </div>
                  <div className="flex items-center space-x-2">
                    <div className="w-3 h-3 bg-red-500 rounded-full"></div>
                    <span className="text-gray-600 dark:text-gray-400">
                      {t('testSummary.stats.testError', { count: mcpTools.filter(t => t.testStatus === 'error').length })}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </FormSection>
    </div>
  );
};

export default Step3;
