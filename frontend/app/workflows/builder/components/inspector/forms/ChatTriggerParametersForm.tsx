'use client';

import * as React from 'react';
import * as ReactDOM from 'react-dom';
import { Info, X, Copy, Check, AlertCircle, ExternalLink } from 'lucide-react';
import type { Node } from 'reactflow';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import { FieldInfoTooltip } from './shared/FieldInfoTooltip';
import { usePopoverPosition } from '../../../hooks/ui/usePopoverPosition';
import { chatEndpointSettingsService } from '@/lib/api/orchestrator';
import type { StandaloneChatEndpoint } from '@/lib/api/orchestrator';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { buildStandaloneSourceNodeId } from '../../../utils/standaloneSourceNodeId';
import Link from 'next/link';

const MATCH_TYPES = [
  { value: 'any', label: 'Any Message', description: 'Trigger on any chat message' },
  { value: 'startsWith', label: 'Starts With', description: 'Message starts with specific text (prefix is removed from extracted message)' },
  { value: 'endsWith', label: 'Ends With', description: 'Message ends with specific text (suffix is removed from extracted message)' },
  { value: 'contains', label: 'Contains', description: 'Message contains specific text' },
  { value: 'equals', label: 'Equals', description: 'Message exactly matches the specified text' },
  { value: 'regex', label: 'Regex Pattern', description: 'Match using regular expression pattern' },
  { value: 'command', label: 'Command', description: 'Message starts with / (slash command)' },
];

interface ChatTriggerData {
  matchType: string;
  pattern: string;
  caseSensitive: boolean;
  commandPrefix?: string;
}

// Module-level guard: prevent duplicate creation across remounts for the same node
const pendingOrCreatedChatEndpoints = new Map<string, string>();

interface ChatTriggerParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  /** Trigger ID for multi-DAG support (e.g., "trigger:my_chat") */
  triggerId?: string | null;
}

