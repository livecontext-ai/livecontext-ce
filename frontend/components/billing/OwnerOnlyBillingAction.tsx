'use client';

/**
 * PR5 - wrapper that conditionally renders billing-mutation CTAs.
 *
 * If the user is the OWNER of the active workspace, children render normally.
 * Otherwise the children are replaced by a disabled placeholder explaining
 * how to enable the action ("switch to your personal organization to
 * subscribe"). The backend BillingController also rejects non-owner
 * mutations with 403 `NOT_WORKSPACE_OWNER` - this component is the UX
 * pre-empt so users don't hit the error path.
 *
 * Usage:
 *   <OwnerOnlyBillingAction>
 *     <Button onClick={upgrade}>Upgrade to Pro</Button>
 *   </OwnerOnlyBillingAction>
 */

import React from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ArrowRightLeft, Lock } from 'lucide-react';
import { useIsCurrentOrgOwner, useCurrentOrgStore } from '@/lib/stores/current-org-store';

interface OwnerOnlyBillingActionProps {
  children: React.ReactNode;
  /** Hide entirely instead of showing the "switch workspace" notice (rare). */
  hideWhenNotOwner?: boolean;
  /** Override className on the wrapper notice. */
  className?: string;
}

export function OwnerOnlyBillingAction({
  children,
  hideWhenNotOwner = false,
  className = '',
}: OwnerOnlyBillingActionProps) {
  const t = useTranslations('billingOwnerOnly');
  const params = useParams();
  const locale = (params?.locale as string) ?? 'en';
  const isOwner = useIsCurrentOrgOwner();
  // Milestone-1 audit B SHOULD-FIX: hide the notice (render null) while
  // the store is empty - pre-hydration. Otherwise an OWNER of their
  // personal org sees a one-frame "switch to personal" notice flash
  // before `smart-providers` hydrates the store.
  const isHydrating = useCurrentOrgStore((s) => s.currentOrgRole === null);

  if (isHydrating) {
    return null;
  }
  if (isOwner) {
    return <>{children}</>;
  }
  if (hideWhenNotOwner) {
    return null;
  }

  return (
    <div
      role="status"
      aria-live="polite"
      className={`rounded-lg border border-amber-200 dark:border-amber-900/40 bg-amber-50 dark:bg-amber-900/15 p-3 ${className}`}
    >
      <div className="flex items-start gap-2 mb-2">
        <Lock className="h-4 w-4 mt-0.5 text-amber-700 dark:text-amber-400 shrink-0" aria-hidden />
        <p className="text-sm text-amber-900 dark:text-amber-100">
          <span className="font-medium">{t('title')}</span>
          <span className="block text-xs text-amber-800/80 dark:text-amber-200/80 mt-0.5">
            {t('subtitle')}
          </span>
        </p>
      </div>
      <Link
        href={`/${locale}/app/settings/organization`}
        className="inline-flex items-center gap-1 text-sm text-amber-800 dark:text-amber-300 hover:text-amber-900 dark:hover:text-amber-100 underline-offset-2 hover:underline"
      >
        <ArrowRightLeft className="h-3.5 w-3.5" aria-hidden />
        {t('switchToPersonal')}
      </Link>
    </div>
  );
}
