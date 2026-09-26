// @vitest-environment jsdom
/**
 * The canvas settings panel holds two kinds of settings, and this suite pins which is which.
 *
 * LAYOUT DIRECTION belongs to the WORKFLOW: it is saved into the plan, and every viewer (the
 * marketplace included) reads it back from there. The select therefore changes the canvas it
 * sits on and nothing else: not the account default (Settings > Preferences), and not any
 * other canvas on screen. It used to write the account default too, which re-oriented every
 * other workflow that falls back on it.
 *
 * INSPECTOR OPEN MODE is the new one: how much of a node a click opens, its settings alone
 * or the full view with input and output. Same contract, so it is proven the same way.
 *
 * Both are rendered next to a probe consumer under ONE real provider, so the linkage is
 * end to end: the panel writes, the probe (standing in for Settings > Preferences, which
 * reads the same context) sees it, and the per-workspace localStorage key is the one the
 * account preference reads back.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('reactflow', () => ({
  Panel: ({ children }: any) => <div>{children}</div>,
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isPreviewOnly: mockPreviewOnly.value }),
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children }: any) => (
    <select value={value ?? ''} onChange={(e) => onValueChange?.(e.target.value)}>
      {children}
    </select>
  ),
  SelectTrigger: ({ children, ...rest }: any) => <span {...rest}>{children}</span>,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  // The dock control is only offered where there is a panel to dock into. A truthy value
  // is all the panel asks for.
  useSidePanelSafe: () => mockSidePanel.value,
}));
vi.mock('../ConnectionTypeSelector', () => ({ ConnectionTypeSelector: () => null }));
vi.mock('../WorkflowPlanGenerator', () => ({ WorkflowPlanGenerator: () => null }));
const mockLayoutConfigForDirection = vi.fn((_direction: string) => ({}));
vi.mock('../../services/LayoutService', () => ({
  applyDagreLayout: (n: unknown) => n,
  layoutConfigForDirection: (direction: string) => mockLayoutConfigForDirection(direction),
}));

const mockPreviewOnly = { value: false };
const mockSidePanel = { value: {} as unknown };

import { CanvasSettingsPanel } from '../CanvasSettingsPanel';
import {
  WorkflowCanvasDirectionScope,
  WorkflowLayoutDirectionProvider,
  useWorkflowLayoutDirection,
} from '@/contexts/WorkflowLayoutDirectionContext';
import {
  InspectorOpenModeProvider,
  useInspectorOpenMode,
} from '@/contexts/InspectorOpenModeContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

/**
 * Stands in for the Settings > Preferences selects: same contexts, other surface.
 *
 * Reads `defaultDirection`, exactly as that page does - the STORED default rather than the
 * direction the open workflow happens to render in. That distinction is load-bearing here:
 * a probe on `direction` cannot tell a persisted write from a memory-only one, because the
 * memory-only setter moves it too.
 */
function AccountPreferenceProbe() {
  const { defaultDirection } = useWorkflowLayoutDirection();
  const { openMode } = useInspectorOpenMode();
  return (
    <>
      <span data-testid="account-direction">{defaultDirection}</span>
      <span data-testid="account-open-mode">{openMode}</span>
    </>
  );
}

/** The direction of the canvas the panel sits on, read inside the same scope. */
function CanvasDirectionProbe({ testId = 'canvas-direction' }: { testId?: string }) {
  const { direction } = useWorkflowLayoutDirection();
  return <span data-testid={testId}>{direction}</span>;
}

/**
 * Mounted the way the builder mounts it: inside a canvas scope (`WorkflowBuilder` wraps
 * itself in one), with the account probe outside it, where Settings lives.
 */
function renderPanel(props: Partial<React.ComponentProps<typeof CanvasSettingsPanel>> = {}) {
  return render(
    <WorkflowLayoutDirectionProvider>
      <InspectorOpenModeProvider>
        <AccountPreferenceProbe />
        <WorkflowCanvasDirectionScope>
          <CanvasDirectionProbe />
          <CanvasSettingsPanel
            isOpen
            onClose={vi.fn()}
            isRunMode={false}
            reactFlowConnectionType="bezier"
            nodes={[]}
            edges={[]}
            {...props}
          />
        </WorkflowCanvasDirectionScope>
      </InspectorOpenModeProvider>
    </WorkflowLayoutDirectionProvider>,
  );
}

/** Each select is found by an option only it offers. */
function selectOffering(optionLabel: string): HTMLSelectElement {
  const select = screen.getByText(optionLabel).closest('select');
  if (!select) throw new Error(`no select offering ${optionLabel}`);
  return select as HTMLSelectElement;
}

const directionSelect = () => selectOffering('layoutVertical');
const openModeSelect = () => selectOffering('inspectorOpenModeAdvanced');

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
  mockPreviewOnly.value = false;
  mockSidePanel.value = {};
});

