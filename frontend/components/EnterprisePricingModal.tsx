'use client';
import React, { useState } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { Check, ArrowRight, MessageSquare } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { usePlans } from '@/lib/hooks/smart-hooks-complete';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useTranslations } from 'next-intl';
import { isCeMode } from '@/lib/format-cost';

interface EnterpriseOption {
  id: string;
  name: string;
  price: number;
  yearlyPrice: number;
  credits: string;
  creditPrice: string;
  features: string[];
  popular?: boolean;
}

interface EnterprisePricingModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (optionId: string, billingCycle: 'monthly' | 'yearly') => void;
  billingCycle: 'monthly' | 'yearly';
  onBillingCycleChange?: (cycle: 'monthly' | 'yearly') => void;
  currentPlan?: string;
  currentCadence?: 'monthly' | 'yearly';
}

const enterpriseOptions: EnterpriseOption[] = [
  {
    id: 'enterprise_basic',
    name: 'Basic',
    price: 500,
    yearlyPrice: 4800,
    credits: '50,000',
    creditPrice: '$0.01',
    features: [
      '50,000 credits per month',
      '500 GB storage space',
      'All MCP tools + exclusive',
      'Priority support 24/7',
      'Advanced documentation',
      'Custom integrations',
      'Detailed analytics',
      'Dedicated API',
      'Training included',
      'SLA 99.5%'
    ]
  },
  {
    id: 'enterprise_standard',
    name: 'Standard',
    price: 1000,
    yearlyPrice: 9600,
    credits: '100,000',
    creditPrice: '$0.01',
    features: [
      '100,000 credits per month',
      '1 TB storage space',
      'All MCP tools + exclusive',
      'Priority support 24/7',
      'Advanced documentation',
      'Custom integrations',
      'Detailed analytics',
      'Dedicated API',
      'Training included',
      'SLA 99.7%',
      'Dedicated account manager'
    ],
    popular: true
  },
  {
    id: 'enterprise_premium',
    name: 'Premium',
    price: 2000,
    yearlyPrice: 19200,
    credits: '250,000',
    creditPrice: '$0.008',
    features: [
      '250,000 credits per month',
      '2.5 TB storage space',
      'All MCP tools + exclusive',
      'Priority support 24/7',
      'Advanced documentation',
      'Custom integrations',
      'Detailed analytics',
      'Dedicated API',
      'Training included',
      'SLA 99.8%',
      'Dedicated account manager',
      'Custom training sessions'
    ]
  },
  {
    id: 'enterprise_ultimate',
    name: 'Ultimate',
    price: 5000,
    yearlyPrice: 48000,
    credits: '500,000',
    creditPrice: '$0.01',
    features: [
      '500,000 credits per month',
      '5 TB storage space',
      'All MCP tools + exclusive',
      'Priority support 24/7',
      'Advanced documentation',
      'Custom integrations',
      'Detailed analytics',
      'Dedicated API',
      'Training included',
      'SLA 99.9%',
      'Dedicated account manager',
      'Custom training sessions',
      'White-label options'
    ]
  }
];

