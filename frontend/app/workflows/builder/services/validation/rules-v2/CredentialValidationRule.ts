/**
 * CredentialValidationRule - Validates credential configuration
 *
 * Validations:
 * #16  Required credential not connected (warning)
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType, isNoteNode } from '../core/nodeUtils';
import { nodeRegistry } from '../../../registry/nodeRegistry';
import {
  matchUserCredentialsForTool,
  hasExactIntegrationMatch,
} from '@/lib/credentials/credentialMatching';

const REDACTED = '[redacted]';
/**
 * The publication-service scrubber replaces sensitive cred values with the
 * literal string {@code "[redacted]"} (key-name match in
 * {@code CredentialKeyDetector.looksSensitive}). A truthy {@code "[redacted]"}
 * would otherwise satisfy the {@code if (selectedCredentialId)} guard below
 * and silently mask a missing cred for an acquired application - the buyer
 * sees no warning, then the run fails resolving credential id "[redacted]".
 * Treat the literal as absent everywhere we check truthiness.
 */
const isPresent = (v: unknown): boolean =>
  v != null && v !== '' && v !== REDACTED;

export class CredentialValidationRule extends BaseValidationRule {
  readonly ruleName = 'CredentialValidation' as const;
  readonly isCritical = false;
  readonly priority = 10;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { nodes, userCredentials } = context;

    for (const node of nodes) {
      if (isNoteNode(node)) continue;

      // Core nodes with dedicated credentials (not under toolData.credentials).
      //
      // A node-level credential id is OPTIONAL for these nodes: the inspector
      // auto-picks the user's matching credential on mount (CredentialSection),
      // and the backend node falls back to the user's default credential for
      // the integration at runtime (e.g. EmailInboxNode ->
      // getDefaultCredential(tenantId, "imap")). So only warn when the id is
      // absent AND the user has no credential for that integration - the same
      // suppression `extractMissingCredentialsFromPlan` applies on the
      // application setup gate. Without it, every email_inbox/send_email/...
      // node warned "not connected" for users whose default IMAP/SMTP
      // credential made the workflow run perfectly.
      const coreCredentialCheck = this.resolveCoreCredentialCheck(node);
      if (coreCredentialCheck) {
        const { savedId, integrationSlug, displayName } = coreCredentialCheck;
        if (
          !isPresent(savedId) &&
          !hasExactIntegrationMatch(userCredentials, integrationSlug)
        ) {
          issues.push(this.buildMissingCredentialIssue(node, displayName));
        }
        continue;
      }

      const toolData = (node.data as any)?.toolData;
      if (!toolData?.credentials || !Array.isArray(toolData.credentials)) continue;

      const requiredToolCreds = toolData.credentials.filter(
        (c: any) => c.isRequired !== false
      );

      if (requiredToolCreds.length === 0) continue;
      if (isPresent(toolData.selectedCredentialId)) continue;

      // Platform credential source is an equivalent "satisfied" state - the
      // backend resolves the credential from `platformCredentialId` instead of
      // the user's personal credentials. A half-configured platform source
      // (source='platform' with no id, or with the literal "[redacted]" left
      // by the publication scrubber) still falls through and warns.
      if (
        toolData.credentialSource === 'platform' &&
        isPresent(toolData.platformCredentialId)
      ) {
        continue;
      }

      // No selectedCredentialId on the node - but the inspector auto-picks the
      // first matching user credential when mounted. Skip the warning if such
      // a credential already exists so the popover matches the inspector.
      const integration =
        toolData.integration ||
        toolData.serviceName ||
        toolData.iconSlug ||
        toolData.apiSlug ||
        'service';
      const hasAutoPickable = requiredToolCreds.some((toolCred: any) =>
        matchUserCredentialsForTool(userCredentials, toolCred, integration).length > 0
      );
      if (hasAutoPickable) continue;

      issues.push(this.buildMissingCredentialIssue(node, integration));
    }

    return this.buildResult(issues);
  }

  /**
   * Map a core node with a dedicated credential to its saved id field, its
   * integration slug (MUST match the backend node's default-credential
   * fallback slug, e.g. {@code EmailInboxNode.IMAP_INTEGRATION = "imap"}),
   * and the display name used in the warning message.
   */
  private resolveCoreCredentialCheck(
    node: any
  ): { savedId: unknown; integrationSlug: string; displayName: string } | null {
    const data = node.data as any;
    if (nodeRegistry.isSendEmailNode(node)) {
      return { savedId: data?.smtpCredentialId, integrationSlug: 'smtp', displayName: 'SMTP' };
    }
    if (nodeRegistry.isEmailInboxNode(node)) {
      return { savedId: data?.imapCredentialId, integrationSlug: 'imap', displayName: 'IMAP' };
    }
    if (nodeRegistry.isSshNode(node)) {
      return { savedId: data?.sshCredentialId, integrationSlug: 'ssh', displayName: 'SSH' };
    }
    if (nodeRegistry.isSftpNode(node)) {
      return { savedId: data?.sftpCredentialId, integrationSlug: 'sftp', displayName: 'SFTP' };
    }
    if (nodeRegistry.isDatabaseNode(node)) {
      return { savedId: data?.dbCredentialId, integrationSlug: 'database', displayName: 'Database' };
    }
    return null;
  }

  private buildMissingCredentialIssue(node: any, integration: string): ValidationIssue {
    const nodeType = getNodeType(node);
    const label = node.data?.label;
    const norm = label ? normalizeLabel(label) : null;
    const elementKey = norm ? `${nodeType}:${norm}` : `${nodeType}:${node.id}`;

    return this.createWarning(
      elementKey,
      nodeType,
      `Step "${label || node.id}" requires ${integration} credential (not connected)`,
      {
        rule: 'missing_credential',
        nodeId: node.id,
        integration,
      }
    );
  }
}
