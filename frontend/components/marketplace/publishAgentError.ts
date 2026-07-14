/**
 * Parser for publication-service's structured 422 publish-agent refusals.
 *
 * The backend returns `{error, message, ...details}`; `apiClient` surfaces it as
 * `ApiError` with `code` = the stable error code and `details` = the full body.
 * This module narrows that into a typed union the modal can render without ever
 * showing raw JSON to the user.
 */

export interface AllAccessViolation {
  agentId: string;
  agentName: string;
  root: boolean;
  /** Chain of agent names from the published root down to the referencing parent. */
  referencedVia?: string[];
  families: string[];
}

export interface TooLargeBreakdownEntry {
  type: string;
  id?: string;
  name?: string;
  items?: number;
  approxBytes?: number;
}

export type ParsedPublishAgentError =
  | { kind: 'allAccess'; violations: AllAccessViolation[] }
  | {
      kind: 'tooLarge';
      sizeBytes?: number;
      maxBytes?: number;
      maxTableRows?: number;
      breakdown: TooLargeBreakdownEntry[];
    }
  | { kind: 'generic'; message: string };

export function parsePublishAgentError(err: unknown, fallbackMessage: string): ParsedPublishAgentError {
  const anyErr = err as { code?: string; message?: string; details?: Record<string, unknown> } | null;
  const details = anyErr?.details as Record<string, unknown> | undefined;
  const code = anyErr?.code ?? (details?.error as string | undefined);

  if (code === 'AGENT_ALL_ACCESS_NOT_PUBLISHABLE' && Array.isArray(details?.violations)) {
    const violations: AllAccessViolation[] = (details!.violations as unknown[])
      .filter((v): v is Record<string, unknown> => !!v && typeof v === 'object')
      .map((v) => ({
        agentId: String(v.agentId ?? ''),
        agentName: String(v.agentName ?? v.agentId ?? ''),
        root: v.root === true,
        referencedVia: Array.isArray(v.referencedVia) ? v.referencedVia.map(String) : undefined,
        families: Array.isArray(v.families) ? v.families.map(String) : [],
      }))
      .filter((v) => v.families.length > 0);
    if (violations.length > 0) {
      return { kind: 'allAccess', violations };
    }
  }

  if (code === 'AGENT_SNAPSHOT_TOO_LARGE') {
    const breakdown: TooLargeBreakdownEntry[] = Array.isArray(details?.breakdown)
      ? (details!.breakdown as unknown[])
          .filter((b): b is Record<string, unknown> => !!b && typeof b === 'object')
          .map((b) => ({
            type: String(b.type ?? ''),
            id: b.id != null ? String(b.id) : undefined,
            name: b.name != null ? String(b.name) : undefined,
            items: typeof b.items === 'number' ? b.items : undefined,
            approxBytes: typeof b.approxBytes === 'number' ? b.approxBytes : undefined,
          }))
      : [];
    return {
      kind: 'tooLarge',
      sizeBytes: typeof details?.sizeBytes === 'number' ? (details!.sizeBytes as number) : undefined,
      maxBytes: typeof details?.maxBytes === 'number' ? (details!.maxBytes as number) : undefined,
      maxTableRows: typeof details?.maxTableRows === 'number' ? (details!.maxTableRows as number) : undefined,
      breakdown,
    };
  }

  return { kind: 'generic', message: anyErr?.message || fallbackMessage };
}

export function bytesToMb(bytes: number): number {
  return Math.round((bytes / (1024 * 1024)) * 10) / 10;
}
