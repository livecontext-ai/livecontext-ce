'use client';

import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { createPortal } from 'react-dom';
import {
  ArrowLeft,
  ArrowRight,
  Check,
  X,
  FileText,
  FolderOpen,
  Trash2,
  Search,
  Workflow,
  Monitor,
  Bot,
  Table,
  AppWindow,
  ChevronDown,
  ChevronRight,
  Briefcase,
  Rocket,
  Zap,
  Star,
  Heart,
  Globe,
  Shield,
  Code,
  Database,
  Cloud,
  BookOpen,
  Camera,
  Music,
  Palette,
  ShoppingCart,
  Users,
  Mail,
  MessageSquare,
  BarChart3,
  Settings,
  Target,
  Lightbulb,
  Layers,
  Puzzle as PuzzleIcon,
  Trophy,
  Flame,
  Sparkles,
  Home,
  Package,
  Gift,
  Coffee,
  Headphones,
  Compass,
  Map,
  Bookmark,
  Bell,
  Crown,
  Gem,
  Leaf,
  Anchor,
  Plane,
  Cpu,
  Terminal,
  Pen,
  GraduationCap,
  Building2,
  Landmark,
  Boxes,
  Clapperboard,
  Gamepad2,
  Microscope,
  Stethoscope,
  Scale,
  Megaphone,
  Radio,
  Tv,
  Smartphone,
  Watch,
  Store,
  Truck,
  CreditCard,
  Wallet,
  Receipt,
  PiggyBank,
  BadgeDollarSign,
  CircleDollarSign,
  HandCoins,
  Banknote,
  Calculator,
  ClipboardList,
  FileSpreadsheet,
  FolderKanban,
  Kanban,
  ListChecks,
  CalendarDays,
  CalendarClock,
  Timer,
  AlarmClock,
  SquareActivity,
  Activity,
  PieChart,
  TrendingUp,
  LineChart,
  AreaChart,
  Wrench,
  Hammer,
  ScanLine,
  QrCode,
  Fingerprint,
  Lock,
  Key,
  Eye,
  EyeOff,
  UserCheck,
  UserPlus,
  UserCog,
  Network,
  Share2,
  Link,
  Unlink,
  Wifi,
  Server,
  HardDrive,
  FolderTree,
  FileCode,
  GitBranch,
  GitPullRequest,
  Bug,
  TestTube2,
  Beaker,
  FlaskConical,
  Atom,
  Dna,
  Brain,
  BrainCircuit,
  Wand2,
  Bot as BotIcon,
  CircuitBoard,
  Webhook,
  Plug,
  Power,
  Battery,
  Satellite,
  Earth,
} from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useTranslations } from 'next-intl';
import { AvatarDisplay } from '@/components/agents';
import { useQueryClient, useQuery } from '@tanstack/react-query';
import { useProjectMutations, useProject, useProjectPermissions, useResourceAssignment } from '@/hooks/useProjects';
import { orchestratorApi } from '@/lib/api';
import { storageApi, S3_FILES_FILTER } from '@/lib/api/storage-api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { queryKeys } from '@/lib/api/query-keys';
import type {
  Project,
  UpdateProjectRequest,
} from '@/lib/api/orchestrator/project.types';

const COLOR_PALETTE = [
  '#3b82f6', '#06b6d4', '#22c55e', '#eab308',
  '#f97316', '#ef4444', '#ec4899', '#8b5cf6',
  '#64748b', '#000000',
];

