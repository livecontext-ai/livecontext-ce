-- Which workspace SAML identity provider created an account.
--
-- A workspace SAML IdP (Keycloak alias org-<uuid>-saml) is configured by a workspace
-- admin, and it asserts whatever identity it likes. Keycloak can link such an IdP to an
-- account that already exists (a password, Google or GitHub account with the same email),
-- after which the admin's IdP signs in AS that person. The app now refuses it: a token
-- brokered by org-<uuid>-saml resolves only to the account that this same IdP created.
-- This column is the record of that creation, written once when the account is created
-- from a SAML login and never changed. NULL for every account created any other way.
--
-- DEPLOYING THIS TO CLOUD NEEDS -f migration_enabled=true: the JPA entity carries the
-- field and auth-service runs ddl-auto: validate.

ALTER TABLE auth.users
    ADD COLUMN IF NOT EXISTS saml_idp_alias VARCHAR(120);

COMMENT ON COLUMN auth.users.saml_idp_alias IS
    'Keycloak alias (org-<uuid>-saml) of the workspace SAML IdP that created this account. '
    'Written once at creation. A token brokered by a workspace IdP only resolves to the account '
    'whose value equals that IdP alias. NULL for accounts created any other way.';

-- Backfill accounts a SAML login created before this column existed: they carry
-- auth_provider = 'SAML', and their first workspace join through that IdP was audited
-- with its alias. Earliest join wins. Accounts with no such event keep NULL and are
-- refused on their next SAML login, which is the safe reading (prod held none when this
-- shipped: no workspace had configured SAML yet).
UPDATE auth.users u
   SET saml_idp_alias = first_join.idp_alias
  FROM (
        SELECT DISTINCT ON (e.actor_user_id)
               e.actor_user_id,
               e.event_data ->> 'idpAlias' AS idp_alias
          FROM auth.organization_audit_event e
         WHERE e.event_type = 'ORG_SAML_SSO_MEMBER_JOINED'
           AND e.actor_user_id IS NOT NULL
           AND e.event_data ->> 'idpAlias' IS NOT NULL
         ORDER BY e.actor_user_id, e.created_at ASC
       ) first_join
 WHERE u.id = first_join.actor_user_id
   AND u.auth_provider = 'SAML'
   AND u.saml_idp_alias IS NULL;
