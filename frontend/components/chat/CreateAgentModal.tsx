'use client';

import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { createPortal } from 'react-dom';
import Image from 'next/image';
import {
  Bot, Mail, ChevronDown, ChevronRight, Check, Search, X, Loader2, Info, Workflow,
  Webhook, Copy, Pencil, ArrowRight, ArrowLeft, User, Settings,
  Puzzle, MessageCircle, Code, ExternalLink, Palette, Clock, Globe, Zap, Plus,
  AppWindow, Table, Monitor, FileText, ShieldCheck, Sparkles, Trash2, Brain
} from 'lucide-react';
import { InfoPopover } from '@/components/ui/info-popover';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Slider } from '@/components/ui/slider';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue, SELECT_EMPTY_VALUE_SENTINEL } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { orchestratorApi } from '@/lib/api/orchestrator';
import type { AgentWebhook, AgentSchedule } from '@/lib/api/orchestrator/types';
import type { Skill } from '@/lib/api';
import { useQuery, useQueryClient, useInfiniteQuery } from '@tanstack/react-query';
import { useToast } from '@/components/Toast';
import Toast from '@/components/Toast';
import { useTranslations } from 'next-intl';
import { useVisibleModels, getModelsCache, isEmptySelectedModel, toNonBridgeSelectedModel } from '@/hooks/useModels';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import { resolveFreeTierPreferredModel } from '@/lib/models/freeTierModel';
import { ModelPicker } from '@/components/ai/ModelPicker';
import { useMcpApis, fetchApiTools, ApiTool } from '@/app/workflows/builder/hooks/useMcpData';
import { apiClient } from '@/lib/api/api-client';
import { getAllowedIds, buildToolsConfigPayload, isGenerationEnabled, getGrant, getFileAccessMode, getMemoryAccessMode, isMailboxEnabled, getMailboxAccessMode, GRANT_FAMILIES, type ResourceGrant } from '@/lib/agents/toolsConfigAccess';
import { initialTurnLimits, buildChangedTurnLimits } from '@/lib/agents/agentTurnLimits';
import { initialCompaction, buildChangedCompaction } from '@/lib/agents/agentCompaction';
import { Switch } from '@/components/ui/switch';
import { ConfirmDeleteModal } from '@/components/chat/ConfirmDeleteModal';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

/** UUID detector for legacy tools_config.tools[] entries - see useAgentFleetState.ts. */
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
import { useLazyLoadObserver } from '@/app/workflows/builder/components/palette/useLazyLoadObserver';
import { AvatarDisplay, AvatarPicker, getPresetDefaultName, isPresetDefaultName } from '@/components/agents';
import { fileService, getPublicAvatarUrlById } from '@/lib/api/orchestrator/file.service';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { storageApi, S3_FILES_FILTER } from '@/lib/api/storage-api';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { scheduleSettingsService } from '@/lib/api/orchestrator/schedule-settings.service';
import { timezoneOptionsFor } from '@/lib/schedule/timezoneOptions';
import { computeAncestorIds } from './ancestorDetection';
import type { AcquiredApplication, WorkflowPublication, DataSource, Interface, Agent } from '@/lib/api/orchestrator/types';
import { SkillFolderTree } from '@/components/skills/SkillFolderTree';
import type { SkillFolder } from '@/lib/api/orchestrator/types';
import { DEFAULT_SKILLS } from '@/lib/constants/defaultSkills';
import { formatCost } from '@/lib/format-cost';
import { getProviderIconSrc, getProviderDisplayName } from '@/lib/ai-providers/providerIcons';
import { REASONING_EFFORT_LEVELS, supportsReasoningEffort } from '@/lib/ai-providers/reasoningEffort';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { ModalStepIndicator } from '@/components/ui/ModalStepIndicator';
import { ChannelDestinationPicker, isWorking, useChatDestinations } from '@/components/app/ChannelDestinationPicker';
import { getClientLocale } from '@/lib/utils/locale';
import { track } from '@/lib/analytics/analytics';
import { ServiceLogo } from '@/components/ui/service-logo';

/** Fallback: synthetic Skill objects for defaults not yet seeded in DB */
const DEFAULT_SKILLS_FALLBACK: Skill[] = DEFAULT_SKILLS.map(ds => ({
  id: ds.id,
  tenantId: '',
  name: ds.name,
  description: ds.description,
  icon: ds.icon,
  instructions: '',
  isActive: true,
  createdAt: '',
  updatedAt: '',
  folderId: null,
  defaultKey: ds.id,
}));

// ============== Constants ==============

type ToolsMode = 'all' | 'none' | 'custom' | 'off';
const MAX_TOOLS = 30;
const TOTAL_STEPS = 3;

/**
 * How long the default model waits for the account's plan verdict (V494).
 *
 * <p>Long enough that a normal balance request wins, short enough that a reader
 * filling in a name never reaches the model field without one. Past it the form
 * falls back to the catalogue default, which is what it always used.
 */
const PLAN_VERDICT_TIMEOUT_MS = 5000;

// Widget position options
const WIDGET_POSITIONS = [
  { value: 'bottom-right', label: 'Bottom Right' },
  { value: 'bottom-left', label: 'Bottom Left' },
  { value: 'top-right', label: 'Top Right' },
  { value: 'top-left', label: 'Top Left' },
] as const;

// Widget theme options
const WIDGET_THEMES = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'auto', label: 'Auto (System)' },
] as const;

// Auto-open delay options
const AUTO_OPEN_DELAYS = [
  { value: 0, label: 'Disabled' },
  { value: 3, label: '3 seconds' },
  { value: 5, label: '5 seconds' },
  { value: 10, label: '10 seconds' },
] as const;

// Preset colors for widget
const PRESET_COLORS = [
  '#000000', '#78716c', '#f97316', '#ef4444', '#ec4899',
  '#8b5cf6', '#6366f1', '#3b82f6', '#14b8a6', '#22c55e',
];

// Schedule presets for agent scheduled execution
const SCHEDULE_PRESETS = [
  { value: 'every_minute', label: 'Every minute', cron: '* * * * *' },
  { value: 'every_5_minutes', label: 'Every 5 minutes', cron: '*/5 * * * *' },
  { value: 'every_15_minutes', label: 'Every 15 minutes', cron: '*/15 * * * *' },
  { value: 'every_30_minutes', label: 'Every 30 minutes', cron: '*/30 * * * *' },
  { value: 'every_hour', label: 'Every hour', cron: '0 * * * *' },
  { value: 'every_day_9am', label: 'Every day at 9:00 AM', cron: '0 9 * * *' },
  { value: 'every_day_noon', label: 'Every day at 12:00 PM', cron: '0 12 * * *' },
  { value: 'every_day_6pm', label: 'Every day at 6:00 PM', cron: '0 18 * * *' },
  { value: 'every_monday', label: 'Every Monday at 9:00 AM', cron: '0 9 * * 1' },
  { value: 'every_weekday', label: 'Every weekday at 9:00 AM', cron: '0 9 * * 1-5' },
  { value: 'first_of_month', label: 'First of every month', cron: '0 9 1 * *' },
  { value: 'custom', label: 'Custom cron expression', cron: '' },
];

/**
 * Which entry of the frequency dropdown a cron IS, or `custom` when it is none of them.
 *
 * <p>One function rather than the same `find(...)?.value || 'custom'` at each place a cron
 * arrives from outside this modal: restoring an existing agent's schedule, and seeding a
 * new one from the agenda. They must agree, or the dropdown claims "Every day at 9:00 AM"
 * over a cron that says something else.
 */
function presetForCron(cron: string): string {
  return SCHEDULE_PRESETS.find(p => p.cron === cron)?.value || 'custom';
}

// ============== Types ==============

interface AgentData {
  id?: string;
  name?: string;
  description?: string;
  systemPrompt?: string;
  modelProvider?: string;
  modelName?: string;
  temperature?: number;
  maxTokens?: number;
  maxIterations?: number;
  /** Per-agent reasoning effort for CLI/bridge models (minimal|low|medium|high|xhigh). */
  reasoningEffort?: string;
  avatarUrl?: string;
  executionTimeout?: number;
  inactivityTimeout?: number;
  /** Advanced turn-limit overrides (V100 columns) - hydrate the advanced section on edit. */
  maxPerResourcePerTurn?: number | null;
  loopIdenticalStop?: number | null;
  loopConsecutiveStop?: number | null;
  /** V350 - per-agent compaction enable + cadence override. */
  compactionEnabled?: boolean | null;
  compactionAfterTurns?: number | null;
  creditBudget?: number | null;
  budgetResetMode?: 'cumulative' | 'monthly' | 'weekly';
  creditsConsumed?: number;
  isPublic?: boolean;
  isActive?: boolean;
  /** V340 - opt-in participation in the shared task backlog (default false). */
  backlogEnabled?: boolean;
  /** V299 - asks permission before a sensitive action in its own unattended runs. */
  requireToolAuthorization?: boolean;
  /** V523 - the workspace destination its requests and questions go to; null = the default. */
  chatChannelLinkId?: string | null;
  /** V524 - whether it reaches the person outside the app at all (default true). */
  chatChannelEnabled?: boolean;
  config?: Record<string, unknown>;
  toolsConfig?: {
    mode?: string;
    tools?: string[];
    workflows?: string[];
    applications?: string[];
    tables?: string[];
    interfaces?: string[];
    agents?: string[];
    webSearch?: boolean;
    // Access axes the modal already reads (getGrant / the restore effects) but
    // did not declare, which forced every caller to cast through `any` and hid
    // the fact that a template's mode and access modes were being dropped.
    workflowsGrant?: string;
    tablesGrant?: string;
    interfacesGrant?: string;
    agentsGrant?: string;
    applicationsGrant?: string;
    // Narrowed to the union the state actually holds, so a restore assigns
    // without a cast and an invalid value is caught at the call site.
    workflowAccessMode?: 'read' | 'write';
    tableAccessMode?: 'read' | 'write';
    interfaceAccessMode?: 'read' | 'write';
    agentAccessMode?: 'read' | 'write';
    applicationAccessMode?: 'read' | 'write';
    skillAccessMode?: 'read' | 'write';
    memoryAccessMode?: 'read' | 'write';
    /** Mailbox tool: opt-in (absent or false = off). */
    mailbox?: boolean;
    /** Mailbox read/write axis, like fileAccessMode. Absent = full access. */
    mailboxAccessMode?: 'read' | 'write';
  } | null;
}

interface WidgetConfig {
  enabled: boolean;
  position: typeof WIDGET_POSITIONS[number]['value'];
  theme: typeof WIDGET_THEMES[number]['value'];
  primaryColor: string;
  welcomeMessage: string;
  bubbleText: string;
  showAvatar: boolean;
  autoOpenDelay: number;
  widgetToken?: string;
  allowedOrigins?: string;
  widgetScriptUrl?: string;
}

interface CreateAgentModalProps {
  onClose: () => void;
  /**
   * Called after a save. Carries the new agent's id on a CREATE, nothing on an edit.
   *
   * <p>`result.scheduleSaved` says what happened to the schedule: true when one was written,
   * false when the write was REFUSED, absent when none was asked for. The schedule is saved
   * in its own request after the agent, and a failure there is reported here and does not
   * fail the save, so a caller that announces "scheduled" on this callback alone would say
   * it over an agent whose schedule does not exist.
   *
   * <p>`result.scheduleHasPrompt` says whether that schedule can do anything UNPROMPTED. A
   * blank instruction is answered at fire time by the agent's assigned inbox and pending
   * reviews, and a brand-new agent has neither, so a caller that announces "it runs from the
   * next occurrence" without reading this says it over an agent that will be skipped on
   * every fire. Present only when a schedule was written.
   */
  onAgentCreated: (
    agentId?: string,
    result?: { scheduleSaved?: boolean; scheduleHasPrompt?: boolean },
  ) => void;
  agent?: AgentData;
  /** Step to open on (1=Basic Info, 2=Configuration, 3=Integration). Defaults to 1. */
  initialStep?: number;
  /**
   * Open with the schedule already ON and set to this cron, used when the agent is being
   * created from somewhere that ALREADY asked when it should run (the agenda's empty-slot
   * dialog). CREATE only: on an edit the schedule is read back from the agent's own row,
   * and a seed would fight that restore.
   *
   * <p>It is deliberately not part of {@link AgentData}: a schedule is a separate resource
   * behind a separate call, and folding it into the agent shape would suggest the agent
   * POST carries it.
   */
  initialSchedule?: {
    cron: string;
    timezone: string;
    /**
     * What that cron MEANS, as the platform itself described it (the caller already asked
     * `validateCron`). Rendered under the raw expression while it is still the seeded one,
     * so a user who picked "Wednesday at 16:00" on a calendar is not handed
     * `0 16 * * 3` with nothing to check it against. Absent is fine: the line disappears.
     */
    description?: string;
  };
}

// ============== Step Indicator Component ==============

interface StepIndicatorProps {
  currentStep: number;
  totalSteps: number;
  onStepClick?: (step: number) => void;
  /** Allow jumping to ANY step (used when editing an existing, already-valid agent). */
  allowAll?: boolean;
}

const StepIndicator: React.FC<StepIndicatorProps> = ({ currentStep, totalSteps, onStepClick, allowAll }) => {
  return (
    <ModalStepIndicator
      currentStep={currentStep}
      onStepClick={onStepClick}
      // Creating an agent builds forward, so only the steps already reached are
      // reachable; EDITING one has all three filled in already, and `allowAll`
      // is what lets the fleet canvas deep-link straight into Configuration or
      // Integration.
      isStepEnabled={(step) => allowAll || step <= currentStep}
      steps={[
        { number: 1, icon: User, label: 'Basic Info' },
        { number: 2, icon: Settings, label: 'Configuration' },
        { number: 3, icon: Puzzle, label: 'Integration' },
      ].slice(0, totalSteps)}
    />
  );
};

// ============== Widget Preview Component ==============

interface WidgetPreviewProps {
  config: WidgetConfig;
  agentName: string;
  avatarUrl: string;
}

const WidgetPreview: React.FC<WidgetPreviewProps> = ({ config, agentName, avatarUrl }) => {
  const [isOpen, setIsOpen] = useState(true);

  // Resolve effective theme: 'auto' follows the app's dark mode
  const isDark = config.theme === 'dark' || (config.theme === 'auto' && (typeof document !== 'undefined' && document.documentElement.classList.contains('dark')));

  const positionClasses = {
    'bottom-right': 'bottom-6 right-6',
    'bottom-left': 'bottom-6 left-6',
    'top-right': 'top-6 right-6',
    'top-left': 'top-6 left-6',
  };

  return (
    <div className="relative w-full h-80 bg-gradient-to-br from-gray-100 to-gray-200 dark:from-gray-800 dark:to-gray-900 rounded-xl overflow-hidden border border-theme">
      {/* Simulated website content */}
      <div className="absolute inset-4 bg-white dark:bg-gray-950 rounded-lg shadow-sm p-4 opacity-50">
        <div className="h-3 w-1/3 bg-gray-300 dark:bg-gray-700 rounded mb-3" />
        <div className="h-2 w-full bg-gray-200 dark:bg-gray-800 rounded mb-2" />
        <div className="h-2 w-4/5 bg-gray-200 dark:bg-gray-800 rounded mb-2" />
        <div className="h-2 w-2/3 bg-gray-200 dark:bg-gray-800 rounded" />
      </div>

      {/* Widget preview */}
      <div className={`absolute ${positionClasses[config.position]}`}>
        {isOpen ? (
          <div
            className="w-72 rounded-2xl shadow-2xl overflow-hidden"
            style={{
              backgroundColor: isDark ? 'var(--bg-primary)' : '#ffffff',
            }}
          >
            {/* Header */}
            <div
              className="px-4 py-3 flex items-center gap-3"
              style={{ backgroundColor: config.primaryColor }}
            >
              {config.showAvatar && (
                <AvatarDisplay avatarUrl={avatarUrl} size="sm" />
              )}
              <div className="flex-1 min-w-0">
                <div className="text-sm font-medium text-white truncate">
                  {agentName || 'Agent'}
                </div>
                <div className="text-xs text-white/70">Online</div>
              </div>
              <button
                onClick={() => setIsOpen(false)}
                className="text-white/70 hover:text-white"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* Chat area */}
            <div className="p-4 h-32 flex flex-col justify-end">
              {config.welcomeMessage && (
                <div
                  className={`text-sm p-2 rounded-lg max-w-[80%] ${
                    isDark
                      ? 'bg-[var(--bg-hover)] text-[var(--text-primary)]'
                      : 'bg-gray-100 text-gray-800'
                  }`}
                >
                  {config.welcomeMessage}
                </div>
              )}
            </div>

            {/* Input */}
            <div className="px-4 pb-4">
              <div
                className={`flex items-center gap-2 px-3 py-2 rounded-md border ${
                  isDark
                    ? 'bg-[var(--bg-secondary)] border-[var(--border-color)]'
                    : 'bg-gray-50 border-gray-200'
                }`}
              >
                <span className={`text-sm flex-1 ${
                  isDark ? 'text-[var(--text-secondary)]' : 'text-gray-500'
                }`}>
                  Type a message...
                </span>
              </div>
            </div>
          </div>
        ) : (
          <button
            onClick={() => setIsOpen(true)}
            // Exempt from the app's square radius ladder ON PURPOSE: this is a
            // PREVIEW of the launcher drawn by the external widget.js on the
            // customer's own site, so its shape has to match that script, not
            // this app's chrome. Squaring the preview alone would show users a
            // widget they will not get.
            className="w-14 h-14 rounded-full shadow-lg flex items-center justify-center transition-transform hover:scale-105"
            style={{ backgroundColor: config.primaryColor }}
          >
            <MessageCircle className="h-6 w-6 text-white" />
          </button>
        )}
      </div>

      {/* Preview label */}
      <div className="absolute top-2 left-2 px-2 py-1 bg-black/50 rounded text-xs text-white">
        Preview
      </div>
    </div>
  );
};

// ============== Main Component ==============

