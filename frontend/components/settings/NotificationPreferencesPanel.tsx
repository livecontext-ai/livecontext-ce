'use client';

import React, { useState } from 'react';
import { useTranslations } from 'next-intl';
import { Bell, Users } from 'lucide-react';
import { Link } from '@/i18n/navigation';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { chatChannelService, notificationPreferencesService } from '@/lib/api/orchestrator';
import type {
  NotificationDelivery,
  NotificationPreferences,
  NotificationTopic,
  NotificationTopicPreference,
} from '@/lib/api/orchestrator';
import { InfoPopover } from '@/components/ui/info-popover';
import { ChatDestinationCard } from '@/components/app/ChatDestinationCard';
import {
  ChannelDestinationPicker,
  isWorking,
  useChatDestinations,
} from '@/components/app/ChannelDestinationPicker';
import { useCanMutateInCurrentOrg, useCurrentOrgStore } from '@/lib/stores/current-org-store';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

export const NOTIFICATION_PREFERENCES_QUERY_KEY = 'notification-preferences';

const DELIVERIES: NotificationDelivery[] = ['BOTH', 'EMAIL', 'CHANNEL', 'OFF'];

const wantsEmail = (d: NotificationDelivery) => d === 'EMAIL' || d === 'BOTH';
const wantsChannel = (d: NotificationDelivery) => d === 'CHANNEL' || d === 'BOTH';

/** The anti-flood rules, in the order a failing workflow meets them. */
const ANTI_SPAM_RULES = [
  'antiSpamBreaks',
  'antiSpamReminder',
  'antiSpamRecovered',
  'antiSpamDigest',
  'antiSpamCap',
] as const;

/** "STARTER" -> "Starter", for the plan hint. */
function planLabel(code: string): string {
  return code.charAt(0) + code.slice(1).toLowerCase();
}

/**
 * Where each kind of notification reaches the person, in the active workspace: email, the
 * workspace's chat channel, both, or nowhere but the bell. The server says which mediums can
 * actually deliver (the plan includes email, a channel is connected), so an option that would
 * silently deliver nothing is disabled here instead of saved.
 */
export function NotificationPreferencesPanel({ enabled }: { enabled: boolean }) {
  const t = useTranslations('settings.notifications');
  const [busyTopic, setBusyTopic] = useState<NotificationTopic | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading, isError, refetch } = useOrgScopedQuery<NotificationPreferences>({
    queryKey: [NOTIFICATION_PREFERENCES_QUERY_KEY],
    queryFn: () => notificationPreferencesService.get(),
    enabled,
  });

  const onChange = async (topic: NotificationTopic, delivery: NotificationDelivery) => {
    setBusyTopic(topic);
    setError(null);
    try {
      await notificationPreferencesService.update(topic, delivery);
      await refetch();
    } catch {
      setError(t('saveFailed'));
    } finally {
      setBusyTopic(null);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center space-x-3">
        <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center">
          <Bell className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <div className="flex items-center gap-1.5">
            <h3 className="text-lg font-semibold text-theme-primary">{t('title')}</h3>
            <InfoPopover
              label={t('antiSpamInfo')}
              accessibleName={t('antiSpamInfo')}
              side="bottom"
              align="start"
              data-testid="notification-anti-spam-info"
              contentTestId="notification-anti-spam-content"
            >
              <div className="space-y-2 text-sm text-theme-secondary">
                <p className="font-semibold text-theme-primary">{t('antiSpamTitle')}</p>
                <ul className="list-disc space-y-1 pl-4">
                  {ANTI_SPAM_RULES.map((rule) => <li key={rule}>{t(rule)}</li>)}
                </ul>
              </div>
            </InfoPopover>
          </div>
          <p className="text-sm text-theme-secondary">{t('description')}</p>
        </div>
      </div>

      {isLoading && <p className="text-sm text-theme-secondary">{t('loading')}</p>}
      {isError && (
        <p className="text-sm text-red-600 dark:text-red-400">
          {t('loadFailed')}{' '}
          <button type="button" className="underline" onClick={() => refetch()}>{t('retry')}</button>
        </p>
      )}

      {data && (
        <>
          <NotificationChannelSection onDefaultChanged={() => refetch()} />

          <div className="space-y-5">
            {data.topics.map((pref) => (
              <TopicRow
                key={pref.topic}
                pref={pref}
                channelConnected={data.channel.connected}
                emailRequiredPlan={data.emailRequiredPlan}
                busy={busyTopic === pref.topic}
                onChange={(d) => onChange(pref.topic, d)}
              />
            ))}
          </div>
          {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}
        </>
      )}
    </div>
  );
}

/**
 * Where channel alerts go: the workspace's DEFAULT chat destination, the only one the delivery
 * ever uses (ChatChannelService.sendNotice). It is drawn exactly as Settings > Channels draws a
 * destination (service, chat, the bot that sends, whether it works), with who can read it
 * attached underneath.
 *
 * <p>Notifications have no destination of their own: changing it here makes another connected
 * chat the workspace default, through the same call and the same permission as Settings >
 * Channels, and the screen says so. The change is offered only when another chat that works
 * exists, and only to a role that may change the workspace.
 */
