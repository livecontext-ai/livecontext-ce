'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';
import { CredentialSection } from '../CredentialSection';

interface SshParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

/** SSH credential requirement passed to CredentialSection */
const SSH_CREDENTIAL = [{
  credentialName: 'ssh',
  isRequired: true,
  displayName: 'SSH Server',
  description: 'SSH connection credentials (host, port, username, password/key)',
  authType: 'custom',
  credentialType: 'ssh',
}];

export function SshParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
}: SshParametersFormProps) {
  const t = useTranslations('workflowBuilder.sshNode');

  const command: string = (data as any).sshCommand ?? '';
  const timeout: number = (data as any).sshTimeout ?? 30000;

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
        sshCredentialId: credentialId,
        sshCredentialName: credentialName,
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

      {/* SSH Credential - same pattern as SMTP */}
      <CredentialSection
        toolCredentials={SSH_CREDENTIAL}
        selectedCredentialId={(data as any).sshCredentialId ?? null}
        onCredentialSelect={handleCredentialSelect}
        integration="ssh"
        isRunMode={isRunMode}
      />

      {/* Command */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('command')} <span className="text-red-500">*</span>
        </span>
        <Textarea
          value={command}
          onChange={(e) => handleChange('sshCommand', e.target.value)}
          placeholder={t('commandPlaceholder')}
          disabled={isRunMode}
          className="text-sm font-mono min-h-[80px]"
        />
      </div>

      {/* Timeout */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('timeout')}
        </span>
        <Input
          type="number"
          value={timeout}
          onChange={(e) => handleChange('sshTimeout', parseInt(e.target.value, 10) || 30000)}
          disabled={isRunMode}
          className="text-sm"
        />
      </div>
    </div>
  );
}
