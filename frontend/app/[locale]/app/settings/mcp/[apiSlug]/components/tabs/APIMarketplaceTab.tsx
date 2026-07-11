import React, { useState, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import {
  CreditCard,
  Code,
  Save,
  SquarePen,
  Settings,
  Edit,
  CheckCircle,
  DollarSign,
  Zap,
  Check,
  XCircle
} from 'lucide-react';
import { MonetizationConfig } from '@/app/[locale]/app/settings/developers/types';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';

interface APIMarketplaceTabProps {
  config: MonetizationConfig;
  setConfig: (config: MonetizationConfig) => void;
  isEditing: boolean;
  setIsEditing: (editing: boolean) => void;
  isSaving: boolean;
  hasChanges: boolean;
  setHasChanges: (hasChanges: boolean) => void;
  onSave: () => void;
  onCancel: () => void;
  mcpTools: Array<{
    id: string;
    name: string;
    toolCategory: string;
    endpoint: string;
  }>;
  userMonetizationState?: any;
  isLoading?: boolean;
  error?: string | null;
  onUpdateToolPaidConfig?: (apiToolId: string, toolConfig: {
    planName?: string;
    quota?: number;
    price?: number;
    overusageCost?: number;
    hardLimit?: boolean;
    rateLimitRequests?: number;
    rateLimitPeriod?: 'second' | 'minute' | 'hour' | 'day' | 'month';
  }) => Promise<any>;
  onBatchUpdateToolsPaidConfig?: (toolsConfig: Record<string, {
    planName?: string;
    quota?: number;
    price?: number;
    overusageCost?: number;
    hardLimit?: boolean;
    rateLimitRequests?: number;
    rateLimitPeriod?: 'second' | 'minute' | 'hour' | 'day' | 'month';
  }>) => Promise<any>;
  onUpdatePaidPlans?: (selectedPlans: Record<string, boolean>, planTools: Record<string, string[]>) => Promise<any>;
  setIsSubTabEditing?: (editing: boolean) => void;
  refreshData?: () => Promise<void>;
}

const APIMarketplaceTab: React.FC<APIMarketplaceTabProps> = ({
  config,
  setConfig,
  isEditing,
  setIsEditing,
  isSaving,
  hasChanges,
  setHasChanges,
  onSave,
  onCancel,
  mcpTools,
  userMonetizationState,
  isLoading,
  error,
  onUpdateToolPaidConfig,
  onBatchUpdateToolsPaidConfig,
  onUpdatePaidPlans,
  setIsSubTabEditing,
  refreshData
}) => {
  const t = useTranslations('mcp.apiMarketplaceTab');
  const [editingTool, setEditingTool] = useState<string | null>(null);
  const [editingPlan, setEditingPlan] = useState<string | null>(null);
  const [initialConfig, setInitialConfig] = useState<MonetizationConfig | null>(null);
  const [isToolEditing, setIsToolEditing] = useState(false);
  const [isPlanEditing, setIsPlanEditing] = useState(false);

  // Use the data passed from parent component
  const processedMonetizationState = userMonetizationState ? {
    tools: userMonetizationState.tools.map(tool => ({
      id: tool.id,
      name: tool.name,
      apiName: tool.apiName,
      apiSlug: tool.apiSlug,
      endpoint: tool.endpoint,
      method: tool.method,
      toolCategories: tool.toolCategories,
      monetization: tool.monetization.map(m => ({
        id: m.id,
        apiToolId: m.apiToolId,
        monetizationType: m.monetizationType,
        planName: m.planName,
        rateLimitRequests: m.rateLimitRequests,
        rateLimitPeriod: m.rateLimitPeriod,
        freeRequests: m.freeRequests,
        freeRequestsType: m.freeRequestsType,
        mauValue: m.mauValue,
        pricePerMau: m.pricePerMau,
        calls: m.calls,
        quota: m.quota,
        price: m.price,
        overusageCost: m.overusageCost,
        hardLimit: m.hardLimit,
        createdAt: m.createdAt,
        updatedAt: m.updatedAt
      }))
    })),
    totalTools: userMonetizationState?.totalTools || 0,
    userId: userMonetizationState?.userId || ''
  } : userMonetizationState;

  // Debug logging
  console.log('🔍 APIMarketplaceTab - userMonetizationState:', userMonetizationState);
  console.log('🔍 APIMarketplaceTab - processedMonetizationState:', processedMonetizationState);
  console.log('🔍 APIMarketplaceTab - config:', config);
  console.log('🔍 APIMarketplaceTab - mcpTools:', mcpTools);
  console.log('🔍 APIMarketplaceTab - editingTool state:', editingTool);
  console.log('🔍 APIMarketplaceTab - isEditing state:', isEditing);

  const handleCancel = () => {
    setEditingTool(null);
    setIsSubTabEditing?.(false);
    // Don't call onCancel() as it would trigger the main pricing model edit mode
    // Just reset the local editing state
  };

  const handleSave = () => {
    setEditingTool(null);
    handleSaveWithPlanChanges();
  };

  const handleToolEdit = (toolName: string) => {
    // If main editing mode is active, don't allow tool editing
    if (isEditing) {
      return;
    }
    console.log('🔧 handleToolEdit called for tool:', toolName);
    console.log('🔧 Current editingTool state:', editingTool);
    setEditingTool(toolName);
    setIsToolEditing(true);
    setIsSubTabEditing?.(true);
    console.log('🔧 After setEditingTool, editingTool should be:', toolName);
  };


  const handlePlanEdit = (planName: string) => {
    // If main editing mode is active, don't allow plan editing
    if (isEditing) {
      return;
    }
    
    // If another plan is already being edited, don't allow editing
    if (isPlanEditing && editingPlan !== planName) {
      return;
    }
    
    console.log('🔧 handlePlanEdit called for plan:', planName);
    console.log('🔧 Current editingPlan state:', editingPlan);
    
    // Save the current config before editing
    setInitialConfig({ ...config });
    
    setEditingPlan(planName);
    setIsPlanEditing(true);
    setIsSubTabEditing?.(true);
    console.log('🔧 After setEditingPlan, editingPlan should be:', planName);
  };

  const handlePlanCancel = () => {
    // Restore the initial config if it exists
    if (initialConfig) {
      console.log('🔧 Restoring initial config:', initialConfig);
      setConfig(initialConfig);
      setInitialConfig(null);
    }
    
    setEditingPlan(null);
    setIsPlanEditing(false);
    setIsSubTabEditing?.(false);
    setHasChanges(false);
  };

  const handlePlanSave = async () => {
    // Validate that each selected plan has at least one tool
    const selectedPlans = Object.entries(config.selectedPlans)
      .filter(([_, isSelected]) => isSelected)
      .map(([plan, _]) => plan.replace('selected', '').toLowerCase());

    const validationErrors = [];
    
    for (const planName of selectedPlans) {
      const planKey = `selected${planName.charAt(0).toUpperCase() + planName.slice(1)}` as keyof typeof config.planTools;
      const planTools = config.planTools[planKey] || [];
      
      if (planTools.length === 0) {
        validationErrors.push(`${planName.charAt(0).toUpperCase() + planName.slice(1)} plan must have at least one tool selected`);
      }
    }

    if (validationErrors.length > 0) {
      alert(`Cannot save: ${validationErrors.join(', ')}`);
      return;
    }

    setEditingPlan(null);
    setIsPlanEditing(false);
    setIsSubTabEditing?.(false);
    setInitialConfig(null); // Clear the initial config after successful save
    
    // Call the enhanced save function which will refresh data
    await handleSaveWithPlanChanges();
  };

  const handlePlanSelection = (plan: keyof typeof config.selectedPlans, selected: boolean) => {
    // Prevent deselecting all plans
    if (!selected && Object.values(config.selectedPlans).filter(v => v).length === 1) {
      return;
    }

    const planToolsKey = plan as keyof typeof config.planTools;
    const currentPlanTools = config.planTools[planToolsKey];

    if (selected && mcpTools.length > 0) {
      // When selecting a plan, include all available tools
      const allToolNames = mcpTools.map(tool => tool.name);
      setConfig({
        ...config,
        selectedPlans: {
          ...config.selectedPlans,
          [plan]: selected
        },
        planTools: {
          ...config.planTools,
          [planToolsKey]: allToolNames
        }
      });
    } else {
      // When deselecting a plan, clear its tools
      setConfig({
        ...config,
        selectedPlans: {
          ...config.selectedPlans,
          [plan]: selected
        },
        planTools: {
          ...config.planTools,
          [planToolsKey]: []
        }
      });
    }
    setHasChanges(true);
    
    // Call the enhanced plan selection change handler
    handlePlanSelectionChange(plan, selected);
  };

  const handleToolPlanChange = (plan: keyof typeof config.selectedPlans, toolName: string, isIncluded: boolean) => {
    const planToolsKey = plan as keyof typeof config.planTools;
    const currentPlanTools = config.planTools[planToolsKey] || [];
    
    let newPlanTools;
    if (isIncluded) {
      // Add tool to plan
      newPlanTools = [...currentPlanTools, toolName];
    } else {
      // Remove tool from plan
      newPlanTools = currentPlanTools.filter(name => name !== toolName);
    }

    setConfig({
      ...config,
      planTools: {
        ...config.planTools,
        [planToolsKey]: newPlanTools
      }
    });
    setHasChanges(true);
  };

  const handlePlanPricingChange = (plan: keyof typeof config.selectedPlans, field: 'price' | 'quota' | 'rps' | 'overusageCost', value: number) => {
    const planName = plan.replace('selected', '');
    console.log('🔧 handlePlanPricingChange:', { plan, planName, field, value });
    const key = `${field}${planName.charAt(0).toUpperCase() + planName.slice(1)}` as keyof MonetizationConfig;
    console.log('🔧 Setting key:', key, 'to value:', value);
    setConfig({
      ...config,
      [key]: value
    });
    setHasChanges(true);
  };

  const handleHardLimitChange = (plan: keyof typeof config.selectedPlans, value: boolean) => {
    const planName = plan.replace('selected', '');
    console.log('🔧 handleHardLimitChange:', { plan, planName, value });
    const key = `hardLimit${planName.charAt(0).toUpperCase() + planName.slice(1)}` as keyof MonetizationConfig;
    console.log('🔧 Setting key:', key, 'to value:', value);
    setConfig({
      ...config,
      [key]: value
    });
    setHasChanges(true);
  };

  const handleRpsPeriodChange = (plan: keyof typeof config.selectedPlans, value: 'second' | 'minute' | 'hour' | 'day') => {
    const planName = plan.replace('selected', '');
    console.log('🔧 handleRpsPeriodChange:', { plan, planName, value });
    const key = `rpsPeriod${planName.charAt(0).toUpperCase() + planName.slice(1)}` as keyof MonetizationConfig;
    console.log('🔧 Setting key:', key, 'to value:', value);
    setConfig({
      ...config,
      [key]: value
    });
    setHasChanges(true);
  };

  // Check if all selected plans have at least one tool
  const isPlanValidationValid = () => {
    const selectedPlans = Object.entries(config.selectedPlans)
      .filter(([_, isSelected]) => isSelected)
      .map(([plan, _]) => plan.replace('selected', '').toLowerCase());

    for (const planName of selectedPlans) {
      const planKey = `selected${planName.charAt(0).toUpperCase() + planName.slice(1)}` as keyof typeof config.planTools;
      const planTools = config.planTools[planKey] || [];
      
      if (planTools.length === 0) {
        return false;
      }
    }
    
    return true;
  };

  // New methods for tool-specific PAID configuration updates
  const handleToolPaidConfigChange = (toolName: string, field: string, value: any) => {
    setConfig({
      ...config,
      toolPaidConfig: {
        ...config.toolPaidConfig,
        [toolName]: {
          ...config.toolPaidConfig?.[toolName],
          [field]: value
        }
      }
    });
    setHasChanges(true);
  };

  const handleToolSave = async () => {
    if (editingTool && onUpdateToolPaidConfig) {
      try {
        const tool = mcpTools.find(t => t.name === editingTool);
        if (!tool) {
          console.error('Tool not found:', editingTool);
          return;
        }
        if (!tool.id) {
          console.warn('Tool missing ID, falling back to global save:', editingTool);
          setEditingTool(null);
          setIsToolEditing(false);
          setIsSubTabEditing?.(false);
          onSave();
          return;
        }

        const toolConfig = config.toolPaidConfig?.[editingTool] || {};
        await onUpdateToolPaidConfig(tool.id, toolConfig);
        setEditingTool(null);
        setIsEditing(false);
        setHasChanges(false);
      } catch (error) {
        console.error('Error saving tool PAID configuration:', error);
      }
    } else {
      setEditingTool(null);
      setIsEditing(false);
      onSave();
    }
  };

  // Handle plan selection/deselection changes
  const handlePlanSelectionChange = (planName: string, isSelected: boolean) => {
    console.log(`🔄 Plan ${planName} ${isSelected ? 'selected' : 'deselected'}`);
    
    const newConfig = {
      ...config,
      selectedPlans: {
        ...config.selectedPlans,
        [planName]: isSelected
      }
    };

    // If deselecting a plan, clear its tools
    if (!isSelected) {
      const planKey = planName.replace('selected', '').toLowerCase();
      const planToolsKey = `selected${planKey.charAt(0).toUpperCase() + planKey.slice(1)}`;
      newConfig.planTools = {
        ...config.planTools,
        [planToolsKey]: []
      };
    }

    setConfig(newConfig);
    setHasChanges(true);
  };

  // Enhanced save function that handles plan changes
  const handleSaveWithPlanChanges = async () => {
    try {
      // Check if there are plan changes that need to be saved
      const hasPlanChanges = hasChanges && onUpdatePaidPlans;
      
      if (hasPlanChanges) {
        console.log('🔄 Saving PAID plans changes:', {
          selectedPlans: config.selectedPlans,
          planTools: config.planTools
        });
        
        await onUpdatePaidPlans(config.selectedPlans, config.planTools);
        console.log('✅ PAID plans saved successfully');
        
        // Refresh data from database after successful save
        if (refreshData) {
          console.log('🔄 Refreshing data after PAID plans save');
          await refreshData();
        }
      } else {
        console.log('ℹ️ No PAID plans changes to save');
      }
      
      // Call the original save function
      onSave();
    } catch (error) {
      console.error('❌ Error saving PAID plans:', error);
    }
  };


  // Show loading state while data is being fetched
  if (isLoading) {
    return (
      <div className="space-y-6">
        <Card className="bg-theme-secondary">
          <CardContent className="flex items-center justify-center py-12">
            <LoadingSpinner size="lg" text={t('loading')} />
          </CardContent>
        </Card>
      </div>
    );
  }

  // Show error state if data fetching failed
  if (error) {
    return (
      <div className="space-y-6">
        <Card className="bg-theme-secondary">
          <CardContent className="flex items-center justify-center py-12">
            <div className="text-center">
              <p className="text-red-500 font-medium">{t('error')}</p>
              <p className="text-theme-secondary text-sm mt-1">{error}</p>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Subscription Plans Configuration */}
      {hasChanges && (
        <div className="flex items-center space-x-2 text-yellow-600">
          <div className="w-2 h-2 bg-yellow-500 rounded-full animate-pulse"></div>
          <span className="text-sm font-medium">{t('unsavedChanges')}</span>
        </div>
      )}
      <div className="space-y-6">
          {['selectedBasic', 'selectedPro', 'selectedUltra', 'selectedMega'].map((plan) => {
            const isSelected = config.selectedPlans[plan as keyof typeof config.selectedPlans] || false;
            const planKey = plan as keyof typeof config.selectedPlans;
            const planName = plan.replace('selected', '');
            const hardLimitKey = `hardLimit${planName}` as keyof MonetizationConfig;
            const hardLimit = config[hardLimitKey] as boolean;
            
            console.log(`🔍 Plan ${planName} - editingPlan: ${editingPlan}, isEditing: ${isPlanEditing}`);
            
            // Get plan data from API - find any tool that has this plan
            const planData = processedMonetizationState?.tools?.find((t: any) => 
              t.monetization?.some((m: any) => 
                m.monetizationType === 'PAID' && 
                m.planName?.toLowerCase() === planName.toLowerCase()
              )
            )?.monetization?.find((m: any) => 
              m.monetizationType === 'PAID' && 
              m.planName?.toLowerCase() === planName.toLowerCase()
            );

            // Debug logging
            console.log(`🔍 Plan ${planName}:`, {
              planData,
              configPrice: config[`price${planName}` as keyof MonetizationConfig],
              configQuota: config[`quota${planName}` as keyof MonetizationConfig],
              configHardLimit: config[`hardLimit${planName}` as keyof MonetizationConfig],
              configRateLimit: config[`rateLimit${planName}` as keyof MonetizationConfig],
              isSelected
            });

            return (
              <Card key={plan} className={`border-none transition-all duration-200 ${
                isSelected ? 'border-theme-primary bg-theme-primary/5' : 'bg-theme-secondary/50'
              } ${hasChanges ? 'ring-2 ring-yellow-400/50' : ''}`}>
                <CardHeader>
                <div className="flex items-center justify-between">
                  <div className="flex items-center space-x-3">
                    {isEditing ? (
                      <Checkbox
                        checked={isSelected}
                        onCheckedChange={(checked) => handlePlanSelection(planKey, checked as boolean)}
                        disabled={isPlanEditing && editingPlan !== planName}
                      />
                    ) : (
                      <Checkbox
                        checked={isSelected}
                        disabled
                      />
                    )}
                    <div>
                      <h4 className="text-lg font-semibold text-theme-primary">{t('plan', { name: planName })}</h4>
                    </div>
                  </div>
                  <div className="flex items-center space-x-2">
                    {isPlanEditing && editingPlan === planName ? (
                      <div className="flex items-center space-x-3">
                        <Button
                          onClick={handlePlanCancel}
                          variant="secondary"
                        >
                          {t('actions.cancel')}
                        </Button>
                        <Button
                          onClick={handlePlanSave}
                          disabled={isSaving || !hasChanges || !isPlanValidationValid()}
                          variant="default"
                          title={!isPlanValidationValid() ? t('tooltips.needsOneTool') : ""}
                        >
                          <Save className="w-4 h-4"/>
                          <span>{isSaving ? t('actions.saving') : t('actions.save')}</span>
                        </Button>
                      </div>
                    ) : (
                      <Button
                        onClick={() => handlePlanEdit(planName)}
                        disabled={isEditing || (isPlanEditing && editingPlan !== planName)}
                        variant="ghost"
                        size="icon"
                        title={
                          isEditing
                            ? t('tooltips.finishPricingFirst')
                            : (isPlanEditing && editingPlan !== planName)
                            ? t('tooltips.finishOtherPlanFirst')
                            : t('tooltips.editPlan', { plan: planName })
                        }
                      >
                        <SquarePen className="w-4 h-4"/>
                      </Button>
                    )}
                  </div>
                </div>
                </CardHeader>

                <CardContent>
                {/* Validation error message */}
                {isSelected && (() => {
                  const planToolsKey = planKey as keyof typeof config.planTools;
                  const planTools = config.planTools[planToolsKey] || [];
                  const hasNoTools = planTools.length === 0;
                  return hasNoTools ? (
                    <div className="mt-3 p-3 bg-orange-100 border border-orange-300 rounded-lg">
                      <p className="text-sm text-orange-700">⚠️ {t('validationError')}</p>
                    </div>
                  ) : null;
                })()}

                {isSelected && (
                  <>
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.monthlyPrice')}
                        </label>
                        {isPlanEditing && editingPlan === planName ? (
                          <input
                            type="number"
                            value={config[`price${planName}` as keyof MonetizationConfig] as number || planData?.price || 0}
                            onChange={(e) => handlePlanPricingChange(planKey, 'price', parseFloat(e.target.value) || 0)}
                            className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                            min={0}
                            step={0.01}
                          />
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg"
                          >
                            ${config[`price${planName}` as keyof MonetizationConfig] as number || planData?.price || 0}
                          </Button>
                        )}
                      </div>

                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.monthlyQuota')}
                        </label>
                        {isPlanEditing && editingPlan === planName ? (
                          <input
                            type="number"
                            value={config[`quota${planName}` as keyof MonetizationConfig] as number || planData?.quota || 1000}
                            onChange={(e) => handlePlanPricingChange(planKey, 'quota', parseInt(e.target.value) || 1000)}
                            className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                            min={0}
                          />
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg"
                          >
                            {config[`quota${planName}` as keyof MonetizationConfig] as number || planData?.quota || 1000} requests
                          </Button>
                        )}
                      </div>

                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.hardLimit')}
                        </label>
                        {isPlanEditing && editingPlan === planName ? (
                          <select
                            value={(hardLimit ?? planData?.hardLimit) ? 'yes' : 'no'}
                            onChange={(e) => handleHardLimitChange(planKey, e.target.value === 'yes')}
                            className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                          >
                            <option value="no">{t('hardLimitOptions.no')}</option>
                            <option value="yes">{t('hardLimitOptions.yes')}</option>
                          </select>
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg"
                          >
                            {(hardLimit ?? planData?.hardLimit) ? t('hardLimitOptions.yes') : t('hardLimitOptions.no')}
                          </Button>
                        )}
                      </div>

                      {/* Overusage Cost - only visible when Hard Limit is No */}
                      {(() => {
                        const currentHardLimit = isPlanEditing
                          ? hardLimit
                          : (hardLimit ?? planData?.hardLimit);
                        return !currentHardLimit;
                      })() && (
                        <div>
                          <label className="block text-sm font-medium text-theme-primary mb-2">
                            {t('fields.overusageCost')}
                          </label>
                          {isPlanEditing && editingPlan === planName ? (
                            <input
                              type="number"
                              step="0.01"
                              value={config[`overusageCost${planName}` as keyof MonetizationConfig] as number || planData?.overusageCost || 0}
                              onChange={(e) => handlePlanPricingChange(planKey, 'overusageCost', parseFloat(e.target.value) || 0)}
                              className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                              min={0}
                            />
                          ) : (
                            <Button
                              variant="readonly"
                              disabled
                              className="w-full justify-start rounded-lg"
                            >
                              ${config[`overusageCost${planName}` as keyof MonetizationConfig] as number || planData?.overusageCost || 0}
                            </Button>
                          )}
                        </div>
                      )}

                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.rateLimitRequests')}
                        </label>
                        {isPlanEditing && editingPlan === planName ? (
                          <input
                            type="number"
                            value={config[`rps${planName}` as keyof MonetizationConfig] as number || planData?.rateLimitRequests || 1000}
                            onChange={(e) => handlePlanPricingChange(planKey, 'rps', parseInt(e.target.value) || 1000)}
                            className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                            min={0}
                          />
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg"
                          >
                            {config[`rps${planName}` as keyof MonetizationConfig] as number || planData?.rateLimitRequests || 1000} requests
                          </Button>
                        )}
                      </div>

                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">
                          {t('fields.rateLimitPeriod')}
                        </label>
                        {isPlanEditing && editingPlan === planName ? (
                          <select
                            value={config[`rpsPeriod${planName}` as keyof MonetizationConfig] as string || planData?.rateLimitPeriod || 'hour'}
                            onChange={(e) => handleRpsPeriodChange(planKey, e.target.value as 'second' | 'minute' | 'hour' | 'day')}
                            className="w-full h-9 px-3 text-sm bg-theme-tertiary border border-theme/20 rounded-lg text-theme-primary focus:outline-none focus:ring-2 focus:ring-theme-primary/50"
                          >
                            <option value="second">{t('periods.second')}</option>
                            <option value="minute">{t('periods.minute')}</option>
                            <option value="hour">{t('periods.hour')}</option>
                            <option value="day">{t('periods.day')}</option>
                          </select>
                        ) : (
                          <Button
                            variant="readonly"
                            disabled
                            className="w-full justify-start rounded-lg capitalize"
                          >
                            {config[`rpsPeriod${planName}` as keyof MonetizationConfig] as string || planData?.rateLimitPeriod || 'hour'}
                          </Button>
                        )}
                      </div>
                    </div>

                    {/* Included Tools for this plan */}
                    <Card className="bg-theme-secondary mt-6">
                      <CardHeader>
                        <CardTitle className="text-base">{t('includedTools')}</CardTitle>
                      </CardHeader>
                      <CardContent>
                        {mcpTools.length > 0 ? (
                          <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
                            {mcpTools.map((tool, toolIndex) => {
                              const isToolInPlan = config.planTools[planKey]?.includes(tool.name) || false;
                              
                              // Debug logging for tools
                              console.log(`🔧 Tool ${tool.name} in plan ${planName}:`, {
                                isToolInPlan,
                                planTools: config.planTools[planKey],
                                toolMonetization: processedMonetizationState?.tools?.find((t: any) => t.name === tool.name)
                              });
                              
                              return (
                                <div key={toolIndex} className="bg-theme-primary/5 rounded-lg p-3">
                                  <div className="flex items-center space-x-3">
                                    {isPlanEditing && editingPlan === planName ? (
                                      <Checkbox
                                        checked={isToolInPlan}
                                        onCheckedChange={(checked) => handleToolPlanChange(planKey, tool.name, checked as boolean)}
                                      />
                                    ) : (
                                      <Checkbox
                                        checked={isToolInPlan}
                                        disabled
                                      />
                                    )}
                                    <div className="flex-1">
                                      <h5 className="font-medium text-theme-primary">{tool.name}</h5>
                                      <p className="text-xs text-theme-secondary truncate">{tool.endpoint}</p>
                                    </div>
                                  </div>
                                  
                                </div>
                              );
                            })}
                          </div>
                        ) : (
                          <div className="text-center py-6">
                            <Code className="w-8 h-8 text-theme-secondary mx-auto mb-2" />
                            <p className="text-sm text-theme-secondary">{t('noToolsAvailable')}</p>
                          </div>
                        )}
                      </CardContent>
                    </Card>
                  </>
                )}
                </CardContent>
              </Card>
            );
          })}
      </div>

    </div>
  );
};

export default APIMarketplaceTab;