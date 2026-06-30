/**
 * Pure function detecting which credentials a workflow plan needs that the
 * current user has NOT configured.
 *
 * Used by:
 *  - The acquired-application "Setup required" UI (`useMissingCredentials`),
 *    which prompts the buyer to connect services BEFORE the first run fires.
 *  - `CredentialValidationRule` in the workflow builder (so both surfaces
 *    derive their warnings from the same source - no logic drift between
 *    the inspector badge and the application setup gate).
 *
 * <p>Coverage:
 * <ul>
 *   <li><b>MCP / agent tool steps</b> - needs `toolDataMap` (the resolved
 *       tool definitions with their `credentials[]` requirement list and
 *       `iconSlug`). Required for wizard prompts because every tool advertises
 *       its credential template by `iconSlug`.</li>
 *   <li><b>core:send_email / core:ssh / core:sftp / core:database</b> -
 *       fully detectable from the plan alone via the typed sub-blocks
 *       ({@code sendEmail.credentialId}, {@code ssh.credentialId}, …).</li>
 *   <li><b>core:http_request</b> - flagged when {@code authType ≠ 'none'}
 *       AND {@code authConfig} is missing/empty (publication acquire fully
 *       drops {@code authConfig}).</li>
 *   <li><b>core:crypto_jwt</b> - flagged when the operation needs key/secret
 *       and those fields are absent or stamped {@code "[redacted]"}.</li>
 *   <li><b>triggers (webhook)</b> - flagged when the auth type requires a
 *       password / secret and the corresponding field is absent.</li>
 * </ul>
 *
 * <p>The {@code "[redacted]"} literal handling is non-negotiable: the
 * publication-service recursive scrubber replaces sensitive values with that
 * literal string for any key matching {@code looksSensitive(...)}. A truthy
 * {@code "[redacted]"} value would otherwise satisfy the
 * {@code if (selectedCredentialId)} guards and silently mask a missing cred.
 *
 * <p>Strict integration matching: we use exact-match on iconSlug (after
 * normalization). This keeps application setup aligned with the inspector and
 * prevents a generic {@code "google"} credential from satisfying
 * {@code "googlecloud"}. The user can always
 * skip a service in the wizard.
 */
import type { Credential } from '@/lib/api/orchestrator';
import { hasExactIntegrationMatch } from '@/lib/credentials/credentialMatching';
import { normalizeIconSlug } from '@/lib/credentials/iconSlug';

// Plan-shape mirrors the (loose) JSONB stored on workflow_publications.plan_snapshot
// and orchestrator.workflows.plan. Typed loosely on purpose: published plans go
// through publication-service's scrubber which may add fields (`_snapshot_*` etc.)
// or strip nested ones we don't care about. Read defensively.
export type WorkflowPlanLike = {
  triggers?: Array<Record<string, any>>;
  mcps?: Array<Record<string, any>>;
  agents?: Array<Record<string, any>>;
  cores?: Array<Record<string, any>>;
  // ignored: tables, interfaces, notes, edges
  [key: string]: any;
};

export interface ToolDataLike {
  /** Stable service identifier - matches CredentialTemplate.icon_slug. */
  iconSlug?: string;
  apiSlug?: string;
  apiName?: string;
  /** Tool DB id (used by the wizard for template pre-fill). */
  toolId?: string;
  /** Each entry describes one credential the tool requires. */
  credentials?: Array<{
    credentialName?: string;
    displayName?: string;
    isRequired?: boolean;
  }>;
}

/**
 * One service the user needs to connect via the {@code CredentialWizard}.
 * Matches the wizard's {@code CredentialRequirement} shape.
 */
export interface WizardableMissingCredential {
  iconSlug: string;
  serviceName: string;
  toolId?: string;
  /** Plan node ids that surfaced this requirement (kept for telemetry/dedup). */
  sourceNodeIds: string[];
}

/**
 * Credentials that don't have a wizardable template (HTTP request auth, raw
 * JWT keys, webhook secrets). The user must open the workflow builder and
 * fill these in the inspector - we surface the list so they know where to
 * go, not just "something is missing".
 */
