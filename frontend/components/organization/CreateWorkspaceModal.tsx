'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { useTranslations } from 'next-intl';
import { Building2 } from 'lucide-react';
import { organizationApi, type Organization } from '@/lib/api/organization-api';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import LoadingSpinner from '@/components/LoadingSpinner';

/**
 * Create-an-additional-workspace modal (shared-wallet model). Gated to PRO+ at the call site;
 * the backend also enforces the per-plan max_workspaces cap and returns 403 when reached.
 * Mirrors the InviteMemberModal visual style (themed panel, centered icon header, portal).
 */
export default function CreateWorkspaceModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (org: Organization) => void;
}) {
  const t = useTranslations('sidebar');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  const submit = useCallback(async (e?: React.FormEvent) => {
    e?.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) return;
    setBusy(true);
    setError(null);
    try {
      const org = await organizationApi.createOrganization(trimmed);
      setName('');
      onCreated(org);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('createWorkspaceError'));
      setBusy(false);
    }
  }, [name, onCreated, t]);

  if (!open || !mounted) return null;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex flex-col items-center text-center mb-6">
          <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mb-4">
            <Building2 className="h-7 w-7 text-theme-primary" />
          </div>
          <h2 className="text-2xl font-semibold text-theme-primary">{t('createWorkspace')}</h2>
          <p className="text-sm text-theme-secondary mt-1">{t('createWorkspaceDesc')}</p>
        </div>

        {/* Form */}
        <form onSubmit={submit} className="space-y-4">
          <Input
            id="create-workspace-name"
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('workspaceNamePlaceholder')}
            disabled={busy}
            maxLength={100}
          />

          {/* Error */}
          {error && (
            <div className="p-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50">
              <p className="text-sm text-red-700 dark:text-red-400">{error}</p>
            </div>
          )}

          {/* Footer */}
          <div className="flex gap-3 mt-8">
            <Button variant="outline" className="flex-1" onClick={onClose} type="button" disabled={busy}>
              {t('createWorkspaceCancel')}
            </Button>
            <Button type="submit" className="flex-1" disabled={busy || !name.trim()}>
              {busy ? <LoadingSpinner size="xs" className="mr-2" /> : null}
              {busy ? t('creating') : t('create')}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
