-- V295 - Re-seed the native (non-catalog-API) credential templates that get
-- wiped by `TRUNCATE catalog.credentials CASCADE` during periodic API
-- re-imports. The pattern was first set by V17 (SMTP) and re-applied by V89
-- after a previous wipe; V89 used ON CONFLICT DO NOTHING which means once
-- Flyway has it on the journal, a subsequent TRUNCATE leaves the table empty
-- with no idempotent path back. This migration uses the same INSERT shape but
-- gates each row by a NOT EXISTS guard.
--
-- DURABILITY CAVEAT: Flyway runs this exactly once. It does NOT survive a future
-- `TRUNCATE catalog.credentials` on the next API re-import - after that these
-- rows are gone again until someone copies this file to a fresh version (the
-- NOT EXISTS guard only makes such a hand re-seed safe to re-run). The durable
-- fix is to seed these native templates from the catalog importer's
-- post-truncate path (ApiMigrationImporter) so every re-import re-creates them;
-- tracked as a follow-up.
--
-- 2026-05-29: also seeds the LLM-provider native credentials (llm_openai,
-- llm_anthropic, llm_google, llm_deepseek, llm_mistral, llm_groq) consumed
-- by the Settings → AI Providers page
-- (frontend/app/[locale]/app/settings/ai-providers/page.tsx) where users
-- save their personal API keys per provider. The Settings page's
-- "Configure Credential" wizard resolves the template by exact name
-- (llm_<provider>); without these seeds it surfaces "Service configuration
-- not found" when the user clicks Configure on a freshly-imported catalog.
-- The native API key flow uses a single "api_key" property plus an
-- optional `mode` field (proxy / no_proxy). The runtime resolver
-- (LlmCredentialRepository) reads `credentialData.api_key` for the
-- user-first → platform credential chain; mode=proxy short-circuits the
-- user step so the platform key is used instead.

SET search_path TO catalog;

-- ─── SMTP (mirrors V17 / V89, idempotent re-seed) ───────────────────
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
SELECT 'a1b2c3d4-e5f6-7890-abcd-000000000001'::uuid,
       'smtp',
       'SMTP Email',
       'SMTP server credentials for sending emails from workflow nodes. Supports any SMTP provider (Brevo, Gmail, SendGrid, Mailgun, etc.)',
       'smtp',
       'custom',
       NULL,
       NULL,
       '/icons/services/smtp.svg',
       'smtp',
       '[
         {"name": "host", "displayName": "SMTP Host", "type": "string", "required": true, "placeholder": "smtp-relay.brevo.com", "description": "SMTP server hostname"},
         {"name": "port", "displayName": "Port", "type": "string", "required": true, "default": "587", "placeholder": "587", "description": "SMTP port (587 for STARTTLS, 465 for SSL)"},
         {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "user@smtp-provider.com", "description": "SMTP login / username"},
         {"name": "password", "displayName": "Password / API Key", "type": "string", "required": true, "placeholder": "Your SMTP password or API key", "typeOptions": {"password": true}, "description": "SMTP password or API key (encrypted at rest)"},
         {"name": "from_email", "displayName": "From Email", "type": "string", "required": true, "placeholder": "noreply@yourdomain.com", "description": "Sender email address (must be verified with your SMTP provider)"},
         {"name": "from_name", "displayName": "From Name", "type": "string", "required": false, "placeholder": "My App", "description": "Sender display name"},
         {"name": "use_tls", "displayName": "Use TLS", "type": "options", "required": false, "default": "true", "options": [{"name": "Yes", "value": "true"}, {"name": "No", "value": "false"}], "description": "Enable TLS/STARTTLS encryption"}
       ]'::jsonb,
       '{}'::jsonb,
       '{"provider": "smtp", "category": "email"}'::jsonb,
       EXTRACT(EPOCH FROM NOW()) * 1000,
       EXTRACT(EPOCH FROM NOW()) * 1000
WHERE NOT EXISTS (SELECT 1 FROM credentials WHERE credential_name = 'smtp');

