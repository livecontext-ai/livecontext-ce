'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useTranslations, useLocale } from 'next-intl';
import {
  Key,
  Terminal,
  CheckCircle2,
  ArrowRight,
  ArrowLeft,
  Rocket,
  AlertCircle,
  ExternalLink,
  Eye,
  EyeOff,
  Save,
  Trash2,
  Check,
  Copy,
  XCircle,
  Sparkles,
  Shield,
  Settings,
  Plug,
  Cloud,
  ShoppingBag,
  Users,
  RefreshCw,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { credentialService } from '@/lib/api/orchestrator/credential.service';
import { bridgeAccessService } from '@/lib/api/orchestrator/bridge-access.service';
import { cloudLinkService, type CloudLinkStatus, type TlsInterceptProbe } from '@/lib/api/cloud-link.service';
import { apiClient } from '@/lib/api';
import { CE_COMPLETE_API_PATH } from '@/components/security/onboardingStatus';
import {
  catalogVisibilityService,
  type IntegrationVisibility,
  type CredentialFieldDef,
} from '@/lib/api/services/catalog-visibility.service';
import { cn } from '@/lib/utils';
import { ServiceIcon } from '@/components/ui/service-icon';
import { CredentialWizard, type CredentialRequirement } from '@/components/credentials/CredentialWizard';
import { useAuth } from '@/lib/providers/smart-providers';
import type {
  LlmProviderStatus,
  LlmProviderDefinition,
  PlatformCredential,
  CreatePlatformCredentialRequest,
  BridgeStatusResponse,
} from '@/lib/api/orchestrator/types';

// Map between the camelCase CLI ids the bridge uses and the local CE-setup state.
const CLI_BACKEND_ID: Record<'claudeCode' | 'codex' | 'geminiCli' | 'mistralVibe', 'claudeCode' | 'codex' | 'geminiCli' | 'mistralVibe'> = {
  claudeCode: 'claudeCode',
  codex: 'codex',
  geminiCli: 'geminiCli',
  mistralVibe: 'mistralVibe',
};

/**
 * Kebab-case bridge provider id used by `BridgeAccessGuard` /
 * `BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID` on the backend.
 * Keep this in sync - mismatched keys silently fail to bootstrap the
 * access policy, leaving it at the (disabled) default.
 */
const CLI_BRIDGE_PROVIDER: Record<'claudeCode' | 'codex' | 'geminiCli' | 'mistralVibe', string> = {
  claudeCode: 'claude-code',
  codex: 'codex',
  geminiCli: 'gemini-cli',
  mistralVibe: 'mistral-vibe',
};

// --- Constants ---

// 1: cloud connection (default), 2: AI providers (API keys), 3: CLI providers, 4: platform credentials, 5: done
const TOTAL_STEPS = 5;

const PROVIDER_DEFINITIONS: LlmProviderDefinition[] = [
  {
    providerName: 'anthropic',
    integrationName: 'llm_anthropic',
    displayName: 'Anthropic (Claude)',
    docsUrl: 'https://console.anthropic.com/settings/keys',
    placeholder: 'sk-ant-...',
  },
  {
    providerName: 'openai',
    integrationName: 'llm_openai',
    displayName: 'OpenAI (GPT)',
    docsUrl: 'https://platform.openai.com/api-keys',
    placeholder: 'sk-...',
  },
  {
    providerName: 'google',
    integrationName: 'llm_google',
    displayName: 'Google (Gemini)',
    docsUrl: 'https://aistudio.google.com/app/apikey',
    placeholder: 'AIza...',
  },
  {
    providerName: 'mistral',
    integrationName: 'llm_mistral',
    displayName: 'Mistral AI',
    docsUrl: 'https://console.mistral.ai/api-keys/',
    placeholder: '...',
  },
  {
    providerName: 'deepseek',
    integrationName: 'llm_deepseek',
    displayName: 'DeepSeek',
    docsUrl: 'https://platform.deepseek.com/api_keys',
    placeholder: 'sk-...',
  },
  {
    providerName: 'xai',
    integrationName: 'llm_xai',
    displayName: 'xAI (Grok)',
    docsUrl: 'https://console.x.ai/',
    placeholder: 'xai-...',
  },
  {
    providerName: 'perplexity',
    integrationName: 'llm_perplexity',
    displayName: 'Perplexity (Sonar)',
    docsUrl: 'https://www.perplexity.ai/settings/api',
    placeholder: 'pplx-...',
  },
  {
    providerName: 'cohere',
    integrationName: 'llm_cohere',
    displayName: 'Cohere (Command R+)',
    docsUrl: 'https://dashboard.cohere.com/api-keys',
    placeholder: '...',
  },
  {
    providerName: 'zai',
    integrationName: 'llm_zai',
    displayName: 'Z.AI (GLM)',
    docsUrl: 'https://open.bigmodel.cn/usercenter/apikeys',
    placeholder: '...',
  },
  {
    providerName: 'openrouter',
    integrationName: 'llm_openrouter',
    displayName: 'OpenRouter (Multi-provider)',
    docsUrl: 'https://openrouter.ai/settings/keys',
    placeholder: 'sk-or-...',
  },
];

// --- Merged integration type for step 2 ---

interface MergedIntegration {
  apiId: string;
  apiName: string;
  iconSlug: string;
  authType: string;
  credentialName?: string;
  category?: string;
  credentialFields?: CredentialFieldDef[];
  hasSecrets: boolean;
}

// --- Inline sub-components ---

function SetupProviderCard({
  definition,
  status,
  onSave,
  onDelete,
  tProviders,
}: {
  definition: LlmProviderDefinition;
  status: LlmProviderStatus | undefined;
  onSave: (integrationName: string, apiKey: string) => Promise<void>;
  onDelete: (integrationName: string) => Promise<void>;
  tProviders: (key: string, values?: Record<string, string>) => string;
}) {
  const [apiKey, setApiKey] = useState('');
  const [showKey, setShowKey] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [saved, setSaved] = useState(false);
  const [expanded, setExpanded] = useState(false);

  const hasDbKey = status?.hasDbKey ?? false;
  const configured = status?.configured ?? false;
  const source = status?.source ?? 'none';

  const handleSave = async () => {
    if (!apiKey.trim()) return;
    setSaving(true);
    try {
      await onSave(definition.integrationName, apiKey.trim());
      setApiKey('');
      setShowKey(false);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await onDelete(definition.integrationName);
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="rounded-xl border border-theme bg-theme-secondary/50 overflow-hidden">
      {/* Collapsed row - click to configure (compact, like platform integrations) */}
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="w-full p-3 flex items-center justify-between hover:bg-theme-secondary/80 transition-colors"
      >
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-9 h-9 bg-theme-tertiary rounded-lg flex items-center justify-center flex-shrink-0">
            <img
              src={`/icons/services/${definition.providerName}.svg`}
              alt={definition.displayName}
              className="w-5 h-5"
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
            />
          </div>
          <p className="text-sm font-medium text-theme-primary text-left truncate">{definition.displayName}</p>
        </div>

        <div className="flex items-center gap-2 flex-shrink-0">
          {configured ? (
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400">
              <CheckCircle2 className="w-3 h-3" />
              {source === 'database' ? tProviders('source.database') : tProviders('source.environment')}
            </span>
          ) : (
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-theme-tertiary text-theme-secondary">
              <Settings className="w-3 h-3" />
              {tProviders('source.none')}
            </span>
          )}
        </div>
      </button>

      {/* Expanded - the full, functional key form */}
      {expanded && (
        <div className="px-3 pb-3 pt-3 border-t border-theme space-y-2">
          <a
            href={definition.docsUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="text-xs text-theme-secondary hover:text-theme-primary inline-flex items-center gap-1"
          >
            {tProviders('getDocs')}
            <ExternalLink className="w-3 h-3" />
          </a>
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <input
                type={showKey ? 'text' : 'password'}
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder={hasDbKey ? tProviders('updateKey') : definition.placeholder}
                className="w-full h-9 px-3 pr-9 text-sm rounded-lg border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-secondary/60 focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]/40"
                onKeyDown={(e) => e.key === 'Enter' && handleSave()}
                autoFocus
              />
              <button
                type="button"
                onClick={() => setShowKey(!showKey)}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-theme-secondary hover:text-theme-primary"
              >
                {showKey ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
              </button>
            </div>
            <Button onClick={handleSave} disabled={!apiKey.trim() || saving} size="sm" className="h-9 px-3 text-xs flex-shrink-0">
              {saving ? <LoadingSpinner size="sm" className="mr-1" /> : saved ? <Check className="w-3 h-3 mr-1" /> : <Save className="w-3 h-3 mr-1" />}
              {saved ? tProviders('saved') : tProviders('save')}
            </Button>
            {hasDbKey && (
              <Button onClick={handleDelete} disabled={deleting} variant="outline" size="sm" className="h-9 px-2.5 text-xs flex-shrink-0 text-red-600 hover:text-red-700 dark:text-red-400 dark:hover:text-red-300">
                {deleting ? <LoadingSpinner size="sm" /> : <Trash2 className="w-3.5 h-3.5" />}
              </Button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function CopyableCommand({ command, label }: { command: string; label: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(command);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="flex items-center gap-2 rounded-lg bg-theme-primary border border-theme px-3 py-2 font-mono text-sm">
      <code className="flex-1 text-theme-primary truncate">{command}</code>
      <button
        type="button"
        onClick={handleCopy}
        className="flex-shrink-0 text-theme-secondary hover:text-theme-primary transition-colors"
        title={label}
      >
        {copied ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
      </button>
    </div>
  );
}

/** Inline credential form for a single integration (step 2) */
function InlineCredentialForm({
  integration,
  onSave,
  tPlatform,
}: {
  integration: MergedIntegration;
  onSave: () => void;
  tPlatform: (key: string, values?: Record<string, string>) => string;
}) {
  const [formData, setFormData] = useState<Record<string, string>>({});
  const [showSecrets, setShowSecrets] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Determine fields from credential definitions or fallback by auth type
  const fields = React.useMemo(() => {
    if (integration.credentialFields?.length) {
      const OAUTH2_FLOW_FIELDS = new Set(['refresh_token', 'access_token']);
      const relevant = integration.credentialFields.filter(
        (f) => f.type !== 'hidden' && !OAUTH2_FLOW_FIELDS.has(f.name)
      );
      return relevant.map((def) => ({
        name: def.name,
        label: def.displayName || def.name.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()),
        isPassword: def.type === 'password' || def.name.includes('secret') || def.name.includes('key') || def.name.includes('password') || def.name.includes('token'),
        required: def.required ?? true,
      }));
    }
    // Fallback by auth type
    const authType = integration.authType?.toLowerCase() || '';
    if (authType.includes('oauth2')) {
      return [
        { name: 'client_id', label: 'Client ID', isPassword: false, required: true },
        { name: 'client_secret', label: 'Client Secret', isPassword: true, required: true },
      ];
    }
    if (authType.includes('basic')) {
      return [
        { name: 'username', label: 'Username', isPassword: false, required: true },
        { name: 'password', label: 'Password', isPassword: true, required: true },
      ];
    }
    return [{ name: 'api_key', label: 'API Key', isPassword: true, required: true }];
  }, [integration]);

  const handleSave = async () => {
    const missing = fields.filter((f) => f.required && !formData[f.name]?.trim());
    if (missing.length > 0) {
      setError(tPlatform('errors.missingFields', { fields: missing.map((f) => f.label).join(', ') }));
      return;
    }

    setSaving(true);
    setError(null);
    try {
      const KNOWN_MAP: Record<string, keyof CreatePlatformCredentialRequest> = {
        client_id: 'clientId', clientId: 'clientId',
        client_secret: 'clientSecret', clientSecret: 'clientSecret',
        api_key: 'apiKey', apiKey: 'apiKey', access_token: 'apiKey',
        username: 'username', password: 'password',
      };

      const request: CreatePlatformCredentialRequest = {
        integrationName: integration.credentialName || integration.iconSlug,
        displayName: integration.apiName,
        authType: integration.authType,
        iconSlug: integration.iconSlug,
      };

      const customFields: Record<string, string> = {};
      for (const [fieldName, value] of Object.entries(formData)) {
        if (!value?.trim()) continue;
        const knownKey = KNOWN_MAP[fieldName];
        if (knownKey) {
          (request as any)[knownKey] = value;
        } else {
          customFields[fieldName] = value;
        }
      }
      if (Object.keys(customFields).length > 0) {
        request.customFields = customFields;
      }

      await credentialService.savePlatformCredential(request);
      setSaved(true);
      setFormData({});
      setTimeout(() => setSaved(false), 2000);
      onSave();
    } catch {
      setError(tPlatform('errors.saveFailedMessage'));
    } finally {
      setSaving(false);
    }
  };

  if (fields.length === 0) {
    return (
      <p className="text-xs text-emerald-600 dark:text-emerald-400 px-2 py-1">
        {tPlatform('form.noCredentialsNeeded')}
      </p>
    );
  }

  return (
    <div className="space-y-2 mt-2">
      {fields.map((field) => (
        <div key={field.name} className="relative">
          <input
            type={field.isPassword && !showSecrets[field.name] ? 'password' : 'text'}
            value={formData[field.name] || ''}
            onChange={(e) => setFormData((prev) => ({ ...prev, [field.name]: e.target.value }))}
            placeholder={field.label}
            className="w-full h-8 px-3 pr-9 text-xs rounded-lg border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-secondary/60 focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]/40"
            onKeyDown={(e) => e.key === 'Enter' && handleSave()}
          />
          {field.isPassword && (
            <button
              type="button"
              onClick={() => setShowSecrets((p) => ({ ...p, [field.name]: !p[field.name] }))}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 text-theme-secondary hover:text-theme-primary"
            >
              {showSecrets[field.name] ? <EyeOff className="w-3 h-3" /> : <Eye className="w-3 h-3" />}
            </button>
          )}
        </div>
      ))}

      {error && (
        <p className="text-xs text-red-600 dark:text-red-400 flex items-center gap-1">
          <AlertCircle className="w-3 h-3" /> {error}
        </p>
      )}

      <Button onClick={handleSave} disabled={saving} size="sm" className="h-7 px-2.5 text-xs">
        {saving ? <LoadingSpinner size="sm" className="mr-1" /> : saved ? <Check className="w-3 h-3 mr-1" /> : <Save className="w-3 h-3 mr-1" />}
        {saved ? tPlatform('success.saved') : tPlatform('saveCredential')}
      </Button>
    </div>
  );
}

/** A compact card for a platform integration (step 2) */
function PlatformIntegrationCard({
  integration,
  onConfigure,
  tPlatform,
}: {
  integration: MergedIntegration;
  onConfigure: (integration: MergedIntegration) => void;
  tPlatform: (key: string, values?: Record<string, string>) => string;
}) {
  return (
    <button
      type="button"
      onClick={() => onConfigure(integration)}
      className="w-full rounded-xl border border-theme bg-theme-secondary/50 p-3 flex items-center justify-between hover:bg-theme-secondary/80 transition-colors"
    >
      <div className="flex items-center gap-3 min-w-0">
        <ServiceIcon iconSlug={integration.iconSlug} size="sm" />
        <div className="text-left min-w-0">
          <p className="text-sm font-medium text-theme-primary truncate">{integration.apiName}</p>
          <p className="text-xs text-theme-secondary">{getAuthTypeLabel(integration.authType)}</p>
        </div>
      </div>

      <div className="flex items-center gap-2 flex-shrink-0">
        {integration.hasSecrets ? (
          <span className="flex items-center gap-1 text-xs text-emerald-600 dark:text-emerald-400 bg-emerald-50 dark:bg-emerald-900/20 px-2 py-0.5 rounded-full">
            <CheckCircle2 className="w-3 h-3" />
            {tPlatform('visibility.configured')}
          </span>
        ) : (
          <span className="flex items-center gap-1 text-xs text-theme-secondary bg-theme-tertiary px-2 py-0.5 rounded-full">
            <Settings className="w-3 h-3" />
            {tPlatform('visibility.configure')}
          </span>
        )}
      </div>
    </button>
  );
}

function getAuthTypeLabel(authType: string): string {
  if (!authType) return '';
  switch (authType.toLowerCase()) {
    case 'oauth2': return 'OAuth 2.0';
    case 'api_key': case 'apikey': return 'API Key';
    case 'basic': case 'basic_auth': return 'Basic Auth';
    case 'bearer': case 'bearer_token': return 'Bearer Token';
    default: return authType;
  }
}

function getErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

// --- Main page ---

export default function CeSetupPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const locale = useLocale();
  const t = useTranslations('ceSetup');
  const tProviders = useTranslations('aiProviders');
  const tPlatform = useTranslations('platformCredentials');
  const { isAuthenticated, isLoading: isAuthLoading, hasRole } = useAuth();

  const [currentStep, setCurrentStep] = useState(1);
  const [statuses, setStatuses] = useState<LlmProviderStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Step 3: CLI providers (Claude Code / Codex / Gemini CLI / Mistral Vibe)
  const [selectedCli, setSelectedCli] = useState<'claudeCode' | 'codex' | 'geminiCli' | 'mistralVibe'>('claudeCode');
  const [bridgeChecking, setBridgeChecking] = useState(false);
  // Per-CLI verify results. A single shared `bridgeStatus` showed the green check only for the
  // LAST verified CLI (and reset on tab switch); keying by CLI keeps every verified CLI's status.
  // Each entry also records whether the bridge ACCESS POLICY actually got enabled, so a CLI that is
  // installed + reachable but whose access stayed off is surfaced (not shown as a false green - a
  // real chat would 403 otherwise).
  const [bridgeResults, setBridgeResults] = useState<Partial<Record<
    'claudeCode' | 'codex' | 'geminiCli' | 'mistralVibe',
    { status: BridgeStatusResponse; installed: boolean; reachable: boolean; policyEnabled: boolean }
  >>>({});

  // Step 4: Platform credentials
  const [platformIntegrations, setPlatformIntegrations] = useState<MergedIntegration[]>([]);
  const [platformLoading, setPlatformLoading] = useState(false);
  const [platformLoaded, setPlatformLoaded] = useState(false);

  // No-provider warning
  const [showNoProviderWarning, setShowNoProviderWarning] = useState(false);

  // Step 1: Cloud connection - the recommended default AI source.
  const [cloudLinkStatus, setCloudLinkStatus] = useState<CloudLinkStatus | null>(null);
  const [cloudLinkConnecting, setCloudLinkConnecting] = useState(false);
  const [cloudLinkError, setCloudLinkError] = useState<string | null>(null);
  const processedCloudCallbackRef = useRef(false);
  // A TLS-intercepting antivirus/proxy can block the cloud token exchange with an
  // untrusted CA. When detected we surface a one-click "trust this proxy" card.
  const [tlsIntercept, setTlsIntercept] = useState<TlsInterceptProbe | null>(null);
  const [tlsTrusting, setTlsTrusting] = useState(false);

  const configuredCount = statuses.filter((s) => s.configured).length;
  const configuredPlatformCount = platformIntegrations.filter((i) => i.hasSecrets).length;

  // Fetch LLM provider status
  const fetchLlmStatus = useCallback(async () => {
    try {
      const data = await credentialService.getLlmProviderStatus();
      setStatuses(data);
      setError(null);
    } catch (err) {
      console.error('Failed to load LLM provider status:', err);
      setError(t('errorLoading'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  // Fetch platform integrations (lazy - only when step 2 is first shown)
  const fetchPlatformIntegrations = useCallback(async () => {
    if (platformLoaded) return;
    setPlatformLoading(true);
    try {
      const [visibilityData, credentialsData] = await Promise.all([
        catalogVisibilityService.getIntegrations().catch(() => [] as IntegrationVisibility[]),
        credentialService.getPlatformCredentials().catch(() => [] as PlatformCredential[]),
      ]);

      const credentialsByName = new Map<string, PlatformCredential>();
      for (const cred of credentialsData) {
        const key = cred.integrationName?.toLowerCase().trim();
        if (key) credentialsByName.set(key, cred);
      }

      const unique = Array.from(new Map(visibilityData.map((v) => [v.apiId, v])).values());

      const merged: MergedIntegration[] = unique.map((vis) => {
        const credKey = vis.credentialName?.toLowerCase().trim();
        const apiKey = vis.apiName?.toLowerCase().replace(/\s+/g, '').trim();
        const cred =
          (credKey ? credentialsByName.get(credKey) : undefined) ||
          (apiKey ? credentialsByName.get(apiKey) : undefined);

        let credentialFields: CredentialFieldDef[] | undefined;
        if (vis.credentialFields) {
          try {
            credentialFields =
              typeof vis.credentialFields === 'string'
                ? JSON.parse(vis.credentialFields)
                : vis.credentialFields;
          } catch { /* ignore */ }
        }

        const hasSecrets = cred != null && (cred.hasClientSecret || cred.hasApiKey || cred.hasBasicAuth || cred.hasCustomFields);

        return {
          apiId: vis.apiId,
          apiName: vis.apiName,
          iconSlug: vis.iconSlug,
          authType: vis.authType,
          credentialName: vis.credentialName,
          category: vis.category,
          credentialFields,
          hasSecrets,
        };
      });

      setPlatformIntegrations(merged);
      setPlatformLoaded(true);
    } catch (err) {
      console.error('Failed to load platform integrations:', err);
    } finally {
      setPlatformLoading(false);
    }
  }, [platformLoaded]);

  useEffect(() => {
    fetchLlmStatus();
  }, [fetchLlmStatus]);

  useEffect(() => {
    if (isAuthLoading || !isAuthenticated || !hasRole('ADMIN')) return;
    if (processedCloudCallbackRef.current) return;
    if (searchParams.get('cloud_link_callback') === '1') return;
    let cancelled = false;
    cloudLinkService.getStatus()
      .then((status) => {
        if (!cancelled) setCloudLinkStatus(status);
      })
      .catch(() => {
        // Optional setup path: keep onboarding usable if the CE cloud-link API is unavailable.
      });
    return () => {
      cancelled = true;
    };
  }, [isAuthLoading, isAuthenticated, hasRole, searchParams]);

  useEffect(() => {
    if (isAuthLoading || !isAuthenticated || !hasRole('ADMIN')) return;
    const callbackCompleted = searchParams.get('cloud_link_callback') === '1';
    const oauthState = searchParams.get('state');
    if (!callbackCompleted || !oauthState || processedCloudCallbackRef.current) return;

    processedCloudCallbackRef.current = true;
    // Cloud is the first step - land back on it so the admin sees the linked
    // confirmation right where they kicked off the connection. The backend
    // promotes the LLM source to CLOUD automatically once the install registers,
    // so the returned status already reflects the cloud-by-default config.
    setCurrentStep(1);
    setCloudLinkConnecting(true);
    setCloudLinkError(null);

    cloudLinkService.connect(oauthState)
      .then((status) => {
        setCloudLinkStatus(status);
        window.history.replaceState({}, '', `/${locale}/ce-setup`);
      })
      .catch((err: unknown) => {
        setCloudLinkError(getErrorMessage(err, t('cloudLinkConnectError')));
        // The token exchange may have failed because a TLS-intercepting AV/proxy
        // presents a CA the backend does not trust. Probe for it so we can offer
        // the one-click "trust this proxy" remedy instead of a dead-end error.
        cloudLinkService.probeTlsIntercept()
          .then((probe) => { if (probe?.intercepted) setTlsIntercept(probe); })
          .catch(() => { /* probe is best-effort; keep the generic error otherwise */ });
      })
      .finally(() => {
        setCloudLinkConnecting(false);
      });
  }, [isAuthLoading, isAuthenticated, hasRole, searchParams, locale, t]);

  // Lazy-load platform integrations when the platform step (step 4) first shows
  useEffect(() => {
    if (currentStep === 4 && !platformLoaded) {
      fetchPlatformIntegrations();
    }
  }, [currentStep, platformLoaded, fetchPlatformIntegrations]);

  // Refresh platform data after a save
  const handlePlatformSaved = useCallback(() => {
    setPlatformLoaded(false); // force re-fetch
  }, []);

  // Step 4: full credential wizard for a platform integration (Gmail, Slack, …),
  // opened on a Configure click so the user sees every field/message.
  const [wizardOpen, setWizardOpen] = useState(false);
  const [wizardRequirements, setWizardRequirements] = useState<CredentialRequirement[]>([]);

  const handleConfigureIntegration = useCallback((integration: MergedIntegration) => {
    setWizardRequirements([{ iconSlug: integration.iconSlug, serviceName: integration.apiName }]);
    setWizardOpen(true);
  }, []);

  // Refetch when platformLoaded is reset (e.g. after saving a credential)
  useEffect(() => {
    if (currentStep === 4 && !platformLoaded && !platformLoading) {
      fetchPlatformIntegrations();
    }
  }, [currentStep, platformLoaded, platformLoading, fetchPlatformIntegrations]);

  const handleSaveLlm = async (integrationName: string, apiKey: string) => {
    const def = PROVIDER_DEFINITIONS.find((d) => d.integrationName === integrationName);
    if (!def) return;

    await credentialService.savePlatformCredential({
      integrationName,
      displayName: def.displayName,
      authType: 'api_key',
      apiKey,
      category: 'llm_provider',
    });

    await credentialService.invalidateLlmCache(def.providerName);
    await fetchLlmStatus();
  };

  const handleDeleteLlm = async (integrationName: string) => {
    const def = PROVIDER_DEFINITIONS.find((d) => d.integrationName === integrationName);
    await credentialService.deletePlatformCredential(integrationName);
    if (def) {
      await credentialService.invalidateLlmCache(def.providerName);
    }
    await fetchLlmStatus();
  };

  const handleVerifyBridge = async () => {
    // Capture the CLI now: the result is stored under THIS id even if the user
    // switches tabs while the async probe is in flight.
    const cli = selectedCli;
    setBridgeChecking(true);
    try {
      // Probe only the CLI the user just selected, and force-bypass the
      // bridge cache so retries actually re-detect after an install.
      const result = await credentialService.getBridgeStatus({
        cli: CLI_BACKEND_ID[cli],
        force: true,
      });
      const installed = !!result.cli?.installed;
      const reachable = result.bridgeReachable !== false;

      // Auto-bootstrap the access policy. Every bridge ships with
      // access_mode='disabled' (V118). Without this step, a freshly onboarded
      // admin who just verified the CLI would still be refused by
      // BridgeAccessGuard on their first chat turn. We upgrade to ADMIN_ONLY
      // (safe default: only the admin can use the bridge; they can widen later
      // via /settings/ai-providers) - but only when the CLI is actually
      // installed AND the current policy is still the shipped 'disabled',
      // so we never overwrite a later manual choice. We TRACK the outcome:
      // installed + reachable is not enough to use the bridge - if the access
      // policy could not be enabled the next chat turn 403s, so we surface
      // "verified but access off" instead of a false green.
      let policyEnabled = false;
      if (installed && reachable) {
        try {
          const bridgeProvider = CLI_BRIDGE_PROVIDER[cli];
          const view = await bridgeAccessService.getPolicyView(bridgeProvider);
          if (view) {
            if (view.policy.accessMode === 'disabled') {
              await bridgeAccessService.updatePolicy(bridgeProvider, {
                accessMode: 'admin_only',
                maxRequestsPerUserPerDay: view.policy.maxRequestsPerUserPerDay ?? null,
              });
            }
            policyEnabled = true;
          }
        } catch (err) {
          // No longer silent: policyEnabled stays false so the badge/recap flag
          // it as "verified but access off" and the admin can retry.
          console.warn('Bridge access policy bootstrap failed:', err);
        }
      }
      setBridgeResults((prev) => ({ ...prev, [cli]: { status: result, installed, reachable, policyEnabled } }));
    } catch {
      setBridgeResults((prev) => ({
        ...prev,
        [cli]: {
          status: { connected: false, bridgeReachable: false, error: 'Failed to reach bridge' },
          installed: false,
          reachable: false,
          policyEnabled: false,
        },
      }));
    } finally {
      setBridgeChecking(false);
    }
  };

  const handleComplete = async () => {
    // Single source of truth is the server (auth.ce_install_state singleton),
    // written by POST /ce/complete. No localStorage marker: it leaked across
    // installs sharing an origin and made a fresh install silently skip the wizard.
    try {
      await apiClient.post(CE_COMPLETE_API_PATH, {});
    } catch (err) {
      // Non-fatal: the guard re-fetches GET /ce/status directly (no cache) on the
      // next /app visit; if the POST eventually lands it sees bootstrapped=true.
      console.warn('[ce-setup] CE complete request failed (non-fatal):', err);
    }
    router.replace(`/${locale}/app/chat`);
  };

  const handleSkip = () => {
    // A linked Cloud account is a valid AI source on its own - don't warn when
    // the admin connected Cloud but entered no local keys.
    if (configuredCount === 0 && !Object.values(bridgeResults).some((r) => r?.installed && r?.reachable) && !cloudLinkStatus?.linked) {
      setShowNoProviderWarning(true);
      return;
    }
    handleComplete();
  };

  const handleNext = () => {
    if (currentStep < TOTAL_STEPS) {
      setCurrentStep((prev) => prev + 1);
    } else {
      handleComplete();
    }
  };

  const handleBack = () => {
    if (currentStep > 1) setCurrentStep((prev) => prev - 1);
  };

  const handleConnectCloud = async () => {
    setCloudLinkConnecting(true);
    setCloudLinkError(null);
    try {
      const { authUrl } = await cloudLinkService.getAuthUrl(`/${locale}/ce-setup`);
      window.location.href = authUrl;
    } catch (err: unknown) {
      setCloudLinkError(getErrorMessage(err, t('cloudLinkConnectError')));
      setCloudLinkConnecting(false);
    }
  };

  // One-click: trust the intercepting proxy/AV CA the probe surfaced, then start a
  // fresh cloud connection (the previous OAuth state was already consumed by the
  // failed exchange, so we cannot just retry - we re-run the full connect flow).
  const handleTrustIntercept = async () => {
    if (!tlsIntercept?.caPem) return;
    setTlsTrusting(true);
    setCloudLinkError(null);
    try {
      await cloudLinkService.trustInterceptCa(tlsIntercept.caPem);
      setTlsIntercept(null);
      await handleConnectCloud();
    } catch (err: unknown) {
      setCloudLinkError(getErrorMessage(err, t('tlsIntercept.trustError')));
      setTlsTrusting(false);
    }
  };

  const cliPrefix = `bridge.${selectedCli}`;
  const cliSteps = [
    { title: tProviders(`${cliPrefix}.step1Title`), command: tProviders(`${cliPrefix}.step1Command`) },
    { title: tProviders(`${cliPrefix}.step2Title`), command: tProviders(`${cliPrefix}.step2Command`) },
    { title: tProviders(`${cliPrefix}.step3Title`), command: tProviders(`${cliPrefix}.step3Command`) },
  ];

  // Require authentication
  if (isAuthLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) {
    router.replace(`/${locale}/login?returnTo=/${locale}/ce-setup`);
    return null;
  }

  // Only admin can access CE setup
  if (!hasRole('ADMIN')) {
    router.replace(`/${locale}/app/chat`);
    return null;
  }

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <div className="flex flex-col items-center gap-3">
          <LoadingSpinner size="lg" />
          <p className="text-sm text-theme-muted">{t('loading')}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-theme-primary py-8 px-4 flex flex-col items-center pt-[12vh]">
      <div className="max-w-lg w-full">
        {/* Header */}
        <div className="text-center mb-6">
          <h1 className="text-2xl font-bold text-theme-primary">{t('title')}</h1>
          <p className="text-sm text-theme-secondary mt-1">{t('subtitle')}</p>
        </div>

        {/* Progress bar */}
        <div className="mb-6">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              {Array.from({ length: TOTAL_STEPS }, (_, i) => i + 1).map((step) => (
                <div
                  key={step}
                  className={`h-2 w-8 rounded-full transition-colors ${
                    step <= currentStep ? 'bg-[var(--accent-primary)]' : 'bg-theme-tertiary'
                  }`}
                />
              ))}
            </div>
            <button
              onClick={handleSkip}
              className="text-sm text-theme-muted hover:text-theme-secondary transition-colors"
            >
              {t('skipForNow')}
            </button>
          </div>
        </div>

        {/* Step 1: Cloud connection (recommended default) */}
        {currentStep === 1 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 min-w-[2.5rem] aspect-square flex-shrink-0 rounded-full bg-[var(--accent-primary)]/10 flex items-center justify-center">
                  <Cloud className="h-5 w-5 text-[var(--accent-primary)]" />
                </div>
                <div>
                  <CardTitle className="text-lg">{t('cloudStep.title')}</CardTitle>
                  <CardDescription>{t('cloudStep.subtitle')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              <p className="text-sm text-theme-secondary">{t('cloudStep.intro')}</p>

              <ul className="space-y-2.5">
                {([
                  { icon: Sparkles, text: t('cloudStep.benefitModels') },
                  { icon: ShoppingBag, text: t('cloudStep.benefitMarketplace') },
                  { icon: Users, text: t('cloudStep.benefitCommunity') },
                  { icon: RefreshCw, text: t('cloudStep.benefitUpdates') },
                ]).map((benefit, i) => (
                  <li key={i} className="flex items-start gap-2.5">
                    <span className="mt-0.5 flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full bg-[var(--accent-primary)]/10">
                      <benefit.icon className="h-3 w-3 text-[var(--accent-primary)]" />
                    </span>
                    <span className="text-sm text-theme-primary">{benefit.text}</span>
                  </li>
                ))}
              </ul>

              {cloudLinkStatus?.linked ? (
                <div className="flex items-center gap-2 rounded-lg border border-emerald-500/20 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-600 dark:text-emerald-400">
                  <CheckCircle2 className="h-4 w-4 flex-shrink-0" />
                  <span>
                    {t('cloudLinkConnected', {
                      username: cloudLinkStatus.cloudUsername || t('cloudLinkCloudUser'),
                    })}
                  </span>
                </div>
              ) : (
                <>
                  {tlsIntercept?.intercepted ? (
                    <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-3 space-y-2">
                      <div className="flex items-start gap-2">
                        <Shield className="h-4 w-4 flex-shrink-0 mt-0.5 text-amber-600 dark:text-amber-400" />
                        <div className="min-w-0">
                          <p className="text-sm font-medium text-amber-800 dark:text-amber-300">
                            {t('tlsIntercept.detected')}
                          </p>
                          <p className="text-xs text-amber-700/90 dark:text-amber-300/80 mt-0.5">
                            {t('tlsIntercept.description')}
                          </p>
                          {tlsIntercept.caSubject && (
                            <p className="text-xs font-mono text-theme-muted truncate mt-1" title={tlsIntercept.caSubject}>
                              {tlsIntercept.caSubject}
                            </p>
                          )}
                          {tlsIntercept.rootPresented === false && (
                            <p className="text-xs text-amber-700/90 dark:text-amber-300/80 mt-1.5">
                              {t('tlsIntercept.rootMissingHint')}
                              {tlsIntercept.caIssuer && (
                                <span className="font-mono text-theme-muted block truncate mt-0.5" title={tlsIntercept.caIssuer}>
                                  {tlsIntercept.caIssuer}
                                </span>
                              )}
                            </p>
                          )}
                        </div>
                      </div>
                      <Button onClick={handleTrustIntercept} disabled={tlsTrusting} className="w-full">
                        {tlsTrusting ? (
                          <LoadingSpinner size="xs" className="mr-2" />
                        ) : (
                          <Shield className="h-4 w-4 mr-2" />
                        )}
                        {tlsTrusting ? t('tlsIntercept.trusting') : t('tlsIntercept.trustButton')}
                      </Button>
                    </div>
                  ) : cloudLinkError ? (
                    <div className="flex items-center gap-2 rounded-lg border border-red-500/20 bg-red-500/10 px-3 py-2 text-sm text-red-600 dark:text-red-400">
                      <AlertCircle className="h-4 w-4 flex-shrink-0" />
                      <span>{cloudLinkError}</span>
                    </div>
                  ) : null}
                  <div className="space-y-2 pt-1">
                    <Button
                      onClick={handleConnectCloud}
                      disabled={cloudLinkConnecting}
                      className="w-full"
                    >
                      {cloudLinkConnecting ? (
                        <LoadingSpinner size="xs" className="mr-2" />
                      ) : (
                        <Cloud className="h-4 w-4 mr-2" />
                      )}
                      {t('cloudLinkConnect')}
                    </Button>
                    <p className="text-xs text-theme-muted text-center">{t('cloudStep.connectHint')}</p>
                  </div>
                </>
              )}
            </CardContent>
          </Card>
        )}

        {/* Step 2: AI Providers (bring your own API keys) */}
        {currentStep === 2 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 min-w-[2.5rem] aspect-square flex-shrink-0 rounded-full bg-[var(--accent-primary)]/10 flex items-center justify-center">
                  <Key className="h-5 w-5 text-[var(--accent-primary)]" />
                </div>
                <div>
                  <CardTitle className="text-lg">{t('stepProviders')}</CardTitle>
                  <CardDescription>{t('providerDescription')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-xs text-theme-muted italic">{t('providerOptional')}</p>
              {error && (
                <div className="rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-3">
                  <p className="text-sm text-red-800 dark:text-red-300 flex items-center gap-2">
                    <AlertCircle className="h-3.5 w-3.5" />
                    {error}
                  </p>
                </div>
              )}

              <div className="space-y-2 max-h-[46vh] overflow-y-auto pr-1">
                {PROVIDER_DEFINITIONS.map((def) => (
                  <SetupProviderCard
                    key={def.providerName}
                    definition={def}
                    status={statuses.find((s) => s.providerName === def.providerName)}
                    onSave={handleSaveLlm}
                    onDelete={handleDeleteLlm}
                    tProviders={tProviders}
                  />
                ))}
              </div>

              {configuredCount > 0 && (
                <div className="flex items-center gap-2 pt-2 text-sm text-emerald-600 dark:text-emerald-400">
                  <CheckCircle2 className="h-4 w-4" />
                  <span>{configuredCount} {t('configured')}</span>
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {/* Step 3: CLI Providers (Claude Code / Codex) */}
        {currentStep === 3 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 min-w-[2.5rem] aspect-square flex-shrink-0 rounded-full bg-[var(--accent-primary)]/10 flex items-center justify-center">
                  <Terminal className="h-5 w-5 text-[var(--accent-primary)]" />
                </div>
                <div>
                  <CardTitle className="text-lg">{t('stepCli')}</CardTitle>
                  <CardDescription>{t('cliDescription')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-5">
              <p className="text-xs text-theme-muted italic">{t('cliOptional')}</p>

              {/* CLI selector tabs */}
              <div className="grid grid-cols-2 gap-2">
                {([
                  { id: 'claudeCode' as const, icon: '/icons/services/claude-code.svg', alt: 'Claude Code', label: 'Claude Code' },
                  { id: 'codex' as const, icon: '/icons/services/codex.svg', alt: 'Codex', label: 'Codex CLI' },
                  { id: 'geminiCli' as const, icon: '/icons/services/gemini-cli.svg', alt: 'Gemini CLI', label: 'Gemini CLI' },
                  { id: 'mistralVibe' as const, icon: '/icons/services/mistral-vibe.svg', alt: 'Mistral Vibe', label: 'Mistral Vibe' },
                ]).map((cli) => (
                  <button
                    key={cli.id}
                    type="button"
                    onClick={() => setSelectedCli(cli.id)}
                    className={cn(
                      'px-3 py-2 rounded-lg text-sm font-medium transition-colors border flex items-center justify-center gap-2',
                      selectedCli === cli.id
                        ? 'bg-[var(--accent-primary)]/10 border-[var(--accent-primary)]/30 text-[var(--accent-primary)]'
                        : 'bg-theme-secondary/50 border-theme text-theme-secondary hover:text-theme-primary'
                    )}
                  >
                    <img src={cli.icon} alt={cli.alt} className="w-4 h-4" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                    {cli.label}
                  </button>
                ))}
              </div>

              <div className="space-y-4">
                {cliSteps.map((step, index) => (
                  <div key={`${selectedCli}-${index}`} className="space-y-2">
                    <div className="flex items-center gap-2">
                      <span className="flex-shrink-0 w-6 h-6 rounded-full bg-theme-tertiary flex items-center justify-center text-xs font-medium text-theme-primary">
                        {index + 1}
                      </span>
                      <span className="text-sm font-medium text-theme-primary">{step.title}</span>
                    </div>
                    <div className="ml-8">
                      <CopyableCommand command={step.command} label={tProviders('bridge.copy')} />
                    </div>
                  </div>
                ))}
              </div>

              <div className="flex items-center justify-end gap-3 pt-2">
                {bridgeResults[selectedCli] && (() => {
                  const r = bridgeResults[selectedCli]!;
                  const cliEntry = r.status.cli;
                  // `!== false`: only an explicit authenticated=false is "login required";
                  // an older bridge that omits the field keeps the prior behavior.
                  const authed = cliEntry?.authenticated !== false;
                  // Installed but not logged in: the CLI would still fail at run time
                  // with "please log in", so it must NOT read as a green "connected".
                  const loginRequired = r.installed && r.reachable && !authed;
                  const ok = r.installed && r.reachable && authed && r.policyEnabled;
                  const verifiedAccessOff = r.installed && r.reachable && authed && !r.policyEnabled;
                  const amber = loginRequired || verifiedAccessOff;
                  const ver = cliEntry?.version ? ` · v${cliEntry.version}` : '';
                  let label: string;
                  if (!r.reachable) {
                    label = tProviders('bridge.notConnected');
                  } else if (!r.installed) {
                    label = cliEntry?.error
                      ? `${tProviders('bridge.notConnected')} · ${cliEntry.error}`
                      : tProviders('bridge.notConnected');
                  } else if (loginRequired) {
                    label = `${tProviders('bridge.loginRequired')}${ver}`;
                  } else if (verifiedAccessOff) {
                    label = tProviders('bridge.verifiedAccessOff');
                  } else {
                    label = `${tProviders('bridge.connected')}${ver}`;
                  }
                  return (
                    <div
                      data-testid="bridge-verify-badge"
                      data-cli-status={ok ? 'connected' : loginRequired ? 'login-required' : verifiedAccessOff ? 'access-off' : 'not-verified'}
                      className={cn(
                        'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium max-w-[24rem] truncate',
                        ok
                          ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400'
                          : amber
                          ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'
                          : 'bg-theme-tertiary text-theme-secondary'
                      )}
                      title={label}
                    >
                      {ok ? <CheckCircle2 className="w-3 h-3 flex-shrink-0" /> : amber ? <AlertCircle className="w-3 h-3 flex-shrink-0" /> : <XCircle className="w-3 h-3 flex-shrink-0" />}
                      <span className="truncate">{label}</span>
                    </div>
                  );
                })()}
                <Button onClick={handleVerifyBridge} disabled={bridgeChecking} size="sm" className="h-8 px-3">
                  {bridgeChecking ? <LoadingSpinner size="xs" className="mr-1.5" /> : <Terminal className="w-3.5 h-3.5 mr-1.5" />}
                  {bridgeChecking ? tProviders('bridge.checking') : tProviders('bridge.verifyConnection')}
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Step 4: Platform Credentials */}
        {currentStep === 4 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 min-w-[2.5rem] aspect-square flex-shrink-0 rounded-full bg-[var(--accent-primary)]/10 flex items-center justify-center">
                  <Plug className="h-5 w-5 text-[var(--accent-primary)]" />
                </div>
                <div>
                  <CardTitle className="text-lg">{t('stepIntegrations')}</CardTitle>
                  <CardDescription>{t('integrationsDescription')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {platformLoading ? (
                <div className="flex items-center justify-center py-8">
                  <LoadingSpinner size="md" />
                </div>
              ) : platformIntegrations.length === 0 ? (
                <div className="text-center py-6">
                  <Shield className="w-10 h-10 mx-auto mb-3 text-theme-secondary/50" />
                  <p className="text-sm text-theme-secondary">{t('noIntegrations')}</p>
                </div>
              ) : (
                <div className="space-y-2 max-h-[400px] overflow-y-auto pr-1">
                  {platformIntegrations.map((integration) => (
                    <PlatformIntegrationCard
                      key={integration.apiId}
                      integration={integration}
                      onConfigure={handleConfigureIntegration}
                      tPlatform={tPlatform}
                    />
                  ))}

                  {configuredPlatformCount > 0 && (
                    <div className="flex items-center gap-2 pt-2 text-sm text-emerald-600 dark:text-emerald-400">
                      <CheckCircle2 className="h-4 w-4" />
                      <span>{configuredPlatformCount} {t('configured')}</span>
                    </div>
                  )}
                </div>
              )}

              <p className="text-xs text-theme-muted mt-3 italic">{t('integrationsOptional')}</p>
            </CardContent>
          </Card>
        )}

        {/* Step 5: Done */}
        {currentStep === 5 && (() => {
          const cloudLinked = !!cloudLinkStatus?.linked;
          const anyBridgeUsable = Object.values(bridgeResults).some((r) => r?.installed && r?.reachable);
          const hasAnything = cloudLinked || configuredCount > 0 || anyBridgeUsable || configuredPlatformCount > 0;
          const configuredProviders = PROVIDER_DEFINITIONS.filter(
            (def) => statuses.find((s) => s.providerName === def.providerName)?.configured
          );
          return (
            <Card className="border-theme animate-fade-in">
              <CardHeader className="pb-4">
                <div className="flex items-center gap-3">
                  <div className={cn(
                    'w-10 h-10 min-w-[2.5rem] aspect-square flex-shrink-0 rounded-full flex items-center justify-center',
                    hasAnything
                      ? 'bg-emerald-100 dark:bg-emerald-900/30'
                      : 'bg-amber-100 dark:bg-amber-900/30'
                  )}>
                    {hasAnything
                      ? <Sparkles className="h-5 w-5 text-emerald-600 dark:text-emerald-400" />
                      : <AlertCircle className="h-5 w-5 text-amber-600 dark:text-amber-400" />}
                  </div>
                  <div>
                    <CardTitle className="text-lg">
                      {hasAnything ? t('doneTitle') : t('doneNothingTitle')}
                    </CardTitle>
                    <CardDescription>
                      {hasAnything ? t('doneDescription') : t('doneNothingDescription')}
                    </CardDescription>
                  </div>
                </div>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="space-y-2">
                  {/* Cloud connection */}
                  {cloudLinked && (
                    <div className="flex items-center justify-between px-3 py-2 rounded-lg bg-theme-secondary/50">
                      <div className="flex items-center gap-2">
                        <Cloud className="w-4 h-4 text-sky-500" />
                        <span className="text-sm text-theme-primary">
                          {t('cloudLinkConnected', {
                            username: cloudLinkStatus?.cloudUsername || t('cloudLinkCloudUser'),
                          })}
                        </span>
                      </div>
                      <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                    </div>
                  )}

                  {/* Configured API providers */}
                  {configuredProviders.map((def) => (
                    <div key={def.providerName} className="flex items-center justify-between px-3 py-2 rounded-lg bg-theme-secondary/50">
                      <div className="flex items-center gap-2">
                        <img
                          src={`/icons/services/${def.providerName}.svg`}
                          alt={def.displayName}
                          className="w-4 h-4"
                          onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
                        />
                        <span className="text-sm text-theme-primary">{def.displayName}</span>
                      </div>
                      <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                    </div>
                  ))}

                  {/* CLI providers - one row per CLI, each reflecting its OWN verify result
                      (green = verified + access enabled, amber = verified but access off, grey =
                      not verified). Previously a single shared status showed only the last CLI. */}
                  {([
                    { id: 'claudeCode' as const, icon: '/icons/services/claude-code.svg', label: 'Claude Code' },
                    { id: 'codex' as const, icon: '/icons/services/codex.svg', label: 'Codex CLI' },
                    { id: 'geminiCli' as const, icon: '/icons/services/gemini-cli.svg', label: 'Gemini CLI' },
                    { id: 'mistralVibe' as const, icon: '/icons/services/mistral-vibe.svg', label: 'Mistral Vibe' },
                  ]).map((cli) => {
                    const r = bridgeResults[cli.id];
                    const authed = r?.status.cli?.authenticated !== false;
                    const loginRequired = !!(r?.installed && r?.reachable && !authed);
                    const ok = !!(r?.installed && r?.reachable && authed && r?.policyEnabled);
                    const verifiedAccessOff = !!(r?.installed && r?.reachable && authed && !r?.policyEnabled);
                    const amber = loginRequired || verifiedAccessOff;
                    return (
                      <div
                        key={cli.id}
                        data-testid={`cli-recap-${cli.id}`}
                        data-cli-status={ok ? 'connected' : loginRequired ? 'login-required' : verifiedAccessOff ? 'access-off' : 'not-verified'}
                        className="flex items-center justify-between px-3 py-2 rounded-lg bg-theme-secondary/50"
                      >
                        <div className="flex items-center gap-2 min-w-0">
                          <img src={cli.icon} alt="" className="w-4 h-4 flex-shrink-0" onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                          <span className="text-sm text-theme-primary">{cli.label}</span>
                          {loginRequired ? (
                            <span className="text-xs text-amber-600 dark:text-amber-400 truncate">· {tProviders('bridge.loginRequired')}</span>
                          ) : verifiedAccessOff ? (
                            <span className="text-xs text-amber-600 dark:text-amber-400 truncate">· {tProviders('bridge.verifiedAccessOff')}</span>
                          ) : null}
                        </div>
                        {ok
                          ? <CheckCircle2 className="w-4 h-4 text-emerald-500 flex-shrink-0" />
                          : amber
                          ? <AlertCircle className="w-4 h-4 text-amber-500 flex-shrink-0" />
                          : <XCircle className="w-4 h-4 text-theme-secondary/40 flex-shrink-0" />}
                      </div>
                    );
                  })}

                  {/* Platform credentials summary */}
                  {configuredPlatformCount > 0 && (
                    <div className="flex items-center justify-between px-3 py-2 rounded-lg bg-theme-secondary/50">
                      <div className="flex items-center gap-2">
                        <Plug className="w-4 h-4 text-theme-primary" />
                        <span className="text-sm text-theme-primary">
                          {t('integrationsConfigured', { count: String(configuredPlatformCount) })}
                        </span>
                      </div>
                      <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                    </div>
                  )}

                  {/* Show unconfigured providers when nothing is set up */}
                  {configuredProviders.length === 0 && PROVIDER_DEFINITIONS.map((def) => (
                    <div key={def.providerName} className="flex items-center justify-between px-3 py-2 rounded-lg bg-theme-secondary/50">
                      <div className="flex items-center gap-2">
                        <img
                          src={`/icons/services/${def.providerName}.svg`}
                          alt={def.displayName}
                          className="w-4 h-4"
                          onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
                        />
                        <span className="text-sm text-theme-primary">{def.displayName}</span>
                      </div>
                      <XCircle className="w-4 h-4 text-theme-secondary/40" />
                    </div>
                  ))}

                </div>

                <p className="text-xs text-theme-muted">{t('doneChangeSettings')}</p>
              </CardContent>
            </Card>
          );
        })()}

        {/* No provider warning */}
        {showNoProviderWarning && (
          <div className="mt-4 p-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-xl">
            <p className="text-sm text-amber-800 dark:text-amber-300 mb-3 flex items-center gap-2">
              <AlertCircle className="h-4 w-4 flex-shrink-0" />
              {t('noProviderConfirm')}
            </p>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" className="h-8" onClick={() => { setShowNoProviderWarning(false); handleComplete(); }}>
                {t('continueAnyway')}
              </Button>
              <Button size="sm" className="h-8" onClick={() => { setShowNoProviderWarning(false); setCurrentStep(2); }}>
                {t('configureProvider')}
              </Button>
            </div>
          </div>
        )}

        {/* Navigation */}
        <div className="mt-6 flex items-center justify-between">
          <Button variant="ghost" onClick={handleBack} disabled={currentStep <= 1}>
            <ArrowLeft className="h-4 w-4 mr-2" />
            {t('back')}
          </Button>

          <div className="flex items-center gap-2">
            {currentStep < TOTAL_STEPS && (
              <Button variant="ghost" onClick={handleNext} className="text-theme-muted">
                {t('skip')}
              </Button>
            )}
            <Button
              onClick={currentStep === TOTAL_STEPS ? handleComplete : handleNext}
              // Step 1 (connect cloud): force a deliberate choice. "Next" stays disabled
              // until the cloud is linked, so the admin either connects cloud (Next then
              // enables) or uses "Skip" to opt out - catalog freshness + the LLM relay are
              // cloud-link benefits, so we don't offer a passive "Next" past this step.
              disabled={currentStep === 1 && !cloudLinkStatus?.linked}
            >
              {currentStep === TOTAL_STEPS ? (
                <>
                  <Rocket className="h-4 w-4 mr-2" />
                  {t('letsGo')}
                </>
              ) : (
                <>
                  {t('next')}
                  <ArrowRight className="h-4 w-4 ml-2" />
                </>
              )}
            </Button>
          </div>
        </div>

        {/* Full credential wizard for platform integrations (step 4) - shows every field/message */}
        <CredentialWizard
          requirements={wizardRequirements}
          open={wizardOpen}
          onOpenChange={(open) => {
            setWizardOpen(open);
            if (!open) {
              setWizardRequirements([]);
              handlePlatformSaved();
            }
          }}
          onComplete={handlePlatformSaved}
        />
      </div>
    </div>
  );
}
