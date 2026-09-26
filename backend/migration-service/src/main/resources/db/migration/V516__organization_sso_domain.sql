-- Email domains a workspace has proven it owns, for SAML SSO.
--
-- Two things read a VERIFIED row: the public "Sign in with SSO" discovery (an email's
-- domain routes to the workspace's identity provider) and the SAML login admission
-- (a SAML login only joins the workspace when the asserted email is on one of its verified
-- domains). Verification is a DNS TXT record the admin publishes, so a workspace can never
-- claim, route or admit a domain it does not control (gmail.com, a competitor's).
--
-- A domain may be PENDING in several workspaces at once (typing a domain proves nothing),
-- but VERIFIED in at most one: the partial unique index is what makes discovery
-- unambiguous.

CREATE TABLE auth.organization_sso_domain (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,
    domain              VARCHAR(253) NOT NULL,
    verification_token  VARCHAR(64) NOT NULL,
    verified_at         TIMESTAMPTZ NULL,
    last_checked_at     TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_org_sso_domain_org
        FOREIGN KEY (organization_id)
        REFERENCES auth.organization(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_org_sso_domain_org_domain UNIQUE (organization_id, domain),

    CONSTRAINT chk_org_sso_domain_lowercase CHECK (domain = LOWER(domain))
);

CREATE UNIQUE INDEX uq_org_sso_domain_verified
    ON auth.organization_sso_domain(domain)
    WHERE verified_at IS NOT NULL;

COMMENT ON TABLE auth.organization_sso_domain IS
    'Email domains a workspace owns (DNS TXT verified), used for SSO discovery and SAML login admission.';
COMMENT ON COLUMN auth.organization_sso_domain.verification_token IS
    'Value the admin publishes as TXT "livecontext-sso-verification=<token>" on _livecontext-sso.<domain>.';
