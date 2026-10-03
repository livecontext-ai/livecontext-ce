// @vitest-environment jsdom
/**
 * The partner's apps to give with an offer: only what the server will accept is offerable (their
 * own active public or unlisted app the cloud can run), a private one is shown but cannot be
 * picked, and at most ten go with one link.
 */
import React, { useState } from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const auth = vi.hoisted(() => ({ numericUserId: 42 as number | null }));
const myApps = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => auth }));
vi.mock('@/lib/api/orchestrator/publication.service', async (importOriginal) => {
  // The real service (other modules bind its methods at import), reading the partner's apps from the test.
  const actual = await importOriginal<typeof import('@/lib/api/orchestrator/publication.service')>();
  actual.publicationService.getMyPublications = ((...a: unknown[]) => myApps.get(...a)) as never;
  return actual;
});

import { MAX_OFFER_APPS, OfferAppsPicker, offerAppState } from '../OfferAppsPicker';

function pub(id: string, overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id, title: `App ${id}`, publisherId: '42', displayMode: 'APPLICATION', status: 'ACTIVE', visibility: 'PUBLIC', nodeIcons: [],
    ...overrides,
  } as WorkflowPublication;
}

let picked: string[] = [];
function Harness() {
  const [selected, setSelected] = useState<string[]>([]);
  picked = selected;
  return <OfferAppsPicker selected={selected} onChange={setSelected} />;
}
function renderPicker() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <Harness />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}
const box = (id: string) => within(screen.getByTestId(`builder-app-${id}`)).getByRole('checkbox') as HTMLInputElement;

beforeEach(() => {
  auth.numericUserId = 42;
  picked = [];
  myApps.get.mockReset();
});
afterEach(cleanup);

describe('offerAppState (the server rule, mirrored)', () => {
  it('the partner\'s own active public or unlisted application is offerable', () => {
    expect(offerAppState(pub('a'), '42')).toBe('offerable');
    expect(offerAppState(pub('a', { visibility: 'UNLISTED' }), '42')).toBe('offerable');
  });

  it('a private one is shown as one to make unlisted; anything else is not listed', () => {
    expect(offerAppState(pub('a', { visibility: 'PRIVATE' }), '42')).toBe('private');
    expect(offerAppState(pub('a', { publisherId: '7' }), '42')).toBeNull();
    expect(offerAppState(pub('a', { status: 'INACTIVE' }), '42')).toBeNull();
    expect(offerAppState(pub('a', { status: 'PENDING_REVIEW' }), '42')).toBeNull();
    expect(offerAppState(pub('a', { displayMode: 'WORKFLOW' }), '42')).toBeNull();
    expect(offerAppState(pub('a', { ceExclusive: true }), '42')).toBeNull();
  });
});

describe('OfferAppsPicker', () => {
  it('lists the offerable apps first, the private ones after and disabled, asks for applications only', async () => {
    myApps.get.mockResolvedValue({ count: 3, publications: [pub('p', { visibility: 'PRIVATE' }), pub('a'), pub('t', { publisherId: '7' })] });
    renderPicker();

    await screen.findByTestId('builder-app-a');
    expect(myApps.get).toHaveBeenCalledWith(true);
    const rows = screen.getAllByTestId(/^builder-app-/);
    expect(rows.map((r) => r.dataset.testid)).toEqual(['builder-app-a', 'builder-app-p']);
    expect(box('p').disabled).toBe(true);
    expect(screen.getByTestId('builder-app-p').textContent).toContain('Private: make it unlisted to offer it');
    // A teammate's app is not the partner's to give.
    expect(screen.queryByTestId('builder-app-t')).toBeNull();
  });

  it('picks and unpicks, keeping the order picked', async () => {
    myApps.get.mockResolvedValue({ count: 2, publications: [pub('a'), pub('b')] });
    renderPicker();

    fireEvent.click(await screen.findByTestId('builder-app-b').then((r) => within(r).getByRole('checkbox')));
    fireEvent.click(box('a'));
    expect(picked).toEqual(['b', 'a']);
    expect(screen.getByTestId('builder-apps-count').textContent).toBe('2/10');

    fireEvent.click(box('b'));
    expect(picked).toEqual(['a']);
  });

  it('at ten picked, the others cannot be added (and the picked ones can still be removed)', async () => {
    myApps.get.mockResolvedValue({ count: 11, publications: Array.from({ length: 11 }, (_, i) => pub(`x${i}`)) });
    renderPicker();
    await screen.findByTestId('builder-app-x0');

    for (let i = 0; i < MAX_OFFER_APPS; i++) fireEvent.click(box(`x${i}`));

    expect(picked).toHaveLength(MAX_OFFER_APPS);
    expect(box('x10').disabled).toBe(true);
    expect(screen.getByTestId('builder-apps-full').textContent).toBe('10 apps at most per link.');
    expect(box('x0').disabled).toBe(false);
    fireEvent.click(box('x0'));
    expect(picked).toHaveLength(MAX_OFFER_APPS - 1);
    expect(box('x10').disabled).toBe(false);
  });

  it('says when the partner has no app to offer, and when the list cannot be read', async () => {
    myApps.get.mockResolvedValue({ count: 0, publications: [] });
    const first = renderPicker();
    expect((await screen.findByTestId('builder-apps-empty')).textContent).toContain('You have not published an app yet');
    first.unmount();

    myApps.get.mockRejectedValue(new Error('down'));
    renderPicker();
    expect((await screen.findByTestId('builder-apps-error')).textContent).toContain('The link can still be created without them');
  });

  it('asks nothing before the account is known', () => {
    auth.numericUserId = null;
    renderPicker();

    expect(myApps.get).not.toHaveBeenCalled();
    // Not "you have no app": nothing is known yet.
    expect(screen.getByTestId('builder-apps-loading')).toBeTruthy();
    expect(screen.queryByTestId('builder-apps-empty')).toBeNull();
  });
});
