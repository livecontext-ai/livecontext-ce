'use client';

import * as React from 'react';
import { Info, Plus, X } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Button } from '@/components/ui/button';
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

interface DatabaseParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

const DB_OPERATIONS = [
  { value: 'select', key: 'select' },
  { value: 'insert', key: 'insert' },
  { value: 'update', key: 'update' },
  { value: 'delete', key: 'delete' },
  { value: 'execute', key: 'execute' },
] as const;

/** Database credential requirement passed to CredentialSection */
const DATABASE_CREDENTIAL = [{
  credentialName: 'database',
  isRequired: true,
  displayName: 'Database',
  description: 'Database connection credentials (type, host, port, database, username, password)',
  authType: 'custom',
  credentialType: 'database',
}];

export function DatabaseParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: DatabaseParametersFormProps) {
  const t = useTranslations('workflowBuilder.databaseNode');

  const operation: string = (data as any).dbOperation ?? 'select';
  const query: string = (data as any).dbQuery ?? '';
  const queryParams: string[] = (data as any).dbQueryParams ?? [];
  const timeout: number = (data as any).dbTimeout ?? 30000;

  const handleChange = React.useCallback(
    (field: string, value: string | number | boolean | string[]) => {
      if (isRunMode) return;
      onUpdate({ ...data, [field]: value } as BuilderNodeData);
    },
    [data, isRunMode, onUpdate],
  );

  const handleCredentialSelect = React.useCallback(
    (credentialId: number | null, credentialName: string) => {
      onUpdate({
        ...data,
        dbCredentialId: credentialId,
        dbCredentialName: credentialName,
      } as BuilderNodeData);
    },
    [data, onUpdate],
  );

  const handleAddParam = React.useCallback(() => {
    if (isRunMode) return;
    handleChange('dbQueryParams', [...queryParams, '']);
  }, [isRunMode, queryParams, handleChange]);

  const handleParamChange = React.useCallback(
    (index: number, value: string) => {
      if (isRunMode) return;
      const updated = [...queryParams];
      updated[index] = value;
      handleChange('dbQueryParams', updated);
    },
    [isRunMode, queryParams, handleChange],
  );

  const handleRemoveParam = React.useCallback(
    (index: number) => {
      if (isRunMode) return;
      const updated = queryParams.filter((_, i) => i !== index);
      handleChange('dbQueryParams', updated);
    },
    [isRunMode, queryParams, handleChange],
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

      {/* Database Credential - same pattern as SMTP */}
      <CredentialSection
        toolCredentials={DATABASE_CREDENTIAL}
        selectedCredentialId={(data as any).dbCredentialId ?? null}
        onCredentialSelect={handleCredentialSelect}
        integration="database"
        isRunMode={isRunMode}
      />

      {/* Operation */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('operation')} <span className="text-red-500">*</span>
        </span>
        <Select
          value={operation}
          onValueChange={(v) => handleChange('dbOperation', v)}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full text-sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {DB_OPERATIONS.map((op) => (
              <SelectItem key={op.value} value={op.value}>
                {t(`operations.${op.key}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* SQL Query */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('query')} <span className="text-red-500">*</span>
        </span>
        <ExpressionEditor
          value={query}
          onChange={(value) => handleChange('dbQuery', value)}
          placeholder={t('queryPlaceholder')}
          className="w-full min-h-[100px]"
          unknownVariables={findUnknownVariables({ dbQuery: query })}
          handleId={`db-query-${node.id}`}
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

      {/* Query Parameters */}
      <div className="space-y-2">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('queryParams')}
        </span>
        {queryParams.map((param: string, index: number) => (
          <div key={index} className="flex items-center gap-2">
            <span className="text-xs text-slate-400 dark:text-slate-500 min-w-[24px]">
              ${index + 1}
            </span>
            <div className="flex-1 min-w-0">
              <ExpressionEditor
                value={param}
                onChange={(value) => handleParamChange(index, value)}
                placeholder={t('queryParamPlaceholder')}
                className="w-full"
                unknownVariables={findUnknownVariables({ [`dbQueryParam${index}`]: param })}
                handleId={`db-query-param-${index}-${node.id}`}
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
            <button
              onClick={() => handleRemoveParam(index)}
              disabled={isRunMode}
              className="text-slate-400 hover:text-red-500 disabled:opacity-50"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
        <Button
          variant="outline"
          size="sm"
          onClick={handleAddParam}
          disabled={isRunMode}
          className="text-xs"
        >
          <Plus className="h-3 w-3 mr-1" />
          {t('addParam')}
        </Button>
      </div>

      {/* Timeout */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('timeout')}
        </span>
        <Input
          type="number"
          value={timeout}
          onChange={(e) => handleChange('dbTimeout', parseInt(e.target.value, 10) || 30000)}
          disabled={isRunMode}
          className="text-sm"
        />
      </div>
    </div>
  );
}
