"use client";

import React, { useState, useEffect, useRef, useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { getClientLocale } from "@/lib/utils/locale";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  ExternalLink,
  AlertCircle,
  CheckCircle,
  ChevronRight,
  Eye,
  EyeOff,
  Info,
} from "lucide-react";
import LoadingSpinner from "@/components/LoadingSpinner";
import Image from "next/image";
import {
  CredentialTemplate,
  CredentialProperty,
  OAuth2InitiateRequest,
  orchestratorApi,
} from '@/lib/api/orchestrator';
import { normalizeIconSlug } from "@/lib/credentials/iconSlug";
import { invalidateCredentialCaches } from "@/lib/credentials/invalidateCredentialCaches";
import { IS_CE } from "@/lib/edition";
import {
  extractIconSlugFromUrl,
  monoDarkInvertClass,
} from "@/lib/credentials/monoIconSlugs";
import { useTranslations } from "next-intl";

// ============================================
// Types
// ============================================

export interface CredentialRequirement {
  iconSlug: string;
  serviceName?: string;
  toolId?: string;
}

export interface CredentialWizardProps {
  /** Single template OR list of requirements for multi-mode */
  template?: CredentialTemplate | null;
  requirements?: CredentialRequirement[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called when wizard finishes - receives list of completed icon slugs */
  onComplete?: (completedIconSlugs: string[]) => void;
  /** Called each time a credential is added */
  onCredentialAdded?: (iconSlug: string) => void;
  /**
   * V166 BYOK: when 'advanced', the wizard opens directly on the BYOK form
   * (`oauth-config` step) instead of the platform-shared `configure` step.
   * Used by the MissingScopesBanner "Switch to Advanced" CTA - the user lands
   * on the BYOK form without having to manually toggle. Defaults to 'standard'.
   */
  initialMode?: 'standard' | 'advanced';
}

type CredentialStatus = "pending" | "completed" | "current";
type WizardStep = "configure" | "success" | "loading" | "error" | "oauth-config";
type AuthType = "oauth2" | "api_key" | "bearer" | "basic" | "none" | "custom" | string;

interface CredentialState {
  requirement: CredentialRequirement;
  status: CredentialStatus;
}

/**
 * Standard ↔ Advanced (BYOK) mode toggle rendered as a 2-segment pill.
 *
 * <p>Replaces the previous card-style "banner" with a structurally unambiguous
 * segmented control: the active segment is visually selected (filled
 * background), the inactive one is clickable and switches the wizard's step.
 * Diagnosis from 3 independent UX reviews: the previous title-noun-phrase
 * (e.g. "Standard connection") read as a feature description rather than a
 * "you are here" indicator, especially because the title competed for the
 * same noun-phrase slot as the alternative action's button label.
 *
 * <p>Renders identically in both the {@code configure} step (active=standard)
 * and the {@code oauth-config} step (active=advanced). Caller passes the
 * current mode and the click-to-switch handler.
 *
 * <p><b>ARIA pattern</b>: uses {@code role="radiogroup"} + two {@code role="radio"}
 * with {@code aria-checked}, per WAI-ARIA APG. This is a "pick one of N
 * mutually exclusive states" control with no associated tabpanel - the wizard
 * navigates between two distinct steps (different forms, different submit
 * buttons), not between content panels of the same view, so {@code tablist}
 * would be the wrong pattern. Both segments stay focusable to preserve the
 * "you are here" affordance for keyboard users; arrow keys move selection
 * (and fire onSwitch) between them.
 *
 * <p>Exported so it can be unit-tested directly (mounting the full wizard
 * pulls in heavy async deps that obscure the toggle's structural contract).
 */
export function ModeToggle({
  active,
  onSwitch,
  disabled,
  t,
}: {
  active: 'standard' | 'advanced';
  onSwitch: () => void;
  disabled: boolean;
  t: (key: string) => string;
}) {
  const segmentBase = "flex-1 px-3 py-2 text-sm font-medium rounded-lg transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-theme-primary";
  const activeClass = "bg-theme-primary text-on-primary cursor-default";
  const inactiveClass = "text-theme-secondary hover:bg-theme-tertiary cursor-pointer";

  // Arrow-key keyboard model per the radio-group pattern: ArrowLeft/Right (and
  // ArrowUp/Down for vertical-axis users) move between segments. Selection
  // follows focus - pressing the arrow IS the switch, no extra Enter needed.
  // This matches the platform-native behavior of segmented controls and avoids
  // the tablist-pattern trap where the active control gets removed from the
  // tab order via HTML disabled.
  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (disabled) return;
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight'
        || e.key === 'ArrowUp' || e.key === 'ArrowDown') {
      e.preventDefault();
      onSwitch();
    }
  };

  return (
    <div
      role="radiogroup"
      aria-label={t("modeToggle.label")}
      onKeyDown={handleKeyDown}
      className="flex items-center gap-1 p-1 rounded-xl border border-theme bg-[var(--bg-primary)]"
    >
      <button
        type="button"
        role="radio"
        aria-checked={active === 'standard'}
        aria-label={active === 'standard' ? undefined : t("modeToggle.ariaSwitchToStandard")}
        // tabIndex per the roving-tabindex pattern: only the checked radio is
        // in the document tab order; the unchecked one is reachable via arrow
        // keys. Both stay focusable programmatically (no HTML disabled), so the
        // "you are here" affordance survives Tab navigation for screen readers.
        tabIndex={active === 'standard' ? 0 : -1}
        disabled={disabled}
        onClick={active === 'standard' ? undefined : onSwitch}
        className={`${segmentBase} ${active === 'standard' ? activeClass : inactiveClass}`}
      >
        {t("modeToggle.standard")}
      </button>
      <button
        type="button"
        role="radio"
        aria-checked={active === 'advanced'}
        aria-label={active === 'advanced' ? undefined : t("modeToggle.ariaSwitchToAdvanced")}
        tabIndex={active === 'advanced' ? 0 : -1}
        disabled={disabled}
        onClick={active === 'advanced' ? undefined : onSwitch}
        className={`${segmentBase} ${active === 'advanced' ? activeClass : inactiveClass}`}
      >
        {t("modeToggle.advanced")}
      </button>
    </div>
  );
}

/**
 * Resolved OAuth2 provider defaults for a credential template - provider-canonical
 * URLs and scopes that BYOK users should never have to retype. {@code null} on a
 * field means the catalog template did not declare a default for it.
 */
export interface ResolvedOAuth2Defaults {
  authUrl: string | null;
  tokenUrl: string | null;
  /** Space-separated form (matches the wizard's input control). */
  scopes: string | null;
}

/**
 * Unwrap a value that may have been serialized through Spring's
 * {@code JdbcTemplate.queryForList} for a Postgres jsonb column. Such columns
 * arrive on the wire as the {@link org.postgresql.util.PGobject}-style envelope
 * {@code { type: "jsonb", value: "<json string>" }} - see
 * {@code CredentialTemplateController.unwrapVariants} (`backend/catalog-service`)
 * which only unwraps the {@code variants} column today; {@code metadata} and
 * {@code properties} reach the frontend still wrapped, so consumers do the
 * unwrap inline. The chain mirrors {@code getTemplateProperties} below.
 *
 * <p>Returns the raw value through one level of {@code .value} unwrap, plus a
 * one-shot {@code JSON.parse} when the inner value is a string. Returns the
 * input untouched when no unwrap applies.
 */
function unwrapJsonbEnvelope(raw: unknown): unknown {
  if (raw && typeof raw === 'object' && !Array.isArray(raw) && (raw as Record<string, unknown>).value != null) {
    raw = (raw as Record<string, unknown>).value;
  }
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw);
    } catch {
      return raw;
    }
  }
  return raw;
}

/**
 * Pull provider-canonical OAuth2 defaults out of a credential template.
 *
 * <p>Source priority:
 * <ol>
 *   <li>{@code template.metadata.oauth2Config.{authorizationUrl, tokenUrl, scopes}} -
 *     the canonical, importer-populated location consumed by the auth-service
 *     {@code OAuth2Engine} for actual OAuth flows. Catalog APIs declare it in
 *     their {@code scripts/api-migrations/*.json} files.</li>
 *   <li>{@code template.properties[].default} for property names
 *     {@code authUrl} / {@code accessTokenUrl|tokenUrl} / {@code scope|scopes} -
 *     legacy fallback retained for templates whose importer pass added the
 *     hidden-credential fields but no metadata block.</li>
 * </ol>
 *
 * <p>Both branches first unwrap the Spring jsonb envelope (see
 * {@link unwrapJsonbEnvelope}) - the catalog wire path leaves
 * {@code template.metadata} and {@code template.properties} as
 * {@code { type: "jsonb", value: "<json>" }} for every catalog-imported row.
 *
 * <p>The resolver is decoupled from {@code hasPlatformCredentials}: the URLs are
 * provider-fixed (Gmail = Google's endpoints regardless of who owns the OAuth
 * client), so they should always pre-fill when the catalog has them - even when
 * LiveContext has no global platform OAuth client for the integration and the
 * user is registering a tenant-owned BYOK from scratch.
 */
export function resolveOAuth2Defaults(template: CredentialTemplate | null | undefined): ResolvedOAuth2Defaults {
  if (!template) {
    return { authUrl: null, tokenUrl: null, scopes: null };
  }

  // Source 1: metadata.oauth2Config (canonical). Unwrap the jsonb envelope first -
  // the catalog wire path leaves metadata as { type: "jsonb", value: "<json>" }.
  const metadata = unwrapJsonbEnvelope(template.metadata);
  if (metadata && typeof metadata === 'object' && !Array.isArray(metadata)) {
    const cfg = (metadata as Record<string, unknown>).oauth2Config;
    if (cfg && typeof cfg === 'object' && !Array.isArray(cfg)) {
      const c = cfg as Record<string, unknown>;
      const authUrl = typeof c.authorizationUrl === 'string' ? c.authorizationUrl : null;
      const tokenUrl = typeof c.tokenUrl === 'string' ? c.tokenUrl : null;
      let scopes: string | null = null;
      if (Array.isArray(c.scopes)) {
        // metadata.oauth2Config.scopes is a JSON string[] - render to the
        // space-separated form the wizard input expects.
        scopes = c.scopes.filter((s): s is string => typeof s === 'string').join(' ') || null;
      } else if (typeof c.scopes === 'string' && c.scopes.length > 0) {
        scopes = c.scopes;
      }
      // If at least the URLs came through metadata, return that - even if
      // scopes is missing (some providers leave scope selection to runtime).
      if (authUrl || tokenUrl || scopes) {
        return { authUrl, tokenUrl, scopes };
      }
    }
  }

  // Source 2: legacy properties[].default fallback. Same envelope unwrap.
  const propsRaw = unwrapJsonbEnvelope(template.properties);
  const propsArray: Array<{ name?: string; default?: string }> = Array.isArray(propsRaw)
    ? (propsRaw as Array<{ name?: string; default?: string }>)
    : [];
  const findDefault = (name: string): string => {
    const p = propsArray.find((x) => x?.name === name);
    return (p?.default ?? '').toString();
  };
  const authUrl = findDefault('authUrl') || null;
  const tokenUrl = findDefault('accessTokenUrl') || findDefault('tokenUrl') || null;
  const scopes = findDefault('scope') || findDefault('scopes') || null;
  return { authUrl, tokenUrl, scopes };
}

