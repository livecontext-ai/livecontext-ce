// @vitest-environment jsdom
/**
 * Tests for the centralized node contextual-action logic
 * ({@link deriveNodeContextFlags} + {@link useNodeContextualButtons}).
 *
 * These guard the single source of truth shared by the canvas node bottom bar
 * (FlowNode, in the StepByStepProvider) and the run-info step popover
 * (WorkflowModeToggle.StepRowActions, a sibling of that provider). The flag
 * derivation was lifted verbatim out of FlowNode, so the flag specs below also
 * pin FlowNode's long-standing behavior - any drift fails here.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { BuilderNodeData } from '@/app/workflows/builder/types';

// Side-panel + heavy panel-content modules are mocked so importing the hook is
// cheap and the structural assertions don't need a live SidePanelProvider.
let mockSidePanel: any;
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => mockSidePanel,
}));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONVERSATION_TAB: 'conversation',
  AGENT_CONFIGURATION_TAB: 'configuration',
}));
vi.mock('@/components/app/DataSourcePanelContent', () => ({
  DataSourcePanelContent: () => null,
}));
const openFilesPanelMock = vi.fn();
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({
  openFilesPanel: (...args: unknown[]) => openFilesPanelMock(...args),
}));

import { deriveNodeContextFlags, useNodeContextualButtons } from '../useNodeContextualButtons';

// Loosely typed fixture builder - node payloads in the wild carry many ad-hoc
// fields (agentConfigId, dataSourceData, workflowData…) that the strict
// BuilderNodeData shape doesn't enumerate, so fixtures use a permissive bag.
const nodeData = (over: Record<string, any>): BuilderNodeData =>
  ({ label: 'Node', ...over } as BuilderNodeData);

describe('deriveNodeContextFlags', () => {
  it('flags an AI agent node by id and resolves the play variant', () => {
    const flags = deriveNodeContextFlags(nodeData({ id: 'ai-agent', label: 'My Agent' }));
    expect(flags.isAiAgentNode).toBe(true);
    expect(flags.isTriggerNode).toBe(false);
    expect(flags.triggerVariant).toBe('play');
  });

  it('flags an AI agent node by label fallback', () => {
    const flags = deriveNodeContextFlags(nodeData({ id: 'some-node', label: 'Support Agent' }));
    expect(flags.isAiAgentNode).toBe(true);
  });

  it('flags a table (datasource) node and exposes no trigger', () => {
    const flags = deriveNodeContextFlags(nodeData({ id: 'read-rows', kind: 'read', dataSourceData: { dataSourceId: 'ds1' } }));
    expect(flags.isTableNode).toBe(true);
    expect(flags.isTriggerNode).toBe(false);
  });

  it('flags a sub-workflow node and resolves its referenced workflow', () => {
    const flags = deriveNodeContextFlags(nodeData({ id: 'sub_workflow', kind: 'sub_workflow', subWorkflowId: 'wf-42' }));
    expect(flags.isSubWorkflowNode).toBe(true);
    expect(flags.referencedWorkflowId).toBe('wf-42');
  });

  it('falls back referencedWorkflowName to the label then to "Workflow"', () => {
    expect(deriveNodeContextFlags(nodeData({ id: 'sub_workflow', kind: 'sub_workflow', label: 'Child Flow' })).referencedWorkflowName).toBe('Child Flow');
    expect(deriveNodeContextFlags(nodeData({ id: 'sub_workflow', kind: 'sub_workflow', label: undefined as any })).referencedWorkflowName).toBe('Workflow');
  });

  it.each([
    ['manual-trigger-1', 'lightning'],
    ['schedule-trigger-1', 'schedule'],
    ['chat-trigger-1', 'message'],
    ['form-trigger-1', 'form'],
    ['webhook-trigger-1', 'webhook'],
    ['error-trigger-1', 'error'],
  ])('maps trigger id %s → isTriggerNode + variant %s', (id, variant) => {
    const flags = deriveNodeContextFlags(nodeData({ id, kind: 'entry' }));
    expect(flags.isTriggerNode).toBe(true);
    expect(flags.triggerVariant).toBe(variant);
  });

  it('maps a workflows-trigger to the workflow variant and a referenced workflow', () => {
    const flags = deriveNodeContextFlags(nodeData({ id: 'workflows-trigger-1', kind: 'entry', workflowData: { workflowId: 'wf-9', workflowName: 'Upstream' } }));
    expect(flags.isWorkflowsTriggerNode).toBe(true);
    expect(flags.triggerVariant).toBe('workflow');
    expect(flags.referencedWorkflowId).toBe('wf-9');
    expect(flags.referencedWorkflowName).toBe('Upstream');
  });

  it('requires kind=entry for a tables trigger (a plain table node is not a trigger)', () => {
    const trigger = deriveNodeContextFlags(nodeData({ id: 'tables-trigger-1', kind: 'entry', dataSourceData: { dataSourceId: 'ds1' } }));
    expect(trigger.isTablesTrigger).toBe(true);
    expect(trigger.triggerVariant).toBe('table');
    const plain = deriveNodeContextFlags(nodeData({ id: 'tables-trigger-1', kind: 'read' }));
    expect(plain.isTablesTrigger).toBe(false);
    expect(plain.isTriggerNode).toBe(false);
  });

  it('treats a kind=entry node with no specific id as a generic trigger (variant "play")', () => {
    const flags = deriveNodeContextFlags(nodeData({ id: 'my-custom-entry', kind: 'entry' }));
    expect(flags.isTriggerNode).toBe(true);
    expect(flags.triggerVariant).toBe('play');
  });

  it.each(['download_file', 'convert_to_file', 'compression', 'sftp', 'media'])('flags %s as a static file-producing node', (kind) => {
    expect(deriveNodeContextFlags(nodeData({ id: kind, kind })).isStaticFileProducingNode).toBe(true);
  });

  it('flags an interface node', () => {
    expect(deriveNodeContextFlags(nodeData({ id: 'interface-1' })).isInterfaceNode).toBe(true);
  });
});

describe('useNodeContextualButtons', () => {
  beforeEach(() => {
    mockSidePanel = { openTab: vi.fn(), updateTab: vi.fn(), setActiveTab: vi.fn(), open: vi.fn(), tabs: [] };
  });

  const render = (data: BuilderNodeData, opts?: { isRunMode?: boolean; includeFiles?: boolean; currentFile?: any }) => {
    const flags = deriveNodeContextFlags(data, data.id);
    return renderHook(() =>
      useNodeContextualButtons({
        data,
        nodeUiId: data.id || 'node',
        isRunMode: opts?.isRunMode ?? false,
        flags,
        includeFiles: opts?.includeFiles,
        currentFile: opts?.currentFile,
      }),
    );
  };

  it('builds agent config + conversation buttons for an agent node', () => {
    const { result } = render(nodeData({ id: 'ai-agent', agentConfigId: 'cfg-1', agentConfigName: 'Helper' }));
    expect(result.current.map((b) => b.key)).toEqual(['agent-config', 'agent-conv']);
  });

  it('opens the agent panel on the config tab when the config button is clicked', () => {
    const { result } = render(nodeData({ id: 'ai-agent', agentConfigId: 'cfg-1', agentConfigName: 'Helper' }));
    result.current.find((b) => b.key === 'agent-config')!.onClick({ stopPropagation() {} } as any);
    expect(mockSidePanel.openTab).toHaveBeenCalledWith(expect.objectContaining({ id: 'agent-cfg-1', label: 'Helper' }));
  });

  it('builds a table-data button for a datasource node', () => {
    const { result } = render(nodeData({ id: 'read-rows', kind: 'read', dataSourceData: { dataSourceId: 'ds1', dataSourceName: 'Orders' } }));
    expect(result.current.map((b) => b.key)).toEqual(['table-data']);
  });

  it('builds a sub-workflow button that, in run mode, dispatches workflowOpenSubWorkflow', () => {
    const { result } = render(nodeData({ id: 'sub_workflow', kind: 'sub_workflow', subWorkflowId: 'wf-42', workflowData: { workflowName: 'Child' } }), { isRunMode: true });
    const btn = result.current.find((b) => b.key === 'subworkflow')!;
    expect(btn).toBeTruthy();
    const spy = vi.spyOn(window, 'dispatchEvent');
    btn.onClick({ stopPropagation() {} } as any);
    expect(spy).toHaveBeenCalled();
    const evt = spy.mock.calls[0][0] as CustomEvent;
    expect(evt.type).toBe('workflowOpenSubWorkflow');
    expect(evt.detail).toMatchObject({ workflowId: 'wf-42', nodeId: 'sub_workflow' });
    spy.mockRestore();
  });

  it('omits the files button unless includeFiles is set (run-info popover excludes it)', () => {
    const dl = nodeData({ id: 'download_file', kind: 'download_file' });
    expect(render(dl).result.current.find((b) => b.key === 'files')).toBeUndefined();
    expect(render(dl, { includeFiles: true }).result.current.find((b) => b.key === 'files')).toBeTruthy();
  });

  it('preserves button order agent → table → files → sub-workflow when includeFiles', () => {
    const data = nodeData({
      id: 'sub_workflow',
      kind: 'media',
      label: 'Agent media',
      agentConfigId: 'cfg-1',
      dataSourceData: { dataSourceId: 'ds1' },
      subWorkflowId: 'wf-1',
    });
    const { result } = render(data, { includeFiles: true });
    expect(result.current.map((b) => b.key)).toEqual(['agent-config', 'agent-conv', 'table-data', 'files', 'subworkflow']);
  });

  /**
   * The canvas file strip (FileNodePreview) carries its own open-in-side-panel
   * button and now sits exactly where the bar's Files button used to be, so the
   * two must never be on screen together. currentFile is set by FileNodePreview
   * only while its strip is rendered, which makes it the exact "strip is up" flag.
   */
  describe('files button vs the canvas file strip (never both)', () => {
    const mediaNode = () => nodeData({ id: 'media', kind: 'media' });

    it('drops the files button once the strip is up (currentFile set) - the strip owns that row and duplicates the button', () => {
      const { result } = render(mediaNode(), { includeFiles: true, currentFile: { path: 'p', id: 'f1' } });
      expect(result.current.find((b) => b.key === 'files')).toBeUndefined();
    });

    it('keeps the files button with no strip (edit mode, or a run that produced no file): nothing else opens the files there', () => {
      const { result } = render(mediaNode(), { includeFiles: true });
      expect(result.current.find((b) => b.key === 'files')).toBeTruthy();
    });

    // Behavior change, deliberate: an MCP/catalog node with a resolved FileRef used
    // to get this button (the old condition was `isStaticFileProducingNode ||
    // currentFile`). A resolved FileRef now means the strip is on screen with its
    // own panel button, so the bar button would be the duplicate we are removing.
    it('gives a non-static node no files button even with a resolved file - the strip it necessarily has already carries that action', () => {
      const { result } = render(nodeData({ id: 'http-request', kind: 'http_request' }), {
        includeFiles: true,
        currentFile: { path: 'p', id: 'f1' },
      });
      expect(result.current.find((b) => b.key === 'files')).toBeUndefined();
    });

    it('opens the Files tab with no file target: the button only survives where nothing has resolved one', () => {
      const { result } = render(mediaNode(), { includeFiles: true });
      result.current.find((b) => b.key === 'files')!.onClick({ stopPropagation() {} } as any);
      expect(openFilesPanelMock).toHaveBeenCalledWith(mockSidePanel);
    });
  });

  it('returns no buttons for a plain node', () => {
    expect(render(nodeData({ id: 'http-request', kind: 'http_request' })).result.current).toEqual([]);
  });
});
