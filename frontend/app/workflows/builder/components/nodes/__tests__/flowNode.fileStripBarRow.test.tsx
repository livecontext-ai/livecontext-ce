// @vitest-environment jsdom
/**
 * Integration: the canvas file strip and the bottom bar never share a row.
 *
 * The run-time strip (FileNodePreview -> FileResultStrip) sits at the bar's own
 * spot, calc(100% + 8px), so the node hugs its file with no gap. Two things must
 * happen together for that to be safe, and they live in two different files:
 *   - useNodeContextualButtons drops the bar's "Files" button (the strip's pill
 *     carries the same openFilesPanel action - the duplicate is what we remove);
 *   - FlowNode lowers whatever the bar still has to show (agent, sub-workflow,
 *     play) by a row via extraOffset, so those buttons stay clickable instead
 *     of landing on top of the strip.
 * Both are keyed off the SAME flag, `currentFile`, which FileNodePreview only
 * publishes while its strip is on screen. The halves are pinned individually in
 * useNodeContextualButtons.test.tsx / fileNodePreview.strip.test.tsx; THIS suite
 * is the only place they are checked against each other on a real FlowNode.
 *
 * The node here is deliberately an agent-flavored media node: a plain file node
 * in run mode ends up with an EMPTY bar once Files is gone (NodeBottomBar renders
 * nothing at all - the ideal case, and nothing to assert an offset on), so the
 * interesting case is the one where the bar survives the strip.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockExec: any;
let mockMode: any;
/** What the stubbed FileNodePreview publishes: a file (strip up) or null. */
let publishedFile: Record<string, unknown> | null;

const execStatus = (over: Record<string, any> = {}) => ({
  isStepByStepMode: false,
  isReady: false,
  isReadyRaw: false,
  canExecute: false,
  isExecuting: false,
  isRerunning: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: true,
  canRerun: false,
  executeStep: vi.fn(),
  rerunStep: vi.fn(),
  fireFromAnyEpoch: vi.fn(),
  ...over,
});

const bottomBarProps: any[] = [];
vi.mock('../NodeBottomBar', () => ({
  BTN_CLS: 'btn-cls-stub',
  ShimmerOverlay: () => null,
  NodeBottomBar: (props: any) => {
    bottomBarProps.push(props);
    return <div data-testid="bottom-bar" />;
  },
}));

// Stands in for the real strip: publishes the file exactly like FileNodePreview
// does while its strip renders (that contract is pinned in fileNodePreview.strip).
vi.mock('../FileNodePreview', () => ({
  FileNodePreview: ({ setCurrentFile }: { setCurrentFile: (f: unknown) => void }) => {
    React.useEffect(() => {
      setCurrentFile(publishedFile);
    }, [setCurrentFile]);
    return publishedFile ? <div data-testid="file-strip" /> : null;
  },
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('../../../contexts/StepByStepContext', () => ({
  useNodeExecutionStatus: () => mockExec,
}));
vi.mock('../../../contexts/ValidationContext', () => ({
  useValidation: () => ({ hasNodeErrors: () => false }),
}));
vi.mock('../../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../../NodePlayButton', () => ({
  NodePlayButton: () => null,
  deriveNodeStatus: () => undefined,
}));
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useNodes: () => [],
  useEdges: () => [],
  useReactFlow: () => ({ getNodes: () => [], getEdges: () => [] }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

// useNodeContextualButtons is the REAL hook here - it is half of what this suite
// verifies. Only its heavy side-panel payloads are stubbed.
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ openTab: vi.fn(), updateTab: vi.fn(), setActiveTab: vi.fn(), open: vi.fn(), tabs: [] }),
}));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONVERSATION_TAB: 'conversation',
  AGENT_CONFIGURATION_TAB: 'configuration',
}));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({ openFilesPanel: vi.fn() }));

