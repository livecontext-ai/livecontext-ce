/**
 * @vitest-environment jsdom
 *
 * NoProviderCta - the "no AI provider configured" call to action rendered by
 * the model pickers on a CE install with zero usable models. Pins the role
 * split (admin gets the two ways out, member gets the ask-your-admin line),
 * the cloud OAuth kickoff, its error path, and the CE-only gate.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import * as React from 'react';

const h = vi.hoisted(() => ({
  isCe: true,
  isAdmin: false,
  getAuthUrl: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
  useLocale: () => 'en',
}));
vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return h.isCe;
  },
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => ({ hasRole: (role: string) => role === 'ADMIN' && h.isAdmin }),
}));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: {
    getAuthUrl: (...args: unknown[]) => h.getAuthUrl(...args),
  },
}));
vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="spinner" />,
}));

import { NoProviderCta } from '../NoProviderCta';

const T = 'aiProviders.noProviderCta';

describe('NoProviderCta', () => {
  beforeEach(() => {
    h.isCe = true;
    h.isAdmin = false;
    h.getAuthUrl.mockReset();
  });
  afterEach(() => cleanup());

  it('renders nothing on a cloud build (CE-only affordance)', () => {
    h.isCe = false;
    h.isAdmin = true;
    render(<NoProviderCta />);
    expect(screen.queryByTestId('no-provider-cta')).toBeNull();
  });

  it('shows an admin both actions: connect to cloud and add an API key', () => {
    h.isAdmin = true;
    render(<NoProviderCta />);
    expect(screen.getByText(`${T}.title`)).toBeInTheDocument();
    expect(screen.getByText(`${T}.body`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: new RegExp(`${T}.connectCloud`) })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: new RegExp(`${T}.addApiKey`) })).toHaveAttribute(
      'href',
      '/en/app/settings/ai-providers',
    );
  });

  it('shows a non-admin member the ask-your-administrator line and NO buttons (both actions are admin-only)', () => {
    h.isAdmin = false;
    render(<NoProviderCta />);
    expect(screen.getByText(`${T}.bodyMember`)).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('kicks off the cloud OAuth flow on connect: fetches the auth url and navigates to it', async () => {
    h.isAdmin = true;
    h.getAuthUrl.mockResolvedValue({ authUrl: 'https://cloud.example/oauth?x=1', state: 's' });
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...originalLocation, href: 'http://ce.local/en/app/chat' },
      writable: true,
    });

    render(<NoProviderCta />);
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`${T}.connectCloud`) }));

    await waitFor(() => expect(window.location.href).toBe('https://cloud.example/oauth?x=1'));
    // No returnPath: the backend whitelist only accepts callback-handling pages,
    // so the flow must land on the default (settings cloud-account).
    expect(h.getAuthUrl).toHaveBeenCalledWith();

    Object.defineProperty(window, 'location', { value: originalLocation, writable: true });
  });

  it('surfaces the connect error and re-enables the button when the auth-url fetch fails', async () => {
    h.isAdmin = true;
    h.getAuthUrl.mockRejectedValue(new Error('boom'));

    render(<NoProviderCta />);
    const button = screen.getByRole('button', { name: new RegExp(`${T}.connectCloud`) });
    fireEvent.click(button);

    expect(await screen.findByText(`${T}.connectError`)).toBeInTheDocument();
    expect(button).toBeEnabled();
  });

  it('renders the compact menu variant with the same content', () => {
    h.isAdmin = true;
    render(<NoProviderCta variant="menu" />);
    expect(screen.getByTestId('no-provider-cta')).toBeInTheDocument();
    expect(screen.getByText(`${T}.title`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: new RegExp(`${T}.connectCloud`) })).toBeInTheDocument();
  });
});
