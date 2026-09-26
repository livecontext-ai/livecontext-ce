'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';
import { CredentialSection } from '../CredentialSection';
import { InfoPopover } from '@/components/ui/info-popover';

interface SshParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
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
  connectionProps,
  findUnknownVariables,
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
        <InfoPopover label={t('title')} size="md" side="right" align="start">
          <p className="font-semibold mb-1">{t('infoTitle')}</p>
          <p className="text-slate-500 dark:text-slate-400 text-xs">
            {t('infoDescription')}
          </p>
        </InfoPopover>
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
        <ExpressionEditor
          value={command}
          onChange={(value) => handleChange('sshCommand', value)}
          placeholder={t('commandPlaceholder')}
          className="w-full min-h-[80px]"
          unknownVariables={findUnknownVariables({ sshCommand: command })}
          handleId={`ssh-command-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
          isRequired
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
