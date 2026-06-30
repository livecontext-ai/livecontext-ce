'use client';

/**
 * Owner-only gate (CE only) - informational, NOT an upsell or action.
 *
 * On a self-hosted (CE) install, every workspace belongs to the admin who set it up: only the
 * workspace OWNER/ADMIN can invite members or create workspaces, and everything runs on the admin's
 * plan. A non-manager MEMBER/VIEWER who clicks "Invite teammates" or "Create workspace" gets this
 * modal explaining why - instead of the plan-gated upsell flow (which would be misleading: there is
 * no per-user plan to upgrade here, billing is the admin's).
 *
 * It performs NO action - a single "Got it" dismiss button closes it. The two variants only differ
 * in copy (invite vs. create-workspace).
 */

import React from 'react';
import { Users, Building2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';

interface OwnerOnlyGateModalProps {
  open: boolean;
  onClose: () => void;
  /** Which action the member tried. 'invite' → invite-members copy; 'workspace' → create-workspace copy. */
  action: 'invite' | 'workspace';
}

export function OwnerOnlyGateModal({ open, onClose, action }: OwnerOnlyGateModalProps) {
  const t = useTranslations('modals.ownerOnlyGate');

  const isWorkspace = action === 'workspace';
  const Icon = isWorkspace ? Building2 : Users;
  const title = isWorkspace ? t('workspaceTitle') : t('inviteTitle');
  const body = isWorkspace ? t('workspaceBody') : t('inviteBody');

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
            {body}
          </p>
          <Button onClick={onClose} className="w-full">
            {t('dismiss')}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
