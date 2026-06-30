// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import React from "react";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  loginWithRedirect: vi.fn(),
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
    };
    return translations[key] ?? key;
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
});
