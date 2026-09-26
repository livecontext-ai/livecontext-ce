'use client';

import React, { useCallback, useState } from 'react';
import { useTranslations } from 'next-intl';
import { CheckCircle2, MessagesSquare, Sparkles, Trash2, User } from 'lucide-react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { PageHeader } from '@/components/settings';
import { Button } from '@/components/ui/button';
import { ServiceIcon } from '@/components/ui/service-icon';
import { ChatDestinationCard, displayOf } from '@/components/app/ChatDestinationCard';
import Toast, { useToast } from '@/components/Toast';
import { ConfirmDeleteModal } from '@/components/chat/ConfirmDeleteModal';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { openAiChatTab } from '@/lib/sidePanel/openAiChatTab';
import { queueAiChatMessage } from '@/lib/sidePanelChat';
import { chatChannelService } from '@/lib/api/orchestrator';
import type { ChatChannelListResponse, ChatChannelSummary } from '@/lib/api/orchestrator';
import { CHAT_CHANNELS, CHAT_CHANNELS_QUERY_KEY, type ChatChannelInfo } from '@/lib/chatChannels';
import { useCanMutateInCurrentOrg, useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { ManualChannelSetup } from './components/ManualChannelSetup';
import { track } from '@/lib/analytics/analytics';

/**
 * Settings > Channels: where the workspace reaches its people outside the app.
 *
 * Connecting is guided by the assistant by default: each service needs a few steps in its own
 * console (a bot to create, a key to copy, a URL to paste), and the assistant runs them one at a
 * time with the `channel` tool, from a request this page sends for the person. The same steps
 * are also offered as a form ("Set up manually"), which costs no assistant turn: the backend
 * decides everything identically. This page also lists what is connected, moves the default
 * and disconnects.
 */
export default function ChannelsSettingsPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const t = useTranslations('channelsSettings');
  const tSettings = useTranslations('settings');
  const { toasts, addToast, removeToast } = useToast();
  const sidePanel = useSidePanelSafe();

  // Destinations belong to the workspace: keyed by it (useOrgScopedQuery), so a workspace switch
  // never shows the previous workspace's list, even for the moment its refetch takes.
  const { data, isLoading, isError, refetch } = useOrgScopedQuery<ChatChannelListResponse>({
    queryKey: [CHAT_CHANNELS_QUERY_KEY],
    queryFn: () => chatChannelService.list(),
    enabled: isAuthenticated,
  });
  const destinations = data?.channels ?? [];
  // The backend refuses every change to where the workspace is reached from a read-only role
  // (a 403), so such a member is shown the list without actions that could only fail. The shared
  // gate also withholds them while an org role is still unknown, rather than flashing them.
  const readOnly = !useCanMutateInCurrentOrg();
  // The explanation only once the role is known to be read-only, never while it loads.
  const isViewer = useCurrentOrgStore((s) => s.currentOrgRole) === 'VIEWER';

  const [busyLinkId, setBusyLinkId] = useState<string | null>(null);
  const [disconnectTarget, setDisconnectTarget] = useState<ChatChannelSummary | null>(null);
  const [manualChannel, setManualChannel] = useState<ChatChannelInfo | null>(null);

  const askAssistant = useCallback((message: string) => {
    if (!sidePanel?.openTab) {
      // Nothing to open the assistant in: say so, rather than a button that silently does nothing.
      addToast({ type: 'error', title: t('assistantUnavailable'), message: '' });
      return;
    }
    openAiChatTab(sidePanel);
    queueAiChatMessage(message);
  }, [sidePanel, addToast, t]);

  const setUp = useCallback((channel: ChatChannelInfo) => {
    track('channel_assistant_help_clicked', { channel: channel.id, intent: 'setup' });
    askAssistant(t('setupPrompt', { channel: channel.label, id: channel.id }));
  }, [askAssistant, t]);

  const troubleshoot = useCallback((destination: ChatChannelSummary) => {
    const channel = displayOf(destination.channel);
    track('channel_assistant_help_clicked', { channel: destination.channel, intent: 'troubleshoot' });
    askAssistant(t('troubleshootPrompt', {
      channel: channel.label,
      chat: destination.chatTitle || destination.chatId,
      error: destination.lastError ?? '',
    }));
  }, [askAssistant, t]);

  const makeDefault = useCallback(async (destination: ChatChannelSummary) => {
    setBusyLinkId(destination.linkId);
    try {
      await chatChannelService.setDefault(destination.linkId);
      await refetch();
    } catch (error) {
      addToast({ type: 'error', title: t('error'), message: error instanceof Error ? error.message : '' });
    } finally {
      setBusyLinkId(null);
    }
  }, [addToast, refetch, t]);

  const confirmDisconnect = useCallback(async () => {
    if (!disconnectTarget) return;
    setBusyLinkId(disconnectTarget.linkId);
    try {
      await chatChannelService.disconnect(disconnectTarget.linkId);
      setDisconnectTarget(null);
      await refetch();
    } catch (error) {
      addToast({ type: 'error', title: t('error'), message: error instanceof Error ? error.message : '' });
    } finally {
      setBusyLinkId(null);
    }
  }, [addToast, disconnectTarget, refetch, t]);

  if (isAuthChecking) {
    return (
      <div className="space-y-8">
        <PageHeader icon={MessagesSquare} title={t('title')} subtitle={t('subtitle')} />
        <div className="text-sm text-theme-secondary">{t('loading')}</div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="space-y-8">
        <PageHeader icon={MessagesSquare} title={t('title')} subtitle={t('subtitle')} />
        <div className="min-h-[200px] flex items-center justify-center">
          <div className="text-center">
            <h2 className="text-lg font-semibold text-theme-primary mb-2">{tSettings('unauthorized')}</h2>
            <p className="text-sm text-theme-secondary mb-4">{tSettings('mustBeLoggedIn')}</p>
            <Button onClick={() => loginWithRedirect()}>
              <User className="h-3.5 w-3.5 mr-1" />
              {tSettings('signIn')}
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <PageHeader icon={MessagesSquare} title={t('title')} subtitle={t('subtitle')} />

      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast key={toast.id} id={toast.id} type={toast.type} title={toast.title}
              message={toast.message} onClose={removeToast} />
          ))}
        </div>
      )}

      {/* Connected destinations */}
      <section className="space-y-3">
        <h2 className="text-base font-semibold text-theme-primary">{t('connectedTitle')}</h2>
        {isLoading ? (
          <div className="text-sm text-theme-secondary">{t('loading')}</div>
        ) : isError ? (
          // Not the empty state: "nothing connected" would be a false answer here.
          <p className="text-sm text-red-500" role="alert" data-testid="chat-channels-error">{t('error')}</p>
        ) : destinations.length === 0 ? (
          <div className="border border-dashed border-theme rounded-lg p-6 text-center">
            <p className="text-sm font-medium text-theme-primary mb-1">{t('emptyTitle')}</p>
            <p className="text-sm text-theme-secondary">{t('emptyHint')}</p>
          </div>
        ) : (
          <ul className="space-y-2" data-testid="chat-channel-destinations">
            {destinations.map((destination) => {
              const working = destination.active && destination.verifiedAt !== null;
              return (
                <li key={destination.linkId}>
                  <ChatDestinationCard
                    destination={destination}
                    actions={readOnly ? null : (
                      <div className="flex max-w-full flex-wrap items-center gap-2">
                        {!working && (
                          <Button size="sm" variant="outline" onClick={() => troubleshoot(destination)}
                            className="h-auto min-h-8 max-w-full whitespace-normal text-left">
                            <Sparkles className="h-3.5 w-3.5 mr-1" />
                            {t('troubleshoot')}
                          </Button>
                        )}
                        {!destination.isDefault && working && (
                          <Button size="sm" variant="outline" disabled={busyLinkId === destination.linkId}
                            onClick={() => makeDefault(destination)} className="h-auto min-h-8 max-w-full whitespace-normal text-left">
                            <CheckCircle2 className="h-3.5 w-3.5 mr-1" />
                            {t('makeDefault')}
                          </Button>
                        )}
                        <Button size="sm" variant="ghost" disabled={busyLinkId === destination.linkId}
                          onClick={() => setDisconnectTarget(destination)} aria-label={t('disconnect')}>
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    )}
                  />
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {/* Services */}
      <section className="space-y-3">
        <div>
          <h2 className="text-base font-semibold text-theme-primary">{t('servicesTitle')}</h2>
          <p className="text-sm text-theme-secondary">{t('servicesHint')}</p>
          {isViewer && (
            <p className="text-sm text-theme-secondary mt-1" data-testid="chat-channels-read-only">{t('readOnly')}</p>
          )}
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          {CHAT_CHANNELS.map((channel) => (
            <div key={channel.id} data-testid={`chat-channel-service-${channel.id}`}
              className="flex min-w-0 flex-col gap-2 rounded-lg border border-theme p-4">
              <div className="flex items-center gap-2">
                <ServiceIcon iconSlug={channel.integration} size="md" />
                <span className="text-sm font-semibold text-theme-primary">{channel.label}</span>
              </div>
              <p className="text-sm text-theme-secondary flex-1">{t(`services.${channel.id}.needs`)}</p>
              <p className="text-xs text-theme-secondary">{t(`services.${channel.id}.time`)}</p>
              {!readOnly && (
              <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                <Button size="sm" onClick={() => setUp(channel)} className="h-auto min-h-8 max-w-full whitespace-normal text-left w-full sm:w-auto">
                  <Sparkles className="h-3.5 w-3.5 mr-1" />
                  {t('setUpWithAssistant')}
                </Button>
                <Button size="sm" variant="outline" onClick={() => setManualChannel(channel)}
                  className="h-auto min-h-8 max-w-full whitespace-normal text-left w-full sm:w-auto" data-testid={`manual-setup-${channel.id}`}>
                  {t('setUpManually')}
                </Button>
              </div>
              )}
            </div>
          ))}
        </div>
      </section>

      {manualChannel && (
        <ManualChannelSetup
          key={manualChannel.id}
          channel={manualChannel}
          connectedCredentialIds={destinations
            .filter((d) => d.channel === manualChannel.id && d.credentialId !== null)
            .map((d) => d.credentialId as number)}
          onClose={() => setManualChannel(null)}
          onConnected={() => { void refetch(); }}
          botUsernames={Object.fromEntries(destinations
            .filter((d) => d.channel === manualChannel.id && d.credentialId !== null && d.botUsername)
            .map((d) => [d.credentialId as number, d.botUsername as string]))}
          onAskAssistant={readOnly ? undefined : () => {
            const target = manualChannel;
            setManualChannel(null);
            setUp(target);
          }}
        />
      )}

      <ConfirmDeleteModal
        isOpen={disconnectTarget !== null}
        title={t('disconnectTitle')}
        message={t('disconnectMessage', {
          chat: disconnectTarget ? disconnectTarget.chatTitle || disconnectTarget.chatId : '',
        })}
        onConfirm={confirmDisconnect}
        onCancel={() => setDisconnectTarget(null)}
        isLoading={busyLinkId !== null && busyLinkId === disconnectTarget?.linkId}
      />
    </div>
  );
}
