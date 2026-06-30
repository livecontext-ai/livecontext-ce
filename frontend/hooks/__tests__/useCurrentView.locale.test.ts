// @vitest-environment jsdom
/**
 * Regression (2026-06-12): useCurrentView's locale stripping hardcoded
 * (en|fr|es), so on /de//pt//zh-prefixed URLs the pathname kept its prefix,
 * matched no /app/* route, and the view fell back to 'chat' - breaking the
 * breadcrumb, the sidebar highlight, and the empty-canvas gating for those
 * locales. The helper is now shared and derived from the routing config.
 */
import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';

let mockPath = '/app/workflow';
let mockParams: Record<string, string | undefined> = {};

vi.mock('next/navigation', () => ({
  usePathname: () => mockPath,
  useParams: () => mockParams,
}));

import { useCurrentView } from '../useCurrentView';
import { locales } from '@/i18n/routing';

describe('useCurrentView - locale prefixes', () => {
  it('resolves the workflow view under EVERY routing locale (regression: de/pt/zh fell back to chat)', () => {
    for (const locale of locales) {
      mockPath = `/${locale}/app/workflow/3f2a0000-0000-0000-0000-000000000000`;
      mockParams = { workflowId: '3f2a0000-0000-0000-0000-000000000000' };
      const { result } = renderHook(() => useCurrentView());
      expect(result.current.view, `locale ${locale}`).toBe('workflow');
      expect(result.current.isDetailPage, `locale ${locale}`).toBe(true);
    }
  });
});
