/**
 * @vitest-environment jsdom
 *
 * The confirmation modal shown before a click inside a sandboxed interface iframe is
 * allowed to open an external link. Mirrors the app's default confirmation-modal shape
 * (portal overlay, Cancel | primary action) and shows the destination URL verbatim so the
 * user can vet it.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import * as React from 'react';

// Key-echo translations (same pattern as the other component tests).
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { OpenLinkConfirmModal } from '../OpenLinkConfirmModal';

describe('OpenLinkConfirmModal', () => {
  afterEach(cleanup);

  it('renders nothing when url is null (closed state)', () => {
    const { container } = render(
      <OpenLinkConfirmModal url={null} onConfirm={() => {}} onCancel={() => {}} />
    );
    expect(container.firstChild).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows the title, description and the destination url verbatim', () => {
    render(<OpenLinkConfirmModal url="https://example.com/abc" onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText('title')).toBeTruthy();
    expect(screen.getByText('description')).toBeTruthy();
    // The exact URL must be visible so the user can decide before opening.
    expect(screen.getByText('https://example.com/abc')).toBeTruthy();
  });

  it('calls onConfirm when the open button is clicked', () => {
    const onConfirm = vi.fn();
    render(<OpenLinkConfirmModal url="https://x.com" onConfirm={onConfirm} onCancel={() => {}} />);
    fireEvent.click(screen.getByText('openAction'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('calls onCancel when the cancel button is clicked', () => {
    const onCancel = vi.fn();
    render(<OpenLinkConfirmModal url="https://x.com" onConfirm={() => {}} onCancel={onCancel} />);
    fireEvent.click(screen.getByText('cancel'));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('calls onCancel when the backdrop is clicked but not the card itself', () => {
    const onCancel = vi.fn();
    render(<OpenLinkConfirmModal url="https://x.com" onConfirm={() => {}} onCancel={onCancel} />);
    const card = screen.getByRole('dialog');
    // Clicking the inner card must NOT dismiss (stopPropagation guards it).
    fireEvent.click(card);
    expect(onCancel).not.toHaveBeenCalled();
    // Clicking the surrounding overlay dismisses.
    fireEvent.click(card.parentElement as HTMLElement);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