-- ─── LLM provider native credentials (browser_agent + AbstractLLMProvider) ───
-- Same shape for every provider - provider/model identity comes from the
-- node block; this template carries the api_key plus the proxy/no_proxy mode
-- toggle (no_proxy default = use the raw key, proxy = opt into platform-
-- managed routing per the 2026-05-28 mode-aware resolver).

-- OpenAI
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
SELECT 'b2c3d4e5-f6a7-8901-bcde-000000000002'::uuid, 'llm_openai', 'OpenAI API key',
       'OpenAI API key for browser-agent steering and workflow LLM nodes (classify, guardrail, workflow-agent). Used directly against api.openai.com when mode=no_proxy; falls through to the platform key when mode=proxy.',
       'llm', 'api_key', NULL, 'https://platform.openai.com/api-keys',
       '/icons/services/openai.svg', 'llm_openai',
       '[
         {"name": "api_key", "displayName": "API Key", "type": "string", "required": true, "placeholder": "sk-…", "typeOptions": {"password": true}, "description": "Your OpenAI API key (encrypted at rest). Get one at https://platform.openai.com/api-keys."},
         {"name": "mode", "displayName": "Mode", "type": "options", "required": false, "default": "no_proxy", "options": [{"name": "Direct (use my key)", "value": "no_proxy"}, {"name": "Platform-managed", "value": "proxy"}], "description": "no_proxy uses your key directly. proxy opts into platform-managed routing (billed in credits)."}
       ]'::jsonb,
       '{}'::jsonb,
       '{"provider": "openai", "category": "llm"}'::jsonb,
       EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000
WHERE NOT EXISTS (SELECT 1 FROM credentials WHERE credential_name = 'llm_openai');

-- Anthropic
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
SELECT 'b2c3d4e5-f6a7-8901-bcde-000000000003'::uuid, 'llm_anthropic', 'Anthropic API key',
       'Anthropic API key for browser-agent steering and workflow LLM nodes.',
       'llm', 'api_key', NULL, 'https://console.anthropic.com/settings/keys',
       '/icons/services/anthropic.svg', 'llm_anthropic',
       '[
         {"name": "api_key", "displayName": "API Key", "type": "string", "required": true, "placeholder": "sk-ant-…", "typeOptions": {"password": true}, "description": "Your Anthropic API key (encrypted at rest)."},
         {"name": "mode", "displayName": "Mode", "type": "options", "required": false, "default": "no_proxy", "options": [{"name": "Direct (use my key)", "value": "no_proxy"}, {"name": "Platform-managed", "value": "proxy"}], "description": "no_proxy uses your key directly. proxy opts into platform-managed routing (billed in credits)."}
       ]'::jsonb,
       '{}'::jsonb,
       '{"provider": "anthropic", "category": "llm"}'::jsonb,
       EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000
WHERE NOT EXISTS (SELECT 1 FROM credentials WHERE credential_name = 'llm_anthropic');

-- Google (Gemini)
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
SELECT 'b2c3d4e5-f6a7-8901-bcde-000000000004'::uuid, 'llm_google', 'Google AI Studio API key',
       'Google AI Studio (Gemini) API key for browser-agent steering and workflow LLM nodes.',
       'llm', 'api_key', NULL, 'https://aistudio.google.com/apikey',
       '/icons/services/google.svg', 'llm_google',
       '[
         {"name": "api_key", "displayName": "API Key", "type": "string", "required": true, "placeholder": "AIza…", "typeOptions": {"password": true}, "description": "Your Google AI Studio API key (encrypted at rest)."},
         {"name": "mode", "displayName": "Mode", "type": "options", "required": false, "default": "no_proxy", "options": [{"name": "Direct (use my key)", "value": "no_proxy"}, {"name": "Platform-managed", "value": "proxy"}], "description": "no_proxy uses your key directly. proxy opts into platform-managed routing (billed in credits)."}
       ]'::jsonb,
       '{}'::jsonb,
       '{"provider": "google", "category": "llm"}'::jsonb,
       EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000
WHERE NOT EXISTS (SELECT 1 FROM credentials WHERE credential_name = 'llm_google');

