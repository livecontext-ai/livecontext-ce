-- Custom API Registration: tenant-scoped platform credentials + catalog source tracking
-- Design doc: the project docs

-- 1. Tenant-scoped platform credentials (OAuth2 apps per tenant)
ALTER TABLE auth.platform_credentials
  ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255) DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_platform_cred_tenant
  ON auth.platform_credentials(tenant_id);

-- Unique per integration per tenant (NULL tenant = platform-wide)
-- COALESCE handles NULL uniqueness (PostgreSQL treats NULLs as distinct in unique indexes)
CREATE UNIQUE INDEX IF NOT EXISTS idx_platform_cred_integration_tenant
  ON auth.platform_credentials(integration_name, COALESCE(tenant_id, '__PLATFORM__'));

-- 2. Custom API icon storage + source tracking
ALTER TABLE catalog.apis
  ADD COLUMN IF NOT EXISTS icon_url VARCHAR(512) DEFAULT NULL;

ALTER TABLE catalog.apis
  ADD COLUMN IF NOT EXISTS source VARCHAR(50) DEFAULT 'import' NOT NULL;

-- 3. Indexes for visibility filtering (tenant isolation on catalog search)
CREATE INDEX IF NOT EXISTS idx_apis_visibility_created_by
  ON catalog.apis(visibility, created_by);

CREATE INDEX IF NOT EXISTS idx_apis_source
  ON catalog.apis(source);
