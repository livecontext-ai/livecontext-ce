// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));

const api = vi.hoisted(() => ({
  getCreatorFollow: vi.fn(),
  followCreator: vi.fn(),
  unfollowCreator: vi.fn(),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: api }));

import { ApiError } from '@/lib/api/api-client';
import { FollowCreatorButton } from '../FollowCreatorButton';

function renderButton() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <FollowCreatorButton creatorId={7} />
    </QueryClientProvider>,
  );
}

const button = () => screen.getByTestId('follow-creator-button');

describe('FollowCreatorButton', () => {
  const originalLocation = window.location;

  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(window, 'location', { configurable: true, value: { href: '/app/u/alice' } });
  });
  afterEach(() => {
    cleanup();
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  it('offers "Subscribe" as a filled call to action when not following', async () => {
    api.getCreatorFollow.mockResolvedValue({ following: false, followerCount: 0 });

    renderButton();

    await waitFor(() => expect(api.getCreatorFollow).toHaveBeenCalledWith(7));
    expect(button()).toHaveTextContent('profile.follow');
    expect(button()).toHaveAttribute('aria-pressed', 'false');
    expect(button().className).toContain('bg-[var(--accent-primary)]');
  });

  it('subscribing calls follow and flips the button to "Subscribed"', async () => {
    api.getCreatorFollow.mockResolvedValue({ following: false, followerCount: 0 });
    api.followCreator.mockResolvedValue({ following: true, followerCount: 1 });

    renderButton();
    await waitFor(() => expect(api.getCreatorFollow).toHaveBeenCalled());
    fireEvent.click(button());

    await waitFor(() => expect(button()).toHaveTextContent('profile.following'));
    expect(api.followCreator).toHaveBeenCalledWith(7);
    expect(button()).toHaveAttribute('aria-pressed', 'true');
  });

  it('clicking while subscribed unsubscribes', async () => {
    api.getCreatorFollow.mockResolvedValue({ following: true, followerCount: 4 });
    api.unfollowCreator.mockResolvedValue({ following: false, followerCount: 3 });

    renderButton();
    await waitFor(() => expect(button()).toHaveTextContent('profile.following'));
    fireEvent.click(button());

    await waitFor(() => expect(button()).toHaveTextContent('profile.follow'));
    expect(api.unfollowCreator).toHaveBeenCalledWith(7);
  });

  it('a signed-out viewer is sent to the login page', async () => {
    api.getCreatorFollow.mockRejectedValue(new ApiError('unauthorized', 401));
    api.followCreator.mockRejectedValue(new ApiError('unauthorized', 401));

    renderButton();
    fireEvent.click(button());

    await waitFor(() => expect(window.location.href).toBe('/login'));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('any other failure keeps the viewer on the profile with an inline message', async () => {
    api.getCreatorFollow.mockResolvedValue({ following: false, followerCount: 0 });
    api.followCreator.mockRejectedValue(new ApiError('boom', 500));

    renderButton();
    await waitFor(() => expect(api.getCreatorFollow).toHaveBeenCalled());
    fireEvent.click(button());

    expect(await screen.findByRole('alert')).toHaveTextContent('profile.followError');
    expect(window.location.href).toBe('/app/u/alice');
    expect(button()).toHaveTextContent('profile.follow');
  });
});
