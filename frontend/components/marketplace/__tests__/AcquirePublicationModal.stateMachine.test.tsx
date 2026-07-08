// @vitest-environment jsdom
/**
 * State-machine coverage for AcquirePublicationModal: confirm -> processing ->
 * success / error / link-required / insufficient-credits, the install progress
 * bar (easeOutCubic + 95% cap until the HTTP call returns), the mounted-ref
 * guard, the close guard while in flight, post-install navigation routing, and
 * the free-vs-paid confirm view. Mirrors the mock setup of
 * AcquirePublicationModal.ceMode.test.tsx (its sibling) so the two stay aligned.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
const routerPush = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: routerPush }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({
  PublisherAvatar: () => null,
}));

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));

import AcquirePublicationModal from '../AcquirePublicationModal';
import { useMarketplaceInstallStore } from '@/lib/stores/marketplace-install-store';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Cloud Thing',
    creditsPerUse: 0,
    publicationType: 'WORKFLOW',
    displayMode: 'WORKFLOW',
    ...overrides,
  } as WorkflowPublication;
}

function resetSvc() {
  svc.acquireRemotePublication.mockResolvedValue({ workflowId: 'w1', agentId: 'a1', resourceId: 'r1' });
  svc.acquireAgentPublication.mockResolvedValue({ agentId: 'a1' });
  svc.acquireResourcePublication.mockResolvedValue({ resourceId: 'r1', type: 'TABLE' });
  svc.acquirePublication.mockResolvedValue({ workflowId: 'w1' });
}

// -------------------------------------------------------------------------
// Progress bar + success/cleanup require driving the simulated install timer,
// so this block uses fake timers and a controllable performance.now().
// -------------------------------------------------------------------------
describe('AcquirePublicationModal - processing / success / progress (fake timers)', () => {
  let nowValue = 0;

  beforeEach(() => {
    vi.clearAllMocks();
    // Kill any machine left over in the module-global install store (a
    // never-resolving acquire from a previous test would otherwise make
    // startInstall a no-op and leak its 'installing' state into this test).
    useMarketplaceInstallStore.getState().clear();
    nowValue = 0;
    vi.useFakeTimers();
    // Deterministic 5000ms target duration (5000 + floor(0 * range)).
    vi.spyOn(Math, 'random').mockReturnValue(0);
    // Drive the easing math by hand instead of wall clock.
    vi.spyOn(performance, 'now').mockImplementation(() => nowValue);
    resetSvc();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  function clickAddToApplications() {
    fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));
  }

  // Push the simulated clock past the install duration and advance fake time
  // through BOTH gating timers: the visual-minimum wait (up to the ~5000ms
  // target duration) and the 300ms snap-to-100 hold. The acquire HTTP call
  // resolves immediately, but the success screen is gated on the visual budget,
  // so a sub-target advance would never reach it.
  async function driveToSuccess() {
    nowValue = 6000; // past the 5000ms target so the interval self-clears at 95%
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
  }

  it('moves confirm -> processing and shows the install progress bar at 0%', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {})); // never resolves
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    clickAddToApplications();

    expect(screen.getByText('processingTitle')).toBeInTheDocument();
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '0');
  });

  it('drives confirm -> processing -> success and calls onSuccess with the acquired id', async () => {
    const onSuccess = vi.fn();
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} onSuccess={onSuccess} />);

    clickAddToApplications();
    await driveToSuccess();

    expect(screen.getByText('successTitle')).toBeInTheDocument();
    expect(onSuccess).toHaveBeenCalledWith('w1');
  });

  it('eases the progress via easeOutCubic and caps at 95% until the acquire resolves', async () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {})); // call never returns
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    clickAddToApplications();

    // Half the duration: easeOutCubic gives 1-(0.5^3)=0.875 -> 83%, NOT the
    // linear 48% - proves the curve is non-linear.
    nowValue = 2500;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60);
    });
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '83');

    // Past the full duration the bar holds at the 95% cap (the final 5% is
    // reserved for the snap-to-100 once the HTTP call returns).
    nowValue = 6000;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60);
    });
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '95');
    expect(screen.queryByText('successTitle')).not.toBeInTheDocument();
  });

  it('does not call onSuccess or update state after the modal unmounts mid-install', async () => {
    const onSuccess = vi.fn();
    const { unmount } = render(
      <AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} onSuccess={onSuccess} />
    );

    clickAddToApplications();
    unmount();
    await driveToSuccess();

    expect(onSuccess).not.toHaveBeenCalled();
    expect(screen.queryByText('successTitle')).not.toBeInTheDocument();
  });

  it('routes an acquired AGENT to the agents page on success', async () => {
    render(<AcquirePublicationModal isOpen publication={pub({ publicationType: 'AGENT' })} onClose={() => {}} />);
    clickAddToApplications();
    await driveToSuccess();

    fireEvent.click(screen.getByRole('button', { name: 'goToAgents' }));
    expect(routerPush).toHaveBeenCalledWith('/app/agent');
  });

  it('routes an acquired TABLE to the data page on success', async () => {
    render(<AcquirePublicationModal isOpen publication={pub({ publicationType: 'TABLE' })} onClose={() => {}} />);
    clickAddToApplications();
    await driveToSuccess();

    fireEvent.click(screen.getByRole('button', { name: 'goToApplications' }));
    expect(routerPush).toHaveBeenCalledWith('/app/data');
  });

  it('routes an acquired INTERFACE to the interface page on success', async () => {
    render(<AcquirePublicationModal isOpen publication={pub({ publicationType: 'INTERFACE' })} onClose={() => {}} />);
    clickAddToApplications();
    await driveToSuccess();

    fireEvent.click(screen.getByRole('button', { name: 'goToApplications' }));
    expect(routerPush).toHaveBeenCalledWith('/app/interface');
  });

  it('routes an acquired WORKFLOW to the applications page on success', async () => {
    render(<AcquirePublicationModal isOpen publication={pub({ publicationType: 'WORKFLOW' })} onClose={() => {}} />);
    clickAddToApplications();
    await driveToSuccess();

    fireEvent.click(screen.getByRole('button', { name: 'goToApplications' }));
    expect(routerPush).toHaveBeenCalledWith('/app/applications');
  });

  it('inlineProgress: success renders NO success screen - the card takes over', async () => {
    const onClose = vi.fn();
    render(<AcquirePublicationModal isOpen inlineProgress publication={pub()} onClose={onClose} />);
    clickAddToApplications();
    // Confirm closed the modal view immediately (the caller's card shows progress).
    expect(onClose).toHaveBeenCalledTimes(1);

    await driveToSuccess();
    expect(screen.queryByText('successTitle')).not.toBeInTheDocument();
    expect(screen.queryByText('processingTitle')).not.toBeInTheDocument();
    // The store carries the terminal success for the page to consume.
    expect(useMarketplaceInstallStore.getState().active?.status).toBe('success');
    expect(useMarketplaceInstallStore.getState().active?.acquiredId).toBe('w1');
  });
});

// -------------------------------------------------------------------------
// Routing, error states, close guard, portal and pricing view resolve without
// the install timer, so this block uses real timers + waitFor (matching the
// ceMode sibling).
// -------------------------------------------------------------------------
describe('AcquirePublicationModal - routing / errors / view (real timers)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Same store hygiene as the fake-timers block above.
    useMarketplaceInstallStore.getState().clear();
    resetSvc();
  });

  afterEach(() => cleanup());

  function clickFreeConfirm() {
    fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));
  }
  function clickPaidConfirm() {
    fireEvent.click(screen.getByRole('button', { name: 'purchaseFor' }));
  }

  it('non-CE WORKFLOW install calls the workflow acquire endpoint', async () => {
    render(<AcquirePublicationModal isOpen publication={pub({ publicationType: 'WORKFLOW' })} onClose={() => {}} />);
    clickFreeConfirm();

    await waitFor(() => expect(svc.acquirePublication).toHaveBeenCalledWith('pub-1'));
    expect(svc.acquireRemotePublication).not.toHaveBeenCalled();
    expect(svc.acquireAgentPublication).not.toHaveBeenCalled();
    expect(svc.acquireResourcePublication).not.toHaveBeenCalled();
  });

  it('402 acquire error transitions to the insufficient-credits state', async () => {
    svc.acquirePublication.mockRejectedValue({ status: 402 });
    render(<AcquirePublicationModal isOpen publication={pub({ creditsPerUse: 5 })} onClose={() => {}} />);
    clickPaidConfirm();

    await waitFor(() => expect(screen.getByText('insufficientCredits')).toBeInTheDocument());
    expect(screen.getByText('creditsRequired')).toBeInTheDocument();
  });

  it('403 CLOUD_ACCOUNT_NOT_LINKED transitions to link-required and routes to cloud-account settings', async () => {
    svc.acquirePublication.mockRejectedValue({ status: 403, code: 'CLOUD_ACCOUNT_NOT_LINKED' });
    render(<AcquirePublicationModal isOpen publication={pub({ creditsPerUse: 5 })} onClose={() => {}} />);
    clickPaidConfirm();

    await waitFor(() => expect(screen.getByText('linkRequired')).toBeInTheDocument());
    expect(screen.getByText('linkRequiredDescription')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'goToSettings' }));
    expect(routerPush).toHaveBeenCalledWith('/app/settings/cloud-account');
  });

  it('a generic acquire error shows the error state; the retry button re-invokes acquire', async () => {
    svc.acquirePublication.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);
    clickFreeConfirm();

    await waitFor(() => expect(screen.getByText('errorTitle')).toBeInTheDocument());
    expect(screen.getByText('boom')).toBeInTheDocument();
    expect(svc.acquirePublication).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'retry' }));
    await waitFor(() => expect(svc.acquirePublication).toHaveBeenCalledTimes(2));
  });

  it('blocks close while an install is in flight (processing state)', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {})); // never resolves -> stays processing
    const onClose = vi.fn();
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={onClose} />);
    clickFreeConfirm();

    // Backdrop is the parent of the processing dialog; its click runs handleClose.
    const backdrop = screen.getByRole('dialog').parentElement as HTMLElement;
    fireEvent.click(backdrop);

    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('progressbar')).toBeInTheDocument();
  });

  it('renders through a portal into document.body; backdrop closes, dialog click does not', () => {
    const onClose = vi.fn();
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={onClose} />);

    const dialog = screen.getByRole('dialog');
    const backdrop = dialog.parentElement as HTMLElement;
    // createPortal mounts the backdrop directly under document.body.
    expect(backdrop.parentElement).toBe(document.body);

    // Clicking inside the dialog stops propagation -> no close.
    fireEvent.click(dialog);
    expect(onClose).not.toHaveBeenCalled();

    // Clicking the backdrop closes.
    fireEvent.click(backdrop);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('free publication shows the free price label and the add CTA', () => {
    render(<AcquirePublicationModal isOpen publication={pub({ creditsPerUse: 0 })} onClose={() => {}} />);

    expect(screen.getByText('freeApplication')).toBeInTheDocument();
    expect(screen.queryByText('oneTimeCost')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeInTheDocument();
  });

  it('paid publication shows the one-time cost label and the purchase CTA', () => {
    render(<AcquirePublicationModal isOpen publication={pub({ creditsPerUse: 5 })} onClose={() => {}} />);

    expect(screen.getByText('oneTimeCost')).toBeInTheDocument();
    expect(screen.queryByText('freeApplication')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'purchaseFor' })).toBeInTheDocument();
  });
});

// -------------------------------------------------------------------------
// inlineProgress mode (marketplace grid / preview header): confirm closes the
// modal and the CARD renders the progress; the modal only re-appears for the
// terminal error screens.
// -------------------------------------------------------------------------
describe('AcquirePublicationModal - inlineProgress mode (real timers)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMarketplaceInstallStore.getState().clear();
    resetSvc();
  });

  afterEach(() => cleanup());

  it('confirm starts the install, closes the modal, fires onInstallStarted - no processing screen', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {})); // stays installing
    const onClose = vi.fn();
    const onInstallStarted = vi.fn();
    render(
      <AcquirePublicationModal
        isOpen
        inlineProgress
        publication={pub()}
        onClose={onClose}
        onInstallStarted={onInstallStarted}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onInstallStarted).toHaveBeenCalledTimes(1);
    expect(useMarketplaceInstallStore.getState().active?.status).toBe('installing');
    expect(screen.queryByText('processingTitle')).not.toBeInTheDocument();
  });

  it('still renders the error screen inline when the acquire fails; retry does not re-close', async () => {
    svc.acquirePublication.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }));
    const onClose = vi.fn();
    render(<AcquirePublicationModal isOpen inlineProgress publication={pub()} onClose={onClose} />);

    fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));
    await waitFor(() => expect(screen.getByText('errorTitle')).toBeInTheDocument());
    expect(screen.getByText('boom')).toBeInTheDocument();

    // Retry restarts the machine WITHOUT another onClose (the caller derives
    // the modal's mount from the store status, not from a close event).
    onClose.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'retry' }));
    expect(onClose).not.toHaveBeenCalled();
    expect(useMarketplaceInstallStore.getState().active?.status).toBe('installing');
  });

  it('a lingering success for this publication is dropped on a fresh open (confirm screen shows)', () => {
    useMarketplaceInstallStore.setState({
      active: {
        publication: pub(),
        ceMode: false,
        inline: true,
        status: 'success',
        progress: 100,
        acquiredId: 'w1',
        error: null,
      },
    });
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeInTheDocument();
    expect(useMarketplaceInstallStore.getState().active).toBeNull();
  });

  it('a lingering success does NOT fire onSuccess on mount (stale-state guard)', () => {
    useMarketplaceInstallStore.setState({
      active: {
        publication: pub(),
        ceMode: false,
        inline: true,
        status: 'success',
        progress: 100,
        acquiredId: 'w1',
        error: null,
      },
    });
    const onSuccess = vi.fn();
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} onSuccess={onSuccess} />);

    // The modal never observed this install running, so the stale success must
    // not be reported (in ChatCore it would auto-approve a tool authorization).
    expect(onSuccess).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeInTheDocument();
  });

  it('a lingering inline ERROR is dropped on a fresh NON-inline open (confirm, not the error screen)', () => {
    useMarketplaceInstallStore.setState({
      active: {
        publication: pub(),
        ceMode: false,
        inline: true,
        status: 'error',
        progress: 40,
        acquiredId: null,
        error: 'boom from the marketplace',
      },
    });
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    expect(screen.queryByText('errorTitle')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeInTheDocument();
    expect(useMarketplaceInstallStore.getState().active).toBeNull();
  });

  it('an INLINE open keeps the terminal error visible (the caller re-mounts the modal to show it)', () => {
    useMarketplaceInstallStore.setState({
      active: {
        publication: pub(),
        ceMode: false,
        inline: true,
        status: 'error',
        progress: 40,
        acquiredId: null,
        error: 'boom from the marketplace',
      },
    });
    render(<AcquirePublicationModal isOpen inlineProgress publication={pub()} onClose={() => {}} />);

    expect(screen.getByText('errorTitle')).toBeInTheDocument();
    expect(screen.getByText('boom from the marketplace')).toBeInTheDocument();
  });

  it('the confirm CTA is disabled while ANOTHER publication is installing (no silent drop)', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {}));
    useMarketplaceInstallStore.getState().startInstall(pub({ id: 'other-pub' }));
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeDisabled();
  });
});
