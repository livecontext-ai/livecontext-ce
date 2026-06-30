/**
 * Shared credential matching logic.
 *
 * Single source of truth used by:
 *  - CredentialSection (inspector dropdown / "Configured" badge)
 *  - useCredentialCheck (chat / service approval cards)
 *  - Any future credential-aware UI
 *
 * Keeping matching centralized avoids the bug where the inspector marked a
 * step "Configured" while the workflow validator still warned
 * "requires service credential (not connected)" because each call site
 * re-implemented its own heuristic.
 */

import type { Credential } from '@/lib/api/orchestrator';
import { normalizeIconSlug } from '@/lib/credentials/iconSlug';

export interface ToolCredentialRequirement {
  credentialName?: string;
  displayName?: string;
}

/**
 * Return every user credential that could satisfy a tool's credential
 * requirement for a given integration.
 *
 * Exact matching strategies (any match wins):
 *  1. userCred.integration === integration
 *  2. userCred.integration === toolCred.credentialName
 *  3. userCred.name === toolCred.credentialName
 *  4. userCred.integration === toolCred.displayName
 */
export function matchUserCredentialsForTool(
  userCredentials: readonly Credential[] | undefined,
  toolCred: ToolCredentialRequirement | undefined,
  integration: string | undefined
): Credential[] {
  if (!userCredentials || userCredentials.length === 0) return [];

  const toolCredName = toolCred?.credentialName?.toLowerCase() ?? '';
  const toolDisplayName = toolCred?.displayName?.toLowerCase() ?? '';
  const integrationLower = integration?.toLowerCase() ?? '';

  if (!toolCredName && !toolDisplayName && !integrationLower) return [];

  return userCredentials.filter((userCred) => {
    const userIntegration = userCred.integration?.toLowerCase() ?? '';
    const userName = userCred.name?.toLowerCase() ?? '';

    if (integrationLower && userIntegration === integrationLower) return true;

    if (toolCredName && userIntegration === toolCredName) return true;

    if (toolCredName && userName === toolCredName) return true;

    if (toolDisplayName && userIntegration === toolDisplayName) return true;

    return false;
  });
}

/**
 * Best single match for an integration. Prefers the user's default credential
 * when multiple candidates exist.
 */
export function findBestUserCredential(
  userCredentials: readonly Credential[] | undefined,
  integration: string | undefined,
  toolCred?: ToolCredentialRequirement
): Credential | null {
  const matches = matchUserCredentialsForTool(userCredentials, toolCred, integration);
  if (matches.length === 0) return null;
  const preferred = matches.find((c) => c.is_default);
  return preferred ?? matches[0];
}

/**
 * Does the user have at least one credential usable for this integration?
 * Uses the same exact-match helper as the inspector dropdown so a credential
 * for one integration never satisfies another integration by substring.
 */
export function hasUserCredentialForIntegration(
  userCredentials: readonly Credential[] | undefined,
  integration: string | undefined
): boolean {
  return findBestUserCredential(userCredentials, integration) !== null;
}

/**
 * Strict exact-match on `integration`. Use when the iconSlug is canonical
 * and a false positive would be misleading, for example, chat UI deciding
 * whether to show a "Connect" button, or an OAuth approval prompt. Prevents
 * "googlecloud" from silently matching a generic "google" credential.
 *
 * Both sides go through `normalizeIconSlug` so the match still holds when the
 * tool surfaces an apiSlug shape ("google-gemini") and the user credential
 * was stored under the canonical icon_slug ("googlegemini"), the same
 * collapse the backend `IconSlugNormalizer.normalize` performs.
 */
export function hasExactIntegrationMatch(
  userCredentials: readonly Credential[] | undefined,
  iconSlug: string | undefined
): boolean {
  if (!iconSlug || !userCredentials || userCredentials.length === 0) return false;
  const slug = normalizeIconSlug(iconSlug);
  if (!slug) return false;
  return userCredentials.some((c) => normalizeIconSlug(c.integration) === slug);
}
