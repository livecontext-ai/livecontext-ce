'use client';

import React, { useState, useEffect } from 'react';
import Image from 'next/image';
import { useTranslations } from 'next-intl';
import { useSearchParams } from 'next/navigation';
import { KeyRound, ExternalLink, CheckCircle } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { useCredentialCheck } from '@/hooks/useCredentialCheck';
import { CredentialWizard } from '@/components/credentials';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import { normalizeIconSlug } from '@/lib/credentials/iconSlug';

export interface CredentialCardProps {
  /** Tool ID that requires credentials */
  toolId: string;
  /** Service icon slug (e.g., 'gmail', 'slack') - used to check credentials */
  iconSlug?: string;
  /** Service name for display */
  serviceName?: string;
  /** Title to display */
  title?: string;
  /** Optional callback when connect button is clicked */
  onConnect?: () => void;
  /** Additional class names */
  className?: string;
}

/**
 * Card displayed when a tool requires user credentials.
 * Uses cached credentials check for optimal performance.
 */
export function CredentialCard({
  toolId,
  iconSlug,
  serviceName,
  title,
  onConnect,
  className = '',
}: CredentialCardProps) {
  const t = useTranslations('credentials');
  const searchParams = useSearchParams();
  const { hasCredential, isLoading, refetch } = useCredentialCheck();

  const { toasts, addToast, removeToast } = useToast();
  const displayName = title || serviceName || 'Service';
  const [imageError, setImageError] = useState(false);
  const [isWizardOpen, setIsWizardOpen] = useState(false);

  // Auto-open wizard if returning from OAuth callback
  useEffect(() => {
    const success = searchParams.get('success');
    const error = searchParams.get('error');

    if ((success || error) && iconSlug) {
      setIsWizardOpen(true);
    }
  }, [searchParams, iconSlug]);

  const handleConnect = () => {
    if (onConnect) {
      onConnect();
    } else {
      // Open the credential wizard
      setIsWizardOpen(true);
    }
  };

  const handleCredentialAdded = (addedIconSlug?: string) => {
    // Refresh credentials check
    refetch();

    // Show success toast
    addToast({
      type: 'success',
      title: t('connected'),
      message: t('connectedTo', { service: displayName }),
      duration: 5000,
    });
  };

  const isConnected = !isLoading && hasCredential(iconSlug);

  // Render card content based on state
  const renderCard = () => {
    // Still loading credentials
    if (isLoading) {
      return (
        <div className={`my-6 rounded-[18px] border border-slate-200 dark:border-slate-700 overflow-hidden bg-slate-50 dark:bg-slate-800/50 ${className}`}>
          <div className="p-4 flex items-center gap-3">
            <LoadingSpinner size="sm" />
            <span className="text-sm text-slate-500">{t('checkingCredentials')}</span>
          </div>
        </div>
      );
    }

    // User has credentials - show success state
    if (isConnected) {
      return (
        <div className={`my-6 rounded-[18px] border border-green-200 dark:border-green-800 overflow-hidden bg-green-50 dark:bg-green-900/20 ${className}`}>
          <div className="p-4">
            <div className="flex items-center gap-3">
              <div className="flex items-center justify-center w-10 h-10 rounded-full bg-white dark:bg-slate-800 shadow-sm">
                {iconSlug && !imageError ? (
                  <Image
                    src={`/icons/services/${normalizeIconSlug(iconSlug)}.svg`}
                    alt={displayName}
                    width={24}
                    height={24}
                    onError={() => setImageError(true)}
                  />
                ) : (
                  <CheckCircle className="w-5 h-5 text-green-600 dark:text-green-400" />
                )}
              </div>
              <div className="flex-1">
                <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                  {t('connected')}
                </h3>
                <p className="text-sm text-slate-600 dark:text-slate-400">
                  {t('connectedTo', { service: displayName })}
                </p>
              </div>
              <CheckCircle className="w-5 h-5 text-green-600 dark:text-green-400" />
            </div>
          </div>
        </div>
      );
    }

    // User doesn't have credentials - show connection prompt
    return (
      <div className={`my-6 rounded-[18px] border border-slate-200 dark:border-slate-700 overflow-hidden bg-slate-50 dark:bg-slate-800/50 ${className}`}>
        <div className="p-4">
          <div className="flex items-center gap-3 mb-3">
            <div className="flex items-center justify-center w-10 h-10 rounded-full bg-white dark:bg-slate-800 shadow-sm">
              {iconSlug && !imageError ? (
                <Image
                  src={`/icons/services/${normalizeIconSlug(iconSlug)}.svg`}
                  alt={displayName}
                  width={24}
                  height={24}
                  onError={() => setImageError(true)}
                />
              ) : (
                <KeyRound className="w-5 h-5 text-slate-500 dark:text-slate-400" />
              )}
            </div>
            <div className="flex-1">
              <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                {t('connectionRequired')}
              </h3>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                {t('connectToUse', { service: displayName })}
              </p>
            </div>
          </div>
          <div className="flex justify-end">
            <Button
              variant="default"
              size="sm"
              onClick={handleConnect}
              className="gap-2"
            >
              <ExternalLink className="w-4 h-4" />
              {t('connectButton')}
            </Button>
          </div>
        </div>
      </div>
    );
  };

  return (
    <>
      {renderCard()}

      {/* Credential Wizard - always mounted so it works across state changes */}
      <CredentialWizard
        requirements={iconSlug ? [{ iconSlug, serviceName: displayName, toolId }] : []}
        open={isWizardOpen}
        onOpenChange={setIsWizardOpen}
        onCredentialAdded={handleCredentialAdded}
      />

      {/* Toast notifications - always mounted so toasts survive state transitions */}
      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </>
  );
}

export default CredentialCard;
