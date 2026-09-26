'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Star } from 'lucide-react';
import { ServiceIcon } from '@/components/ui/service-icon';
import type { ChatChannelSummary } from '@/lib/api/orchestrator';
import { findChatChannel } from '@/lib/chatChannels';

/** How a stored destination is shown: its service's name and icon, or the raw id if unknown. */
export function displayOf(channel: string): { label: string; icon: string } {
  const known = findChatChannel(channel);
  return known ? { label: known.label, icon: known.integration } : { label: channel, icon: channel };
}

interface ChatDestinationCardProps {
  destination: ChatChannelSummary;
  /** Buttons on the right (Settings > Channels: make default, troubleshoot, disconnect). */
  actions?: React.ReactNode;
  /** Also name the bot account the messages are sent from (the credential that will be used). */
  showBot?: boolean;
  /** A note that belongs to this destination, drawn inside the same frame, under it. */
  footer?: React.ReactNode;
  'data-testid'?: string;
}

/**
 * One connected chat destination, drawn the way Settings > Channels lists it: the service's icon,
 * the chat, the service, a "Default" badge, whether a message has reached it, and who may decide.
 * Every screen that shows where the workspace is reached uses this, so a destination looks the
 * same wherever it is named.
 */
export function ChatDestinationCard({
  destination,
  actions,
  showBot = false,
  footer,
  'data-testid': testId,
}: ChatDestinationCardProps) {
  const t = useTranslations('channelsSettings');
  const channel = displayOf(destination.channel);
  const working = destination.active && destination.verifiedAt !== null;
  return (
    <div className="rounded-lg border border-theme p-3" data-testid={testId}>
      <div className="flex flex-wrap items-center gap-3">
        <ServiceIcon iconSlug={channel.icon} size="md" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium text-theme-primary truncate">
              {destination.chatTitle || destination.chatId}
            </span>
            <span className="text-xs text-theme-secondary">{channel.label}</span>
            {destination.isDefault && (
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-600 dark:text-emerald-400">
                <Star className="h-3 w-3" />
                {t('defaultBadge')}
              </span>
            )}
          </div>
          {showBot && destination.botUsername && (
            <p className="text-sm text-theme-secondary" data-testid="chat-destination-bot">
              {t('sentByBot', { bot: destination.botUsername })}
            </p>
          )}
          <p className="text-xs text-theme-secondary">
            {working ? t('statusWorking') : t('statusNotDelivered')}
            {destination.allowedUserIds.length > 0
              ? ` · ${t('restricted', { count: destination.allowedUserIds.length })}`
              : ''}
          </p>
          {destination.lastError && (
            <p className="text-xs text-red-500 break-words">{destination.lastError}</p>
          )}
        </div>
        {actions}
      </div>
      {footer && <div className="mt-3 border-t border-theme pt-3">{footer}</div>}
    </div>
  );
}
