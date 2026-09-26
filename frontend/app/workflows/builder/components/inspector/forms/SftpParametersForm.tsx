'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';
import { CredentialSection } from '../CredentialSection';
import { InfoPopover } from '@/components/ui/info-popover';

interface SftpParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

const OPERATIONS = [
  { value: 'upload', key: 'upload' },
  { value: 'download', key: 'download' },
  { value: 'list', key: 'list' },
  { value: 'delete', key: 'delete' },
  { value: 'rename', key: 'rename' },
  { value: 'mkdir', key: 'mkdir' },
] as const;

/** SFTP credential requirement passed to CredentialSection */
const SFTP_CREDENTIAL = [{
  credentialName: 'sftp',
  isRequired: true,
  displayName: 'SFTP Server',
  description: 'SFTP connection credentials (host, port, username, password/key)',
  authType: 'custom',
  credentialType: 'sftp',
}];

export function SftpParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: SftpParametersFormProps) {
  const t = useTranslations('workflowBuilder.sftpNode');

  const operation: string = (data as any).sftpOperation ?? 'upload';
  const remotePath: string = (data as any).sftpRemotePath ?? '';
  const localContent: string = (data as any).sftpLocalContent ?? '';
  const newPath: string = (data as any).sftpNewPath ?? '';
  const timeout: number = (data as any).sftpTimeout ?? 30000;

  const handleChange = React.useCallback(
    (field: string, value: string | number) => {
      if (isRunMode) return;
      onUpdate({ ...data, [field]: value } as BuilderNodeData);
    },
    [data, isRunMode, onUpdate],
  );

  const handleCredentialSelect = React.useCallback(
    (credentialId: number | null, credentialName: string) => {
      onUpdate({
        ...data,
        sftpCredentialId: credentialId,
        sftpCredentialName: credentialName,
      } as BuilderNodeData);
    },
    [data, onUpdate],
  );

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('title')}
        </span>
        <InfoPopover label={t('title')} size="md" side="right" align="start">
          <p className="font-semibold mb-1">{t('infoTitle')}</p>
          <p className="text-slate-500 dark:text-slate-400 text-xs">
            {t('infoDescription')}
          </p>
        </InfoPopover>
      </div>

      {/* SFTP Credential - same pattern as SMTP */}
      <CredentialSection
        toolCredentials={SFTP_CREDENTIAL}
        selectedCredentialId={(data as any).sftpCredentialId ?? null}
        onCredentialSelect={handleCredentialSelect}
        integration="sftp"
        isRunMode={isRunMode}
      />

      {/* Operation */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('operation')} <span className="text-red-500">*</span>
        </span>
        <Select
          value={operation}
          onValueChange={(v) => handleChange('sftpOperation', v)}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full text-sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {OPERATIONS.map((op) => (
              <SelectItem key={op.value} value={op.value}>
                {t(`operations.${op.key}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Remote Path */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('remotePath')} <span className="text-red-500">*</span>
        </span>
        <ExpressionEditor
          value={remotePath}
          onChange={(value) => handleChange('sftpRemotePath', value)}
          placeholder={t('remotePathPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ sftpRemotePath: remotePath })}
          handleId={`sftp-remotepath-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
        />
      </div>

      {/* File Content (upload only) */}
      {operation === 'upload' && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('localContent')}
          </span>
          <ExpressionEditor
            value={localContent}
            onChange={(value) => handleChange('sftpLocalContent', value)}
            placeholder={t('localContentPlaceholder')}
            className="w-full min-h-[80px]"
            unknownVariables={findUnknownVariables({ sftpLocalContent: localContent })}
            handleId={`sftp-localcontent-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* New Path (rename only) */}
      {operation === 'rename' && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('newPath')} <span className="text-red-500">*</span>
          </span>
          <ExpressionEditor
            value={newPath}
            onChange={(value) => handleChange('sftpNewPath', value)}
            placeholder={t('newPathPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ sftpNewPath: newPath })}
            handleId={`sftp-newpath-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Timeout */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('timeout')}
        </span>
        <Input
          type="number"
          value={timeout}
          onChange={(e) => handleChange('sftpTimeout', parseInt(e.target.value, 10) || 30000)}
          disabled={isRunMode}
          className="text-sm"
        />
      </div>
    </div>
  );
}
