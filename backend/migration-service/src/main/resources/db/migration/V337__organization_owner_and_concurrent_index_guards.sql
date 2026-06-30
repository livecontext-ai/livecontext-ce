-- Add the database-side guard for the organization ownership invariant and
-- verify concurrently-created indexes that must be valid before deploy proceeds.
--
-- flyway:executeInTransaction=false

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_organization_member_one_owner_per_org
    ON auth.organization_member (organization_id)
    WHERE role = 'owner';

DO $$
DECLARE
    invalid_indexes text;
BEGIN
    SELECT string_agg(schema_name || '.' || index_name, ', ' ORDER BY schema_name, index_name)
    INTO invalid_indexes
    FROM (
        VALUES
            ('auth', 'uq_organization_member_one_owner_per_org'),
            ('conversation', 'uq_conversations_primary_agent_per_org'),
            ('conversation', 'uq_conversations_primary_agent_per_user_workspace')
    ) AS expected(schema_name, index_name)
    WHERE NOT EXISTS (
        SELECT 1
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_index i ON i.indexrelid = c.oid
        WHERE n.nspname = expected.schema_name
          AND c.relname = expected.index_name
          AND i.indisvalid = true
    );

    IF invalid_indexes IS NOT NULL THEN
        RAISE EXCEPTION 'Required concurrent index missing or invalid: %', invalid_indexes;
    END IF;
END $$;
