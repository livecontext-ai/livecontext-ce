import React, { useState } from 'react';
import { Info, DollarSign, CreditCard, Gift, Zap, MessageSquare } from 'lucide-react';
import { Step4Props, MonetizationConfig } from '../types';
import { useTheme } from '@/components/ThemeProvider';
import { useTranslations } from 'next-intl';
import {
  FormSection,
  FormField,
  FormInput,
  FormSelect,
  FormGrid,
  ActionButton,
  InfoBox,
  PricingCard
} from './common';
import PricingSections from './common/PricingSections';

const Step4: React.FC<Step4Props> = ({
  monetizationConfig,
  setMonetizationConfig,
  mcpTools,
  apiName,
  onValidationChange
}) => {
  const t = useTranslations('developers.step4');
  const { theme } = useTheme();
  const isDarkmode = theme === 'dark';

  const [showPricingInfo, setShowPricingInfo] = useState(false);
  const [showRateLimitInfo, setShowRateLimitInfo] = useState(false);
  const [showPlansInfo, setShowPlansInfo] = useState(false);
  const [showFreeRequestsInfo, setShowFreeRequestsInfo] = useState(false);

  // States to manage section expansion
  const [sectionsExpanded, setSectionsExpanded] = useState({
    pricing: false,
    rateLimit: false,
    plans: false,
    tools: false,
    freeRequests: false
  });

  const toggleSection = (section: keyof typeof sectionsExpanded) => {
    setSectionsExpanded(prev => ({
      ...prev,
      [section]: !prev[section]
    }));
  };

  const handlePricingChange = (pricing: 'freemium' | 'paid') => {
    const currentPricingModels = monetizationConfig.selectedPricingModels || [monetizationConfig.pricing];

    // Allow selecting up to 2 pricing models (Freemium + Paid)
    if (currentPricingModels.includes(pricing)) {
      // Deselecting
      if (currentPricingModels.length > 1) {
        const newModels = currentPricingModels.filter(p => p !== pricing);
        setMonetizationConfig({
          ...monetizationConfig,
          selectedPricingModels: newModels,
          pricing: newModels[0] // Set main pricing to first remaining
        });
      }
      // Don't allow deselecting the last pricing model
    } else {
      // Selecting
      if (currentPricingModels.length < 2) {
        const newModels = [...currentPricingModels, pricing];
        setMonetizationConfig({
          ...monetizationConfig,
          selectedPricingModels: newModels,
          pricing: newModels[0] // Keep first as main pricing
        });
      }
      // Don't allow selecting more than 2 pricing models
    }
  };

  // Check if multiple pricing models are selected
  const hasMultiplePricingModels = () => {
    const models = monetizationConfig.selectedPricingModels || [monetizationConfig.pricing];
    return models.length > 1;
  };

  // Get selected pricing model names for display
  const getSelectedPricingModelNames = () => {
    const models = monetizationConfig.selectedPricingModels || [monetizationConfig.pricing];
    return models.map(model => model.charAt(0).toUpperCase() + model.slice(1));
  };

  const handleRateLimitChange = (field: 'requests' | 'period', value: number | string) => {
    setMonetizationConfig({
      ...monetizationConfig,
      rateLimit: {
        ...monetizationConfig.rateLimit,
        [field]: value
      }
    });
  };

  const handlePlanSelection = (plan: keyof typeof monetizationConfig.selectedPlans, selected: boolean) => {
    console.log('handlePlanSelection called:', { plan, selected, mcpToolsLength: mcpTools.length });

    // Prevent deselecting all plans
    if (!selected && Object.values(monetizationConfig.selectedPlans).filter(v => v).length === 1) {
      return;
    }

    // If selecting a plan and it has no tools yet, initialize with first tool
    const planToolsKey = plan as keyof typeof monetizationConfig.planTools;
    const currentPlanTools = monetizationConfig.planTools[planToolsKey];
    console.log('Current plan tools:', { planToolsKey, currentPlanTools });

    if (selected && currentPlanTools.length === 0 && mcpTools.length > 0) {
      // Initialize with first tool selected
      console.log('Initializing plan with first tool:', mcpTools[0].name);
      setMonetizationConfig({
        ...monetizationConfig,
        selectedPlans: {
          ...monetizationConfig.selectedPlans,
          [plan]: selected
        },
        planTools: {
          ...monetizationConfig.planTools,
          [planToolsKey]: [mcpTools[0].name]
        }
      });
    } else {
      console.log('Plan selection without tool initialization');
      setMonetizationConfig({
        ...monetizationConfig,
        selectedPlans: {
          ...monetizationConfig.selectedPlans,
          [plan]: selected
        }
      });
    }
  };

  const handleHardLimitChange = (plan: keyof typeof monetizationConfig.selectedPlans, value: boolean) => {
    setMonetizationConfig({
      ...monetizationConfig,
      [`hardLimit${plan.charAt(0).toUpperCase() + plan.slice(1)}` as keyof MonetizationConfig]: value
    });
  };

  const handlePlanPricingChange = (plan: keyof typeof monetizationConfig.selectedPlans, field: 'price' | 'quota' | 'rps', value: number) => {
    setMonetizationConfig({
      ...monetizationConfig,
      [`${field}${plan.charAt(0).toUpperCase() + plan.slice(1)}` as keyof MonetizationConfig]: value
    });
  };

  const handleRpsPeriodChange = (plan: keyof typeof monetizationConfig.selectedPlans, period: 'second' | 'minute' | 'hour' | 'day') => {
    setMonetizationConfig({
      ...monetizationConfig,
      [`rpsPeriod${plan.charAt(0).toUpperCase() + plan.slice(1)}` as keyof MonetizationConfig]: period
    });
  };

  const handleOverusageCostChange = (plan: keyof typeof monetizationConfig.selectedPlans, value: number) => {
    setMonetizationConfig({
      ...monetizationConfig,
      [`overusageCost${plan.charAt(0).toUpperCase() + plan.slice(1)}` as keyof MonetizationConfig]: value
    });
  };

  const handleToolPricingChange = (toolName: string, mauValue: number) => {
    setMonetizationConfig({
      ...monetizationConfig,
      toolPricing: {
        ...monetizationConfig.toolPricing,
        [toolName]: {
          mauValue: Math.max(1, Math.floor(mauValue)), // Minimum 1 MAU, no decimal
          price: Math.max(1, Math.floor(mauValue)) * 0.005, // Automatic conversion to dollars
          currency: 'USD',
          calls: (monetizationConfig.toolPricing[toolName] as any)?.calls || 1 // Default to 1 call
        }
      }
    });
  };

  const handlePlanIncludeAllToolsChange = (plan: keyof typeof monetizationConfig.planTools, includeAll: boolean) => {
    console.log('handlePlanIncludeAllToolsChange called:', { plan, includeAll, mcpToolsLength: mcpTools.length });

    if (includeAll) {
      // Include all tools in this specific plan
      const allToolNames = mcpTools.map(tool => tool.name);
      console.log('Including all tools:', allToolNames);
      setMonetizationConfig({
        ...monetizationConfig,
        planTools: {
          ...monetizationConfig.planTools,
          [plan]: allToolNames
        },
        planAllToolsMode: {
          ...monetizationConfig.planAllToolsMode,
          [plan]: true
        }
      });
    } else {
      // Reset tool selection for this plan and check the first tool
      const firstToolName = mcpTools.length > 0 ? mcpTools[0].name : '';
      console.log('Per tool mode - first tool name:', firstToolName);

      // Ensure the plan is selected if it's not already
      const planKey = plan as keyof typeof monetizationConfig.selectedPlans;
      const isPlanSelected = monetizationConfig.selectedPlans[planKey];

      if (!isPlanSelected) {
        console.log('Plan not selected, selecting it first:', plan);
        setMonetizationConfig({
          ...monetizationConfig,
          selectedPlans: {
            ...monetizationConfig.selectedPlans,
            [planKey]: true
          },
          planTools: {
            ...monetizationConfig.planTools,
            [plan]: firstToolName ? [firstToolName] : []
          },
          planAllToolsMode: {
            ...monetizationConfig.planAllToolsMode,
            [plan]: false
          }
        });
      } else {
        setMonetizationConfig({
          ...monetizationConfig,
          planTools: {
            ...monetizationConfig.planTools,
            [plan]: firstToolName ? [firstToolName] : []
          },
          planAllToolsMode: {
            ...monetizationConfig.planAllToolsMode,
            [plan]: false
          }
        });
      }
    }
  };

  const handlePlanToolSelection = (plan: keyof typeof monetizationConfig.planTools, toolName: string, selected: boolean) => {
    const currentPlanTools = monetizationConfig.planTools[plan] || [];
    let newPlanTools;

    if (selected) {
      newPlanTools = [...currentPlanTools, toolName];
    } else {
      // Prevent deselecting the last tool
      if (currentPlanTools.length <= 1) {
        alert(t('alerts.minOneTool'));
        return;
      }
      newPlanTools = currentPlanTools.filter(name => name !== toolName);
    }

    setMonetizationConfig({
      ...monetizationConfig,
      planTools: {
        ...monetizationConfig.planTools,
        [plan]: newPlanTools
      }
    });
  };

  const handleUniformPricingChange = (mauValue: number) => {
    setMonetizationConfig({
      ...monetizationConfig,
      uniformToolPrice: Math.max(1, Math.floor(mauValue)), // In MAU
      uniformToolPriceInDollars: Math.max(1, Math.floor(mauValue)) * 0.005, // Conversion to dollars
      uniformCalls: (monetizationConfig as any).uniformCalls || 1, // Keep existing calls or default to 1
      // Reset individual prices when using uniform pricing
      toolPricing: {}
    });
  };

  const handleUniformCallsChange = (calls: number) => {
    setMonetizationConfig({
      ...monetizationConfig,
      uniformCalls: Math.max(1, calls)
    } as any);
  };

  // Validation function to check if all tools are included in at least one plan
  const validateToolPlanAssignment = () => {
    // Check if any paid plans are selected
    const hasPaidPlans = monetizationConfig.selectedPricingModels?.includes('paid') || monetizationConfig.pricing === 'paid';

    if (!hasPaidPlans) {
      return { isValid: true, unassignedTools: [] };
    }

    const selectedPlans = Object.entries(monetizationConfig.selectedPlans)
      .filter(([_, isSelected]) => isSelected)
      .map(([plan, _]) => plan);

    if (selectedPlans.length === 0) {
      return { isValid: false, unassignedTools: mcpTools.map(tool => tool.name) };
    }

    // Check if at least one plan has tools assigned
    const hasToolsInPlans = selectedPlans.some(plan => {
      const planTools = monetizationConfig.planTools[plan as keyof typeof monetizationConfig.planTools] || [];
      return planTools.length > 0;
    });

    if (!hasToolsInPlans) {
      return { isValid: false, unassignedTools: mcpTools.map(tool => tool.name) };
    }

    const allAssignedTools = new Set<string>();
    selectedPlans.forEach(plan => {
      const planTools = monetizationConfig.planTools[plan as keyof typeof monetizationConfig.planTools] || [];
      planTools.forEach(toolName => allAssignedTools.add(toolName));
    });

    const unassignedTools = mcpTools
      .map(tool => tool.name)
      .filter(toolName => !allAssignedTools.has(toolName));

    return {
      isValid: unassignedTools.length === 0,
      unassignedTools
    };
  };

  // Get validation result
  const validation = validateToolPlanAssignment();

  // Notify parent of validation state changes
  React.useEffect(() => {
    if (onValidationChange) {
      onValidationChange(validation.isValid);
    }
  }, [validation.isValid, onValidationChange]);

  const rateLimitPeriodOptions = [
    { value: 'second', label: t('periods.perSecond') },
    { value: 'minute', label: t('periods.perMinute') },
    { value: 'hour', label: t('periods.perHour') },
    { value: 'day', label: t('periods.perDay') },
    { value: 'month', label: t('periods.perMonth') }
  ];

  const rpsPeriodOptions = [
    { value: 'second', label: t('periods.second') },
    { value: 'minute', label: t('periods.minute') },
    { value: 'hour', label: t('periods.hour') },
    { value: 'day', label: t('periods.day') }
  ];

  return (
    <div className="space-y-4">
      {/* Pricing Model Section */}
      <div className="space-y-4">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center space-x-3">
            <DollarSign className="w-6 h-6 text-green-500 flex-shrink-0" />
            <h3 className="text-xl font-semibold text-theme-primary">{t('pricingModel.title')}</h3>
          </div>
          <button
            type="button"
            onClick={() => setShowPricingInfo(!showPricingInfo)}
            className="text-theme-muted hover:text-theme-primary transition-colors"
          >
            <Info className="w-5 h-5" />
          </button>
        </div>

        {showPricingInfo && (
          <InfoBox
            type="info"
            title={t('pricingModel.infoTitle')}
          >
            <div className="space-y-3">
              <div className="p-3 bg-purple-50 dark:bg-purple-900/20 border border-purple-200 dark:border-purple-700 rounded-lg">
                <p className="font-medium text-purple-800 dark:text-purple-200"><strong>{t('pricingModel.aiIntegration.title')}</strong></p>
                <p className="text-purple-700 dark:text-purple-300">{t('pricingModel.aiIntegration.description')}</p>
              </div>
              <div className="p-3 bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-700 rounded-lg">
                <p className="font-medium text-orange-800 dark:text-orange-200"><strong>{t('pricingModel.apiMarketplace.title')}</strong></p>
                <p className="text-orange-700 dark:text-orange-300">{t('pricingModel.apiMarketplace.description')}</p>
              </div>
            </div>
          </InfoBox>
        )}

        {/* Two distinct business models: MCP Chat vs Traditional API */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mt-4">
          <PricingCard
            model="freemium"
            title={t('pricingModel.aiIntegration.cardTitle')}
            description={t('pricingModel.aiIntegration.cardDescription')}
            features={[
              t('pricingModel.aiIntegration.features.zeroFriction'),
              t('pricingModel.aiIntegration.features.aiDiscovery'),
              t('pricingModel.aiIntegration.features.creditTracking'),
              t('pricingModel.aiIntegration.features.conversational'),
              t('pricingModel.aiIntegration.features.autoRateLimit'),
              t('pricingModel.aiIntegration.features.premiumUnlock')
            ]}
            isSelected={monetizationConfig.selectedPricingModels?.includes('freemium') || monetizationConfig.pricing === 'freemium'}
            onSelect={() => handlePricingChange('freemium')}
            recommended={true}
          />
          <PricingCard
            model="paid"
            title={t('pricingModel.apiMarketplace.cardTitle')}
            description={t('pricingModel.apiMarketplace.cardDescription')}
            features={[
              t('pricingModel.apiMarketplace.features.clientIntegration'),
              t('pricingModel.apiMarketplace.features.manualApi'),
              t('pricingModel.apiMarketplace.features.customAuth'),
              t('pricingModel.apiMarketplace.features.clientRateLimits'),
              t('pricingModel.apiMarketplace.features.sdks'),
              t('pricingModel.apiMarketplace.features.enterprise')
            ]}
            isSelected={monetizationConfig.selectedPricingModels?.includes('paid') || monetizationConfig.pricing === 'paid'}
            onSelect={() => handlePricingChange('paid')}
            featuresWithIcons={[
              { text: t('pricingModel.apiMarketplace.features.clientIntegration'), icon: "check", color: "text-green-500" },
              { text: t('pricingModel.apiMarketplace.features.manualApi'), icon: "check", color: "text-green-500" },
              { text: t('pricingModel.apiMarketplace.features.customAuth'), icon: "check", color: "text-green-500" },
              { text: t('pricingModel.apiMarketplace.features.clientRateLimits'), icon: "check", color: "text-green-500" },
              { text: t('pricingModel.apiMarketplace.features.sdks'), icon: "check", color: "text-green-500" },
              { text: t('pricingModel.apiMarketplace.features.enterprise'), icon: "check", color: "text-green-500" }
            ]}
          />
        </div>

        {/* Multi-Pricing Selection Info */}
        {hasMultiplePricingModels() && (
          <div className="mt-6 p-4 border border-blue-200 dark:border-blue-700 rounded-lg bg-blue-50 dark:bg-blue-900/20">
            <div className="flex items-start space-x-3">
              <Info className="w-5 h-5 text-blue-600 dark:text-blue-400 mt-0.5 flex-shrink-0" />
              <div className="flex-1">
                <div className="text-sm text-blue-700 dark:text-blue-300 space-y-1">
                  <p><strong>{t('pricingModel.hybrid.title')}</strong> {t('pricingModel.hybrid.description')}</p>
                  <p>• <strong>{t('pricingModel.aiIntegration.cardTitle')}:</strong> {t('pricingModel.hybrid.aiUsers')}</p>
                  <p>• <strong>{t('pricingModel.apiMarketplace.cardTitle')}:</strong> {t('pricingModel.hybrid.apiDevs')}</p>
                </div>
              </div>
            </div>
          </div>
        )}


      </div>

      {/* Pricing Sections Component */}
      <PricingSections
        monetizationConfig={monetizationConfig}
        setMonetizationConfig={setMonetizationConfig}
        mcpTools={mcpTools}
        showRateLimitInfo={showRateLimitInfo}
        setShowRateLimitInfo={setShowRateLimitInfo}
        showPlansInfo={showPlansInfo}
        setShowPlansInfo={setShowPlansInfo}
        showFreeRequestsInfo={showFreeRequestsInfo}
        setShowFreeRequestsInfo={setShowFreeRequestsInfo}
        sectionsExpanded={sectionsExpanded}
        toggleSection={toggleSection}
        rateLimitPeriodOptions={rateLimitPeriodOptions}
        rpsPeriodOptions={rpsPeriodOptions}
        handleRateLimitChange={handleRateLimitChange}
        handlePlanSelection={handlePlanSelection}
        handleHardLimitChange={handleHardLimitChange}
        handlePlanPricingChange={handlePlanPricingChange}
        handleRpsPeriodChange={handleRpsPeriodChange}
        handleOverusageCostChange={handleOverusageCostChange}
        handleToolPricingChange={handleToolPricingChange}
        handlePlanIncludeAllToolsChange={handlePlanIncludeAllToolsChange}
        handlePlanToolSelection={handlePlanToolSelection}
        handleUniformPricingChange={handleUniformPricingChange}
        handleUniformCallsChange={handleUniformCallsChange}
        validation={validation}
      />




    </div>
  );
};

export default Step4;
