'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import type { Node } from 'reactflow';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import type { BuilderNodeData, NodeMock } from '../../types';
import {
  ensureMockPort,
  isMcpCatalogToolNode,
  isPortSelectingNode,
  nodePortOptions,
  sanitizeNodeMock,
} from '../../utils/nodeMock';
import { useNodeDefinitions } from '../../hooks/useNodeDefinitions';
import { getCoreNodeSchema } from './SourceCoreNodeInspector';
import type { OutputSchema } from './outputs/UnifiedNodeOutput';

type MockMode = 'custom' | 'catalog_example' | 'error';

interface MockOutputSectionProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/** Placeholder value for one schema field, keyed by its declared type. */
function schemaFieldPlaceholder(field: OutputSchema): unknown {
  const type = (field.type || '').toLowerCase();
  if (field.children && field.children.length > 0) {
    const child = buildSkeletonFromSchema(field.children);
    return type === 'array' ? [child] : child;
  }
  if (type === 'number' || type === 'integer') return 0;
  if (type === 'boolean') return false;
  if (type === 'array') return [];
  if (type === 'object') return {};
  return '';
}

/** Builds an editable JSON skeleton from a node's static output schema. */
export function buildSkeletonFromSchema(schema: OutputSchema[]): Record<string, unknown> {
  const skeleton: Record<string, unknown> = {};
  for (const field of schema) {
    if (field.runtimeOnly) continue; // body-scoped, never part of the persisted output
    skeleton[field.key] = schemaFieldPlaceholder(field);
  }
  return skeleton;
}

/**
 * Picks the default example of a catalog tool's response list
 * (`toolData.responses` snapshot or a lazily fetched `/tool-responses` list).
 * Non-object examples are wrapped under `result` (a mock output is an object).
 */
function extractDefaultExample(responses: unknown): Record<string, unknown> | undefined {
  if (!Array.isArray(responses) || responses.length === 0) return undefined;
  const list = responses as Array<Record<string, unknown>>;
  const def = list.find((r) => r?.isDefault === true || r?.is_default === true) || list[0];
  const raw = def?.example_jsonb ?? (def as Record<string, unknown>)?.exampleJsonb ?? def?.example;
  if (raw == null) return undefined;
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw);
      return isPlainObject(parsed) ? parsed : { result: parsed };
    } catch {
      return undefined;
    }
  }
  return isPlainObject(raw) ? (raw as Record<string, unknown>) : { result: raw };
}

function modeOfMock(mock: NodeMock | undefined): MockMode {
  if (!mock) return 'custom';
  if (mock.error || mock.source === 'error') return 'error';
  if (mock.source === 'catalog_example') return 'catalog_example';
  return 'custom';
}

/**
 * "Mock output" block rendered INSIDE the Settings section for every
 * mock-capable node (`NodeSettingsSection` gates with `nodeSupportsMock`).
 * Same shape as the other settings rows: label + switch on the right; the
 * configuration only shows while the switch is on (run mode additionally
 * reveals a committed mock read-only, since the switch is frozen there).
 *
 * Edits the node's plan-level `mock` block. No mock = the key is absent so
 * plans stay byte-identical. The BACKEND is the single validator; the gating
 * here (catalog example on mcp tools only, port on branching nodes only)
 * mirrors its parse-time rules.
 */
