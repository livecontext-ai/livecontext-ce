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
import { matchUserCredentialsForTool } from '@/lib/credentials/credentialMatching';

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
      if (nodeRegistry.isSendEmailNode(node)) {
        const data = node.data as any;
        if (!isPresent(data?.smtpCredentialId)) {
          issues.push(this.buildMissingCredentialIssue(node, 'SMTP'));
        }
        continue;
      }
      if (nodeRegistry.isEmailInboxNode(node)) {
        const data = node.data as any;
        if (!isPresent(data?.imapCredentialId)) {
          issues.push(this.buildMissingCredentialIssue(node, 'IMAP'));
        }
        continue;
      }
      if (nodeRegistry.isSshNode(node)) {
        const data = node.data as any;
        if (!isPresent(data?.sshCredentialId)) {
          issues.push(this.buildMissingCredentialIssue(node, 'SSH'));
        }
        continue;
      }
      if (nodeRegistry.isSftpNode(node)) {
        const data = node.data as any;
        if (!isPresent(data?.sftpCredentialId)) {
          issues.push(this.buildMissingCredentialIssue(node, 'SFTP'));
        }
        continue;
      }
      if (nodeRegistry.isDatabaseNode(node)) {
        const data = node.data as any;
        if (!isPresent(data?.dbCredentialId)) {
          issues.push(this.buildMissingCredentialIssue(node, 'Database'));
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
