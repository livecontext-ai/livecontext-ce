// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: (ns?: string) => (k: string) => `${ns}.${k}` }));
// Radix Popover needs pointer APIs jsdom lacks - render trigger + content inline.
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: any) => <div>{children}</div>,
  PopoverTrigger: ({ children }: any) => <>{children}</>,
  PopoverContent: ({ children }: any) => <div>{children}</div>,
}));

const { push, openThread, getPublicProfileById, getRemotePublicProfileById, invalidateQueries, meProfile, isCe } = vi.hoisted(() => ({
  push: vi.fn(),
  openThread: vi.fn(),
  getPublicProfileById: vi.fn(),
  getRemotePublicProfileById: vi.fn(),
  invalidateQueries: vi.fn(),
  meProfile: { current: { id: '7' } as { id: string } | null },
  isCe: { value: false },
}));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/lib/api/dm-api', () => ({ dmApi: { openThread } }));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: { getPublicProfileById, getRemotePublicProfileById } }));
// IS_CE is a getter so a single test can flip it (the CE cloud-profile fallback).
vi.mock('@/lib/edition', () => ({ get IS_CE() { return isCe.value; } }));
// Edition-independent: assert the (relative) cloud path; the cloud-origin rewrite
// is covered by cloudWebUrl's own tests.
vi.mock('@/lib/edition/cloudWebUrl', () => ({ cloudWebUrl: (p: string) => `https://cloud.test${p}` }));
vi.mock('@/hooks/useUserProfile', () => ({ useUserProfile: () => ({ profile: meProfile.current }) }));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries }) }));

import { UserActionMenu } from '../UserActionMenu';

