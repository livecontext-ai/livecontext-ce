'use client';

import React from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { parseUtcAware } from '@/lib/utils/dateFormatters';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { CalendarDays, LayoutGrid, MessageCircle } from 'lucide-react';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { orchestratorApi } from '@/lib/api';
import { dmApi } from '@/lib/api/dm-api';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { PublicationCard, PublicationCardSkeleton } from '@/components/marketplace/PublicationCard';
import type { PublicProfile } from '@/lib/api/services/user-api.service';

interface ProfileContentProps {
  /** The public @handle. Profiles are viewed IN-APP and addressed by their chosen handle -
   *  never the numeric user/tenant id, never the real name nor the raw OAuth username. */
  handle: string;
}

/** Localised "Month YYYY" using the visitor's own locale (never hardcoded). */
function formatJoined(iso: string): string {
  const lang = getClientLocale();
  try {
    // UTC-aware: backend timestamps are UTC wall-clock and may carry no TZ
    // designator, so parse them as UTC and format in UTC.
    return parseUtcAware(iso).toLocaleDateString(lang, { year: 'numeric', month: 'long', timeZone: 'UTC' });
  } catch {
    return '';
  }
}

export default function ProfileContent({ handle }: ProfileContentProps) {
  const t = useTranslations('profile');
  const [opening, setOpening] = React.useState(false);

  const {
    data: profile,
    isLoading,
    isError,
  } = useQuery<PublicProfile>({
    queryKey: ['publicProfile', 'handle', handle],
    queryFn: () => unifiedApiService.getPublicProfileByHandle(handle),
    retry: false,
  });

  const profileUserId = profile?.userId;

  const { data: appsResponse, isLoading: appsLoading } = useQuery({
    queryKey: ['publicProfileApps', profileUserId],
    queryFn: () => orchestratorApi.getByPublisher(String(profileUserId), 0, 24),
    enabled: profileUserId != null,
  });

  if (isLoading) {
    return <ProfileSkeleton />;
  }

  if (isError || !profile) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center py-20 text-center">
        <h1 className="text-base font-medium text-theme-primary">{t('notFoundTitle')}</h1>
        <p className="mt-2 text-sm text-theme-secondary">{t('notFoundSubtitle')}</p>
      </div>
    );
  }

  const apps = appsResponse?.publications ?? [];
  const appCount = appsResponse?.count ?? 0;
  // Only ever the chosen display name - the backend guarantees a non-null value, so the
  // real first/last name (or raw handle) is never surfaced here.
  const displayName = profile.displayName;

  return (
    <div className="flex flex-1 flex-col gap-8">
      {/* ===== Header ===== */}
      <header className="flex flex-col items-center gap-4 border-b border-theme pb-8 text-center sm:flex-row sm:items-start sm:gap-6 sm:text-left">
        {/* Canonical user avatar: the backend serves the uploaded photo or a
            server-generated initials SVG from /api/users/{id}/avatar - the same
            source used for publishers across the marketplace, so a user's avatar
            is identical everywhere. (Never the agent preset, which is what the
            agent-only AvatarDisplay fell back to when avatarUrl was null.) */}
        <PublisherAvatar
          userId={profile.userId}
          name={displayName}
          size={80}
          variant="neutral"
        />

        <div className="min-w-0 flex-1">
          <div className="flex flex-col items-center gap-2 sm:flex-row sm:items-start sm:justify-between">
            <div className="min-w-0">
              <h1 className="truncate text-2xl font-semibold text-theme-primary">{displayName}</h1>
              {profile.handle && (
                <p className="truncate text-sm text-theme-muted">@{profile.handle}</p>
              )}
            </div>

            {/* Direct message: open (or get) a 1:1 thread and jump into it. Falls back
                to login if the viewer isn't authenticated. */}
            <button
              type="button"
              onClick={async () => {
                if (opening || !profile) return;
                setOpening(true);
                try {
                  const thread = await dmApi.openThread(String(profile.userId));
                  window.location.href = `/app/messages/${thread.id}`;
                } catch {
                  window.location.href = '/login';
                } finally {
                  setOpening(false);
                }
              }}
              disabled={opening}
              className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-lg border border-theme bg-transparent px-3 py-1.5 text-sm text-theme-primary transition-colors hover:bg-surface-hover disabled:opacity-60"
            >
              <MessageCircle className="h-3.5 w-3.5" />
              {t('message')}
            </button>
          </div>

          {profile.bio && (
            <p className="mt-3 whitespace-pre-line text-sm text-theme-secondary">{profile.bio}</p>
          )}

          <div className="mt-3 flex flex-wrap items-center justify-center gap-x-4 gap-y-1 text-xs text-theme-muted sm:justify-start">
            <span>
              <strong className="text-theme-primary">{appCount}</strong> {t('appsLabel')}
            </span>
            {profile.joinedAt && (
              <span className="inline-flex items-center gap-1">
                <CalendarDays className="h-3 w-3" />
                {t('memberSince')} {formatJoined(profile.joinedAt)}
              </span>
            )}
          </div>
        </div>
      </header>

      {/* ===== Published apps ===== */}
      <section className="flex flex-1 flex-col">
        <h2 className="mb-3 text-sm font-medium text-theme-primary">{t('publishedApps')}</h2>
        {appsLoading ? (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <PublicationCardSkeleton key={i} />
            ))}
          </div>
        ) : apps.length === 0 ? (
          // Substantial empty state that stretches with the page (min-height floor when
          // the shell can't flex) - an empty profile must still look like a full page.
          <div className="flex min-h-[40vh] flex-1 flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-theme px-6 py-20 text-center">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-theme-tertiary">
              <LayoutGrid className="h-5 w-5 text-theme-muted" aria-hidden="true" />
            </div>
            <p className="mt-1 text-sm font-medium text-theme-primary">{t('noApps')}</p>
            <p className="max-w-sm text-sm text-theme-muted">{t('noAppsHint')}</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {apps.map((pub) => (
              <PublicationCard key={pub.id} publication={pub} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function ProfileSkeleton() {
  return (
    <div className="space-y-8">
      <header className="flex flex-col items-center gap-4 sm:flex-row sm:items-start sm:gap-6">
        <div className="h-20 w-20 flex-shrink-0 animate-pulse rounded-full bg-theme-tertiary" />
        <div className="w-full flex-1 space-y-3">
          <div className="h-5 w-40 animate-pulse rounded bg-theme-tertiary" />
          <div className="h-3 w-24 animate-pulse rounded bg-theme-tertiary" />
          <div className="h-3 w-full max-w-md animate-pulse rounded bg-theme-tertiary" />
        </div>
      </header>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 3 }).map((_, i) => (
          <PublicationCardSkeleton key={i} />
        ))}
      </div>
    </div>
  );
}
