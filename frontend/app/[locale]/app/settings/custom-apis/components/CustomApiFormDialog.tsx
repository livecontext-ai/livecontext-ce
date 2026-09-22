'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Plus, Trash2, ChevronDown, ChevronRight, Upload, X } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import { customApiService } from '@/lib/api/orchestrator';
import { fileService, getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import { useStandardApi } from '@/lib/hooks/useStandardApi';
import type {
  CustomApiAuthEntry,
  CustomApiDefinition,
  CustomApiDetails,
  CustomApiEndpoint,
  CustomApiEndpointParam,
  CustomApiSummary,
  CustomApiExecution,
  CustomApiOutputField,
} from '@/lib/api/orchestrator';

interface CustomApiFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  api: CustomApiSummary | null;
  onSubmit: (data: CustomApiDefinition) => void;
  isLoading: boolean;
}

const EMPTY_PARAM: CustomApiEndpointParam = {
  name: '',
  in: 'query',
  type: 'string',
  required: false,
  description: '',
};

const EMPTY_ENDPOINT: CustomApiEndpoint = {
  name: '',
  endpoint: '',
  method: 'GET',
  description: '',
  params: [],
};

/**
 * Map any stored spelling of an auth type onto the canonical one the options use.
 * Mirrors CredentialTypeNormalizer on the backend; unknown values pass through lowercased so a
 * future type degrades to "no option selected" rather than to the wrong one.
 */
/**
 * The `auth` array to submit, or nothing when there is nothing to say. Preserves a placement the
 * form cannot author, and rebuilds the OAuth endpoints from the fields that can.
 */
function buildAuthEntry(
  authType: string,
  preserved: CustomApiAuthEntry[] | undefined,
  authorizationUrl: string,
  tokenUrl: string,
  scopes: string,
): { auth?: CustomApiAuthEntry[] } {
  const base: CustomApiAuthEntry = { ...(preserved?.[0] ?? {}), type: authType };
  if (authType === 'oauth2') {
    const scopeList = scopes.trim() ? scopes.trim().split(/\s+/) : undefined;
    base.oauth2Config = {
      ...(authorizationUrl.trim() ? { authorizationUrl: authorizationUrl.trim() } : {}),
      ...(tokenUrl.trim() ? { tokenUrl: tokenUrl.trim() } : {}),
      ...(scopeList ? { scopes: scopeList } : {}),
    };
  } else {
    delete base.oauth2Config;
  }
  const meaningful = base.apiKeyConfig !== undefined || base.oauth2Config !== undefined;
  return meaningful ? { auth: [base] } : {};
}

function canonicalAuthType(raw: string | undefined | null): string {
  const v = (raw ?? '').trim().toLowerCase();
  if (!v) return 'none';
  switch (v) {
    case 'apikey':
    case 'api_key':
    case 'api-key':
      return 'api_key';
    case 'bearer':
    case 'bearer_token':
    case 'bearertoken':
    case 'bearer-token':
      return 'bearer_token';
    case 'basic':
    case 'basic_auth':
    case 'basicauth':
    case 'basic-auth':
      return 'basic_auth';
    case 'oauth':
    case 'oauth2':
    case 'oauth_2':
      return 'oauth2';
    default:
      return v;
  }
}

