// @vitest-environment jsdom
import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";

// Catalog-bundle sync requires an active cloud link; the gate redirects unlinked
// CE installs to the Cloud account page (Connect to Cloud) and is a no-op elsewhere.
const editionState = vi.hoisted(() => ({ IS_CE: true, IS_CLOUD: false }));
vi.mock("@/lib/edition", () => editionState);

const linkState = vi.hoisted(() => ({ isCloudLinked: false, isLoading: false }));
vi.mock("@/hooks/useCeCloudLinkStatus", () => ({
  useCeCloudLinkStatus: () => ({ ...linkState, status: null }),
}));

const push = vi.hoisted(() => vi.fn());
vi.mock("@/i18n/navigation", () => ({ useRouter: () => ({ push }) }));

import { useCloudSyncGate } from "../useCloudSyncGate";

beforeEach(() => {
  vi.clearAllMocks();
  editionState.IS_CE = true;
  linkState.isCloudLinked = false;
  linkState.isLoading = false;
});

describe("useCloudSyncGate", () => {
  it("blocks + redirects to Connect to Cloud when CE and not linked", () => {
    const { result } = renderHook(() => useCloudSyncGate());
    expect(result.current.ensureCloudLinked()).toBe(false);
    expect(push).toHaveBeenCalledWith("/app/settings/cloud-account");
    expect(result.current.syncBlocked).toBe(true);
  });

  it("allows the sync when CE and linked", () => {
    linkState.isCloudLinked = true;
    const { result } = renderHook(() => useCloudSyncGate());
    expect(result.current.ensureCloudLinked()).toBe(true);
    expect(push).not.toHaveBeenCalled();
    expect(result.current.syncBlocked).toBe(false);
  });

  it("is a no-op on the cloud edition (bundles are built locally, not synced)", () => {
    editionState.IS_CE = false;
    linkState.isCloudLinked = false;
    const { result } = renderHook(() => useCloudSyncGate());
    expect(result.current.ensureCloudLinked()).toBe(true);
    expect(push).not.toHaveBeenCalled();
    expect(result.current.syncBlocked).toBe(false);
  });

  it("does not flag blocked while the link status is still loading", () => {
    linkState.isLoading = true;
    const { result } = renderHook(() => useCloudSyncGate());
    expect(result.current.syncBlocked).toBe(false);
  });
});
