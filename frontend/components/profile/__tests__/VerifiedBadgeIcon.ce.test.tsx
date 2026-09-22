/**
 * @vitest-environment jsdom
 */
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// Self-hosted. The badge is a managed-cloud feature: a self-hosted install promotes
// its very first user to ADMIN, so a role-derived check would decorate every solo
// install owner with a seal that says nothing to anyone. The edition is resolved at
// module load, hence a separate file from the managed-cloud suite.
vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: false }));

import { VerifiedBadgeIcon } from '../VerifiedBadgeIcon';

describe('VerifiedBadgeIcon on a self-hosted install', () => {
  it('renders nothing even when the payload claims the account is verified', () => {
    // This is the SECOND of two independent locks. The backend already refuses to
    // mark anyone verified off managed cloud; this one means the badge cannot appear
    // in Community Edition even if a payload somehow arrived claiming it - which is
    // exactly the shape of a cloud-linked CE rendering remote marketplace content.
    const { container } = render(<VerifiedBadgeIcon verified label="Verified account" />);

    expect(container.innerHTML).toBe('');
  });
});
