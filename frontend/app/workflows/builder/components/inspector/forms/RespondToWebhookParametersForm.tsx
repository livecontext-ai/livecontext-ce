'use client';

import * as React from 'react';
import { Plus, Trash2, Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

interface WebhookResponseHeader {
  id: string;
  name: string;
  value: string;
}

interface RespondToWebhookParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Generate a unique ID for a new header entry
 */
function generateHeaderId(): string {
  return `header_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Form component for Respond to Webhook node parameters.
 * Configures the HTTP response sent back to the webhook caller:
 * status code, content type, response body, and custom headers.
 */
export function RespondToWebhookParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: RespondToWebhookParametersFormProps) {
  const t = useTranslations('workflowBuilder.respondToWebhookNode');

  const statusCode: number = (data as any).webhookResponseStatusCode ?? 200;
  const contentType: string = (data as any).webhookResponseContentType ?? 'application/json';
  const body: string = (data as any).webhookResponseBody ?? '';

  const headers: WebhookResponseHeader[] = React.useMemo(() => {
    const existingHeaders = (data as any).webhookResponseHeaders as WebhookResponseHeader[] | undefined;
    if (existingHeaders && existingHeaders.length > 0) {
      return existingHeaders;
    }
    return [];
  }, [(data as any).webhookResponseHeaders]);

  const handleStatusCodeChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({
        ...data,
        webhookResponseStatusCode: 200,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 100 || numValue > 599) {
      return;
    }

    onUpdate({
      ...data,
      webhookResponseStatusCode: numValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleContentTypeChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      webhookResponseContentType: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleBodyChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      webhookResponseBody: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleAddHeader = React.useCallback(() => {
    if (isRunMode) return;
    const newHeader: WebhookResponseHeader = {
      id: generateHeaderId(),
      name: '',
      value: '',
    };
    const updatedHeaders = [...headers, newHeader];
    onUpdate({
      ...data,
      webhookResponseHeaders: updatedHeaders,
    } as BuilderNodeData);
  }, [data, headers, isRunMode, onUpdate]);

  const handleDeleteHeader = React.useCallback((headerId: string) => {
    if (isRunMode) return;
    const updatedHeaders = headers.filter(h => h.id !== headerId);
    onUpdate({
      ...data,
      webhookResponseHeaders: updatedHeaders,
    } as BuilderNodeData);
  }, [data, headers, isRunMode, onUpdate]);

  const handleHeaderNameChange = React.useCallback((headerId: string, newName: string) => {
    if (isRunMode) return;
    const updatedHeaders = headers.map(h =>
      h.id === headerId ? { ...h, name: newName } : h
    );
    onUpdate({
      ...data,
      webhookResponseHeaders: updatedHeaders,
    } as BuilderNodeData);
  }, [data, headers, isRunMode, onUpdate]);

  const handleHeaderValueChange = React.useCallback((headerId: string, newValue: string) => {
    if (isRunMode) return;
    const updatedHeaders = headers.map(h =>
      h.id === headerId ? { ...h, value: newValue } : h
    );
    onUpdate({
      ...data,
      webhookResponseHeaders: updatedHeaders,
    } as BuilderNodeData);
  }, [data, headers, isRunMode, onUpdate]);

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

      {/* Status Code */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('statusCode')}</span>
        <Input
          type="number"
          min="100"
          max="599"
          value={statusCode}
          onChange={handleStatusCodeChange}
          className="w-full"
          placeholder={t('statusCodePlaceholder')}
          readOnly={isRunMode}
        />
      </div>

      {/* Content Type */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('contentType')}</span>
        <Select value={contentType} onValueChange={handleContentTypeChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="application/json">{t('contentTypes.application/json')}</SelectItem>
            <SelectItem value="text/plain">{t('contentTypes.text/plain')}</SelectItem>
            <SelectItem value="text/html">{t('contentTypes.text/html')}</SelectItem>
            <SelectItem value="application/xml">{t('contentTypes.application/xml')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Response Body */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('body')}</span>
        <ExpressionEditor
          value={body}
          onChange={handleBodyChange}
          placeholder={t('bodyPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ webhookResponseBody: body })}
          handleId={`webhook-response-body-${node.id}`}
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

      {/* Custom Headers */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('headers')}</span>
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddHeader();
            }}
            title={t('addHeader')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>
      <div className="space-y-3">
        {headers.map((header) => (
          <div key={header.id} className="flex items-center gap-2">
            <Input
              value={header.name}
              onChange={(e) => handleHeaderNameChange(header.id, e.target.value)}
              className="flex-1 min-w-0 text-sm"
              placeholder={t('headerNamePlaceholder')}
              readOnly={isRunMode}
            />
            <Input
              value={header.value}
              onChange={(e) => handleHeaderValueChange(header.id, e.target.value)}
              className="flex-1 min-w-0 text-sm"
              placeholder={t('headerValuePlaceholder')}
              readOnly={isRunMode}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30"
              onClick={(e) => {
                e.stopPropagation();
                handleDeleteHeader(header.id);
              }}
              disabled={isRunMode}
            >
              <Trash2 className="h-3 w-3" />
            </Button>
          </div>
        ))}
      </div>
    </div>
  );
}
