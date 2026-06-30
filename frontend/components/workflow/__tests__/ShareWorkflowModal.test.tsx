// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  getPublicationByWorkflowId: vi.fn(),
  getWorkflowRuns: vi.fn(),
  getCategories: vi.fn(),
  listVersions: vi.fn(),
  getVersion: vi.fn(),
  publishWorkflow: vi.fn(),
  updatePublication: vi.fn(),
  unpublishWorkflow: vi.fn(),
  prePublishScan: vi.fn(),
  useInterfaceRender: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: () => {
    const labels: Record<string, string> = {
      titlePlaceholder: 'Enter a title for your workflow',
      descriptionPlaceholder: 'Describe what this workflow does...',
      visibilityLabel: 'Visibility',
      done: 'Done',
      private: 'Private',
      privateDescription: 'Only you can access this workflow',
      public: 'Public',
      publicDescription: 'Share to the marketplace for others to discover and use',
      titleLabel: 'Title',
      descriptionLabel: 'Description',
      versionLabel: 'Version',
      loadingVersions: 'Loading versions',
      versionSelect: 'Select version',
      noVersions: 'No versions',
      currentVersion: 'Current version',
      publishedVersion: 'Published version',
      publishButton: 'Publish',
      updateButton: 'Update',
      noRunsForVersion: 'No runs',
      step1Title: 'Information',
      step2Title: 'Showcase',
      step3Title: 'Visibility',
      comingSoon: 'Coming soon',
      cancel: 'Cancel',
      next: 'Next',
    };
    return (key: string) => labels[key] ?? key;
  },
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({
    isLoading: false,
    user: {
      email: 'owner@example.com',
      name: 'Owner E2E',
    },
  }),
}));

vi.mock('@/hooks/useUserProfile', () => ({
  useUserProfile: () => ({
    profile: {
      displayName: 'Owner E2E',
    },
  }),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: mocks,
}));

vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceRender: (...args: unknown[]) => mocks.useInterfaceRender(...args),
}));

vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: () => <div data-testid="showcase-preview" />,
}));

vi.mock('@/app/workflows/builder/components/inspector/forms/shared/FieldInfoTooltip', () => ({
  FieldInfoTooltip: () => null,
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="loading-spinner" />,
}));

vi.mock('@/components/marketplace/CategoryPicker', () => ({
  CategoryPicker: () => <div data-testid="category-picker" />,
}));

vi.mock('@/components/marketplace/ImageScreeningModal', () => ({
  ImageScreeningModal: () => null,
}));

vi.mock('@/lib/api/orchestrator/screening.service', () => ({
  screeningService: {
    prePublishScan: mocks.prePublishScan,
    postScreeningDecisions: vi.fn(),
  },
}));

vi.mock('@/lib/featureFlags', () => ({
  PAID_TEMPLATES_ENABLED: false,
}));

vi.mock('@/lib/format-cost', () => ({
  isCeMode: () => false,
}));

