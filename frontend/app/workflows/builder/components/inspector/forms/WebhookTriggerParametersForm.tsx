'use client';

import * as React from 'react';
import * as ReactDOM from 'react-dom';
import { Info, X, Copy, Check, AlertCircle, Eye, EyeOff, ExternalLink } from 'lucide-react';
import type { Node } from 'reactflow';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { BuilderNodeData } from '../../../types';
import { useTranslations } from 'next-intl';
import { usePopoverPosition } from '../../../hooks/ui/usePopoverPosition';
import { webhookSettingsService } from '@/lib/api/orchestrator';
import type { StandaloneWebhook } from '@/lib/api/orchestrator';
import { CurlExamplePopover } from '@/components/webhook/CurlExamplePopover';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { buildStandaloneSourceNodeId } from '../../../utils/standaloneSourceNodeId';
import Link from 'next/link';

// HTTP Methods available for webhook
const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
type HttpMethod = typeof HTTP_METHODS[number];

// Authentication types
const AUTH_TYPES = ['none', 'basic', 'header', 'jwt'] as const;
type AuthType = typeof AUTH_TYPES[number];

// JWT Algorithms
const JWT_ALGORITHMS = ['HS256', 'HS384', 'HS512'] as const;
type JwtAlgorithm = typeof JWT_ALGORITHMS[number];

// Webhook trigger data structure
interface WebhookTriggerData {
  httpMethod: HttpMethod;
  authType: AuthType;
  basicAuth?: {
    username: string;
    password: string;
  };
  headerAuth?: {
    headerName: string;
    headerValue: string;
  };
  jwtAuth?: {
    secretKey: string;
    algorithm: JwtAlgorithm;
  };
}

// Info tooltip with available outputs section
interface WebhookInfoTooltipProps {
  title: string;
  description: string;
  outputsLabel: string;
  outputs: string[];
}

// Helper: convert form-level WebhookTriggerData to authConfig map for CurlExamplePopover
function triggerDataToAuthConfig(data: WebhookTriggerData): Record<string, string> | undefined {
  if (data.authType === 'basic' && data.basicAuth) {
    return { basicUsername: data.basicAuth.username, basicPassword: data.basicAuth.password };
  }
  if (data.authType === 'header' && data.headerAuth) {
    return { authHeaderName: data.headerAuth.headerName, authHeaderValue: data.headerAuth.headerValue };
  }
  if (data.authType === 'jwt' && data.jwtAuth) {
    return { jwtSecret: data.jwtAuth.secretKey, jwtAlgorithm: data.jwtAuth.algorithm };
  }
  return undefined;
}

const WebhookInfoTooltip = ({ title, description, outputsLabel, outputs }: WebhookInfoTooltipProps) => {
  const [isOpen, setIsOpen] = React.useState(false);
  const { buttonRef, popoverPosition } = usePopoverPosition(isOpen, 280);

  return (
    <div className="relative inline-flex">
      <button
        ref={buttonRef}
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen(!isOpen);
        }}
        className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
        title="Click for more info"
      >
        <Info className="h-3 w-3 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300" />
      </button>
      {isOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
        <>
          <div
            className="fixed inset-0 z-[9998]"
            onClick={() => setIsOpen(false)}
          />
          <div
            className="fixed z-[9999] w-72 p-3 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 shadow-lg"
            style={{ top: popoverPosition.top, left: popoverPosition.left }}
          >
            <div className="flex items-start justify-between gap-2 mb-2">
              <span className="font-medium text-sm text-slate-700 dark:text-slate-200">
                {title}
              </span>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  setIsOpen(false);
                }}
                className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
              >
                <X className="h-3.5 w-3.5 text-slate-400" />
              </button>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mb-2">
              {description}
            </p>
            <div className="border-t border-slate-200 dark:border-slate-700 pt-2">
              <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">{outputsLabel}</p>
              <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-0.5 font-mono">
                {outputs.map((output) => (
                  <li key={output}>• {output}</li>
                ))}
              </ul>
            </div>
          </div>
        </>,
        document.body
      )}
    </div>
  );
};

// Password input with show/hide toggle
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

// Module-level guard: prevent duplicate creation across remounts for the same node
const pendingOrCreatedWebhooks = new Map<string, string>();

