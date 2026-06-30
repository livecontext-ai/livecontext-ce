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

interface SendEmailParametersFormProps {
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

/** SMTP credential requirement passed to CredentialSection */
const SMTP_CREDENTIAL = [{
  credentialName: 'smtp',
  isRequired: true,
  displayName: 'SMTP Email',
  description: 'SMTP server credentials for sending emails',
  authType: 'custom',
  credentialType: 'smtp',
}];

/**
 * Form component for Send Email node parameters.
 * SMTP credentials are managed via CredentialSection (same pattern as MCP tools).
 * This form only configures per-email fields: recipients, subject, body.
 */
export function SendEmailParametersForm({
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
}: SendEmailParametersFormProps) {
  const t = useTranslations('workflowBuilder.sendEmailNode');

  const to: string = (data as any).emailTo ?? '';
  const cc: string = (data as any).emailCc ?? '';
  const bcc: string = (data as any).emailBcc ?? '';
  const fromName: string = (data as any).emailFromName ?? '';
  const subject: string = (data as any).emailSubject ?? '';
  const body: string = (data as any).emailBody ?? '';
  const isHtml: string = (data as any).emailIsHtml ?? 'false';
  const inReplyTo: string = (data as any).emailInReplyTo ?? '';
  const references: string = (data as any).emailReferences ?? '';

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

  const handleCredentialSelect = React.useCallback((credentialId: number | null, credentialName: string) => {
    onUpdate({
      ...data,
      smtpCredentialId: credentialId,
      smtpCredentialName: credentialName,
    } as BuilderNodeData);
  }, [data, onUpdate]);

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

      {/* SMTP Credential - same pattern as MCP tool credentials */}
      <CredentialSection
        toolCredentials={SMTP_CREDENTIAL}
        selectedCredentialId={(data as any).smtpCredentialId ?? null}
        onCredentialSelect={handleCredentialSelect}
        integration="smtp"
        isRunMode={isRunMode}
      />

      {/* Section: Recipients */}
      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('recipients')}</span>

      {/* To */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('to')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={to}
          onChange={handleFieldChange('emailTo')}
          placeholder={t('toPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ emailTo: to })}
          handleId={`email-to-${node.id}`}
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

      {/* CC */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('cc')}</span>
        <Input
          type="text"
          value={cc}
          onChange={handleInputChange('emailCc')}
          className="w-full"
          placeholder={t('ccPlaceholder')}
          readOnly={isRunMode}
        />
      </div>

      {/* BCC */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('bcc')}</span>
        <Input
          type="text"
          value={bcc}
          onChange={handleInputChange('emailBcc')}
          className="w-full"
          placeholder={t('bccPlaceholder')}
          readOnly={isRunMode}
        />
      </div>

      {/* From Name (optional override) */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('fromName')}</span>
        <Input
          type="text"
          value={fromName}
          onChange={handleInputChange('emailFromName')}
          className="w-full"
          placeholder={t('fromNamePlaceholder')}
          readOnly={isRunMode}
        />
      </div>

      {/* Section: Email Content */}
      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('emailContent')}</span>

      {/* Subject */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('subject')} <span className="text-red-500">*</span></span>
        <ExpressionEditor
          value={subject}
          onChange={handleFieldChange('emailSubject')}
          placeholder={t('subjectPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ emailSubject: subject })}
          handleId={`email-subject-${node.id}`}
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

      {/* Body */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('body')}</span>
        <ExpressionEditor
          value={body}
          onChange={handleFieldChange('emailBody')}
          placeholder={t('bodyPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ emailBody: body })}
          handleId={`email-body-${node.id}`}
          connections={connections}
          onHandleClick={handleHandleClick}
          draggingFromHandle={draggingFromHandle}
          onHandleMouseDown={handleHandleMouseDown}
          onHandleMouseUp={handleHandleMouseUp}
          hoveredTargetHandle={hoveredTargetHandle}
          onSetHandleRef={handleSetHandleRef}
          readOnly={isRunMode}
        />
      </div>

      {/* HTML Body */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('isHtml')}</span>
        <Select value={isHtml} onValueChange={handleSelectChange('emailIsHtml')} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="true">Yes</SelectItem>
            <SelectItem value="false">No</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Reply threading (optional) */}
      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('replyThreading')}</span>

      {/* In-Reply-To */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('inReplyTo')}</span>
        <Input
          type="text"
          value={inReplyTo}
          onChange={handleInputChange('emailInReplyTo')}
          className="w-full"
          placeholder={t('inReplyToPlaceholder')}
          readOnly={isRunMode}
        />
      </div>

      {/* References */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('references')}</span>
        <Input
          type="text"
          value={references}
          onChange={handleInputChange('emailReferences')}
          className="w-full"
          placeholder={t('referencesPlaceholder')}
          readOnly={isRunMode}
        />
      </div>
    </div>
  );
}
