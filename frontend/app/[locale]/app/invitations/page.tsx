'use client';

/**
 * /app/invitations - incoming-invitations inbox (PR4b).
 *
 * After PR4a killed the silent auto-accept (Q2=a explicit consent), users
 * need a way to see org invitations addressed to their email and click
 * accept. The email link still works as the primary entry point; this
 * inbox is the secondary "I lost the email" path.
 *
 * Each row shows: org name, role, inviter display name, age. Users can
 * accept or decline without relying on email delivery.
 */

import { useEffect, useState, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { useRouter, useParams } from 'next/navigation';
import { Building2, Crown, User, Eye, MailOpen, Check, X } from 'lucide-react';
import { useAuth } from '@/lib/providers/smart-providers';
import { organizationApi, type Invitation, type OrganizationRole } from '@/lib/api/organization-api';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { parseUtcAware } from '@/lib/utils/dateFormatters';

const ROLE_ICONS: Record<OrganizationRole, React.ReactNode> = {
  OWNER: <Crown className="h-3.5 w-3.5 text-amber-500" />,
  ADMIN: <Crown className="h-3.5 w-3.5 text-yellow-500" />,
  MEMBER: <User className="h-3.5 w-3.5 text-theme-secondary" />,
  VIEWER: <Eye className="h-3.5 w-3.5 text-theme-muted" />,
};

export default function InvitationsInboxPage() {
  const t = useTranslations('invitationsInbox');
  const router = useRouter();
  const params = useParams();
  const locale = (params?.locale as string) ?? 'en';
  const { isAuthenticated, isLoading: isAuthLoading } = useAuth();

  const [invitations, setInvitations] = useState<Invitation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [acceptingId, setAcceptingId] = useState<string | null>(null);
  const [decliningId, setDecliningId] = useState<string | null>(null);

  const fetchInbox = useCallback(async () => {
    setError(null);
    try {
      const list = await organizationApi.getMyPendingInvitations();
      setInvitations(list);
    } catch (e) {
      setError(e instanceof Error ? e.message : t('loadError'));
      setInvitations([]);
    }
  }, [t]);

  useEffect(() => {
    if (!isAuthLoading && isAuthenticated) {
      fetchInbox();
    }
  }, [isAuthLoading, isAuthenticated, fetchInbox]);

  if (isAuthLoading || invitations === null) {
    return (
      <div className="p-6 space-y-4 max-w-3xl mx-auto">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-20 w-full" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="p-6 max-w-3xl mx-auto">
        <p className="text-sm text-theme-secondary">{t('signInRequired')}</p>
      </div>
    );
  }

  return (
    <div className="p-6 max-w-3xl mx-auto">
      <header className="mb-6">
        <h1 className="text-2xl font-semibold text-theme-primary flex items-center gap-2">
          <MailOpen className="h-6 w-6 text-theme-secondary" aria-hidden />
          {t('title')}
        </h1>
        <p className="text-sm text-theme-secondary mt-1">{t('subtitle')}</p>
      </header>

      {error && (
        <div className="mb-4 p-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50">
          <p className="text-sm text-red-700 dark:text-red-400">{error}</p>
        </div>
      )}

      {invitations.length === 0 ? (
        <div className="rounded-xl border border-theme bg-theme-secondary/30 p-8 text-center">
          <p className="text-sm text-theme-secondary">{t('emptyState')}</p>
        </div>
      ) : (
        <ul className="space-y-3">
          {invitations.map((inv) => (
            <li
              key={inv.id}
              className="rounded-xl border border-theme bg-theme-primary p-4 flex items-center justify-between gap-4"
            >
              <div className="flex items-start gap-3 min-w-0">
                <Building2 className="h-5 w-5 mt-0.5 text-theme-secondary shrink-0" aria-hidden />
                <div className="min-w-0">
                  <p className="text-sm font-medium text-theme-primary truncate">
                    {inv.organizationName
                      ? t('invitedToJoinNamed', {
                          inviter: inv.invitedByName || t('anAdmin'),
                          org: inv.organizationName,
                        })
                      : t('invitedToJoin', { inviter: inv.invitedByName || t('anAdmin') })}
                  </p>
                  <p className="text-xs text-theme-secondary mt-0.5 flex items-center gap-2">
                    <span className="inline-flex items-center gap-1">
                      {ROLE_ICONS[inv.role] ?? <User className="h-3.5 w-3.5" />}
                      {t(`role.${inv.role}`)}
                    </span>
                    <span aria-hidden>·</span>
                    <time>{parseUtcAware(inv.createdAt).toLocaleDateString(locale, { timeZone: 'UTC' })}</time>
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={decliningId === inv.id || acceptingId === inv.id}
                  onClick={async () => {
                    setDecliningId(inv.id);
                    setError(null);
                    try {
                      await organizationApi.declineInvitationById(inv.id);
                      await fetchInbox();
                    } catch (e) {
                      setError(e instanceof Error ? e.message : t('declineError'));
                    } finally {
                      setDecliningId(null);
                    }
                  }}
                >
                  <X className="h-3.5 w-3.5 mr-1" aria-hidden />
                  {t('decline')}
                </Button>
                <Button
                  size="sm"
                  disabled={acceptingId === inv.id || decliningId === inv.id}
                  onClick={async () => {
                    setAcceptingId(inv.id);
                    setError(null);
                    try {
                      await organizationApi.acceptInvitationById(inv.id);
                      // Refetch the list (the accepted invitation no longer
                      // appears in PENDING). Then navigate to the org page.
                      await fetchInbox();
                      router.push(`/${locale}/app/settings/organization`);
                    } catch (e) {
                      setError(e instanceof Error ? e.message : t('acceptError'));
                      setAcceptingId(null);
                    }
                  }}
                >
                  <Check className="h-3.5 w-3.5 mr-1" aria-hidden />
                  {t('accept')}
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
