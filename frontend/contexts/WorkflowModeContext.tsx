'use client';

import React, { createContext, useContext, useState, useEffect, useCallback, useMemo, useRef, ReactNode } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { orchestratorApi } from '@/lib/api';
import { usePendingInterfacesStore } from '@/lib/stores/pending-interfaces-store';
import { useInterfacePaginationStore } from '@/lib/stores/interface-pagination-store';
import { VIEWING_EPOCH_EVENT, shouldAdoptEpochEvent, type EpochEventDetail } from '@/lib/workflow/epochEventScope';

/**
 * Mode du workflow : 'edit' (édition) ou 'run' (exécution)
 */
export type WorkflowMode = 'edit' | 'run';

interface WorkflowModeContextType {
  mode: WorkflowMode;
  isRunMode: boolean;
  isEditMode: boolean;
  isApplicationMode: boolean;
  /** Preview-only mode: no execution/trigger interaction allowed (marketplace preview ONLY). */
  isPreviewOnly: boolean;
  /** Workflow ID for the current workflow (optional, set by layout) */
  workflowId?: string;
  runId: string | null;
  setRunId: (runId: string | null) => void;
  /** Epoch being viewed on canvas (null = live mode, shows all data) */
  viewingEpoch: number | null;
  setViewingEpoch: (epoch: number | null) => void;
  /**
   * Pinned version of the current workflow (null if unpinned). Fetched once
   * when the provider mounts with a workflowId and kept in sync with pin/unpin
   * actions via the `workflowPinnedVersionChange` window event.
   */
  pinnedVersion: number | null;
  /** Latest saved version (HEAD) of the workflow plan. Null while loading. */
  currentVersion: number | null;
  /**
   * Version actually loaded on the canvas. Equals `currentVersion` on a fresh
   * edit, but diverges when the user restores an older version from the
   * history dropdown. Sourced from the `workflowActiveVersionChange` event.
   */
  activeVersion: number | null;
  /** Tracks the on-canvas plan's dirty state (workflowDirtyChange event). */
  workflowDirty: boolean;
  /** True when the current run targets a pinned workflow - its plan is immutable. */
  isPinnedWorkflow: boolean;
}

const WorkflowModeContext = createContext<WorkflowModeContextType | null>(null);

let lastObservedRunIdForInterfaceStores: string | null = null;

interface WorkflowModeProviderProps {
  children: ReactNode;
  /** Workflow ID passed from the layout (available to all descendants via useWorkflowMode) */
  workflowId?: string;
  /** Pre-set runId for contexts where the URL doesn't contain /run/ (e.g. marketplace preview) */
  initialRunId?: string;
  /** Preview-only mode: disables trigger interaction (e.g. marketplace preview) */
  readOnly?: boolean;
}

/**
 * Provider pour gérer le mode workflow (run vs édition)
 * Détecte automatiquement le mode depuis l'URL :
 * - Mode 'run' si l'URL contient /run/ OR if initialRunId is provided
 * - Mode 'edit' sinon
 */
