// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import FeatureLabel from '../FeatureLabel';

afterEach(() => cleanup());

describe('FeatureLabel', () => {
  it('renders a plain feature with no info icon when there is no || tooltip', () => {
    render(<FeatureLabel feature="100 MB storage" />);
    expect(screen.getByText('100 MB storage')).toBeTruthy();
    // No tooltip trigger button when the feature carries no embedded tooltip.
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('renders the label plus an accessible info "i" trigger when a ||tooltip is present', () => {
    render(
      <FeatureLabel feature="1,000 credits per month||Free credits power your workflows." />
    );
    // The label is shown without the delimiter and without the tooltip text inline.
    expect(screen.getByText('1,000 credits per month')).toBeTruthy();
    expect(screen.queryByText(/Free credits power your workflows/)).toBeNull(); // closed by default
    // The info icon is an accessible button named after the feature label.
    expect(screen.getByRole('button', { name: '1,000 credits per month' })).toBeTruthy();
  });

  it('splits on the first || only and keeps the label exact', () => {
    render(<FeatureLabel feature="Label||tip" />);
    expect(screen.getByText('Label')).toBeTruthy();
    expect(screen.queryByText('Label||tip')).toBeNull();
  });
});
