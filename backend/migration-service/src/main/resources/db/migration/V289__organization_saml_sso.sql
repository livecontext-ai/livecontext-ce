-- Organization-scoped SAML SSO configuration.
--
-- The row is owned by auth-service. Runtime login is still handled by
-- Keycloak; auth-service stores the tenant configuration, provisions the
-- matching Keycloak identity provider, and exposes the SP metadata values
-- admins need to configure their IdP.

CREATE TABLE auth.organization_saml_connection (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID NOT NULL UNIQUE,
    idp_alias              VARCHAR(120) NOT NULL UNIQUE,
    display_name           VARCHAR(80) NOT NULL,
    idp_entity_id          VARCHAR(512) NOT NULL,
    sso_url                VARCHAR(2048) NOT NULL,
    x509_certificate       TEXT NOT NULL,
    hide_on_login_page     BOOLEAN NOT NULL DEFAULT TRUE,
    status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    last_synced_at         TIMESTAMPTZ NULL,
    last_error             TEXT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_org_saml_connection_org
        FOREIGN KEY (organization_id)
        REFERENCES auth.organization(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_org_saml_connection_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ERROR', 'DISABLED'))
);

CREATE INDEX idx_org_saml_connection_status
    ON auth.organization_saml_connection(status);

COMMENT ON TABLE auth.organization_saml_connection IS
    'Organization-scoped SAML SSO IdP configuration provisioned into Keycloak.';
COMMENT ON COLUMN auth.organization_saml_connection.idp_alias IS
    'Deterministic Keycloak identity-provider alias used as kc_idp_hint.';
COMMENT ON COLUMN auth.organization_saml_connection.hide_on_login_page IS
    'When true, the tenant IdP is hidden from the global Keycloak login page and used through kc_idp_hint.';
