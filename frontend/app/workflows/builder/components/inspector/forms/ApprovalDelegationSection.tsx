'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { CHAT_CHANNELS, chatChannelInfo, type ChatChannelId } from '@/lib/chatChannels';
import { getClientLocale } from '@/lib/utils/locale';
import type { ApprovalDelegation } from '../../../types';
import { CredentialSection } from '../CredentialSection';
import { ChannelDestinationPicker, useChatDestinations } from '@/components/app/ChannelDestinationPicker';
import type { ChatChannelSummary } from '@/lib/api/orchestrator';
import { InfoPopover } from '@/components/ui/info-popover';
import { track } from '@/lib/analytics/analytics';

/** The picker's extra choice: name a service and a chat by hand (the older shape). */
const CUSTOM_DESTINATION = '__custom__';
/** The plan's keyword for the workspace default destination. */
const DEFAULT_LINK = 'default';

/**
 * What a destination id looks like on each service (code/syntax tokens, intentionally not
 * translated). Every one also accepts a {{...}} expression.
 */
const CHAT_ID_PLACEHOLDERS: Record<ChatChannelId, string> = {
  telegram: '-100123456789',
  slack: 'C0123456789',
  discord: '1154321098765432100',
  whatsapp: '+33612345678',
  teams: '19:abc123@thread.v2',
};

/** Syntax example for the allowed user ids (code/syntax token, intentionally not translated). */
const ALLOWED_USER_IDS_PLACEHOLDER = '123456789, 987654321';

/** Syntax example for the optional image (code/syntax token, intentionally not translated). */
const IMAGE_PLACEHOLDER = '{{interface:card.output.screenshot}}';

/** Parses the comma-separated allowed user ids input into a trimmed, non-empty string[]. */
function parseAllowedUserIds(text: string): string[] {
  return text
    .split(',')
    .map((id) => id.trim())
    .filter((id) => id !== '');
}

interface ApprovalDelegationSectionProps {
  isRunMode?: boolean;
  approvalDelegation?: ApprovalDelegation;
  handleDelegationChange: (delegation: ApprovalDelegation | undefined) => void;
}

/**
 * "Delegate via external channel" section of the user approval inspector form.
 * Toggle off = no delegation (approvalDelegation removed from node data, plan emits no
 * `approval.delegation` block). Toggle on = one of the chat services, with an optional
 * credential and destination: left empty, the approval goes to the destination the
 * workspace connected on that service (Settings > Channels). A press on the message
 * resolves the approval.
 */
