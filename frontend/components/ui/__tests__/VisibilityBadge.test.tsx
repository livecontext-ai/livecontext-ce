// @vitest-environment jsdom
/**
 * Pins the shared public / private indicator badge: a Globe for public and a Lock for private, with
 * the right tooltip. The PUBLIC-vs-everything-else bucketing it relies on lives in (and is tested
 * via) lib/utils/visibility.
 */
import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

// Echo translation keys so the tooltip text is the i18n key ('visibilityPublic' / 'visibilityPrivate').
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { VisibilityBadge } from '../VisibilityBadge';

afterEach(cleanup);

describe('VisibilityBadge', () => {
  it('PUBLIC → Globe icon + Public tooltip', () => {
    const { container } = render(<VisibilityBadge visibility="PUBLIC" />);
    expect(screen.getByTitle('visibilityPublic')).toBeTruthy();
    expect(container.querySelector('.lucide-globe')).toBeTruthy();
    expect(container.querySelector('.lucide-lock')).toBeNull();
  });

  it('PRIVATE → Lock icon + Private tooltip', () => {
    const { container } = render(<VisibilityBadge visibility="PRIVATE" />);
    expect(screen.getByTitle('visibilityPrivate')).toBeTruthy();
    expect(container.querySelector('.lucide-lock')).toBeTruthy();
    expect(container.querySelector('.lucide-globe')).toBeNull();
  });

  it('UNLISTED / missing fall in the private bucket → Lock', () => {
    const { container: unlisted } = render(<VisibilityBadge visibility="UNLISTED" />);
    expect(unlisted.querySelector('.lucide-lock')).toBeTruthy();
    cleanup();
    const { container: missing } = render(<VisibilityBadge visibility={null} />);
    expect(missing.querySelector('.lucide-lock')).toBeTruthy();
  });
});