export function MockOutputSection({
  node,
  data,
  onUpdate,
  isRunMode = false,
}: MockOutputSectionProps) {
  const t = useTranslations('workflowBuilder.mock');

  const mock = React.useMemo<NodeMock | undefined>(
    () => sanitizeNodeMock(data.mock),
    [data.mock]
  );
  const isConfigured = !!mock;
  const isEnabled = isConfigured && mock?.enabled !== false;
  const derivedMode = modeOfMock(mock);

  // The switch on the right of the label. ON shows the configuration; it also
  // maps to the mock's `enabled` flag once a mock is committed.
  const [isOn, setIsOn] = React.useState(isEnabled);
  // The user can switch the source select BEFORE anything is committed (custom
  // JSON commits on blur, error commits when a message exists). pendingMode
  // carries that not-yet-written choice; any external mock change resets it.
  const [pendingMode, setPendingMode] = React.useState<MockMode | null>(null);
  const mode = pendingMode ?? derivedMode;

  const mockKey = React.useMemo(() => JSON.stringify(mock ?? null), [mock]);
  React.useEffect(() => {
    setPendingMode(null);
  }, [mockKey, node.id]);

  // Reseed the switch when the node changes or the mock changes externally
  // (import, Use-as-mock, removal). A local-only ON (nothing committed yet)
  // is untouched because mockKey does not change.
  React.useEffect(() => {
    setIsOn(isEnabled);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mockKey, node.id]);

  // Run mode is read-only: the switch is frozen, but a committed mock must
  // stay inspectable even when disabled (the switch being off would
  // otherwise hide its configuration with no way to reveal it).
  const showConfig = isOn || (isRunMode && isConfigured);

  const isMcpTool = isMcpCatalogToolNode(node);
  const isPortNode = isPortSelectingNode(node);
  const portOptions = React.useMemo(
    () => (isPortNode ? nodePortOptions(node) : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [isPortNode, node.id, data.decisionConditions, data.switchCases, data.optionChoices, data.approvalOutputs, data.classifyCategories]
  );

  // ---------- Prefill (example / schema skeleton / {}) ----------

  const { getOutputSchema } = useNodeDefinitions();
  const [fetchedExample, setFetchedExample] = React.useState<Record<string, unknown> | null>(null);
  const fetchTriedRef = React.useRef(false);

  // Reset the lazy-fetch state when the selected node changes: a reused
  // component instance must re-fetch the NEW node's example, not keep the
  // previous node's (or its "already tried" flag).
  React.useEffect(() => {
    fetchTriedRef.current = false;
    setFetchedExample(null);
  }, [node.id]);

  const snapshotExample = React.useMemo(() => {
    if (!isMcpTool) return undefined;
    const responses = (data as BuilderNodeData & { toolData?: { responses?: unknown } }).toolData
      ?.responses;
    return extractDefaultExample(responses);
  }, [isMcpTool, data]);

  const prefillObject = React.useMemo<Record<string, unknown>>(() => {
    if (isMcpTool) {
      return snapshotExample ?? fetchedExample ?? {};
    }
    const schema = getCoreNodeSchema(node, getOutputSchema);
    return schema.length > 0 ? buildSkeletonFromSchema(schema) : {};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isMcpTool, snapshotExample, fetchedExample, node.id, getOutputSchema]);

  const prefillText = React.useMemo(
    () => JSON.stringify(prefillObject, null, 2),
    [prefillObject]
  );

  // Lazily fetch the tool's default example response the first time the
  // section opens on an mcp tool node whose snapshot carries no example.
  React.useEffect(() => {
    if (!showConfig || !isMcpTool || snapshotExample || fetchTriedRef.current) return;
    const toolId = (data as BuilderNodeData & { toolData?: { toolId?: string } }).toolData?.toolId;
    if (!toolId) return;
    fetchTriedRef.current = true;
    let cancelled = false;
    unifiedApiService
      .getToolResponses(toolId)
      .then((responses) => {
        if (cancelled) return;
        const example = extractDefaultExample(responses);
        if (example) setFetchedExample(example);
      })
      .catch(() => {
        // Prefill stays {} - free editing is the point, nothing to surface.
      });
    return () => {
      cancelled = true;
    };
  }, [showConfig, isMcpTool, snapshotExample, data]);

  // ---------- Local editor state ----------

  const committedOutputText = React.useMemo(
    () => (mock?.output ? JSON.stringify(mock.output, null, 2) : null),
    [mock?.output]
  );
  const [jsonText, setJsonText] = React.useState<string>(committedOutputText ?? prefillText);
  const [jsonError, setJsonError] = React.useState(false);
  const [errorMessage, setErrorMessage] = React.useState<string>(mock?.error?.message ?? '');

  // Re-seed local editors when the node changes or the committed mock changes
  // externally (import, Use-as-mock, removal).
  React.useEffect(() => {
    setJsonText(committedOutputText ?? prefillText);
    setJsonError(false);
    setErrorMessage(mock?.error?.message ?? '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [node.id, committedOutputText, prefillText]);

  // ---------- Writes ----------

  const writeMock = React.useCallback(
    (next: NodeMock | undefined) => {
      if (isRunMode) return;
      const clean = sanitizeNodeMock(next);
      if (clean) {
        onUpdate({ ...data, mock: clean });
      } else if (data.mock !== undefined) {
        const rest = { ...data };
        delete rest.mock;
        onUpdate(rest);
      }
    },
    [isRunMode, data, onUpdate]
  );

  const handleModeChange = React.useCallback(
    (value: string) => {
      const nextMode = value as MockMode;
      if (nextMode === 'catalog_example') {
        setPendingMode(null);
        writeMock({
          source: 'catalog_example',
          ...(mock?.enabled === false ? { enabled: false } : {}),
        });
        return;
      }
      // custom / error commit later (JSON blur / message input) - only flip the UI.
      setPendingMode(nextMode);
    },
    [writeMock, mock?.enabled]
  );

  const commitJson = React.useCallback(() => {
    if (isRunMode) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(jsonText);
    } catch {
      setJsonError(true);
      return;
    }
    if (!isPlainObject(parsed)) {
      setJsonError(true);
      return;
    }
    setJsonError(false);
    // ensureMockPort: a static mock on a branching node MUST carry a port
    // (backend parse rule) - default to the node's first branch when unset.
    writeMock(
      ensureMockPort(
        {
          output: parsed as Record<string, unknown>,
          ...(mock?.port ? { port: mock.port } : {}),
          ...(mock?.enabled === false ? { enabled: false } : {}),
        },
        node
      )
    );
  }, [isRunMode, jsonText, writeMock, mock?.port, mock?.enabled, node]);

  const handleResetToExample = React.useCallback(() => {
    setJsonText(prefillText);
    setJsonError(false);
  }, [prefillText]);

  const handlePortChange = React.useCallback(
    (port: string) => {
      writeMock({
        port,
        ...(mock?.output ? { output: mock.output } : {}),
        ...(mock?.enabled === false ? { enabled: false } : {}),
      });
    },
    [writeMock, mock?.output, mock?.enabled]
  );

  const commitErrorMessage = React.useCallback(() => {
    if (isRunMode) return;
    const message = errorMessage.trim();
    if (message === '') {
      // Empty message = no simulated error left to keep.
      if (derivedMode === 'error') writeMock(undefined);
      return;
    }
    writeMock({
      error: { message },
      ...(mock?.enabled === false ? { enabled: false } : {}),
    });
  }, [isRunMode, errorMessage, derivedMode, writeMock, mock?.enabled]);

  const handleMasterToggle = React.useCallback(
    (checked: boolean) => {
      if (isRunMode) return;
      setIsOn(checked);
      // Nothing committed yet: the switch only reveals the configuration.
      if (!mock) return;
      writeMock({ ...mock, enabled: checked ? undefined : false });
    },
    [isRunMode, mock, writeMock]
  );

  return (
    <div className="flex flex-col gap-1.5" data-testid="mock-output-section">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium text-slate-500 dark:text-slate-400">
          {t('title')}
        </span>
        <Switch
          checked={isOn}
          onCheckedChange={handleMasterToggle}
          disabled={isRunMode}
          aria-label={t('title')}
        />
      </div>
      <p className="text-sm text-slate-400 dark:text-slate-500">{t('toggleHelp')}</p>

      {showConfig ? (
        <div className="space-y-4 pt-1">
          {/* Source select */}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-slate-500 dark:text-slate-400">
              {t('sourceLabel')}
            </label>
            <Select value={mode} onValueChange={handleModeChange} disabled={isRunMode}>
              <SelectTrigger className="w-full" data-testid="mock-source-select" aria-label={t('sourceLabel')}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="custom">{t('sourceCustom')}</SelectItem>
                {isMcpTool ? (
                  <SelectItem value="catalog_example">{t('sourceCatalogExample')}</SelectItem>
                ) : null}
                <SelectItem value="error">{t('sourceError')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Branch to take - port-selecting nodes only */}
          {mode === 'custom' && isPortNode && portOptions.length > 0 ? (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-slate-500 dark:text-slate-400">
                {t('portLabel')}
              </label>
              <Select
                value={mock?.port ?? ''}
                onValueChange={handlePortChange}
                disabled={isRunMode}
              >
                <SelectTrigger className="w-full" data-testid="mock-port-select" aria-label={t('portLabel')}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {portOptions.map((port) => (
                    <SelectItem key={port} value={port}>
                      {port}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          ) : null}

          {/* Custom JSON editor */}
          {mode === 'custom' ? (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-slate-500 dark:text-slate-400">
                {t('customJsonLabel')}
              </label>
              <Textarea
                value={jsonText}
                onChange={(e) => {
                  setJsonText(e.target.value);
                  setJsonError(false);
                }}
                onBlur={commitJson}
                readOnly={isRunMode}
                rows={8}
                spellCheck={false}
                className="font-mono text-sm"
                aria-label={t('customJsonLabel')}
                data-testid="mock-json-textarea"
              />
              {jsonError ? (
                <p className="text-sm text-red-600 dark:text-red-400" data-testid="mock-json-error">
                  {t('invalidJson')}
                </p>
              ) : (
                <p className="text-sm text-slate-400 dark:text-slate-500">{t('customJsonHelp')}</p>
              )}
              {!isRunMode ? (
                <button
                  type="button"
                  className="self-start text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:underline"
                  onClick={handleResetToExample}
                  data-testid="mock-reset-example"
                >
                  {t('resetToExample')}
                </button>
              ) : null}
            </div>
          ) : null}

          {/* Catalog example */}
          {mode === 'catalog_example' ? (
            <div className="flex flex-col gap-1.5">
              <p className="text-sm text-slate-400 dark:text-slate-500">
                {t('catalogExampleHelp')}
              </p>
              <details>
                <summary className="text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 cursor-pointer select-none">
                  {t('previewExample')}
                </summary>
                <pre className="mt-1.5 max-h-56 overflow-auto rounded-md bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700 p-2 font-mono text-xs text-slate-600 dark:text-slate-300">
                  {prefillText}
                </pre>
                {/* The run serves the example PROJECTED through the tool's
                    output schema - the raw preview can differ slightly. */}
                <p className="mt-1 text-xs text-slate-400 dark:text-slate-500">
                  {t('previewProjectionNote')}
                </p>
              </details>
            </div>
          ) : null}

          {/* Simulated error */}
          {mode === 'error' ? (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-slate-500 dark:text-slate-400">
                {t('errorMessageLabel')}
              </label>
              <Input
                type="text"
                value={errorMessage}
                onChange={(e) => setErrorMessage(e.target.value)}
                onBlur={commitErrorMessage}
                readOnly={isRunMode}
                aria-label={t('errorMessageLabel')}
                data-testid="mock-error-message"
                className="w-full"
              />
            </div>
          ) : null}

        </div>
      ) : null}
    </div>
  );
}
