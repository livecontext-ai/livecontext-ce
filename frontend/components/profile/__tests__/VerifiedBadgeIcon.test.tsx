/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// Managed cloud: the edition is resolved at module load, so the self-hosted case
// needs its own file (VerifiedBadgeIcon.ce.test.tsx) rather than a branch here.
vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: true }));

import { VerifiedBadgeIcon } from '../VerifiedBadgeIcon';

describe('VerifiedBadgeIcon', () => {
  it('renders the check for a verified account, labelled for screen readers', () => {
    render(<VerifiedBadgeIcon verified label="Verified account" />);

    expect(screen.getByRole('img', { name: 'Verified account' })).toBeTruthy();
  });

  it('renders nothing at all when the account is not verified', () => {
    const { container } = render(<VerifiedBadgeIcon verified={false} />);

    expect(container.innerHTML).toBe('');
  });

  it('treats a missing flag as not verified', () => {
    // The flag arrives from a payload that may simply omit it (an older row, a
    // degraded lookup). Absent must never read as verified.
    const { container } = render(<VerifiedBadgeIcon />);

    expect(container.innerHTML).toBe('');
  });

  it('paints the seal filled and the check stroked in white, so the tick stays readable', () => {
    // Two paths with DIFFERENT paint is the whole reason this is inline SVG rather
    // than an icon component: one uniform fill turns the open check path into a wedge.
    const { container } = render(<VerifiedBadgeIcon verified />);
    const paths = container.querySelectorAll('path');

    expect(paths).toHaveLength(2);
    expect(paths[0].getAttribute('fill')).toBe('currentColor');
    expect(paths[1].getAttribute('fill')).toBe('none');
    expect(paths[1].getAttribute('stroke')).toBe('#fff');
  });

  it('sizes itself to the typography slot it sits in', () => {
    const { container: xs } = render(<VerifiedBadgeIcon verified size="xs" />);
    const { container: lg } = render(<VerifiedBadgeIcon verified size="lg" />);

    expect(xs.querySelector('svg')?.getAttribute('width')).toBe('12');
    expect(lg.querySelector('svg')?.getAttribute('width')).toBe('20');
  });

  it('defaults to an English label, for the provider-less public pages', () => {
    // The server-rendered marketplace renders outside the [locale] tree, where
    // there is no translator to call.
    render(<VerifiedBadgeIcon verified />);

    expect(screen.getByRole('img', { name: 'Verified account' })).toBeTruthy();
  });
});
