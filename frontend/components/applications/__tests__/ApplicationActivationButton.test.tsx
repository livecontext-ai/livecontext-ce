// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

vi.mock('next-intl', () => ({
  useTranslations: () => {
    const labels: Record<string, string> = {
      activate: 'Activate',
      deactivate: 'Deactivate',
      statusActive: 'Active',
      statusInactive: 'Inactive',
      live: 'Live',
      off: 'Off',
      activationLoading: 'Loading…',
      activationFailed: 'Activation failed',
      deactivationFailed: 'Deactivation failed',
      activationNoVersion: 'No version',
    };
    return (key: string) => labels[key] ?? key;
  },
}));

const mockListVersions = vi.fn();
const mockPinVersion = vi.fn();
vi.mock('@/lib/api/orchestrator/version.service', () => ({
  versionService: {
    listVersions: (...args: unknown[]) => mockListVersions(...args),
    pinVersion: (...args: unknown[]) => mockPinVersion(...args),
  },
}));

// Activating or deactivating an app is a pin, and a pin is what puts it in the bell's Triggers
// rows. The real hook reaches for a QueryClient this suite has no provider for; what it does
// with the cache is pinned in useRefreshHomeStatus.freshness.test.tsx. A single shared spy,
// returned by identity, so it is stable in the callbacks' dependency lists.
const refreshHomeStatusMock = vi.fn();
vi.mock('@/hooks/useHomeStatus', () => ({
  useRefreshHomeStatus: () => refreshHomeStatusMock,
}));

// Import after mocks
import { ApplicationActivationButton } from '../ApplicationActivationButton';

const WORKFLOW_ID = '11111111-2222-3333-4444-555555555555';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

