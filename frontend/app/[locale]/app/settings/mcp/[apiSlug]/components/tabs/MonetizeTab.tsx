import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { useTranslations } from 'next-intl';
import {
  DollarSign,
  CreditCard,
  Users,
  Settings,
  CheckCircle,
  AlertCircle,
  Info,
  Save,
  Edit,
  Plus,
  Trash2,
  MessageSquare,
  Code,
  Zap,
  Gift,
  SquarePen
} from 'lucide-react';

// Import types from developers
import { MonetizationConfig } from '@/app/[locale]/app/settings/developers/types';
import PricingSections from '@/app/[locale]/app/settings/developers/components/common/PricingSections';
import AIIntegrationTab from './AIIntegrationTab';
import APIMarketplaceTab from './APIMarketplaceTab';
import { useUserDataSync } from '@/hooks/useUserDataSync';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';

interface MonetizeTabProps {
  apiData: any;
  monetizationConfig?: MonetizationConfig;
  onSaveConfig?: (config: MonetizationConfig) => void;
  onUpdateToolFreemiumConfig?: (apiToolId: string, toolConfig: {
    freeRequests?: number;
    freeRequestsType?: 'per-user' | 'global';
    rateLimitRequests?: number;
    rateLimitPeriod?: 'second' | 'minute' | 'hour' | 'day' | 'month';
    mauValue?: number;
    calls?: number;
  }) => Promise<any>;
  onBatchUpdateToolsFreemiumConfig?: (toolsConfig: Record<string, {
    freeRequests?: number;
    freeRequestsType?: 'per-user' | 'global';
    rateLimitRequests?: number;
    rateLimitPeriod?: 'second' | 'minute' | 'hour' | 'day' | 'month';
    mauValue?: number;
    calls?: number;
  }>) => Promise<any>;
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
}

interface UserMonetizationState {
  tools: Array<{
    id: string;
    name: string;
    monetization: Array<{
      monetizationType: string;
      planName: string;
      rateLimitRequests: number;
      rateLimitPeriod: string;
      freeRequests: number;
      freeRequestsType: string;
      mauValue: number;
      pricePerMau: number;
      calls: number;
      quota: number;
      price: number;
      overusageCost: number;
      hardLimit: boolean;
    }>;
  }>;
  totalTools?: number;
  userId?: string;
}

