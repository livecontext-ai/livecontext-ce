'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { ApprovalDelegation } from '../../../types';
import { CredentialSection } from '../CredentialSection';

/** Channel display name (brand, intentionally not translated). */
const TELEGRAM_CHANNEL_LABEL = 'Telegram';

/** Syntax example for the chat id (code/syntax token, intentionally not translated). */
const CHAT_ID_PLACEHOLDER = '-100123456789 or {{trigger:form.output.chat_id}}';

/** Syntax example for the allowed user ids (code/syntax token, intentionally not translated). */
const ALLOWED_USER_IDS_PLACEHOLDER = '123456789, 987654321';

/**
 * Telegram bot credential descriptor passed to CredentialSection (same pattern as SMTP/SSH).
 * OPTIONAL: when no credentialId is configured, the backend falls back to the user's own
 * Telegram credential (catalog implicit resolution, same as an mcp:telegram step).
 */
const TELEGRAM_CREDENTIAL = [{
  credentialName: 'telegram',
  isRequired: false,
  displayName: 'Telegram',
  description: 'Telegram bot credential used to send the approval message',
  credentialType: 'telegram',
}];

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
 * Toggle off = no delegation (approvalDelegation removed from node data, plan
 * emits no `approval.delegation` block). Toggle on = Telegram (v1) with the
 * user's own bot credential; the pending approval is pushed to the chat with
 * inline approve/reject buttons and a tap resolves it.
 */
export function ApprovalDelegationSection({
  isRunMode = false,
  approvalDelegation,
  handleDelegationChange,
}: ApprovalDelegationSectionProps) {
  const t = useTranslations('workflowBuilder.forms.approval.delegation');

  const enabled = approvalDelegation !== undefined;

  const patch = React.useCallback((updates: Partial<ApprovalDelegation>) => {
    if (isRunMode) return;
    handleDelegationChange({ channel: 'telegram', ...approvalDelegation, ...updates });
  }, [isRunMode, approvalDelegation, handleDelegationChange]);

  const handleToggle = React.useCallback((checked: boolean) => {
    if (isRunMode) return;
    handleDelegationChange(checked ? { channel: 'telegram' } : undefined);
  }, [isRunMode, handleDelegationChange]);

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

  return (
    <div className="flex flex-col gap-3 border-t border-slate-100 dark:border-slate-700 pt-4">
      {/* Header: title + info + enable toggle */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
          <Popover>
            <PopoverTrigger asChild>
              <button
                type="button"
                className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
              >
                <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
              </button>
            </PopoverTrigger>
            <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
              <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
                <p>{t('infoBody')}</p>
                <p className="text-xs">{t('infoOptional')}</p>
              </div>
            </PopoverContent>
          </Popover>
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
          {/* Channel (v1: Telegram only) - required while the section is enabled */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('channelLabel')} <span className="text-red-500">*</span></span>
            <Select
              value={approvalDelegation?.channel ?? 'telegram'}
              onValueChange={(value) => patch({ channel: value as 'telegram' })}
              disabled={isRunMode}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="telegram">{TELEGRAM_CHANNEL_LABEL}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Telegram bot credential (optional) - blank = the user's default Telegram credential */}
          <div className="space-y-1">
            <CredentialSection
              toolCredentials={TELEGRAM_CREDENTIAL}
              selectedCredentialId={approvalDelegation?.credentialId ?? null}
              onCredentialSelect={handleCredentialSelect}
              integration="telegram"
              isRunMode={isRunMode}
            />
            <p className="text-xs text-slate-400 dark:text-slate-500">{t('credentialHint')}</p>
          </div>

          {/* Chat id (template-capable, required) - same expression editor as contextTemplate */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chatIdLabel')} <span className="text-red-500">*</span></span>
            <ExpressionEditor
              value={approvalDelegation?.chatId ?? ''}
              onChange={(value) => patch({ chatId: value })}
              placeholder={CHAT_ID_PLACEHOLDER}
              className="w-full"
              isRequired
              readOnly={isRunMode}
            />
            <p className="text-xs text-slate-400 dark:text-slate-500">{t('chatIdHint')}</p>
          </div>

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

          {/* Allowed user ids (comma-separated; empty = anyone in the chat) */}
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
            <p className="text-xs text-slate-400 dark:text-slate-500">{t('allowedUserIdsHint')}</p>
          </div>
        </div>
      )}
    </div>
  );
}
