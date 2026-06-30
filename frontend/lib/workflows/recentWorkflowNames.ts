/**
 * Short-lived in-memory cache of names for workflows the user just created.
 *
 * Why this exists: the create modal redirects straight into /app/workflow/{id}
 * the instant the save POST resolves. The breadcrumb then resolves the title via
 * `getWorkflow(id)`, but that round-trip can transiently fail right after creation
 * - and the breadcrumb fetches exactly once (its effect deps don't change), so the
 * "Workflow {uuid}" fallback then sticks. The creator already typed the name, so
 * we remember it and let the breadcrumb show it immediately, independent of the
 * fetch. Navigating from a card never primes the cache, so that path is unchanged.
 *
 * In-memory (module-scoped) is intentional: the value is only needed for the
 * very next render after the redirect, within the same SPA session. Entries are
 * dropped (via {@link forgetWorkflowName}) once an authoritative server name is
 * read or the workflow is renamed, so a prime is consumed exactly once and can
 * never resurface as a stale name.
 */
const names = new Map<string, string>();

/** Remember the name a user just gave a newly-created workflow. */
export function rememberWorkflowName(id: string, name: string): void {
  if (id && name) names.set(id, name);
}

/** Recall a remembered name, or undefined when none was primed (e.g. card navigation). */
export function recallWorkflowName(id: string | null | undefined): string | undefined {
  return id ? names.get(id) : undefined;
}

/**
 * Drop a primed name. Call once the authoritative server name is known (the prime
 * has served its purpose) or when the workflow is renamed (the prime is now stale).
 */
export function forgetWorkflowName(id: string | null | undefined): void {
  if (id) names.delete(id);
}
