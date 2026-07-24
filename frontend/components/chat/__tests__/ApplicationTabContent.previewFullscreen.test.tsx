/**
 * @vitest-environment jsdom
 *
 * Pins request: the fullscreen (Maximize) toggle MUST be available on the
 * marketplace preview (previewMode=true), not just on /app/applications. The
 * toggle flows to {@link InterfaceToolbar} via its {@code onFullscreen} prop;
 * previously previewMode forced it to {@code undefined} (hiding the button).
 * We stub InterfaceToolbar to a prop-capture and assert onFullscreen is a
 * live callback in BOTH preview and non-preview mode (and still suppressed
 * only when there is no interface template to expand).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, cleanup, act } from '@testing-library/react';
import * as React from 'react';

// The preview-mode floating toolbar tracks the panel rect via a ResizeObserver
// (absent in jsdom). Stub it so the previewMode render path mounts.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver = ResizeObserverStub;

async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

const toolbarProps = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));
const renderDataRef = vi.hoisted(() => ({
  current: {
    htmlTemplate: '<div>app</div>',
    items: [{ data: { foo: 'bar' }, itemIndex: 0 }],
    pagination: { totalPages: 1 },
  } as Record<string, unknown>,
}));

vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ runStatus: 'awaiting_signal', executionTotal: 0 }, { executeStep: vi.fn() }],
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false }),
}));
vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: renderDataRef.current,
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null } }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));

// Prop-capture stub: record the props the toolbar receives on each render.
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: Record<string, unknown>) => {
    toolbarProps.current = props;
    return <div data-testid="toolbar-stub" />;
  },
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: () => <div data-testid="iframe-stub" />,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span /> }));
vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({
  TriggerPanel: () => <div data-testid="trigger-panel-stub" />,
}));
vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({ foo: 'bar' }),
}));
vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({ SAFE_CENTERING_CSS: '', centeringCssFor: () => '' }));
vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { ApplicationTabContent } from '../ApplicationTabContent';

const baseConfig = { interfaceId: 'iface-1', label: 'tab', actionMapping: {} };

// toolbarOpen forces the InterfaceToolbar to render (the default collapsed
// state renders only the grip button, no toolbar to inspect).
async function renderApp(previewMode: boolean) {
  const result = render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
      previewMode={previewMode}
      toolbarOpen
      onToolbarOpenChange={() => undefined}
    />,
  );
  await flushEffects();
  return result;
}

describe('ApplicationTabContent - fullscreen toggle availability', () => {
  beforeEach(() => {
    toolbarProps.current = null;
    renderDataRef.current = {
      htmlTemplate: '<div>app</div>',
      items: [{ data: { foo: 'bar' }, itemIndex: 0 }],
      pagination: { totalPages: 1 },
    };
  });
  afterEach(cleanup);

  it('exposes onFullscreen in PREVIEW mode (marketplace preview can go fullscreen)', async () => {
    await renderApp(true);
    expect(toolbarProps.current).not.toBeNull();
    expect(typeof toolbarProps.current?.onFullscreen).toBe('function');
  });

  it('exposes onFullscreen in non-preview mode (unchanged for /app/applications)', async () => {
    await renderApp(false);
    expect(typeof toolbarProps.current?.onFullscreen).toBe('function');
  });

  it('suppresses onFullscreen only when there is no interface template to expand', async () => {
    renderDataRef.current = { htmlTemplate: '', items: [], pagination: { totalPages: 1 } };
    // With no htmlTemplate hasActions is false, so the toolbar block does not
    // render at all - the fullscreen entry is unreachable, which is the intended
    // "nothing to expand" state. Assert the toolbar never received a callback.
    await renderApp(true);
    expect(toolbarProps.current?.onFullscreen == null || toolbarProps.current === null).toBe(true);
  });
});