const PROJECT_ICONS: { key: string; icon: React.ElementType }[] = [
  { key: 'briefcase', icon: Briefcase },
  { key: 'rocket', icon: Rocket },
  { key: 'zap', icon: Zap },
  { key: 'star', icon: Star },
  { key: 'heart', icon: Heart },
  { key: 'globe', icon: Globe },
  { key: 'shield', icon: Shield },
  { key: 'code', icon: Code },
  { key: 'database', icon: Database },
  { key: 'cloud', icon: Cloud },
  { key: 'book-open', icon: BookOpen },
  { key: 'camera', icon: Camera },
  { key: 'music', icon: Music },
  { key: 'palette', icon: Palette },
  { key: 'shopping-cart', icon: ShoppingCart },
  { key: 'users', icon: Users },
  { key: 'mail', icon: Mail },
  { key: 'message-square', icon: MessageSquare },
  { key: 'bar-chart', icon: BarChart3 },
  { key: 'settings', icon: Settings },
  { key: 'target', icon: Target },
  { key: 'lightbulb', icon: Lightbulb },
  { key: 'layers', icon: Layers },
  { key: 'puzzle', icon: PuzzleIcon },
  { key: 'trophy', icon: Trophy },
  { key: 'flame', icon: Flame },
  { key: 'sparkles', icon: Sparkles },
  { key: 'home', icon: Home },
  { key: 'package', icon: Package },
  { key: 'gift', icon: Gift },
  { key: 'coffee', icon: Coffee },
  { key: 'headphones', icon: Headphones },
  { key: 'compass', icon: Compass },
  { key: 'map', icon: Map },
  { key: 'bookmark', icon: Bookmark },
  { key: 'bell', icon: Bell },
  { key: 'crown', icon: Crown },
  { key: 'gem', icon: Gem },
  { key: 'leaf', icon: Leaf },
  { key: 'anchor', icon: Anchor },
  { key: 'plane', icon: Plane },
  { key: 'cpu', icon: Cpu },
  { key: 'terminal', icon: Terminal },
  { key: 'pen', icon: Pen },
  { key: 'graduation-cap', icon: GraduationCap },
  { key: 'building', icon: Building2 },
  { key: 'landmark', icon: Landmark },
  { key: 'boxes', icon: Boxes },
  { key: 'clapperboard', icon: Clapperboard },
  { key: 'gamepad', icon: Gamepad2 },
  { key: 'microscope', icon: Microscope },
  { key: 'stethoscope', icon: Stethoscope },
  { key: 'scale', icon: Scale },
  { key: 'megaphone', icon: Megaphone },
  { key: 'radio', icon: Radio },
  { key: 'tv', icon: Tv },
  { key: 'smartphone', icon: Smartphone },
  { key: 'watch', icon: Watch },
  // Commerce & Finance
  { key: 'store', icon: Store },
  { key: 'truck', icon: Truck },
  { key: 'credit-card', icon: CreditCard },
  { key: 'wallet', icon: Wallet },
  { key: 'receipt', icon: Receipt },
  { key: 'piggy-bank', icon: PiggyBank },
  { key: 'badge-dollar', icon: BadgeDollarSign },
  { key: 'circle-dollar', icon: CircleDollarSign },
  { key: 'hand-coins', icon: HandCoins },
  { key: 'banknote', icon: Banknote },
  // Productivity & Planning
  { key: 'calculator', icon: Calculator },
  { key: 'clipboard-list', icon: ClipboardList },
  { key: 'file-spreadsheet', icon: FileSpreadsheet },
  { key: 'folder-kanban', icon: FolderKanban },
  { key: 'kanban', icon: Kanban },
  { key: 'list-checks', icon: ListChecks },
  { key: 'calendar-days', icon: CalendarDays },
  { key: 'calendar-clock', icon: CalendarClock },
  { key: 'timer', icon: Timer },
  { key: 'alarm-clock', icon: AlarmClock },
  // Analytics & Monitoring
  { key: 'square-activity', icon: SquareActivity },
  { key: 'activity', icon: Activity },
  { key: 'pie-chart', icon: PieChart },
  { key: 'trending-up', icon: TrendingUp },
  { key: 'line-chart', icon: LineChart },
  { key: 'area-chart', icon: AreaChart },
  // Tools & Utility
  { key: 'wrench', icon: Wrench },
  { key: 'hammer', icon: Hammer },
  { key: 'scan-line', icon: ScanLine },
  { key: 'qr-code', icon: QrCode },
  // Security & Identity
  { key: 'fingerprint', icon: Fingerprint },
  { key: 'lock', icon: Lock },
  { key: 'key', icon: Key },
  { key: 'eye', icon: Eye },
  { key: 'eye-off', icon: EyeOff },
  { key: 'user-check', icon: UserCheck },
  { key: 'user-plus', icon: UserPlus },
  { key: 'user-cog', icon: UserCog },
  // Network & Connectivity
  { key: 'network', icon: Network },
  { key: 'share', icon: Share2 },
  { key: 'link', icon: Link },
  { key: 'unlink', icon: Unlink },
  { key: 'wifi', icon: Wifi },
  { key: 'server', icon: Server },
  { key: 'hard-drive', icon: HardDrive },
  // Development & Code
  { key: 'folder-tree', icon: FolderTree },
  { key: 'file-code', icon: FileCode },
  { key: 'git-branch', icon: GitBranch },
  { key: 'git-pull-request', icon: GitPullRequest },
  { key: 'bug', icon: Bug },
  { key: 'test-tube', icon: TestTube2 },
  // Science & AI
  { key: 'beaker', icon: Beaker },
  { key: 'flask', icon: FlaskConical },
  { key: 'atom', icon: Atom },
  { key: 'dna', icon: Dna },
  { key: 'brain', icon: Brain },
  { key: 'brain-circuit', icon: BrainCircuit },
  { key: 'wand', icon: Wand2 },
  { key: 'bot', icon: BotIcon },
  { key: 'circuit-board', icon: CircuitBoard },
  // Infrastructure
  { key: 'webhook', icon: Webhook },
  { key: 'plug', icon: Plug },
  { key: 'power', icon: Power },
  { key: 'battery', icon: Battery },
  { key: 'satellite', icon: Satellite },
  { key: 'earth', icon: Earth },
];

