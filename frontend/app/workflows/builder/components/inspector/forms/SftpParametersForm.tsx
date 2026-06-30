'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';
import { CredentialSection } from '../CredentialSection';

interface SftpParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
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
        <Popover>
          <PopoverTrigger asChild>
            <button className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300">
              <Info className="h-3.5 w-3.5" />
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
            <p className="font-semibold mb-1">{t('infoTitle')}</p>
            <p className="text-slate-500 dark:text-slate-400 text-xs">
              {t('infoDescription')}
            </p>
          </PopoverContent>
        </Popover>
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
        <Input
          value={remotePath}
          onChange={(e) => handleChange('sftpRemotePath', e.target.value)}
          placeholder={t('remotePathPlaceholder')}
          disabled={isRunMode}
          className="text-sm"
        />
      </div>

      {/* File Content (upload only) */}
      {operation === 'upload' && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('localContent')}
          </span>
          <Textarea
            value={localContent}
            onChange={(e) => handleChange('sftpLocalContent', e.target.value)}
            placeholder={t('localContentPlaceholder')}
            disabled={isRunMode}
            className="text-sm min-h-[80px]"
          />
        </div>
      )}

      {/* New Path (rename only) */}
      {operation === 'rename' && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('newPath')} <span className="text-red-500">*</span>
          </span>
          <Input
            value={newPath}
            onChange={(e) => handleChange('sftpNewPath', e.target.value)}
            placeholder={t('newPathPlaceholder')}
            disabled={isRunMode}
            className="text-sm"
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
