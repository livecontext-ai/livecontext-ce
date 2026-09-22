'use client';

import * as React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink, Plus, DollarSign, User } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import {
  orchestratorApi,
  type Credential,
  type CredentialTemplate,
  type PlatformCredentialPublicInfo,
} from '@/lib/api/orchestrator';
import { CredentialWizard, resolveByokConfig, resolveByokOnlyScopeList, resolvePlatformScopeList } from '@/components/credentials/CredentialWizard';
import { Select, SelectItem, SelectSeparator, SelectTrigger, SelectValue } from '@/components/ui/select';
// The list is PORTALLED, so on a studio surface it lands outside the element carrying the
// studio's colour tokens and comes back in the application's theme. Inert everywhere else:
// off a studio surface this renders exactly what SelectContent renders.
import { StudioSelectContent } from '@/components/studio/StudioSelectContent';
import { ToggleGroup } from '@/components/ui/toggle-group';
import Toast, { useToast } from '@/components/Toast';
import { useTranslations } from 'next-intl';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import {
  matchUserCredentialsForTool,
  findBestUserCredential,
} from '@/lib/credentials/credentialMatching';
import { normalizeScopes } from '@/lib/credentials/normalizeScopes';
import { MissingScopesBanner } from '@/components/credentials/MissingScopesBanner';

import { platformSellsThis } from '@/lib/generation/platformSells';
import { BASE_RATE } from '@/lib/generation/priceModifiers';
import { formatCredits } from '@/lib/generation/price';
import { generationQuoteKey } from '@/lib/generation/quoteKey';

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
  /**
   * Generation model id the node is bound to (`agent:generate` only). One
   * endpoint can back several models at different prices, so with it the rate
   * shown is that MODEL's, not the endpoint's.
   */
  modelId?: string | null;
  /**
   * Size of the run the price is quoted for, in PLATFORM units (10 for a 10
   * second video, the prompt's length for a voice model), the same measurement
   * the billing path sends. A generation priced per unit costs more for a
   * bigger request, so without this the note can only state a rate; with it, it
   * states what THIS node will actually cost.
   *
   * <p>It is NOT converted here into the unit the price is charged per. The
   * quote answers with the unit it priced in and the quantity it charged for
   * (`priceUnit` + `quantity` on the response), and the note prints THOSE, so
   * an estimate can never quote a rate per minute beside a count of seconds.
   */
  quantity?: number | null;
  /**
   * What `quantity` is COUNTED in (second, image, character, call), from the
   * model's own `measuredUnit`.
   *
   * <p>Sent so the quote can refuse a published rate that cannot price this
   * call at all. Omitted means "this surface cannot say", which leaves the
   * question unasked and the answer exactly as it was before.
   */
  quantityUnit?: string | null;
  /**
   * What the call's own CHOICES do to the published rate: 2 for a render sold
   * at twice it, 1.2 for one carrying two files priced at a tenth each.
   *
   * <p>Computed by the surface that holds the parameters (this section sees
   * only the model and the size), from the model's declared `price.modifiers`.
   * It travels because it changes the AMOUNT, and because a caller that left it
   * out of its cache key would stop sharing the quote with the callers that
   * carry it: two requests for one generation, and two amounts that can
   * disagree on the same screen.
   *
   * <p>Omitted means "at the published rate", which is every ordinary endpoint
   * and every model that declares no modifiers.
   */
  priceMultiplier?: number | null;
  /**
   * The factor in WORDS, when the caller can say which choices produced it.
   *
   * <p>This section is handed a number; only the surface holding the parameters knows that the
   * number is "Resolution x2". Optional because not every caller has the model's modifier table
   * (the wizard quotes a whole integration), and the note degrades to the bare factor rather than
   * disappearing: an unexplained multiplier still beats an unexplained total.
   */
  priceFactorReason?: string;
  /**
   * True when the factor CANNOT be known from what is on screen.
   *
   * <p>The workflow inspector's fields accept expressions, and a priced parameter bound to one has
   * no value until the run reaches that step. The local calculation then falls to the reference
   * tier, the quote is asked without a factor, and the server answers the published rate - so this
   * pane printed "60 credits per second, 10 seconds = 600 credits" as a plain fact for a step the
   * server bills 2400.
   *
   * <p>It is a separate flag from `priceFactorReason` because it has to survive the factor gate:
   * that note renders only when the server ECHOES a factor, which in this exact case it never
   * does. The honest statement is about the total, not about a surcharge.
   */
  priceFactorIsUncertain?: boolean;
  /**
   * True when the bound endpoint resells a generated asset (it carries a
   * generation descriptor in the catalog).
   *
   * <p>It travels to the quote because a generation is never sold on the
   * credential-wide default: execution refuses that exact call. A `agent:generate`
   * step says so implicitly by naming a model, but an `mcp:` step bound straight
   * to a generation endpoint names none, so without this the catch-all default
   * came back as a price and the platform toggle offered a step the server
   * refuses to run.
   */
  isGeneration?: boolean;
  isRunMode?: boolean;
  /**
   * Whether this section also EXPLAINS the platform rate, or only offers the
   * choice.
   *
   * <p>Default true, which is the inspector: there, this is the only place a
   * price is stated, so the rate note and the "no personal setup" explanation
   * are what make the platform option comprehensible.
   *
   * <p>A surface that already states the price of the thing being bought
   * passes false. The generation dialog does: it prints each model's quoted
   * amount ON the model options, so that a reader can compare before choosing,
   * and repeating it here would put the same amount on screen twice, which
   * reads as two prices rather than one. The wording is also written for a
   * workflow ("this step", "the base node cost") and says the wrong thing to
   * someone pressing a button in the app.
   */
  showPlatformPricingNotes?: boolean;
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
  /**
   * An overlay of this section opened or closed: the credential wizard, or the
   * dropdown listing the reader's own keys.
   *
   * <p><b>Why a caller ever needs to know.</b> Both render in a portal on the
   * document, OUTSIDE whatever contains this section. A container that closes
   * when the reader interacts somewhere else - a popover, which is how the
   * studio offers this - therefore sees the first click inside the wizard as a
   * click outside ITSELF, closes, unmounts this section, and takes the wizard
   * down with it. The reader presses "add a key", a form appears, and the first
   * field they touch makes it vanish. The dropdown does the same through focus.
   *
   * <p>So the container is TOLD, and can hold itself open for as long as one is
   * up. It is told FALSE when this section unmounts, whatever state the overlay
   * was in: a container still holding itself open for an overlay that no longer
   * exists can never be closed again, which looks like a frozen app and is a
   * worse outcome than the bug this prevents.
   *
   * <p>The inspector, which lives in a panel that closes on nothing, passes
   * nothing and behaves exactly as before.
   */
  onWizardOpenChange?: (open: boolean) => void;
}