-- DeepSeek
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
SELECT 'b2c3d4e5-f6a7-8901-bcde-000000000005'::uuid, 'llm_deepseek', 'DeepSeek API key',
       'DeepSeek API key for browser-agent steering and workflow LLM nodes.',
       'llm', 'api_key', NULL, 'https://platform.deepseek.com/api_keys',
       '/icons/services/deepseek.svg', 'llm_deepseek',
       '[
         {"name": "api_key", "displayName": "API Key", "type": "string", "required": true, "placeholder": "sk-…", "typeOptions": {"password": true}, "description": "Your DeepSeek API key (encrypted at rest)."},
         {"name": "mode", "displayName": "Mode", "type": "options", "required": false, "default": "no_proxy", "options": [{"name": "Direct (use my key)", "value": "no_proxy"}, {"name": "Platform-managed", "value": "proxy"}], "description": "no_proxy uses your key directly. proxy opts into platform-managed routing (billed in credits)."}
       ]'::jsonb,
       '{}'::jsonb,
       '{"provider": "deepseek", "category": "llm"}'::jsonb,
       EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000
WHERE NOT EXISTS (SELECT 1 FROM credentials WHERE credential_name = 'llm_deepseek');

-- Mistral
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
SELECT 'b2c3d4e5-f6a7-8901-bcde-000000000006'::uuid, 'llm_mistral', 'Mistral API key',
       'Mistral API key for browser-agent steering and workflow LLM nodes.',
       'llm', 'api_key', NULL, 'https://console.mistral.ai/api-keys/',
       '/icons/services/mistral.svg', 'llm_mistral',
       '[
         {"name": "api_key", "displayName": "API Key", "type": "string", "required": true, "placeholder": "…", "typeOptions": {"password": true}, "description": "Your Mistral API key (encrypted at rest)."},
         {"name": "mode", "displayName": "Mode", "type": "options", "required": false, "default": "no_proxy", "options": [{"name": "Direct (use my key)", "value": "no_proxy"}, {"name": "Platform-managed", "value": "proxy"}], "description": "no_proxy uses your key directly. proxy opts into platform-managed routing (billed in credits)."}
       ]'::jsonb,
       '{}'::jsonb,
       '{"provider": "mistral", "category": "llm"}'::jsonb,
       EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000
WHERE NOT EXISTS (SELECT 1 FROM credentials WHERE credential_name = 'llm_mistral');

-- Groq
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
SELECT 'b2c3d4e5-f6a7-8901-bcde-000000000007'::uuid, 'llm_groq', 'Groq API key',
       'Groq API key for browser-agent steering and workflow LLM nodes.',
       'llm', 'api_key', NULL, 'https://console.groq.com/keys',
       '/icons/services/groq.svg', 'llm_groq',
       '[
         {"name": "api_key", "displayName": "API Key", "type": "string", "required": true, "placeholder": "gsk_…", "typeOptions": {"password": true}, "description": "Your Groq API key (encrypted at rest)."},
         {"name": "mode", "displayName": "Mode", "type": "options", "required": false, "default": "no_proxy", "options": [{"name": "Direct (use my key)", "value": "no_proxy"}, {"name": "Platform-managed", "value": "proxy"}], "description": "no_proxy uses your key directly. proxy opts into platform-managed routing (billed in credits)."}
       ]'::jsonb,
       '{}'::jsonb,
       '{"provider": "groq", "category": "llm"}'::jsonb,
       EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000
WHERE NOT EXISTS (SELECT 1 FROM credentials WHERE credential_name = 'llm_groq');

-- Align variant with auth_type. V103 added `variant` with UNIQUE(credential_name,
-- variant) and backfilled `variant = auth_type`; the INSERTs above rely on the
-- column DEFAULT 'primary', which diverges from that convention. Correct the rows
-- just seeded so the wizard's variant grouping matches importer-created rows.
-- Idempotent: only touches rows whose variant still differs.
UPDATE credentials SET variant = auth_type
WHERE credential_name IN ('smtp', 'llm_openai', 'llm_anthropic', 'llm_google', 'llm_deepseek', 'llm_mistral', 'llm_groq')
  AND variant IS DISTINCT FROM auth_type;
