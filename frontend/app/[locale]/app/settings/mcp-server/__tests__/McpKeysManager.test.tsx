// @vitest-environment jsdom
/**
 * McpKeysManager: multiple named lc_live_ keys, each optionally scoped to a set
 * of MCP tools. Lists active keys, creates one (full or scoped) with the
 * plaintext shown once, and revokes a key. The service layer is mocked so the
 * component contract (create body, scope gating, revoke call) is pinned here.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars && 'date' in vars ? `${key}:${vars.date}` : key,
  useLocale: () => 'en',
}));

vi.mock('@/lib/utils/dateFormatters', () => ({
  formatUtcDate: () => 'Jul 10, 2026',
}));

const listKeys = vi.fn();
const createKey = vi.fn();
const revokeKey = vi.fn();
vi.mock('@/lib/api/services/mcp-server.service', () => ({
  mcpServerService: {
    listKeys: (...a: unknown[]) => listKeys(...a),
    createKey: (...a: unknown[]) => createKey(...a),
    revokeKey: (...a: unknown[]) => revokeKey(...a),
  },
}));

import { McpKeysManager } from '../McpKeysManager';

const SCOPES = [
  { name: 'workflow', description: 'Build and run workflows' },
  { name: 'table', description: 'Read and write tables' },
];

function renderManager() {
  const onToast = vi.fn();
  render(<McpKeysManager availableScopes={SCOPES} onToast={onToast} />);
  return { onToast };
}

beforeEach(() => {
  listKeys.mockResolvedValue([]);
  createKey.mockResolvedValue({ id: 'k1', name: 'Cursor', maskedApiKey: 'lc_live_...ab12', scopes: null, createdAt: '2026-07-10', lastUsedAt: null, apiKey: 'lc_live_PLAINTEXT' });
  revokeKey.mockResolvedValue(undefined);
});

afterEach(() => {
  vi.clearAllMocks();
  cleanup();
});

describe('McpKeysManager', () => {
  it('lists existing keys with a full-access chip and scope chips', async () => {
    listKeys.mockResolvedValue([
      { id: 'k1', name: 'Laptop', maskedApiKey: 'lc_live_...aaaa', scopes: null, createdAt: '2026-07-10', lastUsedAt: null },
      { id: 'k2', name: 'CI', maskedApiKey: 'lc_live_...bbbb', scopes: ['workflow', 'table'], createdAt: '2026-07-10', lastUsedAt: null },
    ]);
    renderManager();
    expect(await screen.findByText('Laptop')).toBeTruthy();
    expect(screen.getByText('CI')).toBeTruthy();
    // Full-access key shows the fullAccess chip; scoped key shows its tool names.
    expect(screen.getByText('keys.fullAccess')).toBeTruthy();
    expect(screen.getByText('workflow')).toBeTruthy();
    expect(screen.getByText('table')).toBeTruthy();
  });

  it('creates a FULL-access key (scopes null) and shows the plaintext once', async () => {
    renderManager();
    await screen.findByText('keys.empty');
    fireEvent.click(screen.getByText('keys.create'));
    fireEvent.change(screen.getByPlaceholderText('keys.namePlaceholder'), { target: { value: 'Cursor' } });
    // Default mode is full access. Submit.
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getAllByText('keys.create').pop()!);
    await waitFor(() => expect(createKey).toHaveBeenCalledWith({ name: 'Cursor', scopes: null }));
    expect(await screen.findByText('lc_live_PLAINTEXT')).toBeTruthy();
  });

  it('scoped mode requires at least one tool and sends the selected scopes', async () => {
    createKey.mockResolvedValue({ id: 'k9', name: 'CI', maskedApiKey: 'lc_live_...zzzz', scopes: ['workflow'], createdAt: '2026-07-10', lastUsedAt: null, apiKey: 'lc_live_SCOPED' });
    renderManager();
    await screen.findByText('keys.empty');
    fireEvent.click(screen.getByText('keys.create'));
    fireEvent.change(screen.getByPlaceholderText('keys.namePlaceholder'), { target: { value: 'CI' } });
    // Flip the "limit to specific tools" switch to enter scoped mode.
    fireEvent.click(screen.getByRole('switch', { name: 'keys.limitLabel' }));

    const dialog = screen.getByRole('dialog');
    const submit = within(dialog).getAllByText('keys.create').pop()!.closest('button')!;
    // No scope granted yet: submit disabled.
    expect(submit.disabled).toBe(true);
    // Each scope row has its own switch, keyed by the tool name.
    fireEvent.click(screen.getByRole('switch', { name: 'workflow' }));
    expect(submit.disabled).toBe(false);
    fireEvent.click(submit);
    await waitFor(() => expect(createKey).toHaveBeenCalledWith({ name: 'CI', scopes: ['workflow'] }));
  });

  it('disables submit until a name is entered', async () => {
    renderManager();
    await screen.findByText('keys.empty');
    fireEvent.click(screen.getByText('keys.create'));
    const dialog = screen.getByRole('dialog');
    const submit = within(dialog).getAllByText('keys.create').pop()!.closest('button')!;
    expect(submit.disabled).toBe(true);
    fireEvent.change(screen.getByPlaceholderText('keys.namePlaceholder'), { target: { value: 'X' } });
    expect(submit.disabled).toBe(false);
  });

  it('revokes a key only after an inline confirm, then refreshes the list', async () => {
    // Stateful mock so it survives any extra mount-effect refresh: the list
    // reflects whatever revokeKey removed, not a fixed call-count sequence.
    let store = [{ id: 'k1', name: 'Laptop', maskedApiKey: 'lc_live_...aaaa', scopes: null, createdAt: '2026-07-10', lastUsedAt: null }];
    listKeys.mockImplementation(() => Promise.resolve(store));
    revokeKey.mockImplementation((id: string) => { store = store.filter((k) => k.id !== id); return Promise.resolve(undefined); });
    renderManager();
    // First click only arms the confirm (no destructive call yet).
    fireEvent.click(await screen.findByLabelText('keys.revoke'));
    expect(revokeKey).not.toHaveBeenCalled();
    // Confirm actually revokes.
    fireEvent.click(screen.getByText('keys.revokeConfirm'));
    await waitFor(() => expect(revokeKey).toHaveBeenCalledWith('k1'));
    expect(await screen.findByText('keys.empty')).toBeTruthy();
  });

  it('wipes the one-time plaintext from the DOM after the dialog is closed', async () => {
    renderManager();
    await screen.findByText('keys.empty');
    fireEvent.click(screen.getByText('keys.create'));
    fireEvent.change(screen.getByPlaceholderText('keys.namePlaceholder'), { target: { value: 'Cursor' } });
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getAllByText('keys.create').pop()!);
    expect(await screen.findByText('lc_live_PLAINTEXT')).toBeTruthy();
    // Done closes AND clears: the plaintext must be gone from the DOM.
    fireEvent.click(screen.getByText('keys.done'));
    await waitFor(() => expect(screen.queryByText('lc_live_PLAINTEXT')).toBeNull());
  });
});

// Minimal `within` helper (avoids an extra import surface).
function within(el: HTMLElement) {
  return {
    getAllByText: (text: string) =>
      Array.from(el.querySelectorAll('*')).filter((n) => n.textContent === text) as HTMLElement[],
  };
}
