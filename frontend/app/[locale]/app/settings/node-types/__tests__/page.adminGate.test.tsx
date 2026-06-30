// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';

// --- controllable test state -----------------------------------------------
let mockIsCe = false;
let mockHasRole: (role: string) => boolean = () => false;
const getAll = vi.fn();
const getOrganizations = vi.fn(); // must NEVER be called by the admin gate

// Edition mock is inert today (the page no longer imports IS_CE) but acts as a
// forward-guard: if someone later adds an `if (IS_CE) return <unavailable/>`
// branch, the "available in CE" test below will fail.
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_CLOUD() { return !mockIsCe; },
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => mockHasRole(r) }),
}));
// Regression guard: node-type settings is a PLATFORM admin feature; it must not
// resolve admin access from org membership (which denied a platform admin who
// switched into a workspace they don't own).
vi.mock('@/lib/api', () => ({
  organizationApi: { getOrganizations: (...a: unknown[]) => getOrganizations(...a) },
}));
vi.mock('@/lib/api/orchestrator/node-type-settings.service', () => ({
  nodeTypeSettingsService: { getAll: (...a: unknown[]) => getAll(...a) },
}));
vi.mock('@/components/settings', () => ({
  PageHeader: ({ title }: { title: string }) => <h1>{title}</h1>,
}));
vi.mock('@/components/Toast', () => ({
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('../components', () => ({
  NodeTypeCard: () => null,
  NodeTypeCategorySelect: () => null,
}));

import NodeTypeSettingsPage from '../page';

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as any}>
      <NodeTypeSettingsPage />
    </NextIntlClientProvider>,
  );
}

const UNAUTHORIZED = 'Only administrators can manage node type settings.';

describe('NodeTypeSettingsPage - admin access gate', () => {
  beforeEach(() => {
    mockIsCe = false;
    mockHasRole = () => false;
    getAll.mockResolvedValue([]);
    getOrganizations.mockResolvedValue([]);
  });
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('grants access to a platform ADMIN - matches the backend X-User-Roles gate', async () => {
    mockHasRole = (r) => r === 'ADMIN';
    renderPage();
    await waitFor(() => expect(screen.getByText('Node Types')).toBeInTheDocument());
    expect(screen.queryByText(UNAUTHORIZED)).not.toBeInTheDocument();
  });

  it('regression: does NOT consult org membership (getOrganizations) to decide admin access', async () => {
    mockHasRole = (r) => r === 'ADMIN';
    renderPage();
    await waitFor(() => expect(screen.getByText('Node Types')).toBeInTheDocument());
    expect(getOrganizations).not.toHaveBeenCalled();
  });

  it('denies a non-admin with the unauthorized screen', async () => {
    mockHasRole = () => false;
    renderPage();
    await waitFor(() => expect(screen.getByText(UNAUTHORIZED)).toBeInTheDocument());
    expect(screen.queryByText('Node Types')).not.toBeInTheDocument();
  });

  it('stays available to a platform ADMIN in CE - node-types is NOT cloud-only', async () => {
    mockIsCe = true;
    mockHasRole = (r) => r === 'ADMIN';
    renderPage();
    await waitFor(() => expect(screen.getByText('Node Types')).toBeInTheDocument());
    expect(screen.queryByText(UNAUTHORIZED)).not.toBeInTheDocument();
  });
});
