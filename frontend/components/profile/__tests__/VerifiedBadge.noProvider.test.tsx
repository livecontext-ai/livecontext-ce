/**
 * @vitest-environment jsdom
 */
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: true }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const getVerifiedUserIds = vi.fn();
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getVerifiedUserIds: (ids: Array<string | number>) => getVerifiedUserIds(ids) },
}));

import { VerifiedBadge } from '../VerifiedBadge';
import { __resetVerifiedUserCache } from '@/lib/api/verifiedUsers';

describe('VerifiedBadge', () => {
  beforeEach(() => {
    getVerifiedUserIds.mockReset();
    __resetVerifiedUserCache();
  });

  it('renders with NO QueryClientProvider in the tree', async () => {
    // Regression: the badge decorates cards that mount in trees without a query
    // client (the chat highlights row, the applications grid, the DM header). A
    // react-query hook throws outright there, which took the whole tree down instead
    // of merely skipping a badge - it broke 154 tests across 30 files before it was
    // caught. Rendering this component bare is the guard.
    getVerifiedUserIds.mockResolvedValue(['7']);

    render(<VerifiedBadge userId="7" />);

    await waitFor(() => {
      expect(screen.getByRole('img', { name: 'verifiedAccount' })).toBeTruthy();
    });
  });

  it('shows nothing for a user who is not verified', async () => {
    getVerifiedUserIds.mockResolvedValue([]);

    const { container } = render(<VerifiedBadge userId="7" />);

    await waitFor(() => expect(getVerifiedUserIds).toHaveBeenCalled());
    expect(container.innerHTML).toBe('');
  });

  it('takes an already-known flag and skips the lookup entirely', async () => {
    // The profile payload carries `verified`, so that page has nothing to look up.
    render(<VerifiedBadge verified />);

    expect(screen.getByRole('img', { name: 'verifiedAccount' })).toBeTruthy();
    expect(getVerifiedUserIds).not.toHaveBeenCalled();
  });

  it('an explicit false wins over any lookup', async () => {
    const { container } = render(<VerifiedBadge userId="7" verified={false} />);

    expect(container.innerHTML).toBe('');
    expect(getVerifiedUserIds).not.toHaveBeenCalled();
  });

  it('never asks about a missing user id', async () => {
    const { container } = render(<VerifiedBadge userId={null} />);

    expect(container.innerHTML).toBe('');
    expect(getVerifiedUserIds).not.toHaveBeenCalled();
  });
});
