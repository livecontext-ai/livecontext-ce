'use client';

import React from 'react';
import { DollarSign, Edit, MessageSquare, Code } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { FormSection, TYPOGRAPHY } from '../../common';
import { MonetizationConfig, McpTool } from '../../../types';

interface Step4SummaryProps {
  monetizationConfig: MonetizationConfig;
  mcpTools: McpTool[];
  isExpanded: boolean;
  onToggle: () => void;
  onEdit: () => void;
}

export function Step4Summary({
  monetizationConfig,
  mcpTools,
  isExpanded,
  onToggle,
  onEdit
}: Step4SummaryProps) {
  const t = useTranslations('developers.confirmation');

  return (
    <FormSection
      title={t('step4.title')}
      icon={DollarSign}
      iconColor="text-yellow-500"
      collapsible
      isExpanded={isExpanded}
      onToggle={onToggle}
      actionButton={
        <Button
          onClick={onEdit}
          variant="ghost"
          size="icon"
          className="h-8 w-8"
          title={t('step4.edit')}
        >
          <Edit className="w-4 h-4" />
        </Button>
      }
    >
      <div className="space-y-4">
        {/* Selected Pricing Models */}
        <div className="p-4 border border-theme rounded-xl bg-theme-tertiary">
          <h4 className={`${TYPOGRAPHY.sectionTitle} mb-3`}>{t('step4.selectedModels')}</h4>
          <div className="space-y-3">
            <div>
              <span className={TYPOGRAPHY.description}>{t('step4.pricingApproach')}:</span>
              <div className={`${TYPOGRAPHY.value} mt-1`}>
                {monetizationConfig.selectedPricingModels && monetizationConfig.selectedPricingModels.length > 1
                  ? t('step4.hybrid')
                  : monetizationConfig.pricing === 'freemium'
                  ? t('step4.aiIntegration')
                  : t('step4.apiMarketplace')
                }
              </div>
            </div>
          </div>
        </div>

        {/* AI Integration Section (Freemium) */}
        {monetizationConfig.selectedPricingModels?.includes('freemium') && (
          <FreemiumSection monetizationConfig={monetizationConfig} mcpTools={mcpTools} />
        )}

        {/* API Marketplace Section (Paid) */}
        {monetizationConfig.selectedPricingModels?.includes('paid') && (
          <PaidSection monetizationConfig={monetizationConfig} mcpTools={mcpTools} />
        )}
      </div>
    </FormSection>
  );
}

// Freemium Section Component
interface FreemiumSectionProps {
  monetizationConfig: MonetizationConfig;
  mcpTools: McpTool[];
}

function FreemiumSection({ monetizationConfig, mcpTools }: FreemiumSectionProps) {
  const t = useTranslations('developers.confirmation');

  return (
    <div className="p-4 border border-theme rounded-xl bg-theme-tertiary">
      <h4 className={`${TYPOGRAPHY.sectionTitle} mb-3 flex items-center space-x-2`}>
        <MessageSquare className="w-5 h-5 text-purple-500" />
        <span>AI Integration (Freemium)</span>
      </h4>
      <div className="space-y-3">
        {/* Rate Limiting */}
        <RateLimitingDisplay monetizationConfig={monetizationConfig} mcpTools={mcpTools} />

        {/* Free Requests */}
        <FreeRequestsDisplay monetizationConfig={monetizationConfig} mcpTools={mcpTools} />

        {/* Pricing */}
        <PricingDisplay monetizationConfig={monetizationConfig} mcpTools={mcpTools} />
      </div>
    </div>
  );
}

// Paid Section Component
interface PaidSectionProps {
  monetizationConfig: MonetizationConfig;
  mcpTools: McpTool[];
}

