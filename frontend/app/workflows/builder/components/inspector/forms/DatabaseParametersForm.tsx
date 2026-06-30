'use client';

import * as React from 'react';
import { Info, Plus, X } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
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

interface DatabaseParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
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
        <Textarea
          value={query}
          onChange={(e) => handleChange('dbQuery', e.target.value)}
          placeholder={t('queryPlaceholder')}
          disabled={isRunMode}
          className="text-sm font-mono min-h-[100px]"
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
            <Input
              value={param}
              onChange={(e) => handleParamChange(index, e.target.value)}
              placeholder={t('queryParamPlaceholder')}
              disabled={isRunMode}
              className="text-sm flex-1"
            />
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
