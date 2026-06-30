/**
 * @vitest-environment jsdom
 *
 * CE-edition render test for the model-not-managed modal: it opens on its event,
 * renders the REAL i18n strings (en.json), and the CTA is role-aware - an admin
 * gets a "refresh model bundle" action that deep-links to the bundles settings,
 * while a member sees the "ask your admin" copy and no action button.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';

const push = vi.fn();
const { hasRole } = vi.hoisted(() => ({ hasRole: vi.fn<(r: string) => boolean>() }));

vi.mock('@/lib/edition', () => ({ IS_CE: true }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ hasRole }) }));

import ModelNotManagedModal from '../ModelNotManagedModal';

const renderModal = () =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <ModelNotManagedModal />
    </NextIntlClientProvider>,
  );

describe('ModelNotManagedModal (CE edition)', () => {
  beforeEach(() => { push.mockClear(); hasRole.mockReset(); });
  afterEach(() => cleanup());

  it('admin: renders the admin copy and deep-links the bundle refresh action to the bundles tab', () => {
    hasRole.mockReturnValue(true);
    renderModal();
    fireEvent(window, new CustomEvent('modelNotManaged'));

    expect(screen.getByText('Model not available')).toBeTruthy();
    expect(screen.getByText(/Refresh your model bundle to update the available models/i)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /refresh model bundle/i }));
    expect(push).toHaveBeenCalledWith('/en/app/settings/cloud-account?tab=bundles');
  });

  it('member: shows the ask-your-admin copy and no refresh action', () => {
    hasRole.mockReturnValue(false);
    renderModal();
    fireEvent(window, new CustomEvent('modelNotManaged'));

    expect(screen.getByText(/Ask your workspace admin to refresh the model bundle/i)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /refresh model bundle/i })).toBeNull();
  });
});
