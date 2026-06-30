import React from 'react';
import { useTranslations } from 'next-intl';
import { Zap, Gift, DollarSign, CreditCard, Info, MessageSquare, Code, AlertCircle } from 'lucide-react';
import { MonetizationConfig } from '../../types';
import { FormSection, FormField, FormInput, FormSelect, InfoBox, FormGrid } from './index';
import { ToggleGroup } from '@/components/ui/toggle-group';
import { Card } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';

interface PricingSectionsProps {
  monetizationConfig: MonetizationConfig;
  setMonetizationConfig: (config: MonetizationConfig) => void;
  mcpTools: Array<{ name: string; toolCategory: string; endpoint: string }>;
  showRateLimitInfo: boolean;
  setShowRateLimitInfo: (show: boolean) => void;
  showPlansInfo: boolean;
  setShowPlansInfo: (show: boolean) => void;
  showFreeRequestsInfo: boolean;
  setShowFreeRequestsInfo: (show: boolean) => void;
  sectionsExpanded: { [key: string]: boolean };
  toggleSection: (section: string) => void;
  rateLimitPeriodOptions: { value: string; label: string }[];
  rpsPeriodOptions: { value: string; label: string }[];
  handleRateLimitChange: (field: 'requests' | 'period', value: number | string) => void;
  handlePlanSelection: (plan: string, selected: boolean) => void;
  handleHardLimitChange: (plan: string, value: boolean) => void;
  handlePlanPricingChange: (plan: string, field: 'price' | 'quota' | 'rps', value: number) => void;
  handleRpsPeriodChange: (plan: string, period: 'second' | 'minute' | 'hour' | 'day') => void;
  handleOverusageCostChange: (plan: string, value: number) => void;
  handleToolPricingChange: (toolName: string, mauValue: number) => void;
  handlePlanIncludeAllToolsChange: (plan: string, includeAll: boolean) => void;
  handlePlanToolSelection: (plan: string, toolName: string, selected: boolean) => void;
  handleUniformPricingChange: (mauValue: number) => void;
  handleUniformCallsChange: (calls: number) => void;
  validation: { isValid: boolean; unassignedTools: string[] };
}

