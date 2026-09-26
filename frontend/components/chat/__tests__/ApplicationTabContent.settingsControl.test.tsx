/**
 * @vitest-environment jsdom
 *
 * The embedder's settings entry in the application controls.
 *
 * The application page used to float its settings cog alone in the bottom-right
 * corner. It now hands it down as `settingsControl`, and the controls toolbar (the
 * pill the central bottom button opens) carries it as its LAST control, in the
 * normal and the fullscreen layout alike. An app with nothing else in its toolbar
 * must still offer the toolbar when there is a settings entry to reach.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import * as React from 'react';

const htmlTemplateRef = vi.hoisted(() => ({ current: '<p>app</p>' }));

vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getShowcaseRender: vi.fn(), resetApplicationData: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({
  executionService: { scheduleExecuteNow: vi.fn(), triggerSpecific: vi.fn(), triggerManual: vi.fn() },
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ executionTotal: 0 }, { executeStep: vi.fn() }],
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false, isPreviewOnly: false }),
}));
vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: { htmlTemplate: htmlTemplateRef.current, items: [] },
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({ setPendingActivateTab: () => undefined }));
vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(),
    getTokenProvider: () => null, getAuthToken: async () => null,
  },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));

// Renders extraControls inline so the toolbar's contents (and their order) are assertable.
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: { extraControls?: React.ReactNode }) => (
    <div data-testid="toolbar-stub">{props.extraControls}</div>
  ),
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: () => <div data-testid="iframe-stub" />,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="loading-spinner" /> }));
vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({ TriggerPanel: () => null }));
vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({}),
}));
vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({
  SAFE_CENTERING_CSS: '', centeringCssFor: () => '',
}));
vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s), formatUtcTime: (s: string) => s,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { ApplicationTabContent } from '../ApplicationTabContent';

const baseConfig = { interfaceId: 'iface-1', label: 'tab', actionMapping: {} };
const settings = <button type="button" data-testid="settings-stub">settings</button>;

function renderApp(props: Partial<React.ComponentProps<typeof ApplicationTabContent>> = {}) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_abc"
      workflowId="wf-1"
      onAction={() => undefined}
      {...props}
    />,
  );
}

describe('ApplicationTabContent - the settings entry in the controls toolbar', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() { /* no-op */ }
      unobserve() { /* no-op */ }
      disconnect() { /* no-op */ }
    });
    htmlTemplateRef.current = '<p>app</p>';
  });
  afterEach(cleanup);

  it('renders it inside the open toolbar, as its LAST control', () => {
    const view = renderApp({ toolbarOpen: true, settingsControl: settings });

    const toolbar = view.getByTestId('toolbar-stub');
    expect(toolbar.lastElementChild?.getAttribute('data-testid')).toBe('settings-stub');
  });

  it('renders it in the FULLSCREEN toolbar too', () => {
    const view = renderApp({ toolbarOpen: true, isExpanded: true, settingsControl: settings });

    expect(view.getByTestId('toolbar-stub').contains(view.getByTestId('settings-stub'))).toBe(true);
  });

  it('keeps the central toggle for an app with nothing else in its toolbar, so settings stay reachable', () => {
    // No template, no carousel, no pagination: pre-fix no toolbar was drawn at all,
    // and the settings entry handed down had nowhere to appear.
    htmlTemplateRef.current = '';
    const view = renderApp({ settingsControl: settings });

    expect(view.queryByTestId('application-controls-toggle')).not.toBeNull();
  });

  it('draws no toolbar for such an app when there is no settings entry either', () => {
    htmlTemplateRef.current = '';
    const view = renderApp();

    expect(view.queryByTestId('application-controls-toggle')).toBeNull();
  });
});
