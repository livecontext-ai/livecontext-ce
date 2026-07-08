// @vitest-environment jsdom
/**
 * Install-progress rendering + "Open" button of {@link PublicationCard}
 * (2026-07 install-flow redesign): while `installProgress` is set the
 * thumbnail preview is greyed out by a veil that retreats left-to-right as the
 * gauge fills (same progress-bar system as the former in-modal screen), the
 * Install CTA is hidden; once installed and `openHref` is provided, the
 * Install slot renders an "Open" button that navigates to the installed app.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const routerPush = vi.hoisted(() => vi.fn());
const trackMock = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: routerPush }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: trackMock }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/profile/UserActionMenu', () => ({ UserActionMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: { getLandingSnapshot: vi.fn().mockResolvedValue({ landing: null }) } }));

import { PublicationCard } from '../PublicationCard';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Gallery',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    publisherId: 'pub-user',
    status: 'ACTIVE',
    ...overrides,
  } as WorkflowPublication;
}

beforeEach(() => {
  vi.clearAllMocks();
  cleanup();
});

describe('PublicationCard - install progress overlay', () => {
  it('renders the greying veil + gauge while installing and hides the Install CTA', () => {
    render(<PublicationCard publication={pub()} onAcquire={() => {}} installProgress={40} />);

    const overlay = screen.getByTestId('publication-card-install-progress');
    expect(overlay).toBeInTheDocument();
    // The gauge mirrors the modal's bar: role=progressbar + live percent.
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '40');
    expect(screen.getByText('40%')).toBeInTheDocument();
    // The Install CTA is suspended for the duration of the install.
    expect(screen.queryByRole('button', { name: /acquire/ })).not.toBeInTheDocument();
  });

  it('the veil un-greys left-to-right: its left edge tracks the progress percent', () => {
    render(<PublicationCard publication={pub()} onAcquire={() => {}} installProgress={62} />);

    const overlay = screen.getByTestId('publication-card-install-progress');
    const veil = overlay.firstElementChild as HTMLElement;
    // left = progress% → everything left of it is revealed (full color).
    expect(veil.style.left).toBe('62%');
    expect(veil.style.backdropFilter).toContain('grayscale');
  });

  it('clamps out-of-range progress into 0-100', () => {
    render(<PublicationCard publication={pub()} onAcquire={() => {}} installProgress={140} />);
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100');
  });

  it('no overlay when not installing', () => {
    render(<PublicationCard publication={pub()} onAcquire={() => {}} />);
    expect(screen.queryByTestId('publication-card-install-progress')).not.toBeInTheDocument();
  });
});

describe('PublicationCard - Open button (installed)', () => {
  it('replaces the Install button with Open when acquired + openHref, and navigates on click', () => {
    render(
      <PublicationCard
        publication={pub()}
        onAcquire={() => {}}
        isAcquired
        openHref="/app/applications/pub-1"
      />
    );

    // Installed badge + Open button, no Install button.
    expect(screen.getByText('installed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /acquire/ })).not.toBeInTheDocument();
    const open = screen.getByTestId('publication-card-open');
    expect(open).toHaveTextContent('open');

    fireEvent.click(open);
    expect(routerPush).toHaveBeenCalledWith('/app/applications/pub-1');
    expect(trackMock).toHaveBeenCalledWith('app_post_install_opened', expect.objectContaining({
      publication_id: 'pub-1',
    }));
  });

  it('acquired without openHref keeps the badge-only behavior (no Open button)', () => {
    render(<PublicationCard publication={pub()} onAcquire={() => {}} isAcquired />);

    expect(screen.getByText('installed')).toBeInTheDocument();
    expect(screen.queryByTestId('publication-card-open')).not.toBeInTheDocument();
  });

  it('hides the Open button while a (re)install is running', () => {
    render(
      <PublicationCard
        publication={pub()}
        onAcquire={() => {}}
        isAcquired
        openHref="/app/applications/pub-1"
        installProgress={30}
      />
    );

    expect(screen.queryByTestId('publication-card-open')).not.toBeInTheDocument();
    expect(screen.getByTestId('publication-card-install-progress')).toBeInTheDocument();
  });
});