/**
 * Returns the byokOnlyScopes declared on a template, space-joined.
 * Used by the BYOK form to pre-fill the scope input for integrations whose
 * restricted scopes never appear on LiveContext's verified consent screen
 * (e.g. Classroom, Gmail restricted). Empty string when none declared.
 */
function resolveByokOnlyScopes(template: CredentialTemplate | null | undefined): string {
  if (!template) return '';
  const metadata = unwrapJsonbEnvelope(template.metadata);
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return '';
  const cfg = (metadata as Record<string, unknown>).oauth2Config;
  if (!cfg || typeof cfg !== 'object' || Array.isArray(cfg)) return '';
  const c = cfg as Record<string, unknown>;
  if (!Array.isArray(c.byokOnlyScopes)) return '';
  return c.byokOnlyScopes.filter((s): s is string => typeof s === 'string').join(' ');
}

/**
 * The byokOnlyScopes declared on a template, as a list. These are the scopes the
 * platform-shared OAuth client deliberately does NOT request (e.g. Google
 * "restricted" scopes like gmail.readonly that need CASA verification) - they are
 * obtainable only via BYOK. Consumers (e.g. MissingScopesBanner) use this to know
 * a missing scope can never be granted by a Standard reconnect, so the node should
 * route straight to BYOK. Empty when none declared.
 */
export function resolveByokOnlyScopeList(template: CredentialTemplate | null | undefined): string[] {
  const joined = resolveByokOnlyScopes(template);
  return joined ? joined.split(/\s+/).filter(Boolean) : [];
}

/**
 * The platform scopes a template's shared OAuth client actually requests
 * (catalog `oauth2Config.scopes`). A Standard reconnect can only grant scopes in
 * this set - so any required scope NOT in it (whether or not it's declared in
 * byokOnlyScopes) is unmanaged by the platform and needs BYOK. Returns [] when
 * the template declares no oauth2Config.scopes (fully-BYOK), in which case every
 * scope is unmanaged. This is the canonical signal the MissingScopesBanner uses
 * to decide whether Standard is futile. Mirrors {@link resolveByokOnlyScopeList}.
 *
 * <p>CE: there is no platform-shared OAuth app - the install-wide credential the
 * operator saved in Settings is the user's own OAuth client, and the backend
 * ({@code OAuth2Service.initiate}) always puts the FULL catalog scope set
 * (scopes ∪ byokOnlyScopes) on the authorize URL. A Standard (re)connect can
 * therefore grant byok-only scopes too, so they are included here - keeping the
 * MissingScopesBanner and configure-mode routing from steering CE users into a
 * pointless "bring your own OAuth client" detour.
 */
export function resolvePlatformScopeList(template: CredentialTemplate | null | undefined): string[] {
  if (!template) return [];
  const metadata = unwrapJsonbEnvelope(template.metadata);
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return [];
  const cfg = (metadata as Record<string, unknown>).oauth2Config;
  if (!cfg || typeof cfg !== 'object' || Array.isArray(cfg)) return [];
  const arr = (cfg as Record<string, unknown>).scopes;
  const platformScopes = Array.isArray(arr)
    ? arr.filter((s): s is string => typeof s === 'string')
    : [];
  if (!IS_CE) return platformScopes;
  return [...new Set([...platformScopes, ...resolveByokOnlyScopeList(template)])];
}

/**
 * True when a template's platform-shared OAuth client requests ZERO scopes
 * but the API still declares byokOnlyScopes. In this state the Standard
 * "Connect" mode is meaningless (the OAuth flow would request an empty scope
 * list), so the wizard auto-routes the user to the BYOK form and hides the
 * mode toggle. Concrete case: Google Classroom - moved entirely to BYOK to
 * keep the verified consent screen narrow.
 *
 * <p>CE: always false. The "platform-shared client can't request these scopes"
 * constraint doesn't exist in CE - the install-wide credential IS the user's own
 * OAuth client and the backend requests the full catalog scope set (including
 * byokOnlyScopes) on every Standard connect, so Standard mode is never futile.
 */
export function isFullyByokTemplate(template: CredentialTemplate | null | undefined): boolean {
  if (IS_CE) return false;
  if (!template) return false;
  const metadata = unwrapJsonbEnvelope(template.metadata);
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return false;
  const cfg = (metadata as Record<string, unknown>).oauth2Config;
  if (!cfg || typeof cfg !== 'object' || Array.isArray(cfg)) return false;
  const c = cfg as Record<string, unknown>;
  const platformScopes = Array.isArray(c.scopes) ? c.scopes.length : 0;
  const byokOnly = Array.isArray(c.byokOnlyScopes) ? c.byokOnlyScopes.length : 0;
  return platformScopes === 0 && byokOnly > 0;
}

/**
 * Resolved BYOK ("Bring Your Own OAuth") configuration for a template.
 *
 * <p>{@code surface} drives whether and how the credentials wizard exposes
 * the Standard / Custom OAuth toggle. Absence of the metadata block ≡
 * {@code "hidden"} - the safe default that suppresses both the inline pill
 * toggle and the {@code MissingScopesBanner} "Switch to Advanced" CTA.
 *
 * <p>The remaining fields ({@code consoleUrl}, {@code redirectUriHint},
 * {@code scopeNotes}, {@code steps}) populate the schema-driven setup-guide
 * collapsible. They are only consulted when {@code surface !== "hidden"}.
 *
 * <p>See {@code scripts/api-migrations/SCHEMA.md} → "BYOK surface" for the
 * canonical definition. Single source of truth across:
 * <ul>
 *   <li>{@code validate_apis.py} - schema validation at import time</li>
 *   <li>{@code ApiMigrationImporter.java:1549} - JSONB persistence to
 *     {@code catalog.credentials.metadata}</li>
 *   <li>this resolver - frontend read path</li>
 * </ul>
 */
export type ByokSurface = 'hidden' | 'inline' | 'disclosure';
export interface ResolvedByokConfig {
  surface: ByokSurface;
  consoleUrl: string | null;
  redirectUriHint: string | null;
  scopeNotes: string | null;
  steps: Array<{ title: string; body: string }>;
}

/**
 * Read the byok block out of {@code template.metadata.oauth2Config.byok},
 * unwrapping the catalog wire envelope ({@code { type: "jsonb", value: "<json>" }}).
 *
 * <p>Returns the safe default ({@code surface: "hidden"}, no content) when:
 * <ul>
 *   <li>the template is null/undefined,</li>
 *   <li>{@code metadata} is missing or malformed,</li>
 *   <li>{@code oauth2Config.byok} is absent - covers the ~99% of OAuth2
 *     APIs in the catalog that have not opted into BYOK exposure.</li>
 * </ul>
 *
 * <p>Defensive parsing: any field type-mismatch falls back to {@code null}
 * (or empty array for {@code steps}) rather than throwing - a malformed
 * byok block must never crash the wizard render.
 */
export function resolveByokConfig(template: CredentialTemplate | null | undefined): ResolvedByokConfig {
  const fallback: ResolvedByokConfig = {
    surface: 'hidden', consoleUrl: null, redirectUriHint: null, scopeNotes: null, steps: [],
  };
  if (!template) return fallback;

  const metadata = unwrapJsonbEnvelope(template.metadata);
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return fallback;
  const cfg = (metadata as Record<string, unknown>).oauth2Config;
  if (!cfg || typeof cfg !== 'object' || Array.isArray(cfg)) return fallback;
  const byok = (cfg as Record<string, unknown>).byok;
  if (!byok || typeof byok !== 'object' || Array.isArray(byok)) return fallback;

  const b = byok as Record<string, unknown>;
  const surfaceRaw = b.surface;
  const surface: ByokSurface = surfaceRaw === 'inline' || surfaceRaw === 'disclosure' ? surfaceRaw : 'hidden';

  const consoleUrl = typeof b.consoleUrl === 'string' && b.consoleUrl ? b.consoleUrl : null;
  const redirectUriHint = typeof b.redirectUriHint === 'string' && b.redirectUriHint ? b.redirectUriHint : null;
  const scopeNotes = typeof b.scopeNotes === 'string' && b.scopeNotes ? b.scopeNotes : null;

  const steps: Array<{ title: string; body: string }> = [];
  if (Array.isArray(b.steps)) {
    for (const s of b.steps) {
      if (s && typeof s === 'object' && !Array.isArray(s)) {
        const so = s as Record<string, unknown>;
        if (typeof so.title === 'string' && typeof so.body === 'string' && so.title && so.body) {
          steps.push({ title: so.title, body: so.body });
        }
      }
    }
  }

  return { surface, consoleUrl, redirectUriHint, scopeNotes, steps };
}

// ============================================
// Component
// ============================================