function PaidSection({ monetizationConfig, mcpTools }: PaidSectionProps) {
  const t = useTranslations('developers.confirmation');

  return (
    <div className="p-4 border border-theme rounded-xl bg-theme-tertiary">
      <h4 className={`${TYPOGRAPHY.sectionTitle} mb-3 flex items-center space-x-2`}>
        <Code className="w-5 h-5 text-orange-500" />
        <span>API Marketplace (Subscription)</span>
      </h4>
      <div className="space-y-3">
        {/* Selected Plans */}
        <div>
          <span className={TYPOGRAPHY.description}>Selected plans:</span>
          <div className={`${TYPOGRAPHY.value} mt-1`}>
            {monetizationConfig.selectedPlans && typeof monetizationConfig.selectedPlans === 'object'
              ? Object.entries(monetizationConfig.selectedPlans)
                  .filter(([_, isSelected]) => isSelected)
                  .map(([plan]) => plan.charAt(0).toUpperCase() + plan.slice(1))
                  .join(', ')
              : 'No plans selected'
            }
          </div>
        </div>

        {/* Plans Details */}
        {monetizationConfig.selectedPlans &&
         typeof monetizationConfig.selectedPlans === 'object' &&
         Object.entries(monetizationConfig.selectedPlans).some(([_, isSelected]) => isSelected) && (
          <PlansDetailsDisplay monetizationConfig={monetizationConfig} mcpTools={mcpTools} />
        )}
      </div>
    </div>
  );
}