const MonetizeTab: React.FC<MonetizeTabProps> = ({
  apiData,
  monetizationConfig,
  onSaveConfig,
  onUpdateToolFreemiumConfig,
  onBatchUpdateToolsFreemiumConfig,
  onUpdateToolPaidConfig,
  onBatchUpdateToolsPaidConfig,
  onUpdatePaidPlans
}) => {
  const t = useTranslations('mcp.monetize');
  const [isEditing, setIsEditing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  const [activeTab, setActiveTab] = useState<'ai-integration' | 'api-marketplace'>('ai-integration');
  const [hasInitialized, setHasInitialized] = useState(false);
  const [initialConfig, setInitialConfig] = useState<MonetizationConfig | null>(null);
  const [isSubTabEditing, setIsSubTabEditing] = useState(false);
  const [showDeselectModal, setShowDeselectModal] = useState(false);
  const [pricingToDeselect, setPricingToDeselect] = useState<'freemium' | 'paid' | null>(null);
  const [sectionsExpanded, setSectionsExpanded] = useState({
    pricing: false,
    rateLimit: false,
    plans: false,
    tools: false,
  });

  // Initialize config with default values if not provided
  const [config, setConfig] = useState<MonetizationConfig>(() => {
    const defaultConfig: MonetizationConfig = {
      pricing: 'freemium',
      selectedPricingModels: ['freemium'],
      rateLimit: {
        requests: 1000,
        period: 'hour'
      },
      selectedPlans: {
        basic: false,
        pro: false,
        ultra: false,
        mega: false
      },
      priceBasic: 0,
      pricePro: 9.99,
      priceUltra: 29.99,
      priceMega: 99.99,
      quotaBasic: 1000,
      quotaPro: 10000,
      quotaUltra: 50000,
      quotaMega: 200000,
      rpsBasic: 10,
      rpsPro: 100,
      rpsUltra: 500,
      rpsMega: 1000,
      rpsPeriodBasic: 'minute',
      rpsPeriodPro: 'minute',
      rpsPeriodUltra: 'minute',
      rpsPeriodMega: 'minute',
      overusageCostBasic: 0.01,
      overusageCostPro: 0.008,
      overusageCostUltra: 0.005,
      overusageCostMega: 0.003,
      showPlansInfo: false,
      uniformToolPrice: 1,
      uniformToolPriceInDollars: 0.005,
      uniformCalls: 1,
      freeRequestsPerUser: 1000,
      freeRequestsType: 'per-user',
      toolFreeRequests: {},
      toolRateLimits: {},
      toolPricing: {},
      planTools: {
        basic: [],
        pro: [],
        ultra: [],
        mega: []
      },
      planAllToolsMode: {
        basic: true,
        pro: true,
        ultra: true,
        mega: true
      },
      monetization: {
        freeRequests: 1000,
        tokenValue: 1
      },
      hardLimitBasic: false,
      hardLimitPro: false,
      hardLimitUltra: false,
      hardLimitMega: false
    };

    // Merge with provided config, ensuring all required fields exist
    if (monetizationConfig) {
      return {
        ...defaultConfig,
        ...monetizationConfig,
        rateLimit: {
          ...defaultConfig.rateLimit,
          ...monetizationConfig.rateLimit
        },
        selectedPlans: {
          ...defaultConfig.selectedPlans,
          ...monetizationConfig.selectedPlans
        },
        planTools: {
          ...defaultConfig.planTools,
          ...monetizationConfig.planTools
        },
        planAllToolsMode: {
          ...defaultConfig.planAllToolsMode,
          ...monetizationConfig.planAllToolsMode
        }
      };
    }

    return defaultConfig;
  });

  // Use the new data sync hook
  const { profile, monetization, isLoading, error, refreshData } = useUserDataSync();

  // Convert monetization data to the expected format using useMemo to prevent unnecessary recalculations
  const userMonetizationState = useMemo(() => {
    if (!monetization) return null;

    return {
      tools: monetization.tools.map(tool => ({
        id: tool.id,
        name: tool.name,
        apiName: tool.apiName,
        apiSlug: tool.apiSlug,
        endpoint: tool.endpoint,
        method: tool.method,
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
      totalTools: monetization.totalTools,
      userId: monetization.userId
    };
  }, [monetization]);

  // Map API data to local configuration
  useEffect(() => {
    // Early return if loading or no data
    if (isLoading || !userMonetizationState?.tools || userMonetizationState.tools.length === 0) {
      console.log('🔍 MonetizeTab useEffect: Early return - loading or no data');
      return;
    }

    console.log('🔍 MonetizeTab useEffect triggered');
    console.log('userMonetizationState:', userMonetizationState);
    console.log('config before mapping:', config);

    // Only proceed if we have monetization data and it's not loading
    if (userMonetizationState?.tools && userMonetizationState.tools.length > 0) {
      const newSelectedPlans = { basic: false, pro: false, ultra: false, mega: false };
      const newPlanTools = { basic: [], pro: [], ultra: [], mega: [] };

      // Detect pricing models from API data
      const hasFreemium = userMonetizationState.tools.some(tool =>
        tool.monetization?.some(m => m.monetizationType === 'FREEMIUM')
      );
      const hasPaid = userMonetizationState.tools.some(tool =>
        tool.monetization?.some(m => m.monetizationType === 'PAID')
      );

      // Get all unique plan names from API data
      const apiPlans = new Set<string>();
      userMonetizationState.tools.forEach(tool => {
        tool.monetization?.forEach(monetization => {
          if (monetization.monetizationType === 'PAID' && monetization.planName) {
            apiPlans.add(monetization.planName.toLowerCase());
          }
        });
      });

      // Plans will be mapped in the tool processing loop below

      // Determine pricing models based on API data, but only if not already initialized
      // This prevents overriding user deselections
      let newPricingModels: ('freemium' | 'paid')[] = [];

      // Only auto-detect pricing models on first load (when hasInitialized is false)
      if (!hasInitialized) {
        if (hasFreemium) newPricingModels.push('freemium');
        if (hasPaid) newPricingModels.push('paid');
        console.log('🔄 First load - auto-detecting pricing models:', newPricingModels);
      } else {
        // On subsequent loads, preserve existing user selections
        newPricingModels = config.selectedPricingModels || [];
        console.log('🔄 Subsequent load - preserving user selections:', newPricingModels);
      }

      // Map tool-specific configurations from API data
      const newToolFreeRequests: Record<string, number> = {};
      const newToolRateLimits: Record<string, { requests: number; period: 'second' | 'minute' | 'hour' | 'day' | 'month' }> = {};
      const newToolPricing: Record<string, { mauValue: number; price: number; currency: string; calls: number }> = {};
      const newConfig: Partial<MonetizationConfig> = {};

      userMonetizationState.tools.forEach((tool: any) => {
        console.log(`🔍 Processing tool: ${tool.name}`, tool);
        const freemiumConfig = tool.monetization?.find((m: any) => m.monetizationType === 'FREEMIUM');
        console.log(`📋 Freemium config for ${tool.name}:`, freemiumConfig);

        if (freemiumConfig) {
          newToolFreeRequests[tool.name] = freemiumConfig.freeRequests || 1000;
          newToolRateLimits[tool.name] = {
            requests: freemiumConfig.rateLimitRequests || 1000,
            period: (freemiumConfig.rateLimitPeriod as 'second' | 'minute' | 'hour' | 'day' | 'month') || 'hour'
          };
          newToolPricing[tool.name] = {
            mauValue: freemiumConfig.mauValue || 1,
            price: freemiumConfig.pricePerMau || 0.005,
            currency: 'USD',
            calls: freemiumConfig.calls || 1
          };
          console.log(`✅ Mapped ${tool.name}:`, {
            freeRequests: newToolFreeRequests[tool.name],
            rateLimits: newToolRateLimits[tool.name],
            pricing: newToolPricing[tool.name]
          });
        } else {
          console.log(`❌ No freemium config found for ${tool.name}`);
        }

        // Map PAID plans
        const paidConfigs = tool.monetization?.filter((m: any) => m.monetizationType === 'PAID') || [];
        console.log(`💰 Paid configs for ${tool.name}:`, paidConfigs);

        paidConfigs.forEach((paidConfig: any) => {
          const planName = paidConfig.planName?.toLowerCase();
          if (planName && ['basic', 'pro', 'ultra', 'mega'].includes(planName)) {
            const capitalizedPlanName = planName.charAt(0).toUpperCase() + planName.slice(1);
            const planKey = `selected${capitalizedPlanName}` as keyof typeof newSelectedPlans;

            // Mark plan as selected
            newSelectedPlans[planKey] = true;

            // Set plan pricing
            const priceKey = `price${capitalizedPlanName}` as keyof MonetizationConfig;
            const quotaKey = `quota${capitalizedPlanName}` as keyof MonetizationConfig;
            const hardLimitKey = `hardLimit${capitalizedPlanName}` as keyof MonetizationConfig;
            const rateLimitKey = `rateLimit${capitalizedPlanName}` as keyof MonetizationConfig;

            (newConfig as any)[priceKey] = paidConfig.price || 0;
            (newConfig as any)[quotaKey] = paidConfig.quota || 1000;
            (newConfig as any)[hardLimitKey] = paidConfig.hardLimit || false;
            (newConfig as any)[rateLimitKey] = paidConfig.rateLimitRequests || 1000;

            // Add tool to plan
            if (!newPlanTools[planKey]) {
              newPlanTools[planKey] = [];
            }
            if (!newPlanTools[planKey].includes(tool.name)) {
              newPlanTools[planKey].push(tool.name);
            }

            console.log(`✅ Mapped ${capitalizedPlanName} plan for ${tool.name}:`, {
              price: paidConfig.price,
              quota: paidConfig.quota,
              hardLimit: paidConfig.hardLimit,
              rateLimit: paidConfig.rateLimitRequests
            });
          }
        });
      });

      console.log('📊 Mapped data:', {
        newToolFreeRequests,
        newToolRateLimits,
        newToolPricing,
        newSelectedPlans,
        newPlanTools,
        newPricingModels
      });

      console.log('🔍 Final selectedPlans:', newSelectedPlans);
      console.log('🔍 Final planTools:', newPlanTools);

      // Always update tool-specific configurations when API data is available
      console.log('🔄 Updating config with mapped data');
      setConfig(prev => {
        // Check if the configuration has actually changed to avoid unnecessary updates
        const hasChanges =
          JSON.stringify(prev.selectedPricingModels) !== JSON.stringify(newPricingModels) ||
          JSON.stringify(prev.selectedPlans) !== JSON.stringify(newSelectedPlans) ||
          JSON.stringify(prev.planTools) !== JSON.stringify(newPlanTools) ||
          JSON.stringify(prev.toolFreeRequests) !== JSON.stringify(newToolFreeRequests) ||
          JSON.stringify(prev.toolRateLimits) !== JSON.stringify(newToolRateLimits) ||
          JSON.stringify(prev.toolPricing) !== JSON.stringify(newToolPricing);

        if (!hasChanges) {
          console.log('🔄 No changes detected, skipping config update');
          return prev;
        }

        // Only update pricing models if this is the first load or if user hasn't made changes
        const shouldUpdatePricingModels = !hasInitialized || newPricingModels.length > 0;

        return {
          ...prev,
          ...newConfig,
          // Only update pricing models on first load to preserve user deselections
          ...(shouldUpdatePricingModels && {
            selectedPricingModels: newPricingModels,
            pricing: newPricingModels[0] || 'freemium'
          }),
          selectedPlans: newSelectedPlans,
          planTools: newPlanTools,
          toolFreeRequests: newToolFreeRequests,
          toolRateLimits: newToolRateLimits,
          toolPricing: newToolPricing
        };
      });

      // Auto-switch to API Marketplace tab if paid plans are detected (only on initial load)
      if (hasPaid && activeTab === 'ai-integration' && !hasInitialized) {
        console.log('🔄 Switching to API Marketplace tab due to paid plans detected');
        setActiveTab('api-marketplace');
      }

      // Mark as initialized after first data load
      if (!hasInitialized) {
        setHasInitialized(true);
      }
    }
  }, [userMonetizationState, activeTab, hasInitialized]);

  const toggleSection = (section: keyof typeof sectionsExpanded) => {
    setSectionsExpanded(prev => ({
      ...prev,
      [section]: !prev[section]
    }));
  };

  const handlePricingChange = (pricing: 'freemium' | 'paid') => {
    const currentPricingModels = config.selectedPricingModels || [config.pricing];

    // Allow selecting up to 2 pricing models (Freemium + Paid)
    if (currentPricingModels.includes(pricing)) {
      // If deselecting, show confirmation modal
      if (currentPricingModels.length > 1) {
        setPricingToDeselect(pricing);
        setShowDeselectModal(true);
        return;
      }
    } else {
      // If adding, ensure we don't exceed 2 models
      if (currentPricingModels.length < 2) {
        const newModels = [...currentPricingModels, pricing];
        setConfig({
          ...config,
          selectedPricingModels: newModels,
          pricing: newModels[0] // Keep first as main pricing
        });
        setHasChanges(true);
      }
    }
  };

  const handleConfirmDeselect = () => {
    if (pricingToDeselect) {
      const currentPricingModels = config.selectedPricingModels || [config.pricing];
      const newModels = currentPricingModels.filter(p => p !== pricingToDeselect);
      setConfig({
        ...config,
        selectedPricingModels: newModels,
        pricing: newModels[0] // Set main pricing to first remaining
      });
      setHasChanges(true);
    }
    setShowDeselectModal(false);
    setPricingToDeselect(null);
  };

  const handleCancelDeselect = () => {
    setShowDeselectModal(false);
    setPricingToDeselect(null);
  };

  const handleSave = useCallback(async () => {
    if (!hasChanges) return;

    setIsSaving(true);
    try {
      if (onSaveConfig) {
        await onSaveConfig(config);
        setHasChanges(false);
        setIsEditing(false);
        setInitialConfig(null); // Clear initial state after successful save

        // Refresh data from database after successful save
        console.log('🔄 Refreshing data after successful save');
        await refreshData();
      }
    } catch (error) {
      console.error('Error saving configuration:', error);
    } finally {
      setIsSaving(false);
    }
  }, [config, hasChanges, onSaveConfig, refreshData]);

  const handleCancel = () => {
    // Restore to the state before editing started
    if (initialConfig) {
      console.log('🔄 Cancel: Restoring to initial state before editing');
      setConfig(initialConfig);
    } else {
      // Fallback to original config if no initial state was saved
      console.log('🔄 Cancel: No initial state found, using original config');
      setConfig(monetizationConfig || config);
    }
    setIsEditing(false);
    setHasChanges(false);
  };

  const rateLimitPeriodOptions = [
    { value: 'second', label: t('periods.perSecond') },
    { value: 'minute', label: t('periods.perMinute') },
    { value: 'hour', label: t('periods.perHour') },
    { value: 'day', label: t('periods.perDay') },
    { value: 'month', label: t('periods.perMonth') }
  ];

  const rpsPeriodOptions = [
    { value: 'second', label: t('periods.perSecond').toLowerCase() },
    { value: 'minute', label: t('periods.perMinute').toLowerCase() },
    { value: 'hour', label: t('periods.perHour').toLowerCase() },
    { value: 'day', label: t('periods.perDay').toLowerCase() }
  ];

  // Convert API tools to MCP tools format for PricingSections
  const mcpTools = apiData?.tools ? apiData.tools.map((tool: any) => ({
    id: tool.id || `tool-${Math.random().toString(36).substr(2, 9)}`, // Ensure ID is present
    name: tool.name,
    toolCategory: tool.category || 'General',
    endpoint: tool.endpoint
  })) : [];

  // Show loading state while data is being fetched
  if (isLoading) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-center py-12">
          <LoadingSpinner size="lg" text={t('loading')} />
        </div>
      </div>
    );
  }

  // Show error state if data fetching failed
  if (error) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-center py-12">
          <AlertCircle className="w-8 h-8 text-red-500" />
          <div className="ml-3 text-center">
            <p className="text-red-500 font-medium">{t('error.title')}</p>
            <p className="text-theme-secondary text-sm mt-1">{error}</p>
            <Button
              onClick={refreshData}
              variant="default"
              className="mt-3"
            >
              {t('error.retry')}
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {apiData?.platformCredentialMissing === true && (
        <div className="rounded-lg border border-amber-300 bg-amber-50 p-4">
          <div className="flex items-start space-x-3">
            <AlertCircle className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" />
            <div className="flex-1 text-sm">
              <h4 className="font-semibold text-amber-900 mb-1">
                {t('platformCredentialMissing.title')}
              </h4>
              <p className="text-amber-800">
                {t('platformCredentialMissing.body')}
              </p>
            </div>
          </div>
        </div>
      )}
      {/* Pricing Model Selection */}
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
              <DollarSign className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h3 className="text-lg font-semibold text-theme-primary">{t('pricingModel.title')}</h3>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {!isEditing ? (
              <Button
                onClick={() => {
                  setInitialConfig({ ...config });
                  setIsEditing(true);
                }}
                disabled={isSubTabEditing}
                variant="ghost"
                size="icon"
                title={isSubTabEditing ? t('actions.finishEditingFirst') : t('actions.edit')}
              >
                <SquarePen className="w-4 h-4" />
              </Button>
            ) : (
              <div className="flex items-center gap-2">
                <Button
                  onClick={handleCancel}
                  variant="outline"
                >
                  {t('actions.cancel')}
                </Button>
                <Button
                  onClick={handleSave}
                  disabled={isSaving || !hasChanges}
                  variant="default"
                >
                  <Save className="w-4 h-4 mr-2" />
                  {isSaving ? t('actions.saving') : t('actions.save')}
                </Button>
              </div>
            )}
          </div>
        </div>
        {/* Hybrid Approach Information */}
        <div className="mb-6">
          <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
            <div className="flex items-start space-x-3">
              <div className="flex-shrink-0">
                <div className="w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center">
                  <MessageSquare className="w-4 h-4 text-blue-600" />
                </div>
              </div>
              <div className="flex-1">
                <h4 className="text-sm font-semibold text-blue-900 mb-2">
                  {t('hybridInfo.title')}
                </h4>
                <div className="space-y-1 text-sm text-blue-800">
                  <div className="flex items-center space-x-2">
                    <div className="w-2 h-2 bg-purple-500 rounded-full"></div>
                    <span><strong>{t('hybridInfo.aiIntegration')}</strong> {t('hybridInfo.aiIntegrationDesc')}</span>
                  </div>
                  <div className="flex items-center space-x-2">
                    <div className="w-2 h-2 bg-orange-500 rounded-full"></div>
                    <span><strong>{t('hybridInfo.apiMarketplace')}</strong> {t('hybridInfo.apiMarketplaceDesc')}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Two distinct business models: MCP Chat vs Traditional API */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mt-4">
          <div
            className={`relative rounded-xl border-2 p-6 transition-all duration-200 ${isEditing ? 'cursor-pointer' : 'cursor-default'
              } ${config.selectedPricingModels?.includes('freemium') || config.pricing === 'freemium'
                ? 'border-purple-500 bg-purple-50 dark:bg-purple-900/20'
                : 'border-theme/20 hover:border-purple-300'
              }`}
            onClick={isEditing ? () => handlePricingChange('freemium') : undefined}
          >
            <div className="flex items-center space-x-3 mb-4">
              <MessageSquare className="w-6 h-6 text-purple-500" />
              <div>
                <h4 className="text-lg font-semibold text-black">{t('aiIntegration.title')}</h4>
                <p className="text-sm text-black">{t('aiIntegration.subtitle')}</p>
              </div>
            </div>
            <ul className="space-y-2 text-sm text-black">
              <li>• {t('aiIntegration.features.zeroFriction')}</li>
              <li>• {t('aiIntegration.features.aiPowered')}</li>
              <li>• {t('aiIntegration.features.creditBased')}</li>
              <li>• {t('aiIntegration.features.conversational')}</li>
              <li>• {t('aiIntegration.features.autoRateLimit')}</li>
              <li>• {t('aiIntegration.features.premiumUnlock')}</li>
            </ul>
            {config.selectedPricingModels?.includes('freemium') || config.pricing === 'freemium' ? (
              <div className="absolute top-4 right-4">
                <CheckCircle className="w-6 h-6 text-purple-500" />
              </div>
            ) : null}
          </div>

          <div
            className={`relative rounded-xl border-2 p-6 transition-all duration-200 ${isEditing ? 'cursor-pointer' : 'cursor-default'
              } ${config.selectedPricingModels?.includes('paid') || config.pricing === 'paid'
                ? 'border-orange-500 bg-orange-50 dark:bg-orange-900/20'
                : 'border-theme/20 hover:border-orange-300'
              }`}
            onClick={isEditing ? () => handlePricingChange('paid') : undefined}
          >
            <div className="flex items-center space-x-3 mb-4">
              <Code className="w-6 h-6 text-orange-500" />
              <div>
                <h4 className="text-lg font-semibold text-black">{t('apiMarketplace.title')}</h4>
                <p className="text-sm text-black">{t('apiMarketplace.subtitle')}</p>
              </div>
            </div>
            <ul className="space-y-2 text-sm text-black">
              <li>• {t('apiMarketplace.features.clientSide')}</li>
              <li>• {t('apiMarketplace.features.manualApi')}</li>
              <li>• {t('apiMarketplace.features.customAuth')}</li>
              <li>• {t('apiMarketplace.features.clientRateLimits')}</li>
              <li>• {t('apiMarketplace.features.sdks')}</li>
              <li>• {t('apiMarketplace.features.enterprise')}</li>
            </ul>
            {config.selectedPricingModels?.includes('paid') || config.pricing === 'paid' ? (
              <div className="absolute top-4 right-4">
                <CheckCircle className="w-6 h-6 text-orange-500" />
              </div>
            ) : null}
          </div>
        </div>
      </div>

      {/* Tabs for AI Integration and API Marketplace */}
      <div>
        {/* Tab Navigation */}
        <div className="flex border-b border-theme">
          <Button
            onClick={() => setActiveTab('ai-integration')}
            variant="ghost"
            className={`flex-1 px-6 py-4 text-sm font-medium transition-colors duration-200 rounded-none h-auto ${activeTab === 'ai-integration'
              ? 'text-purple-600 border-b-2 border-purple-600 bg-purple-50'
              : 'text-theme-secondary hover:text-theme-primary hover:bg-theme-tertiary'
              }`}
          >
            <div className="flex items-center justify-center space-x-2">
              <MessageSquare className="w-5 h-5" />
              <span>{t('tabs.aiIntegration')}</span>
            </div>
          </Button>
          <Button
            onClick={() => setActiveTab('api-marketplace')}
            variant="ghost"
            className={`flex-1 px-6 py-4 text-sm font-medium transition-colors duration-200 rounded-none h-auto ${activeTab === 'api-marketplace'
              ? 'text-orange-600 border-b-2 border-orange-600 bg-orange-50'
              : 'text-theme-secondary hover:text-theme-primary hover:bg-theme-tertiary'
              }`}
          >
            <div className="flex items-center justify-center space-x-2">
              <Code className="w-5 h-5" />
              <span>{t('tabs.apiMarketplace')}</span>
            </div>
          </Button>
        </div>

        {/* Tab Content */}
        <div className="pt-4">
          {activeTab === 'ai-integration' && config.selectedPricingModels?.includes('freemium') && (
            <AIIntegrationTab
              config={config}
              setConfig={setConfig}
              isEditing={isEditing}
              setIsEditing={setIsEditing}
              isSaving={isSaving}
              hasChanges={hasChanges}
              setHasChanges={setHasChanges}
              onSave={handleSave}
              onCancel={handleCancel}
              mcpTools={mcpTools}
              userMonetizationState={userMonetizationState}
              onUpdateToolFreemiumConfig={onUpdateToolFreemiumConfig}
              onBatchUpdateToolsFreemiumConfig={onBatchUpdateToolsFreemiumConfig}
              setIsSubTabEditing={setIsSubTabEditing}
            />
          )}

          {(() => {
            console.log('🔍 Tab rendering check:', {
              activeTab,
              selectedPricingModels: config.selectedPricingModels,
              includesPaid: config.selectedPricingModels?.includes('paid'),
              shouldRender: activeTab === 'api-marketplace' && config.selectedPricingModels?.includes('paid')
            });
            return null;
          })()}

          {activeTab === 'api-marketplace' && config.selectedPricingModels?.includes('paid') && (
            <APIMarketplaceTab
              config={config}
              setConfig={setConfig}
              isEditing={isEditing}
              setIsEditing={setIsEditing}
              isSaving={isSaving}
              hasChanges={hasChanges}
              setHasChanges={setHasChanges}
              onSave={handleSave}
              onCancel={handleCancel}
              mcpTools={mcpTools}
              userMonetizationState={userMonetizationState}
              isLoading={isLoading}
              error={error}
              onUpdateToolPaidConfig={onUpdateToolPaidConfig}
              onBatchUpdateToolsPaidConfig={onBatchUpdateToolsPaidConfig}
              onUpdatePaidPlans={onUpdatePaidPlans}
              setIsSubTabEditing={setIsSubTabEditing}
              refreshData={refreshData}
            />
          )}

          {/* No content message when no pricing models are selected */}
          {activeTab === 'ai-integration' && !config.selectedPricingModels?.includes('freemium') && (
            <div className="text-center py-12">
              <MessageSquare className="w-12 h-12 text-theme-secondary mx-auto mb-4" />
              <h3 className="text-lg font-medium text-theme-primary mb-2">{t('notSelected.aiIntegration.title')}</h3>
              <p className="text-theme-secondary">{t('notSelected.aiIntegration.description')}</p>
            </div>
          )}

          {activeTab === 'api-marketplace' && !config.selectedPricingModels?.includes('paid') && (
            <div className="text-center py-12">
              <Code className="w-12 h-12 text-theme-secondary mx-auto mb-4" />
              <h3 className="text-lg font-medium text-theme-primary mb-2">{t('notSelected.apiMarketplace.title')}</h3>
              <p className="text-theme-secondary">{t('notSelected.apiMarketplace.description')}</p>
            </div>
          )}
        </div>
      </div>

      {/* Deselection Confirmation Modal */}
      {showDeselectModal && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="max-w-md w-full bg-theme-primary rounded-2xl shadow-2xl p-6 text-center border border-theme">
            {/* Warning icon */}
            <div className="w-16 h-16 bg-orange-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <AlertCircle className="w-8 h-8 text-orange-600" />
            </div>

            {/* Title */}
            <h2 className="text-xl font-semibold text-theme-primary mb-2">
              {t('deselectModal.title')}
            </h2>

            {/* Message */}
            <p className="text-theme-secondary mb-6">
              {t('deselectModal.message', { model: pricingToDeselect === 'freemium' ? t('tabs.aiIntegration') : t('tabs.apiMarketplace') })}
            </p>

            {/* Action buttons */}
            <div className="flex space-x-3">
              <Button
                onClick={handleCancelDeselect}
                variant="outline"
                className="flex-1"
              >
                {t('actions.cancel')}
              </Button>
              <Button
                onClick={handleConfirmDeselect}
                variant="destructive"
                className="flex-1"
              >
                {t('actions.deselect')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default MonetizeTab;
