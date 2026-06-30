'use client';

import React, { useState, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Send, UserPlus, Copy, Check } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useLocale, useTranslations } from 'next-intl';
import { organizationApi, type OrganizationRole } from '@/lib/api/organization-api';

interface InviteMemberModalProps {
  open: boolean;
  onClose: () => void;
  orgId: string;
  onInviteSent: () => void;
}

const INVITABLE_ROLES: { value: OrganizationRole; labelKey: string }[] = [
  { value: 'MEMBER', labelKey: 'roleMember' },
  { value: 'ADMIN', labelKey: 'roleAdmin' },
  { value: 'VIEWER', labelKey: 'roleViewer' },
];

export default function InviteMemberModal({ open, onClose, orgId, onInviteSent }: InviteMemberModalProps) {
  const t = useTranslations('settings.organization');
  const locale = useLocale();
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<OrganizationRole>('MEMBER');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [mounted, setMounted] = useState(false);
  // CE invite-by-link: when the response carries a token, show a copyable link
  // instead of auto-closing - the admin must share it with the invitee.
  const [inviteLink, setInviteLink] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Reset the link/copied state whenever the modal is (re)opened.
  useEffect(() => {
    if (open) {
      setInviteLink(null);
      setCopied(false);
      setSuccess(false);
      setError(null);
    }
  }, [open]);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim()) return;

    setLoading(true);
    setError(null);
    setSuccess(false);
    setInviteLink(null);

    try {
      const invitation = await organizationApi.inviteMember(orgId, email.trim().toLowerCase(), role);
      setSuccess(true);
      onInviteSent();
      if (invitation.token) {
        // CE new-email invite: surface the copyable accept link and KEEP the
        // modal open so the admin can copy it.
        setInviteLink(
          `${window.location.origin}/${locale}/invitations/accept?token=${encodeURIComponent(invitation.token)}`
        );
        setEmail('');
        setRole('MEMBER');
      } else {
        // Existing-user (bell) / cloud (email) invite: no link to show.
        setEmail('');
        setRole('MEMBER');
        setTimeout(() => {
          onClose();
          setSuccess(false);
        }, 1500);
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to send invitation';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, [email, role, orgId, onInviteSent, onClose, locale]);

  const handleCopyLink = useCallback(async () => {
    if (!inviteLink) return;
    try {
      await navigator.clipboard.writeText(inviteLink);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard blocked - the link is still visible/selectable in the input */
    }
  }, [inviteLink]);

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
            <UserPlus className="h-7 w-7 text-theme-primary" />
          </div>
          <h2 className="text-2xl font-semibold text-theme-primary">{t('inviteTitle')}</h2>
          <p className="text-sm text-theme-secondary mt-1">{t('inviteDescription')}</p>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Email Input */}
          <div>
            <label htmlFor="invite-email" className="block text-sm font-medium text-theme-primary mb-1.5">
              {t('email')}
            </label>
            <Input
              id="invite-email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder={t('emailPlaceholder')}
              disabled={loading}
            />
          </div>

          {/* Role Selector */}
          <div>
            <label htmlFor="invite-role" className="block text-sm font-medium text-theme-primary mb-1.5">
              {t('role')}
            </label>
            <Select
              value={role}
              onValueChange={(v) => setRole(v as OrganizationRole)}
              disabled={loading}
            >
              <SelectTrigger id="invite-role">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {INVITABLE_ROLES.map((r) => (
                  <SelectItem key={r.value} value={r.value}>
                    {t(r.labelKey)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Error */}
          {error && (
            <div className="p-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50">
              <p className="text-sm text-red-700 dark:text-red-400">{error}</p>
            </div>
          )}

          {/* Success (no link) */}
          {success && !inviteLink && (
            <div className="p-3 rounded-lg bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800/50">
              <p className="text-sm text-emerald-700 dark:text-emerald-400">{t('invitationSent')}</p>
            </div>
          )}

          {/* CE invite-by-link: copyable accept link to share with the invitee */}
          {inviteLink && (
            <div className="p-3 rounded-lg bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800/50 space-y-2">
              <p className="text-sm font-medium text-emerald-700 dark:text-emerald-400">{t('inviteLinkTitle')}</p>
              <p className="text-xs text-theme-secondary">{t('inviteLinkDescription')}</p>
              <div className="flex gap-2">
                <Input
                  readOnly
                  value={inviteLink}
                  aria-label={t('inviteLinkTitle')}
                  onFocus={(e) => e.currentTarget.select()}
                  className="font-mono text-xs"
                />
                <Button type="button" variant="outline" onClick={handleCopyLink} className="shrink-0">
                  {copied ? (
                    <Check className="h-3.5 w-3.5 mr-1.5 text-emerald-600" />
                  ) : (
                    <Copy className="h-3.5 w-3.5 mr-1.5" />
                  )}
                  {copied ? t('inviteLinkCopied') : t('inviteLinkCopy')}
                </Button>
              </div>
            </div>
          )}

          {/* Footer */}
          {inviteLink ? (
            <div className="flex gap-3 mt-8">
              <Button className="flex-1" onClick={onClose} type="button">
                {t('done')}
              </Button>
            </div>
          ) : (
            <div className="flex gap-3 mt-8">
              <Button variant="outline" className="flex-1" onClick={onClose} type="button">
                {t('cancel')}
              </Button>
              <Button
                type="submit"
                disabled={loading || !email.trim() || success}
                className="flex-1"
              >
                {loading ? (
                  <LoadingSpinner size="xs" className="mr-2" />
                ) : (
                  <Send className="h-3.5 w-3.5 mr-2" />
                )}
                {t('sendInvitation')}
              </Button>
            </div>
          )}
        </form>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
