'use client';

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { useUserProfile } from '@/hooks/useUserProfile';
import { useTranslations } from 'next-intl';
import {
  Globe, Coins, Layout, EyeOff, Check, AlertCircle, AlertTriangle,
  Play, ArrowLeft, ArrowRight, FileText, Lock,
  Monitor, Workflow, Table2, StepForward,
} from 'lucide-react';
import * as LucideIcons from 'lucide-react';
import { orchestratorApi, WorkflowPublication, WorkflowRun } from '@/lib/api';
import type { WorkflowPlanVersion, WorkflowVersionsResponse } from '@/lib/api/orchestrator/types';
import { useInterfaceRender } from '@/app/workflows/builder/hooks/useInterfaces';
import { ShowcasePreview } from '@/components/marketplace/ShowcasePreview';
import { FieldInfoTooltip } from '@/app/workflows/builder/components/inspector/forms/shared/FieldInfoTooltip';
import LoadingSpinner from '@/components/LoadingSpinner';
import { isCeMode } from '@/lib/format-cost';
import { CategoryPicker } from '@/components/marketplace/CategoryPicker';
import { PAID_TEMPLATES_ENABLED } from '@/lib/featureFlags';
import { screeningService, type FlaggedImage, type ScreeningDecisionEntry } from '@/lib/api/orchestrator/screening.service';
import { ImageScreeningModal } from '@/components/marketplace/ImageScreeningModal';
import {
  getPublicationEpochOptions,
  resolveDefaultPublicationEpoch,
} from '@/components/workflow/publicationEpochOptions';

// ============== Constants ==============

// Fixed wizard length: Information → Showcase → Visibility. Visibility now
// lives on the LAST step (it used to gate whether the marketplace step
// existed), so the wizard no longer changes length based on public/private.
const TOTAL_STEPS = 3;

// ============== Types ==============

interface RunOption {
  id: string;
  runId: string;
  status: string;
  startedAt?: string;
  planVersion?: number;
  totalNodes?: number;
  isStepByStep?: boolean;
}

function getCategoryIcon(iconSlug?: string) {
  if (!iconSlug) return null;
  const pascalCase = iconSlug
    .split('-')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join('');
  return (LucideIcons as any)[pascalCase] || null;
}

interface PublishWorkflowModalProps {
  isOpen: boolean;
  onClose: () => void;
  workflowId: string;
  workflowName?: string;
  workflowDescription?: string;
}

// ============== Skeleton ==============

function SkeletonBox({ className }: { className?: string }) {
  return (
    <div className={`bg-slate-200 dark:bg-slate-700 rounded animate-pulse ${className || ''}`} />
  );
}

function StepSkeleton() {
  return (
    <div className="space-y-5">
      <div className="space-y-2">
        <SkeletonBox className="h-4 w-16" />
        <SkeletonBox className="h-10 w-full rounded-xl" />
      </div>
      <div className="space-y-2">
        <SkeletonBox className="h-4 w-24" />
        <SkeletonBox className="h-20 w-full rounded-xl" />
      </div>
      <div className="space-y-2">
        <SkeletonBox className="h-4 w-28" />
        <SkeletonBox className="h-10 w-full rounded-xl" />
      </div>
    </div>
  );
}

// ============== Step Indicator Component ==============

interface StepIndicatorProps {
  currentStep: number;
  onStepClick?: (step: number) => void;
}