vi.mock('../../../hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined, isLoading: false }),
  useInterfaceRender: () => ({ data: undefined, isLoading: false }),
}));
vi.mock('../../../hooks/useRunOutputData', () => ({
  useRunOutputData: () => ({ totalItems: 0, currentIndex: 0, currentItem: undefined, goToIndex: vi.fn(), getObjectAtPath: vi.fn() }),
}));
vi.mock('@/components/agent-fleet/hooks/useAgentActivityStream', () => ({ useAgentActivity: () => null }));
vi.mock('@/contexts/WorkflowRunContext', () => ({ useRun: () => [undefined] }));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileRefToUrl: () => '',
  normalizeFileRef: (x: any) => x,
  findFileRefs: () => [],
  isFileRef: () => false,
  fileService: { formatFileSize: () => '' },
}));
vi.mock('../../interface/InterfaceThumbnail', () => ({ InterfaceThumbnail: () => null }));
vi.mock('../FleetTriggerButtons', () => ({ FleetTriggerButtons: () => null }));
vi.mock('../TriggerNodePinButton', () => ({ TriggerNodePinButton: () => null }));
vi.mock('../TriggerEditLaunchButton', () => ({ TriggerEditLaunchButton: () => null }));
vi.mock('../ResizableNodeWrapper', () => ({ ResizableNodeWrapper: () => null }));
vi.mock('../shared/BrowserLiveCdpPanel', () => ({ AgentBrowsePanelContent: () => null }));

import { FlowNode } from '../FlowNode';

beforeEach(() => {
  bottomBarProps.length = 0;
  publishedFile = null;
  mockMode = {
    isRunMode: true,
    isPreviewOnly: false,
    runId: 'run-1',
    viewingEpoch: null,
    setViewingEpoch: vi.fn(),
    workflowId: 'wf-1',
  };
  mockExec = execStatus();
});

// kind 'media' -> static file-producing (so it renders a strip and qualifies for
// the Files button); label 'Agent media' -> agent buttons, which are the run-mode
// bar content the strip has to make room for.
const mediaAgentNode = () => ({
  id: 'media',
  label: 'Agent media',
  kind: 'media',
  status: 'completed',
  agentConfigId: 'cfg-1',
  agentConfigName: 'Helper',
});

const renderNode = () =>
  render(<FlowNode data={mediaAgentNode() as any} selected={false} id="rf-1" {...({} as any)} />);

const lastBar = () => bottomBarProps[bottomBarProps.length - 1];
const barKeys = () => (lastBar().buttons ?? []).map((b: any) => b.key);

describe('FlowNode: file strip vs bottom bar row', () => {
  it('strip up: the bar drops its Files button AND steps down a row, so nothing lands on the strip', () => {
    publishedFile = { path: 'p/out.mp4', id: 'f1', name: 'out.mp4', mimeType: 'video/mp4', size: 10 };
    const c = renderNode();

    expect(c.queryByTestId('file-strip')).not.toBeNull();
    expect(barKeys()).not.toContain('files');
    expect(lastBar().extraOffset).toBe(true);
    // The buttons the strip cannot stand in for are still there, just lower.
    expect(barKeys()).toEqual(['agent-config', 'agent-conv']);
  });

  it('no strip: the bar keeps its Files button and its normal row - no strip means no reason to leave the space empty', () => {
    publishedFile = null;
    const c = renderNode();

    expect(c.queryByTestId('file-strip')).toBeNull();
    expect(barKeys()).toContain('files');
    expect(lastBar().extraOffset).toBe(false);
  });

  it('the two are driven by ONE flag: the offset and the Files button always flip together', () => {
    publishedFile = { path: 'p/out.mp4', id: 'f1', name: 'out.mp4', mimeType: 'video/mp4', size: 10 };
    renderNode();
    const withStrip = { files: barKeys().includes('files'), offset: lastBar().extraOffset };

    bottomBarProps.length = 0;
    publishedFile = null;
    renderNode();
    const withoutStrip = { files: barKeys().includes('files'), offset: lastBar().extraOffset };

    // Never "button AND strip on the same row", never "no button AND no strip".
    expect(withStrip).toEqual({ files: false, offset: true });
    expect(withoutStrip).toEqual({ files: true, offset: false });
  });
});
