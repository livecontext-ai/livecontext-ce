// @vitest-environment jsdom
/**
 * InterfaceToolbar - semantic page label + re-execution badge (run-mode context).
 *
 * The toolbar's pagination counter historically read "{page+1} / {totalPages}"
 * with no semantics. Callers can now pass:
 *   - `pageLabel`: a semantic replacement (e.g. "Item 2/3") - bare counter
 *     stays the fallback when absent,
 *   - `pageBadge`: a small contextual badge (e.g. "Re-execution 2") that must
 *     render even WITHOUT pagination so a single-page re-run stays visible.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { InterfaceToolbar } from '../InterfaceToolbar';

const noop = () => undefined;

afterEach(cleanup);

describe('InterfaceToolbar - pageLabel / pageBadge', () => {
  it('renders the semantic pageLabel instead of the bare counter (light variant)', () => {
    const { getByTestId } = render(
      <InterfaceToolbar currentPage={1} totalPages={3} pageLabel="Item 2/3" onPrevious={noop} onNext={noop} />,
    );
    expect(getByTestId('interface-pagination-label').textContent).toBe('Item 2/3');
  });

  it('falls back to the bare "{page+1} / {totalPages}" counter when pageLabel is absent', () => {
    const { getByTestId } = render(
      <InterfaceToolbar currentPage={1} totalPages={3} onPrevious={noop} onNext={noop} />,
    );
    expect(getByTestId('interface-pagination-label').textContent).toBe('2 / 3');
  });

  it('renders the re-execution badge alongside the pagination group', () => {
    const { getByTestId } = render(
      <InterfaceToolbar currentPage={0} totalPages={3} pageBadge="Re-execution 2" onPrevious={noop} onNext={noop} />,
    );
    expect(getByTestId('interface-pagination-rerun-badge').textContent).toBe('Re-execution 2');
  });

  it('still renders the badge when there is NO pagination (single-page re-run)', () => {
    const { getByTestId, queryByTestId } = render(
      <InterfaceToolbar currentPage={0} totalPages={1} pageBadge="Re-execution 2" onPrevious={noop} onNext={noop} />,
    );
    expect(getByTestId('interface-pagination-rerun-badge').textContent).toBe('Re-execution 2');
    // No chevrons/counter - the badge stands alone.
    expect(queryByTestId('interface-pagination-label')).toBeNull();
  });

  it('renders no badge when pageBadge is absent', () => {
    const { queryByTestId } = render(
      <InterfaceToolbar currentPage={0} totalPages={3} onPrevious={noop} onNext={noop} />,
    );
    expect(queryByTestId('interface-pagination-rerun-badge')).toBeNull();
  });

  it('dark variant also honors pageLabel and the badge', () => {
    const { getByTestId } = render(
      <InterfaceToolbar
        currentPage={1}
        totalPages={3}
        pageLabel="Item 2/3"
        pageBadge="Re-execution 2"
        onPrevious={noop}
        onNext={noop}
        variant="dark"
      />,
    );
    expect(getByTestId('interface-pagination-label').textContent).toBe('Item 2/3');
    expect(getByTestId('interface-pagination-rerun-badge').textContent).toBe('Re-execution 2');
  });
});
