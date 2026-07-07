'use client';
import React, { useState, useEffect, useCallback, useRef } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import Link from 'next/link';
import { TYPOGRAPHY } from '@/lib/typography';
import { Zap, Database, Clock, Check, ArrowRight } from 'lucide-react';
import PlanSelector from '@/components/pricing/PlanSelector';
import TopUpModal from '@/components/billing/TopUpModal';
import { usePaygTiers } from '@/lib/hooks/smart-hooks-complete';
import { isCeMode } from '@/lib/format-cost';
import Notification from '@/components/common/Notification';
import { useSubscription } from '@/lib/hooks/smart-hooks-complete';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { usePlans } from '@/lib/hooks/smart-hooks-complete';
import EnterprisePricingModal from '@/components/EnterprisePricingModal';
import UpgradeModal, { UpgradeModalState } from '@/components/UpgradeModal';
import { useSearchParams, useRouter } from 'next/navigation';
import Toast from '@/components/Toast';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';
import { ScheduledChangeAlert, DowngradeConfirmModal, BillingCycleChangeModal, CreditChangeModal } from '@/components/billing';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useTranslations, useLocale } from 'next-intl';
import { CREDIT_TIERS, BASE_PRICES, STARTER_MAX_CREDITS, calcPrice as calcPriceBase, formatTierLabel, getCreditCost, resolveMaxTierIndex, clampTierIndex, PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { cloudLinkService, type CloudLinkStatus, CLOUD_NO_SUBSCRIPTION } from '@/lib/api/cloud-link.service';

// CE installs manage billing on the LINKED LiveContext Cloud account; the cloud web app lives
// here (matches the hardcoded cloud host used elsewhere in CE, e.g. marketplace CategoryFilter).
const CLOUD_WEB_BASE = 'https://livecontext.ai';


export default function PricingPage() {
  const t = useTranslations('pricing');
  const tCards = useTranslations('pricing.planCards');
  const locale = useLocale();
  const [billingCycle, setBillingCycle] = useState<'monthly' | 'yearly'>('yearly');
  const [creditTierIndex, setCreditTierIndex] = useState(0);
  // Hidden tiers (5M / 10M) are revealed via ?tiers=full (persisted in localStorage).
  const [fullTiersUnlocked, setFullTiersUnlocked] = useState(false);
  const creditAmount = CREDIT_TIERS[creditTierIndex];

  const calcPrice = (planId: string, cycle: 'monthly' | 'yearly') => {
    return calcPriceBase(planId, cycle, creditTierIndex);
  };

  // Translate a plan's ordered feature keys into display strings. The 'creditsDynamic'
  // sentinel is interpolated with the live slider amount; every other key is a static label.
  const featuresFor = (planId: string): string[] =>
    (PLAN_FEATURE_KEYS[planId] || []).map((key) => {
      if (key === 'creditsDynamic') {
        return tCards('features.creditsPerMonth', { credits: creditAmount.toLocaleString(getClientLocale()) });
      }
      // Free monthly credits carry an info tooltip explaining they run workflows
      // only (chat/agents need a paid plan), via the "label||tooltip" convention.
      if (key === 'creditsFree') {
        return `${tCards('features.creditsFree')}||${tCards('features.creditsFreeTooltip')}`;
      }
      // Managed integration credentials for cloud-linked self-hosted installs carry
      // an info tooltip (relay + per-call credit markup), same "label||tooltip" convention.
      if (key === 'cePlatformCreds') {
        return `${tCards('features.cePlatformCreds')}||${tCards('features.cePlatformCredsTooltip')}`;
      }
      return tCards(`features.${key}`);
    });

  const [notification, setNotification] = useState<{
    type: 'success' | 'error';
    message: string;
    isVisible: boolean;
  } | null>(null);
  const [billingStatus, setBillingStatus] = useState<{
    hasActiveSubscription: boolean;
    canUpgrade: boolean;
    currentPlan: string;
  } | null>(null);
  const [isLoadingBillingStatus, setIsLoadingBillingStatus] = useState(true);
  const { createSubscription, subscription, isLoading: subscriptionLoading, isProcessingCheckout, forceLoadSubscription } = useSubscription();

  // Type assertion pour subscription
  const typedSubscription = subscription as any;

  // CE: the plan that governs this install comes from the LINKED CLOUD ACCOUNT, not a local Stripe
  // subscription (CE has none - its local plan is always FREE). cloudLinkStatus.cloudPlanCode mirrors
  // the backend EffectivePlanResolver, so the grid highlights the same plan the cloud actually bills.
  const [cloudLinkStatus, setCloudLinkStatus] = useState<CloudLinkStatus | null>(null);

  // Determine if the credit slider matches the subscription's credit tier
  // Baseline credit tier the current plan sits on: in CE it's the LINKED cloud subscription's tier
  // (cloudCreditTierIndex), so the slider syncs to it and "changed" is detected against the cloud.
  const subscriptionCreditTierIndex = isCeMode
    ? (cloudLinkStatus?.cloudCreditTierIndex ?? 0)
    : (typedSubscription?.subscription?.creditTierIndex ?? 0);
  const localPlanCode = typedSubscription?.subscription?.planCode || 'FREE';
  // Effective CE plan = the governing cloud plan when one governs, else the local plan (e.g. an
  // admin comp-grant via the team-gate path). The cloud sends '__NONE__' (CLOUD_NO_SUBSCRIPTION) for
  // a connected account with no subscription - treat that as "no cloud plan" and fall back to the
  // local plan, matching the backend EffectivePlanResolver for unlinked/BYOK/free-cloud CE.
  const governingCloudPlan = cloudLinkStatus?.cloudPlanCode && cloudLinkStatus.cloudPlanCode !== CLOUD_NO_SUBSCRIPTION
    ? cloudLinkStatus.cloudPlanCode
    : null;
  const currentPlanCode = isCeMode ? (governingCloudPlan ?? localPlanCode) : localPlanCode;
  // Slider moved off the governing subscription's credit tier → no paid plan is "current" (mirrors
  // cloud): the card de-highlights and its CTA routes the change to the cloud (CE) / checkout (cloud).
  const isCreditTierChanged = currentPlanCode !== 'FREE' && creditTierIndex !== subscriptionCreditTierIndex;
  // If credit tier differs from subscription, no paid plan should show as "Current Plan"
  const effectiveCurrentPlan = isCreditTierChanged ? 'NONE' : currentPlanCode;
  // Current cadence: in CE it's the LINKED cloud subscription's cadence (so toggling the billing
  // cycle away from it de-highlights "current"); falls back to the visible toggle when unknown.
  const effectiveCurrentCadence: 'monthly' | 'yearly' = isCeMode
    ? ((cloudLinkStatus?.cloudCadence as 'monthly' | 'yearly') || billingCycle)
    : ((typedSubscription?.subscription?.cadence as 'monthly' | 'yearly') || 'yearly');

  // Slider cap: 1M by default, full range when unlocked or when the current subscription
  // already sits on a hidden tier (avoids clamping an existing 5M/10M customer down to 1M).
  const maxTierIndex = resolveMaxTierIndex(fullTiersUnlocked, subscriptionCreditTierIndex);

  // etats pour la gestion des modals et de la logique enterprise
  const { isAuthenticated, user } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const { isUpgrade, isDowngrade, getPlanOrder, getPlansByOrder, plans: dbPlans } = usePlans();
  const [showEnterpriseModal, setShowEnterpriseModal] = useState(false);
  const [showUpgradeModal, setShowUpgradeModal] = useState(false);
  const [upgradeModalState, setUpgradeModalState] = useState<UpgradeModalState>('confirm');
  const [isProcessing, setIsProcessing] = useState(false);
  const [upgradeProcessing, setUpgradeProcessing] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' | 'info' } | null>(null);
  const [newPlanCode, setNewPlanCode] = useState<string>('');
  const [upgradeError, setUpgradeError] = useState<string>('');

  // CE only: pull the linked cloud account's governing plan so the grid reflects it (Problem 1).
  // Not linked / unreachable → stays null → falls back to FREE.
  useEffect(() => {
    if (!isCeMode || !isAuthenticated) return;
    let cancelled = false;
    cloudLinkService.getStatus()
      .then((s) => { if (!cancelled) setCloudLinkStatus(s); })
      .catch(() => { /* not linked or cloud unreachable - keep FREE */ });
    return () => { cancelled = true; };
  }, [isAuthenticated]);

  // Router and search params for Stripe return handling
  const searchParams = useSearchParams();
  const router = useRouter();

  // Hidden-tier unlock: ?tiers=full reveals 5M/10M and is remembered in localStorage so a
  // shared link keeps working on later visits; ?tiers=default (or =hidden) re-hides them.
  useEffect(() => {
    const param = searchParams.get('tiers');
    const STORAGE_KEY = 'lc_pricing_full_tiers';
    if (param === 'full') {
      try { localStorage.setItem(STORAGE_KEY, '1'); } catch { /* ignore */ }
      setFullTiersUnlocked(true);
    } else if (param === 'default' || param === 'hidden') {
      try { localStorage.removeItem(STORAGE_KEY); } catch { /* ignore */ }
      setFullTiersUnlocked(false);
    } else {
      let stored = false;
      try { stored = localStorage.getItem(STORAGE_KEY) === '1'; } catch { /* ignore */ }
      if (stored) setFullTiersUnlocked(true);
    }
  }, [searchParams]);

  // Keep the selected tier within the cap. If the cap shrinks (hidden tiers re-hidden while the
  // user is parked on 5M/10M), follow it so the price/checkout never reflect a hidden tier.
  useEffect(() => {
    if (creditTierIndex > maxTierIndex) {
      setCreditTierIndex(maxTierIndex);
    }
  }, [maxTierIndex, creditTierIndex]);
  // New: Downgrade modal state
  const [showDowngradeModal, setShowDowngradeModal] = useState(false);
  const [downgradePlan, setDowngradePlan] = useState<{ code: string; name: string; price?: number } | null>(null);
  const [refreshScheduledChange, setRefreshScheduledChange] = useState(0);
  // New: Billing cycle change modal state
  const [showBillingCycleModal, setShowBillingCycleModal] = useState(false);
  // Credit tier change modal state
  const [showCreditChangeModal, setShowCreditChangeModal] = useState(false);
  const [creditChangeInfo, setCreditChangeInfo] = useState<{
    planName: string; currentCredits: number; currentCost: number;
    newCredits: number; newCost: number; billingCycle: 'monthly' | 'yearly'; isTeam: boolean;
    planId: string; currentPeriodEnd?: string;
    // Credit-pack alone (no plan); modal computes one-shot charge x12 for yearly.
    // Without this, modal falls back to (newCost - currentCost) delta which understates
    // tier > 0 upgrades. Set by upgrade flow at line ~621.
    newCreditCost?: number;
  } | null>(null);
  const [creditChangeLoading, setCreditChangeLoading] = useState(false);
  const [creditChangeSuccess, setCreditChangeSuccess] = useState(false);
  const [creditChangeSuccessDate, setCreditChangeSuccessDate] = useState<string | null>(null);
  const [creditChangeError, setCreditChangeError] = useState<string | null>(null);

  // PAYG top-up modal - opened from a PAYG tier card. The optional
  // preselected tier shortens the path by one click.
  const [showTopUpModal, setShowTopUpModal] = useState(false);
  const [topUpInitialTier, setTopUpInitialTier] = useState<'small' | 'medium' | 'large' | undefined>(undefined);

  // Top-level pricing mode toggle - swaps the page between the subscription
  // plan grid (with its billing-cycle + credit-slider chrome) and the
  // one-time PAYG tier grid. Default 'subscription' (the dominant use case).
  // CE deployments lack Stripe wiring so the toggle is hidden and we lock to
  // subscription (the CE plan grid still shows Free/Team/Enterprise upsell).
  const [pricingMode, setPricingMode] = useState<'subscription' | 'payg'>('subscription');
  const { tiers: paygTiers, configured: paygConfigured, isLoading: paygTiersLoading } = usePaygTiers({
    enabled: pricingMode === 'payg',
  });
  const modeTabContainerRef = useRef<HTMLDivElement>(null);
  const [modeSliderStyle, setModeSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  React.useEffect(() => {
    const updateModeSlider = () => {
      if (!modeTabContainerRef.current) return;
      const activeButton = modeTabContainerRef.current.querySelector(`[data-mode-id="${pricingMode}"]`) as HTMLButtonElement;
      if (activeButton) {
        const containerRect = modeTabContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setModeSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };
    const timer = setTimeout(updateModeSlider, 50);
    window.addEventListener('resize', updateModeSlider);
    return () => {
      window.removeEventListener('resize', updateModeSlider);
      clearTimeout(timer);
    };
  }, [pricingMode]);

  // Animation du slider pour le billing cycle (style Settings)
  const tabContainerRef = useRef<HTMLDivElement>(null);
  const [tabSliderStyle, setTabSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  useEffect(() => {
    const updateSlider = () => {
      if (!tabContainerRef.current) return;
      // On cherche le bouton actif par son data-attribute
      const activeButton = tabContainerRef.current.querySelector(`[data-tab-id="${billingCycle}"]`) as HTMLButtonElement;
      if (activeButton) {
        const containerRect = tabContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        // Calcul relatif
        setTabSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };

    // Petit délai pour assurer le rendu
    const timer = setTimeout(updateSlider, 50);
    window.addEventListener('resize', updateSlider);
    return () => {
      window.removeEventListener('resize', updateSlider);
      clearTimeout(timer);
    };
  }, [billingCycle]);

  // Fonction pour mapper dynamiquement les plans basee sur les donnees du backend
  const getPlanMapping = useCallback(() => {
    if (!dbPlans || typeof dbPlans !== 'object') {
      // Fallback si les plans ne sont pas encore charges
      return {
        'free': 'FREE',
        'starter': 'STARTER',
        'pro': 'PRO',
        'team': 'TEAM'
      };
    }

    // Creer un mapping dynamique base sur les plans du backend
    const mapping: { [key: string]: string } = {};

    // Mapping des IDs frontend vers les codes backend
    const frontendToBackendMapping: { [key: string]: string } = {
      'free': 'FREE',
      'starter': 'STARTER',
      'pro': 'PRO',
      'team': 'TEAM'
    };

    // Ajouter les plans enterprise dynamiquement
    Object.keys(dbPlans).forEach(planCode => {
      if (planCode.startsWith('ENTERPRISE_')) {
        const frontendId = planCode.toLowerCase().replace('enterprise_', 'enterprise_');
        mapping[frontendId] = planCode;
      }
    });

    // Ajouter les plans de base
    Object.entries(frontendToBackendMapping).forEach(([frontendId, backendCode]) => {
      if (dbPlans && Array.isArray(dbPlans) && dbPlans.find((p: any) => p.code === backendCode)) {
        mapping[frontendId] = backendCode;
      }
    });

    return mapping;
  }, [dbPlans]);

  // Initialize billingCycle and creditTierIndex based on current subscription
  // Only sync from subscription for PAID users - FREE users keep the default 'yearly'
  useEffect(() => {
    // CE: sync the slider + billing toggle from the LINKED cloud subscription (its tier/cadence) so
    // the pricing page opens aligned with what the cloud account is actually on.
    if (isCeMode) {
      if (cloudLinkStatus?.cloudCadence) {
        setBillingCycle(cloudLinkStatus.cloudCadence as 'monthly' | 'yearly');
      }
      if (cloudLinkStatus?.cloudCreditTierIndex !== undefined && cloudLinkStatus.cloudCreditTierIndex !== null) {
        setCreditTierIndex(cloudLinkStatus.cloudCreditTierIndex);
      }
      return;
    }
    const planCode = typedSubscription?.subscription?.planCode;
    const isPaid = planCode && planCode !== 'FREE';
    if (isPaid && typedSubscription?.subscription?.cadence) {
      setBillingCycle(typedSubscription.subscription.cadence);
    }
    if (isPaid && typedSubscription?.subscription?.creditTierIndex !== undefined && typedSubscription.subscription.creditTierIndex > 0) {
      setCreditTierIndex(typedSubscription.subscription.creditTierIndex);
    }
  }, [cloudLinkStatus?.cloudCadence, cloudLinkStatus?.cloudCreditTierIndex, typedSubscription?.subscription?.cadence, typedSubscription?.subscription?.creditTierIndex, typedSubscription?.subscription?.planCode]);

  // Callback to notify plan change after successful upgrade
  const handlePlanChanged = React.useCallback((newPlan: string) => {
    // Force typedSubscription reload to update interface
    setTimeout(() => {
      forceLoadSubscription();
    }, 1000);
  }, [typedSubscription?.subscription?.planCode, forceLoadSubscription]);

  // Fonction utilitaire pour afficher les toasts
  const showToast = React.useCallback((message: string, type: 'success' | 'error' | 'info') => {
    setToast({ message, type });
  }, []);

  // Fonction pour fermer les toasts
  const closeToast = React.useCallback(() => {
    setToast(null);
  }, []);

  // Track if we're currently polling for webhook confirmation
  const [isPollingWebhook, setIsPollingWebhook] = useState(false);
  const [planBeforeCheckout, setPlanBeforeCheckout] = useState<string | null>(null);

  // Detect Stripe return from URL params - only trigger once
  React.useEffect(() => {
    const checkoutStatus = searchParams.get('checkout');
    const sessionId = searchParams.get('session_id');

    if (checkoutStatus === 'success' && sessionId && !isPollingWebhook) {
      // Store current plan before we start polling
      const currentPlan = typedSubscription?.subscription?.planCode || 'FREE';
      setPlanBeforeCheckout(currentPlan);
      setIsPollingWebhook(true);
      setShowUpgradeModal(true);
      setUpgradeModalState('processing');

      // Clean up URL immediately (stay on same page, just remove query params)
      window.history.replaceState({}, '', window.location.pathname);
    } else if (checkoutStatus === 'cancelled') {
      // User cancelled checkout
      showToast('Checkout cancelled', 'info');
      // Clean up URL (stay on same page, just remove query params)
      window.history.replaceState({}, '', window.location.pathname);
    }
  }, [searchParams, showToast, isPollingWebhook, typedSubscription?.subscription?.planCode]);

  // Poll for webhook confirmation when isPollingWebhook is true
  React.useEffect(() => {
    if (!isPollingWebhook || !planBeforeCheckout) return;

    let isCancelled = false;
    let attempts = 0;
    const MAX_ATTEMPTS = 30; // 30 attempts = 60 seconds max
    const POLL_INTERVAL = 2000; // 2 seconds between polls

    const poll = async () => {
      while (!isCancelled && attempts < MAX_ATTEMPTS) {
        attempts++;
        console.log(`[Stripe] Polling subscription... attempt ${attempts}/${MAX_ATTEMPTS}`);

        try {
          await forceLoadSubscription();

          // Wait for state to update
          await new Promise(resolve => setTimeout(resolve, 1000));

          // We'll check in the next effect cycle if the plan changed
          // For now, just wait and repeat
          await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL));
        } catch (error) {
          console.error('[Stripe] Error polling subscription:', error);
          await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL));
        }
      }

      // If we exit the loop without success, show error
      if (!isCancelled && attempts >= MAX_ATTEMPTS) {
        setUpgradeModalState('error');
        setUpgradeError('Payment processing is taking longer than expected. Please refresh the page or contact support if the issue persists.');
        setIsPollingWebhook(false);
      }
    };

    poll();

    return () => {
      isCancelled = true;
    };
  }, [isPollingWebhook, planBeforeCheckout, forceLoadSubscription]);

  // Watch for subscription plan change while polling
  React.useEffect(() => {
    if (!isPollingWebhook || !planBeforeCheckout) return;

    const currentPlan = typedSubscription?.subscription?.planCode;

    // Check if plan has changed (webhook processed)
    if (currentPlan && currentPlan !== planBeforeCheckout) {
      console.log(`[Stripe] Webhook confirmed! Plan changed: ${planBeforeCheckout} -> ${currentPlan}`);
      // Update newPlanCode with the actual new plan from subscription
      setNewPlanCode(currentPlan);
      setUpgradeModalState('success');
      setIsPollingWebhook(false);
      setPlanBeforeCheckout(null);
    }
  }, [isPollingWebhook, planBeforeCheckout, typedSubscription?.subscription?.planCode]);

  // Force refresh typedSubscription data when user returns to page - OPTIMISe
  React.useEffect(() => {
    const handleFocus = () => {
      // Seulement si on a vraiment besoin de rafraîchir
      if (typedSubscription && !subscriptionLoading) {
        forceLoadSubscription();
      }
    };

    window.addEventListener('focus', handleFocus);
    return () => window.removeEventListener('focus', handleFocus);
  }, [typedSubscription?.id, forceLoadSubscription, subscriptionLoading]); // Ajouter subscriptionLoading pour eviter les refetch inutiles

  // Afficher l'ordre des plans au chargement pour debug
  React.useEffect(() => {
    const plans = getPlansByOrder();
    if (plans.length > 0) {
      // Plans loaded successfully
    }
  }, [getPlansByOrder]);


  const individualPlans = [
    {
      id: 'free',
      name: tCards('free.name'),
      description: '',
      price: '0',
      period: '',
      credits: '1,000',
      storage: '100 MB',
      features: featuresFor('free'),
      cta: tCards('free.cta'),
      href: '/tools',
      popular: false,
      creditPrice: '$0',
    },
    ...(creditAmount <= STARTER_MAX_CREDITS ? [{
      id: 'starter',
      name: tCards('starter.name'),
      description: '',
      price: String(calcPrice('starter', billingCycle)),
      period: tCards('period'),
      credits: creditAmount.toLocaleString(getClientLocale()),
      storage: '1 GB',
      features: featuresFor('starter'),
      cta: tCards('starter.cta'),
      href: '/auth/signin',
      popular: true,
      creditPrice: '',
      badge: 'Recommended',
    }] : []),
    {
      id: 'pro',
      name: tCards('pro.name'),
      description: '',
      price: String(calcPrice('pro', billingCycle)),
      period: tCards('period'),
      credits: creditAmount.toLocaleString(getClientLocale()),
      storage: '10 GB',
      features: featuresFor('pro'),
      cta: tCards('pro.cta'),
      href: '/auth/signin',
      popular: creditAmount > STARTER_MAX_CREDITS,
      creditPrice: '',
      badge: creditAmount > STARTER_MAX_CREDITS ? 'Recommended' : undefined,
    },
  ];

  const businessPlans = [
    {
      id: 'team',
      name: tCards('team.name'),
      description: '',
      price: String(calcPrice('team', billingCycle)),
      period: tCards('period'),
      credits: creditAmount.toLocaleString(getClientLocale()),
      storage: '100 GB',
      features: featuresFor('team'),
      cta: tCards('team.cta'),
      href: '/auth/signin',
      popular: false,
      creditPrice: '',
    },
    {
      id: 'enterprise',
      name: tCards('enterprise.name'),
      description: '',
      price: tCards('price.contactSales'),
      period: tCards('period'),
      credits: 'Custom',
      storage: '1 TB',
      features: featuresFor('enterprise'),
      cta: tCards('enterprise.cta'),
      href: '#',
      popular: false,
      creditPrice: '',
      disabled: true,
    }
  ];

  const plans = [...individualPlans, ...businessPlans];

  // Fonction pour proceder avec la selection du plan
  const proceedWithPlanSelection = React.useCallback(async (planId: string, billingCycle: 'monthly' | 'yearly'): Promise<{ success: boolean; message?: string; error?: string }> => {
    setIsProcessing(true);

    try {
      // Mapper l'ID du plan vers le code de plan backend de maniere dynamique
      const planMapping = getPlanMapping();
      const backendPlanCode = planMapping[planId] || planId.toUpperCase();

      // Create Stripe checkout session or handle free plan
      const result = await createSubscription({
        planCode: backendPlanCode,
        billingCycle: billingCycle,
        creditTierIndex: String(creditTierIndex)
      });

      if (result) {
        // Check if it's the free plan
        if (result === 'FREE_PLAN_SELECTED' || planId === 'free') {
          showToast('Successfully downgraded to the free plan!', 'success');

          // Refresh typedSubscription after a short delay
          setTimeout(() => {
            forceLoadSubscription();
          }, 1000);

          return {
            success: true,
            message: 'Successfully downgraded to the free plan!'
          };
        } else if (result === 'SWAP_IMMEDIAT') {
          // Immediate swap performed by the backend - show success in modal
          setNewPlanCode(planId.toUpperCase());
          setUpgradeModalState('success');

          // Refresh typedSubscription after a short delay
          setTimeout(() => {
            forceLoadSubscription();
          }, 1000);

          return {
            success: true,
            message: 'Plan changed successfully!'
          };
        } else {
          // Gerer la reponse du backend pour les plans payants
          const checkoutUrl = (result as any)?.url;

          if (checkoutUrl && typeof checkoutUrl === 'string') {
            // Show processing state in modal
            setUpgradeModalState('processing');

            // Redirect to Stripe Checkout after a short delay
            setTimeout(() => {
              window.location.href = checkoutUrl;
            }, 500);
          } else {
            // Pas d'URL de checkout = swap immediat (upgrade ou credit tier change)
            setNewPlanCode(planId.toUpperCase());
            if (showUpgradeModal) {
              setUpgradeModalState('success');
            }

            // Refresh subscription after a short delay
            setTimeout(() => {
              forceLoadSubscription();
            }, 1000);
          }

          return {
            success: true,
            message: 'Redirecting to payment page...'
          };
        }
      } else {
        const errorMessage = 'Error: Unable to create payment session';
        setUpgradeModalState('error');
        setUpgradeError(errorMessage);

        return {
          success: false,
          error: errorMessage
        };
      }
    } catch (error) {
      // Show a user-friendly error
      let errorMessage = 'Error creating subscription';

      if (error instanceof Error) {
        if (error.message.includes('Plan non valide')) {
          errorMessage = 'Plan not available at the moment';
        } else if (error.message.includes('authenticated')) {
          errorMessage = 'Please sign in to continue';
        } else if (error.message.includes('Internal error')) {
          errorMessage = 'Temporary server error, please try again';
        } else {
          errorMessage = error.message;
        }
      }

      setUpgradeModalState('error');
      setUpgradeError(errorMessage);

      return {
        success: false,
        error: errorMessage
      };
    } finally {
      setIsProcessing(false);
    }
  }, [createSubscription, forceLoadSubscription, showToast, getPlanMapping, creditTierIndex, showUpgradeModal]);

  // Fonction unifiee pour la selection des plans (simple et enterprise)
  const handlePlanSelect = React.useCallback(async (planId: string, billingCycle: 'monthly' | 'yearly'): Promise<{ success: boolean; message?: string; error?: string }> => {
    try {

      // Verifier l'authentification
      if (!isAuthenticated) {
        await loginWithRedirect();
        return { success: false, error: 'Authentication required' };
      }

      // Enterprise is "talk to us", NOT self-serve, in BOTH Cloud and CE: deep-link to the
      // contact form with a predefined, localized subject. Handled BEFORE the CE branch so
      // Enterprise routes to contact in CE too (Team and below keep self-serve: Cloud checkout,
      // or the linked cloud account in CE).
      if (planId === 'enterprise') {
        const message = tCards('enterprise.contactMessage');
        router.push(`/contact?category=other&message=${encodeURIComponent(message)}`);
        return { success: true, message: 'Redirecting to contact' };
      }

      // CE (#15): the plan that governs this install comes from the LINKED CLOUD ACCOUNT - there is
      // no local subscription checkout (the CE billing endpoint returns 503). Plan actions are
      // delegated to the cloud (open its pricing page) when linked, or to cloud-account to connect
      // first when not. No-op for Cloud (isCeMode is false), so its checkout flow is unchanged.
      if (isCeMode) {
        showToast(t('cePricing.manageOnCloud'), 'info');
        // Billing lives on the linked cloud account (Problem 2). If linked, open the cloud pricing
        // page in a NEW tab to manage/pay there (CE stays open); if not yet linked, send to
        // cloud-account to connect first - a plan can't be paid without a bound cloud account.
        if (cloudLinkStatus?.linked) {
          window.open(`${CLOUD_WEB_BASE}/${locale}/app/settings/pricing`, '_blank', 'noopener,noreferrer');
        } else {
          router.push(`/${locale}/app/settings/cloud-account`);
        }
        return { success: true, message: 'Managed on the linked cloud account' };
      }

      const currentPlan = typedSubscription?.subscription?.planCode || 'FREE';
      const currentCadence = typedSubscription?.subscription?.cadence || 'yearly';

      // Gestion speciale pour le plan gratuit
      if (planId === 'free') {
        if (currentPlan === 'FREE') {
          showToast('You are already on the Free plan.', 'info');
          return { success: false, error: 'Already on free plan' };
        }
        // Continuer avec la selection du plan gratuit
      }

      // Verifier si c'est le meme plan avec la meme cadence ET le meme credit tier
      if (currentPlan === planId.toUpperCase() && currentCadence === billingCycle && !isCreditTierChanged) {
        showToast('You are already on this plan with this billing cycle.', 'info');
        return { success: false, error: 'Same plan selected' };
      }

      // Same plan + same cadence + different credit tier → open credit change modal
      if (currentPlan === planId.toUpperCase() && currentCadence === billingCycle && isCreditTierChanged) {
        const isTeam = planId.toUpperCase() === 'TEAM';
        const normalizedPlanId = planId.toLowerCase();
        const newCreditCost = getCreditCost(normalizedPlanId, creditTierIndex);
        const planData = plans.find(p => p.id === planId);
        setCreditChangeInfo({
          planName: planData?.name || planId,
          currentCredits: CREDIT_TIERS[subscriptionCreditTierIndex],
          currentCost: calcPriceBase(normalizedPlanId, billingCycle, subscriptionCreditTierIndex),
          newCredits: CREDIT_TIERS[creditTierIndex],
          newCost: calcPriceBase(normalizedPlanId, billingCycle, creditTierIndex),
          // newCreditCost is the credit pack alone (no plan). The modal uses it
          // to compute the actual one-shot charge (x12 for yearly).
          // Without this, the modal would fall back to a (newCost - currentCost)
          // delta which understates the real charge for tier > 0 upgrades.
          newCreditCost,
          billingCycle: billingCycle,
          isTeam,
          planId,
          currentPeriodEnd: typedSubscription?.subscription?.currentPeriodEnd,
        });
        setShowCreditChangeModal(true);
        return { success: true, message: 'Credit change modal opened' };
      }

      // Changement de cycle de facturation (même plan, cycle différent)
      if (currentPlan !== 'FREE' && currentPlan === planId.toUpperCase() && currentCadence !== billingCycle) {
        // Ouvrir la modal de changement de cycle
        setShowBillingCycleModal(true);
        return { success: true, message: 'Billing cycle modal opened' };
      }

      // Changement de plan ET de cycle (bloqué pour l'instant)
      if (currentPlan !== 'FREE' && currentPlan !== planId.toUpperCase() && currentCadence !== billingCycle) {
        showToast(
          `Cannot change both plan and billing cycle at once. Please first change your billing cycle, then upgrade/downgrade your plan.`,
          'error'
        );
        return { success: false, error: 'Cannot change plan and cycle together' };
      }

      // Changement de plan ET de credit tier (bloqué - forcer en deux étapes)
      if (currentPlan !== 'FREE' && currentPlan !== planId.toUpperCase() && isCreditTierChanged) {
        showToast(
          t('errors.cannotChangePlanAndCredits'),
          'error'
        );
        return { success: false, error: 'Cannot change plan and credits together' };
      }

      // Convertir le planId en majuscules pour correspondre aux codes de la base de donnees
      const normalizedPlanId = planId.toUpperCase();
      // Verifier le type de changement (upgrade/downgrade)
      const currentOrder = getPlanOrder ? getPlanOrder(currentPlan) : 'N/A';
      const targetOrder = getPlanOrder ? getPlanOrder(normalizedPlanId) : 'N/A';

      if (isUpgrade(currentPlan, normalizedPlanId)) {
        // C'est un upgrade
        if (currentPlan !== 'FREE') {
          // Afficher le modal explicatif pour tous les upgrades (monthly et yearly)
          setNewPlanCode(planId);
          setShowUpgradeModal(true);
          return { success: true, message: 'Upgrade modal opened' };
        } else {
          // Upgrade from FREE, proceed directly
          return await proceedWithPlanSelection(planId, billingCycle);
        }
      } else if (isDowngrade(currentPlan, normalizedPlanId)) {
        // C'est un downgrade - ouvrir la modal de confirmation
        // Use calcPrice which includes base plan + credit cost for total price
        const targetPlanData = plans.find(p => p.id === planId);
        const targetName = targetPlanData?.name || normalizedPlanId;
        const targetTotalPrice = calcPrice(planId, billingCycle);

        setDowngradePlan({
          code: normalizedPlanId,
          name: targetName,
          price: targetTotalPrice
        });
        setShowDowngradeModal(true);
        return { success: true, message: 'Downgrade modal opened' };
      }

      // Dans tous les autres cas, proceder directement
      return await proceedWithPlanSelection(planId, billingCycle);
    } catch (error) {
      console.error('Error in handlePlanSelect:', error);
      const errorMessage = error instanceof Error ? error.message : 'Unknown error occurred';
      showToast(errorMessage, 'error');
      return { success: false, error: errorMessage };
    }
  }, [isAuthenticated, loginWithRedirect, typedSubscription?.subscription?.planCode, typedSubscription?.subscription?.cadence, typedSubscription?.subscription?.currentPeriodEnd, isUpgrade, isDowngrade, showToast, proceedWithPlanSelection, isCreditTierChanged, creditTierIndex, subscriptionCreditTierIndex, getPlanOrder, t, billingCycle, router, locale, cloudLinkStatus?.linked, tCards]);

  const closeNotification = React.useCallback(() => {
    setNotification(null);
  }, []);

  // Credit tier change confirmation handler
  // Backend decides: upgrade → immediate (billing_cycle_anchor:NOW), downgrade → scheduled
  const handleCreditChangeConfirm = React.useCallback(async () => {
    if (!creditChangeInfo) return;
    setCreditChangeLoading(true);
    setCreditChangeError(null);
    try {
      const result = await unifiedApiService.changeCreditTier(creditTierIndex);
      if (result.url) {
        // Redirect to Stripe Billing Portal to add/update payment method
        window.location.href = result.url;
        return;
      }
      if (result.success) {
        setCreditChangeSuccessDate(result.effectiveDate || null);
        setCreditChangeSuccess(true);
        if (result.changeType === 'SCHEDULED_CREDIT_DOWNGRADE') {
          setRefreshScheduledChange(prev => prev + 1);
        }
        forceLoadSubscription();
      } else {
        setCreditChangeError(result.message || 'Error changing credit tier');
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : 'Error updating credits';
      setCreditChangeError(msg);
    } finally {
      setCreditChangeLoading(false);
    }
  }, [creditChangeInfo, creditTierIndex, forceLoadSubscription]);

  // Fonction pour gerer la selection des plans enterprise
  const handleEnterpriseSelect = React.useCallback(async (optionId: string, billingCycle: 'monthly' | 'yearly') => {

    const currentPlan = typedSubscription?.subscription?.planCode || 'FREE';

    // Convertir l'optionId en code de plan (enterprise_basic -> ENTERPRISE_BASIC)
    const planCode = optionId.toUpperCase();

    // Fermer le modal Enterprise
    setShowEnterpriseModal(false);

    // Verifier si c'est le meme plan
    if (currentPlan === planCode) {
      showToast('You are already on this plan.', 'info');
      return;
    }

    // Verifier le type de changement (upgrade/downgrade/same)

    if (isDowngrade(currentPlan, planCode)) {
      // C'est un downgrade vers Enterprise - ouvrir la modal de confirmation
      // Get price from dbPlans first
      let targetPrice = 0;
      let targetName = planCode;

      if (dbPlans && Array.isArray(dbPlans)) {
        const dbPlan = (dbPlans as any[]).find((p: any) => p.code === planCode);
        if (dbPlan?.prices?.[billingCycle]?.amount_dollars) {
          targetPrice = dbPlan.prices[billingCycle].amount_dollars;
        }
        if (dbPlan?.name) {
          targetName = dbPlan.name;
        }
      }

      // Fallback for Enterprise plans
      if (targetPrice === 0) {
        const enterprisePrices: Record<string, number> = {
          'ENTERPRISE_BASIC': billingCycle === 'monthly' ? 500 : 400,
          'ENTERPRISE_STANDARD': billingCycle === 'monthly' ? 1000 : 800,
          'ENTERPRISE_PREMIUM': billingCycle === 'monthly' ? 2000 : 1600,
          'ENTERPRISE_ULTIMATE': billingCycle === 'monthly' ? 5000 : 4000,
        };
        targetPrice = enterprisePrices[planCode] || 0;
        // Format name nicely
        if (planCode.startsWith('ENTERPRISE_')) {
          const tier = planCode.replace('ENTERPRISE_', '');
          targetName = `Enterprise ${tier.charAt(0) + tier.slice(1).toLowerCase()}`;
        }
      }

      setDowngradePlan({
        code: planCode,
        name: targetName,
        price: targetPrice
      });
      setShowDowngradeModal(true);
      return;
    } else if (currentPlan === 'FREE') {
      // Upgrade depuis FREE, proceder directement au checkout
      await proceedWithPlanSelection(optionId, billingCycle);
    } else if (isUpgrade(currentPlan, planCode)) {
      // C'est un upgrade vers Enterprise, afficher la modale d'upgrade
      setShowUpgradeModal(true);
      // Stocker le plan Enterprise selectionne pour l'upgrade
      setNewPlanCode(optionId);
      return;
    }
  }, [typedSubscription?.subscription?.planCode, billingCycle, showToast, isUpgrade, isDowngrade, proceedWithPlanSelection]);

  // Callback pour confirmer l'upgrade
  const handleUpgradeConfirm = React.useCallback(async () => {
    // Activer le mode processing sans fermer la modal
    setUpgradeProcessing(true);
    setUpgradeModalState('processing');

    try {
      // Si on a un plan selectionne, l'utiliser
      if (newPlanCode) {
        await proceedWithPlanSelection(newPlanCode, billingCycle);
      }
    } catch (error) {
      setUpgradeModalState('error');
      setUpgradeError(error instanceof Error ? error.message : 'An error occurred');
    } finally {
      setUpgradeProcessing(false);
    }
  }, [billingCycle, newPlanCode, proceedWithPlanSelection]);

  // Callback pour fermer le modal d'upgrade
  const handleUpgradeModalClose = React.useCallback(() => {
    setShowUpgradeModal(false);
    setUpgradeModalState('confirm');
    setNewPlanCode('');
    setUpgradeError('');
  }, []);

  return (
    <div className="min-h-screen bg-theme-primary transition-colors duration-300">
      {/* Notification */}
      {notification && (
        <Notification
          type={notification.type}
          message={notification.message}
          isVisible={notification.isVisible}
          onClose={closeNotification}
        />
      )}

      {/* Quotas Debugger (temporaire pour les tests) */}
      {/* <QuotasDebugger /> */}

      {/* User Service Debugger (temporary for testing) */}
      {/* <UserDebugger /> */}

      {/* Mode toggle - Subscription vs Pay-as-you-go. Hidden in CE
          (no Stripe wiring, PAYG checkout endpoint returns 503). */}
      {!isCeMode && (
        <section className="pt-6 pb-2 transition-colors duration-300">
          <div className="w-full flex justify-center">
            <div className="relative inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-full" ref={modeTabContainerRef}>
              {/* Slider highlight */}
              <div
                className="absolute top-1.5 bottom-1.5 rounded-full bg-[var(--bg-primary)] transition-all duration-300 ease-out"
                style={{
                  left: modeSliderStyle.left,
                  width: modeSliderStyle.width,
                  opacity: modeSliderStyle.width ? 1 : 0,
                }}
              />
              {[
                { value: 'subscription', label: t('modeToggle.subscription') },
                { value: 'payg', label: t('modeToggle.payg') },
              ].map((option) => {
                const isActive = pricingMode === option.value;
                return (
                  <button
                    key={option.value}
                    data-mode-id={option.value}
                    onClick={() => setPricingMode(option.value as 'subscription' | 'payg')}
                    className={`relative z-10 flex items-center justify-center px-6 py-2 rounded-full text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none ${isActive
                      ? 'text-[var(--text-primary)]'
                      : 'text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50'
                      }`}
                  >
                    {option.label}
                  </button>
                );
              })}
            </div>
          </div>
        </section>
      )}

      {/* Billing Toggle - subscription mode only.
          Wraps three siblings (billing toggle + scheduled-change alert + plan
          grids section) so the whole subscription flow toggles together. */}
      {pricingMode === 'subscription' && (
      <>
      <section className="transition-colors duration-300">
        <div className="w-full flex justify-center">
          <div className="relative inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-full" ref={tabContainerRef}>
            {/* Slider highlight */}
            <div
              className="absolute top-1.5 bottom-1.5 rounded-full bg-[var(--bg-primary)] transition-all duration-300 ease-out"
              style={{
                left: tabSliderStyle.left,
                width: tabSliderStyle.width,
                opacity: tabSliderStyle.width ? 1 : 0
              }}
            />

            {[
              { value: 'monthly', label: t('billing.monthly') },
              { value: 'yearly', label: t('billing.yearly'), badge: t('billing.yearlyBadge') }
            ].map((option) => {
              const isActive = billingCycle === option.value;
              return (
                <button
                  key={option.value}
                  data-tab-id={option.value}
                  onClick={() => setBillingCycle(option.value as 'monthly' | 'yearly')}
                  className={`relative z-10 flex items-center justify-center px-6 py-2 rounded-full text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none ${isActive
                    ? "text-[var(--text-primary)]"
                    : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
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

        {/* Credit Slider */}
        <div className="w-full flex flex-col items-center mt-4 mb-2 px-4">
          <p className="text-sm text-theme-secondary mb-1">{t('creditSlider.label')}</p>
          <p className="text-lg font-bold text-theme-primary mb-3">
            {creditAmount.toLocaleString(getClientLocale())} {t('creditSlider.creditsPerMonth')}
          </p>
          <div className="w-full max-w-lg">
            <Slider
              value={[clampTierIndex(creditTierIndex, maxTierIndex)]}
              onValueChange={(v) => setCreditTierIndex(v[0])}
              min={0}
              max={maxTierIndex}
              step={1}
            />
            <div className="flex justify-between mt-2 text-xs text-theme-secondary">
              {CREDIT_TIERS.slice(0, maxTierIndex + 1).map((tier, i, visibleTiers) => {
                const isEndpoint = i === 0 || i === visibleTiers.length - 1;
                const isEvenIndex = i % 2 === 0;
                // Mobile: only endpoints (5K, 1M/10M)
                // sm: even indices · md+: all labels
                let visibilityClass = '';
                if (!isEndpoint && !isEvenIndex) {
                  visibilityClass = 'invisible md:visible';
                } else if (!isEndpoint) {
                  visibilityClass = 'invisible sm:visible';
                }
                return (
                  <span key={tier} className={visibilityClass}>
                    {formatTierLabel(tier)}
                  </span>
                );
              })}
            </div>
          </div>
        </div>
      </section>

      {/* Scheduled Change Alert - shows if there's a pending downgrade, billing cycle change, or cancellation */}
      {isAuthenticated && (
        <div className="container mx-auto mt-8 px-4">
          <div className="max-w-7xl mx-auto space-y-4">
            <ScheduledChangeAlert
              key={refreshScheduledChange}
              onCancel={() => {
                showToast('Scheduled change cancelled', 'success');
                forceLoadSubscription();
              }}
              onRefresh={() => setRefreshScheduledChange(prev => prev + 1)}
              cancelAtPeriodEnd={typedSubscription?.subscription?.cancelAtPeriodEnd === true}
              cancellationDate={typedSubscription?.subscription?.currentPeriodEnd}
              onReactivate={() => {
                showToast(t('cancellationReverted'), 'success');
                forceLoadSubscription();
              }}
            />
          </div>
        </div>
      )}

      {/* Individual Plans */}
      <section className="py-4 lg:py-10 transition-colors duration-300">
        <div className="container mx-auto px-4">
          <div className={`grid grid-cols-1 sm:grid-cols-2 ${individualPlans.length >= 3 ? 'xl:grid-cols-3' : 'md:grid-cols-2'} gap-4 md:gap-6 max-w-5xl mx-auto`}>
            {individualPlans.map((plan) => (
              <PlanSelector
                key={plan.id}
                plan={plan}
                billingCycle={billingCycle}
                onPlanSelect={handlePlanSelect}
                isLoading={subscriptionLoading && plan.id !== 'free'}
                isProcessingCheckout={isProcessingCheckout}
                currentPlan={effectiveCurrentPlan}
                currentCadence={effectiveCurrentCadence}
                onPlanChanged={handlePlanChanged}
                onBillingCycleChange={setBillingCycle}
              />
            ))}
          </div>

          {/* Business Plans Section */}
          <div className="max-w-5xl mx-auto mt-12 mb-6 text-center">
            <h2 className="text-2xl font-bold text-theme-primary transition-colors duration-300">
              {t('businessPlans.title')}
            </h2>
            <p className="text-sm text-theme-secondary mt-2 transition-colors duration-300">
              {t('businessPlans.subtitle')}
            </p>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 md:gap-6 max-w-5xl mx-auto">
            {businessPlans.map((plan) => (
              <PlanSelector
                key={plan.id}
                plan={plan}
                billingCycle={billingCycle}
                onPlanSelect={handlePlanSelect}
                isLoading={subscriptionLoading && plan.id !== 'free'}
                isProcessingCheckout={isProcessingCheckout}
                currentPlan={effectiveCurrentPlan}
                currentCadence={effectiveCurrentCadence}
                onPlanChanged={handlePlanChanged}
                onBillingCycleChange={setBillingCycle}
              />
            ))}
          </div>

          <p className="max-w-5xl mx-auto mt-8 text-center text-sm text-theme-secondary transition-colors duration-300">
            {t('billing.taxNote')}
          </p>
        </div>
      </section>
      </>
      )}

      {/* Pay-as-you-go view - alternative to subscription. 3 tier cards laid
          out in the same PlanSelector style (centered, rounded-3xl, Check
          features, full-width CTA) for visual coherence with the rest of the
          page. Click any card → TopUpModal pre-selected on that tier. */}
      {!isCeMode && pricingMode === 'payg' && (
        <section className="py-6 lg:py-10 transition-colors duration-300">
          <div className="container mx-auto px-4">
            {paygTiersLoading ? (
              <div className="max-w-5xl mx-auto py-12 flex items-center justify-center">
                <div className="h-6 w-6 rounded-full border-2 border-theme-secondary/30 border-t-theme-primary animate-spin" aria-label={t('paygSection.loading')} />
              </div>
            ) : !paygConfigured ? (
              <div className="max-w-3xl mx-auto p-6 border border-black/10 dark:border-white/20 rounded-3xl bg-transparent text-center">
                <h3 className="text-lg font-semibold text-theme-primary mb-1 transition-colors duration-300">
                  {t('paygSection.unconfigured.title')}
                </h3>
                <p className="text-sm text-theme-secondary transition-colors duration-300">
                  {t('paygSection.unconfigured.body')}
                </p>
              </div>
            ) : (
              <>
                <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4 md:gap-6 max-w-5xl mx-auto">
                  {paygTiers.map((tier, idx) => {
                    const dollars = tier.amountCents / 100;
                    const symbol = tier.currency.toUpperCase() === 'EUR' ? '€' : '$';
                    const priceLabel = `${symbol}${dollars % 1 === 0 ? dollars.toFixed(0) : dollars.toFixed(2)}`;
                    // Middle tier = recommended (matches PlanSelector badge convention).
                    const isPopular = idx === 1;

                    const handleClick = () => {
                      if (!tier.configured) return;
                      setTopUpInitialTier(tier.tier as 'small' | 'medium' | 'large');
                      setShowTopUpModal(true);
                    };

                    return (
                      <div
                        key={tier.tier}
                        className={`relative p-4 border border-black/10 dark:border-white/20 rounded-3xl transition-all duration-300 ${
                          isPopular ? '!border-2 !border-black dark:!border-white bg-transparent' : 'hover:border-theme/30'
                        } ${!tier.configured ? 'pointer-events-none opacity-60' : ''}`}
                      >
                        {isPopular && (
                          <div className="absolute -top-3 left-1/2 transform -translate-x-1/2">
                            <span className="inline-flex items-center bg-black text-white dark:bg-white dark:text-black border border-transparent px-3 py-1 rounded-full text-xs font-semibold">
                              {t('paygSection.popular')}
                            </span>
                          </div>
                        )}

                        <div className="text-center mb-6">
                          <h3 className="text-xl font-bold text-theme-primary mb-2 transition-colors duration-300">
                            {t(`paygSection.tierName.${tier.tier}`)}
                          </h3>
                          <p className="text-sm text-theme-secondary mb-4 transition-colors duration-300">
                            {t('paygSection.tierDescription')}
                          </p>

                          {/* Price */}
                          <div className="text-3xl font-bold text-theme-primary transition-colors duration-300 mb-2 text-center">
                            <div className="flex items-center justify-center gap-2">
                              <span>{priceLabel}</span>
                              <span className="text-sm text-theme-secondary">{t('paygSection.oneTime')}</span>
                            </div>
                          </div>
                        </div>

                        <div className="flex justify-center mb-6">
                          <ul className="space-y-3 text-sm inline-flex flex-col">
                            <li className="flex items-center gap-2">
                              <Check className="w-4 h-4 text-green-500 flex-shrink-0" />
                              <span className="text-theme-secondary transition-colors duration-300">
                                {t('paygSection.creditsLabel', { count: tier.credits })}
                              </span>
                            </li>
                            <li className="flex items-center gap-2">
                              <Check className="w-4 h-4 text-green-500 flex-shrink-0" />
                              <span className="text-theme-secondary transition-colors duration-300">
                                {t('paygSection.feature.persists')}
                              </span>
                            </li>
                            <li className="flex items-center gap-2">
                              <Check className="w-4 h-4 text-green-500 flex-shrink-0" />
                              <span className="text-theme-secondary transition-colors duration-300">
                                {t('paygSection.feature.noCommitment')}
                              </span>
                            </li>
                          </ul>
                        </div>

                        <Button
                          onClick={handleClick}
                          disabled={!tier.configured}
                          variant="default"
                          size="default"
                          className="w-full"
                        >
                          {t('paygSection.topUp')}
                          <ArrowRight className="inline-block w-4 h-4 ml-2" />
                        </Button>
                      </div>
                    );
                  })}
                </div>

                <p className="max-w-3xl mx-auto mt-8 text-center text-sm text-theme-secondary transition-colors duration-300">
                  {t('paygSection.note')}
                </p>
              </>
            )}
          </div>
        </section>
      )}

      {/* Credits Explanation */}
      <section className="bg-gradient-to-br from-theme-secondary to-theme-tertiary py-16 transition-colors duration-300">
        <div className="container mx-auto px-4">
          <div className="max-w-6xl mx-auto text-center">
            <h2 className="text-3xl font-bold text-theme-primary mb-12 transition-colors duration-300">
              {t('credits.title')}
            </h2>
            <div className="grid md:grid-cols-3 gap-8">
              <div className="p-6 border border-black/10 dark:border-white/20 rounded-3xl bg-transparent hover:bg-theme-tertiary/20 transition-colors">
                <div className="w-14 h-14 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-6">
                  <Zap className="w-7 h-7 text-theme-primary" />
                </div>
                <h3 className="text-xl font-semibold text-theme-primary mb-4 transition-colors duration-300">
                  {t('credits.toolUsage.title')}
                </h3>
                <p className="text-theme-secondary leading-relaxed transition-colors duration-300">
                  {t('credits.toolUsage.description')}
                </p>
              </div>

              <div className="p-6 border border-black/10 dark:border-white/20 rounded-3xl bg-transparent hover:bg-theme-tertiary/20 transition-colors">
                <div className="w-14 h-14 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-6">
                  <Database className="w-7 h-7 text-theme-primary" />
                </div>
                <h3 className="text-xl font-semibold text-theme-primary mb-4 transition-colors duration-300">
                  {t('credits.dataStorage.title')}
                </h3>
                <p className="text-theme-secondary leading-relaxed transition-colors duration-300">
                  {t('credits.dataStorage.description')}
                </p>
              </div>

              <div className="p-6 border border-black/10 dark:border-white/20 rounded-3xl bg-transparent hover:bg-theme-tertiary/20 transition-colors">
                <div className="w-14 h-14 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-6">
                  <Clock className="w-7 h-7 text-theme-primary" />
                </div>
                <h3 className="text-xl font-semibold text-theme-primary mb-4 transition-colors duration-300">
                  {t('credits.monthlyRenewal.title')}
                </h3>
                <p className="text-theme-secondary leading-relaxed transition-colors duration-300">
                  {t('credits.monthlyRenewal.description')}
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* FAQ Section */}
      <section className="py-16 lg:py-20 transition-colors duration-300">
        <div className="container mx-auto px-4">
          <div className="max-w-4xl mx-auto">
            <h2 className="text-3xl font-bold text-theme-primary text-center mb-16 transition-colors duration-300">
              {t('faq.title')}
            </h2>

            <div className="space-y-6">
              <div className="p-6 border border-black/10 dark:border-white/20 rounded-3xl bg-transparent">
                <h3 className="text-xl font-semibold text-theme-primary mb-4 transition-colors duration-300">
                  {t('faq.exceedCredits.question')}
                </h3>
                <p className="text-theme-secondary leading-relaxed transition-colors duration-300">
                  {t('faq.exceedCredits.answer')}
                </p>
              </div>

              <div className="p-6 border border-black/10 dark:border-white/20 rounded-3xl bg-transparent">
                <h3 className="text-xl font-semibold text-theme-primary mb-4 transition-colors duration-300">
                  {t('faq.changePlans.question')}
                </h3>
                <p className="text-theme-secondary leading-relaxed transition-colors duration-300">
                  {t('faq.changePlans.answer')}
                </p>
              </div>

              <div className="p-6 border border-black/10 dark:border-white/20 rounded-3xl bg-transparent">
                <h3 className="text-xl font-semibold text-theme-primary mb-4 transition-colors duration-300">
                  {t('faq.payAsYouGo.question')}
                </h3>
                <p className="text-theme-secondary leading-relaxed transition-colors duration-300">
                  {t('faq.payAsYouGo.answer')}
                </p>
              </div>

              <div className="p-6 border border-black/10 dark:border-white/20 rounded-3xl bg-transparent">
                <h3 className="text-xl font-semibold text-theme-primary mb-4 transition-colors duration-300">
                  {t('faq.sharedStorage.question')}
                </h3>
                <p className="text-theme-secondary leading-relaxed transition-colors duration-300">
                  {t('faq.sharedStorage.answer')}
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>



      {/* CTA Section */}
      <section className="bg-gradient-to-br from-theme-secondary to-theme-tertiary py-16 lg:py-20 transition-colors duration-300">
        <div className="container mx-auto px-4 text-center">
          <div className="max-w-4xl mx-auto">
            <h2 className={`${TYPOGRAPHY.pageTitle} mb-6 transition-colors duration-300`}>
              {t('cta.title')}
            </h2>
            <p className="text-lg md:text-xl text-theme-secondary mb-8 max-w-2xl mx-auto leading-relaxed transition-colors duration-300">
              {t('cta.description')}
            </p>
            <div className="flex flex-col sm:flex-row gap-4 justify-center">
              <Link href="/app">
                <Button variant="default" size="lg">
                  {t('cta.tryFree')}
                </Button>
              </Link>
              <Link href="/contact">
                <Button variant="outline" size="lg">
                  {t('cta.talkToExpert')}
                </Button>
              </Link>
            </div>
          </div>
        </div>
      </section>

      {/* Modals */}
      {/* Enterprise Pricing Modal */}
      <EnterprisePricingModal
        isOpen={showEnterpriseModal}
        onClose={() => setShowEnterpriseModal(false)}
        onSelect={handleEnterpriseSelect}
        billingCycle={billingCycle}
        onBillingCycleChange={setBillingCycle}
        currentPlan={typedSubscription?.subscription?.planCode || 'FREE'}
        currentCadence={typedSubscription?.subscription?.cadence || 'yearly'}
      />

      {/* Unified Upgrade Modal */}
      <UpgradeModal
        open={showUpgradeModal}
        state={upgradeModalState}
        currentPlan={typedSubscription?.subscription?.planCode || 'FREE'}
        targetPlan={newPlanCode?.toUpperCase() || 'UNKNOWN'}
        billingCycle={billingCycle}
        onConfirm={handleUpgradeConfirm}
        onClose={handleUpgradeModalClose}
        loading={upgradeProcessing}
        errorMessage={upgradeError}
        currentTotalPrice={calcPrice((typedSubscription?.subscription?.planCode || 'free').toLowerCase(), billingCycle)}
        targetTotalPrice={calcPrice((newPlanCode || 'free').toLowerCase(), billingCycle)}
      />

      {/* Toast notifications */}
      {toast && (
        <Toast
          id="pricing-toast"
          title={toast.type === 'error' ? 'Error' : toast.type === 'success' ? 'Success' : 'Info'}
          message={toast.message}
          type={toast.type}
          onClose={closeToast}
        />
      )}

      {/* Downgrade Confirmation Modal */}
      {downgradePlan && (() => {
        // Helper function to get price from dbPlans or local plans
        // Total price = base plan + credits (uses calcPrice which already includes both)
        const getPlanPriceFromCode = (planCode: string): number => {
          const planId = planCode?.toLowerCase() || 'free';
          // calcPrice already includes base plan + credit cost for the current tier
          const total = calcPrice(planId, billingCycle);
          if (total > 0) return total;

          // Enterprise plans fallback (no credits logic for enterprise)
          const enterprisePrices: Record<string, number> = {
            'ENTERPRISE_BASIC': billingCycle === 'monthly' ? 500 : 400,
            'ENTERPRISE_STANDARD': billingCycle === 'monthly' ? 1000 : 800,
            'ENTERPRISE_PREMIUM': billingCycle === 'monthly' ? 2000 : 1600,
            'ENTERPRISE_ULTIMATE': billingCycle === 'monthly' ? 5000 : 4000,
          };

          return enterprisePrices[planCode?.toUpperCase() || ''] || 0;
        };

        // Helper function to get plan name
        const getPlanNameFromCode = (planCode: string): string => {
          const normalizedCode = planCode?.toUpperCase() || 'FREE';

          // Try dbPlans first
          if (dbPlans && Array.isArray(dbPlans)) {
            const dbPlan = (dbPlans as any[]).find((p: any) => p.code === normalizedCode);
            if (dbPlan?.name) return dbPlan.name;
          }

          // Fallback to local plans
          const localPlan = plans.find(p => p.id === normalizedCode.toLowerCase());
          if (localPlan?.name) return localPlan.name;

          // Format Enterprise names nicely
          if (normalizedCode.startsWith('ENTERPRISE_')) {
            const tier = normalizedCode.replace('ENTERPRISE_', '');
            return `Enterprise ${tier.charAt(0) + tier.slice(1).toLowerCase()}`;
          }

          return normalizedCode;
        };

        const currentPlanCode = typedSubscription?.subscription?.planCode || 'FREE';

        return (
          <DowngradeConfirmModal
            isOpen={showDowngradeModal}
            onClose={() => {
              setShowDowngradeModal(false);
              setDowngradePlan(null);
            }}
            onSuccess={(effectiveDate) => {
              setShowDowngradeModal(false);
              setDowngradePlan(null);
              setRefreshScheduledChange(prev => prev + 1);
              showToast(`Your plan will change on ${formatUtcDate(effectiveDate)}`, 'success');
              forceLoadSubscription();
            }}
            currentPlan={{
              code: currentPlanCode,
              name: getPlanNameFromCode(currentPlanCode),
              price: getPlanPriceFromCode(currentPlanCode)
            }}
            targetPlan={downgradePlan}
            currentPeriodEnd={typedSubscription?.subscription?.currentPeriodEnd}
            billingCycle={billingCycle}
          />
        );
      })()}

      {/* Billing Cycle Change Modal */}
      <BillingCycleChangeModal
        isOpen={showBillingCycleModal}
        onClose={() => setShowBillingCycleModal(false)}
        onSuccess={(effectiveDate) => {
          setShowBillingCycleModal(false);
          setRefreshScheduledChange(prev => prev + 1);
          showToast(`Your billing cycle will change on ${formatUtcDate(effectiveDate)}`, 'success');
          forceLoadSubscription();
        }}
        currentCycle={(typedSubscription?.subscription?.cadence || 'monthly') as 'monthly' | 'yearly'}
        planName={plans.find(p => p.id === (typedSubscription?.subscription?.planCode?.toLowerCase() || 'free'))?.name || 'Plan'}
        monthlyPrice={(() => {
          const planId = (typedSubscription?.subscription?.planCode || 'starter').toLowerCase();
          const base = BASE_PRICES[planId];
          if (base !== undefined) {
            const creditCost = getCreditCost(planId, subscriptionCreditTierIndex);
            return base + creditCost;
          }
          return 0;
        })()}
        yearlyPrice={(() => {
          const planId = (typedSubscription?.subscription?.planCode || 'starter').toLowerCase();
          const base = BASE_PRICES[planId];
          if (base !== undefined) {
            const creditCost = getCreditCost(planId, subscriptionCreditTierIndex);
            return (Math.round(base * 0.8) + creditCost) * 12;
          }
          return 0;
        })()}
        currentPeriodEnd={typedSubscription?.subscription?.currentPeriodEnd}
      />

      {/* Credit Change Modal */}
      <CreditChangeModal
        isOpen={showCreditChangeModal}
        info={creditChangeInfo}
        loading={creditChangeLoading}
        mode={creditChangeInfo && creditChangeInfo.newCost < creditChangeInfo.currentCost ? 'downgrade' : 'upgrade'}
        success={creditChangeSuccess}
        successDate={creditChangeSuccessDate}
        errorMessage={creditChangeError}
        onConfirm={handleCreditChangeConfirm}
        onClose={() => {
          const wasSuccess = creditChangeSuccess;
          setShowCreditChangeModal(false);
          setCreditChangeInfo(null);
          setCreditChangeSuccess(false);
          setCreditChangeSuccessDate(null);
          setCreditChangeError(null);
          if (wasSuccess) {
            forceLoadSubscription();
          }
        }}
      />

      {/* PAYG top-up modal - opened by the dedicated section above. The modal
          already handles the full flow (tier confirmation, 403 org-owner guard,
          Stripe redirect). We just pass the initial tier when the user clicked
          a specific card so they land on it pre-selected. */}
      <TopUpModal
        isOpen={showTopUpModal}
        onClose={() => {
          setShowTopUpModal(false);
          setTopUpInitialTier(undefined);
        }}
        initialTier={topUpInitialTier}
      />
    </div>
  );
}