export interface ManualMissingCredential {
  nodeId: string;
  label: string;
  /** "http_request" | "crypto_jwt" | "trigger:webhook" - for UI grouping. */
  kind: string;
  reason: string;
}

export interface MissingCredentialsResult {
  wizardable: WizardableMissingCredential[];
  manual: ManualMissingCredential[];
}

const REDACTED = '[redacted]';

const isMissingValue = (v: unknown): boolean =>
  v == null || v === '' || v === REDACTED;

const isPresentValue = (v: unknown): boolean => !isMissingValue(v);

/**
 * Strict iconSlug match - single source of truth in
 * {@code lib/credentials/credentialMatching.ts:116}. False negatives on the
 * application setup gate ("connect Gmail" when the user already has a Gmail
 * cred) are worse than false positives, but a generic {@code "google"}
 * credential masquerading as {@code "googlecloud"} satisfaction is the
 * exact bug the strict variant prevents - same rationale the chat path
 * uses {@code hasExactIntegrationMatch}.
 */
const hasUserCredFor = hasExactIntegrationMatch;

function pushUnique(
  bucket: WizardableMissingCredential[],
  iconSlug: string,
  serviceName: string,
  toolId: string | undefined,
  nodeId: string
): void {
  const existing = bucket.find((b) => b.iconSlug === iconSlug);
  if (existing) {
    if (!existing.sourceNodeIds.includes(nodeId)) {
      existing.sourceNodeIds.push(nodeId);
    }
    if (!existing.toolId && toolId) existing.toolId = toolId;
    return;
  }
  bucket.push({
    iconSlug,
    serviceName,
    toolId,
    sourceNodeIds: [nodeId],
  });
}

function checkMcpStep(
  step: Record<string, any>,
  toolDataMap: Map<string, ToolDataLike> | undefined,
  userCreds: readonly Credential[] | undefined,
  out: WizardableMissingCredential[]
): void {
  const stepId = String(step?.id ?? '');
  const stepLabel = String(step?.label ?? stepId);
  if (!stepId) return;

  // Look up resolved tool data. Without it we can't know iconSlug nor whether
  // the tool actually needs credentials.
  const tool = toolDataMap?.get(stepId);
  if (!tool) return;

  const required = (tool.credentials ?? []).filter(
    (c) => c.isRequired !== false
  );
  if (required.length === 0) return;

  // Plan-level satisfaction signals - set by the inspector when the user picks
  // a personal credential or a platform credential. After acquisition both can
  // be either absent (cleanly stripped by safeRemove) OR `"[redacted]"` (caught
  // by the recursive scrubber). Treat both as missing.
  const params = (step.params ?? {}) as Record<string, any>;
  const selected =
    params.selectedCredentialId ??
    params.credentialId ??
    step.selectedCredentialId ??
    step.credentialId;
  if (isPresentValue(selected)) return;

  const platformId = params.platformCredentialId ?? step.platformCredentialId;
  const credentialSource = params.credentialSource ?? step.credentialSource;
  if (credentialSource === 'platform' && isPresentValue(platformId)) return;

  // Auto-pickable: user has at least one credential matching this tool's iconSlug.
  // Strict match prevents `google` from masquerading as `googlecloud`.
  // Tools that ship without an explicit iconSlug fall back to `apiSlug`, which
  // can carry hyphens ("google-gemini") while the credential template is
  // registered under the canonical, separator-free form ("googlegemini").
  // Collapse to the canonical shape here so downstream lookups (wizard
  // template fetch, hasUserCredFor) hit the same key the backend stores.
  const rawIconSlug = tool.iconSlug || tool.apiSlug;
  if (!rawIconSlug) return;
  const iconSlug = normalizeIconSlug(rawIconSlug);
  if (!iconSlug) return;
  if (hasUserCredFor(userCreds, iconSlug)) return;

  pushUnique(
    out,
    iconSlug,
    tool.apiName || iconSlug,
    tool.toolId,
    stepId
  );
}

