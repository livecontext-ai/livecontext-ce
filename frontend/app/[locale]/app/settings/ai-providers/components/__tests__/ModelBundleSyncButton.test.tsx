// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import React from "react";
import { render, screen, fireEvent, waitFor, cleanup } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

vi.mock("next-intl", () => ({ useTranslations: () => (k: string) => k }));
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
const editionState = vi.hoisted(() => ({ IS_CE: true, IS_CLOUD: false }));
vi.mock("@/lib/edition", () => editionState);
const gate = vi.hoisted(() => ({ ensureCloudLinked: vi.fn(() => true), isCloudLinked: true, syncBlocked: false }));
vi.mock("@/hooks/useCloudSyncGate", () => ({ useCloudSyncGate: () => gate }));
// The component drives useBundleSync off SERVER state, so the mock service
// must expose the full {getSyncStatus, syncNow, syncCancel} trio and syncNow
// must resolve a status row (running flag + last fetch outcome).
const svc = vi.hoisted(() => ({ syncNow: vi.fn(), getSyncStatus: vi.fn(), syncCancel: vi.fn() }));
vi.mock("@/lib/api/model-config.service", () => ({ modelConfigService: svc }));

import { ModelBundleSyncButton } from "../ModelBundleSyncButton";

beforeEach(() => {
  vi.clearAllMocks();
  editionState.IS_CE = true;
  gate.ensureCloudLinked.mockReturnValue(true);
  svc.getSyncStatus.mockResolvedValue({ running: false });
  svc.syncNow.mockResolvedValue({ running: false, lastFetchStatus: "OK" });
  svc.syncCancel.mockResolvedValue({ running: false });
});
afterEach(cleanup);

describe("ModelBundleSyncButton", () => {
  it("syncs the bundle when linked and reports success", async () => {
    render(<ModelBundleSyncButton />);
    fireEvent.click(screen.getByRole("button", { name: "update" }));
    await waitFor(() => expect(svc.syncNow).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("synced")).toBeInTheDocument();
  });

  it("does NOT sync when the gate blocks (unlinked → redirected to Connect)", async () => {
    gate.ensureCloudLinked.mockReturnValue(false);
    render(<ModelBundleSyncButton />);
    fireEvent.click(screen.getByRole("button", { name: "update" }));
    await waitFor(() => expect(gate.ensureCloudLinked).toHaveBeenCalled());
    expect(svc.syncNow).not.toHaveBeenCalled();
  });

  it("surfaces the error message on a failed sync", async () => {
    svc.syncNow.mockRejectedValue(new Error("feed unreachable"));
    render(<ModelBundleSyncButton />);
    fireEvent.click(screen.getByRole("button", { name: "update" }));
    expect(await screen.findByText("feed unreachable")).toBeInTheDocument();
  });

  it("hides the update button on the cloud edition (which builds, not syncs) but keeps the manage link", () => {
    editionState.IS_CE = false;
    render(<ModelBundleSyncButton />);
    expect(screen.queryByRole("button", { name: "update" })).not.toBeInTheDocument();
    expect(screen.getByText("manage")).toBeInTheDocument();
  });
});