function NotificationChannelSection({ onDefaultChanged }: { onDefaultChanged: () => void }) {
  const t = useTranslations('settings.notifications');
  const { destinations, isLoading, isError, refetch } = useChatDestinations();
  const canMutate = useCanMutateInCurrentOrg();
  const isViewer = useCurrentOrgStore((s) => s.currentOrgRole) === 'VIEWER';
  const [changing, setChanging] = useState(false);
  const [changeError, setChangeError] = useState(false);

  // The delivery's own rule (ChatChannelService.resolveDefault): the default, if switched on.
  const current = destinations.find((d) => d.isDefault && d.active) ?? null;
  const alternatives = destinations.filter((d) => isWorking(d) && d.linkId !== current?.linkId);

  const makeDefault = async (linkId: string) => {
    if (linkId === current?.linkId) return;
    setChanging(true);
    setChangeError(false);
    try {
      await chatChannelService.setDefault(linkId);
      await refetch();
      onDefaultChanged();
    } catch {
      setChangeError(true);
    } finally {
      setChanging(false);
    }
  };

  let body: React.ReactNode;
  if (isLoading) {
    body = <p className="text-sm text-theme-secondary">{t('channelLoading')}</p>;
  } else if (isError) {
    body = <p className="text-sm text-red-600 dark:text-red-400" role="alert">{t('channelLoadFailed')}</p>;
  } else if (destinations.length === 0) {
    body = (
      <p className="text-sm text-theme-secondary" data-testid="notification-channel-none">
        {t('noChannel')}{' '}
        <Link href="/app/settings/channels" className="underline text-theme-primary">
          {t('connectChannel')}
        </Link>
      </p>
    );
  } else {
    body = (
      <>
        {current ? (
          <ChatDestinationCard
            destination={current}
            showBot
            data-testid="notification-channel-card"
            footer={(
              <p className="flex items-start gap-2 text-sm text-theme-secondary" data-testid="notification-channel-privacy">
                <Users className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                {t('channelPrivacy')}
              </p>
            )}
          />
        ) : (
          <p className="text-sm text-amber-600 dark:text-amber-400" data-testid="notification-channel-no-default">
            {/* A default that is switched off is still the default: say that, not "there is none". */}
            {destinations.some((d) => d.isDefault) ? t('defaultOff') : t('noDefault')}
          </p>
        )}

        {alternatives.length > 0 && canMutate ? (
          <div className="space-y-1" data-testid="notification-channel-change">
            <span className="text-sm font-medium text-theme-primary">{t('changeLabel')}</span>
            <ChannelDestinationPicker
              // Following the default is what notifications do. A default no message has reached
              // yet is named as itself: the picker's "default" option only knows working ones.
              value={current && !isWorking(current) ? current.linkId : null}
              onChange={(value) => { if (value) void makeDefault(value); }}
              ariaLabel={t('changeLabel')}
              disabled={changing}
            />
            <p className="text-sm text-theme-secondary">{t('changeHint')}</p>
            {changeError && (
              <p className="text-sm text-red-600 dark:text-red-400" role="alert">{t('changeFailed')}</p>
            )}
          </div>
        ) : (
          <>
            {alternatives.length > 0 && isViewer && (
              <p className="text-sm text-theme-secondary" data-testid="notification-channel-read-only">
                {t('changeReadOnly')}
              </p>
            )}
            <Link href="/app/settings/channels" className="block text-sm text-theme-secondary underline underline-offset-2">
              {t('manageChannels')}
            </Link>
          </>
        )}
      </>
    );
  }

  return (
    <section className="space-y-2" data-testid="notification-channel">
      <div>
        <h4 className="text-sm font-medium text-theme-primary">{t('channelTitle')}</h4>
        <p className="text-sm text-theme-secondary">{t('channelIntro')}</p>
      </div>
      {body}
    </section>
  );
}

function TopicRow({
  pref,
  channelConnected,
  emailRequiredPlan,
  busy,
  onChange,
}: {
  pref: NotificationTopicPreference;
  channelConnected: boolean;
  emailRequiredPlan: string | null;
  busy: boolean;
  onChange: (delivery: NotificationDelivery) => void;
}) {
  const t = useTranslations('settings.notifications');
  const topicKey = pref.topic.toLowerCase() as 'failures' | 'credits' | 'account' | 'tasks';

  // A person-scoped topic goes to the PERSONAL workspace's channel, which this screen (showing
  // the active workspace) cannot vouch for, so its channel options are never disabled here.
  const channelOk = pref.personScoped || channelConnected;
  const unavailable = (d: NotificationDelivery) =>
    (wantsEmail(d) && !pref.emailAvailable) || (wantsChannel(d) && !channelOk);
  // The stored choice can outlive what makes it deliverable (a downgrade, a disconnected channel,
  // or simply the default on a Free plan with no channel): say so instead of looking fine.
  const deliversNothing = pref.delivery !== 'OFF'
    && !(wantsEmail(pref.delivery) && pref.emailAvailable)
    && !(wantsChannel(pref.delivery) && channelOk);

  // The plan hint, once per row. A missing channel is said once above the list, not on every row.
  const emailHint = !pref.emailAvailable && emailRequiredPlan
    ? t('emailNeedsPlan', { plan: planLabel(emailRequiredPlan) })
    : null;

  return (
    <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0">
        <h4 className="font-medium text-theme-primary text-sm">{t(`topics.${topicKey}.title`)}</h4>
        <p className="text-sm text-theme-secondary">{t(`topics.${topicKey}.description`)}</p>
        {pref.personScoped && <p className="text-xs text-theme-secondary mt-1">{t('personScoped')}</p>}
        {emailHint && <p className="text-xs text-theme-secondary mt-1">{emailHint}</p>}
        {deliversNothing && (
          <p className="text-xs text-amber-600 dark:text-amber-400 mt-1">{t('deliversNothing')}</p>
        )}
      </div>
      <Select
        value={pref.delivery}
        onValueChange={(v) => onChange(v as NotificationDelivery)}
        disabled={busy}
      >
        <SelectTrigger className="w-full sm:w-48 shrink-0" aria-label={t(`topics.${topicKey}.title`)}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {DELIVERIES.map((d) => (
            <SelectItem key={d} value={d} disabled={d !== pref.delivery && unavailable(d)}>
              {t(`delivery.${d.toLowerCase()}`)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