export function CustomApiFormDialog({
  open,
  onOpenChange,
  api,
  onSubmit,
  isLoading,
}: CustomApiFormDialogProps) {
  const t = useTranslations('customApis');
  const isEdit = api !== null;

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [authType, setAuthType] = useState('none');
  // Declared auth placement / OAuth endpoints. This form has no editor for either, so it holds
  // them only to hand them back on save. Rebuilding the definition without them silently reset a
  // custom header or a whole OAuth configuration to the default on the next edit.
  const [preservedAuth, setPreservedAuth] = useState<CustomApiAuthEntry[] | undefined>(undefined);
  const [oauthAuthorizationUrl, setOauthAuthorizationUrl] = useState('');
  const [oauthTokenUrl, setOauthTokenUrl] = useState('');
  const [oauthScopes, setOauthScopes] = useState('');
  const [preservedIconSlug, setPreservedIconSlug] = useState<string | undefined>(undefined);
  const [iconUrl, setIconUrl] = useState('');
  const [isUploadingIcon, setIsUploadingIcon] = useState(false);
  const iconInputRef = useRef<HTMLInputElement>(null);
  const [category, setCategory] = useState('Custom APIs');
  const [endpoints, setEndpoints] = useState<CustomApiEndpoint[]>([{ ...EMPTY_ENDPOINT }]);
  const [detailsApplied, setDetailsApplied] = useState(false);

  // Advanced API-level fields
  const [apiVersion, setApiVersion] = useState('');
  const [documentation, setDocumentation] = useState('');
  const [rateLimitRps, setRateLimitRps] = useState<string>('');
  const [rateLimitRpd, setRateLimitRpd] = useState<string>('');
  const [showApiAdvanced, setShowApiAdvanced] = useState(false);

  // Per-endpoint advanced toggle state (keyed by endpoint index)
  const [endpointAdvancedOpen, setEndpointAdvancedOpen] = useState<Record<number, boolean>>({});

  // Per-endpoint advanced field state stored in the endpoints array itself via updateEndpointAdvanced

  // Fetch full details when editing an existing API
  const { data: apiDetails, isLoading: fetchingDetails } = useStandardApi<CustomApiDetails>({
    queryKey: ['custom-api-details', api?.id ?? ''],
    queryFn: () => customApiService.getById(api!.id),
    enabled: open && api !== null,
  });

  // Reset form state when dialog opens/closes or api changes
  useEffect(() => {
    if (open && api) {
      setName(api.name);
      setDescription(api.description || '');
      setBaseUrl(api.baseUrl);
      setIconUrl(api.iconUrl || '');
      setIconError('');
      setEndpoints([{ ...EMPTY_ENDPOINT }]);
      setDetailsApplied(false);
      setApiVersion('');
      setDocumentation('');
      setRateLimitRps('');
      setRateLimitRpd('');
      setShowApiAdvanced(false);
      setEndpointAdvancedOpen({});
    } else if (open && !api) {
      setName('');
      setDescription('');
      setBaseUrl('');
      setAuthType('none');
      setPreservedAuth(undefined);
      setPreservedIconSlug(undefined);
      setOauthAuthorizationUrl('');
      setOauthTokenUrl('');
      setOauthScopes('');
      setIconUrl('');
      setIconError('');
      setCategory('Custom APIs');
      setEndpoints([{ ...EMPTY_ENDPOINT }]);
      setDetailsApplied(false);
      setApiVersion('');
      setDocumentation('');
      setRateLimitRps('');
      setRateLimitRpd('');
      setShowApiAdvanced(false);
      setEndpointAdvancedOpen({});
    }
  }, [open, api]);

  // Apply fetched details once available
  useEffect(() => {
    if (apiDetails && !detailsApplied && open && api) {
      // Canonicalise what the API returns before it reaches the Select. Both spellings exist in
      // the wild: everything registered before the auth type was canonicalised stored 'bearer' /
      // 'apikey' / 'oauth2', everything since stores 'bearer_token' / 'api_key' / 'oauth2'. A
      // Select whose value matches no SelectItem renders blank, so pinning the options to either
      // spelling alone leaves one half of the existing APIs with an empty control.
      setAuthType(canonicalAuthType(apiDetails.authType));
      setPreservedAuth(apiDetails.auth);
      setPreservedIconSlug(apiDetails.iconSlug);
      const storedOauth = apiDetails.auth?.[0]?.oauth2Config as
        | { authorizationUrl?: string; tokenUrl?: string; scopes?: string[] }
        | undefined;
      setOauthAuthorizationUrl(storedOauth?.authorizationUrl ?? '');
      setOauthTokenUrl(storedOauth?.tokenUrl ?? '');
      setOauthScopes((storedOauth?.scopes ?? []).join(' '));
      setCategory(apiDetails.categoryName || 'Custom APIs');
      if (apiDetails.iconUrl) setIconUrl(apiDetails.iconUrl);
      if (apiDetails.endpoints && apiDetails.endpoints.length > 0) {
        setEndpoints(apiDetails.endpoints);
      }
      // Apply advanced fields from details
      setApiVersion(apiDetails.apiVersion || '');
      setDocumentation(apiDetails.documentation || '');
      setRateLimitRps(apiDetails.rateLimits?.requestsPerSecond?.toString() || '');
      setRateLimitRpd(apiDetails.rateLimits?.requestsPerDay?.toString() || '');
      // Auto-expand advanced section if any advanced field has a value
      if (apiDetails.apiVersion || apiDetails.documentation || apiDetails.rateLimits) {
        setShowApiAdvanced(true);
      }
      setDetailsApplied(true);
    }
  }, [apiDetails, detailsApplied, open, api]);

  // Icon preview - fetched with a Bearer header and rendered from an in-memory
  // blob: URL (no token in the URL). A local blob:/external URL passes through
  // unchanged; an /api/ URL is fetched. See useAuthedObjectUrl.
  const { url: iconPreviewUrl } = useAuthedObjectUrl(iconUrl || null);

  const handleSubmit = useCallback(() => {
    if (!name.trim() || !baseUrl.trim()) return;

    const validEndpoints = endpoints.filter((ep) => ep.name.trim() !== '');
    if (validEndpoints.length === 0) return;

    const rpsNum = rateLimitRps ? parseInt(rateLimitRps, 10) : NaN;
    const rpdNum = rateLimitRpd ? parseInt(rateLimitRpd, 10) : NaN;
    const rateLimits =
      !isNaN(rpsNum) || !isNaN(rpdNum)
        ? {
            ...(!isNaN(rpsNum) ? { requestsPerSecond: rpsNum } : {}),
            ...(!isNaN(rpdNum) ? { requestsPerDay: rpdNum } : {}),
          }
        : undefined;

    const definition: CustomApiDefinition = {
      apiName: name.trim(),
      baseUrl: baseUrl.trim(),
      apiDescription: description.trim() || undefined,
      authType,
      // The declaration outlives an edit made in a form that cannot express all of it: the
      // placement is carried through verbatim, while the OAuth endpoints ARE editable here and
      // so are rebuilt from the fields. The type is re-stamped from the dropdown so the two can
      // never disagree.
      ...buildAuthEntry(authType, preservedAuth, oauthAuthorizationUrl, oauthTokenUrl, oauthScopes),
      ...(preservedIconSlug ? { iconSlug: preservedIconSlug } : {}),
      apiCategory: category.trim() || 'Custom APIs',
      ...(iconUrl.trim() ? { iconUrl: iconUrl.trim() } : {}),
      ...(apiVersion.trim() ? { apiVersion: apiVersion.trim() } : {}),
      ...(documentation.trim() ? { documentation: documentation.trim() } : {}),
      ...(rateLimits ? { rateLimits } : {}),
      endpoints: validEndpoints.map((ep) => {
        const mapped: CustomApiEndpoint = {
          ...ep,
          params: ep.params.filter((p) => p.name.trim() !== ''),
        };
        // Strip undefined advanced fields
        if (!mapped.toolCategory) delete mapped.toolCategory;
        if (!mapped.nextHint) delete mapped.nextHint;
        if (!mapped.execution?.mode) delete mapped.execution;
        return mapped;
      }),
    };

    onSubmit(definition);
  }, [name, baseUrl, description, authType, preservedAuth, preservedIconSlug, oauthAuthorizationUrl, oauthTokenUrl, oauthScopes, category, iconUrl, endpoints, apiVersion, documentation, rateLimitRps, rateLimitRpd, onSubmit]);

  const addEndpoint = useCallback(() => {
    setEndpoints((prev) => [...prev, { ...EMPTY_ENDPOINT, params: [] }]);
  }, []);

  const removeEndpoint = useCallback((index: number) => {
    setEndpoints((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const updateEndpoint = useCallback(
    (index: number, field: 'name' | 'endpoint' | 'method' | 'description', value: string) => {
      setEndpoints((prev) =>
        prev.map((ep, i) => (i === index ? { ...ep, [field]: value } : ep))
      );
    },
    []
  );

  const updateEndpointAdvanced = useCallback(
    (index: number, field: string, value: unknown) => {
      setEndpoints((prev) =>
        prev.map((ep, i) => {
          if (i !== index) return ep;
          if (field === 'execution.mode') {
            const mode = value as CustomApiExecution['mode'];
            return { ...ep, execution: mode ? { ...ep.execution, mode } : undefined };
          }
          if (field === 'outputSchemaJson') {
            try {
              const parsed = value ? JSON.parse(value as string) as CustomApiOutputField[] : undefined;
              return { ...ep, outputSchema: parsed };
            } catch {
              // Keep the raw string but don't update parsed schema
              return ep;
            }
          }
          return { ...ep, [field]: value || undefined };
        })
      );
    },
    []
  );

  const addParam = useCallback((endpointIndex: number) => {
    setEndpoints((prev) =>
      prev.map((ep, i) =>
        i === endpointIndex
          ? { ...ep, params: [...ep.params, { ...EMPTY_PARAM }] }
          : ep
      )
    );
  }, []);

  const removeParam = useCallback((endpointIndex: number, paramIndex: number) => {
    setEndpoints((prev) =>
      prev.map((ep, i) =>
        i === endpointIndex
          ? { ...ep, params: ep.params.filter((_, pi) => pi !== paramIndex) }
          : ep
      )
    );
  }, []);

  const updateParam = useCallback(
    (
      endpointIndex: number,
      paramIndex: number,
      field: keyof CustomApiEndpointParam,
      value: string | boolean
    ) => {
      setEndpoints((prev) =>
        prev.map((ep, i) =>
          i === endpointIndex
            ? {
                ...ep,
                params: ep.params.map((p, pi) =>
                  pi === paramIndex ? { ...p, [field]: value } : p
                ),
              }
            : ep
        )
      );
    },
    []
  );

  const toggleEndpointAdvanced = useCallback((index: number) => {
    setEndpointAdvancedOpen((prev) => ({ ...prev, [index]: !prev[index] }));
  }, []);

  const [iconError, setIconError] = useState('');

  const handleIconUpload = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setIconError('');

    if (!file.type.startsWith('image/')) {
      setIconError(t('form.iconInvalidType'));
      return;
    }
    if (file.size > 2 * 1024 * 1024) {
      setIconError(t('form.iconTooLarge'));
      return;
    }

    setIsUploadingIcon(true);
    try {
      const result = await fileService.uploadGeneric(file, 'api-icons');
      // The opaque id-based URL is stored on the API; the preview hook fetches it
      // (header-authenticated → blob:) for display - no token in the URL.
      setIconUrl(getFileUrlById(result.id, { inline: true }));
    } catch (err) {
      console.error('Icon upload failed:', err);
      setIconError(t('form.iconUploadFailed'));
    } finally {
      setIsUploadingIcon(false);
      if (iconInputRef.current) iconInputRef.current.value = '';
    }
  }, [t]);

  const validEndpointsForCheck = endpoints.filter((ep) => ep.name.trim() !== '');
  // OAuth2 is refused server-side without both endpoints, and that refusal surfaces as a bare
  // "Error" toast with no message, so the user would be told nothing about which field is
  // missing. Keeping Save disabled says it where it can be acted on.
  const oauthIsComplete =
    authType !== 'oauth2' ||
    (oauthAuthorizationUrl.trim() !== '' && oauthTokenUrl.trim() !== '');

  const isValid =
    name.trim() !== '' &&
    baseUrl.trim() !== '' &&
    oauthIsComplete &&
    validEndpointsForCheck.length > 0 &&
    validEndpointsForCheck.every(
      (ep) =>
        ep.outputSchema &&
        ep.outputSchema.length > 0 &&
        ep.params
          .filter((param) => param.name.trim() !== '')
          .every((param) => param.description.trim() !== ''),
    );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{isEdit ? t('editTitle') : t('createTitle')}</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          {fetchingDetails && (
            <div className="flex items-center gap-2 text-sm text-theme-secondary">
              <LoadingSpinner size="xs" />
              {t('form.loadingDetails')}
            </div>
          )}

          {/* Icon - centered like agent avatar */}
          <div className="flex flex-col items-center gap-1.5">
            <div className="relative group">
              <button
                type="button"
                onClick={() => iconInputRef.current?.click()}
                disabled={isUploadingIcon}
                className="h-20 w-20 rounded-full border-2 border-dashed border-theme hover:border-accent flex items-center justify-center overflow-hidden transition-all bg-muted hover:scale-105"
              >
                {isUploadingIcon ? (
                  <LoadingSpinner size="sm" />
                ) : iconPreviewUrl ? (
                  <img src={iconPreviewUrl} alt="API icon" className="h-full w-full object-cover" />
                ) : (
                  <Upload className="h-5 w-5 text-theme-secondary" />
                )}
              </button>
              {iconPreviewUrl && !isUploadingIcon && (
                <button
                  type="button"
                  onClick={() => { setIconUrl(''); }}
                  className="absolute -top-1 -right-1 h-5 w-5 rounded-lg bg-red-500 text-white flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity"
                >
                  <X className="h-3 w-3" />
                </button>
              )}
              <input
                ref={iconInputRef}
                type="file"
                accept="image/*"
                onChange={handleIconUpload}
                className="hidden"
              />
            </div>
            {iconError && (
              <p className="text-xs text-red-500">{iconError}</p>
            )}
          </div>

          {/* API Name */}
          <div>
            <Label className="text-sm">{t('form.name')}</Label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('form.namePlaceholder')}
              className="mt-1"
            />
          </div>

          {/* Description */}
          <div>
            <Label className="text-sm">{t('form.description')}</Label>
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t('form.descriptionPlaceholder')}
              className="mt-1"
            />
          </div>

          {/* Base URL */}
          <div>
            <Label className="text-sm">{t('form.baseUrl')}</Label>
            <Input
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder={t('form.baseUrlPlaceholder')}
              className="mt-1"
            />
          </div>

          {/* Auth Type */}
          <div>
            <Label className="text-sm">{t('form.authType')}</Label>
            <Select value={authType} onValueChange={setAuthType}>
              <SelectTrigger className="mt-1">
                <SelectValue placeholder={t('form.authType')} />
              </SelectTrigger>
              <SelectContent>
                {/* Canonical spellings. What is STORED may be either spelling, so the loaded
                    value is canonicalised on the way in rather than the options being widened. */}
                <SelectItem value="none">{t('form.authNone')}</SelectItem>
                <SelectItem value="bearer_token">{t('form.authBearer')}</SelectItem>
                <SelectItem value="api_key">{t('form.authApiKey')}</SelectItem>
                <SelectItem value="basic_auth">{t('form.authBasic')}</SelectItem>
                <SelectItem value="oauth2">{t('form.authOAuth2')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* OAuth2 endpoints. Registration REFUSES oauth2 without them, so without these fields
              the option would be a dead end - and an API already stored as oauth2 could not be
              saved at all, because the form had no way to supply what the backend now requires. */}
          {authType === 'oauth2' && (
            <div className="space-y-2 rounded-md border border-theme-border-subtle p-3">
              <p className="text-sm text-theme-text-secondary">{t('form.oauthHint')}</p>
              {!oauthIsComplete && (
                <p className="text-sm text-red-500">{t('form.oauthUrlsRequired')}</p>
              )}
              <div>
                <Label className="text-sm">{t('form.oauthAuthorizationUrl')}</Label>
                <Input
                  value={oauthAuthorizationUrl}
                  onChange={(e) => setOauthAuthorizationUrl(e.target.value)}
                  placeholder="https://provider.example.com/oauth/authorize"
                  className="mt-1"
                />
              </div>
              <div>
                <Label className="text-sm">{t('form.oauthTokenUrl')}</Label>
                <Input
                  value={oauthTokenUrl}
                  onChange={(e) => setOauthTokenUrl(e.target.value)}
                  placeholder="https://provider.example.com/oauth/token"
                  className="mt-1"
                />
              </div>
              <div>
                <Label className="text-sm">{t('form.oauthScopes')}</Label>
                <Input
                  value={oauthScopes}
                  onChange={(e) => setOauthScopes(e.target.value)}
                  placeholder="read write"
                  className="mt-1"
                />
              </div>
            </div>
          )}

          {/* Category */}
          <div>
            <Label className="text-sm">{t('form.category')}</Label>
            <Input
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              placeholder={t('form.categoryPlaceholder')}
              className="mt-1"
            />
          </div>

          {/* API-Level Advanced Settings */}
          <div className="border border-theme rounded-lg">
            <button
              type="button"
              onClick={() => setShowApiAdvanced(!showApiAdvanced)}
              className="flex items-center gap-1.5 w-full px-3 py-2 text-sm text-theme-secondary hover:text-theme-primary transition-colors"
            >
              {showApiAdvanced ? (
                <ChevronDown className="h-3.5 w-3.5" />
              ) : (
                <ChevronRight className="h-3.5 w-3.5" />
              )}
              {t('form.advancedSettings')}
            </button>
            {showApiAdvanced && (
              <div className="px-3 pb-3 space-y-3 border-t border-theme">
                <div className="pt-3">
                  <Label className="text-sm">{t('form.apiVersion')}</Label>
                  <Input
                    value={apiVersion}
                    onChange={(e) => setApiVersion(e.target.value)}
                    placeholder={t('form.apiVersionPlaceholder')}
                    className="mt-1"
                  />
                </div>
                <div>
                  <Label className="text-sm">{t('form.documentation')}</Label>
                  <Input
                    type="url"
                    value={documentation}
                    onChange={(e) => setDocumentation(e.target.value)}
                    placeholder={t('form.documentationPlaceholder')}
                    className="mt-1"
                  />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <Label className="text-sm">{t('form.rateLimitRps')}</Label>
                    <Input
                      type="number"
                      min={0}
                      value={rateLimitRps}
                      onChange={(e) => setRateLimitRps(e.target.value)}
                      placeholder="e.g. 10"
                      className="mt-1"
                    />
                  </div>
                  <div>
                    <Label className="text-sm">{t('form.rateLimitRpd')}</Label>
                    <Input
                      type="number"
                      min={0}
                      value={rateLimitRpd}
                      onChange={(e) => setRateLimitRpd(e.target.value)}
                      placeholder="e.g. 10000"
                      className="mt-1"
                    />
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Endpoints */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <Label className="text-sm font-semibold">{t('endpoints.title')}</Label>
              <Button variant="outline" size="sm" onClick={addEndpoint}>
                <Plus className="h-3.5 w-3.5 mr-1" />
                {t('endpoints.add')}
              </Button>
            </div>

            <div className="space-y-4">
              {endpoints.map((ep, epIdx) => (
                <div
                  key={epIdx}
                  className="border border-theme rounded-lg p-3 space-y-3"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-theme-secondary font-medium">
                      {t('endpoints.title')} {epIdx + 1}
                    </span>
                    {endpoints.length > 1 && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => removeEndpoint(epIdx)}
                        className="h-6 px-2 text-red-500"
                      >
                        <Trash2 className="h-3 w-3 mr-1" />
                        {t('endpoints.remove')}
                      </Button>
                    )}
                  </div>

                  <div className="grid grid-cols-3 gap-2">
                    <div>
                      <Label className="text-xs">{t('endpoints.name')}</Label>
                      <Input
                        value={ep.name}
                        onChange={(e) => updateEndpoint(epIdx, 'name', e.target.value)}
                        placeholder={t('endpoints.namePlaceholder')}
                        className="mt-0.5 text-sm"
                      />
                    </div>
                    <div>
                      <Label className="text-xs">{t('endpoints.path')}</Label>
                      <Input
                        value={ep.endpoint}
                        onChange={(e) => updateEndpoint(epIdx, 'endpoint', e.target.value)}
                        placeholder={t('endpoints.pathPlaceholder')}
                        className="mt-0.5 text-sm"
                      />
                    </div>
                    <div>
                      <Label className="text-xs">{t('endpoints.method')}</Label>
                      <Select
                        value={ep.method}
                        onValueChange={(v) => updateEndpoint(epIdx, 'method', v)}
                      >
                        <SelectTrigger className="mt-0.5 text-sm">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => (
                            <SelectItem key={m} value={m}>
                              {m}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                  </div>

                  <div>
                    <Label className="text-xs">{t('endpoints.description')}</Label>
                    <Input
                      value={ep.description}
                      onChange={(e) => updateEndpoint(epIdx, 'description', e.target.value)}
                      placeholder={t('endpoints.descriptionPlaceholder')}
                      className="mt-0.5 text-sm"
                    />
                  </div>

                  {/* Parameters */}
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <Label className="text-xs">{t('endpoints.params')}</Label>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => addParam(epIdx)}
                        className="h-6 px-2 text-xs"
                      >
                        <Plus className="h-3 w-3 mr-1" />
                        {t('endpoints.addParam')}
                      </Button>
                    </div>
                    {ep.params.length > 0 && (
                      <div className="space-y-2">
                        {ep.params.map((param, pIdx) => (
                          <div key={pIdx} className="space-y-2 rounded-lg border border-theme p-2">
                            <div className="flex flex-wrap items-center gap-2">
                              <Input
                                value={param.name}
                                onChange={(e) =>
                                  updateParam(epIdx, pIdx, 'name', e.target.value)
                                }
                                placeholder={t('endpoints.paramName')}
                                className="min-w-32 flex-1 text-xs"
                              />
                              <Select
                                value={param.in}
                                onValueChange={(v) =>
                                  updateParam(epIdx, pIdx, 'in', v)
                                }
                              >
                                <SelectTrigger className="w-24 text-xs">
                                  <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                  <SelectItem value="query">query</SelectItem>
                                  <SelectItem value="path">path</SelectItem>
                                  <SelectItem value="body">body</SelectItem>
                                  <SelectItem value="header">header</SelectItem>
                                </SelectContent>
                              </Select>
                              <Select
                                value={param.type}
                                onValueChange={(v) =>
                                  updateParam(epIdx, pIdx, 'type', v)
                                }
                              >
                                <SelectTrigger className="w-24 text-xs">
                                  <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                  <SelectItem value="string">string</SelectItem>
                                  <SelectItem value="integer">integer</SelectItem>
                                  <SelectItem value="boolean">boolean</SelectItem>
                                  <SelectItem value="number">number</SelectItem>
                                </SelectContent>
                              </Select>
                              <label className="flex items-center gap-1 text-xs whitespace-nowrap">
                                <input
                                  type="checkbox"
                                  checked={param.required}
                                  onChange={(e) =>
                                    updateParam(epIdx, pIdx, 'required', e.target.checked)
                                  }
                                  className="rounded"
                                />
                                {t('endpoints.paramRequired')}
                              </label>
                              <label className="flex items-center gap-1 text-xs whitespace-nowrap">
                                <input
                                  type="checkbox"
                                  checked={param.hidden || false}
                                  onChange={(e) =>
                                    updateParam(epIdx, pIdx, 'hidden', e.target.checked)
                                  }
                                  className="rounded"
                                />
                                {t('endpoints.paramHidden')}
                              </label>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => removeParam(epIdx, pIdx)}
                                className="h-6 w-6 p-0 text-red-500 shrink-0"
                              >
                                <Trash2 className="h-3 w-3" />
                              </Button>
                            </div>
                            <Input
                              value={param.description}
                              onChange={(e) =>
                                updateParam(epIdx, pIdx, 'description', e.target.value)
                              }
                              placeholder={t('endpoints.paramDescription')}
                              className={`text-xs ${
                                param.name.trim() && !param.description.trim()
                                  ? 'border-red-400'
                                  : ''
                              }`}
                            />
                          </div>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* Output Schema - required */}
                  <div>
                    <Label className="text-xs">
                      {t('endpoints.outputSchema')} <span className="text-red-500">*</span>
                    </Label>
                    <textarea
                      value={
                        ep.outputSchema
                          ? JSON.stringify(ep.outputSchema, null, 2)
                          : ''
                      }
                      onChange={(e) =>
                        updateEndpointAdvanced(epIdx, 'outputSchemaJson', e.target.value)
                      }
                      placeholder='[{"key":"id","type":"string","description":"..."}]'
                      className={`mt-0.5 w-full min-h-[60px] rounded-md border bg-background px-3 py-2 text-xs font-mono ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                        ep.name.trim() && (!ep.outputSchema || ep.outputSchema.length === 0)
                          ? 'border-red-400'
                          : 'border-input'
                      }`}
                      rows={3}
                    />
                    {ep.name.trim() && (!ep.outputSchema || ep.outputSchema.length === 0) && (
                      <p className="text-xs text-red-500 mt-0.5">{t('endpoints.outputSchemaRequired')}</p>
                    )}
                  </div>

                  {/* Endpoint Advanced Section */}
                  <div className="border-t border-theme pt-2">
                    <button
                      type="button"
                      onClick={() => toggleEndpointAdvanced(epIdx)}
                      className="flex items-center gap-1.5 text-xs text-theme-secondary hover:text-theme-primary transition-colors"
                    >
                      {endpointAdvancedOpen[epIdx] ? (
                        <ChevronDown className="h-3 w-3" />
                      ) : (
                        <ChevronRight className="h-3 w-3" />
                      )}
                      {t('endpoints.advanced')}
                    </button>
                    {endpointAdvancedOpen[epIdx] && (
                      <div className="mt-2 space-y-2">
                        <div className="grid grid-cols-2 gap-2">
                          <div>
                            <Label className="text-xs">{t('endpoints.toolCategory')}</Label>
                            <Input
                              value={ep.toolCategory || ''}
                              onChange={(e) =>
                                updateEndpointAdvanced(epIdx, 'toolCategory', e.target.value)
                              }
                              placeholder="e.g. email, storage"
                              className="mt-0.5 text-xs"
                            />
                          </div>
                          <div>
                            <Label className="text-xs">{t('endpoints.nextHint')}</Label>
                            <Input
                              value={ep.nextHint || ''}
                              onChange={(e) =>
                                updateEndpointAdvanced(epIdx, 'nextHint', e.target.value)
                              }
                              placeholder="e.g. list_items"
                              className="mt-0.5 text-xs"
                            />
                          </div>
                        </div>
                        <div>
                          <Label className="text-xs">{t('endpoints.executionMode')}</Label>
                          <Select
                            value={ep.execution?.mode || 'sync'}
                            onValueChange={(v) =>
                              updateEndpointAdvanced(epIdx, 'execution.mode', v === 'sync' ? '' : v)
                            }
                          >
                            <SelectTrigger className="mt-0.5 text-xs">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="sync">sync</SelectItem>
                              <SelectItem value="async_poll">async_poll</SelectItem>
                              <SelectItem value="upload">upload</SelectItem>
                              <SelectItem value="streaming">streaming</SelectItem>
                            </SelectContent>
                          </Select>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t('cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={isLoading || fetchingDetails || !isValid}>
            {isLoading ? '...' : t('save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
