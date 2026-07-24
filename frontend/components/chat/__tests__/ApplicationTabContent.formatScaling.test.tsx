/**
 * @vitest-environment jsdom
 *
 * Pins the format scale-to-fit contract: when the INTERFACE declares a `format` (preset name or
 * "WxH"), the iframe renders inside a virtual viewport of EXACTLY width x height CSS px,
 * transform: scale()d to CONTAIN in the panel (letterboxed, centered). Without a format the
 * native w-full h-full path renders, byte-for-byte as before (no viewport wrapper).
 *
 * The format reaches the panel from the render result (a run reads its frozen snapshot), with the
 * live interface as fallback - never from the workflow node, whose param was retired.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, act } from '@testing-library/react';
import * as React from 'react';

// jsdom lacks ResizeObserver - the format letterbox observes its own box.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver = ResizeObserverStub;

// jsdom computes no layout: give every element a real box so the initial
// measurement (useLayoutEffect -> getBoundingClientRect) yields a non-zero
// letterbox and the scaled viewport mounts. 400x600 against the 1080x1920
// "vertical" preset gives scale = min(400/1080, 600/1920) = 0.3125.
// Mutable so individual tests can size the box (reset in afterEach) - the
// spy below reads BOX at call time.
const DEFAULT_BOX = { width: 400, height: 600 };
const BOX = { ...DEFAULT_BOX };
vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
  () => ({
    width: BOX.width,
    height: BOX.height,
    top: 0,
    left: 0,
    right: BOX.width,
    bottom: BOX.height,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  }) as DOMRect,
);

// Flush the passive plan-fetch effect (getWorkflow) so its trailing setState
// lands inside act() - keeps the React "not wrapped in act(...)" warning out
// of the output. The viewport assertions are synchronous on first render.
async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ runStatus: 'completed', executionTotal: 0 }, { executeStep: vi.fn() }],
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false }),
}));

// The format the INTERFACE declares, as the panel sees it: on the render payload. Mutable so
// each test can set the shape (reset in afterEach); the mock reads it at call time.
const FORMAT: { value: string | undefined } = { value: undefined };

vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: {
      htmlTemplate: '<div>app</div>',
      format: FORMAT.value,
      items: [{ data: { foo: 'bar' }, itemIndex: 0 }],
      pagination: { totalPages: 1 },
    },
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));

vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null },
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: () => <div data-testid="toolbar-stub" />,
}));

// Echo the sizing props so the test can assert the iframe is pinned to the
// exact virtual-viewport dims (format path) vs w-full h-full (native path).
vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: ({ className, style }: { className?: string; style?: React.CSSProperties }) => (
    <div data-testid="iframe-stub" data-classname={className || ''} style={style} />
  ),
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="loading-spinner" />,
}));

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

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { ApplicationTabContent } from '../ApplicationTabContent';

/**
 * @param config the app config. A `format` here is NOT passed to the component (the config has
 *   carried no format since the shape moved to the interface); it is routed into the mocked
 *   render payload, which is where the panel actually reads it from.
 */
function renderApp(config: Record<string, unknown>, extraProps: Record<string, unknown> = {}) {
  const { format, ...appConfig } = config;
  FORMAT.value = format as string | undefined;
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={appConfig as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
      {...extraProps}
    />,
  );
}

describe("ApplicationTabContent - the interface's format drives scale-to-fit", () => {
  afterEach(() => {
    cleanup();
    Object.assign(BOX, DEFAULT_BOX);
    FORMAT.value = undefined;
  });

  it('renders the virtual viewport at exactly 1080x1920 with a contain scale for format="vertical"', async () => {
    const { getByTestId } = renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: 'vertical' });

    const viewport = getByTestId('application-format-viewport');
    expect(viewport.style.width).toBe('1080px');
    expect(viewport.style.height).toBe('1920px');
    // contain scale = min(400/1080, 600/1920) = 0.3125, origin top-left.
    expect(viewport.style.transform).toBe('scale(0.3125)');
    expect(viewport.style.transformOrigin).toBe('0 0');

    // The iframe itself is pinned to the viewport dims (not w-full h-full).
    const iframe = getByTestId('iframe-stub');
    expect(iframe.style.width).toBe('1080px');
    expect(iframe.style.height).toBe('1920px');
    expect(iframe.getAttribute('data-classname')).toBe('');
    await flushEffects();
  });

  it('sizes the letterbox intermediate to the SCALED dims (centered contain box)', async () => {
    const { getByTestId } = renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: 'vertical' });

    const letterboxInner = getByTestId('application-format-viewport').parentElement as HTMLElement;
    // 1080 * 0.3125 = 337.5, 1920 * 0.3125 = 600.
    expect(letterboxInner.style.width).toBe('337.5px');
    expect(letterboxInner.style.height).toBe('600px');
    await flushEffects();
  });

  it('renders the native w-full h-full path with NO viewport wrapper when format is absent', async () => {
    const { queryByTestId, getByTestId } = renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {} });

    expect(queryByTestId('application-format-viewport')).toBeNull();
    const iframe = getByTestId('iframe-stub');
    expect(iframe.getAttribute('data-classname')).toBe('w-full h-full');
    expect(iframe.style.height).toBe('100%');
    expect(iframe.style.width).toBe('');
    await flushEffects();
  });

  it('falls back to the native path for an unknown format string', async () => {
    const { queryByTestId, getByTestId } = renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: 'not-a-format' });

    expect(queryByTestId('application-format-viewport')).toBeNull();
    expect(getByTestId('iframe-stub').getAttribute('data-classname')).toBe('w-full h-full');
    await flushEffects();
  });

  it('applies the same scaled viewport in fullscreen mode (shared iframeContent)', async () => {
    renderApp(
      { interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: 'vertical' },
      { isExpanded: true, onExpandedChange: () => undefined },
    );

    // Fullscreen portals to document.body - query globally via screen.
    const viewport = screen.getByTestId('application-format-viewport');
    expect(viewport.style.width).toBe('1080px');
    expect(viewport.style.height).toBe('1920px');
    await flushEffects();
  });

  it('never upscales: a box larger than the format viewport clamps to scale(1)', async () => {
    // 800x1200 box vs the 390x844 "mobile" preset: raw contain ratio is
    // min(800/390, 1200/844) = ~1.42 - must clamp to 1 (native size, no
    // blurry transform upscale), letterboxed at the viewport's own dims.
    Object.assign(BOX, { width: 800, height: 1200 });
    const { getByTestId } = renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: 'mobile' });

    const viewport = getByTestId('application-format-viewport');
    expect(viewport.style.width).toBe('390px');
    expect(viewport.style.height).toBe('844px');
    expect(viewport.style.transform).toBe('scale(1)');
    // The letterbox intermediate matches the UNSCALED viewport (s = 1).
    const letterboxInner = viewport.parentElement as HTMLElement;
    expect(letterboxInner.style.width).toBe('390px');
    expect(letterboxInner.style.height).toBe('844px');
    await flushEffects();
  });

  it('supports a custom "WxH" format string', async () => {
    const { getByTestId } = renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: '800x400' });

    const viewport = getByTestId('application-format-viewport');
    expect(viewport.style.width).toBe('800px');
    expect(viewport.style.height).toBe('400px');
    // contain scale = min(400/800, 600/400) = 0.5.
    expect(viewport.style.transform).toBe('scale(0.5)');
    await flushEffects();
  });
});