const PricingSections: React.FC<PricingSectionsProps> = (props) => {
  const t = useTranslations('pricing');
  const models = props.monetizationConfig.selectedPricingModels || [props.monetizationConfig.pricing];

  return (
    <div className="space-y-10">
      {/* AI Integration Section */}
      {models.includes('freemium') && (
        <div className="space-y-4">
          <div className="flex items-center space-x-3 mt-6">
            <MessageSquare className="w-6 h-6 text-purple-500 flex-shrink-0" />
            <h3 className="text-xl font-semibold text-theme-primary">{t('aiIntegration')}</h3>
          </div>

          {/* Rate Limiting Section */}
          <FormSection
            title={t('rateLimiting.title')}
            icon={Zap}
            iconColor="text-orange-500"
            collapsible
            isExpanded={props.sectionsExpanded.rateLimit}
            onToggle={() => props.toggleSection('rateLimit')}
          >
            <div className="flex items-center justify-between mb-4">
              <button
                type="button"
                onClick={() => props.setShowRateLimitInfo(!props.showRateLimitInfo)}
                className="text-theme-muted hover:text-theme-primary transition-colors"
              >
                <Info className="w-5 h-5" />
              </button>
            </div>

            {props.showRateLimitInfo && (
              <InfoBox
                type="info"
                title={t('rateLimiting.infoTitle')}
              >
                <div className="space-y-2">
                  <p><strong>{t('rateLimiting.globalSettingLabel')}</strong> {t('rateLimiting.globalSettingText')}</p>
                  <p><strong>{t('rateLimiting.perToolSettingLabel')}</strong> {t('rateLimiting.perToolSettingText')}</p>
                  <p>{t('rateLimiting.limitReachedText')}</p>
                </div>
              </InfoBox>
            )}

            {/* Rate limiting configuration toggle - only if tools exist */}
            {props.mcpTools.length > 0 && (
              <div className="flex items-center justify-center mb-6">
                <ToggleGroup
                  value={(!props.monetizationConfig.toolRateLimits || Object.keys(props.monetizationConfig.toolRateLimits).length === 0) ? 'all' : 'per-tool'}
                  hasBorder={false}
                  onValueChange={(value) => {
                    if (value === 'all') {
                      // Switch to global setting
                      props.setMonetizationConfig({
                        ...props.monetizationConfig,
                        toolRateLimits: {}
                      });
                    } else {
                      // Switch to individual rate limits
                      const initialRateLimits: Record<string, any> = props.mcpTools.reduce((acc, tool) => {
                        acc[tool.name] = {
                          requests: props.monetizationConfig.rateLimit?.requests || 1000,
                          period: props.monetizationConfig.rateLimit?.period || 'hour'
                        };
                        return acc;
                      }, {} as Record<string, any>);

                      props.setMonetizationConfig({
                        ...props.monetizationConfig,
                        toolRateLimits: initialRateLimits
                      });
                    }
                  }}
                  options={[
                    { value: 'all', label: t('allTools') },
                    { value: 'per-tool', label: t('perTool') },
                  ]}
                />
              </div>
            )}

            {/* Global configuration for all tools - Displayed when "All tools" is selected */}
            {(!props.monetizationConfig.toolRateLimits || Object.keys(props.monetizationConfig.toolRateLimits).length === 0) && (
              <FormGrid cols={2}>
                <FormField
                  label={<><span className="hidden sm:inline">{t('rateLimiting.numberOfRequests')}</span><span className="sm:hidden">{t('rateLimiting.requests')}</span></>}
                >
                  <FormInput
                    type="number"
                    value={props.monetizationConfig.rateLimit?.requests?.toString() || '1000'}
                    onChange={(value) => props.handleRateLimitChange('requests', parseInt(value) || 0)}
                    min={1}
                    className="text-sm"
                  />
                </FormField>
                <FormField
                  label={t('rateLimiting.period')}
                >
                  <FormSelect
                    value={props.monetizationConfig.rateLimit?.period || 'hour'}
                    onChange={(value) => props.handleRateLimitChange('period', value)}
                    options={props.rateLimitPeriodOptions}
                  />
                </FormField>
              </FormGrid>
            )}

            {/* Individual rate limits per tool - Displayed only if individual configuration enabled */}
            {props.monetizationConfig.toolRateLimits && Object.keys(props.monetizationConfig.toolRateLimits).length > 0 && props.mcpTools.length > 0 && (
              <div className="space-y-4">
                {/* Individual rate limits header */}
                <div className="mb-4">
                  <h4 className="text-lg font-medium text-theme-primary">{t('rateLimiting.perToolTitle')}</h4>
                  <div className="mt-2 text-xs text-theme-muted">
                    <p>{t('rateLimiting.perToolDescription')}</p>
                  </div>
                </div>

                {Object.entries(
                  props.mcpTools.reduce((acc, tool) => {
                    if (!acc[tool.toolCategory]) acc[tool.toolCategory] = [];
                    acc[tool.toolCategory].push(tool);
                    return acc;
                  }, {} as Record<string, typeof props.mcpTools>)
                ).map(([toolCategory, tools]) => (
                  <Card key={toolCategory} className="border border-theme rounded-xl overflow-hidden bg-theme-tertiary">
                    <div className="bg-theme-secondary px-4 py-2 border-b border-theme">
                      <h4 className="font-medium text-theme-primary">{toolCategory}</h4>
                    </div>
                    <div className="divide-y divide-theme">
                      {tools.map((tool) => (
                        <div key={tool.name}>
                          {/* Desktop layout */}
                          <div className="hidden sm:block p-3 bg-theme-primary">
                            {/* Tool info label - compact */}
                            <div className="mb-3">
                              <h5 className="font-medium text-theme-primary text-sm">{tool.name}</h5>
                              <p className="text-xs text-theme-muted font-mono mt-0.5">
                                {tool.endpoint}
                              </p>
                            </div>
                            {/* Fields - full width, grouped with tool info */}
                            <div className="flex items-center space-x-3 pl-0">
                              <FormInput
                                type="number"
                                value={props.monetizationConfig.toolRateLimits?.[tool.name]?.requests?.toString() || '1000'}
                                onChange={(value) => {
                                  const newValue = Math.max(1, parseInt(value) || 1);
                                  props.setMonetizationConfig({
                                    ...props.monetizationConfig,
                                    toolRateLimits: {
                                      ...props.monetizationConfig.toolRateLimits,
                                      [tool.name]: {
                                        ...props.monetizationConfig.toolRateLimits?.[tool.name],
                                        requests: newValue
                                      }
                                    }
                                  });
                                }}
                                min={1}
                                className="text-sm flex-1"
                                step={1}
                                placeholder="1000"
                              />
                              <FormSelect
                                value={props.monetizationConfig.toolRateLimits?.[tool.name]?.period || 'month'}
                                onChange={(value) => {
                                  props.setMonetizationConfig({
                                    ...props.monetizationConfig,
                                    toolRateLimits: {
                                      ...props.monetizationConfig.toolRateLimits,
                                      [tool.name]: {
                                        ...props.monetizationConfig.toolRateLimits?.[tool.name],
                                        period: value as 'second' | 'minute' | 'hour' | 'day' | 'month'
                                      }
                                    }
                                  });
                                }}
                                options={[
                                  { value: 'second', label: t('periods.perSecond') },
                                  { value: 'minute', label: t('periods.perMinute') },
                                  { value: 'hour', label: t('periods.perHour') },
                                  { value: 'day', label: t('periods.perDay') },
                                  { value: 'month', label: t('periods.perMonth') }
                                ]}
                                className="text-sm !w-auto flex-shrink-0 min-w-[140px]"
                              />
                            </div>
                          </div>

                          {/* Mobile layout - Vertical stacking */}
                          <div className="sm:hidden p-3 bg-theme-primary space-y-3">
                            {/* Tool info */}
                            <div>
                              <h5 className="font-medium text-theme-primary">{tool.name}</h5>
                              <p className="text-xs text-theme-muted mt-1 font-mono">
                                {tool.endpoint}
                              </p>
                            </div>

                            {/* Rate limit controls */}
                            <div className="flex flex-col space-y-2">
                              <div className="flex items-center space-x-2">
                                <FormInput
                                  type="number"
                                  value={props.monetizationConfig.toolRateLimits?.[tool.name]?.requests?.toString() || '1000'}
                                  onChange={(value) => {
                                    const newValue = Math.max(1, parseInt(value) || 1);
                                    props.setMonetizationConfig({
                                      ...props.monetizationConfig,
                                      toolRateLimits: {
                                        ...props.monetizationConfig.toolRateLimits,
                                        [tool.name]: {
                                          ...props.monetizationConfig.toolRateLimits?.[tool.name],
                                          requests: newValue
                                        }
                                      }
                                    });
                                  }}
                                  min={1}
                                  className="text-sm flex-1"
                                  step={1}
                                  placeholder="1000"
                                />
                                <span className="text-sm text-theme-muted whitespace-nowrap">{t('rateLimiting.requests')}</span>
                              </div>

                              <FormSelect
                                value={props.monetizationConfig.toolRateLimits?.[tool.name]?.period || 'month'}
                                onChange={(value) => {
                                  props.setMonetizationConfig({
                                    ...props.monetizationConfig,
                                    toolRateLimits: {
                                      ...props.monetizationConfig.toolRateLimits,
                                      [tool.name]: {
                                        ...props.monetizationConfig.toolRateLimits?.[tool.name],
                                        period: value as 'second' | 'minute' | 'hour' | 'day' | 'month'
                                      }
                                    }
                                  });
                                }}
                                options={[
                                  { value: 'second', label: t('periods.perSecond') },
                                  { value: 'minute', label: t('periods.perMinute') },
                                  { value: 'hour', label: t('periods.perHour') },
                                  { value: 'day', label: t('periods.perDay') },
                                  { value: 'month', label: t('periods.perMonth') }
                                ]}
                                className="text-sm w-full"
                              />
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </FormSection>

          {/* Free requests/month section for Freemium plans */}
          <FormSection
            title={t('freeRequests.title')}
            icon={Gift}
            iconColor="text-blue-500"
            collapsible
            isExpanded={props.sectionsExpanded.freeRequests}
            onToggle={() => props.toggleSection('freeRequests')}
          >
            <div className="flex items-center justify-between mb-4">
              <button
                type="button"
                onClick={() => props.setShowFreeRequestsInfo(!props.showFreeRequestsInfo)}
                className="text-theme-muted hover:text-theme-primary transition-colors"
              >
                <Info className="w-5 h-5" />
              </button>
            </div>

            {props.showFreeRequestsInfo && (
              <InfoBox
                type="info"
                title={t('freeRequests.infoTitle')}
              >
                <div className="space-y-2">
                  <p><strong>{t('freeRequests.betterRankingLabel')}</strong> {t('freeRequests.betterRankingText')}</p>
                  <p>{t('freeRequests.visibilityText')}</p>
                </div>
              </InfoBox>
            )}

            {/* Free requests/month configuration section - only if tools exist */}
            {props.mcpTools.length > 0 && (
              <div className="flex items-center justify-center mb-6">
                <ToggleGroup
                  value={(!props.monetizationConfig.toolFreeRequests || Object.keys(props.monetizationConfig.toolFreeRequests).length === 0) ? 'all' : 'per-tool'}
                  hasBorder={false}
                  onValueChange={(value) => {
                    if (value === 'all') {
                      // Switch to global setting
                      props.setMonetizationConfig({
                        ...props.monetizationConfig,
                        toolFreeRequests: {}
                      });
                    } else {
                      // Switch to individual free requests/month
                      const initialFreeRequests: Record<string, number | string> = props.mcpTools.reduce((acc, tool) => {
                        acc[tool.name] = props.monetizationConfig.freeRequestsPerUser || 1000;
                        acc[`${tool.name}_type`] = 'per-user';
                        return acc;
                      }, {} as Record<string, number | string>);

                      props.setMonetizationConfig({
                        ...props.monetizationConfig,
                        toolFreeRequests: initialFreeRequests
                      });
                    }
                  }}
                  options={[
                    { value: 'all', label: t('allTools') },
                    { value: 'per-tool', label: t('perTool') },
                  ]}
                />
              </div>
            )}

            {/* Global configuration for all tools - Displayed when "All tools" is selected */}
            {(!props.monetizationConfig.toolFreeRequests || Object.keys(props.monetizationConfig.toolFreeRequests).length === 0) && props.mcpTools.length > 0 && (
                <div className="mb-6 p-4 border border-theme rounded-xl bg-theme-tertiary">
                <div className="flex items-center justify-between mb-4">
                  <div>
                    <h4 className="text-lg font-medium text-theme-primary mb-1">{t('freeRequests.globalConfig')}</h4>
                    <p className="text-sm text-theme-muted">{t('freeRequests.globalConfigDescription')}</p>
                  </div>
                </div>

                {/* Desktop layout */}
                <div className="hidden sm:flex items-center space-x-3">
                  <FormInput
                    type="number"
                    value={props.monetizationConfig.freeRequestsPerUser?.toString() || '1000'}
                    onChange={(value) => {
                      const newValue = Math.max(0, parseInt(value) || 0);
                      props.setMonetizationConfig({
                        ...props.monetizationConfig,
                        freeRequestsPerUser: newValue
                      });
                    }}
                    min={0}
                    step={1}
                    placeholder="1000"
                    className="flex-1"
                  />
                  <FormSelect
                    value={(props.monetizationConfig as any).freeRequestsType || 'per-user'}
                    onChange={(value) => {
                      props.setMonetizationConfig({
                        ...props.monetizationConfig,
                        freeRequestsType: value as 'per-user' | 'global'
                      });
                    }}
                    options={[
                      { value: 'per-user', label: t('freeRequests.perUser') },
                      { value: 'global', label: t('freeRequests.global') }
                    ]}
                    className="!w-auto flex-shrink-0 min-w-[120px]"
                  />
                  <span className="text-sm text-theme-muted">{t('freeRequests.requestsPerMonth')}</span>
                </div>

                {/* Mobile layout - Vertical stacking */}
                <div className="sm:hidden flex flex-col space-y-3">
                  <div className="flex flex-col space-y-2">
                    <label className="text-sm font-medium text-theme-primary">{t('freeRequests.freeRequestsPerMonth')}</label>
                    <div className="flex items-center space-x-2">
                      <FormInput
                        type="number"
                        value={props.monetizationConfig.freeRequestsPerUser?.toString() || '1000'}
                        onChange={(value) => {
                          const newValue = Math.max(0, parseInt(value) || 0);
                          props.setMonetizationConfig({
                            ...props.monetizationConfig,
                            freeRequestsPerUser: newValue
                          });
                        }}
                        min={0}
                        step={1}
                        placeholder="1000"
                        className="text-sm flex-1"
                      />
                      <span className="text-sm text-theme-muted whitespace-nowrap">{t('freeRequests.requestsPerMonth')}</span>
                    </div>
                  </div>

                  <div className="flex flex-col space-y-2">
                    <label className="text-sm font-medium text-theme-primary">{t('freeRequests.distributionType')}</label>
                    <FormSelect
                      value={(props.monetizationConfig as any).freeRequestsType || 'per-user'}
                      onChange={(value) => {
                        props.setMonetizationConfig({
                          ...props.monetizationConfig,
                          freeRequestsType: value as 'per-user' | 'global'
                        });
                      }}
                      options={[
                        { value: 'per-user', label: t('freeRequests.perUserDescription') },
                        { value: 'global', label: t('freeRequests.globalDescription') }
                      ]}
                      className="w-full"
                    />
                  </div>
                </div>
              </div>
            )}

            {/* Individual free requests/month per tool - Displayed only if individual configuration enabled */}
            {props.monetizationConfig.toolFreeRequests && Object.keys(props.monetizationConfig.toolFreeRequests).length > 0 && props.mcpTools.length > 0 && (
              <div className="space-y-4">
                {/* Individual free requests/month header */}
                <div className="mb-4">
                  <h4 className="text-lg font-medium text-theme-primary">{t('freeRequests.perToolTitle')}</h4>
                  <div className="mt-2 text-xs text-theme-muted">
                    <p><strong>{t('freeRequests.perUserLabel')}</strong> {t('freeRequests.perUserText')}</p>
                    <p><strong>{t('freeRequests.globalLabel')}</strong> {t('freeRequests.globalText')}</p>
                  </div>
                </div>

                {Object.entries(
                  props.mcpTools.reduce((acc, tool) => {
                    if (!acc[tool.toolCategory]) acc[tool.toolCategory] = [];
                    acc[tool.toolCategory].push(tool);
                    return acc;
                  }, {} as Record<string, typeof props.mcpTools>)
                ).map(([toolCategory, tools]) => (
                  <Card key={toolCategory} className="border border-theme rounded-xl overflow-hidden bg-theme-tertiary">
                    <div className="bg-theme-secondary px-4 py-2 border-b border-theme">
                      <h4 className="font-medium text-theme-primary">{toolCategory}</h4>
                    </div>
                    <div className="divide-y divide-theme">
                      {tools.map((tool, index) => (
                        <div key={`${toolCategory}-${tool.name}-${index}`}>
                          {/* Desktop layout */}
                          <div className="hidden sm:block p-3 bg-theme-primary">
                            {/* Tool info label - compact */}
                            <div className="mb-3">
                              <h5 className="font-medium text-theme-primary text-sm">{tool.name}</h5>
                              <p className="text-xs text-theme-muted font-mono mt-0.5">
                                {tool.endpoint}
                              </p>
                            </div>
                            {/* Fields - full width, grouped with tool info */}
                            <div className="flex items-center space-x-3 pl-0">
                              <FormInput
                                type="number"
                                value={props.monetizationConfig.toolFreeRequests?.[tool.name]?.toString() || '1000'}
                                onChange={(value) => {
                                  const newValue = Math.max(0, parseInt(value) || 0);
                                  props.setMonetizationConfig({
                                    ...props.monetizationConfig,
                                    toolFreeRequests: {
                                      ...props.monetizationConfig.toolFreeRequests,
                                      [tool.name]: newValue
                                    }
                                  });
                                }}
                                min={0}
                                step={1}
                                placeholder="1000"
                                className="flex-1"
                              />
                              <FormSelect
                                value={String(props.monetizationConfig.toolFreeRequests?.[`${tool.name}_type`] || 'per-user')}
                                onChange={(value) => {
                                  props.setMonetizationConfig({
                                    ...props.monetizationConfig,
                                    toolFreeRequests: {
                                      ...props.monetizationConfig.toolFreeRequests,
                                      [`${tool.name}_type`]: value
                                    }
                                  });
                                }}
                                options={[
                                  { value: 'per-user', label: t('freeRequests.perUser') },
                                  { value: 'global', label: t('freeRequests.global') }
                                ]}
                                className="text-sm !w-auto flex-shrink-0 min-w-[140px]"
                              />
                              <span className="text-sm text-theme-muted">{t('freeRequests.requestsPerMonth')}</span>
                            </div>
                          </div>

                          {/* Mobile layout - Vertical stacking */}
                          <div className="sm:hidden p-3 bg-theme-primary space-y-3">
                            {/* Tool info */}
                            <div>
                              <h5 className="font-medium text-theme-primary">{tool.name}</h5>
                              <p className="text-xs text-theme-muted mt-1 font-mono">
                                {tool.endpoint}
                              </p>
                            </div>

                            {/* Free requests controls */}
                            <div className="flex flex-col space-y-2">
                              <div className="flex items-center space-x-2">
                                <FormInput
                                  type="number"
                                  value={props.monetizationConfig.toolFreeRequests?.[tool.name]?.toString() || '1000'}
                                  onChange={(value) => {
                                    const newValue = Math.max(0, parseInt(value) || 0);
                                    props.setMonetizationConfig({
                                      ...props.monetizationConfig,
                                      toolFreeRequests: {
                                        ...props.monetizationConfig.toolFreeRequests,
                                        [tool.name]: newValue
                                      }
                                    });
                                  }}
                                  min={0}
                                  step={1}
                                  placeholder="1000"
                                  className="text-sm flex-1"
                                />
                                <span className="text-sm text-theme-muted whitespace-nowrap">{t('freeRequests.requestsPerMonth')}</span>
                              </div>

                              <FormSelect
                                value={String(props.monetizationConfig.toolFreeRequests?.[`${tool.name}_type`] || 'per-user')}
                                onChange={(value) => {
                                  props.setMonetizationConfig({
                                    ...props.monetizationConfig,
                                    toolFreeRequests: {
                                      ...props.monetizationConfig.toolFreeRequests,
                                      [`${tool.name}_type`]: value
                                    }
                                  });
                                }}
                                options={[
                                  { value: 'per-user', label: t('freeRequests.perUserDescription') },
                                  { value: 'global', label: t('freeRequests.globalDescription') }
                                ]}
                                className="text-sm w-full"
                              />
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </FormSection>

          {/* Specific tool pricing section for Freemium plans */}
          {props.mcpTools.length > 0 && (
            <FormSection
              title={t('pricing.title')}
              icon={DollarSign}
              iconColor="text-green-500"
              collapsible
              isExpanded={props.sectionsExpanded.tools}
              onToggle={() => props.toggleSection('tools')}
            >
              {/* Switch to choose pricing mode */}
              <div className="flex items-center justify-center mb-6">
                <ToggleGroup
                  value={Object.keys(props.monetizationConfig.toolPricing).length === 0 ? 'all' : 'per-tool'}
                  hasBorder={false}
                  onValueChange={(value) => {
                    if (value === 'all') {
                      // Switch to uniform pricing
                      props.setMonetizationConfig({
                        ...props.monetizationConfig,
                        uniformToolPrice: 1,
                        uniformToolPriceInDollars: 0.005,
                        uniformCalls: 1,
                        toolPricing: {}
                      } as any);
                    } else {
                      // Switch to individual pricing
                      const initialPricing = props.mcpTools.reduce((acc, tool) => {
                        acc[tool.name] = {
                          mauValue: 1,
                          price: 0.005,
                          currency: 'USD',
                          calls: 1
                        };
                        return acc;
                      }, {} as Record<string, any>);

                      props.setMonetizationConfig({
                        ...props.monetizationConfig,
                        uniformToolPrice: 0,
                        uniformToolPriceInDollars: 0,
                        toolPricing: initialPricing
                      });
                    }
                  }}
                  options={[
                    { value: 'all', label: t('allTools') },
                    { value: 'per-tool', label: t('perTool') },
                  ]}
                />
              </div>

              {/* Uniform pricing for all tools - Displayed only if no individual pricing */}
              {Object.keys(props.monetizationConfig.toolPricing).length === 0 && (
                <div className="mb-6 p-4 border border-theme rounded-xl bg-theme-tertiary">
                  {/* Desktop layout */}
                  <div className="hidden sm:flex items-center justify-between mb-3">
                    <div>
                      <FormField
                        label={t('pricing.uniformPricing')}
                      >
                        <p className="text-xs text-theme-muted">{t('pricing.mauRate')}</p>
                      </FormField>
                    </div>
                    <div className="flex items-center space-x-2">
                      <FormInput
                        type="number"
                        value={props.monetizationConfig.uniformToolPrice?.toString() || '1'}
                        onChange={(value) => props.handleUniformPricingChange(parseInt(value) || 1)}
                        min={1}
                        step={1}
                        placeholder="1"
                        className="flex-1"
                      />
                      <span className="text-sm text-theme-muted">
                        {t('pricing.mauValueFor', { value: ((props.monetizationConfig.uniformToolPrice || 1) * 0.005).toFixed(8).replace(/\.?0+$/, '') })}
                      </span>
                      <FormInput
                        type="number"
                        value={(props.monetizationConfig as any).uniformCalls?.toString() || '1'}
                        onChange={(value) => props.handleUniformCallsChange(parseInt(value) || 1)}
                        min={1}
                        step={1}
                        placeholder="1"
                        className="flex-1"
                      />
                      <span className="text-sm text-theme-muted">{t('pricing.callCount', { count: (props.monetizationConfig as any).uniformCalls || 1 })}</span>
                      <span className="text-sm text-theme-muted">
                        ({t('pricing.perCallPrice', { price: (((props.monetizationConfig.uniformToolPrice || 1) * 0.005) / ((props.monetizationConfig as any).uniformCalls || 1)).toFixed(8).replace(/\.?0+$/, '') })})
                      </span>
                    </div>
                  </div>

                  {/* Mobile layout - Vertical stacking */}
                  <div className="sm:hidden space-y-4">
                    {/* Header section */}
                    <div>
                      <FormField
                        label={t('pricing.uniformPricing')}
                      >
                        <p className="text-xs text-theme-muted">{t('pricing.mauRate')}</p>
                      </FormField>
                    </div>

                    {/* Pricing controls */}
                    <div className="flex flex-col space-y-4">
                      {/* MAU input */}
                      <div className="flex flex-col space-y-2">
                        <label className="text-sm font-medium text-theme-primary">{t('pricing.monthlyActiveUsers')}</label>
                        <div className="flex items-center space-x-2">
                          <FormInput
                            type="number"
                            value={props.monetizationConfig.uniformToolPrice?.toString() || '1'}
                            onChange={(value) => props.handleUniformPricingChange(parseInt(value) || 1)}
                            min={1}
                            step={1}
                            placeholder="1"
                            className="text-sm flex-1"
                          />
                          <span className="text-sm text-theme-muted whitespace-nowrap">
                            {t('pricing.mauEquals', { value: ((props.monetizationConfig.uniformToolPrice || 1) * 0.005).toFixed(8).replace(/\.?0+$/, '') })}
                          </span>
                        </div>
                      </div>

                      {/* Calls input */}
                      <div className="flex flex-col space-y-2">
                        <label className="text-sm font-medium text-theme-primary">{t('pricing.apiCallsPerMau')}</label>
                        <div className="flex items-center space-x-2">
                          <FormInput
                            type="number"
                            value={(props.monetizationConfig as any).uniformCalls?.toString() || '1'}
                            onChange={(value) => props.handleUniformCallsChange(parseInt(value) || 1)}
                            min={1}
                            step={1}
                            placeholder="1"
                            className="text-sm flex-1"
                          />
                          <span className="text-sm text-theme-muted whitespace-nowrap">
                            {t('pricing.callCount', { count: (props.monetizationConfig as any).uniformCalls || 1 })} ({t('pricing.perCallPrice', { price: (((props.monetizationConfig.uniformToolPrice || 1) * 0.005) / ((props.monetizationConfig as any).uniformCalls || 1)).toFixed(8).replace(/\.?0+$/, '') })})
                          </span>
                        </div>
                      </div>
                    </div>
                  </div>

                  <p className="text-xs text-blue-700 dark:text-blue-300 mt-3">
                    {t('pricing.uniformPricingNote')}
                  </p>
                </div>
              )}

              {/* Individual pricing per tool (grouped by tool category) - Displayed only if individual pricing enabled */}
              {Object.keys(props.monetizationConfig.toolPricing).length > 0 && (
                <div className="space-y-4">
                  {/* Individual pricing header */}
                  <div className="mb-4">
                    <h4 className="text-lg font-medium text-theme-primary">{t('pricing.individualTitle')}</h4>
                    <div className="mt-2 text-xs text-theme-muted">
                      <p><strong>{t('pricing.mauLabel')}</strong> {t('pricing.mauDescription')}</p>
                      <p><strong>{t('pricing.callsLabel')}</strong> {t('pricing.callsDescription')}</p>
                    </div>
                  </div>


                  {Object.entries(
                    props.mcpTools.reduce((acc, tool) => {
                      if (!acc[tool.toolCategory]) acc[tool.toolCategory] = [];
                      acc[tool.toolCategory].push(tool);
                      return acc;
                    }, {} as Record<string, typeof props.mcpTools>)
                  ).map(([toolCategory, tools]) => (
                    <Card key={toolCategory} className="border border-theme rounded-xl overflow-hidden bg-theme-tertiary">
                      <div className="bg-theme-secondary px-4 py-2 border-b border-theme">
                        <h4 className="font-medium text-theme-primary">{toolCategory}</h4>
                      </div>
                      <div className="divide-y divide-theme">
                        {tools.map((tool, index) => (
                          <div key={`${toolCategory}-${tool.name}-${index}`}>
                            {/* Desktop layout */}
                            <div className="hidden sm:block p-3 bg-theme-primary">
                              {/* Tool info label - compact */}
                              <div className="mb-3">
                                <h5 className="font-medium text-theme-primary text-sm">{tool.name}</h5>
                                <p className="text-xs text-theme-muted font-mono mt-0.5">
                                  {tool.endpoint}
                                </p>
                              </div>
                              {/* Fields - full width, grouped with tool info */}
                              <div className="flex items-center space-x-3 pl-0">
                                <FormInput
                                  type="number"
                                  value={props.monetizationConfig.toolPricing[tool.name]?.mauValue?.toString() || '1'}
                                  onChange={(value) => props.handleToolPricingChange(tool.name, parseInt(value) || 1)}
                                  min={1}
                                  step={1}
                                  placeholder="1"
                                  className="flex-1"
                                />
                                <span className="text-sm text-theme-muted whitespace-nowrap">
                                  {t('pricing.mauValueFor', { value: ((props.monetizationConfig.toolPricing[tool.name]?.mauValue || 1) * 0.005).toFixed(8).replace(/\.?0+$/, '') })}
                                </span>
                                <FormInput
                                  type="number"
                                  value={(props.monetizationConfig.toolPricing[tool.name] as any)?.calls?.toString() || '1'}
                                  onChange={(value) => {
                                    const newValue = Math.max(1, parseInt(value) || 1);
                                    props.setMonetizationConfig({
                                      ...props.monetizationConfig,
                                      toolPricing: {
                                        ...props.monetizationConfig.toolPricing,
                                        [tool.name]: {
                                          ...(props.monetizationConfig.toolPricing[tool.name] as any),
                                          calls: newValue
                                        }
                                      }
                                    });
                                  }}
                                  min={1}
                                  step={1}
                                  placeholder="1"
                                  className="flex-1"
                                />
                                <span className="text-sm text-theme-muted whitespace-nowrap">{t('pricing.callCount', { count: (props.monetizationConfig.toolPricing[tool.name] as any)?.calls || 1 })}</span>
                                <span className="text-sm text-theme-muted whitespace-nowrap">
                                  ({t('pricing.perCallPrice', { price: (((props.monetizationConfig.toolPricing[tool.name]?.mauValue || 1) * 0.005) / ((props.monetizationConfig.toolPricing[tool.name] as any)?.calls || 1)).toFixed(8).replace(/\.?0+$/, '') })})
                                </span>
                              </div>
                            </div>

                            {/* Mobile layout - Vertical stacking */}
                            <div className="sm:hidden p-3 bg-theme-primary space-y-3">
                              {/* Tool info */}
                              <div>
                                <h5 className="font-medium text-theme-primary">{tool.name}</h5>
                                <p className="text-xs text-theme-muted mt-1 font-mono">
                                  {tool.endpoint}
                                </p>
                              </div>

                              {/* Pricing controls */}
                              <div className="flex flex-col space-y-3">
                                {/* MAU input */}
                                <div className="flex flex-col space-y-1">
                                  <label className="text-sm font-medium text-theme-primary">{t('pricing.mau')}</label>
                                  <div className="flex items-center space-x-2">
                                    <FormInput
                                      type="number"
                                      value={props.monetizationConfig.toolPricing[tool.name]?.mauValue?.toString() || '1'}
                                      onChange={(value) => props.handleToolPricingChange(tool.name, parseInt(value) || 1)}
                                      min={1}
                                      step={1}
                                      placeholder="1"
                                      className="text-sm flex-1"
                                    />
                                    <span className="text-sm text-theme-muted whitespace-nowrap">
                                      {t('pricing.mauEquals', { value: ((props.monetizationConfig.toolPricing[tool.name]?.mauValue || 1) * 0.005).toFixed(8).replace(/\.?0+$/, '') })}
                                    </span>
                                  </div>
                                </div>

                                {/* Calls input */}
                                <div className="flex flex-col space-y-1">
                                  <label className="text-sm font-medium text-theme-primary">{t('pricing.apiCallsPerMau')}</label>
                                  <div className="flex items-center space-x-2">
                                    <FormInput
                                      type="number"
                                      value={(props.monetizationConfig.toolPricing[tool.name] as any)?.calls?.toString() || '1'}
                                      onChange={(value) => {
                                        const newValue = Math.max(1, parseInt(value) || 1);
                                        props.setMonetizationConfig({
                                          ...props.monetizationConfig,
                                          toolPricing: {
                                            ...props.monetizationConfig.toolPricing,
                                            [tool.name]: {
                                              ...(props.monetizationConfig.toolPricing[tool.name] as any),
                                              calls: newValue
                                            }
                                          }
                                        });
                                      }}
                                      min={1}
                                      step={1}
                                      placeholder="1"
                                      className="text-sm flex-1"
                                    />
                                    <span className="text-sm text-theme-muted whitespace-nowrap">
                                      {t('pricing.callCount', { count: (props.monetizationConfig.toolPricing[tool.name] as any)?.calls || 1 })} ({t('pricing.perCallPrice', { price: (((props.monetizationConfig.toolPricing[tool.name]?.mauValue || 1) * 0.005) / ((props.monetizationConfig.toolPricing[tool.name] as any)?.calls || 1)).toFixed(8).replace(/\.?0+$/, '') })})
                                    </span>
                                  </div>
                                </div>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    </Card>
                  ))}
                </div>
              )}
            </FormSection>
          )}
        </div>
      )}

      {/* API Marketplace Section */}
      {models.includes('paid') && (
        <div className="space-y-4">
          <div className="flex items-center space-x-3 mt-6">
            <Code className="w-6 h-6 text-orange-500 flex-shrink-0" />
            <h3 className="text-xl font-semibold text-theme-primary">{t('apiMarketplace')}</h3>
          </div>

          <FormSection
            title={t('plans.title')}
            icon={CreditCard}
            iconColor="text-yellow-500"
            collapsible
            isExpanded={props.sectionsExpanded.plans}
            onToggle={() => props.toggleSection('plans')}
          >
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center space-x-2">
                <button
                  type="button"
                  onClick={() => props.setShowPlansInfo(!props.showPlansInfo)}
                  className="text-theme-muted hover:text-theme-primary transition-colors"
                >
                  <Info className="w-5 h-5" />
                </button>
              </div>
            </div>

            {props.showPlansInfo && (
              <div className="border border-blue-200 dark:border-blue-700 rounded-lg p-4 bg-blue-50 dark:bg-blue-900/20">
                <div className="flex items-start space-x-3">
                  <Info className="w-5 h-5 text-blue-600 dark:text-blue-400 mt-0.5 flex-shrink-0" />
                  <div className="flex-1">
                    <h4 className="text-sm font-medium mb-1 text-blue-800 dark:text-blue-200">
                      {t('plans.infoTitle')}
                    </h4>
                    <div className="text-sm text-blue-700 dark:text-blue-300">
                      <div className="space-y-2">
                        <p><strong>{t('plans.customizableLabel')}</strong> {t('plans.customizableText')}</p>
                        <p><strong>{t('plans.checkboxesLabel')}</strong> {t('plans.checkboxesText')}</p>
                        <p><strong>{t('plans.defaultPricingLabel')}</strong> {t('plans.defaultPricingText')}</p>
                        <p><strong>{t('plans.hardLimitLabel')}</strong> {t('plans.hardLimitText')}</p>
                        <p><strong>{t('plans.rpsLabel')}</strong> {t('plans.rpsText')}</p>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            )}

            <div className="space-y-6 mt-4">
              {Object.entries(props.monetizationConfig.selectedPlans).map(([plan, isSelected]) => {
                const planKey = plan as keyof typeof props.monetizationConfig.selectedPlans;
                const planName = plan.charAt(0).toUpperCase() + plan.slice(1);
                const hardLimitKey = `hardLimit${planName}` as keyof MonetizationConfig;
                const hardLimit = props.monetizationConfig[hardLimitKey] as boolean;
                const planTools = props.monetizationConfig.planTools[plan as keyof typeof props.monetizationConfig.planTools] || [];
                const includeAllTools = props.monetizationConfig.planAllToolsMode?.[plan as keyof typeof props.monetizationConfig.planAllToolsMode] || false;

                return (
                  <Card key={plan} className={`p-4 border rounded-xl bg-theme-tertiary transition-all duration-200 ${
                    isSelected ? `border-orange-300` : `border-gray-300 hover:border-orange-200`
                  }`}>
                    <div className="flex items-center justify-between mb-4">
                      <div className="flex items-center space-x-3">
                        <Checkbox
                          checked={isSelected}
                          onCheckedChange={(checked) => props.handlePlanSelection(planKey, checked === true)}
                          className="border-orange-500"
                        />
                        <h4 className="text-lg font-medium text-theme-primary">{planName}</h4>
                      </div>
                    </div>

                    {isSelected && (
                      <FormGrid cols={2} className="sm:grid-cols-4">
                        {/* Monthly price */}
                        <FormField
                          label={<><span className="hidden sm:inline">{t('plans.monthlyPrice')}</span><span className="sm:hidden">{t('plans.price')}</span></>}
                        >
                          <FormInput
                            type="number"
                            value={props.monetizationConfig[`price${planName}` as keyof MonetizationConfig].toString()}
                            onChange={(value) => props.handlePlanPricingChange(planKey, 'price', parseFloat(value) || 0)}
                            min={0}
                            step={0.01}
                            className="text-sm"
                          />
                        </FormField>

                        {/* Monthly quota */}
                        <FormField
                          label={<><span className="hidden sm:inline">{t('plans.monthlyQuota')}</span><span className="sm:hidden">{t('plans.quota')}</span></>}
                        >
                          <FormInput
                            type="number"
                            value={props.monetizationConfig[`quota${planName}` as keyof MonetizationConfig].toString()}
                            onChange={(value) => props.handlePlanPricingChange(planKey, 'quota', parseInt(value) || 1000)}
                            min={0}
                            className="text-sm"
                          />
                        </FormField>

                        {/* Hard Limit and Overusage cost on same line */}
                        <FormField
                          label={t('plans.hardLimit')}
                        >
                          <FormSelect
                            value={hardLimit ? 'yes' : 'no'}
                            onChange={(value) => props.handleHardLimitChange(planKey, value === 'yes')}
                            options={[
                              { value: 'yes', label: t('common.yes') },
                              { value: 'no', label: t('common.no') }
                            ]}
                          />

                          {/* Overusage cost - displayed only if Hard Limit = No */}
                          {!hardLimit && (
                            <div className="mt-2">
                              <FormField
                                label={t('plans.overusageCost')}
                              >
                                <FormInput
                                  type="number"
                                  value={props.monetizationConfig[`overusageCost${planName}` as keyof MonetizationConfig].toString()}
                                  onChange={(value) => props.handleOverusageCostChange(planKey, parseFloat(value) || 0.01)}
                                  min={0.001}
                                  step={0.001}
                                />
                                <p className="text-xs text-theme-muted mt-1">{t('plans.perAdditionalRequest')}</p>
                              </FormField>
                            </div>
                          )}
                        </FormField>

                        {/* RPS with period */}
                        <FormField
                          label={<><span className="hidden sm:inline">{t('plans.rps')}</span><span className="sm:hidden">{t('plans.rate')}</span></>}
                        >
                          <div className="space-y-2">
                            <FormInput
                              type="number"
                              value={props.monetizationConfig[`rps${planName}` as keyof MonetizationConfig].toString()}
                              onChange={(value) => props.handlePlanPricingChange(planKey, 'rps', parseInt(value) || 10)}
                              min={0.1}
                              step={0.1}
                              className="text-sm"
                            />
                            <FormSelect
                              value={props.monetizationConfig[`rpsPeriod${planName}` as keyof MonetizationConfig] as string || 'minute'}
                              onChange={(value) => props.handleRpsPeriodChange(planKey, value as 'second' | 'minute' | 'hour' | 'day')}
                              options={props.rpsPeriodOptions}
                              className="text-sm"
                            />
                          </div>
                        </FormField>
                      </FormGrid>
                    )}

                    {/* Tool selection for this plan - displayed only if plan is selected */}
                    {isSelected && props.mcpTools.length > 0 && (
                      <div className="mt-6">
                        <div className="mb-4">
                          <h5 className="text-md font-medium text-theme-primary mb-2">{t('plans.toolsIncluded')}</h5>
                          <p className="text-sm text-theme-muted">
                            {t('plans.selectToolsForPlan', { plan: planName })}
                          </p>
                        </div>

                        {/* Toggle to include all tools in this plan */}
                        <div className="flex items-center justify-center mb-6">
                          <ToggleGroup
                            value={includeAllTools ? 'all' : 'per-tool'}
                            onValueChange={(value) => props.handlePlanIncludeAllToolsChange(plan as keyof typeof props.monetizationConfig.planTools, value === 'all')}
                            hasBorder={false}
                            options={[
                              { value: 'all', label: t('pricing.allTools') },
                              { value: 'per-tool', label: t('pricing.perTool') },
                            ]}
                          />
                        </div>

                        {/* Conditional display based on mode */}
                        {includeAllTools ? (
                          <InfoBox
                            type="info"
                            title={t('plans.allToolsIncluded', { count: props.mcpTools.length })}
                          >
                            <p>{t('plans.disableToggle')}</p>
                          </InfoBox>
                        ) : (
                          <div className="space-y-3">
                            {/* List of tools grouped by category */}
                            <div className="space-y-3">
                              {Object.entries(
                                props.mcpTools.reduce((acc, tool) => {
                                  if (!acc[tool.toolCategory]) acc[tool.toolCategory] = [];
                                  acc[tool.toolCategory].push(tool);
                                  return acc;
                                }, {} as Record<string, typeof props.mcpTools>)
                              ).map(([toolCategory, tools]) => (
                                <Card key={toolCategory} className="border border-theme rounded-xl overflow-hidden bg-theme-tertiary">
                                  <div className="bg-theme-secondary px-4 py-2 border-b border-theme">
                                    <h4 className="font-medium text-theme-primary">{toolCategory}</h4>
                                  </div>
                                  <div className="divide-y divide-theme">
                                    {tools.map((tool, index) => {
                                      const isToolSelected = planTools.includes(tool.name);

                                      return (
                                        <div key={`${toolCategory}-${tool.name}-${index}`}>
                                          {/* Desktop layout */}
                                          <div className="hidden sm:block p-3 bg-theme-primary">
                                            {/* Tool info label - compact */}
                                            <div className="mb-3">
                                              <h5 className="font-medium text-theme-primary text-sm">{tool.name}</h5>
                                              <p className="text-xs text-theme-muted font-mono mt-0.5">
                                                {tool.endpoint}
                                              </p>
                                            </div>
                                            {/* Checkbox - full width, grouped with tool info */}
                                            <div className="flex items-center justify-start pl-0">
                                              <label className="flex items-center space-x-2 cursor-pointer">
                                                <Checkbox
                                                  checked={isToolSelected}
                                                  onCheckedChange={(checked) => props.handlePlanToolSelection(plan as keyof typeof props.monetizationConfig.planTools, tool.name, checked === true)}
                                                  className="border-orange-500"
                                                />
                                                <span className="text-sm text-theme-primary">
                                                  {isToolSelected ? t('plans.included') : t('plans.notIncluded')}
                                                </span>
                                              </label>
                                            </div>
                                          </div>

                                          {/* Mobile layout - Vertical stacking */}
                                          <div className="sm:hidden p-3 bg-theme-primary space-y-3">
                                            {/* Tool info */}
                                            <div>
                                              <h5 className="font-medium text-theme-primary">{tool.name}</h5>
                                              <p className="text-xs text-theme-muted mt-1 font-mono">
                                                {tool.endpoint}
                                              </p>
                                            </div>

                                            {/* Selection checkbox */}
                                            <label className="flex items-center space-x-3 cursor-pointer p-2 rounded border border-theme hover:bg-theme-secondary/50 transition-colors">
                                              <Checkbox
                                                checked={isToolSelected}
                                                onCheckedChange={(checked) => props.handlePlanToolSelection(plan as keyof typeof props.monetizationConfig.planTools, tool.name, checked === true)}
                                                className="border-blue-500"
                                              />
                                              <div className="flex flex-col">
                                                <span className="text-sm font-medium text-theme-primary">
                                                  {isToolSelected ? t('plans.includedInPlan') : t('plans.notIncludedInPlan')}
                                                </span>
                                                <span className="text-xs text-theme-muted">
                                                  {isToolSelected ? t('plans.clickToRemove') : t('plans.clickToAdd')}
                                                </span>
                                              </div>
                                            </label>
                                          </div>
                                        </div>
                                      );
                                    })}
                                  </div>
                                </Card>
                              ))}
                            </div>

                          </div>
                        )}
                      </div>
                    )}
                  </Card>
                );
              })}
            </div>

            {/* Validation Warning Display - at the end of the section */}
            {!props.validation.isValid && (
              <div className="mt-6 p-4 border border-yellow-200 dark:border-yellow-700 rounded-lg bg-yellow-50 dark:bg-yellow-900/20">
                <div className="space-y-2">
                  <div className="flex items-center space-x-2">
                    <AlertCircle className="w-5 h-5 text-yellow-500" />
                    <p className="font-medium text-yellow-800 dark:text-yellow-200">
                      {t('plans.configRequired')}
                    </p>
                  </div>
                  <p className="text-sm text-yellow-700 dark:text-yellow-300">
                    {t('plans.assignToolsWarning', { count: props.validation.unassignedTools.length, tools: props.validation.unassignedTools.join(', ') })}
                  </p>
                </div>
              </div>
            )}
          </FormSection>
        </div>
      )}
    </div>
  );
};

export default PricingSections;
