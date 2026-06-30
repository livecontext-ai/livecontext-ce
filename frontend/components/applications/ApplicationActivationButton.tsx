'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2 } from 'lucide-react';
import { Switch } from '@/components/ui/switch';
import { versionService } from '@/lib/api/orchestrator/version.service';

interface ApplicationActivationButtonProps {
  /** The workflow id underlying this application (applications ARE workflows). */
  workflowId: string;
  /**
   * Initial pinned version, if known. When `undefined` the component fetches
   * it on mount via {@link versionService.listVersions}. Pass the value if you
   * already have it from a parent fetch to skip the round-trip.
   */
  initialPinnedVersion?: number | null;
  /** Optional callback fired after a successful pin/unpin. */
  onChange?: (newPinnedVersion: number | null) => void;
}

/**
 * Activer / Désactiver toggle for applications.
 *
 * <p>Applications are single-run by contract (design v3.5 §L1+§L3): the user
 * does not pick a version, they just toggle the live instance on/off. ON pins
 * the latest plan version (so triggers fire); OFF unpins (so triggers stop).
 * Pin/unpin reuses the existing {@link versionService.pinVersion} endpoint -
 * no new backend route. The version dropdown surfaced for regular workflows
 * is intentionally hidden here to match the single-run UX.
 *
 * <p>Rendered as a {@link Switch} (toggle) with an inline status label
 * ("Actif" / "Inactif"). Toggling ON fetches the latest version and pins it;
 * toggling OFF calls {@code pinVersion(workflowId, null)}.
 */
export function ApplicationActivationButton({
  workflowId,
  initialPinnedVersion,
  onChange,
}: ApplicationActivationButtonProps) {
  const t = useTranslations('applications');
  const [pinnedVersion, setPinnedVersion] = useState<number | null | undefined>(initialPinnedVersion);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isActive = pinnedVersion != null;

  // Fetch the workflow's current pin state if the parent didn't supply it.
  useEffect(() => {
    if (initialPinnedVersion !== undefined) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await versionService.listVersions(workflowId);
        if (cancelled) return;
        // listVersions response shape: { workflowId, currentVersion, pinnedVersion?, versions: […] }
        // Tolerate either field name (current API uses `pinnedVersion`).
        const pinned = (res as { pinnedVersion?: number | null }).pinnedVersion ?? null;
        setPinnedVersion(pinned);
      } catch {
        if (!cancelled) setPinnedVersion(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workflowId, initialPinnedVersion]);

  const onActivate = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      // Activate = pin the LATEST version (single-run apps don't need a version picker).
      const versions = await versionService.listVersions(workflowId);
      const latest =
        (versions as { currentVersion?: number }).currentVersion ??
        // Fall back to max version number from the list if currentVersion is absent.
        Math.max(
          0,
          ...((versions as { versions?: Array<{ version: number }> }).versions ?? []).map((v) => v.version),
        );
      if (!latest || latest < 1) {
        setError(t('activationNoVersion'));
        return;
      }
      const res = await versionService.pinVersion(workflowId, latest);
      setPinnedVersion(res.pinnedVersion);
      onChange?.(res.pinnedVersion);
    } catch (e) {
      setError(e instanceof Error ? e.message : t('activationFailed'));
    } finally {
      setBusy(false);
    }
  }, [workflowId, onChange, t]);

  const onDeactivate = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await versionService.pinVersion(workflowId, null);
      setPinnedVersion(res.pinnedVersion); // null
      onChange?.(res.pinnedVersion);
    } catch (e) {
      setError(e instanceof Error ? e.message : t('deactivationFailed'));
    } finally {
      setBusy(false);
    }
  }, [workflowId, onChange, t]);

  // Pre-mount fetch in flight: render a placeholder so the UI doesn't flicker.
  if (pinnedVersion === undefined) {
    return (
      <div className="flex items-center gap-2">
        <Loader2 className="h-3.5 w-3.5 animate-spin text-theme-secondary" aria-hidden="true" />
        <span className="text-sm text-theme-secondary">{t('activationLoading')}</span>
      </div>
    );
  }

  const onToggle = (next: boolean) => {
    if (busy) return;
    if (next) onActivate();
    else onDeactivate();
  };

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <span
          className={`text-xs font-medium ${
            isActive
              ? 'text-emerald-700 dark:text-emerald-300'
              : 'text-theme-secondary'
          }`}
          aria-live="polite"
        >
          {/* "Live" matches the wording used elsewhere for pinned workflows
              (WorkflowTable: workflow.live, WorkflowBoardCard: status.live).
              Off = not pinned (production triggers refused). */}
          {isActive ? t('live') : t('off')}
        </span>
        {/* Switch stays mounted (preserves role=switch + the disabled-while-busy
            guard). Loader2 is rendered AFTER the Switch when busy so the Switch
            position never shifts - only the row grows on the right edge.
            size="md" (h-8) matches the chat-header Logs button height so the
            toggle reads as a peer control. */}
        <Switch
          checked={isActive}
          onCheckedChange={onToggle}
          disabled={busy}
          size="md"
          aria-label={isActive ? t('deactivate') : t('activate')}
        />
        {busy && (
          <Loader2 className="h-3.5 w-3.5 animate-spin text-theme-secondary" aria-hidden="true" />
        )}
      </div>

      {error && (
        <p className="text-xs text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
