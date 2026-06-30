// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * PublicationStatusIcon picks ONE marker by precedence: shared (Globe) > rejected (X) > pending
 * (Clock) > private (Lock, opt-in) > nothing. The `showPrivate` branch is the new private
 * counterpart to the shared Globe used by the resource listing pages - it must stay opt-in so
 * call sites that don't pass it (e.g. the skill tree) still render nothing for a private item.
 *
 * next-intl is mocked to echo keys, so titles are the i18n keys.
 */
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { PublicationStatusIcon } from '../PublicationStatusIcon';

afterEach(() => {
  cleanup();
});

describe('PublicationStatusIcon - showPrivate (Lock) branch', () => {
  it('renders a Lock titled "private" when not shared/pending/rejected AND showPrivate is set', () => {
    render(<PublicationStatusIcon isShared={false} isPending={false} isRejected={false} showPrivate />);
    expect(screen.getByTitle('common.visibilityPrivate')).toBeInTheDocument();
  });

  it('renders NOTHING for the same private state when showPrivate is omitted (back-compat)', () => {
    const { container } = render(
      <PublicationStatusIcon isShared={false} isPending={false} isRejected={false} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('shared wins over showPrivate → Globe, never the Lock', () => {
    render(<PublicationStatusIcon isShared isPending={false} isRejected={false} showPrivate />);
    expect(screen.getByTitle('workflow.shared')).toBeInTheDocument();
    expect(screen.queryByTitle('common.visibilityPrivate')).not.toBeInTheDocument();
  });

  it('pending wins over showPrivate → Clock, never the Lock', () => {
    render(<PublicationStatusIcon isShared={false} isPending isRejected={false} showPrivate />);
    expect(screen.getByTitle('marketplace.pendingReview')).toBeInTheDocument();
    expect(screen.queryByTitle('common.visibilityPrivate')).not.toBeInTheDocument();
  });
});
