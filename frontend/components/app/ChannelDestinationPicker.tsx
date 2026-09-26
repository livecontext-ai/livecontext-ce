'use client';

import * as React from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { chatChannelService } from '@/lib/api/orchestrator';
import type { ChatChannelListResponse, ChatChannelSummary } from '@/lib/api/orchestrator';
import { CHAT_CHANNELS_QUERY_KEY, findChatChannel } from '@/lib/chatChannels';
import { getClientLocale } from '@/lib/utils/locale';

/** Radix Select cannot hold an empty value, so "the workspace default" has a token of its own. */
export const DEFAULT_DESTINATION = '__default__';

/** How a destination is named to a person: its service, then its chat. */
export function destinationLabel(destination: ChatChannelSummary): string {
  const service = findChatChannel(destination.channel)?.label ?? destination.channel;
  return `${service} · ${destination.chatTitle || destination.chatId}`;
}

/** A destination that can receive something: switched on, and a message has reached it. */
export const isWorking = (destination: ChatChannelSummary) => destination.active && destination.verifiedAt !== null;

/** The workspace's destinations, from the same workspace-keyed cache entry as Settings > Channels. */
export function useChatDestinations() {
  const auth = useOptionalAuth();
  const query = useOrgScopedQuery<ChatChannelListResponse>({
    queryKey: [CHAT_CHANNELS_QUERY_KEY],
    queryFn: () => chatChannelService.list(),
    enabled: !!auth?.isAuthenticated,
  });
  const destinations = query.data?.channels ?? [];
  return {
    destinations,
    workspaceDefault: destinations.find((d) => d.isDefault && isWorking(d)) ?? null,
    isLoading: query.isLoading,
    isError: query.isError,
    /** Re-reads the list, after a change made elsewhere (e.g. a new workspace default). */
    refetch: query.refetch,
  };
}

interface ChannelDestinationPickerProps {
  /** The chosen destination's linkId, or null for the workspace default. */
  value: string | null;
  onChange: (value: string | null, destination: ChatChannelSummary | null) => void;
  /** Names the select for assistive tech (the visible label belongs to the caller). */
  ariaLabel: string;
  disabled?: boolean;
  /** One more choice after the destinations, owned by the caller (e.g. "another chat, by id"). */
  extraOption?: { value: string; label: string };
  /** Set when the extra option is the current choice, so the select shows it. */
  extraSelected?: boolean;
}

/**
 * Where something reaches the person outside the app, picked like a credential: the workspace
 * default, or one of the workspace's connected destinations.
 *
 * <p>Only destinations that work can be picked (a message reached them). A choice that no longer
 * exists stays visible as "disconnected" instead of silently reading as the default: the backend
 * sends nothing there until another one is picked, and the person has to see that.
 */
export function ChannelDestinationPicker({
  value,
  onChange,
  ariaLabel,
  disabled = false,
  extraOption,
  extraSelected = false,
}: ChannelDestinationPickerProps) {
  const t = useTranslations('channelPicker');
  const { destinations, workspaceDefault, isLoading, isError } = useChatDestinations();
  const manageHref = `/${getClientLocale()}/app/settings/channels`;

  if (isLoading) {
    return <p className="text-sm text-theme-secondary">{t('loading')}</p>;
  }
  if (isError) {
    return <p className="text-sm text-red-500" role="alert">{t('error')}</p>;
  }

  const chosen = value ? destinations.find((d) => d.linkId === value) ?? null : null;
  const gone = value !== null && !extraSelected && (chosen === null || !chosen.active);
  const selectValue = extraSelected && extraOption ? extraOption.value : value ?? DEFAULT_DESTINATION;

  if (destinations.length === 0 && !value && !extraSelected) {
    return (
      <div className="space-y-1" data-testid="channel-picker-empty">
        <p className="text-sm text-theme-secondary">{t('empty')}</p>
        <Link href={manageHref} className="text-sm font-medium text-[var(--accent-primary)] underline underline-offset-2">
          {t('connect')}
        </Link>
        {extraOption && (
          <button
            type="button"
            disabled={disabled}
            onClick={() => onChange(extraOption.value, null)}
            className="block text-sm text-theme-secondary underline underline-offset-2"
          >
            {extraOption.label}
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-1" data-testid="channel-picker">
      <Select
        value={selectValue}
        disabled={disabled}
        onValueChange={(next) => {
          if (next === DEFAULT_DESTINATION) {
            onChange(null, workspaceDefault);
          } else if (extraOption && next === extraOption.value) {
            onChange(extraOption.value, null);
          } else {
            onChange(next, destinations.find((d) => d.linkId === next) ?? null);
          }
        }}
      >
        <SelectTrigger className="w-full" aria-label={ariaLabel}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={DEFAULT_DESTINATION}>
            {workspaceDefault
              ? t('defaultOf', { destination: destinationLabel(workspaceDefault) })
              : t('defaultNone')}
          </SelectItem>
          {destinations
            // The default's own chat is already the first option: listed again it read as a second
            // destination. Kept when it IS the current choice (pinned), so the select can show it.
            // Deliberate trade-off: pinning to the chat that is the default TODAY is not offered;
            // following the default reaches the same chat, and pinning another chat still works.
            .filter((destination) => destination.linkId !== workspaceDefault?.linkId || value === destination.linkId)
            .map((destination) => (
            <SelectItem key={destination.linkId} value={destination.linkId} disabled={!isWorking(destination)}>
              {isWorking(destination)
                ? destinationLabel(destination)
                : t('notWorking', { destination: destinationLabel(destination) })}
            </SelectItem>
          ))}
          {/* Only when it is not in the list at all: a switched-off one is listed above, disabled. */}
          {gone && value && chosen === null && (
            <SelectItem value={value} disabled>
              {t('gone')}
            </SelectItem>
          )}
          {extraOption && <SelectItem value={extraOption.value}>{extraOption.label}</SelectItem>}
        </SelectContent>
      </Select>
      {gone && (
        <p className="text-sm text-red-500" role="alert" data-testid="channel-picker-gone">{t('goneHint')}</p>
      )}
      {!gone && value === null && !extraSelected && !workspaceDefault && (
        <p className="text-sm text-theme-secondary" data-testid="channel-picker-no-default">{t('defaultNoneHint')}</p>
      )}
      <Link href={manageHref} className="text-sm text-theme-secondary underline underline-offset-2">
        {t('manage')}
      </Link>
    </div>
  );
}
