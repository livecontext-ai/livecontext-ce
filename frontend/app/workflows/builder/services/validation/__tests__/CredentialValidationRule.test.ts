import { describe, it, expect, beforeEach } from 'vitest';
import { CredentialValidationRule } from '../rules-v2/CredentialValidationRule';
import type { Credential } from '@/lib/api/orchestrator';
import {
  makeStepWithCredentials,
  makeStepNode,
  makeTriggerNode,
  makeNoteNode,
  makeSendEmailNode,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

function makeUserCredential(
  overrides: Partial<Credential> & { integration: string }
): Credential {
  return {
    id: overrides.id ?? 1,
    tenant_id: 'tenant-1',
    name: overrides.name ?? `${overrides.integration}-cred`,
    integration: overrides.integration,
    type: overrides.type ?? 'OAuth2',
    environment: overrides.environment ?? 'Production',
    status: overrides.status ?? 'active',
    credential_data: overrides.credential_data ?? {},
    scopes: overrides.scopes ?? [],
    tags: overrides.tags ?? [],
    is_default: overrides.is_default ?? false,
    created_at: overrides.created_at ?? '2026-01-01T00:00:00Z',
    updated_at: overrides.updated_at ?? '2026-01-01T00:00:00Z',
  };
}

describe('CredentialValidationRule', () => {
  let rule: CredentialValidationRule;

  beforeEach(() => {
    rule = new CredentialValidationRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('CredentialValidation');
    expect(rule.isCritical).toBe(false);
    expect(rule.priority).toBe(10);
  });

  // ===================== #16: Missing credential =====================

  describe('#16 - Required credential not connected', () => {
    it('should warn when required credential is not connected', () => {
      const step = makeStepWithCredentials('API Call', {
        credentials: [{ name: 'api_key', isRequired: true }],
        selectedCredentialId: null,
        integration: 'Slack',
      });
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(1);
      expect(credIssues[0].severity).toBe('warning');
      expect(credIssues[0].message).toContain('Slack');
      expect(credIssues[0].message).toContain('credential');
    });

    it('should pass when credential is connected', () => {
      const step = makeStepWithCredentials('API Call', {
        credentials: [{ name: 'api_key', isRequired: true }],
        selectedCredentialId: 42,
        integration: 'Slack',
      });
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(0);
    });

    it('should pass when no credentials are required', () => {
      const step = makeStepNode('Simple Step');
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(0);
    });

    it('should skip note nodes', () => {
      const note = makeNoteNode('Note');
      const ctx = buildContext([note], []);
      const result = rule.validate(ctx);

      expect(result.issues).toHaveLength(0);
    });

    it('should warn on send-email node with no SMTP credential', () => {
      const step = makeSendEmailNode('Send Welcome', { smtpCredentialId: null });
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(1);
      expect(credIssues[0].message).toContain('SMTP');
    });

    it('should pass on send-email node with SMTP credential set', () => {
      const step = makeSendEmailNode('Send Welcome', { smtpCredentialId: 7 });
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(0);
    });

    it('should pass when selectedCredentialId is null but user has a matching credential (inspector would auto-pick)', () => {
      // Regression for the popover vs. inspector divergence: CredentialSection
      // auto-picks on mount, but the validator was still warning while no node
      // had been clicked yet. Validator must see the user's credential set.
      const step = makeStepWithCredentials('Gmail List', {
        credentials: [{ name: 'oauth', isRequired: true }],
        selectedCredentialId: null,
        integration: 'gmail',
      });
      const userCreds = [makeUserCredential({ id: 7, integration: 'gmail', name: 'Personal Gmail' })];
      const ctx = buildContext([step], [], undefined, userCreds);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(0);
    });

    it('should still warn when selectedCredentialId is null and no user credential matches the integration', () => {
      const step = makeStepWithCredentials('Gmail List', {
        credentials: [{ name: 'oauth', isRequired: true }],
        selectedCredentialId: null,
        integration: 'gmail',
      });
      // User has credentials for another integration - should not satisfy.
      const userCreds = [makeUserCredential({ id: 99, integration: 'github', name: 'GitHub token' })];
      const ctx = buildContext([step], [], undefined, userCreds);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(1);
      expect(credIssues[0].message).toContain('gmail');
    });

    it('should pass when credentialSource=platform and platformCredentialId is set, even without user creds', () => {
      // Inspector toggle flipped to "platform" - the backend resolves billing
      // from platformCredentialId, so the validator must not demand a user
      // credential for this integration.
      const step = makeStepWithCredentials('Gmail via platform', {
        credentials: [{ name: 'oauth', isRequired: true }],
        selectedCredentialId: null,
        integration: 'gmail',
        credentialSource: 'platform',
        platformCredentialId: 42,
      });
      const ctx = buildContext([step], [], undefined, []);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(0);
    });

    it('should still warn when credentialSource=platform but platformCredentialId is null (half-configured)', () => {
      const step = makeStepWithCredentials('Gmail via platform', {
        credentials: [{ name: 'oauth', isRequired: true }],
        selectedCredentialId: null,
        integration: 'gmail',
        credentialSource: 'platform',
        platformCredentialId: null,
      });
      const ctx = buildContext([step], [], undefined, []);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(1);
      expect(credIssues[0].message).toContain('gmail');
    });

    it('should validate multiple steps independently', () => {
      const s1 = makeStepWithCredentials('Slack Call', {
        id: 'step-1',
        credentials: [{ name: 'token', isRequired: true }],
        selectedCredentialId: null,
        integration: 'Slack',
      });
      const s2 = makeStepWithCredentials('GitHub Call', {
        id: 'step-2',
        credentials: [{ name: 'token', isRequired: true }],
        selectedCredentialId: 99,
        integration: 'GitHub',
      });
      const ctx = buildContext([s1, s2], []);
      const result = rule.validate(ctx);

      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(1); // only s1
      expect(credIssues[0].message).toContain('Slack');
    });
  });

  // =========== Regression: "[redacted]" literal masks no longer ============
  // The publication-service scrubber replaces sensitive cred values with the
  // literal string "[redacted]". Pre-fix this would falsely satisfy the
  // truthy guards on selectedCredentialId / platformCredentialId / smtp etc.
  // and silently hide a missing credential warning for an acquired application.
  describe('regression: "[redacted]" sentinel from publication scrub', () => {
    it('flags missing when selectedCredentialId is "[redacted]"', () => {
      const step = makeStepWithCredentials('API Call', {
        credentials: [{ name: 'api_key', isRequired: true }],
        selectedCredentialId: '[redacted]' as any,
        integration: 'Slack',
      });
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);
      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(1);
    });

    it('flags missing when platformCredentialId is "[redacted]" with credentialSource=platform', () => {
      const step = makeStepWithCredentials('API Call', {
        credentials: [{ name: 'api_key', isRequired: true }],
        selectedCredentialId: null,
        platformCredentialId: '[redacted]' as any,
        credentialSource: 'platform',
        integration: 'Slack',
      });
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);
      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(1);
    });

    it('flags missing send_email when smtpCredentialId is "[redacted]"', () => {
      const node = makeSendEmailNode('Send Email', { smtpCredentialId: '[redacted]' as any });
      const ctx = buildContext([node], []);
      const result = rule.validate(ctx);
      const credIssues = result.issues.filter((i) => i.context?.rule === 'missing_credential');
      expect(credIssues).toHaveLength(1);
      expect(credIssues[0].message).toContain('SMTP');
    });
  });
});
