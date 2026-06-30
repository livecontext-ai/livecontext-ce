// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
}));

// Radix Select relies on pointer APIs jsdom lacks; swap it for a native <select>
// that still fires onValueChange so the visibility-change path is exercised faithfully.
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children }: any) => (
    <select data-testid="visibility-select" value={value} onChange={(e) => onValueChange(e.target.value)}>
      {children}
    </select>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
}));

// Radix Tooltip also needs pointer APIs - render trigger+content inline.
vi.mock('@/components/ui/tooltip', () => ({
  TooltipProvider: ({ children }: any) => <>{children}</>,
  Tooltip: ({ children }: any) => <>{children}</>,
  TooltipTrigger: ({ children }: any) => <>{children}</>,
  TooltipContent: ({ children }: any) => <div data-testid="tooltip-content">{children}</div>,
}));

const { updateUserProfile, useUserProfileMock, getHandleStatus } = vi.hoisted(() => ({
  updateUserProfile: vi.fn(),
  useUserProfileMock: vi.fn(),
  getHandleStatus: vi.fn(),
}));
vi.mock('@/hooks/useUserProfile', () => ({ useUserProfile: useUserProfileMock }));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getHandleStatus },
}));

import { PublicProfileSettingsCard } from '../PublicProfileSettingsCard';

const baseProfile = {
  id: '7',
  username: 'alice',
  handle: 'alice_a',
  bio: 'Hi',
  profileVisibility: 'PUBLIC',
};

// The card auto-saves on a 600ms debounce - advance fake timers past it and flush
// the (mocked, immediately-resolved) save promise inside act().
async function flushDebounce() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(600);
  });
}