interface WebhookTriggerParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

export function WebhookTriggerParametersForm({
  node,
  data,
  isRunMode: _isRunModeProp = false,
  onUpdate,
}: WebhookTriggerParametersFormProps) {
  const t = useTranslations('webhookTrigger');
  const ts = useTranslations('webhookSettings');

  // Use the REAL run mode from context (not the prop which is effectiveRunModeForForms=false)
  // Webhook config lives on the backend entity - editing in run mode has no effect
  const { isRunMode: isRunModeContext } = useWorkflowMode();
  const isRunMode = isRunModeContext;

  const [copied, setCopied] = React.useState(false);

  // Auto-created standalone webhook data from node
  const standaloneWebhookId = (data as any).standaloneWebhookId as string | undefined;
  const standaloneWebhookUrl = (data as any).standaloneWebhookUrl as string | undefined;
  const standaloneWebhookToken = (data as any).standaloneWebhookToken as string | undefined;

  // Refs to avoid stale closures in async effects
  const dataRef = React.useRef(data);
  dataRef.current = data;
  const onUpdateRef = React.useRef(onUpdate);
  onUpdateRef.current = onUpdate;

  // All available webhooks for the selector
  const [allWebhooks, setAllWebhooks] = React.useState<StandaloneWebhook[]>([]);
  const [listLoaded, setListLoaded] = React.useState(false);
  const [isLoadingList, setIsLoadingList] = React.useState(true);

  // Currently selected/fetched webhook details
  const [standaloneWebhook, setStandaloneWebhook] = React.useState<StandaloneWebhook | null>(null);
  const [isLoadingWebhook, setIsLoadingWebhook] = React.useState(false);
  const isCreatingRef = React.useRef(false);
  const [autoCreateFailed, setAutoCreateFailed] = React.useState(false);
  const [isLimitReached, setIsLimitReached] = React.useState(false);

  // Fetch all webhooks for the selector (also in run mode to show the name)
  React.useEffect(() => {
    setIsLoadingList(true);
    webhookSettingsService.getAll()
      .then((webhooks) => {
        setAllWebhooks(webhooks);
        setListLoaded(true);
      })
      .catch(() => {
        setAllWebhooks([]);
        setListLoaded(true);
      })
      .finally(() => setIsLoadingList(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Fetch current webhook details when node already has one
  React.useEffect(() => {
    if (standaloneWebhookId) {
      setIsLoadingWebhook(true);
      webhookSettingsService.getById(standaloneWebhookId)
        .then(setStandaloneWebhook)
        .catch(() => setStandaloneWebhook(null))
        .finally(() => setIsLoadingWebhook(false));
    }
  }, [standaloneWebhookId]);

  // Auto-create webhook if node has none (drag-and-drop path)
  // Waits for list to be loaded first so we can generate a unique name
  React.useEffect(() => {
    if (!listLoaded || standaloneWebhookId || isRunMode || isCreatingRef.current) return;
    const nodeDataId = node.id;
    // Module-level dedup guard
    if (pendingOrCreatedWebhooks.has(nodeDataId)) return;
    pendingOrCreatedWebhooks.set(nodeDataId, 'pending');
    isCreatingRef.current = true;
    setIsLoadingWebhook(true);
    setAutoCreateFailed(false);
    const webhookNumber = allWebhooks.length + 1;
    const sourceNodeId = buildStandaloneSourceNodeId('webhook', nodeDataId);
    webhookSettingsService.create({ name: `Webhook #${webhookNumber}`, sourceNodeId })
      .then((webhook) => {
        setStandaloneWebhook(webhook);
        setAllWebhooks((prev) => [...prev, webhook]);
        onUpdateRef.current({
          ...dataRef.current,
          standaloneWebhookId: webhook.id,
          standaloneWebhookUrl: webhook.webhookUrl,
          standaloneWebhookToken: webhook.token,
        } as BuilderNodeData);
      })
      .catch((err: any) => {
        const msg = err?.message || '';
        if (msg.toLowerCase().includes('limit')) {
          setIsLimitReached(true);
        }
        setAutoCreateFailed(true);
      })
      .finally(() => setIsLoadingWebhook(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listLoaded, standaloneWebhookId, isRunMode]);

  // Determine effective webhook ID and URL (from node data or local state fallback)
  const effectiveWebhookId = standaloneWebhookId || standaloneWebhook?.id;
  const effectiveWebhookUrl = standaloneWebhookUrl || standaloneWebhook?.webhookUrl || '';

  // Get webhook trigger data from node data, with defaults
  const webhookTriggerData: WebhookTriggerData = React.useMemo(() => {
    const existing = (data as any).webhookTriggerData as WebhookTriggerData | undefined;
    return existing || {
      httpMethod: standaloneWebhook?.httpMethod as HttpMethod || 'POST',
      authType: standaloneWebhook?.authType as AuthType || 'none',
    };
  }, [(data as any).webhookTriggerData, standaloneWebhook]);

  const handleCopyUrl = React.useCallback(() => {
    if (!effectiveWebhookUrl) return;
    navigator.clipboard.writeText(effectiveWebhookUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }, [effectiveWebhookUrl]);

  // Update handler for webhook trigger data (updates node data + standalone webhook via API)
  const handleUpdateWebhookData = React.useCallback((updates: Partial<WebhookTriggerData>) => {
    if (isRunMode) return;
    const newData = {
      ...webhookTriggerData,
      ...updates,
    };
    onUpdate({
      ...data,
      webhookTriggerData: newData,
    } as BuilderNodeData);

    // Also update the standalone webhook via API if one is linked
    const webhookIdToUpdate = standaloneWebhookId || standaloneWebhook?.id;
    if (webhookIdToUpdate && standaloneWebhook) {
      const authConfig: Record<string, string> = {};
      const authData = { ...webhookTriggerData, ...updates };
      if (authData.authType === 'basic' && authData.basicAuth) {
        authConfig.basicUsername = authData.basicAuth.username;
        authConfig.basicPassword = authData.basicAuth.password;
      } else if (authData.authType === 'header' && authData.headerAuth) {
        authConfig.authHeaderName = authData.headerAuth.headerName;
        authConfig.authHeaderValue = authData.headerAuth.headerValue;
      } else if (authData.authType === 'jwt' && authData.jwtAuth) {
        authConfig.jwtSecret = authData.jwtAuth.secretKey;
        authConfig.jwtAlgorithm = authData.jwtAuth.algorithm;
      }

      webhookSettingsService.update(webhookIdToUpdate, {
        name: standaloneWebhook.name,
        description: standaloneWebhook.description,
        httpMethod: authData.httpMethod,
        authType: authData.authType,
        authConfig: Object.keys(authConfig).length > 0 ? authConfig : undefined,
      }).then(setStandaloneWebhook).catch(() => {/* silently ignore API errors */});
    }
  }, [data, webhookTriggerData, isRunMode, onUpdate, standaloneWebhookId, standaloneWebhook]);

  // Handler for HTTP method change
  const handleMethodChange = React.useCallback((value: string) => {
    handleUpdateWebhookData({ httpMethod: value as HttpMethod });
  }, [handleUpdateWebhookData]);

  // Handler for auth type change
  const handleAuthTypeChange = React.useCallback((value: string) => {
    const newAuthType = value as AuthType;
    const updates: Partial<WebhookTriggerData> = { authType: newAuthType };

    // Initialize auth-specific fields when switching types
    if (newAuthType === 'basic' && !webhookTriggerData.basicAuth) {
      updates.basicAuth = { username: '', password: '' };
    } else if (newAuthType === 'header' && !webhookTriggerData.headerAuth) {
      updates.headerAuth = { headerName: 'X-API-Key', headerValue: '' };
    } else if (newAuthType === 'jwt' && !webhookTriggerData.jwtAuth) {
      updates.jwtAuth = { secretKey: '', algorithm: 'HS256' };
    }

    handleUpdateWebhookData(updates);
  }, [handleUpdateWebhookData, webhookTriggerData]);

  // Handler for basic auth fields
  const handleBasicAuthChange = React.useCallback((field: 'username' | 'password', value: string) => {
    handleUpdateWebhookData({
      basicAuth: {
        ...webhookTriggerData.basicAuth,
        username: webhookTriggerData.basicAuth?.username || '',
        password: webhookTriggerData.basicAuth?.password || '',
        [field]: value,
      },
    });
  }, [handleUpdateWebhookData, webhookTriggerData.basicAuth]);

  // Handler for header auth fields
  const handleHeaderAuthChange = React.useCallback((field: 'headerName' | 'headerValue', value: string) => {
    handleUpdateWebhookData({
      headerAuth: {
        ...webhookTriggerData.headerAuth,
        headerName: webhookTriggerData.headerAuth?.headerName || 'X-API-Key',
        headerValue: webhookTriggerData.headerAuth?.headerValue || '',
        [field]: value,
      },
    });
  }, [handleUpdateWebhookData, webhookTriggerData.headerAuth]);

  // Handler for JWT auth fields
  const handleJwtAuthChange = React.useCallback((field: 'secretKey' | 'algorithm', value: string) => {
    handleUpdateWebhookData({
      jwtAuth: {
        ...webhookTriggerData.jwtAuth,
        secretKey: webhookTriggerData.jwtAuth?.secretKey || '',
        algorithm: (webhookTriggerData.jwtAuth?.algorithm || 'HS256') as JwtAlgorithm,
        [field]: field === 'algorithm' ? value as JwtAlgorithm : value,
      },
    });
  }, [handleUpdateWebhookData, webhookTriggerData.jwtAuth]);

  // Available outputs for connected nodes
  const webhookOutputs = ['payload', 'headers', 'method', 'token', 'trigger_id', 'triggeredAt'];

  return (
    <div className="space-y-4 pt-2">
      {/* Header with info tooltip showing available outputs */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
        <WebhookInfoTooltip
          title={t('infoTitle')}
          description={t('infoDescription')}
          outputsLabel={t('availableOutputs')}
          outputs={webhookOutputs}
        />
      </div>

      {/* Loading state while creating or fetching webhook */}
      {(isLoadingWebhook || isLoadingList) && !effectiveWebhookUrl && (
        <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-blue-500" />
          <span className="text-sm text-slate-500">{t('loadingWebhook')}</span>
        </div>
      )}

      {/* Auto-create failed - limit reached or other error */}
      {autoCreateFailed && !effectiveWebhookId && !isLoadingWebhook && (
        <div className="flex items-start gap-2 p-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-lg">
          <AlertCircle className="h-4 w-4 text-amber-500 flex-shrink-0 mt-0.5" />
          <div className="text-sm text-amber-700 dark:text-amber-300 space-y-1.5">
            {isLimitReached ? (
              <>
                <p>{t('limitReached')}</p>
                <p>{t('limitReachedHint')}</p>
              </>
            ) : (
              <p>{ts('autoCreateFailed')}</p>
            )}
            <Link
              href="/app/settings/public-access"
              className="inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:underline"
            >
              <ExternalLink className="h-3 w-3" />
              {ts('manageWebhooks')}
            </Link>
          </div>
        </div>
      )}

      {/* Standalone webhook: show URL and configuration */}
      {effectiveWebhookId && effectiveWebhookUrl && (
        <div className="space-y-3">
          {/* URL Display */}
          <div className="space-y-2">
            <div className="flex items-center gap-1.5">
              <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('webhookUrl')}</label>
              <CurlExamplePopover
                webhookUrl={effectiveWebhookUrl}
                httpMethod={webhookTriggerData.httpMethod}
                authType={webhookTriggerData.authType}
                authConfig={triggerDataToAuthConfig(webhookTriggerData)}
                label={t('usageExample')}
                variant="icon"
              />
            </div>
            <div className="flex items-center gap-2">
              <div className="flex-1 relative">
                <Input
                  value={effectiveWebhookUrl}
                  readOnly
                  className="pr-10 font-mono text-xs bg-slate-50 dark:bg-slate-900"
                />
                <Button
                  variant="ghost"
                  size="sm"
                  className="absolute right-1 top-1/2 -translate-y-1/2 h-6 px-2"
                  onClick={handleCopyUrl}
                  title={t('copyUrl')}
                >
                  {copied ? (
                    <Check className="h-3.5 w-3.5 text-green-500" />
                  ) : (
                    <Copy className="h-3.5 w-3.5" />
                  )}
                </Button>
              </div>
            </div>
          </div>

          {/* HTTP Method Selector */}
          <div className="space-y-2">
            <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('httpMethod')}
            </label>
            <Select
              value={webhookTriggerData.httpMethod}
              onValueChange={handleMethodChange}
              disabled={isRunMode}
            >
              <SelectTrigger className="w-full">
                <SelectValue placeholder={t('selectMethod')} />
              </SelectTrigger>
              <SelectContent>
                {HTTP_METHODS.map((method) => (
                  <SelectItem key={method} value={method}>
                    {method}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {webhookTriggerData.httpMethod === 'GET' && (
              <p className="text-xs text-amber-600 dark:text-amber-400">
                {t('getMethodNote')}
              </p>
            )}
          </div>

          {/* Authentication Section */}
          <div className="space-y-3">
            <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('authentication')}
            </label>
            <Select
              value={webhookTriggerData.authType}
              onValueChange={handleAuthTypeChange}
              disabled={isRunMode}
            >
              <SelectTrigger className="w-full">
                <SelectValue placeholder={t('selectAuth')} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="none">{t('authNone')}</SelectItem>
                <SelectItem value="basic">{t('authBasic')}</SelectItem>
                <SelectItem value="header">{t('authHeader')}</SelectItem>
                <SelectItem value="jwt">{t('authJwt')}</SelectItem>
              </SelectContent>
            </Select>

            {/* Basic Auth Fields */}
            {webhookTriggerData.authType === 'basic' && (
              <div className="space-y-3 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
                <div className="space-y-2">
                  <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                    {t('username')}
                  </label>
                  <Input
                    value={webhookTriggerData.basicAuth?.username || ''}
                    onChange={(e) => handleBasicAuthChange('username', e.target.value)}
                    placeholder={t('enterUsername')}
                    disabled={isRunMode}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                    {t('password')}
                  </label>
                  <SecureInput
                    value={webhookTriggerData.basicAuth?.password || ''}
                    onChange={(value) => handleBasicAuthChange('password', value)}
                    placeholder={t('enterPassword')}
                    disabled={isRunMode}
                  />
                </div>
              </div>
            )}

            {/* Header Auth Fields */}
            {webhookTriggerData.authType === 'header' && (
              <div className="space-y-3 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
                <div className="space-y-2">
                  <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                    {t('headerName')}
                  </label>
                  <Input
                    value={webhookTriggerData.headerAuth?.headerName || ''}
                    onChange={(e) => handleHeaderAuthChange('headerName', e.target.value)}
                    placeholder="X-API-Key"
                    disabled={isRunMode}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                    {t('headerValue')}
                  </label>
                  <SecureInput
                    value={webhookTriggerData.headerAuth?.headerValue || ''}
                    onChange={(value) => handleHeaderAuthChange('headerValue', value)}
                    placeholder={t('enterHeaderValue')}
                    disabled={isRunMode}
                  />
                </div>
              </div>
            )}

            {/* JWT Auth Fields */}
            {webhookTriggerData.authType === 'jwt' && (
              <div className="space-y-3 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
                <div className="space-y-2">
                  <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                    {t('jwtSecret')}
                  </label>
                  <SecureInput
                    value={webhookTriggerData.jwtAuth?.secretKey || ''}
                    onChange={(value) => handleJwtAuthChange('secretKey', value)}
                    placeholder={t('enterJwtSecret')}
                    disabled={isRunMode}
                  />
                  <p className="text-xs text-slate-400">{t('jwtSecretHelp')}</p>
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                    {t('jwtAlgorithm')}
                  </label>
                  <Select
                    value={webhookTriggerData.jwtAuth?.algorithm || 'HS256'}
                    onValueChange={(value) => handleJwtAuthChange('algorithm', value)}
                    disabled={isRunMode}
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {JWT_ALGORITHMS.map((alg) => (
                        <SelectItem key={alg} value={alg}>
                          {alg}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>
            )}
          </div>

          {/* Manage in Settings link */}
          <div className="pt-1">
            <Link
              href="/app/settings/webhooks"
              className="inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:underline"
            >
              <ExternalLink className="h-3 w-3" />
              {ts('manageWebhooks')}
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
