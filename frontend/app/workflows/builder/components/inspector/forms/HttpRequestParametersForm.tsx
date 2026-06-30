'use client';

import * as React from 'react';
import { Plus, Trash2, Info, Eye, EyeOff, ChevronDown, ChevronRight } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';

// HTTP Methods
const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'] as const;
type HttpMethod = typeof HTTP_METHODS[number];

// Authentication types
const AUTH_TYPES = ['none', 'basic', 'bearer', 'api-key', 'custom-header'] as const;
type AuthType = typeof AUTH_TYPES[number];

// Body types
const BODY_TYPES = ['none', 'json', 'form-data', 'x-www-form-urlencoded', 'raw'] as const;
type BodyType = typeof BODY_TYPES[number];

// Key-Value pair for query params and headers
interface KeyValuePair {
  id: string;
  key: string;
  value: string;
}

// HTTP Request data structure
interface HttpRequestData {
  method: HttpMethod;
  url: string;
  authType: AuthType;
  authConfig?: {
    username?: string;
    password?: string;
    bearerToken?: string;
    apiKeyName?: string;
    apiKeyValue?: string;
    apiKeyLocation?: 'header' | 'query';
    headerName?: string;
    headerValue?: string;
  };
  queryParams?: KeyValuePair[];
  headers?: KeyValuePair[];
  bodyType?: BodyType;
  body?: string;
  contentType?: string;
  timeout?: number;
}

