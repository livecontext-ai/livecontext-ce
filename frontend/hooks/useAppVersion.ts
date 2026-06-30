'use client';

import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/lib/api/api-client';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';

/**
 * Running build, as returned by the backend {@code GET /api/version}. Mirrors the
 * Java {@code VersionInfo} record. The version is surfaced in the CE (self-hosted)
 * edition ONLY, so the query is CE-gated: a cloud build never calls the endpoint
 * and every consumer renders nothing.
 */
export interface AppVersionInfo {
  /** Build version baked at build time, or "dev" when git info was unavailable. */
  version: string;
  /** Short commit sha of the build, or null when unknown. */
  gitSha: string | null;
  /** ISO-8601 UTC build timestamp, or null when unknown. Format in the app locale. */
  buildTime: string | null;
  /** Canonical edition token: ce | self-hosted-enterprise | cloud | dedicated-cloud. */
  edition: string;
  /** True for editions the user runs themselves (CE / self-hosted enterprise). */
  selfHosted: boolean;
  /** True for managed cloud editions (updates handled for the user). */
  managedCloud: boolean;
  /** True when a newer release exists for this self-hosted install. */
  updateAvailable: boolean;
  /** Latest-known published version (shown even when up to date), or null. */
  latestVersion: string | null;
  /** Release-notes link for the latest version, or null. */
  releaseUrl: string | null;
  /** Whether the available update is a security fix (false when no update). */
  securityFix: boolean;
  /** ISO-8601 timestamp of the last successful update-feed check, or null. */
  checkedAt: string | null;
}

export interface UseAppVersionResult {
  /** Version payload, or null while loading / on error / before auth resolves. */
  version: AppVersionInfo | null;
  isLoading: boolean;
  isError: boolean;
}

/**
 * Fetches the running build + edition once, cached for an hour (it only changes
 * on redeploy). Authenticated endpoint, so it stays disabled until auth resolves.
 */
export function useAppVersion(): UseAppVersionResult {
  const { isLoading: isAuthLoading, isAuthenticated } = useAuth();
  // CE only: cloud never fetches the version, so the card / About entry stay empty.
  const enabled = IS_CE && !isAuthLoading && isAuthenticated;

  const { data, isPending, isError } = useQuery({
    queryKey: ['app-version'],
    queryFn: () => apiClient.get<AppVersionInfo>('/version'),
    enabled,
    staleTime: 60 * 60 * 1000, // 1h - version only changes on redeploy
    retry: false,
  });

  return {
    version: data ?? null,
    // Include isAuthLoading so the consumer shows a loading state (not the error
    // fallback) while auth is still resolving and the query is still disabled -
    // a disabled React Query reports isPending=true. Mirrors useCeCloudLinkStatus.
    isLoading: isAuthLoading || (enabled && isPending),
    isError,
  };
}