const EnterprisePricingModal = React.memo(function EnterprisePricingModal({
  isOpen,
  onClose,
  onSelect,
  billingCycle,
  onBillingCycleChange,
  currentPlan = 'FREE',
  currentCadence = 'monthly'
}: EnterprisePricingModalProps) {
  const t = useTranslations('modals.enterprise');
  const tBilling = useTranslations('pricing.billing');
  const { plans: dbPlans } = usePlans();
  const { isAuthenticated } = useAuthGuard();
  const [selectedOption, setSelectedOption] = useState<string | null>(null);

  // Slider animation (Pricing/Settings style)
  const tabContainerRef = React.useRef<HTMLDivElement>(null);
  const [tabSliderStyle, setTabSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  React.useEffect(() => {
    const updateSlider = () => {
      if (!tabContainerRef.current) return;
      const activeButton = tabContainerRef.current.querySelector(`[data-tab-id="${billingCycle}"]`) as HTMLButtonElement;
      if (activeButton) {
        const containerRect = tabContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setTabSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };

    if (isOpen) {
      // Small delay to let the modal finish rendering
      setTimeout(updateSlider, 50);
    }
  }, [billingCycle, isOpen]);

  // Reset selection when modal opens
  React.useEffect(() => {
    if (isOpen) {
      setSelectedOption(null);
    }
  }, [isOpen]);

  // Function to get dynamic price from context
  const getDynamicPrice = (planCode: string, cycle: 'monthly' | 'yearly') => {
    if (!dbPlans || !Array.isArray(dbPlans)) {
      return { amount: 0, currency: 'USD' };
    }

    const plan = dbPlans.find((p: any) => p.code === planCode);
    if (!plan || !(plan as any).prices?.[cycle]) {
      return { amount: 0, currency: 'USD' };
    }

    const price = (plan as any).prices[cycle];
    return {
      amount: price?.amount_dollars || 0,
      currency: price?.currency || 'USD'
    };
  };

  // Function to format price using context or static prices
  const formatPrice = (planCode: string, cycle: 'monthly' | 'yearly') => {
    // Default prices for all users (authenticated or not)
    const defaultPrices = {
      'ENTERPRISE_BASIC': { monthly: 500, yearly: 400 },
      'ENTERPRISE_STANDARD': { monthly: 1000, yearly: 800 },
      'ENTERPRISE_PREMIUM': { monthly: 2000, yearly: 1600 },
      'ENTERPRISE_ULTIMATE': { monthly: 5000, yearly: 4000 }
    };

    // If user is authenticated, try fetching DB prices
    if (isAuthenticated) {
      const price = getDynamicPrice(planCode, cycle);

      if (price.amount > 0) {
        if (cycle === 'yearly') {
          // Convert yearly price to monthly equivalent
          return Math.round(price.amount / 12);
        }
        return price.amount;
      }
    }

    // Fallback to default prices
    const defaultPrice = defaultPrices[planCode as keyof typeof defaultPrices];
    if (defaultPrice) {
      return cycle === 'monthly' ? defaultPrice.monthly : defaultPrice.yearly;
    }

    return 0;
  };

  const handleSelect = (optionId: string) => {
    setSelectedOption(optionId);
    onSelect(optionId, billingCycle);
    onClose();
  };

  return (
    <Dialog open={isOpen} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-6xl w-full max-h-[95vh] sm:max-h-[90vh] overflow-y-auto p-0 gap-0">
        {/* Header with Billing Cycle Toggle */}
        <div className="flex items-center justify-center p-4 sm:p-6 border-b border-theme relative">
          <div className="flex items-center">
            <div className="relative inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-full" ref={tabContainerRef}>
              {/* Slider highlight */}
              <div
                className="absolute top-1.5 bottom-1.5 rounded-full bg-theme-primary transition-all duration-300 ease-out"
                style={{
                  left: tabSliderStyle.left,
                  width: tabSliderStyle.width,
                  opacity: tabSliderStyle.width ? 1 : 0
                }}
              />

              {[
                { value: 'monthly', label: t('monthly') },
                { value: 'yearly', label: t('yearly'), badge: '-20%' }
              ].map((option) => {
                const isActive = billingCycle === option.value;
                return (
                  <button
                    key={option.value}
                    data-tab-id={option.value}
                    onClick={() => onBillingCycleChange?.(option.value as 'monthly' | 'yearly')}
                    className={`relative z-10 flex items-center justify-center px-6 py-2 rounded-full text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none ${isActive
                      ? "text-theme-primary"
                      : "text-theme-secondary hover:text-theme-primary hover:bg-theme-primary/50"
                      }`}
                  >
                    {option.label}
                    {option.badge && (
                      <span className={`ml-2 px-2 py-0.5 text-[10px] font-bold rounded-full transition-colors ${isActive ? 'bg-green-100 text-green-800' : 'bg-green-100/50 text-green-700/70'
                        }`}>
                        {option.badge}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </div>
        </div>

        {/* Enterprise Plan Card */}
        <div className="p-4 sm:p-6">
          <div className="max-w-md mx-auto">
            <div className="p-4 sm:p-8 border border-theme rounded-3xl">
              <div className="text-center pb-4 sm:pb-6">
                <h2 className="text-xl sm:text-2xl font-bold text-theme-primary mb-2 transition-colors duration-300">
                  {t('title')}
                </h2>
                <p className="text-sm sm:text-base text-theme-secondary">

                </p>
              </div>

              <div className="space-y-4 sm:space-y-6">
                {/* Price Selection */}
                <div>
                  <Select
                    value={selectedOption || ''}
                    onValueChange={(value) => {
                      setSelectedOption(value || null);
                    }}
                  >
                    <SelectTrigger className="w-full text-sm sm:text-base text-center font-semibold">
                      <SelectValue placeholder={t('selectPricing')} />
                    </SelectTrigger>
                    <SelectContent>
                      {enterpriseOptions.map((option) => {
                        const planCode = option.id.toUpperCase(); // 'enterprise_basic' -> 'ENTERPRISE_BASIC'
                        const currentPrice = formatPrice(planCode, billingCycle);
                        // Check both the plan code AND the cadence for enterprise plans
                        const isCurrentPlan = currentPlan === planCode && currentCadence === billingCycle;
                        return (
                          <SelectItem key={option.id} value={option.id}>
                            {option.name} - ${currentPrice.toLocaleString(getClientLocale())}/{t('month')} ({isCeMode ? `$${option.credits}` : `${option.credits} ${t('credits')}`}){isCurrentPlan ? ` - ${t('currentPlan')}` : ''}
                          </SelectItem>
                        );
                      })}
                    </SelectContent>
                  </Select>
                  <p className="mt-2 text-center text-xs sm:text-sm text-theme-secondary">
                    {tBilling('taxNote')}
                  </p>
                </div>

                {/* Features */}
                <div className="flex justify-center">
                  <ul className="space-y-2 sm:space-y-3 text-xs sm:text-sm max-w-sm">
                    {[
                      t('features.unlimitedCredits'),
                      t('features.unlimitedStorage'),
                      t('features.allTools'),
                      t('features.support247'),
                      t('features.customTraining'),
                      t('features.integrations'),
                      t('features.sla'),
                      t('features.privateDeployment'),
                      t('features.customApi'),
                      t('features.userManagement'),
                      t('features.auditCompliance')
                    ].map((feature, index) => (
                      <li key={index} className="flex items-center gap-2 sm:gap-3 text-theme-secondary">
                        <div className="flex items-center justify-center w-4 h-4 sm:w-5 sm:h-5 flex-shrink-0">
                          <Check className="w-3 h-3 sm:w-4 sm:h-4 text-green-500" />
                        </div>
                        <span className="text-left">{feature}</span>
                      </li>
                    ))}
                  </ul>
                </div>

                {/* Action Button */}
                <div className="text-center">
                  <Button
                    onClick={() => selectedOption && handleSelect(selectedOption)}
                    disabled={!selectedOption}
                    variant="default"
                    className="w-full text-sm sm:text-base"
                  >
                    {selectedOption ? t('selectPlan', { name: enterpriseOptions.find(opt => opt.id === selectedOption)?.name }) : t('choosePricingFirst')}
                    {selectedOption && (
                      <ArrowRight className="w-3 h-3 sm:w-4 sm:h-4 ml-2" />
                    )}
                  </Button>
                </div>

                {/* Contact Us */}
                <div className="pt-4 sm:pt-6 border-t border-theme">
                  <div className="text-center">
                    <p className="text-theme-secondary mb-3 sm:mb-4 text-xs sm:text-sm">
                      {t('customSolution')}
                    </p>
                    <Button
                      onClick={() => window.open('/contact', '_blank')}
                      variant="outline"
                      size="sm"
                    >
                      <MessageSquare className="w-3 h-3 sm:w-4 sm:h-4 mr-2" />
                      {t('contactUs')}
                    </Button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
});

export default EnterprisePricingModal;
