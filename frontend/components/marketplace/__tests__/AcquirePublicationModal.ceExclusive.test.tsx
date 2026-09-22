// @vitest-environment jsdom
/**
 * CE-exclusive publications in the acquire modal.
 *
 * The modal is the funnel EVERY install entry point goes through, so it must
 * refuse pre-emptively on managed cloud: no confirm CTA, no acquire call, and
 * an explanation naming the features. It also renders the same screen when the
 * backend answers 403 CE_EXCLUSIVE (a stale card, or a direct call).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'RAG Assistant',
    creditsPerUse: 0,
    publicationType: 'WORKFLOW',
    displayMode: 'WORKFLOW',
    ...overrides,
  } as WorkflowPublication;
}

const MANAGED_CLOUD = { IS_MANAGED_CLOUD: true, IS_CLOUD: true, IS_CE: false };
const COMMUNITY_EDITION = { IS_MANAGED_CLOUD: false, IS_CLOUD: false, IS_CE: true };

/**
 * Fresh module registry per edition: the edition constants are frozen at import
 * time. Returns the store so a test can drive it into a terminal state.
 */
async function renderModal(
  edition: { IS_MANAGED_CLOUD: boolean; IS_CLOUD: boolean; IS_CE: boolean },
  publication: WorkflowPublication,
) {
  vi.resetModules();
  vi.doMock('@/lib/edition', () => edition);
  const { useMarketplaceInstallStore } = await import('@/lib/stores/marketplace-install-store');
  useMarketplaceInstallStore.getState().clear();
  const Modal = (await import('../AcquirePublicationModal')).default;
  render(<Modal isOpen publication={publication} onClose={() => {}} />);
  return useMarketplaceInstallStore;
}

beforeEach(() => vi.clearAllMocks());

afterEach(() => {
  cleanup();
  vi.doUnmock('@/lib/edition');
  vi.resetModules();
});

describe('AcquirePublicationModal - CE-exclusive', () => {
  it('cloud: renders the self-hosted-required screen instead of the confirm CTA', async () => {
    await renderModal(MANAGED_CLOUD, pub({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT'] }));

    expect(screen.getByTestId('acquire-modal-ce-exclusive')).toBeInTheDocument();
    expect(screen.getByText('ceExclusiveTitle')).toBeInTheDocument();
    // The install CTA of the confirm screen must not be reachable.
    expect(screen.queryByRole('button', { name: 'addToApplications' })).toBeNull();
  });

  it('cloud: lists the features behind the requirement', async () => {
    await renderModal(
      MANAGED_CLOUD,
      pub({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT', 'VECTOR_SEARCH'] }),
    );

    expect(screen.getByText('ceExclusiveFeatureCliAgent')).toBeInTheDocument();
    expect(screen.getByText('ceExclusiveFeatureVectorSearch')).toBeInTheDocument();
  });

  it('cloud: never fires an acquire call for a CE-exclusive publication', async () => {
    await renderModal(MANAGED_CLOUD, pub({ ceExclusive: true }));

    expect(svc.acquirePublication).not.toHaveBeenCalled();
    expect(svc.acquireRemotePublication).not.toHaveBeenCalled();
  });

  it('self-hosted: shows the normal confirm screen for the same publication', async () => {
    await renderModal(COMMUNITY_EDITION, pub({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT'] }));

    expect(screen.queryByTestId('acquire-modal-ce-exclusive')).toBeNull();
    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeInTheDocument();
  });

  it('renders the same screen from a backend 403, even where the pre-emptive gate is off', async () => {
    // Self-hosted build, so the pre-emptive gate is FALSE and only the store's
    // terminal status can drive this screen. That is the stale-card / direct-call
    // path, and it must land on the explanation, not the generic error screen
    // with its retry button (retrying can never succeed).
    //
    // inlineProgress mode is what the marketplace grid uses, and it is the mode
    // that deliberately PRESERVES a terminal store state across a re-mount; a
    // non-inline open clears it by design (lingering-state hygiene).
    const publication = pub({ ceExclusive: true, ceExclusiveFeatures: ['VECTOR_SEARCH'] });
    const store = await renderModal(COMMUNITY_EDITION, publication);
    cleanup();
    store.setState({
      active: {
        publication,
        ceMode: false,
        demo: false,
        inline: true,
        status: 'ce-exclusive',
        progress: 0,
        acquiredId: null,
        error: 'self-hosted only',
        resources: {},
        withEditableCopy: false,
        editableCopyWorkflowId: null,
        editableCopyFailed: false,
      },
    });
    const Modal = (await import('../AcquirePublicationModal')).default;
    render(<Modal isOpen inlineProgress publication={publication} onClose={() => {}} />);

    expect(screen.getByTestId('acquire-modal-ce-exclusive')).toBeInTheDocument();
    expect(screen.getByText('ceExclusiveFeatureVectorSearch')).toBeInTheDocument();
    // No retry: the only resolution is a different deployment.
    expect(screen.queryByRole('button', { name: 'retry' })).toBeNull();
  });

  it('cloud: a normal publication is unaffected', async () => {
    await renderModal(MANAGED_CLOUD, pub({ ceExclusive: false }));

    expect(screen.queryByTestId('acquire-modal-ce-exclusive')).toBeNull();
    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeInTheDocument();
  });
});
