'use client';

import * as React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink, Plus, DollarSign, User } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { orchestratorApi, type Credential, type CredentialTemplate } from '@/lib/api/orchestrator';
import { CredentialWizard, resolveByokConfig, resolveByokOnlyScopeList, resolvePlatformScopeList } from '@/components/credentials/CredentialWizard';
import { Select, SelectContent, SelectItem, SelectSeparator, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ToggleGroup } from '@/components/ui/toggle-group';
import Toast, { useToast } from '@/components/Toast';
import { useTranslations } from 'next-intl';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import {
  matchUserCredentialsForTool,
  findBestUserCredential,
} from '@/lib/credentials/credentialMatching';
import { normalizeScopes } from '@/lib/credentials/normalizeScopes';
import { MissingScopesBanner } from './MissingScopesBanner';

export type CredentialSource = 'user' | 'platform';

interface ToolCredential {
  credentialName: string;
  isRequired: boolean;
  usage?: string;
  displayName?: string;
  description?: string;
  authType?: string;
  iconUrl?: string;
  credentialType?: string;
  testEndpoint?: string;
  documentationUrl?: string;
  properties?: string;
}

interface CredentialSectionProps {
  toolCredentials: ToolCredential[];
  selectedCredentialId?: number | null;
  onCredentialSelect: (credentialId: number | null, credentialName: string) => void;
  integration?: string;
  /**
   * V166: per-endpoint OAuth scope requirements coming from the MCP node's
   * `metadata.requiredScopes`. When set and the user's bound credential is
   * OAuth2 + missing scopes, a warning banner is shown above the picker.
   * Optional - leave undefined for non-MCP nodes or tools without requirements.
   */
  requiredScopes?: string[];
  /**
   * UUID of the api_tool row this node is bound to. When provided, the
   * platform toggle is gated on *per-endpoint* pricing - the toggle stays
   * hidden for endpoints with no non-zero rate, even if the API as a whole
   * has some pricing. When absent, we fall back to integration-level
   * "any non-zero rate" semantics (e.g. older nodes without apiToolId
   * persisted, or SMTP/SSH/DB nodes that never bind to a catalog tool).
   */
  apiToolId?: string | null;
  isRunMode?: boolean;
  onCredentialStatusChange?: (allRequiredConfigured: boolean) => void;
  /**
   * Read-only container (e.g., agent-fleet inspector) - disables the
   * auto-persist effect so we don't fire onCredentialSelect into the void.
   */
  isReadOnly?: boolean;
  /**
   * Current credential source on the node. `'user'` = user's own credential
   * (default), `'platform'` = shared platform credential billed with per-call
   * markup. When omitted, behaves as `'user'`.
   */
  credentialSource?: CredentialSource;
  /** Platform credential row id - only set when `credentialSource === 'platform'`. */
  platformCredentialId?: number | null;
  /**
   * Invoked when the user switches between user/platform. For `'platform'`
   * we also pass the credential id so the caller can persist both fields in a
   * single update (backend requires `platform ⇒ id != null`).
   */
  onCredentialSourceChange?: (
    source: CredentialSource,
    platformCredentialId: number | null,
  ) => void;
}

interface CredentialStatus {
  credential: ToolCredential;
  userCredentials: Credential[];
  isConfigured: boolean;
  selectedId: number | null;
}

type CredentialWizardMode = 'standard' | 'advanced';

export function resolveConfigureModeForRequiredScopes(
  requiredScopes: string[] | undefined | null,
  template: CredentialTemplate | null | undefined,
): CredentialWizardMode {
  const required = normalizeScopes(requiredScopes);
  if (required.length === 0 || !template) return 'standard';

  const byokOnlyScopes = resolveByokOnlyScopeList(template);
  const byokOffered =
    resolveByokConfig(template).surface !== 'hidden' || byokOnlyScopes.length > 0;
  if (!byokOffered) return 'standard';

  const platformScopeSet = new Set(normalizeScopes(resolvePlatformScopeList(template)));
  return required.some((scope) => !platformScopeSet.has(scope)) ? 'advanced' : 'standard';
}