/** Resolve icon component from stored key */
export function getProjectIcon(iconKey: string | null | undefined): React.ElementType {
  if (!iconKey) return Briefcase;
  const found = PROJECT_ICONS.find(i => i.key === iconKey);
  return found?.icon || Briefcase;
}

const TOTAL_STEPS = 2;

type ResourceType = 'workflow' | 'interface' | 'agent' | 'datasource' | 'application' | 'file';

const RESOURCE_TYPES: { key: ResourceType; icon: React.ElementType; label: string }[] = [
  { key: 'workflow', icon: Workflow, label: 'tabs.workflows' },
  { key: 'interface', icon: Monitor, label: 'tabs.interfaces' },
  { key: 'agent', icon: Bot, label: 'tabs.agents' },
  { key: 'datasource', icon: Table, label: 'tabs.tables' },
  { key: 'application', icon: AppWindow, label: 'tabs.applications' },
  { key: 'file', icon: FileText, label: 'tabs.files' },
];

/** Pending changes tracked locally until save */
interface PendingChange {
  resourceType: ResourceType;
  resourceId: string;
  action: 'assign' | 'remove';
}

// ─── Step Indicator ──────────────────────────────────────────────

interface StepIndicatorProps {
  currentStep: number;
  totalSteps: number;
  onStepClick: (step: number) => void;
}

