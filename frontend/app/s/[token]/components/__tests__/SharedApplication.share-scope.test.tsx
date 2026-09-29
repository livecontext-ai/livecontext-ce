/**
 * @vitest-environment jsdom
 *
 * The /s/{token} application viewer runs under a share token, which may only reach the shared
 * application. It used to list the OWNER's acquired apps (/publications/acquired) to find its
 * workflow; the edge now refuses that list, so the viewer must resolve its workflow through
 * /publications/{id}/application-workflow alone, and, when that fails, say so instead of
 * falling back to the publisher's source workflow (which the share link cannot read).
 */
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import * as React from 'react';

const {
  getPublicationById, getApplicationWorkflow, getAcquiredApplications,
  getApplicationRun, getWorkflow, executeWorkflow,
} = vi.hoisted(() => ({
  getPublicationById: vi.fn(),
  getApplicationWorkflow: vi.fn(),
  getAcquiredApplications: vi.fn(),
  getApplicationRun: vi.fn(),
  getWorkflow: vi.fn(),
  executeWorkflow: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById, getApplicationWorkflow, getAcquiredApplications },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getApplicationRun, getWorkflow, executeWorkflow },
}));
vi.mock('@/components/share/ShareProviders', () => ({
  ShareProviders: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  WorkflowRunProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  SidePanelProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/StreamingContext', () => ({
  StreamingProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/app/SidePanel', () => ({ SidePanel: () => null }));
vi.mock('@/components/views/workflow/WorkflowLoadingState', () => ({
  WorkflowLoadingState: () => <div>loading</div>,
}));
vi.mock('@/components/views/application/ApplicationDetailView', () => ({
  ApplicationDetailView: ({ workflowId, runId }: { workflowId: string; runId: string }) => (
    <div data-testid="app-view">{`${workflowId}|${runId}`}</div>
  ),
}));

import SharedApplication from '../SharedApplication';

const PUB = '11111111-1111-1111-1111-111111111111';

describe('SharedApplication - share-link workflow resolution', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPublicationById.mockResolvedValue({
      id: PUB, title: 'Shared app', workflowId: 'publisher-source-wf', planSnapshot: { nodes: [] },
    });
    getApplicationRun.mockResolvedValue({ runId: 'run-1' });
  });

  it('never asks for the owner acquired-apps list and uses the application workflow', async () => {
    getApplicationWorkflow.mockResolvedValue({ workflowId: 'clone-wf' });

    render(<SharedApplication publicationId={PUB} token="sl_x" />);

    expect(await screen.findByTestId('app-view')).toHaveTextContent('clone-wf|run-1');
    expect(getApplicationWorkflow).toHaveBeenCalledWith(PUB);
    expect(getAcquiredApplications).not.toHaveBeenCalled();
  });

  it('shows the no-workflow error instead of falling back to the publisher source workflow', async () => {
    getApplicationWorkflow.mockRejectedValue(new Error('404'));

    render(<SharedApplication publicationId={PUB} token="sl_x" />);

    expect(await screen.findByText('noWorkflow')).toBeInTheDocument();
    await waitFor(() => expect(getApplicationRun).not.toHaveBeenCalled());
    expect(getWorkflow).not.toHaveBeenCalled();
    expect(getAcquiredApplications).not.toHaveBeenCalled();
  });
});
