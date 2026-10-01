// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));

import { IdentityBadgeIcon } from '../IdentityBadgeIcon';
import { PartnerTierChip } from '@/components/partner/PartnerTierChip';

describe('IdentityBadgeIcon: one badge next to a name, never two', () => {
  afterEach(cleanup);

  it('a partner shows the gold seal only, even when verified', () => {
    render(<IdentityBadgeIcon verified partner verifiedLabel="Verified" partnerLabel="Partner" />);

    expect(screen.getByRole('img', { name: 'Partner' })).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'Verified' })).toBeNull();
  });

  it('a verified account that is not a partner keeps the blue check', () => {
    render(<IdentityBadgeIcon verified partner={false} verifiedLabel="Verified" partnerLabel="Partner" />);

    expect(screen.getByRole('img', { name: 'Verified' })).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'Partner' })).toBeNull();
  });

  it('neither: nothing at all', () => {
    const { container } = render(<IdentityBadgeIcon verified={false} partner={null} />);
    expect(container.innerHTML).toBe('');
  });
});

describe('PartnerTierChip', () => {
  afterEach(cleanup);

  it('names the tier and carries it for styling', () => {
    render(<PartnerTierChip tier="platinum" label="Platinum partner" />);

    const chip = screen.getByText('Platinum partner');
    expect(chip.getAttribute('data-tier')).toBe('platinum');
  });
});
