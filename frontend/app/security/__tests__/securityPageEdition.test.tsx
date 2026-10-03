// @vitest-environment jsdom
/**
 * Regression (CASA round 2, NIT 6): the /security page linked to /.well-known/security.txt in
 * every edition, but the self-hosted (CE) edition does not ship that file (it is excluded from the
 * public export and removed from the CE image), so a CE install showed a link to a 404. The
 * machine-readable pointer is now rendered by the cloud edition only.
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createTranslator, type AbstractIntlMessages } from 'next-intl';
import en from '@/messages/en.json';

const state = vi.hoisted(() => ({ isCe: false }));

vi.mock('@/lib/edition', () => ({
  get IS_CE() { return state.isCe; },
  get IS_CLOUD() { return !state.isCe; },
}));
vi.mock('next-intl/server', () => ({
  getTranslations: async ({ locale, namespace }: { locale: string; namespace: string }) => createTranslator({
    locale, messages: en as AbstractIntlMessages, namespace,
    onError: (error) => { throw error; },
  }),
}));
vi.mock('@/i18n/resolveRequestLocale', () => ({ resolveRequestLocale: async () => 'en' }));
vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}));

import SecurityDisclosurePage from '../page';

async function renderPage(isCe: boolean) {
  state.isCe = isCe;
  render(await SecurityDisclosurePage());
}

function securityTxtLinks(): Element[] {
  return Array.from(document.querySelectorAll('a[href="/.well-known/security.txt"]'));
}

afterEach(() => cleanup());

describe('/security page: the security.txt link follows the edition', () => {
  it('cloud: links to /.well-known/security.txt (the hosted file exists)', async () => {
    await renderPage(false);

    expect(securityTxtLinks()).toHaveLength(1);
  });

  it('CE: no link to /.well-known/security.txt (the self-hosted edition does not ship the file)', async () => {
    await renderPage(true);

    expect(securityTxtLinks()).toHaveLength(0);
    // The policy itself still renders in CE.
    expect(screen.getByRole('heading', { level: 1 })).toBeTruthy();
  });
});