export function CredentialWizard({
  template: singleTemplate,
  requirements = [],
  open,
  onOpenChange,
  onComplete,
  onCredentialAdded,
  initialMode = 'standard',
}: CredentialWizardProps) {
  const t = useTranslations("credentials.wizard");
  const tConfig = useTranslations("credentials.configureDialog");
  const queryClient = useQueryClient();

  // Determine mode: single template or multi-requirements
  const isSingleMode = !!singleTemplate && requirements.length === 0;
  const effectiveRequirements: CredentialRequirement[] = isSingleMode
    ? singleTemplate
      ? [{ iconSlug: extractIconSlug(singleTemplate), serviceName: singleTemplate.display_name }]
      : []
    : requirements;

  // Track status of each credential
  const [credentialStates, setCredentialStates] = useState<CredentialState[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [step, setStep] = useState<WizardStep>("loading");
  const [template, setTemplate] = useState<CredentialTemplate | null>(null);
  // All auth variants offered by the current API (Gmail → [oauth2, api_key]). When the
  // list has ≥2 entries the configure step renders a tab picker above the form so the
  // user can switch between variants. Single-variant APIs get a one-element list and
  // the picker is hidden - the flow is identical to pre-Phase 2c.
  const [variants, setVariants] = useState<CredentialTemplate[]>([]);
  const [credentialName, setCredentialName] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasPlatformCredentials, setHasPlatformCredentials] = useState<boolean | null>(null);
  const [showUnverifiedAppWarning, setShowUnverifiedAppWarning] = useState(false);
  const [imageErrors, setImageErrors] = useState<Record<string, boolean>>({});
  const hasHandledCallback = useRef(false);
  // Tracks the most recently selected template so that in-flight
  // checkPlatformCredentials calls from older variant clicks can detect they
  // are stale and skip their setState writes.
  const activeTemplateIdRef = useRef<string | null>(null);

  // Fields for non-OAuth2 auth types
  const [apiKey, setApiKey] = useState("");
  const [bearerToken, setBearerToken] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showSecret, setShowSecret] = useState(false);

  const currentRequirement = effectiveRequirements[currentIndex];
  const totalCount = effectiveRequirements.length;
  const completedCount = credentialStates.filter((s) => s.status === "completed").length;

  // Fields for custom auth type (dynamic properties)
  const [customFields, setCustomFields] = useState<Record<string, string>>({});

  // Fields for OAuth2 config (custom APIs without platform credentials)
  const [oauthClientId, setOauthClientId] = useState("");
  const [oauthClientSecret, setOauthClientSecret] = useState("");
  const [oauthAuthUrl, setOauthAuthUrl] = useState("");
  const [oauthTokenUrl, setOauthTokenUrl] = useState("");
  const [oauthScopes, setOauthScopes] = useState("");

  // V166 BYOK: snapshot of `initialMode` taken at dialog-open time. Decoupled
  // from the prop so a parent state flip mid-flow (e.g. close-then-reopen
  // during the 1.5s success animation) doesn't reroute the user mid-wizard.
  // Reset to the latest prop value on every open transition.
  const [activeMode, setActiveMode] = useState<'standard' | 'advanced'>(initialMode);
  useEffect(() => {
    if (open) {
      setActiveMode(initialMode);
    }
    // Intentionally only re-syncs on `open` transitions, not on every prop change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // V166 BYOK auto-fill - pre-fill authUrl/tokenUrl/scopes from the template's
  // canonical OAuth provider config (metadata.oauth2Config), with a legacy
  // properties[].default fallback. Decoupled from hasPlatformCredentials: the
  // URLs are provider-fixed (Gmail = Google's endpoints regardless of who owns
  // the OAuth client), so they pre-fill whenever the catalog has them. See
  // {@link resolveOAuth2Defaults} for source priority + parsing details.
  //
  // Gated on `activeMode === 'advanced'` so the custom-API tenant-OAuth
  // registration flow (mode='standard', step='oauth-config' for `tmpl.source ===
  // "custom"`) does not get its user-typed URLs overwritten if a stale catalog
  // template happens to carry legacy authUrl defaults.
  //
  // handleOAuthConfigSave still reads from the same state fields, so the
  // persistence path stays unchanged.
  useEffect(() => {
    if (step !== 'oauth-config' || !template || activeMode !== 'advanced') return;
    const defaults = resolveOAuth2Defaults(template);
    if (defaults.authUrl) setOauthAuthUrl(defaults.authUrl);
    if (defaults.tokenUrl) setOauthTokenUrl(defaults.tokenUrl);
    // In BYOK we pre-fill the scope input with the union of platform scopes
    // AND byokOnlyScopes. The user runs OAuth on their own Google Cloud
    // project where restricted scopes can be declared on their own consent
    // screen, so the full set should be on the URL by default.
    const byokOnlyScopes = resolveByokOnlyScopes(template);
    const combinedScopes = [defaults.scopes, byokOnlyScopes].filter(Boolean).join(' ').trim();
    if (combinedScopes) setOauthScopes(combinedScopes);
  }, [step, template, activeMode]);

  // V166 BYOK: hide authUrl/tokenUrl/scopes whenever provider-canonical defaults
  // are available - independent of hasPlatformCredentials. The previous gate
  // (`hasPlatformCredentials === true`) silently dropped auto-fill when:
  //   - LiveContext had no global OAuth client for the integration, OR
  //   - the user just deleted their tenant-owned BYOK row (cascade-revoke
  //     flow) and was the only platform_credential holder for it.
  // In both cases the URLs were still known from the catalog metadata, so
  // hiding them is correct and saves the user from retyping provider-fixed
  // strings. Custom APIs without any catalog metadata fall through to the
  // editable form (defaults.authUrl/tokenUrl are null → hideOAuthUrls=false).
  const oauthDefaults = activeMode === 'advanced' ? resolveOAuth2Defaults(template) : null;
  const hideOAuthUrls = activeMode === 'advanced'
    && !!oauthDefaults?.authUrl
    && !!oauthDefaults?.tokenUrl;

  // Get effective auth type (normalized)
  const authType: AuthType = (template?.auth_type || "oauth2").toLowerCase();
  const isOAuth2 = authType === "oauth2";
  const isApiKey = authType === "api_key" || authType === "apikey";
  const isBearer = authType === "bearer" || authType === "bearer_token";
  const isBasic = authType === "basic" || authType === "basic_auth";
  const isNone = authType === "none";
  const isCustom = !isOAuth2 && !isApiKey && !isBearer && !isBasic && !isNone;

  // Resolve the BYOK exposure config out of the catalog metadata. Drives
  // whether the Standard / Custom OAuth toggle renders and how prominent it
  // is. surface='hidden' (or absent block) → no toggle anywhere; surface=
  // 'inline' → 2-pill ModeToggle in the main flow; surface='disclosure' →
  // collapsible "Need a custom OAuth app?" affordance for power users only.
  // See {@link resolveByokConfig} for the source-of-truth resolution.
  const byokConfig = resolveByokConfig(template);
  // Fully-BYOK integrations have zero platform scopes - surface the BYOK form
  // as the only option (no Standard toggle). The wizard auto-routes there in
  // checkPlatformCredentials. We still expose the BYOK helpers (steps,
  // scopeNotes) regardless of byokConfig.surface so the user sees the setup
  // guide. byokOffered keeps controlling the standard ModeToggle visibility:
  // when fully BYOK we set it to false explicitly so no toggle renders.
  const fullyByok = isFullyByokTemplate(template);
  const byokOffered = isOAuth2
    && hasPlatformCredentials === true
    && byokConfig.surface !== 'hidden'
    && !fullyByok;

  // ============================================
  // Effects
  // ============================================

  // Initialize credential states when the wizard OPENS or the requirement count
  // changes. `open` is in the deps because the on-close reset wipes
  // credentialStates to []; when the wizard is mounted closed (the panel/gate
  // mount it ahead of time and flip `open` on click) the length-only dependency
  // never re-fired on re-open, leaving credentialStates empty. That broke the
  // multi-credential walkthrough: no progress stepper rendered, so the user had
  // no chips to click and no way to move between services.
  useEffect(() => {
    if (open && effectiveRequirements.length > 0) {
      setCredentialStates(
        effectiveRequirements.map((req, index) => ({
          requirement: req,
          status: index === 0 ? "current" : "pending",
        }))
      );
    }
  }, [open, effectiveRequirements.length]);

  // Fetch template for current requirement
  useEffect(() => {
    if (!open || !currentRequirement) return;

    // If single mode with template provided, use it directly
    if (isSingleMode && singleTemplate) {
      setTemplate(singleTemplate);
      loadVariantsFor(singleTemplate);
      checkPlatformCredentials(singleTemplate);
      return;
    }

    // Multi mode: fetch template by iconSlug
    const fetchTemplate = async () => {
      setStep("loading");
      setError(null);

      try {
        // Tool callers may surface either the canonical icon_slug
        // ("googlegemini") or an apiSlug shape ("google-gemini"). The catalog
        // stores both `icon_slug` and `credential_name` in the canonical
        // separator-free form, so try the request as-is first (cheap when it
        // already matches) and retry with the normalized form before falling
        // back to ILIKE search. Without the retry, hyphenated apiSlugs hit
        // "Service configuration not found" even though the template exists.
        const requestedSlug = currentRequirement.iconSlug;
        const canonicalSlug = normalizeIconSlug(requestedSlug);
        const exactTemplate =
          (await orchestratorApi.getCredentialTemplateByName(requestedSlug)) ||
          (canonicalSlug && canonicalSlug !== requestedSlug
            ? await orchestratorApi.getCredentialTemplateByName(canonicalSlug)
            : null);
        if (exactTemplate) {
          setTemplate(exactTemplate);
          loadVariantsFor(exactTemplate);
          await checkPlatformCredentials(exactTemplate);
          return;
        }

        // Fallback: ILIKE search for catalog API credentials. Match on the
        // canonical form so "google-gemini" / "Google Gemini" / "googlegemini"
        // all converge on the same template.
        const response = await orchestratorApi.getCredentialTemplates({
          search: canonicalSlug || requestedSlug,
          pageSize: 10,
          includeInactive: true,
        });

        const matchingTemplate = response.credentials?.find((tmpl) => {
          const tmplIconCanon = normalizeIconSlug(tmpl.icon_slug);
          const tmplCredCanon = normalizeIconSlug(tmpl.credential_name);
          const tmplDisplayCanon = normalizeIconSlug(tmpl.display_name);
          if (canonicalSlug && tmplIconCanon === canonicalSlug) return true;
          if (canonicalSlug && tmplCredCanon === canonicalSlug) return true;
          if (canonicalSlug && tmplDisplayCanon === canonicalSlug) return true;
          // Icon files in /icons/services/ are stored under the canonical
          // slug, so match the URL against that - keeps symmetry with the
          // three canonical comparisons above.
          if (canonicalSlug && tmpl.icon_url?.includes(canonicalSlug)) return true;
          return false;
        });

        if (!matchingTemplate) {
          setError(t("errors.templateNotFound"));
          setStep("error");
          return;
        }

        setTemplate(matchingTemplate);
        loadVariantsFor(matchingTemplate);
        await checkPlatformCredentials(matchingTemplate);
      } catch (err) {
        console.error("Failed to fetch template:", err);
        setError(t("errors.fetchFailed"));
        setStep("error");
      }
    };

    fetchTemplate();
    // Depend on currentRequirement.iconSlug (string, stable by value) instead of the
    // object itself - effectiveRequirements rebuilds a new object every render in
    // single-mode, which would otherwise re-fire this effect after every setState
    // and overwrite the template the user just picked via handleSelectVariant.
  }, [open, currentRequirement?.iconSlug, currentIndex, isSingleMode, singleTemplate]);

  // Fire-and-forget variant list loader. We don't block the form on this - the single-
  // row template is enough to render today's flow. When the full list arrives, the
  // picker appears only if there is a genuine choice (≥2 variants). Failures are
  // swallowed inside the API client (returns []), so this cannot regress the wizard.
  const loadVariantsFor = useCallback((tmpl: CredentialTemplate) => {
    if (!tmpl?.credential_name) {
      setVariants([]);
      return;
    }
    orchestratorApi
      .getCredentialVariants(tmpl.credential_name)
      .then((rows) => setVariants(rows ?? []))
      .catch(() => setVariants([]));
  }, []);

  // Check platform credentials (only for OAuth2)
  const checkPlatformCredentials = useCallback(async (tmpl: CredentialTemplate) => {
    // Stamp the ref with this template - handleSelectVariant also bumps it on tab
    // click so an older in-flight call can detect it lost the race and bail.
    activeTemplateIdRef.current = tmpl.id;
    const authTypeNorm = (tmpl.auth_type || "").toLowerCase();

    // For 'none' auth type, auto-complete (no credentials needed)
    if (authTypeNorm === "none") {
      // Mark as completed and trigger success - no actual credential needed
      setCredentialStates((prev) =>
        prev.map((s, i) => (i === currentIndex ? { ...s, status: "completed" } : s))
      );
      setStep("success");
      onCredentialAdded?.(currentRequirement?.iconSlug);
      return;
    }

    // For non-OAuth2 types (api_key, bearer, basic, custom), no platform credentials needed
    if (authTypeNorm !== "oauth2") {
      setHasPlatformCredentials(true);
      setShowUnverifiedAppWarning(false);
      setStep("configure");
      return;
    }

    // OAuth2 requires platform credentials. Pass the canonical credential_name
    // (which equals iconSlug post-May-2026 - see SCHEMA.md "iconSlug = single
    // source of truth") so the auth-side `PlatformCredentialService
    // .normalizeIntegrationName` lookup matches the value stored in
    // `auth.platform_credentials.integration_name` (also keyed by iconSlug now).
    // Passing `display_name` like "Twitter / X" used to work because both sides
    // collapsed to "twitterx" via the old normalize-the-apiName scheme; that
    // path is now broken since the DB row holds "twitter".
    try {
      const integrationName = tmpl.credential_name || tmpl.display_name || "";
      const availability = await orchestratorApi.getPlatformCredentialsAvailability(integrationName);
      const available = availability.available;
      if (activeTemplateIdRef.current !== tmpl.id) return; // stale click - newer variant won
      setHasPlatformCredentials(available);
      setShowUnverifiedAppWarning(available && !!availability.showUnverifiedAppWarning);

      // V166 BYOK: when caller requested Advanced mode, jump straight to the
      // BYOK form regardless of whether platform creds exist. The user explicitly
      // wants to bring their own client_id/secret (e.g. for restricted scopes
      // not declared on the LiveContext platform consent screen).
      // Check BOTH `activeMode` AND `initialMode`: at dialog-open time the
      // `[open]` effect's setActiveMode(initialMode) has not applied yet when this
      // async callback's closure was captured, so `activeMode` can still read
      // 'standard' here even though the caller opened us in 'advanced' (the
      // MissingScopesBanner "Use a custom OAuth connection" path). `initialMode`
      // is the prop - 'advanced' synchronously - so it wins the open-time race;
      // `activeMode` covers the in-wizard "Need a custom OAuth app?" toggle.
      if (activeMode === 'advanced' || initialMode === 'advanced') {
        setStep("oauth-config");
        return;
      }

      // Fully-BYOK integrations (e.g. Google Classroom): the platform-shared
      // OAuth client declares zero scopes (only byokOnlyScopes are populated),
      // so Standard mode would issue /authorize?scope= (empty) and fail. Skip
      // the Standard option entirely and land directly on the BYOK form, just
      // like the !available branch below. Mode toggle stays hidden because
      // byokOffered checks remain in place at the render level.
      if (isFullyByokTemplate(tmpl)) {
        setActiveMode('advanced');
        setStep("oauth-config");
        return;
      }

      if (!available) {
        // No platform creds available - fall through to BYOK ("Custom app") so the user
        // can bring their own OAuth client_id/secret. Covers three scenarios uniformly:
        //   • Custom APIs (no platform creds by design - same behaviour as before)
        //   • Catalog APIs whose auth.platform_credentials row is admin-disabled
        //   • Catalog APIs that never had a platform_credentials row in the first place
        // Forcing activeMode='advanced' makes the existing oauth-config pre-fill effect
        // populate authUrl/tokenUrl/scopes from catalog metadata (resolveOAuth2Defaults),
        // and hides the URL inputs when canonical defaults exist (hideOAuthUrls). The
        // ModeToggle stays hidden because byokOffered requires hasPlatformCredentials===true.
        setActiveMode('advanced');
        setStep("oauth-config");
      } else {
        setStep("configure");
      }
    } catch (err) {
      console.error("Failed to check platform credentials:", err);
      if (activeTemplateIdRef.current !== tmpl.id) return; // stale click
      setHasPlatformCredentials(false);
      setShowUnverifiedAppWarning(false);
      // Same uniform fallback as the !available branch above - no platform creds means
      // BYOK. The previous error path (setError + step='error') only fired for catalog
      // APIs whose admin-disabled platform_credentials row hid an otherwise functional
      // integration; users can now still register their own OAuth client.
      setActiveMode('advanced');
      setStep("oauth-config");
    }
  }, [tConfig, currentIndex, currentRequirement?.iconSlug, onCredentialAdded, activeMode, initialMode]);

  // Handle OAuth2 callback from URL
  useEffect(() => {
    if (!open) {
      hasHandledCallback.current = false;
      return;
    }

    const params = new URLSearchParams(window.location.search);
    const success = params.get("success");
    const errorParam = params.get("error");

    if (hasHandledCallback.current) return;
    if (!success && !errorParam) return;

    hasHandledCallback.current = true;

    if (success === "true") {
      markCurrentAsCompleted();
      const cleanUrl = window.location.pathname;
      window.history.replaceState({}, "", cleanUrl);
    } else if (errorParam) {
      setError(decodeURIComponent(errorParam));
      setStep("error");
      const cleanUrl = window.location.pathname;
      window.history.replaceState({}, "", cleanUrl);
    }
    // Same reason as the fetch effect above: depend on the stable iconSlug string,
    // not the regenerated object, so setState-driven re-renders don't retrigger this.
  }, [open, currentRequirement?.iconSlug, currentIndex]);

  // Reset when dialog closes
  useEffect(() => {
    if (!open) {
      setCurrentIndex(0);
      setStep("loading");
      setTemplate(null);
      setVariants([]);
      setCredentialName("");
      setApiKey("");
      setBearerToken("");
      setUsername("");
      setPassword("");
      setShowSecret(false);
      setCustomFields({});
      setOauthClientId("");
      setOauthClientSecret("");
      setOauthAuthUrl("");
      setOauthTokenUrl("");
      setOauthScopes("");
      setError(null);
      setImageErrors({});
      hasHandledCallback.current = false;
      setCredentialStates([]);
      setHasPlatformCredentials(null);
      // Reset activeMode here too - symmetric with every other field above.
      // The on-open useEffect re-syncs it from initialMode on the next open
      // transition, but resetting on close eliminates a residual race window
      // where the first paint after re-open could briefly observe the prior
      // session's mode (currently masked by step→'loading', but only by
      // accident - making the invariant explicit avoids future regressions).
      setActiveMode(initialMode);
    }
  }, [open]);

  // ============================================
  // Handlers
  // ============================================

  const markCurrentAsCompleted = () => {
    setCredentialStates((prev) =>
      prev.map((s, i) => (i === currentIndex ? { ...s, status: "completed" } : s))
    );
    setStep("success");
    onCredentialAdded?.(currentRequirement?.iconSlug);

    // A credential was just created. Refresh every credentials cache entry so
    // consumers that read a possibly-stale cache - notably PublicationInfoPanel's
    // "Setup required" badge (useMissingCredentials) right after the acquired-app
    // setup gate dismisses - reflect it WITHOUT a hard refresh. The parent
    // callbacks above only cover the panel's own wizard; the gate flow never
    // wired a refetch, so do it here at the single creation chokepoint.
    void invalidateCredentialCaches(queryClient);

    // Auto-close after short delay when single credential (no next pending)
    const nextPending = credentialStates.findIndex(
      (s, i) => i !== currentIndex && s.status === "pending"
    );
    if (nextPending === -1) {
      setTimeout(() => {
        onComplete?.(getCompletedIconSlugs());
        onOpenChange(false);
      }, 1500);
    }
  };

  const getCompletedIconSlugs = (): string[] => {
    return credentialStates
      .filter((s) => s.status === "completed")
      .map((s) => s.requirement.iconSlug);
  };

  // Handle OAuth2 connection
  const handleOAuth2Connect = async () => {
    if (!template) return;

    setIsSubmitting(true);
    setError(null);

    try {
      const returnUrl = window.location.pathname;
      const wizardState = {
        returnUrl,
        requirements: effectiveRequirements.map((r) => ({ iconSlug: r.iconSlug, serviceName: r.serviceName })),
        currentIndex,
      };
      sessionStorage.setItem("oauth_wizard_state", JSON.stringify(wizardState));

      const request: OAuth2InitiateRequest = {
        credential_template_id: template.id,
        credential_name: credentialName.trim() || undefined,
        // Don't send integration - backend will use icon_slug from template
        return_url: returnUrl,  // Send returnUrl to backend for post-auth redirection
      };

      // Forward the app UI locale (next-intl) so the provider's consent screen + scope
      // descriptions render in the user's APP language, not their browser language.
      const response = await orchestratorApi.initiateOAuth2(request, getClientLocale());

      if (response.authorization_url) {
        window.location.href = response.authorization_url;
        // If a beforeunload guard (e.g. unsaved workflow changes) shows the
        // native "Leave site?" prompt and the user clicks Stay, the navigation
        // above is cancelled silently - no event fires. Unfreeze the Connect
        // button after a short delay so it doesn't stay disabled forever. If
        // the navigation actually proceeds, this page unloads and the timeout
        // is discarded.
        setTimeout(() => setIsSubmitting(false), 1500);
      } else {
        throw new Error("No authorization URL returned");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : tConfig("errors.failedToInitiate"));
      setIsSubmitting(false);
    }
  };

  // Get template properties as array (handles string, array, or {type,value} JSONB wrapper)
  const getTemplateProperties = useCallback((): CredentialProperty[] => {
    if (!template?.properties) return [];
    let raw: unknown = template.properties;
    // Unwrap JSONB wrapper: { type: "jsonb", value: "[...]" }
    if (typeof raw === "object" && !Array.isArray(raw) && (raw as Record<string, unknown>).value != null) {
      raw = (raw as Record<string, unknown>).value;
    }
    if (Array.isArray(raw)) return raw;
    if (typeof raw === "string") {
      try {
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
      } catch {
        return [];
      }
    }
    return [];
  }, [template]);

  // Credential properties BEYOND the primary auth field(s) - i.e. importer-registered
  // URL-template / account identifiers (Bandwidth account_id, Sinch project + service-plan
  // id, base-URL {domain}/{instance}/{shop} vars …). The basic_auth / bearer / api_key
  // forms hardcode only their primary fields (username+password, token, api_key), so without
  // this any extra `required` property the importer added would never be rendered nor
  // collected - and the runtime URL/header resolution falls back to the wrong value (401/404).
  //
  // The importer always emits the PRIMARY auth secret FIRST (buildCredentialFieldsFromAuth),
  // then the extra fields. The standard branches already collect the primary, so we drop it:
  //   - basic_auth: username + password are excluded by name (always named so).
  //   - api_key / bearer: the single primary field can be MIS-NAMED after its header
  //     (e.g. "Authorization", "x-api-key", "access_token"), which the name-set wouldn't
  //     catch - so we also drop the leading field positionally.
  // Gated to `required` props so it stays a no-op for the majority of APIs that declare only
  // their standard fields. Custom auth already renders every property; oauth2/none consume no
  // credential_data fields - both yield nothing.
  const getExtraCredentialProperties = useCallback((): CredentialProperty[] => {
    if (!(isBasic || isBearer || isApiKey)) return [];
    const props = getTemplateProperties();
    const skipPrimary = (isApiKey || isBearer) ? 1 : 0;
    // Tripwire: today NO non-custom-auth catalog API has a URL/path var that normalizes
    // into this set (the few {token}/{api_key} base-URL vars are all auth_type=custom,
    // rendered by the isCustom branch). If a future basic/bearer/api_key API ever names a
    // real extra var like {token}, it would be silently filtered here - promote it out of
    // this set (the positional skipPrimary already covers the primary auth field).
    const standard = new Set<string>([
      "username", "password", "api_key", "apikey", "bearer_token", "access_token", "token", "secret", "key",
    ]);
    return props
      .filter((_, i) => i >= skipPrimary)
      .filter((p) => !!p?.name && p.required === true && p.type !== "notice"
        && !standard.has(p.name.toLowerCase()));
  }, [getTemplateProperties, isBasic, isBearer, isApiKey]);

  // Handle direct credential save (API Key, Bearer, Basic, Custom)
  const handleDirectSave = async () => {
    if (!template) return;

    setIsSubmitting(true);
    setError(null);

    try {
      let credentialData: Record<string, unknown> = {};
      let credType: string = template.auth_type || "API Key";

      if (isApiKey) {
        if (!apiKey.trim()) {
          setError(t("errors.apiKeyRequired"));
          setIsSubmitting(false);
          return;
        }
        credentialData = { api_key: apiKey.trim() };
        credType = "API Key";
      } else if (isBearer) {
        if (!bearerToken.trim()) {
          setError(t("errors.bearerRequired"));
          setIsSubmitting(false);
          return;
        }
        credentialData = { bearer_token: bearerToken.trim() };
        credType = "API Key"; // Bearer is stored as API Key type
      } else if (isBasic) {
        if (!username.trim() || !password.trim()) {
          setError(t("errors.basicRequired"));
          setIsSubmitting(false);
          return;
        }
        credentialData = { username: username.trim(), password: password.trim() };
        credType = "Basic Auth";
      } else if (isCustom) {
        // Validate required custom fields (with same fallback as render)
        let properties = getTemplateProperties();
        if (properties.length === 0) {
          properties = [{
            name: "api_token",
            displayName: "API Token",
            type: "hidden" as const,
            required: true,
          }];
        }
        for (const prop of properties) {
          if (prop.required && !customFields[prop.name]?.trim()) {
            setError(t("errors.customFieldRequired", { field: prop.displayName || prop.name }));
            setIsSubmitting(false);
            return;
          }
        }
        // Build credential data from custom fields
        for (const prop of properties) {
          if (customFields[prop.name]) {
            credentialData[prop.name] = customFields[prop.name].trim();
          }
        }
        credType = "API Key"; // Custom credentials stored as API Key type
      }

      // Merge importer-registered extra required fields (URL-template / account-id vars such
      // as bandwidth_account_id / sinch_project_id) into credential_data for the standard
      // auth types. isCustom already collected every property above, and
      // getExtraCredentialProperties() returns [] for it, so this only augments
      // basic/bearer/api_key.
      if (isApiKey || isBearer || isBasic) {
        for (const prop of getExtraCredentialProperties()) {
          const val = (customFields[prop.name] ?? prop.default ?? "").trim();
          if (!val) {
            setError(t("errors.customFieldRequired", { field: prop.displayName || prop.name }));
            setIsSubmitting(false);
            return;
          }
          credentialData[prop.name] = val;
        }
      }

      await orchestratorApi.createCredential({
        name: credentialName.trim() || `${template.display_name || template.credential_name}`,
        integration: template.icon_slug || extractIconSlug(template) || template.credential_name || "",
        type: credType as 'OAuth2' | 'API Key' | 'Basic Auth' | 'Webhook',
        environment: "Production",
        status: "active",
        credential_data: credentialData,
        scopes: [],
        tags: [],
        icon_url: template.icon_url,
      });

      markCurrentAsCompleted();
    } catch (err) {
      console.error("Failed to save credential:", err);
      setError(err instanceof Error ? err.message : t("errors.saveFailed"));
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleConnect = () => {
    if (isOAuth2) {
      handleOAuth2Connect();
    } else {
      handleDirectSave();
    }
  };

  // Handle OAuth2 config save for custom APIs - saves tenant platform credential then initiates OAuth2
  const handleOAuthConfigSave = async () => {
    if (!template) return;

    if (!oauthClientId.trim()) {
      setError(tConfig("errors.clientIdRequired"));
      return;
    }
    if (!oauthClientSecret.trim()) {
      setError(tConfig("errors.clientSecretRequired"));
      return;
    }
    if (!oauthAuthUrl.trim() || !oauthTokenUrl.trim()) {
      setError(t("errors.oauthUrlsRequired"));
      return;
    }

    setIsSubmitting(true);
    setError(null);

    try {
      // Save tenant-scoped platform credential
      await orchestratorApi.saveTenantPlatformCredential({
        integrationName: template.credential_name || template.display_name || "",
        displayName: template.display_name || template.credential_name || "",
        authType: "oauth2",
        clientId: oauthClientId.trim(),
        clientSecret: oauthClientSecret.trim(),
        authUrl: oauthAuthUrl.trim(),
        tokenUrl: oauthTokenUrl.trim(),
        defaultScopes: oauthScopes.trim() || undefined,
        iconSlug: template.icon_slug || undefined,
      });

      // Platform credential saved.
      setHasPlatformCredentials(true);
      // V166 BYOK: when the user came in via "Switch to Advanced" on an integration
      // whose OAuth URLs we already know (hideOAuthUrls is true - provider-canonical
      // defaults resolved from catalog metadata), they expect "Save & Connect" to be
      // ONE action. Auto-trigger the OAuth2 flow immediately instead of bouncing
      // back to the configure step (which would ask for a credential name AGAIN).
      // Default credentialName is template.display_name when not set -
      // handleOAuth2Connect already handles the empty case by falling back to the
      // backend's auto-naming.
      if (hideOAuthUrls) {
        await handleOAuth2Connect();
        return;
      }
      // Custom-API tenant OAuth registration: bounce back to configure so the user
      // can pick a credential name and review variants before launching OAuth.
      setStep("configure");
      setIsSubmitting(false);
    } catch (err) {
      console.error("Failed to save OAuth config:", err);
      setError(err instanceof Error ? err.message : t("errors.oauthConfigFailed"));
      setIsSubmitting(false);
    }
  };

  // Find the next pending credential after the current one.
  const findNextCredential = (): number => {
    for (let i = currentIndex + 1; i < credentialStates.length; i++) {
      if (credentialStates[i]?.status === "pending") return i;
    }
    return -1;
  };

  const handleNext = () => {
    const nextIndex = findNextCredential();
    if (nextIndex === -1) {
      onComplete?.(getCompletedIconSlugs());
      onOpenChange(false);
    } else {
      setCredentialStates((prev) =>
        prev.map((s, i) => {
          if (i === nextIndex) return { ...s, status: "current" };
          return s;
        })
      );
      setCurrentIndex(nextIndex);
      resetForm();
    }
  };

  const goToCredential = (index: number) => {
    if (credentialStates[index]?.status === "completed") return;

    setCredentialStates((prev) =>
      prev.map((s, i) => {
        if (i === index) return { ...s, status: "current" };
        if (i === currentIndex && s.status === "current") {
          return { ...s, status: "pending" };
        }
        return s;
      })
    );
    setCurrentIndex(index);
    resetForm();
  };

  const resetForm = () => {
    setStep("loading");
    setCredentialName("");
    setApiKey("");
    setBearerToken("");
    setUsername("");
    setPassword("");
    setShowSecret(false);
    setCustomFields({});
    setOauthClientId("");
    setOauthClientSecret("");
    setOauthAuthUrl("");
    setOauthTokenUrl("");
    setOauthScopes("");
    setError(null);
    setShowUnverifiedAppWarning(false);
    hasHandledCallback.current = false;
    // Reset activeMode back to the prop default so a mid-flow Standard↔Advanced
    // toggle on credential A does NOT bleed into credential B in the multi-
    // credential walkthrough. Without this reset, the user toggling Advanced
    // on credential A would auto-route credential B to BYOK without consent
    // (checkPlatformCredentials short-circuits to setStep("oauth-config") when
    // activeMode==='advanced').
    setActiveMode(initialMode);
  };

  // Switch the active variant (user clicked a different tab). Swap the template, wipe
  // any auth-specific fields the previous variant filled in, then re-run the platform-
  // credential check because OAuth2 needs platform secrets while API_Key does not.
  // Clears hasPlatformCredentials so the UI doesn't flash stale OAuth2 state from the
  // previous tab while the new fetch is in flight.
  const handleSelectVariant = (next: CredentialTemplate) => {
    if (!next || next.id === template?.id) return;
    resetForm();
    setHasPlatformCredentials(null);
    setShowUnverifiedAppWarning(false);
    setStep("loading");
    setTemplate(next);
    void checkPlatformCredentials(next);
  };

  const handleImageError = (iconSlug: string) => {
    setImageErrors((prev) => ({ ...prev, [iconSlug]: true }));
  };

  // ============================================
  // Render helpers
  // ============================================

  const integrationName =
    template?.display_name || currentRequirement?.serviceName || currentRequirement?.iconSlug || "Service";

  const remainingCount = credentialStates.filter((s) => s.status === "pending").length;

  const renderProgressStepper = () => {
    if (totalCount <= 1) return null;

    return (
      <div className="flex flex-wrap gap-2 pb-2">
        {credentialStates.map((state, index) => {
          // A pending service can be jumped to directly - this is how the user
          // moves between services in a multi-service setup (e.g. past an
          // OAuth/BYOK step to a later one) now that there is no Skip button.
          const isClickable = state.status === "pending";
          // SVG files in /icons/services/ are stored under the canonical
          // (separator-free) slug. Normalize defensively so a requirement that
          // arrived as "google-gemini" still picks up googlegemini.svg.
          const iconSlug = normalizeIconSlug(state.requirement.iconSlug) || state.requirement.iconSlug;

          return (
            <button
              key={index}
              data-testid={`cred-step-${iconSlug}`}
              onClick={() => isClickable && goToCredential(index)}
              disabled={!isClickable}
              className={`
                flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-medium transition-all
                ${state.status === "current" ? "bg-slate-100 dark:bg-slate-800 text-slate-900 dark:text-slate-100 ring-2 ring-slate-900 dark:ring-slate-100" : ""}
                ${state.status === "completed" ? "bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-300" : ""}
                ${state.status === "pending" ? "bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 cursor-pointer hover:bg-slate-200 dark:hover:bg-slate-700" : ""}
              `}
            >
              {!imageErrors[iconSlug] ? (
                <Image
                  src={`/icons/services/${iconSlug}.svg`}
                  alt=""
                  width={14}
                  height={14}
                  className={`rounded-sm ${monoDarkInvertClass(iconSlug)}`}
                  onError={() => handleImageError(iconSlug)}
                />
              ) : (
                <span className="w-3.5 h-3.5 rounded-full bg-current opacity-30" />
              )}
              {state.status === "completed" && <CheckCircle className="h-3 w-3" />}
              {state.requirement.serviceName || iconSlug}
            </button>
          );
        })}
      </div>
    );
  };

  // Renders a single dynamic credential property input (text / password / options),
  // bound to the `customFields` state. Shared by the custom-auth form (which renders
  // every property) and `renderExtraCredentialFields` (the extra required props on top
  // of basic/bearer/api_key forms).
  const renderCredentialPropertyField = (prop: CredentialProperty) => {
    const isPassword = prop.typeOptions?.password || prop.type === "hidden";
    const fieldValue = customFields[prop.name] || prop.default || "";
    const isOptions = prop.type === "options" && Array.isArray(prop.options) && prop.options.length > 0;

    return (
      <div key={prop.name} className="space-y-4">
        <Label
          htmlFor={`custom-${prop.name}`}
          className="text-sm font-semibold text-slate-500 dark:text-slate-400"
        >
          {prop.displayName || prop.name}
          {prop.required && <span className="text-red-500 ml-1">*</span>}
        </Label>
        {prop.description && (
          <p className="text-xs text-slate-400">{prop.description}</p>
        )}
        {isOptions ? (
          <Select
            value={fieldValue}
            onValueChange={(value) =>
              setCustomFields((prev) => ({ ...prev, [prop.name]: value }))
            }
          >
            <SelectTrigger className="rounded-xl border border-theme bg-transparent mt-2">
              <SelectValue placeholder={prop.placeholder || prop.displayName || prop.name} />
            </SelectTrigger>
            <SelectContent>
              {prop.options!.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        ) : (
          <div className="relative">
            <Input
              id={`custom-${prop.name}`}
              name={`credential-custom-${prop.name}`}
              type={isPassword && !showSecret ? "password" : "text"}
              placeholder={prop.placeholder || prop.displayName || prop.name}
              value={fieldValue}
              onChange={(e) =>
                setCustomFields((prev) => ({ ...prev, [prop.name]: e.target.value }))
              }
              autoComplete="off"
              className="rounded-xl border border-theme bg-transparent mt-2 pr-10"
            />
            {isPassword && (
              <button
                type="button"
                onClick={() => setShowSecret(!showSecret)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
              >
                {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            )}
          </div>
        )}
      </div>
    );
  };

  // Extra required credential fields (URL-template / account-id vars) rendered AFTER the
  // standard basic/bearer/api_key inputs. Renders nothing for APIs that declare only their
  // standard fields. See {@link getExtraCredentialProperties}.
  const renderExtraCredentialFields = () => {
    const extras = getExtraCredentialProperties();
    if (extras.length === 0) return null;
    return <>{extras.map(renderCredentialPropertyField)}</>;
  };

  const renderAuthTypeFields = () => {
    // Shared props that defeat Chrome/Firefox's aggressive password-manager autofill.
    // autoComplete="off" alone is ignored on username/password pairs; the combination
    // below (new-password + readOnly-until-focus + a decoy name) blocks the autofill
    // machinery because it needs a writable, heuristically-matched field to target.
    const noAutofillProps = {
      autoComplete: "new-password",
      "data-lpignore": "true",
      "data-1p-ignore": "true",
      "data-form-type": "other",
      readOnly: true,
      onFocus: (e: React.FocusEvent<HTMLInputElement>) => e.currentTarget.removeAttribute("readonly"),
    } as const;

    if (isApiKey) {
      return (
        <div className="space-y-4">
          <Label htmlFor="apiKey" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t("apiKey")}
          </Label>
          <div className="relative">
            <Input
              id="apiKey"
              name="cred-ak-field"
              type={showSecret ? "text" : "password"}
              placeholder={t("apiKeyPlaceholder")}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              {...noAutofillProps}
              className="rounded-xl border border-theme bg-transparent mt-2 pr-10"
            />
            <button
              type="button"
              onClick={() => setShowSecret(!showSecret)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
            >
              {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </div>
      );
    }

    if (isBearer) {
      return (
        <div className="space-y-4">
          <Label htmlFor="bearerToken" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t("bearerToken")}
          </Label>
          <div className="relative">
            <Input
              id="bearerToken"
              name="cred-bt-field"
              type={showSecret ? "text" : "password"}
              placeholder={t("bearerPlaceholder")}
              value={bearerToken}
              onChange={(e) => setBearerToken(e.target.value)}
              {...noAutofillProps}
              className="rounded-xl border border-theme bg-transparent mt-2 pr-10"
            />
            <button
              type="button"
              onClick={() => setShowSecret(!showSecret)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
            >
              {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </div>
      );
    }

    if (isBasic) {
      return (
        <>
          {/* Decoy fields: Chrome targets the FIRST matching username/password pair
              it finds inside a dialog. Hidden decoys above absorb the autofill so the
              real fields stay clean. Must be visible-in-DOM for the heuristic to bite. */}
          <input
            type="text"
            name="username"
            tabIndex={-1}
            aria-hidden="true"
            autoComplete="username"
            style={{ position: "absolute", left: "-9999px", width: 1, height: 1, opacity: 0 }}
          />
          <input
            type="password"
            name="password"
            tabIndex={-1}
            aria-hidden="true"
            autoComplete="current-password"
            style={{ position: "absolute", left: "-9999px", width: 1, height: 1, opacity: 0 }}
          />
          <div className="space-y-4">
            <Label htmlFor="basicUsername" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t("username")}
            </Label>
            <Input
              id="basicUsername"
              name="cred-basic-u-field"
              placeholder={t("usernamePlaceholder")}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              {...noAutofillProps}
              className="rounded-xl border border-theme bg-transparent mt-2"
            />
          </div>
          <div className="space-y-4">
            <Label htmlFor="basicPassword" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t("password")}
            </Label>
            <div className="relative">
              <Input
                id="basicPassword"
                name="cred-basic-p-field"
                type={showSecret ? "text" : "password"}
                placeholder={t("passwordPlaceholder")}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                {...noAutofillProps}
                className="rounded-xl border border-theme bg-transparent mt-2 pr-10"
              />
              <button
                type="button"
                onClick={() => setShowSecret(!showSecret)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
              >
                {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>
        </>
      );
    }

    if (isCustom) {
      let properties = getTemplateProperties();
      // Fallback: if no properties defined, show a generic API token field
      if (properties.length === 0) {
        properties = [{
          name: "api_token",
          displayName: "API Token",
          type: "hidden" as const,
          required: true,
          placeholder: "Enter API token or key",
        }];
      }

      return <>{properties.map(renderCredentialPropertyField)}</>;
    }

    return null;
  };

  // ============================================
  // Render
  // ============================================

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md border border-theme bg-theme-primary text-theme-primary rounded-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-3">
            {/* Use one canonical key for the URL, the imageErrors lookup, and
                the onError write so a hyphenated requirement and its canonical
                sibling share the same cache slot. */}
            {(() => {
              // Hyphenated requirements and their canonical sibling must share
              // the same imageErrors cache slot, src URL, and invert-class
              // lookup - collapse the four call-sites into one local const.
              const slug = currentRequirement?.iconSlug
                ? normalizeIconSlug(currentRequirement.iconSlug) || currentRequirement.iconSlug
                : "";
              if (slug && !imageErrors[slug]) {
                return (
                  <Image
                    src={`/icons/services/${slug}.svg`}
                    alt=""
                    width={32}
                    height={32}
                    className={`rounded ${monoDarkInvertClass(slug)}`}
                    onError={() => handleImageError(slug)}
                  />
                );
              }
              if (template?.icon_url) {
                return (
                  <img
                    src={template.icon_url}
                    alt=""
                    className={`h-8 w-8 rounded ${monoDarkInvertClass(extractIconSlugFromUrl(template.icon_url))}`}
                    onError={(e) => {
                      (e.target as HTMLImageElement).style.display = "none";
                    }}
                  />
                );
              }
              return null;
            })()}

            <span>
              {step === "success" ? t("successTitle") : t("title", { name: integrationName })}
            </span>
          </DialogTitle>
          <DialogDescription
            className="text-theme-secondary overflow-hidden"
            title={template?.description}
            style={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}
          >
            {step === "success"
              ? t("successDescription", { name: integrationName })
              : template?.description || t("description")}
          </DialogDescription>
        </DialogHeader>

        {/* Progress stepper */}
        {renderProgressStepper()}

        {/* Loading state */}
        {step === "loading" && (
          <div className="space-y-5">
            <div className="h-12 bg-theme-tertiary rounded-lg animate-pulse" />
            <div className="space-y-2">
              <div className="h-4 w-32 bg-theme-tertiary rounded animate-pulse" />
              <div className="h-10 bg-theme-tertiary rounded animate-pulse" />
            </div>
          </div>
        )}

        {/* Success state */}
        {step === "success" && (
          <div className="space-y-5">
            <div className="flex flex-col items-center gap-4 py-6">
              <div className="w-12 h-12 rounded-full bg-green-100 dark:bg-green-950/30 flex items-center justify-center">
                <CheckCircle className="h-6 w-6 text-green-500" />
              </div>
              <div className="text-center">
                <p className="text-sm font-medium text-theme-primary mb-1">
                  {t("connected", { name: integrationName })}
                </p>
                {remainingCount > 0 && (
                  <p className="text-sm text-theme-secondary">
                    {t("nextCredential", { remaining: remainingCount })}
                  </p>
                )}
              </div>
            </div>

            <DialogFooter className="gap-3 sm:gap-3">
              <Button type="button" variant="ghost" className="h-10 px-5" onClick={() => onOpenChange(false)}>
                {t("close")}
              </Button>
              <Button onClick={handleNext} className="h-10 px-6 gap-2">
                {remainingCount === 0 ? t("done") : t("nextButton")}
                {remainingCount > 0 && <ChevronRight className="h-4 w-4" />}
              </Button>
            </DialogFooter>
          </div>
        )}

        {/* Error state */}
        {step === "error" && (
          <div className="space-y-5">
            <div className="flex flex-col items-center gap-4 py-6">
              <div className="w-12 h-12 rounded-full bg-red-100 dark:bg-red-950/30 flex items-center justify-center">
                <AlertCircle className="h-6 w-6 text-red-500" />
              </div>
              <div className="text-center">
                <p className="text-sm font-medium text-theme-primary mb-1">
                  {tConfig("errors.credentialsNotConfigured")}
                </p>
                <p className="text-sm text-theme-secondary">{error || tConfig("errors.contactAdmin")}</p>
              </div>
            </div>

            <DialogFooter className="gap-3 sm:gap-3">
              <Button type="button" variant="outline" className="h-10 px-5" onClick={() => onOpenChange(false)}>
                {tConfig("close")}
              </Button>
            </DialogFooter>
          </div>
        )}

        {/* OAuth2 config for custom APIs */}
        {step === "oauth-config" && template && (
          <div className="space-y-5">
            {/* V166 BYOK: 2-pill segmented toggle (Standard | Custom OAuth).
                Only rendered when platform creds exist - for custom APIs
                without them, BYOK is the only path and there's no Standard
                mode to fall back to. The active pill (Custom OAuth here) is
                visually selected; clicking the other pill switches the step. */}
            {/* Inline 2-pill toggle: only shown when the catalog declares
                surface='inline' for this provider. No provider currently
                ships with surface='inline' on prod (every OAuth2 API is
                disclosure or hidden) - branch kept as a future-proofing
                affordance for re-enabling per-provider inline opt-in
                without a coordinated 3-layer schema change. Disclosure
                providers get a small "Use 1-click standard connection"
                back link below instead. Hidden providers reach this step
                only via the custom-API tenant OAuth flow (no platform
                creds), which renders no toggle at all. */}
            {byokOffered && byokConfig.surface === 'inline' && (
              <ModeToggle
                active="advanced"
                onSwitch={() => {
                  // Mid-flow flip: keep activeMode in sync with the step so the
                  // auto-fill effect AND hideOAuthUrls re-evaluate against the
                  // new mode (both gates require activeMode==='advanced'). Without
                  // this, switching back to Standard then to Advanced would leave
                  // activeMode stuck on whatever the dialog opened with.
                  setActiveMode('standard');
                  setStep("configure");
                }}
                disabled={isSubmitting}
                t={tConfig}
              />
            )}

            {byokOffered && byokConfig.surface === 'disclosure' && (
              <button
                type="button"
                onClick={() => { setActiveMode('standard'); setStep('configure'); }}
                disabled={isSubmitting}
                className="text-sm text-theme-secondary hover:text-theme-primary inline-flex items-center gap-1.5 disabled:opacity-50"
              >
                <span aria-hidden>←</span>
                {tConfig("modeToggle.backToStandard")}
              </button>
            )}

            {/* Fully-BYOK integrations (Google Classroom, …): platform-shared
                OAuth client requests zero scopes for this provider - by design
                to keep LiveContext's verified consent screen narrow. Tell the
                user upfront that BYOK is the only path, with the catalog's
                scopeNotes as the explanation. */}
            {fullyByok && (
              <div className="rounded-xl border border-amber-300/40 bg-amber-50/50 dark:bg-amber-900/20 px-4 py-3 text-sm text-theme-primary">
                <div className="font-semibold mb-1">{tConfig("modeToggle.byokOnlyTitle")}</div>
                <div className="text-theme-secondary">
                  {byokConfig.scopeNotes || tConfig("modeToggle.byokOnlyDescription")}
                </div>
              </div>
            )}

            <p className="text-sm text-theme-secondary">
              {t("oauthConfig.description")}
            </p>

            {/* Schema-driven setup guide. Renders ONLY when the catalog declares
                a non-empty `byok.steps[]` for this provider - i.e. the
                disclosure-mode providers we have
                written guides for (Slack, Notion, Airtable, GitHub, Microsoft).
                Long-tail OAuth2 APIs without a guide silently render nothing.
                The redirect URI string itself comes from
                NEXT_PUBLIC_OAUTH2_CALLBACK_URL (one runtime source of truth)
                regardless of what JSON the catalog declares. */}
            {byokConfig.surface !== 'hidden' && byokConfig.steps.length > 0 && (() => {
              const callbackUrl =
                process.env.NEXT_PUBLIC_OAUTH2_CALLBACK_URL ||
                (typeof window !== 'undefined'
                  ? `${window.location.origin}/api/credentials/oauth2/callback`
                  : 'https://livecontext.ai/api/credentials/oauth2/callback');
              // Catalog-declared scopes for the "Configure scopes" step. Without this,
              // a BYOK admin reading "Pick the scopes your workflow uses" has no way to
              // know WHICH scopes to enable in their provider console - they would have
              // to guess from a 30+ permission list (Salesforce Connected App, Google
              // Cloud OAuth client, etc.) and any mismatch with what the catalog requests
              // at /authorize? produces a silent consent-screen surprise. Source of
              // truth is metadata.oauth2Config.scopes - already in DB post-import and
              // resolved by resolveOAuth2Defaults; no new fetch.
              const recommendedScopes = oauthDefaults?.scopes
                ? oauthDefaults.scopes.split(/\s+/).filter(Boolean)
                : [];
              return (
                <details className="group rounded-xl border border-theme bg-theme-tertiary px-4 py-3">
                  <summary className="cursor-pointer list-none text-sm font-semibold text-theme-primary flex items-center justify-between">
                    <span>{tConfig("setupGuide.title")}</span>
                    <span className="text-theme-secondary text-xs group-open:rotate-180 transition-transform">▾</span>
                  </summary>
                  <ol className="mt-3 space-y-2 text-sm text-theme-secondary list-decimal list-inside">
                    {byokConfig.steps.map((s, i) => {
                      const isScopeStep = s.title.toLowerCase().includes('scope');
                      return (
                        <li key={i} className="space-y-1.5">
                          <span className="font-medium text-theme-primary">{s.title}</span>
                          <span className="block">{s.body}</span>
                          {isScopeStep && recommendedScopes.length > 0 && (
                            <div className="mt-1.5 flex flex-wrap gap-1.5">
                              {recommendedScopes.map((scope) => (
                                <code
                                  key={scope}
                                  className="text-xs bg-theme-secondary px-2 py-0.5 rounded font-mono break-all text-theme-primary"
                                >
                                  {scope}
                                </code>
                              ))}
                            </div>
                          )}
                        </li>
                      );
                    })}
                  </ol>
                  {/* Redirect URI copy widget - always rendered when a guide
                      exists, since every provider's setup needs the user to
                      whitelist this exact URL somewhere in their app config. */}
                  <div className="mt-3 space-y-1.5">
                    {byokConfig.redirectUriHint && (
                      <span className="text-xs text-theme-secondary block">{byokConfig.redirectUriHint}</span>
                    )}
                    <div className="flex items-center gap-2">
                      <code className="text-xs bg-theme-secondary px-2 py-1 rounded flex-1 break-all">
                        {callbackUrl}
                      </code>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="h-7 px-2 text-xs flex-shrink-0"
                        onClick={() => {
                          navigator.clipboard?.writeText(callbackUrl).catch(() => {});
                        }}
                      >
                        {tConfig("setupGuide.copy")}
                      </Button>
                    </div>
                  </div>
                  {byokConfig.scopeNotes && (
                    <p className="mt-3 text-xs text-theme-secondary">{byokConfig.scopeNotes}</p>
                  )}
                  {byokConfig.consoleUrl && (
                    <a
                      href={byokConfig.consoleUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="mt-3 inline-flex items-center gap-1.5 text-sm text-blue-500 hover:text-blue-600"
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                      {tConfig("setupGuide.openConsole")}
                    </a>
                  )}
                </details>
              );
            })()}

            <div className="space-y-4">
              <Label htmlFor="oauthClientId" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {t("oauthConfig.clientId")} <span className="text-red-500">*</span>
              </Label>
              <Input
                id="oauthClientId"
                placeholder={t("oauthConfig.clientIdPlaceholder")}
                value={oauthClientId}
                onChange={(e) => setOauthClientId(e.target.value)}
                autoComplete="off"
                className="rounded-xl border border-theme bg-transparent mt-2"
              />
            </div>

            <div className="space-y-4">
              <Label htmlFor="oauthClientSecret" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {t("oauthConfig.clientSecret")} <span className="text-red-500">*</span>
              </Label>
              <div className="relative">
                <Input
                  id="oauthClientSecret"
                  type={showSecret ? "text" : "password"}
                  placeholder={t("oauthConfig.clientSecretPlaceholder")}
                  value={oauthClientSecret}
                  onChange={(e) => setOauthClientSecret(e.target.value)}
                  autoComplete="off"
                  className="rounded-xl border border-theme bg-transparent mt-2 pr-10"
                />
                <button
                  type="button"
                  onClick={() => setShowSecret(!showSecret)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                >
                  {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>

            {/* V166 BYOK: optional credential name when we'll auto-fire OAuth
                after save (hideOAuthUrls = provider-canonical URLs resolved, so
                no "configure" bounce). Custom-API tenant OAuth registration
                still goes through the configure step where the name field
                lives, so we don't duplicate it there. */}
            {hideOAuthUrls && (
              <div className="space-y-4">
                <Label htmlFor="byokCredentialName" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                  {tConfig("credentialName")}
                  <span className="ml-1 text-xs font-normal text-theme-secondary">({tConfig("optional")})</span>
                </Label>
                <Input
                  id="byokCredentialName"
                  placeholder={tConfig("credentialNamePlaceholder", {
                    name: template.display_name || tConfig("credential"),
                  })}
                  value={credentialName}
                  onChange={(e) => setCredentialName(e.target.value)}
                  autoComplete="off"
                  className="rounded-xl border border-theme bg-transparent mt-2"
                />
              </div>
            )}

            {/* V166 BYOK: hide authUrl/tokenUrl/scopes whenever the catalog
                template gives us provider-canonical defaults (Gmail = Google's
                fixed endpoints regardless of which OAuth client owns them).
                See {@link resolveOAuth2Defaults} + {@code hideOAuthUrls}. */}
            {!hideOAuthUrls && (
            <div className="space-y-4">
              <Label htmlFor="oauthAuthUrl" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {t("oauthConfig.authUrl")} <span className="text-red-500">*</span>
              </Label>
              <Input
                id="oauthAuthUrl"
                placeholder={t("oauthConfig.authUrlPlaceholder")}
                value={oauthAuthUrl}
                onChange={(e) => setOauthAuthUrl(e.target.value)}
                autoComplete="off"
                className="rounded-xl border border-theme bg-transparent mt-2"
              />
            </div>
            )}

            {!hideOAuthUrls && (
            <div className="space-y-4">
              <Label htmlFor="oauthTokenUrl" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {t("oauthConfig.tokenUrl")} <span className="text-red-500">*</span>
              </Label>
              <Input
                id="oauthTokenUrl"
                placeholder={t("oauthConfig.tokenUrlPlaceholder")}
                value={oauthTokenUrl}
                onChange={(e) => setOauthTokenUrl(e.target.value)}
                autoComplete="off"
                className="rounded-xl border border-theme bg-transparent mt-2"
              />
            </div>
            )}

            {!hideOAuthUrls && (
            <div className="space-y-4">
              <Label htmlFor="oauthScopes" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {t("oauthConfig.scopes")}
                <span className="ml-1 text-xs font-normal text-theme-secondary">({tConfig("optional")})</span>
              </Label>
              <Input
                id="oauthScopes"
                placeholder={t("oauthConfig.scopesPlaceholder")}
                value={oauthScopes}
                onChange={(e) => setOauthScopes(e.target.value)}
                autoComplete="off"
                className="rounded-xl border border-theme bg-transparent mt-2"
              />
            </div>
            )}

            {error && (
              <div className="flex items-center gap-2 text-sm text-red-500">
                <AlertCircle className="h-4 w-4" />
                {error}
              </div>
            )}

            <DialogFooter className="gap-3 sm:gap-3">
              {/* Multi-service walkthroughs move past this OAuth/BYOK step by
                  clicking a later service's chip in the progress stepper above -
                  no per-step Skip button. */}
              <Button type="button" variant="ghost" className="h-10 px-5" onClick={() => onOpenChange(false)} disabled={isSubmitting}>
                {tConfig("cancel")}
              </Button>
              <Button
                onClick={handleOAuthConfigSave}
                disabled={
                  isSubmitting ||
                  !oauthClientId.trim() ||
                  !oauthClientSecret.trim() ||
                  // For custom-API tenant OAuth registration, URLs are user-supplied
                  // and must also be present. For BYOK on known APIs, they are
                  // pre-filled from the template so this guard is auto-satisfied.
                  !oauthAuthUrl.trim() ||
                  !oauthTokenUrl.trim()
                }
                className="h-10 px-6"
              >
                {isSubmitting ? (
                  <>
                    <LoadingSpinner size="xs" className="mr-2" />
                    {t("saving")}
                  </>
                ) : (
                  t("oauthConfig.saveAndConnect")
                )}
              </Button>
            </DialogFooter>
          </div>
        )}

        {/* Configure state */}
        {step === "configure" && template && (
          <div className="space-y-5">
            {/* Inline 2-pill toggle (Standard | Custom OAuth) - rendered only
                when the catalog declares surface='inline'. No provider
                currently ships inline on prod (kept for future per-provider
                opt-in). Disclosure providers get the link below instead.
                Hidden providers render no toggle at all. */}
            {byokOffered && byokConfig.surface === 'inline' && (
              <ModeToggle
                active="standard"
                onSwitch={() => {
                  // Mid-flow flip: keep activeMode in sync with the step so the
                  // auto-fill effect AND hideOAuthUrls re-evaluate against the
                  // new mode (both gates require activeMode==='advanced'). Without
                  // this, the user lands on oauth-config with activeMode stuck on
                  // 'standard' → URLs neither auto-filled nor hidden.
                  setActiveMode('advanced');
                  setStep("oauth-config");
                }}
                disabled={isSubmitting}
                t={tConfig}
              />
            )}

            {/* Variant tabs - only rendered when the API exposes more than one auth method.
                Single-variant APIs skip this entirely and the rest of the form is unchanged. */}
            {variants.length > 1 && (
              <div className="flex flex-wrap gap-2" role="tablist" aria-label={t("variantPickerLabel")}>
                {variants.map((v) => {
                  const isActive = v.id === template.id;
                  const label = v.variant
                    ? v.variant.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase())
                    : v.auth_type || t("variantFallbackLabel");
                  return (
                    <button
                      key={v.id}
                      type="button"
                      role="tab"
                      aria-selected={isActive}
                      onClick={() => handleSelectVariant(v)}
                      className={
                        "rounded-full border px-3 py-1 text-sm transition " +
                        (isActive
                          ? "border-theme bg-theme-accent text-theme-primary"
                          : "border-theme bg-transparent text-theme-secondary hover:bg-theme-tertiary")
                      }
                    >
                      {label}
                    </button>
                  );
                })}
              </div>
            )}

            {/* Auth type indicator for single-variant APIs. Multi-variant APIs already
                show the active type in the tab strip above, so repeating it here with a
                disabled <Select> just looked broken ("why is the dropdown greyed out?"). */}
            {template.auth_type && variants.length <= 1 && (
              <div className="space-y-2">
                <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                  {tConfig("authType")}
                </Label>
                <span className="inline-flex items-center rounded-full bg-theme-tertiary px-3 py-1 text-sm text-theme-secondary">
                  {template.auth_type.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase())}
                </span>
              </div>
            )}

            {/* Unverified-app heads-up - OAuth only. Some providers (Google, Microsoft…)
                show an "unverified app" interstitial during consent for integrations
                that haven't completed brand verification yet. Surfacing this here
                reassures the user that the warning is expected and safe to proceed. */}
            {isOAuth2 && showUnverifiedAppWarning && (
              <div className="flex items-start gap-2.5 rounded-xl border border-blue-200 dark:border-blue-800/50 bg-blue-50 dark:bg-blue-900/20 px-3 py-2.5 text-sm text-blue-800 dark:text-blue-200">
                <Info className="mt-0.5 h-4 w-4 shrink-0 text-blue-600 dark:text-blue-400" />
                <span>{tConfig("unverifiedNotice")}</span>
              </div>
            )}

            {/* Auth-specific fields */}
            {renderAuthTypeFields()}

            {/* Extra required credential fields (URL-template / account-id vars such as the
                Bandwidth Account ID or Sinch project / service-plan id) for basic/bearer/
                api_key auth. No-op for APIs that declare only their standard fields. */}
            {renderExtraCredentialFields()}

            {/* Optional credential name */}
            <div className="space-y-4">
              <Label htmlFor="credentialName" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {tConfig("credentialName")}
                <span className="ml-1 text-xs font-normal text-theme-secondary">({tConfig("optional")})</span>
              </Label>
              <Input
                id="credentialName"
                name="credential-display-name"
                placeholder={tConfig("credentialNamePlaceholder", {
                  name: template.display_name || tConfig("credential"),
                })}
                value={credentialName}
                onChange={(e) => setCredentialName(e.target.value)}
                autoComplete="off"
                className="rounded-xl border border-theme bg-transparent mt-2"
              />
            </div>

            {/* Error Message */}
            {error && (
              <div className="flex items-center gap-2 text-sm text-red-500">
                <AlertCircle className="h-4 w-4" />
                {error}
              </div>
            )}

            {/* Disclosure-mode BYOK affordance - low-emphasis link rendered
                AFTER the form so it doesn't compete with the main 1-click
                Standard CTA. Power users who actually need a custom OAuth
                app discover it at the end; users who just want to connect
                ignore it. Inline-mode providers use the toggle at the top
                of the form instead; hidden-mode providers render nothing. */}
            {byokOffered && byokConfig.surface === 'disclosure' && (
              <button
                type="button"
                onClick={() => { setActiveMode('advanced'); setStep('oauth-config'); }}
                disabled={isSubmitting}
                className="text-sm text-theme-secondary hover:text-theme-primary inline-flex items-center gap-1.5 disabled:opacity-50"
              >
                {tConfig("modeToggle.needCustomOAuth")}
                <span aria-hidden>→</span>
              </button>
            )}

            <DialogFooter className="gap-3 sm:gap-3">
              {/* Documentation Link */}
              {template.documentation_url ? (
                <a
                  href={template.documentation_url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1.5 text-sm text-blue-500 hover:text-blue-600 mr-auto -ml-1"
                >
                  <ExternalLink className="h-3.5 w-3.5" />
                  {tConfig("documentation")}
                </a>
              ) : (
                <div className="mr-auto" />
              )}

              <Button type="button" variant="ghost" className="h-10 px-5" onClick={() => onOpenChange(false)} disabled={isSubmitting}>
                {tConfig("cancel")}
              </Button>

              <Button onClick={handleConnect} disabled={isSubmitting} className="h-10 px-6">
                {isSubmitting ? (
                  <>
                    <LoadingSpinner size="xs" className="mr-2" />
                    {isOAuth2 ? tConfig("connecting") : t("saving")}
                  </>
                ) : (
                  isOAuth2 ? tConfig("connect") : t("save")
                )}
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

// ============================================
// Helpers
// ============================================

function extractIconSlug(template: CredentialTemplate): string {
  if (template.icon_url) {
    const match = template.icon_url.match(/\/([^/]+)\.svg$/);
    if (match) return match[1];
  }
  return (template.credential_name || template.display_name || "")
    .toLowerCase()
    .replace(/\s+/g, "");
}
