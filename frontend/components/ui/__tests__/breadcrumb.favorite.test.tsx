// @vitest-environment jsdom
/**
 * Favorite-toggle star on a breadcrumb segment (used by the application detail
 * breadcrumb). The star is always visible once favorited and hover/focus-revealed
 * otherwise; clicking it fires the segment's onToggle.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { Breadcrumb, type BreadcrumbItem } from '../breadcrumb';

afterEach(() => cleanup());

function renderCrumb(favorite?: BreadcrumbItem['favorite']) {
  const items: BreadcrumbItem[] = [
    { label: 'Applications', href: '/app/applications' },
    { label: 'My App', favorite },
  ];
  return render(<Breadcrumb items={items} />);
}

// next-intl is mocked to echo keys, so the star's accessible name is the i18n key:
// common.removeFromFavorites -> 'removeFromFavorites' (favorited),
// common.addToFavorites -> 'addToFavorites' (not yet favorited).
describe('Breadcrumb favorite star', () => {
  it('renders no star when the segment has no favorite affordance', () => {
    renderCrumb(undefined);
    expect(screen.queryByRole('button', { name: /favorites/i })).not.toBeInTheDocument();
  });

  it('favorited → star is shown immediately (no hover needed) and is pressed', () => {
    renderCrumb({ isFavorite: true, onToggle: vi.fn() });
    const star = screen.getByRole('button', { name: 'removeFromFavorites' });
    expect(star).toBeInTheDocument();
    expect(star).toHaveAttribute('aria-pressed', 'true');
  });

  it('not favorited → star is hidden until the segment is hovered, then toggles on click', () => {
    const onToggle = vi.fn();
    renderCrumb({ isFavorite: false, onToggle });

    // Hidden before hover.
    expect(screen.queryByRole('button', { name: 'addToFavorites' })).not.toBeInTheDocument();

    // The label lives in an inner span; the hover handler is on its parent segment.
    const segment = screen.getByText('My App').parentElement as HTMLElement;
    fireEvent.mouseEnter(segment);

    const star = screen.getByRole('button', { name: 'addToFavorites' });
    expect(star).toBeInTheDocument();
    fireEvent.click(star);
    expect(onToggle).toHaveBeenCalledTimes(1);
  });
});