interface CredentialStatus {
  credential: ToolCredential;
  userCredentials: Credential[];
  isConfigured: boolean;
  selectedId: number | null;
}

type CredentialWizardMode = 'standard' | 'advanced';

// The unit vocabulary and its label helper moved to lib/credentials/priceUnits
// once a second surface needed them: importing them FROM here dragged this
// whole component, wizard included, into that surface's bundle. Re-exported so
// existing importers of this module keep working.
import { priceUnitLabel } from '@/lib/credentials/priceUnits';

/**
 * Which sentence explains the platform rate, and with which values.
 *
 * <p>Pulled out of the JSX so the choice is testable on its own: the whole point
 * of the generation work is that a user sees what a run will cost BEFORE running
 * it, and "60 credits per second, 10 seconds = 600 credits" is a different
 * statement from "600 credits are billed on each call". Which one is truthful
 * depends on what the quote resolved, so getting the branch wrong quotes a price
 * the customer is not charged.
 */
export function describePlatformRate(
  info: PlatformCredentialPublicInfo | undefined,
): { key: string; values?: Record<string, string> } {
  // The amount only reached the credential-wide default, and a generation is
  // never sold on a catch-all: execution REFUSES this call. So there is no rate
  // to state, and stating the default would quote the price of an ordinary
  // lookup for a video. Say what the step will actually do instead. Checked
  // before the flat fallback below, which would otherwise reach for
  // `defaultMarkupCredits` - the very number that is not applicable here.
  if (info?.versionDefaultOnly) {
    return { key: 'source.markupNoteNotSold' };
  }
  const unitCredits = info?.unitCredits;
  const perUnit = unitCredits != null && Number(unitCredits) > 0;

  if (perUnit && info?.priceUnit) {
    const unit = info.priceUnit;
    // A quantity means the surface knows the size of THIS run, so the note can
    // state the total instead of only the rate.
    if (info.quantity != null && info.markupCredits != null) {
      return {
        key: 'source.markupNoteWithUnitRateAndTotal',
        values: {
          unitRate: String(unitCredits),
          unit,
          quantity: String(info.quantity),
          total: String(info.markupCredits),
        },
      };
    }
    return {
      key: 'source.markupNoteWithUnitRate',
      values: { unitRate: String(unitCredits), unit },
    };
  }

  const flatRate = info?.markupCredits ?? info?.defaultMarkupCredits;
  if (flatRate != null) {
    return { key: 'source.markupNoteWithRate', values: { rate: String(flatRate) } };
  }
  return { key: 'source.markupNote' };
}

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
  modelId,
  quantity,
  quantityUnit,
  priceMultiplier,
  priceFactorReason,
  priceFactorIsUncertain,
  isGeneration = false,
  isRunMode = false,
  showPlatformPricingNotes = true,
  onCredentialStatusChange,
  isReadOnly = false,
  credentialSource = 'user',
  platformCredentialId,
  onCredentialSourceChange,
  requiredScopes,
  onWizardOpenChange,
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

  /**
   * Whether ANY overlay of this section is open: the credential wizard, or the
   * "which of my keys" dropdown.
   *
   * <p>Both render in a portal on the document, which is the whole reason the
   * container has to be told. The dropdown is not a lesser case: it takes
   * FOCUS, which is one of the three ways a popover dismisses itself, so a
   * container that only heard about the wizard still lost the pane the moment
   * the reader opened the list of their own keys.
   */
  const [isKeyListOpen, setIsKeyListOpen] = React.useState(false);
  const anyOverlayOpen = isWizardOpen || isKeyListOpen;

  // Told once per change, from the state itself rather than from each of the
  // places that set it: a notification wired into the setters is one refactor
  // away from a container that stays open after the form has gone.
  const onWizardOpenChangeRef = React.useRef(onWizardOpenChange);
  // Kept current in an effect rather than during render: a render can be thrown away, and the
  // cleanup below reads this ref on the way out.
  React.useEffect(() => {
    onWizardOpenChangeRef.current = onWizardOpenChange;
  }, [onWizardOpenChange]);
  React.useEffect(() => {
    onWizardOpenChangeRef.current?.(anyOverlayOpen);
    // UNMOUNTING is a close, and the most important one. A container that holds
    // itself open while an overlay is up and is never told the overlay went
    // would refuse every dismissal for the rest of its life: Escape, the
    // trigger, a click anywhere. This section unmounts under an open overlay
    // whenever the provider it belongs to disappears (a catalogue refetch, a
    // model switch), and the result would look like the app had frozen - a
    // worse bug than the one the guard exists to fix.
    return () => onWizardOpenChangeRef.current?.(false);
  }, [anyOverlayOpen]);

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
  // A generation model is priced per model AND per request size, so both take
  // part in the cache key: changing the duration must re-quote, not reuse the
  // price of the previous one.
  const normalizedModelId = modelId ?? null;
  const normalizedQuantity = quantity ?? null;
  // What the call's own CHOICES do to the rate, from the surface that holds the
  // parameters. It is part of the key for the same reason the quantity is (it
  // changes the amount), and it has to be in the key of EVERY caller of this
  // endpoint or the ones that carry it stop sharing the cache with the ones
  // that do not - which is two requests for one generation, and two amounts
  // that can disagree on screen.
  //
  // `lib/generation/__tests__/quoteKeyCallSites.test.ts` pins the SHAPE of that question: it reads
  // the object literal at every call site and fails when one sends a different set of fields. It
  // cannot see a different VALUE in the same field, which is a second way to split the cache and
  // has happened once (the studio rounded a character count, the other three did not); that half
  // is guarded where the value is computed. This line used to cite `PriceQuoteKeyParityTest`,
  // which existed nowhere in the repo.
  const normalizedMultiplier = priceMultiplier ?? BASE_RATE;
  const { data: platformInfo } = useQuery({
    // Built by the shared helper, not written out here. Every element changes the ANSWER (a
    // generation does not inherit the credential-wide default; a unit can flip "priced" to "not
    // sold"), and this endpoint has four callers, two of them on screen at once: a key that
    // differs by one element is two requests for one generation and two amounts that can disagree.
    queryKey: generationQuoteKey({
      integrationName: normalizedIntegration,
      apiToolId: normalizedApiToolId,
      modelId: normalizedModelId,
      quantity: normalizedQuantity,
      generation: isGeneration,
      quantityUnit,
      priceMultiplier: normalizedMultiplier,
    }),
    queryFn: () => orchestratorApi.getPlatformCredentialPublicInfo(
      normalizedIntegration,
      normalizedApiToolId,
      {
        modelId: normalizedModelId,
        quantity: normalizedQuantity,
        generation: isGeneration,
        quantityUnit,
        priceMultiplier: normalizedMultiplier,
      },
    ),
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
  const platformAvailable = platformSellsThis(platformInfo);

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

  // Once offered, the choice STAYS offered for as long as this control is mounted.
  //
  // Without this it is a one-way door: a reader sitting on the platform source sees the toggle
  // (the clause below), picks their own key, and the toggle vanishes with no way back - the
  // platform option was only on screen because they were standing on it. Leaving is possible and
  // returning is not, which is the shape of a mistake a control should never make.
  //
  // Latched on the platform being REALLY available, never on merely standing on it. A caller can
  // hold `'platform'` as its initial state before anything has been asked - the studio does - and
  // treating that as evidence made the toggle offer a platform key that does not exist: on an
  // integration the platform does not sell at all, the reader was shown a payer choice between
  // their own key and nothing.
  const offeredPlatform = React.useRef(false);
  if (platformAvailable) offeredPlatform.current = true;
  const showPlatformToggle = !!onCredentialSourceChange
    && (platformAvailable || credentialSource === 'platform' || offeredPlatform.current);
  const usingPlatform = credentialSource === 'platform';

  const handleSwitchToUser = () => {
    if (!onCredentialSourceChange || isDisabled) return;
    onCredentialSourceChange('user', null);
  };
  const handleSwitchToPlatform = () => {
    if (!onCredentialSourceChange || isDisabled) return;
    // Gated on the CREDENTIAL existing, not on it being sellable. `platformAvailable` also requires
    // a published price, and requiring that here is what made the way back dead: a reader who left
    // the platform source could press Platform and have nothing happen, with no reason given. An
    // unpriced platform run is refused server-side with a message that says so, which is a better
    // answer than a button that ignores the press.
    if (!platformInfo?.platformCredentialId) return;
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
            // Hugs its two choices and sits in the middle. A block-level pill
            // stretched the full width of the panel, so the track ran on far
            // past the last option and read as an empty third slot.
            className="w-fit mx-auto"
            ariaLabel={t('source.label')}
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
          {usingPlatform && showPlatformPricingNotes && (
            <div className="text-xs text-theme-secondary bg-theme-tertiary/40 border border-theme rounded-md px-2 py-1.5">
              {/* Prefer the per-unit rate (a generation model priced per second /
                  character), then the per-endpoint or version-wide flat rate,
                  then the generic note. */}
              {(() => {
                const note = describePlatformRate(platformInfo);
                if (!note.values) return t(note.key);
                const values = note.values.unit
                  ? { ...note.values, unit: priceUnitLabel(note.values.unit, t) }
                  : note.values;
                return t(note.key, values);
              })()}
              {/* WHY the total is not the rate times the size.
                  This pane quotes an amount that already carries the factor and said nothing
                  about it: the sentence above reads "60 credits per second, 10 seconds = 1200
                  credits", which does not multiply out, and the reader's first assumption about
                  an unexplained total is that one of the two numbers beside it is wrong.

                  Drawn from the factor the SERVER echoed, never from the local calculation, for
                  the same reason the studio badge is: a server that did not apply it answers at
                  the published rate, and a sentence explaining a surcharge the amount does not
                  contain is the same lie in the other direction. */}
              {/* The total above cannot be right when a priced parameter is an expression, and no
                  factor will be echoed for it either - so this is stated on its own, before the
                  factor note, and independently of it. */}
              {priceFactorIsUncertain && (
                <div className="pt-1">{priceFactorReason}</div>
              )}
              {(() => {
                if (priceFactorIsUncertain) return null;
                const echoed = Number(platformInfo?.priceMultiplier);
                if (!Number.isFinite(echoed) || echoed <= 0 || echoed === 1) return null;
                const factor = formatCredits(echoed);
                return (
                  <div className="pt-1">
                    {priceFactorReason
                      ? t('source.markupNoteFactorWithReason', { factor, reason: priceFactorReason })
                      : t('source.markupNoteFactor', { factor })}
                  </div>
                );
              })()}
            </div>
          )}
        </div>
      )}

      {/* Content */}
      <div className="space-y-3">
        {usingPlatform ? (
          showPlatformPricingNotes ? (
            <div className="text-xs text-theme-secondary">
              {t('source.platformExplanation')}
            </div>
          ) : null
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
                  // Its list is portalled and takes focus, exactly like the wizard, so a container
                  // that closes on focus leaving it has to be told this is open too.
                  onOpenChange={setIsKeyListOpen}
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
                  <StudioSelectContent>
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
                  </StudioSelectContent>
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
        // The wizard offers the choice; this is where the answer is RECORDED.
        // Choosing the platform's key creates no credential, it changes which
        // pool this step runs on, and that lives on the node. Passed only when
        // the node can actually carry the answer, so the wizard shows the
        // action exactly where it does something.
        onUsePlatformCredential={
          onCredentialSourceChange && platformInfo?.platformCredentialId != null
            ? () => onCredentialSourceChange('platform', platformInfo.platformCredentialId ?? null)
            : undefined
        }
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
