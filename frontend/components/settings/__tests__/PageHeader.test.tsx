// @vitest-environment jsdom
/**
 * `titleAddon` sits on the title's line but outside the heading element, so an info button next
 * to a section title never becomes part of that heading's accessible name.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { Bell } from 'lucide-react';
import { PageHeader } from '../PageHeader';

afterEach(cleanup);

describe('PageHeader', () => {
  it('keeps the addon out of the heading name while still rendering it', () => {
    render(
      <PageHeader
        icon={Bell}
        title="Notifications"
        subtitle="Where alerts go"
        headingLevel="h2"
        titleAddon={<button type="button">About anti-spam</button>}
      />,
    );
    const heading = screen.getByRole('heading', { level: 2 });
    expect(heading).toHaveAccessibleName('Notifications');
    expect(heading).not.toContainElement(screen.getByRole('button', { name: 'About anti-spam' }));
  });

  it('without an addon renders the plain heading, h1 by default', () => {
    render(<PageHeader icon={Bell} title="Storage" subtitle="Usage" />);
    expect(screen.getByRole('heading', { level: 1, name: 'Storage' })).toBeInTheDocument();
  });
});
