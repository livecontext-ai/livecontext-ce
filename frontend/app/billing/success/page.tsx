'use client';

import { useEffect, useState, useCallback } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useAuth } from '@/lib/providers/smart-providers';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import CheckoutModal, { CheckoutModalType } from '@/components/CheckoutModal';
import { usePlans } from '@/lib/hooks/smart-hooks-complete';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';

export default function BillingSuccessPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { isAuthenticated, isLoading, getAccessTokenSilently } = useAuth();
  const { plans: dbPlans } = usePlans();
  const t = useTranslations('billing');
  
  // Fonction pour mapper dynamiquement les planId vers les noms de plans
  const getPlanNameFromId = useCallback((planId: number) => {
    if (!dbPlans || !Array.isArray(dbPlans) || dbPlans.length === 0) {
      // Fallback si les plans ne sont pas encore charges
      const fallbackMapping: { [key: number]: string } = {
        1: 'FREE',
        2: 'STARTER', 
        3: 'PRO',
        4: 'ENTERPRISE_BASIC',
        5: 'ENTERPRISE_STANDARD',
        6: 'ENTERPRISE_PREMIUM',
        7: 'ENTERPRISE_ULTIMATE',
        8: 'PAYG'
      };
      return fallbackMapping[planId] || 'UNKNOWN';
    }
    
    // Chercher le plan correspondant au planId dans les donnees du backend
    const plan = dbPlans.find((p: any) => p.id === planId) as { code?: string } | undefined;
    console.log('🔍 Plan lookup:', { planId, foundPlan: plan, allPlans: dbPlans });
    return plan?.code || 'UNKNOWN';
  }, [dbPlans]);
  
  // etats pour la gestion des modals
  const [modalType, setModalType] = useState<CheckoutModalType>('processing');
  const [showModal, setShowModal] = useState(false);
  const [planName, setPlanName] = useState('STARTER');
  const [subscriptionDetails, setSubscriptionDetails] = useState<any>(null);
  const [pollingAttempts, setPollingAttempts] = useState(0);
  const [lastCheck, setLastCheck] = useState<Date | null>(null);
  const [message, setMessage] = useState('');

  const displayedPlanName = (() => {
    if (modalType !== 'success' || typeof subscriptionDetails?.planId !== 'number') {
      return planName;
    }

    const resolvedPlanName = getPlanNameFromId(subscriptionDetails.planId);
    return resolvedPlanName === 'UNKNOWN' ? planName : resolvedPlanName;
  })();

  const displayedMessage = (() => {
    if (modalType === 'success' && typeof subscriptionDetails?.planId === 'number') {
      return t('success.activated', { plan: displayedPlanName });
    }
    return message;
  })();

  useEffect(() => {
    console.log('🔄 [BillingSuccess] useEffect triggered:', { 
      isAuthenticated, 
      isLoading, 
      searchParams: searchParams.toString() 
    });
    
    const processCheckoutSuccess = async () => {
      try {
        console.log('🚀 [BillingSuccess] Starting processCheckoutSuccess');
        
        // Afficher la modal meme pendant le chargement
        console.log('⏳ [BillingSuccess] Loading state:', isLoading);
        
        const sessionId = searchParams.get('session_id');
        if (!sessionId) {
          setModalType('error');
          setMessage(t('success.missingSession'));
          setShowModal(true);
          return;
        }

        // V250 - PAYG one-time top-up branch. Stripe appends `?payg=<tier>` to
        // the success URL (set by StripeBillingService.createPaygCheckoutSession).
        // Mode=PAYMENT has NO subscription to provision, so the standard
        // finalize-polling would loop forever. Show a short confirmation,
        // then redirect to /settings/overview where the wallet card refetches
        // and the user sees the credited balance.
        const paygTier = searchParams.get('payg');
        if (paygTier) {
          console.log('💳 [BillingSuccess] PAYG top-up confirmed:', paygTier);
          setModalType('success');
          setPlanName(paygTier.toUpperCase());
          setMessage(t('success.paygActivating'));
          setShowModal(true);
          // Webhook handlePaygTopup credits the bucket + fans out cache
          // invalidation; balance becomes visible within ~1s of the redirect.
          setTimeout(() => {
            router.push('/app/settings/overview');
          }, 2500);
          return;
        }

        // Afficher la modal de traitement immediatement
        console.log('📱 [BillingSuccess] Showing processing modal');
        setModalType('processing');
        setMessage(t('success.finalizing'));
        setShowModal(true);

        // Verifier l'authentification apres avoir affiche la modal
        if (!isAuthenticated) {
          console.log('❌ [BillingSuccess] Not authenticated, will redirect after modal');
          // Ne pas rediriger immediatement, laisser la modal s'afficher
          return;
        }

        // Simuler le polling de finalisation
        let attempts = 0;
        const maxAttempts = 30;
        
        const pollFinalization = async () => {
          attempts++;
          setPollingAttempts(attempts);
          setLastCheck(new Date());
          
          try {
            // Recuperer le token d'authentification
            await getAccessTokenSilently();
            
            // Appeler l'API de finalisation via UnifiedApiService
            const data = await unifiedApiService.finalizeCheckout(sessionId);
            console.log('📊 [BillingSuccess] API Response:', data);

            // Verifier que nous avons une reponse valide
            if (data && data.state) {
              // Verifier l'etat de la reponse
              if (data.state === 'provisioned') {
                // Abonnement cree avec succes
                const actualPlanName = getPlanNameFromId(data.planId);
                setPlanName(actualPlanName);
                
                // Succes - afficher la modal de succes
                setModalType('success');
                setMessage(t('success.activated', { plan: actualPlanName }));
                setSubscriptionDetails({
                  planId: data.planId,
                  status: data.status || 'active',
                  currentPeriodStart: data.currentPeriodStart,
                  currentPeriodEnd: data.currentPeriodEnd
                });
                return;
              } else if (data.state === 'processing') {
                // En attente du webhook - continuer le polling
                console.log('⏳ En attente du webhook:', data.message);
                // Ne pas changer le modal, continuer le polling
              } else if (data.state === 'error') {
                // Erreur - afficher la modal d'erreur
                setModalType('error');
                setMessage(data.error || t('success.error'));
                return;
              }
            } else {
              // Pas de donnees valides - afficher la modal d'erreur
              console.error('❌ Invalid response data:', data);
              setModalType('error');
              setMessage(t('success.invalidResponse'));
              return;
            }
          } catch (apiError) {
            console.warn('⚠️ Error calling checkout finalize API:', apiError);
          }

          // Si pas encore finalise et pas atteint la limite
          if (attempts < maxAttempts) {
            setTimeout(pollFinalization, 2000); // Attendre 2 secondes
          } else {
            // Timeout - afficher la modal de timeout
            setModalType('timeout');
            setMessage(t('success.timeout'));
          }
        };

        // Commencer le polling
        pollFinalization();

      } catch (error) {
        console.error('❌ Error processing checkout success:', error);
        setModalType('error');
        setMessage(t('success.error'));
        setShowModal(true);
      }
    };

    processCheckoutSuccess();
  }, [isAuthenticated, isLoading, searchParams, router]);

  const handleModalClose = () => {
    setShowModal(false);
    // The in-app pricing page shows the (already finalized) new plan; the old
    // `/pricing?success=true&plan=…` target had no page and nothing consumed
    // those params.
    router.push('/app/settings/pricing');
  };

  const handleChat = () => {
    router.push('/app/chat');
  };

  const handleTools = () => {
    router.push('/');
  };

  const handleRetry = () => {
    // Recharger la page pour retenter
    window.location.reload();
  };

  // Afficher le loading spinner pendant le chargement initial
  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900">
        <div className="text-center">
          <div className="flex justify-center mb-4">
            <LoadingSpinner size="lg" />
          </div>
          <h2 className="text-xl font-semibold text-gray-900 dark:text-gray-100 mb-2 mt-4">
            {t('success.loading')}
          </h2>
          <p className="text-gray-600 dark:text-gray-400">
            {t('success.preparing')}
          </p>
        </div>
      </div>
    );
  }

  console.log('🎨 [BillingSuccess] Rendering modal:', { 
    showModal, 
    modalType, 
    planName, 
    message 
  });

  return (
    <CheckoutModal
      open={showModal}
      type={modalType}
      onClose={handleModalClose}
      onChat={handleChat}
      onTools={handleTools}
      onRetry={handleRetry}
      planName={displayedPlanName}
      subscriptionDetails={subscriptionDetails}
      pollingAttempts={pollingAttempts}
      lastCheck={lastCheck}
      message={displayedMessage}
      showChatButton={modalType === 'success'}
      showToolsButton={modalType === 'success'}
      showRetryButton={modalType === 'error' || modalType === 'timeout'}
    />
  );
}