export function WorkflowModeProvider({ children, workflowId, initialRunId, readOnly = false }: WorkflowModeProviderProps) {
  const pathname = usePathname();
  const router = useRouter();
  const [mode, setMode] = useState<WorkflowMode>(initialRunId ? 'run' : 'edit');
  const [runId, setRunIdState] = useState<string | null>(initialRunId || null);
  const [viewingEpoch, setViewingEpochRaw] = useState<number | null>(null);
  // Guard to prevent re-dispatch loops when multiple providers listen
  const isLocalDispatchRef = useRef(false);
  const setViewingEpoch = useCallback((epoch: number | null) => {
    setViewingEpochRaw(epoch);
    // Broadcast to cross-tree consumers (e.g. SidePanel's ApplicationTabContent
    // which lives in a separate WorkflowModeProvider). The event is scoped by
    // runId so that several apps mounted at once (keepMounted side-panel tabs)
    // don't slave their epoch selectors to each other - a listener only adopts
    // an epoch broadcast for its OWN run.
    if (typeof window !== 'undefined') {
      isLocalDispatchRef.current = true;
      const detail: EpochEventDetail = { epoch, runId };
      window.dispatchEvent(new CustomEvent(VIEWING_EPOCH_EVENT, { detail }));
      // Use microtask to reset after all sync listeners fire
      Promise.resolve().then(() => { isLocalDispatchRef.current = false; });
    }
  }, [runId]);

  // Listen for external epoch changes (from other WorkflowModeProvider instances).
  // Ignore changes meant for a different run (multi-app side panel): each app
  // tab runs in its own provider with its own runId.
  useEffect(() => {
    const handler = (e: Event) => {
      if (isLocalDispatchRef.current) return;
      const detail = (e as CustomEvent).detail as EpochEventDetail | undefined;
      if (!shouldAdoptEpochEvent(detail?.runId, runId)) return;
      setViewingEpochRaw(detail?.epoch ?? null);
    };
    window.addEventListener(VIEWING_EPOCH_EVENT, handler);
    return () => window.removeEventListener(VIEWING_EPOCH_EVENT, handler);
  }, [runId]);
  const [isApplicationMode, setIsApplicationMode] = useState<boolean>(() => {
    if (typeof window !== 'undefined') {
      return window.location.pathname.includes('/applications/');
    }
    return false;
  });
  const [pinnedVersion, setPinnedVersion] = useState<number | null>(null);
  const [currentVersion, setCurrentVersion] = useState<number | null>(null);
  const [activeVersion, setActiveVersion] = useState<number | null>(null);
  const [workflowDirty, setWorkflowDirty] = useState(false);

  // Fetch versions once per workflowId - drives the pin button on every
  // trigger node + "run on pinned workflow" readonly behavior in descendants
  // (InspectorPanel, etc.). Lives at the provider level so it survives node
  // unmount/remount when restoring versions with different node ids.
  useEffect(() => {
    if (!workflowId) {
      setPinnedVersion(null);
      setCurrentVersion(null);
      setActiveVersion(null);
      return;
    }
    let cancelled = false;
    orchestratorApi.listVersions(workflowId)
      .then((data) => {
        if (cancelled) return;
        setPinnedVersion(data?.pinnedVersion ?? null);
        setCurrentVersion(data?.currentVersion ?? null);
        setActiveVersion((prev) => prev ?? data?.currentVersion ?? null);
      })
      .catch(() => {
        if (cancelled) return;
        setPinnedVersion(null);
        setCurrentVersion(null);
      });
    return () => { cancelled = true; };
  }, [workflowId]);

  // Stay in sync with version-related events dispatched from
  // WorkflowVersionHistory / useDirtyState / BuilderCanvas.
  useEffect(() => {
    const onPin = (e: Event) => {
      setPinnedVersion((e as CustomEvent).detail?.pinnedVersion ?? null);
    };
    const onActive = (e: Event) => {
      const v = (e as CustomEvent).detail?.version;
      setActiveVersion(typeof v === 'number' ? v : null);
    };
    const onDirty = (e: Event) => {
      setWorkflowDirty(!!(e as CustomEvent).detail?.isDirty);
    };
    const onSaveComplete = async (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (!detail?.success || !workflowId) return;
      try {
        const data = await orchestratorApi.listVersions(workflowId);
        setCurrentVersion(data.currentVersion ?? null);
        setPinnedVersion(data.pinnedVersion ?? null);
        // A save loads the new HEAD into the canvas → realign activeVersion.
        setActiveVersion(data.currentVersion ?? null);
      } catch {
        // Silent: stale state will self-correct on the next pin/save.
      }
    };
    window.addEventListener('workflowPinnedVersionChange', onPin);
    window.addEventListener('workflowActiveVersionChange', onActive);
    window.addEventListener('workflowDirtyChange', onDirty);
    window.addEventListener('workflowViewSaveComplete', onSaveComplete);
    return () => {
      window.removeEventListener('workflowPinnedVersionChange', onPin);
      window.removeEventListener('workflowActiveVersionChange', onActive);
      window.removeEventListener('workflowDirtyChange', onDirty);
      window.removeEventListener('workflowViewSaveComplete', onSaveComplete);
    };
  }, [workflowId]);
  // Track if runId was set programmatically (via initialRunId or setRunId) to avoid URL-based override
  const isProgrammaticRef = useRef(!!initialRunId);
  // Track previous runId to detect run-to-run switches
  const prevRunIdRef = useRef<string | null>(runId);

  useEffect(() => {
    if (typeof window !== 'undefined' && pathname) {
      console.log('[WorkflowModeContext] pathname changed:', pathname);

      // When readOnly is set (e.g. marketplace preview), mode is locked - never override from URL
      if (readOnly) {
        console.log('[WorkflowModeContext] preview mode, skipping URL-based mode detection');
        return;
      }

      // Détecter si on est en mode run : l'URL contient /run/
      const isRunMode = pathname.includes('/run/');
      // Detect application mode: URL contains /applications/
      const isAppMode = pathname.includes('/applications/');
      console.log('[WorkflowModeContext] isRunMode:', isRunMode, 'isApplicationMode:', isAppMode, 'current mode:', mode);

      // Skip URL-based override if mode was set programmatically (e.g. initialRunId)
      if (isProgrammaticRef.current) {
        return;
      }

      setMode(isRunMode ? 'run' : 'edit');
      setIsApplicationMode(isAppMode);

      // Extraire le runId de l'URL si en mode run
      if (isRunMode) {
        const match = pathname.match(/\/run\/([^/]+)/);
        if (match) {
          const extractedRunId = match[1];
          console.log('[WorkflowModeContext] Extracted runId from URL:', extractedRunId);
          setRunIdState(extractedRunId);
        }
      } else {
        console.log('[WorkflowModeContext] Not in run mode, clearing runId');
        setRunIdState(null);
      }
    }
    // IMPORTANT: Ne pas inclure 'mode' dans les dépendances car il est mis à jour par cet effet
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname, readOnly]);

  // Clear interface-related stores when switching between different runs.
  // The local ref catches run switches within one mounted provider. The module
  // value catches route/provider remounts that start directly in run mode
  // (application pages pass initialRunId, so their local prev ref is already
  // initialized to the new run on mount).
  useEffect(() => {
    const prevRunId = prevRunIdRef.current;
    prevRunIdRef.current = runId;
    const previousObservedRunId = lastObservedRunIdForInterfaceStores;
    if (runId) {
      lastObservedRunIdForInterfaceStores = runId;
    }

    const localRunSwitch = !!prevRunId && !!runId && prevRunId !== runId;
    const providerRunSwitch = !!previousObservedRunId && !!runId && previousObservedRunId !== runId;

    // Only clear when switching from one concrete run to another run (not run->edit).
    if (localRunSwitch || providerRunSwitch) {
      const fromRunId = localRunSwitch ? prevRunId : previousObservedRunId;
      console.log('[WorkflowModeContext] Run switch detected:', fromRunId, '->', runId, '- clearing interface stores');
      usePendingInterfacesStore.getState().clear();
      useInterfacePaginationStore.getState().clear();
    }
  }, [runId]);

  const setRunId = useCallback((id: string | null) => {
    // In preview mode (marketplace preview), don't allow clearing runId - mode is locked
    if (readOnly && !id) {
      return;
    }
    isProgrammaticRef.current = !!id;
    setRunIdState(id);
    if (id) {
      setMode('run');
    } else {
      setMode('edit');
    }
  }, [readOnly]);

  const value = useMemo<WorkflowModeContextType>(() => ({
    mode,
    isRunMode: mode === 'run',
    isEditMode: mode === 'edit',
    isApplicationMode,
    isPreviewOnly: readOnly,
    workflowId,
    runId,
    setRunId,
    viewingEpoch,
    setViewingEpoch,
    pinnedVersion,
    currentVersion,
    activeVersion,
    workflowDirty,
    isPinnedWorkflow: pinnedVersion != null,
  }), [mode, isApplicationMode, readOnly, workflowId, runId, setRunId, viewingEpoch, pinnedVersion, currentVersion, activeVersion, workflowDirty]);

  return (
    <WorkflowModeContext.Provider value={value}>
      {children}
    </WorkflowModeContext.Provider>
  );
}

/**
 * Hook pour utiliser le contexte WorkflowMode
 */
export function useWorkflowMode() {
  const context = useContext(WorkflowModeContext);
  if (!context) {
    // Retourner des valeurs par défaut si le contexte n'est pas disponible
    // Cela permet d'utiliser le hook même en dehors du provider
    return {
      mode: 'edit' as WorkflowMode,
      isRunMode: false,
      isEditMode: true,
      isApplicationMode: false,
      isPreviewOnly: false,
      workflowId: undefined,
      runId: null,
      setRunId: () => {},
      viewingEpoch: null,
      setViewingEpoch: () => {},
      pinnedVersion: null,
      currentVersion: null,
      activeVersion: null,
      workflowDirty: false,
      isPinnedWorkflow: false,
    };
  }
  return context;
}
