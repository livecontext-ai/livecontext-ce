import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useRun } from '@/contexts/WorkflowRunContext';
import { type FilePanelTarget } from '@/lib/sidePanel/openFilesPanel';
import { normalizeFileRef, findFileRefs, isFileRef } from '@/lib/api/orchestrator/file.service';
import type { BuilderNodeData } from '../../types';
import { useRunOutputData } from '../../hooks/useRunOutputData';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { shouldFetchFileOutput } from './fileFetchPredicate';
import { FileResultStrip } from './FileResultStrip';

// ============================================================================
// File-producing Node Preview (canvas)
// Shared by download_file, convert_to_file, compression, sftp, media, and any
// tool (MCP) node whose run output carries a FileRef.
// Dedupes (epoch, itemIndex) → max(spawn) so the navigator surfaces a clean
// iteration axis instead of every retry.
// ============================================================================

export function FileNodePreview({
  data,
  setCurrentFile,
  selected = false,
  isStaticFileProducingNode,
}: {
  data: BuilderNodeData;
  setCurrentFile: React.Dispatch<React.SetStateAction<FilePanelTarget | null>>;
  selected?: boolean;
  isStaticFileProducingNode: boolean;
}) {
  const { isRunMode, workflowId, runId, viewingEpoch } = useWorkflowMode();
  const queryClient = useQueryClient();

  // Use normalized label (without prefix) to match DB step_alias.
  // DB stores normalizeLabel("Download File") = "download_file", NOT "core:download_file".
  const stepAlias = React.useMemo(
    () => normalizeLabel(data.label || ''),
    [data.label]
  );

  // Determine if node has completed execution
  const effectiveStatus = data.status;
  const isCompleted = effectiveStatus === 'completed';

  // Spawn item pagination - local to this node, resets when viewing epoch changes
  const [currentPage, setCurrentPage] = React.useState(0);
  React.useEffect(() => { setCurrentPage(0); }, [viewingEpoch]);

  // useRun MUST come before useRunOutputData because the gate predicate below
  // reads runState?.runStatus to decide whether to fetch. Hooks-order rule:
  // both unconditional, deterministic each render.
  const [runState] = useRun(isRunMode ? runId : undefined);

  // Fetch output data only in run mode when completed AND the gate predicate
  // says so. Predicate kills the prod 80-call mount storm: terminal-run
  // unselected MCP nodes don't fetch until clicked. Live runs + selected
  // node + static file types stay eager (see fileFetchPredicate.ts for the
  // full decision tree + rationale on FINISHED_STATUSES vs TERMINAL_STATUSES).
  // dedupeMaxSpawn collapses retries so the navigator scrolls a clean
  // (epoch, itemIndex) axis - same semantics as InterfaceRenderService.
  const { totalItems, currentIndex, currentItem, goToIndex, getObjectAtPath } = useRunOutputData({
    workflowId,
    runId: runId || undefined,
    stepAlias,
    epoch: viewingEpoch,
    enabled: shouldFetchFileOutput({
      isRunMode,
      isCompleted,
      selected,
      isStaticFileProducingNode,
      runStatus: runState?.runStatus,
    }),
    dedupeMaxSpawn: true,
  });
  const resolvedStepCount = React.useMemo(() => {
    if (!runState) return 0;
    const completed = runState.completedSteps?.size || 0;
    const failed = runState.failedSteps?.size || 0;
    const skipped = runState.skippedSteps?.size || 0;
    return completed + failed + skipped;
  }, [runState?.completedSteps?.size, runState?.failedSteps?.size, runState?.skippedSteps?.size]);

  const resolvedCountRef = React.useRef(resolvedStepCount);
  React.useEffect(() => {
    if (!isRunMode || !isCompleted) return;
    if (resolvedStepCount === 0 || resolvedStepCount === resolvedCountRef.current) return;
    resolvedCountRef.current = resolvedStepCount;

    const timeoutId = setTimeout(() => {
      queryClient.invalidateQueries({ queryKey: ['run-output-data'] });
    }, 1000);
    return () => clearTimeout(timeoutId);
  }, [isRunMode, isCompleted, resolvedStepCount, queryClient]);

  // Sync useRunOutputData index with shared page.
  // useRunOutputData now returns items in display order (oldest first, newest at items.length-1),
  // so the ItemNavigator "N / N" points to the most recent epoch. Default currentPage to the
  // newest position on first load (and whenever totalItems grows beyond the current page).
  const didInitRef = React.useRef(false);
  React.useEffect(() => {
    if (totalItems > 0 && !didInitRef.current) {
      setCurrentPage(totalItems - 1);
      didInitRef.current = true;
    }
  }, [totalItems]);
  React.useEffect(() => { didInitRef.current = false; }, [viewingEpoch, runId, stepAlias]);

  const targetIndex = totalItems > 0 ? Math.min(currentPage, totalItems - 1) : 0;
  React.useEffect(() => {
    if (totalItems > 0 && currentIndex !== targetIndex) {
      goToIndex(targetIndex);
    }
  }, [targetIndex, totalItems, currentIndex, goToIndex]);

  // Lazy-load the full output object for richer file data
  const [outputData, setOutputData] = React.useState<any>(null);

  // Reset outputData when epoch changes or current item changes so we reload
  const prevEpochRef = React.useRef(viewingEpoch);
  const prevItemIdRef = React.useRef(currentItem?.id);
  React.useEffect(() => {
    if (prevEpochRef.current !== viewingEpoch || prevItemIdRef.current !== currentItem?.id) {
      prevEpochRef.current = viewingEpoch;
      prevItemIdRef.current = currentItem?.id;
      setOutputData(null);
    }
  }, [viewingEpoch, currentItem?.id]);

  React.useEffect(() => {
    if (!isRunMode || !isCompleted || outputData) return;
    let cancelled = false;

    getObjectAtPath('').then((obj) => {
      if (!cancelled && obj) setOutputData(obj);
    }).catch(() => {});

    return () => { cancelled = true; };
  }, [isRunMode, isCompleted, getObjectAtPath, outputData]);

  // Extract the first FileRef from the output tree using the centralized
  // walker. Covers every shape: canonical FileRef under `output.file` for the
  // static producer nodes (download_file/sftp/convert_to_file/compression/media),
  // image_generation's data.images[]._type='file', create_image's
  // data[0].b64_json (post-dehydration), metadata.attachments[], and any
  // future catalog-tool fileRef field - without per-shape code here.
  //
  // {@code outputData} comes back from StepPayloadService wrapped with
  // envelope keys (_status, _duration_ms, _display_name, _error). The
  // load-bearing _status guard in isFileRef rejects those envelopes, so
  // for static file nodes (download_file et al.) the FileRef-shaped fields
  // sit AT the envelope's top level alongside _status. We strip envelope
  // keys before isFileRef-checking the root, then walk descendants for
  // tools that nest the FileRef deeper (image_generation, create_image).
  // Falls back to the step row's metadata blob when the lazy-loaded output
  // is not yet hydrated (some nodes persist a FileRef under metadata.file).
  const normalized = React.useMemo(() => {
    if (outputData && typeof outputData === 'object') {
      const stripped = { ...(outputData as Record<string, unknown>) };
      delete stripped._status;
      delete stripped._duration_ms;
      delete stripped._display_name;
      delete stripped._error;
      if (isFileRef(stripped)) return normalizeFileRef(stripped as any);
      const refs = findFileRefs(stripped);
      if (refs.length > 0) return normalizeFileRef(refs[0].fileRef);
    }
    if (currentItem?.metadata) {
      const refs = findFileRefs(currentItem.metadata);
      if (refs.length > 0) return normalizeFileRef(refs[0].fileRef);
    }
    return null;
  }, [outputData, currentItem]);

  // The strip renders exactly when a file is resolved on a completed node in run
  // mode (the two early returns below). FlowNode keys BOTH the bottom-bar Files
  // button and the bar's row offset off currentFile, so currentFile must mean
  // "the strip is on screen" and nothing else: gating it on the same condition
  // keeps that invariant exact. Without the isRunMode/isCompleted arms a rerun
  // (status back to running, strip unmounted) would leave currentFile set and
  // hide the Files button with no strip to replace it.
  const stripVisible = isRunMode && isCompleted && !!normalized;

  // Publish the resolved file to the parent while the strip is up, and clear it
  // on unmount so a node whose strip is gone stops claiming the bar's row.
  React.useEffect(() => {
    setCurrentFile(stripVisible && normalized
      ? { path: normalized.path, id: (normalized as { id?: string }).id, name: normalized.name, mimeType: normalized.mimeType, size: normalized.size }
      : null);
    return () => { setCurrentFile(null); };
  }, [normalized, stripVisible, setCurrentFile]);

  // Don't show anything if not in run mode or not completed
  if (!isRunMode || !isCompleted) return null;

  // No loading spinner below the node. The ['run-output-data'] query is
  // invalidated on every status event (each completion bumps resolvedStepCount),
  // so a spinner here reappeared - and grew/flickered the node - on every event.
  // Instead we render nothing while the file (re)fetches: an already-resolved
  // file stays visible (outputData persists across refetches), and the preview
  // simply appears once resolved. The full loading state lives in the inspector
  // panel (RunOutputPreview / RunDataPreview), not on the always-on canvas card.
  if (!normalized) return null;

  // Zero-layout-footprint strip (absolute, below the node border): the FileRef
  // arrives at run time, AFTER auto-layout measured the node, so the previous
  // in-flow preview (mt-3 + image/chip) grew the node and pushed it under its
  // neighbor. The per-item ItemNavigator that used to render below the node was
  // removed: multi-item navigation now lives in the inspector panel
  // (RunOutputPreview / RunDataPreview / ResolvedParamsView), where it's only
  // rendered when the user actually inspects a node.
  return <FileResultStrip file={normalized} />;
}
