/**
 * Tests for `extractMissingCredentialsFromPlan` - single source of truth for
 * "what credentials does this plan need that the user hasn't connected yet".
 *
 * Covers the two regression classes flagged by audit on the original plan:
 *  1. The publication-service scrubber replaces sensitive values with the
 *     literal string `"[redacted]"` (not just stripped). A truthy
 *     `"[redacted]"` would otherwise satisfy `if (selectedCredentialId)`
 *     guards and silently mask a missing cred.
 *  2. Production toolData has `apiSlug` / `iconSlug`, NOT `integration` or
 *     `serviceName`. The extractor reads from those real fields.
 */
import { describe, it, expect } from 'vitest';
import {
  extractMissingCredentialsFromPlan,
  type ToolDataLike,
  type WorkflowPlanLike,
} from '../missingCredentials';
import type { Credential } from '@/lib/api/orchestrator';

function userCred(integration: string, name = `${integration}-default`): Credential {
  return {
    id: Math.floor(Math.random() * 100000),
    tenant_id: 'tenant-1',
    name,
    integration,
    type: 'OAuth2',
    environment: 'Production',
    status: 'active',
    credential_data: {},
    scopes: [],
    tags: [],
    is_default: false,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
  } as Credential;
}

function gmailToolData(): ToolDataLike {
  return {
    iconSlug: 'gmail',
    apiSlug: 'gmail',
    apiName: 'Gmail',
    toolId: '42',
    credentials: [{ credentialName: 'oauth2', isRequired: true }],
  };
}