// Rate Limiting Display
function RateLimitingDisplay({ monetizationConfig, mcpTools }: { monetizationConfig: MonetizationConfig; mcpTools: McpTool[] }) {
  const t = useTranslations('developers.confirmation');

  const hasToolRateLimits = monetizationConfig.toolRateLimits && Object.keys(monetizationConfig.toolRateLimits).length > 0;

  if (hasToolRateLimits) {
    const rateLimitedTools = Object.keys(monetizationConfig.toolRateLimits!).filter(key =>
      !key.includes('_type') && mcpTools.some(tool => tool.name === key)
    );

    return (
      <div>
        <span className={TYPOGRAPHY.description}>Rate limiting:</span>
        <div className={`${TYPOGRAPHY.value} mt-1`}>
          <div className="mt-2 space-y-2">
            {rateLimitedTools.map(toolName => {
              const limitValue = monetizationConfig.toolRateLimits![toolName];
              const limit = typeof limitValue === 'number' ? limitValue : (limitValue && typeof limitValue === 'object' && limitValue.requests) ? limitValue.requests : 0;
              const period = (limitValue && typeof limitValue === 'object' && limitValue.period) ? limitValue.period : 'month';
              const safeLimit = Number.isFinite(limit) ? limit : 0;
              const safePeriod = typeof period === 'string' ? period : 'month';

              const tool = mcpTools.find(t => t.name === toolName);
              return (
                <div key={toolName} className="text-xs bg-theme-background p-3 rounded border border-theme">
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-theme-primary font-medium text-sm">{toolName}</span>
                    <span className="text-theme-muted">{safeLimit} requests/{safePeriod}</span>
                  </div>
                  <div className="text-theme-muted">
                    {tool ? `${tool.method} ${tool.endpoint}` : 'No endpoint'}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    );
  }

  if (!monetizationConfig.rateLimit) {
    return (
      <div>
        <span className={TYPOGRAPHY.description}>Rate limiting:</span>
        <div className={`${TYPOGRAPHY.value} mt-1`}>Not configured</div>
      </div>
    );
  }

  const rateLimit = monetizationConfig.rateLimit;
  if (typeof rateLimit === 'object' && rateLimit.requests && rateLimit.period) {
    return (
      <div>
        <span className={TYPOGRAPHY.description}>Rate limiting:</span>
        <div className={`${TYPOGRAPHY.value} mt-1`}>{rateLimit.requests} requests per {rateLimit.period}</div>
      </div>
    );
  }

  return (
    <div>
      <span className={TYPOGRAPHY.description}>Rate limiting:</span>
      <div className={`${TYPOGRAPHY.value} mt-1`}>Invalid rate limit configuration</div>
    </div>
  );
}

// Free Requests Display
function FreeRequestsDisplay({ monetizationConfig, mcpTools }: { monetizationConfig: MonetizationConfig; mcpTools: McpTool[] }) {
  const t = useTranslations('developers.confirmation');

  const hasToolFreeRequests = monetizationConfig.toolFreeRequests && Object.keys(monetizationConfig.toolFreeRequests).length > 0;

  if (hasToolFreeRequests) {
    const freeRequestTools = Object.keys(monetizationConfig.toolFreeRequests!).filter(k => !k.endsWith('_type'));

    return (
      <div>
        <span className={TYPOGRAPHY.description}>Free requests:</span>
        <div className={`${TYPOGRAPHY.value} mt-1`}>
          <div className="mt-2 space-y-2">
            {freeRequestTools.map(toolName => {
              const freeRequestsValue = monetizationConfig.toolFreeRequests![toolName];
              const type = monetizationConfig.toolFreeRequests![`${toolName}_type`] || 'per-user';
              const freeRequests = typeof freeRequestsValue === 'number' ? freeRequestsValue : 0;
              const safeType = typeof type === 'string' ? type : 'per-user';
              const typeText = safeType === 'per-user' ? 'per user' : safeType === 'global' ? 'global' : safeType;

              return (
                <div key={toolName} className="text-xs bg-theme-background p-3 rounded border border-theme">
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-theme-primary font-medium text-sm">{toolName}</span>
                    <span className="text-theme-muted">{freeRequests} requests/{typeText}/month</span>
                  </div>
                  <div className="text-theme-muted">
                    {(() => {
                      const tool = mcpTools.find(t => t.name === toolName);
                      return tool ? `${tool.method} ${tool.endpoint}` : 'No endpoint';
                    })()}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div>
      <span className={TYPOGRAPHY.description}>Free requests:</span>
      <div className={`${TYPOGRAPHY.value} mt-1`}>
        {typeof monetizationConfig.freeRequestsPerUser === 'number' ? monetizationConfig.freeRequestsPerUser : 'Unlimited'} requests per user
      </div>
    </div>
  );
}

// Pricing Display
function PricingDisplay({ monetizationConfig, mcpTools }: { monetizationConfig: MonetizationConfig; mcpTools: McpTool[] }) {
  const t = useTranslations('developers.confirmation');

  if (typeof monetizationConfig.uniformToolPrice === 'number' && monetizationConfig.uniformToolPrice > 0) {
    const pricePerCall = ((monetizationConfig.uniformToolPrice * 0.005) / (typeof monetizationConfig.uniformCalls === 'number' ? monetizationConfig.uniformCalls : 1)).toFixed(8).replace(/\.?0+$/, '');
    return (
      <div>
        <span className={TYPOGRAPHY.description}>Pricing:</span>
        <div className={`${TYPOGRAPHY.value} mt-1`}>${pricePerCall}/call for all tools</div>
      </div>
    );
  }

  if (monetizationConfig.toolPricing && Object.keys(monetizationConfig.toolPricing).length > 0) {
    const individuallyPricedTools = Object.keys(monetizationConfig.toolPricing);

    return (
      <div>
        <span className={TYPOGRAPHY.description}>Pricing:</span>
        <div className={`${TYPOGRAPHY.value} mt-1`}>
          <div className="mt-2 space-y-2">
            {individuallyPricedTools.map(toolName => {
              const pricing = monetizationConfig.toolPricing![toolName];
              if (!pricing || typeof pricing !== 'object') {
                return (
                  <div key={toolName} className="text-xs bg-theme-background p-3 rounded border border-theme">
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-theme-primary font-medium text-sm">{toolName}</span>
                      <span className="text-theme-muted">Invalid pricing</span>
                    </div>
                  </div>
                );
              }

              const safeMauValue = typeof pricing.mauValue === 'number' ? pricing.mauValue : 1;
              const safeCalls = typeof pricing.calls === 'number' ? pricing.calls : 1;
              const pricePerCall = ((safeMauValue * 0.005) / safeCalls).toFixed(8).replace(/\.?0+$/, '');

              return (
                <div key={toolName} className="text-xs bg-theme-background p-3 rounded border border-theme">
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-theme-primary font-medium text-sm">{toolName}</span>
                    <span className="text-theme-muted">${pricePerCall}/call</span>
                  </div>
                  <div className="text-theme-muted">
                    {(() => {
                      const tool = mcpTools.find(t => t.name === toolName);
                      return tool ? `${tool.method} ${tool.endpoint}` : `No endpoint found for ${toolName}`;
                    })()}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div>
      <span className={TYPOGRAPHY.description}>Pricing:</span>
      <div className={`${TYPOGRAPHY.value} mt-1`}>No pricing configured</div>
    </div>
  );
}

// Plans Details Display
function PlansDetailsDisplay({ monetizationConfig, mcpTools }: { monetizationConfig: MonetizationConfig; mcpTools: McpTool[] }) {
  const selectedPlans = Object.entries(monetizationConfig.selectedPlans || {})
    .filter(([_, isSelected]) => isSelected)
    .map(([plan]) => plan);

  return (
    <div className="mt-3 space-y-2">
      {selectedPlans.map(plan => {
        const planName = plan.charAt(0).toUpperCase() + plan.slice(1);
        const price = monetizationConfig[`price${planName}` as keyof MonetizationConfig] as number;
        const quota = monetizationConfig[`quota${planName}` as keyof MonetizationConfig] as number;
        const hardLimit = monetizationConfig[`hardLimit${planName}` as keyof MonetizationConfig] as boolean;
        const rps = monetizationConfig[`rps${planName}` as keyof MonetizationConfig] as number;
        const rpsPeriod = monetizationConfig[`rpsPeriod${planName}` as keyof MonetizationConfig] as string;
        const overusageCost = monetizationConfig[`overusageCost${planName}` as keyof MonetizationConfig] as number;
        const includedToolIds = monetizationConfig.planTools?.[plan as keyof typeof monetizationConfig.planTools] || [];
        const includedTools = mcpTools.filter(tool => includedToolIds.includes(tool.name));

        return (
          <div key={plan} className="text-xs bg-theme-background p-4 rounded border border-theme">
            <div className="flex items-center justify-between mb-3">
              <span className="text-theme-primary font-medium text-sm">{planName}</span>
              <span className="text-theme-muted">${price || 0}/month</span>
            </div>

            <div className="space-y-2">
              <div className="flex justify-between text-xs">
                <span className="text-theme-muted">Quota:</span>
                <span className="text-theme-muted">{quota || 0} requests/month</span>
              </div>

              <div className="flex justify-between text-xs">
                <span className="text-theme-muted">Rate limit:</span>
                <span className="text-theme-muted">{rps || 0} requests/{rpsPeriod || 'second'}</span>
              </div>

              <div className="flex justify-between text-xs">
                <span className="text-theme-muted">Hard limit:</span>
                <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                  hardLimit ? 'bg-red-100 text-red-800' : 'bg-green-100 text-green-800'
                }`}>
                  {hardLimit ? 'Yes' : 'No'}
                </span>
              </div>

              {!hardLimit && (
                <div className="flex justify-between text-xs">
                  <span className="text-theme-muted">Overusage cost:</span>
                  <span className="text-theme-muted">${overusageCost || 0}/request</span>
                </div>
              )}

              <div className="pt-2 border-t border-theme-tertiary">
                <div className="text-theme-muted text-xs">
                  {Array.isArray(includedToolIds) && includedToolIds.length > 0
                    ? includedTools.length === 1
                      ? `${includedTools[0].name} tool included`
                      : `${includedTools.length} tools included: ${includedTools.map(tool => tool.name).join(', ')}`
                    : 'No tools assigned'
                  }
                </div>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
