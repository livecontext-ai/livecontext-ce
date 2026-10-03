/**
 * A personal offer's link (lifecycle email: /<locale>/app/settings/pricing?lc_offer=<code>) opens
 * the offer's own full-screen page, in the email's language. The links already sent keep working.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import { proxy } from '@/proxy';

function request(path: string): NextRequest {
  return new NextRequest(`https://livecontext.ai${path}`);
}

describe('proxy: personal offer links', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it('the email link opens the offer page with everything it carried, in the email language', () => {
    const response = proxy(request('/fr/app/settings/pricing?lc_offer=JANE-BONUS-72H&billingCycle=monthly')) as Response;

    expect(response.status).toBe(307);
    expect(response.headers.get('location')).toBe('https://livecontext.ai/offer/personal?lc_offer=JANE-BONUS-72H&billingCycle=monthly');
    expect(response.headers.get('set-cookie')).toMatch(/NEXT_LOCALE=fr;/);
  });

  it('a link without a locale keeps the reader language as it is', () => {
    const response = proxy(request('/app/settings/pricing?lc_offer=JANE-BONUS-72H')) as Response;

    expect(response.headers.get('location')).toBe('https://livecontext.ai/offer/personal?lc_offer=JANE-BONUS-72H');
    expect(response.headers.get('set-cookie') ?? '').not.toMatch(/NEXT_LOCALE=/);
  });

  it('the pricing page without an offer code is not redirected', () => {
    const response = proxy(request('/en/app/settings/pricing?billingCycle=monthly')) as Response | undefined;

    expect(response?.headers.get('location') ?? '').not.toContain('/offer/personal');
  });

  it('self-hosted: no personal offer, the pricing page stays', () => {
    editionMock.IS_CE = true;
    const response = proxy(request('/fr/app/settings/pricing?lc_offer=JANE-BONUS-72H')) as Response | undefined;

    expect(response?.headers.get('location') ?? '').not.toContain('/offer/personal');
  });
});
