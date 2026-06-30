// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { FavoriteStarButton } from '../FavoriteStarButton';

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('FavoriteStarButton', () => {
  it('renders the "add" label and is not pressed when not favorited', () => {
    render(<FavoriteStarButton isFavorite={false} onToggle={() => {}} />);
    const btn = screen.getByLabelText('addToFavorites');
    expect(btn).toBeInTheDocument();
    expect(btn).toHaveAttribute('aria-pressed', 'false');
  });

  it('renders the "remove" label and is pressed when favorited', () => {
    render(<FavoriteStarButton isFavorite onToggle={() => {}} />);
    const btn = screen.getByLabelText('removeFromFavorites');
    expect(btn).toHaveAttribute('aria-pressed', 'true');
  });

  it('calls onToggle on click', () => {
    const onToggle = vi.fn();
    render(<FavoriteStarButton isFavorite={false} onToggle={onToggle} />);
    fireEvent.click(screen.getByLabelText('addToFavorites'));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('stops click propagation so the surrounding card does not navigate', () => {
    const onToggle = vi.fn();
    const cardClick = vi.fn();
    render(
      <div onClick={cardClick}>
        <FavoriteStarButton isFavorite={false} onToggle={onToggle} />
      </div>,
    );
    fireEvent.click(screen.getByLabelText('addToFavorites'));
    expect(onToggle).toHaveBeenCalledTimes(1);
    expect(cardClick).not.toHaveBeenCalled();
  });

  it('merges a className override (e.g. a different corner placement)', () => {
    render(<FavoriteStarButton isFavorite={false} onToggle={() => {}} className="top-2 left-2 bottom-auto" />);
    expect(screen.getByLabelText('addToFavorites').className).toContain('top-2');
  });
});