describe('UserActionMenu', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    isCe.value = false;
    meProfile.current = { id: '7' };
    getPublicProfileById.mockResolvedValue({ userId: 8, displayName: 'Bob', handle: 'bob_b' });
    getRemotePublicProfileById.mockResolvedValue({ userId: 8, displayName: 'Cloud Bob', handle: 'cloud_bob' });
    openThread.mockResolvedValue({ id: 't-42', otherUserId: '8' });
  });
  afterEach(cleanup);

  it('renders the children untouched (no menu) when there is no usable user id', () => {
    render(<UserActionMenu userId={null}>visible-chip</UserActionMenu>);
    expect(screen.getByText('visible-chip')).toBeInTheDocument();
    expect(screen.queryByTestId('user-action-trigger')).not.toBeInTheDocument();
  });

  it('"view profile" resolves the @handle from the numeric id and navigates to /app/u/{handle}', async () => {
    render(<UserActionMenu userId="8">chip</UserActionMenu>);

    fireEvent.click(screen.getByTestId('user-action-view-profile'));

    await waitFor(() => expect(getPublicProfileById).toHaveBeenCalledWith('8'));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/app/u/bob_b'));
  });

  it('remote: "view profile" resolves the cloud @handle via the cloud proxy and opens the CLOUD profile in a new tab', async () => {
    // Cloud-linked CE: the id is a cloud id absent from the local auth DB, so the
    // local by-id read would 404. Resolve through the cloud proxy and deep-link to
    // the cloud profile page (new tab) - never the local router.
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    render(<UserActionMenu userId="8" remote>chip</UserActionMenu>);

    fireEvent.click(screen.getByTestId('user-action-view-profile'));

    await waitFor(() => expect(getRemotePublicProfileById).toHaveBeenCalledWith('8'));
    await waitFor(() =>
      expect(openSpy).toHaveBeenCalledWith('https://cloud.test/app/u/cloud_bob', '_blank', 'noopener,noreferrer'),
    );
    expect(getPublicProfileById).not.toHaveBeenCalled(); // never the local read in remote mode
    expect(push).not.toHaveBeenCalled(); // never client-side routing to a local /app/u
    openSpy.mockRestore();
  });

  it('remote: does not open a tab when the cloud profile has no handle (private/unknown)', async () => {
    getRemotePublicProfileById.mockResolvedValue({ userId: 8, displayName: 'Cloud Bob', handle: null });
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    render(<UserActionMenu userId="8" remote>chip</UserActionMenu>);

    fireEvent.click(screen.getByTestId('user-action-view-profile'));

    await waitFor(() => expect(getRemotePublicProfileById).toHaveBeenCalled());
    expect(openSpy).not.toHaveBeenCalled();
    openSpy.mockRestore();
  });

  it('"send message" opens/gets the 1:1 thread, refreshes the DM inbox cache, and jumps in', async () => {
    render(<UserActionMenu userId={8}>chip</UserActionMenu>);

    fireEvent.click(screen.getByTestId('user-action-send-message'));

    await waitFor(() => expect(openThread).toHaveBeenCalledWith('8'));
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['dm', 'threads'] });
    await waitFor(() => expect(push).toHaveBeenCalledWith('/app/messages/t-42'));
  });

  it('hides "send message" on yourself (you cannot DM yourself)', () => {
    render(<UserActionMenu userId="7">me-chip</UserActionMenu>);

    expect(screen.getByTestId('user-action-view-profile')).toBeInTheDocument();
    expect(screen.queryByTestId('user-action-send-message')).not.toBeInTheDocument();
  });

  it('hides "send message" for a cloud-sourced (remote) user - a CE DM can never reach a cloud user', () => {
    // Cloud-linked CE marketplace: publisher/reviewer ids are CLOUD ids absent from the local
    // auth DB. Opening a DM here would create a one-sided local thread (recipient shows as
    // "Unknown user" with the default agent avatar), so the action is hidden in remote mode.
    render(<UserActionMenu userId="8" remote>chip</UserActionMenu>);

    expect(screen.getByTestId('user-action-view-profile')).toBeInTheDocument(); // view profile stays
    expect(screen.queryByTestId('user-action-send-message')).not.toBeInTheDocument();
  });

  it('keeps "send message" for a non-remote (local) user - the default, unchanged behaviour', () => {
    render(<UserActionMenu userId="8" remote={false}>chip</UserActionMenu>);

    expect(screen.getByTestId('user-action-send-message')).toBeInTheDocument();
  });

  it('regression: inside a card <Link>, clicking the chip must NOT navigate (marketplace click-through)', () => {
    // The marketplace PublicationCard wraps the WHOLE card in a <Link href>. The old
    // stopPropagation-only trigger skipped the Link's React handler but let the
    // anchor's NATIVE default navigation fire - clicking the publisher name opened
    // the application page instead of the menu.
    const anchorClick = vi.fn();
    render(
      <a href="/app/marketplace/pub-1/preview" onClick={anchorClick}>
        <UserActionMenu userId="8">chip</UserActionMenu>
      </a>,
    );

    const notPrevented = fireEvent.click(screen.getByTestId('user-action-trigger'));

    expect(notPrevented).toBe(false); // default prevented → no browser navigation
    expect(anchorClick).not.toHaveBeenCalled(); // no bubble → no client-side routing
  });

  it('does not navigate when the profile has no handle (private/unknown)', async () => {
    getPublicProfileById.mockResolvedValue({ userId: 8, displayName: 'Bob', handle: null });
    render(<UserActionMenu userId="8">chip</UserActionMenu>);

    fireEvent.click(screen.getByTestId('user-action-view-profile'));

    await waitFor(() => expect(getPublicProfileById).toHaveBeenCalled());
    expect(push).not.toHaveBeenCalled();
  });

  it('CE: a cloud publisher with no local profile falls back to the cloud profile (not the login page)', async () => {
    // The `remote` flag is not threaded onto every surface (e.g. the app Info panel),
    // so in CE a cloud publisher can be rendered with remote=false. The local by-id
    // read then 404s (no such local user) and a local push would bounce to the login
    // page - so in CE we fall back to the cloud profile.
    isCe.value = true;
    getPublicProfileById.mockRejectedValue(new Error('404')); // no local profile for a cloud id
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    render(<UserActionMenu userId="8">chip</UserActionMenu>);

    fireEvent.click(screen.getByTestId('user-action-view-profile'));

    await waitFor(() => expect(getRemotePublicProfileById).toHaveBeenCalledWith('8'));
    await waitFor(() =>
      expect(openSpy).toHaveBeenCalledWith('https://cloud.test/app/u/cloud_bob', '_blank', 'noopener,noreferrer'),
    );
    expect(push).not.toHaveBeenCalled(); // never the broken local route in CE
    openSpy.mockRestore();
  });

  it('non-CE: a user with no local profile does NOT fall back to the cloud (cloud build, unchanged)', async () => {
    getPublicProfileById.mockRejectedValue(new Error('404'));
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    render(<UserActionMenu userId="8">chip</UserActionMenu>);

    fireEvent.click(screen.getByTestId('user-action-view-profile'));

    await waitFor(() => expect(getPublicProfileById).toHaveBeenCalled());
    expect(getRemotePublicProfileById).not.toHaveBeenCalled();
    expect(openSpy).not.toHaveBeenCalled();
    openSpy.mockRestore();
  });
});
