'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import type { Connection } from '../useInspectorConnections';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import { CredentialSection } from '../CredentialSection';

interface EmailInboxParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

/** IMAP credential requirement passed to CredentialSection */
const IMAP_CREDENTIAL = [{
  credentialName: 'imap',
  isRequired: true,
  displayName: 'IMAP Email',
  description: 'IMAP server credentials for reading a mailbox',
  authType: 'custom',
  credentialType: 'imap',
}];

const ACTIONS = ['none', 'list_folders', 'create_folder', 'mark_read', 'mark_unread', 'flag', 'unflag', 'move', 'delete'];

/**
 * Form for the Email Inbox node. IMAP credentials are managed via CredentialSection
 * (same pattern as the Send Email node's SMTP credential). This form configures the
 * per-node fields: folder, read filters, and the optional mailbox action.
 */
export function EmailInboxParametersForm({
  node,
  data,
  connections,
  isRunMode = false,
  onUpdate,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: EmailInboxParametersFormProps) {
  const t = useTranslations('workflowBuilder.emailInboxNode');

  const folder: string = (data as any).emailFolder ?? '';
  const unreadOnly: string = (data as any).emailUnreadOnly ?? 'false';
  const limit: string = (data as any).emailLimit ?? '';
  const markSeen: string = (data as any).emailMarkSeen ?? 'false';
  const sinceDays: string = (data as any).emailSinceDays ?? '';
  const action: string = (data as any).emailAction ?? 'none';
  const messageUid: string = (data as any).emailMessageUid ?? '';
  const targetFolder: string = (data as any).emailTargetFolder ?? '';
  const createTargetIfMissing: string = (data as any).emailCreateTargetIfMissing ?? 'false';
  const fromContains: string = (data as any).emailFromContains ?? '';
  const subjectContains: string = (data as any).emailSubjectContains ?? '';
  const bodyContains: string = (data as any).emailBodyContains ?? '';
  const flaggedOnly: string = (data as any).emailFlaggedOnly ?? 'false';
  const beforeDays: string = (data as any).emailBeforeDays ?? '';
  const downloadAttachments: string = (data as any).emailDownloadAttachments ?? 'false';

  const handleFieldChange = React.useCallback((key: string) => (value: string) => {
    if (isRunMode) return;
    onUpdate({ ...data, [key]: value } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleInputChange = React.useCallback((key: string) => (event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    onUpdate({ ...data, [key]: event.target.value } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleSelectChange = React.useCallback((key: string) => (value: string) => {
    if (isRunMode) return;
    onUpdate({ ...data, [key]: value } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleCredentialSelect = React.useCallback((credentialId: number | null) => {
    onUpdate({ ...data, imapCredentialId: credentialId } as BuilderNodeData);
  }, [data, onUpdate]);

  const isRead = action === 'none';
  // create_folder is mailbox-level like list_folders: it targets a folder, never a single message.
  const isCreateFolder = action === 'create_folder';
  const isMessageAction = action !== 'none' && action !== 'list_folders' && !isCreateFolder;
  const showTargetFolder = action === 'move' || isCreateFolder;

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
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
              <p>{t('infoDescription')}</p>
            </div>
          </PopoverContent>
        </Popover>
      </div>

      {/* IMAP Credential */}
      <CredentialSection
        toolCredentials={IMAP_CREDENTIAL}
        selectedCredentialId={(data as any).imapCredentialId ?? null}
        onCredentialSelect={handleCredentialSelect}
        integration="imap"
        isRunMode={isRunMode}
      />

      {/* Folder */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('folder')}</span>
        <Input
          type="text"
          value={folder}
          onChange={handleInputChange('emailFolder')}
          className="w-full"
          placeholder={t('folderPlaceholder')}
          readOnly={isRunMode}
        />
      </div>

      {/* Action */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('action')}</span>
        <Select value={action} onValueChange={handleSelectChange('emailAction')} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ACTIONS.map((a) => (
              <SelectItem key={a} value={a}>{t(`actions.${a}`)}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {isRead && (
        <>
          {/* Read-mode section */}
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('readSection')}</span>

          {/* Unread only */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('unreadOnly')}</span>
            <Select value={unreadOnly} onValueChange={handleSelectChange('emailUnreadOnly')} disabled={isRunMode}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="true">{t('yes')}</SelectItem>
                <SelectItem value="false">{t('no')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Limit */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('limit')}</span>
            <Input
              type="number"
              value={limit}
              onChange={handleInputChange('emailLimit')}
              className="w-full"
              placeholder={t('limitPlaceholder')}
              readOnly={isRunMode}
            />
          </div>

          {/* Since days */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('sinceDays')}</span>
            <Input
              type="number"
              value={sinceDays}
              onChange={handleInputChange('emailSinceDays')}
              className="w-full"
              placeholder={t('sinceDaysPlaceholder')}
              readOnly={isRunMode}
            />
          </div>

          {/* Mark seen */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('markSeen')}</span>
            <Select value={markSeen} onValueChange={handleSelectChange('emailMarkSeen')} disabled={isRunMode}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="true">{t('yes')}</SelectItem>
                <SelectItem value="false">{t('no')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Before days */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('beforeDays')}</span>
            <Input
              type="number"
              value={beforeDays}
              onChange={handleInputChange('emailBeforeDays')}
              className="w-full"
              placeholder={t('beforeDaysPlaceholder')}
              readOnly={isRunMode}
            />
          </div>

          {/* Flagged only */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('flaggedOnly')}</span>
            <Select value={flaggedOnly} onValueChange={handleSelectChange('emailFlaggedOnly')} disabled={isRunMode}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="true">{t('yes')}</SelectItem>
                <SelectItem value="false">{t('no')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Search section */}
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('searchSection')}</span>

          {/* From contains */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('fromContains')}</span>
            <Input
              type="text"
              value={fromContains}
              onChange={handleInputChange('emailFromContains')}
              className="w-full"
              placeholder={t('fromContainsPlaceholder')}
              readOnly={isRunMode}
            />
          </div>

          {/* Subject contains */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('subjectContains')}</span>
            <Input
              type="text"
              value={subjectContains}
              onChange={handleInputChange('emailSubjectContains')}
              className="w-full"
              placeholder={t('subjectContainsPlaceholder')}
              readOnly={isRunMode}
            />
          </div>

          {/* Body contains */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('bodyContains')}</span>
            <Input
              type="text"
              value={bodyContains}
              onChange={handleInputChange('emailBodyContains')}
              className="w-full"
              placeholder={t('bodyContainsPlaceholder')}
              readOnly={isRunMode}
            />
          </div>

          {/* Download attachments */}
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('downloadAttachments')}</span>
            <Select value={downloadAttachments} onValueChange={handleSelectChange('emailDownloadAttachments')} disabled={isRunMode}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="true">{t('yes')}</SelectItem>
                <SelectItem value="false">{t('no')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </>
      )}

      {(isMessageAction || showTargetFolder) && (
        <>
          {/* Action-mode section */}
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('actionSection')}</span>

          {/* Message UID (single-message actions only, never list_folders/create_folder) */}
          {isMessageAction && (
          <div className="space-y-1">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('messageUid')} <span className="text-red-500">*</span></span>
            <ExpressionEditor
              value={messageUid}
              onChange={handleFieldChange('emailMessageUid')}
              placeholder={t('messageUidPlaceholder')}
              className="w-full"
              unknownVariables={findUnknownVariables({ emailMessageUid: messageUid })}
              handleId={`email-uid-${node.id}`}
              connections={connections}
              onHandleClick={handleHandleClick}
              draggingFromHandle={draggingFromHandle}
              onHandleMouseDown={handleHandleMouseDown}
              onHandleMouseUp={handleHandleMouseUp}
              hoveredTargetHandle={hoveredTargetHandle}
              onSetHandleRef={handleSetHandleRef}
              isRequired={true}
              readOnly={isRunMode}
            />
          </div>
          )}

          {/* Target folder (move = destination, create_folder = folder to create) */}
          {showTargetFolder && (
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {isCreateFolder ? t('folderToCreate') : t('targetFolder')} <span className="text-red-500">*</span>
              </span>
              <Input
                type="text"
                value={targetFolder}
                onChange={handleInputChange('emailTargetFolder')}
                className="w-full"
                placeholder={t('targetFolderPlaceholder')}
                readOnly={isRunMode}
              />
              <p className="text-sm text-slate-400 dark:text-slate-500">{t('targetFolderHint')}</p>
            </div>
          )}

          {/* Create target if missing (move only) */}
          {action === 'move' && (
            <div className="space-y-1">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('createTargetIfMissing')}</span>
              <Select value={createTargetIfMissing} onValueChange={handleSelectChange('emailCreateTargetIfMissing')} disabled={isRunMode}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{t('yes')}</SelectItem>
                  <SelectItem value="false">{t('no')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          )}
        </>
      )}
    </div>
  );
}