export function ChatTriggerParametersForm({
  node,
  data,
  isRunMode: _isRunModeProp = false,
  onUpdate,
  triggerId,
}: ChatTriggerParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const { isRunMode: isRunModeContext } = useWorkflowMode();
  const isRunMode = isRunModeContext;

  const [copied, setCopied] = React.useState(false);

  // Auto-created standalone chat endpoint data from node
  const standaloneChatEndpointId = (data as any).standaloneChatEndpointId as string | undefined;
  const standaloneChatUrl = (data as any).standaloneChatUrl as string | undefined;

  // Refs to avoid stale closures in async effects
  const dataRef = React.useRef(data);
  dataRef.current = data;
  const onUpdateRef = React.useRef(onUpdate);
  onUpdateRef.current = onUpdate;

  // All available chat endpoints for the selector
  const [allEndpoints, setAllEndpoints] = React.useState<StandaloneChatEndpoint[]>([]);
  const [listLoaded, setListLoaded] = React.useState(false);
  const [isLoadingList, setIsLoadingList] = React.useState(true);

  // Currently selected/fetched endpoint details
  const [standaloneEndpoint, setStandaloneEndpoint] = React.useState<StandaloneChatEndpoint | null>(null);
  const [isLoadingEndpoint, setIsLoadingEndpoint] = React.useState(false);
  const isCreatingRef = React.useRef(false);
  const [autoCreateFailed, setAutoCreateFailed] = React.useState(false);
  const [isLimitReached, setIsLimitReached] = React.useState(false);

  // Fetch all chat endpoints for the selector
  React.useEffect(() => {
    setIsLoadingList(true);
    chatEndpointSettingsService.getAll()
      .then((endpoints) => {
        setAllEndpoints(endpoints);
        setListLoaded(true);
      })
      .catch(() => {
        setAllEndpoints([]);
        setListLoaded(true);
      })
      .finally(() => setIsLoadingList(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Fetch current endpoint details when node already has one
  React.useEffect(() => {
    if (standaloneChatEndpointId) {
      setIsLoadingEndpoint(true);
      chatEndpointSettingsService.getById(standaloneChatEndpointId)
        .then(setStandaloneEndpoint)
        .catch(() => setStandaloneEndpoint(null))
        .finally(() => setIsLoadingEndpoint(false));
    }
  }, [standaloneChatEndpointId]);

  // Auto-create chat endpoint if node has none (waits for list to load for unique name)
  React.useEffect(() => {
    if (!listLoaded || standaloneChatEndpointId || isRunMode || isCreatingRef.current) return;
    const nodeDataId = node.id;
    // Module-level dedup guard
    const existingId = pendingOrCreatedChatEndpoints.get(nodeDataId);
    if (existingId) {
      if (existingId !== '__pending__') {
        onUpdateRef.current({ ...dataRef.current, standaloneChatEndpointId: existingId } as BuilderNodeData);
      }
      return;
    }
    pendingOrCreatedChatEndpoints.set(nodeDataId, '__pending__');
    isCreatingRef.current = true;
    setIsLoadingEndpoint(true);
    setAutoCreateFailed(false);
    const endpointNumber = allEndpoints.length + 1;
    const sourceNodeId = buildStandaloneSourceNodeId('chat', nodeDataId);
    chatEndpointSettingsService.create({ name: `Chat #${endpointNumber}`, sourceNodeId })
      .then((endpoint) => {
        pendingOrCreatedChatEndpoints.set(nodeDataId, endpoint.id);
        setStandaloneEndpoint(endpoint);
        setAllEndpoints((prev) => [...prev, endpoint]);
        // Use refs to get the latest data/onUpdate (avoids stale closures)
        onUpdateRef.current({
          ...dataRef.current,
          standaloneChatEndpointId: endpoint.id,
          standaloneChatUrl: endpoint.chatUrl,
          standaloneChatToken: endpoint.token,
        } as BuilderNodeData);
      })
      .catch((err: any) => {
        const msg = err?.message || '';
        if (msg.toLowerCase().includes('limit')) {
          setIsLimitReached(true);
        }
        pendingOrCreatedChatEndpoints.delete(nodeDataId);
        setAutoCreateFailed(true);
      })
      .finally(() => setIsLoadingEndpoint(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listLoaded, standaloneChatEndpointId, isRunMode]);

  // Determine effective endpoint ID and URL (from node data or local state fallback)
  const effectiveEndpointId = standaloneChatEndpointId || standaloneEndpoint?.id;
  const effectiveChatUrl = standaloneChatUrl || standaloneEndpoint?.chatUrl || '';

  const handleCopyUrl = React.useCallback(() => {
    if (!effectiveChatUrl) return;
    navigator.clipboard.writeText(effectiveChatUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }, [effectiveChatUrl]);

  const chatTriggerData: ChatTriggerData = React.useMemo(() => {
    const existing = (data as any).chatTriggerData as ChatTriggerData | undefined;
    return existing || {
      matchType: 'any',
      pattern: '',
      caseSensitive: false,
      commandPrefix: '/',
    };
  }, [(data as any).chatTriggerData]);

  const showPatternField = ['regex', 'startsWith', 'endsWith', 'contains', 'equals'].includes(chatTriggerData.matchType);
  const isCommandType = chatTriggerData.matchType === 'command';
  const isRegexType = chatTriggerData.matchType === 'regex';

  const handleMatchTypeChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      chatTriggerData: {
        ...chatTriggerData,
        matchType: value,
        // Reset pattern when switching to 'any'
        pattern: value === 'any' ? '' : chatTriggerData.pattern,
      },
    } as BuilderNodeData);
  }, [data, chatTriggerData, isRunMode, onUpdate]);

  const handlePatternChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      chatTriggerData: {
        ...chatTriggerData,
        pattern: value,
      },
    } as BuilderNodeData);
  }, [data, chatTriggerData, isRunMode, onUpdate]);

  const handleCaseSensitiveChange = React.useCallback((value: boolean) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      chatTriggerData: {
        ...chatTriggerData,
        caseSensitive: value,
      },
    } as BuilderNodeData);
  }, [data, chatTriggerData, isRunMode, onUpdate]);

  const handleCommandPrefixChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      chatTriggerData: {
        ...chatTriggerData,
        commandPrefix: value,
      },
    } as BuilderNodeData);
  }, [data, chatTriggerData, isRunMode, onUpdate]);

  const [isInfoOpen, setIsInfoOpen] = React.useState(false);
  const { buttonRef: infoButtonRef, popoverPosition } = usePopoverPosition(isInfoOpen, 280);

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chat.configuration')}</span>
        <div className="relative inline-flex">
          <button
            ref={infoButtonRef}
            onClick={(e) => {
              e.stopPropagation();
              setIsInfoOpen(!isInfoOpen);
            }}
            className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            title={t('chat.moreInfo')}
          >
            <Info className="h-3 w-3 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300" />
          </button>
          {isInfoOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
            <>
              <div
                className="fixed inset-0 z-[9998]"
                onClick={() => setIsInfoOpen(false)}
              />
              <div
                className="fixed z-[9999] w-72 p-3 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 shadow-lg"
                style={{ top: popoverPosition.top, left: popoverPosition.left }}
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <span className="font-medium text-sm text-slate-700 dark:text-slate-200">
                    {t('chat.title')}
                  </span>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setIsInfoOpen(false);
                    }}
                    className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
                  >
                    <X className="h-3.5 w-3.5 text-slate-400" />
                  </button>
                </div>
                <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mb-2">
                  {t('chat.description')}
                </p>
                <div className="border-t border-slate-200 dark:border-slate-700 pt-2">
                  <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">{t('chat.availableOutputs')}</p>
                  <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-0.5 font-mono">
                    <li>• message.text</li>
                    <li>• message.timestamp</li>
                    <li>• message.args (for commands)</li>
                  </ul>
                </div>
              </div>
            </>,
            document.body
          )}
        </div>
      </div>

      {/* Loading state while creating or fetching endpoint */}
      {(isLoadingEndpoint || isLoadingList) && !effectiveChatUrl && (
        <div className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-blue-500" />
          <span className="text-sm text-slate-500">{t('chat.loadingEndpoint')}</span>
        </div>
      )}

      {/* Auto-create failed - limit reached or other error */}
      {autoCreateFailed && !effectiveEndpointId && !isLoadingEndpoint && (
        <div className="flex items-start gap-2 p-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-lg">
          <AlertCircle className="h-4 w-4 text-amber-500 flex-shrink-0 mt-0.5" />
          <div className="text-sm text-amber-700 dark:text-amber-300 space-y-1.5">
            {isLimitReached ? (
              <>
                <p>{t('chat.limitReached')}</p>
                <p>{t('chat.limitReachedHint')}</p>
              </>
            ) : (
              <p>{t('chat.createFailed')}</p>
            )}
            <Link
              href="/app/settings/public-access?tab=chat"
              className="inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:underline"
            >
              <ExternalLink className="h-3 w-3" />
              {t('chat.manageEndpoints')}
            </Link>
          </div>
        </div>
      )}

      {/* Chat Endpoint URL Display */}
      {effectiveEndpointId && effectiveChatUrl && (
        <div className="space-y-2">
          <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chat.chatUrl')}</label>
          <div className="flex items-center gap-2">
            <div className="flex-1 relative">
              <Input
                value={effectiveChatUrl}
                readOnly
                className="pr-10 font-mono text-xs bg-slate-50 dark:bg-slate-900"
              />
              <Button
                variant="ghost"
                size="sm"
                className="absolute right-1 top-1/2 -translate-y-1/2 h-6 px-2"
                onClick={handleCopyUrl}
                title={t('chat.copyUrl')}
              >
                {copied ? (
                  <Check className="h-3.5 w-3.5 text-green-500" />
                ) : (
                  <Copy className="h-3.5 w-3.5" />
                )}
              </Button>
            </div>
          </div>
          <div className="pt-1">
            <Link
              href="/app/settings/public-access?tab=chat"
              className="inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:underline"
            >
              <ExternalLink className="h-3 w-3" />
              {t('chat.manageInSettings')}
            </Link>
          </div>
        </div>
      )}

      {/* Match Type */}
      <div className="space-y-2">
        <div className="flex items-center gap-1.5">
          <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chat.matchType')}</label>
          <FieldInfoTooltip description={MATCH_TYPES.find(m => m.value === chatTriggerData.matchType)?.description || ''} />
        </div>
        <Select
          value={chatTriggerData.matchType}
          onValueChange={handleMatchTypeChange}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder={t('chat.selectMatchType')} />
          </SelectTrigger>
          <SelectContent>
            {MATCH_TYPES.map((matchType) => (
              <SelectItem key={matchType.value} value={matchType.value} description={matchType.description}>
                {matchType.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Pattern Field - Only for match types that require a value */}
      {showPatternField && (
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1.5">
              <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {isRegexType ? t('chat.patternRegex') : t('chat.text')}
              </label>
              <FieldInfoTooltip
                description={
                  isRegexType
                    ? 'Regular expression to match messages'
                    : chatTriggerData.matchType === 'startsWith'
                      ? 'Text that the message must start with (prefix is trimmed from extracted message)'
                      : chatTriggerData.matchType === 'endsWith'
                        ? 'Text that the message must end with (suffix is trimmed from extracted message)'
                        : chatTriggerData.matchType === 'equals'
                          ? 'Text that the message must exactly match'
                          : 'Text that must be present in the message'
                }
              />
            </div>
            {!isRegexType && (
              <label className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                <input
                  type="checkbox"
                  checked={chatTriggerData.caseSensitive}
                  onChange={(e) => handleCaseSensitiveChange(e.target.checked)}
                  disabled={isRunMode}
                  className="rounded border-slate-300"
                />
                {t('chat.caseSensitive')}
              </label>
            )}
          </div>
          {isRegexType ? (
            <Textarea
              value={chatTriggerData.pattern}
              onChange={(e) => handlePatternChange(e.target.value)}
              placeholder="^hello.*world$"
              className="w-full font-mono text-sm"
              rows={2}
              readOnly={isRunMode}
            />
          ) : (
            <Input
              value={chatTriggerData.pattern}
              onChange={(e) => handlePatternChange(e.target.value)}
              placeholder={
                chatTriggerData.matchType === 'startsWith'
                  ? '/hello'
                  : chatTriggerData.matchType === 'endsWith'
                    ? '?'
                    : chatTriggerData.matchType === 'equals'
                      ? 'exact text'
                      : 'search term'
              }
              className="w-full"
              readOnly={isRunMode}
            />
          )}
        </div>
      )}

      {/* Command Prefix - Only for command type */}
      {isCommandType && (
        <div className="space-y-2">
          <div className="flex items-center gap-1.5">
            <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('chat.commandName')}</label>
            <FieldInfoTooltip description="The command name after the slash (e.g., /help, /start)" />
          </div>
          <div className="flex items-center gap-2">
            <span className="text-sm text-slate-500 dark:text-slate-400 font-mono">/</span>
            <Input
              value={chatTriggerData.pattern}
              onChange={(e) => handlePatternChange(e.target.value)}
              placeholder="help, start, run..."
              className="w-full font-mono"
              readOnly={isRunMode}
            />
          </div>
        </div>
      )}
    </div>
  );
}
