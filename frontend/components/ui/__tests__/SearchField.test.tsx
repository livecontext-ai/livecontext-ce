/**
 * @vitest-environment jsdom
 *
 * SearchField: shared clean search input (leading icon, optional shortcut
 * hint while empty, accessible clear button while filled). Shared by the
 * sidebar search fields, the conversation search modal and the global
 * search bar, so its contract is pinned here directly.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

import { SearchField } from '../search-field';

afterEach(cleanup);

describe('SearchField', () => {
  it('renders an accessible, clickable clear button while the field has a value', () => {
    const onClear = vi.fn();
    render(
      <SearchField value="hello" onChange={() => {}} onClear={onClear} clearLabel="Clear search" />
    );
    const clear = screen.getByRole('button', { name: 'Clear search' });
    fireEvent.click(clear);
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('shows the shortcut hint only while empty', () => {
    const { rerender } = render(
      <SearchField value="" onChange={() => {}} onClear={() => {}} shortcutHint="Ctrl K" />
    );
    expect(screen.getByText('Ctrl K')).toBeTruthy();
    rerender(
      <SearchField value="x" onChange={() => {}} onClear={() => {}} shortcutHint="Ctrl K" />
    );
    expect(screen.queryByText('Ctrl K')).toBeNull();
  });

  it('hides the clear button while empty', () => {
    render(<SearchField value="" onChange={() => {}} onClear={() => {}} clearLabel="Clear" />);
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();
  });

  it('forwards input props (placeholder, data-testid) to the input element', () => {
    render(
      <SearchField value="" onChange={() => {}} placeholder="Find..." data-testid="sf-input" />
    );
    const input = screen.getByTestId('sf-input') as HTMLInputElement;
    expect(input.placeholder).toBe('Find...');
  });
});
