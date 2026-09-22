// @vitest-environment jsdom
/**
 * Reaching a step's run data from the header.
 *
 * The Edit / Run data switcher is the only control that says "show me what this
 * step ran with rather than how it is configured". It used to render only when
 * the panel was wide (`isAdvanced || isFullscreen`), which quietly coupled it to
 * a gate about PICKERS: a node pinned compact showed its configuration form and
 * offered no way back to its run data, so a reader looking for a resolved value
 * saw the expression that produced it and concluded the value was missing.
 *
 * A compact panel has the same run to explain as a wide one. It does not have
 * the same WIDTH, though: at 300px, the icon, the toolbar and a two-segment
 * control leave the node's name a few dozen pixels, and the name is what tells
 * the reader which node they are looking at. So the switcher is inline beside
 * the title on a wide panel and on its own row underneath on a compact one.
 */
import { describe, it, expect, vi } from 'vitest';
import * as React from 'react';
import { fireEvent, render } from '@testing-library/react';
import type { Node } from 'reactflow';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined }),
  Link: ({ children }: { children?: React.ReactNode }) => <span>{children}</span>,
}));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/components/ui/LoadingSpinner', () => ({ LoadingSpinner: () => <span>spinner</span> }));

import { InspectorPanelHeader } from '../InspectorPanelHeader';
import type { BuilderNodeData } from '../../../types';

// The node the report came in on: the onboarding template's transform, whose
// `data.id` is `core-1` and which was therefore pinned compact.
const node = {
  id: 'n1', type: 'flowNode', position: { x: 0, y: 0 },
  data: { id: 'core-1', label: 'Build greeting', kind: 'transform' },
} as Node<BuilderNodeData>;

function renderHeader(over: {
  isAdvanced?: boolean;
  isFullscreen?: boolean;
  isRunMode?: boolean;
  canToggle?: boolean;
  isInterfaceNode?: boolean;
  isTabbedLayout?: boolean;
  showExecutionData?: boolean;
} = {}) {
  const onShowExecutionDataChange = vi.fn();
  render(
    <InspectorPanelHeader
      node={node}
      data={node.data}
      isRunMode={over.isRunMode ?? true}
      isFullscreen={over.isFullscreen ?? false}
      isAdvanced={over.isAdvanced ?? false}
      isTabbedLayout={over.isTabbedLayout ?? false}
      isTriggerNode={false}
      isInterfaceNode={over.isInterfaceNode ?? false}
      shouldForceSmallMode={false}
      isTableSelected={false}
      triggerNavigationLevel="root"
      selectedDataSourceId={null}
      dataSources={[]}
      showExecutionData={over.showExecutionData ?? false}
      onShowExecutionDataChange={onShowExecutionDataChange}
      canShowExecutionDataToggle={over.canToggle ?? true}
      stepByStepStatus={{
        isSteppedRun: false, isStepByStepMode: false, canExecute: false,
        isExecuting: false, canRerun: false, isRerunning: false,
        executeStep: vi.fn(), rerunStep: vi.fn(),
      }}
      hasGlobalValidationErrors={false}
      onUpdate={vi.fn()}
    />,
  );
  return { onShowExecutionDataChange };
}

/** ViewModeTabs stamps each segment with its id, in every variant. */
const runDataSegment = () => document.querySelector<HTMLButtonElement>('[data-tab-id="data"]');
const editSegment = () => document.querySelector<HTMLButtonElement>('[data-tab-id="edit"]');
/** The compact panel's dedicated row, absent when the switcher is inline. */
const ownRow = () => document.querySelector('[data-testid="inspector-view-switcher-row"]');

describe('the Edit / Run data switcher is reachable', () => {
  it('is offered on a COMPACT panel, which is the regression', () => {
    renderHeader({ isAdvanced: false });
    expect(runDataSegment()).toBeTruthy();
  });

  it('is still offered on a wide panel', () => {
    renderHeader({ isAdvanced: true });
    expect(runDataSegment()).toBeTruthy();
  });

  it('is offered in fullscreen', () => {
    renderHeader({ isAdvanced: false, isFullscreen: true });
    expect(runDataSegment()).toBeTruthy();
  });
});

describe('where it goes, so it never costs the node its name', () => {
  it('takes its own row on a compact panel, out of the title row', () => {
    renderHeader({ isAdvanced: false });
    const row = ownRow();
    expect(row, 'a compact header puts the switcher below the title').toBeTruthy();
    expect(row!.contains(runDataSegment()!)).toBe(true);
  });

  it('sits inline beside the title on a wide panel, where there is room', () => {
    renderHeader({ isAdvanced: true });
    expect(ownRow(), 'a wide header has no second row').toBeNull();
    expect(runDataSegment()).toBeTruthy();
  });

  it('shows labels on its own row rather than the icon-only form', () => {
    // The whole point of the second row is that it is not fighting for width.
    renderHeader({ isAdvanced: false });
    expect(runDataSegment()!.textContent).toContain('runDataMode');
    expect(editSegment()!.textContent).toContain('editMode');
  });
});

describe('it actually switches the view', () => {
  it('asks for run data when Run data is pressed', () => {
    const { onShowExecutionDataChange } = renderHeader({ showExecutionData: false });
    fireEvent.click(runDataSegment()!);
    expect(onShowExecutionDataChange).toHaveBeenCalledWith(true);
  });

  it('asks for the configuration when Edit is pressed', () => {
    const { onShowExecutionDataChange } = renderHeader({ showExecutionData: true });
    fireEvent.click(editSegment()!);
    expect(onShowExecutionDataChange).toHaveBeenCalledWith(false);
  });

  it('marks the active view, so the control reads as a state and not a pair of buttons', () => {
    renderHeader({ showExecutionData: true });
    expect(runDataSegment()!.getAttribute('aria-pressed')).toBe('true');
    expect(editSegment()!.getAttribute('aria-pressed')).toBe('false');
  });
});

describe('when it has nothing to offer, it is not there', () => {
  it('renders nothing outside run mode, where there is no run to show', () => {
    renderHeader({ isRunMode: false });
    expect(runDataSegment()).toBeNull();
    expect(editSegment()).toBeNull();
  });

  it('renders nothing when the run has no step data for this node', () => {
    // Without a Run data segment the control is one always-pressed button whose
    // click sets what is already set: chrome pretending to be a choice.
    renderHeader({ canToggle: false });
    expect(runDataSegment()).toBeNull();
    expect(editSegment()).toBeNull();
  });

  it('leaves an interface node alone, which has its own Output / Preview / Schema control', () => {
    // Deliberate and unchanged by this diff: an interface node switches views in
    // its Output column, not here. Worth naming what that costs, because the
    // registry fix widened the set of nodes it applies to from 143 to 364: that
    // column only renders on a WIDE panel, so on a compact one an interface node
    // has neither control. Pre-existing for the 143, new for the other 221.
    renderHeader({ isInterfaceNode: true, isAdvanced: false });
    expect(runDataSegment()).toBeNull();
  });

  it('defers to the tabbed layout, which renders the switcher in the content', () => {
    // Otherwise a narrow panel on a wide window shows two of them.
    renderHeader({ isTabbedLayout: true });
    expect(runDataSegment()).toBeNull();
  });
});
