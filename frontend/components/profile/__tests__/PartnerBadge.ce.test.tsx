// @vitest-environment jsdom
import React from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// Self-hosted: the partner program does not exist there, so the seal must never render,
// even if a payload claimed it.
vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: false }));

import { PartnerBadgeIcon } from '../PartnerBadgeIcon';

describe('PartnerBadgeIcon on a self-hosted install', () => {
  it('renders nothing even when told the user is a partner', () => {
    const { container } = render(<PartnerBadgeIcon partner size="lg" />);
    expect(container.innerHTML).toBe('');
  });
});
