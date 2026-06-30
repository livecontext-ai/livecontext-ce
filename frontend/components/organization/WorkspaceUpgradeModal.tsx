'use client';

/**
 * Workspace upgrade gate - two upsell variants gated behind the same modal:
 *
 *  - 'teammates' (default): "Invite teammates" needs a TEAM (or ENTERPRISE_*) plan -
 *    team collaboration (members, roles, shared credit pool).
 *  - 'workspace': "Create workspace" needs a PRO+ plan - additional workspaces are a
 *    PRO entitlement (PRO=3, TEAM=10, ENTERPRISE=unlimited; FREE/STARTER=1). This variant
 *    must NOT show the TEAM copy: a STARTER user only needs PRO to add a workspace.
 *
 * Path forward: button redirects to /app/settings/pricing where the user can pick a paid
 * tier. The modal does NOT create a workspace itself - it is a pure upsell gate.
 */

import React, { useCallback } from 'react';
import { Users, Building2, ArrowRight } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useRouter } from '@/i18n/navigation';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';

interface WorkspaceUpgradeModalProps {
  open: boolean;
  onClose: () => void;
  /** Which upsell to show. 'teammates' → TEAM (collaboration); 'workspace' → PRO (extra workspaces). */
  variant?: 'teammates' | 'workspace';
}

export function WorkspaceUpgradeModal({ open, onClose, variant = 'teammates' }: WorkspaceUpgradeModalProps) {
  const t = useTranslations('modals.workspaceUpgrade');
  const router = useRouter();

  const handleUpgrade = useCallback(() => {
    onClose();
    router.push('/app/settings/pricing');
  }, [onClose, router]);

  const isWorkspace = variant === 'workspace';
  const Icon = isWorkspace ? Building2 : Users;
  const title = isWorkspace ? t('workspaceTitle') : t('title');
  const description = isWorkspace ? t('workspaceDescription') : t('description');
  const cta = isWorkspace ? t('workspaceUpgradeCta') : t('upgradeCta');

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md p-6 gap-0">
        <div className="text-center">
          <div className="w-12 h-12 bg-indigo-100 dark:bg-indigo-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
            <Icon className="h-6 w-6 text-indigo-600 dark:text-indigo-400" />
          </div>
          <h2 className="text-lg font-semibold text-theme-primary mb-2">
            {title}
          </h2>
          <p className="text-sm text-theme-secondary mb-5">
            {description}
          </p>
          <Button onClick={handleUpgrade} className="w-full">
            {cta}
            <ArrowRight className="w-3.5 h-3.5" />
          </Button>
          <button
            onClick={onClose}
            className="mt-3 text-xs text-theme-muted hover:text-theme-primary underline-offset-2 hover:underline"
          >
            {t('dismiss')}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