import { PublishWorkflowModal } from '../ShareWorkflowModal';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('PublishWorkflowModal', () => {
  beforeEach(() => {
    mocks.getWorkflowRuns.mockResolvedValue([]);
    mocks.getCategories.mockResolvedValue({ categories: [] });
    mocks.prePublishScan.mockResolvedValue({ clean: true, flagged: [] });
    // Default: the run has not rendered any epoch yet. Individual tests that
    // care about epoch pinning override this with real items.
    mocks.useInterfaceRender.mockReturnValue({ data: null, isLoading: false, isPlaceholderData: false });
    mocks.listVersions.mockResolvedValue({
      versions: [{ version: 1, createdAt: '2026-05-26T12:00:00Z', runCount: 0, nodeCount: 2 }],
      currentVersion: 1,
    });
    mocks.getVersion.mockResolvedValue({ plan: { interfaces: [], tables: [], triggers: [] } });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('keeps user-edited publication metadata when workflow props refresh while open', async () => {
    mocks.getPublicationByWorkflowId.mockResolvedValue(null);

    const { rerender } = render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Original workflow title"
        workflowDescription="Original workflow description"
        onClose={vi.fn()}
      />,
    );

    const titleInput = await screen.findByPlaceholderText('Enter a title for your workflow');
    await waitFor(() => {
      expect(titleInput).toHaveValue('Original workflow title');
    });

    fireEvent.change(titleInput, { target: { value: 'User typed title' } });
    expect(titleInput).toHaveValue('User typed title');

    rerender(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Refreshed workflow title"
        workflowDescription="Refreshed workflow description"
        onClose={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(titleInput).toHaveValue('User typed title');
    });
  });

  it('uses the latest workflow metadata when publication status resolves after props refresh', async () => {
    const status = deferred<null>();
    mocks.getPublicationByWorkflowId.mockReturnValue(status.promise);

    const { rerender } = render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName=""
        workflowDescription=""
        onClose={vi.fn()}
      />,
    );

    rerender(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Loaded workflow title"
        workflowDescription="Loaded workflow description"
        onClose={vi.fn()}
      />,
    );

    status.resolve(null);

    const titleInput = await screen.findByPlaceholderText('Enter a title for your workflow');
    await waitFor(() => {
      expect(titleInput).toHaveValue('Loaded workflow title');
    });
  });

  it('clears a stale epoch pin when opening another workflow without a publication', async () => {
    mocks.getPublicationByWorkflowId.mockImplementation(async (workflowId: string) => {
      if (workflowId === 'workflow-1') {
        return {
          published: true,
          id: 'publication-1',
          title: 'Existing publication',
          description: 'Existing description',
          creditsPerUse: 0,
          visibility: 'PUBLIC',
          showcaseRunId: 'run-old',
          showcaseInterfaceId: '33333333-3333-3333-3333-333333333333',
          showcaseChosenEpoch: 4,
          planVersion: 1,
        };
      }
      return null;
    });
    mocks.getWorkflowRuns.mockResolvedValue([
      {
        id: 'run-new-uuid',
        runId: 'run-new',
        status: 'COMPLETED',
        startedAt: '2026-05-26T12:00:00Z',
        planVersion: 1,
        totalNodes: 2,
        executionMode: 'automatic',
      },
    ]);
    mocks.getVersion.mockResolvedValue({
      plan: {
        interfaces: [{ id: '44444444-4444-4444-4444-444444444444', label: 'Main interface' }],
        tables: [],
        triggers: [],
      },
    });
    mocks.publishWorkflow.mockResolvedValue({ id: 'publication-2' });

    const { rerender } = render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Workflow one"
        workflowDescription="First workflow"
        onClose={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(mocks.getPublicationByWorkflowId).toHaveBeenCalledWith('workflow-1');
    });

    rerender(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-2"
        workflowName="Workflow two"
        workflowDescription="Second workflow"
        onClose={vi.fn()}
      />,
    );

    const titleInput = await screen.findByPlaceholderText('Enter a title for your workflow');
    await waitFor(() => {
      expect(titleInput).toHaveValue('Workflow two');
    });

    const nextButton = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => {
      expect(nextButton).toBeEnabled();
    });
    fireEvent.click(nextButton);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /next/i })).toBeEnabled();
    });
    fireEvent.click(screen.getByRole('button', { name: /next/i }));

    const publishButton = await screen.findByRole('button', { name: /publish/i });
    await waitFor(() => {
      expect(publishButton).toBeEnabled();
    });
    fireEvent.click(publishButton);

    await waitFor(() => {
      expect(mocks.publishWorkflow).toHaveBeenCalled();
    });
    expect(mocks.publishWorkflow.mock.calls[0][0].showcaseEpoch).toBeUndefined();
  });

  it('ignores stale publication status responses after switching workflows', async () => {
    const firstStatus = deferred<any>();
    mocks.getPublicationByWorkflowId.mockImplementation((workflowId: string) => {
      if (workflowId === 'workflow-1') {
        return firstStatus.promise;
      }
      return Promise.resolve(null);
    });

    const { rerender } = render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Workflow one"
        workflowDescription="First workflow"
        onClose={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(mocks.getPublicationByWorkflowId).toHaveBeenCalledWith('workflow-1');
    });

    rerender(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-2"
        workflowName="Workflow two"
        workflowDescription="Second workflow"
        onClose={vi.fn()}
      />,
    );

    const titleInput = await screen.findByPlaceholderText('Enter a title for your workflow');
    await waitFor(() => {
      expect(titleInput).toHaveValue('Workflow two');
    });

    firstStatus.resolve({
      published: true,
      id: 'publication-1',
      title: 'Stale publication title',
      description: 'Stale publication description',
      creditsPerUse: 0,
      visibility: 'PUBLIC',
      showcaseRunId: 'run-old',
      showcaseInterfaceId: '33333333-3333-3333-3333-333333333333',
      showcaseChosenEpoch: 4,
      planVersion: 1,
    });

    await waitFor(() => {
      expect(titleInput).toHaveValue('Workflow two');
    });
  });

  // The publish confirmation is now an in-modal banner (no takeover screen) and
  // the modal stays open. A PRIVATE share goes live instantly, so it must show
  // the "shared privately / live" copy - never the public review message.
  it('PRIVATE publish shows the "shared privately" banner in the modal, not "submitted for review"', async () => {
    const onClose = vi.fn();
    // First load: not yet published. Post-publish refetch returns the new
    // PRIVATE publication (as the real API would) so visibility stays PRIVATE
    // when the success screen renders.
    mocks.getPublicationByWorkflowId
      .mockResolvedValueOnce(null)
      .mockResolvedValue({
        published: true,
        id: 'publication-private',
        title: 'Solo workflow',
        description: 'Personal',
        creditsPerUse: 0,
        visibility: 'PRIVATE',
        showcaseRunId: 'run-1',
        showcaseInterfaceId: '44444444-4444-4444-4444-444444444444',
        planVersion: 1,
      });
    mocks.getWorkflowRuns.mockResolvedValue([
      {
        id: 'run-uuid',
        runId: 'run-1',
        status: 'COMPLETED',
        startedAt: '2026-05-26T12:00:00Z',
        planVersion: 1,
        totalNodes: 2,
        executionMode: 'automatic',
      },
    ]);
    mocks.getVersion.mockResolvedValue({
      plan: {
        interfaces: [{ id: '44444444-4444-4444-4444-444444444444', label: 'Main interface' }],
        tables: [],
        triggers: [],
      },
    });
    mocks.publishWorkflow.mockResolvedValue({ id: 'publication-private' });

    render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Solo workflow"
        workflowDescription="Personal"
        onClose={onClose}
      />,
    );

    // Visibility now lives on the LAST step. Navigate Step 1 → Step 2 → Step 3
    // (run + interface auto-selected on load), then switch to PRIVATE there.
    const next1 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next1).toBeEnabled());
    fireEvent.click(next1);
    const next2 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next2).toBeEnabled());
    fireEvent.click(next2);

    // On the final (Visibility) step, switch to PRIVATE.
    const privateToggle = await screen.findByText('Private');
    fireEvent.click(privateToggle);

    const publishButton = await screen.findByRole('button', { name: /publish/i });
    await waitFor(() => expect(publishButton).toBeEnabled());
    fireEvent.click(publishButton);

    await waitFor(() => expect(mocks.publishWorkflow).toHaveBeenCalled());
    // visibility forwarded as PRIVATE
    expect(mocks.publishWorkflow.mock.calls[0][0].visibility).toBe('PRIVATE');

    // In-modal banner shows the private (live, no-review) copy...
    await screen.findByText('publishSuccessPrivateDescription');
    // ...and NOT the public "submitted for review" description.
    expect(screen.queryByText('publishSuccessDescription')).not.toBeInTheDocument();
    // The confirmation is the in-wizard banner, NOT the old takeover screen:
    // its heading + "Done" button no longer render (these distinguish the new
    // behavior - the pre-change success screen rendered both)...
    expect(screen.queryByText('publishSuccessPrivate')).not.toBeInTheDocument();
    expect(screen.queryByText('Done')).not.toBeInTheDocument();
    // ...and the wizard chrome (step indicator) is still mounted around it.
    expect(screen.getByText('Showcase')).toBeInTheDocument();
    // The modal stays open - the publish flow no longer auto-closes it.
    expect(onClose).not.toHaveBeenCalled();
  });

  // Re-sharing an already-published PUBLIC app goes back to review (backend
  // resets it to PENDING_REVIEW), so the update confirmation must say so rather
  // than the bare "Sharing updated".
  it('PUBLIC re-share (update) shows the "resubmitted for review" message', async () => {
    const IFACE = '44444444-4444-4444-4444-444444444444';
    mocks.getPublicationByWorkflowId.mockResolvedValue({
      published: true,
      id: 'publication-1',
      title: 'Existing app',
      description: 'Existing description',
      creditsPerUse: 0,
      visibility: 'PUBLIC',
      showcaseRunId: 'run-1',
      showcaseInterfaceId: IFACE,
      planVersion: 1,
    });
    mocks.getWorkflowRuns.mockResolvedValue([
      {
        id: 'run-uuid',
        runId: 'run-1',
        status: 'COMPLETED',
        startedAt: '2026-05-26T12:00:00Z',
        planVersion: 1,
        totalNodes: 2,
        executionMode: 'automatic',
      },
    ]);
    mocks.getVersion.mockResolvedValue({
      plan: { interfaces: [{ id: IFACE, label: 'Main interface' }], tables: [], triggers: [] },
    });
    mocks.updatePublication.mockResolvedValue({ id: 'publication-1' });

    render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Existing app"
        workflowDescription="Existing description"
        onClose={vi.fn()}
      />,
    );

    // PUBLIC = 3 steps. The action button (Update/Publish) only renders on the
    // last step; steps 1-2 show "Next". Navigate Next → Next → Update.
    const next1 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next1).toBeEnabled());
    fireEvent.click(next1);
    const next2 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next2).toBeEnabled());
    fireEvent.click(next2);

    const updateButton = await screen.findByRole('button', { name: /update/i });
    await waitFor(() => expect(updateButton).toBeEnabled());
    fireEvent.click(updateButton);

    await waitFor(() => expect(mocks.updatePublication).toHaveBeenCalled());
    // The update stays in the wizard and shows the re-review banner.
    await screen.findByText('updateResubmittedForReview');
    expect(screen.queryByText('updateSuccess')).not.toBeInTheDocument();
  });

  // The showcase now always pins exactly one epoch and defaults to the latest
  // captured one (no "all epochs" view). This must reach the publish payload as
  // showcaseEpoch - the pre-change code left it null/undefined.
  it('pins the latest captured epoch by default and forwards it as showcaseEpoch', async () => {
    const IFACE = '44444444-4444-4444-4444-444444444444';
    mocks.getPublicationByWorkflowId.mockResolvedValue(null);
    mocks.getWorkflowRuns.mockResolvedValue([
      {
        id: 'run-uuid',
        runId: 'run-1',
        status: 'COMPLETED',
        startedAt: '2026-05-26T12:00:00Z',
        planVersion: 1,
        totalNodes: 2,
        executionMode: 'automatic',
      },
    ]);
    mocks.getVersion.mockResolvedValue({
      plan: { interfaces: [{ id: IFACE, label: 'Main interface' }], tables: [], triggers: [] },
    });
    // Model the real hook: it only returns rendered items once a run AND an
    // interface are actually selected (it returns nothing for the null ids the
    // wizard passes before auto-selection). This null→data transition is what
    // re-triggers the default-latest effect - a blanket mock that returns data
    // for null ids would collapse that transition and hide the wiring.
    mocks.useInterfaceRender.mockImplementation(
      (interfaceId: string | null, runId: string | null) =>
        interfaceId && runId
          ? { data: { items: [{ epoch: 0 }, { epoch: 1 }, { epoch: 2 }] }, isLoading: false, isPlaceholderData: false }
          : { data: null, isLoading: false, isPlaceholderData: false },
    );
    mocks.publishWorkflow.mockResolvedValue({ id: 'publication-1' });

    render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Epoch workflow"
        workflowDescription="desc"
        onClose={vi.fn()}
      />,
    );

    // PUBLIC = 3 steps. Navigate Next → Next → Publish (run + interface auto-selected).
    const next1 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next1).toBeEnabled());
    fireEvent.click(next1);
    const next2 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next2).toBeEnabled());
    fireEvent.click(next2);

    const publishButton = await screen.findByRole('button', { name: /publish/i });
    await waitFor(() => expect(publishButton).toBeEnabled());
    fireEvent.click(publishButton);

    await waitFor(() => expect(mocks.publishWorkflow).toHaveBeenCalled());
    // Latest of [0,1,2] is pinned by default - no "all epochs" sentinel.
    expect(mocks.publishWorkflow.mock.calls[0][0].showcaseEpoch).toBe(2);
  });

  // Sharing requires an interface (the wizard blocks Step 1 without one). When
  // the chosen version has a completed run but NO interface, "Next" stays
  // disabled - the modal must explain WHY instead of leaving the user stuck.
  it('explains why sharing is blocked when the version has a run but no interface', async () => {
    mocks.getPublicationByWorkflowId.mockResolvedValue(null);
    mocks.getWorkflowRuns.mockResolvedValue([
      {
        id: 'run-uuid',
        runId: 'run-1',
        status: 'COMPLETED',
        startedAt: '2026-05-26T12:00:00Z',
        planVersion: 1,
        totalNodes: 2,
        executionMode: 'automatic',
      },
    ]);
    // Version 1 has a completed (auto) run, but its plan has zero interfaces.
    mocks.getVersion.mockResolvedValue({ plan: { interfaces: [], tables: [], triggers: [] } });

    render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="No-interface workflow"
        workflowDescription="desc"
        onClose={vi.fn()}
      />,
    );

    // The explanation appears once the plan resolves...
    await screen.findByText('noInterfaceCannotShare');
    // ...and the user cannot advance past Step 1 (interface required to share).
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
  });

  // The publish confirmation must render INSIDE the wizard (banner) and keep the
  // modal open - a PUBLIC share enters moderation, so the banner says so.
  it('PUBLIC publish shows the in-review banner in the modal and does not auto-close', async () => {
    const onClose = vi.fn();
    const IFACE = '44444444-4444-4444-4444-444444444444';
    mocks.getPublicationByWorkflowId
      .mockResolvedValueOnce(null)
      .mockResolvedValue({
        published: true,
        id: 'publication-public',
        title: 'Shared app',
        description: 'desc',
        creditsPerUse: 0,
        visibility: 'PUBLIC',
        showcaseRunId: 'run-1',
        showcaseInterfaceId: IFACE,
        planVersion: 1,
      });
    mocks.getWorkflowRuns.mockResolvedValue([
      {
        id: 'run-uuid',
        runId: 'run-1',
        status: 'COMPLETED',
        startedAt: '2026-05-26T12:00:00Z',
        planVersion: 1,
        totalNodes: 2,
        executionMode: 'automatic',
      },
    ]);
    mocks.getVersion.mockResolvedValue({
      plan: { interfaces: [{ id: IFACE, label: 'Main interface' }], tables: [], triggers: [] },
    });
    mocks.publishWorkflow.mockResolvedValue({ id: 'publication-public' });

    render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Shared app"
        workflowDescription="desc"
        onClose={onClose}
      />,
    );

    // PUBLIC = 3 steps. Navigate Next → Next → Publish (default visibility PUBLIC).
    const next1 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next1).toBeEnabled());
    fireEvent.click(next1);
    const next2 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next2).toBeEnabled());
    fireEvent.click(next2);

    const publishButton = await screen.findByRole('button', { name: /publish/i });
    await waitFor(() => expect(publishButton).toBeEnabled());
    fireEvent.click(publishButton);

    await waitFor(() => expect(mocks.publishWorkflow).toHaveBeenCalled());
    expect(mocks.publishWorkflow.mock.calls[0][0].visibility).toBe('PUBLIC');

    // In-modal "submitted for review" banner - not the private copy.
    await screen.findByText('publishSuccessDescription');
    expect(screen.queryByText('publishSuccessPrivateDescription')).not.toBeInTheDocument();
    // The confirmation renders INSIDE the wizard, not as the old takeover
    // screen: its heading + "Done" button are gone (both present pre-change)...
    expect(screen.queryByText('publishSuccess')).not.toBeInTheDocument();
    expect(screen.queryByText('Done')).not.toBeInTheDocument();
    // ...and the wizard chrome (step indicator) is still mounted around it.
    expect(screen.getByText('Showcase')).toBeInTheDocument();
    // The wizard stays open: the publish flow never calls onClose itself.
    expect(onClose).not.toHaveBeenCalled();
  });

  // Unpublishing must confirm with an in-modal banner and keep the modal open -
  // the old flow auto-closed after 1s, hiding the confirmation.
  it('unpublish shows a success banner in the modal and does not auto-close', async () => {
    const onClose = vi.fn();
    const IFACE = '44444444-4444-4444-4444-444444444444';
    mocks.getPublicationByWorkflowId.mockResolvedValue({
      published: true,
      id: 'publication-1',
      title: 'Shared app',
      description: 'desc',
      creditsPerUse: 0,
      visibility: 'PUBLIC',
      showcaseRunId: 'run-1',
      showcaseInterfaceId: IFACE,
      planVersion: 1,
    });
    mocks.getWorkflowRuns.mockResolvedValue([
      {
        id: 'run-uuid',
        runId: 'run-1',
        status: 'COMPLETED',
        startedAt: '2026-05-26T12:00:00Z',
        planVersion: 1,
        totalNodes: 2,
        executionMode: 'automatic',
      },
    ]);
    mocks.getVersion.mockResolvedValue({
      plan: { interfaces: [{ id: IFACE, label: 'Main interface' }], tables: [], triggers: [] },
    });
    mocks.unpublishWorkflow.mockResolvedValue(undefined);

    render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="Shared app"
        workflowDescription="desc"
        onClose={onClose}
      />,
    );

    // Open the destructive confirmation, type the required phrase, confirm.
    const unpublishBtn = await screen.findByRole('button', { name: 'unpublish' });
    fireEvent.click(unpublishBtn);
    const confirmInput = await screen.findByPlaceholderText('unpublish');
    fireEvent.change(confirmInput, { target: { value: 'unpublish' } });
    fireEvent.click(screen.getByRole('button', { name: 'unpublishConfirmButton' }));

    await waitFor(() => expect(mocks.unpublishWorkflow).toHaveBeenCalled());
    // The success banner renders in the wizard.
    await screen.findByText('unpublishSuccess');

    // The old behavior auto-closed after 1s. Wait past that window to prove the
    // modal now stays open (onClose is never called by the unpublish flow).
    await new Promise((resolve) => setTimeout(resolve, 1100));
    expect(onClose).not.toHaveBeenCalled();
  });

  // A category is mandatory: a fresh publish defaults to "automation" (the
  // publisher can no longer ship an uncategorized application).
  it('defaults the category to Automation for a new publish', async () => {
    const AUTOMATION_ID = 'a0000000-0000-4000-8000-000000000001';
    const IFACE = '44444444-4444-4444-4444-444444444444';
    mocks.getPublicationByWorkflowId.mockResolvedValue(null);
    mocks.getCategories.mockResolvedValue({
      categories: [
        { id: AUTOMATION_ID, slug: 'automation', name: 'Automation' },
        { id: 'cat-marketing', slug: 'marketing', name: 'Marketing' },
      ],
    });
    mocks.getWorkflowRuns.mockResolvedValue([
      {
        id: 'run-uuid',
        runId: 'run-1',
        status: 'COMPLETED',
        startedAt: '2026-05-26T12:00:00Z',
        planVersion: 1,
        totalNodes: 2,
        executionMode: 'automatic',
      },
    ]);
    mocks.getVersion.mockResolvedValue({
      plan: { interfaces: [{ id: IFACE, label: 'Main interface' }], tables: [], triggers: [] },
    });
    mocks.publishWorkflow.mockResolvedValue({ id: 'publication-1' });

    render(
      <PublishWorkflowModal
        isOpen
        workflowId="workflow-1"
        workflowName="App"
        workflowDescription="desc"
        onClose={vi.fn()}
      />,
    );

    const next1 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next1).toBeEnabled());
    fireEvent.click(next1);
    const next2 = await screen.findByRole('button', { name: /next/i });
    await waitFor(() => expect(next2).toBeEnabled());
    fireEvent.click(next2);

    const publishButton = await screen.findByRole('button', { name: /publish/i });
    await waitFor(() => expect(publishButton).toBeEnabled());
    fireEvent.click(publishButton);

    await waitFor(() => expect(mocks.publishWorkflow).toHaveBeenCalled());
    // No category was picked → the wizard auto-selected "automation".
    expect(mocks.publishWorkflow.mock.calls[0][0].categoryId).toBe(AUTOMATION_ID);
  });
});
