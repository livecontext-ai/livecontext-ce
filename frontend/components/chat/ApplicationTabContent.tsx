'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { AlertCircle, X, Lock, StepForward, Grip, Calendar, Play, FormInput, MessageCircle, Webhook, ChevronLeft, ChevronRight, Wand2, RotateCcw, Volume2, VolumeX } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useInterfaceRender, useInterfaceById } from '@/app/workflows/builder/hooks/useInterfaces';
import { useRun } from '@/contexts/WorkflowRunContext';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { InterfaceIframe } from '@/app/workflows/builder/components/interface/InterfaceIframe';
import { resolveInterfaceFormat } from '@/lib/interfaces/interfaceFormats';
import { InterfaceToolbar } from '@/app/workflows/builder/components/interface/InterfaceToolbar';
import type { RenderMode } from '@/app/workflows/builder/utils/interfaceHtmlUtils';
import { mergeTriggerDataIntoResolved } from '@/app/workflows/builder/utils/interfaceHtmlUtils';
import { centeringCssFor } from '@/app/workflows/builder/utils/safeCenteringCss';
import LoadingSpinner from '@/components/LoadingSpinner';
import { parseUtcAware } from '@/lib/utils/dateFormatters';
import { useTranslations } from 'next-intl';
import { useSharedInterfacePage } from '@/lib/stores/interface-pagination-store';

import { computeIsAwaitingSignal, isCurrentInterfaceItemPending } from './interfaceAwaitingSignal';
import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { executionService } from '@/lib/api/orchestrator/execution.service';
import { triggerKey } from '@/app/workflows/builder/utils/labelNormalizer';
import { isNavigateRef } from '@/app/workflows/builder/utils/interfaceActionRefs';
import { TriggerPanel, type TriggerPanelConfig } from '@/app/workflows/builder/components/TriggerPanel';
import { formatUtcTime } from '@/lib/utils/dateFormatters';
import { RunStateIndicator } from './RunStateIndicator';
import { RunActionBar } from './RunActionBar';
import { computeRunBlockers } from '@/lib/workflow/runBlockers';
import { dispatchInterfaceContinue, requestInterfaceContinue } from '@/lib/workflow/interfaceContinue';
import { VIEWING_EPOCH_EVENT, shouldAdoptEpochEvent, type EpochEventDetail } from '@/lib/workflow/epochEventScope';
import { getPickedEpoch, markEpochPickedByUser, useDefaultEpochSelection } from '@/components/workflow/run-panel/useDefaultEpochSelection';
import { epochDisplayDurationMs, isEpochLive, resolveEpochBadgeStatus } from '@/components/workflow/run-panel/runFormatting';
import { EpochStatusIcon } from '@/components/workflow/EpochStatusIcon';
import { getRunStatusLabel } from '@/lib/utils/runStatusUtils';
import { RunActionButton, resolveRunAction } from '@/components/workflow/run-panel/RunActionButton';
import { useRunActions } from '@/components/workflow/run-panel/useRunActions';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { usePathname } from '@/i18n/navigation';

export interface ApplicationConfig {
  interfaceId: string;
  label: string;
  actionMapping: Record<string, string>;
  /** The workflow node ID (e.g., "interface:my_form") for signal resolution */
  nodeId?: string;
  /** The app's declared entry page (isEntryInterface). The carousel OPENS on it; page ORDER stays canvas order. */
  isEntryInterface?: boolean;
}

/**
 * The publication an application is shown against, and what the template actions may do
 * with it. Threaded down unchanged through the carousel, so it is declared once here.
 */
export interface ApplicationTemplateSource {
  publicationId: string;
  /**
   * The publication is sourced from the CLOUD marketplace. Reads route through the cloud
   * proxy; the reset endpoint is local-only and has no publication row to read, so it is
   * withheld.
   */
  remote?: boolean;
  /**
   * Whether "reset the data" applies on THIS surface. The reset targets the caller's
   * INSTALLED clone, so it is true exactly when this surface is bound to that clone:
   * false on a source workflow (the tables the endpoint rewrites are not the ones on
   * screen) and false with no install at all (the endpoint has nothing to resolve and
   * answers 404). Ownership of the publication does not decide it - a publisher who
   * installed their own app is looking at their clone like anyone else. Loading the
   * example values is unaffected and stays available either way.
   */
  canReset?: boolean;
}

interface ApplicationTabContentProps {
  config: ApplicationConfig;
  runId: string | null;
  workflowId?: string;
  /** Run-panel bus surface paired with this application. */
  runSurfaceId?: string;
  onAction: (triggerRef: string, data: Record<string, unknown>) => void;
  /** Carousel dot indicators injected into the InterfaceToolbar */
  carouselControls?: React.ReactNode;
  /** Controlled fullscreen state (lifted from parent carousel) */
  isExpanded?: boolean;
  onExpandedChange?: (expanded: boolean) => void;
  /** Controlled toolbar open state (lifted from parent carousel) */
  toolbarOpen?: boolean;
  onToolbarOpenChange?: (open: boolean) => void;
  /** Controlled viewingEpoch (lifted from parent carousel so it survives tab switches) */
  viewingEpoch?: number | null;
  onViewingEpochChange?: (epoch: number | null) => void;
  /**
   * Open on the newest fire instead of on all of them.
   *
   * Set where the application IS the product (a published app, a shared link):
   * its visitors want the latest result, not a pager spanning every fire the
   * workflow ever had. On the workflow page the app is one view of a run among
   * others, so it opens on all epochs like the canvas and the Run panel, and
   * this stays off. Purely local either way: the epoch is never broadcast from
   * here, so no other surface is dragged along.
   */
  openOnLatestEpoch?: boolean;
  /**
   * Marketplace-preview mode. Anonymous visitors and authenticated browsing
   * users hitting `/app/marketplace/{publicationId}/preview` MUST NOT be
   * able to fire any workflow action against the publisher's tenant. When
   * true, three behavior gates fire:
   *
   *   1. The Launch (trigger) button is hidden from the floating toolbar.
   *   2. The Continue button (which routes through useWorkflowEventBridge
   *      to an authed interfaceService.fireInterfaceAction) is hidden too.
   *   3. The floating pill toolbar is portalled to document.body so it
   *      escapes the preview shell's `overflow-hidden` clipping (root
   *      cause of the original user-reported bug - clicking the grip
   *      3-dots opened a wide toolbar that was visually hidden by the
   *      shell's overflow rule). Position is computed from the preview
   *      container's bounding rect (tracked via ResizeObserver +
   *      scroll/resize listeners) so the toolbar lands at the bottom-
   *      center of the PANEL - not the viewport, which would dock it
   *      under the app shell on the marketplace page.
   *
   * The publisher viewing their own preview must see exactly what an
   * anonymous visitor sees - see PublicationPreviewShell. Same code path,
   * same gates, regardless of auth state.
   */
  previewMode?: boolean;
  /**
   * The publication this application was installed from. Its presence is what
   * enables the two "template" toolbar actions, so a caller that is not showing
   * an installed application simply omits it:
   * <ul>
   *   <li><b>Load the template values</b> - reads the publisher's showcase render
   *       and seeds the interface forms + the trigger panel with the example
   *       inputs. Read-only, nothing is submitted.</li>
   *   <li><b>Reset the data</b> - restores the installed app's tables to the rows
   *       frozen in the publication. Destructive, confirmed first, and hidden for
   *       a {@code remote} publication (a cloud-sourced install has no local
   *       publication row, so the reset endpoint has nothing to read).</li>
   * </ul>
   * Callers must omit it for the publisher's own view: the publisher's page is
   * bound to the SOURCE workflow, so resetting their preview clone would be an
   * invisible no-op.
   */
  templateSource?: ApplicationTemplateSource;
  /**
   * Mute state for the application's own audio/video. Undefined = play as
   * authored; a boolean hands the volume to the embedder, which then flips it
   * with a message rather than by re-rendering the interface.
   */
  mediaMuted?: boolean;
  /**
   * Flip {@link mediaMuted}. Given together with a defined {@code mediaMuted}, the
   * controls toolbar grows a speaker button - the one place a visitor can turn the
   * application's sound on. Omit it wherever nobody owns the volume.
   */
  onToggleMediaMuted?: () => void;
}

type ResolvedVariablePagination = {
  name: string;
  page: number;
  totalPages: number;
};

function toFiniteNonNegativeInteger(value: unknown): number | null {
  const numberValue = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(numberValue) || numberValue < 0) return null;
  return Math.floor(numberValue);
}

function isExplicitFalse(value: unknown): boolean {
  return value === false || value === 'false';
}

