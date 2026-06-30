// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { AvatarDisplay, AVATAR_PRESETS } from '../AvatarPicker';

afterEach(cleanup);

// AvatarDisplay is used app-wide (DM rows, chat header, fleet…) - these pin the
// anti-blink behaviour added for custom (network) avatar URLs: pulse placeholder
// until painted, fade-in on load, preset fallback on error. Preset/initials-less
// branches are untouched.
describe('AvatarDisplay - custom URL anti-blink', () => {
  it('shows a pulsing placeholder until the image loads, then fades it in', () => {
    const { container } = render(<AvatarDisplay avatarUrl="/api/users/8/avatar" name="Bob" />);

    expect(container.querySelector('.animate-pulse')).toBeInTheDocument();
    const img = screen.getByAltText('Bob') as HTMLImageElement;
    expect(img.className).toContain('opacity-0');

    fireEvent.load(img);

    expect(img.className).toContain('opacity-100');
    expect(container.querySelector('.animate-pulse')).not.toBeInTheDocument();
  });

  it('falls back to the default preset on a failed load (never a broken img)', () => {
    render(<AvatarDisplay avatarUrl="/api/users/8/avatar" name="Bob" />);
    fireEvent.error(screen.getByAltText('Bob'));

    expect((screen.getByAltText('Bob') as HTMLImageElement).src).toContain(AVATAR_PRESETS[0].image);
  });

  it('a preset avatarUrl keeps the direct (non-network) render path', () => {
    const { container } = render(<AvatarDisplay avatarUrl={AVATAR_PRESETS[1].id} name="Echo" />);

    expect(container.querySelector('.animate-pulse')).not.toBeInTheDocument();
    expect((screen.getByAltText('Echo') as HTMLImageElement).src).toContain(AVATAR_PRESETS[1].image);
  });
});