describe('PublicProfileSettingsCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
    updateUserProfile.mockResolvedValue({});
    getHandleStatus.mockResolvedValue({ canChange: true, nextChangeDate: null });
    useUserProfileMock.mockReturnValue({ profile: baseProfile, updateUserProfile, isLoading: false });
  });
  afterEach(() => {
    vi.useRealTimers();
    cleanup();
  });

  it('seeds the fields from the loaded profile', () => {
    render(<PublicProfileSettingsCard />);
    expect((screen.getByTestId('profile-bio') as HTMLTextAreaElement).value).toBe('Hi');
    expect((screen.getByTestId('visibility-select') as HTMLSelectElement).value).toBe('PUBLIC');
  });

  it('has no Save button - auto-saves the edited bio + visibility after the debounce', async () => {
    render(<PublicProfileSettingsCard />);
    expect(screen.queryByTestId('profile-save')).toBeNull();

    fireEvent.change(screen.getByTestId('profile-bio'), { target: { value: 'Updated bio' } });
    fireEvent.change(screen.getByTestId('visibility-select'), { target: { value: 'PRIVATE' } });

    // Nothing persisted until the debounce window elapses.
    expect(updateUserProfile).not.toHaveBeenCalled();
    await flushDebounce();

    expect(updateUserProfile).toHaveBeenCalledTimes(1);
    expect(updateUserProfile).toHaveBeenCalledWith(
      expect.objectContaining({
        bio: 'Updated bio',
        profileVisibility: 'PRIVATE',
      }),
    );
  });

  it('collapses rapid keystrokes into a single debounced save', async () => {
    render(<PublicProfileSettingsCard />);
    const bio = screen.getByTestId('profile-bio');

    fireEvent.change(bio, { target: { value: 'a' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(300); });
    fireEvent.change(bio, { target: { value: 'ab' } }); // resets the 600ms timer
    await act(async () => { await vi.advanceTimersByTimeAsync(300); });
    expect(updateUserProfile).not.toHaveBeenCalled();

    await act(async () => { await vi.advanceTimersByTimeAsync(300); });
    expect(updateUserProfile).toHaveBeenCalledTimes(1);
    expect(updateUserProfile.mock.calls[0][0].bio).toBe('ab');
  });

  it('links to the IN-APP profile by @handle (never the numeric tenant id) and has no website/social editors', () => {
    render(<PublicProfileSettingsCard />);
    expect(screen.getByText('profile.viewPublicProfile').closest('a')).toHaveAttribute('href', '/app/u/alice_a');
    expect(screen.queryByTestId('profile-website')).toBeNull();
    expect(screen.queryByTestId('profile-add-link')).toBeNull();
  });

  it('renders the handle read-only (with @ prefix) plus a pencil toggle - same UX as display name', () => {
    render(<PublicProfileSettingsCard />);

    const handleInput = screen.getByTestId('profile-handle') as HTMLInputElement;
    expect(handleInput).toBeDisabled();
    expect(handleInput.value).toBe('@alice_a');
    expect(screen.getByTestId('profile-handle-edit')).toBeEnabled();
    expect(screen.getByTestId('profile-handle-info')).toBeInTheDocument();
    // Not editing yet → no save/cancel controls.
    expect(screen.queryByTestId('profile-handle-save')).toBeNull();
    expect(screen.queryByTestId('profile-handle-cancel')).toBeNull();
  });

  it('regression: the @handle is NEVER auto-saved - typing it does not fire the debounced save', async () => {
    render(<PublicProfileSettingsCard />);
    fireEvent.click(screen.getByTestId('profile-handle-edit'));
    const handleInput = screen.getByTestId('profile-handle') as HTMLInputElement;
    expect(handleInput.value).toBe('alice_a');

    fireEvent.change(handleInput, { target: { value: 'alice_dev' } });
    await flushDebounce();

    // Pre-fix the card debounced-saved every keystroke, which would consume the
    // 1-change-per-week cooldown on a half-typed value.
    expect(updateUserProfile).not.toHaveBeenCalled();
  });

  it('Enter in the edit input saves the handle (same payload as the Save button)', async () => {
    updateUserProfile.mockResolvedValue({ ...baseProfile, handle: 'alice_dev' });
    render(<PublicProfileSettingsCard />);

    fireEvent.click(screen.getByTestId('profile-handle-edit'));
    const handleInput = screen.getByTestId('profile-handle');
    fireEvent.change(handleInput, { target: { value: 'alice_dev' } });
    await act(async () => {
      fireEvent.keyDown(handleInput, { key: 'Enter' });
    });

    expect(updateUserProfile).toHaveBeenCalledWith({ handle: 'alice_dev' });
  });

  it('cancel exits edit mode and restores the saved handle', () => {
    render(<PublicProfileSettingsCard />);
    fireEvent.click(screen.getByTestId('profile-handle-edit'));
    fireEvent.change(screen.getByTestId('profile-handle'), { target: { value: 'half_typed' } });

    fireEvent.click(screen.getByTestId('profile-handle-cancel'));

    expect(updateUserProfile).not.toHaveBeenCalled();
    const handleInput = screen.getByTestId('profile-handle') as HTMLInputElement;
    expect(handleInput).toBeDisabled();
    expect(handleInput.value).toBe('@alice_a');
  });

  it('regression: the debounced bio/visibility save does not carry the handle field', async () => {
    render(<PublicProfileSettingsCard />);

    fireEvent.change(screen.getByTestId('profile-bio'), { target: { value: 'Updated bio' } });
    await flushDebounce();

    expect(updateUserProfile).toHaveBeenCalledTimes(1);
    expect(updateUserProfile.mock.calls[0][0]).not.toHaveProperty('handle');
  });

  it('saves the @handle only via its explicit Save button, with a handle-only payload', async () => {
    updateUserProfile.mockResolvedValue({ ...baseProfile, handle: 'alice_dev' });
    render(<PublicProfileSettingsCard />);

    fireEvent.click(screen.getByTestId('profile-handle-edit'));
    fireEvent.change(screen.getByTestId('profile-handle'), { target: { value: 'alice_dev' } });
    await act(async () => {
      fireEvent.click(screen.getByTestId('profile-handle-save'));
    });

    expect(updateUserProfile).toHaveBeenCalledTimes(1);
    expect(updateUserProfile).toHaveBeenCalledWith({ handle: 'alice_dev' });
    // The change consumed the weekly slot → the status is re-fetched.
    expect(getHandleStatus).toHaveBeenCalledTimes(2); // mount + post-save
    // Successful save exits edit mode (read-only input with the new value).
    const handleInput = screen.getByTestId('profile-handle') as HTMLInputElement;
    expect(handleInput).toBeDisabled();
    expect(handleInput.value).toBe('@alice_dev');
  });

  it('disables the pencil and shows the next-change date while in cooldown', async () => {
    getHandleStatus.mockResolvedValue({ canChange: false, nextChangeDate: '2026-06-15T10:00:00' });
    render(<PublicProfileSettingsCard />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0); // let the status fetch resolve
    });

    // The cooldown gates the pencil - edit mode is unreachable, so no save button either.
    expect(screen.getByTestId('profile-handle-edit')).toBeDisabled();
    expect(screen.getByTestId('profile-handle-cooldown')).toBeInTheDocument();
    expect(screen.queryByTestId('profile-handle-save')).toBeNull();
  });

  it('shows taken/invalid feedback and reverts when the server keeps the old handle (200 unchanged)', async () => {
    updateUserProfile.mockResolvedValue({ ...baseProfile, handle: 'alice_a' }); // not applied
    render(<PublicProfileSettingsCard />);

    fireEvent.click(screen.getByTestId('profile-handle-edit'));
    fireEvent.change(screen.getByTestId('profile-handle'), { target: { value: 'taken_handle' } });
    await act(async () => {
      fireEvent.click(screen.getByTestId('profile-handle-save'));
    });

    // Stays in edit mode so the user can immediately try another handle.
    expect(screen.getByText('profile.handleTakenOrInvalid')).toBeInTheDocument();
    expect((screen.getByTestId('profile-handle') as HTMLInputElement).value).toBe('alice_a');
  });

  it('treats a case-variant of the current handle as a silent no-op (no save, no false "taken" error)', async () => {
    render(<PublicProfileSettingsCard />);

    fireEvent.click(screen.getByTestId('profile-handle-edit'));
    fireEvent.change(screen.getByTestId('profile-handle'), { target: { value: 'Alice_A' } });
    await act(async () => {
      fireEvent.click(screen.getByTestId('profile-handle-save'));
    });

    expect(updateUserProfile).not.toHaveBeenCalled();
    expect(screen.queryByText('profile.handleTakenOrInvalid')).toBeNull();
    // The no-op exits edit mode back to the read-only @value.
    expect((screen.getByTestId('profile-handle') as HTMLInputElement).value).toBe('@alice_a');
  });

  it('re-syncs the cooldown status and shows an error when the handle save fails (429 → null)', async () => {
    updateUserProfile.mockResolvedValue(null);
    render(<PublicProfileSettingsCard />);

    fireEvent.click(screen.getByTestId('profile-handle-edit'));
    fireEvent.change(screen.getByTestId('profile-handle'), { target: { value: 'alice_dev' } });
    getHandleStatus.mockResolvedValue({ canChange: false, nextChangeDate: '2026-06-15T10:00:00' });
    await act(async () => {
      fireEvent.click(screen.getByTestId('profile-handle-save'));
    });

    expect(screen.getByText('profile.handleSaveError')).toBeInTheDocument();
    expect(screen.getByTestId('profile-handle-cooldown')).toBeInTheDocument();
  });

  it('seeds only once - a profile refetch mid-edit does not clobber an in-flight change', () => {
    const { rerender } = render(<PublicProfileSettingsCard />);

    fireEvent.change(screen.getByTestId('profile-bio'), { target: { value: 'My new bio' } });

    // Simulate the post-save refetch landing with a DIFFERENT server value. Because the
    // form seeds once (seededRef), the user's in-flight edit must survive.
    useUserProfileMock.mockReturnValue({
      profile: { ...baseProfile, bio: 'Stale server bio' },
      updateUserProfile,
      isLoading: false,
    });
    rerender(<PublicProfileSettingsCard />);

    expect((screen.getByTestId('profile-bio') as HTMLTextAreaElement).value).toBe('My new bio');
  });

  it('flushes a pending debounced save on unmount (no edit is lost on navigation)', async () => {
    const { unmount } = render(<PublicProfileSettingsCard />);

    fireEvent.change(screen.getByTestId('profile-bio'), { target: { value: 'Quick edit' } });
    expect(updateUserProfile).not.toHaveBeenCalled(); // debounce hasn't fired yet

    await act(async () => {
      unmount();
    });

    expect(updateUserProfile).toHaveBeenCalledTimes(1);
    expect(updateUserProfile.mock.calls[0][0].bio).toBe('Quick edit');
  });

  it('does not show the "saved" indicator when the save fails (resolves null)', async () => {
    updateUserProfile.mockResolvedValue(null);
    render(<PublicProfileSettingsCard />);

    fireEvent.change(screen.getByTestId('profile-bio'), { target: { value: 'x' } });
    await flushDebounce();

    expect(updateUserProfile).toHaveBeenCalled();
    expect(screen.queryByText('profile.saved')).toBeNull();
  });
});