export function CredentialSection({
  toolCredentials,
  selectedCredentialId,
  onCredentialSelect,
  integration,
  apiToolId,
  isRunMode = false,
  onCredentialStatusChange,
  isReadOnly = false,
  credentialSource = 'user',
  platformCredentialId,
  onCredentialSourceChange,
  requiredScopes,
}: CredentialSectionProps) {
  const t = useTranslations('credentials');
  const { toasts, addToast, removeToast } = useToast();
  const { isPreviewOnly } = useWorkflowMode();

  // Credentials are user-scoped config - always interactive, even in preview/readonly mode.
  // Only truly disabled during active run execution (isRunMode && !isPreviewOnly).
  const isDisabled = isRunMode && !isPreviewOnly;

  const queryClient = useQueryClient();

  // Wizard state - use template mode like /settings/credentials
  const [isWizardOpen, setIsWizardOpen] = React.useState(false);
  const [selectedTemplate, setSelectedTemplate] = React.useState<CredentialTemplate | null>(null);
  const [configuringCredential, setConfiguringCredential] = React.useState<ToolCredential | null>(null);
  // V166 BYOK: which mode the wizard opens on. 'advanced' = jump straight to
  // the BYOK form (oauth-config step). Set either by an explicit CTA or by
  // requiredScopes when Standard cannot grant the endpoint's scopes.
  const [wizardInitialMode, setWizardInitialMode] = React.useState<CredentialWizardMode>('standard');

  // Shared cache for user credentials - deduplicated across all CredentialSection instances
  const {
    data: userCredentials = [],
    isLoading: isLoadingCredentials,
    error: credentialsError,
  } = useQuery({
    queryKey: ['user-credentials'],
    queryFn: () => orchestratorApi.getAllCredentials(),
    staleTime: 30_000, // 30s - shared across all inspector nodes
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // Per-integration (or per-endpoint when apiToolId is set) platform credential
  // availability. Cached for 5 min since admin publications rarely happen
  // mid-edit. Keyed on apiToolId so two nodes of the same API but different
  // endpoints can receive different answers (one priced, the other not).
  const normalizedIntegration = integration?.toLowerCase() || '';
  const normalizedApiToolId = apiToolId ?? null;
  const { data: platformInfo } = useQuery({
    queryKey: ['platform-credential-public-info', normalizedIntegration, normalizedApiToolId],
    queryFn: () => orchestratorApi.getPlatformCredentialPublicInfo(normalizedIntegration, normalizedApiToolId),
    staleTime: 5 * 60_000,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    enabled: !!normalizedIntegration && !!onCredentialSourceChange,
  });
  // Toggle visibility requires both (a) a platform credential exists and is
  // enabled AND (b) pricing is published for this endpoint. Without (b) the
  // platform option would let users switch to a rate-free "free ride", which
  // we never want - admins opt endpoints into platform-sourcing explicitly
  // by publishing a per-tool or API-wide rate.
  const platformAvailable =
    !!platformInfo?.available
    && platformInfo.platformCredentialId != null
    && !!platformInfo?.hasPricing;

  // Build search terms from tool credentials (stable with useMemo)
  const searchTerms = React.useMemo(() => {
    const terms = new Set<string>();
    if (integration) terms.add(integration.toLowerCase());
    toolCredentials.forEach((tc) => {
      // Only use displayName - credentialName is a technical requirement name
      // (e.g. "get_message") that never matches catalog credential templates
      if (tc.displayName) terms.add(tc.displayName.toLowerCase());
    });
    return Array.from(terms).sort().join(',');
  }, [integration, toolCredentials]);

  // Fetch only matching templates (not all 500) - cached per search terms
  const {
    data: credentialTemplates = [],
    isLoading: isLoadingTemplates,
  } = useQuery({
    queryKey: ['credential-templates', searchTerms],
    queryFn: async () => {
      const terms = searchTerms.split(',').filter(Boolean);
      if (terms.length === 0) return [];

      const results = await Promise.all(
        terms.map((term) =>
          orchestratorApi.getCredentialTemplates({ search: term, pageSize: 10, includeInactive: true })
            .then((res) => res.credentials || [])
            .catch(() => [] as CredentialTemplate[])
        )
      );

      // Deduplicate templates by credential_name
      const seen = new Set<string>();
      const unique: CredentialTemplate[] = [];
      for (const templates of results) {
        for (const tmpl of templates) {
          const key = tmpl.credential_name || tmpl.display_name || '';
          if (!seen.has(key)) {
            seen.add(key);
            unique.push(tmpl);
          }
        }
      }
      return unique;
    },
    staleTime: 5 * 60_000, // 5 min - templates rarely change
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    enabled: searchTerms.length > 0,
  });

  const isLoading = isLoadingCredentials || isLoadingTemplates;
  const error = credentialsError instanceof Error ? credentialsError.message : null;

  // Refetch credentials after wizard completes - invalidate + refetch and return fresh data
  const refetchCredentials = React.useCallback(async () => {
    const result = await queryClient.fetchQuery({
      queryKey: ['user-credentials'],
      queryFn: () => orchestratorApi.getAllCredentials(),
      staleTime: 0, // Force fresh fetch
    });
    return result || [];
  }, [queryClient]);

  // OAuth2 callback (success/error params + toast + URL cleanup) is handled
  // at the WorkflowBuilder level so the toast fires immediately on return,
  // even when no node is selected. Don't duplicate it here.

  // Find matching template for a tool credential
  const findMatchingTemplate = React.useCallback((toolCred: ToolCredential): CredentialTemplate | null => {
    const credName = toolCred.credentialName?.toLowerCase() || '';
    const displayName = toolCred.displayName?.toLowerCase() || '';
    const integrationLower = integration?.toLowerCase() || '';

    // Priority 1: exact match on credential_name (most reliable)
    const exactMatch = credentialTemplates.find((tmpl) => {
      const tmplName = tmpl.credential_name?.toLowerCase() || '';
      return tmplName === credName;
    });
    if (exactMatch) return exactMatch;

    // Priority 2: exact match on display_name, icon_slug, or integration
    const exactDisplayMatch = credentialTemplates.find((tmpl) => {
      const tmplName = tmpl.credential_name?.toLowerCase() || '';
      const tmplDisplayName = tmpl.display_name?.toLowerCase() || '';
      const tmplIconSlug = tmpl.icon_slug?.toLowerCase() || '';
      return (
        tmplDisplayName === credName ||
        tmplIconSlug === credName ||
        tmplName === integrationLower ||
        tmplDisplayName === integrationLower ||
        tmplIconSlug === integrationLower
      );
    });
    if (exactDisplayMatch) return exactDisplayMatch;

    // Priority 3: fuzzy match - but only if credName is the FULL prefix/suffix,
    // not a substring of a longer name (avoids "openai" matching "azureopenai")
    return credentialTemplates.find((tmpl) => {
      const tmplName = tmpl.credential_name?.toLowerCase() || '';
      const tmplDisplayName = tmpl.display_name?.toLowerCase() || '';
      return (
        credName.includes(tmplName) ||
        (displayName && tmplDisplayName.includes(displayName)) ||
        (displayName && displayName.includes(tmplDisplayName))
      );
    }) || null;
  }, [credentialTemplates, integration]);

  // Calculate credential statuses
  const credentialStatuses: CredentialStatus[] = React.useMemo(() => {
    if (!toolCredentials || toolCredentials.length === 0) {
      return [];
    }

    return toolCredentials.map((toolCred) => {
      const matchingCredentials = matchUserCredentialsForTool(
        userCredentials,
        toolCred,
        integration
      );

      // Prefer the user's default credential, fall back to the first match.
      // (Cheaper than a second pass through `findBestUserCredential` since we
      // already have the filtered list.)
      const autoPick = matchingCredentials.find((c) => c.is_default) ?? matchingCredentials[0] ?? null;

      // If the parent-saved id is still present in the match list, honor it -
      // otherwise it's stale (credential deleted, or points to a different
      // integration entirely) and must be replaced by the auto-pick.
      const savedIdIsValid =
        selectedCredentialId != null &&
        matchingCredentials.some((c) => c.id === selectedCredentialId);

      return {
        credential: toolCred,
        userCredentials: matchingCredentials,
        isConfigured: matchingCredentials.length > 0,
        selectedId: savedIdIsValid ? selectedCredentialId! : (autoPick?.id ?? null),
      };
    });
  }, [toolCredentials, userCredentials, integration, selectedCredentialId]);

  // Auto-persist the auto-picked credential onto the node so downstream
  // consumers (validator, execution engine, agent prompts) see the same
  // "configured" state as this dropdown. Without this, the UI showed
  // "Configured" while CredentialValidationRule still warned
  // "requires service credential (not connected)" because toolData.selectedCredentialId
  // remained null until the user manually clicked the dropdown.
  //
  // Also recovers from a stale selectedCredentialId: if the saved id no longer
  // exists in the user's credential list (deleted), re-pick the best match
  // instead of keeping the dropdown stuck on an orphan id.
  const firstRequiredStatus = React.useMemo(
    () => credentialStatuses.find((s) => s.credential.isRequired && s.isConfigured),
    [credentialStatuses]
  );
  React.useEffect(() => {
    if (isLoading || isReadOnly) return;
    if (!firstRequiredStatus) return;

    const targetId = firstRequiredStatus.selectedId;
    if (targetId == null) return;
    if (targetId === selectedCredentialId) return; // already in sync

    // firstRequiredStatus.selectedId was computed above with stale-id rejection,
    // so this always sends a credential that actually exists in the user's list.
    onCredentialSelect(targetId, firstRequiredStatus.credential.credentialName);
    // onCredentialSelect is supplied by parents as an inline arrow fn and is
    // intentionally excluded from deps to avoid a re-sync loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isLoading, isReadOnly, selectedCredentialId, firstRequiredStatus]);

  // Calculate if all required credentials are configured
  const allRequiredConfigured = React.useMemo(() => {
    if (!toolCredentials || toolCredentials.length === 0) {
      return true; // No credentials needed
    }

    // Platform source short-circuits per-credential configuration - the platform
    // credential itself is already provisioned by an admin. We only need to be
    // sure the node actually carries a platformCredentialId (enforced downstream).
    if (credentialSource === 'platform' && platformCredentialId != null) {
      return true;
    }

    const hasRequiredCredentials = credentialStatuses.some((s) => s.credential.isRequired);
    if (!hasRequiredCredentials) {
      return true; // No required credentials
    }

    return credentialStatuses
      .filter((s) => s.credential.isRequired)
      .every((s) => s.isConfigured);
  }, [credentialStatuses, toolCredentials, credentialSource, platformCredentialId]);

  // Notify parent component about credential status
  // This effect should run when credentials are loaded or when tool credentials/integration change
  React.useEffect(() => {
    if (onCredentialStatusChange && !isLoading) {
      onCredentialStatusChange(allRequiredConfigured);
    }
  }, [allRequiredConfigured, onCredentialStatusChange, isLoading]);

  // Stable fallback requirements for wizard when template isn't cached yet.
  // Hoisted ABOVE the early return below: this component can mount with an empty
  // toolCredentials list (e.g. browser_agent before a model is picked), so every
  // hook must run unconditionally - otherwise the hook count changes between
  // renders (React "Rendered more hooks than during the previous render").
  const wizardRequirements = React.useMemo(() => {
    if (selectedTemplate || !configuringCredential) return [];
    return [{
      iconSlug: configuringCredential.credentialName || integration || '',
      serviceName: configuringCredential.displayName || configuringCredential.credentialName || '',
    }];
  }, [selectedTemplate, configuringCredential, integration]);

  // No credentials required for this tool
  if (!toolCredentials || toolCredentials.length === 0) {
    return null;
  }

  // Open credential wizard for a specific credential

  const handleConfigureClick = async (toolCred: ToolCredential, mode?: CredentialWizardMode) => {
    setConfiguringCredential(toolCred);

    // Try exact fetch by credentialType first (reliable for workflow-native nodes: smtp, ssh, sftp, database)
    // Falls back to the cached ILIKE-based list for catalog API credentials
    let template: CredentialTemplate | null = null;
    const credType = toolCred.credentialType;
    if (credType) {
      const exactTemplate = await orchestratorApi.getCredentialTemplateByName(credType);
      if (exactTemplate) {
        template = exactTemplate;
      }
    }

    if (!template) {
      template = findMatchingTemplate(toolCred);
    }

    setSelectedTemplate(template);
    setWizardInitialMode(mode ?? resolveConfigureModeForRequiredScopes(requiredScopes, template));
    setIsWizardOpen(true);
  };

  // Handle credential added from wizard
  const handleCredentialAdded = async (iconSlug: string) => {
    const credentials = await refetchCredentials();

    // Auto-select the newly created credential using the shared matcher so this
    // stays consistent with the inspector dropdown / validator.
    if (configuringCredential) {
      const matchingCred = findBestUserCredential(
        credentials,
        iconSlug,
        configuringCredential
      );
      if (matchingCred) {
        onCredentialSelect(matchingCred.id, configuringCredential.credentialName);
      }
    }
  };

  // Handle wizard complete - show success toast like /settings/credentials
  const handleWizardComplete = () => {
    addToast({
      type: 'success',
      title: t('toasts.credentialCreated'),
      message: t('toasts.credentialConfigured'),
    });
    setIsWizardOpen(false);
    setSelectedTemplate(null);
    setConfiguringCredential(null);
  };

  const showPlatformToggle = !!onCredentialSourceChange && (platformAvailable || credentialSource === 'platform');
  const usingPlatform = credentialSource === 'platform';

  const handleSwitchToUser = () => {
    if (!onCredentialSourceChange || isDisabled) return;
    onCredentialSourceChange('user', null);
  };
  const handleSwitchToPlatform = () => {
    if (!onCredentialSourceChange || isDisabled) return;
    if (!platformAvailable || !platformInfo?.platformCredentialId) return;
    onCredentialSourceChange('platform', platformInfo.platformCredentialId);
  };

  return (
    <div className="space-y-3">
      {/* Source toggle - only shown when a platform credential exists for this
          integration (or the node is already on platform source). */}
      {showPlatformToggle && (
        <div className="space-y-1.5">
          <span className="text-xs font-semibold text-[var(--text-secondary)]">
            {t('source.label')}
          </span>
          <ToggleGroup
            variant="pill"
            value={usingPlatform ? 'platform' : 'user'}
            onValueChange={(v) => {
              if (v === 'platform') handleSwitchToPlatform();
              else handleSwitchToUser();
            }}
            disabled={isDisabled}
            options={[
              {
                value: 'user',
                label: t('source.user'),
                icon: <User className="w-3.5 h-3.5" />,
              },
              {
                value: 'platform',
                label: t('source.platform'),
                icon: <DollarSign className="w-3.5 h-3.5" />,
              },
            ]}
          />
          {usingPlatform && (
            <div className="text-xs text-theme-secondary bg-theme-tertiary/40 border border-theme rounded-md px-2 py-1.5">
              {/* Prefer the per-endpoint rate when the inspector resolved one,
                  fall back to the version-wide default for the "no apiToolId"
                  case. When both are absent, show the generic note. */}
              {(() => {
                const rate = platformInfo?.markupCredits ?? platformInfo?.defaultMarkupCredits;
                return rate
                  ? t('source.markupNoteWithRate', { rate })
                  : t('source.markupNote');
              })()}
            </div>
          )}
        </div>
      )}

      {/* Content */}
      <div className="space-y-3">
        {usingPlatform ? (
          <div className="text-xs text-theme-secondary">
            {t('source.platformExplanation')}
          </div>
        ) : isLoading ? (
          <div className="flex items-center justify-center py-8">
            <LoadingSpinner size="sm" />
          </div>
        ) : error ? (
          <div className="text-sm text-red-500">{error}</div>
        ) : (
          credentialStatuses.map((status, index) => (
            <div key={`${status.credential.credentialName}-${index}`} className="space-y-1.5">
              {/* Header - same style as parameters */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-semibold text-[var(--text-secondary)]">
                    {status.credential.displayName || status.credential.credentialName}
                  </span>
                  {status.credential.isRequired && (
                    <span className="text-xs text-red-500">*</span>
                  )}
                  {status.credential.authType && (
                    <span className="text-[10px] text-[var(--text-tertiary)] font-mono">
                      ({status.credential.authType})
                    </span>
                  )}
                </div>
                {status.isConfigured && (
                  <span className="text-[10px] text-emerald-600 dark:text-emerald-400 font-medium">
                    {t('configured')}
                  </span>
                )}
              </div>

              {/* Not configured: show configure button */}
              {!status.isConfigured && (
                <Button
                  type="button"
                  variant="default"
                  size="sm"
                  className="w-full mt-1"
                  onClick={() => handleConfigureClick(status.credential)}
                  disabled={isDisabled}
                >
                  <Plus className="w-4 h-4" />
                  {t('configure')}
                </Button>
              )}

              {/* Configured: select dropdown to choose or add new */}
              {status.isConfigured && (
                <Select
                  value={status.selectedId ? String(status.selectedId) : undefined}
                  onValueChange={(value) => {
                    if (value === '__new__') {
                      handleConfigureClick(status.credential);
                    } else {
                      onCredentialSelect(Number(value), status.credential.credentialName);
                    }
                  }}
                  disabled={isDisabled}
                >
                  <SelectTrigger className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5">
                    <SelectValue placeholder={t('selectCredential')} />
                  </SelectTrigger>
                  <SelectContent>
                    {status.userCredentials.map((cred) => (
                      <SelectItem key={cred.id} value={String(cred.id)} className="text-xs">
                        {cred.name}
                      </SelectItem>
                    ))}
                    {status.userCredentials.length > 0 && <SelectSeparator />}
                    <SelectItem value="__new__" className="text-xs">
                      <span className="flex items-center gap-1.5">
                        <Plus className="w-3 h-3" />
                        {t('addNewCredential')}
                      </span>
                    </SelectItem>
                  </SelectContent>
                </Select>
              )}

              {/* V166: missing-scopes warning for OAuth2 credentials. Renders only
                  when requiredScopes is set on the parent (MCP node only) AND the
                  bound credential is OAuth2 AND at least one scope is missing. */}
              {status.isConfigured && requiredScopes && requiredScopes.length > 0 && (() => {
                const selected = status.userCredentials.find((c) => c.id === status.selectedId);
                if (!selected) return null;
                // Drop the hardcoded GOOGLE_ICON_SLUGS allowlist - gating now reads
                // the byok.surface flag from the catalog template's metadata, the
                // single source of truth shared with the wizard. Templates declaring
                // surface='hidden' (or no byok block, the default for ~99% of OAuth2
                // APIs) suppress the "Switch to Advanced" CTA. inline + disclosure
                // both expose it; the wizard chooses how to render the toggle once
                // the user lands on the BYOK form.
                const matchedTemplate = findMatchingTemplate(status.credential);
                const byokOnlyScopes = resolveByokOnlyScopeList(matchedTemplate);
                const platformScopes = resolvePlatformScopeList(matchedTemplate);
                // Offer BYOK when the catalog exposes it (surface ≠ hidden) OR the
                // template declares byokOnlyScopes - the latter means some required
                // scope can only be granted via the user's own OAuth app, so BYOK
                // must be reachable regardless of the surface flag.
                const byokOffered =
                  resolveByokConfig(matchedTemplate).surface !== 'hidden' || byokOnlyScopes.length > 0;
                return (
                  <MissingScopesBanner
                    requiredScopes={requiredScopes}
                    grantedScopes={selected.scopes}
                    credentialType={selected.type}
                    platformScopes={platformScopes}
                    byokOnlyScopes={byokOnlyScopes}
                    integrationDisplayName={status.credential.displayName || status.credential.credentialName}
                    onReconnect={() => handleConfigureClick(status.credential, 'standard')}
                    onSwitchToAdvanced={byokOffered ? () => handleConfigureClick(status.credential, 'advanced') : undefined}
                  />
                );
              })()}
            </div>
          ))
        )}

        {/* Link to manage all credentials - only show when credentials are not configured */}
        {!usingPlatform && !allRequiredConfigured && (
          <div className="pt-2">
            <button
              type="button"
              onClick={() => window.open('/app/settings/credentials', '_blank')}
              className="text-xs text-blue-600 hover:text-blue-700 dark:text-blue-400 dark:hover:text-blue-300 flex items-center gap-1"
            >
              <ExternalLink className="w-3 h-3" />
              {t('manageAll')}
            </button>
          </div>
        )}
      </div>

      {/* Credential Wizard Modal - same as /settings/credentials */}
      <CredentialWizard
        template={selectedTemplate}
        requirements={wizardRequirements}
        open={isWizardOpen}
        onOpenChange={(open) => {
          setIsWizardOpen(open);
          if (!open) {
            setSelectedTemplate(null);
            setConfiguringCredential(null);
            setWizardInitialMode('standard');
          }
        }}
        onCredentialAdded={handleCredentialAdded}
        onComplete={handleWizardComplete}
        initialMode={wizardInitialMode}
      />

      {/* Toast notifications - same as /settings/credentials */}
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-[9999] flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast
              key={toast.id}
              id={toast.id}
              type={toast.type}
              title={toast.title}
              message={toast.message}
              onClose={removeToast}
            />
          ))}
        </div>
      )}
    </div>
  );
}
