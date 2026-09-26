// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import React from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  loginWithRedirect: vi.fn(),
  discoverSso: vi.fn(),
  track: vi.fn(),
  isCe: false,
  searchParams: new URLSearchParams("org=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee&hint=org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml"),
}));

vi.mock("next/navigation", () => ({
  useParams: () => ({ locale: "en" }),
  useSearchParams: () => mocks.searchParams,
}));

vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => {
    const translations: Record<string, string> = {
      redirecting: "Redirecting to your SSO provider...",
      invalid: "This SSO link is invalid.",
      failed: "Could not start SSO sign-in.",
      notFound: "No SSO is set up for this email domain.",
      lookupFailed: "We could not check SSO right now.",
      ceUnavailable: "SSO sign-in is only available on LiveContext Cloud.",
      emailLabel: "Work email",
      continue: "Continue",
    };
    return translations[key] ?? key;
  },
}));

vi.mock("@/lib/analytics/analytics", () => ({
  track: (...args: unknown[]) => mocks.track(...args),
}));

vi.mock("@/lib/api/organization-api", () => ({
  organizationApi: { discoverSso: mocks.discoverSso },
}));

vi.mock("@/lib/edition", () => ({
  get IS_CE() {
    return mocks.isCe;
  },
}));

vi.mock("@/components/LoadingSpinner", () => ({
  default: () => <div data-testid="spinner" />,
}));

vi.mock("@/lib/providers/smart-providers", () => ({
  useAuth: () => ({
    isLoading: false,
    loginWithRedirect: mocks.loginWithRedirect,
  }),
}));

import SamlSsoStartPage from "../page";

