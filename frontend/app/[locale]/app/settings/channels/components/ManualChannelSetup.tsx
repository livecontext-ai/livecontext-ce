'use client';

import React, { useState } from 'react';
import { useTranslations } from 'next-intl';
import { Check, CheckCircle2, Copy, ExternalLink, Info, Loader2, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { CredentialSection } from '@/app/workflows/builder/components/inspector/CredentialSection';
import { chatChannelService } from '@/lib/api/orchestrator';
import type { ChatCandidate, ChatChannelConnectResult } from '@/lib/api/orchestrator';
import type { ChatChannelInfo } from '@/lib/chatChannels';
import { track } from '@/lib/analytics/analytics';

interface ManualChannelSetupProps {
  channel: ChatChannelInfo | null;
  /**
   * Credentials of this service already connected in THIS workspace. The backend keeps the account
   * setting it stored for such a bot when none is sent, so it is only required for a bot seen for the
   * first time; and on Telegram such a bot has its webhook, so its chats cannot be listed.
   */
  connectedCredentialIds?: number[];
  onClose: () => void;
  /** Called after a connect attempt, delivered or not: the list has a new row either way. */
  onConnected: () => void;
  /**
   * The bot of each credential already connected on this service, when known, so "open your
   * bot" can be offered before any search (a connected Telegram bot cannot be searched).
   */
  botUsernames?: Record<number, string>;
  /** Hand the setup to the assistant instead (closes this form). Absent: no such button. */
  onAskAssistant?: () => void;
}

/** A page of the service the person is sent to, with the label key under guide.links. */
interface GuideLink { key: string; href: string }

/**
 * Where each step of a service's how-to is shown. The texts are the messages
 * channelsSettings.manual.guide.<channel>.<n>; each one sits in the section it is about, with the
 * button it needs next to it, so the person reads and acts in the same place.
 */
interface ChannelGuide {
  /** Steps shown in "your account", before the credential picker, and the pages they need. */
  account: number[];
  accountLinks?: GuideLink[];
  /** The step asking the person to write to their bot first (Telegram), shown on its own. */
  hello?: number;
  /** Steps shown in "where messages go", and the pages they need. */
  destination: number[];
  destinationLinks?: GuideLink[];
}

const BOT_FATHER: GuideLink = { key: 'botFather', href: 'https://t.me/BotFather' };
const USER_INFO_BOT: GuideLink = { key: 'userInfoBot', href: 'https://t.me/userinfobot' };

export const GUIDES: Record<string, ChannelGuide> = {
  telegram: { account: [1, 2], accountLinks: [BOT_FATHER], hello: 3, destination: [4], destinationLinks: [USER_INFO_BOT] },
  slack: { account: [1], destination: [2, 3] },
  discord: {
    account: [1, 2, 3],
    accountLinks: [{ key: 'discordPortal', href: 'https://discord.com/developers/applications' }],
    destination: [4],
  },
  whatsapp: {
    account: [1, 2],
    accountLinks: [{ key: 'metaApps', href: 'https://developers.facebook.com/apps' }],
    destination: [3, 4],
  },
  teams: { account: [1], destination: [2, 3] },
};

/** Whether a typed destination is the bot itself (its @username, with or without the @). */
export function isTheBotItself(typed: string, botUsername: string | null | undefined): boolean {
  if (!botUsername) {
    return false;
  }
  const bare = typed.trim().replace(/^@/, '');
  return bare !== '' && bare.toLowerCase() === botUsername.toLowerCase();
}

const errorText = (error: unknown) => (error instanceof Error ? error.message : '');

/**
 * Connecting a channel by hand: the same three steps the assistant runs (account, destination,
 * test message), as a form, so connecting costs no assistant turn.
 *
 * <p>Everything is decided by the backend exactly as for the assistant: which chats are offered,
 * whether the account setting is valid (a Discord key is checked with Discord), whether the test
 * message arrived. This form only collects the choices and shows the answer, including what is
 * left to do in the service's own console.
 *
 * <p>A destination is picked from the chats the account can see, or typed. Typing is not a
 * fallback for rare cases: once a Telegram bot has its webhook, Telegram stops listing its chats,
 * so a second destination on the same bot, or a public channel's @name, can only be typed.
 */
export function ManualChannelSetup({
  channel, connectedCredentialIds = [], onClose, onConnected, botUsernames = {}, onAskAssistant,
}: ManualChannelSetupProps) {
  const t = useTranslations('channelsSettings.manual');
  const [credentialId, setCredentialId] = useState<number | null>(null);
  const [accountSetting, setAccountSetting] = useState('');
  const [chats, setChats] = useState<ChatCandidate[] | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [discoveredBot, setDiscoveredBot] = useState<string | null>(null);
  const [chosen, setChosen] = useState<ChatCandidate | null>(null);
  const [typedChat, setTypedChat] = useState('');
  const [busy, setBusy] = useState<'discover' | 'connect' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ChatChannelConnectResult | null>(null);
  const [copied, setCopied] = useState(false);

  if (!channel) {
    return null;
  }

  const settingKept = credentialId !== null && connectedCredentialIds.includes(credentialId);
  const chatId = chosen?.chatId ?? typedChat.trim();
  const botUsername = discoveredBot ?? (credentialId !== null ? botUsernames[credentialId] ?? null : null);
  const typedIsTheBot = !chosen && isTheBotItself(typedChat, botUsername);
  // Telegram only lists the chats of a bot with no webhook, and connecting a bot sets one: for a
  // bot already connected the button could only ever answer "unavailable", so it is not offered.
  const canFindChats = channel.discoversChats
    && !(channel.id === 'telegram' && credentialId !== null && connectedCredentialIds.includes(credentialId));
  const guide = GUIDES[channel.id] ?? { account: [], destination: [] };

  const discover = async () => {
    setBusy('discover');
    setError(null);
    setNotice(null);
    setChosen(null);
    try {
      const found = await chatChannelService.discover(channel.id, credentialId);
      setDiscoveredBot(found.botUsername ?? null);
      setNotice(found.notice ?? null);
      setChats(found.notice ? null : found.chats);
      // A notice replaces the list (the provider cannot list chats), so it counts as none found.
      track('channel_discovery_run', {
        channel: channel.id,
        chats_found: found.notice ? 0 : (found.chats?.length ?? 0),
      });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(null);
    }
  };

  const connect = async () => {
    if (!chatId) {
      return;
    }
    setBusy('connect');
    setError(null);
    try {
      const outcome = await chatChannelService.connect({
        channel: channel.id,
        credentialId,
        chatId,
        chatTitle: chosen?.title ?? null,
        chatType: chosen?.type ?? null,
        accountSetting: channel.accountSetting ? accountSetting.trim() || null : null,
      });
      setResult(outcome);
      onConnected();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(null);
    }
  };

  const copyInstructions = async () => {
    try {
      await navigator.clipboard.writeText(result?.setupInstructions ?? '');
      setCopied(true);
    } catch {
      // Clipboard refused (permissions, insecure context): the text stays selectable on screen.
    }
  };

  const ready = !!credentialId
    && (!channel.accountSetting || settingKept || accountSetting.trim() !== '')
    && chatId !== ''
    && !typedIsTheBot;

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-lg" aria-busy={busy !== null}>
        <DialogHeader>
          <DialogTitle>{t('title', { channel: channel.label })}</DialogTitle>
          <DialogDescription>{t('intro')}</DialogDescription>
          {onAskAssistant && !result && (
            <div>
              <button type="button" onClick={onAskAssistant} className={LINK_BUTTON} data-testid="manual-ask-assistant">
                <Sparkles className="h-3.5 w-3.5" />{t('guide.askAssistant')}
              </button>
            </div>
          )}
        </DialogHeader>

        {result ? (
          <div className="space-y-3" data-testid="manual-result" role="status">
            <p className={`flex items-center gap-2 text-sm ${result.delivered ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-500'}`}>
              {result.delivered && <CheckCircle2 className="h-3.5 w-3.5" />}
              {result.delivered ? t('delivered') : t('notDelivered')}
            </p>
            {result.warning && (
              <div className="space-y-1">
                <p className="text-sm text-[var(--text-secondary)]">{t('details')}</p>
                <p className="text-sm text-[var(--text-secondary)] break-words">{result.warning}</p>
              </div>
            )}
            {result.setupInstructions && (
              <div className="rounded-lg border border-theme p-3 space-y-2">
                <div className="flex items-center justify-between gap-2">
                  <p className="text-sm font-medium text-theme-primary">{t('remaining')}</p>
                  <Button size="sm" variant="ghost" onClick={copyInstructions} data-testid="manual-copy-steps">
                    {copied ? <Check className="h-3.5 w-3.5 mr-1" /> : <Copy className="h-3.5 w-3.5 mr-1" />}
                    {copied ? t('copied') : t('copy')}
                  </Button>
                </div>
                <p className="text-sm text-[var(--text-secondary)] break-words select-text" data-testid="manual-setup-steps">
                  {result.setupInstructions}
                </p>
              </div>
            )}
          </div>
        ) : (
          <div className="space-y-4">
            {/* 1. The account: how to get it, where to get it, then the picker */}
            <section className="space-y-2" data-testid="manual-step-account">
              <h3 className="text-sm font-semibold text-theme-primary">{`1. ${t('stepAccount', { channel: channel.label })}`}</h3>
              <StepTexts t={t} channelId={channel.id} steps={guide.account} />
              <LinkButtons t={t} links={guide.accountLinks} />
              <CredentialSection
                toolCredentials={[{ credentialName: channel.integration, isRequired: true, displayName: channel.label }]}
                selectedCredentialId={credentialId}
                onCredentialSelect={(id) => {
                  setCredentialId(id);
                  setChats(null);
                  setNotice(null);
                  setDiscoveredBot(null);
                  setChosen(null);
                }}
                integration={channel.integration}
              />
            </section>

            {/* The one extra value some services need */}
            {channel.accountSetting && (
              <div className="space-y-1">
                <label htmlFor="channel-account-setting" className="text-sm font-semibold text-theme-primary">
                  {t(`accountSetting.${channel.accountSetting}.label`)}
                </label>
                <Input
                  id="channel-account-setting"
                  value={accountSetting}
                  onChange={(e) => setAccountSetting(e.target.value)}
                  placeholder={t(`accountSetting.${channel.accountSetting}.placeholder`)}
                />
                <p className="text-sm text-[var(--text-secondary)]">
                  {settingKept ? t('accountSettingKept') : t(`accountSetting.${channel.accountSetting}.hint`)}
                </p>
              </div>
            )}

            {/* 2. Telegram only: a bot cannot write to someone who never wrote to it */}
            {guide.hello !== undefined && (
              <section className="space-y-2" data-testid="manual-step-hello">
                <h3 className="text-sm font-semibold text-theme-primary">{`2. ${t('stepHello')}`}</h3>
                <StepTexts t={t} channelId={channel.id} steps={[guide.hello]} />
                {botUsername && (
                  <div className="flex flex-wrap gap-2">
                    <a href={`https://t.me/${botUsername}`} target="_blank" rel="noopener noreferrer"
                      className={LINK_BUTTON} data-testid="manual-open-bot">
                      <ExternalLink className="h-3.5 w-3.5" />{t('guide.openBot', { bot: botUsername })}
                    </a>
                  </div>
                )}
              </section>
            )}

            {/* Last: where messages go */}
            <fieldset className="space-y-2" data-testid="manual-step-destination">
              <legend className="text-sm font-semibold text-theme-primary">
                {`${guide.hello !== undefined ? 3 : 2}. ${t('stepDestination')}`}
              </legend>
              <StepTexts t={t} channelId={channel.id} steps={guide.destination} />
              {canFindChats && (
                <>
                  <Button variant="outline" size="sm" onClick={discover} disabled={!credentialId || busy !== null}
                    className="h-auto min-h-8 max-w-full whitespace-normal text-left" data-testid="manual-find-chats"
                    aria-describedby="channel-find-chats-hint">
                    {busy === 'discover' && <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />}
                    {t('findChats')}
                  </Button>
                  <p id="channel-find-chats-hint" className="text-sm text-[var(--text-secondary)]">
                    {t(`findChatsHint.${channel.id}`)}
                  </p>
                  {notice && (
                    <p className="flex items-start gap-1.5 text-sm text-[var(--text-secondary)]" role="status" data-testid="manual-notice">
                      <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      <span>{channel.id === 'telegram' ? t('listUnavailable.telegram') : notice}</span>
                    </p>
                  )}
                  {chats && chats.length === 0 && (
                    <p className="text-sm text-[var(--text-secondary)]">{t(`noChats.${channel.id}`)}</p>
                  )}
                  {chats && chats.length > 0 && (
                    <ul className="max-h-48 space-y-1 overflow-y-auto" data-testid="manual-chats">
                      {chats.map((chat) => (
                        <li key={chat.chatId}>
                          <label className="flex cursor-pointer items-center gap-2 rounded-md p-1.5 text-sm hover:bg-theme-tertiary">
                            <input
                              type="radio"
                              name="chat"
                              checked={chosen?.chatId === chat.chatId}
                              onChange={() => {
                                setChosen(chat);
                                setTypedChat('');
                              }}
                            />
                            <span className="truncate text-theme-primary">{chat.title || chat.chatId}</span>
                          </label>
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
              <div className="space-y-1">
                <label htmlFor="channel-chat-id" className="text-sm text-theme-primary">
                  {!channel.discoversChats ? t('phoneLabel') : canFindChats ? t('typeChatLabel') : t('chatIdLabel')}
                </label>
                {/* The field on its own line, full width, so its example stays readable. */}
                <Input
                  id="channel-chat-id"
                  value={typedChat}
                  onChange={(e) => {
                    setTypedChat(e.target.value);
                    setChosen(null);
                  }}
                  placeholder={t(`chatIdPlaceholder.${channel.id}`)}
                />
                <LinkButtons t={t} links={guide.destinationLinks} />
                {typedIsTheBot ? (
                  <p className="text-sm text-red-500" role="alert" data-testid="manual-bot-itself">
                    {t('botItself', { bot: botUsername ?? '' })}
                  </p>
                ) : (
                  <p className="text-sm text-[var(--text-secondary)]">
                    {channel.discoversChats ? t(`typeChatHint.${channel.id}`) : t('phoneHint')}
                  </p>
                )}
              </div>
            </fieldset>
          </div>
        )}

        {error && <p className="text-sm text-red-500 break-words" role="alert">{error}</p>}

        <DialogFooter>
          {result ? (
            <>
              {!result.delivered && (
                <Button variant="outline" onClick={() => { setResult(null); setCopied(false); }}
                  className="h-auto min-h-8 max-w-full whitespace-normal text-left w-full sm:w-auto">
                  {t('tryAgain')}
                </Button>
              )}
              <Button onClick={onClose} className="h-auto min-h-8 max-w-full whitespace-normal text-left w-full sm:w-auto">{t('close')}</Button>
            </>
          ) : (
            <Button onClick={connect} disabled={!ready || busy !== null} className="h-auto min-h-8 max-w-full whitespace-normal text-left w-full sm:w-auto">
              {busy === 'connect' && <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />}
              {t('connect')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const LINK_BUTTON = 'inline-flex max-w-full items-center gap-1 rounded-md border border-theme px-2 py-1 text-sm text-theme-primary hover:bg-theme-tertiary';

type Translate = ReturnType<typeof useTranslations>;

/** The how-to lines of one section, as a short numbered list. */
function StepTexts({ t, channelId, steps }: { t: Translate; channelId: string; steps: number[] }) {
  if (steps.length === 0) {
    return null;
  }
  return (
    <ul className="list-disc space-y-1 pl-5 text-sm text-[var(--text-secondary)]">
      {steps.map((n) => <li key={n}>{t(`guide.${channelId}.${n}`)}</li>)}
    </ul>
  );
}

/** The service pages a section sends the person to, opened in a new tab. */
function LinkButtons({ t, links }: { t: Translate; links?: GuideLink[] }) {
  if (!links || links.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-wrap gap-2">
      {links.map((link) => (
        <a key={link.key} href={link.href} target="_blank" rel="noopener noreferrer" className={LINK_BUTTON}
          data-testid={`manual-link-${link.key}`}>
          <ExternalLink className="h-3.5 w-3.5" />{t(`guide.links.${link.key}`)}
        </a>
      ))}
    </div>
  );
}
