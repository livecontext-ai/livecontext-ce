"use client";

import React, { useState, useEffect, useCallback, useRef } from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import { ChevronDown, Pencil, Check, X, Save, Pin, PinOff } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import LoadingSpinner from "@/components/LoadingSpinner";
import { orchestratorApi } from "@/lib/api";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";
import type { WorkflowPlanVersion } from "@/lib/api/orchestrator/types";
import { useTranslations } from "next-intl";

interface WorkflowSaveWithVersionsProps {
  workflowId: string;
  saveStatus: 'idle' | 'saving' | 'saved' | 'error';
  isDirty: boolean;
  onSave: () => void;
  /** true = desktop (shows text labels), false = mobile (icons only) */
  desktop?: boolean;
  /** Disable save while the agent is streaming */
  isAgentStreaming?: boolean;
  /** When true, the workflow is in run mode - reset to latest version on transition */
  isRunMode?: boolean;
}

export const WorkflowSaveWithVersions: React.FC<WorkflowSaveWithVersionsProps> = ({
  workflowId,
  saveStatus,
  isDirty,
  onSave,
  desktop = true,
  isAgentStreaming = false,
  isRunMode = false,
}) => {
  const t = useTranslations();
  const router = useRouter();
  const [showVersions, setShowVersions] = useState(false);
  const [versions, setVersions] = useState<WorkflowPlanVersion[]>([]);
  const [currentVersion, setCurrentVersion] = useState<number>(0);
  const [activeVersion, _setActiveVersion] = useState<number | null>(null);
  const activeVersionRef = useRef<number | null>(null);
  const setActiveVersion = (v: number | null) => {
    activeVersionRef.current = v;
    _setActiveVersion(v);
    if (v !== null) {
      window.dispatchEvent(new CustomEvent('workflowActiveVersionChange', { detail: { version: v } }));
    }
  };
  const [loading, setLoading] = useState(false);
  const [restoring, _setRestoring] = useState<number | null>(null);
  const restoringRef = useRef<number | null>(null);
  const setRestoring = (v: number | null) => { restoringRef.current = v; _setRestoring(v); };
  const [editingVersion, setEditingVersion] = useState<number | null>(null);
  const [editLabel, setEditLabel] = useState("");
  const [pinnedVersion, setPinnedVersion] = useState<number | null>(null);
  const [pinning, setPinning] = useState(false);
  const [pinConfirm, setPinConfirm] = useState<{ version: number | null; label: string } | null>(null);
  const [pinError, setPinError] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => { setMounted(true); return () => setMounted(false); }, []);

  const fetchVersions = useCallback(async () => {
    if (!workflowId) return;
    setLoading(true);
    try {
      const data = await orchestratorApi.listVersions(workflowId);
      setVersions(data.versions || []);
      setCurrentVersion(data.currentVersion || 0);
      setPinnedVersion(data.pinnedVersion ?? null);
      if (activeVersionRef.current === null) {
        setActiveVersion(data.currentVersion || 0);
      }
    } catch (err) {
      console.error("Failed to fetch versions:", err);
    } finally {
      setLoading(false);
    }
  }, [workflowId]);

  // Fetch versions when dropdown opens
  useEffect(() => {
    if (showVersions) {
      fetchVersions();
    }
  }, [showVersions, fetchVersions]);

  // Reset activeVersion when transitioning in/out of run mode
  // (run may auto-save a new version in the backend)
  const prevRunModeRef = useRef(isRunMode);
  useEffect(() => {
    if (prevRunModeRef.current !== isRunMode) {
      prevRunModeRef.current = isRunMode;
      setActiveVersion(null);
      if (showVersions) {
        fetchVersions();
      }
    }
  }, [isRunMode, showVersions, fetchVersions]);

  // Reset activeVersion after a manual save so it picks up the new latest on next open
  // Skip if we just did a restore (restore also triggers a save-like status change)
  // Also refetch versions immediately if dropdown is already open
  const lastSaveStatusRef = useRef(saveStatus);
  useEffect(() => {
    if (saveStatus === 'saved' && lastSaveStatusRef.current === 'saving') {
      setActiveVersion(null);
      if (showVersions) {
        fetchVersions();
      }
    }
    lastSaveStatusRef.current = saveStatus;
  }, [saveStatus, showVersions, fetchVersions]);

  // Keep our local pinnedVersion in sync when a pin/unpin happens elsewhere
  // (e.g. the trigger-node pin button on the canvas). Without this, the
  // dropdown's pin badge stays stale while it's open.
  useEffect(() => {
    const handler = (e: Event) => {
      setPinnedVersion((e as CustomEvent).detail?.pinnedVersion ?? null);
    };
    window.addEventListener('workflowPinnedVersionChange', handler);
    return () => window.removeEventListener('workflowPinnedVersionChange', handler);
  }, []);

  // Close on click outside (same pattern as model selector)
  useEffect(() => {
    if (!showVersions) return;

    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setShowVersions(false);
        setEditingVersion(null);
      }
    };

    const timeoutId = setTimeout(() => {
      document.addEventListener("mousedown", handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timeoutId);
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [showVersions]);

  const handleRestore = async (version: number) => {
    if (isDirty) {
      if (!confirm(t("versionHistory.unsavedChangesConfirm"))) return;
    }
    setRestoring(version);
    try {
      const result = await orchestratorApi.restoreVersion(workflowId, version);
      if (result.success && result.plan) {
        window.dispatchEvent(new CustomEvent('workflowPlanRestore', {
          detail: { plan: result.plan }
        }));
        setActiveVersion(version);
        setShowVersions(false);
      }
    } catch (err) {
      console.error("Failed to restore version:", err);
    } finally {
      setRestoring(null);
    }
  };

  const handleRename = async (version: number) => {
    try {
      await orchestratorApi.renameVersion(workflowId, version, editLabel || null);
      setEditingVersion(null);
      setEditLabel("");
      await fetchVersions();
    } catch (err) {
      console.error("Failed to rename version:", err);
    }
  };

  const requestPin = (version: number | null, label: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setPinConfirm({ version, label });
  };

  const confirmPin = async () => {
    if (pinConfirm === null) return;
    const version = pinConfirm.version;
    setPinConfirm(null);
    setPinning(true);
    try {
      const result = await orchestratorApi.pinVersion(workflowId, version);
      if (result.success) {
        setPinnedVersion(result.pinnedVersion);
        window.dispatchEvent(new CustomEvent('workflowPinnedVersionChange', {
          detail: { pinnedVersion: result.pinnedVersion }
        }));

        // Pinning a version (not unpinning) freezes that plan as production:
        // schedule/webhook/chat triggers start firing on it server-side and the
        // pinned plan is read-only at runtime. Land the user on the production
        // run so they can observe live execution - same router.push pattern as
        // WorkflowRunsHistoryPanel.handleRunClick. The pinned plan is independent
        // of any local draft edits the user may have on a newer version, so no
        // dirty-state confirmation is needed (consistent with handleRunClick).
        // The runId comes from the pin response (single round-trip, no extra
        // API call); the backend computes it via the same TRUSTED-status lookup
        // as getPinnedWorkflowRun. Falls through silently when the field is
        // null (unpin or no production run yet).
        if (result.productionRunIdPublic) {
          router.push(`/app/workflow/${workflowId}/run/${result.productionRunIdPublic}`);
        }
      }
    } catch (err: any) {
      console.error("Failed to pin version:", err);
      const msg = err?.message || t('versionHistory.pinError');
      setPinError(msg);
      setTimeout(() => setPinError(null), 5000);
    } finally {
      setPinning(false);
    }
  };

  const startEditing = (version: WorkflowPlanVersion, e: React.MouseEvent) => {
    e.stopPropagation();
    setEditingVersion(version.version);
    setEditLabel(version.label || "");
  };

  const cancelEditing = () => {
    setEditingVersion(null);
    setEditLabel("");
  };

  const formatDate = (dateStr: string) => {
    try {
      return formatUtcDateTime(dateStr);
    } catch {
      return dateStr;
    }
  };

  const isSaveDisabled = isRunMode || saveStatus === 'saving' || isAgentStreaming || (saveStatus === 'idle' && !isDirty);

  return (
    <div className="relative flex items-center" ref={containerRef}>
      <Button
        variant="ghost"
        size="sm"
        onClick={onSave}
        disabled={isSaveDisabled}
        title={t('actions.save')}
        className={`h-8 gap-2 ${desktop ? 'pl-2 pr-1.5 lg:pl-3' : 'pl-2 pr-1'} rounded-r-none ${
          saveStatus === 'saved' ? 'text-green-600 dark:text-green-400' :
          saveStatus === 'error' ? 'text-red-600 dark:text-red-400' : ''
        }`}
      >
        {saveStatus === 'saving' ? (
          <>
            <LoadingSpinner size="xs" />
            {desktop && <span className="hidden lg:inline">{t('common.saving')}</span>}
          </>
        ) : saveStatus === 'saved' ? (
          <>
            <Check className="w-4 h-4" />
            {desktop && <span className="hidden lg:inline">{t('common.saved')}</span>}
          </>
        ) : saveStatus === 'error' ? (
          <>
            <Save className="w-4 h-4" />
            {desktop && <span className="hidden lg:inline">{t('common.error')}</span>}
          </>
        ) : (
          <>
            <Save className="w-4 h-4" />
            {desktop && <span className="hidden lg:inline">{t('actions.save')}</span>}
          </>
        )}
      </Button>
      {/* Chevron always clickable, outside disabled button */}
      <button
        onClick={() => setShowVersions(!showVersions)}
        className="h-8 px-1 rounded-r-full hover:bg-accent hover:text-accent-foreground transition-colors"
        title={t('versionHistory.title')}
      >
        <ChevronDown className={`w-3 h-3 transition-transform duration-200 ${showVersions ? 'rotate-180' : ''}`} />
      </button>

      {/* Version dropdown - same style as model selector */}
      {showVersions && (
        <div className="absolute top-full right-0 mt-2 w-72 bg-theme-primary border border-theme rounded-2xl shadow-lg z-50 p-2">
          {/* Header */}
          <div className="px-3 py-2">
            <div className="text-sm font-medium text-theme-primary">{t('versionHistory.title')}</div>
          </div>

          {/* Version list */}
          <div className="space-y-1 max-h-72 overflow-y-auto pr-1">
            {loading ? (
              <div className="flex items-center justify-center py-6">
                <LoadingSpinner size="sm" />
              </div>
            ) : versions.length === 0 ? (
              <div className="py-6 text-center">
                <p className="text-sm text-theme-secondary">
                  {t('versionHistory.noVersions')}
                </p>
                <p className="text-xs text-theme-secondary mt-1">
                  {t('versionHistory.noVersionsDescription')}
                </p>
              </div>
            ) : (
              // Sort: pinned version first, then descending by version number
              [...versions].sort((a, b) => {
                if (pinnedVersion !== null) {
                  if (a.version === pinnedVersion) return -1;
                  if (b.version === pinnedVersion) return 1;
                }
                return b.version - a.version;
              }).map((version) => {
                const isCurrent = version.version === activeVersion;
                return (
                <div
                  key={version.version}
                  onClick={() => {
                    if (!isRunMode && editingVersion !== version.version && !isCurrent) handleRestore(version.version);
                  }}
                  className={`group flex items-center gap-3 px-3 py-2.5 rounded-xl transition-colors ${
                    isCurrent
                      ? 'bg-gray-100 dark:bg-gray-800'
                      : isRunMode
                        ? 'bg-transparent'
                        : 'cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-800'
                  } ${restoring === version.version ? 'opacity-50 pointer-events-none' : ''}`}
                >
                  {editingVersion === version.version ? (
                    <div className="flex items-center gap-1.5 w-full" onClick={(e) => e.stopPropagation()}>
                      <Input
                        value={editLabel}
                        onChange={(e) => setEditLabel(e.target.value)}
                        placeholder={t("versionHistory.renamePlaceholder")}
                        className="h-7 text-xs flex-1"
                        autoFocus
                        onKeyDown={(e) => {
                          if (e.key === "Enter") handleRename(version.version);
                          if (e.key === "Escape") cancelEditing();
                        }}
                      />
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6 text-green-600 hover:text-green-700 hover:bg-green-100 dark:text-green-400 dark:hover:text-green-300 dark:hover:bg-green-900/30"
                        onClick={() => handleRename(version.version)}
                      >
                        <Check className="w-3.5 h-3.5" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6 text-red-600 hover:text-red-700 hover:bg-red-100 dark:text-red-400 dark:hover:text-red-300 dark:hover:bg-red-900/30"
                        onClick={cancelEditing}
                      >
                        <X className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  ) : (
                    <>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-1.5">
                          <span className={`text-sm font-medium flex-shrink-0 ${
                            isCurrent ? 'text-theme-primary font-semibold' : 'text-theme-secondary'
                          }`}>
                            v{version.version}
                          </span>
                          {isCurrent && (
                            <span className="text-xs bg-emerald-100 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400 px-1.5 py-0.5 rounded-full">
                              {t('versionHistory.current')}
                            </span>
                          )}
                          {pinnedVersion === version.version && (
                            <span className="text-xs bg-amber-100 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400 px-1.5 py-0.5 rounded-full flex items-center gap-0.5">
                              <Pin className="w-2.5 h-2.5" />
                              {t('versionHistory.pinned')}
                            </span>
                          )}
                          {version.label && (
                            <span className="text-xs text-theme-secondary truncate">
                              {version.label}
                            </span>
                          )}
                        </div>
                        <p className="text-xs text-theme-secondary">
                          {formatDate(version.createdAt)}
                          {version.nodeCount != null && (
                            <span className="ml-1.5">&middot; {version.nodeCount} nodes</span>
                          )}
                          {version.runCount != null && version.runCount > 0 && (
                            <span className="ml-1.5">&middot; {version.runCount} {t('versionHistory.runs')}</span>
                          )}
                        </p>
                      </div>
                      {restoring === version.version ? (
                        <LoadingSpinner size="xs" className="flex-shrink-0" />
                      ) : (
                        <div className="flex items-center gap-0.5 flex-shrink-0">
                          <Button
                            variant="ghost"
                            size="sm"
                            className={`h-7 w-7 p-0 transition-opacity ${
                              pinnedVersion === version.version
                                ? 'opacity-100 text-amber-500 hover:text-amber-600 hover:bg-amber-100 dark:hover:bg-amber-900/30'
                                : 'opacity-0 group-hover:opacity-100'
                            }`}
                            onClick={(e) => {
                              const isUnpin = pinnedVersion === version.version;
                              requestPin(
                                isUnpin ? null : version.version,
                                `v${version.version}`,
                                e
                              );
                            }}
                            disabled={pinning}
                            title={pinnedVersion === version.version
                              ? t("versionHistory.unpin")
                              : t("versionHistory.pin")
                            }
                          >
                            {pinnedVersion === version.version
                              ? <PinOff className="w-3 h-3" />
                              : <Pin className="w-3 h-3" />
                            }
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-7 w-7 p-0 opacity-0 group-hover:opacity-100 transition-opacity"
                            onClick={(e) => startEditing(version, e)}
                            title={t("versionHistory.rename")}
                          >
                            <Pencil className="w-3 h-3" />
                          </Button>
                        </div>
                      )}
                    </>
                  )}
                </div>
                );
              })
            )}
          </div>
        </div>
      )}
      {/* Pin error toast - rendered as portal so it's visible even when dropdown is closed */}
      {mounted && pinError && createPortal(
        <div className="fixed top-4 right-4 z-[9999] max-w-sm animate-in fade-in-0 slide-in-from-top-2 duration-300">
          <div className="flex items-start gap-3 px-4 py-3 rounded-xl bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 shadow-lg">
            <X className="w-4 h-4 text-red-500 shrink-0 mt-0.5 cursor-pointer" onClick={() => setPinError(null)} />
            <p className="text-sm text-red-600 dark:text-red-400">{pinError}</p>
          </div>
        </div>,
        document.body
      )}

      {/* Pin confirmation modal - same style as CreateWorkflowModal */}
      {mounted && pinConfirm !== null && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setPinConfirm(null)}
        >
          <div
            className="max-w-sm w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            {pinConfirm.version !== null ? (
              /* ---- Pin modal: simple & clean ---- */
              <>
                <div className="text-center mb-6">
                  <div className="w-14 h-14 bg-amber-100 dark:bg-amber-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
                    <Pin className="w-7 h-7 text-amber-600 dark:text-amber-400" />
                  </div>
                  <h3 className="text-lg font-semibold text-theme-primary">
                    {t('versionHistory.pin')} - {pinConfirm.label}
                  </h3>
                  <p className="text-sm text-theme-secondary mt-2">
                    {t('versionHistory.pinConfirm')}
                  </p>
                </div>
                <div className="flex gap-3">
                  <Button variant="outline" onClick={() => setPinConfirm(null)} className="flex-1">
                    {t('common.cancel')}
                  </Button>
                  <Button onClick={confirmPin} className="flex-1">
                    {t('versionHistory.pin')}
                  </Button>
                </div>
              </>
            ) : (
              /* ---- Unpin modal: dissuasive ---- */
              <>
                <div className="text-center mb-6">
                  <div className="w-14 h-14 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
                    <PinOff className="w-7 h-7 text-red-600 dark:text-red-400" />
                  </div>
                  <h3 className="text-lg font-semibold text-theme-primary">
                    {t('versionHistory.unpin')} - {pinConfirm.label}
                  </h3>
                  <p className="text-sm text-theme-secondary mt-2">
                    {t('versionHistory.unpinConfirm')}
                  </p>
                  <div className="mt-3 p-3 bg-red-50 dark:bg-red-900/20 rounded-xl border border-red-200 dark:border-red-800">
                    <p className="text-xs text-red-600 dark:text-red-400">
                      {t('versionHistory.unpinWarning')}
                    </p>
                  </div>
                </div>
                <div className="flex gap-3">
                  <Button variant="outline" onClick={() => setPinConfirm(null)} className="flex-1">
                    {t('common.cancel')}
                  </Button>
                  <Button
                    onClick={confirmPin}
                    className="flex-1 bg-red-600 hover:bg-red-700 text-white"
                  >
                    {t('versionHistory.unpin')}
                  </Button>
                </div>
              </>
            )}
          </div>
        </div>,
        document.body
      )}
    </div>
  );
};
