/**
 * @vitest-environment jsdom
 *
 * LC-003 regression (security audit). `ApplicationTabContent` overrode the interface iframe
 * sandbox with `allow-same-origin allow-scripts allow-forms`. The frame renders
 * publisher-authored HTML/JS through `srcDoc`, and a srcdoc document inherits the embedder's
 * origin, so `allow-same-origin` let the framed script read `parent.localStorage` (the OIDC user
 * object, refresh token included): account takeover from a marketplace listing or a share link.
 *
 * The assertion is on the prop this component actually hands to InterfaceIframe, so restoring
 * the flag in ApplicationTabContent.tsx fails this suite.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import * as React from 'react';

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
    data: { htmlTemplate: '<div>app</div>', items: [] },
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
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: () => <div data-testid="toolbar-stub" />,
}));

// Echo the sandbox prop the component passes: that is the thing under test.
vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: ({ sandbox }: { sandbox?: string }) => (
    <div data-testid="iframe-stub" data-sandbox={sandbox ?? '__unset__'} />
  ),
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

function sandboxOf(extraProps: Record<string, unknown> = {}): string {
  const view = render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={{ interfaceId: 'iface-1', label: 'tab', actionMapping: {} } as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
      {...extraProps}
    />,
  );
  return view.getByTestId('iframe-stub').getAttribute('data-sandbox') ?? '';
}

describe('ApplicationTabContent iframe sandbox (LC-003)', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() { /* no-op */ }
      unobserve() { /* no-op */ }
      disconnect() { /* no-op */ }
    });
  });
  afterEach(cleanup);

  it('never grants allow-same-origin to the publisher-authored frame', () => {
    expect(sandboxOf()).not.toContain('allow-same-origin');
  });

  it('grants exactly allow-scripts and allow-forms, so applications keep working', () => {
    expect(sandboxOf().split(/\s+/).filter(Boolean).sort()).toEqual(['allow-forms', 'allow-scripts']);
  });

  it('applies the same sandbox in preview mode (marketplace listings and share links)', () => {
    expect(sandboxOf({ previewMode: true })).not.toContain('allow-same-origin');
  });
});
