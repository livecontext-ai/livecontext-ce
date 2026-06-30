// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));

const { getPublicProfileByHandle, getByPublisher } = vi.hoisted(() => ({
  getPublicProfileByHandle: vi.fn(),
  getByPublisher: vi.fn(),
}));

vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getPublicProfileByHandle },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getByPublisher } }));
vi.mock('@/lib/api/dm-api', () => ({ dmApi: { openThread: vi.fn() } }));
// PublisherAvatar (the canonical USER avatar) is intentionally NOT mocked - the
// regression test below asserts the profile renders the user avatar endpoint and
// never the agent-only preset SVG.
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({ publication }: { publication: { title: string } }) => (
    <div data-testid="pub-card">{publication.title}</div>
  ),
  PublicationCardSkeleton: () => null,
}));

import ProfileContent from '../ProfileContent';

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

const profile = {
  userId: 7,
  displayName: 'Alice A.',
  handle: 'alice_a',
  avatarUrl: '/api/users/7/avatar',
  bio: 'I build automations',
  joinedAt: '2024-03-01T00:00:00',
};

describe('ProfileContent', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('renders the display name + @handle, bio and apps - looked up by handle (never the tenant id)', async () => {
    getPublicProfileByHandle.mockResolvedValue(profile);
    getByPublisher.mockResolvedValue({ publications: [{ id: 'p1', title: 'Invoice Bot' }], count: 1 });

    renderWithClient(<ProfileContent handle="alice_a" />);

    expect(await screen.findByText('Alice A.')).toBeInTheDocument();
    // The @handle (a chosen, non-real-name slug) is shown; apps still resolve by the internal id.
    expect(screen.getByText('@alice_a')).toBeInTheDocument();
    expect(screen.getByText('I build automations')).toBeInTheDocument();
    expect(await screen.findByTestId('pub-card')).toHaveTextContent('Invoice Bot');
    expect(getPublicProfileByHandle).toHaveBeenCalledWith('alice_a');
    expect(getByPublisher).toHaveBeenCalledWith('7', 0, 24);
  });

  it('regression: renders the canonical USER avatar (user endpoint → photo or server initials SVG), never the agent preset, when the user has no uploaded photo', async () => {
    // A freshly-created user has no storage-backed avatar → the backend returns
    // avatarUrl: null on purpose, expecting an initials fallback. The bug: the
    // agent-only AvatarDisplay fell back to AVATAR_PRESETS[0] = /avatars/avatar-1.svg,
    // stamping an agent SVG onto a user profile.
    getPublicProfileByHandle.mockResolvedValue({ ...profile, avatarUrl: null });
    getByPublisher.mockResolvedValue({ publications: [], count: 0 });

    renderWithClient(<ProfileContent handle="alice_a" />);

    const avatar = await screen.findByAltText('Alice A.');
    // Canonical user avatar endpoint (proxied), keyed by the numeric userId - the
    // same source used for marketplace publishers, so the avatar is identical
    // everywhere. NEVER an agent preset under /avatars/.
    expect(avatar).toHaveAttribute('src', '/api/proxy/users/7/avatar');
    expect(avatar.getAttribute('src') ?? '').not.toContain('/avatars/avatar-');
  });

  it('shows a not-found state when the handle lookup fails and does not load apps', async () => {
    getPublicProfileByHandle.mockRejectedValue(new Error('404'));

    renderWithClient(<ProfileContent handle="ghost" />);

    expect(await screen.findByText('profile.notFoundTitle')).toBeInTheDocument();
    expect(getByPublisher).not.toHaveBeenCalled();
  });

  it('renders a friendly empty state when the user has no published apps', async () => {
    getPublicProfileByHandle.mockResolvedValue(profile);
    getByPublisher.mockResolvedValue({ publications: [], count: 0 });

    renderWithClient(<ProfileContent handle="alice_a" />);

    expect(await screen.findByText('Alice A.')).toBeInTheDocument();
    expect(await screen.findByText('profile.noApps')).toBeInTheDocument();
  });

  it('regression: the empty state stretches with the page instead of collapsing into a strip', async () => {
    getPublicProfileByHandle.mockResolvedValue(profile);
    getByPublisher.mockResolvedValue({ publications: [], count: 0 });

    const { container } = renderWithClient(<ProfileContent handle="alice_a" />);
    const empty = (await screen.findByText('profile.noApps')).parentElement!;

    // The dashed panel grows (flex-1 + min-height floor) inside a flex column root -
    // pre-fix it was a thin py-10 <p> and a no-apps profile looked super condensed.
    expect(empty.className).toContain('flex-1');
    expect(empty.className).toContain('min-h-[40vh]');
    expect((container.firstElementChild as HTMLElement).className).toContain('flex-col');
  });
});
