-- V166: per-endpoint OAuth scope requirements
--
-- Optional column on catalog.api_tools storing the OAuth scopes a user's credential
-- must have granted before LiveContext will dispatch the call. NULL = no requirement
-- (existing behavior for all rows). Populated by the importer when the source JSON
-- declares an endpoint-level "requiredScopes": ["..."] array. Read at runtime by
-- HttpExecutionService.preflightScopeCheck before tryGetCredentialResolution.
--
-- JSONB chosen over TEXT[] to mirror the existing JsonbString pattern used for
-- execution_spec, output_schema, and pagination on the same entity. No new Spring
-- Data JDBC converter required.
--
-- No backfill: NULL is the safe default. In-flight runs and tools whose JSON has
-- not been re-imported behave exactly as today (preflight short-circuits on null).

ALTER TABLE catalog.api_tools
    ADD COLUMN required_scopes JSONB;

COMMENT ON COLUMN catalog.api_tools.required_scopes IS
    'Optional JSON array of OAuth scope strings the user credential must have granted '
    'before LiveContext dispatches the call. NULL = no requirement.';
