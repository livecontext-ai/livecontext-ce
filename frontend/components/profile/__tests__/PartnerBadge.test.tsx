// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: true }));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const getUserBadges = vi.fn();
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getUserBadges: (ids: Array<string | number>) => getUserBadges(ids) },
}));

import { VerifiedBadge } from '../VerifiedBadge';
import { PartnerBadgeIcon } from '../PartnerBadgeIcon';
import { VerifiedBadgeIcon } from '../VerifiedBadgeIcon';
import { __resetVerifiedUserCache } from '@/lib/api/verifiedUsers';

describe('Official-partner badge', () => {
  beforeEach(() => {
    getUserBadges.mockReset();
    __resetVerifiedUserCache();
  });
  afterEach(cleanup);

  it('resolves from the user id and renders the gold seal after the name', async () => {
    getUserBadges.mockResolvedValue({ verified: [], partners: ['7'] });

    const { container } = render(<VerifiedBadge userId="7" />);

    await waitFor(() => expect(screen.getByRole('img', { name: 'partnerAccount' })).toBeTruthy());
    expect(screen.queryByRole('img', { name: 'verifiedAccount' })).toBeNull();
    expect(container.querySelector('[data-badge="partner"]')).toBeTruthy();
  });

  it('a user carrying both shows ONE badge, the gold partner seal (the same seal twice would stutter)', async () => {
    getUserBadges.mockResolvedValue({ verified: ['7'], partners: ['7'] });

    render(<VerifiedBadge userId="7" />);

    await waitFor(() => expect(screen.getByRole('img', { name: 'partnerAccount' })).toBeTruthy());
    expect(screen.getAllByRole('img')).toHaveLength(1);
    expect(screen.queryByRole('img', { name: 'verifiedAccount' })).toBeNull();
  });

  it('a verified user who is not a partner keeps the blue check', async () => {
    getUserBadges.mockResolvedValue({ verified: ['7'], partners: [] });

    render(<VerifiedBadge userId="7" />);

    await waitFor(() => expect(screen.getByRole('img', { name: 'verifiedAccount' })).toBeTruthy());
    expect(screen.queryByRole('img', { name: 'partnerAccount' })).toBeNull();
  });

  it('the partner seal is the verified badge itself in gold: same seal, same check, another colour', () => {
    render(<><VerifiedBadgeIcon verified label="v" /><PartnerBadgeIcon partner label="p" /></>);

    const verifiedPaths = screen.getByRole('img', { name: 'v' }).querySelectorAll('path');
    const partnerPaths = screen.getByRole('img', { name: 'p' }).querySelectorAll('path');
    expect(partnerPaths[0].getAttribute('d')).toBe(verifiedPaths[0].getAttribute('d'));
    expect(partnerPaths[1].getAttribute('d')).toBe(verifiedPaths[1].getAttribute('d'));
    // Told apart by colour and by name, never by shape.
    expect(screen.getByRole('img', { name: 'p' }).getAttribute('class')).toMatch(/text-\[#[0-9a-f]{6}\]/);
    expect(screen.getByRole('img', { name: 'v' }).getAttribute('class')).toContain('#1d9bf0');
  });

  it('regression: in light mode the gold seal and its white check reach 3:1 contrast (non-text contrast), on a white page too', () => {
    render(<PartnerBadgeIcon partner label="p" />);

    // The light-mode fill is the first text-[#hex] class (the dark one is prefixed dark:).
    const light = screen.getByRole('img', { name: 'p' }).getAttribute('class')!.match(/(?:^|\s)text-\[(#[0-9a-f]{6})\]/)![1];
    const luminance = (hex: string) => {
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
        .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    // White check on the seal, and the seal on a white page: the same pair of colours.
    expect((1 + 0.05) / (luminance(light) + 0.05)).toBeGreaterThanOrEqual(3);
    // In dark mode the seal is the bright gold: the check switches to a dark ink to stay readable.
    const check = screen.getByRole('img', { name: 'p' }).querySelectorAll('path')[1];
    const darkInk = check.getAttribute('class')!.match(/dark:stroke-\[(#[0-9a-f]{6})\]/)![1];
    const darkSeal = screen.getByRole('img', { name: 'p' }).getAttribute('class')!.match(/dark:text-\[(#[0-9a-f]{6})\]/)![1];
    expect((luminance(darkSeal) + 0.05) / (luminance(darkInk) + 0.05)).toBeGreaterThanOrEqual(3);
  });

  it('when both flags are known, no lookup happens', () => {
    render(<VerifiedBadge userId="7" verified={false} partner />);

    expect(screen.getByRole('img', { name: 'partnerAccount' })).toBeTruthy();
    expect(getUserBadges).not.toHaveBeenCalled();
  });

  it('when only verified is known, the partner flag is still looked up', async () => {
    // The profile payload of an older backend carries `verified` but no `partner`: the badge
    // must not read that as "not a partner".
    getUserBadges.mockResolvedValue({ verified: [], partners: ['7'] });

    render(<VerifiedBadge userId="7" verified />);

    await waitFor(() => expect(screen.getByRole('img', { name: 'partnerAccount' })).toBeTruthy());
    // Resolved as a partner: the gold seal replaces the blue check.
    expect(screen.queryByRole('img', { name: 'verifiedAccount' })).toBeNull();
    expect(getUserBadges).toHaveBeenCalledTimes(1);
  });

  it('the icon renders nothing for a non-partner', () => {
    const { container } = render(<PartnerBadgeIcon partner={false} />);
    expect(container.innerHTML).toBe('');
  });

  it('an explicit pixel size wins over the named size', () => {
    render(<PartnerBadgeIcon partner px={64} label="seal" />);
    const svg = screen.getByRole('img', { name: 'seal' });
    expect(svg.getAttribute('width')).toBe('64');
  });
});
