/**
 * @vitest-environment jsdom
 *
 * The inspector's run actions, which had no test at all while being restructured three times:
 * play and re-run were split out of a shared step-by-step gate, a trigger exclusion was added,
 * and the re-run label was moved onto the run's persisted mode.
 *
 * The three rules pinned here:
 *  - play is for stepping only; automatic mode must not show a permanently disabled one;
 *  - re-run is offered in BOTH modes, because restarting from a node is mode-blind;
 *  - never on a trigger, where "restart from here" means the whole DAG, matching the canvas
 *    bar and the context menu.
 */
import { describe, it, expect, vi } from 'vitest';
import * as React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import type { Node } from 'reactflow';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// A child of the header reaches next-intl's navigation module, which needs a Next runtime.
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined }),
  Link: ({ children }: { children?: React.ReactNode }) => <span>{children}</span>,
}));
// `useOptionalTheme` is mocked alongside `useTheme`: the icons in this tree render
// through `useThemeSafely`, which reads the context OPTIONALLY so the same icons can
// render on the public marketplace outside any ThemeProvider. A mock missing it throws.
vi.mock('@/components/ThemeProvider', () => ({ useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/components/ui/LoadingSpinner', () => ({ LoadingSpinner: () => <span>spinner</span> }));

import { InspectorPanelHeader } from '../InspectorPanelHeader';
import type { BuilderNodeData } from '../../../types';

const node = {
  id: 'n1', type: 'flowNode', position: { x: 0, y: 0 },
  data: { id: 'n1', label: 'My Node', kind: 'action' },
} as Node<BuilderNodeData>;

const status = (over: Record<string, boolean> = {}) => ({
  isSteppedRun: false,
  isStepByStepMode: false,
  canExecute: false,
  isExecuting: false,
  canRerun: false,
  isRerunning: false,
  executeStep: vi.fn(),
  rerunStep: vi.fn(),
  ...over,
});

function renderHeader(over: {
  isRunMode?: boolean;
  isTriggerNode?: boolean;
  status?: Record<string, boolean>;
  isAdvanced?: boolean;
  onOpenLogs?: () => void;
} = {}) {
  render(
    <InspectorPanelHeader
      node={node}
      data={node.data}
      isRunMode={over.isRunMode ?? true}
      isFullscreen={false}
      isAdvanced={over.isAdvanced ?? false}
      isTriggerNode={over.isTriggerNode ?? false}
      isInterfaceNode={false}
      shouldForceSmallMode={false}
      isTableSelected={false}
      triggerNavigationLevel="root"
      selectedDataSourceId={null}
      dataSources={[]}
      showExecutionData={false}
      onShowExecutionDataChange={vi.fn()}
      canShowExecutionDataToggle={false}
      stepByStepStatus={status(over.status)}
      hasGlobalValidationErrors={false}
      onUpdate={vi.fn()}
      onOpenLogs={over.onOpenLogs}
    />,
  );
}

/** The re-run button is the only one titled with a rerun key. */
const rerunButton = () =>
  screen.queryByTitle('rerunTooltip') ?? screen.queryByTitle('rerunTooltipAuto');

describe('InspectorPanelHeader run actions', () => {
  it('offers the re-run on a settled node of an AUTOMATIC run', () => {
    // The headline case: before the gate change this surface showed nothing outside stepping.
    renderHeader({ status: { canRerun: true } });
    expect(rerunButton()).toBeTruthy();
  });

  it('warns that the rest replays on its own when the run is automatic', () => {
    renderHeader({ status: { canRerun: true, isSteppedRun: false } });
    expect(screen.queryByTitle('rerunTooltipAuto')).toBeTruthy();
    expect(screen.queryByTitle('rerunTooltip')).toBeNull();
  });

  it('keeps the plain label on a FINISHED stepped run, where a restart executes nothing', () => {
    // isStepByStepMode folds terminality in and is false here; only isSteppedRun is truthful.
    renderHeader({ status: { canRerun: true, isSteppedRun: true, isStepByStepMode: false } });
    expect(screen.queryByTitle('rerunTooltip')).toBeTruthy();
    expect(screen.queryByTitle('rerunTooltipAuto')).toBeNull();
  });

  it('shows no play button in automatic mode, rather than a dead disabled one', () => {
    renderHeader({ status: { canRerun: true } });
    expect(screen.queryByTitle('executeStep')).toBeNull();
    expect(screen.queryByTitle('waitingDependencies')).toBeNull();
  });

  it('shows the play button while the user is stepping the run', () => {
    renderHeader({ status: { isStepByStepMode: true, isSteppedRun: true, canExecute: true } });
    expect(screen.queryByTitle('executeStep')).toBeTruthy();
  });

  it('never offers a re-run on a trigger: there it is the whole DAG', () => {
    renderHeader({ isTriggerNode: true, status: { canRerun: true } });
    expect(rerunButton()).toBeNull();
  });

  it('offers no run actions outside run mode', () => {
    renderHeader({ isRunMode: false, status: { canRerun: true, isStepByStepMode: true } });
    expect(rerunButton()).toBeNull();
  });

  it('offers the side-panel Logs action without restoring the former Logs segment', () => {
    const onOpenLogs = vi.fn();
    renderHeader({ isAdvanced: true, onOpenLogs });

    fireEvent.click(screen.getByTitle('viewLogs'));

    expect(onOpenLogs).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Logs')).toBeNull();
  });
});