function checkCoreNode(
  core: Record<string, any>,
  userCreds: readonly Credential[] | undefined,
  wizardable: WizardableMissingCredential[],
  manual: ManualMissingCredential[]
): void {
  const id = String(core?.id ?? '');
  const label = String(core?.label ?? id);
  const type = String(core?.type ?? '');

  // SMTP - wizardable under iconSlug "smtp"
  if (type === 'send_email') {
    const se = (core.sendEmail ?? {}) as Record<string, any>;
    // Acquisition strips `cores[].sendEmail.credentialId` AND username/password.
    // Treat presence of the parent block as evidence that the publisher
    // configured email - so missing cred is a real gap.
    const hasAnyConfig =
      isPresentValue(se.smtpHost) ||
      isPresentValue(se.fromEmail) ||
      isPresentValue(se.toEmail);
    if (!hasAnyConfig) return;
    if (isPresentValue(se.credentialId)) return;
    if (isPresentValue(se.smtpUsername) && isPresentValue(se.smtpPassword)) return;
    if (hasUserCredFor(userCreds, 'smtp')) return;
    pushUnique(wizardable, 'smtp', 'SMTP Email', undefined, id);
    return;
  }

  // IMAP - wizardable under iconSlug "imap"
  if (type === 'email_inbox') {
    const ei = (core.emailInbox ?? {}) as Record<string, any>;
    // Acquisition strips `cores[].emailInbox.credentialId`. Presence of the parent
    // block is evidence the publisher configured an inbox - so a missing cred is a real gap.
    const hasAnyConfig =
      isPresentValue(ei.folder) ||
      isPresentValue(ei.action) ||
      isPresentValue(ei.messageUid) ||
      core.emailInbox != null;
    if (!hasAnyConfig) return;
    if (isPresentValue(ei.credentialId)) return;
    if (hasUserCredFor(userCreds, 'imap')) return;
    pushUnique(wizardable, 'imap', 'IMAP Email', undefined, id);
    return;
  }

  if (type === 'ssh') {
    const ssh = (core.ssh ?? {}) as Record<string, any>;
    if (isPresentValue(ssh.credentialId)) return;
    // Inline username+password OR username+privateKey is also valid (rare).
    if (
      isPresentValue(ssh.username) &&
      (isPresentValue(ssh.password) || isPresentValue(ssh.privateKey))
    ) {
      return;
    }
    if (hasUserCredFor(userCreds, 'ssh')) return;
    pushUnique(wizardable, 'ssh', 'SSH', undefined, id);
    return;
  }

  if (type === 'sftp') {
    const sftp = (core.sftp ?? {}) as Record<string, any>;
    if (isPresentValue(sftp.credentialId)) return;
    if (
      isPresentValue(sftp.username) &&
      (isPresentValue(sftp.password) || isPresentValue(sftp.privateKey))
    ) {
      return;
    }
    if (hasUserCredFor(userCreds, 'sftp')) return;
    pushUnique(wizardable, 'sftp', 'SFTP', undefined, id);
    return;
  }

  if (type === 'database') {
    const db = (core.database ?? {}) as Record<string, any>;
    if (isPresentValue(db.credentialId)) return;
    if (
      isPresentValue(db.username) &&
      isPresentValue(db.password) &&
      isPresentValue(db.host)
    ) {
      return;
    }
    if (hasUserCredFor(userCreds, 'database')) return;
    pushUnique(wizardable, 'database', 'Database', undefined, id);
    return;
  }

  // HTTP request - not wizardable. Auth lives in `httpRequest.authConfig`
  // which is fully removed at acquisition for any non-none authType. No
  // credential template per `authType`, so the user must reconfigure inline.
  if (type === 'http_request') {
    const http = (core.httpRequest ?? {}) as Record<string, any>;
    const authType = String(http.authType ?? 'none');
    if (authType === 'none') return;
    const cfg = http.authConfig as Record<string, any> | null | undefined;
    const isCfgEmpty =
      !cfg ||
      Object.values(cfg).every(isMissingValue);
    if (!isCfgEmpty) return;
    manual.push({
      nodeId: id,
      label,
      kind: 'http_request',
      reason: `HTTP authentication (${authType}) was stripped on acquire`,
    });
    return;
  }

  // crypto_jwt sign/verify - manual setup. The publication scrubber redacts
  // `key`, `secret`, and `token`. The plan still carries the operation; we
  // only flag when the operation actually needs the missing field.
  if (type === 'crypto_jwt') {
    const jwt = (core.cryptoJwt ?? {}) as Record<string, any>;
    const op = String(jwt.operation ?? 'sign');
    if (op === 'sign' || op === 'create') {
      const needsKey = !isPresentValue(jwt.key) && !isPresentValue(jwt.secret);
      if (needsKey) {
        manual.push({
          nodeId: id,
          label,
          kind: 'crypto_jwt',
          reason: 'JWT signing key was stripped on acquire',
        });
      }
      return;
    }
    if (op === 'verify') {
      if (!isPresentValue(jwt.key) && !isPresentValue(jwt.secret)) {
        manual.push({
          nodeId: id,
          label,
          kind: 'crypto_jwt',
          reason: 'JWT verification key was stripped on acquire',
        });
      }
      return;
    }
  }
}