const StepIndicator: React.FC<StepIndicatorProps> = ({ currentStep, totalSteps, onStepClick }) => {
  const t = useTranslations('project');
  const steps = [
    { number: 1, icon: FileText, label: t('steps.info') },
    { number: 2, icon: FolderOpen, label: t('steps.resources') },
  ];

  return (
    <div className="flex items-center justify-center gap-2 mb-6">
      {steps.slice(0, totalSteps).map((step, index) => {
        const isActive = step.number === currentStep;
        const isCompleted = step.number < currentStep;
        const Icon = step.icon;

        return (
          <React.Fragment key={step.number}>
            <button
              type="button"
              onClick={() => onStepClick(step.number)}
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
            {index < totalSteps - 1 && (
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

// ─── Resource Collapsible Section (local state only) ─────────────

interface ResourceSectionProps {
  projectId: string;
  resourceType: ResourceType;
  icon: React.ElementType;
  label: string;
  canAssign: boolean;
  pendingChanges: PendingChange[];
  onToggle: (resourceType: ResourceType, resourceId: string, action: 'assign' | 'remove') => void;
}

const ResourceSection: React.FC<ResourceSectionProps> = ({
  projectId,
  resourceType,
  icon: Icon,
  label,
  canAssign,
  pendingChanges,
  onToggle,
}) => {
  const t = useTranslations('project');
  const [expanded, setExpanded] = useState(false);
  const [search, setSearch] = useState('');

  const { data: allResources = [], isLoading } = useQuery({
    queryKey: ['resources', resourceType],
    queryFn: async () => {
      switch (resourceType) {
        case 'workflow': return orchestratorApi.getWorkflows({ size: 100 });
        case 'interface': {
          const list = await orchestratorApi.getInterfaces();
          return (list || []).filter((i: any) => !i.interfaceType || i.interfaceType !== 'web_search');
        }
        case 'agent': return orchestratorApi.getAgents();
        case 'datasource': return orchestratorApi.getDataSources();
        case 'application': {
          const res = await publicationService.getMyPublications();
          return res.publications || [];
        }
        case 'file': {
          const page = await storageApi.getExplorerEntries({ size: 100, ...S3_FILES_FILTER });
          return page.content || [];
        }
        default: return [];
      }
    },
  });

  // Compute visual state: real data + pending changes
  const { assigned, available } = useMemo(() => {
    const myPending = pendingChanges.filter(c => c.resourceType === resourceType);
    const pendingAssignIds = new Set(myPending.filter(c => c.action === 'assign').map(c => c.resourceId));
    const pendingRemoveIds = new Set(myPending.filter(c => c.action === 'remove').map(c => c.resourceId));

    const assignedList: { id: string; name: string; avatarUrl?: string }[] = [];
    const availableList: { id: string; name: string; avatarUrl?: string }[] = [];

    for (const r of allResources as any[]) {
      const item = { id: String(r.id), name: r.name || r.title || r.fileName || r.id, avatarUrl: r.avatarUrl };
      const rProjectId = r.projectId || r.project_id;
      const isReallyAssigned = rProjectId === projectId;

      // Visual state after pending changes
      if (pendingRemoveIds.has(item.id)) {
        availableList.push(item); // was assigned, pending removal
      } else if (pendingAssignIds.has(item.id)) {
        assignedList.push(item); // was available, pending assignment
      } else if (isReallyAssigned) {
        assignedList.push(item);
      } else if (!rProjectId) {
        availableList.push(item);
      }
    }
    return { assigned: assignedList, available: availableList };
  }, [allResources, projectId, pendingChanges, resourceType]);

  const filtered = available.filter((r) =>
    r.name.toLowerCase().includes(search.toLowerCase())
  );

  const handleToggle = (resourceId: string, isCurrentlyAssigned: boolean) => {
    onToggle(resourceType, resourceId, isCurrentlyAssigned ? 'remove' : 'assign');
  };

  return (
    <div className="border border-black/10 dark:border-white/10 rounded-xl overflow-hidden">
      {/* Collapsible header */}
      <div
        className="flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-[var(--bg-secondary)] transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="w-8 h-8 rounded-full bg-theme-tertiary flex items-center justify-center flex-shrink-0">
          <Icon className="h-4 w-4 text-theme-primary" />
        </div>
        <div className="flex-1 min-w-0">
          <span className="text-sm font-medium text-theme-primary">{t(label)}</span>
          {isLoading ? (
            <span className="text-xs text-theme-muted ml-2">
              <LoadingSpinner size="xs" className="inline" />
            </span>
          ) : assigned.length > 0 ? (
            <span className="text-xs text-theme-muted ml-2">
              {assigned.length} assigned
            </span>
          ) : null}
        </div>
        {!isLoading && assigned.length > 0 && (
          <span className="text-xs text-theme-secondary bg-theme-tertiary px-2 py-0.5 rounded-full">
            {assigned.length}
          </span>
        )}
        {expanded
          ? <ChevronDown className="w-4 h-4 text-theme-secondary flex-shrink-0" />
          : <ChevronRight className="w-4 h-4 text-theme-secondary flex-shrink-0" />
        }
      </div>

      {/* Expanded content */}
      {expanded && (
        <div className="border-t border-black/5 dark:border-white/5">
          {/* Loading skeleton */}
          {isLoading && (
            <div className="px-4 py-3 space-y-3">
              {[1, 2, 3].map(i => (
                <div key={i} className="flex items-center gap-3 pl-2 animate-pulse">
                  <div className="w-4 h-4 rounded border border-gray-200 dark:border-gray-700 bg-gray-100 dark:bg-gray-800" />
                  <div className="h-3.5 w-3.5 rounded bg-gray-100 dark:bg-gray-800" />
                  <div className="h-4 flex-1 rounded bg-gray-100 dark:bg-gray-800 max-w-[180px]" />
                </div>
              ))}
            </div>
          )}
          {/* Search */}
          {!isLoading && (available.length > 0 || assigned.length > 3) && (
            <div className="px-4 py-2 border-b border-black/5 dark:border-white/5">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-theme-secondary" />
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder={t('searchResources')}
                  className="w-full pl-9 pr-3 h-9 text-sm rounded-lg border border-black/10 dark:border-white/10 bg-[var(--bg-primary)] text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]/20"
                  onClick={(e) => e.stopPropagation()}
                />
              </div>
            </div>
          )}

          {/* Resource list */}
          {!isLoading && (
          <div className="max-h-[250px] overflow-y-auto">
            {/* Assigned resources first */}
            {assigned.filter(r => !search || r.name.toLowerCase().includes(search.toLowerCase())).map((r) => (
              <div
                key={r.id}
                className="flex items-center gap-3 px-4 py-2.5 pl-6 hover:bg-[var(--bg-tertiary)] cursor-pointer transition-colors"
                onClick={() => canAssign && handleToggle(r.id, true)}
              >
                <button
                  type="button"
                  disabled={!canAssign}
                  className="w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 bg-[var(--accent-primary)] border-[var(--accent-primary)]"
                >
                  <Check className="w-3 h-3 text-[var(--accent-foreground)]" />
                </button>
                {resourceType === 'agent' && r.avatarUrl ? (
                  <AvatarDisplay avatarUrl={r.avatarUrl} name={r.name} size="sm" className="!w-5 !h-5" />
                ) : (
                  <Icon className="h-3.5 w-3.5 text-theme-secondary flex-shrink-0" />
                )}
                <span className="text-sm text-theme-primary truncate">{r.name}</span>
              </div>
            ))}

            {/* Available (unassigned) resources */}
            {filtered.map((r) => (
              <div
                key={r.id}
                className={`flex items-center gap-3 px-4 py-2.5 pl-6 transition-colors ${
                  canAssign ? 'hover:bg-[var(--bg-tertiary)] cursor-pointer' : 'opacity-50 cursor-not-allowed'
                }`}
                onClick={() => canAssign && handleToggle(r.id, false)}
              >
                <button
                  type="button"
                  disabled={!canAssign}
                  className="w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 border-gray-300 dark:border-gray-600"
                >
                  {/* empty checkbox */}
                </button>
                {resourceType === 'agent' && r.avatarUrl ? (
                  <AvatarDisplay avatarUrl={r.avatarUrl} name={r.name} size="sm" className="!w-5 !h-5" />
                ) : (
                  <Icon className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
                )}
                <span className="text-sm text-theme-secondary truncate">{r.name}</span>
              </div>
            ))}

            {assigned.length === 0 && available.length === 0 && (
              <div className="py-6 text-center text-sm text-theme-secondary">
                {t('noResourcesYet')}
              </div>
            )}
          </div>
          )}
        </div>
      )}
    </div>
  );
};

// ─── Main Modal ──────────────────────────────────────────────────

export interface ProjectMultiStepModalProps {
  project?: Project;
  initialStep?: number;
  onClose: () => void;
  onSuccess: (project: Project) => void;
  onDelete?: () => void;
}

export function ProjectMultiStepModal({
  project,
  initialStep = 1,
  onClose,
  onSuccess,
  onDelete,
}: ProjectMultiStepModalProps) {
  const t = useTranslations('project');
  const queryClient = useQueryClient();
  const isEditMode = !!project;

  // Step management
  const [currentStep, setCurrentStep] = useState(initialStep);
  const [mounted, setMounted] = useState(false);

  // Step 1: Info
  const [name, setName] = useState(project?.name || '');
  const [description, setDescription] = useState(project?.description || '');
  const [color, setColor] = useState(project?.color || '#3b82f6');
  const [icon, setIcon] = useState(project?.icon || 'briefcase');
  const [error, setError] = useState<string | null>(null);

  // Mutations
  const { createProject, updateProject, deleteProject } = useProjectMutations();
  const isSaving = createProject.isPending || updateProject.isPending;
  const isDeletePending = deleteProject.isPending;

  // Created project ID for step 2 after create
  const [createdProject, setCreatedProject] = useState<Project | null>(null);
  const activeProjectId = createdProject?.id || project?.id || '';

  // Resource assignment (used only on save)
  const { assignResource, removeResource } = useResourceAssignment(activeProjectId);
  const [pendingChanges, setPendingChanges] = useState<PendingChange[]>([]);
  const [savingResources, setSavingResources] = useState(false);

  // Edit mode: fetch detail with members
  const { project: projectDetail } = useProject(isEditMode ? project!.id : null);
  const permissions = useProjectPermissions(projectDetail || project || null);

  useEffect(() => { setMounted(true); }, []);

  // ─── Pending changes management ────────────────────────────────

  const handleToggle = useCallback((resourceType: ResourceType, resourceId: string, action: 'assign' | 'remove') => {
    setPendingChanges(prev => {
      // Check if there's already a pending change for this resource
      const existingIdx = prev.findIndex(c => c.resourceType === resourceType && c.resourceId === resourceId);
      if (existingIdx >= 0) {
        // Cancel it out (toggle back)
        return prev.filter((_, i) => i !== existingIdx);
      }
      // Add new pending change
      return [...prev, { resourceType, resourceId, action }];
    });
  }, []);

  // ─── Navigation ───────────────────────────────────────────────

  const canProceedFromStep = (step: number): boolean => {
    switch (step) {
      case 1: return name.trim().length > 0;
      case 2: return true;
      default: return false;
    }
  };

  const goToStep = (step: number) => {
    if (step <= currentStep || canProceedFromStep(step - 1)) {
      setCurrentStep(step);
    }
  };

  const nextStep = async () => {
    // For new projects, must create first to get an ID for step 2
    if (currentStep === 1 && !isEditMode && !createdProject) {
      try {
        setError(null);
        const created = await createProject.mutateAsync({
          name: name.trim(),
          description: description.trim() || undefined,
          color,
          icon,
        });
        setCreatedProject(created);
        setCurrentStep(2);
      } catch (err: any) {
        setError(err?.message || t('error'));
      }
      return;
    }

    // In edit mode, just navigate - save happens only on Save button
    if (currentStep < TOTAL_STEPS && canProceedFromStep(currentStep)) {
      setCurrentStep((prev) => prev + 1);
    }
  };

  const prevStep = () => {
    if (currentStep > 1) setCurrentStep((prev) => prev - 1);
  };

  // ─── Handlers ─────────────────────────────────────────────────

  const executePendingChanges = async () => {
    if (pendingChanges.length === 0) return;
    setSavingResources(true);
    try {
      for (const change of pendingChanges) {
        if (change.action === 'assign') {
          await assignResource.mutateAsync({
            resourceType: change.resourceType,
            resourceId: change.resourceId,
          });
        } else {
          await removeResource.mutateAsync({
            resourceType: change.resourceType,
            resourceId: change.resourceId,
          });
        }
      }
      setPendingChanges([]);
      // Invalidate resource queries
      queryClient.invalidateQueries({ queryKey: queryKeys.project.detail(activeProjectId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.project.resources(activeProjectId) });
    } finally {
      setSavingResources(false);
    }
  };

  const handleSave = async () => {
    try {
      setError(null);
      let resultProject = createdProject || project!;

      // Save project info if in edit mode
      if (isEditMode) {
        const data: UpdateProjectRequest = {
          name: name.trim(),
          description: description.trim() || undefined,
          color,
          icon,
        };
        resultProject = await updateProject.mutateAsync({ id: project!.id, data });
      }

      // Save pending resource changes
      await executePendingChanges();
      onSuccess(resultProject);
    } catch (err: any) {
      setError(err?.message || t('error'));
    }
  };

  const handleDelete = async () => {
    if (!project) return;
    if (!window.confirm(t('deleteConfirm'))) return;
    try {
      await deleteProject.mutateAsync(project.id);
      onDelete?.();
      onClose();
    } catch (err: any) {
      setError(err?.message || t('error'));
    }
  };

  // ─── Render ───────────────────────────────────────────────────

  if (!mounted) return null;

  const isProcessing = isSaving || savingResources;

  const stepDescriptions = [
    t('steps.infoDesc'),
    t('steps.resourcesDesc'),
  ];

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="max-w-2xl w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-black/10 dark:border-white/10 max-h-[90vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-8 pt-8 pb-4">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-2xl font-semibold text-theme-primary">
              {isEditMode ? t('editProject') : t('createProject')}
            </h3>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
            >
              <X className="h-5 w-5 text-theme-secondary" />
            </button>
          </div>
          <p className="text-sm text-theme-secondary text-center mb-4">
            {stepDescriptions[currentStep - 1]}
          </p>
          <StepIndicator
            currentStep={currentStep}
            totalSteps={TOTAL_STEPS}
            onStepClick={goToStep}
          />
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-8 pb-4">
          {/* Step 1: Info */}
          {currentStep === 1 && (
            <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
              {/* Name */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1.5">{t('name')}</label>
                <Input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t('namePlaceholder')}
                  autoFocus
                />
              </div>

              {/* Description */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1.5">{t('description')}</label>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t('descriptionPlaceholder')}
                  rows={3}
                  className="flex w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] ring-offset-background placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2 resize-none"
                />
              </div>

              {/* Color & Icon */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">{t('color')}</label>
                <div className="flex gap-2 flex-wrap">
                  {COLOR_PALETTE.map((c) => (
                    <button
                      key={c}
                      type="button"
                      onClick={() => setColor(c)}
                      className={`w-7 h-7 rounded-full transition-transform ${
                        color === c ? 'ring-2 ring-offset-2 ring-black/30 dark:ring-white/30 scale-110' : 'hover:scale-105'
                      }`}
                      style={{ backgroundColor: c }}
                    />
                  ))}
                </div>
              </div>

              {/* Icon */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">{t('icon')}</label>
                <div className="flex items-center gap-3">
                  {/* Preview circle */}
                  <div
                    className="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0"
                    style={{ backgroundColor: color }}
                  >
                    {(() => {
                      const IconComp = getProjectIcon(icon);
                      return <IconComp className="w-5 h-5 text-white" />;
                    })()}
                  </div>
                  {/* Dropdown trigger */}
                  <Popover>
                    <PopoverTrigger asChild>
                      <button
                        type="button"
                        className="flex items-center gap-2 px-3 py-2 rounded-xl border border-black/10 dark:border-white/10 bg-[var(--bg-primary)] hover:bg-[var(--bg-secondary)] transition-colors text-sm text-theme-primary"
                      >
                        {(() => {
                          const IconComp = getProjectIcon(icon);
                          return <IconComp className="w-4 h-4 text-theme-secondary" />;
                        })()}
                        <span className="text-theme-secondary">{t('icon')}</span>
                        <ChevronDown className="w-3.5 h-3.5 text-theme-muted" />
                      </button>
                    </PopoverTrigger>
                    <PopoverContent
                      align="start"
                      sideOffset={5}
                      className="z-[10000] w-[min(340px,calc(100vw-48px))] p-3 bg-theme-primary rounded-2xl border border-gray-300/70 dark:border-gray-600/70"
                    >
                      <div className="grid grid-cols-8 gap-1.5 max-h-[280px] overflow-y-auto p-0.5">
                        {PROJECT_ICONS.map((ic) => {
                          const IconComp = ic.icon;
                          const isSelected = icon === ic.key;
                          return (
                            <button
                              key={ic.key}
                              type="button"
                              onClick={() => setIcon(ic.key)}
                              className={`w-8 h-8 rounded-lg flex items-center justify-center transition-all ${
                                isSelected
                                  ? 'ring-2 ring-[var(--accent-primary)] bg-[var(--accent-primary)]/10 text-[var(--accent-primary)]'
                                  : 'hover:bg-black/5 dark:hover:bg-white/5 text-theme-secondary hover:text-theme-primary'
                              }`}
                            >
                              <IconComp className="w-4 h-4" />
                            </button>
                          );
                        })}
                      </div>
                    </PopoverContent>
                  </Popover>
                </div>
              </div>
            </div>
          )}

          {/* Step 2: Resources */}
          {currentStep === 2 && (
            <div className="space-y-3 animate-in fade-in-0 slide-in-from-right-4 duration-300">
              {activeProjectId ? (
                RESOURCE_TYPES.map((rt) => (
                  <ResourceSection
                    key={rt.key}
                    projectId={activeProjectId}
                    resourceType={rt.key}
                    icon={rt.icon}
                    label={rt.label}
                    canAssign={permissions.canAssignResources || !isEditMode}
                    pendingChanges={pendingChanges}
                    onToggle={handleToggle}
                  />
                ))
              ) : (
                <p className="text-sm text-theme-secondary text-center py-8">{t('noResourcesYet')}</p>
              )}
            </div>
          )}

          {/* Error */}
          {error && (
            <div className="mt-3 p-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50">
              <p className="text-sm text-red-700 dark:text-red-400">{error}</p>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-8 py-4 border-t border-black/10 dark:border-white/10 flex items-center justify-between">
          <div className="flex items-center gap-2">
            {isEditMode && permissions.canDelete && currentStep === 1 && (
              <Button
                variant="ghost"
                onClick={handleDelete}
                disabled={isDeletePending}
                className="text-red-600 hover:text-red-700 hover:bg-red-50 dark:hover:bg-red-900/20"
              >
                <Trash2 className="h-4 w-4 mr-2" />
                {isDeletePending ? t('saving') : t('deleteProject')}
              </Button>
            )}
            {currentStep > 1 && (
              <Button variant="ghost" onClick={prevStep} disabled={isProcessing}>
                <ArrowLeft className="h-4 w-4 mr-2" />
                {t('back')}
              </Button>
            )}
          </div>

          <div className="flex gap-2">
            <Button variant="outline" onClick={onClose} disabled={isProcessing}>
              {t('cancel')}
            </Button>

            {currentStep < TOTAL_STEPS ? (
              <>
                {currentStep > 1 && (
                  <Button variant="outline" onClick={() => setCurrentStep((prev) => prev + 1)}>
                    {t('skip')}
                  </Button>
                )}
                <Button onClick={nextStep} disabled={!canProceedFromStep(currentStep) || isProcessing}>
                  {isProcessing && <LoadingSpinner size="xs" className="mr-2" />}
                  {t('next')}
                  <ArrowRight className="h-4 w-4 ml-2" />
                </Button>
              </>
            ) : (
              <Button onClick={handleSave} disabled={!name.trim() || isProcessing}>
                {isProcessing ? (
                  <>
                    <LoadingSpinner size="xs" className="mr-2" />
                    {t('saving')}
                  </>
                ) : (
                  <>
                    <Check className="h-4 w-4 mr-2" />
                    {isEditMode ? t('save') : t('create')}
                  </>
                )}
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
