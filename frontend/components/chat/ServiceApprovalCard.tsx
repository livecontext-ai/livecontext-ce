'use client';

import React, { useState, useEffect } from 'react';
import Image from 'next/image';
import { useTranslations } from 'next-intl';
import { useSearchParams, useRouter, usePathname } from 'next/navigation';
import { KeyRound, CheckCircle, AlertTriangle, ExternalLink } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { CredentialWizard, type CredentialWizardRequirement } from '@/components/credentials';
import { useCredentialCheck } from '@/hooks/useCredentialCheck';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import type { ServiceApprovalInfo, PendingServiceApproval } from '@/contexts/StreamingContext';
import { normalizeIconSlug } from '@/lib/credentials/iconSlug';

export interface ServiceApprovalCardProps {
  /** Conversation ID */
  conversationId: string;
  /** Pending service approval data */
  pendingApproval: PendingServiceApproval;
  /** Callback when services are approved - receives list of approved service names */
  onApproved?: (serviceNames: string[]) => void;
  /** Callback when approval is denied - receives list of denied service names */
  onDenied?: (serviceNames: string[]) => void;
  /** Additional class names */
  className?: string;
}

/**
 * Card displayed when the agent needs access to external services.
 * Allows batch approval of multiple services.
 */
export function ServiceApprovalCard({
  conversationId,
  pendingApproval,
  onApproved,
  onDenied,
  className = '',
}: ServiceApprovalCardProps) {
  const t = useTranslations('serviceApproval');
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const { toasts, addToast, removeToast } = useToast();
  const { hasCredential, refetch: refetchCredentials, isLoading: credentialsLoading, credentials } = useCredentialCheck();
  const [isApproving, setIsApproving] = useState(false);
  const [isApproved, setIsApproved] = useState(false);
  const [isDenied, setIsDenied] = useState(false);
  const [imageErrors, setImageErrors] = useState<Record<string, boolean>>({});
  const [isWizardOpen, setIsWizardOpen] = useState(false);
  const [hasProcessedOAuthCallback, setHasProcessedOAuthCallback] = useState(false);

  console.log('[ServiceApprovalCard] Credentials loading state:', credentialsLoading);
  console.log('[ServiceApprovalCard] All credentials:', Array.isArray(credentials) ? credentials.map(c => ({ name: c.name, integration: c.integration })) : credentials);

  // Get services that have iconSlug (can be checked for credentials)
  const servicesWithIconSlug = pendingApproval.services.filter(s => s.iconSlug);

  // Get services that need credentials (have iconSlug and user doesn't have credential)
  const servicesNeedingCredentials = servicesWithIconSlug.filter(
    s => !hasCredential(s.iconSlug)
  );

  // Detect "needs attention" mode: all services have credentials but LLM is still requesting
  // This means the credentials exist but might be expired/invalid
  const allServicesHaveCredentials = servicesWithIconSlug.length > 0 && servicesNeedingCredentials.length === 0;

  console.log('[ServiceApprovalCard] Credential check:', {
    totalServices: pendingApproval.services.length,
    servicesWithIconSlug: pendingApproval.services.filter(s => s.iconSlug).length,
    servicesNeedingCredentials: servicesNeedingCredentials.length,
    serviceDetails: pendingApproval.services.map(s => ({
      serviceName: s.serviceName,
      iconSlug: s.iconSlug,
      hasCredential: s.iconSlug ? hasCredential(s.iconSlug) : 'no-iconSlug'
    }))
  });

  // Convert to CredentialWizardRequirement format
  const credentialRequirements: CredentialWizardRequirement[] = servicesNeedingCredentials.map(s => ({
    iconSlug: s.iconSlug!,
    serviceName: s.serviceName,
    toolId: s.toolName,
  }));

  // Approve services - just dismiss the card (no backend call, approval is transient)
  const approveServices = React.useCallback(() => {
    const serviceNames = [...new Set(pendingApproval.services.map(s => s.serviceName))];
    setIsApproved(true);
    if (onApproved) {
      onApproved(serviceNames);
    }
  }, [pendingApproval.services, onApproved]);

  // Auto-approve when all credentials exist (whether already present or newly connected)
  // Don't auto-approve when needsAttention (credentials may be expired/invalid)
  useEffect(() => {
    if (
      !credentialsLoading &&
      servicesNeedingCredentials.length === 0 &&
      allServicesHaveCredentials &&
      !isApproved &&
      !pendingApproval.needsAttention
    ) {
      console.log('[ServiceApprovalCard] ✅ All credentials present - auto-approving');
      approveServices();
    }
  }, [credentialsLoading, servicesNeedingCredentials.length, allServicesHaveCredentials, isApproved, approveServices, pendingApproval.needsAttention]);

  // Auto-open wizard if returning from OAuth callback
  useEffect(() => {
    const success = searchParams.get('success');
    const error = searchParams.get('error');
    const credentialId = searchParams.get('credentialId');

    // Only process callback once
    if (hasProcessedOAuthCallback) {
      return;
    }

    if (success === 'true') {
      console.log('[ServiceApprovalCard] OAuth callback success - processing');
      setHasProcessedOAuthCallback(true);

      // Show success notification
      addToast({
        type: 'success',
        title: 'Credential Connected',
        message: 'Your account has been successfully connected.',
        duration: 5000,
      });

      // Clean up URL query params
      const cleanUrl = pathname;
      router.replace(cleanUrl, { scroll: false });

      // Directly approve services and show green state
      // This prevents "needs attention" mode from appearing after OAuth success
      approveServices();
    } else if (error) {
      console.log('[ServiceApprovalCard] OAuth callback error:', error);
      setHasProcessedOAuthCallback(true);

      // Show error notification
      addToast({
        type: 'error',
        title: 'Connection Failed',
        message: decodeURIComponent(error),
        duration: 7000,
      });

      // Clean up URL query params
      const cleanUrl = pathname;
      router.replace(cleanUrl, { scroll: false });

      // Reopen wizard to let user retry
      if (credentialRequirements.length > 0) {
        setIsWizardOpen(true);
      }
    }
  }, [searchParams, hasProcessedOAuthCallback, credentialRequirements.length, addToast, approveServices, router, pathname]);

  const handleApprove = async () => {
    // If there are services needing credentials, open the wizard first
    if (credentialRequirements.length > 0) {
      setIsWizardOpen(true);
      return;
    }

    // All credentials are already configured, proceed with approval
    await approveServices();
  };

  const handleWizardComplete = async (completedIconSlugs: string[]) => {
    // Refresh credentials
    refetchCredentials();
    setIsWizardOpen(false);

    // Only approve if ALL required credentials were configured (not skipped)
    const allConfigured = servicesNeedingCredentials.every(
      (s) => completedIconSlugs.includes(s.iconSlug!)
    );

    if (allConfigured) {
      await approveServices();
    }
    // If not all configured, the card remains visible for the user to try again
  };

  const handleCredentialAdded = () => {
    refetchCredentials();
  };

  const handleDeny = () => {
    // Show denied state - no API call needed
    // The services won't be added to Conversation.approvedServices
    setIsDenied(true);
    const serviceNames = [...new Set(pendingApproval.services.map(s => s.serviceName))];
    if (onDenied) {
      onDenied(serviceNames);
    }
  };

  const handleImageError = (serviceType: string) => {
    setImageErrors(prev => ({ ...prev, [serviceType]: true }));
  };

  // Handle "Manage Credentials" button - opens settings in new tab
  const handleManageCredentials = () => {
    window.open('/app/settings/credentials', '_blank');
  };

  // Handle "Retry" button - manually approve with existing credentials
  const handleRetry = async () => {
    await approveServices();
  };

  // Show success state after approval
  if (isApproved) {
    return (
      <div className={`my-4 ${className}`}>
        <div className="rounded-2xl border border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-900/20 p-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center w-8 h-8 rounded-full bg-white dark:bg-slate-800">
              <CheckCircle className="w-4 h-4 text-green-600 dark:text-green-400" />
            </div>
            <div className="flex-1">
              <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                {t('approved')}
              </h3>
              <p className="text-xs text-slate-600 dark:text-slate-400">
                {t('approvedMessage')}
              </p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // Show denied state after cancel
  if (isDenied) {
    return (
      <div className={`my-4 ${className}`}>
        <div className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 p-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center w-8 h-8 rounded-full bg-white dark:bg-slate-800">
              <KeyRound className="w-4 h-4 text-slate-400 dark:text-slate-500" />
            </div>
            <div className="flex-1">
              <h3 className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {t('denied')}
              </h3>
              <p className="text-xs text-slate-400 dark:text-slate-500">
                {t('deniedMessage')}
              </p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // "Needs attention" mode: credentials exist but may be expired/invalid (force=true or reconnection)
  const needsAttentionMode = pendingApproval.needsAttention === true;
  const cardToneClass = needsAttentionMode
    ? 'border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-950/30 shadow-sm'
    : 'border-theme bg-gradient-to-br from-[var(--bg-secondary)] to-[var(--bg-tertiary)] shadow-sm';
  const iconToneClass = needsAttentionMode
    ? 'bg-amber-100 dark:bg-amber-900/60'
    : 'bg-theme-primary border border-theme';
  const iconClassName = needsAttentionMode
    ? 'w-4 h-4 text-amber-700 dark:text-amber-300'
    : 'w-4 h-4 text-theme-primary';

  // Show approval request. First-time connects use the neutral authorization-card
  // surface; forced reconnects use the warning surface and retry action.
  return (
    <div className={`my-4 ${className}`}>
      <div className={`rounded-2xl border p-4 ${cardToneClass}`} data-approval-variant={needsAttentionMode ? 'warning' : 'neutral'}>
        {/* Header */}
        <div className="flex items-center gap-3 mb-3">
          <div className={`flex items-center justify-center w-8 h-8 rounded-full ${iconToneClass}`}>
            {needsAttentionMode ? (
              <AlertTriangle className={iconClassName} />
            ) : (
              <KeyRound className={iconClassName} />
            )}
          </div>
          <div>
            <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
              {needsAttentionMode ? t('needsAttentionTitle') : t('title')}
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              {needsAttentionMode ? t('needsAttentionSubtitle') : t('subtitle')}
            </p>
          </div>
        </div>

        {/* Services list */}
        <div className="space-y-2">
          {pendingApproval.services.map((service, index) => (
            <div
              key={`${service.serviceType}-${index}`}
              className="flex items-center gap-3 p-2.5 rounded-xl bg-theme-primary border border-theme"
            >
              {/* Service icon - normalize slug to match SVG filenames ([a-z0-9]+) */}
              <div className="flex items-center justify-center w-7 h-7 rounded-full bg-white dark:bg-slate-700 border border-slate-200 dark:border-slate-600">
                {service.iconSlug && !imageErrors[service.serviceType] ? (
                  <Image
                    src={`/icons/services/${normalizeIconSlug(service.iconSlug)}.svg`}
                    alt={service.serviceName}
                    width={18}
                    height={18}
                    onError={() => handleImageError(service.serviceType)}
                  />
                ) : (
                  <KeyRound className="w-3.5 h-3.5 text-slate-400" />
                )}
              </div>

              {/* Service info */}
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-slate-900 dark:text-slate-100 truncate">
                  {service.serviceName}
                </p>
                {service.toolName && (
                  <p className="text-xs text-slate-500 dark:text-slate-400 truncate">
                    {t('serviceNeeded', { service: service.serviceName, tool: service.toolName })}
                  </p>
                )}
              </div>
            </div>
          ))}
        </div>

        {/* Reason (if provided) */}
        {pendingApproval.reason && (
          <div className="mt-3 p-2.5 rounded-xl bg-theme-primary border border-theme">
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
              {t('reason')}
            </p>
            <p className="text-sm text-slate-600 dark:text-slate-300">
              {pendingApproval.reason}
            </p>
          </div>
        )}

        {/* Action buttons - different based on mode */}
        <div className="flex justify-end gap-2 mt-3">
          {needsAttentionMode ? (
            <>
              {/* Needs Attention mode: Deny + Manage Credentials + Retry */}
              <Button
                variant="ghost"
                size="sm"
                onClick={handleDeny}
              >
                {t('deny')}
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={handleManageCredentials}
                className="gap-2"
              >
                <ExternalLink className="w-3.5 h-3.5" />
                {t('manageCredentials')}
              </Button>
              <Button
                variant="default"
                size="sm"
                onClick={handleRetry}
                disabled={isApproving}
                className="gap-2"
              >
                {isApproving ? (
                  <>
                    <LoadingSpinner size="xs" />
                    {t('approving')}
                  </>
                ) : (
                  <>{t('retry')}</>
                )}
              </Button>
            </>
          ) : (
            <>
              {/* Normal mode: Deny + Connect */}
              <Button
                variant="ghost"
                size="sm"
                onClick={handleDeny}
                disabled={isApproving}
              >
                {t('deny')}
              </Button>
              <Button
                variant="default"
                size="sm"
                onClick={handleApprove}
                disabled={isApproving}
                className="gap-2"
              >
                {isApproving ? (
                  <>
                    <LoadingSpinner size="xs" />
                    {t('approving')}
                  </>
                ) : (
                  <>{t('approveAll')}</>
                )}
              </Button>
            </>
          )}
        </div>
      </div>

      {/* Credential wizard */}
      <CredentialWizard
        requirements={credentialRequirements}
        open={isWizardOpen}
        onOpenChange={setIsWizardOpen}
        onComplete={handleWizardComplete}
        onCredentialAdded={handleCredentialAdded}
      />

      {/* Toast notifications */}
      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );
}

export default ServiceApprovalCard;
