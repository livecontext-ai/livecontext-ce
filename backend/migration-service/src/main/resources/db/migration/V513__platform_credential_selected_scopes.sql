-- BYOK scope selection. A tenant (or a CE install) that brings its own OAuth client picks which
-- catalog scopes (platform scopes + byokOnlyScopes) its connect requests, because providers such
-- as TikTok and Figma refuse the WHOLE authorization over one scope the app was not given.
-- Space-separated, like default_scopes. NULL = no selection = request every catalog scope, which
-- is the behaviour every existing row keeps. Read by auth-service OAuth2Service at connect time,
-- always intersected with the catalog so a scope the catalog drops is never requested.
ALTER TABLE auth.platform_credentials
    ADD COLUMN IF NOT EXISTS selected_scopes TEXT;