// Generate unique ID
function generateId(): string {
  return `${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

// Secure input with show/hide toggle
interface SecureInputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
}

const SecureInput = ({ value, onChange, placeholder, disabled }: SecureInputProps) => {
  const [showValue, setShowValue] = React.useState(false);

  return (
    <div className="relative">
      <Input
        type={showValue ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        disabled={disabled}
        className="pr-10"
      />
      <button
        type="button"
        onClick={() => setShowValue(!showValue)}
        className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-slate-400 hover:text-slate-600"
        disabled={disabled}
      >
        {showValue ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
      </button>
    </div>
  );
};

interface HttpRequestParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  // Connection handlers
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

export function HttpRequestParametersForm({
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
}: HttpRequestParametersFormProps) {
  const t = useTranslations('workflowBuilder.httpRequest');

  // Collapsible states
  const [showQueryParams, setShowQueryParams] = React.useState(false);
  const [showHeaders, setShowHeaders] = React.useState(false);
  const [showBody, setShowBody] = React.useState(false);
  const [showAdvanced, setShowAdvanced] = React.useState(false);

  // Get HTTP request data from node data, with defaults
  const httpData: HttpRequestData = React.useMemo(() => {
    const existing = (data as any).httpRequestData as HttpRequestData | undefined;
    return existing || {
      method: 'GET',
      url: '',
      authType: 'none',
      queryParams: [],
      headers: [],
      bodyType: 'none',
      body: '',
      timeout: 30000,
    };
  }, [(data as any).httpRequestData]);

  // Update handler
  const handleUpdate = React.useCallback((updates: Partial<HttpRequestData>) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      httpRequestData: { ...httpData, ...updates },
    } as BuilderNodeData);
  }, [data, httpData, isRunMode, onUpdate]);

  // Method change
  const handleMethodChange = React.useCallback((value: string) => {
    handleUpdate({ method: value as HttpMethod });
  }, [handleUpdate]);

  // URL change
  const handleUrlChange = React.useCallback((value: string) => {
    handleUpdate({ url: value });
  }, [handleUpdate]);

  // Auth type change
  const handleAuthTypeChange = React.useCallback((value: string) => {
    const newAuthType = value as AuthType;
    const updates: Partial<HttpRequestData> = { authType: newAuthType };

    // Initialize auth config when switching types
    if (newAuthType === 'basic') {
      updates.authConfig = { username: '', password: '' };
    } else if (newAuthType === 'bearer') {
      updates.authConfig = { bearerToken: '' };
    } else if (newAuthType === 'api-key') {
      updates.authConfig = { apiKeyName: 'X-API-Key', apiKeyValue: '', apiKeyLocation: 'header' };
    } else if (newAuthType === 'custom-header') {
      updates.authConfig = { headerName: '', headerValue: '' };
    } else {
      updates.authConfig = undefined;
    }

    handleUpdate(updates);
  }, [handleUpdate]);

  // Auth config update
  const handleAuthConfigChange = React.useCallback((field: string, value: string) => {
    handleUpdate({
      authConfig: {
        ...httpData.authConfig,
        [field]: value,
      },
    });
  }, [handleUpdate, httpData.authConfig]);

  // Query params handlers
  const handleAddQueryParam = React.useCallback(() => {
    if (isRunMode) return;
    const newParam: KeyValuePair = { id: generateId(), key: '', value: '' };
    handleUpdate({
      queryParams: [...(httpData.queryParams || []), newParam],
    });
    setShowQueryParams(true);
  }, [handleUpdate, httpData.queryParams, isRunMode]);

  const handleUpdateQueryParam = React.useCallback((id: string, field: 'key' | 'value', value: string) => {
    handleUpdate({
      queryParams: (httpData.queryParams || []).map(p =>
        p.id === id ? { ...p, [field]: value } : p
      ),
    });
  }, [handleUpdate, httpData.queryParams]);

  const handleDeleteQueryParam = React.useCallback((id: string) => {
    handleUpdate({
      queryParams: (httpData.queryParams || []).filter(p => p.id !== id),
    });
  }, [handleUpdate, httpData.queryParams]);

  // Headers handlers
  const handleAddHeader = React.useCallback(() => {
    if (isRunMode) return;
    const newHeader: KeyValuePair = { id: generateId(), key: '', value: '' };
    handleUpdate({
      headers: [...(httpData.headers || []), newHeader],
    });
    setShowHeaders(true);
  }, [handleUpdate, httpData.headers, isRunMode]);

  const handleUpdateHeader = React.useCallback((id: string, field: 'key' | 'value', value: string) => {
    handleUpdate({
      headers: (httpData.headers || []).map(h =>
        h.id === id ? { ...h, [field]: value } : h
      ),
    });
  }, [handleUpdate, httpData.headers]);

  const handleDeleteHeader = React.useCallback((id: string) => {
    handleUpdate({
      headers: (httpData.headers || []).filter(h => h.id !== id),
    });
  }, [handleUpdate, httpData.headers]);

  // Body handlers
  const handleBodyTypeChange = React.useCallback((value: string) => {
    const bodyType = value as BodyType;
    const updates: Partial<HttpRequestData> = { bodyType };

    // Set default content type based on body type
    if (bodyType === 'json') {
      updates.contentType = 'application/json';
    } else if (bodyType === 'form-data') {
      updates.contentType = 'multipart/form-data';
    } else if (bodyType === 'x-www-form-urlencoded') {
      updates.contentType = 'application/x-www-form-urlencoded';
    } else if (bodyType === 'raw') {
      updates.contentType = 'text/plain';
    }

    handleUpdate(updates);
    if (bodyType !== 'none') {
      setShowBody(true);
    }
  }, [handleUpdate]);

  const handleBodyChange = React.useCallback((value: string) => {
    handleUpdate({ body: value });
  }, [handleUpdate]);

  // Timeout handler
  const handleTimeoutChange = React.useCallback((value: string) => {
    const timeout = parseInt(value, 10);
    if (!isNaN(timeout) && timeout > 0) {
      handleUpdate({ timeout });
    }
  }, [handleUpdate]);

  // Available outputs - aligned with backend HttpRequestNode output keys
  const httpOutputs = ['success', 'status', 'statusText', 'data', 'headers', 'error'];

  return (
    <div className="space-y-4 pt-2">
      {/* Header with info */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
        <Popover>
          <PopoverTrigger asChild>
            <button type="button" className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700">
              <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
            <div className="space-y-2 text-sm">
              <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
              <p className="text-slate-600 dark:text-slate-300">{t('infoDescription')}</p>
              <div className="border-t pt-2">
                <p className="text-xs font-semibold text-slate-500 mb-1">{t('availableOutputs')}</p>
                <ul className="text-xs text-slate-500 space-y-0.5 font-mono">
                  {httpOutputs.map((output) => (
                    <li key={output}>• {output}</li>
                  ))}
                </ul>
              </div>
            </div>
          </PopoverContent>
        </Popover>
      </div>

      {/* Method */}
      <div className="space-y-1">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('method')}</label>
        <Select value={httpData.method} onValueChange={handleMethodChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {HTTP_METHODS.map((method) => (
              <SelectItem key={method} value={method}>
                {method}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* URL */}
      <div className="space-y-1">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('url')}</label>
        <ExpressionEditor
          value={httpData.url}
          onChange={handleUrlChange}
          placeholder="https://api.example.com/endpoint"
          className="w-full"
          unknownVariables={findUnknownVariables({ url: httpData.url })}
          handleId={`http-request-url-${node.id}`}
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

      {/* Authentication */}
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('authentication')}</label>
        <Select value={httpData.authType} onValueChange={handleAuthTypeChange} disabled={isRunMode}>
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="none">{t('noAuth')}</SelectItem>
            <SelectItem value="basic">{t('basicAuth')}</SelectItem>
            <SelectItem value="bearer">{t('bearerToken')}</SelectItem>
            <SelectItem value="api-key">{t('apiKey')}</SelectItem>
            <SelectItem value="custom-header">{t('customHeader')}</SelectItem>
          </SelectContent>
        </Select>

        {/* Basic Auth */}
        {httpData.authType === 'basic' && (
          <div className="space-y-2 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('username')}</label>
              <Input
                value={httpData.authConfig?.username || ''}
                onChange={(e) => handleAuthConfigChange('username', e.target.value)}
                placeholder={t('username')}
                disabled={isRunMode}
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('password')}</label>
              <SecureInput
                value={httpData.authConfig?.password || ''}
                onChange={(value) => handleAuthConfigChange('password', value)}
                placeholder={t('password')}
                disabled={isRunMode}
              />
            </div>
          </div>
        )}

        {/* Bearer Token */}
        {httpData.authType === 'bearer' && (
          <div className="space-y-2 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
            <label className="text-xs font-medium text-slate-500">{t('token')}</label>
            <ExpressionEditor
              value={httpData.authConfig?.bearerToken || ''}
              onChange={(value) => handleAuthConfigChange('bearerToken', value)}
              placeholder={t('tokenPlaceholder')}
              className="w-full"
              unknownVariables={findUnknownVariables({ token: httpData.authConfig?.bearerToken || '' })}
              handleId={`http-request-bearer-${node.id}`}
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
        )}

        {/* API Key */}
        {httpData.authType === 'api-key' && (
          <div className="space-y-2 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('keyName')}</label>
              <Input
                value={httpData.authConfig?.apiKeyName || ''}
                onChange={(e) => handleAuthConfigChange('apiKeyName', e.target.value)}
                placeholder="X-API-Key"
                disabled={isRunMode}
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('keyValue')}</label>
              <SecureInput
                value={httpData.authConfig?.apiKeyValue || ''}
                onChange={(value) => handleAuthConfigChange('apiKeyValue', value)}
                placeholder={t('keyValuePlaceholder')}
                disabled={isRunMode}
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('addTo')}</label>
              <Select
                value={httpData.authConfig?.apiKeyLocation || 'header'}
                onValueChange={(value) => handleAuthConfigChange('apiKeyLocation', value)}
                disabled={isRunMode}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="header">{t('addToHeader')}</SelectItem>
                  <SelectItem value="query">{t('addToQuery')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
        )}

        {/* Custom Header */}
        {httpData.authType === 'custom-header' && (
          <div className="space-y-2 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('headerName')}</label>
              <Input
                value={httpData.authConfig?.headerName || ''}
                onChange={(e) => handleAuthConfigChange('headerName', e.target.value)}
                placeholder="Authorization"
                disabled={isRunMode}
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-500">{t('headerValue')}</label>
              <SecureInput
                value={httpData.authConfig?.headerValue || ''}
                onChange={(value) => handleAuthConfigChange('headerValue', value)}
                placeholder={t('headerValuePlaceholder')}
                disabled={isRunMode}
              />
            </div>
          </div>
        )}
      </div>

      {/* Query Parameters */}
      <Collapsible open={showQueryParams} onOpenChange={setShowQueryParams}>
        <div className="flex items-center justify-between">
          <CollapsibleTrigger className="flex items-center gap-1 text-sm font-semibold text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300">
            {showQueryParams ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            {t('queryParameters')}
            {(httpData.queryParams?.length || 0) > 0 && (
              <span className="ml-1 text-xs bg-slate-200 dark:bg-slate-700 px-1.5 py-0.5 rounded">
                {httpData.queryParams?.length}
              </span>
            )}
          </CollapsibleTrigger>
          {!isRunMode && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-6 w-6"
              onClick={(e) => { e.stopPropagation(); handleAddQueryParam(); }}
            >
              <Plus className="h-3 w-3" />
            </Button>
          )}
        </div>
        <CollapsibleContent className="mt-2 space-y-2">
          {(httpData.queryParams || []).map((param) => (
            <div key={param.id} className="flex gap-2 items-start">
              <Input
                value={param.key}
                onChange={(e) => handleUpdateQueryParam(param.id, 'key', e.target.value)}
                placeholder={t('key')}
                className="flex-1"
                disabled={isRunMode}
              />
              <div className="flex-1">
                <ExpressionEditor
                  value={param.value}
                  onChange={(value) => handleUpdateQueryParam(param.id, 'value', value)}
                  placeholder={t('value')}
                  className="w-full"
                  unknownVariables={findUnknownVariables({ [param.id]: param.value })}
                  handleId={`http-request-qp-${param.id}-${node.id}`}
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
              {!isRunMode && (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-slate-400 hover:text-red-500"
                  onClick={() => handleDeleteQueryParam(param.id)}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              )}
            </div>
          ))}
          {(httpData.queryParams?.length || 0) === 0 && (
            <p className="text-xs text-slate-400 italic">{t('noQueryParams')}</p>
          )}
        </CollapsibleContent>
      </Collapsible>

      {/* Headers */}
      <Collapsible open={showHeaders} onOpenChange={setShowHeaders}>
        <div className="flex items-center justify-between">
          <CollapsibleTrigger className="flex items-center gap-1 text-sm font-semibold text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300">
            {showHeaders ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            {t('headers')}
            {(httpData.headers?.length || 0) > 0 && (
              <span className="ml-1 text-xs bg-slate-200 dark:bg-slate-700 px-1.5 py-0.5 rounded">
                {httpData.headers?.length}
              </span>
            )}
          </CollapsibleTrigger>
          {!isRunMode && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-6 w-6"
              onClick={(e) => { e.stopPropagation(); handleAddHeader(); }}
            >
              <Plus className="h-3 w-3" />
            </Button>
          )}
        </div>
        <CollapsibleContent className="mt-2 space-y-2">
          {(httpData.headers || []).map((header) => (
            <div key={header.id} className="flex gap-2 items-start">
              <Input
                value={header.key}
                onChange={(e) => handleUpdateHeader(header.id, 'key', e.target.value)}
                placeholder={t('headerNamePlaceholder')}
                className="flex-1"
                disabled={isRunMode}
              />
              <div className="flex-1">
                <ExpressionEditor
                  value={header.value}
                  onChange={(value) => handleUpdateHeader(header.id, 'value', value)}
                  placeholder={t('value')}
                  className="w-full"
                  unknownVariables={findUnknownVariables({ [header.id]: header.value })}
                  handleId={`http-request-hdr-${header.id}-${node.id}`}
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
              {!isRunMode && (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-slate-400 hover:text-red-500"
                  onClick={() => handleDeleteHeader(header.id)}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              )}
            </div>
          ))}
          {(httpData.headers?.length || 0) === 0 && (
            <p className="text-xs text-slate-400 italic">{t('noHeaders')}</p>
          )}
        </CollapsibleContent>
      </Collapsible>

      {/* Body */}
      <Collapsible open={showBody} onOpenChange={setShowBody}>
        <div className="flex items-center justify-between">
          <CollapsibleTrigger className="flex items-center gap-1 text-sm font-semibold text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300">
            {showBody ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            {t('body')}
            {httpData.bodyType && httpData.bodyType !== 'none' && (
              <span className="ml-1 text-xs bg-slate-200 dark:bg-slate-700 px-1.5 py-0.5 rounded">
                {httpData.bodyType}
              </span>
            )}
          </CollapsibleTrigger>
        </div>
        <CollapsibleContent className="mt-2 space-y-2">
          <Select value={httpData.bodyType || 'none'} onValueChange={handleBodyTypeChange} disabled={isRunMode}>
            <SelectTrigger className="w-full">
              <SelectValue placeholder={t('bodyTypePlaceholder')} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="none">{t('bodyNone')}</SelectItem>
              <SelectItem value="json">{t('bodyJson')}</SelectItem>
              <SelectItem value="form-data">{t('bodyFormData')}</SelectItem>
              <SelectItem value="x-www-form-urlencoded">{t('bodyUrlEncoded')}</SelectItem>
              <SelectItem value="raw">{t('bodyRaw')}</SelectItem>
            </SelectContent>
          </Select>

          {httpData.bodyType && httpData.bodyType !== 'none' && (
            <div className="space-y-2">
              <ExpressionEditor
                value={httpData.body || ''}
                onChange={handleBodyChange}
                placeholder={
                  httpData.bodyType === 'json'
                    ? t('bodyJsonPlaceholder')
                    : httpData.bodyType === 'x-www-form-urlencoded'
                    ? t('bodyUrlEncodedPlaceholder')
                    : t('bodyRawPlaceholder')
                }
                className="w-full min-h-[100px]"
                unknownVariables={findUnknownVariables({ body: httpData.body || '' })}
                handleId={`http-request-body-${node.id}`}
                connections={connections}
                onHandleClick={handleHandleClick}
                draggingFromHandle={draggingFromHandle}
                onHandleMouseDown={handleHandleMouseDown}
                onHandleMouseUp={handleHandleMouseUp}
                hoveredTargetHandle={hoveredTargetHandle}
                onSetHandleRef={handleSetHandleRef}
                readOnly={isRunMode}
              />
              {httpData.bodyType === 'json' && (
                <p className="text-xs text-slate-400">{t('bodyJsonTip')}</p>
              )}
            </div>
          )}
        </CollapsibleContent>
      </Collapsible>

      {/* Advanced Options */}
      <Collapsible open={showAdvanced} onOpenChange={setShowAdvanced}>
        <CollapsibleTrigger className="flex items-center gap-1 text-sm font-semibold text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300">
          {showAdvanced ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          {t('advancedOptions')}
        </CollapsibleTrigger>
        <CollapsibleContent className="mt-2 space-y-3">
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-500">{t('timeout')}</label>
            <Input
              type="number"
              value={httpData.timeout || 30000}
              onChange={(e) => handleTimeoutChange(e.target.value)}
              placeholder={t('timeoutPlaceholder')}
              min={1000}
              max={300000}
              disabled={isRunMode}
            />
            <p className="text-xs text-slate-400">{t('timeoutHelp')}</p>
          </div>
        </CollapsibleContent>
      </Collapsible>
    </div>
  );
}
