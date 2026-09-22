// @vitest-environment jsdom
/**
 * The Continue button an interface node carries on the canvas while it is parked
 * on a blocking `__continue`.
 *
 * <p>Wiring is what breaks here, not the button (that has its own test). Three
 * facts are pinned:
 *  - the button appears in BOTH renderings of this node - the compact card and
 *    the format preview - because a user hits whichever the workflow happens to
 *    use, and a control present in only one reads as a broken feature;
 *  - it is bound to the BACKEND step id and to the node's own pending
 *    INTERFACE_SIGNAL queue, not to the React Flow node id, which addresses
 *    nothing on the server;
 *  - it is refused on a read-only surface (the marketplace preview renders a
 *    publisher's frozen showcase, so Continue there would try to advance
 *    someone else's run) - the application's own Continue button is hidden
 *    there for the same reason.
 *
 * <p>Plus the layout consequence: the button shares the node's side-attachment
 * band with the spawn-item pager, so the bottom bar has to step out of the way.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

let mockMode: any;
let mockExec: any;

vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useReactFlow: () => ({ setNodes: vi.fn() }),
  useStore: (selector: (s: any) => unknown) => selector({ nodeInternals: new Map() }),
  useStoreApi: () => ({ getState: () => ({ nodeInternals: new Map() }) }),
}));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }));
vi.mock('../../interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: () => <div data-testid="thumbnail" />,
}));
vi.mock('../ResizableNodeWrapper', () => ({ ResizableNodeWrapper: () => null }));
vi.mock('../../inspector/outputs/ItemNavigator', () => ({
  ItemNavigator: () => <div data-testid="item-navigator" />,
}));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('@/contexts/WorkflowRunContext', () => ({ useRun: () => [undefined] }));
let mockRenderData: any;
vi.mock('../../../hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: { id: 'iface-1', htmlTemplate: '<h1>hi</h1>' }, isLoading: false }),
  useInterfaceRender: () => ({ refetch: vi.fn(), data: mockRenderData, isLoading: false, error: null }),
}));
vi.mock('../../../utils/interfaceHtmlUtils', () => ({
  translateWithMapping: (d: any) => d,
  mergeTriggerDataIntoResolved: (d: any) => d,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('../../../contexts/StepByStepContext', () => ({ useNodeExecutionStatus: () => mockExec }));
vi.mock('../../NodePlayButton', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../NodePlayButton')>()),
  NodePlayButton: () => null,
  deriveNodeStatus: () => undefined,
}));

// The bottom bar is where the layout consequence shows up.
const bottomBarProps: any[] = [];
vi.mock('../NodeBottomBar', () => ({
  NodeBottomBar: (props: any) => {
    bottomBarProps.push(props);
    return null;
  },
}));

// The button itself is unit-tested separately; here we only need the props it
// is handed, so the stub renders a marker and records them.
const continueProps: any[] = [];
vi.mock('../InterfaceContinueButton', () => ({
  InterfaceContinueButton: (props: any) => {
    continueProps.push(props);
    return props.awaiting ? <button data-testid="continue-marker" /> : null;
  },
}));

import { InterfacePreviewNode } from '../InterfacePreviewNode';

const data = (over: Record<string, any> = {}) => ({
  id: 'interface-iface-1',
  label: 'My UI',
  kind: 'interface',
  onNodeUpdate: vi.fn(),
  interfaceData: { interfaceId: 'iface-1', editorExpression: '<h1>hi</h1>', showPreview: true },
  ...over,
});

function renderNode(over: Record<string, any> = {}) {
  return render(
    <InterfacePreviewNode data={data(over)} selected={false} id="interface-iface-1" {...({} as any)} />,
  );
}

const lastBar = () => bottomBarProps[bottomBarProps.length - 1];
const lastContinue = () => continueProps[continueProps.length - 1];

beforeEach(() => {
  bottomBarProps.length = 0;
  continueProps.length = 0;
  mockRenderData = undefined;
  mockMode = {
    isRunMode: true,
    isPreviewOnly: false,
    isApplicationMode: false,
    runId: 'run_1',
    workflowId: 'wf_1',
    viewingEpoch: null,
  };
  mockExec = {
    isStepByStepMode: false,
    isReady: false,
    canExecute: false,
    isExecuting: false,
    isRerunning: false,
    isRunning: false,
    isFailed: false,
    isSkipped: false,
    isCompleted: false,
    isAwaitingSignal: true,
    isInteractive: true,
    canRerun: false,
    stepId: 'interface:my_ui',
    executeStep: vi.fn(),
    rerunStep: vi.fn(),
    fireFromAnyEpoch: vi.fn(),
    pendingSignals: [],
    interfaceSignals: [
      { id: 5, nodeId: 'interface:my_ui', signalType: 'INTERFACE_SIGNAL', status: 'PENDING', epoch: 2, itemId: '0' },
    ],
  };
});

describe('InterfacePreviewNode Continue button', () => {
  it('offers Continue on the format preview when the node is parked', () => {
    renderNode();
    expect(screen.getByTestId('continue-marker')).toBeTruthy();
  });

  it('offers it on the compact card too', () => {
    renderNode({ isPreviewMode: false });
    expect(screen.getByTestId('continue-marker')).toBeTruthy();
  });

  it('binds it to the backend step id and to the node INTERFACE_SIGNAL queue', () => {
    renderNode();
    expect(lastContinue()).toMatchObject({
      stepId: 'interface:my_ui',
      runId: 'run_1',
      workflowId: 'wf_1',
      awaiting: true,
    });
    expect(lastContinue().signals).toHaveLength(1);
    expect(lastContinue().signals[0].id).toBe(5);
  });

  it('does not offer it while the node is merely running', () => {
    mockExec.isAwaitingSignal = false;
    mockExec.isRunning = true;
    renderNode();
    expect(screen.queryByTestId('continue-marker')).toBeNull();
  });

  it("does not offer it on a read-only surface - that run is not the viewer's", () => {
    mockMode.isPreviewOnly = true;
    renderNode();
    expect(screen.queryByTestId('continue-marker')).toBeNull();
  });

  it('does not offer it outside run mode - the builder has no signal to resolve', () => {
    mockMode.isRunMode = false;
    renderNode();
    expect(screen.queryByTestId('continue-marker')).toBeNull();
  });

  it('does not offer it on a historical epoch, where the fire would advance another one', () => {
    // An interface fire carries no epoch: the backend resolves the node's NEWEST
    // parked signal. From a past epoch that is a success message about work the
    // user cannot see, so the control is withheld the way every other
    // run-advancing control on this canvas is.
    mockExec.isInteractive = false;
    renderNode();
    expect(screen.queryByTestId('continue-marker')).toBeNull();
  });

  it('pushes the bottom bar out while the attachment band is occupied', () => {
    renderNode();
    expect(lastBar().extraOffset, 'the Continue button sits where the bar hangs').toBe(true);
  });

  it('leaves the bottom bar in place when nothing occupies the band', () => {
    mockExec.isAwaitingSignal = false;
    renderNode();
    expect(lastBar().extraOffset).toBe(false);
  });

  it('pushes the compact card bottom bar out too - its condition changed as well', () => {
    // The compact branch used to offset on the pager alone; it now offsets on
    // whatever occupies the band, and only that branch renders on a node whose
    // preview is off.
    renderNode({ isPreviewMode: false });
    expect(lastBar().extraOffset).toBe(true);

    bottomBarProps.length = 0;
    mockExec.isAwaitingSignal = false;
    renderNode({ isPreviewMode: false });
    expect(lastBar().extraOffset).toBe(false);
  });

  it('withholds the band, not just the button, without a run id or a step id', () => {
    // Both legs gate the band and therefore the bottom bar's offset, so they
    // are checked HERE and not only inside the button.
    mockMode.runId = undefined;
    renderNode();
    expect(screen.queryByTestId('continue-marker')).toBeNull();
    expect(lastBar().extraOffset).toBe(false);

    bottomBarProps.length = 0;
    mockMode.runId = 'run_1';
    mockExec.stepId = undefined;
    renderNode();
    expect(screen.queryByTestId('continue-marker')).toBeNull();
    expect(lastBar().extraOffset).toBe(false);
  });

  it('shares one container with the spawn-item pager instead of stacking on it', () => {
    // BOTH occupants of the band at once, which is the case that motivated
    // unifying the container: a focused epoch (so the pager shows) with several
    // spawn items, on a node that is parked (so the button shows). Without the
    // render payload the pager never renders and this asserts nothing - which is
    // exactly how this test read before.
    mockMode.viewingEpoch = 2;
    mockRenderData = { htmlTemplate: '<h1>hi</h1>', pagination: { totalPages: 5 }, items: [{ itemIndex: 0 }] };
    renderNode();

    const marker = screen.getByTestId('continue-marker');
    const navigator = screen.getByTestId('item-navigator');
    expect(navigator.parentElement).toBe(marker.parentElement);
    // One absolutely-positioned slot: getSideAttachment returns a single
    // offset, so two containers would sit exactly on top of each other.
    expect(marker.parentElement?.className).toContain('absolute');
  });

  it('still shows the pager alone when the node is not parked', () => {
    mockMode.viewingEpoch = 2;
    mockExec.isAwaitingSignal = false;
    mockRenderData = { htmlTemplate: '<h1>hi</h1>', pagination: { totalPages: 5 }, items: [{ itemIndex: 0 }] };
    renderNode();
    expect(screen.getByTestId('item-navigator')).toBeTruthy();
    expect(screen.queryByTestId('continue-marker')).toBeNull();
    expect(lastBar().extraOffset, 'the pager alone still occupies the band').toBe(true);
  });
});