const StepIndicator: React.FC<StepIndicatorProps> = ({ currentStep, onStepClick }) => {
  const t = useTranslations('publishWorkflow');
  const steps = [
    { number: 1, icon: FileText, label: t('step1Title') },
    { number: 2, icon: Play, label: t('step2Title') },
    { number: 3, icon: Globe, label: t('step3Title') },
  ];

  return (
    <div className="flex items-center justify-center gap-2 mb-6">
      {steps.map((step, index) => {
        const isActive = step.number === currentStep;
        const isCompleted = step.number < currentStep;
        const Icon = step.icon;

        return (
          <React.Fragment key={step.number}>
            <button
              type="button"
              onClick={() => onStepClick?.(step.number)}
              disabled={step.number > currentStep}
              className={`flex items-center gap-2 px-3 py-1.5 rounded-full transition-all ${
                isActive
                  ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                  : isCompleted
                  ? 'bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 cursor-pointer hover:bg-emerald-500/30'
                  : 'bg-theme-tertiary text-theme-secondary cursor-not-allowed'
              }`}
            >
              {isCompleted ? (
                <Check className="h-4 w-4" />
              ) : (
                <Icon className="h-4 w-4" />
              )}
              <span className="text-sm font-medium hidden sm:inline">{step.label}</span>
            </button>
            {index < steps.length - 1 && (
              <div className={`w-8 h-0.5 rounded-full ${
                step.number < currentStep ? 'bg-emerald-500' : 'bg-theme-tertiary'
              }`} />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
};

// ============== Main Component ==============

export function PublishWorkflowModal({
  isOpen,
  onClose,
  workflowId,
  workflowName = '',
  workflowDescription = '',
}: PublishWorkflowModalProps) {
  const t = useTranslations('publishWorkflow');
  const tCommon = useTranslations('common');
  const { user, isLoading: authLoading } = useAuthGuard();
  const { profile: userProfile } = useUserProfile();

  // Step navigation
  const [currentStep, setCurrentStep] = useState(1);

  // Form state
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const titleDirtyRef = useRef(false);
  const descriptionDirtyRef = useRef(false);
  const activeModalWorkflowIdRef = useRef<string | null>(null);
  const latestWorkflowMetadataRef = useRef({
    title: workflowName,
    description: workflowDescription,
  });
  const [selectedInterfaceId, setSelectedInterfaceId] = useState<string>('none');
  const [selectedRunId, setSelectedRunId] = useState<string>('none');
  // The showcase always pins exactly one captured epoch. null is only the
  // transient "not resolved yet" state (no run/interface picked, or the run
  // captured no epoch); once the showcase run renders, the effect below
  // defaults this to the latest captured epoch. Sent as showcaseEpoch in the
  // publish/update payload.
  const [selectedEpoch, setSelectedEpoch] = useState<number | null>(null);

  // Wave 2a part 3 - image-screening modal state. Non-null when the pre-
  // publish scan flagged external images and the publisher must either
  // attest "I have rights" or cancel. Reset to null after a publish
  // completes OR the user cancels out of the modal.
  const [pendingScreening, setPendingScreening] = useState<{
    flagged: FlaggedImage[];
    attestationTextVersion: string;
    aiReplacementCostPerImage?: number;
  } | null>(null);
  const [selectedCategoryId, setSelectedCategoryId] = useState<string>('none');
  const [price, setPrice] = useState<number>(0);
  const [visibility, setVisibility] = useState<'PRIVATE' | 'PUBLIC'>('PUBLIC');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isLoading, setIsLoading] = useState(false);

  // Version state
  const [versions, setVersions] = useState<WorkflowPlanVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [currentVersion, setCurrentVersion] = useState<number>(0);
  const [activeVersion, _setActiveVersion] = useState<number | null>(null);
  const activeVersionRef = useRef<number | null>(null);
  const setActiveVersion = (v: number | null) => { activeVersionRef.current = v; _setActiveVersion(v); };
  const [isLoadingVersions, setIsLoadingVersions] = useState(false);

  // Runs state
  const [runs, setRuns] = useState<RunOption[]>([]);
  const [isLoadingRuns, setIsLoadingRuns] = useState(false);

  // Categories state
  const [categories, setCategories] = useState<any[]>([]);
  const [isLoadingCategories, setIsLoadingCategories] = useState(false);

  // Pending interface ID for pre-selection
  const [pendingInterfaceId, setPendingInterfaceId] = useState<string | null>(null);

  // Publication state
  const [existingPublication, setExistingPublication] = useState<WorkflowPublication | null>(null);
  const [isPublished, setIsPublished] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Post-publish phase: null (wizard) | 'publishing' | 'error'. Success no
  // longer takes over the screen - it confirms with an in-modal banner and
  // keeps the wizard open (see runPublish / successMessage).
  const [publishPhase, setPublishPhase] = useState<'publishing' | 'error' | null>(null);
  const [publishError, setPublishError] = useState<string | null>(null);

  // Display mode is derived from the selected showcase interface: a workflow
  // publication with an interface renders as an APPLICATION on the marketplace,
  // without one it renders as a plain WORKFLOW. EXPERIENCE mode was removed
  // (V272 + workstream B) - APPLICATION is now the only interactive option.

  // Unpublish confirmation
  const [showUnpublishConfirm, setShowUnpublishConfirm] = useState(false);
  const [unpublishConfirmText, setUnpublishConfirmText] = useState('');

  // Plan data (for actionMapping detection + node icon preview + included resources)
  const [planInterfaces, setPlanInterfaces] = useState<any[]>([]);
  const [planTables, setPlanTables] = useState<any[]>([]);
  const [hasWorkflowTrigger, setHasWorkflowTrigger] = useState(false);
  // The version whose plan has finished loading into planInterfaces/planTables.
  // Gating the "no interface" explanation on `planLoadedVersion === selectedVersion`
  // means it only renders once THIS version's plan has resolved - so it never
  // flashes on first load or during a version switch (when planInterfaces still
  // holds the previous version's data).
  const [planLoadedVersion, setPlanLoadedVersion] = useState<number | null>(null);

  // Action mapping detection

  // User info - prefer backend profile displayName (set during onboarding), fallback to OIDC.
  // Avatar is NOT persisted: viewers resolve it at render time via /api/proxy/users/{publisherId}/avatar.
  const userDisplayName = userProfile?.displayName || user?.name || user?.nickname || user?.given_name || user?.email?.split('@')[0] || '';
  const userEmail = user?.email || '';

  // ============== Filtered runs by version ==============

  const filteredRuns = useMemo(() => {
    if (selectedVersion === null) return runs;
    return runs.filter(run => run.planVersion === undefined || run.planVersion === selectedVersion);
  }, [runs, selectedVersion]);

  const selectedPreviewRunId = isOpen && selectedRunId !== 'none' ? selectedRunId : null;
  const selectedPreviewInterfaceId = isOpen && selectedInterfaceId !== 'none' ? selectedInterfaceId : null;
  const {
    data: epochRenderData,
    isLoading: isLoadingEpochOptions,
    isPlaceholderData: isPlaceholderEpochData,
  } = useInterfaceRender(
    selectedPreviewInterfaceId,
    selectedPreviewRunId,
    0,
    500,
  );
  const epochOptions = useMemo(
    () => getPublicationEpochOptions(epochRenderData),
    [epochRenderData],
  );

  // The showcase always pins exactly one epoch (no "all epochs" view): once the
  // selected run/interface has rendered, keep a still-valid pin, otherwise
  // default to the latest captured epoch.
  useEffect(() => {
    const next = resolveDefaultPublicationEpoch(
      selectedEpoch,
      epochOptions,
      !!epochRenderData && !isPlaceholderEpochData,
    );
    if (next !== selectedEpoch) {
      setSelectedEpoch(next);
    }
  }, [epochRenderData, epochOptions, isPlaceholderEpochData, selectedEpoch]);

  // Auto-select first valid (auto) run when filtered runs change
  useEffect(() => {
    if (selectedRunId !== 'none') return; // already selected
    const firstAutoRun = filteredRuns.find(r => !r.isStepByStep);
    if (firstAutoRun) {
      setSelectedRunId(firstAutoRun.runId);
    }
  }, [filteredRuns, selectedRunId]);

  // A category is mandatory - default to "automation" (or the first available)
  // whenever nothing is selected, so an application is never published
  // uncategorized and the publisher can't clear the selection. Only fills the
  // "none" gap; an existing publication's loaded category is preserved.
  useEffect(() => {
    if (categories.length === 0 || selectedCategoryId !== 'none') return;
    const fallback = categories.find((c: any) => c.slug === 'automation') ?? categories[0];
    if (fallback?.id) setSelectedCategoryId(fallback.id);
  }, [categories, selectedCategoryId]);

  // Versions that have at least one AUTO run (step-by-step runs cannot be published)
  const versionsWithAutoRuns = useMemo(() => {
    const set = new Set<number>();
    for (const run of runs) {
      if (run.planVersion != null && !run.isStepByStep) set.add(run.planVersion);
    }
    return set;
  }, [runs]);

  // Versions that have runs but ALL are step-by-step (shown grayed with icon)
  const versionsWithStepByStepOnly = useMemo(() => {
    const allVersions = new Set<number>();
    for (const run of runs) {
      if (run.planVersion != null) allVersions.add(run.planVersion);
    }
    const set = new Set<number>();
    for (const v of allVersions) {
      if (!versionsWithAutoRuns.has(v)) set.add(v);
    }
    return set;
  }, [runs, versionsWithAutoRuns]);

  // Unique datasource count from plan tables
  const uniqueDatasourceIds = useMemo(() => {
    const ids = new Set<string>();
    for (const t of planTables) {
      if (t.dataSourceId) ids.add(t.dataSourceId.toString());
    }
    return ids;
  }, [planTables]);

  // ============== Step navigation ==============

  const isPrivate = visibility === 'PRIVATE';
  const totalSteps = TOTAL_STEPS;

  const canProceedFromStep = (step: number): boolean => {
    switch (step) {
      case 1: return !!title.trim() && selectedVersion !== null && !hasWorkflowTrigger && versionsWithAutoRuns.has(selectedVersion) && planInterfaces.length > 0;
      case 2: return selectedRunId !== 'none' && selectedInterfaceId !== 'none';
      case 3: return true;
      default: return false;
    }
  };

  const nextStep = () => {
    if (currentStep < totalSteps && canProceedFromStep(currentStep)) {
      setCurrentStep(prev => prev + 1);
    }
  };

  const prevStep = () => {
    if (currentStep > 1) {
      setCurrentStep(prev => prev - 1);
    }
  };

  const goToStep = (step: number) => {
    if (step <= currentStep || canProceedFromStep(step - 1)) {
      setCurrentStep(step);
    }
  };

  const setTitleFromLoad = useCallback((nextTitle: string) => {
    if (!titleDirtyRef.current) {
      setTitle(nextTitle);
    }
  }, []);

  const setDescriptionFromLoad = useCallback((nextDescription: string) => {
    if (!descriptionDirtyRef.current) {
      setDescription(nextDescription);
    }
  }, []);

  const handleTitleChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    titleDirtyRef.current = true;
    setTitle(event.target.value);
  }, []);

  const handleDescriptionChange = useCallback((event: React.ChangeEvent<HTMLTextAreaElement>) => {
    descriptionDirtyRef.current = true;
    setDescription(event.target.value);
  }, []);

  // ============== Load plan for actionMapping detection + node icon preview ==============

  useEffect(() => {
    if (!workflowId || selectedVersion === null) {
      setPlanInterfaces([]);
      setPlanTables([]);
      setHasWorkflowTrigger(false);
      setPlanLoadedVersion(null);
      return;
    }
    let cancelled = false;
    const loadingVersion = selectedVersion;
    (async () => {
      try {
        const detail = await orchestratorApi.getVersion(workflowId, loadingVersion);
        if (cancelled) return;
        const plan = detail?.plan;
        const rawInterfaces = (plan?.interfaces || []).map((i: any) => ({
          ...i,
          interfaceId: i.interfaceId || i.id,
        }));
        setPlanInterfaces(rawInterfaces.filter((i: any) => i.interfaceId));
        setPlanTables(plan?.tables || []);
        setHasWorkflowTrigger(
          Array.isArray(plan?.triggers) && plan.triggers.some((t: any) => t.type === 'workflow')
        );
      } catch {
        if (!cancelled) {
          setPlanInterfaces([]);
          setPlanTables([]);
              setHasWorkflowTrigger(false);
        }
      } finally {
        // Mark this version's plan as resolved only after its data is set, so
        // the "no interface" explanation can't render against stale/empty state.
        if (!cancelled) setPlanLoadedVersion(loadingVersion);
      }
    })();
    return () => { cancelled = true; };
  }, [workflowId, selectedVersion]);

  // ============== Data fetching ==============

  // Fetch versions
  const fetchVersions = useCallback(async () => {
    if (!workflowId || authLoading) return;
    setIsLoadingVersions(true);
    try {
      const response: WorkflowVersionsResponse = await orchestratorApi.listVersions(workflowId);
      setVersions(response.versions || []);
      setCurrentVersion(response.currentVersion || 0);
      // Initialize activeVersion from API if not already set by history events
      const apiCurrent = response.currentVersion || 0;
      if (activeVersionRef.current === null) {
        setActiveVersion(apiCurrent);
      }
      // Pre-select active version (respects restores) or fall back to API current
      setSelectedVersion(prev => prev !== null ? prev : (activeVersionRef.current ?? apiCurrent));
    } catch (err) {
      console.error('Error fetching versions:', err);
      setVersions([]);
    } finally {
      setIsLoadingVersions(false);
    }
  }, [workflowId, authLoading]);

  // Fetch runs
  const fetchRuns = useCallback(async () => {
    if (!workflowId || authLoading) return;
    setIsLoadingRuns(true);
    try {
      const workflowRuns = await orchestratorApi.getWorkflowRuns(workflowId);
      const allRuns = workflowRuns
        .map((run: WorkflowRun) => ({
          id: run.id,
          runId: run.runId,
          status: run.status,
          startedAt: run.startedAt,
          planVersion: run.planVersion,
          totalNodes: run.totalNodes,
          isStepByStep: run.executionMode === 'step_by_step',
        }));
      setRuns(allRuns);
    } catch (err) {
      console.error('Error fetching runs:', err);
      setRuns([]);
    } finally {
      setIsLoadingRuns(false);
    }
  }, [workflowId, authLoading]);

  // When run selection changes, auto-select interface from plan
  useEffect(() => {
    if (!selectedRunId || selectedRunId === 'none') {
      setSelectedInterfaceId('none');
      return;
    }
    // Wait for plan interfaces to load before auto-selecting
    if (planInterfaces.length === 0) return;

    if (pendingInterfaceId && planInterfaces.some((i: any) => i.interfaceId === pendingInterfaceId)) {
      setSelectedInterfaceId(pendingInterfaceId);
      setPendingInterfaceId(null);
    } else {
      setSelectedInterfaceId(planInterfaces[0].interfaceId);
    }
  }, [selectedRunId, planInterfaces, pendingInterfaceId]);

  // Fetch categories
  const fetchCategories = useCallback(async () => {
    setIsLoadingCategories(true);
    try {
      const response = await orchestratorApi.getCategories(true);
      setCategories(response.categories || []);
    } catch (err) {
      console.error('Error fetching categories:', err);
      setCategories([]);
    } finally {
      setIsLoadingCategories(false);
    }
  }, []);

  // Fetch existing publication
  const fetchPublicationStatus = useCallback(async () => {
    if (!workflowId || authLoading) return;
    const requestWorkflowId = workflowId;
    setIsLoading(true);
    setError(null);
    try {
      const response = await orchestratorApi.getPublicationByWorkflowId(requestWorkflowId);
      if (activeModalWorkflowIdRef.current !== requestWorkflowId) {
        return;
      }
      const fallback = latestWorkflowMetadataRef.current;
      if (response && response.published) {
        setIsPublished(true);
        setExistingPublication(response);
        setTitleFromLoad(response.title || fallback.title);
        setDescriptionFromLoad(response.description || fallback.description);
        setPrice(response.creditsPerUse || 0);
        setVisibility(response.visibility as 'PRIVATE' | 'PUBLIC' || 'PRIVATE');
        const runId = response.showcaseRunId || 'none';
        const interfaceId = response.showcaseInterfaceId || 'none';
        if (runId !== 'none' && interfaceId !== 'none') {
          setPendingInterfaceId(interfaceId);
        }
        setSelectedRunId(runId);
        setSelectedInterfaceId(interfaceId !== 'none' ? interfaceId : 'none');
        setSelectedCategoryId(response.category?.id || 'none');
        // Rehydrate the publisher's pinned epoch. A legacy publication with no
        // pin (null) falls through to the default-latest effect once the run
        // renders, so the showcase ends up pinned to one epoch either way.
        setSelectedEpoch(response.showcaseChosenEpoch ?? null);
        // If published, set version from publication if available
        if (response.planVersion) {
          setSelectedVersion(response.planVersion);
        }
      } else {
        setIsPublished(false);
        setExistingPublication(null);
        setTitleFromLoad(fallback.title);
        setDescriptionFromLoad(fallback.description);
        setSelectedInterfaceId('none');
        setSelectedRunId('none');
        setSelectedEpoch(null);
        setPrice(0);
        setVisibility('PUBLIC');
      }
    } catch (err: any) {
      if (activeModalWorkflowIdRef.current !== requestWorkflowId) {
        return;
      }
      if (err?.statusCode === 404 || err?.message?.includes('404')) {
        setIsPublished(false);
        setExistingPublication(null);
        const fallback = latestWorkflowMetadataRef.current;
        setTitleFromLoad(fallback.title);
        setDescriptionFromLoad(fallback.description);
        setSelectedInterfaceId('none');
        setSelectedRunId('none');
        setSelectedEpoch(null);
        setPrice(0);
        setVisibility('PUBLIC');
      } else {
        console.error('Error fetching publication status:', err);
        setIsPublished(false);
        setExistingPublication(null);
        const fallback = latestWorkflowMetadataRef.current;
        setTitleFromLoad(fallback.title);
        setDescriptionFromLoad(fallback.description);
        setSelectedInterfaceId('none');
        setSelectedRunId('none');
        setSelectedEpoch(null);
        setPrice(0);
        setVisibility('PUBLIC');
      }
    } finally {
      if (activeModalWorkflowIdRef.current === requestWorkflowId) {
        setIsLoading(false);
      }
    }
  }, [workflowId, authLoading, setTitleFromLoad, setDescriptionFromLoad]);

  // Fetch data when modal opens
  useEffect(() => {
    if (!isOpen) {
      activeModalWorkflowIdRef.current = null;
      return;
    }

    latestWorkflowMetadataRef.current = {
      title: workflowName,
      description: workflowDescription,
    };
    setTitleFromLoad(workflowName);
    setDescriptionFromLoad(workflowDescription);
  }, [isOpen, workflowName, workflowDescription, setTitleFromLoad, setDescriptionFromLoad]);

  useEffect(() => {
    if (isOpen) {
      if (activeModalWorkflowIdRef.current === workflowId) {
        return;
      }
      activeModalWorkflowIdRef.current = workflowId;
      titleDirtyRef.current = false;
      descriptionDirtyRef.current = false;
      setTitle(workflowName);
      setDescription(workflowDescription);
      setCurrentStep(1);
      setSelectedEpoch(null);
      fetchPublicationStatus();
      fetchRuns();
      fetchCategories();
      fetchVersions();
      setError(null);
      setSuccessMessage(null);
      setPublishPhase(null);
      setPublishError(null);
    }
  }, [isOpen, workflowId, workflowName, workflowDescription, fetchPublicationStatus, fetchRuns, fetchCategories, fetchVersions]);

  // Convert UUID runId to public format once runs are loaded
  useEffect(() => {
    if (runs.length > 0 && selectedRunId && selectedRunId !== 'none') {
      if (selectedRunId.length === 36 && selectedRunId.includes('-')) {
        const matchingRun = runs.find(run => run.id === selectedRunId);
        if (matchingRun && matchingRun.runId !== selectedRunId) {
          setSelectedRunId(matchingRun.runId);
        }
      }
    }
  }, [runs, selectedRunId]);

  // Listen for activeVersion changes from WorkflowVersionHistory (save/restore)
  useEffect(() => {
    const handler = (e: CustomEvent) => {
      setActiveVersion(e.detail.version);
    };
    window.addEventListener('workflowActiveVersionChange', handler as EventListener);
    return () => window.removeEventListener('workflowActiveVersionChange', handler as EventListener);
  }, []);

  // ============== Handlers ==============

  // Actual publish (or update). Extracted so the screening flow can resume
  // it after the publisher attests on flagged images. {@code attestedDecisions}
  // carries the rows to post AFTER publish succeeds (need the publicationId
  // for the FK - for updates we already have it on existingPublication).
  const runPublish = async (attestedDecisions: ScreeningDecisionEntry[] | null) => {
    setIsSubmitting(true);
    setError(null);
    setSuccessMessage(null);
    setPublishPhase('publishing');
    setPublishError(null);
    try {
      const previewId = selectedInterfaceId === 'none' ? undefined : selectedInterfaceId;
      const showcaseId = selectedRunId === 'none' ? undefined : selectedRunId;
      const categoryId = (selectedCategoryId && selectedCategoryId !== 'none') ? selectedCategoryId : undefined;
      // While paid templates are disabled, force price to 0 regardless of
      // any stale state from an earlier session - backend also rejects, this
      // is defense-in-UX so the publisher never sees a "price not allowed"
      // error after clicking publish.
      const effectivePrice = PAID_TEMPLATES_ENABLED ? price : 0;
      // Derive display mode from interface presence: with an interface the
      // workflow renders as an APPLICATION on the marketplace, without one
      // it stays a plain WORKFLOW. Backend enforces the same invariant.
      const displayMode: 'APPLICATION' | 'WORKFLOW' = previewId ? 'APPLICATION' : 'WORKFLOW';

      // V273 - only forward the chosen epoch when the publisher picked one.
      // Updates need an explicit clear flag because JSON omits undefined.
      const showcaseEpoch = selectedEpoch != null ? selectedEpoch : undefined;
      const clearShowcaseEpoch = selectedEpoch == null;

      // V274 - wizard always declares itself so the backend's auto-screening
      // path skips (the wizard handles audit logging via /screening-decisions
      // POST below, with KEPT_ATTESTED / SKIPPED based on user choice).
      const viaScreeningWizard = true;

      const imageReplacements = attestedDecisions
        ?.filter(d => (d.decision === 'REPLACED_AI' || d.decision === 'REPLACED_UPLOAD') && d.replacementRef)
        .map(d => ({ originalUrl: d.url, storageKey: d.replacementRef! }));

      let publicationIdForDecisions: string | undefined;
      if (isPublished && existingPublication) {
        await orchestratorApi.updatePublication(existingPublication.id, {
          title,
          description,
          showcaseInterfaceId: previewId,
          showcaseRunId: showcaseId,
          categoryId,
          creditsPerUse: effectivePrice,
          visibility,
          displayMode,
          clearShowcaseEpoch,
          showcaseEpoch,
          viaScreeningWizard,
          imageReplacements: imageReplacements?.length ? imageReplacements : undefined,
        });
        publicationIdForDecisions = existingPublication.id;
        // Every re-share re-enters moderation (backend resets PUBLIC/UNLISTED
        // to PENDING_REVIEW). Tell the publisher their update went back to
        // review; PRIVATE stays live so the plain "updated" message holds.
        setSuccessMessage(isPrivate ? t('updateSuccess') : t('updateResubmittedForReview'));
        setPublishPhase(null); // stay in wizard for updates
      } else {
        const created = await orchestratorApi.publishWorkflow({
          workflowId,
          title,
          description,
          showcaseInterfaceId: previewId,
          showcaseRunId: showcaseId,
          categoryId,
          creditsPerUse: effectivePrice,
          publisherName: userDisplayName,
          publisherEmail: userEmail,
          visibility,
          planVersion: selectedVersion ?? undefined,
          displayMode,
          showcaseEpoch,
          viaScreeningWizard,
          imageReplacements: imageReplacements?.length ? imageReplacements : undefined,
        });
        publicationIdForDecisions = created?.id;
        setIsPublished(true);
        // Stay in the wizard and confirm with an in-modal banner instead of a
        // separate takeover screen, so the publisher reads the status and
        // closes when ready. PUBLIC enters moderation ("submitted for review");
        // PRIVATE goes live instantly.
        setSuccessMessage(isPrivate ? t('publishSuccessPrivateDescription') : t('publishSuccessDescription'));
        setPublishPhase(null);
      }

      // Wave 2a part 3 - fire-and-forget the audit log AFTER publish
      // succeeded. Per the v3 audit, the audit row is the safe-harbor
      // evidence: it must persist when the publisher attested, but a
      // logging failure must NOT roll back a successful publish.
      if (attestedDecisions && attestedDecisions.length > 0 && publicationIdForDecisions) {
        try {
          await screeningService.postScreeningDecisions({
            publicationId: publicationIdForDecisions,
            decisions: attestedDecisions,
          });
        } catch (e) {
          console.warn('Failed to log image-screening decisions (publish itself succeeded):', e);
        }
      }

      await fetchPublicationStatus();
    } catch (err: any) {
      console.error('Error publishing workflow:', err);
      setPublishError(err.message || t('publishError'));
      setPublishPhase('error');
      setError(err.message || t('publishError'));
    } finally {
      setIsSubmitting(false);
    }
  };

  // Wave 2a part 3 - pre-publish entry point. When an interface is in
  // play, scan it first; if any image is flagged, gate publish behind the
  // ImageScreeningModal (the publisher attests "I have rights" or
  // cancels). On clean templates the scan is a single fast HTTP and the
  // publish proceeds immediately - zero added friction for the common
  // case. Scan failure (network, 5xx) MUST NOT block publish: per the
  // v3 audit ("never auto-block") we log and continue.
  const handlePublish = async () => {
    const previewId = selectedInterfaceId === 'none' ? undefined : selectedInterfaceId;
    const showcaseId = selectedRunId === 'none' ? undefined : selectedRunId;
    if (previewId) {
      try {
        const scan = await screeningService.prePublishScan({
          interfaceId: previewId,
          workflowId,
          runId: showcaseId,
          epoch: selectedEpoch ?? undefined,
        });
        if (!scan.clean && scan.flagged.length > 0) {
          setPendingScreening({
            flagged: scan.flagged,
            attestationTextVersion: scan.attestationTextVersion,
            aiReplacementCostPerImage: scan.aiReplacementCostPerImage,
          });
          return; // modal will resume via handleScreeningConfirm
        }
      } catch (e) {
        console.warn('Pre-publish image scan failed, proceeding without screening gate:', e);
      }
    }
    await runPublish(null);
  };

  const handleScreeningConfirm = async (decisions: ScreeningDecisionEntry[]) => {
    setPendingScreening(null);
    await runPublish(decisions);
  };

  const handleScreeningCancel = () => {
    setPendingScreening(null);
  };

  const handleUnpublish = async () => {
    setIsSubmitting(true);
    setError(null);
    setSuccessMessage(null);
    try {
      await orchestratorApi.unpublishWorkflow(workflowId);
      // Keep the modal open and show the confirmation banner - the publisher
      // closes when ready (no auto-dismiss), consistent with the publish flow.
      setSuccessMessage(t('unpublishSuccess'));
      setIsPublished(false);
      setExistingPublication(null);
      setSelectedCategoryId('none');
      setSelectedEpoch(null);
    } catch (err: any) {
      console.error('Error unpublishing workflow:', err);
      setError(err.message || t('unpublishError'));
    } finally {
      setIsSubmitting(false);
    }
  };

  // ============== Render helpers ==============

  const showLoading = authLoading || isLoading;

  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  if (!isOpen || !mounted) return null;

  // Visibility toggle - moved to the LAST step (step 3) so it is the final
  // decision before publishing. Shared as a helper since it is referenced from
  // the last step only.
  const renderVisibilityToggle = () => (
    <div>
      <label className="block text-sm font-medium text-theme-primary mb-3">
        {t('visibilityLabel')}
      </label>
      <div className="grid grid-cols-2 gap-3">
        <button
          type="button"
          onClick={() => setVisibility('PRIVATE')}
          className={`flex items-center gap-2.5 p-3 rounded-xl border-2 transition-all ${
            visibility === 'PRIVATE'
              ? 'border-[var(--accent-primary)] bg-[var(--accent-primary)]/5'
              : 'border-theme hover:border-theme-secondary'
          }`}
        >
          <Lock className={`h-5 w-5 shrink-0 ${visibility === 'PRIVATE' ? 'text-[var(--accent-primary)]' : 'text-theme-secondary'}`} />
          <div className="text-left">
            <span className={`text-sm font-medium block ${visibility === 'PRIVATE' ? 'text-[var(--accent-primary)]' : 'text-theme-primary'}`}>
              {t('private')}
            </span>
            <span className="text-xs text-theme-secondary">{t('privateDescription')}</span>
          </div>
        </button>
        <button
          type="button"
          onClick={() => setVisibility('PUBLIC')}
          className={`flex items-center gap-2.5 p-3 rounded-xl border-2 transition-all ${
            visibility === 'PUBLIC'
              ? 'border-[var(--accent-primary)] bg-[var(--accent-primary)]/5'
              : 'border-theme hover:border-theme-secondary'
          }`}
        >
          <Globe className={`h-5 w-5 shrink-0 ${visibility === 'PUBLIC' ? 'text-[var(--accent-primary)]' : 'text-theme-secondary'}`} />
          <div className="text-left">
            <span className={`text-sm font-medium block ${visibility === 'PUBLIC' ? 'text-[var(--accent-primary)]' : 'text-theme-primary'}`}>
              {t('public')}
            </span>
            <span className="text-xs text-theme-secondary">{t('publicDescription')}</span>
          </div>
        </button>
      </div>
    </div>
  );

  // ============== Step 1: Info & Version ==============

  const renderStep1 = () => (
    <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
      {/* Title */}
      <div>
        <label className="block text-sm font-medium text-theme-primary mb-2">
          {t('titleLabel')}
        </label>
        <Input
          value={title}
          onChange={handleTitleChange}
          placeholder={t('titlePlaceholder')}
          className="w-full"
        />
      </div>

      {/* Description */}
      <div>
        <label className="block text-sm font-medium text-theme-primary mb-2">
          {t('descriptionLabel')}
        </label>
        <Textarea
          value={description}
          onChange={handleDescriptionChange}
          placeholder={t('descriptionPlaceholder')}
          rows={3}
          className="resize-none"
        />
      </div>

      {/* Version selector */}
      <div>
        <label className="block text-sm font-medium text-theme-primary mb-2">
          {t('versionLabel')}
        </label>
        <Select
          value={selectedVersion?.toString() || ''}
          onValueChange={(val) => {
            const v = parseInt(val, 10);
            setSelectedVersion(v);
            // Reset run/interface when version changes
            setSelectedRunId('none');
            setSelectedInterfaceId('none');
            setSelectedEpoch(null);
          }}
          disabled={isLoadingVersions}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder={isLoadingVersions ? t('loadingVersions') : t('versionSelect')} />
          </SelectTrigger>
          <SelectContent className="z-[10000]">
            {versions.length === 0 && !isLoadingVersions && (
              <SelectItem value="none" disabled>
                <span className="text-theme-secondary">{t('noVersions')}</span>
              </SelectItem>
            )}
            {versions.map((v) => {
              const locale = getClientLocale();
              const dateStr = v.createdAt ? formatUtcDateTime(v.createdAt, { locale }) : '';
              const isSelected = v.version === selectedVersion;
              const hasAutoRuns = versionsWithAutoRuns.has(v.version);
              const isStepByStepOnly = versionsWithStepByStepOnly.has(v.version);
              const hasAnyRuns = hasAutoRuns || isStepByStepOnly || (v.runCount != null && v.runCount > 0);
              const isDisabled = !hasAutoRuns;
              return (
                <SelectItem
                  key={v.version}
                  value={v.version.toString()}
                  disabled={isDisabled}
                  className={`pl-3 [&>span:first-child]:hidden ${
                    isSelected ? 'bg-gray-100 dark:bg-gray-800' : ''
                  } ${isDisabled ? 'opacity-50' : ''}`}
                >
                  <div className="flex flex-col gap-0.5">
                    <div className="flex items-center gap-1.5">
                      <span className={`text-sm font-medium ${isSelected ? 'font-semibold' : ''}`}>v{v.version}</span>
                      {v.label && <span className="text-xs text-theme-secondary">{v.label}</span>}
                      {v.version === (activeVersion ?? currentVersion) && (
                        <span className="text-xs bg-emerald-100 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400 px-1.5 py-0.5 rounded-full">
                          {t('currentVersion')}
                        </span>
                      )}
                      {isPublished && existingPublication?.planVersion === v.version && (
                        <span className="text-xs bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 px-1.5 py-0.5 rounded-full">
                          {t('publishedVersion')}
                        </span>
                      )}
                      {isStepByStepOnly && (
                        <span className="inline-flex items-center gap-1 text-[10px] text-theme-tertiary">
                          <StepForward className="h-3 w-3" />
                          step-by-step only
                        </span>
                      )}
                      {!hasAnyRuns && (
                        <span className="text-[10px] text-theme-tertiary">{t('noRunsForVersion')}</span>
                      )}
                    </div>
                    <p className="text-xs text-theme-secondary">
                      {dateStr}
                      {v.nodeCount != null && (
                        <span className="ml-1.5">&middot; {v.nodeCount} nodes</span>
                      )}
                      {v.runCount != null && v.runCount > 0 && (
                        <span className="ml-1.5">&middot; {v.runCount} runs</span>
                      )}
                    </p>
                  </div>
                </SelectItem>
              );
            })}
          </SelectContent>
        </Select>
        {hasWorkflowTrigger && (
          <p className="text-xs text-amber-600 dark:text-amber-400 mt-1 flex items-center gap-1">
            <AlertTriangle className="h-3 w-3 shrink-0" />
            {t('workflowTriggerWarning')}
          </p>
        )}
      </div>

      {/* Sharing publishes the workflow as an interactive application, so it
          requires at least one interface. When the chosen version has runs but
          no interface, the "Next" button stays disabled - explain why instead
          of leaving the user stuck without a reason. */}
      {selectedVersion !== null
        && planLoadedVersion === selectedVersion
        && !hasWorkflowTrigger
        && versionsWithAutoRuns.has(selectedVersion)
        && planInterfaces.length === 0 && (
        <div className="flex items-start gap-2 p-3 rounded-xl bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800">
          <AlertCircle className="h-4 w-4 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
          <span className="text-sm text-amber-700 dark:text-amber-300">{t('noInterfaceCannotShare')}</span>
        </div>
      )}

    </div>
  );

  // ============== Step 2: Showcase ==============

  const renderStep2 = () => {
    const hasCompletedRuns = filteredRuns.length > 0;

    return (
      <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
        {(hasCompletedRuns || isLoadingRuns) && (
          <>
            {/* Run selector */}
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">
                {t('showcaseRun')}
              </label>
              <Select
                value={selectedRunId}
                onValueChange={(value) => {
                  if (value !== selectedRunId) {
                    setSelectedEpoch(null);
                  }
                  setSelectedRunId(value);
                }}
                disabled={isLoadingRuns}
              >
                <SelectTrigger className="w-full">
                  <SelectValue placeholder={isLoadingRuns ? t('loadingRuns') : t('selectRun')} />
                </SelectTrigger>
                <SelectContent className="z-[10000]">
                  {filteredRuns.map((run) => {
                    const locale = getClientLocale();
                    const dateStr = run.startedAt ? formatUtcDateTime(run.startedAt, { locale }) : '';
                    const isRunDisabled = run.isStepByStep;
                    return (
                      <SelectItem key={run.id} value={run.runId} disabled={isRunDisabled}>
                        <div className={`flex items-center gap-2 ${isRunDisabled ? 'opacity-50' : ''}`}>
                          {run.isStepByStep ? (
                            <StepForward className="h-3 w-3 text-slate-400" />
                          ) : (
                            <Play className="h-4 w-4" />
                          )}
                          <span>{dateStr}</span>
                          {run.totalNodes != null && (
                            <span className="text-xs text-slate-400">&middot; {run.totalNodes} nodes</span>
                          )}
                          {run.isStepByStep && (
                            <span className="text-xs text-slate-400">&middot; step-by-step</span>
                          )}
                          {run.status && (
                            <span className="text-xs text-slate-400">&middot; {run.status?.toLowerCase()}</span>
                          )}
                          {isPublished && existingPublication?.showcaseRunId === run.runId && (
                            <span className="text-xs bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 px-1.5 py-0.5 rounded-full whitespace-nowrap">
                              {t('publishedVersion')}
                            </span>
                          )}
                        </div>
                      </SelectItem>
                    );
                  })}
                </SelectContent>
              </Select>
              <p className="text-xs text-theme-secondary mt-1">{t('showcaseRunHint')}</p>
            </div>

            {/* Interface + Showcase epoch on the same row. The epoch select
                always pins exactly one captured epoch (defaults to the latest)
                - there is no "all epochs" option. */}
            {selectedRunId && selectedRunId !== 'none' && planInterfaces.length > 0 && (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {/* Interface selector */}
                <div>
                  <label className="block text-sm font-medium text-theme-primary mb-2">
                    {t('previewInterface')}
                  </label>
                  <Select
                    value={selectedInterfaceId}
                    onValueChange={(value) => {
                      if (value !== selectedInterfaceId) {
                        setSelectedEpoch(null);
                      }
                      setSelectedInterfaceId(value);
                    }}
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue placeholder={t('selectInterface')} />
                    </SelectTrigger>
                    <SelectContent className="z-[10000]">
                      {planInterfaces.map((iface: any) => (
                        <SelectItem key={iface.interfaceId} value={iface.interfaceId}>
                          <div className="flex items-center gap-2">
                            <Layout className="h-4 w-4" />
                            <span>{iface.label}</span>
                          </div>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                {/* Showcase epoch selector */}
                <div>
                  <label className="block text-sm font-medium text-theme-primary mb-2">
                    {t('epochLabel')}
                  </label>
                  <Select
                    value={selectedEpoch != null ? String(selectedEpoch) : ''}
                    onValueChange={(value) => setSelectedEpoch(Number(value))}
                    disabled={isLoadingEpochOptions || epochOptions.length === 0}
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue placeholder={isLoadingEpochOptions ? t('epochLoading') : t('epochPlaceholder')} />
                    </SelectTrigger>
                    <SelectContent className="z-[10000]">
                      {epochOptions.map((epoch) => (
                        <SelectItem key={epoch} value={String(epoch)}>
                          {t('epochOption', { epoch })}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>
            )}

            {/* Showcase preview - same component and 16:10 dimensions as the
                marketplace card, pinned to the selected epoch so it matches
                exactly what visitors will see. */}
            {selectedRunId && selectedRunId !== 'none' && selectedInterfaceId && selectedInterfaceId !== 'none' && (
              <ShowcasePreview
                runId={selectedRunId}
                interfaceId={selectedInterfaceId}
                epoch={selectedEpoch ?? undefined}
                className="border border-theme"
              />
            )}
          </>
        )}

      </div>
    );
  };

  // ============== Step 3: Visibility (+ Marketplace settings when PUBLIC) =====

  const renderStep3 = () => (
    <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
      {/* Visibility - the final decision before publishing. */}
      {renderVisibilityToggle()}

      {visibility === 'PUBLIC' && (
        <>
          {/* Included resources recap */}
          <div>
            <div className="flex items-center gap-1.5 mb-3">
              <label className="text-sm font-medium text-theme-primary">
                {t('includedResourcesLabel')}
              </label>
              <FieldInfoTooltip description={t('includedResourcesHint')} />
            </div>
            <div className="flex flex-col gap-1.5">
              <div className="flex items-center gap-2">
                <Workflow className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
                <span className="text-sm text-theme-primary">{t('includedWorkflow')}</span>
              </div>
              {planInterfaces.length > 0 && (
                <div className="flex items-center gap-2">
                  <Monitor className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
                  <span className="text-sm text-theme-primary">
                    {planInterfaces.length === 1
                      ? t('includedInterface')
                      : t('includedInterfaces', { count: planInterfaces.length })}
                  </span>
                </div>
              )}
              {uniqueDatasourceIds.size > 0 && (
                <div className="flex items-center gap-2">
                  <Table2 className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
                  <span className="text-sm text-theme-primary">
                    {uniqueDatasourceIds.size === 1
                      ? t('includedDatasource')
                      : t('includedDatasources', { count: uniqueDatasourceIds.size })}
                  </span>
                  <span className="text-xs text-theme-secondary">
                    {t('datasourceStructureOnly')}
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* Category */}
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('categoryLabel')}
            </label>
            <CategoryPicker
              value={selectedCategoryId}
              onChange={setSelectedCategoryId}
              categories={categories}
              allowNone={false}
            />
          </div>

          {/* Price */}
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">
              {t('priceLabel')}
            </label>
            <div className="flex items-center gap-3">
              <fieldset disabled={!PAID_TEMPLATES_ENABLED} className="disabled:opacity-60">
                <div className="flex items-center gap-3">
                  <div className="relative">
                    <Coins className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
                    <Input
                      type="number"
                      min={0}
                      value={PAID_TEMPLATES_ENABLED ? price : 0}
                      onChange={(e) => setPrice(Math.max(0, parseInt(e.target.value) || 0))}
                      placeholder="0"
                      className="w-28 pl-9"
                    />
                  </div>
                  <span className="text-sm text-theme-secondary">{isCeMode ? '$' : t('credits')}</span>
                </div>
              </fieldset>
              {/* "Coming soon" badge to the right of the price while paid
                  templates are disabled. */}
              {!PAID_TEMPLATES_ENABLED && (
                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300 whitespace-nowrap">
                  {t('comingSoon')}
                </span>
              )}
            </div>
            {PAID_TEMPLATES_ENABLED && (
              <p className="text-xs text-theme-secondary mt-1">{t('priceHint')}</p>
            )}
          </div>
        </>
      )}

    </div>
  );

  // ============== Phase screens (publishing / success / error) ==============

  if (publishPhase === 'publishing') {
    return createPortal(
      <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4">
        <div className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme">
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <LoadingSpinner size="md" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">{t('publishing')}</h2>
            <p className="text-sm text-theme-secondary">{t('publishingMessage')}</p>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  if (publishPhase === 'error') {
    return createPortal(
      <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={() => setPublishPhase(null)}>
        <div className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme" onClick={(e) => e.stopPropagation()}>
          <div className="text-center">
            <div className="w-16 h-16 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
              <AlertTriangle className="h-8 w-8 text-red-600 dark:text-red-400" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">{t('publishError')}</h2>
            <p className="text-sm text-theme-secondary mb-6">{publishError}</p>
            <div className="flex gap-3">
              <Button onClick={() => setPublishPhase(null)} variant="outline" className="flex-1">{t('back')}</Button>
              <Button onClick={handlePublish} className="flex-1">{t('retry')}</Button>
            </div>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // ============== Modal ==============

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="share-workflow-modal-title"
        className="max-w-2xl w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-8 pt-8 pb-4">
          <div className="text-center mb-4">
            <h3 id="share-workflow-modal-title" className="text-2xl font-semibold text-theme-primary">
              {isPublished ? t('manageTitle') : t('title')}
            </h3>
            <p className="text-sm text-theme-secondary mt-1">
              {currentStep === 1 && t('step1Title')}
              {currentStep === 2 && t('step2Title')}
              {currentStep === 3 && t('step3Title')}
            </p>
          </div>
          <StepIndicator
            currentStep={currentStep}
            onStepClick={goToStep}
          />
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-8 pb-4">
          {/* Messages */}
          {successMessage && (
            <div className="flex items-center gap-2 p-3 mb-4 bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 rounded-xl">
              <Check className="h-4 w-4 text-green-600 dark:text-green-400 shrink-0" />
              <span className="text-sm text-green-700 dark:text-green-300">{successMessage}</span>
            </div>
          )}
          {error && (
            <div className="flex items-center gap-2 p-3 mb-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl">
              <AlertCircle className="h-4 w-4 text-red-600 dark:text-red-400 shrink-0" />
              <span className="text-sm text-red-700 dark:text-red-300">{error}</span>
            </div>
          )}

          {showLoading ? (
            <StepSkeleton />
          ) : (
            <>
              {currentStep === 1 && renderStep1()}
              {currentStep === 2 && renderStep2()}
              {currentStep === 3 && renderStep3()}
            </>
          )}
        </div>

        {/* Footer */}
        {!showLoading && (
          <div className="px-8 py-4 border-t border-theme flex justify-between">
            <div className="flex items-center gap-2">
              {currentStep > 1 && (
                <Button
                  variant="ghost"
                  onClick={prevStep}
                  disabled={isSubmitting}
                >
                  <ArrowLeft className="h-4 w-4 mr-2" />
                  {t('back')}
                </Button>
              )}
              {isPublished && existingPublication && (
                <Button
                  variant="destructive"
                  onClick={() => { setUnpublishConfirmText(''); setShowUnpublishConfirm(true); }}
                  disabled={isSubmitting}
                  className="flex items-center gap-1.5"
                >
                  <EyeOff className="w-3.5 h-3.5" />
                  {t('unpublish')}
                </Button>
              )}
            </div>

            <div className="flex gap-2">
              <Button variant="outline" onClick={onClose} disabled={isSubmitting}>
                {tCommon('cancel')}
              </Button>
              {currentStep < totalSteps ? (
                <Button onClick={nextStep} disabled={!canProceedFromStep(currentStep)}>
                  {t('next')}
                  <ArrowRight className="h-4 w-4 ml-2" />
                </Button>
              ) : (
                <Button onClick={handlePublish} disabled={isSubmitting || !title.trim() || selectedRunId === 'none' || selectedInterfaceId === 'none'}>
                  {isSubmitting ? (
                    <>
                      <LoadingSpinner size="xs" className="mr-2" />
                    </>
                  ) : (
                    <>
                      {isPrivate ? <Lock className="w-4 h-4 mr-1.5" /> : <Globe className="w-4 h-4 mr-1.5" />}
                      {isPublished ? t('updateButton') : t('publishButton')}
                    </>
                  )}
                </Button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );

  const unpublishConfirmModal = showUnpublishConfirm && existingPublication ? (
    <div
      className="fixed inset-0 bg-black/30 backdrop-blur-sm z-[10001] flex items-center justify-center p-4"
      onClick={() => setShowUnpublishConfirm(false)}
    >
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="unpublish-workflow-modal-title"
        className="max-w-md w-full bg-theme-primary rounded-2xl shadow-2xl border border-theme animate-in fade-in-0 zoom-in-95 duration-200 p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 rounded-full bg-red-100 dark:bg-red-900/30 flex items-center justify-center shrink-0">
            <EyeOff className="w-5 h-5 text-red-600 dark:text-red-400" />
          </div>
          <h3 id="unpublish-workflow-modal-title" className="text-base font-semibold text-theme-primary">
            {t('unpublishConfirmTitle')}
          </h3>
        </div>

        <div className="space-y-3 mb-6">
          <p className="text-sm text-theme-secondary">
            {t('unpublishConfirmMessage')}
          </p>

          <ul className="text-sm text-theme-secondary space-y-1 list-disc list-inside">
            <li>{t('unpublishLostVisibility')}</li>
            <li>{t('unpublishLostAcquisitions')}</li>
          </ul>

          {/* Type "unpublish" to confirm */}
          <div>
            <label className="block text-sm text-theme-secondary mb-1.5">
              {t('unpublishTypeConfirm')}
            </label>
            <Input
              value={unpublishConfirmText}
              onChange={(e) => setUnpublishConfirmText(e.target.value)}
              placeholder="unpublish"
              className="w-full"
              autoFocus
            />
          </div>
        </div>

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => setShowUnpublishConfirm(false)} disabled={isSubmitting}>
            {tCommon('cancel')}
          </Button>
          <Button
            variant="destructive"
            onClick={() => { setShowUnpublishConfirm(false); handleUnpublish(); }}
            disabled={isSubmitting || unpublishConfirmText.toLowerCase() !== 'unpublish'}
            className="flex items-center gap-1.5"
          >
            {isSubmitting ? <LoadingSpinner size="xs" /> : <EyeOff className="w-4 h-4" />}
            {t('unpublishConfirmButton')}
          </Button>
        </div>
      </div>
    </div>
  ) : null;

  return createPortal(
    <>
      {modalContent}
      {unpublishConfirmModal}
      {pendingScreening && (
        <ImageScreeningModal
          open
          flagged={pendingScreening.flagged}
          attestationTextVersion={pendingScreening.attestationTextVersion}
          interfaceId={selectedInterfaceId === 'none' ? '' : selectedInterfaceId}
          aiReplacementCostPerImage={pendingScreening.aiReplacementCostPerImage}
          onCancel={handleScreeningCancel}
          onConfirm={handleScreeningConfirm}
        />
      )}
    </>,
    document.body,
  );
}

// Keep backward-compatible export name
export { PublishWorkflowModal as ShareWorkflowModal };
