// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => {
    const map: Record<string, string> = {
      cloud: 'Cloud',
      selfHosted: 'Self-hosted',
      or: 'or',
      availableOn: 'Available on Cloud and self-hosted',
    };
    return map[key] ?? key;
  },
}));

import DeploymentBadge from '../DeploymentBadge';

afterEach(cleanup);

describe('DeploymentBadge', () => {
  it('shows both deployment labels joined by "or"', () => {
    render(<DeploymentBadge />);
    expect(screen.getByText('Cloud')).toBeTruthy();
    expect(screen.getByText('or')).toBeTruthy();
    expect(screen.getByText('Self-hosted')).toBeTruthy();
  });

  it('is a static, non-interactive note (no buttons, no links)', () => {
    render(<DeploymentBadge />);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryAllByRole('link')).toHaveLength(0);
    const note = screen.getByRole('note');
    expect(note.getAttribute('aria-label')).toBe('Available on Cloud and self-hosted');
  });

  it('forwards a custom className onto the chip', () => {
    render(<DeploymentBadge className="my-custom-class" />);
    expect(screen.getByRole('note').className).toContain('my-custom-class');
  });
});
