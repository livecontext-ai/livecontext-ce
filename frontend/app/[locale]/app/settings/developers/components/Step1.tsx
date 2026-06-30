import React, { useState, useEffect, useCallback } from 'react';
import { Plus, X, Settings, Tag, Info, Zap, CheckCircle, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Step1Props, McpTool } from '../types';
import { useTranslations } from 'next-intl';
import { useCategoriesContext } from '@/lib/hooks/smart-hooks-complete';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import TemplateSelector from './TemplateSelector';
import LoadingSpinner from '@/components/LoadingSpinner';
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

const Step1: React.FC<Step1Props> = React.memo(({
    apiName,
    setApiName,
    selectedCategory,
    setSelectedCategory,
    selectedSubcategory,
    setSelectedSubcategory,
    apiDescription,
    setApiDescription,
    predefinedTools,
    apiNameValidation,
    setApiNameValidation,
}) => {
    const t = useTranslations('developers.step1');
    const {
        categories: dbCategories,
        subcategories: dbSubcategories,
        categoryOptions: contextCategoryOptions,
        getSubcategoriesByCategoryName,
        addCustomCategory,
        addCustomSubcategory,
        fetchSubcategoriesForCategory,
        categoriesLoading,
        subcategoriesLoading
    } = useCategoriesContext();

    const [showNewCategoryForm, setShowNewCategoryForm] = useState(false);
    const [newCategory, setNewCategory] = useState('');
    const [showNewSubcategoryForm, setShowNewSubcategoryForm] = useState(false);
    const [newSubcategory, setNewSubcategory] = useState('');
    const [showCategoriesInfo, setShowCategoriesInfo] = useState(false);
    const [showTemplateSelector, setShowTemplateSelector] = useState(false);

    // Use props for validation state instead of local state

    // etat local pour gerer les categories et sous-categories (comme Step3)
    const [categoryData, setCategoryData] = useState({
        category: selectedCategory,
        subcategory: selectedSubcategory,
        isCustomCategory: false,
        isCustomSubcategory: false
    });

    // Charger les categories au montage du composant
    useEffect(() => {
        console.log('🔄 Step1: Loading categories on component mount');
    }, []);

    // Charger les sous-categories quand une categorie est selectionnee
    useEffect(() => {
        if (categoryData.category && dbCategories.length > 0) {
            console.log('🔄 Step1: Category selected, loading subcategories for:', categoryData.category);
            // Trouver l'ID de la categorie et declencher le chargement
            const category = dbCategories.find(cat => cat.name === categoryData.category);
            if (category) {
                // Verifier si les sous-categories ne sont pas deja chargees
                const existingSubs = getSubcategoriesByCategoryName(categoryData.category);
                if (existingSubs.length === 0) {
                    console.log('🔄 No subcategories found, fetching...');
                    fetchSubcategoriesForCategory(category.id);
                } else {
                    console.log('✅ Subcategories already loaded for:', categoryData.category);
                }
            }
        }
    }, [categoryData.category, dbCategories.length]); // Supprimer les fonctions des dependances

    // States to manage section expansion
    const [sectionsExpanded, setSectionsExpanded] = useState({
        basic: true,
        categories: true
    });

    // Synchroniser l'etat local avec les props (comme Step3)
    useEffect(() => {
        setCategoryData(prev => ({
            ...prev,
            category: selectedCategory,
            subcategory: selectedSubcategory
        }));
    }, [selectedCategory, selectedSubcategory]);

    // Fonction pour valider l'unicite du nom d'API
    const validateApiName = useCallback(async (name: string) => {
        if (!name.trim()) {
            setApiNameValidation?.({
                isValid: true,
                message: '',
                isChecking: false
            });
            return;
        }

        setApiNameValidation?.({ isValid: true, message: '', isChecking: true });

        try {
            const result = await unifiedApiService.checkApiNameUniquenes(name);
            setApiNameValidation?.({
                isValid: result.isUnique,
                message: result.message || '',
                isChecking: false
            });
        } catch (error) {
            console.error('Error validating API name:', error);
            setApiNameValidation?.({
                isValid: true, // En cas d'erreur, considerer comme valide
                message: '',
                isChecking: false
            });
        }
    }, [setApiNameValidation]);

    // Valider le nom d'API quand il change
    useEffect(() => {
        const timeoutId = setTimeout(() => {
            validateApiName(apiName);
        }, 500); // Delai de 500ms pour eviter trop d'appels

        return () => clearTimeout(timeoutId);
    }, [apiName, validateApiName]);

    const toggleSection = (section: keyof typeof sectionsExpanded) => {
        setSectionsExpanded(prev => ({
            ...prev,
            [section]: !prev[section]
        }));
    };

    const handleTemplateSelect = (tools: McpTool[], apiConfig: any) => {
        // Handle template selection logic here
        console.log('Template selected with tools:', tools);
        console.log('Template selected with apiConfig:', apiConfig);
        setShowTemplateSelector(false);
    };

    const addNewCategory = () => {
        if (newCategory.trim()) {
            // Verifier si la categorie existe deja dans la DB
            const existingCategory = dbCategories.find(cat => cat.name === newCategory);

            if (!existingCategory) {
                // Creer une categorie personnalisee
                addCustomCategory(newCategory);
            }

            // Trouver l'ID de la categorie (nouvelle ou existante)
            const categoryId = existingCategory?.id || dbCategories.find(cat => cat.name === newCategory)?.id || '';

            setCategoryData(prev => ({
                ...prev,
                category: newCategory,
                subcategory: '', // Reset subcategory when category changes
                isCustomCategory: !existingCategory // Marquer comme personnalisee seulement si pas trouvee dans la DB
            }));

            // Mettre a jour les props parent
            setSelectedCategory(newCategory);
            setSelectedSubcategory('');

            // Sauvegarder dans localStorage
            if (typeof window !== 'undefined') {
                localStorage.setItem('livecontext_selectedCategory', newCategory);
                localStorage.setItem('livecontext_selectedCategoryId', categoryId);
                localStorage.setItem('livecontext_selectedSubcategory', '');
                localStorage.setItem('livecontext_selectedSubcategoryId', '');
            }

            setNewCategory('');
            setShowNewCategoryForm(false);
        }
    };

    const addNewSubcategory = () => {
        if (newSubcategory.trim() && categoryData.category) {
            // Verifier si la sous-categorie existe deja dans la DB pour cette categorie
            const existingSubcategory = getSubcategoriesByCategoryName(categoryData.category)
                .find(sub => sub.value === newSubcategory);

            if (!existingSubcategory) {
                // Creer une sous-categorie personnalisee
                addCustomSubcategory(newSubcategory, categoryData.category);
            }

            // Trouver l'ID de la sous-categorie (nouvelle ou existante)
            const categoryId = dbCategories.find(cat => cat.name === categoryData.category)?.id || '';
            const subcategoryId = existingSubcategory?.id || dbSubcategories.find(sub =>
                sub.name === newSubcategory && sub.categoryId === categoryId
            )?.id || '';

            setCategoryData(prev => ({
                ...prev,
                subcategory: newSubcategory,
                isCustomSubcategory: !existingSubcategory // Marquer comme personnalisee seulement si pas trouvee dans la DB
            }));

            // Mettre a jour les props parent
            setSelectedSubcategory(newSubcategory);

            // Sauvegarder dans localStorage
            if (typeof window !== 'undefined') {
                localStorage.setItem('livecontext_selectedSubcategory', newSubcategory);
                localStorage.setItem('livecontext_selectedSubcategoryId', subcategoryId);
            }

            setNewSubcategory('');
            setShowNewSubcategoryForm(false);
        }
    };

    // Utiliser les options du contexte (similaire a Step3)
    const availableCategories = contextCategoryOptions.map(cat => cat.value);

    // Trouver l'ID de la categorie selectionnee
    const selectedCategoryId = categoryData.category
        ? dbCategories.find(cat => cat.name === categoryData.category)?.id
        : '';

    // Sous-categories filtrees par categorie selectionnee (utiliser le nouveau systeme)
    const availableSubcategories = categoryData.category
        ? getSubcategoriesByCategoryName(categoryData.category).map(sub => sub.value)
        : [];

    return (
        <div className="space-y-8">
            {/* Basic Configuration Section */}
            <FormSection
                title={t('title')}
                description={t('description')}
                icon={Settings}
                collapsible
                isExpanded={sectionsExpanded.basic}
                onToggle={() => toggleSection('basic')}
            >
                <FormField
                    label={t('fields.apiName')}
                    required
                    description={t('fields.apiNameDescription')}
                >
                    <div className="relative">
                        <FormInput
                            type="text"
                            value={apiName}
                            onChange={setApiName}
                            placeholder={t('fields.apiNamePlaceholder')}
                            className={apiNameValidation.isValid ? '' : 'border-red-500 focus:border-red-500'}
                        />
                        {apiNameValidation.isChecking && (
                            <div className="absolute right-3 top-1/2 transform -translate-y-1/2">
                                <LoadingSpinner size="sm" className="text-blue-500" />
                            </div>
                        )}
                        {!apiNameValidation.isChecking && apiName && (
                            <div className="absolute right-3 top-1/2 transform -translate-y-1/2">
                                {apiNameValidation.isValid ? (
                                    <CheckCircle className="w-4 h-4 text-green-500" />
                                ) : (
                                    <AlertCircle className="w-4 h-4 text-red-500" />
                                )}
                            </div>
                        )}
                    </div>
                    {apiNameValidation.message && (
                        <div className={`mt-2 text-sm ${apiNameValidation.isValid ? 'text-green-600' : 'text-red-600'}`}>
                            {apiNameValidation.message}
                        </div>
                    )}
                </FormField>

                <div className="mt-4">
                    <FormField
                        label={t('fields.apiDescription')}
                        required
                        description={t('fields.apiDescriptionDescription')}
                    >
                        <RichTextarea
                            value={apiDescription}
                            onChange={setApiDescription}
                            rows={4}
                            placeholder={t('fields.apiDescriptionPlaceholder')}
                        />
                    </FormField>
                </div>
            </FormSection>

            {/* Category Management Section */}
            <FormSection
                title={t('category.title')}
                description={t('category.description')}
                icon={Tag}
                collapsible
                isExpanded={sectionsExpanded.categories}
                onToggle={() => toggleSection('categories')}
            >
                <div className="flex items-center justify-between mb-6">
                    <button
                        type="button"
                        onClick={() => setShowCategoriesInfo(!showCategoriesInfo)}
                        className="text-theme-muted hover:text-theme-primary transition-colors"
                    >
                        <Info className="w-5 h-5" />
                    </button>
                </div>

                {showCategoriesInfo && (
                    <InfoBox
                        type="info"
                        title={t('category.infoTitle')}
                    >
                        <p className="text-theme-secondary mb-4">
                            {t('category.infoText1')}
                        </p>
                        <p>
                            {t('category.infoText2')}
                        </p>
                    </InfoBox>
                )}

                {/* Main category */}
                <div className="mt-4">
                    <FormField
                        label={t('category.mainCategory')}
                        required
                        description={t('category.mainCategoryDescription')}
                    >
                        <div className="space-y-4">
                            {showNewCategoryForm && (
                                <div className="p-4 bg-theme-tertiary rounded-xl border border-theme space-y-4">
                                    <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-0">
                                        <div className="flex-1 sm:mr-4">
                                            <FormInput
                                                type="text"
                                                value={newCategory}
                                                onChange={setNewCategory}
                                                placeholder={t('category.newCategoryPlaceholder')}
                                            />
                                        </div>
                                        <div className="flex flex-col sm:flex-row gap-2 sm:gap-0 sm:flex-shrink-0">
                                            <Button
                                                variant="default"
                                                size="sm"
                                                onClick={addNewCategory}
                                                disabled={!newCategory.trim()}
                                                className="w-full sm:w-auto"
                                            >
                                                {t('common.add')}
                                            </Button>
                                            <div className="sm:ml-2">
                                                <Button
                                                    variant="ghost"
                                                    size="sm"
                                                    onClick={() => setShowNewCategoryForm(false)}
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

                            <div className="flex items-center justify-between">
                                <div className="flex-1 mr-4">
                                    {categoriesLoading ? (
                                        <div className="flex items-center justify-center p-3 border border-theme rounded-lg">
                                            <LoadingSpinner size="md" text={t('category.loadingCategories')} />
                                        </div>
                                    ) : (
                                        <div className="relative">
                                            <FormSelect
                                                value={categoryData.category}
                                                onChange={(value) => {
                                                    const selectedCategory = contextCategoryOptions.find(cat => cat.value === value);
                                                    const isExistingCategory = selectedCategory && !selectedCategory.isCustom;

                                                    // Trouver l'ID de la categorie dans la base de donnees
                                                    const categoryId = dbCategories.find(cat => cat.name === value)?.id || '';

                                                    setCategoryData(prev => ({
                                                        ...prev,
                                                        category: value,
                                                        subcategory: '', // Reset subcategory when category changes
                                                        isCustomCategory: !isExistingCategory
                                                    }));

                                                    // Mettre a jour les props parent
                                                    setSelectedCategory(value);
                                                    setSelectedSubcategory('');

                                                    // Sauvegarder dans localStorage
                                                    if (typeof window !== 'undefined') {
                                                        localStorage.setItem('livecontext_selectedCategory', value);
                                                        localStorage.setItem('livecontext_selectedCategoryId', categoryId);
                                                        localStorage.setItem('livecontext_selectedSubcategory', '');
                                                        localStorage.setItem('livecontext_selectedSubcategoryId', '');
                                                    }
                                                }}
                                                options={contextCategoryOptions.map(cat => ({
                                                    value: cat.value,
                                                    label: cat.label,
                                                    icon: cat.icon,
                                                    color: cat.color
                                                }))}
                                                placeholder={t('category.selectCategory')}
                                            />
                                            {categoryData.isCustomCategory && (
                                                <div className="absolute -top-2 -right-2 bg-yellow-500 text-white text-xs px-2 py-1 rounded-full">
                                                    {t('common.custom')}
                                                </div>
                                            )}
                                        </div>
                                    )}
                                </div>
                                <div className="flex-shrink-0">
                                    <Button
                                        variant="ghost"
                                        size="icon"
                                        className="rounded-full w-10 h-10 sm:w-10 sm:h-10 min-w-[44px] min-h-[44px]"
                                        onClick={() => setShowNewCategoryForm(!showNewCategoryForm)}
                                        disabled={categoriesLoading}
                                    >
                                        <Plus className="h-4 w-4" />
                                    </Button>
                                </div>
                            </div>
                        </div>
                    </FormField>
                </div>

                {/* Subcategory - Toujours visible mais desactive si pas de categorie */}
                <div className="mt-2">
                    <FormField
                        label={t('subcategory.label')}
                        required
                        description={!selectedCategory ? t('subcategory.selectCategoryFirst') : t('subcategory.description')}
                    >
                        <div className="space-y-4">
                            {showNewSubcategoryForm && (
                                <div className="p-4 bg-theme-tertiary rounded-xl border border-theme space-y-4">
                                    <div className="flex items-center justify-between">
                                        <div className="flex-1 mr-4">
                                            <FormInput
                                                type="text"
                                                value={newSubcategory}
                                                onChange={setNewSubcategory}
                                                placeholder={t('subcategory.newSubcategoryPlaceholder')}
                                            />
                                        </div>
                                        <div className="flex flex-col sm:flex-row gap-2 sm:gap-0 sm:flex-shrink-0">
                                            <Button
                                                variant="default"
                                                size="sm"
                                                onClick={addNewSubcategory}
                                                disabled={!newSubcategory.trim()}
                                                className="w-full sm:w-auto"
                                            >
                                                {t('common.add')}
                                            </Button>
                                            <div className="sm:ml-2">
                                                <Button
                                                    variant="ghost"
                                                    size="sm"
                                                    onClick={() => setShowNewSubcategoryForm(false)}
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

                            <div className="flex items-center justify-between">
                                <div className="flex-1 mr-4">
                                    {subcategoriesLoading ? (
                                        <div className="flex items-center justify-center p-3 border border-theme rounded-lg">
                                            <LoadingSpinner size="md" text={t('subcategory.loadingSubcategories')} />
                                        </div>
                                    ) : (
                                        <div className="relative">
                                            <FormSelect
                                                value={categoryData.subcategory}
                                                onChange={(value) => {
                                                    const existingSubcategory = getSubcategoriesByCategoryName(categoryData.category)
                                                        .find(sub => sub.value === value);
                                                    const isExistingSubcategory = existingSubcategory && !existingSubcategory.isCustom;

                                                    // Trouver l'ID de la sous-categorie dans la base de donnees
                                                    const subcategoryId = dbSubcategories.find(sub =>
                                                        sub.name === value && sub.categoryId === dbCategories.find(cat => cat.name === categoryData.category)?.id
                                                    )?.id || '';

                                                    setCategoryData(prev => ({
                                                        ...prev,
                                                        subcategory: value,
                                                        isCustomSubcategory: !isExistingSubcategory
                                                    }));

                                                    // Mettre a jour les props parent
                                                    setSelectedSubcategory(value);

                                                    // Sauvegarder dans localStorage
                                                    if (typeof window !== 'undefined') {
                                                        localStorage.setItem('livecontext_selectedSubcategory', value);
                                                        localStorage.setItem('livecontext_selectedSubcategoryId', subcategoryId);
                                                    }
                                                }}
                                                options={getSubcategoriesByCategoryName(categoryData.category).map(sub => ({
                                                    value: sub.value,
                                                    label: sub.label,
                                                    icon: sub.icon,
                                                    color: sub.color
                                                }))}
                                                placeholder={
                                                    !categoryData.category
                                                        ? t('subcategory.selectCategoryFirst')
                                                        : subcategoriesLoading
                                                            ? t('subcategory.loadingSubcategories')
                                                            : getSubcategoriesByCategoryName(categoryData.category).length === 0
                                                                ? t('subcategory.noSubcategories')
                                                                : t('subcategory.selectSubcategory')
                                                }
                                                disabled={subcategoriesLoading || !categoryData.category}
                                            />
                                            {categoryData.isCustomSubcategory && (
                                                <div className="absolute -top-2 -right-2 bg-yellow-500 text-white text-xs px-2 py-1 rounded-full">
                                                    {t('common.custom')}
                                                </div>
                                            )}
                                        </div>
                                    )}
                                </div>
                                <div className="flex-shrink-0">
                                    <Button
                                        variant="ghost"
                                        size="icon"
                                        className="rounded-full w-10 h-10 sm:w-10 sm:h-10 min-w-[44px] min-h-[44px]"
                                        onClick={() => setShowNewSubcategoryForm(!showNewSubcategoryForm)}
                                        disabled={subcategoriesLoading || !categoryData.category}
                                    >
                                        <Plus className="h-4 w-4" />
                                    </Button>
                                </div>
                            </div>
                        </div>
                    </FormField>
                </div>
            </FormSection>

            {/* Template selection modal */}
            {showTemplateSelector && (
                <TemplateSelector
                    onTemplateSelect={handleTemplateSelect}
                    onClose={() => setShowTemplateSelector(false)}
                />
            )}
        </div>
    );
});

Step1.displayName = 'Step1';

export default Step1;