describe('extractMissingCredentialsFromPlan', () => {
  describe('MCP / agent tool steps', () => {
    it('Regression - flags missing Gmail when user has no matching cred', () => {
      const plan: WorkflowPlanLike = {
        mcps: [{ id: 'gmail/send_email', label: 'Send Email', params: {} }],
      };
      const tools = new Map<string, ToolDataLike>([
        ['gmail/send_email', gmailToolData()],
      ]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(1);
      expect(result.wizardable[0]).toMatchObject({
        iconSlug: 'gmail',
        serviceName: 'Gmail',
        toolId: '42',
        sourceNodeIds: ['gmail/send_email'],
      });
    });

    it('Skips when user has a matching cred (strict iconSlug match)', () => {
      const plan: WorkflowPlanLike = {
        mcps: [{ id: 'gmail/send_email', label: 'Send', params: {} }],
      };
      const tools = new Map([['gmail/send_email', gmailToolData()]]);
      const result = extractMissingCredentialsFromPlan(plan, tools, [userCred('gmail')]);
      expect(result.wizardable).toHaveLength(0);
    });

    it('Strict match - generic "google" cred does NOT mask "googlecloud" tool', () => {
      const plan: WorkflowPlanLike = {
        mcps: [{ id: 'googlecloud/list_buckets', label: 'List buckets', params: {} }],
      };
      const tools = new Map<string, ToolDataLike>([
        [
          'googlecloud/list_buckets',
          { iconSlug: 'googlecloud', apiSlug: 'googlecloud', apiName: 'Google Cloud', credentials: [{ isRequired: true }] },
        ],
      ]);
      const result = extractMissingCredentialsFromPlan(plan, tools, [userCred('google')]);
      expect(result.wizardable).toHaveLength(1);
      expect(result.wizardable[0].iconSlug).toBe('googlecloud');
    });

    it('Dedups by iconSlug across multiple steps + tracks all source nodeIds', () => {
      const plan: WorkflowPlanLike = {
        mcps: [
          { id: 'gmail/send_email', label: 'Send 1', params: {} },
          { id: 'gmail/list_messages', label: 'List', params: {} },
          { id: 'gmail/send_email', label: 'Send 2', params: {} }, // same id twice
        ],
      };
      const tools = new Map<string, ToolDataLike>([
        ['gmail/send_email', gmailToolData()],
        ['gmail/list_messages', { ...gmailToolData(), toolId: '43' }],
      ]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(1);
      expect(result.wizardable[0].iconSlug).toBe('gmail');
      expect(result.wizardable[0].sourceNodeIds).toEqual(
        expect.arrayContaining(['gmail/send_email', 'gmail/list_messages'])
      );
    });

    it('Regression - `[redacted]` selectedCredentialId is treated as missing', () => {
      // The publication-service scrubber replaces sensitive values with the
      // literal "[redacted]". A truthy "[redacted]" would otherwise satisfy
      // `if (selectedCredentialId)` and silently mask the missing cred.
      const plan: WorkflowPlanLike = {
        mcps: [
          {
            id: 'gmail/send_email',
            label: 'Send',
            params: { selectedCredentialId: '[redacted]' },
          },
        ],
      };
      const tools = new Map([['gmail/send_email', gmailToolData()]]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(1);
    });

    it('Skips when params.selectedCredentialId points to a real cred', () => {
      const plan: WorkflowPlanLike = {
        mcps: [
          {
            id: 'gmail/send_email',
            label: 'Send',
            params: { selectedCredentialId: 1234 },
          },
        ],
      };
      const tools = new Map([['gmail/send_email', gmailToolData()]]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(0);
    });

    it('Skips when credentialSource=platform with a real platformCredentialId', () => {
      const plan: WorkflowPlanLike = {
        mcps: [
          {
            id: 'gmail/send_email',
            label: 'Send',
            params: { credentialSource: 'platform', platformCredentialId: 99 },
          },
        ],
      };
      const tools = new Map([['gmail/send_email', gmailToolData()]]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(0);
    });

    it('Regression - `[redacted]` platformCredentialId is treated as missing', () => {
      const plan: WorkflowPlanLike = {
        mcps: [
          {
            id: 'gmail/send_email',
            label: 'Send',
            params: { credentialSource: 'platform', platformCredentialId: '[redacted]' },
          },
        ],
      };
      const tools = new Map([['gmail/send_email', gmailToolData()]]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(1);
    });

    it('Skips MCP steps when toolDataMap omits them (graceful when batch fetch fails)', () => {
      const plan: WorkflowPlanLike = {
        mcps: [{ id: 'unknown/tool', label: 'Unknown', params: {} }],
      };
      const result = extractMissingCredentialsFromPlan(plan, new Map(), []);
      expect(result.wizardable).toHaveLength(0);
      expect(result.manual).toHaveLength(0);
    });

    it('Skips tools whose credentials are all isRequired:false', () => {
      const plan: WorkflowPlanLike = {
        mcps: [{ id: 'public/api', label: 'Public', params: {} }],
      };
      const tools = new Map<string, ToolDataLike>([
        [
          'public/api',
          {
            iconSlug: 'public',
            apiName: 'Public',
            credentials: [{ isRequired: false }],
          },
        ],
      ]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(0);
    });

    it('Combined - `[redacted]` selectedCredentialId WITH a matching user cred falls through to auto-pick (no flag)', () => {
      // Documented behaviour: when the sentinel signals "stale" but the
      // user already owns a credential for the same iconSlug, the inspector
      // auto-picks on open. We don't double-prompt - the user can connect
      // a fresh cred via Settings if they want one.
      const plan: WorkflowPlanLike = {
        mcps: [
          {
            id: 'gmail/send_email',
            label: 'Send',
            params: { selectedCredentialId: '[redacted]' },
          },
        ],
      };
      const tools = new Map([['gmail/send_email', gmailToolData()]]);
      const result = extractMissingCredentialsFromPlan(plan, tools, [userCred('gmail')]);
      expect(result.wizardable).toHaveLength(0);
    });

    it('Regression - hyphenated apiSlug ("google-gemini") surfaces as canonical iconSlug ("googlegemini")', () => {
      // Real bug from the multi-credential wizard. Tool data without an
      // explicit iconSlug falls back to apiSlug, which the catalog stores in
      // hyphenated form ("google-gemini"). The credential template, however,
      // is registered under the canonical separator-free slug
      // ("googlegemini"). Pre-fix the wizard's lookup chain failed on every
      // strategy (exact name miss, ILIKE substring miss because "googlegemini"
      // does not contain "google-gemini") → "Service configuration not found".
      // Post-fix the extractor collapses to the canonical form before push,
      // and a user cred stored as "googlegemini" satisfies the strict match.
      const plan: WorkflowPlanLike = {
        mcps: [{ id: 'google-gemini/generate_content', label: 'Gen', params: {} }],
      };
      const tools = new Map<string, ToolDataLike>([
        [
          'google-gemini/generate_content',
          {
            // No iconSlug → triggers the apiSlug fallback path.
            apiSlug: 'google-gemini',
            apiName: 'Google Gemini',
            credentials: [{ isRequired: true }],
          },
        ],
      ]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(1);
      expect(result.wizardable[0].iconSlug).toBe('googlegemini');

      // And - same tool, this time the user already owns a "googlegemini"
      // cred. The strict match must still suppress the prompt even though
      // the tool data carried the hyphenated form.
      const skip = extractMissingCredentialsFromPlan(plan, tools, [userCred('googlegemini')]);
      expect(skip.wizardable).toHaveLength(0);
    });

    it('Agents path - agent.tools[] reference surfaces missing cred', () => {
      const plan: WorkflowPlanLike = {
        agents: [
          {
            id: 'agent:assistant',
            type: 'agent',
            label: 'Assistant',
            tools: ['gmail/send_email'],
          },
        ],
      };
      const tools = new Map([['gmail/send_email', gmailToolData()]]);
      const result = extractMissingCredentialsFromPlan(plan, tools, []);
      expect(result.wizardable).toHaveLength(1);
      expect(result.wizardable[0].iconSlug).toBe('gmail');
      expect(result.wizardable[0].sourceNodeIds).toContain('gmail/send_email');
    });
  });

  describe('SMTP / SSH / SFTP / Database core nodes', () => {
    it('Flags send_email when sendEmail block is configured but credentialId stripped', () => {
      const plan: WorkflowPlanLike = {
        cores: [
          {
            id: 'core:notify',
            type: 'send_email',
            label: 'Notify',
            sendEmail: { smtpHost: 'smtp.gmail.com', fromEmail: 'a@b.com' },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.wizardable).toHaveLength(1);
      expect(result.wizardable[0].iconSlug).toBe('smtp');
    });

    it('Skips send_email when the parent block is empty (publisher never configured it)', () => {
      const plan: WorkflowPlanLike = {
        cores: [{ id: 'core:notify', type: 'send_email', sendEmail: {} }],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.wizardable).toHaveLength(0);
    });

    it('Skips send_email when smtpUsername+smtpPassword are inline (no credentialId needed)', () => {
      // Some publishers wire SMTP inline (smaller workflows, no credential
      // entity). The inline path is a valid satisfaction signal.
      const plan: WorkflowPlanLike = {
        cores: [
          {
            id: 'core:notify',
            type: 'send_email',
            sendEmail: {
              smtpHost: 'smtp.gmail.com',
              fromEmail: 'a@b.com',
              smtpUsername: 'a@b.com',
              smtpPassword: 'inline-pass',
            },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.wizardable).toHaveLength(0);
    });

    it('Skips send_email when user has an SMTP credential', () => {
      const plan: WorkflowPlanLike = {
        cores: [
          {
            id: 'core:notify',
            type: 'send_email',
            sendEmail: { smtpHost: 'smtp.gmail.com', fromEmail: 'a@b.com' },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, [userCred('smtp')]);
      expect(result.wizardable).toHaveLength(0);
    });

    it('Flags ssh / sftp / database with stripped credentialId', () => {
      const plan: WorkflowPlanLike = {
        cores: [
          { id: 'core:remote', type: 'ssh', ssh: {} },
          { id: 'core:upload', type: 'sftp', sftp: {} },
          { id: 'core:db', type: 'database', database: {} },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      const slugs = result.wizardable.map((w) => w.iconSlug).sort();
      expect(slugs).toEqual(['database', 'sftp', 'ssh']);
    });
  });

  describe('Manual setup (http_request, crypto_jwt, webhook trigger auth)', () => {
    it('Flags http_request with non-none authType and stripped authConfig', () => {
      const plan: WorkflowPlanLike = {
        cores: [
          {
            id: 'core:call_api',
            type: 'http_request',
            label: 'Call API',
            httpRequest: { method: 'GET', url: 'https://x', authType: 'bearer' },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.manual).toHaveLength(1);
      expect(result.manual[0]).toMatchObject({
        nodeId: 'core:call_api',
        kind: 'http_request',
      });
    });

    it('Skips http_request with authType=none', () => {
      const plan: WorkflowPlanLike = {
        cores: [
          {
            id: 'core:call_api',
            type: 'http_request',
            httpRequest: { method: 'GET', url: 'https://x', authType: 'none' },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.manual).toHaveLength(0);
    });

    it('Skips http_request with authConfig still populated', () => {
      const plan: WorkflowPlanLike = {
        cores: [
          {
            id: 'core:call_api',
            type: 'http_request',
            httpRequest: {
              method: 'GET',
              url: 'https://x',
              authType: 'bearer',
              authConfig: { bearerToken: 'still-here' },
            },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.manual).toHaveLength(0);
    });

    it('Flags crypto_jwt sign without key/secret', () => {
      const plan: WorkflowPlanLike = {
        cores: [
          {
            id: 'core:sign',
            type: 'crypto_jwt',
            cryptoJwt: { operation: 'sign', algorithm: 'HS256' },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.manual).toHaveLength(1);
      expect(result.manual[0].kind).toBe('crypto_jwt');
    });

    it('Flags webhook trigger with basic auth and missing password', () => {
      const plan: WorkflowPlanLike = {
        triggers: [
          {
            id: 'trigger:hook',
            type: 'webhook',
            label: 'Receive',
            params: { authType: 'basic', basicUsername: 'still-here' /* basicPassword stripped */ },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.manual).toHaveLength(1);
      expect(result.manual[0].kind).toBe('trigger:webhook');
    });

    it('Regression - webhook with authType=header flags when authHeaderValue is stripped', () => {
      // Pre-fix the helper used a non-existent 'header-secret' authType key
      // and looked at `headerName`/`headerSecret` fields that don't exist in
      // the real plan schema (real fields are `authHeaderName`/`authHeaderValue`,
      // see TriggerNodeCreator.ts:69-71). A real header-auth webhook would
      // therefore never get flagged.
      const plan: WorkflowPlanLike = {
        triggers: [
          {
            id: 'trigger:hook',
            type: 'webhook',
            label: 'Receive',
            params: { authType: 'header', authHeaderName: 'X-API-Key' /* authHeaderValue stripped */ },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.manual).toHaveLength(1);
      expect(result.manual[0].kind).toBe('trigger:webhook');
      expect(result.manual[0].reason).toContain('header');
    });

    it('Webhook with authType=jwt flags when jwtSecretKey is stripped', () => {
      const plan: WorkflowPlanLike = {
        triggers: [
          {
            id: 'trigger:hook',
            type: 'webhook',
            params: { authType: 'jwt' /* jwtSecretKey stripped */ },
          },
        ],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.manual).toHaveLength(1);
    });

    it('Skips webhook trigger with authType=none', () => {
      const plan: WorkflowPlanLike = {
        triggers: [{ id: 'trigger:hook', type: 'webhook', params: { authType: 'none' } }],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.manual).toHaveLength(0);
    });
  });

  describe('Empty / null safety', () => {
    it('Returns empty result for null plan', () => {
      const result = extractMissingCredentialsFromPlan(null, undefined, []);
      expect(result.wizardable).toHaveLength(0);
      expect(result.manual).toHaveLength(0);
    });

    it('Returns empty result for plan with no relevant nodes', () => {
      const plan: WorkflowPlanLike = {
        cores: [{ id: 'core:wait', type: 'wait', wait: { duration: 5000 } }],
        triggers: [{ id: 'trigger:cron', type: 'schedule', params: {} }],
      };
      const result = extractMissingCredentialsFromPlan(plan, undefined, []);
      expect(result.wizardable).toHaveLength(0);
      expect(result.manual).toHaveLength(0);
    });
  });
});