export const CreateAgentModal: React.FC<CreateAgentModalProps> = ({
  onClose,
  onAgentCreated,
  agent,
  initialStep,
  initialSchedule,
}) => {
  const isEditMode = !!agent?.id;
  const t = useTranslations('modals.createAgent');
  // Reuse the chat-config strings for the fields shared with the composer Options
  // panel (image generation + the 3 advanced turn-limit overrides) so the labels
  // stay in one place and at i18n parity.
  const tc = useTranslations('chatConfig');
  const { toasts, addToast, removeToast } = useToast();
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');
  // Audit 2026-07-02 - VIEWER role in an org workspace is read-only: creating or
  // updating an agent must not be submittable (the Save button is disabled and
  // handleSave guards defensively; the modal stays browsable).
  const canMutate = useCanMutateInCurrentOrg();
  const queryClient = useQueryClient();

  // Step management - open on `initialStep` (clamped) when provided (e.g. fleet model
  // edit → Configuration, integration edit → Integration), else Basic Info.
  const [currentStep, setCurrentStep] = useState(() => {
    const s = initialStep ?? 1;
    return s >= 1 && s <= TOTAL_STEPS ? s : 1;
  });

  // Step 1: Basic Info
  const defaultAvatar = agent?.avatarUrl || 'preset:purple';
  const [name, setName] = useState(agent?.name || (isEditMode ? '' : getPresetDefaultName(defaultAvatar) || ''));
  const [description, setDescription] = useState(agent?.description || '');
  const [avatarUrl, setAvatarUrl] = useState(defaultAvatar);

  // When avatar changes in create mode, auto-fill name if empty or still a preset default
  const handleAvatarChange = useCallback((newAvatarUrl: string) => {
    setAvatarUrl(newAvatarUrl);
    if (!isEditMode) {
      setName(prev => {
        if (!prev || isPresetDefaultName(prev)) {
          return getPresetDefaultName(newAvatarUrl) || prev;
        }
        return prev;
      });
    }
  }, [isEditMode]);

  // Step 2: Configuration
  const [systemPrompt, setSystemPrompt] = useState(agent?.systemPrompt || '');
  const [modelProvider, setModelProvider] = useState(agent?.modelProvider || '');
  const [modelName, setModelName] = useState(agent?.modelName || '');
  const [temperature, setTemperature] = useState(agent?.temperature ?? 0.7);
  const [maxTokens, setMaxTokens] = useState(agent?.maxTokens ?? 16000);
  const [maxIterations, setMaxIterations] = useState(agent?.maxIterations ?? 100);
  const [executionTimeout, setExecutionTimeout] = useState(agent?.executionTimeout ?? 3600);
  // Per-agent inactivity watchdog window (seconds). 0 = disabled; 10-7200 = custom; default 300 (5 min).
  const [inactivityTimeout, setInactivityTimeout] = useState(agent?.inactivityTimeout ?? 300);
  // Per-agent reasoning effort (bridge/CLI models). "" = inherit (model default / CLI default).
  const [reasoningEffort, setReasoningEffort] = useState<string>(agent?.reasoningEffort ?? '');
  const [creditBudget, setCreditBudget] = useState<number | null>(agent?.creditBudget ?? null);
  const [budgetResetMode, setBudgetResetMode] = useState<'cumulative' | 'monthly' | 'weekly'>(agent?.budgetResetMode ?? 'cumulative');
  const [isActive, setIsActive] = useState(agent?.isActive ?? true);
  // V340 - opt-in shared-backlog participation. Default false: a new/edited agent
  // is NOT pulled onto unassigned backlog work unless explicitly enabled here.
  const [backlogEnabled, setBacklogEnabled] = useState(agent?.backlogEnabled ?? false);
  // Sensitive actions: ask the person first, or run them (the default, as agents always have).
  const [requireToolAuthorization, setRequireToolAuthorization] = useState(agent?.requireToolAuthorization ?? false);
  // Where the agent's requests and questions reach the person: null = the workspace default.
  const [chatChannelLinkId, setChatChannelLinkId] = useState<string | null>(agent?.chatChannelLinkId ?? null);
  // The channel card's switch. Stored true by default (every existing agent reaches the workspace
  // default), but it can only be on where a destination actually works.
  const [chatChannelEnabled, setChatChannelEnabled] = useState(agent?.chatChannelEnabled ?? true);
  const { destinations: chatDestinations, isLoading: channelsLoading, isError: channelsError } = useChatDestinations();
  // Loading or unreadable is not "no channel": the card waits, rather than telling a workspace
  // that has channels to go and connect one.
  const channelsKnown = !channelsLoading && !channelsError;
  const channelAvailable = channelsKnown && chatDestinations.some(isWorking);
  const channelOn = channelAvailable && chatChannelEnabled;
  const [toolsMode, setToolsMode] = useState<ToolsMode>('all');
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set());
  const [expandedCategories, setExpandedCategories] = useState<Set<string>>(new Set());
  const [toolsPopoverOpen, setToolsPopoverOpen] = useState(false);
  const [toolsCache, setToolsCache] = useState<Map<string, ApiTool[]>>(new Map());
  const [loadingApis, setLoadingApis] = useState<Set<string>>(new Set());
  const [toolsSearchQuery, setToolsSearchQuery] = useState('');
  const [debouncedSearchQuery, setDebouncedSearchQuery] = useState('');
  const [selectedWorkflows, setSelectedWorkflows] = useState<Set<string>>(new Set());
  const [workflowsInitialized, setWorkflowsInitialized] = useState(false);
  const [selectedApplications, setSelectedApplications] = useState<Set<string>>(new Set());
  const [applicationsInitialized, setApplicationsInitialized] = useState(false);
  const [selectedTables, setSelectedTables] = useState<Set<string>>(new Set());
  const [tablesInitialized, setTablesInitialized] = useState(false);
  const [selectedInterfaces, setSelectedInterfaces] = useState<Set<string>>(new Set());
  const [interfacesInitialized, setInterfacesInitialized] = useState(false);
  const [selectedSubAgents, setSelectedSubAgents] = useState<Set<string>>(new Set());
  const [subAgentsInitialized, setSubAgentsInitialized] = useState(false);
  // Files are opt-in: an empty selection = full org file access; a non-empty
  // selection scopes the agent's `files` tool to exactly those file ids.
  const [selectedFiles, setSelectedFiles] = useState<Set<string>>(new Set());
  const [filesInitialized, setFilesInitialized] = useState(false);
  const [webSearchEnabled, setWebSearchEnabled] = useState(true);
  // Image generation - opt-in (default off; cost per image varies). Hydrated from
  // Unified generation grant (image, video, audio, voice, music) - opt-in, and its OWN
  // toggle: an image grant must never widen into a per-second video model, which spends
  // an order of magnitude more per call. Hydrated from toolsConfig.generation on edit.
  const [generationEnabled, setGenerationEnabled] = useState(() => isGenerationEnabled(agent?.toolsConfig));
  // Mailbox - opt-in (default off): it reads the account's mail and can send from its
  // address. mailboxAccessMode is the separate read/write axis, defaulting to full access
  // like every other <x>AccessMode, so turning the tool on does not silently restrict it.
  const [mailboxEnabled, setMailboxEnabled] = useState<boolean>(() => isMailboxEnabled(agent?.toolsConfig));
  const [mailboxAccessMode, setMailboxAccessMode] = useState<'read' | 'write'>(
    () => getMailboxAccessMode(agent?.toolsConfig),
  );
  // Per-family access GRANT (axis 1): "none" | "all" | "custom". Hydrated from the
  // agent's toolsConfig.<family>Grant on edit (absent ⇒ 'none' - deny by default,
  // matching the backend). 'custom' scopes to the family's selected id list below.
  const [workflowsGrant, setWorkflowsGrant] = useState<ResourceGrant>(() => getGrant(agent?.toolsConfig, 'workflows'));
  const [tablesGrant, setTablesGrant] = useState<ResourceGrant>(() => getGrant(agent?.toolsConfig, 'tables'));
  const [interfacesGrant, setInterfacesGrant] = useState<ResourceGrant>(() => getGrant(agent?.toolsConfig, 'interfaces'));
  const [agentsGrant, setAgentsGrant] = useState<ResourceGrant>(() => getGrant(agent?.toolsConfig, 'agents'));
  const [applicationsGrant, setApplicationsGrant] = useState<ResourceGrant>(() => getGrant(agent?.toolsConfig, 'applications'));
  // Per-resource access modes (axis 2): "read" or "write" (default)
  const [tableAccessMode, setTableAccessMode] = useState<'read' | 'write'>('write');
  const [workflowAccessMode, setWorkflowAccessMode] = useState<'read' | 'write'>('write');
  const [interfaceAccessMode, setInterfaceAccessMode] = useState<'read' | 'write'>('write');
  const [agentAccessMode, setAgentAccessMode] = useState<'read' | 'write'>('write');
  const [applicationAccessMode, setApplicationAccessMode] = useState<'read' | 'write'>('write');
  const [skillAccessMode, setSkillAccessMode] = useState<'read' | 'write'>('write');
  // Files read/write (axis 2). No grant axis - applies to the agent's file access
  // whether scoped or full. 'read' blocks create_folder/move_to_folder. Default 'write'.
  const [fileAccessMode, setFileAccessMode] = useState<'read' | 'write'>('write');
  const [memoryAccessMode, setMemoryAccessMode] = useState<'read' | 'write'>('write');
  const [resourceAccessPopoverOpen, setResourceAccessPopoverOpen] = useState(false);
  const [resourceSearchQuery, setResourceSearchQuery] = useState('');
  // Which resource families are expanded. CREATE (or no toolsConfig): expand all - the user
  // is choosing from scratch. EDIT: expand only families that ALREADY have access (grant !=
  // 'none', or files with a scoped list) and collapse the 'none' ones, so on update the user
  // immediately sees what's granted and the denied families stay out of the way.
  const [expandedResourceCategories, setExpandedResourceCategories] = useState<Set<string>>(() => {
    const ALL_CATEGORIES = ['workflows', 'applications', 'tables', 'interfaces', 'agents', 'files'];
    if (!isEditMode || !agent?.toolsConfig) {
      return new Set(ALL_CATEGORIES);
    }
    const expanded = new Set<string>();
    GRANT_FAMILIES.forEach(family => {
      if (getGrant(agent.toolsConfig, family) !== 'none') expanded.add(family);
    });
    // 'files' has no grant axis - expand it when the agent actually scopes files.
    if (getAllowedIds(agent.toolsConfig, 'files').length > 0) expanded.add('files');
    return expanded;
  });

  // Step 2: Skills - no pre-selection, user manages all skills
  const [selectedSkillsMap, setSelectedSkillsMap] = useState<Map<string, Record<string, any>>>(() => {
    return new Map<string, Record<string, any>>();
  });
  const [skillsPopoverOpen, setSkillsPopoverOpen] = useState(false);
  const MAX_SKILLS = 10;

  // Step 3: Integration - Webhook (created on save via agent webhook token)
  const [webhookEnabled, setWebhookEnabled] = useState(false);
  const [webhookData, setWebhookData] = useState<AgentWebhook | null>(null);
  const [webhookMemory, setWebhookMemory] = useState(false);
  const webhookRestoredRef = React.useRef(false);

  // Step 3: Integration - Schedule.
  //
  // `initialSchedule` is honoured on CREATE only. On an edit the restore effect below
  // reads the agent's own schedule row and overwrites all four of these, so a seed there
  // would be a value flashed on screen and then replaced - and if the fetch failed, a
  // schedule the agent does not have, left switched on, and created by the next save.
  const seededSchedule = isEditMode ? undefined : initialSchedule;
  const [scheduleEnabled, setScheduleEnabled] = useState(Boolean(seededSchedule));
  const [scheduleData, setScheduleData] = useState<AgentSchedule | null>(null);
  const [scheduleCron, setScheduleCron] = useState(seededSchedule?.cron || '0 9 * * *');
  const [scheduleTimezone, setScheduleTimezone] = useState(
    seededSchedule?.timezone
      || (typeof Intl !== 'undefined' ? Intl.DateTimeFormat().resolvedOptions().timeZone : 'UTC')
  );
  const [scheduleMaxExecutions, setScheduleMaxExecutions] = useState<number | null>(null);
  const [schedulePrompt, setSchedulePrompt] = useState('');
  const [scheduleWithMemory, setScheduleWithMemory] = useState(false);
  const [schedulePreset, setSchedulePreset] = useState(
    seededSchedule ? presetForCron(seededSchedule.cron) : 'every_day_9am',
  );
  const [scheduleLimitReached, setScheduleLimitReached] = useState(false);
  const [scheduleAdvancedOpen, setScheduleAdvancedOpen] = useState(false);
  const [advancedModeOpen, setAdvancedModeOpen] = useState(false);
  // Advanced turn-limit overrides (V100 columns). Available at BOTH create and edit -
  // POST/PUT /agents both accept maxPerResourcePerTurn / loopIdenticalStop /
  // loopConsecutiveStop. Defaults mirror the backend (5 / 15 / 40). Captured initials
  // let the payload send a field only when the user actually changed it, so an
  // untouched agent keeps its NULL columns (→ backend YAML defaults).
  // `agent` is stable for the modal's lifetime, so these initial values are stable too;
  // comparing current state against them (buildChangedTurnLimits) lets the payload send a
  // turn-limit only when the user actually changed it (untouched → NULL columns → defaults).
  const turnLimitInitials = initialTurnLimits(agent);
  const [maxPerResourcePerTurn, setMaxPerResourcePerTurn] = useState<number>(turnLimitInitials.maxPerResourcePerTurn);
  const [loopIdenticalStop, setLoopIdenticalStop] = useState<number>(turnLimitInitials.loopIdenticalStop);
  const [loopConsecutiveStop, setLoopConsecutiveStop] = useState<number>(turnLimitInitials.loopConsecutiveStop);
  // V350 - per-agent compaction enable + cadence (create + edit). Sent in the payload
  // only when changed from the captured initial, so an untouched agent keeps its NULL
  // columns (→ inherit the conversation override, then the platform default).
  const compactionInitials = initialCompaction(agent);
  const [compactionEnabled, setCompactionEnabled] = useState<boolean>(compactionInitials.compactionEnabled);
  const [compactionAfterTurns, setCompactionAfterTurns] = useState<number>(compactionInitials.compactionAfterTurns);
  // Summariser-model override ('' pair = inherit). Both-or-neither: the ModelPicker
  // writes both halves together, and the OFF toggle blanks both (explicit clear).
  const [compactionModelProvider, setCompactionModelProvider] = useState<string>(compactionInitials.compactionModelProvider);
  const [compactionModelName, setCompactionModelName] = useState<string>(compactionInitials.compactionModelName);
  const [compactionModelOpen, setCompactionModelOpen] = useState<boolean>(
    compactionInitials.compactionModelProvider !== '' && compactionInitials.compactionModelName !== '',
  );
  const scheduleRestoredRef = React.useRef(false);

  // Step 3: Integration - Widget
  const [widgetConfig, setWidgetConfig] = useState<WidgetConfig>({
    enabled: false,
    position: 'bottom-right',
    theme: 'auto',
    primaryColor: '#000000',
    welcomeMessage: 'Hello! How can I help you today?',
    bubbleText: 'Chat with us',
    showAvatar: true,
    autoOpenDelay: 0,
  });

  // UI state
  const [isCreating, setIsCreating] = useState(false);
  const [mounted, setMounted] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  // Fetch providers and models
  const { models, providers, defaultModel, defaultProvider, isLoading: modelsLoading } = useVisibleModels();
  // Which models this agent's turns can be paid on. A Free account's monthly
  // credits fund chat and agent turns only on the models a cloud admin opened
  // to the free tier.
  const { prefersFreeTierModels, verdictReady } = useMonthlyCreditsCannotPay();

  // Fetch MCP APIs
  const {
    data: mcpApisData,
    isLoading: apisLoading,
    isFetching: isFetchingApis,
    fetchNextPage,
    hasNextPage,
  } = useMcpApis(true, debouncedSearchQuery);

  // Fetch workflows - paginated (infinite scroll). A tenant with >100 workflows must
  // be able to scroll the picker to reach all of them, not just the first capped page.
  const {
    data: workflowsData,
    isLoading: workflowsLoading,
    isFetching: isFetchingWorkflows,
    fetchNextPage: fetchNextWorkflowsPage,
    hasNextPage: hasNextWorkflowsPage,
  } = useInfiniteQuery({
    queryKey: ['workflows', 'infinite'],
    queryFn: ({ pageParam }) => orchestratorApi.getWorkflowsPage({ page: pageParam, size: 100 }),
    enabled: true,
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      (lastPage.page + 1) * lastPage.size < lastPage.totalCount ? lastPage.page + 1 : undefined,
  });

  // Flatten all loaded pages into the single array shape downstream code already expects.
  const workflows = useMemo(
    () => workflowsData?.pages.flatMap((p) => p.workflows) ?? [],
    [workflowsData],
  );

  // Fetch acquired + published applications (mirrors /app/applications page logic)
  const { data: allApplicationsData, isLoading: applicationsLoading } = useQuery({
    queryKey: ['allApplications'],
    queryFn: async () => {
      const [acquiredRes, publishedRes] = await Promise.all([
        publicationService.getAcquiredApplications(),
        publicationService.getMyPublications(),
      ]);
      const items: AcquiredApplication[] = [];
      const seenIds = new Set<string>();
      // Published apps first (convert to AcquiredApplication shape)
      for (const pub of publishedRes.publications || []) {
        items.push({
          workflowId: pub.workflowId,
          sourcePublicationId: pub.id,
          name: pub.title,
          description: pub.description,
          publication: pub,
        });
        seenIds.add(pub.id);
      }
      // Then acquired (skip duplicates)
      for (const app of acquiredRes.applications || []) {
        if (!seenIds.has(app.sourcePublicationId)) {
          items.push(app);
          seenIds.add(app.sourcePublicationId);
        }
      }
      return items;
    },
    enabled: true,
  });

  const acquiredApplications: AcquiredApplication[] = allApplicationsData || [];

  // Fetch data sources (tables)
  const { data: tablesData, isLoading: tablesLoading } = useQuery({
    queryKey: ['dataSources'],
    queryFn: () => orchestratorApi.getDataSources(),
    enabled: true,
  });
  const allTables: DataSource[] = tablesData || [];

  // Fetch interfaces
  const { data: interfacesData, isLoading: interfacesLoading } = useQuery({
    queryKey: ['interfaces'],
    queryFn: () => orchestratorApi.getInterfaces(),
    enabled: true,
  });
  const allInterfaces: Interface[] = (interfacesData || []).filter((i: any) => !i.interfaceType || i.interfaceType !== 'web_search');

  // Fetch agents (for sub-agent selection)
  const { data: agentsData, isLoading: agentsLoading } = useQuery({
    queryKey: ['agents'],
    queryFn: () => orchestratorApi.getAgents(),
    enabled: true,
  });
  // Fetch runtime caller→callee edges for ancestor detection
  const { data: subAgentEdges } = useQuery({
    queryKey: ['subAgentEdges'],
    queryFn: () => agentService.getSubAgentEdges(),
    enabled: !!agent?.id,
  });

  // Show all agents except self; ancestors are shown but disabled (to avoid circular references)
  const { allAgents, ancestorIds } = useMemo(() => {
    const raw = agentsData || [];
    if (!agent?.id) return { allAgents: raw, ancestorIds: new Set<string>() };

    return {
      allAgents: raw.filter((a: Agent) => a.id !== agent.id),
      ancestorIds: computeAncestorIds(agent.id, raw, subAgentEdges || []),
    };
  }, [agentsData, subAgentEdges, agent?.id]);

  // Fetch files (org-scoped storage entries) for the file allow-list picker.
  // S3_FILES_FILTER lists only real object-storage uploads - the same set as the
  // Files page (app/file), excluding step-output JSON and DB-resident pseudo-files.
  const {
    data: filesData,
    isLoading: filesLoading,
    isFetching: isFetchingFiles,
    fetchNextPage: fetchNextFilesPage,
    hasNextPage: hasNextFilesPage,
  } = useInfiniteQuery({
    queryKey: ['agentPickerFiles', 'infinite'],
    queryFn: ({ pageParam }) => storageApi.getExplorerEntries({ page: pageParam, size: 100, ...S3_FILES_FILTER }),
    enabled: true,
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) =>
      allPages.length < lastPage.totalPages ? allPages.length : undefined,
  });
  // Flatten all loaded pages' content into the {id,name} shape the picker consumes.
  const allFiles: { id: string; name: string }[] = useMemo(
    () => (filesData?.pages.flatMap((p) => p.content) ?? [])
      .map((f) => ({ id: f.id, name: f.fileName || f.mimeType || f.id })),
    [filesData],
  );

  // No standalone webhook fetch needed - we use agent webhook tokens

  // Fetch skills & folders
  const { data: skillsData, refetch: refetchSkills } = useQuery({
    queryKey: ['skills'],
    queryFn: () => orchestratorApi.getSkills(),
    enabled: true,
  });
  const availableSkills: Skill[] = useMemo(() => {
    const dbSkills = (skillsData || []) as Skill[];
    // If DB already contains default skills (seeded with defaultKey), use them directly.
    // Otherwise, append frontend fallback so defaults are always visible.
    const hasDbDefaults = dbSkills.some((s: any) => s.defaultKey);
    if (hasDbDefaults) return dbSkills;
    return [...DEFAULT_SKILLS_FALLBACK, ...dbSkills];
  }, [skillsData]);

  const { data: skillFoldersData } = useQuery({
    queryKey: ['skillFolders'],
    queryFn: () => orchestratorApi.getAllSkillFolders(),
    enabled: true,
  });
  const skillFolders: SkillFolder[] = (skillFoldersData || []) as SkillFolder[];

  const agentScopedSkills = useMemo(
    () => availableSkills.map(skill => ({
      ...skill,
      isDefaultActive: selectedSkillsMap.has(skill.id),
    })),
    [availableSkills, selectedSkillsMap],
  );

  const agentSkillOverrides = useMemo(() => {
    const overrides: Record<string, boolean> = {};
    for (const skill of availableSkills) {
      overrides[skill.id] = selectedSkillsMap.has(skill.id);
    }
    return overrides;
  }, [availableSkills, selectedSkillsMap]);

  const updateAgentSkillSelection = useCallback((skillId: string, nextActive: boolean) => {
    setSelectedSkillsMap(prev => {
      const next = new Map(prev);
      if (nextActive) {
        if (!next.has(skillId) && next.size >= MAX_SKILLS) return prev;
        next.set(skillId, {});
      } else {
        next.delete(skillId);
      }
      return next;
    });
  }, [MAX_SKILLS]);

  // Fetch webhook and widget configuration when editing
  useEffect(() => {
    if (isEditMode && agent?.id) {
      // Restore agent webhook token (run once)
      if (!webhookRestoredRef.current) {
        webhookRestoredRef.current = true;
        agentService.getWebhook(agent.id).then((wh) => {
          if (wh) {
            setWebhookEnabled(true);
            setWebhookData(wh);
            setWebhookMemory(wh.memoryEnabled ?? false);
          }
        }).catch(() => {
          // No webhook configured
        });
      }

      // Restore agent schedule (run once)
      if (!scheduleRestoredRef.current) {
        scheduleRestoredRef.current = true;
        agentService.getSchedule(agent.id).then((sched) => {
          if (sched) {
            setScheduleEnabled(true);
            setScheduleData(sched);
            setScheduleCron(sched.cronExpression);
            setScheduleTimezone(sched.timezone);
            setScheduleMaxExecutions(sched.maxExecutions ?? null);
            setSchedulePrompt(sched.schedulePrompt || '');
            setScheduleWithMemory(sched.withMemory ?? false);
            setSchedulePreset(presetForCron(sched.cronExpression));
          }
        }).catch(() => {});

        // Check plan limits
        scheduleSettingsService.getConfig().then(cfg => {
          if (cfg && cfg.currentCount >= cfg.maxPerUser) {
            setScheduleLimitReached(true);
          }
        }).catch(() => {});
      }

      // Fetch widget config
      orchestratorApi.getWidgetConfig(agent.id).then((widget) => {
        if (widget) {
          setWidgetConfig({
            enabled: widget.isActive,
            position: widget.position as typeof WIDGET_POSITIONS[number]['value'],
            theme: widget.theme as typeof WIDGET_THEMES[number]['value'],
            primaryColor: widget.primaryColor,
            welcomeMessage: widget.welcomeMessage,
            bubbleText: widget.bubbleText,
            showAvatar: widget.showAvatar,
            autoOpenDelay: widget.autoOpenDelay,
            widgetToken: widget.widgetToken,
            allowedOrigins: widget.allowedOrigins || '',
            widgetScriptUrl: widget.widgetScriptUrl,
          });
        }
      }).catch(() => {
        // No widget configured
      });

      // Fetch agent skills - load actual assignments only
      orchestratorApi.getAgentSkills(agent.id).then((agentSkills) => {
        const map = new Map<string, Record<string, any>>();
        if (agentSkills && agentSkills.length > 0) {
          agentSkills.forEach((as: any) => map.set(as.skillId, {}));
        }
        setSelectedSkillsMap(map);
      }).catch(() => {
        setSelectedSkillsMap(new Map());
      });
    }
  }, [isEditMode, agent?.id]);

  // Mount effect
  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Fetch schedule plan limits for new agent creation
  useEffect(() => {
    if (!isEditMode) {
      scheduleSettingsService.getConfig().then(cfg => {
        if (cfg && cfg.currentCount >= cfg.maxPerUser) {
          setScheduleLimitReached(true);
        }
      }).catch(() => {});
    }
  }, [isEditMode]);

  // Debounced search for APIs
  useEffect(() => {
    const trimmedQuery = toolsSearchQuery.trim();
    if (trimmedQuery.length === 0) {
      setDebouncedSearchQuery('');
      return;
    }
    if (trimmedQuery.length === 1) return;
    const timer = setTimeout(() => setDebouncedSearchQuery(trimmedQuery), 500);
    return () => clearTimeout(timer);
  }, [toolsSearchQuery]);

  // A Free account creates its agent on the free tier's best-ranked model,
  // when one exists. The catalogue default is the admin's global #1, which is the
  // right answer for an account with a wallet and can be a model the Free plan's
  // monthly credits do not pay for - so a brand-new account would build an agent
  // that is refused on its first run.
  // Same resolver as the chat composer and the two side panels, so the four
  // surfaces cannot drift into four answers.
  //
  // Undefined unless the preference actually CHANGES the answer, and that is
  // deliberate: `defaultProvider` is the catalogue's own pairing, while looking
  // the default id up in the flattened list can land on another provider serving
  // the same model (a CLI bridge outranking its cloud twin). So a paid account,
  // a self-hosted install and a catalogue with nothing opened to the free tier
  // all keep the exact pair they got before.
  const freeTierDefault = useMemo(() => {
    if (!prefersFreeTierModels) return undefined;
    const catalogueDefault = defaultModel ? models.find(m => m.id === defaultModel) : undefined;
    const preferred = resolveFreeTierPreferredModel(models, catalogueDefault, prefersFreeTierModels);
    return preferred && preferred !== catalogueDefault ? preferred : undefined;
  }, [models, defaultModel, prefersFreeTierModels]);

  // The plan verdict is not guaranteed to arrive: a balance request that exhausts
  // its retries settles into "not loading, no data" and stays there. Waiting on it
  // forever would leave both fields blank and save an agent with NO model, while
  // the picker still displays a fallback - so the reader would be shown a model
  // that is not the one being stored. Giving up means doing exactly what this
  // effect did before the verdict existed, which is never worse than before.
  const [verdictWaitElapsed, setVerdictWaitElapsed] = useState(false);
  useEffect(() => {
    if (verdictReady || verdictWaitElapsed) return;
    const timer = window.setTimeout(() => setVerdictWaitElapsed(true), PLAN_VERDICT_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [verdictReady, verdictWaitElapsed]);

  // Set default provider and model
  useEffect(() => {
    if (modelsLoading || providers.length === 0) return;
    // Wait for the plan verdict before writing. `prefersFreeTierModels` is false
    // while the balance request is in flight, which is indistinguishable from a
    // paid account: if the models land first this pins the catalogue default,
    // and the free-tier answer arriving a tick later finds both fields already
    // set and never applies. A saved agent keeps that model for good.
    if (!verdictReady && !verdictWaitElapsed) return;

    // Nothing chosen yet: the create case, and the only one the free-tier
    // preference speaks to. Provider and id are written TOGETHER, from one
    // model, so the pair is always coherent.
    if (!modelProvider && !modelName && freeTierDefault) {
      setModelProvider(freeTierDefault.provider);
      setModelName(freeTierDefault.id);
      return;
    }

    // Everything else, unchanged: the catalogue's own default pair, each half
    // filled independently. A legacy agent can carry one field and not the
    // other (both are optional), and completing it from a model of another
    // provider would make the pair incoherent.
    if (!modelProvider && defaultProvider) setModelProvider(defaultProvider);
    if (!modelName && defaultModel) setModelName(defaultModel);
  }, [modelsLoading, providers, defaultProvider, defaultModel, freeTierDefault, verdictReady, verdictWaitElapsed, modelProvider, modelName]);

  // Note: the previous `isUnrestrictedEdit` shortcut (which pre-selected EVERY
  // resource when the agent had absent keys or mode='all') has been removed.
  // It encoded the inverted security rule "absent === all" and re-created the
  // leak inside the modal - opening a legacy agent then clicking Save would
  // grant it access to every resource in the tenant.
  // Post-fix: selections come strictly from explicit `tc.<key>` arrays; absent
  // === [] (no pre-selection). The V163 backfill ensures every persisted agent
  // has explicit empty lists, so no agent is left in an ambiguous state.
  // See lib/agents/toolsConfigAccess.ts for the canonical reader.

  // Initialize workflows and restore toolsConfig on edit
  useEffect(() => {
    if (!workflowsInitialized && workflows.length > 0) {
      const tc = agent?.toolsConfig as any;
      if (isEditMode && tc && typeof tc === 'object') {
        // Restore tools mode
        const mode = tc.mode;
        if (mode === 'none' || mode === 'custom' || mode === 'off') {
          setToolsMode(mode);
        }
        // Restore selected tools - checkbox state keys by `${apiSlug}:${tool.name}`,
        // but legacy agents (MCP-builder created) store api_tools.id UUIDs. Set the raw
        // entries first so the UI has a starting state, then kick an async resolver
        // that rewrites UUID entries to the apiSlug:name keys the checkbox expects.
        const tools = tc.tools;
        if (Array.isArray(tools) && tools.length > 0) {
          setSelectedTools(new Set(tools));
          const legacyUuids = tools.filter((t: any) => typeof t === 'string' && UUID_REGEX.test(t));
          if (legacyUuids.length > 0) {
            apiClient.get<any[]>('/workflow-inspector/tools/by-ids', {
              params: { ids: legacyUuids.join(',') }
            }).then(resolved => {
              const byUuid = new Map<string, string>();
              (resolved || []).forEach((row: any) => {
                if (row?.toolId && row?.apiSlug && row?.name) {
                  byUuid.set(row.toolId, `${row.apiSlug}:${row.name}`);
                }
              });
              setSelectedTools(prev => {
                const next = new Set<string>();
                prev.forEach(entry => {
                  next.add(byUuid.get(entry) || entry);
                });
                return next;
              });
            }).catch(() => { /* fail-open - UUIDs remain but at least UI renders */ });
          }
        }
      }
      // Selections restored strictly from explicit lists. Absent → empty
      // (security rule: absent === [] - see lib/agents/toolsConfigAccess.ts).
      if (isEditMode) {
        setSelectedWorkflows(new Set(getAllowedIds(tc, 'workflows')));
      } else {
        setSelectedWorkflows(new Set());
      }
      setWorkflowsInitialized(true);
    }
  }, [workflows, workflowsInitialized, isEditMode, agent]);

  // Initialize applications and restore from toolsConfig on edit
  useEffect(() => {
    if (!applicationsInitialized && acquiredApplications.length > 0) {
      if (isEditMode) {
        setSelectedApplications(new Set(getAllowedIds(agent?.toolsConfig, 'applications')));
      } else {
        setSelectedApplications(new Set());
      }
      setApplicationsInitialized(true);
    }
  }, [acquiredApplications, applicationsInitialized, isEditMode, agent]);

  // Initialize tables and restore from toolsConfig on edit
  useEffect(() => {
    if (!tablesInitialized && allTables.length > 0) {
      if (isEditMode) {
        // Tables stored as bigint in JSON; coerce to string for the Set.
        setSelectedTables(new Set(getAllowedIds(agent?.toolsConfig, 'tables')));
      } else {
        setSelectedTables(new Set());
      }
      setTablesInitialized(true);
    }
  }, [allTables, tablesInitialized, isEditMode, agent]);

  // Initialize interfaces and restore from toolsConfig on edit
  useEffect(() => {
    if (!interfacesInitialized && allInterfaces.length > 0) {
      if (isEditMode) {
        setSelectedInterfaces(new Set(getAllowedIds(agent?.toolsConfig, 'interfaces')));
      } else {
        setSelectedInterfaces(new Set());
      }
      setInterfacesInitialized(true);
    }
  }, [allInterfaces, interfacesInitialized, isEditMode, agent]);

  // Initialize sub-agents and restore from toolsConfig on edit
  useEffect(() => {
    if (!subAgentsInitialized && allAgents.length > 0) {
      if (isEditMode) {
        // Filter out ancestors saved before the circular-ref guard.
        setSelectedSubAgents(new Set(
          getAllowedIds(agent?.toolsConfig, 'agents').filter(id => !ancestorIds.has(id))
        ));
      } else {
        setSelectedSubAgents(new Set());
      }
      setSubAgentsInitialized(true);
    }
  }, [allAgents, ancestorIds, subAgentsInitialized, isEditMode, agent]);

  // Initialize files and restore the file allow-list from toolsConfig on edit.
  // Files are opt-in: a stored non-empty list scopes the agent; absent/empty = full access.
  useEffect(() => {
    if (!filesInitialized && allFiles.length > 0) {
      if (isEditMode) {
        setSelectedFiles(new Set(getAllowedIds(agent?.toolsConfig, 'files')));
      } else {
        setSelectedFiles(new Set());
      }
      setFilesInitialized(true);
    }
  }, [allFiles, filesInitialized, isEditMode, agent]);

  // Restore webSearch toggle on edit
  useEffect(() => {
    if (isEditMode && agent?.toolsConfig && typeof agent.toolsConfig === 'object') {
      const tc = agent.toolsConfig as any;
      if (tc.webSearch === false) {
        setWebSearchEnabled(false);
      }
    }
  }, [isEditMode, agent?.toolsConfig]);

  // Apply a TEMPLATE prefill (an `agent` prop with a toolsConfig but no id).
  //
  // The two restore effects around this one are gated on `isEditMode`, so a
  // prefill's `mode` and `*AccessMode` were silently dropped and fell back to
  // the create defaults ('all' tools, 'write' access) - the exact opposite of
  // what a locked-down template declares. Per-family GRANTS were never affected
  // (their useState initializers call getGrant unconditionally), so only these
  // two axes need handling here.
  //
  // Kept as its own effect rather than widening the gates: every existing edit
  // path stays byte-identical, and this one cannot fire for the other callers
  // (they pass either a real agent, which has an id, or no agent at all).
  useEffect(() => {
    // No cast: AgentData now declares the grant / access-mode keys this reads.
    const tc = agent?.toolsConfig;
    if (isEditMode || !tc || typeof tc !== 'object') return;

    if (tc.mode === 'none' || tc.mode === 'custom' || tc.mode === 'off' || tc.mode === 'all') {
      setToolsMode(tc.mode);
    }
    // webSearch defaults to TRUE in create mode, so a template that says "no
    // tools" ships an agent that can still search the web unless this is read.
    if (tc.webSearch === false) setWebSearchEnabled(false);
    if (tc.tableAccessMode) setTableAccessMode(tc.tableAccessMode);
    if (tc.workflowAccessMode) setWorkflowAccessMode(tc.workflowAccessMode);
    if (tc.interfaceAccessMode) setInterfaceAccessMode(tc.interfaceAccessMode);
    if (tc.agentAccessMode) setAgentAccessMode(tc.agentAccessMode);
    if (tc.applicationAccessMode) setApplicationAccessMode(tc.applicationAccessMode);
    // Kept at parity with the edit effect below: omitting one is how a declared
    // restriction gets silently dropped, which is the bug this effect fixes.
    if (tc.skillAccessMode) setSkillAccessMode(tc.skillAccessMode);
  }, [isEditMode, agent?.toolsConfig]);

  // Restore access modes on edit
  useEffect(() => {
    if (isEditMode && agent?.toolsConfig && typeof agent.toolsConfig === 'object') {
      const tc = agent.toolsConfig as any;
      if (tc.tableAccessMode) setTableAccessMode(tc.tableAccessMode);
      if (tc.workflowAccessMode) setWorkflowAccessMode(tc.workflowAccessMode);
      if (tc.interfaceAccessMode) setInterfaceAccessMode(tc.interfaceAccessMode);
      if (tc.agentAccessMode) setAgentAccessMode(tc.agentAccessMode);
      if (tc.applicationAccessMode) setApplicationAccessMode(tc.applicationAccessMode);
      if (tc.skillAccessMode) setSkillAccessMode(tc.skillAccessMode);
      // Files: default 'write' when absent (getFileAccessMode), so an edit of a
      // pre-fileAccessMode agent shows R/W (its current backend behavior).
      setFileAccessMode(getFileAccessMode(tc));
      // Memory: same rule. Reading it back is what stops an edit from resetting a
      // recall-only agent to full write access, since the payload builder rebuilds
      // the whole tools_config from this state.
      setMemoryAccessMode(getMemoryAccessMode(tc));
      // Mailbox: both halves, for the same reason and with more at stake. The lazy
      // useState initializers only run on mount, and this effect depends on
      // agent?.toolsConfig, so the prop is expected to change afterwards. Left out, a
      // later save would emit mailbox:false (revoking a granted mailbox) and
      // mailboxAccessMode:'write' (turning a read-only mail agent into one that can
      // send from the account's address), with nobody having touched either switch.
      setMailboxEnabled(isMailboxEnabled(tc));
      setMailboxAccessMode(getMailboxAccessMode(tc));
    }
  }, [isEditMode, agent?.toolsConfig]);

  // Get current provider data
  const currentProviderData = useMemo(() => {
    return providers.find(p => p.name === modelProvider);
  }, [providers, modelProvider]);

  const availableModels = useMemo(() => {
    return currentProviderData?.models || [];
  }, [currentProviderData]);

  // Get MCP APIs list
  const mcpApis = useMemo(() => {
    const allApis = mcpApisData?.pages.flatMap(page => page.content ?? []) || [];
    const uniqueApis = new Map<string, typeof allApis[0]>();
    allApis.forEach(api => {
      if (api?.slug && !uniqueApis.has(api.slug)) {
        uniqueApis.set(api.slug, api);
      }
    });
    return Array.from(uniqueApis.values());
  }, [mcpApisData]);

  // Filter all resource categories with unified search
  const filteredResourceCategories = useMemo(() => {
    const query = resourceSearchQuery.toLowerCase().trim();
    const filter = <T extends { name?: string; description?: string }>(items: T[]): T[] => {
      if (!query) return items;
      return items.filter(item =>
        item.name?.toLowerCase().includes(query) ||
        item.description?.toLowerCase().includes(query)
      );
    };
    return {
      workflows: filter(workflows),
      applications: filter(acquiredApplications),
      tables: filter(allTables),
      interfaces: filter(allInterfaces),
      agents: filter(allAgents),
      files: filter(allFiles),
    };
  }, [resourceSearchQuery, workflows, acquiredApplications, allTables, allInterfaces, allAgents, allFiles]);

  const resourceAccessSummary = useMemo(() => {
    const totalSelected = selectedWorkflows.size + selectedApplications.size +
      selectedTables.size + selectedInterfaces.size + selectedSubAgents.size + selectedFiles.size;
    const totalAvailable = workflows.length + acquiredApplications.length +
      allTables.length + allInterfaces.length + allAgents.length + allFiles.length;
    return { totalSelected, totalAvailable };
  }, [selectedWorkflows.size, selectedApplications.size, selectedTables.size,
      selectedInterfaces.size, selectedSubAgents.size, selectedFiles.size, workflows.length,
      acquiredApplications.length, allTables.length, allInterfaces.length, allAgents.length, allFiles.length]);

  // Simple toggle - webhook is created/deleted only on save
  // Keep webhookData intact so the save handler can detect a delete is needed
  const handleWebhookToggle = useCallback(() => {
    setWebhookEnabled(prev => !prev);
  }, []);

  // Lazy load observer
  const apiLoadMoreRef = useLazyLoadObserver({
    enabled: toolsPopoverOpen,
    hasMore: !!hasNextPage,
    isLoading: isFetchingApis,
    isInitialLoading: apisLoading,
    dataLength: mcpApis.length,
    onLoadMore: () => fetchNextPage(),
  });

  // Infinite-scroll sentinel for the WORKFLOWS resource picker - fires the next page
  // when the bottom sentinel scrolls into view inside the resource-access popover.
  const workflowsLoadMoreRef = useLazyLoadObserver({
    enabled: resourceAccessPopoverOpen,
    hasMore: !!hasNextWorkflowsPage,
    isLoading: isFetchingWorkflows,
    isInitialLoading: workflowsLoading,
    dataLength: workflows.length,
    onLoadMore: () => fetchNextWorkflowsPage(),
  });

  // Infinite-scroll sentinel for the FILES resource picker.
  const filesLoadMoreRef = useLazyLoadObserver({
    enabled: resourceAccessPopoverOpen,
    hasMore: !!hasNextFilesPage,
    isLoading: isFetchingFiles,
    isInitialLoading: filesLoading,
    dataLength: allFiles.length,
    onLoadMore: () => fetchNextFilesPage(),
  });

  // Handler functions
  const handleProviderChange = (providerName: string) => {
    const provider = providers.find(p => p.name === providerName);
    setModelProvider(providerName);
    if (provider?.defaultModel) {
      setModelName(provider.defaultModel);
    } else if (provider?.models?.[0]) {
      setModelName(provider.models[0].id);
    }
  };

  const getToolsForApi = useCallback((apiSlug: string): ApiTool[] => {
    return toolsCache.get(apiSlug) || [];
  }, [toolsCache]);

  const loadToolsForApi = useCallback((apiSlug: string) => {
    setToolsCache(prevCache => {
      if (prevCache.has(apiSlug)) return prevCache;

      setLoadingApis(prevLoading => {
        if (prevLoading.has(apiSlug)) return prevLoading;

        const nextLoading = new Set(prevLoading);
        nextLoading.add(apiSlug);

        fetchApiTools(apiSlug)
          .then(tools => {
            setToolsCache(cache => {
              const nextCache = new Map(cache);
              nextCache.set(apiSlug, tools);
              return nextCache;
            });
          })
          .catch(error => {
            console.error(`Failed to load tools for ${apiSlug}:`, error);
            setToolsCache(cache => {
              const nextCache = new Map(cache);
              nextCache.set(apiSlug, []);
              return nextCache;
            });
          })
          .finally(() => {
            setLoadingApis(loading => {
              const nextLoading = new Set(loading);
              nextLoading.delete(apiSlug);
              return nextLoading;
            });
          });

        return nextLoading;
      });

      return prevCache;
    });
  }, []);

  const toggleCategory = useCallback((apiSlug: string) => {
    setExpandedCategories(prev => {
      const next = new Set(prev);
      if (next.has(apiSlug)) {
        next.delete(apiSlug);
      } else {
        next.add(apiSlug);
        loadToolsForApi(apiSlug);
      }
      return next;
    });
  }, [loadToolsForApi]);

  const toggleTool = useCallback((toolId: string) => {
    setSelectedTools(prev => {
      const next = new Set(prev);
      if (next.has(toolId)) {
        next.delete(toolId);
      } else {
        if (next.size >= MAX_TOOLS) return prev;
        next.add(toolId);
      }
      return next;
    });
  }, []);

  const isToolSelected = useCallback((toolId: string) => {
    return selectedTools.has(toolId);
  }, [selectedTools]);

  const toggleWorkflow = useCallback((workflowId: string) => {
    setSelectedWorkflows(prev => {
      const next = new Set(prev);
      if (next.has(workflowId)) {
        next.delete(workflowId);
      } else {
        next.add(workflowId);
      }
      return next;
    });
  }, []);

  const toggleApplication = useCallback((applicationId: string) => {
    setSelectedApplications(prev => {
      const next = new Set(prev);
      if (next.has(applicationId)) {
        next.delete(applicationId);
      } else {
        next.add(applicationId);
      }
      return next;
    });
  }, []);

  const toggleTable = useCallback((tableId: string) => {
    setSelectedTables(prev => {
      const next = new Set(prev);
      if (next.has(tableId)) {
        next.delete(tableId);
      } else {
        next.add(tableId);
      }
      return next;
    });
  }, []);

  const toggleInterface = useCallback((interfaceId: string) => {
    setSelectedInterfaces(prev => {
      const next = new Set(prev);
      if (next.has(interfaceId)) {
        next.delete(interfaceId);
      } else {
        next.add(interfaceId);
      }
      return next;
    });
  }, []);

  const toggleSubAgent = useCallback((agentId: string) => {
    if (ancestorIds.has(agentId)) return;
    setSelectedSubAgents(prev => {
      const next = new Set(prev);
      if (next.has(agentId)) {
        next.delete(agentId);
      } else {
        next.add(agentId);
      }
      return next;
    });
  }, [ancestorIds]);

  const toggleFile = useCallback((fileId: string) => {
    setSelectedFiles(prev => {
      const next = new Set(prev);
      if (next.has(fileId)) {
        next.delete(fileId);
      } else {
        next.add(fileId);
      }
      return next;
    });
  }, []);

  const getToolsModeDisplay = () => {
    switch (toolsMode) {
      case 'all': return t('allTools');
      case 'none': return t('noTools');
      case 'off': return t('offTools');
      case 'custom': return t('customTools', { count: selectedTools.size, max: MAX_TOOLS });
    }
  };

  const handleAvatarUpload = async (file: File): Promise<string> => {
    const result = await fileService.uploadGeneric(file, 'avatar');
    // Public avatar URL (anonymous serve): avatars render on marketplace cards and
    // shared apps via plain <img>, so the auth-gated by-id URL would never load there.
    return getPublicAvatarUrlById(result.id);
  };

  const updateWidgetConfig = <K extends keyof WidgetConfig>(key: K, value: WidgetConfig[K]) => {
    setWidgetConfig(prev => ({ ...prev, [key]: value }));
  };

  // Generate embed code
  const generateEmbedCode = () => {
    const token = widgetConfig.widgetToken || 'YOUR_WIDGET_TOKEN';
    const scriptUrl = widgetConfig.widgetScriptUrl || 'https://app.livecontext.ai/widget.js';
    return `<script
  src="${scriptUrl}"
  data-widget-token="${token}"
  data-position="${widgetConfig.position}"
  data-theme="${widgetConfig.theme}"
  data-color="${widgetConfig.primaryColor}"
  data-welcome="${widgetConfig.welcomeMessage}"
  data-bubble-text="${widgetConfig.bubbleText}"
  data-show-avatar="${widgetConfig.showAvatar}"
  data-auto-open="${widgetConfig.autoOpenDelay}"
></script>`;
  };

  /**
   * An armed schedule with no instruction runs on DELEGATED work, or on nothing at all.
   *
   * <p>`ScheduledTaskPromptBuilder.build` answers a wakeup with the agent's assigned inbox
   * and pending reviews when it has any, and only falls back to the stored `schedulePrompt`
   * when it has none. So a blank instruction is a supported setup for an agent that is sent
   * work, and a dead schedule for every other one: `ScheduleExecutorService` logs "has no
   * effective prompt ... skipping" and returns, leaving an advancing schedule, a chip on the
   * calendar and a silent agent.
   *
   * <p>Which is why this WARNS and does not refuse. Refusing would block a configuration the
   * platform supports, and would block it hardest where it is already in use: an existing
   * agent could not be edited at all until a static prompt was invented for it, and the
   * obvious escape (switch the schedule off) DELETES a live schedule on an edit. The MCP
   * `agent` tool accepts a blank prompt too, so refusing here would also put the rule in one
   * layer of the three this repo asks to agree.
   */
  const scheduleHasNoPrompt = scheduleEnabled && schedulePrompt.trim().length === 0;

  // Navigation
  const canProceedFromStep = (step: number): boolean => {
    switch (step) {
      case 1: return name.trim().length > 0;
      case 2: return true; // Configuration is optional
      case 3: return true; // Integration is optional
      default: return false;
    }
  };

  const nextStep = () => {
    if (currentStep < TOTAL_STEPS && canProceedFromStep(currentStep)) {
      setCurrentStep(prev => prev + 1);
    }
  };

  const prevStep = () => {
    if (currentStep > 1) {
      setCurrentStep(prev => prev - 1);
    }
  };

  const goToStep = (step: number) => {
    // When editing an existing (valid) agent, every step is reachable directly.
    if (isEditMode || step <= currentStep || canProceedFromStep(step - 1)) {
      setCurrentStep(step);
    }
  };

  // Save handler
  const handleSave = async () => {
    if (!name.trim()) return;
    if (!canMutate) return; // read-only VIEWER in an org workspace

    try {
      setIsCreating(true);

      // Build toolsConfig payload via the shared helper. The 5 internal resource
      // lists are ALWAYS emitted explicitly, regardless of `mode`. The backend
      // now MERGES partial updates (AgentService.updateAgent normalizes after
      // merging), so omitting a list would no longer wipe DB state - but emitting
      // them anyway is defense-in-depth: a future regression in the merge logic
      // cannot silently drop a list, and DB inspection of the modal's intent is
      // unambiguous. The previous "omit when mode='all'" workaround was based on
      // the pre-fix REPLACE semantics and has been removed.
      const toolsConfig = buildToolsConfigPayload({
        mode: toolsMode,
        tools: Array.from(selectedTools),
        workflows: Array.from(selectedWorkflows),
        applications: Array.from(selectedApplications),
        tables: Array.from(selectedTables),
        interfaces: Array.from(selectedInterfaces),
        agents: Array.from(selectedSubAgents),
        files: Array.from(selectedFiles),
        webSearch: webSearchEnabled,
        generation: generationEnabled,
        mailbox: mailboxEnabled,
        mailboxAccessMode,
        // Axis 1 - per-family grant (none|all|custom). The selected id lists above
        // are only emitted as the scope when the family's grant is 'custom'.
        workflowsGrant,
        tablesGrant,
        interfacesGrant,
        agentsGrant,
        applicationsGrant,
        // Axis 2 - per-family read/write mode (orthogonal to the grant)
        tableAccessMode,
        workflowAccessMode,
        interfaceAccessMode,
        agentAccessMode,
        applicationAccessMode,
        skillAccessMode,
        fileAccessMode,
        memoryAccessMode,
      });

      const payload: any = {
        name: name.trim(),
        // EDIT: send the explicit (possibly empty) string so the user can CLEAR these
        // fields. The backend merge treats null/absent as "no change" and "" as "clear"
        // (AgentService.updateAgent: `if (systemPrompt != null) set...`), so `|| undefined`
        // here silently dropped an emptied field and the old value survived. CREATE: keep
        // omitting when empty so the backend applies its own defaults.
        description: isEditMode ? description.trim() : (description.trim() || undefined),
        systemPrompt: isEditMode ? systemPrompt.trim() : (systemPrompt.trim() || undefined),
        modelProvider: modelProvider.trim() || undefined,
        modelName: modelName.trim() || undefined,
        temperature: temperature || 0.7,
        maxTokens: maxTokens || 16000,
        maxIterations: maxIterations || 100,
        executionTimeout: executionTimeout || 3600,
        // Sent verbatim (no `|| default`) so 0 reaches the backend as "disabled".
        inactivityTimeout: inactivityTimeout,
        // Only effort-capable bridge models (claude-code, codex) honor it; send it
        // (or "" to clear) only for those.
        reasoningEffort: supportsReasoningEffort({ provider: modelProvider }) ? (reasoningEffort || '') : undefined,
        creditBudget: creditBudget,
        budgetResetMode: budgetResetMode,
        toolsConfig: toolsConfig,
        avatarUrl: avatarUrl || undefined,
        isPublic: false,
        isActive: isActive,
        backlogEnabled: backlogEnabled,
        // Sent when it differs from what is stored (always on create when one is picked), so an
        // untouched agent keeps its column; null goes back to the workspace default.
        ...(chatChannelLinkId !== (agent?.chatChannelLinkId ?? null) ? { chatChannelLinkId } : {}),
        ...(chatChannelEnabled !== (agent?.chatChannelEnabled ?? true) ? { chatChannelEnabled } : {}),
        // Advanced turn-limit overrides - sent only when the user changed them from
        // the captured initial value, so an untouched agent keeps NULL columns
        // (backend YAML defaults) instead of being pinned to the UI defaults.
        ...buildChangedTurnLimits(
          { maxPerResourcePerTurn, loopIdenticalStop, loopConsecutiveStop },
          turnLimitInitials,
        ),
        // V350 - compaction enable + cadence + summariser model, sent only when
        // changed (else inherit). The model pair goes out whole: both non-blank =
        // override, both "" = explicit clear.
        ...buildChangedCompaction(
          { compactionEnabled, compactionAfterTurns, compactionModelProvider, compactionModelName },
          compactionInitials,
        ),
      };

      let savedAgent;
      if (isEditMode && agent?.id) {
        savedAgent = await orchestratorApi.updateAgent(agent.id, payload);
      } else {
        savedAgent = await orchestratorApi.createAgent(payload);
      }

      // The channel card's three choices, reported only when one of them changed on this save
      // (an untouched agent, or a new one left on the defaults, reports nothing). The link id
      // itself is not sent: only whether a specific destination was picked.
      if (savedAgent?.id && (
        chatChannelLinkId !== (agent?.chatChannelLinkId ?? null)
        || chatChannelEnabled !== (agent?.chatChannelEnabled ?? true)
        || requireToolAuthorization !== (agent?.requireToolAuthorization ?? false)
      )) {
        track('agent_channel_configured', {
          enabled: chatChannelEnabled,
          destination: chatChannelLinkId ? 'specific' : 'default',
          sensitive_actions: requireToolAuthorization,
        });
      }

      // Sensitive actions: their own endpoint (a partial update must never disarm an agent by
      // omission), called only when the choice changed. The agent is saved either way.
      if (savedAgent?.id && requireToolAuthorization !== (agent?.requireToolAuthorization ?? false)) {
        try {
          await agentService.setToolAuthorization(savedAgent.id, requireToolAuthorization);
        } catch (authErr) {
          console.error('Error setting agent tool authorization:', authErr);
          addToast({ type: 'error', title: t('error'), message: t('sensitiveActionsSaveFailed') });
        }
      }

      // Handle webhook - create or delete agent webhook token
      if (savedAgent?.id) {
        try {
          if (webhookEnabled && !webhookData) {
            // Create a new agent webhook token
            await agentService.createOrUpdateWebhook(savedAgent.id, {
              httpMethod: 'POST',
              authType: 'none',
              memoryEnabled: webhookMemory,
            });
          } else if (webhookEnabled && webhookData) {
            // Update memory setting if changed
            if (webhookMemory !== webhookData.memoryEnabled) {
              await agentService.createOrUpdateWebhook(savedAgent.id, {
                memoryEnabled: webhookMemory,
              });
            }
          } else if (!webhookEnabled && webhookData) {
            // User toggled OFF in edit mode - delete the webhook token
            await agentService.deleteWebhook(savedAgent.id);
          }
        } catch (webhookErr) {
          console.error('Error managing agent webhook:', webhookErr);
          addToast({
            type: 'error',
            title: t('error'),
            message: t('webhookCreateFailed'),
          });
        }
      }

      // Handle schedule. What happened here is reported back to the caller: the agent is
      // already saved by this point and a refusal below does not undo it, so "the agent was
      // created" and "it is scheduled" are two different answers and only one of them is
      // known before this block runs.
      let scheduleSaved: boolean | undefined;
      let scheduleHasPrompt: boolean | undefined;
      if (savedAgent?.id) {
        try {
          if (scheduleEnabled) {
            await agentService.createOrUpdateSchedule(savedAgent.id, {
              cron: scheduleCron,
              timezone: scheduleTimezone,
              maxExecutions: scheduleMaxExecutions,
              schedulePrompt: schedulePrompt.trim() || '',
              withMemory: scheduleWithMemory,
            });
            scheduleSaved = true;
            scheduleHasPrompt = schedulePrompt.trim().length > 0;
          } else if (!scheduleEnabled && scheduleData) {
            await agentService.deleteSchedule(savedAgent.id);
          }
        } catch (scheduleErr) {
          console.error('Error managing agent schedule:', scheduleErr);
          // Only a schedule the user ASKED for counts as refused here. A failed delete
          // leaves the old schedule running, which is a different problem and not one the
          // caller should report as "not scheduled".
          if (scheduleEnabled) scheduleSaved = false;
          addToast({
            type: 'error',
            title: t('error'),
            message: t('scheduleCreateFailed'),
          });
        }
      }

      // Handle widget configuration - mirror the webhook/schedule pattern: create/update
      // when enabled, and DEACTIVATE when toggled OFF for an existing widget. Without the
      // OFF branch, disabling the widget on update was a silent no-op and it kept serving
      // (isActive stayed true). Deactivate (not delete) so the embed token/URL stays stable
      // if the widget is re-enabled later - createOrUpdateWidgetConfig flips isActive back on.
      if (savedAgent?.id) {
        try {
          if (widgetConfig.enabled) {
            await orchestratorApi.createOrUpdateWidgetConfig(savedAgent.id, {
              position: widgetConfig.position,
              theme: widgetConfig.theme,
              primaryColor: widgetConfig.primaryColor,
              welcomeMessage: widgetConfig.welcomeMessage,
              bubbleText: widgetConfig.bubbleText,
              showAvatar: widgetConfig.showAvatar,
              autoOpenDelay: widgetConfig.autoOpenDelay,
              allowedOrigins: widgetConfig.allowedOrigins,
            });
          } else if (widgetConfig.widgetToken) {
            // Existing widget toggled OFF -> stop serving it (keeps the token for re-enable).
            await orchestratorApi.setWidgetActive(savedAgent.id, false);
          }
        } catch (widgetErr) {
          console.error('Error saving widget config:', widgetErr);
          addToast({
            type: 'error',
            title: t('error'),
            message: t('widgetCreateFailed'),
          });
        }
      }

      // Handle skills assignment - filter out fallback default:* IDs (not real UUIDs)
      if (savedAgent?.id) {
        try {
          const assignments = Array.from(selectedSkillsMap.keys())
            .filter(id => !id.startsWith('default:'))
            .map((skillId) => ({ skillId }));
          await orchestratorApi.setAgentSkills(savedAgent.id, assignments);
        } catch (skillsErr) {
          console.error('Error saving agent skills:', skillsErr);
        }
      }

      await queryClient.invalidateQueries({ queryKey: ['agents'] });
      // Backend creates a conversation for the new agent - refresh the conversation list
      if (!isEditMode) {
        queryClient.invalidateQueries({ queryKey: ['conversations'] });
      }
      // The id only travels on a CREATE: on an edit the agent already sits where it sits,
      // and handing its id to the list would refile it under whatever folder is open.
      onAgentCreated(isEditMode ? undefined : savedAgent?.id, { scheduleSaved, scheduleHasPrompt });
      onClose();
    } catch (err) {
      console.error('Error creating agent:', err);
      // Surface a clear message for the common duplicate-name case (default preset names
      // like "Nova" collide) instead of a generic failure - and without the agent-tool
      // jargon the backend message carries ("Use agent(action='update', ...)").
      const isDuplicateName = err instanceof Error && /already exists/i.test(err.message);
      addToast({
        type: 'error',
        title: t('error'),
        message: isDuplicateName ? t('duplicateName', { name: name.trim() }) : t('createFailed'),
      });
    } finally {
      setIsCreating(false);
    }
  };

  // Delete the agent being edited. Same REST call as the list view, so both entry
  // points stay on one code path; the parent's refresh callback closes the modal,
  // which is why `isDeleting` is only reset on failure (success unmounts us).
  const handleDelete = async () => {
    if (!isEditMode || !agent?.id || !canMutate) return;
    setIsDeleting(true);
    try {
      await orchestratorApi.deleteAgent(agent.id);
      await queryClient.invalidateQueries({ queryKey: ['agents'] });
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
      onAgentCreated();
      onClose();
    } catch (err) {
      console.error('Error deleting agent:', err);
      addToast({ type: 'error', title: t('error'), message: t('deleteFailed') });
      setShowDeleteConfirm(false);
      setIsDeleting(false);
    }
  };

  // Helper: render a single collapsible resource category section inside the unified popover
  const renderResourceCategory = ({ categoryKey, icon, label, filteredItems, allItems, selectedIds, onToggleAll, isLoading, loadingText, emptyText, renderItem, accessMode, onAccessModeToggle, grant, onGrantChange, loadMoreRef, isFetchingMore }: {
    categoryKey: string;
    icon: React.ReactNode;
    label: string;
    filteredItems: any[];
    allItems: any[];
    selectedIds: Set<string>;
    onToggleAll: () => void;
    isLoading: boolean;
    loadingText: string;
    emptyText: string;
    renderItem: (item: any) => React.ReactNode;
    accessMode?: 'read' | 'write';
    onAccessModeToggle?: () => void;
    /**
     * Per-family GRANT (axis 1). When provided, the header shows a 3-state
     * None/All/Custom selector and the id list is rendered ONLY for 'custom'.
     * Families without a grant (e.g. files) keep the legacy always-list behavior.
     */
    grant?: ResourceGrant;
    onGrantChange?: (next: ResourceGrant) => void;
    /**
     * Infinite-scroll sentinel. When provided, a bottom sentinel div is rendered
     * after the item list (the family is paginated); the parent's
     * IntersectionObserver attaches to it to fetch the next page on scroll.
     */
    loadMoreRef?: React.RefObject<HTMLDivElement>;
    /** True while the next page is loading - shows a small spinner row. */
    isFetchingMore?: boolean;
  }) => {
    const selectedCount = selectedIds.size;
    const totalCount = allItems.length;
    const allSelected = selectedCount === totalCount && totalCount > 0;
    const isExpanded = expandedResourceCategories.has(categoryKey);
    // A grant-driven family only shows its id list (and Select-all) under 'custom'.
    // A grant-less family (files) always lists.
    const hasGrant = grant !== undefined && !!onGrantChange;
    const showList = !hasGrant || grant === 'custom';

    const toggleExpanded = () => {
      setExpandedResourceCategories(prev => {
        const next = new Set(prev);
        if (next.has(categoryKey)) {
          next.delete(categoryKey);
        } else {
          next.add(categoryKey);
        }
        return next;
      });
    };

    return (
      <div className="border-b border-theme last:border-b-0">
        <div data-testid={`resource-cat-${categoryKey}-${isExpanded ? 'expanded' : 'collapsed'}`} className="flex items-center justify-between px-3 py-2 bg-[var(--bg-secondary)]/50 cursor-pointer select-none" onClick={toggleExpanded}>
          <div className="flex items-center gap-2 text-xs font-medium text-theme-secondary uppercase tracking-wider">
            {isExpanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            {icon}
            <span>{label}</span>
            {/* Count badge only meaningful for a scoped (custom) selection */}
            {showList && <span className="text-theme-muted">({selectedCount}/{totalCount})</span>}
          </div>
          <div className="flex items-center gap-2">
            {/* Axis 1 - 3-state grant selector (None / All / Custom) */}
            {hasGrant && (
              <div
                role="radiogroup"
                aria-label={t('grantSelectorLabel', { resource: label })}
                className="flex items-center rounded-md border border-theme overflow-hidden"
                onClick={(e) => e.stopPropagation()}
              >
                {(['none', 'all', 'custom'] as const).map(g => (
                  <button
                    key={g}
                    type="button"
                    role="radio"
                    aria-checked={grant === g}
                    onClick={(e) => { e.stopPropagation(); onGrantChange!(g); }}
                    className={`text-[10px] px-1.5 py-0.5 font-medium transition-colors ${
                      grant === g
                        ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                        : 'bg-transparent text-theme-secondary hover:text-theme-primary'
                    }`}
                  >
                    {t(`grant_${g}`)}
                  </button>
                ))}
              </div>
            )}
            {/* Axis 2 - R/W pill. DECOUPLED from selectedCount: render whenever the
                family is granted at all (grant !== 'none'), so "All + Read/Write"
                is expressible. Grant-less families (files) are accessible by default
                (empty scope = full access), so their R/W pill ALWAYS shows - read-only
                must be settable even when no specific files are scoped. */}
            {accessMode && onAccessModeToggle && (hasGrant ? grant !== 'none' : true) && (
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); onAccessModeToggle(); }}
                className={`text-[10px] px-1.5 py-0.5 rounded font-medium transition-colors ${
                  accessMode === 'read'
                    ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'
                    : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400'
                }`}
              >
                {accessMode === 'read' ? t('accessModeRead') : t('accessModeReadWrite')}
              </button>
            )}
            {showList && totalCount > 0 && (
              <button type="button" onClick={(e) => { e.stopPropagation(); onToggleAll(); }} className="text-xs text-theme-secondary hover:text-theme-primary transition-colors">
                {allSelected ? t('deselectAll') : t('selectAll')}
              </button>
            )}
          </div>
        </div>
        {isExpanded && showList && (
          <>
            {isLoading ? (
              <div className="px-3 py-2 text-sm text-theme-secondary flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin" />
                {loadingText}
              </div>
            ) : filteredItems.length === 0 ? (
              <div className="px-3 py-2 text-sm text-theme-secondary">
                {resourceSearchQuery ? t('noSearchResults') : emptyText}
              </div>
            ) : (
              <div className="py-0.5">
                {filteredItems.map(renderItem)}
                {/* Infinite-scroll sentinel: when this family is paginated, the parent's
                    IntersectionObserver watches this div to fetch the next page on scroll. */}
                {loadMoreRef && (
                  <div ref={loadMoreRef} data-testid={`resource-cat-${categoryKey}-load-more`} className="py-2 flex justify-center">
                    {isFetchingMore && <Loader2 className="h-4 w-4 text-theme-secondary animate-spin" />}
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </div>
    );
  };

  // ============== Render ==============

  if (!mounted) return null;

  const modalContent = (
    <>
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      >
        <div
          className="max-w-2xl w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] flex flex-col"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="px-8 pt-8 pb-4">
            <div className="text-center mb-4">
              <h3 className="text-xl font-semibold text-theme-primary">
                {isEditMode ? t('editTitle') : t('title')}
              </h3>
              <p className="text-sm text-theme-secondary mt-1">
                {currentStep === 1 && 'Define your agent identity'}
                {currentStep === 2 && 'Configure behavior and capabilities'}
                {currentStep === 3 && 'Set up external integrations (optional)'}
              </p>
            </div>
            <StepIndicator
              currentStep={currentStep}
              totalSteps={TOTAL_STEPS}
              onStepClick={goToStep}
              allowAll={isEditMode}
            />
          </div>

          {/* Content */}
          <div className="flex-1 overflow-y-auto px-8 pb-4">
            {/* Step 1: Basic Info */}
            {currentStep === 1 && (
              <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
                {/* Avatar */}
                <div className="flex justify-center">
                  <Popover>
                    <PopoverTrigger asChild>
                      <button type="button" className="relative group">
                        <AvatarDisplay avatarUrl={avatarUrl} size="xl" />
                        <div className="absolute -bottom-1 -right-1 w-6 h-6 bg-theme-secondary rounded-full flex items-center justify-center border-2 border-[var(--bg-primary)] group-hover:bg-[var(--accent-primary)] transition-colors">
                          <Pencil className="w-3 h-3 text-theme-secondary group-hover:text-[var(--bg-primary)]" />
                        </div>
                      </button>
                    </PopoverTrigger>
                    <PopoverContent className="w-auto p-3 bg-[var(--bg-primary)] border border-theme rounded-xl shadow-lg z-[10001]" align="center">
                      <AvatarPicker
                        value={avatarUrl}
                        onChange={handleAvatarChange}
                        onUpload={handleAvatarUpload}
                        onGenerate={(prompt) => agentService.generateAvatar(prompt)}
                        size="sm"
                      />
                    </PopoverContent>
                  </Popover>
                </div>

                {/* Name */}
                <div>
                  <label className="block text-sm font-medium text-theme-primary mb-2">
                    {t('nameLabel')} <span className="text-red-500">*</span>
                  </label>
                  <Input
                    type="text"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder={t('namePlaceholder')}
                    className="w-full"
                  />
                </div>

                {/* Description */}
                <div>
                  <label className="block text-sm font-medium text-theme-primary mb-2">{t('descriptionLabel')}</label>
                  <Input
                    type="text"
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder={t('descriptionPlaceholder')}
                    className="w-full"
                  />
                </div>
              </div>
            )}

            {/* Step 2: Configuration */}
            {currentStep === 2 && (
              <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
                {/* System Prompt */}
                <div>
                  <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                    {t('systemPromptLabel')}
                    <InfoPopover label={t('systemPromptLabel')}>{t('systemPromptInfo')}</InfoPopover>
                  </span>
                  <textarea
                    value={systemPrompt}
                    onChange={(e) => setSystemPrompt(e.target.value)}
                    placeholder={t('systemPromptPlaceholder')}
                    className="w-full min-h-[100px] px-4 py-3 text-sm rounded-xl border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2"
                    rows={4}
                  />
                </div>

                {/* Model Selection - shared ModelPicker enforces the typed
                    SelectedModel contract (same contract as chat / workflow
                    node inspectors) so the two fields can't drift on casing
                    or silently carry a qualified id. */}
                <ModelPicker
                  value={{ provider: modelProvider, id: modelName }}
                  onChange={(next) => {
                    setModelProvider(next.provider);
                    setModelName(next.id);
                  }}
                  disabled={modelsLoading}
                  providerLabel={t('modelProviderLabel')}
                  modelLabel={t('modelNameLabel')}
                  costProfile="chatConversation"
                />

                {/* Credit Budget */}
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                      {t('creditBudgetLabel')}
                      <InfoPopover label={t('creditBudgetLabel')}>{t('creditBudgetInfo')}</InfoPopover>
                    </span>
                    <Input
                      type="number"
                      value={creditBudget ?? ''}
                      onChange={(e) => {
                        const val = e.target.value;
                        setCreditBudget(val === '' ? null : parseFloat(val));
                      }}
                      placeholder={t('unlimited')}
                      className="w-full"
                      min="0"
                      step="0.01"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-2">
                      {t('resetModeLabel')}
                    </label>
                    <Select value={budgetResetMode} onValueChange={(value: 'cumulative' | 'monthly' | 'weekly') => setBudgetResetMode(value)}>
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="cumulative">{t('cumulative')}</SelectItem>
                        <SelectItem value="monthly">{t('monthly')}</SelectItem>
                        <SelectItem value="weekly">{t('weekly')}</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                </div>
                {isEditMode && agent && (agent.creditsConsumed ?? 0) > 0 && (
                  <div className="flex items-center justify-between p-3 rounded-xl border border-theme bg-theme-secondary">
                    <div className="text-sm text-theme-secondary">
                      {t('creditsUsed')}: <span className="font-medium text-theme-primary">{formatCost(agent.creditsConsumed ?? 0, 2)}</span>
                      {agent.creditBudget != null && (
                        <span className="text-theme-secondary"> / {formatCost(agent.creditBudget, 0)}</span>
                      )}
                    </div>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={async () => {
                        if (agent?.id && confirm(t('resetCreditsConfirm'))) {
                          try {
                            await agentService.resetCredits(agent.id);
                            onAgentCreated?.();
                          } catch {
                            // ignore
                          }
                        }
                      }}
                    >
                      {t('resetCredits')}
                    </Button>
                  </div>
                )}

                {/* Tools Selection */}
                <div>
                  <label className="block text-sm font-medium text-theme-primary mb-2">{t('toolsLabel')}</label>
                  <Select value={toolsMode} onValueChange={(value: ToolsMode) => setToolsMode(value)}>
                    <SelectTrigger className="w-full">
                      <div className="flex items-center gap-2">
                        <Image src="/icons/integration_black.svg" alt="Integration" width={16} height={16} className="w-4 h-4 dark:invert" />
                        <span>{getToolsModeDisplay()}</span>
                      </div>
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">
                        <div className="flex items-center gap-2">
                          <Image src="/icons/integration_black.svg" alt="Integration" width={16} height={16} className="w-4 h-4 dark:invert" />
                          <span>{t('allTools')}</span>
                        </div>
                      </SelectItem>
                      <SelectItem value="none">
                        <div className="flex items-center gap-2">
                          <X className="w-4 h-4 text-theme-secondary" />
                          <span>{t('noTools')}</span>
                        </div>
                      </SelectItem>
                      <SelectItem value="off">
                        <div className="flex items-center gap-2">
                          <X className="w-4 h-4 text-theme-secondary" />
                          <span>{t('offTools')}</span>
                        </div>
                      </SelectItem>
                      <SelectItem value="custom">
                        <div className="flex items-center gap-2">
                          <Check className="w-4 h-4 text-theme-secondary" />
                          <span>{t('customToolsOption', { max: MAX_TOOLS })}</span>
                        </div>
                      </SelectItem>
                    </SelectContent>
                  </Select>

                  {/* Custom Tools Picker */}
                  {toolsMode === 'custom' && (
                    <Popover open={toolsPopoverOpen} onOpenChange={(open) => {
                      setToolsPopoverOpen(open);
                      if (!open) {
                        setToolsSearchQuery('');
                        setDebouncedSearchQuery('');
                      }
                    }}>
                      <PopoverTrigger asChild>
                        <button type="button" className="mt-2 flex h-auto min-h-[44px] w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] ring-offset-background focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:ring-offset-0 hover:bg-[var(--bg-secondary)] transition-colors">
                          <span className="text-theme-secondary">
                            {selectedTools.size === 0 ? t('selectTools') : t('toolsSelected', { count: selectedTools.size, max: MAX_TOOLS })}
                          </span>
                          <ChevronDown className="h-4 w-4 opacity-50" />
                        </button>
                      </PopoverTrigger>
                      <PopoverContent className="w-[var(--radix-popover-trigger-width)] p-0 bg-[var(--bg-primary)] border border-theme rounded-xl shadow-lg z-[10001]" align="start" sideOffset={4}>
                        <div className="flex items-center justify-between px-3 py-2 border-b border-theme">
                          <span className="text-sm font-medium text-theme-primary">{t('toolsLabel')}</span>
                          <span className={`text-xs ${selectedTools.size >= MAX_TOOLS ? 'text-red-500' : 'text-theme-secondary'}`}>
                            {selectedTools.size}/{MAX_TOOLS}
                          </span>
                        </div>
                        <div className="px-3 py-2 border-b border-theme">
                          <div className="relative flex items-center">
                            <div className="absolute left-3 pointer-events-none z-10">
                              <Search className="h-4 w-4 text-theme-secondary" />
                            </div>
                            <Input type="text" placeholder={t('searchApis')} value={toolsSearchQuery} onChange={(e) => setToolsSearchQuery(e.target.value)} className="pl-9 pr-9 h-9" />
                            <div className="absolute right-3 z-10 flex items-center">
                              {toolsSearchQuery && isFetchingApis ? (
                                <Loader2 className="h-4 w-4 text-theme-secondary animate-spin" />
                              ) : toolsSearchQuery ? (
                                <button type="button" onClick={() => setToolsSearchQuery('')} className="text-theme-secondary hover:text-theme-primary">
                                  <X className="h-4 w-4" />
                                </button>
                              ) : null}
                            </div>
                          </div>
                        </div>
                        <div className="max-h-[250px] overflow-y-auto" onScroll={(e) => {
                          const target = e.target as HTMLDivElement;
                          const bottom = target.scrollHeight - target.scrollTop - target.clientHeight < 50;
                          if (bottom && hasNextPage && !isFetchingApis && !apisLoading && mcpApis.length > 0) {
                            fetchNextPage();
                          }
                        }}>
                          {apisLoading ? (
                            <div className="p-4 text-center text-sm text-theme-secondary">{t('loadingTools')}</div>
                          ) : mcpApis.length === 0 ? (
                            <div className="p-4 text-center text-sm text-theme-secondary">
                              {debouncedSearchQuery ? t('noSearchResults') : t('noToolsAvailable')}
                            </div>
                          ) : (
                            <div className="py-1">
                              {mcpApis.map((api) => {
                                const apiSlug = api.slug;
                                const apiTools = getToolsForApi(apiSlug);
                                const isExpanded = expandedCategories.has(apiSlug);
                                const isLoadingThisApi = loadingApis.has(apiSlug);
                                return (
                                  <div key={apiSlug}>
                                    <div className="flex items-center gap-2 px-3 py-2 cursor-pointer hover:bg-[var(--bg-secondary)] transition-colors" onClick={() => toggleCategory(apiSlug)}>
                                      {api.iconSlug ? (
                                        <ServiceLogo as={Image} src={`/icons/services/${api.iconSlug}.svg`} alt={api.apiName} width={16} height={16} className="w-4 h-4 flex-shrink-0 rounded-md p-0.5 dark:bg-slate-100/10" onError={(e) => { (e.target as HTMLImageElement).src = '/icons/integration_black.svg'; (e.target as HTMLImageElement).classList.add('dark:invert'); }} />
                                      ) : (
                                        <Image src="/icons/integration_black.svg" alt="Integration" width={16} height={16} className="w-4 h-4 dark:invert flex-shrink-0" />
                                      )}
                                      <div className="flex-1 min-w-0">
                                        <span className="text-sm font-medium text-theme-primary truncate block">{api.apiName}</span>
                                      </div>
                                      <span className="text-xs text-theme-secondary flex-shrink-0">{api.toolsCount || ''}</span>
                                      {isExpanded ? <ChevronDown className="w-4 h-4 text-theme-secondary flex-shrink-0" /> : <ChevronRight className="w-4 h-4 text-theme-secondary flex-shrink-0" />}
                                    </div>
                                    {isExpanded && (
                                      <div className="bg-[var(--bg-secondary)]/50 py-1">
                                        {isLoadingThisApi ? (
                                          <div className="py-2 px-3 pl-8 text-sm text-theme-secondary flex items-center gap-2">
                                            <Loader2 className="h-4 w-4 animate-spin" />
                                            {t('loadingTools')}
                                          </div>
                                        ) : apiTools.length === 0 ? (
                                          <div className="py-2 px-3 pl-8 text-sm text-theme-secondary">{t('noToolsAvailable')}</div>
                                        ) : (
                                          apiTools.map((tool) => {
                                            const toolId = `${apiSlug}:${tool.name}`;
                                            const isSelected = isToolSelected(toolId);
                                            const isDisabled = !isSelected && selectedTools.size >= MAX_TOOLS;
                                            return (
                                              <div key={toolId} className={`flex items-start gap-3 px-3 py-2 pl-8 transition-colors ${isDisabled ? 'opacity-50 cursor-not-allowed' : 'hover:bg-[var(--bg-tertiary)] cursor-pointer'}`} onClick={() => !isDisabled && toggleTool(toolId)}>
                                                <button type="button" disabled={isDisabled} className={`w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 ${isSelected ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]' : 'border-gray-300 dark:border-gray-600'}`}>
                                                  {isSelected && <Check className="w-3 h-3 text-[var(--accent-foreground)]" />}
                                                </button>
                                                <div className="flex-1 min-w-0">
                                                  <span className="text-sm text-theme-primary">{tool.name}</span>
                                                  {tool.description && <p className="text-xs text-theme-secondary mt-0.5 line-clamp-2">{tool.description}</p>}
                                                </div>
                                              </div>
                                            );
                                          })
                                        )}
                                      </div>
                                    )}
                                  </div>
                                );
                              })}
                              <div ref={apiLoadMoreRef} className="py-2 flex justify-center">
                                {hasNextPage && isFetchingApis && <Loader2 className="h-5 w-5 text-theme-secondary animate-spin" />}
                              </div>
                            </div>
                          )}
                        </div>
                      </PopoverContent>
                    </Popover>
                  )}
                </div>

                {/* Skills Selection */}
                <div>
                  <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                    {t('skillsLabel')}
                    <InfoPopover label={t('skillsLabel')}>{t('skillsInfo')}</InfoPopover>
                  </span>

                  <Popover open={skillsPopoverOpen} onOpenChange={setSkillsPopoverOpen}>
                    <PopoverTrigger asChild>
                      <button type="button" className="flex h-auto min-h-[44px] w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] ring-offset-background focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:ring-offset-0 hover:bg-[var(--bg-secondary)] transition-colors">
                        <div className="flex items-center gap-2">
                          <Zap className="w-4 h-4 text-theme-secondary" />
                          <span>
                            {selectedSkillsMap.size === 0
                              ? t('selectSkills')
                              : t('skillsCount', { count: selectedSkillsMap.size, max: MAX_SKILLS })}
                          </span>
                        </div>
                        <ChevronDown className="h-4 w-4 opacity-50" />
                      </button>
                    </PopoverTrigger>
                    <PopoverContent className="w-[var(--radix-popover-trigger-width)] p-0 bg-[var(--bg-primary)] border border-theme rounded-xl shadow-lg z-[10001]" align="start" sideOffset={4}>
                      <div className="flex items-center justify-between px-3 py-2 border-b border-theme">
                        <span className="text-sm font-medium text-theme-primary">{t('skillsLabel')}</span>
                        <div className="flex items-center gap-2">
                          {selectedSkillsMap.size > 0 && (
                            <button
                              type="button"
                              onClick={() => setSkillAccessMode(prev => prev === 'read' ? 'write' : 'read')}
                              className={`text-[10px] px-1.5 py-0.5 rounded font-medium transition-colors ${
                                skillAccessMode === 'read'
                                  ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'
                                  : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400'
                              }`}
                            >
                              {skillAccessMode === 'read' ? 'Read' : 'R/W'}
                            </button>
                          )}
                          <span className={`text-xs ${selectedSkillsMap.size >= MAX_SKILLS ? 'text-red-500' : 'text-theme-secondary'}`}>
                            {selectedSkillsMap.size}/{MAX_SKILLS}
                          </span>
                        </div>
                      </div>
                      <div className="max-h-[300px] overflow-y-auto p-2">
                        {availableSkills.length === 0 ? (
                          <div className="text-center py-6">
                            <Zap className="w-8 h-8 mx-auto mb-2 text-theme-muted" />
                            <p className="text-sm text-theme-muted">{t('noSkillsAvailable')}</p>
                          </div>
                        ) : (
                          <SkillFolderTree
                            allFolders={skillFolders}
                            allSkills={agentScopedSkills}
                            onCreateFolder={() => {}}
                            onRenameFolder={() => {}}
                            onDeleteFolder={() => {}}
                            onCreateSkill={() => {}}
                            onEditSkill={() => {}}
                            onDeleteSkill={() => {}}
                            onMoveSkillToFolder={() => {}}
                            onMoveFolderToFolder={() => {}}
                            isAdmin={isAdmin}
                            userOverrides={agentSkillOverrides}
                            onToggleSkillActive={updateAgentSkillSelection}
                            onToggleSkillIsDefaultActive={updateAgentSkillSelection}
                          />
                        )}
                      </div>
                    </PopoverContent>
                  </Popover>
                </div>

                {/* Long-term memory read/write axis. Standalone rather than attached to a
                    picker like skills or files: memory has no id list to scope, only this
                    axis. What the agent saves lands in every agent's context in the
                    workspace, so the choice belongs next to the other capability switches
                    rather than buried in an advanced panel. */}
                <div>
                  <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                    {t('memoryAccessLabel')}
                    <InfoPopover label={t('memoryAccessLabel')}>{t('memoryAccessInfo')}</InfoPopover>
                  </span>
                  <button
                    type="button"
                    onClick={() => setMemoryAccessMode(prev => (prev === 'read' ? 'write' : 'read'))}
                    className="flex h-auto min-h-[44px] w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
                  >
                    <div className="flex items-center gap-2">
                      <Brain className="w-4 h-4 text-theme-secondary" />
                      <span>{memoryAccessMode === 'read' ? t('memoryAccessRead') : t('memoryAccessWrite')}</span>
                    </div>
                    <Switch checked={memoryAccessMode === 'write'} presentational />
                  </button>
                </div>

                {/* Web Search toggle */}
                <div>
                  <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                    {t('webSearchLabel')}
                    <InfoPopover label={t('webSearchLabel')}>{t('webSearchInfo')}</InfoPopover>
                  </span>
                  <button
                    type="button"
                    onClick={() => setWebSearchEnabled(!webSearchEnabled)}
                    className="flex h-auto min-h-[44px] w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
                  >
                    <div className="flex items-center gap-2">
                      <Globe className="w-4 h-4 text-theme-secondary" />
                      <span>{webSearchEnabled ? t('enabled') : t('disabled')}</span>
                    </div>
                    <Switch checked={webSearchEnabled} presentational />
                  </button>
                </div>

                {/* Resource Access - unified popover for workflows, applications, tables, interfaces, agents */}
                <div>
                  <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                    {t('resourceAccessLabel')}
                    <InfoPopover label={t('resourceAccessLabel')}>{t('resourceAccessInfo')}</InfoPopover>
                  </span>
                  <Popover open={resourceAccessPopoverOpen} onOpenChange={(open) => { setResourceAccessPopoverOpen(open); if (!open) setResourceSearchQuery(''); }}>
                    <PopoverTrigger asChild>
                      <button type="button" className="flex h-auto min-h-[44px] w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] ring-offset-background focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:ring-offset-0 hover:bg-[var(--bg-secondary)] transition-colors">
                        <div className="flex items-center gap-2">
                          <Settings className="w-4 h-4 text-theme-secondary" />
                          <span>
                            {resourceAccessSummary.totalAvailable === 0
                              ? t('noResourcesAvailable')
                              : resourceAccessSummary.totalSelected === resourceAccessSummary.totalAvailable
                              ? t('allResources')
                              : resourceAccessSummary.totalSelected === 0
                              ? t('noResources')
                              : t('resourcesSelected', { count: resourceAccessSummary.totalSelected, total: resourceAccessSummary.totalAvailable })}
                          </span>
                        </div>
                        <ChevronDown className="h-4 w-4 opacity-50" />
                      </button>
                    </PopoverTrigger>
                    <PopoverContent className="w-[var(--radix-popover-trigger-width)] p-0 bg-[var(--bg-primary)] border border-theme rounded-xl shadow-lg z-[10001]" align="start" sideOffset={4}>
                      {/* Global search */}
                      <div className="px-3 py-2 border-b border-theme">
                        <div className="relative flex items-center">
                          <div className="absolute left-3 pointer-events-none z-10"><Search className="h-4 w-4 text-theme-secondary" /></div>
                          <Input type="text" placeholder={t('searchResources')} value={resourceSearchQuery} onChange={(e) => setResourceSearchQuery(e.target.value)} className="pl-9 pr-9 h-9" />
                          {resourceSearchQuery && (
                            <button type="button" onClick={() => setResourceSearchQuery('')} className="absolute right-3 z-10 text-theme-secondary hover:text-theme-primary">
                              <X className="h-4 w-4" />
                            </button>
                          )}
                        </div>
                      </div>
                      <div className="max-h-[400px] overflow-y-auto">
                        {resourceAccessSummary.totalAvailable === 0 && !workflowsLoading && !applicationsLoading && !tablesLoading && !interfacesLoading && !agentsLoading ? (
                          <div className="p-4 text-center text-sm text-theme-secondary">{t('noResourcesAvailable')}</div>
                        ) : (
                          <>
                            {/* Agents (Sub-Agents) */}
                            {renderResourceCategory({
                              categoryKey: 'agents',
                              icon: <Bot className="w-3.5 h-3.5" />,
                              label: t('agentAccessLabel'),
                              filteredItems: filteredResourceCategories.agents,
                              allItems: allAgents,
                              selectedIds: selectedSubAgents,
                              onToggleAll: () => {
                                const selectableAgents = allAgents.filter((a: Agent) => !ancestorIds.has(a.id));
                                const allSelected = selectableAgents.length > 0 && selectableAgents.every((a: Agent) => selectedSubAgents.has(a.id));
                                if (allSelected) {
                                  setSelectedSubAgents(new Set());
                                } else {
                                  setSelectedSubAgents(new Set(selectableAgents.map((a: Agent) => a.id)));
                                }
                              },
                              isLoading: agentsLoading,
                              loadingText: t('loadingAgents'),
                              emptyText: t('noAgentsAvailable'),
                              renderItem: (subAgent: Agent) => {
                                const isAncestor = ancestorIds.has(subAgent.id);
                                const isSelected = !isAncestor && selectedSubAgents.has(subAgent.id);
                                return (
                                  <div
                                    key={subAgent.id}
                                    className={`flex items-start gap-3 px-3 py-1.5 transition-colors ${isAncestor ? 'opacity-50 cursor-not-allowed' : 'hover:bg-[var(--bg-secondary)] cursor-pointer'}`}
                                    onClick={() => toggleSubAgent(subAgent.id)}
                                  >
                                    <button type="button" disabled={isAncestor} className={`w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 ${isSelected ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]' : 'border-gray-300 dark:border-gray-600'}`}>
                                      {isSelected && <Check className="w-3 h-3 text-[var(--accent-foreground)]" />}
                                    </button>
                                    <div className="flex items-center gap-2 flex-1 min-w-0">
                                      <AvatarDisplay avatarUrl={subAgent.avatarUrl} name={subAgent.name} size="sm" className="w-6 h-6 flex-shrink-0" />
                                      <div className="flex-1 min-w-0">
                                        <span className={`text-sm block ${isAncestor ? 'text-theme-muted' : 'text-theme-primary'}`}>{subAgent.name}</span>
                                        {isAncestor && <p className="text-xs text-amber-500 dark:text-amber-400 mt-0.5">{t('ancestorAgentWarning')}</p>}
                                        {!isAncestor && subAgent.description && <p className="text-xs text-theme-secondary mt-0.5 line-clamp-1">{subAgent.description}</p>}
                                      </div>
                                    </div>
                                  </div>
                                );
                              },
                              accessMode: agentAccessMode,
                              onAccessModeToggle: () => setAgentAccessMode(prev => prev === 'read' ? 'write' : 'read'),
                              grant: agentsGrant,
                              onGrantChange: setAgentsGrant,
                            })}

                            {/* Applications */}
                            {renderResourceCategory({
                              categoryKey: 'applications',
                              icon: <AppWindow className="w-3.5 h-3.5" />,
                              label: t('applicationAccessLabel'),
                              filteredItems: filteredResourceCategories.applications,
                              allItems: acquiredApplications,
                              selectedIds: selectedApplications,
                              onToggleAll: () => {
                                if (selectedApplications.size === acquiredApplications.length && acquiredApplications.length > 0) {
                                  setSelectedApplications(new Set());
                                } else {
                                  setSelectedApplications(new Set(acquiredApplications.map((app: AcquiredApplication) => app.sourcePublicationId)));
                                }
                              },
                              isLoading: applicationsLoading,
                              loadingText: t('loadingApplications'),
                              emptyText: t('noApplicationsAvailable'),
                              renderItem: (item: AcquiredApplication) => {
                                const isSelected = selectedApplications.has(item.sourcePublicationId);
                                return (
                                  <div key={item.sourcePublicationId} className="flex items-start gap-3 px-3 py-1.5 hover:bg-[var(--bg-secondary)] cursor-pointer transition-colors" onClick={() => toggleApplication(item.sourcePublicationId)}>
                                    <button type="button" className={`w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 ${isSelected ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]' : 'border-gray-300 dark:border-gray-600'}`}>
                                      {isSelected && <Check className="w-3 h-3 text-[var(--accent-foreground)]" />}
                                    </button>
                                    <div className="flex-1 min-w-0">
                                      <span className="text-sm text-theme-primary block">{item.name}</span>
                                      {item.description && <p className="text-xs text-theme-secondary mt-0.5 line-clamp-1">{item.description}</p>}
                                    </div>
                                  </div>
                                );
                              },
                              accessMode: applicationAccessMode,
                              onAccessModeToggle: () => setApplicationAccessMode(prev => prev === 'read' ? 'write' : 'read'),
                              grant: applicationsGrant,
                              onGrantChange: setApplicationsGrant,
                            })}

                            {/* Workflows */}
                            {renderResourceCategory({
                              categoryKey: 'workflows',
                              icon: <Workflow className="w-3.5 h-3.5" />,
                              label: t('workflowAccessLabel'),
                              filteredItems: filteredResourceCategories.workflows,
                              allItems: workflows,
                              selectedIds: selectedWorkflows,
                              onToggleAll: () => {
                                if (selectedWorkflows.size === workflows.length && workflows.length > 0) {
                                  setSelectedWorkflows(new Set());
                                } else {
                                  setSelectedWorkflows(new Set(workflows.map((wf: any) => wf.id)));
                                }
                              },
                              isLoading: workflowsLoading,
                              loadingText: t('loadingWorkflows'),
                              emptyText: t('noWorkflowsAvailable'),
                              renderItem: (item: any) => {
                                const isSelected = selectedWorkflows.has(item.id);
                                return (
                                  <div key={item.id} className="flex items-start gap-3 px-3 py-1.5 hover:bg-[var(--bg-secondary)] cursor-pointer transition-colors" onClick={() => toggleWorkflow(item.id)}>
                                    <button type="button" className={`w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 ${isSelected ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]' : 'border-gray-300 dark:border-gray-600'}`}>
                                      {isSelected && <Check className="w-3 h-3 text-[var(--accent-foreground)]" />}
                                    </button>
                                    <div className="flex-1 min-w-0">
                                      <span className="text-sm text-theme-primary block">{item.name}</span>
                                      {item.description && <p className="text-xs text-theme-secondary mt-0.5 line-clamp-1">{item.description}</p>}
                                    </div>
                                  </div>
                                );
                              },
                              accessMode: workflowAccessMode,
                              onAccessModeToggle: () => setWorkflowAccessMode(prev => prev === 'read' ? 'write' : 'read'),
                              grant: workflowsGrant,
                              onGrantChange: setWorkflowsGrant,
                              loadMoreRef: workflowsLoadMoreRef,
                              isFetchingMore: isFetchingWorkflows && !!hasNextWorkflowsPage,
                            })}

                            {/* Interfaces */}
                            {renderResourceCategory({
                              categoryKey: 'interfaces',
                              icon: <Monitor className="w-3.5 h-3.5" />,
                              label: t('interfaceAccessLabel'),
                              filteredItems: filteredResourceCategories.interfaces,
                              allItems: allInterfaces,
                              selectedIds: selectedInterfaces,
                              onToggleAll: () => {
                                if (selectedInterfaces.size === allInterfaces.length && allInterfaces.length > 0) {
                                  setSelectedInterfaces(new Set());
                                } else {
                                  setSelectedInterfaces(new Set(allInterfaces.map((i: Interface) => i.id)));
                                }
                              },
                              isLoading: interfacesLoading,
                              loadingText: t('loadingInterfaces'),
                              emptyText: t('noInterfacesAvailable'),
                              renderItem: (item: Interface) => {
                                const isSelected = selectedInterfaces.has(item.id);
                                return (
                                  <div key={item.id} className="flex items-start gap-3 px-3 py-1.5 hover:bg-[var(--bg-secondary)] cursor-pointer transition-colors" onClick={() => toggleInterface(item.id)}>
                                    <button type="button" className={`w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 ${isSelected ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]' : 'border-gray-300 dark:border-gray-600'}`}>
                                      {isSelected && <Check className="w-3 h-3 text-[var(--accent-foreground)]" />}
                                    </button>
                                    <div className="flex-1 min-w-0">
                                      <span className="text-sm text-theme-primary block">{item.name}</span>
                                      {item.description && <p className="text-xs text-theme-secondary mt-0.5 line-clamp-1">{item.description}</p>}
                                    </div>
                                  </div>
                                );
                              },
                              accessMode: interfaceAccessMode,
                              onAccessModeToggle: () => setInterfaceAccessMode(prev => prev === 'read' ? 'write' : 'read'),
                              grant: interfacesGrant,
                              onGrantChange: setInterfacesGrant,
                            })}

                            {/* Tables */}
                            {renderResourceCategory({
                              categoryKey: 'tables',
                              icon: <Table className="w-3.5 h-3.5" />,
                              label: t('tableAccessLabel'),
                              filteredItems: filteredResourceCategories.tables,
                              allItems: allTables,
                              selectedIds: selectedTables,
                              onToggleAll: () => {
                                if (selectedTables.size === allTables.length && allTables.length > 0) {
                                  setSelectedTables(new Set());
                                } else {
                                  setSelectedTables(new Set(allTables.map((tbl: DataSource) => tbl.id)));
                                }
                              },
                              isLoading: tablesLoading,
                              loadingText: t('loadingTables'),
                              emptyText: t('noTablesAvailable'),
                              renderItem: (item: DataSource) => {
                                const isSelected = selectedTables.has(item.id);
                                return (
                                  <div key={item.id} className="flex items-start gap-3 px-3 py-1.5 hover:bg-[var(--bg-secondary)] cursor-pointer transition-colors" onClick={() => toggleTable(item.id)}>
                                    <button type="button" className={`w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 ${isSelected ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]' : 'border-gray-300 dark:border-gray-600'}`}>
                                      {isSelected && <Check className="w-3 h-3 text-[var(--accent-foreground)]" />}
                                    </button>
                                    <div className="flex-1 min-w-0">
                                      <span className="text-sm text-theme-primary block">{item.name}</span>
                                      {item.description && <p className="text-xs text-theme-secondary mt-0.5 line-clamp-1">{item.description}</p>}
                                    </div>
                                  </div>
                                );
                              },
                              accessMode: tableAccessMode,
                              onAccessModeToggle: () => setTableAccessMode(prev => prev === 'read' ? 'write' : 'read'),
                              grant: tablesGrant,
                              onGrantChange: setTablesGrant,
                            })}

                            {renderResourceCategory({
                              categoryKey: 'files',
                              icon: <FileText className="w-3.5 h-3.5" />,
                              label: t('fileAccessLabel'),
                              filteredItems: filteredResourceCategories.files,
                              allItems: allFiles,
                              selectedIds: selectedFiles,
                              onToggleAll: () => {
                                if (selectedFiles.size === allFiles.length && allFiles.length > 0) {
                                  setSelectedFiles(new Set());
                                } else {
                                  setSelectedFiles(new Set(allFiles.map((f) => f.id)));
                                }
                              },
                              isLoading: filesLoading,
                              loadingText: t('loadingFiles'),
                              emptyText: t('noFilesAvailable'),
                              renderItem: (item: { id: string; name: string }) => {
                                const isSelected = selectedFiles.has(item.id);
                                return (
                                  <div key={item.id} className="flex items-start gap-3 px-3 py-1.5 hover:bg-[var(--bg-secondary)] cursor-pointer transition-colors" onClick={() => toggleFile(item.id)}>
                                    <button type="button" className={`w-4 h-4 mt-0.5 rounded border flex items-center justify-center transition-colors flex-shrink-0 ${isSelected ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]' : 'border-gray-300 dark:border-gray-600'}`}>
                                      {isSelected && <Check className="w-3 h-3 text-[var(--accent-foreground)]" />}
                                    </button>
                                    <div className="flex-1 min-w-0">
                                      <span className="text-sm text-theme-primary block truncate">{item.name}</span>
                                    </div>
                                  </div>
                                );
                              },
                              loadMoreRef: filesLoadMoreRef,
                              isFetchingMore: isFetchingFiles && !!hasNextFilesPage,
                              accessMode: fileAccessMode,
                              onAccessModeToggle: () => setFileAccessMode(prev => prev === 'read' ? 'write' : 'read'),
                            })}

                          </>
                        )}
                      </div>
                    </PopoverContent>
                  </Popover>
                </div>

                {/* Advanced limits - the 3 turn-limit overrides (maxPerResourcePerTurn /
                    loopIdenticalStop / loopConsecutiveStop). Available at CREATE and EDIT:
                    both POST and PUT /agents accept these columns, so native local-state
                    fields replace the old edit-only ChatConfigPanel (which needed an agent
                    id to auto-save). Sent in the payload only when changed from initial. */}
                <div className="pt-2">
                  <button
                    type="button"
                    onClick={() => setAdvancedModeOpen(prev => !prev)}
                    className="flex items-center gap-1 text-sm font-medium text-theme-primary hover:text-[var(--accent-primary)] transition-colors"
                  >
                    {advancedModeOpen ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                    <span>{t('advancedModeLabel')}</span>
                  </button>
                  {advancedModeOpen && (
                    <div className="mt-2 space-y-3 rounded-xl border border-theme bg-[var(--bg-secondary)] p-4">
                      <p className="text-xs text-theme-secondary">{tc('advancedSectionDescription')}</p>

                      {/* Temperature */}
                      <div>
                        <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                          {t('temperatureLabel')}
                          <InfoPopover label={t('temperatureLabel')}>{t('temperatureInfo')}</InfoPopover>
                        </span>
                        <div className="space-y-2">
                          <Slider value={[temperature]} onValueChange={(values) => setTemperature(values[0])} min={0} max={2} step={0.1} className="w-full" />
                          <div className="flex justify-between text-xs text-theme-secondary">
                            <span>0</span>
                            <span className="font-medium text-theme-primary">{temperature.toFixed(1)}</span>
                            <span>2</span>
                          </div>
                        </div>
                      </div>

                      {/* Reasoning effort - only for effort-capable bridge models (claude-code, codex) */}
                      {supportsReasoningEffort({ provider: modelProvider }) && (
                        <div>
                          <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            {t('reasoningEffortLabel')}
                          </label>
                          <Select
                            value={reasoningEffort || SELECT_EMPTY_VALUE_SENTINEL}
                            onValueChange={(v) => setReasoningEffort(v === SELECT_EMPTY_VALUE_SENTINEL ? '' : v)}
                          >
                            <SelectTrigger className="w-full">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="">{t('reasoningEffortInherit')}</SelectItem>
                              {REASONING_EFFORT_LEVELS.map((lvl) => (
                                <SelectItem key={lvl} value={lvl}>{lvl}</SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                      )}

                      {/* Max Tokens, Iterations, Timeout & Inactivity (2x2 grid) */}
                      <div className="grid grid-cols-2 gap-4">
                        <div>
                          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            {t('maxTokensLabel')}
                            <InfoPopover label={t('maxTokensLabel')}>{t('maxTokensInfo')}</InfoPopover>
                          </span>
                          <Input type="number" value={maxTokens} onChange={(e) => setMaxTokens(parseInt(e.target.value) || 1000)} placeholder="1000" className="w-full" min="1" />
                        </div>
                        <div className={toolsMode === 'none' ? 'opacity-50' : ''}>
                          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            {t('maxIterationsLabel')}
                            <InfoPopover label={t('maxIterationsLabel')}>{t('maxIterationsInfo')}</InfoPopover>
                          </span>
                          <Input type="number" value={maxIterations} onChange={(e) => setMaxIterations(parseInt(e.target.value) || 100)} placeholder="100" className="w-full" min="1" max="1000" disabled={toolsMode === 'none'} />
                        </div>
                        <div>
                          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            {t('executionTimeoutLabel')}
                            <InfoPopover label={t('executionTimeoutLabel')}>{t('executionTimeoutInfo')}</InfoPopover>
                          </span>
                          <Input type="number" value={executionTimeout} onChange={(e) => setExecutionTimeout(parseInt(e.target.value) || 3600)} placeholder="3600" className="w-full" min="10" max="7200" />
                        </div>
                        <div>
                          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            {t('inactivityTimeoutLabel')}
                            <InfoPopover label={t('inactivityTimeoutLabel')}>{t('inactivityTimeoutInfo')}</InfoPopover>
                          </span>
                          <Input type="number" value={inactivityTimeout} onChange={(e) => setInactivityTimeout(parseInt(e.target.value) || 0)} placeholder="300" className="w-full" min="0" max="7200" />
                        </div>
                      </div>

                      {/* Generation toggle - opt-in (default off). Persists to
                          toolsConfig.generation; read by AgentModuleResolver. Opt-in on
                          its own: it covers video, audio, voice and music too, which cost
                          an order of magnitude more per call than an image. */}
                      <div>
                        <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                          {tc('generationLabel')}
                          <InfoPopover label={tc('generationLabel')}>{tc('generationInfo')}</InfoPopover>
                        </span>
                        <button
                          type="button"
                          onClick={() => setGenerationEnabled(!generationEnabled)}
                          className="flex h-auto min-h-[44px] w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
                        >
                          <div className="flex items-center gap-2">
                            <Sparkles className="w-4 h-4 text-theme-secondary" />
                            <span>{generationEnabled ? t('enabled') : t('disabled')}</span>
                          </div>
                          <Switch checked={generationEnabled} presentational />
                        </button>
                      </div>

                      {/* Mailbox toggle - opt-in (default off). Persists to toolsConfig.mailbox;
                          read by AgentModuleResolver.isMailboxEnabled. Opt-in rather than
                          opt-out, and the comparison that settles it is web search: that one
                          reads the public web, this one reads the account's mail and can send
                          from its address, to a person, with no undo. Read-only is a separate
                          axis (toolsConfig.mailboxAccessMode), so an inbox scanner can be given
                          the reads without the ability to send. */}
                      <div>
                        <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                          {tc('mailboxLabel')}
                          <InfoPopover label={tc('mailboxLabel')}>{tc('mailboxInfo')}</InfoPopover>
                        </span>
                        <button
                          type="button"
                          onClick={() => setMailboxEnabled(!mailboxEnabled)}
                          className="flex h-auto min-h-[44px] w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
                        >
                          <div className="flex items-center gap-2">
                            <Mail className="w-4 h-4 text-theme-secondary" />
                            <span>{mailboxEnabled ? t('enabled') : t('disabled')}</span>
                          </div>
                          <Switch checked={mailboxEnabled} presentational />
                        </button>
                      </div>

                      {/* Mailbox read/write axis, shown only once the tool is on: offering a
                          mode for a capability the agent does not have reads as a setting that
                          does nothing. */}
                      {mailboxEnabled && (
                        <div>
                          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            {tc('mailboxAccessLabel')}
                            <InfoPopover label={tc('mailboxAccessLabel')}>{tc('mailboxAccessInfo')}</InfoPopover>
                          </span>
                          <button
                            type="button"
                            onClick={() => setMailboxAccessMode(mailboxAccessMode === 'write' ? 'read' : 'write')}
                            className="flex h-auto min-h-[44px] w-full items-center justify-between rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
                          >
                            <div className="flex items-center gap-2">
                              <Mail className="w-4 h-4 text-theme-secondary" />
                              <span>{mailboxAccessMode === 'write' ? tc('mailboxAccessWrite') : tc('mailboxAccessRead')}</span>
                            </div>
                            <Switch checked={mailboxAccessMode === 'write'} presentational />
                          </button>
                        </div>
                      )}

                      {([
                        { label: tc('maxPerResourcePerTurnLabel'), info: tc('maxPerResourcePerTurnInfo'), value: maxPerResourcePerTurn, set: setMaxPerResourcePerTurn, min: 1, max: 100 },
                        { label: tc('loopIdenticalStopLabel'), info: tc('loopIdenticalStopInfo'), value: loopIdenticalStop, set: setLoopIdenticalStop, min: 2, max: 100 },
                        { label: tc('loopConsecutiveStopLabel'), info: tc('loopConsecutiveStopInfo'), value: loopConsecutiveStop, set: setLoopConsecutiveStop, min: 4, max: 200 },
                      ] as const).map((f) => (
                        <div key={f.label} className="flex items-center justify-between gap-3">
                          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
                            <span className="min-w-0">{f.label}</span>
                            <InfoPopover label={f.label}>{f.info}</InfoPopover>
                          </span>
                          <Input
                            type="number"
                            value={f.value}
                            onChange={(e) => {
                              const parsed = parseInt(e.target.value, 10);
                              if (Number.isFinite(parsed)) f.set(parsed);
                            }}
                            min={f.min}
                            max={f.max}
                            className="w-28 shrink-0 text-right"
                          />
                        </div>
                      ))}
                      {/* V350 - per-agent compaction enable + cadence */}
                      <div className="flex items-center justify-between gap-3 pt-3 border-t border-theme">
                        <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
                          <span className="min-w-0">{tc('compactionEnabledLabel')}</span>
                          <InfoPopover label={tc('compactionEnabledLabel')}>{tc('compactionEnabledInfo')}</InfoPopover>
                        </span>
                        <Switch
                          checked={compactionEnabled}
                          onCheckedChange={setCompactionEnabled}
                          aria-label={tc('compactionEnabledLabel')}
                        />
                      </div>
                      {compactionEnabled && (
                        <div className="flex items-center justify-between gap-3">
                          <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
                            <span className="min-w-0">{tc('compactionAfterTurnsLabel')}</span>
                            <InfoPopover label={tc('compactionAfterTurnsLabel')}>{tc('compactionAfterTurnsInfo')}</InfoPopover>
                          </span>
                          <Input
                            type="number"
                            value={compactionAfterTurns}
                            onChange={(e) => {
                              const parsed = parseInt(e.target.value, 10);
                              if (Number.isFinite(parsed)) setCompactionAfterTurns(parsed);
                            }}
                            min={1}
                            max={100}
                            className="w-28 shrink-0 text-right"
                          />
                        </div>
                      )}
                      {/* Summariser-model override - off = inherit (conversations on this
                          agent use this compaction model if set, otherwise the platform
                          default; the primary chat model is never a tier). Toggling ON
                          seeds the pair from the primary model selection so the shown pick
                          is what gets saved - unless that pick is a CLI bridge, which can
                          never serve the summariser's bare single completion; then the
                          seed falls back to the first non-bridge provider's default.
                          Toggling OFF blanks both halves (explicit clear). */}
                      {compactionEnabled && (
                        <div className="space-y-2">
                          <div className="flex items-center justify-between gap-3">
                            <span className="flex items-center gap-1.5 text-sm font-medium text-theme-primary min-w-0">
                              <span className="min-w-0">{tc('compactionModelLabel')}</span>
                              <InfoPopover label={tc('compactionModelLabel')}>{tc('compactionModelInfo')}</InfoPopover>
                            </span>
                            <Switch
                              checked={compactionModelOpen}
                              onCheckedChange={(checked) => {
                                setCompactionModelOpen(checked);
                                if (checked) {
                                  if (!compactionModelProvider && !compactionModelName) {
                                    const seed = toNonBridgeSelectedModel(
                                      { provider: modelProvider, id: modelName },
                                      getModelsCache(),
                                    );
                                    if (!isEmptySelectedModel(seed)) {
                                      setCompactionModelProvider(seed.provider);
                                      setCompactionModelName(seed.id);
                                    }
                                  }
                                } else {
                                  setCompactionModelProvider('');
                                  setCompactionModelName('');
                                }
                              }}
                              aria-label={tc('compactionModelLabel')}
                            />
                          </div>
                          {compactionModelOpen ? (
                            <ModelPicker
                              value={{ provider: compactionModelProvider, id: compactionModelName }}
                              onChange={(next) => {
                                setCompactionModelProvider(next.provider);
                                setCompactionModelName(next.id);
                              }}
                              disabled={modelsLoading}
                              providerLabel={tc('compactionModelProviderLabel')}
                              modelLabel={tc('compactionModelNameLabel')}
                              excludeBridgeProviders
                              costProfile="chatConversation"
                            />
                          ) : (
                            <p className="text-xs text-theme-secondary">{tc('compactionModelPlatformDefault')}</p>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* Step 3: Integration */}
            {currentStep === 3 && (
              <div className="space-y-6 animate-in fade-in-0 slide-in-from-right-4 duration-300">
                <p className="text-sm text-theme-secondary text-center">
                  Configure how external systems can interact with your agent. Both options can be enabled simultaneously.
                </p>

                {/* A run started from here has nobody in front of it: where its questions and
                    permission requests reach the person, picked like a credential. */}
                <div className="border border-theme rounded-xl overflow-hidden" data-testid="agent-channel-card">
                  <button
                    type="button"
                    disabled={!channelAvailable}
                    aria-pressed={channelOn}
                    data-testid="agent-channel-toggle"
                    onClick={() => {
                      const next = !chatChannelEnabled;
                      setChatChannelEnabled(next);
                      // Asking permission only exists with a channel to ask on.
                      if (!next) setRequireToolAuthorization(false);
                    }}
                    className={`w-full flex items-center justify-between p-4 transition-colors ${channelOn ? 'bg-[var(--accent-primary)]/10' : 'hover:bg-theme-secondary'} disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-transparent`}
                  >
                    <div className="flex items-center gap-3">
                      <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${channelOn ? 'bg-[var(--accent-primary)]' : 'bg-theme-tertiary'}`}>
                        <MessageCircle className={`h-5 w-5 ${channelOn ? 'text-[var(--bg-primary)]' : 'text-theme-secondary'}`} />
                      </div>
                      <div className="text-left">
                        <div className="text-sm font-medium text-theme-primary">{t('channelCardTitle')}</div>
                        <div className="text-xs text-theme-secondary">
                          {channelsLoading ? t('channelCardLoading')
                            : channelsError ? t('channelCardError')
                            : channelAvailable ? t('channelCardDescription') : t('channelCardUnavailable')}
                        </div>
                      </div>
                    </div>
                    <Switch checked={channelOn} presentational />
                  </button>
                  {channelsKnown && !channelAvailable && (
                    <div className="px-4 pb-4 space-y-2">
                      {/* A new tab: leaving would drop what is typed in this modal. */}
                      <a
                        href={`/${getClientLocale()}/app/settings/channels`}
                        target="_blank"
                        rel="noreferrer"
                        className="text-sm font-medium text-[var(--accent-primary)] underline underline-offset-2"
                        data-testid="agent-channel-connect"
                      >
                        {t('channelCardConnect')}
                      </a>
                      {requireToolAuthorization && (
                        // Armed with nowhere to ask: every unattended sensitive action is refused,
                        // and nothing else on screen would say so.
                        <div className="flex flex-wrap items-center gap-2 text-sm text-theme-secondary" data-testid="agent-armed-without-channel">
                          <span>{t('armedWithoutChannel')}</span>
                          <button
                            type="button"
                            onClick={() => setRequireToolAuthorization(false)}
                            className="text-sm font-medium underline underline-offset-2 text-theme-primary"
                          >
                            {t('armedWithoutChannelDisarm')}
                          </button>
                        </div>
                      )}
                    </div>
                  )}
                  {channelOn && (
                    <div className="p-4 pt-3 space-y-3 border-t border-theme">
                      <div className="space-y-1">
                        <span className="text-sm font-medium text-theme-primary">{t('channelDestinationLabel')}</span>
                        <ChannelDestinationPicker
                          value={chatChannelLinkId}
                          onChange={(value) => setChatChannelLinkId(value)}
                          ariaLabel={t('channelDestinationLabel')}
                        />
                        <p className="text-xs text-theme-secondary">{t('channelDestinationHint')}</p>
                      </div>
                      {/* Sensitive actions (installs, runs, sub-agents, catalog calls): only offered
                          here, where the request has somewhere to reach the person. */}
                      <button
                        type="button"
                        onClick={() => setRequireToolAuthorization(!requireToolAuthorization)}
                        aria-pressed={requireToolAuthorization}
                        data-testid="sensitive-actions-toggle"
                        className="flex h-auto min-h-[44px] w-full items-center justify-between gap-3 rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
                      >
                        <div className="flex items-start gap-2 text-left">
                          <ShieldCheck className="mt-0.5 w-4 h-4 text-theme-secondary shrink-0" />
                          <div>
                            <div className="text-sm text-theme-primary">{t('sensitiveActionsAskLabel')}</div>
                            <div className="text-xs text-theme-secondary">{t('sensitiveActionsAskHint')}</div>
                          </div>
                        </div>
                        <Switch checked={requireToolAuthorization} presentational />
                      </button>
                    </div>
                  )}
                </div>

                {/* Webhook Integration - created on save */}
                <div className="border border-theme rounded-xl overflow-hidden">
                  <button
                    type="button"
                    onClick={handleWebhookToggle}
                    className={`w-full flex items-center justify-between p-4 transition-colors ${webhookEnabled ? 'bg-[var(--accent-primary)]/10' : 'hover:bg-theme-secondary'}`}
                  >
                    <div className="flex items-center gap-3">
                      <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${webhookEnabled ? 'bg-[var(--accent-primary)]' : 'bg-theme-tertiary'}`}>
                        <Webhook className={`h-5 w-5 ${webhookEnabled ? 'text-[var(--bg-primary)]' : 'text-theme-secondary'}`} />
                      </div>
                      <div className="text-left">
                        <div className="text-sm font-medium text-theme-primary">Webhook</div>
                        <div className="text-xs text-theme-secondary">{t('webhookLinkDescription')}</div>
                      </div>
                    </div>
                    <Switch checked={webhookEnabled} presentational />
                  </button>

                  {webhookEnabled && (
                    <div className="p-4 pt-0 space-y-3 border-t border-theme">
                      {/* Default config */}
                      <div className="flex items-center gap-4 mt-4 text-xs text-theme-secondary">
                        <div className="flex items-center gap-1.5">
                          <span className="text-theme-tertiary">Method:</span>
                          <span className="px-1.5 py-0.5 bg-[var(--bg-tertiary)] rounded font-mono font-medium text-theme-primary">POST</span>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <span className="text-theme-tertiary">Auth:</span>
                          <span className="px-1.5 py-0.5 bg-[var(--bg-tertiary)] rounded font-mono font-medium text-theme-primary">none</span>
                        </div>
                      </div>

                      {/* Conversation Memory toggle - same style as schedule */}
                      <div className="flex items-center justify-between p-3 bg-[var(--bg-secondary)] rounded-lg">
                        <div>
                          <div className="text-sm font-medium text-theme-primary">{t('scheduleWithMemory')}</div>
                          <div className="text-xs text-theme-secondary">{t('scheduleWithMemoryHelp')}</div>
                        </div>
                        <Switch checked={webhookMemory} onCheckedChange={(v) => setWebhookMemory(v)} aria-label={t('scheduleWithMemory')} />
                      </div>

                      {webhookData ? (
                        <>
                          {/* Webhook URL */}
                          <div className="bg-[var(--bg-secondary)] rounded-lg p-3">
                            <label className="block text-sm font-medium text-theme-primary mb-2">{t('webhookUrlLabel')}</label>
                            <div className="flex items-center gap-2">
                              <code className="flex-1 text-xs bg-[var(--bg-tertiary)] px-3 py-2 rounded border border-theme overflow-x-auto">{webhookData.webhookUrl}</code>
                              <button type="button" onClick={() => navigator.clipboard.writeText(webhookData.webhookUrl)} className="p-2 text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-tertiary)] rounded transition-colors">
                                <Copy className="h-4 w-4" />
                              </button>
                            </div>
                          </div>

                          {/* Curl example */}
                          <div className="bg-[var(--bg-secondary)] rounded-lg p-3">
                            <label className="block text-sm font-medium text-theme-primary mb-2">Quick start</label>
                            <div className="relative">
                              <pre className="text-xs bg-[var(--bg-tertiary)] px-3 py-2 rounded border border-theme overflow-x-auto whitespace-pre-wrap break-all font-mono text-theme-secondary">{`curl -X POST ${webhookData.webhookUrl} \\
  -H "Content-Type: application/json" \\
  -d '{"message": "Hello from webhook"}'`}</pre>
                              <button
                                type="button"
                                onClick={() => navigator.clipboard.writeText(`curl -X POST ${webhookData.webhookUrl} -H "Content-Type: application/json" -d '{"message": "Hello from webhook"}'`)}
                                className="absolute top-1.5 right-1.5 p-1 text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-secondary)] rounded transition-colors"
                              >
                                <Copy className="h-3 w-3" />
                              </button>
                            </div>
                          </div>
                        </>
                      ) : (
                        <div className="flex items-center gap-2 p-3 bg-[var(--bg-secondary)] rounded-lg">
                          <Zap className="h-3.5 w-3.5 text-[var(--accent-primary)]" />
                          <span className="text-xs text-theme-secondary">A webhook URL will be generated when you save.</span>
                        </div>
                      )}
                    </div>
                  )}
                </div>

                {/* Schedule Integration */}
                <div className="border border-theme rounded-xl overflow-hidden">
                  <button
                    type="button"
                    onClick={() => setScheduleEnabled(prev => !prev)}
                    className={`w-full flex items-center justify-between p-4 transition-colors ${scheduleEnabled ? 'bg-[var(--accent-primary)]/10' : 'hover:bg-theme-secondary'}`}
                  >
                    <div className="flex items-center gap-3">
                      <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${scheduleEnabled ? 'bg-[var(--accent-primary)]' : 'bg-theme-tertiary'}`}>
                        <Clock className={`h-5 w-5 ${scheduleEnabled ? 'text-[var(--bg-primary)]' : 'text-theme-secondary'}`} />
                      </div>
                      <div className="text-left">
                        <div className="text-sm font-medium text-theme-primary">{t('scheduleLabel')}</div>
                        <div className="text-xs text-theme-secondary">{t('scheduleDescription')}</div>
                      </div>
                    </div>
                    <Switch checked={scheduleEnabled} presentational />
                  </button>

                  {scheduleEnabled && (
                    <div className="p-4 pt-0 space-y-4 border-t border-theme">
                      {/* Plan limit warning */}
                      {scheduleLimitReached && !scheduleData && (
                        <div className="flex items-center gap-2 p-3 mt-4 bg-amber-500/10 border border-amber-500/30 rounded-lg">
                          <Info className="h-3.5 w-3.5 text-amber-500 shrink-0" />
                          <span className="text-xs text-amber-600 dark:text-amber-400">{t('scheduleLimitReached')}</span>
                        </div>
                      )}

                      {/* Scheduled Task prompt */}
                      <div className="mt-4">
                        <label className="block text-sm font-medium text-theme-primary mb-2">{t('scheduleTaskLabel')}</label>
                        <textarea
                          value={schedulePrompt}
                          onChange={(e) => setSchedulePrompt(e.target.value)}
                          placeholder={t('scheduleTaskPlaceholder')}
                          rows={3}
                          className="w-full text-sm rounded-lg border border-theme bg-[var(--bg-secondary)] text-theme-primary px-3 py-2 placeholder:text-theme-tertiary resize-none"
                        />
                        <p className="text-xs text-theme-secondary mt-1">{t('scheduleTaskHelp')}</p>
                        {/* Next to the empty field, where the decision is being made. The
                            condition it names is real and checkable by the reader: an agent
                            that is sent tasks runs them on a blank instruction, and one that
                            is not does nothing at all. */}
                        {scheduleHasNoPrompt && (
                          <p className="text-xs text-amber-600 dark:text-amber-400 mt-1">
                            {t('scheduleTaskEmptyWarning')}
                          </p>
                        )}
                      </div>

                      {/* Frequency */}
                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">{t('scheduleFrequencyLabel')}</label>
                        <Select
                          value={schedulePreset}
                          onValueChange={(value) => {
                            setSchedulePreset(value);
                            const preset = SCHEDULE_PRESETS.find(p => p.value === value);
                            if (preset && preset.cron) {
                              setScheduleCron(preset.cron);
                            }
                          }}
                        >
                          <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                          <SelectContent>
                            {SCHEDULE_PRESETS.map(preset => (
                              <SelectItem key={preset.value} value={preset.value}>{preset.label}</SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>

                      {/* Custom cron input */}
                      {schedulePreset === 'custom' && (
                        <div>
                          <label className="block text-sm font-medium text-theme-primary mb-2">{t('scheduleCustomCron')}</label>
                          <Input
                            type="text"
                            value={scheduleCron}
                            onChange={(e) => setScheduleCron(e.target.value)}
                            placeholder="* * * * *"
                            className="w-full font-mono"
                          />
                          {/* The seed's own description, and ONLY while the expression is
                              still the one it described. A cron the user has since edited is
                              a different schedule, and leaving the old sentence under it
                              would be the panel confidently reading out the wrong one. */}
                          {seededSchedule?.description && scheduleCron === seededSchedule.cron && (
                            <p className="text-xs text-theme-secondary mt-1">{seededSchedule.description}</p>
                          )}
                        </div>
                      )}

                      {/* Conversation Memory toggle */}
                      <div className="flex items-center justify-between p-3 bg-[var(--bg-secondary)] rounded-lg">
                        <div>
                          <div className="text-sm font-medium text-theme-primary">{t('scheduleWithMemory')}</div>
                          <div className="text-xs text-theme-secondary">{t('scheduleWithMemoryHelp')}</div>
                        </div>
                        <Switch checked={scheduleWithMemory} onCheckedChange={(v) => setScheduleWithMemory(v)} aria-label={t('scheduleWithMemory')} />
                      </div>

                      {/* Advanced Options (collapsible) */}
                      <div>
                        <button
                          type="button"
                          onClick={() => setScheduleAdvancedOpen(prev => !prev)}
                          className="flex items-center gap-1.5 text-xs text-theme-secondary hover:text-theme-primary transition-colors"
                        >
                          {scheduleAdvancedOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
                          <span>Advanced Options</span>
                        </button>

                        {scheduleAdvancedOpen && (
                          <div className="mt-3 space-y-4">
                            <div className="grid grid-cols-2 gap-4">
                              <div>
                                <label className="block text-sm font-medium text-theme-primary mb-2">{t('scheduleTimezone')}</label>
                                <Select value={scheduleTimezone} onValueChange={setScheduleTimezone}>
                                  <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                                  <SelectContent>
                                    {timezoneOptionsFor(scheduleTimezone).map(tz => (
                                      <SelectItem key={tz} value={tz}>{tz}</SelectItem>
                                    ))}
                                  </SelectContent>
                                </Select>
                              </div>
                              <div>
                                <label className="block text-sm font-medium text-theme-primary mb-2">{t('scheduleMaxExecutions')}</label>
                                <Input
                                  type="number"
                                  min={1}
                                  value={scheduleMaxExecutions ?? ''}
                                  onChange={(e) => setScheduleMaxExecutions(e.target.value ? parseInt(e.target.value) : null)}
                                  placeholder={t('scheduleMaxExecutionsPlaceholder')}
                                  className="w-full"
                                />
                              </div>
                            </div>

                            {/* Shared backlog participation (V340) - opt-in. Lives here in the
                                schedule's Advanced Options (not a standalone card): the flag
                                governs autonomous backlog pickup, which is most relevant for a
                                scheduled agent. Help text sits in an info tooltip (same pattern
                                as the Configuration step) rather than a sub-line. */}
                            <div className="flex items-center justify-between p-3 bg-[var(--bg-secondary)] rounded-lg">
                              <div className="flex items-center gap-1.5">
                                <span className="text-sm font-medium text-theme-primary">{t('backlogEnabled')}</span>
                                <InfoPopover label={t('backlogEnabled')}>{t('backlogEnabledHelp')}</InfoPopover>
                              </div>
                              <Switch checked={backlogEnabled} onCheckedChange={(v) => setBacklogEnabled(v)} aria-label={t('backlogEnabled')} />
                            </div>
                          </div>
                        )}
                      </div>

                      {/* Execution stats (edit mode with existing schedule) */}
                      {scheduleData && (
                        <div className="bg-[var(--bg-secondary)] rounded-lg p-3 space-y-2">
                          <div className="flex items-center justify-between text-xs">
                            <span className="text-theme-secondary">{t('scheduleExecutions')}</span>
                            <span className="text-theme-primary font-medium">{scheduleData.executionCount}</span>
                          </div>
                          {scheduleData.nextExecutionAt && (
                            <div className="flex items-center justify-between text-xs">
                              <span className="text-theme-secondary">{t('scheduleNextRun')}</span>
                              <span className="text-theme-primary font-medium">
                                {formatUtcDateTime(scheduleData.nextExecutionAt)}
                              </span>
                            </div>
                          )}
                          {scheduleData.lastExecutionAt && (
                            <div className="flex items-center justify-between text-xs">
                              <span className="text-theme-secondary">{t('scheduleLastRun')}</span>
                              <span className="text-theme-primary font-medium">
                                {formatUtcDateTime(scheduleData.lastExecutionAt)}
                              </span>
                            </div>
                          )}
                        </div>
                      )}

                      {/* Info message for new schedules */}
                      {!scheduleData && !scheduleLimitReached && (
                        <div className="flex items-center gap-2 p-3 bg-[var(--bg-secondary)] rounded-lg">
                          <Zap className="h-3.5 w-3.5 text-[var(--accent-primary)]" />
                          <span className="text-xs text-theme-secondary">{t('scheduleWillBeCreated')}</span>
                        </div>
                      )}
                    </div>
                  )}
                </div>

                {/* Widget Integration */}
                <div className="border border-theme rounded-xl overflow-hidden">
                  <button
                    type="button"
                    onClick={() => updateWidgetConfig('enabled', !widgetConfig.enabled)}
                    className={`w-full flex items-center justify-between p-4 transition-colors ${widgetConfig.enabled ? 'bg-[var(--accent-primary)]/10' : 'hover:bg-theme-secondary'}`}
                  >
                    <div className="flex items-center gap-3">
                      <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${widgetConfig.enabled ? 'bg-[var(--accent-primary)]' : 'bg-theme-tertiary'}`}>
                        <MessageCircle className={`h-5 w-5 ${widgetConfig.enabled ? 'text-[var(--bg-primary)]' : 'text-theme-secondary'}`} />
                      </div>
                      <div className="text-left">
                        <div className="text-sm font-medium text-theme-primary">Chat Widget</div>
                        <div className="text-xs text-theme-secondary">Embed a chat interface on your website</div>
                      </div>
                    </div>
                    <Switch checked={widgetConfig.enabled} presentational />
                  </button>

                  {widgetConfig.enabled && (
                    <div className="p-4 pt-0 space-y-4 border-t border-theme">
                      {/* Widget Preview */}
                      <div className="pt-4">
                        <label className="block text-sm font-medium text-theme-primary mb-2">Preview</label>
                        <WidgetPreview config={widgetConfig} agentName={name} avatarUrl={avatarUrl} />
                      </div>

                      {/* Appearance */}
                      <div className="grid grid-cols-2 gap-4">
                        <div>
                          <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            <Palette className="h-3.5 w-3.5" />
                            Position
                          </label>
                          <Select value={widgetConfig.position} onValueChange={(value: typeof WIDGET_POSITIONS[number]['value']) => updateWidgetConfig('position', value)}>
                            <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                            <SelectContent>
                              {WIDGET_POSITIONS.map(pos => (
                                <SelectItem key={pos.value} value={pos.value}>{pos.label}</SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                        <div>
                          <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            <Globe className="h-3.5 w-3.5" />
                            Theme
                          </label>
                          <Select value={widgetConfig.theme} onValueChange={(value: typeof WIDGET_THEMES[number]['value']) => updateWidgetConfig('theme', value)}>
                            <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                            <SelectContent>
                              {WIDGET_THEMES.map(theme => (
                                <SelectItem key={theme.value} value={theme.value}>{theme.label}</SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                      </div>

                      {/* Primary Color */}
                      <div>
                        <label className="block text-sm font-medium text-theme-primary mb-2">Primary Color</label>
                        <div className="flex items-center gap-2">
                          <div className="flex gap-1.5">
                            {PRESET_COLORS.map(color => (
                              <button
                                key={color}
                                type="button"
                                onClick={() => updateWidgetConfig('primaryColor', color)}
                                className={`w-7 h-7 rounded-xl transition-transform ${widgetConfig.primaryColor === color ? 'ring-2 ring-offset-2 ring-[var(--accent-primary)] scale-110' : 'hover:scale-105'}`}
                                style={{ backgroundColor: color }}
                              />
                            ))}
                          </div>
                          <Input
                            type="text"
                            value={widgetConfig.primaryColor}
                            onChange={(e) => updateWidgetConfig('primaryColor', e.target.value)}
                            className="w-24 text-xs"
                            placeholder="#000000"
                          />
                        </div>
                      </div>

                      {/* Messages */}
                      <div className="grid grid-cols-2 gap-4">
                        <div>
                          <label className="block text-sm font-medium text-theme-primary mb-2">Welcome Message</label>
                          <Input
                            type="text"
                            value={widgetConfig.welcomeMessage}
                            onChange={(e) => updateWidgetConfig('welcomeMessage', e.target.value)}
                            placeholder="Hello! How can I help?"
                            className="w-full"
                          />
                        </div>
                        <div>
                          <label className="block text-sm font-medium text-theme-primary mb-2">Bubble Text</label>
                          <Input
                            type="text"
                            value={widgetConfig.bubbleText}
                            onChange={(e) => updateWidgetConfig('bubbleText', e.target.value)}
                            placeholder="Chat with us"
                            className="w-full"
                          />
                        </div>
                      </div>

                      {/* Options */}
                      <div className="grid grid-cols-2 gap-4">
                        <div>
                          <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                            <Clock className="h-3.5 w-3.5" />
                            Auto-open Delay
                          </label>
                          <Select value={String(widgetConfig.autoOpenDelay)} onValueChange={(value) => updateWidgetConfig('autoOpenDelay', parseInt(value))}>
                            <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                            <SelectContent>
                              {AUTO_OPEN_DELAYS.map(delay => (
                                <SelectItem key={delay.value} value={String(delay.value)}>{delay.label}</SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                        <div className="flex flex-col gap-3 pt-6">
                          <label className="flex items-center gap-2 cursor-pointer">
                            <input
                              type="checkbox"
                              checked={widgetConfig.showAvatar}
                              onChange={(e) => updateWidgetConfig('showAvatar', e.target.checked)}
                              className="w-4 h-4 rounded border-gray-300"
                            />
                            <span className="text-sm text-theme-primary">Show avatar</span>
                          </label>
                        </div>
                      </div>

                      {/* Allowed Origins */}
                      <div>
                        <label className="text-sm font-medium text-theme-primary mb-1 block">
                          Allowed Origins
                        </label>
                        <textarea
                          value={widgetConfig.allowedOrigins || ''}
                          onChange={(e) => updateWidgetConfig('allowedOrigins', e.target.value)}
                          placeholder="https://example.com, https://app.example.com"
                          rows={2}
                          className="w-full text-sm rounded-lg border border-theme bg-[var(--bg-secondary)] text-theme-primary px-3 py-2 placeholder:text-theme-tertiary resize-none"
                        />
                        <p className="text-xs text-theme-secondary mt-1">
                          Comma-separated list of domains allowed to embed this widget. Leave empty to allow all domains.
                        </p>
                      </div>

                      {/* Widget Token (read-only, shown only in edit mode) */}
                      {isEditMode && widgetConfig.widgetToken && (
                        <div>
                          <label className="text-sm font-medium text-theme-primary mb-1 block">
                            Widget Token
                          </label>
                          <div className="flex items-center gap-2">
                            <input
                              type="text"
                              value={widgetConfig.widgetToken}
                              readOnly
                              className="flex-1 h-9 text-sm rounded-lg border border-theme bg-[var(--bg-tertiary)] text-theme-secondary px-3 font-mono"
                            />
                            <button
                              type="button"
                              onClick={() => navigator.clipboard.writeText(widgetConfig.widgetToken || '')}
                              className="p-2 text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-secondary)] rounded-lg transition-colors"
                              title="Copy token"
                            >
                              <Copy className="h-4 w-4" />
                            </button>
                          </div>
                          <p className="text-xs text-theme-secondary mt-1">
                            This token identifies your widget. It is automatically generated when the widget is created.
                          </p>
                        </div>
                      )}

                      {/* Embed Code */}
                      <div>
                        <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                          <Code className="h-3.5 w-3.5" />
                          Embed Code
                        </label>
                        <div className="relative">
                          <pre className="text-xs bg-[var(--bg-tertiary)] px-4 py-3 rounded-lg border border-theme overflow-x-auto whitespace-pre-wrap">
                            {generateEmbedCode()}
                          </pre>
                          <button
                            type="button"
                            onClick={() => navigator.clipboard.writeText(generateEmbedCode())}
                            className="absolute top-2 right-2 p-1.5 text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-secondary)] rounded transition-colors"
                          >
                            <Copy className="h-4 w-4" />
                          </button>
                        </div>
                        <p className="text-xs text-theme-secondary mt-2">
                          Add this script to your website&apos;s HTML to display the chat widget.
                        </p>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="px-8 py-4 border-t border-theme flex justify-between">
            <div className="flex gap-2">
              <Button
                variant="ghost"
                onClick={prevStep}
                disabled={currentStep === 1 || isCreating || isDeleting}
              >
                <ArrowLeft className="h-4 w-4 mr-2" />
                Back
              </Button>
              {/* Only an existing agent can be deleted, and only by a member who may mutate. */}
              {isEditMode && canMutate && (
                <Button
                  variant="ghost"
                  onClick={() => setShowDeleteConfirm(true)}
                  disabled={isCreating || isDeleting}
                  className="text-red-600 hover:text-red-700 hover:bg-red-50 dark:text-red-400 dark:hover:text-red-300 dark:hover:bg-red-950/40"
                >
                  <Trash2 className="h-4 w-4 mr-2" />
                  {t('deleteAgent')}
                </Button>
              )}
            </div>

            <div className="flex gap-2">
              <Button variant="outline" onClick={onClose} disabled={isCreating || isDeleting}>
                Cancel
              </Button>
              {currentStep < TOTAL_STEPS ? (
                <Button onClick={nextStep} disabled={!canProceedFromStep(currentStep) || isDeleting}>
                  Next
                  <ArrowRight className="h-4 w-4 ml-2" />
                </Button>
              ) : (
                <Button onClick={handleSave} disabled={!name.trim() || isCreating || isDeleting || !canMutate}>
                  {isCreating ? (
                    <>
                      <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                      Saving...
                    </>
                  ) : (
                    <>
                      <Check className="h-4 w-4 mr-2" />
                      {isEditMode ? 'Update Agent' : 'Create Agent'}
                    </>
                  )}
                </Button>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Delete confirmation - only reachable while editing an existing agent. */}
      {isEditMode && agent?.id && (
        <ConfirmDeleteModal
          isOpen={showDeleteConfirm}
          title={t('deleteTitle')}
          message={t('deleteConfirmation', { name: agent.name || '' })}
          isLoading={isDeleting}
          onConfirm={handleDelete}
          onCancel={() => { if (!isDeleting) setShowDeleteConfirm(false); }}
        />
      )}

      {/* Toast notifications */}
      <div className="fixed top-4 right-4 z-[10000] space-y-2">
        {toasts.map((toast) => (
          <Toast
            key={toast.id}
            id={toast.id}
            type={toast.type}
            title={toast.title}
            message={toast.message}
            duration={toast.duration}
            onClose={removeToast}
          />
        ))}
      </div>
    </>
  );

  return createPortal(modalContent, document.body);
};
