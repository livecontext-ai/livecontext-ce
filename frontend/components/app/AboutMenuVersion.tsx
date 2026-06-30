'use client';

import type { AppVersionInfo } from '@/hooks/useAppVersion';

/**
 * Trailing meta for the user-menu "About" entry: the running build version, plus
 * an amber update dot when a self-hosted install is behind (mirrors the Settings
 * nav "Information" badge). Shown in the CE edition only (the useAppVersion hook is
 * CE-gated, so cloud passes a null version here and nothing renders); the dot only
 * appears for a self-hosted install with an available update.
 */
export default function AboutMenuVersion({ version }: { version: AppVersionInfo | null }) {
  if (!version?.version) return null;

  return (
    <span className="flex items-center gap-1.5 text-xs text-theme-muted">
      {version.version}
      {version.selfHosted && version.updateAvailable && (
        <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500" aria-hidden="true" />
      )}
    </span>
  );
}
