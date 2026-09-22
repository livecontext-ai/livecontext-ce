import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest, NextResponse } from 'next/server';

const intl = vi.hoisted(() => vi.fn());
vi.mock('next-intl/middleware', () => ({ default: () => intl }));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
import { proxy } from '@/proxy';

describe('persona landing routing', () => {
  beforeEach(() => {
    intl.mockReset();
    intl.mockReturnValue(NextResponse.next());
  });

  it.each(['/for/creator', '/fr/for/support', '/de/for/sales', '/es/for/marketing', '/pt/for/recruiting', '/zh/for/creator', '/en/for/creator'])(
    'routes %s through locale middleware instead of stripping its locale or returning 404', (path) => {
      const request = new NextRequest(`https://livecontext.ai${path}`);
      const response = proxy(request);
      expect(intl).toHaveBeenCalledWith(request);
      expect(response).toBe(intl.mock.results[0].value);
    },
  );

  it('does not treat similarly named unknown routes as persona pages', () => {
    proxy(new NextRequest('https://livecontext.ai/foreign/creator'));
    expect(intl).not.toHaveBeenCalled();
  });
});