export function ApplicationTabContent({ config, runId, workflowId, runSurfaceId, onAction, carouselControls, isExpanded: controlledExpanded, onExpandedChange, toolbarOpen: controlledToolbarOpen, onToolbarOpenChange, viewingEpoch: controlledViewingEpoch, onViewingEpochChange, openOnLatestEpoch = false, previewMode = false, templateSource, mediaMuted, onToggleMediaMuted }: ApplicationTabContentProps) {
  const t = useTranslations('marketplace');
  const tActions = useTranslations('actions');
  const tCanvas = useTranslations('workflowBuilder.canvas');
  const tRun = useTranslations('runMode');
  // Root-scoped: the epoch status names live under `status.*`, shared with every other
  // run badge, so the epoch dropdown says exactly what the run panel says.
  const tRoot = useTranslations();
  // Sound labels live in the applications namespace, shared with the app cards and
  // the marketplace previews - one source of truth rather than the same sentence
  // translated twice in two namespaces and drifting apart.
  const tSound = useTranslations('applications');
  const openControlsLabel = tCanvas('openApplicationControls');
  const [isDragging, setIsDragging] = React.useState(false);
  const [actionError, setActionError] = React.useState<string | null>(null);
  /**
   * The publisher's example inputs, once the user asks for them. Declared here (rather
   * than next to the handlers that write it) because the SUBMIT paths below must be able
   * to clear it: the seed exists to help the user fill the form, and the moment they fire
   * a trigger their own input supersedes it. Left set, it would outrank the run's real
   * trigger data in the iframe forever and silently revert every later submission back to
   * the publisher's example.
   */
  const [templateValues, setTemplateValues] =
    React.useState<Record<string, Record<string, unknown>> | null>(null);
  const [actionNotice, setActionNotice] =
    React.useState<{ type: 'success' | 'warning'; message: string } | null>(null);
  const [showPreviewToast, setShowPreviewToast] = React.useState(false);
  const previewToastTimeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  // Launchable triggers: chat/form/webhook (rendered as tabs in TriggerPanel)
  // + first manual (fired inline).
  // chat/form/webhook configs come from one of two sources (live wins):
  //   1. Canvas live cache via workflowPanelTriggerDataChange - dispatched
  //      by WorkflowBuilder when mounted (richer: live form-node data).
  //   2. Plan-derived fallback via workflowService.getWorkflow - used when
  //      no canvas is mounted (visualize-card popup in agent chat,
  //      /s/<token> share pages without a builder shell). Closes the
  //      previous gap where panelConfigs was [] in those contexts.
  // Manual triggers are detected from the same plan fetch (the canvas-side
  // dispatch filters them out at WorkflowBuilder.tsx:740-742).
  type LaunchableState = {
    panelConfigs: TriggerPanelConfig[];   // chat/form/webhook (multi-tab)
    firstManual: { id: string; label: string } | null;
    // First schedule trigger. With a runId it fires through the run-scoped
    // trigger endpoint; without one it falls back to the workflow-scoped
    // execute-now endpoint used by the schedule inspector.
    firstSchedule: { id: string; label: string } | null;
  };
  const [launchable, setLaunchable] = React.useState<LaunchableState>({
    panelConfigs: [],
    firstManual: null,
    firstSchedule: null,
  });

  // Two sources for chat/form/webhook configs:
  //  1. Live cache via `workflowPanelTriggerDataChange` - dispatched by the
  //     canvas (WorkflowBuilder) when mounted. Richer (live form-node data,
  //     backend-availability filtering). Wins when present.
  //  2. Plan-derived fallback - built from `wf.plan.triggers` directly. Used
  //     when the canvas isn't mounted (visualize-popup card in agent chat,
  //     /s/<token> share pages without canvas) so the Launch button still
  //     surfaces every trigger.
  // panelConfigs in launchable = liveConfigs.length > 0 ? liveConfigs : planConfigs.
  const [liveConfigs, setLiveConfigs] = React.useState<TriggerPanelConfig[]>([]);
  const [planConfigs, setPlanConfigs] = React.useState<TriggerPanelConfig[]>([]);

  React.useEffect(() => {
    if (!workflowId) {
      setLiveConfigs([]);
      setPlanConfigs([]);
      setLaunchable(prev => ({ ...prev, firstManual: null }));
      return;
    }
    let cancelled = false;
    // Seed liveConfigs from cache - but ONLY when the cache is for THIS
    // workflowId. The cache is a single global slot; a user navigating from
    // app A (which fills the cache) to a visualize-card popup of app B would
    // otherwise inherit A's trigger tabs against B's runId.
    const cached = (window as any).__applicationTriggerData;
    if (cached?.workflowId === workflowId && Array.isArray(cached?.configs)) {
      setLiveConfigs(cached.configs);
    } else {
      setLiveConfigs([]);
    }
    // Reset planConfigs while the new workflow's plan fetch is in flight -
    // otherwise the panelConfigs render would briefly show app A's tabs
    // against app B's runId between workflowId-change and fetch-resolve.
    setPlanConfigs([]);
    const handler = (event: CustomEvent) => {
      if (event.detail?.workflowId && event.detail.workflowId !== workflowId) return;
      const configs = event.detail?.configs;
      setLiveConfigs(Array.isArray(configs) ? configs : []);
    };
    window.addEventListener('workflowPanelTriggerDataChange', handler as EventListener);
    // Plan fetch - drives BOTH manual detection AND the plan-derived
    // chat/form/webhook fallback (so visualize-popup / share pages without
    // a canvas mounted still get launchable triggers).
    (async () => {
      try {
        const wf = await workflowService.getWorkflow(workflowId);
        const triggers = (wf.plan as { triggers?: Array<{ id: string; label?: string; type?: string; params?: Record<string, unknown> }> } | undefined)?.triggers;
        if (cancelled) return;
        if (!Array.isArray(triggers)) {
          setPlanConfigs([]);
          setLaunchable(prev => ({ ...prev, firstManual: null, firstSchedule: null }));
          return;
        }
        const manual = triggers.find(t => (t.type || '').toLowerCase() === 'manual');
        const schedule = triggers.find(t => (t.type || '').toLowerCase() === 'schedule');
        setLaunchable(prev => ({
          ...prev,
          firstManual: manual
            ? { id: triggerKey(manual.label) || manual.id, label: manual.label || 'Manual' }
            : null,
          firstSchedule: schedule
            ? { id: triggerKey(schedule.label) || schedule.id, label: schedule.label || 'Schedule' }
            : null,
        }));
        setPlanConfigs(buildPanelConfigsFromPlan(triggers));
      } catch {
        if (!cancelled) {
          setPlanConfigs([]);
          setLaunchable(prev => ({ ...prev, firstManual: null, firstSchedule: null }));
        }
      }
    })();
    return () => {
      cancelled = true;
      window.removeEventListener('workflowPanelTriggerDataChange', handler as EventListener);
    };
  }, [workflowId]);

  // Sync liveConfigs / planConfigs into launchable.panelConfigs (live wins).
  React.useEffect(() => {
    setLaunchable(prev => ({
      ...prev,
      panelConfigs: liveConfigs.length > 0 ? liveConfigs : planConfigs,
    }));
  }, [liveConfigs, planConfigs]);

  // Has any launchable trigger? (panel configs OR manual OR schedule)
  const hasPanelTriggers = launchable.panelConfigs.length > 0;
  const hasAnyLaunchable = hasPanelTriggers || !!launchable.firstManual || !!launchable.firstSchedule;

  const [isLaunching, setIsLaunching] = React.useState(false);
  // handleLaunchTrigger is declared AFTER useRun() further down so it can
  // close over runContext without TDZ. See the named callback below.
  // Pagination state keyed by runId - all interfaces share the same epoch
  const [currentPage, setCurrentPage] = useSharedInterfacePage(runId ?? null);
  // Per-variable pagination state for SQL-level JSONB array slicing
  const [variablePages, setVariablePages] = React.useState<Record<string, number>>({});

  // Expanded (fullscreen) state - use controlled props if provided, otherwise local state
  const [localExpanded, setLocalExpanded] = React.useState(false);
  const isExpanded = controlledExpanded ?? localExpanded;

  // Ref to always have current expanded value (avoids stale closures in callbacks)
  const expandedRef = React.useRef(isExpanded);
  expandedRef.current = isExpanded;

  const setIsExpanded = React.useCallback((value: boolean | ((prev: boolean) => boolean)) => {
    const next = typeof value === 'function' ? value(expandedRef.current) : value;
    if (onExpandedChange) {
      onExpandedChange(next);
    } else {
      setLocalExpanded(next);
    }
  }, [onExpandedChange]);

  // Disable iframe pointer events while dragging on React Flow canvas
  React.useEffect(() => {
    const handleDragStart = () => setIsDragging(true);
    const handleDragEnd = () => setIsDragging(false);

    window.addEventListener('mousedown', handleDragStart);
    window.addEventListener('mouseup', handleDragEnd);
    return () => {
      window.removeEventListener('mousedown', handleDragStart);
      window.removeEventListener('mouseup', handleDragEnd);
    };
  }, []);

  const { isRunMode, isPreviewOnly } = useWorkflowMode();

  // viewingEpoch: use controlled props if provided (lifted to carousel), otherwise local state.
  const [localViewingEpoch, setLocalViewingEpoch] = React.useState<number | null>(null);
  const viewingEpoch = controlledViewingEpoch !== undefined ? controlledViewingEpoch : localViewingEpoch;

  // Cross-tree viewingEpoch sync: canvas RunInfo ↔ SidePanel Application tab.
  // Scoped by runId so two apps mounted at once (keepMounted side-panel tabs)
  // keep independent epoch selection instead of slaving to each other.
  React.useEffect(() => {
    const handler = (event: CustomEvent<EpochEventDetail>) => {
      const detail = event.detail;
      if (!shouldAdoptEpochEvent(detail?.runId, runId)) return;
      if (onViewingEpochChange) {
        onViewingEpochChange(detail.epoch);
      } else {
        setLocalViewingEpoch(detail.epoch);
      }
    };
    window.addEventListener(VIEWING_EPOCH_EVENT, handler as EventListener);
    return () => window.removeEventListener(VIEWING_EPOCH_EVENT, handler as EventListener);
  }, [onViewingEpochChange, runId]);

  // Epoch selector: set locally/parent AND broadcast to canvas so RunInfo stays in sync.
  // Scope the broadcast to THIS run so sibling app tabs (other runs) ignore it.
  //
  // Shows an epoch WITHOUT claiming the user chose it. Used by the jump that
  // follows a new fire for a tab pinned by its own seeding (a published app):
  // recording that would hand a pick to the workflow surfaces of the same run,
  // which nobody asked for. The dropdown goes through `handleEpochPickedByUser`
  // below, which does record.
  const handleViewEpoch = React.useCallback((epoch: number | null) => {
    if (onViewingEpochChange) {
      onViewingEpochChange(epoch);
    } else {
      setLocalViewingEpoch(epoch);
    }
    const detail: EpochEventDetail = { epoch, runId };
    window.dispatchEvent(new CustomEvent(VIEWING_EPOCH_EVENT, { detail }));
  }, [onViewingEpochChange, runId]);

  /**
   * The dropdown click, and ONLY that.
   *
   * The epoch a user picked is remembered per run, module-globally, and shared
   * with every surface of that run (the canvas, the Run tab). Marking it from
   * `handleViewEpoch` would let this tab's own auto-jump count as a choice and
   * drag the other surfaces onto whatever epoch it landed on.
   */
  const handleEpochPickedByUser = React.useCallback((epoch: number | null) => {
    markEpochPickedByUser(runId, epoch);
    handleViewEpoch(epoch);
  }, [handleViewEpoch, runId]);

  /**
   * Restore the epoch the user picked for this run - the same hook the canvas
   * and the Run tab use, for the same reason.
   *
   * This tab is mounted and unmounted by the side-panel sub-tab switch, and the
   * cross-tree event that carries a pick is fired once, at the click. A tab that
   * was not mounted then has no way to learn about it: picking an epoch in the
   * Run tab and switching to Application dropped the choice and showed the
   * cumulative view again (and, on an application page, the newest fire seeded
   * over it). The pick lives at module scope keyed by run, precisely so it
   * survives the surface that made it.
   *
   * Gated on run mode like the other two surfaces: the edit/run toggle clears the
   * epoch WITHOUT recording it (deliberately - a reset the user did not ask for is
   * not a choice), so a tab still mounted with a run id would otherwise restore
   * the pick and broadcast it back onto the canvas that just dropped it. The gate
   * costs nothing: the epoch list comes from `useRun`, which this tab already
   * subscribes to in run mode only.
   */
  useDefaultEpochSelection({
    runId,
    selectedEpoch: viewingEpoch,
    onSelectEpoch: handleViewEpoch,
    enabled: isRunMode,
  });

  const [runState, runContext] = useRun(isRunMode ? runId || undefined : undefined);

  // Multi-trigger panel state: when there are chat/form/webhook triggers,
  // the Launch button opens TriggerPanel (multi-tab UI) instead of trying
  // to navigate the side panel. Works in any context (auth'd, visualize
  // popup, /s/<token> share page) because TriggerPanel renders as a
  // floating self-contained panel - no WorkflowPanelContent dependency.
  const [isTriggerPanelOpen, setIsTriggerPanelOpen] = React.useState(false);

  // chat/form/webhook → open TriggerPanel (multi-tab) so the user can pick
  //   any trigger, fill its input, and fire. onExecuteTrigger routes through
  //   runContext.executeStep so the WS subscription stays warm.
  // manual (and no chat/form/webhook) → fire inline (no panel needed).
  // mixed (manual + chat/form/webhook) → opens the panel; manual stays
  //   addressable via the inline branch only when no panel triggers exist.
  const handleLaunchTrigger = React.useCallback(async () => {
    if (hasPanelTriggers) {
      setIsTriggerPanelOpen(true);
      return;
    }
    if (launchable.firstManual && runId && !isLaunching) {
      setIsLaunching(true);
      // Firing manual/schedule produces a new epoch with its own trigger data. The seed
      // has to step aside here too, otherwise it keeps overriding that epoch's real values
      // in the interface - the same trap the submit paths close.
      //
      // Cleared BEFORE the try, unlike a form submit which keeps the seed when it fails.
      // The asymmetry is deliberate: these triggers carry no user input, so a failed fire
      // leaves nothing worth retyping and nothing to preserve.
      setTemplateValues(null);
      try {
        if (runContext?.executeStep) {
          await runContext.executeStep(runId, launchable.firstManual.id, undefined, 'manual');
        } else {
          await executionService.triggerManual(runId);
        }
      } catch (e) {
        console.error('[ApplicationTabContent] manual fire failed', e);
      } finally {
        setIsLaunching(false);
      }
      return;
    }
    if (launchable.firstSchedule && !isLaunching) {
      setIsLaunching(true);
      // Same contract as the manual branch above: new epoch, so the seed steps aside.
      setTemplateValues(null);
      try {
        if (runId) {
          if (runContext?.executeStep) {
            await runContext.executeStep(runId, launchable.firstSchedule.id, undefined, 'schedule');
          } else {
            await executionService.triggerSpecific(runId, launchable.firstSchedule.id, 'schedule');
          }
        } else if (workflowId) {
          await executionService.scheduleExecuteNow(workflowId, launchable.firstSchedule.id);
        }
      } catch (e) {
        console.error('[ApplicationTabContent] schedule force-fire failed', e);
      } finally {
        setIsLaunching(false);
      }
    }
  }, [hasPanelTriggers, launchable.firstManual, launchable.firstSchedule, runId, workflowId, isLaunching, runContext]);

  // Bridge TriggerPanel's onExecuteTrigger to the run-manager so chat/form/
  // webhook fires keep WS subscription warm + the UI updates live.
  // Returns readySteps (TriggerPanel uses it to refresh its disable state).
  const handlePanelExecuteTrigger = React.useCallback(async (
    triggerId: string,
    triggerType: 'chat' | 'form' | 'webhook',
    payload: Record<string, unknown>,
  ): Promise<string[] | undefined> => {
    if (!runId) return undefined;
    // Same contract as safeOnAction: once the user fires a trigger, their input replaces
    // the template seed - keeping it would revert the interface to the example on the
    // next render.
    setTemplateValues(null);
    if (runContext?.executeStep) {
      // Returns StepExecutionResult; readySteps is on the response shape.
      const result = await runContext.executeStep(runId, triggerId, payload, triggerType);
      return result?.readySteps;
    }
    // Fallback raw API (no live updates).
    try {
      const response = await orchestratorApi.triggerSpecific(runId, triggerId, triggerType, payload);
      return response?.readySteps as string[] | undefined;
    } catch (e) {
      console.error('[ApplicationTabContent] panel trigger fire failed', e);
      return undefined;
    }
  }, [runId, runContext]);

  const { data: interfaceDetails } = useInterfaceById(config.interfaceId);

  // Epoch list from run state (needed before render hook)
  const epochTimestamps = runState?.epochTimestamps ?? [];
  const totalEpochs = epochTimestamps.length;

  // On the workflow page nothing is selected on first load: a run opens on ALL
  // of its epochs, here as everywhere else. This tab used to seed the newest one
  // unconditionally, and because `handleViewEpoch` broadcasts the choice to
  // every surface of the run, merely opening the Application tab dragged the
  // canvas and the Run tab onto that epoch - the cumulative view was
  // unreachable for a workflow with an app.
  //
  // Where the application IS the product (`openOnLatestEpoch`), the newest fire
  // is what its visitors came for, so it is selected LOCALLY - through the
  // controlled prop, never the broadcasting handler.
  const seededLatestRef = React.useRef(false);
  React.useEffect(() => {
    if (!openOnLatestEpoch || seededLatestRef.current || totalEpochs === 0) return;
    if (viewingEpoch != null) { seededLatestRef.current = true; return; }
    // A choice the user made for this run outranks the seed - "All epochs" is a
    // choice too, hence `!== undefined`. Without this, an app page reopened
    // after picking an epoch in the Run tab would seed the newest fire over it
    // in the same commit the restore above put it back.
    //
    // That deliberately includes the `null` written by `selectAllEpochs` when the
    // user fires from a focused epoch: after a "fire from here" the app opens on
    // the cumulative view rather than pinning the newest epoch. Nothing is hidden
    // by that - All-epochs pins the pager to page 0, which IS the newest fire's
    // content (see the pagination rules below); only the badge differs. Treating
    // that null as "no choice" would instead let the seed re-pin an epoch the
    // user just left, which is the exact failure `selectAllEpochs` exists to stop.
    if (getPickedEpoch(runId) !== undefined) { seededLatestRef.current = true; return; }
    const latest = Math.max(...epochTimestamps.map((e: { epoch?: number }) => e.epoch ?? 0));
    // Latch only on a real seed, so a first snapshot that carries no usable
    // epoch does not disable seeding for the life of this tab.
    if (latest <= 0) return;
    seededLatestRef.current = true;
    if (onViewingEpochChange) onViewingEpochChange(latest);
    else setLocalViewingEpoch(latest);
  }, [openOnLatestEpoch, totalEpochs, epochTimestamps, viewingEpoch, onViewingEpochChange, runId]);

  const { data: renderData, isLoading, isFetching, isPlaceholderData, refetch } = useInterfaceRender(
    config.interfaceId,
    runId,
    currentPage,
    1,
    viewingEpoch ?? undefined,
    variablePages
  );

  // Reset pagination when viewingEpoch changes.
  //
  // Backend sort in InterfaceRenderService.render(): epoch DESC, itemIndex DESC.
  // → page 0 is ALWAYS the most recent epoch (not the oldest).
  //
  // Rules:
  //   • In "All" (viewingEpoch == null) mode we want the interface to render
  //     the freshest epoch's content by default - that's page 0 given the
  //     DESC sort. Status counts still accumulate across ALL epochs.
  //   • When switching to a specific epoch, reset to page 0 (within-epoch
  //     pagination starts at the top, highest itemIndex).
  //   • When switching BACK from a specific epoch to All, re-pin to page 0
  //     (= latest epoch).
  //   • We do NOT auto-scroll when new epochs arrive while the user stays in
  //     All - respect their current position.
  const prevEpochRef = React.useRef<number | null>(viewingEpoch);
  const allModeInitialPinRef = React.useRef(false);
  React.useEffect(() => {
    const epochChanged = prevEpochRef.current !== viewingEpoch;
    prevEpochRef.current = viewingEpoch;

    if (viewingEpoch == null) {
      // All mode. Pin to page 0 (latest epoch) once per entry into this mode.
      if (!allModeInitialPinRef.current || epochChanged) {
        if (totalEpochs > 0) {
          setCurrentPage(0);
          setVariablePages({});
          allModeInitialPinRef.current = true;
        }
      }
    } else {
      // Specific epoch - reset the All-mode pin so next switch back re-pins.
      allModeInitialPinRef.current = false;
      if (epochChanged) {
        setCurrentPage(0);
        setVariablePages({});
      }
    }
  }, [viewingEpoch, setCurrentPage, totalEpochs]);

  // Debounced refetch when execution state changes.
  // Uses executionTotal (monotonically increasing sum of all per-node statusCounts)
  // instead of resolvedStepCount (Set-based, unreliable across epoch resets).
  const executionTotal = runState?.executionTotal ?? 0;

  const execTotalRef = React.useRef(executionTotal);
  React.useEffect(() => {
    if (!isRunMode) return;

    if (executionTotal === 0 || executionTotal === execTotalRef.current) return;

    execTotalRef.current = executionTotal;
    const timeoutId = setTimeout(() => {
      refetch();
    }, 2000);
    return () => clearTimeout(timeoutId);
  }, [isRunMode, executionTotal, refetch]);

  // Show loading overlay only during actual fetches where displayed content is stale placeholder.
  // Don't show during debounce wait (old content is still valid) or initial load with no content yet
  // (InterfaceIframe handles its own fade-in).
  const isTransitioning = isFetching && isPlaceholderData;

  const htmlTemplate = renderData?.htmlTemplate || '';
  const items = renderData?.items || [];

  const itemData = React.useMemo(() => {
    if (items.length === 0) return undefined;
    return items[0]?.data as Record<string, unknown> | undefined;
  }, [items]);

  const triggerData = React.useMemo(() => {
    if (items.length === 0) return undefined;
    return items[0]?.triggerData as Record<string, Record<string, unknown>> | undefined;
  }, [items]);

  // Merge trigger data into resolved data so {{trigger:name.output.field}} works in templates
  const resolvedData = React.useMemo(() => {
    return mergeTriggerDataIntoResolved(itemData, triggerData);
  }, [itemData, triggerData]);

  // When the run has no data yet, render in "edit" mode so `{{xxx|default}}`
  // pipe defaults show instead of raw `{{…}}` placeholders.
  const hasResolvedData = !!resolvedData && Object.keys(resolvedData).length > 0;
  const renderMode: RenderMode = hasResolvedData ? 'run' : 'edit';

  // Center iframe body content for FRAGMENT templates - see SAFE_CENTERING_CSS
  // for rationale on the `safe center` keyword (centers small interfaces but
  // lets tall dashboards scroll from the top instead of clipping above the
  // viewport). A COMPLETE document gets nothing (author owns the body layout;
  // parity with the screenshot/video renderer, which injects nothing).
  const centeringCss = centeringCssFor(htmlTemplate);

  // Auto-navigate to the latest epoch ONLY when a new one genuinely appears
  // mid-session (e.g. the user fires a trigger and a new epoch closes while
  // they're watching). On the initial mount / refresh we default to "All
  // epochs" (viewingEpoch == null) so RunInfo shows cumulative statusCounts
  // - otherwise the user lands on one specific epoch and sees counts = 1 on
  // a node that actually ran across multiple epochs.
  const prevEpochCountRef = React.useRef(0);
  const prevRunIdRef2 = React.useRef(runId);
  const initialSyncDoneRef = React.useRef(false);
  React.useEffect(() => {
    // Reset on run change so we re-prime the baseline for the new run.
    if (prevRunIdRef2.current !== runId) {
      prevRunIdRef2.current = runId;
      prevEpochCountRef.current = 0;
      initialSyncDoneRef.current = false;
    }
    if (totalEpochs === 0) return;

    // First time we see epochs for this run: prime the baseline, but DO NOT
    // force viewingEpoch to the latest. A null viewingEpoch means "All" and
    // must be preserved on refresh; a non-null viewingEpoch was lifted from
    // a previous carousel tab and is equally left untouched.
    if (!initialSyncDoneRef.current) {
      initialSyncDoneRef.current = true;
      prevEpochCountRef.current = totalEpochs;
      return;
    }

    // Post-initial-sync: a new epoch closed. Auto-jump only when the user is
    // already pinned to a specific epoch (they were watching live epoch N -
    // carry them to N+1). If they chose "All", respect that choice.
    //
    // This MOVES a pin the user set, so it moves the remembered pick with it:
    // leaving it on the epoch they were carried away from would have the next
    // surface that mounts empty restore a stale one. When the pin came from
    // this tab's own seeding (a published app), there is no pick to move and
    // none is invented - the workflow surfaces stay on all epochs.
    const newEpochAppeared = totalEpochs > prevEpochCountRef.current;
    if (newEpochAppeared) {
      prevEpochCountRef.current = totalEpochs;
      if (viewingEpoch != null) {
        const latestEpoch = Math.max(...epochTimestamps.map((e: any) => e.epoch));
        // `!= null` on purpose: a recorded `null` IS the user asking for all
        // epochs. Treating it as "they picked something" would turn their
        // choice into a pin on the newest fire and broadcast it to the canvas.
        if (getPickedEpoch(runId) != null) handleEpochPickedByUser(latestEpoch);
        else handleViewEpoch(latestEpoch);
      }
    }
  }, [runId, totalEpochs, epochTimestamps, viewingEpoch, handleEpochPickedByUser, handleViewEpoch]);

  // Pending interface signals for this node (split-aware: one signal per itemId).
  const interfacePendingSignals = React.useMemo(() => {
    if (!runState?.pendingSignals || !config.nodeId) return [];
    return runState.pendingSignals.filter(
      s => s.nodeId === config.nodeId && s.signalType === 'INTERFACE_SIGNAL'
    );
  }, [runState?.pendingSignals, config.nodeId]);
  const pendingSignalCount = interfacePendingSignals.length;

  // Render pagination is meaningful whenever the backend exposes multiple
  // rendered pages. In All-epochs mode this browses the ordered page set
  // returned by InterfaceRenderService; choosing an epoch narrows that set.
  const effectiveTotalPages = renderData?.pagination?.totalPages ?? 0;
  const effectiveSpawnPages = effectiveTotalPages;
  const hasSpawnPagination = effectiveSpawnPages > 1;

  // ── Run-context semantics for the pagination counter ──
  // The render API tags every item with its {epoch, spawn, itemIndex} triple.
  // When the user is pinned to ONE epoch, each page IS one item of that epoch
  // → replace the bare "{page+1} / {totalPages}" counter with "Item X/Y"
  // (1-based, same convention as the Files browser). In "All epochs" mode the
  // pages span epochs so the bare counter stays (an item-number there would
  // look stuck across epochs of one item each).
  const currentItemTriple = items[0];
  const itemPageLabel = viewingEpoch != null && hasSpawnPagination && currentItemTriple
    ? tRun('itemOfTotal', { number: (currentItemTriple.itemIndex ?? 0) + 1, total: effectiveSpawnPages })
    : undefined;
  // Re-execution badge: spawn > 0 means the displayed item is a re-run within
  // the same epoch. Shown 1-based ("Re-execution 2" = second execution) and
  // ONLY for re-runs so first executions stay clean.
  const reExecutionBadge = currentItemTriple && (currentItemTriple.spawn ?? 0) > 0
    ? tRun('reExecutionBadge', { number: currentItemTriple.spawn + 1 })
    : undefined;

  React.useEffect(() => {
    if (effectiveSpawnPages <= 0) return;
    if (currentPage < 0) {
      setCurrentPage(0);
    } else if (currentPage >= effectiveSpawnPages) {
      setCurrentPage(effectiveSpawnPages - 1);
    }
  }, [currentPage, effectiveSpawnPages, setCurrentPage]);

  const handlePrevious = React.useCallback(() => {
    if (currentPage > 0) setCurrentPage(prev => prev - 1);
  }, [currentPage, setCurrentPage]);

  const handleNext = React.useCallback(() => {
    if (currentPage < effectiveSpawnPages - 1) {
      setCurrentPage(prev => prev + 1);
    }
  }, [currentPage, effectiveSpawnPages, setCurrentPage]);

  // Handle pagination actions from iframe bridge script (__pagination:prev/next)
  const handleIframePagination = React.useCallback((direction: 'prev' | 'next') => {
    if (direction === 'prev') handlePrevious();
    else if (direction === 'next') handleNext();
  }, [handlePrevious, handleNext]);

  // Handle per-variable pagination from iframe (variable-pagination postMessage or __varpage: triggers)
  const handleVariablePagination = React.useCallback((variableName: string, page: number) => {
    setVariablePages(prev => {
      if (prev[variableName] === page) return prev;
      return { ...prev, [variableName]: page };
    });
  }, []);

  const variablePaginationItems = React.useMemo<ResolvedVariablePagination[]>(() => {
    if (!resolvedData) return [];

    return Object.keys(resolvedData)
      .filter(key => key.endsWith('__totalPages'))
      .map(key => {
        const name = key.slice(0, -'__totalPages'.length);
        if (isExplicitFalse(resolvedData[`${name}__paginationSupported`])) return null;
        const totalPages = toFiniteNonNegativeInteger(resolvedData[key]);
        if (totalPages == null || totalPages <= 1) return null;
        const resolvedPage = toFiniteNonNegativeInteger(resolvedData[`${name}__page`]);
        const requestedPage = toFiniteNonNegativeInteger(variablePages[name]);
        return {
          name,
          page: resolvedPage ?? requestedPage ?? 0,
          totalPages,
        };
      })
      .filter((item): item is ResolvedVariablePagination => item != null)
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [resolvedData, variablePages]);

  const [activeVariablePageName, setActiveVariablePageName] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (variablePaginationItems.length === 0) {
      if (activeVariablePageName != null) setActiveVariablePageName(null);
      return;
    }
    if (!activeVariablePageName || !variablePaginationItems.some(item => item.name === activeVariablePageName)) {
      setActiveVariablePageName(variablePaginationItems[0].name);
    }
  }, [activeVariablePageName, variablePaginationItems]);

  const activeVariablePage = React.useMemo(() => {
    return variablePaginationItems.find(item => item.name === activeVariablePageName)
      ?? variablePaginationItems[0]
      ?? null;
  }, [activeVariablePageName, variablePaginationItems]);

  React.useEffect(() => {
    if (variablePaginationItems.length === 0) return;

    setVariablePages(prev => {
      let next = prev;
      let changed = false;

      for (const item of variablePaginationItems) {
        if (item.page < item.totalPages) continue;
        if (prev[item.name] === item.totalPages - 1) continue;
        if (!changed) next = { ...prev };
        next[item.name] = item.totalPages - 1;
        changed = true;
      }

      return changed ? next : prev;
    });
  }, [variablePaginationItems]);

  const handleVariablePrevious = React.useCallback(() => {
    if (!activeVariablePage || activeVariablePage.page <= 0) return;
    handleVariablePagination(activeVariablePage.name, activeVariablePage.page - 1);
  }, [activeVariablePage, handleVariablePagination]);

  const handleVariableNext = React.useCallback(() => {
    if (!activeVariablePage || activeVariablePage.page >= activeVariablePage.totalPages - 1) return;
    handleVariablePagination(activeVariablePage.name, activeVariablePage.page + 1);
  }, [activeVariablePage, handleVariablePagination]);

  // Fullscreen toggle
  const handleToggleExpanded = React.useCallback(() => {
    setIsExpanded(prev => !prev);
  }, [setIsExpanded]);

  // Listen for openInterfaceFullscreen events from canvas nodes
  React.useEffect(() => {
    const handleExternalFullscreen = (event: CustomEvent<{ interfaceId: string }>) => {
      if (event.detail.interfaceId === config.interfaceId) {
        setIsExpanded(true);
      }
    };
    window.addEventListener('openInterfaceFullscreen', handleExternalFullscreen as EventListener);
    return () => {
      window.removeEventListener('openInterfaceFullscreen', handleExternalFullscreen as EventListener);
    };
  }, [config.interfaceId, setIsExpanded]);

  // Escape to exit expanded mode, arrow keys for pagination
  React.useEffect(() => {
    if (!isExpanded) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsExpanded(false);
      } else if (e.key === 'ArrowLeft') {
        handlePrevious();
      } else if (e.key === 'ArrowRight') {
        handleNext();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isExpanded, handlePrevious, handleNext, setIsExpanded]);

  // Prevent body scroll when expanded
  React.useEffect(() => {
    if (isExpanded) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [isExpanded]);

  const hasActions = hasSpawnPagination || variablePaginationItems.length > 0 || totalEpochs > 1 || !!htmlTemplate;

  // Show preview-mode toast with auto-dismiss
  const showPreviewBlockedToast = React.useCallback(() => {
    if (previewToastTimeoutRef.current) clearTimeout(previewToastTimeoutRef.current);
    setShowPreviewToast(true);
    previewToastTimeoutRef.current = setTimeout(() => setShowPreviewToast(false), 4000);
  }, []);

  // Cleanup timeout on unmount
  React.useEffect(() => {
    return () => { if (previewToastTimeoutRef.current) clearTimeout(previewToastTimeoutRef.current); };
  }, []);

  // Block trigger re-submissions while workflow is running (backend rejects with 409 anyway)
  const isRunning = runState?.runStatus === 'running';
  const actionInFlightRef = React.useRef(false);

  // Wrap onAction to catch errors and show them to the user (for iframe bridge script)
  // Uses actionInFlightRef to prevent duplicate trigger calls (bridge script can fire twice)
  // isRunning guard only affects trigger actions (safeOnAction); __continue and __pagination
  // use separate callbacks (handleContinue, handleIframePagination) and are never blocked.
  // Navigate actions (<prefix>:<label>:navigate) are pure frontend tab switches - always
  // allowed through. A trigger merely LABELLED "Navigate" is not one of them: see
  // isNavigateRef, which is why this is not a bare `endsWith(':navigate')`.
  const safeOnAction = React.useCallback(async (triggerRef: string, data: Record<string, unknown>) => {
    const isNavigateAction = isNavigateRef(triggerRef);
    if (isPreviewOnly && !isNavigateAction) {
      showPreviewBlockedToast();
      return;
    }
    // Navigate actions bypass isRunning and actionInFlight guards (no API call)
    if (!isNavigateAction && (isRunning || actionInFlightRef.current)) {
      return;
    }
    if (!isNavigateAction) {
      actionInFlightRef.current = true;
    }
    setActionError(null);
    try {
      await onAction(triggerRef, data);
      // The user has SUBMITTED: their own input is now the run's trigger data, so the
      // template seed must step aside or the next render would show the example again.
      //
      // A navigate action is exempt. It is a pure page switch with no API call, and on a
      // multi-page app the menu page is routinely where the user loads the values and the
      // form lives one navigate away - clearing here would make the whole action a silent
      // no-op the moment they turn the page. A failed submit also keeps the seed on
      // purpose: the user is going to retry, and retyping the example is exactly what the
      // action exists to spare them.
      if (!isNavigateAction) {
        setTemplateValues(null);
      }
      // After trigger fires, stay on current viewingEpoch. The auto-navigate effect
      // will switch to the new epoch once it appears in epochTimestamps.
    } catch (error: any) {
      const message = error?.message || error?.toString() || 'Action failed';
      setActionError(message);
    } finally {
      if (!isNavigateAction) {
        actionInFlightRef.current = false;
      }
    }
  }, [onAction, isPreviewOnly, isRunning, showPreviewBlockedToast]);

  // Handle __continue: resolve the interface signal so the workflow continues past this interface.
  // Use the *rendered* item's itemIndex (from the API response) rather than the pagination
  // page number. In split context, itemIndex directly maps to the signal's itemId on the
  // backend, ensuring the correct per-item signal is resolved. currentPage is a pagination
  // cursor (DESC-sorted) and does NOT equal the item's split index.
  const currentItemIndex = items[0]?.itemIndex ?? 0;

  // Check if the currently displayed item still has a pending signal (guards against
  // clicking Continue on an already-resolved item after stale render data).
  const isCurrentItemPending = React.useMemo(() => {
    const fallbackAwaitingSinglePage = effectiveTotalPages <= 1
      && computeIsAwaitingSignal(config.nodeId, runState, config.actionMapping);
    return isCurrentInterfaceItemPending(interfacePendingSignals, currentItemIndex, fallbackAwaitingSinglePage);
  }, [
    interfacePendingSignals,
    currentItemIndex,
    effectiveTotalPages,
    config.nodeId,
    runState?.awaitingSignalSteps,
    runState?.runningSteps,
    config.actionMapping,
  ]);

  const handleContinue = React.useCallback((actionKey: string, data: Record<string, unknown>) => {
    if (!runId || !config.nodeId) return;
    // Deliberately WITHOUT a workflowId: an application surface can be showing a
    // publisher's workflow under a different id than the canvas it sits in, and
    // an unnamed event reaches every bridge (see isEventForWorkflow).
    dispatchInterfaceContinue({ runId, nodeId: config.nodeId, actionKey, data, itemIndex: currentItemIndex });
  }, [runId, config.nodeId, currentItemIndex]);

  // Detect if the interface node is awaiting signal.
  // Only BLOCKING interfaces (actionMapping contains "__continue") ever yield
  // AWAITING_SIGNAL - non-blocking interfaces complete via SUCCESS and the
  // Continue button would be a no-op (backend fallback, but workflow has
  // already moved past this node). Logic extracted to a pure helper for unit
  // testing - see interfaceAwaitingSignal.ts and its test.
  const isAwaitingSignal = React.useMemo(() => {
    return computeIsAwaitingSignal(config.nodeId, runState, config.actionMapping);
  }, [config.nodeId, runState?.awaitingSignalSteps, runState?.runningSteps, config.actionMapping]);

  const [isContinuing, setIsContinuing] = React.useState(false);

  // ── What is this run waiting on that the person here can answer? ──
  // Run-wide, not node-scoped: `runState.pendingSignals` always carried the
  // approvals of every node and this surface filtered them out, so an approval
  // parked anywhere left the application frozen with nothing to click and no
  // explanation. Preview surfaces are excluded: they render a publisher's
  // frozen showcase, where acting would advance someone else's run.
  //
  // TWO lists, because scope belongs to the ACTION and not to the STATEMENT.
  // Deriving the indicator from the actionable list put the original lie back:
  // on a preview, or while reading an older epoch, a parked run had an empty
  // actionable list and therefore fell back to a sweeping busy blue - claiming
  // work was happening while the run sat waiting on a person.
  //
  // `runWideBlockers` is the TRUTH about the run: is it waiting on a human at
  // all. It feeds the indicator only.
  const runWideBlockers = React.useMemo(
    () => computeRunBlockers(runState, {
      displayedInterfaceNodeId: config.nodeId,
      interfaceIsAwaiting: isAwaitingSignal,
      displayedItemIndex: currentItemIndex,
    }),
    [runState, config.nodeId, isAwaitingSignal, currentItemIndex],
  );
  // `blockers` is what THIS viewer may answer HERE. It feeds the action bar.
  const blockers = React.useMemo(
    () => (previewMode ? [] : computeRunBlockers(runState, {
      displayedInterfaceNodeId: config.nodeId,
      interfaceIsAwaiting: isAwaitingSignal,
      // The item the Continue button would carry: the endpoint resolves
      // max(epoch) FOR THAT ITEM, so the epoch guard has to key on it too.
      displayedItemIndex: currentItemIndex,
      // `viewingEpoch`, NOT `currentDisplayEpoch`: null here means "all epochs"
      // and must leave the list unfiltered, while currentDisplayEpoch coerces
      // that null to the newest fire and would silently hide every other one.
      viewingEpoch,
    })),
    [previewMode, runState, config.nodeId, isAwaitingSignal, viewingEpoch, currentItemIndex],
  );

  // The run row stays RUNNING while a node is parked (the backend never writes
  // AWAITING_SIGNAL to it), so `isRunning` on its own kept the app sweeping a
  // busy blue for the whole time it was in fact waiting for the user. Waiting
  // wins over running for exactly that reason.
  const runIndicatorState = runWideBlockers.length > 0
    ? 'awaiting' as const
    : (isRunning ? 'running' as const : null);

  const handleResolveApproval = React.useCallback(async (
    blocker: { nodeId: string; epoch?: number; itemId?: string },
    resolution: 'APPROVED' | 'REJECTED',
  ) => {
    // THROW, never return: the caller reads a resolved promise as "it landed"
    // and leaves its spinner up forever. Unreachable while the bar only renders
    // with a run, but a silent success is the one shape this bar exists to end.
    if (!runId || !runContext) throw new Error('No run bound to this application');
    // Straight onto the run context, which owns the resolve + re-hydrate. No
    // StepByStep context is needed (the application surface has none), and no
    // new plumbing: this is the same method the canvas approval node ends up in.
    await runContext.resolveApproval(runId, blocker.nodeId, resolution, blocker.epoch, blocker.itemId);
  }, [runId, runContext]);

  /**
   * Why the BUTTON's continue is awaited while the iframe's is not: the bridge
   * used to answer nobody, so a 403 or a 404 looked exactly like success and
   * the only thing that ever cleared the spinner was the 10 s safety valve
   * below. The in-page bridge action still tracks completion through the run
   * state it re-renders from; a button has no other way to learn it was
   * refused, and this change promotes that button out of a collapsed toolbar
   * onto an always-visible bar.
   */
  const [continueFailure, setContinueFailure] = React.useState<'failed' | 'forbidden' | null>(null);
  const handleDefaultContinue = React.useCallback(async () => {
    if (!runId || !config.nodeId || isContinuing) return;
    // Guard: don't send Continue for an already-resolved item (stale render data race)
    if (!isCurrentItemPending) return;
    setContinueFailure(null);
    setIsContinuing(true);
    const response = await requestInterfaceContinue({
      runId,
      nodeId: config.nodeId,
      actionKey: '__continue',
      data: {},
      itemIndex: currentItemIndex,
    });
    setIsContinuing(false);
    // Already resolved is not a failure: someone continued from another surface
    // and the run DID move; the refresh will unpark the node.
    if (response.ok || response.alreadyResolved) return;
    // Never `response.error`: that is the client's own English, and this screen
    // is read in six locales.
    setContinueFailure(response.status === 403 ? 'forbidden' : 'failed');
  }, [runId, config.nodeId, isContinuing, isCurrentItemPending, currentItemIndex]);

  // A refusal describes ONE question. Cleared when the run moves to another,
  // or the red line sits under an approval it never referred to - possibly for
  // good, since the Continue that would have cleared it may be gone.
  const currentBlockerKey = blockers[0]
    ? (blockers[0].kind === 'approval' ? `a${blockers[0].signalId}` : `i${blockers[0].nodeId}`)
    : null;
  React.useEffect(() => {
    setContinueFailure(null);
  }, [currentBlockerKey]);
  // ...and it expires on its own, matching the canvas Continue button. Without
  // this a 403 on a run that then sits parked leaves a red line up for good.
  React.useEffect(() => {
    if (!continueFailure) return;
    const timer = window.setTimeout(() => setContinueFailure(null), 8_000);
    return () => window.clearTimeout(timer);
  }, [continueFailure]);

  // Reset loading when awaiting state clears OR when execution progresses
  // (covers parallel epochs where isAwaitingSignal stays true because the
  // next epoch's signal is already registered before the current one resolves).
  React.useEffect(() => {
    if (!isAwaitingSignal) setIsContinuing(false);
  }, [isAwaitingSignal]);

  React.useEffect(() => {
    if (isContinuing) setIsContinuing(false);
  }, [executionTotal]); // eslint-disable-line react-hooks/exhaustive-deps

  // Safety valve: reset isContinuing after 10s if neither isAwaitingSignal nor
  // executionTotal changed (guards against silent backend errors where the event
  // bridge catches the error but never propagates it back to this component).
  React.useEffect(() => {
    if (!isContinuing) return;
    const timeout = setTimeout(() => setIsContinuing(false), 10_000);
    return () => clearTimeout(timeout);
  }, [isContinuing]);

  // ── Does the application on screen carry any audio/video? ──
  // Only the frame can answer (it is sandboxed and cross-origin), so it reports it
  // up here. The answer is kept LOCAL because it is the toolbar that needs it: a
  // speaker on a silent app promises a sound that does not exist.
  const [hasMediaAudio, setHasMediaAudio] = React.useState(false);

  // ── Toolbar collapsed/expanded toggle - use controlled props if provided, otherwise local state ──
  const [localToolbarOpen, setLocalToolbarOpen] = React.useState(false);
  const toolbarOpen = controlledToolbarOpen ?? localToolbarOpen;
  const setToolbarOpen = React.useCallback((open: boolean) => {
    if (onToolbarOpenChange) {
      onToolbarOpenChange(open);
    } else {
      setLocalToolbarOpen(open);
    }
  }, [onToolbarOpenChange]);

  // ── Epoch selector: badge with popup (same data as RunInfo EpochSelector) ──
  const [epochDropdownOpen, setEpochDropdownOpen] = React.useState(false);
  const epochDropdownRef = React.useRef<HTMLDivElement>(null);

  // Close dropdown on click outside
  React.useEffect(() => {
    if (!epochDropdownOpen) return;
    const handler = (e: MouseEvent) => {
      if (epochDropdownRef.current && !epochDropdownRef.current.contains(e.target as Node)) {
        setEpochDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [epochDropdownOpen]);

  // Current display epoch: viewingEpoch if set, otherwise latest epoch.
  // `null` means all of them, and the control says so in words - a bare number
  // there could only be read as "you are on epoch N".
  const currentDisplayEpoch = React.useMemo(() => {
    if (viewingEpoch != null) return viewingEpoch;
    if (epochTimestamps.length > 0) return Math.max(...epochTimestamps.map((e: any) => e.epoch ?? 1));
    return 1;
  }, [viewingEpoch, epochTimestamps]);
  const showsAllEpochs = viewingEpoch == null;

  // Sorted epochs (newest first) + durations (same as RunInfo EpochSelector)
  const sortedEpochs = React.useMemo(() => {
    const sorted = [...epochTimestamps].sort((a: any, b: any) => b.epoch - a.epoch);
    const now = Date.now();
    return sorted.map((entry: any) => {
      const startMs = entry.startedAt ? parseUtcAware(entry.startedAt).getTime() : 0;
      // Shared with the RunInfo EpochSelector on purpose: this used to compute
      // `endedAt - startedAt`, which is the epoch's LIFETIME. An epoch closes only
      // when it is reconciled (the next fire, a resume, a restart recovery sweep),
      // so that span counted idle time and printed 32h42m for epochs that ran for
      // seconds.
      // Same rule as the RunInfo EpochSelector: the epoch's own outcome, except while
      // the RUN says it is executing. An open header alone does not mean "running" - a
      // stopped or cancelled run abandons whatever epoch was open, and its duration
      // must stop counting there too.
      const badgeStatus = resolveEpochBadgeStatus(entry, runState?.runStatus);
      const isRunning = isEpochLive(entry, runState?.runStatus);
      const duration = epochDisplayDurationMs(entry, now, isRunning);
      return { ...entry, duration, isRunning, badgeStatus, startMs };
    });
  }, [epochTimestamps, runState?.runStatus]);

  const maxDuration = React.useMemo(() => Math.max(...sortedEpochs.map(e => e.duration ?? 0), 1), [sortedEpochs]);

  /** Status of the epoch currently on screen, for the collapsed selector button. */
  const displayedEpochStatus: string | null = React.useMemo(
    () => sortedEpochs.find(e => e.epoch === currentDisplayEpoch)?.badgeStatus ?? null,
    [sortedEpochs, currentDisplayEpoch],
  );

  // ── Template actions: load the publisher's example inputs, reset the data ──
  //
  // Neither is offered in a preview: an anonymous visitor must not write to the
  // publisher's tenant. Outside a preview the publication is always in hand, so what
  // is left to decide is per-action, below - `templateSource` says which publication,
  // not that the caller installed it.
  const templateActionsAvailable = !!templateSource && !previewMode && !isPreviewOnly;
  // A cloud-sourced (remote) install has no local publication row, so the reset
  // endpoint would have nothing to read. Loading the values still works - that read
  // goes through the cloud proxy.
  // Reset needs more than the publication: the caller must be on a surface where the
  // tables it rewrites are the ones on screen (canReset), and the publication must be
  // local. Loading the example values needs neither - it only fills the forms.
  // `=== true`, not `!== false`: the reset wipes real data, so an omitted flag must
  // withhold it rather than grant it. Every call site passes an explicit answer today;
  // this is what a future one that forgets gets.
  const canResetData = templateActionsAvailable
    && !templateSource?.remote
    && templateSource?.canReset === true;

  const [isLoadingTemplateValues, setIsLoadingTemplateValues] = React.useState(false);
  const [isResettingData, setIsResettingData] = React.useState(false);

  /**
   * Read the publisher's showcase render and seed every form with its trigger inputs.
   *
   * <p>The interface id is deliberately NOT passed: ours belongs to the INSTALLED
   * clone and does not exist in the publisher's frozen workflow, so asking for it
   * would miss. The publication's landing render is used instead, and that is
   * sufficient because `triggerData` is keyed by trigger key (a normalized label,
   * stable across the clone) rather than by interface - so the values apply to
   * whichever page the user is on.
   */
  const handleLoadTemplateValues = React.useCallback(async () => {
    if (!templateSource || isLoadingTemplateValues) return;
    setIsLoadingTemplateValues(true);
    setActionError(null);
    setActionNotice(null);
    try {
      const render = await publicationService.getShowcaseRender(
        templateSource.publicationId,
        { authenticated: true },
        !!templateSource.remote
      );
      const values = render?.items?.[0]?.triggerData;
      if (!values || Object.keys(values).length === 0) {
        setActionError(t('templateValuesEmpty'));
        return;
      }
      // Fresh object identity on every load so asking twice re-seeds the inputs
      // even when the values are unchanged.
      setTemplateValues({ ...values });
      // Open the trigger panel when there is one: that is where the user submits.
      if (hasPanelTriggers) setIsTriggerPanelOpen(true);
    } catch (err: any) {
      setActionError(err?.message || t('templateValuesFailed'));
    } finally {
      setIsLoadingTemplateValues(false);
    }
  }, [templateSource, isLoadingTemplateValues, hasPanelTriggers, t]);

  /**
   * Restore the installed application's tables to the rows frozen in the publication.
   * Destructive, so it confirms first; on success the interface is re-rendered so the
   * restored rows are visible immediately.
   */
  const handleResetData = React.useCallback(async () => {
    if (!templateSource || isResettingData) return;
    if (typeof window !== 'undefined' && !window.confirm(t('resetDataConfirm'))) return;
    setIsResettingData(true);
    setActionError(null);
    setActionNotice(null);
    try {
      const result = await publicationService.resetApplicationData(templateSource.publicationId);
      const partial = (result.tablesSkipped?.length ?? 0) > 0;
      // Reported in the component's own banner rather than the `workflowToast`
      // event: that listener lives on the builder canvas, which is not guaranteed
      // to be mounted on every surface showing an application.
      setActionNotice({
        type: partial ? 'warning' : 'success',
        message: partial
          ? t('resetDataPartial', {
              tables: result.tablesReset,
              rows: result.rowsRestored,
              skipped: result.tablesSkipped.join(', '),
            })
          : t('resetDataSuccess', { tables: result.tablesReset, rows: result.rowsRestored }),
      });
      refetch();
    } catch (err: any) {
      setActionError(err?.message || t('resetDataFailed'));
    } finally {
      setIsResettingData(false);
    }
  }, [templateSource, isResettingData, t, refetch]);

  /**
   * Stop, beside Launch.
   *
   * An application IS the run for the person using it: they fire a trigger from
   * this toolbar and then watch the interface. Until now the only way to stop
   * what they had just started was a control on another surface entirely - the
   * canvas pill, or the Run tab of the side panel - and on the application page
   * the user is looking at the interface, not at either of those.
   *
   * Scope, deliberately:
   *  - the STOP alone (RUNNING / PAUSED). Cancelling or re-arming a run is
   *    lifecycle management, which belongs to the run surfaces.
   *  - never in a preview, on EITHER signal (the embedder's `previewMode` and the
   *    run's own frozen flag), like the template actions above. A preview must
   *    not act on the publisher's workflow, and the canvas' refusal is silent -
   *    a dead click is the very thing this control exists to abolish.
   *  - never for a VIEWER: stopping someone's run is a mutation, and the repo
   *    gates mutations on the workspace role. (Launch, next to it, carries no
   *    such gate today - a separate, pre-existing gap, not one to widen here.)
   *  - NOT on a /s/<token> share, checked HERE rather than left to the panel
   *    tab's route scope in another file. The gateway's share allow-list covers
   *    firing triggers, interface actions and the form upload - not stopping a
   *    run - so a visitor's click would 403 every time: a control that is always
   *    dead, which is the very thing this change exists to abolish. Note the
   *    role check does NOT cover this: an anonymous visitor has no organisation,
   *    and `useCanMutateInCurrentOrg` reads a personal workspace as allowed.
   */
  const runActions = useRunActions(workflowId, runId, runSurfaceId);
  const canMutateRun = useCanMutateInCurrentOrg();
  const pathname = usePathname();
  const isPublicShareRoute = (pathname ?? '').startsWith('/s/');
  const canStopRun = !previewMode
    && !isPreviewOnly
    && !runActions.isPreviewOnly
    && !isPublicShareRoute
    && canMutateRun
    && resolveRunAction(runActions.status) === 'stop';

  // ── Shared toolbar extraControls (launch + epoch selector + continue) ──
  const toolbarExtraControls = React.useMemo(() => {
    // Launch button - opens the multi-trigger panel (TriggerPanel) when there
    // are chat/form/webhook triggers (works in any context: app page, agent
    // visualize popup, /s/<token> share). For workflows whose only trigger is
    // manual, the click fires inline (no panel needed). Hidden if no
    // launchable trigger exists.
    // Style mirrors the Continue button (Button size="sm" h-8 black-on-white
    // pill) so the toolbar's two action buttons read as peer controls.
    // Label/icon strategy:
    //   • exactly one panel trigger → show that trigger's label + per-type
    //     icon (the click feels deterministic).
    //   • two or more panel triggers → generic "Launch" + Play icon - the
    //     button doesn't claim a specific trigger because the panel auto-
    //     selects the first tab but the user can switch.
    //   • zero panel triggers + manual → the manual label + Play icon (inline
    //     fire, no panel).
    const panelCount = launchable.panelConfigs.length;
    const onlyPanelCfg = panelCount === 1 ? launchable.panelConfigs[0] : null;
    // Precedence ladder for label + type + icon: panel triggers (the
    // explicit chat/form/webhook author intent) > manual > schedule. The
    // schedule fallback is the "force fire cron now" affordance for
    // workflows whose only trigger is a periodic schedule.
    const buttonLabel = onlyPanelCfg?.triggerLabel
      || (panelCount > 1 ? tActions('launch') : null)
      || launchable.firstManual?.label
      || launchable.firstSchedule?.label
      || tActions('launch');
    const buttonType: 'chat' | 'form' | 'webhook' | 'manual' | 'multi' | 'schedule' | null =
      onlyPanelCfg?.type
      ?? (panelCount > 1 ? 'multi' : null)
      ?? (launchable.firstManual ? 'manual' : null)
      ?? (launchable.firstSchedule ? 'schedule' : null);
    const TriggerIcon = buttonType === 'form' ? FormInput
      : buttonType === 'chat' ? MessageCircle
      : buttonType === 'webhook' ? Webhook
      : buttonType === 'manual' ? Play
      : buttonType === 'multi' ? Play
      : buttonType === 'schedule' ? Calendar
      : null;
    // Hide the Launch button entirely in preview mode - anonymous visitors
    // browsing a marketplace publication MUST NOT be able to fire the
    // publisher's workflow (the backend would 403, but surfacing the button
    // is a confusing affordance). The publisher viewing their own preview
    // sees exactly what the visitor sees by contract - same gate applies.
    const launchButton = !previewMode && hasAnyLaunchable && TriggerIcon ? (
      <Button
        key="launch"
        onClick={handleLaunchTrigger}
        // Disabled iff we're already firing OR we'd have nothing to fire:
        // - panel triggers always render the panel (no runId required)
        // - manual needs runId (it resumes a WAITING_TRIGGER run)
        // - schedule needs only workflowId (spawns a fresh run server-side)
        disabled={isLaunching || (!hasPanelTriggers && !runId && !launchable.firstSchedule)}
        size="sm"
        className="h-8 px-3 rounded-xl shadow-none border-0 gap-1.5 focus-visible:ring-theme-tertiary disabled:opacity-50"
        title={tActions('launchTrigger', { label: buttonLabel })}
      >
        {isLaunching ? <LoadingSpinner size="sm" /> : <TriggerIcon className="h-3.5 w-3.5" />}
        <span className="text-xs font-medium">{buttonLabel}</span>
      </Button>
    ) : null;

    const epochSelector = totalEpochs > 0 && runId ? (
      <div key="epoch" ref={epochDropdownRef} className="relative flex items-center">
        {/* Epoch badge button */}
        <button
          type="button"
          onClick={() => totalEpochs > 1 ? setEpochDropdownOpen(prev => !prev) : undefined}
          className={`h-7 flex items-center gap-1.5 px-2.5 rounded-xl text-xs transition-colors ${
            epochDropdownOpen
              ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
              : 'text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)]'
          }`}
          data-testid="application-epoch-selector"
          data-all-epochs={showsAllEpochs || undefined}
          data-epoch-status={(!showsAllEpochs && displayedEpochStatus) || undefined}
          title={showsAllEpochs
            ? tRun('allEpochs')
            : [tRun('epochBadge', { number: currentDisplayEpoch }),
               displayedEpochStatus ? getRunStatusLabel(displayedEpochStatus, (k) => tRoot(k)) : null,
              ].filter(Boolean).join('\n')}
        >
          <Calendar className="h-3 w-3" />
          <span className={`font-medium ${showsAllEpochs ? '' : 'tabular-nums'}`}>
            {showsAllEpochs ? tRun('allEpochs') : currentDisplayEpoch}
          </span>
          {/* Closed dropdown still tells the outcome of the epoch on screen - the
              status is the reason to open the list, so it must not require opening it. */}
          {!showsAllEpochs && <EpochStatusIcon status={displayedEpochStatus} />}
        </button>

        {/* Epoch dropdown popup (same layout as RunInfo EpochSelector) */}
        {epochDropdownOpen && totalEpochs > 1 && (
          <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 z-50 rounded-xl bg-white/95 dark:bg-gray-800/95 backdrop-blur shadow-xl border border-slate-200 dark:border-slate-700 py-1.5 min-w-[260px] max-h-[240px] overflow-y-auto">
            {/* Back to the cumulative view. Without it, picking one fire here
                was a one-way door: nothing in this tab could undo it. */}
            <button
              type="button"
              onClick={() => {
                handleEpochPickedByUser(null);
                setEpochDropdownOpen(false);
              }}
              data-testid="application-epoch-option-all"
              data-selected={showsAllEpochs || undefined}
              className={`w-full flex items-center gap-1.5 px-3 py-1.5 text-xs transition-colors ${
                showsAllEpochs
                  ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
                  : 'border-l-2 border-transparent hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
              }`}
            >
              <div className="w-5 shrink-0 flex items-center justify-center">
                <Calendar className="h-3 w-3" />
              </div>
              <span className="flex-1 text-left">{tRun('allEpochs')}</span>
            </button>

            {sortedEpochs.map((entry) => {
              const isSelected = !showsAllEpochs && entry.epoch === currentDisplayEpoch;
              const barPct = maxDuration > 0 ? Math.max(5, ((entry.duration ?? 0) / maxDuration) * 100) : 5;

              return (
                <button
                  key={entry.epoch}
                  type="button"
                  onClick={() => {
                    handleEpochPickedByUser(entry.epoch);
                    setEpochDropdownOpen(false);
                  }}
                  data-testid={`application-epoch-option-${entry.epoch}`}
                  data-epoch-status={entry.badgeStatus ?? undefined}
                  /* The status word: the row is too narrow for a text pill, so the
                     badge is the icon and the title carries the name. */
                  title={entry.badgeStatus
                    ? getRunStatusLabel(entry.badgeStatus, (k) => tRoot(k))
                    : undefined}
                  className={`w-full flex items-center gap-1.5 px-3 py-1.5 text-xs transition-colors ${
                    isSelected
                      ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
                      : 'border-l-2 border-transparent hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
                  }`}
                >
                  {/* Epoch number */}
                  <div className={`w-5 shrink-0 text-center tabular-nums ${
                    isSelected ? 'font-bold text-gray-900 dark:text-gray-100' : 'font-medium text-gray-500 dark:text-gray-400'
                  }`}>
                    {entry.epoch}
                  </div>
                  {/* Per-epoch status badge - reserved slot, so the row never shifts
                      as an epoch goes from running to its outcome. The word is added
                      for screen readers ADDITIVELY: an aria-label on the button would
                      replace the time range and duration it also announces. */}
                  <EpochStatusIcon status={entry.badgeStatus} />
                  {entry.badgeStatus && (
                    <span className="sr-only">{getRunStatusLabel(entry.badgeStatus, (k) => tRoot(k))}</span>
                  )}
                  {/* Time range */}
                  <span className={`flex-1 text-center text-[10px] tabular-nums whitespace-nowrap ${
                    entry.isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
                  }`}>
                    {entry.startedAt ? `${formatEpochTime(entry.startedAt)} → ${entry.endedAt ? formatEpochTime(entry.endedAt) : '...'}` : '-'}
                  </span>
                  {/* Duration bar */}
                  <div className="w-12 h-[3px] rounded-full bg-gray-100 dark:bg-white/[0.06] overflow-hidden shrink-0">
                    <div
                      className={`h-full rounded-full transition-all ${
                        entry.isRunning ? 'bg-blue-500 animate-pulse' : isSelected ? 'bg-emerald-500' : 'bg-emerald-500/70'
                      }`}
                      style={{ width: `${barPct}%` }}
                    />
                  </div>
                  {/* Duration text */}
                  <span className={`min-w-[32px] text-right text-[10px] tabular-nums font-medium shrink-0 ${
                    entry.isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
                  }`}>
                    {entry.duration != null ? formatEpochDuration(entry.duration) : ''}
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </div>
    ) : null;

    // Same risk class as launchButton: Continue dispatches
    // `workflowInterfaceContinue` which routes through
    // `useWorkflowEventBridge` → authed `interfaceService.fireInterfaceAction`
    // against the publisher's tenant. The event bridge IS mounted on the
    // preview page (WorkflowRunCanvas runs inside ApplicationDetailView even
    // in preview), so leaving the button visible is a confusing 403-pending
    // affordance for visitors. Hide it for consistency with launchButton.
    // In split context, the Continue button resolves ONLY the currently displayed
    // item (not all pending). Label communicates per-item scope; tooltip changes
    // when the current item is already resolved so the user understands the
    // disabled state.
    const continueLabel = t('continueWorkflow');
    const isItemResolved = !isCurrentItemPending && !isContinuing;
    // Tooltip context: WHICH epoch/item will be continued. Epoch is the raw
    // epoch number (matches the epoch selector); item is 1-based.
    const continueContext = currentItemTriple
      ? `${tRun('epochBadge', { number: currentItemTriple.epoch })} · ${tRun('itemBadge', { number: (currentItemTriple.itemIndex ?? 0) + 1 })}`
      : null;
    const continueBaseTitle = pendingSignalCount > 1
      ? t('continueItemRemaining', { count: pendingSignalCount })
      : continueLabel;
    const continueTitle = isItemResolved
      ? t('itemAlreadyResolved')
      : continueContext
        ? `${continueBaseTitle} - ${continueContext}`
        : continueBaseTitle;
    const continueButton = !previewMode && isAwaitingSignal && runId && config.nodeId ? (
      <Button
        key="continue"
        onClick={handleDefaultContinue}
        disabled={isContinuing || !isCurrentItemPending}
        size="sm"
        className="h-8 px-3 rounded-xl shadow-none border-0 gap-1.5 focus-visible:ring-theme-tertiary disabled:opacity-50"
        title={continueTitle}
      >
        {isContinuing ? <LoadingSpinner size="sm" /> : <StepForward className="h-3.5 w-3.5" />}
        <span className="text-xs font-medium">{continueLabel}</span>
        {pendingSignalCount > 1 && (
          <span className="inline-flex items-center justify-center h-4 min-w-[16px] px-1 rounded-md bg-white/20 dark:bg-black/20 text-[10px] font-semibold tabular-nums">
            {pendingSignalCount}
          </span>
        )}
      </Button>
    ) : null;

    const variablePaginationControl = activeVariablePage ? (
      <div key="variable-pagination" className="flex items-center gap-1" data-testid="application-variable-pagination">
        {variablePaginationItems.length > 1 ? (
          <select
            value={activeVariablePage.name}
            onChange={(event) => setActiveVariablePageName(event.target.value)}
            aria-label={tCanvas('variablePageSource')}
            title={tCanvas('variablePageSource')}
            data-testid="application-variable-page-select"
            className="h-7 max-w-[92px] rounded-xl bg-transparent px-2 text-xs font-medium text-[var(--text-secondary)] outline-none hover:bg-gray-200 dark:hover:bg-gray-700 hover:text-gray-900 dark:hover:text-gray-100"
          >
            {variablePaginationItems.map(item => (
              <option key={item.name} value={item.name}>{item.name}</option>
            ))}
          </select>
        ) : (
          <span
            className="max-w-[92px] truncate px-2 text-xs font-medium text-[var(--text-secondary)]"
            title={activeVariablePage.name}
          >
            {activeVariablePage.name}
          </span>
        )}
        <button
          type="button"
          onClick={handleVariablePrevious}
          disabled={activeVariablePage.page <= 0}
          aria-label={tCanvas('previousVariablePage')}
          title={tCanvas('previousVariablePage')}
          data-testid="application-variable-page-previous"
          className="w-7 h-7 p-0 rounded-xl transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
        </button>
        <span
          className="text-xs text-[var(--text-secondary)] font-medium min-w-[42px] text-center tabular-nums"
          data-testid="application-variable-page-label"
        >
          {activeVariablePage.page + 1} / {activeVariablePage.totalPages}
        </span>
        <button
          type="button"
          onClick={handleVariableNext}
          disabled={activeVariablePage.page >= activeVariablePage.totalPages - 1}
          aria-label={tCanvas('nextVariablePage')}
          title={tCanvas('nextVariablePage')}
          data-testid="application-variable-page-next"
          className="w-7 h-7 p-0 rounded-xl transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
        >
          <ChevronRight className="h-3.5 w-3.5" />
        </button>
      </div>
    ) : null;

    // Template values: fills the forms with the publisher's example inputs. Icon-only
    // so the pill stays compact next to Launch / Continue, which are the actions that
    // actually run something - this one only prepares them.
    const templateValuesButton = templateActionsAvailable ? (
      <button
        key="template-values"
        type="button"
        onClick={handleLoadTemplateValues}
        disabled={isLoadingTemplateValues}
        aria-label={t('loadTemplateValues')}
        title={t('loadTemplateValuesHint')}
        data-testid="application-load-template-values"
        className="w-7 h-7 p-0 rounded-xl transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
      >
        {isLoadingTemplateValues ? <LoadingSpinner size="sm" /> : <Wand2 className="h-3.5 w-3.5" />}
      </button>
    ) : null;

    // Reset data: destructive, so it is visually quiet (icon-only, same weight as the
    // pagination controls) and confirms before doing anything.
    const resetDataButton = canResetData ? (
      <button
        key="reset-data"
        type="button"
        onClick={handleResetData}
        disabled={isResettingData}
        aria-label={t('resetData')}
        title={t('resetDataHint')}
        data-testid="application-reset-data"
        className="w-7 h-7 p-0 rounded-xl transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
      >
        {isResettingData ? <LoadingSpinner size="sm" /> : <RotateCcw className="h-3.5 w-3.5" />}
      </button>
    ) : null;

    // Sound: wherever the embedder owns the volume (the application page), the app
    // starts muted, so this button is the one place a visitor turns it back on. It
    // lives with the application controls rather than behind a separate floating cog:
    // it is a view control, peer to pagination and fullscreen, not an action that runs
    // something. Absent when nobody owns the volume (mediaMuted undefined = play as
    // authored) or when the page holds no media at all - a speaker on a silent app
    // promises a sound that does not exist.
    const canToggleSound = mediaMuted !== undefined && !!onToggleMediaMuted && hasMediaAudio;
    const soundLabel = mediaMuted ? tSound('unmuteSound') : tSound('muteSound');
    const soundButton = canToggleSound ? (
      <button
        key="sound"
        type="button"
        onClick={onToggleMediaMuted}
        aria-pressed={!mediaMuted}
        aria-label={soundLabel}
        title={soundLabel}
        data-testid="application-sound-toggle"
        className="w-7 h-7 p-0 rounded-xl transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
      >
        {mediaMuted ? <VolumeX className="h-3.5 w-3.5" /> : <Volume2 className="h-3.5 w-3.5" />}
      </button>
    ) : null;

    // Built INSIDE the memo: a JSX element has a fresh identity every render, so
    // holding it in a dependency would add one more guaranteed-changing entry to
    // this list. (It would not be the only one - `epochTimestamps` above already
    // allocates a fresh array per render - but adding a second is not a reason to
    // add a second.)
    const stopButton = canStopRun ? (
      <RunActionButton
        key="stop"
        status={runActions.status}
        onStop={() => runActions.perform('stop')}
        pendingAction={runActions.pending}
        failed={runActions.failed}
        size="panel"
      />
    ) : null;

    if (!variablePaginationControl && !launchButton && !epochSelector && !continueButton
        && !templateValuesButton && !resetDataButton && !soundButton && !stopButton) {
      return undefined;
    }
    return <>{soundButton}{variablePaginationControl}{templateValuesButton}{resetDataButton}{launchButton}{stopButton}{epochSelector}{continueButton}</>;
  }, [canStopRun, runActions.status, runActions.pending, runActions.failed, runActions.perform, mediaMuted, onToggleMediaMuted, hasMediaAudio, tSound, totalEpochs, epochTimestamps, sortedEpochs, maxDuration, viewingEpoch, showsAllEpochs, currentDisplayEpoch, displayedEpochStatus, epochDropdownOpen, handleViewEpoch, handleEpochPickedByUser, runId, isAwaitingSignal, config.nodeId, isContinuing, isCurrentItemPending, handleDefaultContinue, t, tRun, tRoot, currentItemTriple, pendingSignalCount, launchable, hasPanelTriggers, hasAnyLaunchable, handleLaunchTrigger, isLaunching, tActions, previewMode, activeVariablePage, variablePaginationItems, handleVariablePrevious, handleVariableNext, tCanvas, templateActionsAvailable, canResetData, handleLoadTemplateValues, isLoadingTemplateValues, handleResetData, isResettingData]);

  // ── The interface's display format - scale-to-fit virtual viewport ──
  // When the INTERFACE declares a format (preset name or "WxH"), the iframe renders inside a
  // virtual viewport of EXACTLY width x height CSS px, CSS transform: scale()d to CONTAIN in
  // the container (min ratio, centered both axes, letterboxed). transform keeps the iframe
  // fully interactive. Without a format the native w-full h-full path is untouched.
  // The render result wins over the entity: a run reads its frozen snapshot, which may carry
  // the shape the interface had when the run started.
  const formatViewport = React.useMemo(
    () => resolveInterfaceFormat(renderData?.format ?? interfaceDetails?.format),
    [renderData?.format, interfaceDetails?.format],
  );
  // Letterbox measurement: useState callback ref (same rationale as
  // appContainerEl below) + ResizeObserver so the scale tracks panel resizes.
  const [formatBoxEl, setFormatBoxEl] = React.useState<HTMLDivElement | null>(null);
  // The LETTERBOXED area itself - the scaled box the application actually
  // occupies. Distinct from `formatBoxEl` (the full-size container the box is
  // centred in) and from `formatViewport` (the UNSCALED declared dimensions,
  // carried by the inner `application-format-viewport` element). The trigger panel anchors on this one: it has to fit,
  // and centre on, the application the user sees. Anchoring on the container
  // instead made the anchor ~= the window whenever a format was declared, so
  // a phone-format app inside a wide browser still got a full-width panel
  // spilling past both its edges - the reported symptom.
  const [letterboxEl, setLetterboxEl] = React.useState<HTMLDivElement | null>(null);
  const [formatBox, setFormatBox] = React.useState({ width: 0, height: 0 });
  React.useLayoutEffect(() => {
    if (!formatViewport || !formatBoxEl) return;
    const update = () => {
      const rect = formatBoxEl.getBoundingClientRect();
      setFormatBox(prev =>
        prev.width === rect.width && prev.height === rect.height
          ? prev
          : { width: rect.width, height: rect.height },
      );
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(formatBoxEl);
    return () => ro.disconnect();
  }, [formatViewport, formatBoxEl]);
  // Clamped at 1: a box larger than the format viewport renders at native
  // size (centered), never upscaled - transform-upscaling rasterizes the
  // iframe blurry. Only downscaling ever happens.
  const formatScale = formatViewport && formatBox.width > 0 && formatBox.height > 0
    ? Math.min(1, formatBox.width / formatViewport.width, formatBox.height / formatViewport.height)
    : 0;

  // ── Shared iframe content ──
  // The application stays visible at all times. An overlay on top says what the
  // run is doing: blue and sweeping while the engine executes, amber and still
  // while it waits on a person - see RunStateIndicator. Deliberately NOT keyed
  // on `runStatus`, which reads `running` for the whole time a node is parked.
  // Rendered here (inside the shared content) so every surface that shows an
  // application gets the same border in both panel and fullscreen modes.
  const renderApplicationIframe = (sizing: { className?: string; style: React.CSSProperties }) => (
    <InterfaceIframe
      htmlTemplate={htmlTemplate}
      mode={renderMode}
      resolvedData={hasResolvedData ? resolvedData : undefined}
      customCss={centeringCss + (renderData?.cssTemplate || interfaceDetails?.cssTemplate || '')}
      jsTemplate={(renderData as any)?.jsTemplate || (interfaceDetails as any)?.jsTemplate || undefined}
      className={sizing.className}
      style={sizing.style}
      sandbox="allow-same-origin allow-scripts allow-forms"
      actionMapping={config.actionMapping}
      triggerData={triggerData}
      prefillTriggerData={templateValues ?? undefined}
      onAction={safeOnAction}
      onPagination={handleIframePagination}
      onContinue={handleContinue}
      onVariablePagination={handleVariablePagination}
      fileUploadContext={workflowId && runId ? { workflowId, runId } : undefined}
      mediaMuted={mediaMuted}
      onMediaAudioPresence={setHasMediaAudio}
    />
  );
  const iframeContent = (
    <div className="relative w-full h-full">
      {!htmlTemplate ? (
        <div className="flex items-center justify-center h-full text-slate-400 dark:text-slate-500 text-sm">
          No template configured
        </div>
      ) : formatViewport ? (
        // Contain structure mirrors InterfaceThumbnail's contain mode: an
        // intermediate box sized to the SCALED dims centers the letterbox,
        // the inner div holds the exact virtual viewport with scale(s) from
        // the top-left. Nothing renders until the box is measured (one frame).
        <div
          ref={setFormatBoxEl}
          className="w-full h-full flex items-center justify-center overflow-hidden"
        >
          {formatScale > 0 && (
            <div
              ref={setLetterboxEl}
              data-testid="application-format-scaled-box"
              style={{
                width: formatViewport.width * formatScale,
                height: formatViewport.height * formatScale,
                position: 'relative',
                flex: 'none',
              }}
            >
              <div
                data-testid="application-format-viewport"
                style={{
                  width: formatViewport.width,
                  height: formatViewport.height,
                  transform: `scale(${formatScale})`,
                  transformOrigin: '0 0',
                }}
              >
                {renderApplicationIframe({
                  style: { width: formatViewport.width, height: formatViewport.height },
                })}
              </div>
            </div>
          )}
        </div>
      ) : (
        renderApplicationIframe({ className: 'w-full h-full', style: { height: '100%' } })
      )}
      {/* Smooth loading overlay during transitions (epoch change, pagination, execution refetch) */}
      <div
        className="absolute inset-0 bg-white/60 dark:bg-slate-900/60 flex items-center justify-center pointer-events-none"
        style={{
          opacity: isTransitioning ? 1 : 0,
          transition: 'opacity 200ms ease-in-out',
        }}
      >
        <LoadingSpinner size="sm" />
      </div>
      {/* Run-state indicator - overlays the app (still visible underneath)
          while the run executes or waits on a human. Renders nothing otherwise. */}
      <RunStateIndicator
        state={runIndicatorState}
        label={
          runIndicatorState !== 'awaiting'
            ? tActions('running')
            // "for you" only when this viewer actually has something to answer
            // here. On a preview, or on an older epoch, the run IS parked (so the
            // ring is amber) but it is not waiting on the reader.
            : blockers.length > 0 ? tRun('awaitingYou') : tRun('awaitingSomeone')
        }
      />
      {/* The action bar sits INSIDE the shared content on purpose: that is what
          gives it to every surface at once (right side panel, application page,
          chat card, fullscreen) instead of once per branch. Above the
          application toolbar, which is collapsed by default - the reason the
          Continue button was effectively hidden until now. */}
      {blockers.length > 0 && (
        <div className="absolute bottom-20 inset-x-0 z-40 flex justify-center pointer-events-none px-3">
          <RunActionBar
            blockers={blockers}
            displayedInterfaceNodeId={config.nodeId}
            onContinue={handleDefaultContinue}
            onResolveApproval={handleResolveApproval}
            isContinuing={isContinuing}
            continueDisabled={!isCurrentItemPending}
            continueFailure={continueFailure}
          />
        </div>
      )}
    </div>
  );

  // Element ref (useState pattern, not useRef) for the application
  // container. The TriggerPanel reads its bounding rect to center its
  // dropdown on the iframe rather than the viewport - without this, the
  // panel sits at viewport-center which is OFF the visible application area
  // in side-panel layouts (workflow inspector, marketplace shell). We use
  // useState + callback ref instead of useRef so the parent re-renders
  // once the DOM node attaches, propagating the element down to TriggerPanel
  // (a plain useRef.current would be `null` at the time the JSX is built
  // and the TriggerPanel would never see the anchor).
  const [appContainerEl, setAppContainerEl] = React.useState<HTMLDivElement | null>(null);

  // Track the preview container's viewport rect so the portalled floating
  // toolbar (previewMode branch below) lands at the bottom-center of the
  // preview panel - NOT the bottom-center of the viewport. The portal escapes
  // the marketplace shell's overflow-hidden clipping, but `fixed`
  // positioning would otherwise dock the toolbar to the page bottom which is
  // visibly wrong on the marketplace page (header + sidebar take ~250px on
  // the left). Recompute on resize / scroll so the toolbar follows panel
  // movement (e.g. inspector pane open/close, mobile rotation).
  const [containerRect, setContainerRect] = React.useState<DOMRect | null>(null);
  React.useLayoutEffect(() => {
    if (!previewMode || !appContainerEl) {
      setContainerRect(null);
      return;
    }
    const update = () => setContainerRect(appContainerEl.getBoundingClientRect());
    update();
    const ro = new ResizeObserver(update);
    ro.observe(appContainerEl);
    // Capture phase catches scroll on any ancestor (the marketplace shell
    // may scroll independently of window).
    window.addEventListener('scroll', update, true);
    window.addEventListener('resize', update);
    return () => {
      ro.disconnect();
      window.removeEventListener('scroll', update, true);
      window.removeEventListener('resize', update);
    };
  }, [previewMode, appContainerEl]);

  // ── Expanded (fullscreen) mode - portal overlay ──
  if (isExpanded) {
    return createPortal(
      <div ref={setAppContainerEl} className="fixed inset-0 z-[9999] flex flex-col bg-white dark:bg-slate-900 group/expanded">
        {/* Top bar: close button - always visible in fullscreen */}
        <div className="fixed top-4 right-4 z-[10000]">
          <Button
            onClick={handleToggleExpanded}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-xl shadow-none border-0 bg-white/40 dark:bg-gray-800/40 backdrop-blur-sm hover:bg-white/90 dark:hover:bg-gray-800/90 opacity-50 hover:opacity-100 transition-all duration-200"
            title="Close (Escape)"
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Content - full viewport iframe (the running indicator is rendered
            inside iframeContent so it traces this area while executing) */}
        <div className="flex-1 w-full h-full overflow-y-auto relative">
          {iframeContent}
        </div>

        {/* Floating pill toolbar - bottom center (collapsed/expanded toggle) */}
        {(hasActions || carouselControls) && (
          <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[10000]">
            {toolbarOpen ? (
              <InterfaceToolbar
                currentPage={currentPage}
                totalPages={activeVariablePage ? 0 : effectiveSpawnPages}
                pageLabel={activeVariablePage ? undefined : itemPageLabel}
                pageBadge={reExecutionBadge}
                onPrevious={handlePrevious}
                onNext={handleNext}
                onFullscreen={handleToggleExpanded}
                isFullscreen
                variant="light"
                leadingControls={carouselControls}
                extraControls={toolbarExtraControls}
                onClose={() => setToolbarOpen(false)}
              />
            ) : (
              <button
                type="button"
                onClick={() => setToolbarOpen(true)}
                aria-label={openControlsLabel}
                title={openControlsLabel}
                data-testid="application-controls-toggle"
                className="h-8 w-8 rounded-xl flex items-center justify-center bg-white/40 dark:bg-gray-800/40 backdrop-blur-sm text-gray-400 dark:text-gray-500 hover:bg-white/90 dark:hover:bg-gray-800/90 hover:text-gray-700 dark:hover:text-gray-200 opacity-50 hover:opacity-100 transition-all duration-200"
              >
                <Grip className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
        )}

        {/* Preview mode toast (fullscreen) */}
        {showPreviewToast && (
          <div className="fixed inset-x-0 top-16 z-[10001] flex justify-center pointer-events-none animate-in fade-in slide-in-from-top-2 duration-300">
            <div className="pointer-events-auto flex items-start gap-3 max-w-sm w-full mx-4 px-4 py-3 rounded-xl bg-amber-50 dark:bg-amber-950/60 border border-amber-200 dark:border-amber-800 shadow-lg">
              <Lock className="h-5 w-5 text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5" />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold text-amber-900 dark:text-amber-200">
                  {t('previewModeTitle')}
                </p>
                <p className="text-xs text-amber-700 dark:text-amber-400 mt-0.5">
                  {t('previewModeMessage')}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowPreviewToast(false)}
                className="pointer-events-auto flex-shrink-0 text-amber-400 hover:text-amber-600 dark:hover:text-amber-300"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
        )}
        {/* Multi-trigger panel (fullscreen mode mirror - see normal-mode return). */}
        {runId && (
          <TriggerPanel
            isOpen={isTriggerPanelOpen}
            onClose={() => setIsTriggerPanelOpen(false)}
            runId={runId}
            workflowId={workflowId}
            triggerConfigs={launchable.panelConfigs}
            onExecuteTrigger={handlePanelExecuteTrigger}
            onTriggerSuccess={() => setIsTriggerPanelOpen(false)}
            anchorElement={letterboxEl ?? appContainerEl}
            prefillValues={templateValues}
          />
        )}
      </div>,
      document.body,
    );
  }

  // ── Normal (panel) mode ──
  return (
    <div ref={setAppContainerEl} className="flex-1 flex flex-col min-h-0 relative">
      {/* Interface iframe - full bleed, no padding. While the run is executing,
          iframeContent overlays the running indicator (app stays visible). */}
      <div
        className="flex-1 min-h-0 overflow-hidden"
        style={isDragging ? { pointerEvents: 'none' } : undefined}
      >
        <div className="w-full h-full relative">
          {iframeContent}
        </div>
      </div>

      {/* Error banner */}
      {actionError && (
        <div className="flex-shrink-0 w-full px-2">
          <div className="flex items-start gap-2 px-3 py-2 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-800 rounded-md text-sm text-red-700 dark:text-red-400">
            <AlertCircle className="h-4 w-4 flex-shrink-0 mt-0.5" />
            <span className="flex-1 break-words">{actionError}</span>
            <button
              type="button"
              onClick={() => setActionError(null)}
              className="flex-shrink-0 text-red-400 hover:text-red-600 dark:hover:text-red-300"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      )}

      {/* Outcome of a template action (data reset). Amber when the reset was
          PARTIAL so "some tables were left alone" never reads as a clean success. */}
      {actionNotice && (
        <div className="flex-shrink-0 w-full px-2">
          <div
            data-testid="application-action-notice"
            className={actionNotice.type === 'warning'
              ? 'flex items-start gap-2 px-3 py-2 bg-amber-50 dark:bg-amber-950/30 border border-amber-200 dark:border-amber-800 rounded-md text-sm text-amber-800 dark:text-amber-300'
              : 'flex items-start gap-2 px-3 py-2 bg-emerald-50 dark:bg-emerald-950/30 border border-emerald-200 dark:border-emerald-800 rounded-md text-sm text-emerald-700 dark:text-emerald-400'}
          >
            <span className="flex-1 break-words">{actionNotice.message}</span>
            <button
              type="button"
              onClick={() => setActionNotice(null)}
              className="flex-shrink-0 opacity-60 hover:opacity-100"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      )}

      {/* Floating pill toolbar: collapsed/expanded toggle.
          Preview mode portals the block to document.body so it escapes
          the marketplace shell's `overflow-hidden` clipping (the root
          bug). Position is COMPUTED from the panel's bounding rect so
          the toolbar lands at the bottom-center of the preview PANEL,
          not the viewport - a viewport-centered toolbar would dock under
          the app shell's left sidebar on the marketplace page (~250px
          inset). Same z-index as the fullscreen toolbar so it floats
          above any publisher-supplied iframe content.
          Non-preview surfaces (workflow side-panel) keep the in-
          container `absolute bottom-4` positioning that respects the
          side-panel layout.
          Fullscreen (onFullscreen) IS available in preview: the expanded
          view is its own `fixed inset-0` portal to document.body, so it
          overlays the whole viewport (escaping the marketplace shell) and
          `isExpanded` toggles through the carousel-lifted state. */}
      {(hasActions || carouselControls) && (() => {
        const toolbarBlock = toolbarOpen ? (
          <InterfaceToolbar
            currentPage={currentPage}
            totalPages={activeVariablePage ? 0 : effectiveSpawnPages}
            pageLabel={activeVariablePage ? undefined : itemPageLabel}
            pageBadge={reExecutionBadge}
            onPrevious={handlePrevious}
            onNext={handleNext}
            onFullscreen={htmlTemplate ? handleToggleExpanded : undefined}
            variant="light"
            leadingControls={carouselControls}
            extraControls={toolbarExtraControls}
            onClose={() => setToolbarOpen(false)}
          />
        ) : (
          <button
            type="button"
            onClick={() => setToolbarOpen(true)}
            aria-label={openControlsLabel}
            title={openControlsLabel}
            data-testid="application-controls-toggle"
            className="h-8 w-8 rounded-xl flex items-center justify-center bg-white/40 dark:bg-gray-800/40 backdrop-blur-sm text-gray-400 dark:text-gray-500 hover:bg-white/90 dark:hover:bg-gray-800/90 hover:text-gray-700 dark:hover:text-gray-200 opacity-50 hover:opacity-100 transition-all duration-200"
          >
            <Grip className="h-3.5 w-3.5" />
          </button>
        );
        if (previewMode) {
          // Wait for the container rect before mounting - without it the
          // portal would flash at the top-left corner for one frame.
          if (!containerRect) return null;
          // Translate "bottom-4 left-1/2" of the container into fixed-position
          // coordinates so the toolbar lands at the same visual spot it would
          // have if we hadn't portalled it. `bottom` is computed from the
          // viewport bottom so the toolbar tracks the container as it scrolls.
          const TOOLBAR_INSET_PX = 16; // matches the old `bottom-4` Tailwind utility
          return createPortal(
            <div
              style={{
                position: 'fixed',
                bottom: Math.max(0, window.innerHeight - containerRect.bottom + TOOLBAR_INSET_PX),
                left: containerRect.left + containerRect.width / 2,
                transform: 'translateX(-50%)',
                zIndex: 10000,
              }}
            >
              {toolbarBlock}
            </div>,
            document.body,
          );
        }
        return (
          <div className="absolute bottom-4 left-1/2 -translate-x-1/2 z-30">
            {toolbarBlock}
          </div>
        );
      })()}

      {/* Preview mode toast - shown when user tries to trigger an action */}
      {showPreviewToast && (
        <div className="absolute inset-x-0 top-4 z-50 flex justify-center pointer-events-none animate-in fade-in slide-in-from-top-2 duration-300">
          <div className="pointer-events-auto flex items-start gap-3 max-w-sm w-full mx-4 px-4 py-3 rounded-xl bg-amber-50 dark:bg-amber-950/60 border border-amber-200 dark:border-amber-800 shadow-lg">
            <Lock className="h-5 w-5 text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-semibold text-amber-900 dark:text-amber-200">
                {t('previewModeTitle')}
              </p>
              <p className="text-xs text-amber-700 dark:text-amber-400 mt-0.5">
                {t('previewModeMessage')}
              </p>
            </div>
            <button
              type="button"
              onClick={() => setShowPreviewToast(false)}
              className="pointer-events-auto flex-shrink-0 text-amber-400 hover:text-amber-600 dark:hover:text-amber-300"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      )}
      {/* Multi-trigger panel - opens from the toolbar Launch button. Mounts
          self-contained (floating, draggable) so it works anywhere this
          component renders: /app/applications/<id>, the visualize-card popup
          panel, and /s/<token> share pages (no WorkflowPanelContent
          dependency). chat/form/webhook tabs are auto-built from
          launchable.panelConfigs (sourced from the canvas dispatch). */}
      {runId && (
        <TriggerPanel
          isOpen={isTriggerPanelOpen}
          onClose={() => setIsTriggerPanelOpen(false)}
          runId={runId}
          workflowId={workflowId}
          triggerConfigs={launchable.panelConfigs}
          onExecuteTrigger={handlePanelExecuteTrigger}
          onTriggerSuccess={() => setIsTriggerPanelOpen(false)}
          anchorElement={letterboxEl ?? appContainerEl}
          prefillValues={templateValues}
        />
      )}
    </div>
  );
}

// ── Plan-derived TriggerPanelConfig builder ──
//
// Mirrors the canvas-side WorkflowBuilder.triggerPanelConfigs builder, but
// uses raw plan triggers only (no live node data, no backend-availability
// filter). This is the fallback used when no canvas is mounted: visualize
// popup card in agent chat, /s/<token> share pages. Live canvas dispatch
// (when present) takes precedence over this.
export function buildPanelConfigsFromPlan(
  planTriggers: Array<{ id: string; label?: string; type?: string; params?: Record<string, unknown> }>,
): TriggerPanelConfig[] {
  const out: TriggerPanelConfig[] = [];
  for (const t of planTriggers) {
    const type = (t.type || '').toLowerCase();
    if (type !== 'chat' && type !== 'form' && type !== 'webhook') continue;
    const triggerId = triggerKey(t.label || '') || `trigger:${t.id}`;
    const triggerLabel = t.label || (type === 'chat' ? 'Chat' : type === 'webhook' ? 'Webhook' : 'Form');
    const params = (t.params ?? {}) as Record<string, unknown>;

    if (type === 'chat') {
      out.push({ triggerId, triggerLabel, type: 'chat' });
      continue;
    }
    if (type === 'webhook') {
      const headers = params.headers;
      const body = params.body;
      out.push({
        triggerId,
        triggerLabel,
        type: 'webhook',
        webhookMethod: (params.method as 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE') || 'POST',
        webhookUrlPreview: (params.url as string) || (params.path as string) || '',
        webhookDefaultHeaders: typeof headers === 'string'
          ? headers
          : headers
            ? JSON.stringify(headers, null, 2)
            : '{\n  "Content-Type": "application/json"\n}',
        webhookDefaultBody: typeof body === 'string'
          ? body
          : body
            ? JSON.stringify(body, null, 2)
            : '{\n  \n}',
      });
      continue;
    }
    // form
    const rawFields = Array.isArray(params.fields) ? (params.fields as Array<Record<string, unknown>>) : [];
    out.push({
      triggerId,
      triggerLabel,
      type: 'form',
      formTitle: (params.formTitle as string) || triggerLabel,
      formDescription: (params.formDescription as string) || '',
      submitButtonText: (params.submitButtonText as string) || 'Submit',
      fields: rawFields.map((f, idx) => ({
        id: (f.id as string) || `field-${idx}`,
        name: (f.name as string) || (f.id as string) || `field-${idx}`,
        label: (f.label as string) || (f.name as string) || `Field ${idx + 1}`,
        type: (f.type as string) || 'text',
        placeholder: (f.placeholder as string) || '',
        required: !!f.required,
        options: (f.options as Array<{ label: string; value: string }>) || [],
        accept: (f.accept as string) || '',
      })),
    });
  }
  return out;
}

// ── Helpers (same as WorkflowModeToggle EpochSelector) ──

function formatEpochTime(isoString: string): string {
  return formatUtcTime(isoString, { withSeconds: true });
}

function formatEpochDuration(ms: number): string {
  if (ms < 1000) return '<1s';
  const sec = ms / 1000;
  if (sec < 10) return `${sec.toFixed(1)}s`;
  if (sec < 60) return `${Math.round(sec)}s`;
  const minutes = Math.floor(sec / 60);
  const remSec = Math.round(sec % 60);
  if (minutes < 60) return `${minutes}m${String(remSec).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  const remainMin = minutes % 60;
  return `${hours}h${String(remainMin).padStart(2, '0')}m`;
}