beforeEach(() => {
  mockListVersions.mockReset();
  mockPinVersion.mockReset();
  refreshHomeStatusMock.mockReset();
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('ApplicationActivationButton', () => {
  it('renders Off + unchecked switch when initial pinned version is null', () => {
    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={null} />);

    expect(screen.getByText('Off')).toBeInTheDocument();
    const sw = screen.getByRole('switch', { name: 'Activate' });
    expect(sw).toHaveAttribute('aria-checked', 'false');
  });

  it('renders Live + checked switch when initial pinned version is set', () => {
    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={3} />);

    expect(screen.getByText('Live')).toBeInTheDocument();
    const sw = screen.getByRole('switch', { name: 'Deactivate' });
    expect(sw).toHaveAttribute('aria-checked', 'true');
  });

  it('shows loading placeholder when no initial version supplied (fetches on mount)', async () => {
    mockListVersions.mockResolvedValue({ currentVersion: 2, pinnedVersion: null, versions: [] });

    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} />);

    // Synchronously: loading state visible.
    expect(screen.getByText('Loading…')).toBeInTheDocument();

    // After fetch resolves: switch rendered.
    await waitFor(() => {
      expect(screen.getByRole('switch', { name: 'Activate' })).toBeInTheDocument();
    });
    expect(mockListVersions).toHaveBeenCalledWith(WORKFLOW_ID);
  });

  it('toggling ON pins the latest version and reflects new state', async () => {
    mockListVersions.mockResolvedValue({ currentVersion: 5, pinnedVersion: null, versions: [] });
    mockPinVersion.mockResolvedValue({ success: true, pinnedVersion: 5, productionRunIdPublic: null });
    const onChange = vi.fn();

    render(
      <ApplicationActivationButton
        workflowId={WORKFLOW_ID}
        initialPinnedVersion={null}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole('switch', { name: 'Activate' }));

    await waitFor(() => {
      expect(mockPinVersion).toHaveBeenCalledWith(WORKFLOW_ID, 5);
    });
    expect(onChange).toHaveBeenCalledWith(5);

    // Post-click UI flips to Active + checked switch announcing Deactivate.
    await waitFor(() => {
      const sw = screen.getByRole('switch', { name: 'Deactivate' });
      expect(sw).toHaveAttribute('aria-checked', 'true');
    });
  });

  it('toggling OFF unpins (passes null) and reflects new state', async () => {
    mockPinVersion.mockResolvedValue({ success: true, pinnedVersion: null, productionRunIdPublic: null });
    const onChange = vi.fn();

    render(
      <ApplicationActivationButton
        workflowId={WORKFLOW_ID}
        initialPinnedVersion={3}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole('switch', { name: 'Deactivate' }));

    await waitFor(() => {
      expect(mockPinVersion).toHaveBeenCalledWith(WORKFLOW_ID, null);
    });
    expect(onChange).toHaveBeenCalledWith(null);
    expect(mockListVersions).not.toHaveBeenCalled(); // unpin doesn't need version lookup

    await waitFor(() => {
      const sw = screen.getByRole('switch', { name: 'Activate' });
      expect(sw).toHaveAttribute('aria-checked', 'false');
    });
  });

  it('asks for the bell automation rows again after activating (regression: the Triggers tab read one step behind)', async () => {
    // Activation pins the latest version, which is exactly what makes this app appear in the
    // bell's Triggers rows and in the imminent-fire ring. Nothing else invalidates that
    // payload, so without this ask the user activates and then reads pre-activation state.
    mockListVersions.mockResolvedValue({ currentVersion: 4, pinnedVersion: null, versions: [] });
    // Production shape: the endpoint answers `success` too, and the refresh is gated on it.
    mockPinVersion.mockResolvedValue({ success: true, pinnedVersion: 4, productionRunIdPublic: null });

    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={null} />);
    fireEvent.click(screen.getByRole('switch', { name: 'Activate' }));

    await waitFor(() => expect(refreshHomeStatusMock).toHaveBeenCalledTimes(1));
    // No bound: the caller just changed the data, so how fresh the copy is says nothing.
    expect(refreshHomeStatusMock).toHaveBeenCalledWith();
  });

  it('asks for the bell automation rows again after deactivating', async () => {
    // Unpinning REMOVES the row, which is just as wrong to show for another poll interval.
    mockPinVersion.mockResolvedValue({ success: true, pinnedVersion: null, productionRunIdPublic: null });

    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={3} />);
    fireEvent.click(screen.getByRole('switch', { name: 'Deactivate' }));

    await waitFor(() => expect(refreshHomeStatusMock).toHaveBeenCalledTimes(1));
    expect(refreshHomeStatusMock).toHaveBeenCalledWith();
  });

  it('does not ask when the server declines the pin without throwing', async () => {
    // A resolved `{ success: false }` changed no row. Asking anyway would also stamp "asked
    // just now", which silences the visit-ask the user makes seconds later.
    mockListVersions.mockResolvedValue({ currentVersion: 4, pinnedVersion: null, versions: [] });
    mockPinVersion.mockResolvedValue({ success: false, pinnedVersion: null, productionRunIdPublic: null });

    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={null} />);
    fireEvent.click(screen.getByRole('switch', { name: 'Activate' }));

    await waitFor(() => expect(mockPinVersion).toHaveBeenCalledTimes(1));
    expect(refreshHomeStatusMock).not.toHaveBeenCalled();
  });

  it('does not ask when the pin call fails', async () => {
    // The rows did not change, so asking would only spend a request.
    mockListVersions.mockResolvedValue({ currentVersion: 1, pinnedVersion: null, versions: [] });
    mockPinVersion.mockRejectedValue(new Error('boom'));

    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={null} />);
    fireEvent.click(screen.getByRole('switch', { name: 'Activate' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('boom'));
    expect(refreshHomeStatusMock).not.toHaveBeenCalled();
  });

  it('shows error when pin call fails', async () => {
    mockListVersions.mockResolvedValue({ currentVersion: 1, pinnedVersion: null, versions: [] });
    mockPinVersion.mockRejectedValue(new Error('boom'));

    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={null} />);

    fireEvent.click(screen.getByRole('switch', { name: 'Activate' }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('boom');
    });
    // Stays Inactive (unchecked) after failed activation.
    const sw = screen.getByRole('switch', { name: 'Activate' });
    expect(sw).toHaveAttribute('aria-checked', 'false');
  });

  it('shows error when no version is available to pin', async () => {
    // listVersions returns empty - no version to pin.
    mockListVersions.mockResolvedValue({ pinnedVersion: null, versions: [] });

    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={null} />);

    fireEvent.click(screen.getByRole('switch', { name: 'Activate' }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('No version');
    });
    expect(mockPinVersion).not.toHaveBeenCalled();
  });

  it('disables switch while pin/unpin call is in flight (no layout shift - switch stays mounted)', async () => {
    let resolve: ((value: unknown) => void) | null = null;
    mockPinVersion.mockReturnValue(new Promise((r) => { resolve = r; }));

    render(<ApplicationActivationButton workflowId={WORKFLOW_ID} initialPinnedVersion={3} />);

    const sw = screen.getByRole('switch', { name: 'Deactivate' });
    fireEvent.click(sw);

    // Switch is disabled during in-flight call (still mounted, just blocked).
    await waitFor(() => {
      expect(sw).toBeDisabled();
    });

    // Resolve the call so we don't leak.
    resolve?.({ success: true, pinnedVersion: null, productionRunIdPublic: null });
    await waitFor(() => {
      const swAfter = screen.getByRole('switch', { name: 'Activate' });
      expect(swAfter).not.toBeDisabled();
    });
  });
});