function checkTrigger(
  trigger: Record<string, any>,
  manual: ManualMissingCredential[]
): void {
  const id = String(trigger?.id ?? '');
  const label = String(trigger?.label ?? id);
  if (trigger?.type !== 'webhook') return;

  const params = (trigger.params ?? {}) as Record<string, any>;
  const authType = String(params.authType ?? 'none');
  if (authType === 'none' || authType === '') return;

  // Webhook trigger auth schema mirrors TriggerNodeCreator.ts:54-78. Each
  // authType has its OWN secret-bearing fields - they live as siblings of
  // `authType` under `params`, not nested under an auth object. The
  // publication scrubber strips them by key name (`basicPassword`,
  // `jwtSecretKey`, etc.), so detection is "the secret field is missing".
  // Required fields for each variant:
  const fieldByAuth: Record<string, string[]> = {
    basic: ['basicUsername', 'basicPassword'],
    header: ['authHeaderName', 'authHeaderValue'],
    jwt: ['jwtSecretKey'],
  };
  const fields = fieldByAuth[authType];
  if (!fields) return;
  const isMissing = fields.some((f) => !isPresentValue(params[f]));
  if (!isMissing) return;
  manual.push({
    nodeId: id,
    label,
    kind: 'trigger:webhook',
    reason: `Webhook authentication (${authType}) was stripped on acquire`,
  });
}

/**
 * Main entry - scan a workflow plan and return everything the user must do
 * before the workflow can run end-to-end.
 *
 * @param plan          Plan JSON (from {@code workflowService.getWorkflow}
 *                      or {@code publication.planSnapshot}).
 * @param toolDataMap   Resolved tool data by tool id (from
 *                      {@code ToolDataService.fetchToolsBatch}). Optional -
 *                      omit to skip MCP/agent coverage (cores + triggers
 *                      still scanned).
 * @param userCreds     Current user's credentials. Used to suppress
 *                      requirements the user already satisfies via strict
 *                      iconSlug match.
 */
export function extractMissingCredentialsFromPlan(
  plan: WorkflowPlanLike | null | undefined,
  toolDataMap: Map<string, ToolDataLike> | undefined,
  userCreds: readonly Credential[] | undefined
): MissingCredentialsResult {
  const wizardable: WizardableMissingCredential[] = [];
  const manual: ManualMissingCredential[] = [];
  if (!plan) return { wizardable, manual };

  for (const mcp of plan.mcps ?? []) {
    checkMcpStep(mcp, toolDataMap, userCreds, wizardable);
  }
  // Agents that bind tools also expose those tools' credential needs; the
  // tool ids live in `agent.tools[]` - scan each as if it were an MCP step.
  for (const agent of plan.agents ?? []) {
    const tools = (agent.tools ?? []) as string[];
    for (const ref of tools) {
      // Format: "mcp:label" - we synthesize a step-shape so checkMcpStep can
      // reuse the same lookup on the toolDataMap.
      const ghost = { id: ref, label: ref, params: {} };
      checkMcpStep(ghost, toolDataMap, userCreds, wizardable);
    }
  }
  for (const core of plan.cores ?? []) {
    checkCoreNode(core, userCreds, wizardable, manual);
  }
  for (const trigger of plan.triggers ?? []) {
    checkTrigger(trigger, manual);
  }

  return { wizardable, manual };
}
