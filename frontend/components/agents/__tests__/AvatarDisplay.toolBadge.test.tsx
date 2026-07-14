// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import { AvatarDisplay, AVATAR_PRESETS } from '../AvatarPicker';
import { PRESET_GRADIENTS } from '../avatarColors';

afterEach(cleanup);

function renderAvatar(avatarUrl?: string) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <AvatarDisplay avatarUrl={avatarUrl} name="Echo" />
    </NextIntlClientProvider>,
  );
}

// The tool badge is the display half of the '?tool=<id>' avatar customization:
// it must render on the circle's bottom-right edge wherever AvatarDisplay is used,
// pick up the avatar's own primary color, and stay absent for tool-less values.
describe('AvatarDisplay - tool badge', () => {
  it('renders the badge with its localized tooltip and the preset default primary color', () => {
    renderAvatar('preset:blue?tool=wrench');

    const badge = screen.getByTestId('avatar-tool-badge');
    expect(badge).toHaveAttribute('title', 'Wrench');
    // Background = the preset's own first gradient stop (JSDOM normalizes hex to rgb).
    const [c1] = PRESET_GRADIENTS['preset:blue'];
    expect(badge.style.backgroundColor).toBe(hexToRgb(c1));
  });

  it('uses the CUSTOM primary color when the avatar is recolored', () => {
    renderAvatar('preset:blue?c1=FF0000&c2=00FF00&tool=rocket');

    const badge = screen.getByTestId('avatar-tool-badge');
    expect(badge).toHaveAttribute('title', 'Rocket');
    expect(badge.style.backgroundColor).toBe('rgb(255, 0, 0)');
  });

  it('renders no badge for a tool-less preset or an unknown tool id', () => {
    const { unmount } = renderAvatar(AVATAR_PRESETS[1].id);
    expect(screen.queryByTestId('avatar-tool-badge')).not.toBeInTheDocument();
    unmount();

    renderAvatar('preset:blue?tool=sword');
    expect(screen.queryByTestId('avatar-tool-badge')).not.toBeInTheDocument();
  });

  it('keeps the avatar image itself intact next to the badge', () => {
    renderAvatar('preset:blue?tool=wrench');
    expect((screen.getByAltText('Echo') as HTMLImageElement).src).toContain(AVATAR_PRESETS[1].image);
  });
});

function hexToRgb(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
}