afterEach(() => cleanup());

describe('CanvasSettingsPanel - layout direction belongs to the workflow', () => {
  it('regression: changes this canvas only, never the account default', () => {
    // The toggle used to write the account default as well, so choosing how ONE workflow
    // reads re-oriented every other workflow that falls back on the default.
    renderPanel();

    fireEvent.change(directionSelect(), { target: { value: 'vertical' } });

    expect(screen.getByTestId('canvas-direction')).toHaveProperty('textContent', 'vertical');
    expect(screen.getByTestId('account-direction')).toHaveProperty('textContent', 'horizontal');
    expect(window.localStorage.getItem('lc.workflow.layoutDirection:personal')).toBeNull();
  });

  it('leaves another canvas on screen alone (a sub-workflow in the side panel)', () => {
    render(
      <WorkflowLayoutDirectionProvider>
        <InspectorOpenModeProvider>
          <WorkflowCanvasDirectionScope>
            <CanvasDirectionProbe testId="other-canvas" />
          </WorkflowCanvasDirectionScope>
          <WorkflowCanvasDirectionScope>
            <CanvasSettingsPanel
              isOpen
              onClose={vi.fn()}
              isRunMode={false}
              reactFlowConnectionType="bezier"
              nodes={[]}
              edges={[]}
            />
          </WorkflowCanvasDirectionScope>
        </InspectorOpenModeProvider>
      </WorkflowLayoutDirectionProvider>,
    );

    fireEvent.change(directionSelect(), { target: { value: 'vertical' } });

    expect(screen.getByTestId('other-canvas')).toHaveProperty('textContent', 'horizontal');
  });

  it('re-lays the graph out in the new direction, in the same change', () => {
    // Positions only mean something in the direction they were computed in, so the
    // direction and the new positions land together (one undo step, one Save).
    const onForceNodesUpdate = vi.fn();
    renderPanel({ nodes: [{ id: 'a', position: { x: 0, y: 0 }, data: {} }] as any, onForceNodesUpdate });

    fireEvent.change(directionSelect(), { target: { value: 'vertical' } });

    expect(onForceNodesUpdate).toHaveBeenCalledTimes(1);
    expect(mockLayoutConfigForDirection).toHaveBeenLastCalledWith('vertical');
  });

  it('does nothing when the direction does not change', () => {
    // Re-selecting the value already shown must not re-flow a graph the user has
    // hand-placed.
    const onForceNodesUpdate = vi.fn();
    renderPanel({ nodes: [{ id: 'a', position: { x: 0, y: 0 }, data: {} }] as any, onForceNodesUpdate });

    fireEvent.change(directionSelect(), { target: { value: 'horizontal' } });

    expect(onForceNodesUpdate, 'a graph moved for a direction that did not change')
      .not.toHaveBeenCalled();
    expect(screen.getByTestId('canvas-direction')).toHaveProperty('textContent', 'horizontal');
  });

  it('starts from the account default for a canvas whose plan has not said otherwise', () => {
    window.localStorage.setItem('lc.workflow.layoutDirection:personal', 'vertical');

    renderPanel();

    expect(directionSelect().value).toBe('vertical');
  });
});

describe('CanvasSettingsPanel - what a node click opens', () => {
  it('opens on the simple default', () => {
    renderPanel();

    expect(openModeSelect().value).toBe('simple');
    expect(screen.getByTestId('account-open-mode')).toHaveProperty('textContent', 'simple');
  });

  it('writes the SAME preference the account settings read', () => {
    renderPanel();

    fireEvent.change(openModeSelect(), { target: { value: 'advanced' } });

    expect(screen.getByTestId('account-open-mode')).toHaveProperty('textContent', 'advanced');
    expect(window.localStorage.getItem('lc.workflow.inspectorOpenMode:personal')).toBe('advanced');
  });

  it('shows the choice already made in the account settings', () => {
    window.localStorage.setItem('lc.workflow.inspectorOpenMode:personal', 'advanced');

    renderPanel();

    expect(openModeSelect().value).toBe('advanced');
  });

  it('scopes the choice to the active workspace', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    renderPanel();

    fireEvent.change(openModeSelect(), { target: { value: 'advanced' } });

    expect(window.localStorage.getItem('lc.workflow.inspectorOpenMode:org-a')).toBe('advanced');
    expect(window.localStorage.getItem('lc.workflow.inspectorOpenMode:personal')).toBeNull();
  });

  it('is hidden in the read-only preview, whose nodes are not configurable', () => {
    mockPreviewOnly.value = true;

    renderPanel();

    expect(screen.queryByText('inspectorOpenModeAdvanced')).toBeNull();
  });
});