describe("SamlSsoStartPage", () => {
  beforeEach(() => {
    mocks.loginWithRedirect.mockReset();
    mocks.loginWithRedirect.mockResolvedValue(undefined);
    mocks.discoverSso.mockReset();
    mocks.track.mockReset();
    mocks.isCe = false;
    mocks.searchParams = new URLSearchParams("org=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee&hint=org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml");
  });

  afterEach(() => {
    cleanup();
  });

  it("passes the organization IdP alias as a Keycloak idp hint", async () => {
    render(<SamlSsoStartPage />);

    await waitFor(() => {
      expect(mocks.loginWithRedirect).toHaveBeenCalledWith({
        appState: { returnTo: "/en/app/chat?ssoOrg=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" },
        authorizationParams: {
          kc_idp_hint: "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml",
        },
      });
    });
  });

  it("reports a workspace link once, as found, even under StrictMode", async () => {
    render(<React.StrictMode><SamlSsoStartPage /></React.StrictMode>);

    await waitFor(() => expect(mocks.loginWithRedirect).toHaveBeenCalled());
    expect(mocks.track.mock.calls).toEqual([["sso_lookup_submitted", { result: "found", mode: "link" }]]);
  });

  it("reports an invalid workspace link as not_found", async () => {
    mocks.searchParams = new URLSearchParams("org=not-a-uuid&hint=org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml");
    render(<SamlSsoStartPage />);

    await waitFor(() => expect(mocks.track).toHaveBeenCalledWith("sso_lookup_submitted", { result: "not_found", mode: "link" }));
  });

  it("rejects malformed SSO hints before redirecting to Keycloak", async () => {
    mocks.searchParams = new URLSearchParams("org=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee&hint=https://evil.example/saml");

    render(<SamlSsoStartPage />);

    await waitFor(() => {
      expect(screen.getByText("This SSO link is invalid.")).toBeInTheDocument();
    });
    expect(mocks.loginWithRedirect).not.toHaveBeenCalled();
  });

  it("rejects malformed organization ids before redirecting to Keycloak", async () => {
    mocks.searchParams = new URLSearchParams("org=not-a-uuid&hint=org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml");

    render(<SamlSsoStartPage />);

    await waitFor(() => {
      expect(screen.getByText("This SSO link is invalid.")).toBeInTheDocument();
    });
    expect(mocks.loginWithRedirect).not.toHaveBeenCalled();
  });

  it("rejects SSO hints that do not belong to the requested organization", async () => {
    mocks.searchParams = new URLSearchParams("org=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee&hint=org-11111111222233334444555555555555-saml");

    render(<SamlSsoStartPage />);

    await waitFor(() => {
      expect(screen.getByText("This SSO link is invalid.")).toBeInTheDocument();
    });
    expect(mocks.loginWithRedirect).not.toHaveBeenCalled();
  });

  it("normalizes uppercase organization ids before returning from SSO", async () => {
    mocks.searchParams = new URLSearchParams("org=AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE&hint=org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml");

    render(<SamlSsoStartPage />);

    await waitFor(() => {
      expect(mocks.loginWithRedirect).toHaveBeenCalledWith({
        appState: { returnTo: "/en/app/chat?ssoOrg=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" },
        authorizationParams: {
          kc_idp_hint: "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml",
        },
      });
    });
  });

  describe("without a workspace link (the Sign in with SSO button)", () => {
    const ORG = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    const HINT = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";

    beforeEach(() => {
      mocks.searchParams = new URLSearchParams("");
    });

    const submit = (email: string) => {
      fireEvent.change(screen.getByLabelText("Work email"), { target: { value: email } });
      fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    };

    it("asks for the work email instead of calling the link invalid, and does not redirect on its own", () => {
      render(<SamlSsoStartPage />);

      expect(screen.getByLabelText("Work email")).toBeInTheDocument();
      expect(screen.queryByText("This SSO link is invalid.")).not.toBeInTheDocument();
      expect(mocks.loginWithRedirect).not.toHaveBeenCalled();
    });

    it("sends a verified domain to its workspace IdP and pre-fills the address there", async () => {
      mocks.discoverSso.mockResolvedValue({ found: true, organizationId: ORG, idpHint: HINT });
      render(<SamlSsoStartPage />);

      submit("  jane@acme.com ");

      await waitFor(() => {
        expect(mocks.loginWithRedirect).toHaveBeenCalledWith({
          appState: { returnTo: `/en/app/chat?ssoOrg=${ORG}` },
          authorizationParams: { kc_idp_hint: HINT, login_hint: "jane@acme.com" },
        });
      });
      expect(mocks.discoverSso).toHaveBeenCalledWith("jane@acme.com");
      expect(mocks.track).toHaveBeenCalledWith("sso_lookup_submitted", { result: "found", mode: "email" });
      // Never the address, nor its domain.
      expect(JSON.stringify(mocks.track.mock.calls)).not.toContain("jane");
      expect(JSON.stringify(mocks.track.mock.calls)).not.toContain("acme");
    });

    it("says there is no SSO for an unknown domain and stays on the page", async () => {
      mocks.discoverSso.mockResolvedValue({ found: false });
      render(<SamlSsoStartPage />);

      submit("user@example.com");

      expect(await screen.findByText("No SSO is set up for this email domain.")).toBeInTheDocument();
      expect(mocks.loginWithRedirect).not.toHaveBeenCalled();
      expect(mocks.track).toHaveBeenCalledWith("sso_lookup_submitted", { result: "not_found", mode: "email" });
      expect(JSON.stringify(mocks.track.mock.calls)).not.toContain("gmail");
    });

    it("refuses a discovery answer whose hint does not belong to its organization", async () => {
      // Same rule as the link mode: the hint must be the org's own alias, never an arbitrary IdP.
      mocks.discoverSso.mockResolvedValue({ found: true, organizationId: ORG, idpHint: "google" });
      render(<SamlSsoStartPage />);

      submit("jane@acme.com");

      expect(await screen.findByText("No SSO is set up for this email domain.")).toBeInTheDocument();
      expect(mocks.loginWithRedirect).not.toHaveBeenCalled();
    });

    it("reports a failed lookup as such, not as 'no SSO'", async () => {
      mocks.discoverSso.mockRejectedValue(new Error("network"));
      render(<SamlSsoStartPage />);

      submit("jane@acme.com");

      expect(await screen.findByText("We could not check SSO right now.")).toBeInTheDocument();
      expect(mocks.track).toHaveBeenCalledWith("sso_lookup_submitted", { result: "lookup_failed", mode: "email" });
      expect(JSON.stringify(mocks.track.mock.calls)).not.toContain("jane");
    });

    it("on a self-hosted install, says SSO is cloud-only and offers no form", () => {
      mocks.isCe = true;
      render(<SamlSsoStartPage />);

      expect(screen.getByText("SSO sign-in is only available on LiveContext Cloud.")).toBeInTheDocument();
      expect(screen.queryByLabelText("Work email")).not.toBeInTheDocument();
      expect(mocks.discoverSso).not.toHaveBeenCalled();
    });
  });
});
