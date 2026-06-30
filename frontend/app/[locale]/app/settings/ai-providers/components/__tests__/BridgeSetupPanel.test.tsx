/**
 * @vitest-environment jsdom
 *
 * Status-badge logic for the CLI-bridge setup panel. The badge must distinguish a
 * usable CLI (installed AND authenticated) from one that is merely INSTALLED but not
 * logged in - the latter must read "Login required", NOT a green "Connected", because
 * a bridge-CLI model would still fail at run time with "please log in".
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import messages from '../../../../../../../messages/en.json';

const getBridgeStatus = vi.fn();
vi.mock('@/lib/api/orchestrator/credential.service', () => ({
  credentialService: { getBridgeStatus: (...a: unknown[]) => getBridgeStatus(...a) },
}));

import BridgeSetupPanel from '../BridgeSetupPanel';

// The panel takes `t` as a prop, scoped to the `aiProviders` namespace. Resolve the
// REAL en.json strings so the assertions match what the user actually sees.
const t = (key: string): string => {
  let o: unknown = (messages as Record<string, unknown>).aiProviders;
  for (const p of key.split('.')) o = (o as Record<string, unknown> | undefined)?.[p];
  return typeof o === 'string' ? o : key;
};

const renderPanel = () => render(<BridgeSetupPanel cli="claudeCode" t={t} />);

async function verifyWith(cli: Record<string, unknown>) {
  getBridgeStatus.mockResolvedValueOnce({ bridgeReachable: true, connected: true, cli });
  fireEvent.click(screen.getByRole('button', { name: /verify connection/i }));
}

describe('BridgeSetupPanel status badge', () => {
  beforeEach(() => getBridgeStatus.mockReset());
  afterEach(() => cleanup());

  it('shows "Login required" (NOT a green Connected) when the CLI is installed but not authenticated', async () => {
    renderPanel();
    await verifyWith({ installed: true, authenticated: false, version: '2.1.187', error: null });
    await waitFor(() => expect(screen.getByText(/Login required/i)).toBeTruthy());
    expect(screen.getByText(/Login required/i).textContent).toContain('2.1.187');
    // The fix: it must NOT claim "Connected" for an unauthenticated CLI.
    expect(screen.queryByText((c) => c.startsWith('Connected'))).toBeNull();
  });

  it('shows "Connected" only when the CLI is installed AND authenticated', async () => {
    renderPanel();
    await verifyWith({ installed: true, authenticated: true, version: '2.1.187', error: null });
    await waitFor(() => expect(screen.getByText((c) => c.startsWith('Connected'))).toBeTruthy());
    expect(screen.queryByText(/Login required/i)).toBeNull();
  });

  it('shows "Not connected" when the CLI is not installed', async () => {
    renderPanel();
    await verifyWith({ installed: false, authenticated: false, version: null, error: 'not found' });
    await waitFor(() => expect(screen.getByText(/Not connected/i)).toBeTruthy());
  });
});