export function ApprovalDelegationSection({
  isRunMode = false,
  approvalDelegation,
  handleDelegationChange,
}: ApprovalDelegationSectionProps) {
  const t = useTranslations('workflowBuilder.forms.approval.delegation');

  const enabled = approvalDelegation !== undefined;
  const { destinations, workspaceDefault } = useChatDestinations();
  // A picked destination decides the service; the node's recorded one is only a fallback.
  const usesDestination = !!approvalDelegation?.linkId;
  const picked = !usesDestination ? null : approvalDelegation?.linkId === DEFAULT_LINK
    ? workspaceDefault
    : destinations.find((d) => d.linkId === approvalDelegation?.linkId) ?? null;
  const channel = chatChannelInfo(picked?.channel ?? approvalDelegation?.channel);

  const patch = React.useCallback((updates: Partial<ApprovalDelegation>) => {
    if (isRunMode) return;
    handleDelegationChange({ channel: 'telegram', ...approvalDelegation, ...updates });
  }, [isRunMode, approvalDelegation, handleDelegationChange]);

  // On: the workspace default destination, picked like a default credential. Naming a service
  // and a chat by hand stays one choice away ("another chat") for what the list cannot express.
  const handleToggle = React.useCallback((checked: boolean) => {
    if (isRunMode) return;
    const defaultChannel = chatChannelInfo(workspaceDefault?.channel).id;
    // Switched off there is no destination nor service left to describe.
    track('approval_channel_configured', checked
      ? { enabled: true, destination: 'default', channel: defaultChannel }
      : { enabled: false });
    handleDelegationChange(checked
      ? { channel: defaultChannel, linkId: DEFAULT_LINK }
      : undefined);
  }, [isRunMode, handleDelegationChange, workspaceDefault]);

  // Switching service drops what belongs to the previous one: a credential id names an
  // account of that service, and a destination id is written in its format. What does not
  // exist on the new service (an image, allowed people on Teams) is dropped too, so the plan
  // never carries a setting the send would silently ignore.
  const carryOver = React.useCallback((serviceId: string) => {
    const next = chatChannelInfo(serviceId);
    const { image, allowedUserIds, messageTemplate, approveLabel, rejectLabel } = approvalDelegation ?? {};
    return {
      channel: next.id,
      ...(messageTemplate !== undefined ? { messageTemplate } : {}),
      ...(approveLabel !== undefined ? { approveLabel } : {}),
      ...(rejectLabel !== undefined ? { rejectLabel } : {}),
      ...(next.sendsImages && image ? { image } : {}),
      ...(next.identifiesPresser && allowedUserIds ? { allowedUserIds } : {}),
    };
  }, [approvalDelegation]);

  const handleChannelChange = React.useCallback((value: string) => {
    if (isRunMode || !approvalDelegation) return;
    handleDelegationChange(carryOver(value));
  }, [isRunMode, approvalDelegation, handleDelegationChange, carryOver]);

  // A destination replaces the service, account and chat named by hand; "another chat" goes back
  // to naming them (on the service of what was picked, the likeliest next choice).
  const handleDestinationChange = React.useCallback(
    (value: string | null, destination: ChatChannelSummary | null) => {
      if (isRunMode || !approvalDelegation) return;
      if (value === CUSTOM_DESTINATION) {
        track('approval_channel_configured', { enabled: true, destination: 'custom', channel: channel.id });
        handleDelegationChange(carryOver(channel.id));
        return;
      }
      track('approval_channel_configured', {
        enabled: true,
        destination: value ? 'specific' : 'default',
        channel: chatChannelInfo(destination?.channel ?? channel.id).id,
      });
      handleDelegationChange({
        ...carryOver(destination?.channel ?? channel.id),
        linkId: value ?? DEFAULT_LINK,
      });
    },
    [isRunMode, approvalDelegation, handleDelegationChange, carryOver, channel.id],
  );

  // Defensive Number() coercion: some callers hand a numeric STRING id (e.g. "40").
  // The plan contract stores credentialId as a number, so coerce here and drop
  // only true non-numerics (matching the importer/exporter tolerance).
  const handleCredentialSelect = React.useCallback((credentialId: number | null) => {
    const coerced = credentialId == null ? Number.NaN : Number(credentialId);
    patch({ credentialId: Number.isFinite(coerced) ? coerced : undefined });
  }, [patch]);

  // Local text buffer for the comma-separated ids: a plain join/parse controlled
  // input would strip the separator the moment it is typed ("123," parses to
  // ["123"] and re-renders as "123"). The buffer only resyncs from node data when
  // it no longer parses to the stored array (e.g. another node was selected).
  const canonicalIdsText = (approvalDelegation?.allowedUserIds ?? []).join(', ');
  const [allowedIdsText, setAllowedIdsText] = React.useState(canonicalIdsText);
  React.useEffect(() => {
    setAllowedIdsText((prev) => (parseAllowedUserIds(prev).join(', ') === canonicalIdsText ? prev : canonicalIdsText));
  }, [canonicalIdsText]);

  const handleAllowedUserIdsChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const text = event.target.value;
    setAllowedIdsText(text);
    patch({ allowedUserIds: parseAllowedUserIds(text) });
  }, [patch]);

  const credentials = React.useMemo(() => [{
    credentialName: channel.integration,
    isRequired: false,
    displayName: channel.label,
    description: t('credentialDescription', { channel: channel.label }),
    credentialType: channel.integration,
  }], [channel, t]);

  return (
    <div className="flex flex-col gap-3 border-t border-slate-100 dark:border-slate-700 pt-4">
      {/* Header: title + info + enable toggle */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
          <InfoPopover label={t('title')} size="sm" side="right" align="start">
            <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
              <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
              <p>{t('infoBody')}</p>
              <p className="text-xs">{t('infoOptional')}</p>
            </div>
          </InfoPopover>
        </div>
        <Switch
          checked={enabled}
          onCheckedChange={handleToggle}
          disabled={isRunMode}
          aria-label={t('enableAria')}
        />
      </div>

      {enabled && (
        <div className="flex flex-col gap-3">
          {/* Destination, picked like a credential: the default, a connected one, or another chat */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('destinationLabel')}</span>
            <ChannelDestinationPicker
              value={usesDestination && approvalDelegation?.linkId !== DEFAULT_LINK ? approvalDelegation?.linkId ?? null : null}
              onChange={handleDestinationChange}
              ariaLabel={t('destinationLabel')}
              disabled={isRunMode}
              extraOption={{ value: CUSTOM_DESTINATION, label: t('customDestination') }}
              extraSelected={!usesDestination}
            />
            <p className="text-xs text-slate-400 dark:text-slate-500">{t('destinationHint')}</p>
          </div>

          {!usesDestination && (<>
          {/* Service - required while the section names its own chat */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('channelLabel')} <span className="text-red-500">*</span></span>
            <Select value={channel.id} onValueChange={handleChannelChange} disabled={isRunMode}>
              <SelectTrigger className="w-full" aria-label={t('channelLabel')}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {CHAT_CHANNELS.map((option) => (
                  <SelectItem key={option.id} value={option.id}>{option.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-slate-400 dark:text-slate-500">
              {t('channelHint')}{' '}
              <a
                href={`/${getClientLocale()}/app/settings/channels`}
                target="_blank"
                rel="noreferrer"
                className="underline hover:text-slate-600 dark:hover:text-slate-300"
              >
                {t('manageChannels')}
              </a>
            </p>
          </div>

          {/* Account (optional) - blank = the account connected on that service */}
          <div className="space-y-1">
            <CredentialSection
              toolCredentials={credentials}
              selectedCredentialId={approvalDelegation?.credentialId ?? null}
              onCredentialSelect={handleCredentialSelect}
              integration={channel.integration}
              isRunMode={isRunMode}
            />
            <p className="text-xs text-slate-400 dark:text-slate-500">{t('credentialHint', { channel: channel.label })}</p>
          </div>

          {/* Destination (optional, template-capable) - blank = the connected destination */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chatIdLabel')}</span>
            <ExpressionEditor
              value={approvalDelegation?.chatId ?? ''}
              onChange={(value) => patch({ chatId: value })}
              placeholder={CHAT_ID_PLACEHOLDERS[channel.id]}
              className="w-full"
              readOnly={isRunMode}
            />
            <p className="text-xs text-slate-400 dark:text-slate-500">{t('chatIdHint', { channel: channel.label })}</p>
          </div>
          </>)}

          {/* Message template (template-capable, optional; blank = resolved approval context) */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('messageLabel')}</span>
            <ExpressionEditor
              value={approvalDelegation?.messageTemplate ?? ''}
              onChange={(value) => patch({ messageTemplate: value })}
              placeholder={t('messagePlaceholder')}
              className="w-full"
              readOnly={isRunMode}
            />
          </div>

          {/* Image (Telegram only; template-capable, optional; non-blank = photo message) */}
          {channel.sendsImages && (
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('imageLabel')}</span>
              <ExpressionEditor
                value={approvalDelegation?.image ?? ''}
                onChange={(value) => patch({ image: value })}
                placeholder={IMAGE_PLACEHOLDER}
                className="w-full"
                readOnly={isRunMode}
              />
              <p className="text-xs text-slate-400 dark:text-slate-500">{t('imageHint')}</p>
            </div>
          )}

          {/* Custom approve/reject button labels (template-capable, optional; blank = channel defaults) */}
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('approveLabelLabel')}</span>
              <ExpressionEditor
                value={approvalDelegation?.approveLabel ?? ''}
                onChange={(value) => patch({ approveLabel: value })}
                placeholder={t('approveLabelPlaceholder')}
                className="w-full"
                readOnly={isRunMode}
              />
            </div>
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('rejectLabelLabel')}</span>
              <ExpressionEditor
                value={approvalDelegation?.rejectLabel ?? ''}
                onChange={(value) => patch({ rejectLabel: value })}
                placeholder={t('rejectLabelPlaceholder')}
                className="w-full"
                readOnly={isRunMode}
              />
            </div>
          </div>
          <p className="text-xs text-slate-400 dark:text-slate-500">{t('buttonLabelsHint')}</p>

          {/* Allowed user ids (comma-separated; empty = anyone in the chat). Not on a service
              whose press does not say who pressed (Teams links). */}
          {channel.identifiesPresser ? (
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('allowedUserIdsLabel')}</span>
              <Input
                type="text"
                value={allowedIdsText}
                onChange={handleAllowedUserIdsChange}
                className="w-full"
                placeholder={ALLOWED_USER_IDS_PLACEHOLDER}
                readOnly={isRunMode}
              />
              <p className="text-xs text-slate-400 dark:text-slate-500">{t('allowedUserIdsHint', { channel: channel.label })}</p>
            </div>
          ) : (
            <p className="text-xs text-slate-400 dark:text-slate-500">{t('anonymousPressNote', { channel: channel.label })}</p>
          )}
        </div>
      )}
    </div>
  );
}
